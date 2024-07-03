import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;

public class WidgetDatasetCheckboxes implements Widget {
	
	// "model"
	List<Field>                selectedDatasets       = new ArrayList<Field>();
	List<Field.Bitfield.State> selectedBitfieldEdges  = new ArrayList<Field.Bitfield.State>();
	List<Field.Bitfield.State> selectedBitfieldLevels = new ArrayList<Field.Bitfield.State>();
	long durationSampleCount;
	long durationMilliseconds;
	boolean userSpecifiedTheDuration = false; // true if the user manually specified it, or imported a settings file
	enum DurationUnit {
		SAMPLES { @Override public String toString() { return "Samples"; } },
		SECONDS { @Override public String toString() { return "Seconds"; } },
		MINUTES { @Override public String toString() { return "Minutes"; } },
		HOURS   { @Override public String toString() { return "Hours";   } },
		DAYS    { @Override public String toString() { return "Days";    } }
	};
	DurationUnit durationUnit = DurationUnit.SAMPLES;
	enum AxisType {
		SAMPLE_COUNT { @Override public String toString() { return "Sample Count"; } },
		TIMESTAMPS   { @Override public String toString() { return "Timestamps";   } },
		TIME_ELAPSED { @Override public String toString() { return "Time Elapsed"; } }
	};
	AxisType axisType = AxisType.SAMPLE_COUNT;
	
	// "view"
	Map<Field, JCheckBox>  datasetCheckboxes = new LinkedHashMap<Field, JCheckBox>();
	Map<Field.Bitfield.State, JToggleButton> edgeButtons  = new LinkedHashMap<Field.Bitfield.State, JToggleButton>();
	Map<Field.Bitfield.State, JToggleButton> levelButtons = new LinkedHashMap<Field.Bitfield.State, JToggleButton>();
	Map<Field, JToggleButton> bitfieldEdgeButtonsForEntireDataset  = new LinkedHashMap<Field, JToggleButton>();
	Map<Field, JToggleButton> bitfieldLevelButtonsForEntireDataset = new LinkedHashMap<Field, JToggleButton>();
	JTextField durationTextfield = new JTextField(Long.toString(durationSampleCount));
	JComboBox<DurationUnit> durationUnitCombobox = new JComboBox<DurationUnit>(DurationUnit.values());
	JToggleButton sampleCountMode = new JToggleButton("Sample Count", true);
	JToggleButton timestampsMode = new JToggleButton("Timestamps", false);
	JToggleButton timeElapsedMode = new JToggleButton("Time Elapsed", false);
	boolean isVisible = true;
	
	// "controller"
	boolean  allowTime;
	Consumer<List<Field>>                datasetsEventHandler;
	Consumer<List<Field.Bitfield.State>> bitfieldEdgesEventHandler;
	Consumer<List<Field.Bitfield.State>> bitfieldLevelsEventHandler;
	BiFunction<AxisType, Long, Long>       durationEventHandler;
	
