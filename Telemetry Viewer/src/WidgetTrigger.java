import java.nio.FloatBuffer;
import java.util.List;
import java.util.Queue;
import java.util.function.Consumer;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;

public class WidgetTrigger implements Widget {
	
	private enum Mode {
		DISABLED { @Override public String toString() { return "Disabled"; } },
		AUTO     { @Override public String toString() { return "Auto";     } },
		NORMAL   { @Override public String toString() { return "Normal";   } },
		SINGLE   { @Override public String toString() { return "Single";   } }
	};
	
	private Mode triggerMode = Mode.DISABLED;
	private WidgetToggleButtonEnum<Mode> triggerModeButtons;
	
	private enum Affects {
		THIS_CHART  { @Override public String toString() { return "This Chart";  } },
		EVERY_CHART { @Override public String toString() { return "Every Chart"; } }
	}
	
	private Affects triggerAffects = Affects.THIS_CHART;
	private WidgetToggleButtonEnum<Affects> triggerAffectsButtons;
	
	private enum Type {
		RISING_EDGE  { @Override public String toString() { return "Rising Edge";  } },
		FALLING_EDGE { @Override public String toString() { return "Falling Edge"; } },
		BOTH_EDGES   { @Override public String toString() { return "Both Edges";   } }
	};
	
	private Type triggerType = Type.RISING_EDGE;
	private WidgetToggleButtonEnum<Type> triggerTypeButtons;
	
	private DatasetsInterface datasets = new DatasetsInterface();
	private Dataset triggerChannel = null;
	private WidgetDatasetComboboxes triggerChannelCombobox;
	private boolean userSpecifiedTheChannel = false;
	
	private float triggerLevel = 0;
	private WidgetTextfieldFloat triggerLevelTextfield;
	
	private float triggerHysteresis = 0;
	private WidgetTextfieldFloat triggerHysteresisTextfield;
	
	private int triggerPrePostRatio = 20;
	private JLabel prePostLabel;
	private JSlider prePostRatioSlider;
	
	private PositionedChart chart;
	
	public void setLevel(float newLevel, boolean resetTrigger) {
		
		// ignore if unchanged
		if(newLevel == triggerLevel)
			return;
		
		// update the model
		triggerLevel = newLevel;
		triggerLevelTextfield.setNumber(newLevel);
		if(resetTrigger)
			resetTrigger(true);
		
	}
	
	public void setPrePostRatio(int newRatio) {
		
		prePostRatioSlider.setValue(newRatio);
		
	}
	
	public void setDefaultChannel(Dataset dataset) {
		
		if(!userSpecifiedTheChannel) {
			triggerChannelCombobox.setDataset(0, dataset);
			userSpecifiedTheChannel = false; // the event handler will set this to true, so reset it to false
		}
		
	}
	
