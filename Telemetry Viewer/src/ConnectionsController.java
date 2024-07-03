import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Stream;

import javax.swing.SwingUtilities;

/**
 * ConnectionController manages all Connections, but the bulk of the work is done by the individual Connections.
 */
public class ConnectionsController {
	
	public static volatile boolean importing = false;
	public static volatile boolean exporting = false;
	public static volatile boolean realtimeImporting = true; // false = importing files as fast as possible
	public static volatile boolean previouslyImported = false; // true = the Connections contain imported data
	private static Thread exportThread;
	
	public static List<Connection>                        allConnections = new ArrayList<Connection>();
	public static List<ConnectionTelemetry>         telemetryConnections = new ArrayList<ConnectionTelemetry>();
	public static List<ConnectionCamera>               cameraConnections = new ArrayList<ConnectionCamera>();
	public static Map<ConnectionTelemetry, DatasetsInterface> interfaces = new HashMap<ConnectionTelemetry, DatasetsInterface>();
	static {
		addConnection(null);
	}
	
	private static final String filenameSanitizer = "[^a-zA-Z0-9_\\.\\- ]"; // only allow letters, numbers, underscores, periods, hyphens and spaces.
	
	public record Device(String name, boolean isAvailable, Supplier<Connection> connection) {}
	public static Stream<Device> getDevicesStream() {
		
		List<Device> list = new ArrayList<Device>();
		ConnectionTelemetryUART.getNames().forEach(name -> {
			boolean isAvailable = telemetryConnections.stream().noneMatch(con -> con.name.get().equals(name));
			list.add(new Device(name, isAvailable, isAvailable ? () -> new ConnectionTelemetryUART(name) :
			                                                     () -> null));
		});
		list.add(new Device("TCP", true, () -> new ConnectionTelemetryTCP()));
		list.add(new Device("UDP", true, () -> new ConnectionTelemetryUDP()));
		boolean isDemoAvailable = telemetryConnections.stream().noneMatch(con -> con.name.get().equals("Demo Mode"));
		list.add(new Device("Demo Mode", isDemoAvailable, isDemoAvailable ? () -> new ConnectionTelemetryDemo() :
		                                                                    () -> null));
		boolean isStressAvailable = telemetryConnections.stream().noneMatch(con -> con.name.get().equals("Stress Test Mode"));
		list.add(new Device("Stress Test Mode", isStressAvailable, isStressAvailable ? () -> new ConnectionTelemetryStressTest() :
		                                                                               () -> null));
		// don't support webcams on AArch64 Linux (Pi4, etc.) because the webcam library crashes
		if(!(System.getProperty("os.name").toLowerCase().equals("linux") && System.getProperty("os.arch").toLowerCase().equals("aarch64"))) {
			ConnectionCamera.getNames().forEach(name -> {
				boolean isAvailable = cameraConnections.stream().noneMatch(con -> con.name.get().equals(name));
				list.add(new Device(name, isAvailable, isAvailable ? () -> new ConnectionCamera(name) :
				                                                     () -> null));
			});
			list.add(new Device(ConnectionCamera.mjpegOverHttp, true, () -> new ConnectionCamera(ConnectionCamera.mjpegOverHttp)));
		}
		return list.stream();
		
	}
	
	public static void addConnection(Connection newConnection) {
		
		if(newConnection == null)
			newConnection = getDevicesStream().filter(Device::isAvailable)
			                                  .map(device -> device.connection().get())
			                                  .findFirst().orElse(null);
		
		allConnections.add(newConnection);
		if(newConnection instanceof ConnectionTelemetry newConn)
			telemetryConnections.add(newConn);
		else if(newConnection instanceof ConnectionCamera newConn)
			cameraConnections.add(newConn);
		
		interfaces.clear();
		telemetryConnections.forEach(connection -> interfaces.put(connection, new DatasetsInterface(connection)));
		
		// CommunicationView.instance will be null when static { addConnection(null); } from above gets run,
		// because the CommunicationView constructor will still be in progress at that time!
		if(CommunicationView.instance != null)
			CommunicationView.instance.redraw(); // redraw bottom panel so it shows the connection widgets
		SettingsView.instance.redraw();      // redraw the left panel so it shows the TX GUI if appropriate
		
	}
	
