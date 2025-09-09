import java.awt.Color;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import javax.swing.JFrame;

public class ConnectionTelemetryStressTest extends ConnectionTelemetry {
	
	public ConnectionTelemetryStressTest() {

		configWidgets.add(name.set("Stress Test Mode"));
		configWidgets.add(protocol.set(Protocol.BINARY).forceDisabled(true));
		configWidgets.add(sampleRate.disableWithMessage("Maximum").forceDisabled(true));
		
		new Field(this).setLocation(0).setType(Field.Type.UINT8_SYNC_WORD   ).setName("0xAA").insert();
		new Field(this).setLocation(1).setType(Field.Type.INT16_LE          ).setName("a"   ).setColor(Color.RED  ).insert();
		new Field(this).setLocation(3).setType(Field.Type.INT16_LE          ).setName("b"   ).setColor(Color.GREEN).insert();
		new Field(this).setLocation(5).setType(Field.Type.INT16_LE          ).setName("c"   ).setColor(Color.BLUE ).insert();
		new Field(this).setLocation(7).setType(Field.Type.INT16_LE          ).setName("d"   ).setColor(Color.CYAN ).insert();
		new Field(this).setLocation(9).setType(Field.Type.UINT16_LE_CHECKSUM).insert();
		setFieldsDefined(true);
		
	}
	
	@Override public String getName() { return name.get(); }

	@Override public void connectToDevice(boolean showGui) {
		
		removeAllData();
		Charts.removeAll();
		Settings.GUI.tileColumns.set(6);
		Settings.GUI.tileRows.set(6);
		Settings.GUI.hintsEnabled.set(true);
		Settings.GUI.antialiasingLevel.set(1);
		
		OpenGLTimeDomainChart chart = (OpenGLTimeDomainChart) Charts.Type.TIME_DOMAIN.createAt(0, 0, 5, 5);
		chart.datasetsAndDurationWidget.datasets.get(getDatasetByLocation(1)).set(true);
		chart.datasetsAndDurationWidget.durationUnit.set(DatasetsInterface.DurationUnit.SAMPLES);
		chart.datasetsAndDurationWidget.duration.set("10000000");
		chart.cacheEnabled.set(true);
		
		Main.window.setExtendedState(JFrame.NORMAL);
		
		receiverThread = new Thread(() -> {
			
			previousSampleCountTimestamp = 0;
			previousSampleCount = 0;
			calculatedSamplesPerSecond = 0;
			
			setStatus(Status.CONNECTING, false);
			byte[] txBuffer = new byte[11 * 65536]; // 11 bytes per packet, 2^16 packets
			ByteBuffer buffer = ByteBuffer.wrap(txBuffer).order(ByteOrder.LITTLE_ENDIAN);
			short a = 0, b = 1, c = 2, d = 3;
			for(int i = 0; i < 65536; i++) {
				buffer.put((byte) 0xAA);
				buffer.putShort(a);
				buffer.putShort(b);
				buffer.putShort(c);
				buffer.putShort(d);
				buffer.putShort((short) (a+b+c+d));
				a++; b++; c++; d++;
			}

			setStatus(Status.CONNECTED, false);
			SharedByteStream stream = new SharedByteStream(ConnectionTelemetryStressTest.this);
			startProcessingTelemetry(stream);

			long bytesSent = 0;
			long start = System.currentTimeMillis();

			try {
				while(true) {
					
					// stop if requested
					if(!isConnected())
						throw new Exception();
					
					// "transmit" the waveforms
					stream.write(txBuffer, txBuffer.length);
					bytesSent += txBuffer.length;
					long millisecondsElapsed = System.currentTimeMillis() - start;
					if(millisecondsElapsed > 3000) {
						String text = String.format("%1.1f Mbps (%1.1f Mpackets/sec)", (double) bytesSent / (double) millisecondsElapsed / 125.0,
						                                                               (double) bytesSent / (double) millisecondsElapsed / 11000.0);
						Notifications.showHintForMilliseconds(text, 3000 - Theme.animationMilliseconds, true);
						bytesSent = 0;
						start = System.currentTimeMillis();
					}
					
				}
			}  catch(Exception e) {
				stopProcessingTelemetry();
			}
			
		});
		receiverThread.setPriority(Thread.MAX_PRIORITY);
		receiverThread.setName("Stress Test Simulator Thread");
		receiverThread.start();
		
	}
	
	@Override protected boolean supportsTransmitting()             { return false; }
	@Override protected boolean supportsUserDefinedDataStructure() { return false; }

}
