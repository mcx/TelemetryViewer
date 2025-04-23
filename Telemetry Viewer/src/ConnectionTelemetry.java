import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.font.FontRenderContext;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.NetworkInterface;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Scanner;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.TitledBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellRenderer;

import net.miginfocom.swing.MigLayout;

public abstract class ConnectionTelemetry extends Connection {
	
	enum Protocol {
		CSV    { @Override public String toString() { return "CSV Mode";    } },
		BINARY { @Override public String toString() { return "Binary Mode"; } },
		TC66   { @Override public String toString() { return "TC66 Mode";   } };
	};
	
	private volatile String tc66firmwareVersion = "Firmware version: unknown";
	private volatile String tc66serialNumber = "Serial number: unknown";
	private volatile String tc66powerOnCount = "Power on count: unknown";
	
	protected Map<Integer, Field> fields = new TreeMap<Integer, Field>(); // <location, field>
	private volatile boolean fieldsDefined = false;
	
	public void setFieldsDefined(boolean isDefined) {
		fieldsDefined = isDefined;
		CommunicationView.instance.redraw(); // also redraw the bottom panel, because the Export button is enabled/disabled based on this
	}
	
	public boolean isFieldsDefined() {
		return fieldsDefined;
	}
	
	// connection settings
	protected WidgetTextfield<Integer> sampleRate;
	protected WidgetCombobox<Protocol> protocol;
	protected WidgetCombobox<String> baudRate; // for UART/Demo modes
	protected WidgetTextfield<Integer> portNumber; // for TCP/UDP modes
	
	private Timer sampleRateCalculator;
	protected volatile long previousSampleCountTimestamp = 0;
	protected volatile int  previousSampleCount = 0;
	protected volatile int  calculatedSamplesPerSecond = 0;
	
	public int getSampleRate() {
		int rate = sampleRate.get();
		if(rate == 0)
			rate = calculatedSamplesPerSecond;
		return (rate == 0) ? 1000 : rate; // because charts expect >0
	}
	
	// transmit settings
	protected WidgetCombobox<WidgetTextfield.Mode> transmitDatatype;
	protected WidgetTextfield<String> transmitData;
	protected WidgetCheckbox transmitAppendCR;
	protected WidgetCheckbox transmitAppendLF;
	protected WidgetCheckbox transmitRepeatedly;
	protected WidgetTextfield<Integer> transmitRepeatedlyMilliseconds;
	protected JButton transmitSaveButton;
	protected JButton transmitTransmitButton;
	
	protected Queue<byte[]> transmitQueue = new ConcurrentLinkedQueue<byte[]>();
	protected long nextRepititionTimestamp = 0;
	public record Packet(String label, byte[] bytes, String bytesAsHexString) {}
	protected List<Packet> transmitSavedPackets = new ArrayList<Packet>();
	
	public static String localIp = "[Local IP Address Unknown]";
	static {
		try {
			localIp = Collections.list(NetworkInterface.getNetworkInterfaces()).stream()
			          .filter( device -> !device.getDisplayName().contains("VMware"))
			          .filter( device -> !device.getDisplayName().contains("VPN"))
			          .flatMap(device -> Collections.list(device.getInetAddresses()).stream())
			          .filter(address -> address.isSiteLocalAddress())
			          .map(   address -> address.getHostAddress())
			          .collect(Collectors.joining(" or "))
			          .transform(result -> result.isEmpty() ? "[Local IP Address Unknown]" : result);
		} catch(Exception e) {}
	}
	
