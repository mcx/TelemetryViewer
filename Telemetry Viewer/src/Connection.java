import java.awt.Dimension;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import net.miginfocom.swing.MigLayout;

public abstract class Connection {
	
	Thread receiverThread;    // listens for data from a device
	Thread processorThread;   // optional, processes the received data
	Thread transmitterThread; // optional, sends data to a device
	
	// concrete class constructors MUST call name.set() to select an appropriate option
	final WidgetCombobox<String> name = new WidgetCombobox<String>(null, Connections.getDevicesStream(this).map(device -> device.name()).toList(), null)
	                                        .setExportLabel("type")
	                                        .onChange((newName, oldName) -> {
	                                            // accept and ignore if no change
	                                            if(newName.equals(oldName) || oldName == null)
	                                                return true;
	                                            
	                                            // reject change if the device is not available
	                                            var newDevice = Connections.getDevicesStream(this)
	                                                                       .filter(device -> device.name().equals(newName) && device.isAvailable())
	                                                                       .findFirst().orElse(null);
	                                            if(newDevice == null)
	                                                return false;
	                                            
	                                            // no need to replace this connection if just changing between UART ports
	                                            if(oldName.startsWith("UART") && newName.startsWith("UART")) {
	                                                Settings.GUI.redraw(); // so the TX GUI shows the new port name
	                                                return true;
	                                            }
	                                            
	                                            // replace this connection
	                                            Connections.replaceConnection(this, newDevice.connection().get());
	                                            return true;
	                                        });
	
	final List<Widget> configWidgets = new ArrayList<Widget>();
	private final JButton connectButton;
	private final JButton removeButton;
	final List<Widget> transmitWidgets = new ArrayList<Widget>();
	
	protected enum Status {
		// current status //                                  // text to show on the Connect button //
		DISCONNECTED      { @Override public String toString() { return "Connect";       } },
		CONNECTING        { @Override public String toString() { return "Connecting";    } },
		CONNECTED         { @Override public String toString() { return "Disconnect";    } },
		DISCONNECTING     { @Override public String toString() { return "Disconnecting"; } }
	}
	private volatile Status status = Status.DISCONNECTED;
	private volatile boolean reconnect = false;
	
	protected static final String maxSampleCountErrorMessage = "Reached maximum sample count. Disconnected.";

	@SuppressWarnings("serial")
	public Connection() {
		
		// once every 2 seconds: try to reconnect if the connection failed
		Timer reconnectTimer = new Timer(2000, event -> {
			if(!Connections.allConnections.contains(this)) {
				// connection removed, so stop this timer
				((Timer) event.getSource()).stop();
			} else if(reconnect && Settings.GUI.autoReconnect.isFalse()) {
				// auto reconnect disabled
				reconnect = false;
			} else if(reconnect && status == Status.DISCONNECTED) {
				// connection failed, try to reconnect 
				Notifications.printInfo("Attempting to reconnect to " + getName() + ".");
				connect(false);
			}
		});
		reconnectTimer.start();
		
		// create and configure the connect and remove buttons that will be shown in the configuration GUI
		connectButton = new JButton(status.toString()) {
			private final JButton dummyButton = new JButton("Disconnecting"); // used to give the connect button a fixed size so the Connection GUIs line up nicely
			@Override public Dimension getPreferredSize() {
				dummyButton.setBorder(Theme.narrowButtonBorder);
				return dummyButton.getPreferredSize();
			}
		};
		connectButton.addActionListener(event -> {
			reconnect = false; // if the user clicks the button, cancel automatic reconnecting
			if(status == Status.DISCONNECTED)
				connect(true);
			else if(status == Status.CONNECTED)
				disconnect(null, false);
		});
		connectButton.setBorder(Theme.narrowButtonBorder);
		removeButton = new JButton(Theme.removeSymbol);
		removeButton.setBorder(Theme.narrowButtonBorder);
		removeButton.addActionListener(event -> Connections.removeConnection(this));
		
	}
	
	/**
	 * @return    A user-friendly String describing this connection.
	 *            This will be shown to the user everywhere except the name combobox.
	 */
	abstract public String getName();
	
	/**
	 * @return    A GUI for configuring this connection, to be shown in the bottom panel.
	 */
	final public JPanel getConfigGUI() {
		
		JPanel panel = new JPanel(new MigLayout("hidemode 3, gap " + Theme.padding  + ", insets 0 " + Theme.padding + " 0 0"));
		configWidgets.reversed().forEach(widget -> widget.appendTo(panel, ""));
		panel.add(connectButton);
		panel.add(removeButton);
		return panel;
		
	}
	
