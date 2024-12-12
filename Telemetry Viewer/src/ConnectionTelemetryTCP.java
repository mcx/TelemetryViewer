import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.swing.SwingUtilities;

public class ConnectionTelemetryTCP extends ConnectionTelemetry {
	
	private final int MAX_TCP_IDLE_MILLISECONDS = 10000; // if connected but no new samples after than much time, disconnect and wait for a new connection
	
	public ConnectionTelemetryTCP() {
		
		widgetsList.add(name.set("TCP"));
		widgetsList.add(portNumber);
		widgetsList.add(protocol.removeValue(Protocol.TC66));
		widgetsList.add(sampleRate);
		
	}

	@Override public void connectLive(boolean showGui) {
		
		previousSampleCountTimestamp = 0;
		previousSampleCount = 0;
		calculatedSamplesPerSecond = 0;
		
		if(showGui)
			setFieldsDefined(false);
		
		receiverThread = new Thread(() -> {
			
			ServerSocket tcpServer = null;
			Socket tcpSocket = null;
			Field.Type checksumProcessor = fields.values().stream().filter(Field::isChecksum).map(field -> field.type.get()).findFirst().orElse(null);
			SharedByteStream stream = new SharedByteStream(ConnectionTelemetryTCP.this, checksumProcessor);
			
			// start the TCP server
			try {
				tcpServer = new ServerSocket(portNumber.get());
				tcpServer.setSoTimeout(1000);
			} catch (Exception e) {
				try { tcpServer.close(); } catch(Exception e2) {}
				SwingUtilities.invokeLater(() -> disconnect("Unable to start the TCP server. Another program might already be using port " + portNumber.get() + "."));
				return;
			}
			
			setConnected(true);
			
			if(showGui)
				Main.showConfigurationGui(getDataStructureGui());
			
			startProcessingTelemetry(stream);
			
			// listen for a connection
			while(true) {

				try {
					
					if(Thread.interrupted() || !isConnected())
						throw new InterruptedException();
					
					tcpSocket = tcpServer.accept();
					tcpSocket.setSoTimeout(5000); // each valid packet of data must take <5 seconds to arrive
					InputStream is = tcpSocket.getInputStream();

					NotificationsController.showVerboseForMilliseconds("TCP connection established with a client at " + tcpSocket.getRemoteSocketAddress().toString().substring(1) + ".", 5000, true); // trim leading "/" from the IP address
					
					// enter an infinite loop that checks for activity. if the TCP port is idle for >10 seconds, abandon it so another device can try to connect.
					long previousTimestamp = System.currentTimeMillis();
					int previousSampleNumber = getSampleCount();
					while(true) {
						int byteCount = is.available();
						if(byteCount > 0) {
							byte[] buffer = new byte[byteCount];
							is.read(buffer, 0, byteCount);
							stream.write(buffer, byteCount);
							continue;
						}
						Thread.sleep(1);
						int sampleNumber = getSampleCount();
						long timestamp = System.currentTimeMillis();
						if(sampleNumber > previousSampleNumber) {
							previousSampleNumber = sampleNumber;
							previousTimestamp = timestamp;
						} else if(previousTimestamp < timestamp - MAX_TCP_IDLE_MILLISECONDS) {
							NotificationsController.showFailureForMilliseconds("The TCP connection was idle for too long. It has been closed so another device can connect.", 5000, true);
							tcpSocket.close();
							break;
						}
					}
					
				} catch(SocketTimeoutException ste) {
					
					// a client never connected, so do nothing and let the loop try again.
					NotificationsController.showVerboseForMilliseconds("TCP socket timed out while waiting for a connection.", 5000, true);
					
				} catch(IOException ioe) {
					
					// an IOException can occur if an InterruptedException occurs while receiving data
					// let this be detected by the connection test in the loop
					if(!isConnected())
						continue;
					
					// problem while accepting the socket connection, or getting the input stream, or reading from the input stream
					stopProcessingTelemetry();
					try { tcpSocket.close(); } catch(Exception e2) {}
					try { tcpServer.close(); } catch(Exception e2) {}
					SwingUtilities.invokeLater(() -> disconnect("TCP connection failed."));
					return;
					
				}  catch(InterruptedException ie) {
					
					stopProcessingTelemetry();
					try { tcpSocket.close(); } catch(Exception e2) {}
					try { tcpServer.close(); } catch(Exception e2) {}
					return;
					
				}
			
			}
			
		});
		
		receiverThread.setPriority(Thread.MAX_PRIORITY);
		receiverThread.setName("TCP Server");
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
			
			return Map.of("Java Software", "[ Define at least one CSV column to see example software. ]");
			
		} else if (protocol.get() == Protocol.CSV && getDatasetCount() > 0) {
		
			List<Field> datasets         = getDatasetsList();
			List<String> names             = datasets.stream().map(dataset -> dataset.name.get().toLowerCase().replace(' ', '_')).toList(); // example: "a" "b"
			String printfIntArgs           = names.stream().collect(Collectors.joining(", "));                                                            // example: "a, b"
			String printfFloatFormatString = IntStream.rangeClosed(0, datasets.get(datasets.size() - 1).location.get())
			                                          .mapToObj(location -> getDatasetByLocation(location) == null ? "0" : "%f")
			                                          .collect(Collectors.joining(","));                                                                  // example: "%f,%f" or "%f,0,%f" if sparse
			
			return Map.of("Java Software", """
				import java.io.PrintWriter;
				import java.net.Socket;
				
				public class Main {
				
					public static void main(String[] args) throws InterruptedException {
					
						// enter an infinite loop that tries to connect to the TCP server once every 3 seconds
						while(true) {
						
							try(Socket socket = new Socket("%s", %d)) { // EDIT THIS LINE
							
								// enter another infinite loop that sends packets of telemetry
								PrintWriter output = new PrintWriter(socket.getOutputStream(), true);
								while(true) {
								%s
									output.println(String.format("%s", %s));
									if(output.checkError())
										throw new Exception();
									Thread.sleep(%d);
								}
								
							} catch(Exception e) {
							
								Thread.sleep(3000);
								
							}
							
						}
						
					}
					
				}
				""".formatted(ConnectionTelemetry.localIp,
				              portNumber.get(),
				              names.stream().map(name -> "\t\t\t\t\tfloat " + name + " = ...; // EDIT THIS LINE\n").collect(Collectors.joining()),
				              printfFloatFormatString,
				              printfIntArgs,
				              Math.round(1000.0 / getSampleRate()),
				              1));
		
		} else {
			
			return Map.of(); // no example firmware
			
		}
		
	}
	
	@Override protected boolean supportsTransmitting()             { return false; }
	@Override protected boolean supportsUserDefinedDataStructure() { return true;  }

}
