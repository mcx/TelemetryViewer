import java.awt.Color;
import java.awt.Dimension;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import net.miginfocom.swing.MigLayout;

public abstract class ConnectionTelemetry extends Connection {
	
	enum TransmitDataType {
		TEXT   { @Override public String toString() { return "Text";   } },
		HEX    { @Override public String toString() { return "Hex";    } },
		BINARY { @Override public String toString() { return "Binary"; } };
		
		public static TransmitDataType fromString(String text) {
			return text.equals(TEXT.toString())   ? TransmitDataType.TEXT :
			       text.equals(HEX.toString())    ? TransmitDataType.HEX :
			       text.equals(BINARY.toString()) ? TransmitDataType.BINARY :
			                                        null;
		}
	};
	
	// sample rate
	protected volatile int     sampleRate = 0;
	protected final    int     sampleRateMinimum = 1;
	protected final    int     sampleRateMaximum = Integer.MAX_VALUE;
	protected volatile boolean sampleRateAutomatic = true;
	protected WidgetTextfieldInt sampleRateTextfield;
	
	public int getSampleRate() {
		return (sampleRate == 0) ? 1000 : sampleRate; // charts expect >0
	}
	
	protected void setSampleRate(int newRate) {
		if(newRate == sampleRate)
			return;
		sampleRate = newRate;
		sampleRateTextfield.setNumber(sampleRate);
	}
	
	protected void setSampleRateAutomatic(boolean isAutomatic) {
		sampleRateAutomatic = isAutomatic;
	}
	
	// communication protocol
	enum Protocol {
		CSV    { @Override public String toString() { return "CSV Mode";    } },
		BINARY { @Override public String toString() { return "Binary Mode"; } },
		TC66   { @Override public String toString() { return "TC66 Mode";   } };
		
		public static Protocol fromString(String text) {
			return text.equals(CSV.toString())    ? Protocol.CSV :
			       text.equals(BINARY.toString()) ? Protocol.BINARY :
			       text.equals(TC66.toString())   ? Protocol.TC66 :
			                                        null;
		}
	};
	protected volatile Protocol protocol = Protocol.CSV;
	protected WidgetComboboxEnum<Protocol> protocolCombobox;
	
	public boolean isProtocolCsv()    { return protocol == Protocol.CSV;    }
	public boolean isProtocolBinary() { return protocol == Protocol.BINARY; }
	public boolean isProtocolTc66()   { return protocol == Protocol.TC66;   }
	
	protected void setProtocol(Protocol newProtocol) {
		if(newProtocol == protocol)
			return;
		
		boolean wasTC66 = (protocol == Protocol.TC66);
		protocol = newProtocol;
		datasets.removeAll();
		protocolCombobox.setSelectedItem(protocol);
		
		if(protocol == Protocol.TC66) {
			setSampleRate(2);
			setSampleRateAutomatic(false);
			datasets.removeSyncWord();
			DatasetsController.BinaryFieldProcessor fake = DatasetsController.binaryFieldProcessors[0];
			datasets.insert(0,  fake, "Voltage",          new Color(0x00FF00), "V",       1, 1);
			datasets.insert(1,  fake, "Current",          new Color(0x00FFFF), "A",       1, 1);
			datasets.insert(2,  fake, "Power",            new Color(0xFF00FF), "W",       1, 1);
			datasets.insert(3,  fake, "Resistance",       new Color(0x00FFFF), "\u2126",  1, 1);
			datasets.insert(4,  fake, "Group 0 Capacity", new Color(0xFF0000), "mAh",     1, 1);
			datasets.insert(5,  fake, "Group 0 Energy",   new Color(0xFFFF00), "mWh",     1, 1);
			datasets.insert(6,  fake, "Group 1 Capacity", new Color(0xFF0000), "mAh",     1, 1);
			datasets.insert(7,  fake, "Group 1 Energy",   new Color(0xFFFF00), "mWh",     1, 1);
			datasets.insert(8,  fake, "PCB Temperature",  new Color(0xFFFF00), "Degrees", 1, 1);
			datasets.insert(9,  fake, "D+ Voltage",       new Color(0x8000FF), "V",       1, 1);
			datasets.insert(10, fake, "D- Voltage",       new Color(0x0000FF), "V",       1, 1);
			setDataStructureDefined(true);
		} else if(wasTC66) {
			setSampleRate(0);
			setSampleRateAutomatic(true);
		}
		
		CommunicationView.instance.redraw(); // because the sampleRateTextfield may need to be enabled/disabled
	}
	
	// connection type
	public enum Type {
		UART        { @Override public String toString() { return "UART";             } },
		TCP         { @Override public String toString() { return "TCP";              } },
		UDP         { @Override public String toString() { return "UDP";              } },
		DEMO        { @Override public String toString() { return "Demo Mode";        } },
		STRESS_TEST { @Override public String toString() { return "Stress Tess Mode"; } };
		
		public static Type fromString(String text) {
			return text.startsWith(UART.toString())    ? Type.UART :
			       text.equals(TCP.toString())         ? Type.TCP :
			       text.equals(UDP.toString())         ? Type.UDP :
			       text.equals(DEMO.toString())        ? Type.DEMO :
			       text.equals(STRESS_TEST.toString()) ? Type.STRESS_TEST :
			                                             null;
		}
	};
	protected volatile Type type = Type.UART;
	protected WidgetComboboxString namesCombobox;
	
	public boolean isTypeUart()       {return type == Type.UART;        }
	public boolean isTypeTCP()        {return type == Type.TCP;         }
	public boolean isTypeUDP()        {return type == Type.UDP;         }
	public boolean isTypeDemo()       {return type == Type.DEMO;        }
	public boolean isTypeStressTest() {return type == Type.STRESS_TEST; }
	
	// baud rate for UART mode
	protected volatile int baudRate = 9600;
	protected JComboBox<String> baudRateCombobox;
	
