import java.awt.Color;
import java.io.PrintWriter;
import java.util.Queue;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;

public class ConnectionTelemetryDemo extends ConnectionTelemetry {
	
	/**
	 * Prepares, but does not connect to, a connection that simulates a device providing telemetry.
	 * 
	 * @param connectionName    The connection to select.
	 */
	public ConnectionTelemetryDemo() {
		
		name = Type.DEMO.toString();
		type = Type.DEMO;
		
		// sample rate
		sampleRate = 10000;
		sampleRateAutomatic = false;
		sampleRateTextfield = new WidgetTextfieldInt("Sample Rate",
		                                             "samples rate (hz)",
		                                             "Hz",
		                                             sampleRate,
		                                             sampleRate,
		                                             sampleRate,
		                                             newRate -> {});
		sampleRateTextfield.setToolTipText("Number of telemetry packets sent to the PC each second.");
		sampleRateTextfield.setEnabled(false);
		
		// communication protocol
		protocol = Protocol.CSV;
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
		datasets.insert(0, null, "Low Quality Noise",                Color.RED,   "Volts", 1, 1);
		datasets.insert(1, null, "Noisey Sine Wave 100-500Hz",       Color.GREEN, "Volts", 1, 1);
		datasets.insert(2, null, "Intermittent Sawtooth Wave 100Hz", Color.BLUE,  "Volts", 1, 1);
		datasets.insert(3, null, "Clean Sine Wave 1kHz",             Color.CYAN,  "Volts", 1, 1);
		setDataStructureDefined(true);
		
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
		
		connected = true;
		CommunicationView.instance.redraw();
		
		if(showGui)
			Main.showConfigurationGui(isProtocolCsv() ? new DataStructureCsvView(this) :
			                                            new DataStructureBinaryView(this));

		// simulate the transmission of a telemetry packet every 100us.
		transmitterThread = new Thread(() -> {
			
			long startTime = System.currentTimeMillis();
			int startSampleNumber = getSampleCount();
			int sampleNumber = startSampleNumber;
			
			if(sampleNumber == Integer.MAX_VALUE) {
				SwingUtilities.invokeLater(() -> disconnect("Reached maximum sample count. Disconnected.")); // invokeLater to prevent deadlock
				return;
			}
			
			double oscillatingFrequency = 100; // Hz
			boolean oscillatingHigher = true;
			int samplesForCurrentFrequency = (int) Math.round(1.0 / oscillatingFrequency * 10000.0);
			int currentFrequencySampleCount = 0;
			
			while(true) {
				float scalar = ((System.currentTimeMillis() % 30000) - 15000) / 100.0f;
				float lowQualityNoise = (System.nanoTime() / 100 % 100) * scalar * 1.0f / 14000f;
				for(int i = 0; i < 10; i++) {
					datasets.getByIndex(0).setSample(sampleNumber, lowQualityNoise);
					datasets.getByIndex(1).setSample(sampleNumber, (float) (Math.sin(2 * Math.PI * oscillatingFrequency * currentFrequencySampleCount / 10000.0) + 0.07*(Math.random()-0.5)));
					datasets.getByIndex(2).setSample(sampleNumber, (sampleNumber % 10000 < 1000) ? (sampleNumber % 100) / 100f : 0);
					datasets.getByIndex(3).setSample(sampleNumber, (float) Math.sin(2 * Math.PI * 1000 * sampleNumber / 10000.0));
					
					sampleNumber++;
					incrementSampleCount(1);
					
					if(sampleNumber == Integer.MAX_VALUE) {
						SwingUtilities.invokeLater(() -> disconnect("Reached maximum sample count. Disconnected.")); // invokeLater to prevent deadlock
						return;
					}

					currentFrequencySampleCount++;
					if(currentFrequencySampleCount == samplesForCurrentFrequency) {
						if(oscillatingFrequency >= 500)
							oscillatingHigher = false;
						else if(oscillatingFrequency <= 100)
							oscillatingHigher = true;
						oscillatingFrequency *= oscillatingHigher ? 1.005 : 0.995;
						samplesForCurrentFrequency = (int) Math.round(1.0 / oscillatingFrequency * 10000.0);
						currentFrequencySampleCount = 0;
					}
				}
				
				try {
					long actualMilliseconds = System.currentTimeMillis() - startTime;
					long expectedMilliseconds = Math.round((sampleNumber - startSampleNumber) / 10.0);
					long sleepMilliseconds = expectedMilliseconds - actualMilliseconds;
					if(sleepMilliseconds >= 1)
						Thread.sleep(sleepMilliseconds);
				} catch(InterruptedException e) {
					return;
				}
			}
			
		});
		
		transmitterThread.setPriority(Thread.MAX_PRIORITY);
		transmitterThread.setName("Demo Waveform Simulator Thread");
		transmitterThread.start();
		
	}

	@Override public void importSettings(Queue<String> lines) throws AssertionError {
		
		// no settings to import, done
		setDataStructureDefined(true);
		CommunicationView.instance.redraw();

	}

	@Override public void exportSettings(PrintWriter file) {

		file.println("\tconnection type = Demo Mode");

	}
	
	/**
	 * @return    Null, because Demo Mode does not support transmitting.
	 */
	@Override public JPanel getUpdatedTransmitGUI() {
		
		return null;
		
	}

}
