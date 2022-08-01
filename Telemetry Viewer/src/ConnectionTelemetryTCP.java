import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Queue;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;

public class ConnectionTelemetryTCP extends ConnectionTelemetry {
	
	private final int MAX_TCP_IDLE_MILLISECONDS = 10000; // if connected but no new samples after than much time, disconnect and wait for a new connection
	
	/**
	 * Prepares, but does not start, a TCP server for receiving telemetry.
	 */
	public ConnectionTelemetryTCP() {
		
		name = Type.TCP.toString();
		type = Type.TCP;
		
		// sample rate
		sampleRate = 0;
		sampleRateAutomatic = true;
		sampleRateTextfield = new WidgetTextfieldInt("Sample Rate",
		                                             "samples rate (hz)",
		                                             "Hz",
		                                             sampleRateMinimum,
		                                             sampleRateMaximum,
		                                             sampleRateAutomatic ? 0 : sampleRate,
		                                             true,
		                                             0,
		                                             "Automatic",
		                                             newRate -> {
		                                             	setSampleRate(newRate);
		                                             	setSampleRateAutomatic(newRate == 0);
		                                             });
		sampleRateTextfield.setToolTipText("<html>Number of telemetry packets sent to the PC each second.<br>Use 0 to have it automatically calculated.<br>If this number is inaccurate, things like the frequency domain chart will be inaccurate.</html>");
		
		// communication protocol
		protocol = Protocol.CSV;
		protocolCombobox = new WidgetComboboxEnum<Protocol>(Protocol.values(),
		                                                    protocol,
		                                                    newProtocol -> setProtocol(newProtocol));
		protocolCombobox.removeItem(Protocol.TC66);
		
		// connections list
		namesCombobox = ConnectionsController.getNamesCombobox(this);
		
		// port number
		portNumber = 8080;
		portNumberTextfield = new WidgetTextfieldInt("Port",
		                                             "tcp port",
		                                             "",
		                                             portNumberMinimum,
		                                             portNumberMaximum,
		                                             portNumber,
		                                             newNumber -> setPortNumber(newNumber));
		
		// populate the panel
		settingsGui.add(sampleRateTextfield);
		settingsGui.add(protocolCombobox);
		settingsGui.add(portNumberTextfield);
		settingsGui.add(namesCombobox);
		settingsGui.add(connectButton);
		settingsGui.add(removeButton);
		
	}

	/**
	 * @return    A GUI for controlling this Connection.
	 */
	@Override public JPanel getUpdatedConnectionGui() {
		
		removeButton.setVisible(ConnectionsController.allConnections.size() > 1 && !ConnectionsController.importing);
		connectButton.setText(connected ? "Disconnect" : "Connect");
		if(ConnectionsController.importing)
			namesCombobox.disableWithMessage("Importing [" + name + "]");
		
		// disable widgets if appropriate
		boolean importingOrExporting = ConnectionsController.importing || ConnectionsController.exporting;
		sampleRateTextfield.setEnabled(!importingOrExporting && !connected);
		protocolCombobox.setEnabled(!importingOrExporting && !connected);
		portNumberTextfield.setEnabled(!importingOrExporting && !connected);
		namesCombobox.setEnabled(!importingOrExporting && !connected);
		connectButton.setEnabled(!importingOrExporting);
		
		return settingsGui;
		
	}