	/**
	 * Prepares, but does not connect to, a connection that can receive "normal telemetry" (a stream of numbers to visualize.)
	 */
	public ConnectionTelemetry() {
		
		// sample rate defaults to 0 (automatic mode)
		sampleRate = WidgetTextfield.ofInt(1, Integer.MAX_VALUE, 0, 0, "Automatic")
		                            .setPrefix("Sample Rate")
		                            .setSuffix("Hz")
		                            .setExportLabel("sample rate hz")
		                            .setToolTipText("<html>Number of telemetry packets sent to the PC each second.<br>Use 0 to have it automatically calculated.<br>If this number is inaccurate, things like the frequency domain chart will be inaccurate.</html>");
		
		// automatically calculate the sample rate when appropriate
		sampleRateCalculator = new Timer(1000, event -> {
			if(!sampleRate.is(0) || !isConnected() || !isFieldsDefined()) {
				// skip this iteration if not ready/applicable
				return;
			} else if(previousSampleCountTimestamp == 0) {
				// initialize automatic sample rate mode
				previousSampleCountTimestamp = Connections.importing ? Connections.getFirstTimestamp() : System.currentTimeMillis();
				previousSampleCount = getSampleCount();
			} else {
				// calculate the sample rate
				long currentTimestamp = Connections.importing ? Connections.getLastTimestamp() : System.currentTimeMillis();
				int  currentSampleCount = getSampleCount();
				long millisecondsDelta = currentTimestamp - previousSampleCountTimestamp;
				int sampleCountDelta = currentSampleCount - previousSampleCount;
				calculatedSamplesPerSecond = (int) Math.round((double) sampleCountDelta / ((double) millisecondsDelta / 1000.0));
				sampleRate.disableWithNumber(calculatedSamplesPerSecond);
				previousSampleCountTimestamp = currentTimestamp;
				previousSampleCount = currentSampleCount;
			}
		});
		sampleRateCalculator.start();
		
		// baud rate
		baudRate = new WidgetCombobox<String>(null,
		                                      List.of("9600 Baud",
		                                              "19200 Baud",
		                                              "38400 Baud",
		                                              "57600 Baud",
		                                              "115200 Baud",
		                                              "230400 Baud",
		                                              "460800 Baud",
		                                              "921600 Baud",
		                                              "1000000 Baud",
		                                              "2000000 Baud"),
		                                      "9600 Baud")
		               .setEditable(true)
		               .setExportLabel("speed")
		               .onChange((newValue, oldValue) -> {
		                   try {
		                       String text = newValue.trim();
		                       if(text.toLowerCase().endsWith("baud"))
		                           text = text.substring(0, text.length() - 4).trim();
		                       int rate = Integer.parseInt(text);
		                       if(rate > 0) {
		                           baudRate.set(rate + " Baud");
		                           return true; // valid baud rate
		                       } else {
		                           return false; // invalid baud rate
		                       }
		                   } catch(Exception e) {
		                       return false; // not a number
		                   }
		               });
		
		// TCP or UDP port number
		portNumber = WidgetTextfield.ofInt(1, 65535, 8080)
		                            .setPrefix("Port")
		                            .setExportLabel("server port")
		                            .setFixedWidth(9)
		                            .onChange((newValue, oldValue) -> {
		                            	// the port number must be unique among all connections
		                                List<Integer> usedPorts = Connections.telemetryConnections
		                                                                     .stream()
		                                                                     .filter(connection -> connection != this)
		                                                                     .filter(connection -> connection.name.is("TCP") || connection.name.is("UDP"))
		                                                                     .map(connection -> connection.portNumber.get())
		                                                                     .toList();
		                                if(!usedPorts.contains(newValue)) {
		                                    return true;
		                                } else {
		                                    int firstUnusedPort = IntStream.rangeClosed(8080, 65535)
		                                                                   .filter(number -> !usedPorts.contains(number))
		                                                                   .findFirst().getAsInt();
		                                    portNumber.set(firstUnusedPort);
		                                    return true;
		                                }
		                            });
		
		// transmitted data can be automatically repeated every n milliseconds
		transmitRepeatedly = new WidgetCheckbox("Repeat", false)
		                         .setExportLabel("transmit repeatedly");
		
		transmitRepeatedlyMilliseconds = WidgetTextfield.ofInt(1, Integer.MAX_VALUE, 1000)
		                                                .setSuffix("ms")
		                                                .setExportLabel("transmit repitition interval milliseconds");
		
		// packets can be saved to JButtons
		transmitSaveButton = new JButton("Save");
		transmitSaveButton.addActionListener(event -> {
			byte[] bytes = transmitData.getAsBytes(transmitAppendCR.get(), transmitAppendLF.get());
			String label = transmitDatatype.get().toString() + ": " + transmitData.getAsText(transmitAppendCR.get(), transmitAppendLF.get());
			String bytesAsHexString = transmitData.getAsHexText(transmitAppendCR.get(), transmitAppendLF.get());
			if(transmitSavedPackets.stream().noneMatch(packet -> packet.label.equals(label))) {
				transmitSavedPackets.add(new Packet(label, bytes, bytesAsHexString));
				SettingsView.instance.redraw(); // so this TX GUI gets redrawn
			}
			transmitData.set("");
		});
		
		// transmit button for sending the data once
		transmitTransmitButton = new JButton("Transmit");
		transmitTransmitButton.addActionListener(event -> transmitQueue.add(transmitData.getAsBytes(transmitAppendCR.get(), transmitAppendLF.get())));
		
		// data provided by the user
		transmitData = WidgetTextfield.ofText("")
		                              .setExportLabel("transmit data")
		                              .onEnter(event -> transmitTransmitButton.doClick())
		                              .onChange((newText, oldText) -> {
		                                   boolean haveData = transmitAppendCR.get() || transmitAppendLF.get() || !newText.isEmpty();
		                                   transmitRepeatedly.setEnabled(haveData);
		                                   transmitRepeatedlyMilliseconds.setEnabled(haveData);
		                                   transmitSaveButton.setEnabled(haveData);
		                                   transmitTransmitButton.setEnabled(haveData);
		                                   return true;
		                              })
		                              .onIncompleteChange(text -> {
		                                  boolean haveData = transmitAppendCR.get() || transmitAppendLF.get() || !text.isEmpty();
		                                  transmitRepeatedly.setEnabled(haveData);
		                                  transmitRepeatedlyMilliseconds.setEnabled(haveData);
		                                  transmitSaveButton.setEnabled(haveData);
		                                  transmitTransmitButton.setEnabled(haveData);
		                              });
		
		// for text mode, \r or \n can be automatically appended
		transmitAppendCR = new WidgetCheckbox("CR", false)
		                       .setExportLabel("transmit appends cr")
		                       .onChange(isAppened -> {
		                            boolean haveData = transmitAppendCR.get() || transmitAppendLF.get() || !transmitData.get().isEmpty();
		                            transmitRepeatedly.setEnabled(haveData);
		                            transmitRepeatedlyMilliseconds.setEnabled(haveData);
		                            transmitSaveButton.setEnabled(haveData);
		                            transmitTransmitButton.setEnabled(haveData);
		                       });
		
		transmitAppendLF = new WidgetCheckbox("LF", false)
		                       .setExportLabel("transmit appends lf")
		                       .onChange(isAppened -> {
		                            boolean haveData = transmitAppendCR.get() || transmitAppendLF.get() || !transmitData.get().isEmpty();
		                            transmitRepeatedly.setEnabled(haveData);
		                            transmitRepeatedlyMilliseconds.setEnabled(haveData);
		                            transmitSaveButton.setEnabled(haveData);
		                            transmitTransmitButton.setEnabled(haveData);
		                       });
		
		// which data format the user will provide
		transmitDatatype = new WidgetCombobox<WidgetTextfield.Mode>(null, Arrays.asList(WidgetTextfield.Mode.values()), WidgetTextfield.Mode.TEXT)
		                       .removeValue(WidgetTextfield.Mode.INTEGER) // only support TEXT / HEX / BINARY modes
		                       .removeValue(WidgetTextfield.Mode.FLOAT)
		                       .setExportLabel("transmit data type")
		                       .onChange((newDatatype, oldDatatype) -> {
		                           byte[] oldDataBytes = transmitData.getAsBytes(transmitAppendCR.get(), transmitAppendLF.get());
		                           transmitData.setDataType(newDatatype);
		                           transmitAppendCR.setEnabled(newDatatype == WidgetTextfield.Mode.TEXT);
		                           transmitAppendLF.setEnabled(newDatatype == WidgetTextfield.Mode.TEXT);
		                           if(newDatatype == WidgetTextfield.Mode.TEXT) {
		                               byte[] bytes = oldDataBytes;
		                               boolean lf =      bytes.length > 0 && bytes[bytes.length - 1] == '\n';
		                               boolean cr = lf ? bytes.length > 1 && bytes[bytes.length - 2] == '\r' :
		                                                 bytes.length > 0 && bytes[bytes.length - 1] == '\r';
		                               transmitAppendCR.set(cr);
		                               transmitAppendLF.set(lf);
		                               int bytesToSkip = (cr && lf) ? 2 : cr ? 1 : lf ? 1 : 0;
		                               byte[] textBytes = Arrays.copyOf(bytes, bytes.length - bytesToSkip);
		                               transmitData.setFromBytes(textBytes);
		                           } else {
		                               transmitAppendCR.set(false);
		                               transmitAppendLF.set(false);
		                               transmitData.setFromBytes(oldDataBytes);
		                           }
		                           return true;
		                       });
		
		// communication protocol
		protocol = new WidgetCombobox<Protocol>(null, Arrays.asList(Protocol.values()), Protocol.CSV)
		               .setExportLabel("protocol")
		               .onChange((newProtocol, oldProtocol) -> {
		            	   // this will be called automatically after constructing, that event should be ignored because there is no change
		            	   if(newProtocol == oldProtocol)
		            		   return true;
		            	   
		                   // reset everything when changing protocols
		                   removeAllFields();
		                   dsPanel = null;
		                   transmitQueue.clear();
		                   transmitSavedPackets.clear();
		                   nextRepititionTimestamp = 0;
		                   transmitDatatype.set(WidgetTextfield.Mode.TEXT);
		                   transmitAppendCR.set(false);
		                   transmitAppendLF.set(false);
		                   
		                   if(newProtocol == Protocol.TC66) {
		                       new Field(this).setLocation(0 ).setName("Voltage"         ).setColor(new Color(0x00FF00)).setUnit("V"      ).insert();
		                       new Field(this).setLocation(1 ).setName("Current"         ).setColor(new Color(0x00FFFF)).setUnit("A"      ).insert();
		                       new Field(this).setLocation(2 ).setName("Power"           ).setColor(new Color(0xFF00FF)).setUnit("W"      ).insert();
		                       new Field(this).setLocation(3 ).setName("Resistance"      ).setColor(new Color(0x00FFFF)).setUnit("\u2126" ).insert();
		                       new Field(this).setLocation(4 ).setName("Group 0 Capacity").setColor(new Color(0xFF0000)).setUnit("mAh"    ).insert();
		                       new Field(this).setLocation(5 ).setName("Group 0 Energy"  ).setColor(new Color(0xFFFF00)).setUnit("mWh"    ).insert();
		                       new Field(this).setLocation(6 ).setName("Group 1 Capacity").setColor(new Color(0xFF0000)).setUnit("mAh"    ).insert();
		                       new Field(this).setLocation(7 ).setName("Group 1 Energy"  ).setColor(new Color(0xFFFF00)).setUnit("mWh"    ).insert();
		                       new Field(this).setLocation(8 ).setName("PCB Temperature" ).setColor(new Color(0xFFFF00)).setUnit("Degrees").insert();
		                       new Field(this).setLocation(9 ).setName("D+ Voltage"      ).setColor(new Color(0x8000FF)).setUnit("V"      ).insert();
		                       new Field(this).setLocation(10).setName("D- Voltage"      ).setColor(new Color(0x0000FF)).setUnit("V"      ).insert();
		                       setFieldsDefined(true);
		                       sampleRate.set(2).forceDisabled(true);
		                       baudRate.set("9600 Baud").forceDisabled(true);
		                       transmitData.set("getva");
		                       transmitRepeatedly.set(true);
		                       transmitRepeatedlyMilliseconds.set(200);
		                       transmitSavedPackets.add(new Packet("Rotate Display",  "rotat".getBytes(), "72 6F 74 61 74"));
		                       transmitSavedPackets.add(new Packet("Previous Screen", "lastp".getBytes(), "6C 61 73 74 70"));
		                       transmitSavedPackets.add(new Packet("Next Screen",     "nextp".getBytes(), "6E 65 78 74 70"));
		                   } else {
		                       sampleRate.set(0).forceDisabled(false);
		                       baudRate.forceDisabled(false);
		                       transmitData.set("");
		                       transmitRepeatedly.set(false);
		                       transmitRepeatedlyMilliseconds.set(1000);
		                   }
		                   
		                   SettingsView.instance.redraw(); // because the TX GUI is different for TC66 and non-TC66 protocols
		                   return true;
		               });
		
	}
	