	/**
	 * A widget that lets the user select datasets and optionally specify a chart duration.
	 * 
	 * @param datasetsHandler          If not null, allow the user to select normal datasets.
	 * @param bitfieldEdgesHandler     If not null, allow the user to select bitfield edge events.
	 * @param bitfieldLevelsHandler    If not null, allow the user to select bitfield levels.
	 * @param durationHandler          If not null, allow the user to specify the chart duration.
	 * @param allowTime                If true, the chart duration can be specified as a sample count or length of time. If false, only a sample count is allowed.
	 */
	public WidgetDatasetCheckboxes(Consumer<List<Field>> datasetsHandler, Consumer<List<Field.Bitfield.State>> bitfieldEdgesHandler, Consumer<List<Field.Bitfield.State>> bitfieldLevelsHandler, BiFunction<AxisType, Long, Long> durationHandler, boolean allowTime) {
		
		super();
		
		datasetsEventHandler = datasetsHandler;
		bitfieldEdgesEventHandler = bitfieldEdgesHandler;
		bitfieldLevelsEventHandler = bitfieldLevelsHandler;
		durationEventHandler = durationHandler;
		this.allowTime = allowTime;
		
		durationSampleCount = 10_000;
		durationMilliseconds = 10_000;
		if(!ConnectionsController.telemetryConnections.isEmpty() && ConnectionsController.telemetryConnections.get(0).getSampleRate() < Integer.MAX_VALUE / 10)
			durationSampleCount = ConnectionsController.telemetryConnections.get(0).getSampleRate() * 10L;
		durationTextfield.setText(Long.toString(durationSampleCount));
		
		durationTextfield.addFocusListener(new FocusListener() {
			@Override public void focusLost(FocusEvent fe)   { setDuration(durationTextfield.getText(), true); }
			@Override public void focusGained(FocusEvent fe) { durationTextfield.selectAll(); }
		});
		durationTextfield.addActionListener(event -> setDuration(durationTextfield.getText(), true));
		durationUnitCombobox.addActionListener(event -> setDurationUnit((DurationUnit) durationUnitCombobox.getSelectedItem()));
		
		sampleCountMode.setBorder(Theme.narrowButtonBorder);
		sampleCountMode.addActionListener(event -> setAxisType(AxisType.SAMPLE_COUNT));
		timestampsMode.setBorder(Theme.narrowButtonBorder);
		timestampsMode.addActionListener(event -> setAxisType(AxisType.TIMESTAMPS));
		timestampsMode.setEnabled(false);
		timeElapsedMode.setBorder(Theme.narrowButtonBorder);
		timeElapsedMode.addActionListener(event -> setAxisType(AxisType.TIME_ELAPSED));
		timeElapsedMode.setEnabled(false);
		
		ButtonGroup group = new ButtonGroup();
		group.add(sampleCountMode);
		group.add(timestampsMode);
		group.add(timeElapsedMode);
		
		appendTo(null, ""); // not actually appending, just initializing the widgets
		
	}
	
	Map<Component, String> widgets = new LinkedHashMap<Component, String>(); // key = Swing widget, value = MigLayout component constraints
	