	/**
	 * Updates the widgets in the configuration GUI. This should be called before showing the configuration GUI on screen.
	 */
	final public void updateConfigGUI() {
		
		boolean importingOrExporting = Connections.importing || Connections.exporting;
		configWidgets.forEach(widget -> widget.setEnabled(!importingOrExporting && status == Status.DISCONNECTED));
		connectButton.setText(status.toString());
		connectButton.setEnabled(!importingOrExporting && (status == Status.DISCONNECTED || status == Status.CONNECTED));
		removeButton.setVisible(!importingOrExporting && Connections.allConnections.size() > 1);
		if(Connections.importing)
			name.disableWithMessage("Importing [" + name.get() + "]");
		
	}
	
	/**
	 * This method is thread-safe.
	 * 
	 * Updates the connection status,
	 * redraws the bottom panel and left panels because they contain widgets that may need to be enabled or disabled,
	 * and optionally shows the data structure GUI. If the GUI is not shown, the user will be advised to add a chart if no charts exist.
	 * 
	 * @param newStatus               Connection status.
	 * @param showConfigurationGui    If the data structure GUI should be shown to the user.
	 */
	final synchronized protected void setStatus(Status newStatus, boolean showConfigurationGui) {
		
		// disconnect() might be called while CONNECTING, which will change state to DISCONNECTING
		// in that case, connectToDevice() might try to transition to CONNECTED but we should stay in the DISCONNECTING state in order to gracefully disconnect
		if(status == Status.DISCONNECTING && newStatus != Status.DISCONNECTED)
			return;
		
		status = newStatus;
		Connections.GUI.redraw(); // the import/export/configuration widgets will be enabled/disabled as needed
		Settings.GUI.redraw();    // the TX GUIs will be enabled/disabled as needed
		
		if(status == Status.CONNECTED && reconnect) {
			Notifications.removeIfConnectionRelated();
			Notifications.showHintForMilliseconds("Automatically reconnected to " + getName() + ".", 5000, true);
		} else if(status == Status.CONNECTED && showConfigurationGui && this instanceof ConnectionTelemetry c) {
			c.setFieldsDefined(false);
			Main.showDataStructureGui(c);
		} else if(status == Status.CONNECTED && !showConfigurationGui) {
			SwingUtilities.invokeLater(() -> { // invokeLater because if importing, the charts will be created next
				if(isConnected() && !Charts.exist()) // isConnected() because an error could have occurred while importing
					Notifications.showHintUntil("Add a chart by clicking on a tile, or click-and-dragging across multiple tiles.", () -> Charts.exist(), true);
			});
		}
		
	}
	
	final public boolean isConnected()    { return status == Status.CONNECTED;    }
	final public boolean isDisconnected() { return status == Status.DISCONNECTED; }
	
	/**
	 * Prepares to connect, then calls connectToDevice() to actually connect.
	 * 
	 * @param interactive    True if the user clicked the connect button.
	 *                       False if a settings file is being imported, or an automatic reconnect is being attempted.
	 */
	final public void connect(boolean interactive) {
		
		if(status != Status.DISCONNECTED)
			disconnect(null, true);
		
		// remove connection-related notifications if the user clicked the connect button
		if(interactive)
			Notifications.removeIfConnectionRelated();
		
		if(Connections.previouslyImported) {
			Connections.allConnections.forEach(Connection::removeAllData);
			Connections.previouslyImported = false;
		}
		
		connectToDevice(interactive);
		
	}
	
	/**
	 * Connects to a device and listens for incoming data.
	 * 
	 * @param interactive    True if the user clicked the connect button.
	 *                       False if a settings file is being imported, or an automatic reconnect is being attempted.
	 */
	abstract protected void connectToDevice(boolean interactive);
	
	/**
	 * "Connects to" (imports) data from a file.
	 * 
	 * @param path                       Path to the file.
	 * @param firstTimestamp             Timestamp when the first sample from ANY connection was acquired. This is used to allow importing to happen in real time.
	 * @param beginImportingTimestamp    Timestamp when all import threads should begin importing.
	 * @param completedByteCount         Variable to increment as progress is made (this is periodically queried by a progress bar.)
	 */
	abstract public void connectToFile(String path, long firstTimestamp, long beginImportingTimestamp, AtomicLong completedByteCount);
	
