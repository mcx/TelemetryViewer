import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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
	
	@Override public Map<String, String> getExampleCode() {
		
		if(protocol.is(Protocol.CSV) && getDatasetCount() == 0) {
			
			return Map.of("Arduino Firmware", "[ Define at least one CSV column to see example firmware. ]");
			
		} else if(protocol.is(Protocol.CSV) && getDatasetCount() > 0) {
		
			int baud                       = Integer.parseInt(baudRate.get().split(" ")[0]);
			List<Field> datasets           = getDatasetsList();
			List<String> names             = datasets.stream().map(dataset -> dataset.name.get().toLowerCase().replace(' ', '_')).toList(); // example: "a" "b"
			String intVariables            = names.stream().map(name -> "\tint "   + name + " = ...; // EDIT THIS LINE\n")
			                                               .collect(Collectors.joining());                                            // example: "int a = ..."
			String floatVariables          = names.stream().map(name -> "\tfloat " + name + " = ...; // EDIT THIS LINE\n")
			                                               .collect(Collectors.joining());                                            // example: "float a = ..."
			String floatTextVariables      = names.stream().map(name -> "\tchar "    + name + "_text[30];\n")
			                                               .collect(Collectors.joining());                                            // example: "char a_text[30]; ..."
			String floatConvertedVariables = names.stream().map(name -> "\tdtostrf(" + name + ", 10, 10, " + name + "_text);\n")
			                                               .collect(Collectors.joining());                                            // example: "dtostrf(a, 10, 10, a_text); ..."
			String printfIntArgs           = names.stream().collect(Collectors.joining(", "));                                        // example: "a, b"
			String printfFloatArgs         = names.stream().map(name -> name + "_text")
			                                               .collect(Collectors.joining(", "));                                        // example: "a_text, b_text"
			String printfIntFormatString   = IntStream.rangeClosed(0, datasets.getLast().location.get())
			                                          .mapToObj(location -> getDatasetByLocation(location) == null ? "0" : "%d")
			                                          .collect(Collectors.joining(","));                                              // example: "%d,%d" or "%d,0,%d" if sparse
			String printfFloatFormatString = IntStream.rangeClosed(0, datasets.getLast().location.get())
			                                          .mapToObj(location -> getDatasetByLocation(location) == null ? "0" : "%f")
			                                          .collect(Collectors.joining(","));                                              // example: "%f,%f" or "%f,0,%f" if sparse
			int printfIntStringLength      = IntStream.rangeClosed(0, datasets.getLast().location.get())
			                                          .map(location -> getDatasetByLocation(location) == null ? 2 : 7)
			                                          .sum() + 1;                                                                     // 2 bytes per unused location, 7 bytes per location, +1 for the null terminator
			int printfFloatStringLength    = IntStream.rangeClosed(0, datasets.getLast().location.get())
			                                          .map(location -> getDatasetByLocation(location) == null ? 2 : 31)
			                                          .sum() + 1;                                                                     // 2 bytes per unused location, 31 bytes per location, +1 for the null terminator
			
			return Map.of("Arduino Firmware", """
				void setup() {
					Serial.begin(%d);
				}
				
				// use this loop if sending integers
				void loop() {
				%s
					char text[%d];
					snprintf(text, %d, "%s", %s);
					Serial.println(text);
					
					delay(...); // EDIT THIS LINE
				}
				
				// or use this loop if sending floats
				void loop() {
				%s
				%s
				%s
					char text[%d];
					snprintf(text, %d, "%s", %s);
					Serial.println(text);
					
					delay(...); // EDIT THIS LINE
				}
				""".formatted(baud,
				              intVariables,
				              printfIntStringLength,
				              printfIntStringLength,
				              printfIntFormatString,
				              printfIntArgs,
				              floatVariables,
				              floatTextVariables,
				              floatConvertedVariables,
				              printfFloatStringLength,
				              printfFloatStringLength,
				              printfFloatFormatString,
				              printfFloatArgs));
		
		} else {
			
			return Map.of(); // no example firmware
			
		}
		
	}

	@Override protected boolean supportsTransmitting()             { return true; }
	@Override protected boolean supportsUserDefinedDataStructure() { return true; }

}