import java.io.PrintWriter;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;

public class WidgetTrigger implements Widget {
	
	public enum Mode {
		DISABLED { @Override public String toString() { return "Disabled"; } },
		AUTO     { @Override public String toString() { return "Auto";     } },
		NORMAL   { @Override public String toString() { return "Normal";   } },
		SINGLE   { @Override public String toString() { return "Single";   } }
	};
	
	public enum Affects {
		THIS_CHART  { @Override public String toString() { return "This Chart";  } },
		EVERY_CHART { @Override public String toString() { return "Every Chart"; } }
	}
	
	public enum Type {
		RISING_EDGE  { @Override public String toString() { return "Rising Edge";  } },
		FALLING_EDGE { @Override public String toString() { return "Falling Edge"; } },
		BOTH_EDGES   { @Override public String toString() { return "Both Edges";   } }
	};
	
	final public WidgetToggleButton<Mode>         mode;
	final public WidgetToggleButton<Affects>      affects;
	final public WidgetToggleButton<Type>         type;
	final public DatasetsInterface.WidgetDatasets channel;
	final public WidgetTextfield<Float>           level;
	final public WidgetTextfield<Float>           hysteresis;
	final public WidgetSlider<Integer>            prePostRatio;
	final private List<Widget> widgets;
	
	private boolean triggeringPaused = false;
	public Field normalDataset = null;
	public Field.Bitfield.State bitfieldState = null;
	private boolean userSpecifiedTheChannel = false;
	final DatasetsInterface datasets = new DatasetsInterface(); // must use our own interface, because the trigger channel might not be displayed on the chart
	final private Chart chart;
	
	private boolean triggered = false;
	private int     triggeredSampleNumber    = -1; // the trigger point
	private long    triggeredTimestamp       = -1;
	private int     triggeredEndSampleNumber = -1; // the right edge of a time domain plot
	private long    triggeredEndTimestamp    = -1;
	
	private int  normalTriggerSearchFromSampleNumber = 0; // in normal mode, start looking here for a new trigger
	
	private int  prePausedEndSampleNumber; // when triggering is paused, the right edge of a time domain plot
	private long prePausedEndTimestamp;
	
