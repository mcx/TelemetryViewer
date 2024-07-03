import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.swing.SwingUtilities;

public class ConnectionTelemetryUDP extends ConnectionTelemetry {
	
	public ConnectionTelemetryUDP() {
		
		widgetsList.add(name.set("UDP"));
		widgetsList.add(portNumber);
		widgetsList.add(protocol.removeValue(Protocol.TC66));
		widgetsList.add(sampleRate);
		
	}

	@Override public void connectLive(boolean showGui) {
		
		previousSampleCountTimestamp = 0;
		previousSampleCount = 0;
		
		if(showGui)
			setFieldsDefined(false);
		
		receiverThread = new Thread(() -> {
			
			DatagramSocket udpListener = null;
			Field.Type checksumProcessor = fields.values().stream().filter(Field::isChecksum).map(field -> field.type.get()).findFirst().orElse(null);
			SharedByteStream stream = new SharedByteStream(ConnectionTelemetryUDP.this, checksumProcessor);
			
			// start the UDP listener
			try {
				udpListener = new DatagramSocket(portNumber.get());
				udpListener.setSoTimeout(1000);
				udpListener.setReceiveBufferSize(67108864); // 64MB
			} catch (Exception e) {
				try { udpListener.close(); } catch(Exception e2) {}
				SwingUtilities.invokeLater(() -> disconnect("Unable to start the UDP listener. Make sure another program is not already using port " + portNumber.get() + "."));
				return;
			}
			
			setConnected(true);
			
			if(showGui)
				Main.showConfigurationGui(getDataStructureGui());
			
			startProcessingTelemetry(stream);
			
			// listen for packets
			byte[] buffer = new byte[65507]; // max packet size: 65535 - (8byte UDP header) - (20byte IP header)
			DatagramPacket udpPacket = new DatagramPacket(buffer, buffer.length);
			while(true) {

				try {
					
					if(Thread.interrupted() || !isConnected())
						throw new InterruptedException();
					
					udpListener.receive(udpPacket);
					stream.write(buffer, udpPacket.getLength());
					
				} catch(SocketTimeoutException ste) {
					
					// a client never sent a packet, so do nothing and let the loop try again.
					NotificationsController.printDebugMessage("UDP socket timed out while waiting for a packet.");
					
				} catch(IOException ioe) {
					
					// an IOException can occur if an InterruptedException occurs while receiving data
					// let this be detected by the connection test in the loop
					if(!isConnected())
						continue;
					
					// problem while reading from the socket
					stopProcessingTelemetry();
					try { udpListener.close(); }   catch(Exception e) {}
					SwingUtilities.invokeLater(() -> disconnect("UDP packet error."));
					return;
					
				}  catch(InterruptedException ie) {
					
					stopProcessingTelemetry();
					try { udpListener.close(); }   catch(Exception e) {}
					return;
					
				}
			
			}
			
		});
		
		receiverThread.setPriority(Thread.MAX_PRIORITY);
		receiverThread.setName("UDP Listener Thread");
		receiverThread.start();
		
	}
	
	@Override public List<Widget> getConfigurationWidgets() {
		
		boolean isEnabled = !isConnected() && !ConnectionsController.importing && !ConnectionsController.exporting;
		
		return List.of(sampleRate.setEnabled(isEnabled),
		               protocol.setEnabled(isEnabled),
		               portNumber.setEnabled(isEnabled));
		
	}
	
