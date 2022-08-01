import java.awt.Color;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import javax.swing.JFrame;
import javax.swing.JPanel;

public class ConnectionTelemetryStressTest extends ConnectionTelemetry {
	
	/**
	 * Prepares, but does not connect to, a connection that simulates a device flooding this PC with telemetry.
	 */
	public ConnectionTelemetryStressTest() {
		
		name = ConnectionTelemetry.Type.STRESS_TEST.toString();
		type = Type.STRESS_TEST;

		protocol = Protocol.BINARY;
		
		// sample rate
		sampleRate = Integer.MAX_VALUE;
		sampleRateAutomatic = false;
		sampleRateTextfield = new WidgetTextfieldInt("Sample Rate",
		                                             "samples rate (hz)",
		                                             "Hz",
		                                             sampleRate,
		                                             sampleRate,
		                                             sampleRate,
		                                             newRate -> {});
		sampleRateTextfield.setToolTipText("<html>Number of telemetry packets sent to the PC each second.</html>");
		sampleRateTextfield.disableWithMessage("Maximum");
		
		// communication protocol
		protocol = Protocol.BINARY;
		protocolCombobox = new WidgetComboboxEnum<Protocol>(Protocol.values(),
		                                                    protocol,
		                                                    newProtocol -> {});
		protocolCombobox.setEnabled(false);
		
		// connections list
		namesCombobox = ConnectionsController.getNamesCombobox(this);
		
		// populate the panel
		settingsGui.add(sampleRateTextfield);
		settingsGui.add(protocolCombobox);
		settingsGui.add(namesCombobox);
		settingsGui.add(connectButton);
		settingsGui.add(removeButton);
		
		// define the data structure
		
		
	}

