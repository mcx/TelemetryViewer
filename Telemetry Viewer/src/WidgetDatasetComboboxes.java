import java.awt.Component;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.function.Consumer;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;

public class WidgetDatasetComboboxes implements Widget {
	
	private List<JLabel> comboboxLabels = new ArrayList<JLabel>();
	private List<JComboBox<Dataset>> comboboxes = new ArrayList<JComboBox<Dataset>>();
	
	private String[] labels;
	private Consumer<List<Dataset>> eventHandler;
	
	/**
	 * A widget that lets the user select a specific number of datasets via comboboxes.
	 * 
	 * @param labels          Labels for each combobox. Array size must be >0, and corresponds to number of comboboxes.
	 * @param eventHandler    Will be notified of when the selected dataset(s) change.
	 */
	public WidgetDatasetComboboxes(String[] labels, Consumer<List<Dataset>> eventHandler) {
		
		this.labels = labels;
		this.eventHandler = eventHandler;
		createDatasetWidgets();
		validateAndNotifyHandler(comboboxes.get(0));
		
	}
	
	@SuppressWarnings("serial")
	private void createDatasetWidgets() {
		
		comboboxLabels.clear();
		comboboxes.clear();
		
		List<Dataset> list = new ArrayList<Dataset>();
		ConnectionsController.telemetryConnections.forEach(connection -> list.addAll(connection.datasets.getList()));
		list.removeIf(dataset -> dataset.isBitfield);
		
		boolean multipleConnections = false;
		for(int i = 1; i < list.size(); i++)
			if(list.get(i).connection != list.get(i-1).connection)
				multipleConnections = true;
		
		for(String label : labels) {
			comboboxLabels.add((label != null && !label.isEmpty()) ? new JLabel(label + ": ") : null);
			
			JComboBox<Dataset> combobox = new JComboBox<Dataset>();
			list.forEach(dataset -> combobox.addItem(dataset));
			if(multipleConnections)
				combobox.setRenderer(new DefaultListCellRenderer() {
					@Override public Component getListCellRendererComponent(final JList<?> list, Object value, final int index, final boolean isSelected, final boolean cellHasFocus) {
						return super.getListCellRendererComponent(list, ((Dataset)value).connection.name + ": " + value.toString(), index, isSelected, cellHasFocus);
					}
				});
			if(list.isEmpty())
				combobox.setEnabled(false);
			combobox.addActionListener(event -> validateAndNotifyHandler(combobox));
			comboboxes.add(combobox);
		}
		
	}
	
	public void setDataset(int comboboxN, Dataset newDataset) {
		
		comboboxes.get(comboboxN).setSelectedItem(newDataset);
		
	}
	
	private boolean maskEvents = false;
	
	private void validateAndNotifyHandler(JComboBox<Dataset> eventSource) {
		
		// ensure all selected datasets are from the same connection
		Dataset newDataset = (Dataset) eventSource.getSelectedItem();
		comboboxes.forEach(combobox -> {
			Dataset d = (Dataset) combobox.getSelectedItem();
			if(d != null && d.connection != newDataset.connection) {
				maskEvents = true;
				combobox.setSelectedItem(newDataset);
				maskEvents = false;
			}
		});
		
		// important: provide the chart with a NEW list, to ensure comparisons fail and caches flush
		List<Dataset> list = new ArrayList<Dataset>();
		comboboxes.forEach(combobox -> {
			Dataset d = (Dataset) combobox.getSelectedItem();
			if(d != null)
				list.add(d);
		});
		
		if(!maskEvents)
			eventHandler.accept(list);
		
	}
	
	/**
	 * Ensures this widget is in sync with its state.
	 */
	@Override public void appendToGui(JPanel gui) {
		
		// if available connections changed, recreate comboboxes but default to the previously selected datasets
		Set<ConnectionTelemetry> oldConnections = new HashSet<ConnectionTelemetry>();
		Set<ConnectionTelemetry> newConnections = new HashSet<ConnectionTelemetry>(ConnectionsController.telemetryConnections);
		for(int i = 0; i < comboboxes.get(0).getItemCount(); i++)
			oldConnections.add(comboboxes.get(0).getItemAt(i).connection);
		
		if(!oldConnections.equals(newConnections)) {
			List<Dataset> oldSelections = new ArrayList<Dataset>();
			comboboxes.forEach(combobox -> oldSelections.add((Dataset) combobox.getSelectedItem()));
			createDatasetWidgets();
			for(int i = 0; i < oldSelections.size(); i++) {
				maskEvents = true;
				comboboxes.get(i).setSelectedItem(oldSelections.get(i));
				maskEvents = false;
			}
		}
		
		// populate the GUI
		for(int i = 0; i < comboboxLabels.size(); i++) {
			JLabel label = comboboxLabels.get(i);
			JComboBox<Dataset> combobox = comboboxes.get(i);
			if(label != null)
				gui.add(label, "split 2");
			gui.add(combobox, "grow");
		}
		
	}
	
	@Override public void setVisible(boolean isVisible) {
		
		comboboxLabels.forEach(label -> label.setVisible(isVisible));
		comboboxes.forEach(combobox -> combobox.setVisible(isVisible));
		
	}
	
	public void setEnabled(boolean isEnabled) {
		
		comboboxes.forEach(combobox -> combobox.setEnabled(isEnabled));
		
	}
	
	/**
	 * Updates the widget and chart based on settings from a settings file.
	 * 
	 * @param lines    A queue of remaining lines from the settings file.
	 */
	@Override public void importFrom(Queue<String> lines) {
		
		// parse the telemetry datasets line
		String line = ChartUtils.parseString(lines.remove(), "datasets = %s");
		if(!line.equals("")) {
			try {
				String[] tokens = line.split(",");
				if(tokens.length != comboboxLabels.size())
					throw new Exception();
				for(int i = 0; i < tokens.length; i++) {
					int connectionN = Integer.parseInt(tokens[i].split(" ")[1]);
					int locationN   = Integer.parseInt(tokens[i].split(" ")[3]);
					Dataset d = ConnectionsController.telemetryConnections.get(connectionN).datasets.getByLocation(locationN);
					if(d == null)
						throw new Exception();
					comboboxes.get(i).setSelectedItem(d);
				}
			} catch(Exception e) { throw new AssertionError("Invalid datasets list."); }
		}
		
	}
	
	/**
	 * Saves the current state to one or more lines of text.
	 * 
	 * @param    Append lines of text to this List.
	 */
	@Override public void exportTo(List<String> lines) {
		
		List<Dataset> list = new ArrayList<Dataset>();
		comboboxes.forEach(combobox -> {
			Dataset d = (Dataset) combobox.getSelectedItem();
			if(d != null)
				list.add(d);
		});
		
		// selected datasets
		String line = new String("datasets = ");
		for(Dataset d : list)
			line += "connection " + ConnectionsController.telemetryConnections.indexOf(d.connection) + " location " + d.location + ",";
		if(line.endsWith(","))
			line = line.substring(0, line.length() - 1);
		lines.add(line);
		
	}

}
