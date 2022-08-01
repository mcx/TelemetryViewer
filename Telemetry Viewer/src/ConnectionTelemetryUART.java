import java.awt.Color;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import com.fazecast.jSerialComm.SerialPort;

import net.miginfocom.swing.MigLayout;

public class ConnectionTelemetryUART extends ConnectionTelemetry {
	
	static List<String> portNames = new ArrayList<String>();
	static {
		for(SerialPort port : SerialPort.getCommPorts())
			portNames.add(Type.UART + ": " + port.getSystemPortName());
		Collections.sort(portNames);
	}
	
	public static List<String> getPortNames() {
		return portNames;
	}
	
	public void setPortName(String newName) {
		if(newName.equals(name))
			return;
		name = newName;
		namesCombobox.setSelectedItem(name);
		namesCombobox.removeItem(Type.UART.toString()); // in case the object was created without a port name
		
		SettingsView.instance.redraw(); // so the transmit panel shows the new port name
	}
	
	// transmit settings
	protected Queue<byte[]> transmitQueue = new ConcurrentLinkedQueue<byte[]>();
	protected long previousRepititionTimestamp = 0;
	
	private TransmitDataType transmitDatatype = TransmitDataType.TEXT;
	private WidgetComboboxEnum<TransmitDataType> transmitDatatypeCombobox;
	
	private byte[] transmitDataBytes;
	private WidgetTextfieldString transmitDataTextfield;
	
	private boolean transmitAppendCR = false;
	private WidgetCheckbox transmitAppendCRcheckbox;
	
	private boolean transmitAppendLF = false;
	private WidgetCheckbox transmitAppendLFcheckbox;
	
	private boolean transmitRepeatedly = false;
	private WidgetCheckbox transmitRepeatedlyCheckbox;

	private int transmitRepeatedlyMilliseconds = 1000;
	private WidgetTextfieldInt transmitRepeatedlyMillisecondsTextfield;
	
	private JButton transmitSaveButton;
	private JButton transmitTransmitButton;
	
	public record Packet(String label, byte[] bytes) {}
	private List<Packet> transmitSavedPackets = new ArrayList<Packet>();
	
