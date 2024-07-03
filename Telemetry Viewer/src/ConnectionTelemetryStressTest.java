import java.awt.Color;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;

import javax.swing.JFrame;

public class ConnectionTelemetryStressTest extends ConnectionTelemetry {
	
	public ConnectionTelemetryStressTest() {

		widgetsList.add(name.set("Stress Test Mode"));
		widgetsList.add(protocol.set(Protocol.BINARY).setEnabled(false));
		widgetsList.add(sampleRate.disableWithMessage("Maximum"));
		
		new Field(this).setLocation(0).setType(Field.Type.UINT8_SYNC_WORD   ).setName("0xAA").insert();
		new Field(this).setLocation(1).setType(Field.Type.INT16_LE          ).setName("a"   ).setColor(Color.RED  ).insert();
		new Field(this).setLocation(3).setType(Field.Type.INT16_LE          ).setName("b"   ).setColor(Color.GREEN).insert();
		new Field(this).setLocation(5).setType(Field.Type.INT16_LE          ).setName("c"   ).setColor(Color.BLUE ).insert();
		new Field(this).setLocation(7).setType(Field.Type.INT16_LE          ).setName("d"   ).setColor(Color.CYAN ).insert();
		new Field(this).setLocation(9).setType(Field.Type.UINT16_LE_CHECKSUM).insert();
		setFieldsDefined(true);
		
	}

	@Override public void connectLive(boolean showGui) {
		
		removeAllData();
		previousSampleCountTimestamp = 0;
		previousSampleCount = 0;
		
		ChartsController.removeAllCharts();
		
		SettingsView.instance.tileColumnsTextfield.set(6);
		SettingsView.instance.tileRowsTextfield.set(6);
		SettingsView.instance.timeFormatCombobox.set(SettingsView.TimeFormat.ONLY_TIME);
		SettingsView.instance.timeFormat24hoursCheckbox.set(false);
		SettingsView.instance.hintsCheckbox.set(true);
		SettingsView.instance.hintsColorButton.set(Color.GREEN);
		SettingsView.instance.warningsCheckbox.set(true);
		SettingsView.instance.warningsColorButton.set(Color.YELLOW);
		SettingsView.instance.failuresCheckbox.set(true);
		SettingsView.instance.failuresColorButton.set(Color.RED);
		SettingsView.instance.verboseCheckbox.set(true);
		SettingsView.instance.verboseColorButton.set(Color.CYAN);
		SettingsView.instance.tooltipsVisibility.set(true);
		SettingsView.instance.antialiasingSlider.set(1);
		
		OpenGLTimeDomainChart chart = (OpenGLTimeDomainChart) ChartsController.createAndAddChart("Time Domain").setPosition(0, 0, 5, 5);
		chart.datasetsAndDurationWidget.setNormalDatasetSelected(getDatasetByLocation(1), true);
		chart.datasetsAndDurationWidget.setDurationUnit(WidgetDatasetCheckboxes.DurationUnit.SAMPLES);
		chart.datasetsAndDurationWidget.setAxisType(WidgetDatasetCheckboxes.AxisType.SAMPLE_COUNT);
		chart.datasetsAndDurationWidget.setDuration("10000000", false);
		chart.cacheEnabled.set(true);
		
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

			Field.Type checksumProcessor = fields.values().stream().filter(Field::isChecksum).map(field -> field.type.get()).findFirst().orElse(null);
			SharedByteStream stream = new SharedByteStream(ConnectionTelemetryStressTest.this, checksumProcessor);
			setConnected(true);
			startProcessingTelemetry(stream);

			long bytesSent = 0;
			long start = System.currentTimeMillis();

			while(true) {

				try {
					
					if(Thread.interrupted() || !isConnected())
						throw new InterruptedException();
					
					stream.write(array, array.length);
					bytesSent += array.length;
					long millisecondsElapsed = System.currentTimeMillis() - start;
					if(millisecondsElapsed > 3000) {
						String text = String.format("%1.1f Mbps (%1.1f Mpackets/sec)", (double) bytesSent / (double) millisecondsElapsed / 125.0,
						                                                               (double) bytesSent / (double) millisecondsElapsed / 11000.0);
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
	
	@Override public List<Widget> getConfigurationWidgets() {
		
		return List.of(sampleRate, protocol); // both widgets were disabled in the constructor
		
	}
	
	@Override public Map<String, String> getExampleCode() {
		
		return Map.of();
		
	}
	
	@Override protected boolean supportsTransmitting()             { return false; }
	@Override protected boolean supportsUserDefinedDataStructure() { return false; }

}
