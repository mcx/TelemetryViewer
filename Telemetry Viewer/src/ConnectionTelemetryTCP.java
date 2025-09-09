import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;

public class ConnectionTelemetryTCP extends ConnectionTelemetry {
	
	private final int MAX_TCP_IDLE_MILLISECONDS = 10000; // if connected but no new samples after than much time, disconnect and wait for a new connection
	
	public ConnectionTelemetryTCP() {
		
		configWidgets.add(name.set("TCP"));
		configWidgets.add(portNumber);
		configWidgets.add(protocol.removeValue(Protocol.TC66));
		configWidgets.add(sampleRate);
		
	}
	
	@Override public String getName() { return "TCP Port " + portNumber.get(); }

	@Override public void connectToDevice(boolean showGui) {
		
		receiverThread = new Thread(() -> {
		
			previousSampleCountTimestamp = 0;
			previousSampleCount = 0;
			calculatedSamplesPerSecond = 0;
			
			ServerSocket tcpServer = null;
			Socket tcpSocket = null;
			
			// start the TCP server
			setStatus(Status.CONNECTING, false);
			try {
				tcpServer = new ServerSocket(portNumber.get());
				tcpServer.setSoTimeout(1000);
			} catch (Exception e) {
				try { tcpServer.close(); } catch(Exception e2) {}
				disconnect("Unable to start the TCP server. Another program might already be using port " + portNumber.get() + ".", false);
				return;
			}
			setStatus(Status.CONNECTED, showGui);
			SharedByteStream stream = new SharedByteStream(ConnectionTelemetryTCP.this);
			startProcessingTelemetry(stream);
			
			// listen for a connection
			while(true) {

				try {
					
					// stop if requested
					if(!isConnected())
						throw new Exception();
					
					tcpSocket = tcpServer.accept();
					tcpSocket.setSoTimeout(1000);
					InputStream is = tcpSocket.getInputStream();

					Notifications.printInfo("TCP connection established with a client at " + tcpSocket.getRemoteSocketAddress().toString().substring(1) + "."); // trim leading "/" from the IP address
					
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
							Notifications.showFailureForMilliseconds("The TCP connection was idle for too long. It has been closed so another device can connect.", 5000, true);
							tcpSocket.close();
							break;
						}
					}
					
				} catch(SocketTimeoutException ste) {
					
					// a client never connected, so do nothing and let the loop try again.
					
				} catch(Exception e) {
					
					stopProcessingTelemetry();
					try { tcpSocket.close(); } catch(Exception e2) {}
					try { tcpServer.close(); } catch(Exception e2) {}
					if(isConnected())
						disconnect("Error while reading from " + getName() + ".", false);
					return;
					
				}
			
			}
			
		});
		receiverThread.setPriority(Thread.MAX_PRIORITY);
		receiverThread.setName("TCP Server");
		receiverThread.start();
		
	}
	
	@Override protected boolean supportsTransmitting()             { return false; }
	@Override protected boolean supportsUserDefinedDataStructure() { return true;  }

}