	public int getBaudRate() {
		return baudRate;
	}
	
	protected void setBaudRate(int newRate) {
		if(newRate == baudRate)
			return;
		baudRate = newRate;
		baudRateCombobox.setSelectedItem(baudRate + " Baud");
	}
	
	// port number for TCP/UDP modes
	protected volatile int portNumber = 8080;
	protected final    int portNumberMinimum = 1;
	protected final    int portNumberMaximum = 65535;
	protected WidgetTextfieldInt portNumberTextfield;
	
	public int getPortNumber() { return portNumber; }
	
	protected void setPortNumber(int newNumber) {
		if(newNumber == portNumber)
			return;
		portNumber = newNumber;
		portNumberTextfield.setNumber(portNumber);
	}

	
	public final DatasetsController datasets = new DatasetsController(this);
	
	protected JPanel settingsGui = new JPanel(new MigLayout("hidemode 3, gap " + Theme.padding  + ", insets 0 " + Theme.padding + " 0 0"));
	protected JButton connectButton;
	protected JButton removeButton;
	
	private volatile int packetByteCount = 0; // INCLUDING the optional sync word and checksum
	
	protected long previousSampleCountTimestamp = 0; // for automatic sample rate calculation if enabled
	protected int  previousSampleCount = 0;
	
	public void setDataStructureDefined(boolean isDefined) {
		
		if(!isDefined) {
			packetByteCount = 0;
			return;
		}
		
		if(!isProtocolBinary()) {
			packetByteCount = 1; // not used outside of binary mode, so using 1 to indicate it's defined
			return;
		}
		
		int packetLength = 0; 
		if(datasets.getChecksumProcessor() != null)
			packetLength = datasets.getChecksumProcessorOffset() + datasets.getChecksumProcessor().getByteCount();
		else
			for(Dataset dataset : datasets.getList()) {
				if(dataset.location + dataset.processor.getByteCount() - 1 > packetLength)
					packetLength = dataset.location + dataset.processor.getByteCount();
			}
		packetByteCount = packetLength;
		
	}
	
	public boolean isDataStructureDefined() {
		
		return packetByteCount > 0;
		
	}
	
	public volatile static String localIp = "[Local IP Address Unknown]";
	static {
		try { 
			String ips = "";
			Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
			while(interfaces.hasMoreElements()) {
				NetworkInterface ni = interfaces.nextElement();
				Enumeration<InetAddress> addresses = ni.getInetAddresses();
				while(addresses.hasMoreElements()) {
					InetAddress address = addresses.nextElement();
					if(address.isSiteLocalAddress() && !ni.getDisplayName().contains("VMware") && !ni.getDisplayName().contains("VPN"))
						ips += address.getHostAddress() + " or ";
				}
			}
			if(ips.length() > 0)
				localIp = ips.substring(0, ips.length() - 4);
		} catch(Exception e) {}
	}
	
	/**
	 * Prepares, but does not connect to, a connection that can receive "normal telemetry" (a stream of numbers to visualize.)
	 * 
	 */
	@SuppressWarnings("serial")
	public ConnectionTelemetry() {
		
		// connect/disconnect button
		connectButton = new JButton("Connect") {
			@Override public Dimension getPreferredSize() { // giving this button a fixed size so the GUI lines up nicely
				return new JButton("Disconnect").getPreferredSize();
			}
		};
		
		connectButton.addActionListener(event -> {
			if(connectButton.getText().equals("Connect"))
				connect(true);
			else if(connectButton.getText().equals("Disconnect"))
				disconnect(null);
		});
		
		// remove connection button
		removeButton = new JButton(Theme.removeSymbol);
		removeButton.setBorder(Theme.narrowButtonBorder);
		removeButton.addActionListener(event -> {
			ConnectionsController.removeConnection(ConnectionTelemetry.this);
			CommunicationView.instance.redraw();
		});
		
		// automatically calculate the sample rate if needed
		Timer sampleRateCalculator = new Timer(1000, null);
		sampleRateCalculator.addActionListener(event -> {
			
			if(settingsGui.getParent() == null) {
				// cancel timer if this GUI is no longer on screen
				sampleRateCalculator.stop();
				return;
			} else if(!sampleRateAutomatic || !connected || !isDataStructureDefined()) {
				// skip this iteration if not ready/applicable
				return;
			} else if(previousSampleCountTimestamp == 0) {
				// initialize automatic sample rate mode
				previousSampleCountTimestamp = ConnectionsController.importing ? ConnectionsController.getFirstTimestamp() :
				                                                                 System.currentTimeMillis();
				previousSampleCount = getSampleCount();
			} else {
				// calculate the sample rate
				long currentTimestamp = ConnectionsController.importing ? ConnectionsController.getLastTimestamp() :
				                                                          System.currentTimeMillis();
				int  currentSampleCount = getSampleCount();
				long millisecondsDelta = currentTimestamp - previousSampleCountTimestamp;
				int sampleCountDelta = currentSampleCount - previousSampleCount;
				int samplesPerSecond = (int) Math.round((double) sampleCountDelta / ((double) millisecondsDelta / 1000.0));
				sampleRate = samplesPerSecond;
				sampleRateTextfield.disableWithNumber(samplesPerSecond);
				previousSampleCountTimestamp = currentTimestamp;
				previousSampleCount = currentSampleCount;
			}
			
		});
		sampleRateCalculator.start();
		
	}
	
	@Override public long readFirstTimestamp(String path) {
		
		try(Scanner file = new Scanner(new FileInputStream(path), "UTF-8")) {
			
			if(!file.nextLine().split(",")[1].startsWith("UNIX Timestamp"))
				throw new Exception();
			
			return Long.parseLong(file.nextLine().split(",")[1]);
			
		} catch(Exception e) {
			
			return Long.MAX_VALUE;
			
		}
		
	}
	