	/**
	 * Ensures this widget is in sync with its state.
	 */
	@Override public void appendTo(JPanel panel, String constraints) {
		
		widgets.clear();
		
		datasetCheckboxes.clear();
		edgeButtons.clear();
		levelButtons.clear();
		bitfieldEdgeButtonsForEntireDataset.clear();
		bitfieldLevelButtonsForEntireDataset.clear();
		
		// ensure the selected datasets still exist
		selectedDatasets.removeIf(item -> !ConnectionsController.telemetryConnections.contains(item.connection));
		selectedBitfieldEdges.removeIf(item -> !ConnectionsController.telemetryConnections.contains(item.dataset.connection));
		selectedBitfieldLevels.removeIf(item -> !ConnectionsController.telemetryConnections.contains(item.dataset.connection));
		
		boolean showNormalDatasets = datasetsEventHandler != null;
		boolean showBitfieldDatasets = bitfieldEdgesEventHandler != null && bitfieldLevelsEventHandler != null;
		boolean showDuration = durationEventHandler != null;
			
		if(showNormalDatasets) {
			
			for(ConnectionTelemetry connection : ConnectionsController.telemetryConnections) {
				
				if(!connection.isFieldsDefined())
					continue;
				
				if(ConnectionsController.telemetryConnections.size() > 1)
					widgets.put(new JLabel(connection.name.get() + " Datasets", SwingConstants.CENTER), "");
				
				for(Field dataset : connection.getDatasetsList()) {
					
					if(dataset.isBitfield)
						continue;
					
					JCheckBox checkbox = new JCheckBox(dataset.name.get());
					checkbox.setSelected(selectedDatasets.contains(dataset));
					checkbox.addActionListener(event -> setNormalDatasetSelected(dataset, checkbox.isSelected()));
					datasetCheckboxes.put(dataset, checkbox);
					
					widgets.put(checkbox, "");
					
				}
				
			}
		
		}
		
		if(showBitfieldDatasets) {
			
			for(ConnectionTelemetry connection : ConnectionsController.telemetryConnections) {
				
				if(!connection.isFieldsDefined())
					continue;
				
				int rowCount = 0;
				String label = ConnectionsController.telemetryConnections.size() == 1 ? "Bitfields" : connection.name.get() + " Bitfields";
				
				for(Field dataset : connection.getDatasetsList()) {
					
					if(!dataset.isBitfield)
						continue;
					
					// show toggle buttons for the entire dataset
					JToggleButton allEdgesButton = new JToggleButton("_\u20D2\u203E");
					allEdgesButton.setBorder(Theme.narrowButtonBorder);
					allEdgesButton.setToolTipText("Show edges");
					boolean allEdgesSelected = true;
					for(Field.Bitfield b : dataset.bitfields)
						for(Field.Bitfield.State s : b.states)
							if(!selectedBitfieldEdges.contains(s))
								allEdgesSelected = false;
					allEdgesButton.setSelected(allEdgesSelected);
					allEdgesButton.addActionListener(event -> {
						boolean selectAll = allEdgesButton.isSelected();
						for(Field.Bitfield bitfield : dataset.bitfields)
							for(Field.Bitfield.State state : bitfield.states)
								setBitfieldEdgeSelected(state, selectAll);
					});
					
					JToggleButton allLevelsButton = new JToggleButton(" \u0332\u0305 \u0332\u0305 \u0332\u0305 ");
					allLevelsButton.setBorder(Theme.narrowButtonBorder);
					allLevelsButton.setToolTipText("Show levels");
					boolean allLevelsSelected = true;
					for(Field.Bitfield b : dataset.bitfields)
						for(Field.Bitfield.State s : b.states)
							if(!selectedBitfieldLevels.contains(s))
								allLevelsSelected = false;
					allLevelsButton.setSelected(allLevelsSelected);
					allLevelsButton.addActionListener(event -> {
						boolean selectAll = allLevelsButton.isSelected();
						for(Field.Bitfield bitfield : dataset.bitfields)
							for(Field.Bitfield.State state : bitfield.states)
								setBitfieldLevelSelected(state, selectAll);
					});
					
					widgets.put(new JLabel((rowCount++ == 0) ? label : "", SwingConstants.CENTER), "");
					widgets.put(allEdgesButton, "split 3");
					widgets.put(allLevelsButton, "");
					widgets.put(new JLabel("<html><b>" + dataset.name.get() + " (All / None)</b></html>"), "grow");
					
					bitfieldEdgeButtonsForEntireDataset.put(dataset, allEdgesButton);
					bitfieldLevelButtonsForEntireDataset.put(dataset, allLevelsButton);
					
					// also show toggle buttons for each state of each bitfield
					for(Field.Bitfield bitfield : dataset.bitfields) {
						for(Field.Bitfield.State state : bitfield.states) {
							
							JToggleButton edgeButton = new JToggleButton("_\u20D2\u203E");
							edgeButton.setBorder(Theme.narrowButtonBorder);
							edgeButton.setToolTipText("Show edges");
							edgeButton.setSelected(selectedBitfieldEdges.contains(state));
							edgeButton.addActionListener(event -> setBitfieldEdgeSelected(state, edgeButton.isSelected()));
							edgeButtons.put(state, edgeButton);
							
							JToggleButton levelButton = new JToggleButton(" \u0332\u0305 \u0332\u0305 \u0332\u0305 ");
							levelButton.setBorder(Theme.narrowButtonBorder);
							levelButton.setToolTipText("Show levels");
							levelButton.setSelected(selectedBitfieldLevels.contains(state));
							levelButton.addActionListener(event -> setBitfieldLevelSelected(state, levelButton.isSelected()));
							levelButtons.put(state, levelButton);
							
							widgets.put(edgeButton, "split 3");
							widgets.put(levelButton, "");
							widgets.put(new JLabel(state.name), "grow");
							
						}
					}
					
				}
					
			}
			
		}
		
		disableDatasetsFromOtherConnections();
		
		if(showDuration && !allowTime) {
			
			widgets.put(new JLabel("Sample Count: "), "split 2");
			widgets.put(durationTextfield, "grow");
			
		} else if(showDuration && allowTime) {
			
			widgets.put(new JLabel("Duration: "), "split 3");
			widgets.put(durationTextfield, "grow");
			widgets.put(durationUnitCombobox, "");
			widgets.put(new JLabel("Show as: "), "split 4");
			widgets.put(sampleCountMode, "grow");
			widgets.put(timestampsMode, "grow");
			widgets.put(timeElapsedMode, "grow");
			
		}
		
		notifyHandlers();
		
		if(panel != null) {
			widgets.forEach((component, constraint) -> component.setVisible(isVisible));
			widgets.forEach((component, constraint) -> panel.add(component, constraint));
		}
		
	}
	
	@Override public void setVisible(boolean isVisible) {
		
		this.isVisible = isVisible;
		widgets.forEach((component, constraints) -> component.setVisible(isVisible));
		
		// also resize the ConfigureView if it's on screen
		if(!ConfigureView.instance.getPreferredSize().equals(new Dimension(0, 0))) {
			ConfigureView.instance.setPreferredSize(null);
			ConfigureView.instance.revalidate();
			ConfigureView.instance.repaint();
		}
		
	}