	/**
	 * Prepares for, but does not connect to, a UART that provides telemetry.
	 * 
	 * @param connectionName    The connection to select.
	 */
	public ConnectionTelemetryUART(String connectionName) {
		
		// configure this connection
		name = connectionName;
		type = Type.UART;
		
		// transmitted data can be automatically repeated every n milliseconds
		transmitRepeatedlyCheckbox = new WidgetCheckbox("Repeat",
		                                                transmitRepeatedly,
		                                                isChecked -> transmitRepeatedly = isChecked);
		
		transmitRepeatedlyMillisecondsTextfield = new WidgetTextfieldInt("",
		                                                                 "transmit repetition milliseconds",
		                                                                 "ms",
		                                                                 1,
		                                                                 Integer.MAX_VALUE,
		                                                                 transmitRepeatedlyMilliseconds,
		                                                                 newTime -> transmitRepeatedlyMilliseconds = newTime);
		
		// packets can be saved to JButtons
		transmitSaveButton = new JButton("Save");
		transmitSaveButton.addActionListener(event -> {
			byte[] bytes = transmitDataBytes;
			String label = (transmitDatatype == TransmitDataType.TEXT) ? "Text: " + ChartUtils.convertBytesToTextString(bytes, true) :
			               (transmitDatatype == TransmitDataType.HEX)  ? "Hex: "  + ChartUtils.convertBytesToHexString(bytes) :
			                                                             "Bin: "  + ChartUtils.convertBytesToBinString(bytes);
			boolean notAlreadySaved = true;
			for(Packet packet : transmitSavedPackets)
				if(Arrays.equals(packet.bytes, bytes) && packet.label.equals(label))
					notAlreadySaved = false;
			if(notAlreadySaved) {
				transmitSavedPackets.add(new Packet(label, bytes));
				SettingsView.instance.redraw(); // so this Transmit panel gets redrawn
			}
			transmitDataTextfield.setUserText("");
		});
		
		// transmit button for sending the data once
		transmitTransmitButton = new JButton("Transmit");
		transmitTransmitButton.addActionListener(event -> transmitQueue.add(transmitDataBytes));
		
		// data provided by the user
		transmitDataTextfield = new WidgetTextfieldString("",
		                                                  "transmit data",
		                                                  "",
		                                                  transmitDatatype,
		                                                  "",
		                                                  newText -> updateDataBytes());
		transmitDataTextfield.addKeyListener(new KeyAdapter() {
			@Override public void keyReleased(KeyEvent e) {
				boolean haveData = transmitAppendCR || transmitAppendLF || transmitDataTextfield.hasText();
				transmitRepeatedlyCheckbox.setEnabled(haveData);
				transmitRepeatedlyMillisecondsTextfield.setEnabled(haveData);
				transmitSaveButton.setEnabled(haveData);
				transmitTransmitButton.setEnabled(haveData);
			}
		});
		
		// for text mode, \r or \n can be automatically appended
		transmitAppendCRcheckbox = new WidgetCheckbox("CR",
		                                              transmitAppendCR,
		                                              isAppened -> {
		                                                  transmitAppendCR = isAppened;
		                                                  updateDataBytes();
		                                              });
		
		transmitAppendLFcheckbox = new WidgetCheckbox("LF",
		                                              transmitAppendLF,
		                                              isAppened -> {
		                                                  transmitAppendLF = isAppened;
		                                                  updateDataBytes();
		                                              });
		
		// which data format the user will provide
		transmitDatatypeCombobox = new WidgetComboboxEnum<TransmitDataType>(TransmitDataType.values(),
		                                                                    transmitDatatype,
		                                                                    newDatatype -> {
		                                                                        byte[] oldDataBytes = transmitDataBytes;
		                                                                        transmitDatatype = newDatatype;
		                                                                        transmitDataTextfield.setDataType(transmitDatatype);
		                                                                        transmitAppendCRcheckbox.setEnabled(transmitDatatype == TransmitDataType.TEXT);
		                                                                        transmitAppendLFcheckbox.setEnabled(transmitDatatype == TransmitDataType.TEXT);
		                                                                        if(transmitDatatype == TransmitDataType.TEXT) {
		                                                                            byte[] bytes = oldDataBytes;
		                                                                            boolean lf =      bytes.length > 0 && bytes[bytes.length - 1] == '\n';
		                                                                            boolean cr = lf ? bytes.length > 1 && bytes[bytes.length - 2] == '\r' :
		                                                                                              bytes.length > 0 && bytes[bytes.length - 1] == '\r';
		                                                                            transmitAppendCRcheckbox.setSelected(cr);
		                                                                            transmitAppendLFcheckbox.setSelected(lf);
		                                                                            int bytesToSkip = (cr && lf) ? 2 : cr ? 1 : lf ? 1 : 0;
		                                                                            byte[] textBytes = Arrays.copyOf(bytes, bytes.length - bytesToSkip);
		                                                                            transmitDataTextfield.setUserTextFromBytes(textBytes);
		                                                                        } else {
		                                                                            transmitAppendCRcheckbox.setSelected(false);
		                                                                            transmitAppendLFcheckbox.setSelected(false);
		                                                                            transmitDataTextfield.setUserTextFromBytes(oldDataBytes);
		                                                                        }
		                                                                    });
		transmitDataTextfield.addActionListener(event -> transmitTransmitButton.doClick());
		
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
		                                                    newProtocol -> {
		                                                        setProtocol(newProtocol);
		                                                        
		                                                        // also reset transmit settings
		                                                        transmitQueue.clear();
		                                                        transmitSavedPackets.clear();
		                                                        previousRepititionTimestamp = 0;
		                                                        transmitDatatypeCombobox.setSelectedItem(ConnectionTelemetry.TransmitDataType.TEXT);
		                                                        transmitDataTextfield.setUserText((protocol == Protocol.TC66) ? "getva" : "");
		                                                        transmitAppendCRcheckbox.setSelected(false);
		                                                        transmitAppendLFcheckbox.setSelected(false);
		                                                        transmitRepeatedlyCheckbox.setSelected(protocol == Protocol.TC66);
		                                                        transmitRepeatedlyMillisecondsTextfield.setNumber((protocol == Protocol.TC66) ? 200 : 1000);
		                                                        if(protocol == Protocol.TC66) {
		                                                            transmitSavedPackets.add(new Packet("Rotate Display",  "rotat".getBytes()));
		                                                            transmitSavedPackets.add(new Packet("Previous Screen", "lastp".getBytes()));
		                                                            transmitSavedPackets.add(new Packet("Next Screen",     "nextp".getBytes()));
		                                                        }
		                                                        SettingsView.instance.redraw(); // redraw the transmit GUI if visible
		                                                    });
		