	/**
	 * A widget that lets the user configure a trigger and all of its settings.
	 */
	public WidgetTrigger(Chart chart, Consumer<Boolean> eventHandler) {
		
		super();
		this.chart = chart;
		
		affects = new WidgetToggleButton<Affects>("Affects", Affects.values(), Affects.THIS_CHART)
		              .setExportLabel("trigger affects")
		              .onChange((newSetting, oldSetting) -> {
		                   if(OpenGLCharts.globalTrigger == this && newSetting == Affects.THIS_CHART) { // exiting global trigger mode
		                       OpenGLCharts.globalTrigger = null;
		                   } else if(OpenGLCharts.globalTrigger != null && OpenGLCharts.globalTrigger != this && newSetting == Affects.EVERY_CHART) { // taking global control
		                       OpenGLCharts.globalTrigger.affects.set(Affects.THIS_CHART);
		                       OpenGLCharts.globalTrigger.mode.set(Mode.DISABLED);
		                       OpenGLCharts.globalTrigger = this;
		                   } else if(newSetting == Affects.EVERY_CHART) {
		                       OpenGLCharts.globalTrigger = this;
		                   }
		                   return true;
		               });
		
		type = new WidgetToggleButton<Type>("Type", Type.values(), Type.RISING_EDGE)
		           .setExportLabel("trigger type")
		           .onChange((newType, oldType) -> {
		                resetTrigger();
		                return true;
		            });
		
		level = WidgetTextfield.ofFloat(-Float.MAX_VALUE, Float.MAX_VALUE, 0)
		                       .setPrefix("Level")
		                       .setExportLabel("trigger level")
		                       .onChange((newLevel, oldLevel) -> {
		                                     resetTrigger();
		                                     return true;
		                                 });
		
		hysteresis = WidgetTextfield.ofFloat(0, Float.MAX_VALUE, 0)
		                            .setPrefix("Hysteresis")
		                            .setExportLabel("trigger hysteresis")
		                            .onChange((newValue, oldValue) -> {
		                                          resetTrigger();
		                                          return true;
		                                      });
		
		channel = datasets.getDatasetOrStateCombobox(newDataset -> {
		                                                 normalDataset = newDataset;
		                                                 bitfieldState = null;
		                                                 userSpecifiedTheChannel = true;
		                                                 resetTrigger();
		                                                 level.setSuffix(normalDataset.unit.get());
		                                                 level.setEnabled(true);
		                                                 hysteresis.setSuffix(normalDataset.unit.get());
		                                                 hysteresis.setEnabled(true);
		                                             },
		                                             newState -> {
		                                                 normalDataset = null;
		                                                 bitfieldState = newState;
		                                                 userSpecifiedTheChannel = true;
		                                                 resetTrigger();
		                                                 SwingUtilities.invokeLater(() -> { // invokeLater so importing can finish before disabling
		                                                     level.disableWithMessage(" -- ");
		                                                     hysteresis.disableWithMessage(" -- ");
		                                                 });
		                                                 
		                                             });
		
		// the event handler above will get called automatically, so reset userSpecifiedTheChannel to false
		SwingUtilities.invokeLater(() -> userSpecifiedTheChannel = false);
		
		prePostRatio = WidgetSlider.ofInt("Pre/Post Ratio", 0, 10000, 2000)
		                           .setExportLabel("trigger pre/post ratio")
		                           .onDrag(dragStarted -> setPaused(true),
		                                     dragEnded -> setPaused(false));
		
		mode = new WidgetToggleButton<Mode>("", Mode.values(), Mode.DISABLED)
		           .setExportLabel("trigger mode")
		           .onChange((newMode, oldMode) -> {
		                boolean triggerChannelExists = normalDataset != null || bitfieldState != null;
		                boolean enabled = newMode != Mode.DISABLED;
		                if(enabled && !triggerChannelExists)
		                    return false;
		                resetTrigger();
		                affects.setEnabled(enabled && triggerChannelExists);
		                type.setEnabled(enabled && triggerChannelExists);
		                channel.setEnabled(enabled && triggerChannelExists);
		                level.setEnabled(enabled && normalDataset != null);
		                hysteresis.setEnabled(enabled && normalDataset != null);
		                if(bitfieldState != null) {
		                    level.disableWithMessage(" -- ");
		                    hysteresis.disableWithMessage(" -- ");
		                }
		                prePostRatio.setEnabled(enabled && triggerChannelExists);
		                if(eventHandler != null)
		                    eventHandler.accept(enabled && triggerChannelExists);
		                return true;
		            });
		
		widgets = List.of(mode, affects, type, channel, level, hysteresis, prePostRatio);
		
	}
	
	public boolean isEnabled() {
		return !mode.is(Mode.DISABLED);
	}
	
	@Override public void setEnabled(boolean isEnabled) {
		
		mode.set(isEnabled ? Mode.AUTO : Mode.DISABLED);
		
	}
	
	/**
	 * Removes an existing trigger so a new event can be detected.
	 */
	public void resetTrigger() {
		triggered                = false;
		triggeredSampleNumber    = -1;
		triggeredTimestamp       = -1;
		triggeredEndSampleNumber = -1;
		triggeredEndTimestamp    = -1;
		normalTriggerSearchFromSampleNumber = 0;
	}
	
	public void setDefaultChannel(Field dataset) {
		if(!userSpecifiedTheChannel) {
			channel.triggerChannelCombobox.set(dataset.toString());
			userSpecifiedTheChannel = false; // the event handler will set this to true, so reset it to false
		}
	}
	
	public void setPaused(boolean isPaused) {
		if(isPaused && OpenGLCharts.globalTrigger == this)
			OpenGLCharts.GUI.pauseAndSaveState(triggeredEndTimestamp);
		else if(!isPaused && OpenGLCharts.globalTrigger == this)
			OpenGLCharts.GUI.unpauseAndRestoreState();
		
		triggeringPaused = isPaused;
	}
	
	public boolean isPaused() {
		return triggeringPaused;
	}
	
