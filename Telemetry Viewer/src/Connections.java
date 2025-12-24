import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

import com.fazecast.jSerialComm.SerialPort;

import net.miginfocom.swing.MigLayout;

/**
 * ConnectionController manages all Connections, but the bulk of the work is done by the individual Connections.
 */
public class Connections {
	
	public static volatile boolean importing = false;
	public static volatile boolean exporting = false;
	public static volatile boolean realtimeImporting = true; // false = importing files as fast as possible
	public static volatile boolean previouslyImported = false; // true = the Connections contain imported data
	private static Thread exportThread;
	
	public static List<Connection>                allConnections = new ArrayList<Connection>();
	public static List<ConnectionTelemetry> telemetryConnections = new ArrayList<ConnectionTelemetry>();
	public static List<ConnectionCamera>       cameraConnections = new ArrayList<ConnectionCamera>();
	
	public final static List<String> uarts = Stream.of(SerialPort.getCommPorts())
	                                               .map(port -> "UART: " + port.getSystemPortName())
	                                               .sorted()
	                                               .collect(Collectors.toCollection(() -> Collections.synchronizedList(new ArrayList<String>())));
	public final static List<String> cameras = Webcam.getCameras().stream().map(cam -> "Cam: " + cam.name())
	                                                              .sorted()
	                                                              .collect(Collectors.toCollection(() -> Collections.synchronizedList(new ArrayList<String>())));
	static {
		Thread t = new Thread(() -> {
			while(true) {
				try {
					
					// check what devices are currently present
					List<String> newUarts = Stream.of(SerialPort.getCommPorts())
					                              .map(port -> "UART: " + port.getSystemPortName())
					                              .sorted().toList();
					List<String> newCameras = Webcam.getCameras().stream().map(cam -> "Cam: " + cam.name())
					                                              .sorted()
					                                              .collect(Collectors.toCollection(() -> Collections.synchronizedList(new ArrayList<String>())));
					
					boolean updateComboboxes = !newUarts.equals(uarts) || !newCameras.equals(cameras);
					
					// update the lists and notify if devices have appeared or disappeared
					newUarts.stream().filter(uart -> !uarts.contains(uart))
					                 .forEach(uart -> {
					                      uarts.add(uart);
					                      uarts.sort(null);
					                      Notifications.showDevice("Device found: " + uart.substring(6) + "."); // trim leading "UART: "
					                  });
					
					uarts.stream().filter(uart -> !newUarts.contains(uart))
					              .toList() // to prevent a ConcurrentModificationException
					              .forEach(uart -> {
					                   uarts.remove(uart);
					                   Notifications.showDevice("Device lost: " + uart.substring(6) + "."); // trim leading "UART: "
					               });
					
					newCameras.stream().filter(camera -> !cameras.contains(camera))
					                   .forEach(camera -> {
					                        cameras.add(camera);
					                        cameras.sort(null);
					                        Notifications.showDevice("Device found: " + camera.substring(5) + "."); // trim leading "Cam: "
					                    });
					
					cameras.stream().filter(camera -> !newCameras.contains(camera))
					                .toList() // to prevent a ConcurrentModificationException
					                .forEach(camera -> {
					                     cameras.remove(camera);
					                     Notifications.showDevice("Device lost: " + camera.substring(5) + "."); // trim leading "Cam: "
					                 });
					
					// update the connection name comboboxes if a device was lost or found
					if(updateComboboxes)
						SwingUtilities.invokeLater(() -> {
							allConnections.forEach(connection -> {
								List<String> newNames = getDevicesStream(connection).map(Device::name).toList();
								String selectedName = connection.name.get();
								connection.name.resetValues(newNames, selectedName);
							});
						});
					
				} catch(Exception e) {}
				
				// wait 1 second before repeating
				try { Thread.sleep(1000); } catch(Exception e) {}
			}
		});
		t.setName("Device Monitoring Thread");
		t.start();
	}
	
	static {
		addConnection(null);
	}
	
	public static ConnectionsGUI GUI = new ConnectionsGUI();
	
	private static final String filenameSanitizer = "[^a-zA-Z0-9_\\.\\- ]"; // only allow letters, numbers, underscores, periods, hyphens and spaces.
	
	public record Device(String name, boolean isAvailable, Supplier<Connection> connection) {}
	