		// connections list
		namesCombobox = ConnectionsController.getNamesCombobox(this);
		
		// baud rate
		baudRateCombobox = new JComboBox<String>(new String[] {"9600 Baud", "19200 Baud", "38400 Baud", "57600 Baud", "115200 Baud", "230400 Baud", "460800 Baud", "921600 Baud", "1000000 Baud", "2000000 Baud"});
		baudRateCombobox.setMaximumRowCount(baudRateCombobox.getItemCount() + 1);
		baudRateCombobox.setMinimumSize(baudRateCombobox.getPreferredSize());
		baudRateCombobox.setMaximumSize(baudRateCombobox.getPreferredSize());
		baudRateCombobox.setEditable(true);
		baudRateCombobox.setSelectedItem(Integer.toString(baudRate) + " Baud");
		baudRateCombobox.addActionListener(event -> {
			try {
				String text = baudRateCombobox.getSelectedItem().toString().trim();
				if(text.endsWith("Baud"))
					text = text.substring(0, text.length() - 4).trim();
				int rate = Integer.parseInt(text);
				if(rate > 0 && rate != baudRate) {
					baudRateCombobox.setSelectedItem(rate + " Baud");
					setBaudRate(rate);
				} else if(rate <= 0)
					throw new Exception();
			} catch(Exception e) {
				baudRateCombobox.setSelectedItem(baudRate + " Baud");
			}
			CommunicationView.instance.redraw();
		});
		baudRateCombobox.getEditor().getEditorComponent().addFocusListener(new FocusListener() {
			@Override public void focusGained(FocusEvent e) { baudRateCombobox.getEditor().selectAll(); }
			@Override public void focusLost(FocusEvent e) { }
		});
		
