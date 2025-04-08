import java.awt.Toolkit;
import java.io.PrintWriter;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The user establishes one or more Connections, with each Connection providing a stream of data.
 * The data might be telemetry, or camera images, or ...
 * The data might be coming from a live connection, or imported from a file.
 * 
 * This parent class defines some abstract methods that must be implemented by each child class.
 * The rest of this class contains fields and methods that are used by some of the child classes.
 * Ideally there would be a deeper inheritance structure to divide some of this into even more classes, but for now everything just inherits from Connection.
 */
public abstract class Connection {
	
	Thread receiverThread;    // listens for incoming data
	Thread processorThread;   // processes the received data
	Thread transmitterThread; // sends data
	private volatile boolean connected = false;
	protected volatile WidgetCombobox<String> name;
	
	final public void setConnected(boolean isConnected) {
		connected = isConnected;
		CommunicationView.instance.redraw(); // redraw the bottom panel so the import/export/connection widgets can be enabled/disabled as needed
		SettingsView.instance.redraw();      // redraw the left panel so the TX GUIs can correctly indicate connection status
	}
	
	final public boolean isConnected() {
		return connected;
	}
	
	/**
	 * @return    The widgets for configuring this connection, except for the name combobox and connect/remove buttons.
	 *            This code should enable/disable the widgets as needed (widgets are usually disabled if connected, importing or exporting.)
	 */
	public abstract List<Widget> getConfigurationWidgets();
	
	/**
	 * Prepares to connect, then calls connectLive() to actually connect.
	 * 
	 * @param showGui    If true, show a configuration GUI after establishing the connection.
	 */
	public void connect(boolean showGui) {
		
		if(isConnected())
			disconnect(null);
		
		NotificationsController.removeIfConnectionRelated();
		
		if(ConnectionsController.previouslyImported) {
			ConnectionsController.allConnections.forEach(Connection::removeAllData);
			ConnectionsController.previouslyImported = false;
		}
		
		connectLive(showGui);
		
	}
	
	/**
	 * Connects and listens for incoming data.
	 * 
	 * @param showGui    If true, show a configuration GUI after establishing the connection.
	 */
	public abstract void connectLive(boolean showGui);
	
	/**
	 * Configures this connection by reading from a settings file.
	 * 
	 * @param lines              Lines of text from the settings file.
	 * @throws AssertionError    If the settings file does not contain a valid configuration.
	 */
	public abstract void importSettings(ConnectionsController.QueueOfLines lines) throws AssertionError;
	
	/**
	 * Saves the configuration to a settings file.
	 * 
	 * @param file    Destination file.
	 */
	public abstract void exportSettings(PrintWriter file);
	
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
	 * Reads data (samples or images or ...) from a file, instead of a live connection.
	 * 
	 * @param path                       Path to the file.
	 * @param firstTimestamp             Timestamp when the first sample from ANY connection was acquired. This is used to allow importing to happen in real time.
	 * @param beginImportingTimestamp    Timestamp when all import threads should begin importing.
	 * @param completedByteCount         Variable to increment as progress is made (this is periodically queried by a progress bar.)
	 */
	public abstract void importDataFile(String path, long firstTimestamp, long beginImportingTimestamp, AtomicLong completedByteCount);
	
	/**
	 * Causes the file import thread to finish importing the file as fast as possible (instead of using a real-time playback speed.)
	 * If it is already importing as fast as possible, this will instead cancel the process.
	 */
	public final void finishImporting() {
		
		if(ConnectionsController.realtimeImporting) {
			// currently importing real-time, switch to importing ASAP
			ConnectionsController.realtimeImporting = false;
			CommunicationView.instance.redraw();
		} else {
			// currently importing ASAP, so cancel importing
			setConnected(false);
			if(receiverThread != null)
				while(receiverThread.isAlive()); // wait
			NotificationsController.printDebugMessage("Importing... Canceled");
		}
		
	}
	
	/**
	 * Writes data (samples or images or ...) to a file, so it can be replayed later on.
	 * 
	 * @param path                  Path to the file.
	 * @param completedByteCount    Variable to increment as progress is made (this is periodically queried by a progress bar.)
	 */
	public abstract void exportDataFile(String path, AtomicLong completedByteCount);
	
	/**
	 * Permanently closes the connection and removes any cached data in memory or on disk.
	 */
	public abstract void dispose();
	
	/**
	 * If connected, disconnects from the device and removes any connection-related Notifications.
	 * This method blocks until disconnected, so it should not be called directly from the receiver thread.
	 * 
	 * @param errorMessage    If not null, show this as a Notification until a new connection is attempted. 
	 */
	public void disconnect(String errorMessage) {

		Main.hideConfigurationGui();
		
		if(connected) {
			
			// tell the receiver thread to terminate by setting the boolean AND interrupting the thread because
			// interrupting the thread might generate an IOException, but we don't want to report that as an error
			setConnected(false);
			if(transmitterThread != null && transmitterThread.isAlive()) {
				transmitterThread.interrupt();
				while(transmitterThread.isAlive()); // wait
			}
			if(receiverThread != null && receiverThread.isAlive()) {
				receiverThread.interrupt();
				while(receiverThread.isAlive()); // wait
			}
			
			NotificationsController.removeIfConnectionRelated();
			
		}
		
		if(errorMessage != null) {
			Toolkit.getDefaultToolkit().beep();
			NotificationsController.showFailureUntil(errorMessage, () -> false, true);
		}
		
	}
	
}