	public record Result(boolean isTriggered,
	                     ConnectionTelemetry connection,
	                     int  chartEndSampleNumber,        // charts should use this
	                     long chartEndTimestamp,           // charts should use this
	                     int  triggeredSampleNumber,       // the trigger point
	                     long triggeredTimestamp,          // the trigger point
	                     int  nonTriggeredEndSampleNumber, // the timeline marker should use this
	                     long nonTriggeredEndTimestamp) {} // the timeline marker should use this
	
	/**
	 * Checks for a trigger event and determines the last sample number or timestamp that should be on screen.
	 * 
	 * The OpenGLChartsView will always determine the non-triggered endSampleNumber and endTimestamp.
	 * If there is a global trigger, OpenGLChartsView will then call this function with those values to determine the triggered values that will be given to the charts.
	 * If there isn't a global trigger, an OpenGLTimeDomainChart may call this function to determine its own triggered value to use.
	 * 
	 * @param nonTriggeredEndSampleNumber    Sample number that should be at the right edge of a plot if not triggered.
	 * @param nonTriggeredEndTimestamp       Timestamp     that should be at the right edge of a plot if not triggered.
	 * @param zoomLevel                      Current zoom level.
	 * @return                               All of the details about the trigger event.
	 */
	public Result checkForTrigger(int nonTriggeredEndSampleNumber, long nonTriggeredEndTimestamp, double zoomLevel) {
		
		Field dataset = normalDataset != null ? normalDataset :
		                bitfieldState != null ? bitfieldState.dataset :
		                                        null;
		int trueMaxSampleNumber = (dataset != null) ? dataset.connection.getSampleCount() - 1 : -1;
		
		// sanity checks
		if(mode.is(Mode.DISABLED) || trueMaxSampleNumber < 1 || (nonTriggeredEndSampleNumber < 1 && nonTriggeredEndTimestamp < 1))
			return new Result(false,
			                  datasets.connection,
			                  nonTriggeredEndSampleNumber,
			                  nonTriggeredEndTimestamp,
			                  -1,
			                  -1,
			                  nonTriggeredEndSampleNumber,
			                  nonTriggeredEndTimestamp);
		
		// reset the trigger if the user rewound to a time before the trigger point
		boolean triggerInvalid = chart.sampleCountMode ? nonTriggeredEndSampleNumber < triggeredSampleNumber :
		                                                 nonTriggeredEndTimestamp    < triggeredTimestamp;
		if(triggered && triggerInvalid)
			resetTrigger();
		
		// reset the trigger in auto mode if time has progressed past that trigger window
		boolean autoTriggerExpired = mode.is(Mode.AUTO) && (chart.sampleCountMode ? nonTriggeredEndSampleNumber > triggeredEndSampleNumber :
		                                                                            nonTriggeredEndTimestamp    > triggeredEndTimestamp);
		if(triggered && autoTriggerExpired)
			resetTrigger();
		
		// if the user is dragging the trigger level or pre/post ratio
		// let the trigger be recalculated based on the old trigger window
		// this lets the user see a live preview of how the change would have affected the plot
		if(triggeringPaused) {
			resetTrigger();
			nonTriggeredEndSampleNumber = prePausedEndSampleNumber;
			nonTriggeredEndTimestamp    = prePausedEndTimestamp;
		} else {
			prePausedEndSampleNumber = triggered ? triggeredEndSampleNumber : nonTriggeredEndSampleNumber;
			prePausedEndTimestamp    = triggered ? triggeredEndTimestamp    : nonTriggeredEndTimestamp;
		}
		
		// if still triggered in single or auto mode, use the existing trigger point
		if(triggered && (mode.is(Mode.SINGLE) || mode.is(Mode.AUTO)))
			return new Result(true,
			                  datasets.connection,
			                  triggeredEndSampleNumber,
			                  triggeredEndTimestamp,
			                  triggeredSampleNumber,
			                  triggeredTimestamp,
			                  nonTriggeredEndSampleNumber,
			                  nonTriggeredEndTimestamp);
		
		// if still triggered in normal mode, use the existing trigger point if it is still applicable
		if(triggered && mode.is(Mode.NORMAL) && chart.sampleCountMode ? (nonTriggeredEndSampleNumber <= triggeredEndSampleNumber) : (nonTriggeredEndTimestamp <= triggeredEndTimestamp))
			return new Result(true,
			                  datasets.connection,
			                  triggeredEndSampleNumber,
			                  triggeredEndTimestamp,
			                  triggeredSampleNumber,
			                  triggeredTimestamp,
			                  nonTriggeredEndSampleNumber,
			                  nonTriggeredEndTimestamp);
		
		// we're not triggered, or we're triggered in normal mode but we need to test new samples
		
		// determine the trigger window (what sample numbers to check)
		int  plotDomainSampleCount  = chart.sampleCountMode ? (int) ((chart.duration - 1) * zoomLevel) :
		                                                      (int) Math.round(chart.duration * zoomLevel / 1000.0 * dataset.connection.getSampleRate());
		long plotDomainMilliseconds = chart.sampleCountMode ? Math.round((chart.duration - 1) * zoomLevel / dataset.connection.getSampleRate() * 1000.0) :
		                                                      Math.round(chart.duration * zoomLevel);
		if(plotDomainSampleCount < 1)
			plotDomainSampleCount = 1;
		if(plotDomainMilliseconds < 1)
			plotDomainMilliseconds = 1;
		double preTriggerPercent = prePostRatio.get() / 10000.0;
		double postTriggerPercent = 1.0 - preTriggerPercent;
		int maxSampleNumber = chart.sampleCountMode ? Integer.min(nonTriggeredEndSampleNumber, trueMaxSampleNumber) :
		                                              datasets.getClosestSampleNumberAtOrBefore(nonTriggeredEndTimestamp, trueMaxSampleNumber);
		long startTimestamp = nonTriggeredEndTimestamp - plotDomainMilliseconds;
		int minSampleNumber = chart.sampleCountMode ? maxSampleNumber - plotDomainSampleCount :
		                                              datasets.getClosestSampleNumberAtOrBefore(startTimestamp, maxSampleNumber);
		if(minSampleNumber < 0)
			minSampleNumber = 0;
		
		boolean triggerOnRisingEdge  = type.is(Type.RISING_EDGE)  || type.is(Type.BOTH_EDGES);
		boolean triggerOnFallingEdge = type.is(Type.FALLING_EDGE) || type.is(Type.BOTH_EDGES);
		boolean triggerOnDataset = normalDataset != null; // false = trigger on a Bitfield State
		float triggerLevel = triggerOnDataset ? level.get() : bitfieldState.value;
		float risingEdgeArmingValue  = level.get() - hysteresis.get();
		float fallingEdgeArmingValue = level.get() + hysteresis.get();
		int LSBit   = triggerOnDataset ? 0 : bitfieldState.bitfield.LSBit;
		int bitmask = triggerOnDataset ? 0 : bitfieldState.bitfield.bitmask;
		
		// if not triggered, search forwards through the trigger window for a new trigger
		if(!triggered) {
			boolean risingEdgeArmed  = false;
			boolean fallingEdgeArmed = false;
			FloatBuffer buffer = datasets.getSamplesBuffer(dataset, minSampleNumber, maxSampleNumber);
			for(int sampleNumber = minSampleNumber; sampleNumber <= maxSampleNumber; sampleNumber++) {
				float value = buffer.get(sampleNumber - minSampleNumber);
				if(!triggerOnDataset)
					value = ((int) value >> LSBit) & bitmask;
				boolean belowThreshold = (triggerOnDataset && value < risingEdgeArmingValue) ||
				                         (!triggerOnDataset && value != triggerLevel);
				boolean aboveThreshold = (triggerOnDataset && value > fallingEdgeArmingValue) ||
				                         (!triggerOnDataset && value == triggerLevel);
				boolean risen  = (triggerOnDataset && value >= triggerLevel) ||
				                 (!triggerOnDataset && value == triggerLevel);
				boolean fallen = (triggerOnDataset && value <= triggerLevel) ||
				                 (!triggerOnDataset && value != triggerLevel);
				if(triggerOnRisingEdge && belowThreshold)
					risingEdgeArmed = true;
				if(triggerOnFallingEdge && aboveThreshold)
					fallingEdgeArmed = true;
				if((risingEdgeArmed && triggerOnRisingEdge && risen) || (fallingEdgeArmed && triggerOnFallingEdge && fallen)) {
					triggered = true;
					triggeredSampleNumber = sampleNumber;
					triggeredTimestamp = datasets.getTimestamp(sampleNumber);
					
					triggeredEndSampleNumber = triggeredSampleNumber + (int)  Math.round(plotDomainSampleCount  * postTriggerPercent);
					triggeredEndTimestamp    = triggeredTimestamp    + (long) Math.round(plotDomainMilliseconds * postTriggerPercent);
					
					normalTriggerSearchFromSampleNumber = triggeredEndSampleNumber;
					break;
				}
			}
		}
		
		// in normal mode, if triggered, if triggering not paused, search forwards *repeatedly* for the *newest* trigger (from normalTriggerSearchFromSampleNumber to maxSampleNumber)
		if(mode.is(Mode.NORMAL) && triggered && !triggeringPaused && normalTriggerSearchFromSampleNumber < maxSampleNumber) {
			boolean risingEdgeArmed  = false;
			boolean fallingEdgeArmed = false;
			minSampleNumber = normalTriggerSearchFromSampleNumber;
			FloatBuffer buffer = datasets.getSamplesBuffer(dataset, minSampleNumber, maxSampleNumber);
			for(int sampleNumber = minSampleNumber; sampleNumber <= maxSampleNumber; sampleNumber++) {
				float value = buffer.get(sampleNumber - minSampleNumber);
				if(!triggerOnDataset)
					value = ((int) value >> LSBit) & bitmask;
				boolean belowThreshold = (triggerOnDataset && value < risingEdgeArmingValue) ||
				                         (!triggerOnDataset && value != triggerLevel);
				boolean aboveThreshold = (triggerOnDataset && value > fallingEdgeArmingValue) ||
				                         (!triggerOnDataset && value == triggerLevel);
				boolean risen  = (triggerOnDataset && value >= triggerLevel) ||
				                 (!triggerOnDataset && value == triggerLevel);
				boolean fallen = (triggerOnDataset && value <= triggerLevel) ||
				                 (!triggerOnDataset && value != triggerLevel);
				if(triggerOnRisingEdge && belowThreshold)
					risingEdgeArmed = true;
				if(triggerOnFallingEdge && aboveThreshold)
					fallingEdgeArmed = true;
				if((risingEdgeArmed && triggerOnRisingEdge && risen) || (fallingEdgeArmed && triggerOnFallingEdge && fallen)) {
					triggered = true;
					triggeredSampleNumber = sampleNumber;
					triggeredTimestamp = datasets.getTimestamp(sampleNumber);
					
					triggeredEndSampleNumber = triggeredSampleNumber + (int)  Math.round(plotDomainSampleCount  * postTriggerPercent);
					triggeredEndTimestamp    = triggeredTimestamp    + (long) Math.round(plotDomainMilliseconds * postTriggerPercent);
					
					normalTriggerSearchFromSampleNumber = triggeredEndSampleNumber;
					
					// don't "break", keep checking for a newer trigger
					sampleNumber = normalTriggerSearchFromSampleNumber;
					risingEdgeArmed = false;
					fallingEdgeArmed = false;
				}
			}
			normalTriggerSearchFromSampleNumber = Integer.max(normalTriggerSearchFromSampleNumber, maxSampleNumber);
		}
		
		// in normal mode, if not triggered, if triggering not paused, search *backwards* for an older trigger (from maxSampleNumber to normalTriggerSearchFromSampleNumber)
		if(mode.is(Mode.NORMAL) && !triggered && !triggeringPaused && normalTriggerSearchFromSampleNumber < maxSampleNumber) {
			boolean risingEdgeArmed  = false;
			boolean fallingEdgeArmed = false;
			FloatBuffer buffer = null;
			int firstSampleInBuffer = -1;
			for(int sampleNumber = maxSampleNumber; sampleNumber >= normalTriggerSearchFromSampleNumber; sampleNumber--) {
				if(sampleNumber == maxSampleNumber || sampleNumber < firstSampleInBuffer) { // need to refill the buffer
					int max = sampleNumber;
					int min = Math.max(0, max - 8192); // buffer size is arbitrary
					buffer = datasets.getSamplesBuffer(dataset, min, max);
					firstSampleInBuffer = min;
				}
				float value = buffer.get(sampleNumber - firstSampleInBuffer);
				if(!triggerOnDataset)
					value = ((int) value >> LSBit) & bitmask;
				boolean aboveThreshold = (triggerOnDataset && value >= triggerLevel) ||
				                         (!triggerOnDataset && value == triggerLevel);
				boolean belowThreshold = (triggerOnDataset && value <= triggerLevel) ||
				                         (!triggerOnDataset && value != triggerLevel);
				boolean risen  = (triggerOnDataset && value < risingEdgeArmingValue) ||
				                 (!triggerOnDataset && value != triggerLevel);
				boolean fallen = (triggerOnDataset && value > fallingEdgeArmingValue) ||
				                 (!triggerOnDataset && value == triggerLevel);
				if(triggerOnRisingEdge && aboveThreshold) {
					risingEdgeArmed = true;
					triggeredSampleNumber = sampleNumber;
				}
				if(triggerOnFallingEdge && belowThreshold) {
					fallingEdgeArmed = true;
					triggeredSampleNumber = sampleNumber;
				}
				if((risingEdgeArmed && triggerOnRisingEdge && risen) || (fallingEdgeArmed && triggerOnFallingEdge && fallen)) {
					triggered = true;
					triggeredTimestamp = datasets.getTimestamp(triggeredSampleNumber);
					
					triggeredEndSampleNumber = triggeredSampleNumber + (int)  Math.round(plotDomainSampleCount  * postTriggerPercent);
					triggeredEndTimestamp    = triggeredTimestamp    + (long) Math.round(plotDomainMilliseconds * postTriggerPercent);
					
					normalTriggerSearchFromSampleNumber = Integer.max(triggeredEndSampleNumber, maxSampleNumber);
					break;
				}
			}
			
			if(!triggered)
				normalTriggerSearchFromSampleNumber = maxSampleNumber;
		}
		
		// done
		return triggered          ? new Result(true, // use the new or existing trigger point
		                                       datasets.connection,
		                                       triggeredEndSampleNumber,
		                                       triggeredEndTimestamp,
		                                       triggeredSampleNumber,
		                                       triggeredTimestamp,
		                                       nonTriggeredEndSampleNumber,
		                                       nonTriggeredEndTimestamp) :
		       mode.is(Mode.AUTO) ? new Result(false, // no trigger, so show the non-triggered values
		                                       datasets.connection,
		                                       nonTriggeredEndSampleNumber,
		                                       nonTriggeredEndTimestamp,
		                                       -1,
		                                       -1,
		                                       nonTriggeredEndSampleNumber,
		                                       nonTriggeredEndTimestamp) :
		                            new Result(false, // no trigger, and show *nothing*
		                                       datasets.connection,
		                                       -1,
		                                       -1,
		                                       -1,
		                                       -1,
		                                       nonTriggeredEndSampleNumber,
		                                       nonTriggeredEndTimestamp);
		
	}
	
	@Override public void appendTo(JPanel panel, String constraints) {
		if(OpenGLCharts.globalTrigger != null && OpenGLCharts.globalTrigger != this) {
			mode.set(Mode.DISABLED);
			mode.setEnabled(false);
		} else {
			mode.setEnabled(true);
		}
		widgets.forEach(widget -> widget.appendTo(panel, ""));
	}
	
	@Override public WidgetTrigger setVisible(boolean isVisible) {
		widgets.forEach(widget -> widget.setVisible(isVisible));
		return this;
	}
	
	@Override public void importFrom(Connections.QueueOfLines lines) throws AssertionError {
		widgets.forEach(widget -> widget.importFrom(lines));
		SwingUtilities.invokeLater(() -> userSpecifiedTheChannel = true); // invokeLater because the constructor also invokeLater's this to false
	}
	
	@Override public void exportTo(PrintWriter file) {
		widgets.forEach(widget -> widget.exportTo(file));
	}

}