	@Override public void removeAllData() {
		
		datasets.removeAllData();
		OpenGLChartsView.instance.switchToLiveView();
		
	}

	/**
	 * Reads CSV samples from a file, instead of a live connection.
	 * 
	 * @param path                       Path to the file.
	 * @param firstTimestamp             Timestamp when the first sample from ANY connection was acquired. This is used to allow importing to happen in real time.
	 * @param beginImportingTimestamp    Timestamp when all import threads should begin importing.
	 * @param completedByteCount         Variable to increment as progress is made (this is periodically queried by a progress bar.)
	 */
	@Override public void importDataFile(String path, long firstTimestamp, long beginImportingTimestamp, AtomicLong completedByteCount) {

		removeAllData();
		
		receiverThread = new Thread(() -> {
			
			try {
				
				// open the file
				Scanner file = new Scanner(new FileInputStream(path), "UTF-8");
				
				connected = true;
				previousSampleCountTimestamp = 0;
				previousSampleCount = 0;
				CommunicationView.instance.redraw();
				
				// sanity checks
				if(!file.hasNextLine()) {
					SwingUtilities.invokeLater(() -> disconnect("The CSV file is empty."));
					file.close();
					return;
				}
				
				String header = file.nextLine();
				completedByteCount.addAndGet((long) (header.length() + 2)); // assuming each char is 1 byte, and EOL is 2 bytes.
				String[] tokens = header.split(",");
				int columnCount = tokens.length;
				if(columnCount != datasets.getCount() + 2) {
					SwingUtilities.invokeLater(() -> disconnect("The CSV file header does not match the current data structure."));
					file.close();
					return;
				}
				
				boolean correctColumnLabels = true;
				if(!tokens[0].startsWith("Sample Number"))  correctColumnLabels = false;
				if(!tokens[1].startsWith("UNIX Timestamp")) correctColumnLabels = false;
				for(int i = 0; i < datasets.getCount(); i++) {
					Dataset d = datasets.getByIndex(i);
					String expectedLabel = d.name + " (" + d.unit + ")";
					if(!tokens[2+i].equals(expectedLabel))
						correctColumnLabels = false;
				}
				if(!correctColumnLabels) {
					SwingUtilities.invokeLater(() -> disconnect("The CSV file header does not match the current data structure."));
					file.close();
					return;
				}

				if(!file.hasNextLine()) {
					SwingUtilities.invokeLater(() -> disconnect("The CSV file does not contain any samples."));
					file.close();
					return;
				}
				
				// parse the lines of data
				String line = file.nextLine();
				completedByteCount.addAndGet((long) (line.length() + 2));
				int sampleNumber = getSampleCount();
				while(true) {
					tokens = line.split(",");
					if(ConnectionsController.realtimeImporting) {
						if(Thread.interrupted()) {
							ConnectionsController.realtimeImporting = false;
							CommunicationView.instance.redraw();
						} else {
							long delay = (Long.parseLong(tokens[1]) - firstTimestamp) - (System.currentTimeMillis() - beginImportingTimestamp);
							if(delay > 0)
								try {
									Thread.sleep(delay);
								} catch(Exception e) {
									ConnectionsController.realtimeImporting = false;
									CommunicationView.instance.redraw();
								}
						}
					} else if(Thread.interrupted()) {
						break; // not real-time, and interrupted again, so abort
					}
					for(int columnN = 2; columnN < columnCount; columnN++)
						datasets.getByIndex(columnN - 2).setConvertedSample(sampleNumber, Float.parseFloat(tokens[columnN]));
					sampleNumber++;
					incrementSampleCountWithTimestamp(1, Long.parseLong(tokens[1]));
					
					if(file.hasNextLine()) {
						line = file.nextLine();
						completedByteCount.addAndGet((long) (line.length() + 2));
					} else {
						break;
					}
				}
				
				// done
				SwingUtilities.invokeLater(() -> disconnect(null));
				file.close();
				
			} catch (IOException e) {
				SwingUtilities.invokeLater(() -> disconnect("Unable to open the CSV Log file."));
			} catch (Exception e) {
				SwingUtilities.invokeLater(() -> disconnect("Unable to parse the CSV Log file."));
			}
			
		});
		
		receiverThread.setPriority(Thread.MAX_PRIORITY);
		receiverThread.setName("CSV File Import Thread");
		receiverThread.start();

	}
	
	/**
	 * Exports all samples to a CSV file.
	 * 
	 * @param path                  Full path with file name but without the file extension.
	 * @param completedByteCount    Variable to increment as progress is made (this is periodically queried by a progress bar.)
	 */
	@Override public void exportDataFile(String path, AtomicLong completedByteCount) {
		
		List<Dataset> list = datasets.getList();
		int datasetsCount = list.size();
		int sampleCount = getSampleCount();
		
		try {
			
			PrintWriter logFile = new PrintWriter(path + ".csv", "UTF-8");
			
			// first line is the header
			logFile.print("Sample Number (" + sampleRate + " samples per second),UNIX Timestamp (Milliseconds since 1970-01-01)");
			for(int i = 0; i < datasetsCount; i++) {
				Dataset d = datasets.getByIndex(i);
				logFile.print("," + d.name + " (" + d.unit + ")");
			}
			logFile.println();
			
			// remaining lines are the samples
			// split the work into one worker thread per CSV column, with each thread processing up to 8192 samples at a time
			// worker threads return the corresponding text that belongs in their CSV column
			// this thread then collects the data and outputs it to the CSV file
			int startingSampleNumber = 0;
			final int MAX_SAMPLE_COUNT_PER_THREAD = 8192;
			ExecutorService pool = Executors.newCachedThreadPool();
			List<Future<String[]>> futures = new ArrayList<Future<String[]>>(datasetsCount + 2);
			List<String[]> results = new ArrayList<String[]>(datasetsCount + 2);
			
			// submit first batch
			int count = Integer.min(MAX_SAMPLE_COUNT_PER_THREAD, sampleCount - startingSampleNumber);
			for(int columnN = 0; columnN < datasetsCount + 2; columnN++)
				futures.add(pool.submit(new ExportWorker(columnN, startingSampleNumber, count)));
			
			while(startingSampleNumber < sampleCount) {
				
				// get results
				for(int i = 0; i < datasetsCount + 2; i++)
					results.add(futures.get(i).get());
				futures.clear();
				startingSampleNumber += count;
				
				// if more samples to export, submit another batch BEFORE processing the above results
				int nextCount = Integer.min(MAX_SAMPLE_COUNT_PER_THREAD, sampleCount - startingSampleNumber);
				if(startingSampleNumber < sampleCount) {
					for(int columnN = 0; columnN < datasetsCount + 2; columnN++)
						futures.add(pool.submit(new ExportWorker(columnN, startingSampleNumber, nextCount)));
				}
				
				// process above results
				for(int rowN = 0; rowN < count; rowN++) {
					logFile.print(results.get(0)[rowN]);
					for(int columnN = 1; columnN < datasetsCount + 2; columnN++)
						logFile.print("," + results.get(columnN)[rowN]);
					logFile.println();
				}
				results.clear();
				
				// update the progress tracker
				completedByteCount.addAndGet(count);
				
				count = nextCount;
				
			}
			
			logFile.close();
			
		} catch(Exception e) { }
		
	}
	
