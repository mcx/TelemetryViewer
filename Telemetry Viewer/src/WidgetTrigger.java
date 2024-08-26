import java.io.PrintWriter;
import java.nio.FloatBuffer;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.JPanel;

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
	
	final public WidgetToggleButtonEnum<Mode> mode;
	final public WidgetToggleButtonEnum<Affects> affects;
	final public WidgetToggleButtonEnum<Type> type;
	final public WidgetDatasetComboboxes channel;
	final public WidgetTextfield<Float> level;
	final public WidgetTextfield<Float> hysteresis;
	final public WidgetSlider prePostRatio;
	final private List<Widget> widgets;
	
	private boolean triggeringPaused = false;
	public Field triggerChannel = null;
	private boolean userSpecifiedTheChannel = false;
	final private DatasetsInterface datasets = new DatasetsInterface(); // must use our own interface, because the trigger channel might not be displayed on the chart
	final private PositionedChart chart;
	
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
	public WidgetTrigger(PositionedChart chart, Consumer<Boolean> eventHandler) {
		
		super();
		this.chart = chart;
		
		affects = new WidgetToggleButtonEnum<Affects>("Affects",
		                                              "trigger affects",
		                                              Affects.values(),
		                                              Affects.THIS_CHART,
		                                              newSetting -> {
		                                                  if(OpenGLChartsView.globalTrigger == this && newSetting == Affects.THIS_CHART) { // exiting global trigger mode
		                                                      OpenGLChartsView.globalTrigger = null;
		                                                  } else if(OpenGLChartsView.globalTrigger != null && OpenGLChartsView.globalTrigger != this && newSetting == Affects.EVERY_CHART) { // taking global control
		                                                      OpenGLChartsView.globalTrigger.affects.set(Affects.THIS_CHART);
		                                                      OpenGLChartsView.globalTrigger.mode.set(Mode.DISABLED);
		                                                      OpenGLChartsView.globalTrigger = this;
		                                                  } else if(newSetting == Affects.EVERY_CHART) {
		                                                      OpenGLChartsView.globalTrigger = this;
		                                                  }
		                                                  return true;
		                                              });
		
		type = new WidgetToggleButtonEnum<Type>("Type",
		                                        "trigger type",
		                                        Type.values(),
		                                        Type.RISING_EDGE,
		                                        newType -> {
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
		
		channel = new WidgetDatasetComboboxes(new String[] {"Channel"},
		                                      newDatasets -> {
		                                          if(newDatasets.isEmpty()) // no telemetry connections
		                                              return;
		                                          datasets.setNormals(newDatasets);
		                                          triggerChannel = newDatasets.get(0);
		                                          userSpecifiedTheChannel = true;
		                                          resetTrigger();
		                                          level.setSuffix(datasets.getNormal(0).unit.get());
		                                          hysteresis.setSuffix(datasets.getNormal(0).unit.get());
		                                      });
		
		// the event handler above will get called automatically, so reset userSpecifiedTheChannel to false
		userSpecifiedTheChannel = false;
		
		prePostRatio = new WidgetSlider("Pre/Post Ratio", 0, 100, 20)
		                   .setExportLabel("trigger pre/post ratio")
		                   .onChange(dragStarted -> setPaused(true),
		                             null,
		                             dragEnded -> setPaused(false));
		
		mode = new WidgetToggleButtonEnum<Mode>("",
		                                        "trigger mode",
		                                        Mode.values(),
		                                        Mode.DISABLED,
		                                        newMode -> {
		                                            if(newMode != Mode.DISABLED && triggerChannel == null)
		                                                return false;
		                                            resetTrigger();
		                                            boolean triggerEnabled = newMode != Mode.DISABLED && triggerChannel != null;
		                                            affects.setEnabled(triggerEnabled);
		                                            type.setEnabled(triggerEnabled);
		                                            channel.setEnabled(triggerEnabled);
		                                            level.setEnabled(triggerEnabled);
		                                            hysteresis.setEnabled(triggerEnabled);
		                                            prePostRatio.setEnabled(triggerEnabled);
		                                            eventHandler.accept(triggerEnabled);
		                                            return true;
		                                        });
		
		widgets = List.of(mode, affects, type, channel, level, hysteresis, prePostRatio);
		
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
			channel.setDataset(0, dataset);
			userSpecifiedTheChannel = false; // the event handler will set this to true, so reset it to false
		}
	}
	
	public void setPaused(boolean isPaused) {
		if(isPaused && OpenGLChartsView.globalTrigger == this)
			OpenGLChartsView.instance.pauseAndSaveState(triggeredEndTimestamp);
		else if(!isPaused && OpenGLChartsView.globalTrigger == this)
			OpenGLChartsView.instance.unpauseAndRestoreState();
		
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
		
		int trueMaxSampleNumber = (triggerChannel == null) ? -1 : triggerChannel.connection.getSampleCount() - 1;
		
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
		                                                      (int) Math.round(chart.duration * zoomLevel / 1000.0 * triggerChannel.connection.getSampleRate());
		long plotDomainMilliseconds = chart.sampleCountMode ? Math.round((chart.duration - 1) * zoomLevel / triggerChannel.connection.getSampleRate() * 1000.0) :
		                                                      Math.round(chart.duration * zoomLevel);
		if(plotDomainSampleCount < 1)
			plotDomainSampleCount = 1;
		if(plotDomainMilliseconds < 1)
			plotDomainMilliseconds = 1;
		double preTriggerPercent = prePostRatio.get() / 100.0;
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
		
		// if not triggered, search forwards through the trigger window for a new trigger
		if(!triggered) {
			boolean risingEdgeArmed  = false;
			boolean fallingEdgeArmed = false;
			FloatBuffer buffer = datasets.getSamplesBuffer(triggerChannel, minSampleNumber, maxSampleNumber);
			for(int sampleNumber = minSampleNumber; sampleNumber <= maxSampleNumber; sampleNumber++) {
				float value = buffer.get(sampleNumber - minSampleNumber);
				if(triggerOnRisingEdge && value < level.get() - hysteresis.get())
					risingEdgeArmed = true;
				if(triggerOnFallingEdge && value > level.get() + hysteresis.get())
					fallingEdgeArmed = true;
				if((risingEdgeArmed && triggerOnRisingEdge && value >= level.get()) || (fallingEdgeArmed && triggerOnFallingEdge && value <= level.get())) {
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
			FloatBuffer buffer = datasets.getSamplesBuffer(triggerChannel, minSampleNumber, maxSampleNumber);
			for(int sampleNumber = minSampleNumber; sampleNumber <= maxSampleNumber; sampleNumber++) {
				float value = buffer.get(sampleNumber - minSampleNumber);
				if(triggerOnRisingEdge && value < level.get() - hysteresis.get())
					risingEdgeArmed = true;
				if(triggerOnFallingEdge && value > level.get() + hysteresis.get())
					fallingEdgeArmed = true;
				if((risingEdgeArmed && triggerOnRisingEdge && value >= level.get()) || (fallingEdgeArmed && triggerOnFallingEdge && value <= level.get())) {
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
					buffer = datasets.getSamplesBuffer(triggerChannel, min, max);
					firstSampleInBuffer = min;
				}
				float value = buffer.get(sampleNumber - firstSampleInBuffer);
				if(triggerOnRisingEdge && value >= level.get()) {
					risingEdgeArmed = true;
					triggeredSampleNumber = sampleNumber;
				}
				if(triggerOnFallingEdge && value <= level.get()) {
					fallingEdgeArmed = true;
					triggeredSampleNumber = sampleNumber;
				}
				if((risingEdgeArmed && triggerOnRisingEdge && value < level.get() - hysteresis.get()) || (fallingEdgeArmed && triggerOnFallingEdge && value > level.get() + hysteresis.get())) {
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
		if(OpenGLChartsView.globalTrigger != null && OpenGLChartsView.globalTrigger != this) {
			mode.set(Mode.DISABLED);
			mode.setEnabled(false);
		} else {
			mode.setEnabled(true);
		}
		widgets.forEach(widget -> widget.appendTo(panel, ""));
	}
	
	@Override public void setVisible(boolean isVisible) {
		widgets.forEach(widget -> widget.setVisible(isVisible));
	}
	
	@Override public void importFrom(ConnectionsController.QueueOfLines lines) throws AssertionError {
		widgets.forEach(widget -> widget.importFrom(lines));
	}
	
	@Override public void exportTo(PrintWriter file) {
		widgets.forEach(widget -> widget.exportTo(file));
	}

}