	public static void removeConnection(Connection oldConnection) {
		
		oldConnection.dispose();
		
		allConnections.remove(oldConnection);
		if(oldConnection instanceof ConnectionTelemetry oldConn)
			telemetryConnections.remove(oldConn);
		else if(oldConnection instanceof ConnectionCamera oldConn)
			cameraConnections.remove(oldConn);
		
		interfaces.clear();
		telemetryConnections.forEach(connection -> interfaces.put(connection, new DatasetsInterface(connection)));
		
		CommunicationView.instance.redraw(); // redraw bottom panel so it doesn't show the old connection's widgets
		SettingsView.instance.redraw();      // redraw the left panel so it doesn't show the old connection's TX GUI
		
		if(allConnections.isEmpty())
			OpenGLChartsView.instance.setPlayLive(); // ensure we're not paused at a time/sampleNumber that no longer exists
		
	}
	
	public static void replaceConnection(Connection oldConnection, Connection newConnection) {
		
		oldConnection.dispose();
		
		allConnections.set(allConnections.indexOf(oldConnection), newConnection);
		if(oldConnection instanceof ConnectionTelemetry oldConn && newConnection instanceof ConnectionTelemetry newConn) {
			telemetryConnections.set(telemetryConnections.indexOf(oldConn), newConn);
		} else if(oldConnection instanceof ConnectionCamera oldConn && newConnection instanceof ConnectionCamera newConn) {
			cameraConnections.set(cameraConnections.indexOf(oldConn), newConn);
		} else if(oldConnection instanceof ConnectionTelemetry) {
			telemetryConnections.remove(oldConnection);
			cameraConnections.add((ConnectionCamera) newConnection);
		} else if(oldConnection instanceof ConnectionCamera) {
			cameraConnections.remove(oldConnection);
			telemetryConnections.add((ConnectionTelemetry) newConnection);
		}
		
		interfaces.clear();
		telemetryConnections.forEach(connection -> interfaces.put(connection, new DatasetsInterface(connection)));
		
		CommunicationView.instance.redraw(); // redraw bottom panel so it shows the connection widgets
		SettingsView.instance.redraw();      // redraw the left panel so it shows the TX GUI if appropriate
		
	}
	
	public static void removeAllConnections() {
		
		allConnections.stream().toList().forEach(ConnectionsController::removeConnection); // toList() to prevent a ConcurrentModificationException
		
	}
	
	/**
	 * @return    Default number of samples to use for a new chart. This is equivalent to 10 seconds.
	 */
	public static int getDefaultChartDuration() {
		
		if(telemetryConnections.isEmpty()) {
			return 10000;
		} else {
			int sampleCount = Guava.saturatedMultiply(telemetryConnections.get(0).getSampleRate(), 10);
			return Math.min(sampleCount, Integer.MAX_VALUE / 16);
		}
		
	}
	
	public static class SampleDetails {
		public ConnectionTelemetry connection;
		public int sampleNumber;
		public long timestamp;
		public SampleDetails(ConnectionTelemetry connection, int sampleNumber, long timestamp) {
			this.connection = connection;
			this.sampleNumber = sampleNumber;
			this.timestamp = timestamp;
		}
	}
	