	private class ExportWorker implements Callable<String[]> {
		
		private final int csvColumnNumber;
		private final int firstSampleNumber;
		private final int sampleCount;
		
		/**
		 * Prepares a "worker thread" that will generate text values for one CSV column.
		 * This allows splitting up the work of exporting into multiple threads (one thread per CSV column.)
		 * Since a massive amount of samples may be exported, the work may be further split by having each thread only process a certain range of samples (so we don't run out of memory.)
		 * 
		 * @param csvColumnNumber      Which CSV column this thread will generate text values for.
		 * @param firstSampleNumber    First sample number to process, inclusive.
		 * @param sampleCount          Total number of samples to process.
		 */
		public ExportWorker(int csvColumnNumber, int firstSampleNumber, int sampleCount) {
			this.csvColumnNumber = csvColumnNumber;
			this.firstSampleNumber = firstSampleNumber;
			this.sampleCount = sampleCount;
		}

		/**
		 * Generates the text values for this CSV column.
		 * 
		 * @return    A String[] containing the lines of text corresponding with this CSV column.
		 */
		@Override public String[] call() throws Exception {

			String[] text = new String[sampleCount];
			
			if(csvColumnNumber == 0) {
				// first column is the sample number
				for(int i = 0; i < sampleCount; i++)
					text[i] = Integer.toString(firstSampleNumber + i);
			} else if(csvColumnNumber == 1) {
				// second column is the UNIX timestamp
				StorageTimestamps.Cache cache = createTimestampsCache();
				for(int i = 0; i < sampleCount; i++)
					text[i] = Long.toString(getTimestamp(firstSampleNumber + i, cache));
			} else {
				// other columns are the datasets
				Dataset dataset = datasets.getByIndex(csvColumnNumber - 2);
				StorageFloats.Cache cache = dataset.createCache();
				FloatBuffer buffer = dataset.getSamplesBuffer(firstSampleNumber, firstSampleNumber + sampleCount - 1, cache);
				for(int i = 0; i < sampleCount; i++)
					text[i] = Float.toString(buffer.get());
			}
			
			return text;
			
		}
		
	}
	
	@Override public void dispose() {
		
		if(connected)
			disconnect(null);
		
		datasets.dispose();
		timestamps.dispose();
		
		// if this is the only connection, remove all charts, because there may be a timeline chart
		if(ConnectionsController.allConnections.size() == 1)
			ChartsController.removeAllCharts();
		
	}
	
