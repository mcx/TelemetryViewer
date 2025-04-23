import java.awt.Desktop;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;

import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class CommunicationView extends JPanel {
	
	static CommunicationView instance = new CommunicationView();
	
	private JToggleButton settingsButton;
	private JButton importButton;
	private JButton exportButton;
	private JButton helpButton;
	private JButton connectionButton;
	private List<Connection> previousConnections;

	/**
	 * Private constructor to enforce singleton usage.
	 */
	private CommunicationView () {
		
		super();
		setLayout(new MigLayout("wrap 6, gap " + Theme.padding  + ", insets " + Theme.padding, "[][][][]push[]push[]"));
		
		// settings
		settingsButton = new JToggleButton("Settings");
		settingsButton.setSelected(SettingsView.instance.isVisible());
		settingsButton.addActionListener(event -> showSettings(settingsButton.isSelected()));
		
		// import
		importButton = new JButton("Import");
		importButton.addActionListener(event -> {
			
			JFileChooser inputFiles = new JFileChooser(System.getProperty("user.dir"));
			inputFiles.setMultiSelectionEnabled(true);
			inputFiles.setFileFilter(new FileNameExtensionFilter("Settings (*.txt) Data (*.csv) or Videos (*.mkv)", "txt", "csv", "mkv"));
			JFrame parentWindow = (JFrame) SwingUtilities.windowForComponent(this);
			if(inputFiles.showOpenDialog(parentWindow) == JFileChooser.APPROVE_OPTION) {
				List<String> filepaths = Stream.of(inputFiles.getSelectedFiles()).map(file -> file.getAbsolutePath()).toList();
				Connections.importFiles(filepaths);
			}
			
		});
		
		// export
		exportButton = new JButton("Export");
		exportButton.setEnabled(false);
		exportButton.addActionListener(event -> {
			
			JDialog exportWindow = new JDialog(Main.window, "Select Files to Export");
			exportWindow.setLayout(new MigLayout("wrap 1, insets " + Theme.padding));
			
			JCheckBox settingsFileCheckbox = new JCheckBox("Settings file (the connection settings, chart settings, and GUI settings)", true);
			Map<JCheckBox, ConnectionTelemetry> csvOptions = new LinkedHashMap<JCheckBox, ConnectionTelemetry>();
			Map<JCheckBox, ConnectionCamera>    mkvOptions = new LinkedHashMap<JCheckBox, ConnectionCamera>();
			
			Connections.telemetryConnections.stream().filter(connection -> connection.getSampleCount() > 0)
			                                         .forEach(connection -> csvOptions.put(new JCheckBox("CSV file for \"" + connection.getName() + "\" (the acquired samples and corresponding timestamps)", true), connection));
			Connections.cameraConnections.stream().filter(connection -> connection.getSampleCount() > 0)
			                                      .forEach(connection -> mkvOptions.put(new JCheckBox("MKV file for \"" + connection.getName() + "\" (the acquired images and corresponding timestamps)", true), connection));

			JButton cancelButton = new JButton("Cancel");
			cancelButton.addActionListener(event2 -> exportWindow.dispose());
			
			JButton confirmButton = new JButton("Export");
			confirmButton.addActionListener(event2 -> {
				
				// cancel if every checkbox is unchecked
				boolean nothingSelected = !settingsFileCheckbox.isSelected() &&
				                          csvOptions.keySet().stream().noneMatch(checkbox -> checkbox.isSelected()) &&
				                          mkvOptions.keySet().stream().noneMatch(checkbox -> checkbox.isSelected());
				if(nothingSelected) {
					exportWindow.dispose();
					return;
				}
				
				JFileChooser saveFile = new JFileChooser(System.getProperty("user.dir"));
				saveFile.setDialogTitle("Export as...");
				JFrame parentWindow = (JFrame) SwingUtilities.windowForComponent(this);
				if(saveFile.showSaveDialog(parentWindow) == JFileChooser.APPROVE_OPTION) {
					String absolutePath = saveFile.getSelectedFile().getAbsolutePath();
					// remove the file extension if the user specified one
					if(saveFile.getSelectedFile().getName().indexOf(".") != -1)
						absolutePath = absolutePath.substring(0, absolutePath.lastIndexOf("."));
					boolean exportSettingsFile = settingsFileCheckbox.isSelected();
					List<ConnectionTelemetry> csvFiles = csvOptions.entrySet().stream().filter(entry -> entry.getKey().isSelected()).map(entry -> entry.getValue()).toList();
					List<ConnectionCamera>    mkvFiles = mkvOptions.entrySet().stream().filter(entry -> entry.getKey().isSelected()).map(entry -> entry.getValue()).toList();
					Connections.exportFiles(absolutePath, exportSettingsFile, csvFiles, mkvFiles);
					exportWindow.dispose();
				}
				
			});
			
			JPanel buttonsPanel = new JPanel();
			buttonsPanel.setLayout(new MigLayout("insets " + (Theme.padding * 2) + " 0 0 0", "[33%!][grow][33%!]")); // space the buttons, and 3 equal columns
			buttonsPanel.add(cancelButton,  "growx, cell 0 0");
			buttonsPanel.add(confirmButton, "growx, cell 2 0");
			
			exportWindow.add(settingsFileCheckbox);
			csvOptions.keySet().forEach(checkbox -> exportWindow.add(checkbox));
			mkvOptions.keySet().forEach(checkbox -> exportWindow.add(checkbox));
			exportWindow.add(buttonsPanel, "grow x");
			exportWindow.pack();
			exportWindow.setModal(true);
			exportWindow.setLocationRelativeTo(Main.window);
			exportWindow.setVisible(true);
			
		});
		
		// help
		helpButton = new JButton("Help");
		helpButton.addActionListener(event -> {
			
			JFrame parentWindow = (JFrame) SwingUtilities.windowForComponent(CommunicationView.instance);
			String helpText = "<html><b>" + Main.versionString + " (" + Main.versionDate + ")</b><br>" +
			                  "A fast and easy tool for visualizing data received over a UART/TCP/UDP connection.<br><br>" +
			                  "Step 1: Use the controls at the lower-right corner of the window to connect to a serial port or to start a TCP/UDP server.<br>" +
			                  "Step 2: A \"Data Structure Definition\" screen will appear. Use it to specify how your data is laid out, then click \"Done.\"<br>" +
			                  "Step 3: Click-and-drag in the tiles region to place a chart.<br>" +
			                  "Step 4: A chart configuration panel will appear. Use it to specify the type of chart and its settings, then click \"Done.\"<br>" +
			                  "Repeat steps 3 and 4 to create more charts if desired.<br>" +
			                  "If multiple telemetry streams will be used, click \"New Connection\" then repeat steps 1-4 as needed.<br><br>" +
			                  "Use your scroll wheel to rewind or fast forward.<br>" +
			                  "Use your scroll wheel while holding down Ctrl to zoom in or out.<br>" +
			                  "Use your scroll wheel while holding down Shift to adjust display scaling.<br><br>" +
			                  "Click the x icon at the top-right corner of any chart to remove it.<br>" +
			                  "Click the box icon at the top-right corner of any chart to maximize it.<br>" +
			                  "Click the gear icon at the top-right corner of any chart to change its settings.<br><br>" +
			                  "Click the \"Settings\" button to adjust options related to the GUI, or to transmit data to connected UARTs.<br>" +
			                  "Click the \"Import\" button to open previously saved files.<br>" +
			                  "Click the \"Export\" button to save your settings and/or data to files.<br>" +
			                  "Files can also be imported via drag-n-drop.<br><br>" +
			                  "Author: Farrell Farahbod<br>" +
			                  "This software is free and open source.</html>";
			JLabel helpLabel = new JLabel(helpText);
			JButton websiteButton = new JButton("<html><a href=\"http://www.farrellf.com/TelemetryViewer/\">http://www.farrellf.com/TelemetryViewer/</a></html>");
			websiteButton.addActionListener(click -> { try { Desktop.getDesktop().browse(new URI("http://www.farrellf.com/TelemetryViewer/")); } catch(Exception ex) {} });
			JButton paypalButton = new JButton("<html><a href=\"https://paypal.me/farrellfarahbod/\">https://paypal.me/farrellfarahbod/</a></html>");
			paypalButton.addActionListener(click -> { try { Desktop.getDesktop().browse(new URI("https://paypal.me/farrellfarahbod/")); } catch(Exception ex) {} });
			
			JPanel panel = new JPanel();
			panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
			panel.add(helpLabel);
			panel.add(websiteButton);
			panel.add(new JLabel("<html><br>If you find this software useful and want to \"buy me a coffee\" that would be awesome!</html>"));
			panel.add(paypalButton);
			panel.add(new JLabel(" "));
			JOptionPane.showMessageDialog(parentWindow, panel, "Help", JOptionPane.PLAIN_MESSAGE);

		});
		
		connectionButton = new JButton();
		connectionButton.addActionListener(event -> {
			     if(Connections.importing &&  Connections.realtimeImporting) Connections.finishImporting();
			else if(Connections.importing && !Connections.realtimeImporting) Connections.cancelImporting();
			else if(Connections.exporting)                                   Connections.cancelExporting();
			else                                                             Connections.addConnection(null);
		});
		
		// update and show the components
		redraw();
		
	}
	
	/**
	 * Redraws the bottom panel. This should be done when any of the following events occur:
	 * 
	 *     When importing:
	 *         import/export buttons are disabled
	 *         the newConnection button can be used to cancel importing
	 *         connection names become "Importing [Name]"
	 *         all connection widgets are disabled
	 *         
	 *     When exporting:
	 *         import/export buttons are disabled
	 *         newConnection button can be used to cancel exporting
	 *         all connection widgets are disabled
	 *         
	 *     When connected:
	 *         connect/disconnect button text set to "Disconnect"
	 *         all connection widgets are disabled
	 *         
	 *     When disconnected:
	 *         connect/disconnect button text set to "Connect"
	 *         all connection widgets are enabled
	 *         
	 *     When the first sample/frame is received:
	 *         export button is enabled
	 *         
	 *     When zero samples/frames exist:
	 *         export button is disabled
	 *         
	 *     When a connection is created or removed
	 *         the connection widgets will be shown or not
	 */
	public void redraw() {
		
		Runnable task = () -> {
			
			boolean connectionsChanged = !Connections.allConnections.equals(previousConnections);
			
			if(connectionsChanged) {
				
				// only repopulate if the connections have changed
				// this allows the user to keep interacting with a widget while a connection automatically reconnects
				// if we always repopulate, a widget could lose focus while the user is interacting with it
				removeAll();
				add(settingsButton);
				add(importButton);
				add(exportButton);
				add(helpButton);
				add(connectionButton);
				for(int i = 0; i < Connections.allConnections.size(); i++)
					add(Connections.allConnections.get(i).getConfigGUI(), "align right, cell 5 " + i);
				
				previousConnections = List.copyOf(Connections.allConnections);
				
			}
			
			// update all widgets
			Connections.allConnections.forEach(connection -> connection.updateConfigGUI());
			importButton.setEnabled(!Connections.importing && !Connections.exporting);
			exportButton.setEnabled(!Connections.importing && !Connections.exporting && Connections.telemetryExists());
			connectionButton.setText(Connections.importing &&  Connections.realtimeImporting ? "Finish Importing" :
			                         Connections.importing && !Connections.realtimeImporting ? "Cancel Importing" :
			                         Connections.exporting                                   ? "Cancel Exporting" :
			                                                                                   "New Connection");
			
			// redraw
			revalidate();
			repaint();
			
		};
		
		// after the first sample or frame is received, the data processing thread will call redraw(), so we can't assume we're on the EDT
		if(SwingUtilities.isEventDispatchThread())
			task.run();
		else
			SwingUtilities.invokeLater(task);
		
	}
	
	/**
	 * @param isShown    True to show the settings panel.
	 */
	public void showSettings(boolean isShown) {

		SettingsView.instance.setVisible(isShown);
		settingsButton.setSelected(isShown);
			
	}
	
}