	public static SampleDetails getClosestSampleDetailsFor(long timestamp) {
		
		long smallestError = Long.MAX_VALUE;
		
		ConnectionTelemetry closestConnection = null;
		int closestSampleNumber = 0;
		long closestTimestamp = 0;
		
		for(Map.Entry<ConnectionTelemetry, DatasetsInterface> entry : interfaces.entrySet()) {
			
			ConnectionTelemetry connection = entry.getKey();
			DatasetsInterface datasets = entry.getValue();
			
			int trueLastSampleNumber = connection.getSampleCount() - 1;
			int closestSampleNumberBefore = datasets.getClosestSampleNumberAtOrBefore(timestamp, trueLastSampleNumber);
			int closestSampleNumberAfter = closestSampleNumberBefore + 1;
			if(closestSampleNumberAfter > trueLastSampleNumber)
				closestSampleNumberAfter = trueLastSampleNumber;
			
			long beforeError = timestamp - datasets.getTimestamp(closestSampleNumberBefore);
			long afterError  = datasets.getTimestamp(closestSampleNumberAfter) - timestamp;
			long error = Long.min(Math.abs(beforeError), Math.abs(afterError));
			
			if(error < smallestError) {
				closestConnection = connection;
				closestSampleNumber = beforeError < afterError ? closestSampleNumberBefore : closestSampleNumberAfter;
				closestTimestamp = datasets.getTimestamp(closestSampleNumber);
			}
			
		}
		
		return new SampleDetails(closestConnection, closestSampleNumber, closestTimestamp);
		
	}
	
	/**
	 * @return    True if telemetry can be received.
	 */
	public static boolean telemetryPossible() {
		
		for(ConnectionTelemetry connection : telemetryConnections)
			if(connection.isConnected() && connection.isFieldsDefined())
				return true;
		
		for(ConnectionCamera connection : cameraConnections)
			if(connection.isConnected())
				return true;
		
		return false;
		
	}
	
	/**
	 * @return    True if at least one sample or camera image has been acquired.
	 */
	public static boolean telemetryExists() {
		
		return allConnections.stream().anyMatch(connection -> connection.getSampleCount() > 0);
		
	}
	
	/**
	 * @return    Timestamp of the first sample or camera image, or Long.MAX_VALUE if no telemetry has been acquired.
	 */
	public static long getFirstTimestamp() {
		
		return allConnections.stream().filter(connection -> connection.getSampleCount() > 0)
		                              .mapToLong(connection -> connection.getFirstTimestamp())
		                              .min().orElse(Long.MAX_VALUE);
		
	}
	
	/**
	 * @return    Timestamp of the last sample or camera image, or Long.MIN_VALUE if no telemetry has been acquired.
	 */
	public static long getLastTimestamp() {
		
		return allConnections.stream().filter(connection -> connection.getSampleCount() > 0)
		                              .mapToLong(connection -> connection.getLastTimestamp())
		                              .max().orElse(Long.MIN_VALUE);
		
	}
	
	public static WidgetComboboxString getNamesCombobox(Connection connection) {
		
		return new WidgetComboboxString(getDevicesStream().map(Device::name).toList(), null)
		           .setExportLabel("type")
		           .onChange(newName -> {
		               // ignore this event if the connection is still be constructed
		               if(connection.name == null || connection.name.get() == null)
		                   return true;
		               
		               // accept and ignore if no change
		               String oldName = connection.name.get();
		               if(newName.equals(oldName))
		                   return true;
		               
		               // reject change if the device is not available
		               Device dev = getDevicesStream().filter(device -> device.name.equals(newName) &&
		                                                                device.isAvailable)
		                                              .findFirst().orElse(null);
		               if(dev == null)
		                   return false;
		               
		               if(oldName.startsWith("UART") && newName.startsWith("UART")) {
		                   SwingUtilities.invokeLater(() -> SettingsView.instance.redraw()); // so the TX GUI shows the new port name, invokeLater so the name change goes into effect first!
		                   return true; // no need to replace this connection, just changing the port number
		               }
		               
		               if(oldName.startsWith("Cam") && newName.startsWith("Cam")) {
		            	   SwingUtilities.invokeLater(() -> CommunicationView.instance.redraw()); // so the widgets get redrawn when switching between local and MJPEG-over-HTTP modes!
		                   return true; // no need to replace this connection, just changing the settings
		               }
		               
		               replaceConnection(connection, dev.connection.get());
		               return true;
		           });
		
	}
	