	/**
	 * @return    A GUI for controlling this Connection.
	 */
	@Override public JPanel getUpdatedConnectionGui() {
		
		removeButton.setVisible(ConnectionsController.allConnections.size() > 1 && !ConnectionsController.importing);
		connectButton.setText(connected ? "Disconnect" : "Connect");
		if(ConnectionsController.importing)
			namesCombobox.disableWithMessage("Importing [" + name + "]");
		
		boolean importingOrExporting = ConnectionsController.importing || ConnectionsController.exporting;
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
		
		SettingsController.setTileColumns(6);
		SettingsController.setTileRows(6);
		SettingsController.setTimeFormat("Only Time");
		SettingsController.setTimeFormat24hours(false);
		SettingsController.setHintNotificationVisibility(true);
		SettingsController.setHintNotificationColor(Color.GREEN);
		SettingsController.setWarningNotificationVisibility(true);
		SettingsController.setWarningNotificationColor(Color.YELLOW);
		SettingsController.setFailureNotificationVisibility(true);
		SettingsController.setFailureNotificationColor(Color.RED);
		SettingsController.setVerboseNotificationVisibility(true);
		SettingsController.setVerboseNotificationColor(Color.CYAN);
		SettingsController.setTooltipVisibility(true);
		SettingsController.setAntialiasingLevel(1);
		
		DatasetsController.BinaryFieldProcessor processor = null;
		for(DatasetsController.BinaryFieldProcessor p : DatasetsController.binaryFieldProcessors)
			if(p.toString().equals("int16 LSB First"))
				processor = p;
		
		datasets.removeAll();
		datasets.insertSyncWord((byte) 0xAA);
		datasets.insert(1, processor, "a", Color.RED,   "", 1, 1);
		datasets.insert(3, processor, "b", Color.GREEN, "", 1, 1);
		datasets.insert(5, processor, "c", Color.BLUE,  "", 1, 1);
		datasets.insert(7, processor, "d", Color.CYAN,  "", 1, 1);
		
		DatasetsController.BinaryChecksumProcessor checksumProcessor = null;
		for(DatasetsController.BinaryChecksumProcessor p : DatasetsController.binaryChecksumProcessors)
			if(p.toString().equals("uint16 Checksum LSB First"))
				checksumProcessor = p;
		datasets.insertChecksum(9, checksumProcessor);
		
		setDataStructureDefined(true);
		CommunicationView.instance.redraw();
		
		PositionedChart chart = ChartsController.createAndAddChart("Time Domain", 0, 0, 5, 5);
		List<String> chartSettings = new ArrayList<String>();
		chartSettings.add("datasets = connection 0 location 1");
		chartSettings.add("bitfield edge states = ");
		chartSettings.add("bitfield level states = ");
		chartSettings.add("duration = 10000000");
		chartSettings.add("duration unit = Samples");
		chartSettings.add("time axis shows = Sample Count");
		chartSettings.add("show legend = true");
		chartSettings.add("cached mode = true");
		chartSettings.add("x-axis show ticks = true");
		chartSettings.add("x-axis show title = true");
		chartSettings.add("y-axis minimum = -1.0");
		chartSettings.add("y-axis minimum automatic = true");
		chartSettings.add("y-axis maximum = 1.0");
		chartSettings.add("y-axis maximum automatic = true");
		chartSettings.add("y-axis show ticks = true");
		chartSettings.add("y-axis show title = true");
		chartSettings.add("trigger mode = Disabled");
		chartSettings.add("trigger affects = This Chart");
		chartSettings.add("trigger type = Rising Edge");
		chartSettings.add("datasets = connection 0 location 1");
		chartSettings.add("trigger level = 0");
		chartSettings.add("trigger hysteresis = 0");
		chartSettings.add("trigger pre/post ratio = 20");
		chart.importFrom(new ConnectionsController.QueueOfLines(chartSettings));
		
		Main.window.setExtendedState(JFrame.NORMAL);
		
		// prepare the TX buffer
		byte[] array = new byte[11 * 65536]; // 11 bytes per packet, 2^16 packets
		ByteBuffer buffer = ByteBuffer.wrap(array);
		buffer.order(ByteOrder.LITTLE_ENDIAN);
		short a = 0;
		short b = 1;
		short c = 2;
		short d = 3;
		for(int i = 0; i < 65536; i++) {
			buffer.put((byte) 0xAA);
			buffer.putShort(a);
			buffer.putShort(b);
			buffer.putShort(c);
			buffer.putShort(d);
			buffer.putShort((short) (a+b+c+d));
			a++;
			b++;
			c++;
			d++;
		}
		
		transmitterThread = new Thread(() -> {

			SharedByteStream stream = new SharedByteStream(ConnectionTelemetryStressTest.this);
			connected = true;
			CommunicationView.instance.redraw();
			startProcessingTelemetry(stream);

			long bytesSent = 0;
			long start = System.currentTimeMillis();

			while(true) {

				try {
					
					if(Thread.interrupted() || !connected)
						throw new InterruptedException();
					
					stream.write(array, array.length);
					bytesSent += array.length;
					long end = System.currentTimeMillis();
					if(end - start > 3000) {
						String text = String.format("%1.1f Mbps (%1.1f Mpackets/sec)", (bytesSent / (double)(end-start) * 1000.0 * 8.0 / 1000000), (bytesSent / 11 / (double)(end-start) * 1000.0) / 1000000.0);
						NotificationsController.showVerboseForMilliseconds(text, 3000 - Theme.animationMilliseconds, true);
						bytesSent = 0;
						start = System.currentTimeMillis();
					}
					
				}  catch(InterruptedException ie) {
					
					stopProcessingTelemetry();
					return;
					
				}
			
			}
			
		});
		
		transmitterThread.setPriority(Thread.MAX_PRIORITY);
		transmitterThread.setName("Stress Test Simulator Thread");
		transmitterThread.start();
		
	}

	@Override public void importSettings(Queue<String> lines) throws AssertionError {
		
		// no settings to import, done
		setDataStructureDefined(true);
		CommunicationView.instance.redraw();

	}

	@Override public void exportSettings(PrintWriter file) {
			
		file.println("\tconnection type = Stress Test Mode");

	}
	
	/**
	 * @return    Null, because Stress Test Mode does not support transmitting.
	 */
	@Override public JPanel getUpdatedTransmitGUI() {
		
		return null;
		
	}

}
