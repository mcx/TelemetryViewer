import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;

public class ConnectionTelemetryUDP extends ConnectionTelemetry {
	
	public ConnectionTelemetryUDP() {
		
		configWidgets.add(name.set("UDP"));
		configWidgets.add(portNumber);
		configWidgets.add(protocol.removeValue(Protocol.TC66));
		configWidgets.add(sampleRate);
		
	}
	
	@Override public String getName() { return "UDP Port " + portNumber.get(); }

	@Override public void connectToDevice(boolean showGui) {
		
		receiverThread = new Thread(() -> {
		
			previousSampleCountTimestamp = 0;
			previousSampleCount = 0;
			calculatedSamplesPerSecond = 0;
			
			DatagramSocket udpListener = null;
			
			// start the UDP listener
			try {
				udpListener = new DatagramSocket(portNumber.get());
				udpListener.setSoTimeout(1000);
				udpListener.setReceiveBufferSize(67108864); // 64MB
			} catch (Exception e) {
				try { udpListener.close(); } catch(Exception e2) {}
				disconnect("Unable to start the UDP listener. Make sure another program is not already using port " + portNumber.get() + ".", false);
				return;
			}
			
			setStatus(Status.CONNECTED, showGui);
			SharedByteStream stream = new SharedByteStream(ConnectionTelemetryUDP.this);
			startProcessingTelemetry(stream);
			
			// listen for packets
			byte[] buffer = new byte[65507]; // max packet size: 65535 - (8byte UDP header) - (20byte IP header)
			DatagramPacket udpPacket = new DatagramPacket(buffer, buffer.length);
			while(true) {

				try {
					
					// stop if requested
					if(!isConnected())
						throw new Exception();
					
					udpListener.receive(udpPacket);
					stream.write(buffer, udpPacket.getLength());
					
				} catch(SocketTimeoutException ste) {
					
					// a client never sent a packet, so do nothing and let the loop try again.
					
				} catch(Exception e) {
					
					stopProcessingTelemetry();
					try { udpListener.close(); } catch(Exception e2) {}
					if(isConnected())
						disconnect("Error while reading from " + getName() + ".", false);
					return;
					
				}
			
			}
			
		});
		receiverThread.setPriority(Thread.MAX_PRIORITY);
		receiverThread.setName("UDP Listener Thread");
		receiverThread.start();
		
	}
	
	@Override protected boolean supportsTransmitting()             { return false; }
	@Override protected boolean supportsUserDefinedDataStructure() { return true;  }

}