	/**
	 * Configures this connection by reading from a settings file.
	 * 
	 * @param lines              Lines of text from the settings file.
	 * @throws AssertionError    If the settings file does not contain a valid configuration.
	 */
	public abstract void importFrom(Connections.QueueOfLines lines) throws AssertionError;
	
	/**
	 * Saves the configuration to a settings file.
	 * 
	 * @param file    Destination file.
	 */
	public abstract void exportTo(PrintWriter file);
	
	/**
	 * Reads just enough from a data file to determine the timestamp of the first item.
	 * 
	 * @param path    Path to the file.
	 * @return        Timestamp for the first item, or Long.MAX_VALUE on error.
	 */
	public abstract long readFirstTimestamp(String path);
	
	/**
	 * Reads the timestamp for a specific sample number or frame number.
	 * 
	 * @param sampleNumber    The sample number or frame number.
	 * @return                Corresponding timestamp.
	 */
	public abstract long getTimestamp(int sampleNumber);
	
	/**
	 * @return    Timestamp of the first sample or frame, or 0 if none exist.
	 */
	public abstract long getFirstTimestamp();
	
	/**
	 * @return    Timestamp of the last sample or frame, or 0 if none exist.
	 */
	public abstract long getLastTimestamp();
	
	/**
	 * @return    The number of samples or frames available.
	 */
	public abstract int getSampleCount();
	
	/**
	 * Removes all samples or frames. This is a non-permanent version of dispose().
	 * This does not close the connection, so this code must ensure there is no race condition.
	 */
	public abstract void removeAllData();
	
	/**
	 * Writes data (samples or images) to a file, so it can be replayed later on.
	 * 
	 * @param path                  Path to the file.
	 * @param completedByteCount    Variable to increment as progress is made (this is periodically queried by a progress bar.)
	 */
	public abstract void exportDataFile(String path, AtomicLong completedByteCount);
	
	/**
	 * Gets a GUI for transmitting data to the connected device.
	 * 
	 * @return    The GUI, or null if a GUI is not supported.
	 */
	public abstract JPanel getUpdatedTransmitGUI();
	
	/**
	 * Permanently closes the connection and removes any cached data in memory or on disk.
	 */
	public abstract void dispose();
	
	/**
	 * Disconnects from the device.
	 * 
	 * @param errorMessage      If not null, show this as a Notification until a new connection is attempted.
	 * @param blockUntilDone    If true, this method will block until disconnected.
	 */
	final public void disconnect(String errorMessage, boolean blockUntilDone) {
		
		Runnable task = () -> {
			
			if(this instanceof ConnectionTelemetry connection)
				Main.hideDataStructureGui(connection);
			
			boolean wasConnected = (status == Status.CONNECTED);
			
			if(status == Status.CONNECTING && !blockUntilDone) {
				
				// we were trying to connect, but failed, so connectToDevice() called disconnect()
				
				// if this was an automatic reconnect, block for 500ms (so the connect button can say "connecting" long enough to see)
				if(reconnect)
					try { Thread.sleep(500); } catch(Exception e) {}
				
				// now we are disconnected and should only show an error message if this was NOT an automatic reconnect attempt
				setStatus(Status.DISCONNECTED, false);
				if(errorMessage != null && !reconnect)
					Notifications.showFailureUntil(errorMessage, () -> false, true);
				
			} else {
				
				// we were connecting/connected/disconnecting, but now we need to disconnect
				
				// start by flagging this connection as disconnecting, which will flag the TX/RX threads to gracefully end
				setStatus(Status.DISCONNECTING, false);
				
				// wait for the TX/RX threads to gracefully end
				if(transmitterThread != null && transmitterThread.isAlive())
					while(transmitterThread.isAlive()); // wait
				if(receiverThread != null && receiverThread.isAlive())
					while(receiverThread.isAlive()); // wait
				
				// we're now disconnected
				setStatus(Status.DISCONNECTED, false);
				
				// if we were connected, and there is an error message, the connection failed
				// try to reconnect if we are not importing, and if the failure was not the maximum-sample-count error
				if(wasConnected)
					reconnect = !Connections.importing &&
					            (errorMessage != null) &&
					            (errorMessage != maxSampleCountErrorMessage) &&
					            Settings.GUI.autoReconnect.isTrue();
				
				// show an error message if applicable
				if(errorMessage != null)
					Notifications.showFailureUntil(reconnect ? errorMessage + " Attempting to reconnect." : errorMessage, () -> false, true);
				else
					Notifications.removeIfConnectionRelated();
				
			}
			
		};
		
		if(blockUntilDone)
			task.run();
		else
			new Thread(task).start();
		
	}
	
}