	/**
	 * @return    A user-friendly String describing this ConnectionTelemetry.
	 *            If there is >1 ConnectionTelemetry:
	 *                This will be shown to the user by WidgetDatasets, and will also be used when exporting/importing.
	 *            Therefore this String *must* uniquely identify this ConnectionTelemetry among *every* ConnectionTelemetry!
	 */
	@Override public String toString() {
		String text = name.get();
		if(text.equals("TCP") || text.equals("UDP"))
			text += " :" + portNumber.get();
		return text;
	}
	
	JPanel dsPanel;
	Field pending;
	JTable dataStructureTable;
	JScrollPane scrollableRegion;
	
	private void createPendingFieldAndRepopulatePanel(Field deriveFrom, JTabbedPane exampleCodePane) {
		
		// create and configure the pending dataset
		pending = new Field(this)
		              .onInsert(errorMessage -> {
		                   if(errorMessage != null)
		                       JOptionPane.showMessageDialog(null, errorMessage, "Unable to Add the Dataset", JOptionPane.ERROR_MESSAGE);
		                   else
		                       createPendingFieldAndRepopulatePanel(pending, exampleCodePane);
		               });
		
		if(deriveFrom != null) {
			int location = (isFieldAllowed(pending, deriveFrom.location.get(), Field.Type.UINT8) == null) ? deriveFrom.location.get() :
			                                                                                                getFirstAvailableLocation();
			pending.setLocation(location)
			       .setType(deriveFrom.type.get())
			       .setName(deriveFrom.name.get())
			       .setColor(deriveFrom.color.get())
			       .setUnit(deriveFrom.unit.get())
			       .setScalingFactors(deriveFrom.scalingFactorA.get(), deriveFrom.scalingFactorB.get());
		}
		
		if(protocol.is(Protocol.CSV))
			pending.type.setVisible(false);
		
		// repopulate the panel
		dsPanel.removeAll();
		
		dsPanel.add(new JLabel((this instanceof ConnectionTelemetryDemo) ?
			"<html><font size=+0><b>Data Structure Definition: (Not Editable in Demo Mode)</b></font></html>" :
			"<html><font size=+0><b>Data Structure Definition:</b></font></html>"), "span");

		pending.location.appendTo(dsPanel, "gapafter " + 2 * Theme.padding);
		pending.type.appendTo(dsPanel, "gapafter " + 2 * Theme.padding);
		pending.name.appendTo(dsPanel, "gapafter " + 2 * Theme.padding);
		pending.color.appendTo(dsPanel, "gapafter " + 2 * Theme.padding);
		pending.unit.appendTo(dsPanel, "gapafter " + 2 * Theme.padding);
		pending.scalingFactorA.appendTo(dsPanel, "");
		dsPanel.add(pending.equalsLabel, "");
		pending.scalingFactorB.appendTo(dsPanel, "gapafter " + 2 * Theme.padding);
		dsPanel.add(pending.addButton, "pushx, align right");
		dsPanel.add(pending.doneButton, "wrap");
		
		dataStructureTable.revalidate();
		dataStructureTable.repaint();
		
		scrollableRegion.setViewportView(dataStructureTable);
		dsPanel.add(scrollableRegion, "grow, span");
		
		exampleCodePane.removeAll();
		getExampleCode().forEach((tabName, sourceCode) -> {
			JTextArea code = new JTextArea(sourceCode);
			code.setEditable(false);
			code.setTabSize(4);
			code.setFont(new Font("Consolas", Font.PLAIN, dsPanel.getFont().getSize()));
			code.setCaretPosition(0); // scroll back to the top
			exampleCodePane.add(tabName, new JScrollPane(code));
		});
		
		dsPanel.add(new JLabel("<html><font size=+0><b>Example Firmware / Software:</b></font></html>"), "span, gaptop 10px");
		dsPanel.add(exampleCodePane, "grow, span, width 100%"); // 100% ensures the prefWidth getting massive doesn't shrink the other widgets
		
		if(!pending.location.is(-1) && !(this instanceof ConnectionTelemetryDemo))
			SwingUtilities.invokeLater(() -> pending.name.requestFocus());
		
		dsPanel.revalidate();
		dsPanel.repaint();
		
	}
	
	@SuppressWarnings("serial")
	final public JPanel getDataStructureGui() {
		
		if(dsPanel != null) {
			SwingUtilities.invokeLater(() -> pending.name.requestFocus());
			return dsPanel;
		}
		
		dsPanel = new JPanel(new MigLayout("hidemode 3, fill, insets " + Theme.padding + ", gap " + Theme.padding, "", "[][sgy,fill][50%][][50%]0")); // "sgy,fill" stretches the components to all have the same height
		
		dataStructureTable = new JTable(new AbstractTableModel() {
			@Override public int getRowCount()                { return fields.size(); }
			@Override public int getColumnCount()             { return 6;             }
			@Override public String getColumnName(int column) { return switch(column) { case 0  -> protocol.is(Protocol.CSV) ? "Column Number" : "Byte Offset, Data Type";
			                                                                            case 1  -> "Name";
			                                                                            case 2  -> "Color";
			                                                                            case 3  -> "Unit";
			                                                                            case 4  -> "Scaling Factor";
			                                                                            case 5  -> "";
			                                                                            default -> "Error";};}
			@Override public Object getValueAt(int row, int column) {
				Field field = getFieldByIndex(row);
				if(field.isSyncWord()) {
					return switch(column) { case 0  -> "0, [Sync Word]";
					                        case 1  -> field.name.get();
					                        default -> "";};
				} else if(field.isDataset()) {
					return switch(column) { case 0  -> protocol.is(Protocol.CSV) ? Integer.toString(field.location.get()) : field.location.get() + ", " + field.type.get().toString();
					                        case 1  -> field.name.get();
					                        case 2  -> "<html><font color=\"rgb(" + field.color.get().getRed() + "," + field.color.get().getGreen() + "," + field.color.get().getBlue() + ")\">\u25B2</font></html>";
					                        case 3  -> field.isBitfield ? "" : field.unit.get();
					                        case 4  -> field.isBitfield ? "" : String.format("%3.3f = %3.3f %s", field.scalingFactorA.get(), field.scalingFactorB.get(), field.unit.get());
					                        default -> "";};
				} else if(field.isChecksum()) {
					return switch(column) { case 0  -> field.location.get() + ", [Checksum]";
					                        case 1  -> field.type.get().toString();
					                        default -> "";};
				} else {
					return ""; // should never get here
				}
			}
		});
		dataStructureTable.setRowHeight((int) (dsPanel.getFont().getStringBounds("Abc", new FontRenderContext(null, true, true)).getHeight() * 1.5));
		dataStructureTable.getColumn("").setCellRenderer(new TableCellRenderer() {
			@Override public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
				JButton b = new JButton("Remove");
				b.setEnabled(!(ConnectionTelemetry.this instanceof ConnectionTelemetryDemo));
				return b;
			}
		});
		scrollableRegion = new JScrollPane(dataStructureTable);
		scrollableRegion.getVerticalScrollBar().setUnitIncrement(10);
		