	/**
	 * Spawns a new thread that starts processing received telemetry packets.
	 * 
	 * @param stream    Bytes of received telemetry.
	 */
	protected void startProcessingTelemetry(SharedByteStream stream) {
		
		processorThread = new Thread(() -> {
			
			// wait for the data structure to be defined
			while(!isDataStructureDefined()) {
				try {
					Thread.sleep(1);
				} catch (InterruptedException e) {
					return;
				}
			}
			
			// cache a list of the datasets
			List<Dataset> list = datasets.getList();
			
			// if no telemetry after 100ms, notify the user
			String waitingForTelemetry = isTypeUart() ? name.substring(6) + " is connected. Send telemetry." :
			                             isTypeTCP()  ? "The TCP server is running. Send telemetry to " + localIp + ":" + portNumber :
			                             isTypeUDP()  ? "The UDP listener is running. Send telemetry to " + localIp + ":" + portNumber :
			                                                                    "";
			String receivingTelemetry  = isTypeUart() ? name.substring(6) + " is connected and receiving telemetry." :
			                             isTypeTCP()  ? "The TCP server is running and receiving telemetry." :
			                             isTypeUDP()  ? "The UDP listener is running and receiving telemetry." :
			                                                                    "";
			int oldSampleCount = getSampleCount();
			Timer t = new Timer(100, event -> {
				
				if(isTypeDemo() || isTypeStressTest())
					return;
				
				if(connected) {
					if(getSampleCount() == oldSampleCount)
						NotificationsController.showHintUntil(waitingForTelemetry, () -> getSampleCount() > oldSampleCount, true);
					else
						NotificationsController.showVerboseForMilliseconds(receivingTelemetry, 5000, true);
				}
				
			});
			t.setRepeats(false);
			t.start();
			
			if(isProtocolCsv()) {
				
				// prepare for CSV mode
				stream.setPacketSize(0, list, 0, (byte) 0);
				int maxLocation = 0;
				for(Dataset d : list)
					if(d.location > maxLocation)
						maxLocation = d.location;
				float[] numberForLocation = new float[maxLocation + 1];
				String line = null;
				
				while(true) {
					
					try {

						if(Thread.interrupted())
							throw new InterruptedException();
						
						// read and parse each line of text
						line = stream.readLine();
						String[] tokens = line.split(",");
						for(int i = 0; i < numberForLocation.length; i++)
							numberForLocation[i] = Float.parseFloat(tokens[i]);
						
						int sampleNumber = getSampleCount();
						if(sampleNumber + 1 < 0) { // <0 because of overflow
							SwingUtilities.invokeLater(() -> disconnect("Reached maximum sample count. Disconnected.")); // invokeLater to prevent deadlock
							throw new InterruptedException();
						}
						
						for(Dataset d : list)
							d.setSample(sampleNumber, numberForLocation[d.location]);
						incrementSampleCount(1);
						
					} catch(NumberFormatException | NullPointerException | ArrayIndexOutOfBoundsException e1) {
						
						NotificationsController.showFailureForMilliseconds("A corrupt or incomplete telemetry packet was received:\n\"" + line + "\"", 5000, false);
						
					} catch(InterruptedException e2) {
						
						return;
						
					}
					
				}
				
			} else if(isProtocolBinary()) {
				
				// prepare for binary mode 
				final int syncWordByteCount = datasets.syncWordByteCount;
				final byte syncWord = datasets.syncWord;
				stream.setPacketSize(packetByteCount, list, syncWordByteCount, syncWord);
				
				// use multiple threads to process incoming data in parallel, with each thread parsing up to 8 blocks at a time
				Phaser phaser = new Phaser(1);
				int phase = -1;
				final int THREAD_COUNT = Runtime.getRuntime().availableProcessors();
				final int MAX_BLOCK_COUNT_PER_THREAD = 8;
				ExecutorService pool = Executors.newFixedThreadPool(THREAD_COUNT);
				Parser[] parsers = new Parser[THREAD_COUNT];
				for(int i = 0; i < THREAD_COUNT; i++)
					parsers[i] = new Parser(list, packetByteCount, MAX_BLOCK_COUNT_PER_THREAD, phaser, syncWordByteCount, syncWord);
				
				while(true) {
					
					try {
						
						if(Thread.interrupted())
							throw new InterruptedException();
						
						SharedByteStream.DataBuffer data = stream.getBytes();
						
						// ensure room exists for the new samples
						int sampleNumber = getSampleCount();
						int packetCount = (data.end - data.offset + 1) / packetByteCount;
						if(sampleNumber + packetCount < 0) { // <0 because of overflow
							SwingUtilities.invokeLater(() -> disconnect("Reached maximum sample count. Disconnected.")); // invokeLater to prevent deadlock
							throw new InterruptedException();
						}
						
						boolean abort = false;
						
						// part 1 of 3: process packets individually if not block aligned
						int samplesBeforeNextBlock = Integer.min(packetCount, (StorageFloats.BLOCK_SIZE - (sampleNumber % StorageFloats.BLOCK_SIZE)) % StorageFloats.BLOCK_SIZE);
						while(samplesBeforeNextBlock > 0) {
							
							if(syncWordByteCount > 0 && data.buffer[data.offset] != syncWord) {
								abort = true;
								break;
							}
							if(!datasets.checksumPassed(data.buffer, data.offset, packetByteCount)) {
								data.offset += packetByteCount;
								abort = true;
								break;
							}
							
							for(Dataset dataset : list) {
								float rawNumber = dataset.processor.extractValue(data.buffer, data.offset + dataset.location);
								dataset.setSample(sampleNumber, rawNumber);
							}
							data.offset += packetByteCount;
							incrementSampleCount(1);
							sampleNumber++;
							samplesBeforeNextBlock--;
							
						}
						
						if(abort) {
							stream.releaseBytes(data);
							continue;
						}
						
						// part 2 of 3: process blocks of packets in parallel if block aligned and more than one full block remaining
						packetCount = (data.end - data.offset + 1) / packetByteCount;
						int blocksRemaining = packetCount / StorageFloats.BLOCK_SIZE;
						if(blocksRemaining > 0) {
							int threadN = 0;
							int threadOffset = data.offset;
							while(blocksRemaining > 0) {
								
								int blockCount = Integer.min(blocksRemaining, MAX_BLOCK_COUNT_PER_THREAD);
								pool.execute(parsers[threadN].configure(data, threadOffset, blockCount, sampleNumber, phase++));
								int threadPacketCount = blockCount * StorageFloats.BLOCK_SIZE;
								sampleNumber += threadPacketCount;
								threadOffset += threadPacketCount * packetByteCount;
								blocksRemaining -= blockCount;
								threadN = (threadN + 1) % THREAD_COUNT;
								
							}
							
							int currentPhase = phaser.awaitAdvance(phase);
							while(currentPhase != phase + 1)
								currentPhase = phaser.awaitAdvance(currentPhase);
							
							abort = getSampleCount() != sampleNumber;
							if(abort) {
								stream.releaseBytes(data);
								continue;
							}
						}
						
						// part 3 of 3: process the rest of the packets individually if any remain after the blocks
						packetCount = (data.end - data.offset + 1) / packetByteCount;
						while(packetCount > 0) {
						
							if(syncWordByteCount > 0 && data.buffer[data.offset] != syncWord) {
								abort = true;
								break;
							}
							if(!datasets.checksumPassed(data.buffer, data.offset, packetByteCount)) {
								data.offset += packetByteCount;
								abort = true;
								break;
							}
							
							for(Dataset dataset : list) {
								float rawNumber = dataset.processor.extractValue(data.buffer, data.offset + dataset.location);
								dataset.setSample(sampleNumber, rawNumber);
							}
							data.offset += packetByteCount;
							incrementSampleCount(1);
							sampleNumber++;
							packetCount--;
							
						}
						
						// done
						stream.releaseBytes(data);
					
					} catch(InterruptedException e) {
						
						pool.shutdown();
						try { pool.awaitTermination(5, TimeUnit.SECONDS); } catch (Exception e2) {}
						return;
						
					}
					
				}
				
			} else if(isProtocolTc66()) {
				
				int packetLength = 64;
				stream.setPacketSize(packetLength, list, 0, (byte) 0);
				
				// for some reason the TC66/TC66C uses AES encryption when sending measurements to the PC
				// this key is NOT a secret and IS intentionally in this publicly accessible source code
				byte[] tc66key = new byte[] {
					(byte) 0x58, (byte) 0x21, (byte) 0xfa, (byte) 0x56, (byte) 0x01, (byte) 0xb2, (byte) 0xf0, (byte) 0x26, (byte) 0x87, (byte) 0xff, (byte) 0x12, (byte) 0x04, (byte) 0x62, (byte) 0x2a, (byte) 0x4f, (byte) 0xb0,
					(byte) 0x86, (byte) 0xf4, (byte) 0x02, (byte) 0x60, (byte) 0x81, (byte) 0x6f, (byte) 0x9a, (byte) 0x0b, (byte) 0xa7, (byte) 0xf1, (byte) 0x06, (byte) 0x61, (byte) 0x9a, (byte) 0xb8, (byte) 0x72, (byte) 0x88
				};
				SecretKey key = new SecretKeySpec(tc66key, "AES");
				Cipher aes = null;
				try {
					aes = Cipher.getInstance("AES/ECB/NoPadding");
					aes.init(Cipher.DECRYPT_MODE, key);
				} catch(Exception e) {
					SwingUtilities.invokeLater(() -> disconnect("Unable to prepare TC66 decryption logic.")); // invokeLater to prevent a deadlock
					e.printStackTrace();
					return;
				}
				
				BiFunction<byte[], Integer, Long> getUint32 = (array, offset) -> { return ((long) (array[offset+0] & 0xFF) << 0)  |
					                                                                      ((long) (array[offset+1] & 0xFF) << 8)  |
					                                                                      ((long) (array[offset+2] & 0xFF) << 16) |
					                                                                      ((long) (array[offset+3] & 0xFF) << 24); };
				
				boolean firstPacket = true;
				byte[] currentPacket = new byte[3*packetLength]; // a full packet is actually 3 "sub packets" that start with "pac1", "pac2", or "pac3".
				byte[] previousPacket = new byte[3*packetLength];
				boolean waitingForPac1 = true;
				boolean waitingForPac2 = false;
				boolean waitingForPac3 = false;
					                                                                      
				while(true) {
					
					try {
						
						if(Thread.interrupted())
							throw new InterruptedException();
						
						// get all received telemetry packets
						SharedByteStream.PacketsBuffer packets = stream.readPackets((byte) 0, 0);
						
						// process the received telemetry packets
						while(packets.count > 0) {
							
							// decrypt the "sub packet"
							byte[] packet = aes.doFinal(packets.buffer, packets.offset, packetLength);
							if(waitingForPac1 && packet[0] == 'p' && packet[1] == 'a' && packet[2] == 'c' && packet[3] == '1') {
								// got the first part
								System.arraycopy(packet, 0, currentPacket, 0, packetLength);
								waitingForPac1 = false;
								waitingForPac2 = true;
								packets.count--;
								packets.offset += packetLength;
								continue;
							} else if(waitingForPac2 && packet[0] == 'p' && packet[1] == 'a' && packet[2] == 'c' && packet[3] == '2') {
								// got the second part
								System.arraycopy(packet, 0, currentPacket, packetLength, packetLength);
								waitingForPac2 = false;
								waitingForPac3 = true;
								packets.count--;
								packets.offset += packetLength;
								continue;
							} else if(waitingForPac3 && packet[0] == 'p' && packet[1] == 'a' && packet[2] == 'c' && packet[3] == '3') {
								// got the third part. if this packet isn't a duplicate, parse it.
								System.arraycopy(packet, 0, currentPacket, packetLength*2, packetLength);
								waitingForPac3 = false;
								waitingForPac1 = true;
								packets.count--;
								packets.offset += packetLength;
								if(Arrays.equals(currentPacket, previousPacket))
									continue;
								System.arraycopy(currentPacket, 0, previousPacket, 0, currentPacket.length);
								
								// log some info to the terminal
								if(firstPacket) {
									firstPacket = false;
									String device          = new String(new char[] {(char) currentPacket[4], (char) currentPacket[5], (char) currentPacket[6],  (char) currentPacket[7]});
									String firmwareVersion = new String(new char[] {(char) currentPacket[8], (char) currentPacket[9], (char) currentPacket[10], (char) currentPacket[11]});
									long serialNumber      = getUint32.apply(currentPacket, 12);
									long powerOnCount      = getUint32.apply(currentPacket, 44);
									NotificationsController.showVerboseForMilliseconds(String.format("Device: %s, Firmware Version: %s, Serial Number: %d, Power On Count: %d", device, firmwareVersion, serialNumber, powerOnCount), 5000, true);
								}
								
								// extract data
								float voltage          = getUint32.apply(currentPacket, 48) / 10000f;  // converting to volts
								float current          = getUint32.apply(currentPacket, 52) / 100000f; // converting to amps
								float power            = getUint32.apply(currentPacket, 56) / 10000f;  // converting to watts
								float resistance       = getUint32.apply(currentPacket, 68) / 10f;     // converting to ohms
								long group0mah         = getUint32.apply(currentPacket, 72);
								long group0mwh         = getUint32.apply(currentPacket, 76);
								long group1mah         = getUint32.apply(currentPacket, 80);
								long group1mwh         = getUint32.apply(currentPacket, 84);
								boolean temperatureNeg = getUint32.apply(currentPacket, 88) == 1;
								long temperature       = getUint32.apply(currentPacket, 92) * (temperatureNeg ? -1 : 1); // degrees, C or F (set by user)
								float dPlusVoltage    = getUint32.apply(currentPacket, 96)  / 100f; // converting to volts
								float dMinusVoltage   = getUint32.apply(currentPacket, 100) / 100f; // converting to volts
								
								// populate the datasets
								int sampleNumber = getSampleCount();
								if(sampleNumber + 1 < 0) { // <0 because of overflow
									SwingUtilities.invokeLater(() -> disconnect("Reached maximum sample count. Disconnected.")); // invokeLater to prevent deadlock
									throw new InterruptedException();
								}
								
								list.get(0).setConvertedSample (sampleNumber, voltage);
								list.get(1).setConvertedSample (sampleNumber, current);
								list.get(2).setConvertedSample (sampleNumber, power);
								list.get(3).setConvertedSample (sampleNumber, resistance);
								list.get(4).setConvertedSample (sampleNumber, (float) group0mah);
								list.get(5).setConvertedSample (sampleNumber, (float) group0mwh);
								list.get(6).setConvertedSample (sampleNumber, (float) group1mah);
								list.get(7).setConvertedSample (sampleNumber, (float) group1mwh);
								list.get(8).setConvertedSample (sampleNumber, (float) temperature);
								list.get(9).setConvertedSample (sampleNumber, dPlusVoltage);
								list.get(10).setConvertedSample(sampleNumber, dMinusVoltage);
								incrementSampleCount(1);
							} else {
								// got a sub packet that is out of order, so skip over it so we can re-align
								packets.count--;
								packets.offset += packetLength;
								continue;
							}
							
						}
						
					} catch (BadPaddingException | IllegalBlockSizeException e) {
					
						NotificationsController.showFailureForMilliseconds("Error while decrypting a packet from the TC66.", 5000, true);
						e.printStackTrace();
						continue;
						
					} catch(InterruptedException e) {
						
						return;
						
					}
				}
			}
			
		});
		
		processorThread.setPriority(Thread.MAX_PRIORITY);
		processorThread.setName("Telemetry Processing Thread");
		processorThread.start();
		
	}
	