	/**
	 * Grays out datasets from other connections.
	 */
	private void disableDatasetsFromOtherConnections() {
		
		// not needed if only one connection
		if(ConnectionsController.telemetryConnections.size() < 2)
			return;
		
		// re-enable all widgets if nothing is selected
		if(selectedDatasets.isEmpty() && selectedBitfieldEdges.isEmpty() && selectedBitfieldLevels.isEmpty()) {
			for(JCheckBox checkbox : datasetCheckboxes.values())
				checkbox.setEnabled(true);
			for(JToggleButton button : bitfieldEdgeButtonsForEntireDataset.values())
				button.setEnabled(true);
			for(JToggleButton button : bitfieldLevelButtonsForEntireDataset.values())
				button.setEnabled(true);
			for(JToggleButton button : edgeButtons.values())
				button.setEnabled(true);
			for(JToggleButton button : levelButtons.values())
				button.setEnabled(true);
			return;
		}
		
		// determine which connection has been selected
		ConnectionTelemetry connection = !selectedDatasets.isEmpty()      ? selectedDatasets.get(0).connection :
		                                 !selectedBitfieldEdges.isEmpty() ? selectedBitfieldEdges.get(0).dataset.connection :
		                                                                    selectedBitfieldLevels.get(0).dataset.connection;
		
		// disable widgets for datasets from the other connections
		for(Map.Entry<Field, JCheckBox> entry : datasetCheckboxes.entrySet())
			if(entry.getKey().connection != connection)
				entry.getValue().setEnabled(false);
		
		for(Entry<Field, JToggleButton> entry : bitfieldEdgeButtonsForEntireDataset.entrySet())
			if(entry.getKey().connection != connection)
				entry.getValue().setEnabled(false);
		
		for(Entry<Field, JToggleButton> entry : bitfieldLevelButtonsForEntireDataset.entrySet())
			if(entry.getKey().connection != connection)
				entry.getValue().setEnabled(false);
		
		for(Entry<Field.Bitfield.State, JToggleButton> entry : edgeButtons.entrySet())
			if(entry.getKey().dataset.connection != connection)
				entry.getValue().setEnabled(false);
		
		for(Entry<Field.Bitfield.State, JToggleButton> entry : levelButtons.entrySet())
			if(entry.getKey().dataset.connection != connection)
				entry.getValue().setEnabled(false);
		
	}
	
	private boolean maskNotifications = false;
	
	/**
	 * Determines which normal datasets, bitfield edges and bitfield levels have been selected, then notifies the handlers.
	 */
	private void notifyHandlers() {
		
		if(maskNotifications)
			return;
		
		// important: provide the chart with NEW lists, to ensure comparisons fail and caches flush
		if(datasetsEventHandler != null)
			datasetsEventHandler.accept(new ArrayList<Field>(selectedDatasets));

		if(bitfieldEdgesEventHandler != null)
			bitfieldEdgesEventHandler.accept(new ArrayList<Field.Bitfield.State>(selectedBitfieldEdges));
		
		if(bitfieldLevelsEventHandler != null)
			bitfieldLevelsEventHandler.accept(new ArrayList<Field.Bitfield.State>(selectedBitfieldLevels));
		
		if(durationEventHandler != null) {
			long proposedDuration = (axisType == AxisType.SAMPLE_COUNT) ? durationSampleCount : durationMilliseconds;
			long actualDuration = durationEventHandler.apply(axisType, proposedDuration);
			if(actualDuration != proposedDuration)
				if(durationUnit == DurationUnit.SAMPLES)
					setDuration(Long.toString(actualDuration), false);
				else
					setDuration(Double.toString(convertMillisecondsToDuration(actualDuration)), false);
		}
		
	}
	
