import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.event.WindowAdapter;
import java.io.File;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public class Main {

	static final String versionString = "Telemetry Viewer v0.8";
	static final String versionDate   = "2021-07-24";
	
	@SuppressWarnings("serial")
	static JFrame window = new JFrame(versionString) {
		
		int dataStructureViewWidth = -1;
		@Override public Dimension getPreferredSize() {
			if(dataStructureViewWidth < 0)
				dataStructureViewWidth = ConnectionsController.telemetryConnections.get(0).getDataStructureGui().getPreferredSize().width
				                         + new JScrollBar().getPreferredSize().width
				                         + Theme.padding;
			
			int communicationViewWidth  = CommunicationView.instance.getPreferredSize().width;
			int communicationViewHeight = CommunicationView.instance.getPreferredSize().height;
			int settingsViewHeight      = SettingsView.instance.getPreferredSize().height;
			
			int width = Integer.max(dataStructureViewWidth, communicationViewWidth + 8*Theme.padding);
			int height = settingsViewHeight + communicationViewHeight + (8 * Theme.padding);
			return new Dimension(width, height);
		}
		
		@Override public Dimension getMinimumSize() {
			return getPreferredSize();
		}
		
	};
	
	/**
	 * Entry point for the program.
	 * This just creates and configures the main window.
	 * 
	 * @param args    Command line arguments (not currently used.)
	 */
	@SuppressWarnings("serial")
	public static void main(String[] args) {
		
		// use the native OS theme when drawing Swing widgets
		// this also requires some workarounds for Linux users:
		//    1. getSystemLookAndFeelClassName() only uses the GTK theme for Gnome users, but it should be used for *all* Linux users.
		//    2. the GTK theme defaults to always drawing numbers *above* JSliders, but that is bad because numbers are *also* drawn *below* JSliders.
		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
			UIManager.setLookAndFeel("com.sun.java.swing.plaf.gtk.GTKLookAndFeel");
			UIManager.put("Slider.paintValue", false);
		} catch(Exception e){}
		
		// create the cache folder
		Path cacheDir = Paths.get("cache");
		try { Files.createDirectory(cacheDir); } catch(FileAlreadyExistsException e) {} catch(Exception e) { e.printStackTrace(); }
		
		SwingUtilities.invokeLater(() -> {
			
			// populate the window
			window.setLayout(new BorderLayout());
			window.add(OpenGLChartsView.instance,  BorderLayout.CENTER);
			window.add(SettingsView.instance,      BorderLayout.WEST);
			window.add(CommunicationView.instance, BorderLayout.SOUTH);
			window.add(ConfigureView.instance,     BorderLayout.EAST);
			
			window.setSize(window.getPreferredSize());
			window.setMinimumSize(window.getMinimumSize());
			window.setLocationRelativeTo(null);
	//		window.setExtendedState(JFrame.MAXIMIZED_BOTH);
			
			// allow the user to drag-n-drop settings/CSV/camera files
			window.setDropTarget(new DropTarget() {			
				@Override public void drop(DropTargetDropEvent event) {
					try {
						event.acceptDrop(DnDConstants.ACTION_LINK);
						@SuppressWarnings("unchecked")
						List<File> files = (List<File>) event.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
						List<String> filepaths = files.stream().map(file -> file.getAbsolutePath()).toList();
						ConnectionsController.importFiles(filepaths);
					} catch(Exception e) {
						NotificationsController.showFailureUntil("Error while processing files: " + e.getMessage(), () -> false, true);
						e.printStackTrace();
					}
				}
			});
			
			// automatically import settings/CSV/camera files if their names start with "default" and are located in the current working directory
			List<String> files = Stream.of(new File(".").list()).filter(file -> file.equals("default.txt") || 
			                                                                   (file.startsWith("default - connection ") && file.endsWith(".csv")) ||
			                                                                   (file.startsWith("default - connection ") && file.endsWith(".mkv"))).toList();
			if(!files.contains("default.txt")) {
				NotificationsController.showHintUntil("Start by connecting to a device or opening a file by using the buttons below.", () -> false, true);
			} else {
				ConnectionsController.importFiles(files);
				SwingUtilities.invokeLater(() -> {
					if(!ConnectionsController.telemetryPossible()) { // FIXME: have importFile return a boolean, because a camera may not IMMEDIATELY connect, so this test can fail when everything is fine
						NotificationsController.showFailureUntil("Error while automatically importing files.", () -> false, true);
						NotificationsController.showHintUntil("Start by connecting to a device or opening a file by using the buttons below.", () -> false, true);
					} else {
						String connectionNames = ConnectionsController.allConnections.stream().map(connection -> connection.name.get()).collect(Collectors.joining(" and "));
						String successMessage  = (files.size() == 1 ? "Automatically connected to " : "Automatically imported ") + connectionNames;
						long expirationTimestamp = System.currentTimeMillis() + 5000;
						NotificationsController.showHintUntil(successMessage, () -> System.currentTimeMillis() > expirationTimestamp, true);		
					}
				});
			}
			
			// handle window close events
			window.addWindowListener(new WindowAdapter() {
				@Override public void windowClosing(java.awt.event.WindowEvent windowEvent) {
					
					// cancel importing
					if(ConnectionsController.importing)
						ConnectionsController.cancelImporting();
					
					// cancel exporting if the user confirms it
					if(ConnectionsController.exporting) {
						int result = JOptionPane.showConfirmDialog(window, "Exporting in progress. Exit anyway?", "Confirm", JOptionPane.YES_NO_OPTION);
						if(result == JOptionPane.YES_OPTION)
							ConnectionsController.cancelExporting();
						else
							return; // don't close
					}
					
					// close connections and remove their cache files
					ConnectionsController.allConnections.forEach(connection -> connection.dispose());
					try { Files.deleteIfExists(cacheDir); } catch(Exception e) { }
					
					// die
					window.dispose();
					System.exit(0);
					
				}
			});
			
			// show the window
			window.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE); // so the windowClosing listener can cancel the close
			window.setVisible(true);
			
		});
		
	}
	
	/**
	 * Hides the charts and settings panels, then shows the data structure screen in the middle of the main window.
	 * This method is thread-safe.
	 */
	public static void showConfigurationGui(JPanel gui) {
		
		SwingUtilities.invokeLater(() -> {
			OpenGLChartsView.instance.animator.pause();
			CommunicationView.instance.showSettings(false);
			ConfigureView.instance.close();
			window.remove(OpenGLChartsView.instance);
			window.add(gui, BorderLayout.CENTER);
			window.revalidate();
			window.repaint();
		});
		
	}
	
	/**
	 * Hides the data structure screen and shows the charts in the middle of the main window.
	 * This method is thread-safe.
	 */
	public static void hideConfigurationGui() {
		
		SwingUtilities.invokeLater(() -> {
			ConnectionsController.telemetryConnections.forEach(connection -> window.remove(connection.getDataStructureGui()));
			window.add(OpenGLChartsView.instance, BorderLayout.CENTER);
			window.revalidate();
			window.repaint();
			OpenGLChartsView.instance.animator.resume();
		});
		
	}

}
