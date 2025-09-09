import java.awt.Color;

public class ConnectionTelemetryDemo extends ConnectionTelemetry {
	
	public ConnectionTelemetryDemo() {
		
		configWidgets.add(name.set("Demo Mode"));
		configWidgets.add(baudRate.forceDisabled(true));
		configWidgets.add(protocol.set(Protocol.CSV).forceDisabled(true));
		configWidgets.add(sampleRate.set(10000).forceDisabled(true));
		
		new Field(this).setLocation(0).setName("Low Quality Noise"               ).setColor(Color.RED  ).setUnit("Volts").insert();
		new Field(this).setLocation(1).setName("Noisey Sine Wave 100-500Hz"      ).setColor(Color.GREEN).setUnit("Volts").insert();
		new Field(this).setLocation(2).setName("Intermittent Sawtooth Wave 100Hz").setColor(Color.BLUE ).setUnit("Volts").insert();
		new Field(this).setLocation(3).setName("Clean Sine Wave 1kHz"            ).setColor(Color.CYAN ).setUnit("Volts").insert();
		setFieldsDefined(true);
		
	}
	
	@Override public String getName() { return name.get(); }

	@Override public void connectToDevice(boolean showGui) {

		// simulate the transmission of a telemetry packet every 100us.
		receiverThread = new Thread(() -> {
		
			previousSampleCountTimestamp = 0;
			previousSampleCount = 0;
			calculatedSamplesPerSecond = 0;
			
			setStatus(Status.CONNECTED, showGui);
			
			long startTime = System.currentTimeMillis();
			int startSampleNumber = getSampleCount();
			int sampleNumber = startSampleNumber;
			if(sampleNumber == Integer.MAX_VALUE) {
				disconnect(maxSampleCountErrorMessage, false);
				return;
			}
			
			double oscillatingFrequency = 100; // Hz
			boolean oscillatingHigher = true;
			int samplesForCurrentFrequency = (int) Math.round(1.0 / oscillatingFrequency * 10000.0);
			int currentFrequencySampleCount = 0;
			
			while(true) {
				
				// stop if requested
				if(!isConnected())
					break;
				
				// generate 10 samples for each waveform
				float scalar = ((System.currentTimeMillis() % 30000) - 15000) / 100.0f;
				float lowQualityNoise = (System.nanoTime() / 100 % 100) * scalar * 1.0f / 14000f;
				for(int i = 0; i < 10; i++) {
					getDatasetByIndex(0).setSample(sampleNumber, lowQualityNoise);
					getDatasetByIndex(1).setSample(sampleNumber, (float) (Math.sin(2 * Math.PI * oscillatingFrequency * currentFrequencySampleCount / 10000.0) + 0.07*(Math.random()-0.5)));
					getDatasetByIndex(2).setSample(sampleNumber, (sampleNumber % 10000 < 1000) ? (sampleNumber % 100) / 100f : 0);
					getDatasetByIndex(3).setSample(sampleNumber, (float) Math.sin(2 * Math.PI * 1000 * sampleNumber / 10000.0));
					sampleNumber++;
					incrementSampleCount(1);
					if(sampleNumber == Integer.MAX_VALUE) {
						disconnect(maxSampleCountErrorMessage, false);
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
				
				long actualMilliseconds = System.currentTimeMillis() - startTime;
				long expectedMilliseconds = Math.round((sampleNumber - startSampleNumber) / 10.0);
				long sleepMilliseconds = expectedMilliseconds - actualMilliseconds;
				if(sleepMilliseconds >= 1)
					try { Thread.sleep(sleepMilliseconds); } catch(Exception e) {}
				
			}
			
		});
		receiverThread.setPriority(Thread.MAX_PRIORITY);
		receiverThread.setName("Demo Waveform Simulator Thread");
		receiverThread.start();
		
	}
	
	@Override protected boolean supportsTransmitting()             { return false; }
	@Override protected boolean supportsUserDefinedDataStructure() { return false; }

}
