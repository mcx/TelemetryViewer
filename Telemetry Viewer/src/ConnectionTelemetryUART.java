import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import javax.swing.SwingUtilities;
import com.fazecast.jSerialComm.SerialPort;

public class ConnectionTelemetryUART extends ConnectionTelemetry {
	
	static List<String> portNames = Stream.of(SerialPort.getCommPorts())
	                                      .map(port -> "UART: " + port.getSystemPortName())
	                                      .sorted()
	                                      .toList();
	
	public static List<String> getNames() {
		return portNames;
	}
	
	public ConnectionTelemetryUART(String connectionName) {
		
		widgetsList.add(name.set(connectionName));
		widgetsList.add(baudRate);
		widgetsList.add(protocol);
		widgetsList.add(sampleRate);
		
	}

	@Override public void connectLive(boolean showGui) {
		
		previousSampleCountTimestamp = 0;
		previousSampleCount = 0;
		calculatedSamplesPerSecond = 0;
		
		SerialPort uartPort = SerialPort.getCommPort(name.get().substring(6)); // trim the leading "UART: "
		uartPort.setBaudRate(Integer.parseInt(baudRate.get().split(" ")[0]));
		uartPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, 0, 0);
		
		// try 3 times before giving up, because some Bluetooth UARTs have trouble connecting
		if(!uartPort.openPort() && !uartPort.openPort() && !uartPort.openPort()) {
			SwingUtilities.invokeLater(() -> disconnect("Unable to connect to " + name.get().substring(6) + "."));
			return;
		}
		
		setConnected(true);
		
		if(protocol.is(Protocol.TC66))
			showGui = false;

		if(showGui) {
			setFieldsDefined(false);
			Main.showConfigurationGui(getDataStructureGui());
		}
		
		receiverThread = new Thread(() -> {
			
			InputStream uart = uartPort.getInputStream();
			Field.Type checksumProcessor = fields.values().stream().filter(Field::isChecksum).map(field -> field.type.get()).findFirst().orElse(null);
			SharedByteStream stream = new SharedByteStream(ConnectionTelemetryUART.this, checksumProcessor);
			startProcessingTelemetry(stream);
			byte[] buffer = new byte[1048576]; // 1MB
			
			// listen for packets
			while(true) {

				try {
					
					if(Thread.interrupted() || !isConnected())
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
					if(!isConnected())
						continue;
					
					// problem while reading from the UART
					stopProcessingTelemetry();
					uartPort.closePort();
					SwingUtilities.invokeLater(() -> disconnect("Error while reading from " + name.get()));
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
					
					if(Thread.interrupted() || !isConnected())
						throw new InterruptedException();
					
					while(!transmitQueue.isEmpty()) {
						byte[] data = transmitQueue.remove();
						uart.write(data);
						
//						String message = "Transmitted: ";
//						for(byte b : data)
//							message += String.format("%02X ", b);
//						NotificationsController.showDebugMessage(message);
					}
					
					if(transmitRepeatedly.get() && System.currentTimeMillis() >= nextRepititionTimestamp) {
						nextRepititionTimestamp = System.currentTimeMillis() + transmitRepeatedlyMilliseconds.get();
						uart.write(transmitData.getAsBytes(transmitAppendCR.get(), transmitAppendLF.get()));
						
//						String message = "Transmitted: ";
//						for(byte b : transmitDataBytes)
//							message += String.format("%02X ", b);
//						NotificationsController.showDebugMessage(message);
					}
					
					Thread.sleep(1);
					
				} catch(IOException e) {
					
					// an IOException can occur if an InterruptedException occurs while transmitting data
					// let this be detected by the connection test in the loop
					if(!isConnected())
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
	
	@Override public List<Widget> getConfigurationWidgets() {
		
		boolean isEnabled = !isConnected() && !ConnectionsController.importing && !ConnectionsController.exporting;
		
		return List.of(sampleRate.setEnabled(isEnabled && !protocol.is(Protocol.TC66)),
		               protocol.setEnabled(isEnabled),
		               baudRate.setEnabled(isEnabled && !protocol.is(Protocol.TC66)));
		
	}
	
	@Override public Map<String, String> getExampleCode() {
		
		if(protocol.get() == Protocol.CSV && getDatasetCount() == 0) {
			
			return Map.of("Arduino Firmware", "[ Define at least one CSV column to see example firmware. ]");
			
		} else if (protocol.get() == Protocol.CSV && getDatasetCount() > 0) {
		
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
			String printfIntFormatString   = IntStream.rangeClosed(0, datasets.get(datasets.size() - 1).location.get())
			                                          .mapToObj(location -> getDatasetByLocation(location) == null ? "0" : "%d")
			                                          .collect(Collectors.joining(","));                                              // example: "%d,%d" or "%d,0,%d" if sparse
			String printfFloatFormatString = IntStream.rangeClosed(0, datasets.get(datasets.size() - 1).location.get())
			                                          .mapToObj(location -> getDatasetByLocation(location) == null ? "0" : "%f")
			                                          .collect(Collectors.joining(","));                                              // example: "%f,%f" or "%f,0,%f" if sparse
			int printfIntStringLength      = IntStream.rangeClosed(0, datasets.get(datasets.size() - 1).location.get())
			                                          .map(location -> getDatasetByLocation(location) == null ? 2 : 7)
			                                          .sum() + 1;                                                                     // 2 bytes per unused location, 7 bytes per location, +1 for the null terminator
			int printfFloatStringLength    = IntStream.rangeClosed(0, datasets.get(datasets.size() - 1).location.get())
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