	@Override public void connect(boolean showGui) {

		if(connected)
			disconnect(null);
		
		NotificationsController.removeIfConnectionRelated();
		
		if(ConnectionsController.previouslyImported) {
			for(Connection connection : ConnectionsController.allConnections)
				connection.removeAllData();
			ConnectionsController.previouslyImported = false;
		}
		
		previousSampleCountTimestamp = 0;
		previousSampleCount = 0;
		
		if(showGui) {
			setDataStructureDefined(false);
			CommunicationView.instance.redraw();	
		}
		
		receiverThread = new Thread(() -> {
			
			ServerSocket tcpServer = null;
			Socket tcpSocket = null;
			SharedByteStream stream = new SharedByteStream(ConnectionTelemetryTCP.this);
			
			// start the TCP server
			try {
				tcpServer = new ServerSocket(portNumber);
				tcpServer.setSoTimeout(1000);
			} catch (Exception e) {
				try { tcpServer.close(); } catch(Exception e2) {}
				SwingUtilities.invokeLater(() -> disconnect("Unable to start the TCP server. Make sure another program is not already using port " + portNumber + "."));
				return;
			}
			
			connected = true;
			CommunicationView.instance.redraw();
			
			if(showGui)
				Main.showConfigurationGui(isProtocolCsv() ? new DataStructureCsvView(this) :
				                                        new DataStructureBinaryView(this));
			
			startProcessingTelemetry(stream);
			
			// listen for a connection
			while(true) {

				try {
					
					if(Thread.interrupted() || !connected)
						throw new InterruptedException();
					
					tcpSocket = tcpServer.accept();
					tcpSocket.setSoTimeout(5000); // each valid packet of data must take <5 seconds to arrive
					InputStream is = tcpSocket.getInputStream();

					NotificationsController.showVerboseForMilliseconds("TCP connection established with a client at " + tcpSocket.getRemoteSocketAddress().toString().substring(1) + ".", 5000, true); // trim leading "/" from the IP address
					
					// enter an infinite loop that checks for activity. if the TCP port is idle for >10 seconds, abandon it so another device can try to connect.
					long previousTimestamp = System.currentTimeMillis();
					int previousSampleNumber = getSampleCount();
					while(true) {
						int byteCount = is.available();
						if(byteCount > 0) {
							byte[] buffer = new byte[byteCount];
							is.read(buffer, 0, byteCount);
							stream.write(buffer, byteCount);
							continue;
						}
						Thread.sleep(1);
						int sampleNumber = getSampleCount();
						long timestamp = System.currentTimeMillis();
						if(sampleNumber > previousSampleNumber) {
							previousSampleNumber = sampleNumber;
							previousTimestamp = timestamp;
						} else if(previousTimestamp < timestamp - MAX_TCP_IDLE_MILLISECONDS) {
							NotificationsController.showFailureForMilliseconds("The TCP connection was idle for too long. It has been closed so another device can connect.", 5000, true);
							tcpSocket.close();
							break;
						}
					}
					
				} catch(SocketTimeoutException ste) {
					
					// a client never connected, so do nothing and let the loop try again.
					NotificationsController.showVerboseForMilliseconds("TCP socket timed out while waiting for a connection.", 5000, true);
					
				} catch(IOException ioe) {
					
					// an IOException can occur if an InterruptedException occurs while receiving data
					// let this be detected by the connection test in the loop
					if(!connected)
						continue;
					
					// problem while accepting the socket connection, or getting the input stream, or reading from the input stream
					stopProcessingTelemetry();
					try { tcpSocket.close(); } catch(Exception e2) {}
					try { tcpServer.close(); } catch(Exception e2) {}
					SwingUtilities.invokeLater(() -> disconnect("TCP connection failed."));
					return;
					
				}  catch(InterruptedException ie) {
					
					stopProcessingTelemetry();
					try { tcpSocket.close(); } catch(Exception e2) {}
					try { tcpServer.close(); } catch(Exception e2) {}
					return;
					
				}
			
			}
			
		});
		
		receiverThread.setPriority(Thread.MAX_PRIORITY);
		receiverThread.setName("TCP Server");
		receiverThread.start();
		
	}

	@Override public void importSettings(Queue<String> lines) throws AssertionError {
			
		int port = ChartUtils.parseInteger(lines.remove(), "server port = %d");
		if(port < 0 || port > 65535)
			throw new AssertionError("Invalid port number.");
		
		String protocolString = ChartUtils.parseString(lines.remove(), "protocol = %s");
		if(!protocolString.equals(Protocol.CSV.toString()) && !protocolString.equals(Protocol.BINARY.toString()))
			throw new AssertionError("Invalid packet type.");
		
		int hz = ChartUtils.parseInteger(lines.remove(), "sample rate hz = %d");
		if(hz < 1)
			throw new AssertionError("Invalid sample rate.");
		
		setSampleRate(hz);
		setSampleRateAutomatic(hz == 0);
		setProtocol(Protocol.fromString(protocolString));
		setPortNumber(port);
		
		String syncWord = ChartUtils.parseString(lines.remove(), "sync word = %s");
		try {
			datasets.syncWord = (byte) Integer.parseInt(syncWord.substring(2), 16);
		} catch(Exception e) {
			throw new AssertionError("Invalid sync word.");
		}
		int syncWordByteCount = ChartUtils.parseInteger(lines.remove(), "sync word byte count = %d");
		if(syncWordByteCount < 0 || syncWordByteCount > 1)
			throw new AssertionError("Invalud sync word size.");
		datasets.syncWordByteCount = syncWordByteCount;
		
		int datasetsCount = ChartUtils.parseInteger(lines.remove(), "datasets count = %d");
		if(datasetsCount < 1)
			throw new AssertionError("Invalid datasets count.");
		
		ChartUtils.parseExact(lines.remove(), "");

		for(int i = 0; i < datasetsCount; i++) {
			
			int location            = ChartUtils.parseInteger(lines.remove(), "dataset location = %d");
			String processorName    = ChartUtils.parseString (lines.remove(), "binary processor = %s");
			DatasetsController.BinaryFieldProcessor processor = null;
			for(DatasetsController.BinaryFieldProcessor p : DatasetsController.binaryFieldProcessors)
				if(p.toString().equals(processorName))
					processor = p;
			if(isProtocolBinary() && processor == null)
				throw new AssertionError("Invalid binary processor.");
			String name             = ChartUtils.parseString (lines.remove(), "name = %s");
			String colorText        = ChartUtils.parseString (lines.remove(), "color = 0x%s");
			String unit             = ChartUtils.parseString (lines.remove(), "unit = %s");
			float conversionFactorA = ChartUtils.parseFloat  (lines.remove(), "conversion factor a = %f");
			float conversionFactorB = ChartUtils.parseFloat  (lines.remove(), "conversion factor b = %f");
			
			Color color = new Color(Integer.parseInt(colorText, 16));
			
			datasets.insert(location, processor, name, color, unit, conversionFactorA, conversionFactorB);
			
			if(processor != null && processor.toString().endsWith("Bitfield")) {
				Dataset dataset = datasets.getByLocation(location);
				String line = lines.remove();
				while(!line.equals("")){
					try {
						String bitNumbers = line.split(" ")[0];
						String[] stateNamesAndColors = line.substring(bitNumbers.length() + 3).split(","); // skip past "[n:n] = "
						bitNumbers = bitNumbers.substring(1, bitNumbers.length() - 1); // remove [ and ]
						int MSBit = Integer.parseInt(bitNumbers.split(":")[0]);
						int LSBit = Integer.parseInt(bitNumbers.split(":")[1]);
						Dataset.Bitfield bitfield = dataset.addBitfield(MSBit, LSBit);
						for(int stateN = 0; stateN < stateNamesAndColors.length; stateN++) {
							Color c = new Color(Integer.parseInt(stateNamesAndColors[stateN].split(" ")[0].substring(2), 16));
							String n = stateNamesAndColors[stateN].substring(9);
							bitfield.states[stateN].color = c;
							bitfield.states[stateN].glColor = new float[] {c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, 1};
							bitfield.states[stateN].name = n;
						}
					} catch(Exception e) {
						throw new AssertionError("Line does not specify a bitfield range.");
					}
					line = lines.remove();
				}
			} else {
				ChartUtils.parseExact(lines.remove(), "");
			}
			
		}
		
		int checksumOffset = ChartUtils.parseInteger(lines.remove(), "checksum location = %d");
		String checksumName = ChartUtils.parseString(lines.remove(), "checksum processor = %s");
		
		if(checksumOffset >= 1 && !checksumName.equals("null")) {
			DatasetsController.BinaryChecksumProcessor processor = null;
			for(DatasetsController.BinaryChecksumProcessor p : DatasetsController.binaryChecksumProcessors)
				if(p.toString().equals(checksumName))
					processor = p;
			datasets.insertChecksum(checksumOffset, processor);
		}
		
		setDataStructureDefined(true);
		CommunicationView.instance.redraw();

	}