		JTabbedPane exampleCodePane = new JTabbedPane();
		
		dataStructureTable.addMouseListener(new MouseListener() {
			@Override public void mousePressed(MouseEvent e) {
				if(ConnectionTelemetry.this instanceof ConnectionTelemetryDemo)
					return;
				Field field = getFieldByIndex(dataStructureTable.getSelectedRow());
				String message = field.type.get().isSyncWord() ? "Remove the sync word?" :
				                 field.type.get().isDataset()  ? "Remove " + field.name.get() + "?" :
				                                                 "Remove the checksum?";
				if(JOptionPane.showConfirmDialog(dsPanel, message, message, JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
					removeField(field.location.get());
					createPendingFieldAndRepopulatePanel(field, exampleCodePane);
				}
				dataStructureTable.clearSelection();
			}
			
			// clear the selection again, in case the user click-and-dragged over the table
			@Override public void mouseReleased(MouseEvent e) { dataStructureTable.clearSelection(); }
			@Override public void mouseExited(MouseEvent e)   { }
			@Override public void mouseEntered(MouseEvent e)  { }
			@Override public void mouseClicked(MouseEvent e)  { }
		});
		
		createPendingFieldAndRepopulatePanel(null, exampleCodePane);
		
		return dsPanel;
		
	}
	
	abstract Map<String, String> getExampleCode(); // <tabName, sourceCode>
	
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
		
		getDatasetsList().forEach(dataset -> dataset.floats.clear());
		clearTimestamps();
		