	public class Parser implements Runnable {
		
		private SharedByteStream.DataBuffer data; // buffer of telemetry packets
		private int offset;                       // where in the buffer this object should start parsing
		private int blockCount;                   // how many blocks this thread should parse
		private int firstSampleNumber;            // which sample number the first packet corresponds to
		private int phase = -2;                   // which phase to wait for
		
		private final List<Dataset> datasets;
		private final int datasetsCount;
		private final int packetByteCount;
		private final float[][] minimumValue;   // [blockN][datasetN]
		private final float[][] maximumValue;   // [blockN][datasetN]
		private final Phaser phaser;
		
		private final int syncWordByteCount;
		private final byte syncWord;
		
		/**
		 * Initializes this object, but does not start to parse any data.
		 * Before calling run(), you must call configure().
		 * 
		 * @param datasets           List of Datasets that receive the parsed data.
		 * @param packetByteCount    Number of bytes in each packet INCLUDING the sync word and optional checksum.
		 * @param maxBlockCount      Maximum number of blocks that should be parsed by this object.
		 * @param phaser             Phaser to await on before incrementing the sample count.
		 */
		public Parser(List<Dataset> datasets, int packetByteCount, int maxBlockCount, Phaser phaser, int syncWordByteCount, byte syncWord) {
			
			this.datasets = datasets;
			this.datasetsCount = datasets.size();
			this.packetByteCount = packetByteCount;
			this.minimumValue = new float[maxBlockCount][datasetsCount];
			this.maximumValue = new float[maxBlockCount][datasetsCount];
			this.phaser = phaser;
			
			this.syncWordByteCount = syncWordByteCount;
			this.syncWord = syncWord;
			
		}
		