	@Override public void exportSettings(PrintWriter file) {
		
		file.println("\tconnection type = " + type.toString());
		file.println("\tserver port = "     + portNumber);
		file.println("\tprotocol = "        + protocol.toString());
		file.println("\tsample rate hz = "  + (sampleRateAutomatic ? 0 : sampleRate));
		
		file.println("\tsync word = " + String.format("0x%0" + Integer.max(2, 2 * datasets.syncWordByteCount) + "X", datasets.syncWord));
		file.println("\tsync word byte count = " + datasets.syncWordByteCount);
		file.println("\tdatasets count = " + datasets.getCount());
		file.println("");
		for(Dataset dataset : datasets.getList()) {
			
			file.println("\t\tdataset location = " + dataset.location);
			file.println("\t\tbinary processor = " + (dataset.processor == null ? "null" : dataset.processor.toString()));
			file.println("\t\tname = " + dataset.name);
			file.println("\t\tcolor = " + String.format("0x%02X%02X%02X", dataset.color.getRed(), dataset.color.getGreen(), dataset.color.getBlue()));
			file.println("\t\tunit = " + dataset.unit);
			file.println("\t\tconversion factor a = " + dataset.conversionFactorA);
			file.println("\t\tconversion factor b = " + dataset.conversionFactorB);
			if(dataset.processor != null && dataset.processor.toString().endsWith("Bitfield"))
				for(Dataset.Bitfield bitfield : dataset.bitfields) {
					file.print("\t\t[" + bitfield.MSBit + ":" + bitfield.LSBit + "] = " + String.format("0x%02X%02X%02X ", bitfield.states[0].color.getRed(), bitfield.states[0].color.getGreen(), bitfield.states[0].color.getBlue()) + bitfield.states[0].name);
					for(int i = 1; i < bitfield.states.length; i++)
						file.print("," + String.format("0x%02X%02X%02X ", bitfield.states[i].color.getRed(), bitfield.states[i].color.getGreen(), bitfield.states[i].color.getBlue()) + bitfield.states[i].name);
					file.println();
				}
			file.println("");
		}
		
		file.println("\t\tchecksum location = " + datasets.getChecksumProcessorOffset());
		file.println("\t\tchecksum processor = " + (datasets.getChecksumProcessor() == null ? "null" : datasets.getChecksumProcessor().toString()));

	}
	
	/**
	 * @return    Null, because TCP Mode does not currently support transmitting.
	 */
	@Override public JPanel getUpdatedTransmitGUI() {
		
		return null;
		
	}

}
