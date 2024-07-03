import java.awt.Desktop;
import java.awt.Dimension;
import java.net.URI;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
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
				ConnectionsController.importFiles(filepaths);
			}
			
		});
		
		// export
		exportButton = new JButton("Export");
		exportButton.setEnabled(false);
		exportButton.addActionListener(event -> {
			
			JDialog exportWindow = new JDialog(Main.window, "Select Files to Export");
			exportWindow.setLayout(new MigLayout("wrap 1, insets " + Theme.padding));
			
			JCheckBox settingsFileCheckbox = new JCheckBox("Settings file (the data structures, chart settings, and GUI settings)", true);
			List<Map.Entry<JCheckBox, ConnectionTelemetry>> csvFileCheckboxes = new ArrayList<Map.Entry<JCheckBox, ConnectionTelemetry>>();
			List<Map.Entry<JCheckBox, ConnectionCamera>> cameraFileCheckboxes = new ArrayList<Map.Entry<JCheckBox, ConnectionCamera>>();
			
			for(ConnectionTelemetry connection : ConnectionsController.telemetryConnections)
				if(connection.getSampleCount() > 0)
					csvFileCheckboxes.add(new AbstractMap.SimpleEntry<JCheckBox, ConnectionTelemetry>(new JCheckBox("CSV file for \"" + connection.name.get() + "\" (the acquired samples and corresponding timestamps)", true), connection));
			for(ConnectionCamera camera : ConnectionsController.cameraConnections)
				if(camera.getSampleCount() > 0)
					cameraFileCheckboxes.add(new AbstractMap.SimpleEntry<JCheckBox, ConnectionCamera>(new JCheckBox("MKV file for \"" + camera.name.get() + "\" (the acquired images and corresponding timestamps)", true), camera));
			
			JButton cancelButton = new JButton("Cancel");
			cancelButton.addActionListener(event2 -> exportWindow.dispose());
			
			JButton confirmButton = new JButton("Export");
			confirmButton.addActionListener(event2 -> {
				
				// cancel if every checkbox is unchecked
				boolean nothingSelected = true;
				if(settingsFileCheckbox.isSelected())
					nothingSelected = false;
				for(Entry<JCheckBox, ConnectionTelemetry> entry : csvFileCheckboxes)
					if(entry.getKey().isSelected())
						nothingSelected = false;
				for(Entry<JCheckBox, ConnectionCamera> entry : cameraFileCheckboxes)
					if(entry.getKey().isSelected())
						nothingSelected = false;
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
					List<ConnectionTelemetry> connectionsList = new ArrayList<ConnectionTelemetry>();
					List<ConnectionCamera>    camerasList     = new ArrayList<ConnectionCamera>();
					for(Entry<JCheckBox, ConnectionTelemetry> entry : csvFileCheckboxes)
						if(entry.getKey().isSelected())
							connectionsList.add(entry.getValue());
					for(Entry<JCheckBox, ConnectionCamera> entry : cameraFileCheckboxes)
						if(entry.getKey().isSelected())
							camerasList.add(entry.getValue());
					ConnectionsController.exportFiles(absolutePath, exportSettingsFile, connectionsList, camerasList);
					exportWindow.dispose();
				}
				
			});
			
			JPanel buttonsPanel = new JPanel();
			buttonsPanel.setLayout(new MigLayout("insets " + (Theme.padding * 2) + " 0 0 0", "[33%!][grow][33%!]")); // space the buttons, and 3 equal columns
			buttonsPanel.add(cancelButton,  "growx, cell 0 0");
			buttonsPanel.add(confirmButton, "growx, cell 2 0");
			
			exportWindow.add(settingsFileCheckbox);
			for(Entry<JCheckBox, ConnectionTelemetry> entry : csvFileCheckboxes)
				exportWindow.add(entry.getKey());
			for(Entry<JCheckBox, ConnectionCamera> entry : cameraFileCheckboxes)
				exportWindow.add(entry.getKey());
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
		
		connectionButton = new JButton("New Connection");
		connectionButton.addActionListener(event -> ConnectionsController.addConnection(null));
		
		// show the components
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
	 *     When the packet type is changed:
	 *         sampleRate disabled if using TC66 mode
	 *         
	 *     When a connection is created or removed
	 *         the connection widgets will be shown or not
	 *         
	 *     Note regarding demo mode and stress test mode:
	 *         sampleRate and protocol are always disabled
	 */
	public void redraw() {
		
		Runnable task = () -> {
			
			// repopulate
			removeAll();
			add(settingsButton);
			add(importButton);
			add(exportButton);
			add(helpButton);
			add(connectionButton);
			for(int i = 0; i < ConnectionsController.allConnections.size(); i++) {
				Connection connection = ConnectionsController.allConnections.get(i);
				JPanel panel = new JPanel(new MigLayout("hidemode 3, gap " + Theme.padding  + ", insets 0 " + Theme.padding + " 0 0"));
				
				if(ConnectionsController.importing)
					connection.name.disableWithMessage("Importing [" + connection.name.get() + "]");
				else
					connection.name.setEnabled(!connection.isConnected() && !ConnectionsController.exporting);
				
				// connect/disconnect button
				JButton connectButton = new JButton(connection.isConnected() ? "Disconnect" : "Connect") {
					@Override public Dimension getPreferredSize() { // giving this button a fixed size so the GUI lines up nicely
						return new JButton("Disconnect").getPreferredSize();
					}
				};
				connectButton.addActionListener(event -> {
					if(connectButton.getText().equals("Connect"))
						connection.connect(true);
					else if(connectButton.getText().equals("Disconnect"))
						connection.disconnect(null);
				});
				connectButton.setEnabled(!ConnectionsController.importing && !ConnectionsController.exporting);
				
				// remove connection button
				JButton removeButton = new JButton(Theme.removeSymbol);
				removeButton.setBorder(Theme.narrowButtonBorder);
				removeButton.addActionListener(event -> ConnectionsController.removeConnection(connection));
				if(ConnectionsController.allConnections.size() < 2 || ConnectionsController.importing)
					removeButton.setVisible(false);
				
				// populate
				connection.getConfigurationWidgets().forEach(widget -> widget.appendTo(panel, ""));
				connection.name.appendTo(panel, "");
				panel.add(connectButton);
				panel.add(removeButton);
				add(panel, "align right, cell 5 " + i);
			}
			
			// reconfigure
			List.of(connectionButton.getActionListeners()).forEach(listener -> connectionButton.removeActionListener(listener));
			if(!ConnectionsController.importing && !ConnectionsController.exporting) {
				importButton.setEnabled(true);
				exportButton.setEnabled(ConnectionsController.telemetryExists());
				connectionButton.setText("New Connection");
				connectionButton.addActionListener(event -> ConnectionsController.addConnection(null));
			} else if(ConnectionsController.importing) {
				importButton.setEnabled(false);
				exportButton.setEnabled(false);
				connectionButton.setText(ConnectionsController.realtimeImporting ? "Finish Importing" : "Cancel Importing");
				connectionButton.addActionListener(event -> ConnectionsController.finishImporting());
			} else if(ConnectionsController.exporting) {
				importButton.setEnabled(false);
				exportButton.setEnabled(false);
				connectionButton.setText("Cancel Exporting");
				connectionButton.addActionListener(event -> ConnectionsController.cancelExporting());
			}
			
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