	/**
	 * Imports a settings file, log files, and/or camera files.
	 * The user will be notified if there is a problem with any of the files.
	 * 
	 * @param filepaths    A List<String> of file paths.
	 */
	public static void importFiles(List<String> filepaths) {
		
		if(importing || exporting) {
			NotificationsController.showFailureForMilliseconds("Unable to import more files while importing or exporting is in progress.", 5000, true);
			return;
		}
		
		// sanity check
		long settingsFileCount = filepaths.stream().filter(path ->  path.endsWith(".txt")).count();
		long csvFileCount      = filepaths.stream().filter(path ->  path.endsWith(".csv")).count();
		long mkvFileCount      = filepaths.stream().filter(path ->  path.endsWith(".mkv")).count();
		long invalidFileCount  = filepaths.stream().filter(path -> !path.endsWith(".txt") &&
		                                                           !path.endsWith(".csv") &&
		                                                           !path.endsWith(".mkv")).count();
		
		if(invalidFileCount > 0) {
			NotificationsController.showFailureForMilliseconds("Unsupported file type. Only files exported from TelemetryViewer can be imported:\nSettings files (.txt)\nCSV files (.csv)\nCamera files (.mkv)", 5000, true);
			return;
		}
		if(settingsFileCount > 1) {
			NotificationsController.showFailureForMilliseconds("Only one settings file can be opened at a time.", 5000, true);
			return;
		}
		
		// if not importing a settings file, disconnect and remove existing samples/frames
		if(settingsFileCount == 0) {
			for(Connection connection : allConnections) {
				connection.disconnect(null);
				connection.removeAllData();
			}
		}
		
		// import the settings file if requested
		if(settingsFileCount == 1) {
			removeAllConnections();
			for(String filepath : filepaths)
				if(filepath.endsWith(".txt"))
					if(!importSettingsFile(filepath, csvFileCount + mkvFileCount == 0)) {
						removeAllConnections();
						addConnection(null);
						return;
					}
		}
		
		Map<Connection, String> imports = new HashMap<Connection, String>(); // <Connection, corresponding file path>
		
		for(String filepath : filepaths) {
			if(filepath.endsWith(".csv")) {
				for(int connectionN = 0; connectionN < allConnections.size(); connectionN++) {
					Connection connection = allConnections.get(connectionN);
					if(filepath.endsWith(" - connection " + connectionN + " - " + connection.name.get().replaceAll(filenameSanitizer, "") + ".csv"))
						imports.put(connection, filepath);
				}
			} else if(filepath.endsWith(".mkv")) {
				for(int connectionN = 0; connectionN < allConnections.size(); connectionN++) {
					Connection connection = allConnections.get(connectionN);
					if(filepath.endsWith(" - connection " + connectionN + " - " + connection.name.get().replaceAll(filenameSanitizer, "_") + ".mkv"))
						imports.put(connection, filepath);
				}
			}
		}
		
		// allow importing an MKV file by itself
		boolean moviePlayerMode = settingsFileCount == 0 && csvFileCount == 0 && mkvFileCount == 1;
		if(moviePlayerMode) {
			String cameraName = "Cam: Unknown Camera";
			try { cameraName = new ConnectionCamera.Mkv().parseFile(filepaths.get(0)).connectionName; } catch(Exception e) {}
			
			removeAllConnections();
			imports.clear();
			
			SettingsView.instance.tileColumnsTextfield.set(6);
			SettingsView.instance.tileRowsTextfield.set(6);
			if(SettingsView.instance.timeFormatCombobox.is(SettingsView.TimeFormat.ONLY_TIME)) {
				SettingsView.instance.timeFormatCombobox.set(SettingsView.TimeFormat.TIME_AND_YYYY_MM_DD);
				SettingsView.instance.timeFormat24hoursCheckbox.set(true);
			}
			SettingsView.instance.antialiasingSlider.set(8);
			
			ConnectionCamera connection = new ConnectionCamera(cameraName);
			addConnection(connection);
			imports.put(connection, filepaths.get(0));
			
			OpenGLCameraChart cameraChart = (OpenGLCameraChart) ChartsController.createAndAddChart("Camera").setPosition(0, 0, 5, 4);
			cameraChart.cameraName.set(cameraName);
			ChartsController.createAndAddChart("Timeline").setPosition(0, 5, 5, 5);
		}
		
		if(csvFileCount + mkvFileCount != imports.size()) {
			if(settingsFileCount == 1)
				allConnections.forEach(connection -> connection.disconnect(null));
			NotificationsController.showFailureForMilliseconds("Data file does not correspond with an existing connection.", 5000, true);
			return;
		}
		
		boolean importingInProgress = csvFileCount + mkvFileCount > 0;
		if(importingInProgress) {
			
			importing = true;
			realtimeImporting = true;
			CommunicationView.instance.redraw();
			
			long totalByteCount = 0;
			for(String filepath : filepaths)
				if(filepath.endsWith(".csv") || filepath.endsWith(".mkv"))
					try { totalByteCount += Files.size(Paths.get(filepath)); } catch(Exception e) { }
			
			AtomicLong completedByteCount = NotificationsController.showProgressBar("Importing...", totalByteCount);
		
			// import the CSV / MKV files
			long firstTimestamp = imports.entrySet().stream()
			                                        .mapToLong(entry -> entry.getKey().readFirstTimestamp(entry.getValue()))
			                                        .min().orElse(Long.MAX_VALUE);
			long now = System.currentTimeMillis();
			if(firstTimestamp != Long.MAX_VALUE)
				imports.entrySet().forEach(entry -> entry.getKey().importDataFile(entry.getValue(), firstTimestamp, now, completedByteCount));
			
			// when importing an MKV file by itself, "finish" importing it, then rewind, then play (so the timeline shows the entire amount of time)
			if(moviePlayerMode) {
				cameraConnections.get(0).finishImporting();
				OpenGLChartsView.instance.setPaused(firstTimestamp, null, 0);
				OpenGLChartsView.instance.setPlayForwards();
			}

			// have another thread clean up when importing finishes
			long byteCount = totalByteCount;
			new Thread(() -> {
				while(true) {
					boolean allDone = allConnections.stream().noneMatch(connection -> connection.receiverThread != null &&
					                                                                  connection.receiverThread.isAlive());
					if(allDone) {
						previouslyImported = true;
						importing = false;
						realtimeImporting = false;
						CommunicationView.instance.redraw();
						completedByteCount.addAndGet(byteCount); // to ensure it gets marked done
						return;
					} else {
						try { Thread.sleep(5); } catch(Exception e) { }
					}
				}
			}).start();
			
		}
		
	}
	