		CommunicationView.instance.redraw();
		OpenGLChartsView.instance.setPlayLive();
		
	}
	
	private record ParsedData(int packetCount,      // number of valid parsed telemetry packets
	                          int[] byteCount,      // number of bytes parsed per telemetry packet
	                          long[] timestamps,    // timestamp from each packet
	                          float[][] samples) {} // [datasetN][sampleN]
	
	private class ImportWorker implements Callable<ParsedData> {
		
		private final int datasetsCount;
		private final List<String> lines;
		private long[] timestamps;
		private float[][] samples;
		private int packetCount;
		private int[] byteCount;
		
		public ImportWorker(int datasetCount, List<String> lines) {
			this.datasetsCount = datasetCount;
			this.lines = lines;
			timestamps = new long[lines.size()];
			samples = new float[datasetCount][lines.size()];
			packetCount = 0;
			byteCount = new int[lines.size()];
		}

		@Override public ParsedData call() throws Exception {
			try {
				lines.forEach(line -> {
					String[] columns = line.split(",");
					timestamps[packetCount] = Long.parseLong(columns[1]);
					for(int datasetN = 0; datasetN < datasetsCount; datasetN++)
						samples[datasetN][packetCount] = Float.parseFloat(columns[datasetN + 2]);
					byteCount[packetCount] = line.length() + 2; // assuming each char is 1 byte, and EOL is 2 bytes
					packetCount++;
				});
			} catch(NumberFormatException e) {
				// ending early
			}
			
			return new ParsedData(packetCount, byteCount, timestamps, samples);
		}
		
	}
	
	@Override public void connectToFile(String path, long firstTimestamp, long beginImportingTimestamp, AtomicLong completedByteCount) {
		
		receiverThread = new Thread(() -> {
			
			try (FileReader file = new FileReader(path, StandardCharsets.UTF_8); BufferedReader buffer = new BufferedReader(file)) {
				
				setStatus(Status.CONNECTED, false);
				previousSampleCountTimestamp = 0;
				previousSampleCount = 0;
				int sampleNumber = getSampleCount();
				List<Field> datasets = getDatasetsList(); // cache a list of the datasets
				int datasetsCount = datasets.size();
				
				// sanity checks
				String line = buffer.readLine();
				if(line == null) {
					disconnect("The CSV file is empty.", false);
					return;
				}
				
				String[] columns = line.split(",");
				if(columns.length != datasetsCount + 2) {
					disconnect("The CSV file header does not match the current data structure.", false);
					return;
				}
				
				boolean correctColumnLabels = true;
				if(!columns[0].startsWith("Sample Number"))  correctColumnLabels = false;
				if(!columns[1].startsWith("UNIX Timestamp")) correctColumnLabels = false;
				for(int datasetN = 0; datasetN < datasetsCount; datasetN++) {
					Field d = datasets.get(datasetN);
					if(!columns[datasetN + 2].equals(d.name.get() + " (" + d.unit.get() + ")"))
						correctColumnLabels = false;
				}
				if(!correctColumnLabels) {
					disconnect("The CSV file header does not match the current data structure.", false);
					return;
				}
				completedByteCount.addAndGet((long) (line.length() + 2)); // assuming each char is 1 byte, and EOL is 2 bytes

				// parse and import the packets
				final int THREAD_COUNT = Runtime.getRuntime().availableProcessors();
				final int LINES_PER_THREAD = 1024;
				ExecutorService pool = Executors.newFixedThreadPool(THREAD_COUNT);
				Queue<Future<ParsedData>> futures = new LinkedList<Future<ParsedData>>();
				
				while(true) {
					
					if(!isConnected())
						break;
					
					for(int threadN = 0; threadN < THREAD_COUNT; threadN++) {
						List<String> lines = new ArrayList<String>(LINES_PER_THREAD);
						for(int lineN = 0; lineN < LINES_PER_THREAD; lineN++) {
							line = buffer.readLine();
							if(line == null)
								break;
							lines.add(line);
						}
						futures.add(pool.submit(new ImportWorker(datasetsCount, lines)));
					}
					
					while(!futures.isEmpty()) {
						ParsedData data = futures.remove().get();
						for(int packetN = 0; packetN < data.packetCount; packetN++) {
							if(sampleNumber == Integer.MAX_VALUE)
								throw new Exception();
							if(Connections.realtimeImporting) {
								long delay = (data.timestamps[packetN] - firstTimestamp) - (System.currentTimeMillis() - beginImportingTimestamp);
								if(delay > 0)
									try { Thread.sleep(delay); } catch(Exception e) { }
							}
							for(int datasetN = 0; datasetN < datasetsCount; datasetN++)
								datasets.get(datasetN).setConvertedSample(sampleNumber, data.samples[datasetN][packetN]);
							sampleNumber++;
							incrementSampleCountWithTimestamp(1, data.timestamps[packetN]);
							completedByteCount.addAndGet(data.byteCount[packetN]);
						}
						if(data.packetCount != data.timestamps.length)
							throw new Exception(); // an error occurred while parsing
					}
					
					if(line == null)
						break; // reached end of file
					
				}
				
				// done
				disconnect(null, false);
				
			} catch (IOException e) {
				disconnect("Unable to open the CSV Log file.", false);
			} catch (InterruptedException e) {
				disconnect(null, false);
			} catch (Exception e) {
				disconnect("Error while parsing the CSV Log file.", false);
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
		
		List<Field> list = getDatasetsList();
		int datasetsCount = list.size();
		int sampleCount = getSampleCount();
		
		try {
			
			PrintWriter logFile = new PrintWriter(path + ".csv", "UTF-8");
			
			// first line is the header
			logFile.print("Sample Number (" + sampleRate.get() + " samples per second),UNIX Timestamp (Milliseconds since 1970-01-01)");
			for(int i = 0; i < datasetsCount; i++) {
				Field d = getDatasetByIndex(i);
				logFile.print("," + d.name.get() + " (" + d.unit.get() + ")");
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
				LongBuffer buffer = getTimestampsBuffer(firstSampleNumber, firstSampleNumber + sampleCount - 1, createTimestampsCache());
				for(int i = 0; i < sampleCount; i++)
					text[i] = Long.toString(buffer.get());
			} else {
				// other columns are the datasets
				Field dataset = getDatasetByIndex(csvColumnNumber - 2);
				StorageFloats.Cache cache = dataset.createCache();
				FloatBuffer buffer = dataset.getSamplesBuffer(firstSampleNumber, firstSampleNumber + (sampleCount - 1), cache);
				for(int i = 0; i < sampleCount; i++)
					text[i] = Float.toString(buffer.get());
			}
			
			return text;
			
		}
		
	}
	
	@Override public void dispose() {
		
		if(!isDisconnected())
			disconnect(null, true);
		
		Main.window.remove(getDataStructureGui()); // ensure the DS GUI is no longer on screen
		
		sampleRateCalculator.stop();
		removeAllFields();
		timestamps.dispose();
		
		// if this is the only connection, remove all charts, because there may be a timeline chart
		if(Connections.allConnections.size() == 1)
			Charts.removeAll();
		
	}
	
	/**
	 * Spawns a new thread that starts processing received telemetry packets.
	 * 
	 * @param stream    Bytes of received telemetry.
	 */
	protected void startProcessingTelemetry(SharedByteStream stream) {
		
		processorThread = new Thread(() -> {
			
			// wait for the data structure to be defined
			while(!isFieldsDefined()) {
				try {
					Thread.sleep(1);
				} catch (InterruptedException e) {
					return;
				}
			}
			
			// cache a list of the datasets
			List<Field> datasets = getDatasetsList();
			
			// if no telemetry after 100ms, notify the user
			String waitingForTelemetry = (this instanceof ConnectionTelemetryUART) ? name.get().substring(6) + " is connected. Send telemetry." :
			                             (this instanceof ConnectionTelemetryTCP)  ? "The TCP server is running. Send telemetry to " + localIp + ":" + portNumber.get() :
			                             (this instanceof ConnectionTelemetryUDP)  ? "The UDP listener is running. Send telemetry to " + localIp + ":" + portNumber.get() :
			                                                                         "";
			String receivingTelemetry  = (this instanceof ConnectionTelemetryUART) ? name.get().substring(6) + " is connected and receiving telemetry." :
			                             (this instanceof ConnectionTelemetryTCP)  ? "The TCP server is running and receiving telemetry." :
			                             (this instanceof ConnectionTelemetryUDP)  ? "The UDP listener is running and receiving telemetry." :
			                                                                         "";
			int oldSampleCount = getSampleCount();
			Timer t = new Timer(100, event -> {
				
				if((this instanceof ConnectionTelemetryDemo) || (this instanceof ConnectionTelemetryStressTest))
					return;
				
				if(isConnected()) {
					if(getSampleCount() == oldSampleCount)
						Notifications.showHintUntil(waitingForTelemetry, () -> getSampleCount() > oldSampleCount, true);
					else
						Notifications.printInfo(receivingTelemetry);
				}
				
			});
			t.setRepeats(false);
			t.start();
			
			if(protocol.is(Protocol.CSV)) {
				
				// prepare for CSV mode
				stream.setPacketSize(0, 0, (byte) 0);
				int maxLocation = 0;
				for(Field d : datasets)
					if(d.location.get() > maxLocation)
						maxLocation = d.location.get();
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
						if(sampleNumber == Integer.MAX_VALUE) {
							disconnect(maxSampleCountErrorMessage, false);
							throw new InterruptedException();
						}
						
						for(Field d : datasets)
							d.setSample(sampleNumber, numberForLocation[d.location.get()]);
						incrementSampleCount(1);
						
					} catch(NumberFormatException | NullPointerException | ArrayIndexOutOfBoundsException e1) {
						
						Notifications.showFailureForMilliseconds("A corrupt or incomplete telemetry packet was received:\n\"" + line + "\"", 5000, false);
						
					} catch(InterruptedException e2) {
						
						return;
						
					}
					
				}
				
			} else if(protocol.is(Protocol.BINARY)) {
				
				// prepare for binary mode
				final Field syncWordField = fields.values().stream().filter(Field::isSyncWord).findFirst().orElse(null);
				final Field checksumField = fields.values().stream().filter(Field::isChecksum).findFirst().orElse(null);
				final Field.Type checksumProcessor = (checksumField == null) ? null : checksumField.type.get();
				final int syncWordByteCount = (syncWordField == null) ? 0 : syncWordField.type.get().getByteCount();
				final byte syncWord         = (syncWordField == null) ? 0 : (byte) Integer.parseInt(syncWordField.name.get().substring(2), 16);
				final int packetByteCount = (checksumField != null) ? checksumField.location.get() + checksumField.type.get().getByteCount() :
				                                                      datasets.get(datasets.size() - 1).location.get() + datasets.get(datasets.size() - 1).type.get().getByteCount();
				final int datasetsCount = datasets.size();
				final Field dataset[] = new Field[datasetsCount];
				final Field.Type processorForDataset[] = new Field.Type[datasetsCount];
				final int locationForDataset[] = new int[datasetsCount];
				for(int i = 0; i < datasetsCount; i++) {
					dataset[i] = datasets.get(i);
					processorForDataset[i] = datasets.get(i).type.get();
					locationForDataset[i] = datasets.get(i).location.get();
				}
				
				stream.setPacketSize(packetByteCount, syncWordByteCount, syncWord);
				
				// use multiple threads to process incoming data in parallel, with each thread parsing up to 8 blocks at a time
				Phaser phaser = new Phaser(1);
				int phase = -1;
				final int THREAD_COUNT = Runtime.getRuntime().availableProcessors();
				final int MAX_BLOCK_COUNT_PER_THREAD = 8;
				ExecutorService pool = Executors.newFixedThreadPool(THREAD_COUNT);
				Parser[] parsers = new Parser[THREAD_COUNT];
				for(int i = 0; i < THREAD_COUNT; i++)
					parsers[i] = new Parser(datasets, packetByteCount, MAX_BLOCK_COUNT_PER_THREAD, phaser, syncWordByteCount, syncWord, checksumProcessor);
				
				while(true) {
					
					try {
						
						if(Thread.interrupted())
							throw new InterruptedException();
						
						SharedByteStream.DataBuffer data = stream.getBytes();
						
						// ensure room exists for the new samples
						int sampleNumber = getSampleCount();
						int maxAllowedPacketCount = Integer.MAX_VALUE - sampleNumber;
						int receivedPacketCount = (data.end - data.offset + 1) / packetByteCount;
						int packetCount = Integer.min(receivedPacketCount, maxAllowedPacketCount);
						if(sampleNumber == Integer.MAX_VALUE) {
							disconnect(maxSampleCountErrorMessage, false);
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
							if(checksumProcessor != null && !checksumProcessor.testChecksum(data.buffer, data.offset, packetByteCount, syncWordByteCount)) {
								data.offset += packetByteCount;
								abort = true;
								break;
							}
							
							for(int i = 0; i < datasetsCount; i++) {
								float rawNumber = processorForDataset[i].parse(data.buffer, data.offset + locationForDataset[i]);
								dataset[i].setSample(sampleNumber, rawNumber);
							}
							data.offset += packetByteCount;
							incrementSampleCount(1);
							sampleNumber++;
							samplesBeforeNextBlock--;
							packetCount--;
							
						}
						
						if(abort) {
							stream.releaseBytes(data);
							continue;
						}
						
						// part 2 of 3: process blocks of packets in parallel if block aligned and more than one full block remaining
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
								packetCount -= threadPacketCount;
								
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
						while(packetCount > 0) {
						
							if(syncWordByteCount > 0 && data.buffer[data.offset] != syncWord) {
								abort = true;
								break;
							}
							if(checksumProcessor != null && !checksumProcessor.testChecksum(data.buffer, data.offset, packetByteCount, syncWordByteCount)) {
								data.offset += packetByteCount;
								abort = true;
								break;
							}
							
							for(int i = 0; i < datasetsCount; i++) {
								float rawNumber = processorForDataset[i].parse(data.buffer, data.offset + locationForDataset[i]);
								dataset[i].setSample(sampleNumber, rawNumber);
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
				
			} else if(protocol.is(Protocol.TC66)) {
				
				int packetLength = 64;
				stream.setPacketSize(packetLength, 0, (byte) 0);
				
				// for some reason the TC66/TC66C uses AES encryption when sending measurements to the PC
				// this key is NOT a secret and is intentionally in this publicly accessible source code
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
					disconnect("Unable to prepare TC66 decryption logic.", false);
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
//									String device          = new String(new char[] {(char) currentPacket[4], (char) currentPacket[5], (char) currentPacket[6],  (char) currentPacket[7]});
									String firmwareVersion = new String(new char[] {(char) currentPacket[8], (char) currentPacket[9], (char) currentPacket[10], (char) currentPacket[11]});
									long serialNumber      = getUint32.apply(currentPacket, 12);
									long powerOnCount      = getUint32.apply(currentPacket, 44);
									tc66firmwareVersion = "Firmware version: " + firmwareVersion;
									tc66serialNumber = "Serial number: " + serialNumber;
									tc66powerOnCount = "Power on count: " + powerOnCount;
									SettingsView.instance.redraw();
//									Notifications.printInfo(String.format("Device: %s, Firmware Version: %s, Serial Number: %d, Power On Count: %d", device, firmwareVersion, serialNumber, powerOnCount));
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
								float dPlusVoltage     = getUint32.apply(currentPacket, 96)  / 100f; // converting to volts
								float dMinusVoltage    = getUint32.apply(currentPacket, 100) / 100f; // converting to volts
								
								// populate the datasets
								int sampleNumber = getSampleCount();
								if(sampleNumber == Integer.MAX_VALUE) {
									disconnect(maxSampleCountErrorMessage, false);
									throw new InterruptedException();
								}
								
								datasets.get(0).setConvertedSample (sampleNumber, voltage);
								datasets.get(1).setConvertedSample (sampleNumber, current);
								datasets.get(2).setConvertedSample (sampleNumber, power);
								datasets.get(3).setConvertedSample (sampleNumber, resistance);
								datasets.get(4).setConvertedSample (sampleNumber, (float) group0mah);
								datasets.get(5).setConvertedSample (sampleNumber, (float) group0mwh);
								datasets.get(6).setConvertedSample (sampleNumber, (float) group1mah);
								datasets.get(7).setConvertedSample (sampleNumber, (float) group1mwh);
								datasets.get(8).setConvertedSample (sampleNumber, (float) temperature);
								datasets.get(9).setConvertedSample (sampleNumber, dPlusVoltage);
								datasets.get(10).setConvertedSample(sampleNumber, dMinusVoltage);
								incrementSampleCount(1);
							} else {
								// got a sub packet that is out of order, so skip over it so we can re-align
								packets.count--;
								packets.offset += packetLength;
								continue;
							}
							
						}
						
					} catch (BadPaddingException | IllegalBlockSizeException e) {
					
						Notifications.showFailureForMilliseconds("Error while decrypting a packet from the TC66.", 5000, true);
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
		
		private final int datasetsCount;
		private final Field dataset[];
		private final Field.Type processorForDataset[];
		private final int locationForDataset[];
		private final float conversionFactorForDataset[];
		
		private final int packetByteCount;
		private final float[][] minimumValue;   // [blockN][datasetN]
		private final float[][] maximumValue;   // [blockN][datasetN]
		private final Phaser phaser;
		
		private final int syncWordByteCount;
		private final byte syncWord;
		private final Field.Type checksumProcessor;
		
		/**
		 * Initializes this object, but does not start to parse any data.
		 * Before calling run(), you must call configure().
		 * 
		 * @param datasets           List of Datasets that receive the parsed data.
		 * @param packetByteCount    Number of bytes in each packet INCLUDING the sync word and optional checksum.
		 * @param maxBlockCount      Maximum number of blocks that should be parsed by this object.
		 * @param phaser             Phaser to await on before incrementing the sample count.
		 */
		public Parser(List<Field> datasets, int packetByteCount, int maxBlockCount, Phaser phaser, int syncWordByteCount, byte syncWord, Field.Type checksumProcessor) {
			
			datasetsCount = datasets.size();
			dataset = new Field[datasetsCount];
			processorForDataset = new Field.Type[datasetsCount];
			locationForDataset = new int[datasetsCount];
			conversionFactorForDataset = new float[datasetsCount];
			for(int i = 0; i < datasetsCount; i++) {
				dataset[i] = datasets.get(i);
				processorForDataset[i] = datasets.get(i).type.get();
				locationForDataset[i] = datasets.get(i).location.get();
				conversionFactorForDataset[i] = datasets.get(i).conversionFactor;
			}
			
			this.packetByteCount = packetByteCount;
			this.minimumValue = new float[maxBlockCount][datasetsCount];
			this.maximumValue = new float[maxBlockCount][datasetsCount];
			this.phaser = phaser;
			
			this.syncWordByteCount = syncWordByteCount;
			this.syncWord = syncWord;
			this.checksumProcessor = checksumProcessor;
			
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
					slots[datasetN] = dataset[datasetN].getSlot(firstSampleNumber + (blockN * StorageFloats.BLOCK_SIZE));
				
				int slotOffset = (firstSampleNumber + (blockN * StorageFloats.BLOCK_SIZE)) % StorageFloats.SLOT_SIZE;
				float[] minVal = minimumValue[blockN];
				float[] maxVal = maximumValue[blockN];
				for(int packetN = 0; packetN < StorageFloats.BLOCK_SIZE; packetN++) {
					
					if(syncWordByteCount > 0 && data.buffer[offset] != syncWord) {
						problem = true;
						goodPacketsBeforeProblem = (blockN * StorageFloats.BLOCK_SIZE) + packetN;
						break;
					}
					if(checksumProcessor != null && !checksumProcessor.testChecksum(data.buffer, offset, packetByteCount, syncWordByteCount)) {
						problem = true;
						goodPacketsBeforeProblem = (blockN * StorageFloats.BLOCK_SIZE) + packetN;
						break;
					}
					
					for(int datasetN = 0; datasetN < datasetsCount; datasetN++) {
						float f = processorForDataset[datasetN].parse(data.buffer, offset + locationForDataset[datasetN]) * conversionFactorForDataset[datasetN];
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
					dataset[datasetN].setRangeOfBlock(firstSampleNumber + (blockN * StorageFloats.BLOCK_SIZE), minimumValue[blockN][datasetN], maximumValue[blockN][datasetN]);
			
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
	
	public int getClosestSampleNumberAtOrBefore(long timestamp, int maxSampleNumber) {
		
		return timestamps.getClosestSampleNumberAtOrBefore(timestamp, maxSampleNumber);
		
	}
	
	public int getClosestSampleNumberAfter(long timestamp) {
		
		return timestamps.getClosestSampleNumberAfter(timestamp);
		
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
	@Override public long getTimestamp(int sampleNumber) {
		
		if(sampleNumber < 0)
			return firstTimestamp;
		
		return timestamps.getTimestamp(sampleNumber);
		
	}
	
	public FloatBuffer getTimestampsBuffer(int firstSampleNumber, int lastSampleNumber, long plotMinX, StorageTimestamps.Cache cache) {
		
		return timestamps.getTampstamps(firstSampleNumber, lastSampleNumber, plotMinX, cache);
		
	}
	
	public LongBuffer getTimestampsBuffer(int firstSampleNumber, int lastSampleNumber, StorageTimestamps.Cache cache) {
		
		return timestamps.getTampstamps(firstSampleNumber, lastSampleNumber, cache);
		
	}
	
	/**
	 * @return    The current number of samples stored in the Datasets.
	 */
	@Override public int getSampleCount() {
		
		return sampleCount.get();
		
	}
	
	/**
	 * @return    The number of datasets in the data structure.
	 */
	public int getDatasetCount() {
		
		return (int) fields.values().stream().filter(Field::isDataset).count();
		
	}
	
	/**
	 * @param index    An index between 0 and DatasetsController.getCount()-1, inclusive.
	 * @return         The Dataset.
	 */
	public Field getDatasetByIndex(int index) {
		
		return fields.values().stream().filter(Field::isDataset).toList().get(index);
		
	}
	
	public Field getFieldByIndex(int index) {
		
		return fields.values().stream().toList().get(index);
		
	}
	
	/**
	 * @param location    CSV column number, or Binary packet byte offset. (Locations may be sparse.)
	 * @return            The Dataset, or null if it does not exist.
	 */
	public Field getDatasetByLocation(int location) {
		
		return fields.get(location);

	}
	
	/**
	 * @param field          Field to test.
	 * @param newLocation    Proposed CSV column number, or binary packet byte offset.
	 * @param newType        Proposed data type.
	 * @return               null if allowed, or a user-friendly String describing why the location or data type is not allowed.
	 */
	public String isFieldAllowed(Field field, int newLocation, Field.Type newType) {
		
		Field existingSyncWord = fields.values().stream().filter(Field::isSyncWord).findFirst().orElse(null);
		Field existingChecksum = fields.values().stream().filter(Field::isChecksum).findFirst().orElse(null);
		
		if(newType.isSyncWord() && protocol.is(Protocol.CSV))
			return "CSV mode does not support sync words.";
		
		if(newType.isSyncWord() && existingSyncWord != null && field != existingSyncWord)
			return "A sync word has already been defined.";
		
		if(newType.isSyncWord() && existingSyncWord == null & fields.values().stream().anyMatch(existingField -> existingField.location.is(0)))
			return "A dataset already exists at the start of the packet.";
		
		if(newType.isSyncWord() && newLocation != 0)
			return "A sync word can only be placed at the start of the packet.";
		
		if(newType.isDataset() && protocol.is(Protocol.CSV) && fields.values().stream().anyMatch(existingField -> (existingField.location.is(newLocation)) && (existingField != field)))
			return "A dataset already exists at column " + newLocation + ".";
		
		if(newType.isDataset() && protocol.is(Protocol.BINARY) && existingSyncWord != null && existingSyncWord != field && newLocation < existingSyncWord.type.get().getByteCount())
			return "Can not place a dataset that overlaps with the sync word.";
		
		if(newType.isDataset() && protocol.is(Protocol.BINARY) && existingChecksum != null && existingChecksum != field && (newLocation + newType.getByteCount() - 1 >= existingChecksum.location.get()))
			return "Can not place a dataset that overlaps with the checksum or is placed after the checksum.";
		
		if(newType.isDataset() && protocol.is(Protocol.BINARY)) {
			int proposedStartByte = newLocation;
			int proposedEndByte = proposedStartByte + newType.getByteCount() - 1;
			for(Field dataset : getDatasetsList()) {
				if(dataset == field && dataset.location.get() == newLocation && dataset.type.get().getByteCount() >= newType.getByteCount())
					return null; // same dataset and location, occupying the same or less space, so allow it
				int existingStartByte = dataset.location.get();
				int existingEndByte = existingStartByte + dataset.type.get().getByteCount() - 1;
				if(proposedStartByte >= existingStartByte && proposedStartByte <= existingEndByte)
					return "Can not place a dataset that overlaps an existing field."; // starting inside existing range
				if(proposedEndByte >= existingStartByte && proposedEndByte <= existingEndByte)
					return "Can not place a dataset that overlaps an existing field."; // ending inside existing range
				if(existingStartByte >= proposedStartByte && existingEndByte <= proposedEndByte)
					return "Can not place a dataset that overlaps an existing field."; // encompassing existing range
			}
		}
		
		if(newType.isChecksum() && protocol.is(Protocol.CSV))
			return "CSV mode does not support checksums.";

		if(newType.isChecksum() && existingChecksum != null && existingChecksum != field)
			return "A checksum field already exists.";
		
		if(newType.isChecksum() && newLocation == 0)
			return "A checksum field can only be placed at the end of a packet.";
		
		if(newType.isChecksum() && getDatasetsList().stream().anyMatch(dataset -> newLocation <= dataset.location.get() + dataset.type.get().getByteCount() - 1))
			return "A checksum field can only be placed at the end of a packet.";
		
		if(newType.isChecksum() && (newLocation - 1) % newType.getByteCount() != 0)
			return "This checksum must be aligned on a " + newType.getByteCount() + " byte boundary. (The number of bytes before the checksum, not counting the sync word, must be a multiple of " + newType.getByteCount() + " bytes.)";
		
		// success, the location is available
		return null;
		
	}
	
	public void removeAllFields() {
		
		fields.values().stream().toList().forEach(field -> removeField(field.location.get())); // toList() to prevent a ConcurrentModificationException
		setFieldsDefined(false);
		
	}
	
	public List<Field> getDatasetsList() {
		
		return fields.values().stream().filter(Field::isDataset).toList();
		
	}
	
	/**
	 * @param field    The data type being inserted. Could be a sync word, dataset, or checksum.
	 * @return         null on success, or a user-friendly String describing why the field could not be added.
	 */
	public String insertField(Field field) {
		
		// sanity checks
		String errorMessage = isFieldAllowed(field, field.location.get(), field.type.get());
		if(errorMessage != null)
			return errorMessage;
		
		if(field.isDataset() && field.name.get().isEmpty())
			return "A dataset name is required.";
		
		if(field.isSyncWord())
			try {
				Integer.parseInt(field.name.get().substring(2), 16);
			} catch(NumberFormatException e) {
				return "Invalid sync word.";
			}
			
		// insert
		fields.put(field.location.get(), field);
		if(field.isSyncWord()) {
			return null;
		} else if(field.isDataset()) {
			removeAllData(); // remove any existing samples, because every dataset must contain samples for every sample number
			return null;
		} else if(field.isChecksum()) {
			return null;
		} else {
			return "Unknown error."; // we should never get here
		}
		
	}
	
	/**
	 * @param location    CSV column number, or binary packet byte offset.
	 * @return            null on success, or a user-friendly String describing why the field could not be removed.
	 */
	public String removeField(int location) {
		
		Field field = fields.get(location);
		if(field == null)
			return "A field does not exist at location " + location + ".";
		
		// ensure the configure panel isn't open
		ConfigureView.instance.close();
		
		// remove any charts referencing the dataset
		Charts.removeIf(chart -> chart.datasets.contains(field) || (chart.trigger != null && chart.trigger.datasets.contains(field)));
		
		// remove the dataset
		fields.remove(location);
		if(field.isDataset())
			field.floats.dispose();
		
		// remove timestamps if no other datasets are left
		if(fields.isEmpty()) {
			clearTimestamps();
			CommunicationView.instance.redraw();
			OpenGLChartsView.instance.setPlayLive();
			
			// if this is the only connection, also remove all charts because a timeline chart may still exist
			if(Connections.allConnections.size() == 1)
				Charts.removeAll();
		}
		
		return null; // success
		
	}
	
	/**
	 * @return    The first unoccupied CSV column number or byte offset, or -1 if they are all occupied.
	 */
	public int getFirstAvailableLocation() {
		
		if(protocol.is(Protocol.CSV)) {
			
			return IntStream.range(0, Integer.MAX_VALUE)
			                .filter(i -> getDatasetByLocation(i) == null)
			                .findFirst().orElse(-1);
			
		} else {
			
			Field checksum = fields.values().stream().filter(Field::isChecksum).findFirst().orElse(null);
			return IntStream.range(0, (checksum == null) ? Integer.MAX_VALUE : checksum.location.get() + checksum.type.get().getByteCount())
			                .filter(i -> fields.values().stream().noneMatch(dataset ->
			                                                                i >= dataset.location.get() &&
			                                                                i <  dataset.location.get() + dataset.type.get().getByteCount()))
			                .findFirst().orElse(-1);
			
		}
		
	}
	
	abstract protected boolean supportsTransmitting();
	abstract protected boolean supportsUserDefinedDataStructure();

	@Override public void importFrom(Connections.QueueOfLines lines) throws AssertionError {
		
		configWidgets.stream().skip(1).forEach(widget -> widget.importFrom(lines));

		if(supportsTransmitting() && !protocol.is(Protocol.TC66)) {
			transmitDatatype.importFrom(lines);
			transmitData.importFrom(lines);
			transmitAppendCR.importFrom(lines);
			transmitAppendLF.importFrom(lines);
			transmitRepeatedly.importFrom(lines);
			transmitRepeatedlyMilliseconds.importFrom(lines);
			
			int saveCount = lines.parseInteger("transmit saved packet count = %d");
			if(saveCount < 0)
				throw new AssertionError("Invalid saved packet count.");
			
			while(saveCount-- > 0) {
				try {
					String label = lines.remove();
					String hexText = lines.remove().trim();
					String[] hexBytes = hexText.split(" ");
					byte[] bytes = new byte[hexBytes.length];
					for(int i = 0; i < hexBytes.length; i++)
						bytes[i] = (byte) (Integer.parseInt(hexBytes[i], 16) & 0xFF);
					if(label.equals("") || bytes.length == 0)
						throw new Exception();
					transmitSavedPackets.add(new Packet(label, bytes, hexText));
				} catch(Exception e) {
					throw new AssertionError("Invalid saved packet.");
				}
			}
		}
		
		if(supportsUserDefinedDataStructure() && !protocol.is(Protocol.TC66)) {
			int fieldCount = lines.parseInteger("field count = %d");
			if(fieldCount < 1)
				throw new AssertionError("Invalid datasets count.");
			for(int i = 0; i < fieldCount; i++)
				new Field(this).importFrom(lines);
		}
		
		setFieldsDefined(true);

	}

	@Override public void exportTo(PrintWriter file) {
		
		configWidgets.forEach(widget -> widget.exportTo(file));
		
		if(supportsTransmitting() && !protocol.is(Protocol.TC66)) {
			transmitDatatype.exportTo(file);
			transmitData.exportTo(file);
			transmitAppendCR.exportTo(file);
			transmitAppendLF.exportTo(file);
			transmitRepeatedly.exportTo(file);
			transmitRepeatedlyMilliseconds.exportTo(file);
			
			file.println("\ttransmit saved packet count = " + transmitSavedPackets.size());
			transmitSavedPackets.forEach(packet -> {
				file.println("\t\t" + packet.label());
				file.println("\t\t" + packet.bytesAsHexString);
			});
		}
		
		if(supportsUserDefinedDataStructure() && !protocol.is(Protocol.TC66)) {
			file.println("\tfield count = " + fields.size());
			fields.values().stream().forEach(dataset -> dataset.exportTo(file));
		}
		
		file.println("");

	}
	
	@Override public JPanel getUpdatedTransmitGUI() {
		
		if(Connections.importing || !supportsTransmitting())
			return null;
		
		JPanel gui = new JPanel(new MigLayout("hidemode 3, fillx, wrap 1, insets " + Theme.padding + ", gap " + Theme.padding, "[fill,grow]"));
		String title = protocol.is(Protocol.TC66) ? "TC66 (" + getName() + (isConnected() ? "" : " - disconnected") + ")" :
		                                            "Transmit to " + getName() + (isConnected() ? "" : " (disconnected)");
		gui.setBorder(new TitledBorder(title));
		
		if(protocol.is(Protocol.TC66)) {
			
			gui.add(new JLabel(tc66firmwareVersion, SwingConstants.CENTER));
			gui.add(new JLabel(tc66serialNumber, SwingConstants.CENTER));
			gui.add(new JLabel(tc66powerOnCount, SwingConstants.CENTER));
			
			// for the TC66, the user can only send some pre-defined packets
			transmitSavedPackets.forEach(packet -> {
				JButton packetButton = new JButton(packet.label());
				packetButton.setHorizontalAlignment(SwingConstants.LEFT);
				packetButton.addActionListener(clicked -> transmitQueue.add(packet.bytes()));
				packetButton.setEnabled(isConnected());
				gui.add(packetButton);
			});
			
		} else {
			
			transmitDatatype.appendTo(gui, "split 2");
			transmitData.appendTo(gui, "grow x, width 1:1:"); // min/pref width = 1px, so this doesn't widen the panel
			
			transmitAppendCR.appendTo(gui, "split 4");
			transmitAppendLF.appendTo(gui, "");
			transmitRepeatedly.appendTo(gui, "");
			transmitRepeatedlyMilliseconds.appendTo(gui, "grow x, width 1:1:"); // min/pref width = 1px, so this doesn't widen the panel
			
			gui.add(transmitSaveButton, "split 2, grow x");
			gui.add(transmitTransmitButton, "grow x");
			
			boolean haveData = transmitAppendCR.get() || transmitAppendLF.get() || !transmitData.get().isEmpty();
			transmitDatatype.setEnabled(isConnected());
			transmitData.setEnabled(isConnected());
			transmitAppendCR.setEnabled(isConnected() && transmitDatatype.is(WidgetTextfield.Mode.TEXT));
			transmitAppendLF.setEnabled(isConnected() && transmitDatatype.is(WidgetTextfield.Mode.TEXT));
			transmitRepeatedly.setEnabled(isConnected() && haveData);
			transmitRepeatedlyMilliseconds.setEnabled(isConnected() && haveData);
			transmitSaveButton.setEnabled(isConnected() && haveData);
			transmitTransmitButton.setEnabled(isConnected() && haveData);
			
			transmitSavedPackets.forEach(packet -> {
				JButton sendButton = new JButton(packet.label());
				sendButton.setHorizontalAlignment(SwingConstants.LEFT);
				sendButton.addActionListener(clicked -> transmitQueue.add(packet.bytes()));
				sendButton.setEnabled(isConnected());
				JButton removeButton = new JButton(Theme.removeSymbol);
				removeButton.setBorder(Theme.narrowButtonBorder);
				removeButton.addActionListener(click -> {
					transmitSavedPackets.remove(packet);
					SettingsView.instance.redraw(); // so this TX GUI gets redrawn
				});
				removeButton.setEnabled(isConnected());
				gui.add(sendButton, "split 2, grow x, width 1:1:"); // min/pref width = 1px, so this doesn't widen the panel
				gui.add(removeButton);
			});
			
		}
			
		return gui;
		
	}

}