	/**
	 * A widget that lets the user configure a trigger and all of its settings.
	 * 
	 * @param eventHandler    Will be notified when the trigger is enabled or disabled.
	 */
	public WidgetTrigger(PositionedChart chart, Consumer<Boolean> eventHandler) {
		
		super();
		
		this.chart = chart;
		
		// trigger affects this chart or all charts
		triggerAffectsButtons = new WidgetToggleButtonEnum<Affects>("Affects",
		                                                            "trigger affects",
		                                                            Affects.values(),
		                                                            triggerAffects,
		                                                            newSetting -> {
		                                                                triggerAffects = newSetting;
		                                                                resetTrigger(true);
		                                                            });
		
		// trigger type
		triggerTypeButtons = new WidgetToggleButtonEnum<Type>("Type",
		                                                      "trigger type",
		                                                      Type.values(),
		                                                      triggerType,
		                                                      newType -> {
		                                                          triggerType = newType;
		                                                          resetTrigger(true);
		                                                      });
		
		// trigger level
		triggerLevelTextfield = new WidgetTextfieldFloat("Level",
		                                                 "trigger level",
		                                                 "",
		                                                 -Float.MAX_VALUE,
		                                                 Float.MAX_VALUE,
		                                                 triggerLevel,
		                                                 newLevel -> setLevel(newLevel, true));
		
		// trigger hysteresis
		triggerHysteresisTextfield = new WidgetTextfieldFloat("Hysteresis",
		                                                      "trigger hysteresis",
		                                                      "",
		                                                      -Float.MAX_VALUE,
		                                                      Float.MAX_VALUE,
		                                                      triggerHysteresis,
		                                                      newValue -> {
		                                                          triggerHysteresis = newValue;
		                                                          resetTrigger(true);
		                                                      });
		
		// trigger channel
		triggerChannelCombobox = new WidgetDatasetComboboxes(new String[] {"Channel"},
		                                                     newDatasets -> {
		                                                         if(newDatasets.isEmpty()) // no telemetry connections
		                                                             return;
		                                                         datasets.setNormals(List.of(newDatasets.get(0)));
		                                                         triggerChannel = newDatasets.get(0);
		                                                         userSpecifiedTheChannel = true;
		                                                         resetTrigger(true);
		                                                         triggerLevelTextfield.setUnit(datasets.getNormal(0).unit);
		                                                         triggerHysteresisTextfield.setUnit(datasets.getNormal(0).unit);
		                                                     });
		
		// pre/post ratio
		prePostLabel = new JLabel("Pre/Post Ratio: ");
		prePostRatioSlider = new JSlider();
		prePostRatioSlider.setValue(triggerPrePostRatio);
		prePostRatioSlider.addChangeListener(event -> triggerPrePostRatio = prePostRatioSlider.getValue());
		userSpecifiedTheChannel = false;
		
		triggerModeButtons = new WidgetToggleButtonEnum<Mode>("",
		                                                      "trigger mode",
		                                                      Mode.values(),
		                                                      triggerMode,
		                                                      newMode -> {
		                                                          triggerMode = newMode;
		                                                          resetTrigger(true);
		                                                          boolean triggerEnabled = triggerMode != Mode.DISABLED && triggerChannel != null;
		                                                          triggerChannelCombobox.setEnabled(triggerEnabled);
		                                                          triggerLevelTextfield.setEnabled(triggerEnabled);
		                                                          triggerHysteresisTextfield.setEnabled(triggerEnabled);
		                                                          eventHandler.accept(triggerEnabled);
		                                                      });
		
	}
	
	@Override public void appendToGui(JPanel gui) {
		
		triggerModeButtons.appendToGui(gui);
		triggerAffectsButtons.appendToGui(gui);
		triggerTypeButtons.appendToGui(gui);
		triggerChannelCombobox.appendToGui(gui);
		triggerLevelTextfield.appendToGui(gui);
		triggerHysteresisTextfield.appendToGui(gui);
		
		gui.add(prePostLabel, "split 2");
		gui.add(prePostRatioSlider, "width min:min:max, grow"); // force preferred width = min width
		
	}
	
	@Override public void setVisible(boolean isVisible) {
		
		triggerModeButtons.setVisible(isVisible);
		triggerAffectsButtons.setVisible(isVisible);
		triggerTypeButtons.setVisible(isVisible);
		triggerChannelCombobox.setVisible(isVisible);
		triggerLevelTextfield.setVisible(isVisible);
		triggerHysteresisTextfield.setVisible(isVisible);
		prePostLabel.setVisible(isVisible);
		prePostRatioSlider.setVisible(isVisible);
		
	}
	
	private boolean triggered = false;
	private int triggeredSampleNumber = -1;
	private int triggeredEndSampleNumber = -1;
	private long triggeredTimestamp = -1;
	private long triggeredEndTimestamp = -1;
	private int nextTriggerableSampleNumber = -1;
	private long nextTriggerableTimestamp = -1;
	private int previousMaxSampleNumber = -1;
	
	/**
	 * Prepares for detecting the next trigger event.
	 * 
	 * @param resetNextTriggerPoint    If true, also allow the next trigger point to occur before the current trigger point.
	 */
	public void resetTrigger(boolean resetNextTriggerPoint) {
		triggered = false;
		triggeredSampleNumber = -1;
		triggeredEndSampleNumber = -1;
		triggeredTimestamp = -1;
		triggeredEndTimestamp = -1;
		previousMaxSampleNumber = -1;
		triggeredMinSampleNumber = -1;
		if(resetNextTriggerPoint) {
			nextTriggerableSampleNumber = -1;
			nextTriggerableTimestamp = -1;
			if(OpenGLChartsView.instance.isTriggeredView())
		      	  OpenGLChartsView.instance.switchToLiveView();
		}
	}
	