	/**
	 * Exports data to files. While exporting is in progress, importing/exporting/connecting/disconnecting will be prohibited.
	 * Connecting/disconnecting is prohibited to prevent the data structure from being changed while exporting is in progress.
	 * 
	 * @param filepath               The absolute path, including the part of the filename that will be common to all exported files.
	 * @param exportSettingsFile     If true, export a settings file.
	 * @param telemetryToExport      List of ConnectionTelemetrys to export.
	 * @param camerasToExport        List of ConnectionCameras to export.
	 */
	public static void exportFiles(String filepath, boolean exportSettingsFile, List<ConnectionTelemetry> telemetryToExport, List<ConnectionCamera> camerasToExport) {
		
		exportThread = new Thread(() -> {
			
			exporting = true;
			CommunicationView.instance.redraw();
			
			long totalSampleCount = 0;
			if(exportSettingsFile)
				totalSampleCount++;
			for(ConnectionTelemetry connection : telemetryToExport)
				totalSampleCount += connection.getSampleCount();
			for(ConnectionCamera camera : camerasToExport)
				totalSampleCount += camera.getFileSize(); // not equivalent to a sampleCount, but hopefully good enough
			AtomicLong completedSampleCount = NotificationsController.showProgressBar("Exporting...", totalSampleCount);
			
			if(exportSettingsFile) {
				exportSettingsFile(filepath + ".txt");
				completedSampleCount.incrementAndGet();
			}
	
			for(ConnectionTelemetry connection : telemetryToExport) {
				int connectionN = allConnections.indexOf(connection);
				String filename = filepath + " - connection " + connectionN + " - " + connection.name.get().replaceAll(filenameSanitizer, "");
				connection.exportDataFile(filename, completedSampleCount);
			}
	
			for(ConnectionCamera connection : camerasToExport) {
				int connectionN = allConnections.indexOf(connection);
				String filename = filepath + " - connection " + connectionN + " - " + connection.name.get().replaceAll(filenameSanitizer, "_");
				connection.exportDataFile(filename, completedSampleCount);
			}
			
			completedSampleCount.addAndGet(totalSampleCount); // ensure it gets marked done
			
			exporting = false;
			CommunicationView.instance.redraw();
			
		});
		
		exportThread.setPriority(Thread.MIN_PRIORITY); // exporting is not critical
		exportThread.setName("File Export Thread");
		exportThread.start();
		
	}
	