		// populate the panel
		settingsGui.add(sampleRateTextfield);
		settingsGui.add(protocolCombobox);
		settingsGui.add(baudRateCombobox);
		settingsGui.add(namesCombobox);
		settingsGui.add(connectButton);
		settingsGui.add(removeButton);
		
	}
	
	/**
	 * Updates transmitDataBytes[] with the proposed packet's bytes.
	 * This method should be called whenever the data textfield is updated, and whenever the append CR/LF checkboxes are updated.
	 */
	private void updateDataBytes() {
		
		byte[] bytes = transmitDataTextfield.getUserTextAsBytes();
		if(transmitAppendCR) {
			bytes = Arrays.copyOf(bytes, bytes.length + 1);
			bytes[bytes.length - 1] = '\r';
		}
		if(transmitAppendLF) {
			bytes = Arrays.copyOf(bytes, bytes.length + 1);
			bytes[bytes.length - 1] = '\n';
		}
		transmitDataBytes = bytes;
		
		transmitRepeatedlyCheckbox.setEnabled(transmitDataBytes.length > 0);
		transmitRepeatedlyMillisecondsTextfield.setEnabled(transmitDataBytes.length > 0);
		transmitSaveButton.setEnabled(transmitDataBytes.length > 0);
		transmitTransmitButton.setEnabled(transmitDataBytes.length > 0);
		
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
		sampleRateTextfield.setEnabled(!importingOrExporting && !connected && !isProtocolTc66());
		protocolCombobox.setEnabled(!importingOrExporting && !connected);
		namesCombobox.setEnabled(!importingOrExporting && !connected);
		baudRateCombobox.setEnabled(!importingOrExporting && !connected);
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
		
		SerialPort uartPort = SerialPort.getCommPort(name.substring(6)); // trim the leading "UART: "
		uartPort.setBaudRate(baudRate);
		uartPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, 0, 0);
		
		// try 3 times before giving up, because some Bluetooth UARTs have trouble connecting
		if(!uartPort.openPort() && !uartPort.openPort() && !uartPort.openPort()) {
			SwingUtilities.invokeLater(() -> disconnect("Unable to connect to " + name.substring(6) + "."));
			return;
		}
		
		connected = true;
		CommunicationView.instance.redraw();
		
		if(showGui && !isProtocolTc66()) {
			setDataStructureDefined(false);
			Main.showConfigurationGui(isProtocolCsv() ? new DataStructureCsvView(this) :
			                                         new DataStructureBinaryView(this));
		}
		
		receiverThread = new Thread(() -> {
			
			InputStream uart = uartPort.getInputStream();
			SharedByteStream stream = new SharedByteStream(ConnectionTelemetryUART.this);
			startProcessingTelemetry(stream);
			byte[] buffer = new byte[1048576]; // 1MB
			
			// listen for packets
			while(true) {

				try {
					
					if(Thread.interrupted() || !connected)
						throw new InterruptedException();
					
					int length = uart.available();
					if(length < 1) {
						Thread.sleep(1);
					} else {
						if(length > buffer.length)
							buffer = new byte[length];
						length = uart.read(buffer, 0, length);
						if(length < 0)
							throw new IOException();
						else
							stream.write(buffer, length);
					}
					
				} catch(IOException ioe) {
					
					// an IOException can occur if an InterruptedException occurs while receiving data
					// let this be detected by the connection test in the loop
					if(!connected)
						continue;
					
					// problem while reading from the UART
					stopProcessingTelemetry();
					uartPort.closePort();
					SwingUtilities.invokeLater(() -> disconnect("Error while reading from " + name));
					return;
					
				}  catch(InterruptedException ie) {
					
					stopProcessingTelemetry();
					uartPort.closePort();
					return;
					
				}
			
			}
			
		});
		
		receiverThread.setPriority(Thread.MAX_PRIORITY);
		receiverThread.setName("UART Receiver Thread");
		receiverThread.start();
		
		transmitterThread = new Thread(() -> {
			
			OutputStream uart = uartPort.getOutputStream();
			
			while(true) {
				
				try {
					
					if(Thread.interrupted() || !connected)
						throw new InterruptedException();
					
					while(!transmitQueue.isEmpty()) {
						byte[] data = transmitQueue.remove();
						uart.write(data);
						
//						String message = "Transmitted: ";
//						for(byte b : data)
//							message += String.format("%02X ", b);
//						NotificationsController.showDebugMessage(message);
					}
					
					if(transmitRepeatedly && previousRepititionTimestamp + transmitRepeatedlyMilliseconds <= System.currentTimeMillis()) {
						previousRepititionTimestamp = System.currentTimeMillis();
						uart.write(transmitDataBytes);
						
//						String message = "Transmitted: ";
//						for(byte b : transmitDataBytes)
//							message += String.format("%02X ", b);
//						NotificationsController.showDebugMessage(message);
					}
					
					Thread.sleep(1);
					
				} catch(IOException e) {
					
					// an IOException can occur if an InterruptedException occurs while transmitting data
					// let this be detected by the connection test in the loop
					if(!connected)
						continue;
					
					// problem while writing to the UART
					NotificationsController.showFailureForMilliseconds("Error while writing to " + name, 5000, false);
					
				} catch(InterruptedException ie) {
					
					return;
					
				}
				
			}
			
		});
		
		transmitterThread.setPriority(Thread.MAX_PRIORITY);
		transmitterThread.setName("UART Transmitter Thread");
		transmitterThread.start();
		
	}

	@Override public void importSettings(Queue<String> lines) throws AssertionError {
		
		String portName = ChartUtils.parseString(lines.remove(), "port = %s");
		if(portName.length() < 1)
			throw new AssertionError("Invalid port.");
		setPortName("UART: " + portName);
		
		int baud = ChartUtils.parseInteger(lines.remove(), "baud rate = %d");
		if(baud < 1)
			throw new AssertionError("Invalid baud rate.");
		setBaudRate(baud);
		
		String protocol = ChartUtils.parseString(lines.remove(), "protocol = %s");
		Protocol newProtocol = Protocol.fromString(protocol);
		if(newProtocol == null)
			throw new AssertionError("Invalid protocol.");
		setProtocol(newProtocol);
		
		int hz = ChartUtils.parseInteger(lines.remove(), "sample rate hz = %d");
		if(hz < 0)
			throw new AssertionError("Invalid sample rate.");
		setSampleRate(hz);
		setSampleRateAutomatic(hz == 0);
		
		String transmitType = ChartUtils.parseString(lines.remove(), "transmit data type = %s");
		TransmitDataType datatype = TransmitDataType.fromString(transmitType);
		if(datatype == null)
			throw new AssertionError("Invalid transmit type.");
		transmitDatatypeCombobox.setSelectedItem(datatype);
		
		String transmitData = ChartUtils.parseString(lines.remove(), "transmit data = %s");
		transmitDataTextfield.setUserText(transmitData);
		
		boolean appendsCR = ChartUtils.parseBoolean(lines.remove(), "transmit appends cr = %b");
		transmitAppendCRcheckbox.setSelected(appendsCR);
		
		boolean appendsLF = ChartUtils.parseBoolean(lines.remove(), "transmit appends lf = %b");
		transmitAppendLFcheckbox.setSelected(appendsLF);
		
		boolean repeats = ChartUtils.parseBoolean(lines.remove(), "transmit repeatedly = %b");
		transmitRepeatedlyCheckbox.setSelected(repeats);
		
		int repititionInterval = ChartUtils.parseInteger(lines.remove(), "transmit repitition interval milliseconds = %d");
		if(repititionInterval < 1)
			throw new AssertionError("Invalid transmit repitition interval.");
		transmitRepeatedlyMillisecondsTextfield.setNumber(repititionInterval);
		
		int saveCount = ChartUtils.parseInteger(lines.remove(), "transmit saved packet count = %d");
		if(saveCount < 0)
			throw new AssertionError("Invalid saved packet count.");
		
		while(saveCount-- > 0) {
			try {
				String label = lines.remove();
				String[] hexBytes = lines.remove().trim().split(" ");
				byte[] bytes = new byte[hexBytes.length];
				for(int i = 0; i < hexBytes.length; i++)
					bytes[i] = (byte) (Integer.parseInt(hexBytes[i], 16) & 0xFF);
				if(label.equals("") || bytes.length == 0)
					throw new Exception();
				transmitSavedPackets.add(new Packet(label, bytes));
			} catch(Exception e) {
				throw new AssertionError("Invalid saved packet.");
			}
		}
		
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
		file.println("\tport = "            + name.substring(6)); // skip past "UART: "
		file.println("\tbaud rate = "       + baudRate);
		file.println("\tprotocol = "        + protocol.toString());
		file.println("\tsample rate hz = "  + (sampleRateAutomatic ? 0 : sampleRate));
		
		file.println("\ttransmit data type = "                        + transmitDatatype.toString());
		file.println("\ttransmit data = "                             + transmitDataTextfield.getUserText());
		file.println("\ttransmit appends cr = "                       + transmitAppendCR);
		file.println("\ttransmit appends lf = "                       + transmitAppendLF);
		file.println("\ttransmit repeatedly = "                       + transmitRepeatedly);
		file.println("\ttransmit repitition interval milliseconds = " + transmitRepeatedlyMilliseconds);
		file.println("\ttransmit saved packet count = "               + transmitSavedPackets.size());
		transmitSavedPackets.forEach(packet -> {
			file.println("\t\t" + packet.label);
			file.println("\t\t" + ChartUtils.convertBytesToHexString(packet.bytes));
		});
		
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
	
	@Override public JPanel getUpdatedTransmitGUI() {
		
		if(isProtocolTc66()) {
			
			// special case: for the TC66, the user can only send some pre-defined packets
			JPanel gui = new JPanel(new MigLayout("hidemode 3, fillx, wrap 1, insets " + Theme.padding + ", gap " + Theme.padding, "[fill,grow]"));
			gui.setBorder(BorderFactory.createTitledBorder("TC66 (" + name + (connected ? "" : " - disconnected") + ")"));
			
			transmitSavedPackets.forEach(packet -> {
				JButton packetButton = new JButton(packet.label);
				packetButton.setHorizontalAlignment(SwingConstants.LEFT);
				packetButton.addActionListener(clicked -> transmitQueue.add(packet.bytes));
				packetButton.setEnabled(connected);
				gui.add(packetButton);
			});
			
			return gui;
			
		} else {
			
			boolean haveData = !transmitDataTextfield.getText().isEmpty() || transmitAppendCRcheckbox.isSelected() || transmitAppendLFcheckbox.isSelected();
			
			// show all of the widgets and saved packets
			JPanel gui = new JPanel(new MigLayout("hidemode 3, fillx, wrap 2, insets " + Theme.padding + ", gap " + Theme.padding));
			gui.setBorder(BorderFactory.createTitledBorder("Transmit to " + name + (connected ? "" : " (disconnected)")));
			
			gui.add(transmitDatatypeCombobox, "grow x");
			gui.add(transmitDataTextfield, "grow x");
			gui.add(transmitAppendCRcheckbox, "span 2, split 4");
			gui.add(transmitAppendLFcheckbox);
			gui.add(transmitRepeatedlyCheckbox);
			gui.add(transmitRepeatedlyMillisecondsTextfield, "grow x");
			gui.add(transmitSaveButton, "span 2, split 2, grow x");
			gui.add(transmitTransmitButton, "grow x");
			
			transmitDatatypeCombobox.setEnabled(connected);
			transmitDataTextfield.setEnabled(connected);
			transmitAppendCRcheckbox.setEnabled(connected && transmitDatatype == TransmitDataType.TEXT);
			transmitAppendLFcheckbox.setEnabled(connected && transmitDatatype == TransmitDataType.TEXT);
			transmitRepeatedlyCheckbox.setEnabled(connected && haveData);
			transmitRepeatedlyMillisecondsTextfield.setEnabled(connected && haveData);
			transmitSaveButton.setEnabled(connected && haveData);
			transmitTransmitButton.setEnabled(connected && haveData);
			
			transmitSavedPackets.forEach(packet -> {
				JButton sendButton = new JButton(packet.label);
				sendButton.setHorizontalAlignment(SwingConstants.LEFT);
				sendButton.addActionListener(clicked -> transmitQueue.add(packet.bytes));
				sendButton.setEnabled(connected);
				JButton removeButton = new JButton(Theme.removeSymbol);
				removeButton.setBorder(Theme.narrowButtonBorder);
				removeButton.addActionListener(click -> {
					transmitSavedPackets.remove(packet);
					SettingsView.instance.redraw(); // so this Transmit panel gets redrawn
				});
				removeButton.setEnabled(connected);
				gui.add(sendButton, "span 2, split 2, grow x, width 1:1:"); // setting min/pref width to 1px to ensure this button doesn't widen the panel
				gui.add(removeButton);
			});
			
			return gui;
			
		}
		
	}

}