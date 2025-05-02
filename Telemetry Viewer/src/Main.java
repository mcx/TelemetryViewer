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
				dataStructureViewWidth = Connections.telemetryConnections.get(0).getDataStructureGui().getPreferredSize().width
				                         + new JScrollBar().getPreferredSize().width
				                         + Theme.padding;
			
			int connectionsGuiWidth  = Connections.GUI.getPreferredSize().width;
			int connectionsGuiHeight = Connections.GUI.getPreferredSize().height;
			int settingsGuiHeight    =    Settings.GUI.getPreferredSize().height;
			
			int width = Integer.max(dataStructureViewWidth, connectionsGuiWidth + 8*Theme.padding);
			int height = settingsGuiHeight + connectionsGuiHeight + (8 * Theme.padding);
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
			window.add(OpenGLCharts.GUI, BorderLayout.CENTER);
			window.add(Settings.GUI,     BorderLayout.WEST);
			window.add(Connections.GUI,  BorderLayout.SOUTH);
			window.add(Configure.GUI,    BorderLayout.EAST);
			
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
						Connections.importFiles(filepaths);
					} catch(Exception e) {
						Notifications.showFailureUntil("Error while processing files: " + e.getMessage(), () -> false, true);
						e.printStackTrace();
					}
				}
			});
			
			// automatically import settings/CSV/MKV files if their names start with "default" and are located in the current working directory
			List<String> files = Stream.of(new File(".").list()).filter(file -> file.equals("default.txt") || 
			                                                                   (file.startsWith("default - connection ") && file.endsWith(".csv")) ||
			                                                                   (file.startsWith("default - connection ") && file.endsWith(".mkv"))).toList();
			if(!files.contains("default.txt"))
				Notifications.showHintUntil("Start by connecting to a device or opening a file by using the buttons below.", () -> false, true);
			else
				Connections.importFiles(files);
			
			// handle window close events
			window.addWindowListener(new WindowAdapter() {
				@Override public void windowClosing(java.awt.event.WindowEvent windowEvent) {
					
					// cancel importing
					if(Connections.importing)
						Connections.cancelImporting();
					
					// cancel exporting if the user confirms it
					if(Connections.exporting) {
						int result = JOptionPane.showConfirmDialog(window, "Exporting in progress. Exit anyway?", "Confirm", JOptionPane.YES_NO_OPTION);
						if(result == JOptionPane.YES_OPTION)
							Connections.cancelExporting();
						else
							return; // don't close
					}
					
					// close connections and remove their cache files
					Connections.allConnections.forEach(connection -> connection.dispose());
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
	public static void showDataStructureGui(ConnectionTelemetry connection) {
		
		SwingUtilities.invokeLater(() -> {
			OpenGLCharts.GUI.animator.pause();
			Connections.GUI.showSettings(false);
			Configure.GUI.close();
			window.remove(OpenGLCharts.GUI);
			window.add(connection.getDataStructureGui(), BorderLayout.CENTER);
			window.revalidate();
			window.repaint();
		});
		
	}
	
	/**
	 * Hides the data structure screen and shows the charts in the middle of the main window.
	 * This method is thread-safe.
	 */
	public static void hideDataStructureGui(ConnectionTelemetry connection) {
		
		SwingUtilities.invokeLater(() -> {
			JPanel dsGui = connection.getDataStructureGui();
			boolean configGuiVisible = dsGui.getParent() != null;
			if(configGuiVisible) {
				window.remove(dsGui);
				window.add(OpenGLCharts.GUI, BorderLayout.CENTER);
				window.revalidate();
				window.repaint();
				OpenGLCharts.GUI.animator.resume();
			}
		});
		
	}

}