		public Parser configure(SharedByteStream.DataBuffer data, int offset, int blockCount, int firstSampleNumber, int phase) {
			
			// wait for this thread to finish processing it's previous configuration
			if(this.phase != -2) {
				int currentPhase = phaser.awaitAdvance(this.phase + 1);
				while(currentPhase < this.phase + 2)
					currentPhase = phaser.awaitAdvance(currentPhase);
			}

			this.data              = data;
			this.offset            = offset;
			this.blockCount        = blockCount;
			this.firstSampleNumber = firstSampleNumber;
			this.phase             = phase;
			
			return this;
			
		}

		@Override public void run() {
				
			float[][] slots = new float[datasetsCount][];
			boolean problem = false;
			int goodPacketsBeforeProblem = 0;
				
			// parse each packet of each block
			for(int blockN = 0; blockN < blockCount; blockN++) {
				
				if(problem)
					break;
				
				for(int datasetN = 0; datasetN < datasetsCount; datasetN++)
					slots[datasetN] = datasets.get(datasetN).getSlot(firstSampleNumber + (blockN * StorageFloats.BLOCK_SIZE));
				
				int slotOffset = (firstSampleNumber + (blockN * StorageFloats.BLOCK_SIZE)) % StorageFloats.SLOT_SIZE;
				float[] minVal = minimumValue[blockN];
				float[] maxVal = maximumValue[blockN];
				for(int packetN = 0; packetN < StorageFloats.BLOCK_SIZE; packetN++) {
					
					if(syncWordByteCount > 0 && data.buffer[offset] != syncWord) {
						problem = true;
						goodPacketsBeforeProblem = (blockN * StorageFloats.BLOCK_SIZE) + packetN;
						break;
					}
					if(!ConnectionTelemetry.this.datasets.checksumPassed(data.buffer, offset, packetByteCount)) {
						problem = true;
						goodPacketsBeforeProblem = (blockN * StorageFloats.BLOCK_SIZE) + packetN;
						break;
					}
					
					for(int datasetN = 0; datasetN < datasetsCount; datasetN++) {
						Dataset d = datasets.get(datasetN);
						float f = d.processor.extractValue(data.buffer, offset + d.location) * d.conversionFactor;
						slots[datasetN][slotOffset] = f;
						if(packetN == 0) {
							minVal[datasetN] = f;
							maxVal[datasetN] = f;
						}
						if(f < minVal[datasetN])
							minVal[datasetN] = f;
						if(f > maxVal[datasetN])
							maxVal[datasetN] = f;
					}
					
					offset += packetByteCount;
					slotOffset++;
					
				}
			}
			
			// update datasets
			for(int datasetN = 0; datasetN < datasetsCount; datasetN++)
				for(int blockN = 0; blockN < blockCount; blockN++)
					datasets.get(datasetN).setRangeOfBlock(firstSampleNumber + (blockN * StorageFloats.BLOCK_SIZE), minimumValue[blockN][datasetN], maximumValue[blockN][datasetN]);
			
			// wait for previous thread to finish
			if(phase >= 0) {
				int currentPhase = phaser.awaitAdvance(phase);
				while(currentPhase != phase + 1)
					currentPhase = phaser.awaitAdvance(currentPhase);
			}
			
			// update the offset and sample count
			if(!problem && getSampleCount() == firstSampleNumber) {
				// this thread and all previous threads were successful
				data.offset += packetByteCount * blockCount * StorageFloats.BLOCK_SIZE;
				incrementSampleCount(StorageFloats.BLOCK_SIZE * blockCount);
			} else if(problem && getSampleCount() == firstSampleNumber) {
				// this thread was the first to have a problem
				data.offset += goodPacketsBeforeProblem * packetByteCount;
				incrementSampleCount(goodPacketsBeforeProblem);
			} else {
				// a previous thread had a problem, so do nothing
			}
			
			// indicate that this thread has finished
			phaser.arrive();
			
		}
		
	}
	