	/**
	 * Updates the widget and chart based on settings from a settings file.
	 * 
	 * @param lines    A queue of remaining lines from the settings file.
	 */
	@Override public void importFrom(ConnectionsController.QueueOfLines lines) throws AssertionError {
		
		maskNotifications = true;
		
		selectedDatasets.clear();
		selectedBitfieldEdges.clear();
		selectedBitfieldLevels.clear();
		
		// parse the telemetry datasets line
		String line = lines.parseString("datasets = %s");
		if(!line.equals("")) {
			try {
				String[] tokens = line.split(",");
				for(String token : tokens) {
					int connectionN = Integer.parseInt(token.split(" ")[1]);
					int locationN   = Integer.parseInt(token.split(" ")[3]);
					Field d = ConnectionsController.telemetryConnections.get(connectionN).getDatasetByLocation(locationN);
					if(d == null)
						throw new Exception();
					selectedDatasets.add(d);
				}
			} catch(Exception e) { throw new AssertionError("Invalid datasets list."); }
		}
		
		// parse the bitfield edge states line
		line = lines.parseString("bitfield edge states = %s");
		if(!line.equals("")) {
			try {
				String[] states = line.split(",");
				for(String state : states) {
					boolean found = false;
					for(ConnectionTelemetry connection : ConnectionsController.telemetryConnections)
						for(Field dataset : connection.getDatasetsList())
							if(dataset.isBitfield)
								for(Field.Bitfield bitfield : dataset.bitfields)
									for(Field.Bitfield.State s : bitfield.states)
										if(s.toString().equals(state)) {
											found = true;
											selectedBitfieldEdges.add(s);
										}
					if(!found)
						throw new Exception();
				}
			} catch(Exception e) { throw new AssertionError("Invalid bitfield edge states list."); }
		}
		
		// parse the bitfield level states line
		line = lines.parseString("bitfield level states = %s");
		if(!line.equals("")) {
			try {
				String[] states = line.split(",");
				for(String state : states) {
					boolean found = false;
					for(ConnectionTelemetry connection : ConnectionsController.telemetryConnections)
						for(Field dataset : connection.getDatasetsList())
							if(dataset.isBitfield)
								for(Field.Bitfield bitfield : dataset.bitfields)
									for(Field.Bitfield.State s : bitfield.states)
										if(s.toString().equals(state)) {
											found = true;
											selectedBitfieldLevels.add(s);
										}
					if(!found)
						throw new Exception();
				}
			} catch(Exception e) { throw new AssertionError("Invalid bitfield level states list."); }
		}
		
		// parse the duration if enabled
		if(durationEventHandler != null) {
			String number = lines.parseString("duration = %s");
			String unit   = lines.parseString("duration unit = %s");
			DurationUnit unitEnum = null;
			for(DurationUnit option : DurationUnit.values())
				if(option.toString().equals(unit))
					unitEnum = option;
			if(unitEnum == null)
				throw new AssertionError("Invalid duration unit.");
			String type = lines.parseString("time axis shows = %s");
			AxisType typeEnum = null;
			for(AxisType option : AxisType.values())
				if(option.toString().equals(type))
					typeEnum = option;
			if(typeEnum == null)
				throw new AssertionError("Invalid time axis type.");
			
			setDurationUnit(unitEnum);
			setAxisType(typeEnum);
			setDuration(number, true);
		}
		
		// update the chart
		maskNotifications = false;
		notifyHandlers();
		
	}
	
	@Override public void exportTo(PrintWriter file) {
		
		boolean durationEanbled = durationEventHandler != null;
		
		// selected datasets
		String line = new String("datasets = ");
		for(Field d : selectedDatasets)
			line += "connection " + ConnectionsController.telemetryConnections.indexOf(d.connection) + " location " + d.location.get() + ",";
		if(line.endsWith(","))
			line = line.substring(0, line.length() - 1);
		file.println("\t" + line);
		
		// selected bitfield edges
		line = new String("bitfield edge states = ");
		for(Field.Bitfield.State s : selectedBitfieldEdges)
			line += s.toString() + ",";
		if(line.endsWith(","))
			line = line.substring(0, line.length() - 1);
		file.println("\t" + line);
		
		// selected bitfield levels
		line = new String("bitfield level states = ");
		for(Field.Bitfield.State s : selectedBitfieldLevels)
			line += s.toString() + ",";
		if(line.endsWith(","))
			line = line.substring(0, line.length() - 1);
		file.println("\t" + line);
		
		// duration
		if(durationEanbled) {
			file.println("\t" + "duration = " + ((durationUnit == DurationUnit.SAMPLES) ? Long.toString(durationSampleCount) : convertMillisecondsToDuration(durationMilliseconds)));
			file.println("\t" + "duration unit = " + durationUnit);
			file.println("\t" + "time axis shows = " + axisType);
		}
		
	}
	