	/**
	 * Similar to resetTrigger(), and allows the next trigger point to occur before the current trigger point, but does NOT switch back to Live View.
	 * 
	 * This method is called when the user is dragging the trigger level or trigger pre/post ratio widgets.
	 * This allows a live redraw of where a new trigger would have occurred.
	 */
	public void clearTrigger() {
		triggered = false;
		triggeredSampleNumber = -1;
		triggeredEndSampleNumber = -1;
		triggeredTimestamp = -1;
		triggeredEndTimestamp = -1;
		previousMaxSampleNumber = -1;
		nextTriggerableSampleNumber = -1;
		nextTriggerableTimestamp = -1;
	}
	
	/**
	 * Called by the chart so it can draw a marker at the trigger point.
	 * 
	 * @return    The triggered sample number, or -1 if not triggered.
	 */
	public int getTriggeredSampleNumber() {
		
		return triggered ? triggeredSampleNumber : -1;
		
	}
	
	/**
	 * Called by the chart so it can draw a marker at the trigger level.
	 * 
	 * @return    The y-axis value that would cause a trigger.
	 */
	public float getTriggerLevel() {
		
		return triggerLevel;
		
	}
	
	/**
	 * Checks for a new trigger event if the chart is showing time as the x-axis.
	 * 
	 * @param endTimestamp     Timestamp that should be at the right edge of the plot if not triggered.
	 * @param zoomLevel        Current zoom level.
	 * @param recalcTrigger    If true, force recalculation of the trigger.
	 * @return                 Timestamp that should be at the right edge of the plot.
	 */
	public long checkForTriggerMillisecondsMode(long endTimestamp, double zoomLevel, boolean recalcTrigger) {
		
		// recalculate the trigger if the user is dragging the trigger level or pre/post markers
		if(recalcTrigger)
			clearTrigger();
		
		// don't trigger if the user is time-shifting
		if(OpenGLChartsView.instance.isPausedView())
			return endTimestamp;
		
		// don't trigger if already triggered in single-trigger mode
		if(triggered && triggerMode == Mode.SINGLE)
			return triggeredEndTimestamp;
		
		// determine which samples to test
		long chartDomain = (long) Math.ceil(chart.duration * zoomLevel);
		double preTriggerPercent = triggerPrePostRatio / 100.0;
		double postTriggerPercent = 1.0 - preTriggerPercent;
		int maxSampleNumber = datasets.getClosestSampleNumberAtOrBefore(endTimestamp, triggerChannel.connection.getSampleCount() - 1);
		long startTimestamp = datasets.getTimestamp(maxSampleNumber) - chartDomain;
		int minSampleNumber = datasets.getClosestSampleNumberAtOrBefore(startTimestamp, maxSampleNumber);
		if(minSampleNumber > previousMaxSampleNumber && previousMaxSampleNumber != -1)
			minSampleNumber = previousMaxSampleNumber;
		if(recalcTrigger && triggeredMinSampleNumber != -1)
			minSampleNumber = triggeredMinSampleNumber;
		if(minSampleNumber < 0)
			minSampleNumber = 0;
		previousMaxSampleNumber = maxSampleNumber;
		
		// don't trigger if already triggered, and it should still be on screen
		if(triggered && nextTriggerableTimestamp >= endTimestamp)
			return triggeredEndTimestamp;
		
		// not triggered, so reset if auto-trigger mode
		if(triggerMode == Mode.AUTO) {
			resetTrigger(false);
			if(triggerAffects == Affects.EVERY_CHART && OpenGLChartsView.instance.isTriggeredView())
				OpenGLChartsView.instance.setLiveView();
		}
		
		// check for a new trigger
		minSampleNumber = Integer.max(minSampleNumber, datasets.getClosestSampleNumberAfter(nextTriggerableTimestamp));
		boolean triggerOnRisingEdge  = (triggerType == Type.RISING_EDGE)  || (triggerType == Type.BOTH_EDGES);
		boolean triggerOnFallingEdge = (triggerType == Type.FALLING_EDGE) || (triggerType == Type.BOTH_EDGES);
		boolean risingEdgeArmed = false;
		boolean fallingEdgeArmed = false;
		FloatBuffer buffer = datasets.getSamplesBuffer(triggerChannel, minSampleNumber, maxSampleNumber);
		for(int sampleNumber = minSampleNumber; sampleNumber <= maxSampleNumber; sampleNumber++) {
			float value = buffer.get(sampleNumber - minSampleNumber);
			if(triggerOnRisingEdge && value < triggerLevel - triggerHysteresis)
				risingEdgeArmed = true;
			if(triggerOnFallingEdge && value > triggerLevel + triggerHysteresis)
				fallingEdgeArmed = true;
			if((risingEdgeArmed && triggerOnRisingEdge && value >= triggerLevel) || (fallingEdgeArmed && triggerOnFallingEdge && value <= triggerLevel)) {
				triggeredSampleNumber = sampleNumber;
				triggeredTimestamp = datasets.getTimestamp(sampleNumber);
				triggered = true;
				nextTriggerableTimestamp = triggeredTimestamp + (long) Math.round(chartDomain * postTriggerPercent);
				long millisecondsAfterTrigger = (long) Math.round(chartDomain * postTriggerPercent);
				triggeredMinSampleNumber = minSampleNumber;
				triggeredEndTimestamp = triggeredTimestamp + millisecondsAfterTrigger;
				if(triggerAffects == Affects.EVERY_CHART)
					OpenGLChartsView.instance.setTriggeredView(triggeredEndTimestamp, triggerChannel.connection, triggeredEndSampleNumber);
				return triggeredEndTimestamp;
			}
		}
		
		// done
		return triggered ? triggeredEndTimestamp :
		       triggerMode == Mode.AUTO ? endTimestamp :
		       datasets.connection.getFirstTimestamp() - 1;
		
	}
	