	/**
	 * Stops the threads that process incoming telemetry packets, blocking until done.
	 */
	protected void stopProcessingTelemetry() {
		
		if(processorThread != null && processorThread.isAlive()) {
			processorThread.interrupt();
			while(processorThread.isAlive()); // wait
		}
		
	}
	
	/**
	 * @return    The transmit GUI, or null if no GUI should be displayed.
	 */
	public abstract JPanel getUpdatedTransmitGUI();
	
	private AtomicInteger sampleCount = new AtomicInteger(0);
	private StorageTimestamps timestamps = new StorageTimestamps(this);
	private long firstTimestamp = 0;
	private long lastTimestamp = 0;
	
	public StorageTimestamps.Cache createTimestampsCache() {
		
		return timestamps.createCache();
		
	}
	
	public void clearTimestamps() {
		
		timestamps.clear();
		sampleCount.set(0);
		firstTimestamp = 0;
		lastTimestamp = 0;
		
	}
	
	/**
	 * Increments the sample count and sets the timestamp(s) to the current time.
	 * Call this function after all datasets have received new values from a *live* connection.
	 * 
	 * @param amount    How many new samples were added.
	 */
	public void incrementSampleCount(int amount) {
		
		long timestamp = System.currentTimeMillis();
		timestamps.appendTimestamps(timestamp, amount);
		
		int oldSampleCount = sampleCount.getAndAdd(amount);
		if(oldSampleCount == 0) {
			firstTimestamp = timestamp;
			CommunicationView.instance.redraw();
		}
		lastTimestamp = timestamp;
		
	}
	
	/**
	 * Increments the sample count and sets the timestamp(s) to a specific value.
	 * Call this function after all datasets have received new values from an *imported* connection.
	 * 
	 * @param amount       How many new samples were added.
	 * @param timestamp    The timestamp to use for those samples.
	 */
	public void incrementSampleCountWithTimestamp(int amount, long timestamp) {
		
		timestamps.appendTimestamps(timestamp, amount);
		
		int oldSampleCount = sampleCount.getAndAdd(amount);
		if(oldSampleCount == 0) {
			firstTimestamp = timestamp;
			CommunicationView.instance.redraw();
		}
		lastTimestamp = timestamp;
		
	}
	
	public int getClosestSampleNumberAtOrBefore(long timestamp, int maxSampleNumber, StorageTimestamps.Cache cache) {
		
		return timestamps.getClosestSampleNumberAtOrBefore(timestamp, maxSampleNumber, cache);
		
	}
	
	public int getClosestSampleNumberAfter(long timestamp, StorageTimestamps.Cache cache) {
		
		return timestamps.getClosestSampleNumberAfter(timestamp, cache);
		
	}
	
	/**
	 * @return    The timestamp for sample number 0, or 0 if there are no samples.
	 */
	@Override public long getFirstTimestamp() {
		
		return firstTimestamp;
		
	}
	
	/**
	 * @return    The timestamp for the most recent sample, or 0 if there are no samples.
	 */
	@Override public long getLastTimestamp() {
		
		return lastTimestamp;
		
	}
	
	/**
	 * Gets the timestamp for one specific sample.
	 * 
	 * @param sampleNumber    Which sample to check.
	 * @return                The corresponding UNIX timestamp.
	 */
	@Override public long getTimestamp(int sampleNumber, StorageTimestamps.Cache cache) {
		
		if(sampleNumber < 0)
			return firstTimestamp;
		
		return timestamps.getTimestamp(sampleNumber, cache);
		
	}
	
	public FloatBuffer getTimestampsBuffer(int firstSampleNumber, int lastSampleNumber, long plotMinX, StorageTimestamps.Cache cache) {
		
		return timestamps.getTampstamps(firstSampleNumber, lastSampleNumber, plotMinX, cache);
		
	}
	
	/**
	 * @return    The current number of samples stored in the Datasets.
	 */
	@Override public int getSampleCount() {
		
		return sampleCount.get();
		
	}

}