	/**
	 * Adds or removes a normal (not bitfield) dataset from the list of selected datasets.
	 * This method should only be called when checkboxes are used. If comboboxes are used, call replaceNormalDataset() instead.
	 * This method should only be called when the chart accepts normal datasets.
	 * 
	 * @param dataset       The dataset to add or remove.
	 * @param isSelected    True if the dataset is selected.
	 */
	public void setNormalDatasetSelected(Field dataset, boolean isSelected) {
		
		// ignore if no change or invalid
		if((isSelected && selectedDatasets.contains(dataset)) || (!isSelected && !selectedDatasets.contains(dataset)))
			return;
		if(!datasetCheckboxes.containsKey(dataset) || datasetsEventHandler == null)
			return;
		
		// update the model
		if(isSelected)
			selectedDatasets.add(dataset);
		else
			selectedDatasets.remove(dataset);
		
		// reset the duration if appropriate
		if(selectedDatasets.size() == 1 && !userSpecifiedTheDuration)
			setDuration(durationUnit == DurationUnit.SAMPLES ? Integer.toString(dataset.connection.getSampleRate() * 10) :
			            durationUnit == DurationUnit.SECONDS ? Double.toString(10.0) :
			            durationUnit == DurationUnit.MINUTES ? Double.toString(10.0 / 60.0) :
			            durationUnit == DurationUnit.HOURS   ? Double.toString(10.0 / 60.0 / 60.0) :
			                                                   Double.toString(10.0 / 60.0 / 60.0 / 24.0), false);
		
		// update the view
		datasetCheckboxes.get(dataset).setSelected(isSelected);
		disableDatasetsFromOtherConnections();
		
		// update the chart
		notifyHandlers();
		
	}
	
	/**
	 * Adds or removes a bitfield edge event from the list of selected events.
	 * This method should only be called when the chart accepts bitfield edges.
	 * 
	 * @param edge          The bitfield edge event to add or remove.
	 * @param isSelected    True if the bitfield edge event is selected.
	 */
	public void setBitfieldEdgeSelected(Field.Bitfield.State edge, boolean isSelected) {
		
		// ignore if no change or invalid
		if((isSelected && selectedBitfieldEdges.contains(edge)) || (!isSelected && !selectedBitfieldEdges.contains(edge)))
			return;
		if(!edgeButtons.containsKey(edge) || !bitfieldEdgeButtonsForEntireDataset.containsKey(edge.dataset) || bitfieldEdgesEventHandler == null)
			return;
		
		// update the model
		if(isSelected)
			selectedBitfieldEdges.add(edge);
		else
			selectedBitfieldEdges.remove(edge);
		
		// reset the duration if appropriate
		if(selectedDatasets.size() == 1 && !userSpecifiedTheDuration)
			setDuration(durationUnit == DurationUnit.SAMPLES ? Integer.toString(edge.connection.getSampleRate() * 10) :
			            durationUnit == DurationUnit.SECONDS ? Double.toString(10.0) :
			            durationUnit == DurationUnit.MINUTES ? Double.toString(10.0 / 60.0) :
			            durationUnit == DurationUnit.HOURS   ? Double.toString(10.0 / 60.0 / 60.0) :
			                                                   Double.toString(10.0 / 60.0 / 60.0 / 24.0), false);
		
		// update the view
		edgeButtons.get(edge).setSelected(isSelected);
		boolean allEdgesOfThisDatasetSelected = true;
		for(Field.Bitfield bitfield : edge.dataset.bitfields)
			for(Field.Bitfield.State state : bitfield.states)
				if(!selectedBitfieldEdges.contains(state))
					allEdgesOfThisDatasetSelected = false;
		bitfieldEdgeButtonsForEntireDataset.get(edge.dataset).setSelected(allEdgesOfThisDatasetSelected);
		disableDatasetsFromOtherConnections();
		
		// update the chart
		notifyHandlers();
		
	}