	public static Stream<Device> getDevicesStream(Connection parent) {
		
		List<Device> list = new ArrayList<Device>();
		uarts.forEach(name -> {
			boolean isAvailable = telemetryConnections.stream().noneMatch(con -> con != parent && con.name.is(name));
			list.add(new Device(name, isAvailable, () -> new ConnectionTelemetry(name)));
		});
		boolean isDemoAvailable   = telemetryConnections.stream().noneMatch(con -> con != parent && con.name.is("Demo Mode"));
		boolean isStressAvailable = telemetryConnections.stream().noneMatch(con -> con != parent && con.name.is("Stress Test Mode"));
		list.add(new Device("TCP",              true,              () -> new ConnectionTelemetry("TCP")));
		list.add(new Device("UDP",              true,              () -> new ConnectionTelemetry("UDP")));
		list.add(new Device("Demo Mode",        isDemoAvailable,   () -> new ConnectionTelemetry("Demo Mode")));
		list.add(new Device("Stress Test Mode", isStressAvailable, () -> new ConnectionTelemetry("Stress Test Mode")));
		cameras.forEach(name -> {
			boolean isAvailable = cameraConnections.stream().noneMatch(con -> con != parent && con.name.is(name));
			list.add(new Device(name, isAvailable, () -> new ConnectionCamera(name)));
		});
		list.add(new Device(ConnectionCamera.mjpegOverHttp, true, () -> new ConnectionCamera(ConnectionCamera.mjpegOverHttp)));
		return list.stream();
		
	}
	
	public static void addConnection(Connection newConnection) {
		
		if(newConnection == null)
			newConnection = getDevicesStream(null).filter(Device::isAvailable)
			                                      .map(device -> device.connection().get())
			                                      .findFirst().orElse(null);
		
		allConnections.add(newConnection);
		if(newConnection instanceof ConnectionTelemetry newConn)
			telemetryConnections.add(newConn);
		else if(newConnection instanceof ConnectionCamera newConn)
			cameraConnections.add(newConn);
		
		// Connections.GUI will be null when static { addConnection(null); } from above gets run,
		// because the Connections.GUI constructor will still be in progress at that time!
		if(Connections.GUI != null)
			Connections.GUI.redraw(); // redraw the bottom panel so it shows the connection widgets
		Settings.GUI.redraw();        // redraw the left panel so it shows the TX GUI if appropriate
		
	}
	
	public static void removeConnection(Connection oldConnection) {
		
		oldConnection.dispose();
		
		allConnections.remove(oldConnection);
		telemetryConnections.remove(oldConnection);
		cameraConnections.remove(oldConnection);
		
		Connections.GUI.redraw(); // redraw the bottom panel so it doesn't show the old connection's widgets
		Settings.GUI.redraw();    // redraw the left panel so it doesn't show the old connection's TX GUI
		
		if(allConnections.isEmpty())
			OpenGLCharts.GUI.setPlayLive(); // ensure we're not paused at a time/sampleNumber that no longer exists
		
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
		
		Connections.GUI.redraw(); // redraw the bottom panel so it shows the connection widgets
		Settings.GUI.redraw();    // redraw the left panel so it shows the TX GUI if appropriate
		
	}
	