	static void finishImporting() {
		
		allConnections.forEach(Connection::finishImporting);
		CommunicationView.instance.redraw();
		
	}
	
	/**
	 * Aborts the file exporting process. This may leave incomplete files on disk.
	 */
	static void cancelExporting() {
		
		if(exportThread != null && exportThread.isAlive()) {
			NotificationsController.printDebugMessage("Exporting... Canceled");
			exportThread.interrupt();
			while(exportThread.isAlive()); // wait
			
			exporting = false;
			CommunicationView.instance.redraw();
		}
		
	}
	
	/**
	 * Saves the GUI settings, communication settings, data structure definition, and chart settings.
	 * 
	 * @param outputFilePath    An absolute path to a .txt file.
	 */
	static void exportSettingsFile(String outputFilePath) {
		
		try {
			
			PrintWriter file = new PrintWriter(new File(outputFilePath), "UTF-8");
			file.println("Telemetry Viewer v0.8 Settings");
			file.println("");
			
			SettingsView.instance.exportTo(file);
			
			file.println(allConnections.size() + " Connections:");
			file.println("");
			allConnections.forEach(connection -> connection.exportSettings(file));
			
			file.println(ChartsController.getCharts().size() + " Charts:");
			ChartsController.getCharts().forEach(chart -> chart.exportTo(file));
			
			file.close();
			
		} catch (IOException e) {
			
			NotificationsController.showFailureForMilliseconds("Unable to save the settings file.", 5000, false);
			
		}
		
	}

	/**
	 * Changes the current state to match settings specified by a file.
	 * (GUI settings, connection settings, data structure definitions for each connection, and chart settings.)
	 * 
	 * @param path       Path to the settings (.txt) file.
	 * @param connect    True to connect, or false to just configure things without connecting to the device.
	 * @return           True on success, or false on error.
	 */
	private static boolean importSettingsFile(String path, boolean connect) {
		
		QueueOfLines lines = null;
		NotificationsController.removeIfConnectionRelated();
		
		try {

			lines = new QueueOfLines(Files.readAllLines(new File(path).toPath(), StandardCharsets.UTF_8));
			
			lines.parseExact("Telemetry Viewer v0.8 Settings");
			lines.parseExact("");
			
			SettingsView.instance.importFrom(lines);

			int connectionsCount = lines.parseInteger("%d Connections:");
			lines.parseExact("");
			
			for(int i = 0; i < connectionsCount; i++) {
				String type = lines.parseString("type = %s");
				Connection newConnection = type.startsWith("Cam: ")        ? new ConnectionCamera(type) :
				                           type.equals("TCP")              ? new ConnectionTelemetryTCP() :
				                           type.equals("UDP")              ? new ConnectionTelemetryUDP() :
				                           type.equals("Demo Mode")        ? new ConnectionTelemetryDemo() :
				                           type.equals("Stress Test Mode") ? new ConnectionTelemetryStressTest() :
				                                                             new ConnectionTelemetryUART(type);
				addConnection(newConnection);
				newConnection.importSettings(lines);
				lines.parseExact("");
				if(connect)
					newConnection.connect(false);
			}
			if(connectionsCount == 0)
				addConnection(null);

			int chartsCount = lines.parseInteger("%d Charts:");
			if(chartsCount == 0) {
				NotificationsController.showHintUntil("Add a chart by clicking on a tile, or click-and-dragging across multiple tiles.", () -> !ChartsController.getCharts().isEmpty(), true);
				return true;
			}

			for(int i = 0; i < chartsCount; i++) {
				
				lines.parseExact("");
				String chartType = lines.parseString("chart type = %s");
				
				PositionedChart chart = ChartsController.createAndAddChart(chartType);
				if(chart == null) {
					lines.lineNumber--;
					throw new AssertionError("Invalid chart type.");
				}
				chart.importFrom(lines);
				
			}
			
			return true;
			
		} catch (IOException ioe) {
			
			NotificationsController.showFailureUntil("Unable to open the settings file.", () -> false, true);
			return false;
			
		} catch(AssertionError ae) {
		
			ChartsController.removeAllCharts();
			for(Connection connection : allConnections)
				connection.disconnect(null);
			
			NotificationsController.showFailureUntil("Error while parsing the settings file:\nLine " + lines.lineNumber + ": " + ae.getMessage(), () -> false, true);
			return false;
		
		}
		
	}
	