	/**
	 * Adds or removes a bitfield level from the list of selected levels.
	 * This method should only be called when the chart accepts bitfield levels.
	 * 
	 * @param level         The bitfield level to add or remove.
	 * @param isSelected    True if the bitfield level is selected.
	 */
	public void setBitfieldLevelSelected(Field.Bitfield.State level, boolean isSelected) {
		
		// ignore if no change or invalid
		if((isSelected && selectedBitfieldLevels.contains(level)) || (!isSelected && !selectedBitfieldLevels.contains(level)))
			return;
		if(!levelButtons.containsKey(level) || !bitfieldLevelButtonsForEntireDataset.containsKey(level.dataset) || bitfieldLevelsEventHandler == null)
			return;
		
		// update the model
		if(isSelected)
			selectedBitfieldLevels.add(level);
		else
			selectedBitfieldLevels.remove(level);
		
		// reset the duration if appropriate
		if(selectedDatasets.size() == 1 && !userSpecifiedTheDuration)
			setDuration(durationUnit == DurationUnit.SAMPLES ? Integer.toString(level.connection.getSampleRate() * 10) :
			            durationUnit == DurationUnit.SECONDS ? Double.toString(10.0) :
			            durationUnit == DurationUnit.MINUTES ? Double.toString(10.0 / 60.0) :
			            durationUnit == DurationUnit.HOURS   ? Double.toString(10.0 / 60.0 / 60.0) :
			                                                   Double.toString(10.0 / 60.0 / 60.0 / 24.0), false);
		
		// update the view
		levelButtons.get(level).setSelected(isSelected);
		boolean allLevelsOfThisDatasetSelected = true;
		for(Field.Bitfield bitfield : level.dataset.bitfields)
			for(Field.Bitfield.State state : bitfield.states)
				if(!selectedBitfieldLevels.contains(state))
					allLevelsOfThisDatasetSelected = false;
		bitfieldLevelButtonsForEntireDataset.get(level.dataset).setSelected(allLevelsOfThisDatasetSelected);
		disableDatasetsFromOtherConnections();
		
		// update the chart
		notifyHandlers();
		
	}
	
	/**
	 * Converts from a number of milliseconds to a numbers of seconds/minutes/hours/days depending on the current unit.
	 * 
	 * @param milliseconds    Number of milliseconds.
	 * @return                Corresponding number of seconds/minutes/hours/days.
	 */
	private double convertMillisecondsToDuration(long milliseconds) {
		
		return (durationUnit == DurationUnit.SECONDS) ? (milliseconds /      1_000.0) :
		       (durationUnit == DurationUnit.MINUTES) ? (milliseconds /     60_000.0) :
		       (durationUnit == DurationUnit.HOURS)   ? (milliseconds /  3_600_000.0) :
		                                                (milliseconds / 86_400_000.0);
		
	}
	
	/**
	 * Sets the duration of the chart.
	 * 
	 * @param text             The duration, which may be a sample count or number of seconds/minutes/hours/days (depending on durationUnit).
	 * @param userSpecified    If true, the user specified this duration.
	 */
	public void setDuration(String text, boolean userSpecified) {
		
		if(durationUnit == DurationUnit.SAMPLES) {
			
			// sanitize
			long newSampleCount = durationSampleCount;
			try {
				long newValue = Long.parseLong(text.trim());
				if(newValue > 0)
					newSampleCount = newValue;
			} catch(Exception e) { }
			
			// update the model
			durationSampleCount = newSampleCount;
			if(userSpecified)
				userSpecifiedTheDuration = true;
			
			// update the view
			durationTextfield.setText(Long.toString(durationSampleCount));
			
			// update the chart
			notifyHandlers();
			
		} else {
			
			// sanitize
			double newTime = convertMillisecondsToDuration(durationMilliseconds);
			long newMilliseconds = durationMilliseconds;
			try {
				double newValue = Double.parseDouble(text.trim());
				long milliseconds = (durationUnit == DurationUnit.SECONDS) ? Math.round(newValue *      1_000.0) :
				                    (durationUnit == DurationUnit.MINUTES) ? Math.round(newValue *     60_000.0) :
				                    (durationUnit == DurationUnit.HOURS)   ? Math.round(newValue *  3_600_000.0) :
				                                                             Math.round(newValue * 86_400_000.0);
				if(milliseconds > 0) {
					newTime = convertMillisecondsToDuration(milliseconds);
					newMilliseconds = milliseconds;
				}
			} catch(Exception e) { }
			
			// update the model
			durationMilliseconds = newMilliseconds;
			if(userSpecified)
				userSpecifiedTheDuration = true;
			
			// update the view
			durationTextfield.setText(Double.toString(newTime));
			
			// update the chart
			notifyHandlers();
			
		}
		
	}
	