	public static void removeAllConnections() {
		
		allConnections.stream().toList().forEach(Connections::removeConnection); // toList() to prevent a ConcurrentModificationException
		
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
	
	public record SampleDetails(ConnectionTelemetry connection, int sampleNumber, long timestamp) {}
	
	public static SampleDetails getClosestSampleDetailsFor(long timestamp) {
		
		long smallestError = Long.MAX_VALUE;
		ConnectionTelemetry closestConnection = null;
		int closestSampleNumber = 0;
		long closestTimestamp = 0;
		
		for(ConnectionTelemetry connection : telemetryConnections) {
			
			int trueLastSampleNumber = connection.getSampleCount() - 1;
			int closestSampleNumberBefore = connection.getClosestSampleNumberAtOrBefore(timestamp, trueLastSampleNumber);
			int closestSampleNumberAfter = closestSampleNumberBefore + 1;
			if(closestSampleNumberAfter > trueLastSampleNumber)
				closestSampleNumberAfter = trueLastSampleNumber;
			
			long beforeError = timestamp - connection.getTimestamp(closestSampleNumberBefore);
			long afterError  = connection.getTimestamp(closestSampleNumberAfter) - timestamp;
			long error = Long.min(Math.abs(beforeError), Math.abs(afterError));
			
			if(error < smallestError) {
				smallestError = error;
				closestConnection = connection;
				closestSampleNumber = beforeError < afterError ? closestSampleNumberBefore : closestSampleNumberAfter;
				closestTimestamp = connection.getTimestamp(closestSampleNumber);
			}
			
		}
		
		return new SampleDetails(closestConnection, closestSampleNumber, closestTimestamp);
		
	}
	
	/**
	 * @return    True if telemetry can be received.
	 */
	public static boolean exist() {
		
		return allConnections.stream().filter(connection -> {
			if(connection instanceof ConnectionTelemetry c)
				return c.isConnected() && c.isFieldsDefined();
			else
				return connection.isConnected();
		}).count() > 0;
		
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
	
	/**
	 * Imports a settings file, log files, and/or camera files.
	 * The user will be notified if there is a problem with any of the files.
	 * 
	 * @param filepaths    A List<String> of file paths.
	 */
	public static void importFiles(List<String> filepaths) {
		
		if(importing || exporting) {
			Notifications.showFailureForMilliseconds("Unable to import more files while importing or exporting is in progress.", 5000, true);
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
			Notifications.showFailureForMilliseconds("Unsupported file type. Only files exported from TelemetryViewer can be imported:\nSettings files (.txt)\nCSV files (.csv)\nCamera files (.mkv)", 5000, true);
			return;
		}
		if(settingsFileCount > 1) {
			Notifications.showFailureForMilliseconds("Only one settings file can be opened at a time.", 5000, true);
			return;
		}
		
		// if not importing a settings file, disconnect and remove existing samples/frames
		if(settingsFileCount == 0) {
			for(Connection connection : allConnections) {
				connection.disconnect(null, true);
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
					if(filepath.endsWith(" - connection " + connectionN + " - " + connection.name.get().replaceAll(filenameSanitizer, "") + ".mkv"))
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
			
			Settings.GUI.tileColumns.set(6);
			Settings.GUI.tileRows.set(6);
			if(Settings.GUI.timeFormat.is(Settings.TimeFormat.ONLY_TIME))
				Settings.GUI.timeFormat.set(Settings.TimeFormat.TIME_AND_YYYY_MM_DD);
			Settings.GUI.antialiasingLevel.set(8);
			
			if(cameraName.startsWith("http")) {
				ConnectionCamera connection = new ConnectionCamera(ConnectionCamera.mjpegOverHttp);
				connection.url.set(cameraName);
				addConnection(connection);
				imports.put(connection, filepaths.get(0));
			} else {
				ConnectionCamera connection = new ConnectionCamera("Cam: " + cameraName);
				addConnection(connection);
				imports.put(connection, filepaths.get(0));
			}
			
			Charts.Type.CAMERA.createAt(0, 0, 5, 4);
			Charts.Type.TIMELINE.createAt(0, 5, 5, 5);
		}
		
		if(csvFileCount + mkvFileCount != imports.size()) {
			if(settingsFileCount == 1)
				allConnections.forEach(connection -> connection.disconnect(null, true));
			Notifications.showFailureForMilliseconds("Data file does not correspond with an existing connection.", 5000, true);
			return;
		}
		
		boolean importingInProgress = csvFileCount + mkvFileCount > 0;
		if(importingInProgress) {
			
			importing = true;
			realtimeImporting = true;
			Connections.GUI.redraw();
			
			long totalByteCount = 0;
			for(String filepath : filepaths)
				if(filepath.endsWith(".csv") || filepath.endsWith(".mkv"))
					try { totalByteCount += Files.size(Paths.get(filepath)); } catch(Exception e) { }
			
			AtomicLong completedByteCount = Notifications.showProgressBar("Importing...", totalByteCount);
		
			// import the CSV / MKV files
			long firstTimestamp = imports.entrySet().stream()
			                                        .mapToLong(entry -> entry.getKey().readFirstTimestamp(entry.getValue()))
			                                        .min().orElse(Long.MAX_VALUE);
			long now = System.currentTimeMillis();
			if(firstTimestamp != Long.MAX_VALUE)
				imports.entrySet().forEach(entry -> entry.getKey().connectToFile(entry.getValue(), firstTimestamp, now, completedByteCount));
			
			// when importing an MKV file by itself, "finish" importing it, then rewind, then play (so the timeline shows the entire amount of time)
			if(moviePlayerMode) {
				finishImporting();
				while(!telemetryExists()); // wait until at least the first frame is imported
				OpenGLCharts.GUI.setPaused(firstTimestamp, null, 0);
				OpenGLCharts.GUI.setPlayForwards();
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
						Connections.GUI.redraw();
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
			Connections.GUI.redraw();
			
			long totalSampleCount = 0;
			if(exportSettingsFile)
				totalSampleCount++;
			for(ConnectionTelemetry connection : telemetryToExport)
				totalSampleCount += connection.getSampleCount();
			for(ConnectionCamera camera : camerasToExport)
				totalSampleCount += camera.getFileSize(); // not equivalent to a sampleCount, but hopefully good enough
			AtomicLong completedSampleCount = Notifications.showProgressBar("Exporting...", totalSampleCount);
			
			if(exportSettingsFile) {
				exportSettingsFile(filepath + ".txt");
				completedSampleCount.incrementAndGet();
			}
			
			List<Connection> connections = Collections.synchronizedList(allConnections); // not sure if needed, maybe List.indexOf() isn't thread-safe
			Stream.concat(telemetryToExport.stream(), camerasToExport.stream())
			      .parallel()
			      .forEach(connection -> {
			           int connectionN = connections.indexOf(connection);
			           String filename = filepath + " - connection " + connectionN + " - " + connection.name.get().replaceAll(filenameSanitizer, "");
			           connection.exportDataFile(filename, completedSampleCount);
			      });
			
			completedSampleCount.addAndGet(totalSampleCount); // ensure it gets marked done
			
			exporting = false;
			Connections.GUI.redraw();
			
		});
		
		exportThread.setPriority(Thread.MIN_PRIORITY); // exporting is not critical
		exportThread.setName("File Export Thread");
		exportThread.start();
		
	}
	
	static void finishImporting() {
		
		Connections.realtimeImporting = false; // now importing ASAP
		Connections.GUI.redraw();
		
	}
	
	static void cancelImporting() {
		
		allConnections.forEach(connection -> connection.disconnect(null, true));
		Notifications.printInfo("Importing... Canceled");
		Connections.GUI.redraw();
		
	}
	
	/**
	 * Aborts the file exporting process. This may leave incomplete files on disk.
	 */
	static void cancelExporting() {
		
		if(exportThread != null && exportThread.isAlive()) {
			Notifications.printInfo("Exporting... Canceled");
			exportThread.interrupt();
			while(exportThread.isAlive()); // wait
			
			exporting = false;
			Connections.GUI.redraw();
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
			
			Settings.GUI.exportTo(file);
			
			file.println(allConnections.size() + " Connections:");
			file.println("");
			allConnections.forEach(connection -> connection.exportTo(file));
			
			file.println(Charts.count() + " Charts:");
			Charts.forEach(chart -> chart.exportTo(file));
			
			file.close();
			
		} catch (IOException e) {
			
			Notifications.showFailureForMilliseconds("Unable to save the settings file.", 5000, false);
			
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
		Notifications.removeIfConnectionRelated();
		
		try {

			lines = new QueueOfLines(Files.readAllLines(new File(path).toPath(), StandardCharsets.UTF_8));
			
			lines.parseExact("Telemetry Viewer v0.8 Settings");
			lines.parseExact("");
			
			Settings.GUI.importFrom(lines);

			int connectionsCount = lines.parseInteger("%d Connections:");
			lines.parseExact("");
			
			for(int i = 0; i < connectionsCount; i++) {
				String type = lines.parseString("type = %s");
				Connection newConnection = type.startsWith("Cam: ") ? new ConnectionCamera(type) :
				                                                      new ConnectionTelemetry(type);
				addConnection(newConnection);
				newConnection.importFrom(lines);
				lines.parseExact("");
				if(connect)
					newConnection.connect(false);
			}
			if(connectionsCount == 0)
				addConnection(null);

			int chartsCount = lines.parseInteger("%d Charts:");
			for(int i = 0; i < chartsCount; i++)
				Chart.importFrom(lines);
			
			return true;
			
		} catch (IOException ioe) {
			
			Notifications.showFailureUntil("Unable to open the settings file.", () -> false, true);
			return false;
			
		} catch(AssertionError ae) {
		
			Charts.removeAll();
			for(Connection connection : allConnections)
				connection.disconnect(null, true);
			
			Notifications.showFailureUntil("Error while parsing the settings file:\nLine " + lines.lineNumber + ": " + ae.getMessage(), () -> false, true);
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
	
	@SuppressWarnings("serial")
	static class ConnectionsGUI extends JPanel {
		
		private JToggleButton settingsButton;
		private WidgetButton importButton;
		private WidgetButton exportButton;
		private WidgetButton helpButton;
		private WidgetButton connectionButton;
		private List<Connection> previousConnections;

		/**
		 * Private constructor to enforce singleton usage.
		 */
		private ConnectionsGUI () {
			
			super();
			setLayout(new MigLayout("wrap 6, gap " + Theme.padding  + ", insets " + Theme.padding, "[][][][]push[]push[]"));
			
			settingsButton = new JToggleButton("Settings");
			settingsButton.setSelected(Settings.GUI.isVisible());
			settingsButton.addActionListener(event -> showSettings(settingsButton.isSelected()));
			
			importButton = new WidgetButton("Import").onClick(event -> {
				JFileChooser inputFiles = new JFileChooser(System.getProperty("user.dir"));
				inputFiles.setMultiSelectionEnabled(true);
				inputFiles.setFileFilter(new FileNameExtensionFilter("Settings (*.txt) Data (*.csv) or Videos (*.mkv)", "txt", "csv", "mkv"));
				JFrame parentWindow = (JFrame) SwingUtilities.windowForComponent(this);
				if(inputFiles.showOpenDialog(parentWindow) == JFileChooser.APPROVE_OPTION) {
					List<String> filepaths = Stream.of(inputFiles.getSelectedFiles()).map(file -> file.getAbsolutePath()).toList();
					Connections.importFiles(filepaths);
				}
			});
			
			exportButton = new WidgetButton("Export").setEnabled(false).onClick(event -> {
				
				JDialog exportWindow = new JDialog(Main.window, "Select Files to Export");
				exportWindow.setLayout(new MigLayout("wrap 1, insets " + Theme.padding));
				
				JCheckBox settingsFileCheckbox = new JCheckBox("Settings file (the connection settings, chart settings, and GUI settings)", true);
				Map<JCheckBox, ConnectionTelemetry> csvOptions = new LinkedHashMap<JCheckBox, ConnectionTelemetry>();
				Map<JCheckBox, ConnectionCamera>    mkvOptions = new LinkedHashMap<JCheckBox, ConnectionCamera>();
				
				Connections.telemetryConnections.stream().filter(connection -> connection.getSampleCount() > 0)
				                                         .forEach(connection -> csvOptions.put(new JCheckBox("CSV file for \"" + connection.getName() + "\" (the acquired samples and corresponding timestamps)", true), connection));
				Connections.cameraConnections.stream().filter(connection -> connection.getSampleCount() > 0)
				                                      .forEach(connection -> mkvOptions.put(new JCheckBox("MKV file for \"" + connection.getName() + "\" (the acquired images and corresponding timestamps)", true), connection));

				WidgetButton cancelButton = new WidgetButton("Cancel").onClick(event2 -> exportWindow.dispose());
				
				WidgetButton confirmButton = new WidgetButton("Export").onClick(event2 -> {
					
					// cancel if every checkbox is unchecked
					boolean nothingSelected = !settingsFileCheckbox.isSelected() &&
					                          csvOptions.keySet().stream().noneMatch(checkbox -> checkbox.isSelected()) &&
					                          mkvOptions.keySet().stream().noneMatch(checkbox -> checkbox.isSelected());
					if(nothingSelected) {
						exportWindow.dispose();
						return;
					}
					
					JFileChooser saveFile = new JFileChooser(System.getProperty("user.dir"));
					saveFile.setDialogTitle("Export as...");
					JFrame parentWindow = (JFrame) SwingUtilities.windowForComponent(this);
					if(saveFile.showSaveDialog(parentWindow) == JFileChooser.APPROVE_OPTION) {
						String absolutePath = saveFile.getSelectedFile().getAbsolutePath();
						// remove the file extension if the user specified one
						if(saveFile.getSelectedFile().getName().indexOf(".") != -1)
							absolutePath = absolutePath.substring(0, absolutePath.lastIndexOf("."));
						boolean exportSettingsFile = settingsFileCheckbox.isSelected();
						List<ConnectionTelemetry> csvFiles = csvOptions.entrySet().stream().filter(entry -> entry.getKey().isSelected()).map(entry -> entry.getValue()).toList();
						List<ConnectionCamera>    mkvFiles = mkvOptions.entrySet().stream().filter(entry -> entry.getKey().isSelected()).map(entry -> entry.getValue()).toList();
						Connections.exportFiles(absolutePath, exportSettingsFile, csvFiles, mkvFiles);
						exportWindow.dispose();
					}
					
				});
				
				JPanel buttonsPanel = new JPanel();
				buttonsPanel.setLayout(new MigLayout("insets " + (Theme.padding * 2) + " 0 0 0", "[33%!][grow][33%!]")); // space the buttons, and 3 equal columns
				cancelButton.appendTo(buttonsPanel, "growx, cell 0 0");
				confirmButton.appendTo(buttonsPanel, "growx, cell 2 0");
				
				exportWindow.add(settingsFileCheckbox);
				csvOptions.keySet().forEach(checkbox -> exportWindow.add(checkbox));
				mkvOptions.keySet().forEach(checkbox -> exportWindow.add(checkbox));
				exportWindow.add(buttonsPanel, "grow x");
				exportWindow.pack();
				exportWindow.setModal(true);
				exportWindow.setLocationRelativeTo(Main.window);
				exportWindow.setVisible(true);
				
			});
			
			// help
			helpButton = new WidgetButton("Help").onClick(event -> {
				
				JFrame parentWindow = (JFrame) SwingUtilities.windowForComponent(GUI);
				String helpText = "<html><b>" + Main.versionString + " (" + Main.versionDate + ")</b><br>" +
				                  "A fast and easy tool for visualizing data received over UART, TCP, UDP or camera connections.<br><br>" +
				                  "Step 1: Use the controls at the lower-right corner of the window to connect to a device or to start a TCP/UDP server.<br>" +
				                  "Step 2: A \"Data Structure\" screen will appear for UART/TCP/UDP connections. Use it to specify how your data is laid out, then click \"Done.\"<br>" +
				                  "Step 3: Click-and-drag in the tiles region to place a chart.<br>" +
				                  "Step 4: A chart configuration panel will appear. Use it to specify the type of chart and its settings, then click \"Done.\"<br>" +
				                  "Repeat steps 3 and 4 to create more charts if desired.<br>" +
				                  "If multiple telemetry streams will be used, click \"New Connection\" then repeat steps 1-4 as needed.<br><br>" +
				                  "Use your scroll wheel to rewind or fast forward.<br>" +
				                  "Use your scroll wheel while holding down Ctrl to zoom in or out.<br>" +
				                  "Use your scroll wheel while holding down Shift to adjust display scaling.<br><br>" +
				                  "Click the x icon at the top-right corner of any chart to remove it.<br>" +
				                  "Click the box icon at the top-right corner of any chart to maximize it.<br>" +
				                  "Click the gear icon at the top-right corner of any chart to adjust its settings.<br><br>" +
				                  "Click the \"Settings\" button to adjust options related to the GUI, to transmit data to devices, or to adjust camera settings.<br>" +
				                  "Click the \"Import\" button to open previously saved files.<br>" +
				                  "Click the \"Export\" button to save your settings and/or data to files.<br>" +
				                  "If settings are exported to a file named \"default.txt\" placed in the same folder as Telemetry Viewer, it will be automatically imported at start up.<br>" +
				                  "Files can also be imported via drag-n-drop.<br><br>" +
				                  "Author: Farrell Farahbod<br>" +
				                  "This software is free and open source.</html>";
				JLabel helpLabel = new JLabel(helpText);
				WidgetButton websiteButton = new WidgetButton("<html><a href=\"http://www.farrellf.com/TelemetryViewer/\">http://www.farrellf.com/TelemetryViewer/</a></html>")
				                                 .onClick(event2 -> { try { Desktop.getDesktop().browse(new URI("http://www.farrellf.com/TelemetryViewer/")); } catch(Exception ex) {} });
				WidgetButton paypalButton = new WidgetButton("<html><a href=\"https://paypal.me/farrellfarahbod/\">https://paypal.me/farrellfarahbod/</a></html>")
				                                .onClick(event2 -> { try { Desktop.getDesktop().browse(new URI("https://paypal.me/farrellfarahbod/")); } catch(Exception ex) {} });
				
				JPanel panel = new JPanel();
				panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
				panel.add(helpLabel);
				websiteButton.appendTo(panel, "");
				panel.add(new JLabel("<html><br>If you find this software useful and want to \"buy me a coffee\" that would be great!</html>"));
				paypalButton.appendTo(panel, "");
				panel.add(new JLabel(" "));
				JOptionPane.showMessageDialog(parentWindow, panel, "Help", JOptionPane.PLAIN_MESSAGE);

			});
			
			connectionButton = new WidgetButton("").onClick(event -> {
				     if(Connections.importing &&  Connections.realtimeImporting) Connections.finishImporting();
				else if(Connections.importing && !Connections.realtimeImporting) Connections.cancelImporting();
				else if(Connections.exporting)                                   Connections.cancelExporting();
				else                                                             Connections.addConnection(null);
			});
			
			// update and show the components
			redraw();
			
		}
		
		/**
		 * Redraws the panel. This should be done when any of the following events occur:
		 * 
		 *     When importing:
		 *         import/export buttons are disabled
		 *         the newConnection button can be used to cancel importing
		 *         connection names become "Importing [Name]"
		 *         all connection widgets are disabled
		 *         
		 *     When exporting:
		 *         import/export buttons are disabled
		 *         newConnection button can be used to cancel exporting
		 *         all connection widgets are disabled
		 *         
		 *     When connected:
		 *         connect/disconnect button text set to "Disconnect"
		 *         all connection widgets are disabled
		 *         
		 *     When disconnected:
		 *         connect/disconnect button text set to "Connect"
		 *         all connection widgets are enabled
		 *         
		 *     When the first sample/frame is received:
		 *         export button is enabled
		 *         
		 *     When zero samples/frames exist:
		 *         export button is disabled
		 *         
		 *     When a connection is created or removed
		 *         the connection widgets will be shown or not
		 */
		public void redraw() {
			
			Runnable task = () -> {
				
				boolean connectionsChanged = !Connections.allConnections.equals(previousConnections);
				
				if(connectionsChanged) {
					
					// only repopulate if the connections have changed
					// this allows the user to keep interacting with a widget while a connection automatically reconnects
					// if we always repopulate, a widget could lose focus while the user is interacting with it
					removeAll();
					add(settingsButton);
					importButton.appendTo(this, "");
					exportButton.appendTo(this, "");
					helpButton.appendTo(this, "");
					connectionButton.appendTo(this, "");
					for(int i = 0; i < Connections.allConnections.size(); i++)
						add(Connections.allConnections.get(i).getConfigGUI(), "align right, cell 5 " + i);
					
					previousConnections = List.copyOf(Connections.allConnections);
					
				}
				
				// update all widgets
				Connections.allConnections.forEach(connection -> connection.updateConfigGUI());
				importButton.setEnabled(!Connections.importing && !Connections.exporting);
				exportButton.setEnabled(!Connections.importing && !Connections.exporting && Connections.telemetryExists());
				connectionButton.setText(Connections.importing &&  Connections.realtimeImporting ? "Finish Importing" :
				                         Connections.importing && !Connections.realtimeImporting ? "Cancel Importing" :
				                         Connections.exporting                                   ? "Cancel Exporting" :
				                                                                                   "New Connection");
				
				// redraw
				revalidate();
				repaint();
				
			};
			
			// after the first sample or frame is received, the data processing thread will call redraw(), so we can't assume we're on the EDT
			if(SwingUtilities.isEventDispatchThread())
				task.run();
			else
				SwingUtilities.invokeLater(task);
			
		}
		
		/**
		 * @param isShown    True to show the settings panel.
		 */
		public void showSettings(boolean isShown) {

			Settings.GUI.setVisible(isShown);
			settingsButton.setSelected(isShown);
				
		}
		
	}

	
}
