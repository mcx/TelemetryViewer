import java.awt.Component;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;

public class WidgetDatasetComboboxes implements Widget {
	
	private final List<JLabel> comboboxLabels = new ArrayList<JLabel>();
	private final List<JComboBox<Field>> comboboxes = new ArrayList<JComboBox<Field>>();
	private final String[] labels;
	private final Consumer<List<Field>> eventHandler;
	
	/**
	 * A widget that lets the user select a specific number of datasets via comboboxes.
	 * 
	 * @param labels          Labels for each combobox. Array size must be >0, and corresponds to number of comboboxes.
	 * @param eventHandler    Will be notified of when the selected dataset(s) change.
	 */
	public WidgetDatasetComboboxes(String[] labels, Consumer<List<Field>> eventHandler) {
		
		this.labels = labels;
		this.eventHandler = eventHandler;
		createDatasetWidgets();
		validateAndNotifyHandler(comboboxes.get(0));
		
	}
	
	@SuppressWarnings("serial")
	private void createDatasetWidgets() {
		
		comboboxLabels.clear();
		comboboxes.clear();
		
		List<Field> list = new ArrayList<Field>();
		ConnectionsController.telemetryConnections.forEach(connection -> list.addAll(connection.getDatasetsList()));
		list.removeIf(dataset -> dataset.isBitfield);
		
		boolean multipleConnections = false;
		for(int i = 1; i < list.size(); i++)
			if(list.get(i).connection != list.get(i-1).connection)
				multipleConnections = true;
		
		for(String label : labels) {
			comboboxLabels.add((label != null && !label.isEmpty()) ? new JLabel(label + ": ") : null);
			
			JComboBox<Field> combobox = new JComboBox<Field>();
			list.forEach(dataset -> combobox.addItem(dataset));
			if(multipleConnections)
				combobox.setRenderer(new DefaultListCellRenderer() {
					@Override public Component getListCellRendererComponent(final JList<?> list, Object value, final int index, final boolean isSelected, final boolean cellHasFocus) {
						return super.getListCellRendererComponent(list, ((Field)value).connection.name + ": " + value.toString(), index, isSelected, cellHasFocus);
					}
				});
			if(list.isEmpty())
				combobox.setEnabled(false);
			combobox.addActionListener(event -> validateAndNotifyHandler(combobox));
			comboboxes.add(combobox);
		}
		
	}
	
	public void setDataset(int comboboxN, Field newDataset) {
		
		comboboxes.get(comboboxN).setSelectedItem(newDataset);
		
	}
	
	private boolean maskEvents = false;
	
	private void validateAndNotifyHandler(JComboBox<Field> eventSource) {
		
		// ensure all selected datasets are from the same connection
		Field newDataset = (Field) eventSource.getSelectedItem();
		comboboxes.forEach(combobox -> {
			Field d = (Field) combobox.getSelectedItem();
			if(d != null && d.connection != newDataset.connection) {
				maskEvents = true;
				combobox.setSelectedItem(newDataset);
				maskEvents = false;
			}
		});
		
		// important: provide the chart with a NEW list, to ensure comparisons fail and caches flush
		List<Field> list = new ArrayList<Field>();
		comboboxes.forEach(combobox -> {
			Field d = (Field) combobox.getSelectedItem();
			if(d != null)
				list.add(d);
		});
		
		if(!maskEvents)
			eventHandler.accept(list);
		
	}
	
	/**
	 * Ensures this widget is in sync with its state.
	 */
	@Override public void appendTo(JPanel panel, String constraints) {
		
		// if available connections changed, recreate comboboxes but default to the previously selected datasets
		Set<ConnectionTelemetry> oldConnections = new HashSet<ConnectionTelemetry>();
		Set<ConnectionTelemetry> newConnections = new HashSet<ConnectionTelemetry>(ConnectionsController.telemetryConnections);
		for(int i = 0; i < comboboxes.get(0).getItemCount(); i++)
			oldConnections.add(comboboxes.get(0).getItemAt(i).connection);
		
		if(!oldConnections.equals(newConnections)) {
			List<Field> oldSelections = new ArrayList<Field>();
			comboboxes.forEach(combobox -> oldSelections.add((Field) combobox.getSelectedItem()));
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
			JComboBox<Field> combobox = comboboxes.get(i);
			if(label != null)
				panel.add(label, "split 2");
			panel.add(combobox, "grow");
		}
		
	}
	
	@Override public void setVisible(boolean isVisible) {
		
		comboboxLabels.forEach(label -> label.setVisible(isVisible));
		comboboxes.forEach(combobox -> combobox.setVisible(isVisible));
		
	}
	
	public void setEnabled(boolean isEnabled) {
		
		comboboxLabels.forEach(label -> label.setEnabled(isEnabled));
		comboboxes.forEach(combobox -> combobox.setEnabled(isEnabled));
		
	}
	
	/**
	 * Updates the widget and chart based on settings from a settings file.
	 * 
	 * @param lines    A queue of remaining lines from the settings file.
	 */
	@Override public void importFrom(ConnectionsController.QueueOfLines lines) throws AssertionError {
		
		// parse the telemetry datasets line
		String line = lines.parseString("datasets = %s");
		if(!line.equals("")) {
			try {
				String[] tokens = line.split(",");
				if(tokens.length != comboboxLabels.size())
					throw new Exception();
				for(int i = 0; i < tokens.length; i++) {
					int connectionN = Integer.parseInt(tokens[i].split(" ")[1]);
					int locationN   = Integer.parseInt(tokens[i].split(" ")[3]);
					Field d = ConnectionsController.telemetryConnections.get(connectionN).getDatasetByLocation(locationN);
					if(d == null)
						throw new Exception();
					comboboxes.get(i).setSelectedItem(d);
				}
			} catch(Exception e) { throw new AssertionError("Invalid datasets list."); }
		}
		
	}
	
	@Override public void exportTo(PrintWriter file) {
		
		List<Field> list = new ArrayList<Field>();
		comboboxes.forEach(combobox -> {
			Field d = (Field) combobox.getSelectedItem();
			if(d != null)
				list.add(d);
		});
		
		// selected datasets
		String line = new String("datasets = ");
		for(Field d : list)
			line += "connection " + ConnectionsController.telemetryConnections.indexOf(d.connection) + " location " + d.location.get() + ",";
		if(line.endsWith(","))
			line = line.substring(0, line.length() - 1);
		file.println("\t" + line);
		
	}

}