	int triggeredMinSampleNumber;
	
	/**
	 * Checks for a new trigger event if the chart is showing sample numbers as the x-axis.
	 * 
	 * @param endSampleNumber    Sample number that should be at the right edge of the plot if not triggered.
	 * @param zoomLevel          Current zoom level.
	 * @param recalcTrigger      If true, force recalculation of the trigger.
	 * @return                   Sample number that should be at the right edge of the plot.
	 */
	public int checkForTriggerSampleCountMode(int endSampleNumber, double zoomLevel, boolean recalcTrigger) {
		
		// recalculate the trigger if the user is dragging the trigger level or pre/post markers
		if(recalcTrigger)
			clearTrigger();
		
		// don't trigger if the user is time-shifting
		if(OpenGLChartsView.instance.isPausedView())
			return endSampleNumber;
		
		// don't trigger if already triggered in single-trigger mode
		if(triggered && triggerMode == Mode.SINGLE)
			return triggeredEndSampleNumber;
		
		// determine which samples to test
		int chartDomain = (int) ((chart.duration - 1) * zoomLevel);
		if(chartDomain < 1)
			chartDomain = 1;
		double preTriggerPercent = triggerPrePostRatio / 100.0;
		double postTriggerPercent = 1.0 - preTriggerPercent;
		int maxSampleNumber = Integer.min(endSampleNumber, triggerChannel.connection.getSampleCount() - 1);
		int minSampleNumber = maxSampleNumber - chartDomain;
		if(minSampleNumber > previousMaxSampleNumber && previousMaxSampleNumber != -1)
			minSampleNumber = previousMaxSampleNumber;
		if(recalcTrigger && triggeredMinSampleNumber != -1)
			minSampleNumber = triggeredMinSampleNumber;
		if(minSampleNumber < 0)
			minSampleNumber = 0;
		previousMaxSampleNumber = maxSampleNumber;
		
		// don't trigger if already triggered, and it should still be on screen
		if(triggered && nextTriggerableSampleNumber >= maxSampleNumber)
			return triggeredEndSampleNumber;
		
		// not triggered, so reset if auto-trigger mode
		if(triggerMode == Mode.AUTO) {
			resetTrigger(false);
			if(triggerAffects == Affects.EVERY_CHART && OpenGLChartsView.instance.isTriggeredView())
				OpenGLChartsView.instance.setLiveView();
		}
		
		// check for a new trigger
		minSampleNumber = Integer.max(minSampleNumber, nextTriggerableSampleNumber);
		boolean triggerOnRisingEdge  = (triggerType == Type.RISING_EDGE)  || (triggerType == Type.BOTH_EDGES);
		boolean triggerOnFallingEdge = (triggerType == Type.FALLING_EDGE) || (triggerType == Type.BOTH_EDGES);
		boolean risingEdgeArmed = false;
		boolean fallingEdgeArmed = false;
		FloatBuffer buffer = datasets.getSamplesBuffer(triggerChannel, minSampleNumber, maxSampleNumber);
		for(int sampleNumber = minSampleNumber; sampleNumber <= maxSampleNumber; sampleNumber++) {
			float value = buffer.get(sampleNumber - minSampleNumber);
			if(triggerOnRisingEdge && value < triggerLevel - triggerHysteresis)
				risingEdgeArmed = true;
			if(triggerOnFallingEdge && value > triggerLevel + triggerHysteresis)
				fallingEdgeArmed = true;
			if((risingEdgeArmed && triggerOnRisingEdge && value >= triggerLevel) || (fallingEdgeArmed && triggerOnFallingEdge && value <= triggerLevel)) {
				triggeredSampleNumber = sampleNumber;
				triggered = true;
				nextTriggerableSampleNumber = triggeredSampleNumber + (int) Math.round(chartDomain * postTriggerPercent);
				long triggeredTimestamp = datasets.getTimestamp(triggeredSampleNumber);
				long millisecondsAfterTrigger = (long) ((chartDomain / triggerChannel.connection.getSampleRate() * 1000) * postTriggerPercent);
				long triggeredEndTimestamp = triggeredTimestamp + millisecondsAfterTrigger;
				triggeredMinSampleNumber = minSampleNumber;
				triggeredEndSampleNumber = triggeredSampleNumber + (int) Math.round(chartDomain * postTriggerPercent);
				if(triggerAffects == Affects.EVERY_CHART)
					OpenGLChartsView.instance.setTriggeredView(triggeredEndTimestamp, triggerChannel.connection, triggeredEndSampleNumber);
				return triggeredEndSampleNumber;
			}
		}
		
		// done
		return triggered ? triggeredEndSampleNumber :
		       triggerMode == Mode.AUTO ? endSampleNumber :
		       -1;
		
	}
	