	@SuppressWarnings("serial")
	public static class QueueOfLines extends LinkedList<String> {
		
		int lineNumber = 0;
		
		/**
		 * A Queue<String> that keeps track of how many items have been removed from the Queue.
		 * This is for easily tracking the current line number, so it can be displayed in error messages if necessary.
		 * 
		 * @param lines    The lines of text to insert into the queue. Leading tabs will be removed.
		 */
		public QueueOfLines(List<String> lines) {
			super();
			for(String line : lines) {
				while(line.startsWith("\t"))
					line = line.substring(1);
				add(line);
			}
		}
		
		@Override public String remove() {
			lineNumber++;
			try {
				return super.remove();
			} catch(Exception e) {
				throw new AssertionError("Incomplete file. More lines are required.");
			}
		}
		
		/**
		 * Checks if the current line of text exactly matches a format string.
		 * Throws an AssertionException if the text does not match the format string.
		 * 
		 * @param formatString    Expected line of text, for example: "GUI Settings:"
		 */
		public void parseExact(String formatString) {
			
			String text = remove();
			
			if(!text.equals(formatString)) {
				String message = "Text does not match the expected value.\nExpected: " + formatString + "\nFound: " + text;
				throw new AssertionError(message);
			}
			
		}
		
		/**
		 * Attempts to extract an integer from the beginning or end of the current line of text.
		 * Throws an AssertionException if the text does not match the format string or does not start/end with an integer value.
		 * 
		 * @param formatString    Expected line of text, for example: "sample count = %d"
		 * @return                The integer value extracted from the text.
		 */
		public int parseInteger(String formatString) {
			
			String text = remove();
			
			if(!formatString.startsWith("%d") && !formatString.endsWith("%d"))
				throw new AssertionError("Source code contains an invalid format string.");
			
			if(formatString.startsWith("%d")) {
				
				// starting with %d, so an integer should be at the start of the text
				try {
					String[] tokens = text.split(" ");
					int number = Integer.parseInt(tokens[0]);
					String expectedText = formatString.substring(2);
					String remainingText = "";
					for(int i = 1; i < tokens.length; i++)
						remainingText += " " + tokens[i];
					if(remainingText.equals(expectedText))
						return number;
					else {
						String message = "Text does not match the expected value.\nExpected: " + formatString + "\nFound: " + text;
						throw new AssertionError(message);
					}
				} catch(Exception e) {
					String message = "Text does not start with an integer.\nExpected: " + formatString + "\nFound: " + text;
					throw new AssertionError(message);
				}
				
			} else  {
				
				// ending with %d, so an integer should be at the end of the text
				try {
					String expectedText = formatString.substring(0, formatString.length() - 2);
					if(!text.startsWith(expectedText)) {
						String message = "Text does not match the expected value.\nExpected: " + formatString + "\nFound: " + text;
						throw new AssertionError(message);
					}
					String[] tokens = text.split(" ");
					int number = Integer.parseInt(tokens[tokens.length - 1]);
					return number;
				} catch(Exception e) {
					String message = "Text does not end with an integer.\nExpected: " + formatString + "\nFound: " + text;
					throw new AssertionError(message);
				}
				
			}
			
		}
		