	/**
	 * Sets how the duration is specified, and converts to the new unit if necessary.
	 * 
	 * @param newUnit    The new duration unit: DurationUnit.SAMPLES or .SECONDS or .MINUTES or .HOURS or .DAYS
	 */
	public void setDurationUnit(DurationUnit newUnit) {
		
		// update the model
		if(durationUnit == DurationUnit.SAMPLES && newUnit != DurationUnit.SAMPLES) {
			// convert from sample count to milliseconds
			int sampleRateHz = !selectedDatasets.isEmpty()                           ?       selectedDatasets.get(0).connection.getSampleRate() :
			                   !selectedBitfieldEdges.isEmpty()                      ?  selectedBitfieldEdges.get(0).connection.getSampleRate() :
			                   !selectedBitfieldLevels.isEmpty()                     ? selectedBitfieldLevels.get(0).connection.getSampleRate() :
			                   !ConnectionsController.telemetryConnections.isEmpty() ? ConnectionsController.telemetryConnections.get(0).getSampleRate() : 1000;
			if(sampleRateHz == Integer.MAX_VALUE)
				sampleRateHz = 1000;
			durationMilliseconds = Math.round((double) durationSampleCount / (double) sampleRateHz * 1000.0);
			if(axisType == AxisType.SAMPLE_COUNT)
				axisType = AxisType.TIMESTAMPS;
		} else if(durationUnit != DurationUnit.SAMPLES && newUnit == DurationUnit.SAMPLES) {
			// convert from milliseconds to sample count
			int sampleRateHz = !selectedDatasets.isEmpty()                           ?       selectedDatasets.get(0).connection.getSampleRate() :
			                   !selectedBitfieldEdges.isEmpty()                      ?  selectedBitfieldEdges.get(0).connection.getSampleRate() :
			                   !selectedBitfieldLevels.isEmpty()                     ? selectedBitfieldLevels.get(0).connection.getSampleRate() :
			                   !ConnectionsController.telemetryConnections.isEmpty() ? ConnectionsController.telemetryConnections.get(0).getSampleRate() : 1000;
			if(sampleRateHz == Integer.MAX_VALUE)
				sampleRateHz = 1000;
			durationSampleCount = Math.round((double) durationMilliseconds / 1000.0 * (double) sampleRateHz);
			axisType = AxisType.SAMPLE_COUNT;
		}
		durationUnit = newUnit;
		
		// update the view
		durationTextfield.setText(durationUnit == DurationUnit.SAMPLES ?                   Long.toString(durationSampleCount) :
		                          durationUnit == DurationUnit.SECONDS ?      Double.toString(durationMilliseconds / 1_000.0) :
		                          durationUnit == DurationUnit.MINUTES ?     Double.toString(durationMilliseconds / 60_000.0) :
		                          durationUnit == DurationUnit.HOURS   ?  Double.toString(durationMilliseconds / 3_600_000.0) :
		                                                                 Double.toString(durationMilliseconds / 86_400_000.0));
		durationUnitCombobox.setSelectedItem(newUnit);
		if(axisType == AxisType.SAMPLE_COUNT) {
			sampleCountMode.setSelected(true);
			sampleCountMode.setEnabled(true);
			timestampsMode.setEnabled(false);
			timeElapsedMode.setEnabled(false);
		} else {
			sampleCountMode.setEnabled(false);
			timestampsMode.setSelected(axisType == AxisType.TIMESTAMPS);
			timeElapsedMode.setSelected(axisType == AxisType.TIME_ELAPSED);
			timestampsMode.setEnabled(true);
			timeElapsedMode.setEnabled(true);
		}
		
		// update the chart
		notifyHandlers();
		
	}
	
	/**
	 * Sets how the time axis should be displayed to the user.
	 * 
	 * @param newType    The new axis type: AxisType.SAMPLE_COUNT or .TIMESTAMPS or .TIME_ELAPSED
	 */
	public void setAxisType(AxisType newType) {
		
		// ignore if invalid
		if(newType == AxisType.SAMPLE_COUNT && durationUnit != DurationUnit.SAMPLES)
			newType = axisType;
		if(newType != AxisType.SAMPLE_COUNT && durationUnit == DurationUnit.SAMPLES)
			newType = axisType;
		
		// update the model
		axisType = newType;
		
		// update the view
		if(axisType == AxisType.SAMPLE_COUNT)
			sampleCountMode.setSelected(true);
		else if(axisType == AxisType.TIMESTAMPS)
			timestampsMode.setSelected(true);
		else
			timeElapsedMode.setSelected(true);
		
		// update the chart
		notifyHandlers();
		
	}

}