	/**
	 * Updates the widget and chart based on settings from a settings file.
	 * 
	 * @param lines    A queue of remaining lines from the settings file.
	 */
	@Override public void importFrom(Queue<String> lines) {

		triggerModeButtons.importFrom(lines);
		triggerAffectsButtons.importFrom(lines);
		triggerTypeButtons.importFrom(lines);
		triggerChannelCombobox.importFrom(lines);
		triggerLevelTextfield.importFrom(lines);
		triggerHysteresisTextfield.importFrom(lines);
		
		int ratio = ChartUtils.parseInteger(lines.remove(), "trigger pre/post ratio = %d");
		if(ratio < 0 || ratio > 100)
			throw new AssertionError("Invalid trigger pre/post ratio.");
		else
			setPrePostRatio(ratio);
		
	}
	
	/**
	 * Saves the current state to one or more lines of text.
	 * 
	 * @param    Append lines of text to this List.
	 */
	@Override public void exportTo(List<String> lines) {
		
		triggerModeButtons.exportTo(lines);
		triggerAffectsButtons.exportTo(lines);
		triggerTypeButtons.exportTo(lines);
		triggerChannelCombobox.exportTo(lines);
		triggerLevelTextfield.exportTo(lines);
		triggerHysteresisTextfield.exportTo(lines);
		lines.add("trigger pre/post ratio = " + triggerPrePostRatio);
		
	}

}