		/**
		 * Attempts to extract a boolean from the end of the current line of text.
		 * Throws an AssertionException if the text does not match the format string or does not end with a boolean value.
		 * 
		 * @param formatString    Expected line of text, for example: "show x-axis title = %b"
		 * @return                The boolean value extracted from the text.
		 */
		public boolean parseBoolean(String formatString) {
			
			String text = remove();
			
			if(!formatString.endsWith("%b"))
				throw new AssertionError("Source code contains an invalid format string.");
			
			try {
				String expectedText = formatString.substring(0, formatString.length() - 2);
				String actualText = text.substring(0, expectedText.length());
				String token = text.substring(expectedText.length()); 
				if(actualText.equals(expectedText))
					if(token.toLowerCase().equals("true"))
						return true;
					else if(token.toLowerCase().equals("false"))
						return false;
					else {
						String message = "Text does not end with a boolean.\nExpected: " + formatString + "\nFound: " + text;
						throw new AssertionError(message);
					}
				else
					throw new Exception();
			} catch(Exception e) {
				String message = "Text does not match the expected value.\nExpected: " + formatString + "\nFound: " + text;
				throw new AssertionError(message);
			}
			
		}
		

		
		/**
		 * Attempts to extract a float from the beginning or end of the current line of text.
		 * Throws an AssertionException if the text does not match the format string or does not start/end with a float value.
		 * 
		 * @param formatString    Expected line of text, for example: "manual y-axis maximum = %f"
		 * @return                The float value extracted from the text.
		 */
		public float parseFloat(String formatString) {
			
			String text = remove();
			
			if(!formatString.startsWith("%f") && !formatString.endsWith("%f"))
				throw new AssertionError("Source code contains an invalid format string.");
			
			if(formatString.startsWith("%f")) {
				
				// starting with %f, so a float should be at the start of the text
				try {
					String[] tokens = text.split(" ");
					float number = Float.parseFloat(tokens[0]);
					String expectedText = formatString.substring(2);
					String remainingText = "";
					for(int i = 1; i < tokens.length; i++)
						remainingText += " " + tokens[i];
					if(remainingText.equals(expectedText))
						return number;
					else {
						String message = "Text does not match the expected value.\nExpected: " + formatString + "\nFound: " + text;
						throw new AssertionError(message);
					}
				} catch(Exception e) {
					String message = "Text does not start with a floating point number.\nExpected: " + formatString + "\nFound: " + text;
					throw new AssertionError(message);
				}
				
			} else  {
				
				// ending with %f, so a float should be at the end of the text
				try {
					String[] tokens = text.split(" ");
					float number = Float.parseFloat(tokens[tokens.length - 1]);
					String expectedText = formatString.substring(0, formatString.length() - 2);
					String remainingText = "";
					for(int i = 0; i < tokens.length - 1; i++)
						remainingText += tokens[i] + " ";
					if(remainingText.equals(expectedText))
						return number;
					else {
						String message = "Text does not match the expected value.\nExpected: " + formatString + "\nFound: " + text;
						throw new AssertionError(message);
					}
				} catch(Exception e) {
					String message = "Text does not end with a floating point number.\nExpected: " + formatString + "\nFound: " + text;
					throw new AssertionError(message);
				}
				
			}
			
		}
		
		/**
		 * Attempts to extract a string from the end of the current line of text.
		 * Throws an AssertionException if the text does not match the format string.
		 * 
		 * @param formatString    Expected line of text, for example: "packet type = %s"
		 * @return                The String value extracted from the text.
		 */
		public String parseString(String formatString) {
			
			String text = remove();
			
			if(!formatString.endsWith("%s"))
				throw new AssertionError("Source code contains an invalid format string.");
			
			try {
				String expectedText = formatString.substring(0, formatString.length() - 2);
				String actualText = text.substring(0, expectedText.length());
				String token = text.substring(expectedText.length()); 
				if(actualText.equals(expectedText))
					return token;
				else
					throw new Exception();
			} catch(Exception e) {
				String message = "Text does not match the expected value.\nExpected: " + formatString + "\nFound: " + text;
				throw new AssertionError(message);
			}
			
		}

	}
	
}
