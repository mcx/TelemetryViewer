import com.fazecast.jSerialComm.SerialPort;

public class ConnectionTelemetryUART extends ConnectionTelemetry {
	
	public ConnectionTelemetryUART(String connectionName) {
		
		configWidgets.add(name.set(connectionName));
		configWidgets.add(baudRate);
		configWidgets.add(protocol);
		configWidgets.add(sampleRate);
		
	}
	
	@Override public String getName() { return name.get().substring(6); } // trim leading "UART: "

	@Override public void connectToDevice(boolean showGui) {
		
		receiverThread = new Thread(() -> {
		
			previousSampleCountTimestamp = 0;
			previousSampleCount = 0;
			calculatedSamplesPerSecond = 0;
			
			setStatus(Status.CONNECTING, false);
			SerialPort uart = SerialPort.getCommPort(getName());
			uart.setBaudRate(Integer.parseInt(baudRate.get().split(" ")[0]));
			uart.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, 0, 0);
			if(!uart.openPort() && !uart.openPort() && !uart.openPort()) { // some Bluetooth UARTs have trouble connecting, so try 3 times
				disconnect("Unable to connect to " + getName() + ".", false);
				return;
			}
			setStatus(Status.CONNECTED, showGui && !protocol.is(Protocol.TC66));
			
			// start the transmit thread now that we're connected
			transmitterThread = new Thread(() -> {
					
				try {
					
					while(true) {
					
						// stop if requested
						if(!isConnected())
							throw new Exception();
						
						// transmit data if available
						while(!transmitQueue.isEmpty()) {
							byte[] data = transmitQueue.remove();
							uart.writeBytes(data, data.length);
//							Notifications.printInfo("Transmitted to " + getName(), data);
						}
						if(transmitRepeatedly.get() && System.currentTimeMillis() >= nextRepititionTimestamp) {
							nextRepititionTimestamp = System.currentTimeMillis() + transmitRepeatedlyMilliseconds.get();
							byte[] data = transmitData.getAsBytes(transmitAppendCR.get(), transmitAppendLF.get());
							uart.writeBytes(data, data.length);
//							Notifications.printInfo("Transmitted to " + getName(), data);
						}
						
						Thread.sleep(1);
				
					}
						
				} catch(Exception e) {
					
					if(isConnected())
						disconnect("Error while writing to " + getName() + ".", false);
					
				}
				
			});
			transmitterThread.setPriority(Thread.MAX_PRIORITY);
			transmitterThread.setName("UART Transmitter Thread for " + getName());
			transmitterThread.start();
			
			// start receiving data
			SharedByteStream stream = new SharedByteStream(ConnectionTelemetryUART.this);
			startProcessingTelemetry(stream);
			byte[] buffer = new byte[1048576]; // 1MB
			
			try {
				
				while(true) {
					
					// stop if requested
					if(!isConnected())
						throw new Exception();
					
					// receive data if available
					int length = uart.bytesAvailable();
					if(length < 0) {
						throw new Exception();
					} else if(length == 0) {
						Thread.sleep(1);
					} else {
						if(length > buffer.length)
							buffer = new byte[length];
						uart.readBytes(buffer, length);
						stream.write(buffer, length);
					}
					
				}
				
			} catch(Exception e) {
			
				stopProcessingTelemetry();
				uart.closePort();
				if(isConnected())
					disconnect("Error while reading from " + getName() + ".", false);
				
			}
			
		});
		receiverThread.setPriority(Thread.MAX_PRIORITY);
		receiverThread.setName("UART Receiver Thread for " + getName());
		receiverThread.start();
		
	}

	@Override protected boolean supportsTransmitting()             { return true; }
	@Override protected boolean supportsUserDefinedDataStructure() { return true; }

}