	@Override public Map<String, String> getExampleCode() {
		
		if(protocol.get() == Protocol.CSV && getDatasetCount() == 0) {
			
			return Map.of("Arduino/ESP8266 Firmware", "[ Define at least one CSV column to see example firmware. ]");
			
		} else if (protocol.get() == Protocol.CSV && getDatasetCount() > 0) {
		
			int baudRate                   = 9600;
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
			
			return Map.of("Arduino/ESP8266 Firmware", """
				void setup() {
					pinMode(LED_BUILTIN, OUTPUT);
					Serial.begin(%d);
					
					if(esp8266_test_communication() &&
					   esp8266_reset() &&
					   esp8266_client_mode() &&
					   esp8266_join_ap("wifi_network_name_here", "wifi_password_here") && // EDIT THIS LINE
					   esp8266_start_udp("%s", %d)) { // EDIT THIS LINE
					
						// success, turn on LED
						digitalWrite(LED_BUILTIN, HIGH);
						
					} else {
					
						// failure, blink LED
						while(true) {
							digitalWrite(LED_BUILTIN, HIGH);
							delay(1000);
							digitalWrite(LED_BUILTIN, LOW);
							delay(1000);
						}
						
					}
				}
				
				// use this loop if sending integers
				void loop() {
				%s
					char text[%d];
					snprintf(text, %d, "%s", %s);
					esp8266_transmit_udp(text);
				}
				
				// or use this loop if sending floats
				void loop() {
				%s
				%s
				%s
					char text[%d];
					snprintf(text, %d, "%s", %s);
					esp8266_transmit_udp(text);
				}
				
				#define MAX_COMMAND_TIME  10000 // milliseconds
				
				bool esp8266_test_communication(void) {
					delay(500); // wait for module to boot up
					Serial.print("AT\\r\\n");
					unsigned long startTime = millis();
					while(true) {
						if(Serial.find("OK"))
							return true;
						if(millis() > startTime + MAX_COMMAND_TIME)
							return false;
					}
				}
				
				bool esp8266_reset(void) {
					Serial.print("AT+RST\\r\\n");
					unsigned long startTime = millis();
					while(true) {
						if(Serial.find("ready"))
							return true;
						if(millis() > startTime + MAX_COMMAND_TIME)
							return false;
					}
				}
				
				bool esp8266_client_mode(void) {
					Serial.print("AT+CWMODE=1\\r\\n");
					unsigned long startTime = millis();
					while(true) {
						if(Serial.find("OK"))
							return true;
						if(millis() > startTime + MAX_COMMAND_TIME)
							return false;
					}
				}
				
				bool esp8266_join_ap(String ssid, String password) {
					Serial.print("AT+CWJAP=\\"" + ssid + "\\",\\\"" + password + "\\"\\r\\n");
					unsigned long startTime = millis();
					while(true) {
						if(Serial.find("WIFI CONNECTED"))
							break;
						if(millis() > startTime + MAX_COMMAND_TIME)
							return false;
					}
					while(true) {
						if(Serial.find("WIFI GOT IP"))
							break;
						if(millis() > startTime + MAX_COMMAND_TIME)
							return false;
					}
					while(true) {
						if(Serial.find("OK"))
							return true;
						if(millis() > startTime + MAX_COMMAND_TIME)
							return false;
					}
				}
				
				bool esp8266_start_udp(String ip_address, int port_number) {
					Serial.print("AT+CIPSTART=\\"UDP\\",\\"" + ip_address + "\\"," + port_number + "\\r\\n");
					unsigned long startTime = millis();
					while(true) {
						if(Serial.find("CONNECT"))
							break;
						if(millis() > startTime + MAX_COMMAND_TIME)
							return false;
					}
					while(true) {
						if(Serial.find("OK"))
							return true;
						if(millis() > startTime + MAX_COMMAND_TIME)
							return false;
					}
				}
				
				bool esp8266_transmit_udp(String text) {
					Serial.print("AT+CIPSEND=" + String(text.length()) + "\\r\\n");
					unsigned long startTime = millis();
					while(true) {
						if(Serial.find("OK"))
							break;
						if(millis() > startTime + MAX_COMMAND_TIME)
							return false;
					}
					while(true) {
						if(Serial.find(">"))
							break;
						if(millis() > startTime + MAX_COMMAND_TIME)
							return false;
					}
					Serial.print(text);
					while(true) {
						if(Serial.find("SEND OK"))
							return true;
						if(millis() > startTime + MAX_COMMAND_TIME)
							return false;
					}
				}
				""".formatted(baudRate,
				              ConnectionTelemetry.localIp,
				              portNumber.get(),
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
				              printfFloatArgs),
				
				"Java Software", """
				import java.net.DatagramPacket;
				import java.net.DatagramSocket;
				import java.net.InetAddress;
				
				public class Main {
				
					public static void main(String[] args) throws InterruptedException {
					
						// enter an infinite loop that binds a UDP socket
						while(true) {
						
							try(DatagramSocket socket = new DatagramSocket()) {
							
								// enter another infinite loop that sends packets of telemetry
								while(true) {
								%s
									byte[] buffer = String.format("%s\\n", %s).getBytes();
									DatagramPacket packet = new DatagramPacket(buffer, 0, buffer.length, InetAddress.getByName("%s"), %d); // EDIT THIS LINE
									socket.send(packet);
									Thread.sleep(%d);
								}
								
							} catch(Exception e) {
							
								Thread.sleep(3000);
								
							}
							
						}
						
					}
					
				}
				""".formatted(names.stream().map(name -> "\t\t\t\t\tfloat " + name + " = ...; // EDIT THIS LINE\n").collect(Collectors.joining()),
				              printfFloatFormatString,
				              printfIntArgs,
				              ConnectionTelemetry.localIp,
				              portNumber.get(),
				              Math.round(1000.0 / getSampleRate())));
		
		} else if(protocol.get() == Protocol.BINARY && getDatasetCount() == 0) {
			
			return Map.of("Java Software", "[ Define at least one dataset to see example software. ]");
			
		} else if (protocol.get() == Protocol.BINARY && getDatasetCount() > 0) {
			
			int datasetsCount = getDatasetCount();
			int byteCount = (datasetsCount > 0) ? getDatasetByIndex(datasetsCount - 1).location.get() + getDatasetByIndex(datasetsCount - 1).type.get().getByteCount() :
			                                      0;
			String argumentsText = getDatasetsList().stream().map(dataset -> dataset.type.get().getJavaTypeName() + " " + dataset.name.get().replace(' ', '_'))
			                                                 .map(String::toLowerCase)
			                                                 .collect(Collectors.joining(", "));
			
			String fieldLines = fields.values().stream().<String>mapMulti((field, consumer) -> {
			                                                if(field.isSyncWord()) {
			                                                	consumer.accept("\t\t\tbuffer.put((byte) %s);".formatted(field.name.get()));
			                                                	consumer.accept("\t\t\t");
			                                                } else if(field.isDataset()) {
			                                                    consumer.accept("\t\t\tbuffer.position(%d);".formatted(field.location.get()));
			                                                    consumer.accept("\t\t\tbuffer.order(ByteOrder.%s);".formatted(field.type.get().isLittleEndian() ? "LITTLE_ENDIAN" : "BIG_ENDIAN"));
			                                                    consumer.accept("\t\t\tbuffer.put%s(%s);".formatted(field.type.get().getJavaTypeName().replace("Byte", ""),
			                                                                                                        field.name.get().replace(' ', '_').toLowerCase()));
			                                                    consumer.accept("\t\t\t");
			                                                } else if(field.isChecksum()) {
			                                                	consumer.accept("\t\t\t%s checksum = 0;".formatted(field.type.get().getByteCount() == 1 ? "byte" : "short"));
			                                                	int startByteOffset = fields.get(0).isSyncWord() ? fields.get(0).type.get().getByteCount() : 0;
			                                                	consumer.accept("\t\t\tbuffer.position(%d);".formatted(startByteOffset));
			                                                	consumer.accept("\t\t\tbuffer.order(ByteOrder.%s);".formatted(field.type.get().isLittleEndian() ? "LITTLE_ENDIAN" : "BIG_ENDIAN"));
			                                                	int endByteOffset = field.location.get();
			                                                	int payloadByteCount = endByteOffset - startByteOffset;
			                                                	int payloadWordCount = payloadByteCount / field.type.get().getByteCount();
			                                                	consumer.accept("\t\t\tfor(int i = 0; i < %d; i++)".formatted(payloadWordCount));
			                                                	consumer.accept("\t\t\t\tchecksum += buffer.get%s();".formatted(field.type.get().getByteCount() == 1 ? "" : "Short"));
			                                                	consumer.accept("\t\t\tbuffer.put%s(checksum);".formatted(field.type.get().getByteCount() == 1 ? "" : "Short"));
			                                                }
			                                            })
			                                            .collect(Collectors.joining("\n"));
			
			String generatorArguments = fields.values().stream().filter(Field::isDataset)
			                                                    .map(field -> switch(field.type.get().getJavaTypeName()) {
			                                                        case "Byte"   -> "(byte) rng.nextInt()";
			                                                        case "Short"  -> "(short) rng.nextInt()";
			                                                        case "Int"    -> "rng.nextInt()";
			                                                        case "Long"   -> "rng.nextLong()";
			                                                        case "Float"  -> "rng.nextFloat()";
			                                                        case "Double" -> "rng.nextDouble()";
			                                                        default -> "";
			                                                    })
			                                                    .collect(Collectors.joining("\n\t\t\t              ")); // one arg per line
			
			return Map.of("Java Software", """
				// note: Java doesn't support unsigned numbers, so ensure the underlying bits are actually what you expect
				
				import java.net.InetSocketAddress;
				import java.nio.ByteBuffer;
				import java.nio.ByteOrder;
				import java.nio.channels.DatagramChannel;
				import java.util.Random;
				
				public class Main {
				
					static DatagramChannel channel;
					static ByteBuffer buffer = ByteBuffer.allocateDirect(%d);
					
					public static void sendTelemetry(%s) {
						
						try {
							
							if(channel == null)
								channel = DatagramChannel.open();
							
							buffer.clear();
							
				%s
							channel.send(buffer.flip(), new InetSocketAddress("%s", %d)); // EDIT THIS LINE
							
						} catch(Exception e) {
							
							System.err.println("Error while attempting to send telemetry:");
							e.printStackTrace();
							
						}
					
					}
					
					// EDIT THIS FUNCTION
					// THIS DEMO IS AN INFINITE LOOP SENDING RANDOM NUMBERS FOR EACH DATASET AT ROUGHLY 1kHz
					public static void main(String[] args) {
						
						Random rng = new Random();
						
						while(true) {
							sendTelemetry(%s);
							try { Thread.sleep(1); } catch(Exception e) {}
						}
						
					}
					
				}
				""".formatted(byteCount,
				              argumentsText,
				              fieldLines,
				              ConnectionTelemetry.localIp,
				              portNumber.get(),
				              generatorArguments));
		
		} else {
			
			return Map.of(); // no example firmware
			
		}
		
	}
	
	@Override protected boolean supportsTransmitting()             { return false; }
	@Override protected boolean supportsUserDefinedDataStructure() { return true;  }

}
