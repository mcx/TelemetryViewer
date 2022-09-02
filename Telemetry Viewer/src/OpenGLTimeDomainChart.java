import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.swing.Box;
import javax.swing.JPanel;
import com.jogamp.opengl.GL2ES3;
import com.jogamp.opengl.GL3;

public class OpenGLTimeDomainChart extends PositionedChart {
	
	// plot region
	float xPlotLeft;
	float xPlotRight;
	float plotWidth;
	float yPlotTop;
	float yPlotBottom;
	float plotHeight;
	float plotMaxY;
	float plotMinY;
	float plotRange;
	
	// x-axis title
	float yXaxisTitleTextBasline;
	float yXaxisTitleTextTop;
	String xAxisTitle;
	float xXaxisTitleTextLeft;
	
	// legend
	float xLegendBorderLeft;
	float yLegendBorderBottom;
	float yLegendTextBaseline;
	float yLegendTextTop;
	float yLegendBorderTop;
	float[][] legendMouseoverCoordinates;
	float[][] legendBoxCoordinates;
	float[] xLegendNameLeft;
	float xLegendBorderRight;
	
	// x-axis scale
	float yXaxisTickTextBaseline;
	float yXaxisTickTextTop;
	float yXaxisTickBottom;
	float yXaxisTickTop;
	boolean isTimestampsMode;
	
	// y-axis title
	float xYaxisTitleTextTop;
	float xYaxisTitleTextBaseline;
	String yAxisTitle;
	float yYaxisTitleTextLeft;
	
	// y-axis scale
	Map<Float, String> yDivisions;
	float xYaxisTickTextRight;
	float xYaxisTickLeft;
	float xYaxisTickRight;
	AutoScale autoscale;
	
	Plot plot;
	List<Dataset> allDatasets = new ArrayList<Dataset>(); // normal and bitfields
	
	// trigger
	boolean triggerEnabled = false;
	boolean triggeringPaused = false;
	int earlierEndSampleNumber = -1;
	long earlierEndTimestamp = -1;
	float earlierPlotMaxY = 1;
	float earlierPlotMinY = -1;
	
	// user settings
	private WidgetDatasetCheckboxes datasetsAndDurationWidget;
	
	private boolean legendVisible = true;
	private WidgetCheckbox legendCheckbox;
	
	private boolean cached = false;
	private WidgetCheckbox cachedCheckbox;

	private boolean xAxisTicksVisible = true;
	private WidgetCheckbox xAxisTicksCheckbox;
	
	private boolean xAxisTitleVisible = true;
	private WidgetCheckbox xAxisTitleCheckbox;
	
	private float yAxisMinimum = -1;
	private WidgetTextfieldFloat yAxisMinimumTextfield;
	
	private boolean yAxisMinimumAutomatic = true;
	private WidgetCheckbox yAxisMinimumAutomaticCheckbox;
	
	private float yAxisMaximum = 1;
	private WidgetTextfieldFloat yAxisMaximumTextfield;
	
	private boolean yAxisMaximumAutomatic = true;
	private WidgetCheckbox yAxisMaximumAutomaticCheckbox;
	
	private boolean yAxisTicksVisible = true;
	private WidgetCheckbox yAxisTicksCheckbox;
	
	private boolean yAxisTitleVisible = true;
	private WidgetCheckbox yAxisTitleCheckbox;
	
	WidgetTrigger triggerWidget;
	
	@Override public String toString() {
		
		return "Time Domain";
		
	}
	
	/**
	 * Updates the List of all datasets.
	 */
	private void updateAllDatasetsList() {
		
		allDatasets = new ArrayList<Dataset>(datasets.normalDatasets);
		
		datasets.edgeStates.forEach(state -> {
			if(!allDatasets.contains(state.dataset))
				allDatasets.add(state.dataset);
		});
		datasets.levelStates.forEach(state -> {
			if(!allDatasets.contains(state.dataset))
				allDatasets.add(state.dataset);
		});
		
	}
	
	public OpenGLTimeDomainChart(int x1, int y1, int x2, int y2) {
		
		super(x1, y1, x2, y2);
		
		autoscale = new AutoScale(AutoScale.MODE_EXPONENTIAL, 30, 0.10f);
		
		// create the control widgets and event handlers
		legendCheckbox = new WidgetCheckbox("Show Legend",
		                                    legendVisible,
		                                    isVisible -> legendVisible = isVisible);
		
		cachedCheckbox = new WidgetCheckbox("Cached Mode",
		                                    cached,
		                                    newCachedMode -> {
		                                        cached = newCachedMode;
		                                        autoscale = cached ? new AutoScale(AutoScale.MODE_STICKY,       1, 0.10f) :
		                                                             new AutoScale(AutoScale.MODE_EXPONENTIAL, 30, 0.10f);
		                                    });
		
		xAxisTicksCheckbox = new WidgetCheckbox("Show Ticks",
		                                        "x-axis show ticks",
		                                        xAxisTicksVisible,
		                                        isVisible -> xAxisTicksVisible = isVisible);
		
		xAxisTitleCheckbox = new WidgetCheckbox("Show Title",
		                                        "x-axis show title",
		                                        xAxisTitleVisible,
		                                        isVisible -> xAxisTitleVisible = isVisible);
		
		yAxisMinimumTextfield = new WidgetTextfieldFloat("Minimum",
		                                                 "y-axis minimum",
		                                                 "",
		                                                 -Float.MAX_VALUE,
		                                                 Float.MAX_VALUE,
		                                                 yAxisMinimum,
		                                                 newMinimum -> {
		                                                     yAxisMinimum = newMinimum;
		                                                     if(yAxisMinimum > yAxisMaximum)
		                                                         yAxisMaximumTextfield.setNumber(yAxisMinimum);
		                                                 });
		
		yAxisMinimumAutomaticCheckbox = new WidgetCheckbox("Automatic",
		                                                   "y-axis minimum automatic",
		                                                   yAxisMinimumAutomatic,
		                                                   isAutomatic -> {
		                                                       yAxisMinimumAutomatic = isAutomatic;
		                                                       if(isAutomatic)
		                                                           yAxisMinimumTextfield.disableWithMessage("Automatic");
		                                                       else
		                                                           yAxisMinimumTextfield.setEnabled(true);
		                                                   });
		
		yAxisMaximumTextfield = new WidgetTextfieldFloat("Maximum",
		                                                 "y-axis maximum",
		                                                 "",
		                                                 -Float.MAX_VALUE,
		                                                 Float.MAX_VALUE,
		                                                 yAxisMaximum,
		                                                 newMaximum -> {
		                                                     yAxisMaximum = newMaximum;
		                                                     if(yAxisMaximum < yAxisMinimum)
		                                                         yAxisMinimumTextfield.setNumber(yAxisMaximum);
		                                                 });
		
		yAxisMaximumAutomaticCheckbox = new WidgetCheckbox("Automatic",
		                                                   "y-axis maximum automatic",
		                                                   yAxisMaximumAutomatic,
		                                                   isAutomatic -> {
		                                                       yAxisMaximumAutomatic = isAutomatic;
		                                                       if(isAutomatic)
		                                                           yAxisMaximumTextfield.disableWithMessage("Automatic");
		                                                       else
		                                                           yAxisMaximumTextfield.setEnabled(true);
		                                                   });
		
		yAxisTicksCheckbox = new WidgetCheckbox("Show Ticks",
		                                        "y-axis show ticks",
		                                        yAxisTicksVisible,
		                                        isVisible -> yAxisTicksVisible = isVisible);
		
		yAxisTitleCheckbox = new WidgetCheckbox("Show Title",
		                                        "y-axis show title",
		                                        yAxisTitleVisible,
		                                        isVisible -> yAxisTitleVisible = isVisible);
		
		datasetsAndDurationWidget = new WidgetDatasetCheckboxes(newDatasets -> {
		                                                            datasets.setNormals(newDatasets);
		                                                            updateAllDatasetsList();
		                                                            if(datasets.normalsCount() == 1) {
		                                                                yAxisMinimumTextfield.setUnit(datasets.getNormal(0).unit);
		                                                                yAxisMaximumTextfield.setUnit(datasets.getNormal(0).unit);
		                                                                triggerWidget.setDefaultChannel(datasets.getNormal(0));
		                                                            } else if(datasets.normalsCount() == 0) {
		                                                                yAxisMinimumTextfield.setUnit("");
		                                                                yAxisMaximumTextfield.setUnit("");
		                                                            }
		                                                        },
		                                                        newBitfieldEdges -> {
		                                                            datasets.setEdges(newBitfieldEdges);
		                                                            updateAllDatasetsList();
		                                                        },
		                                                        newBitfieldLevels -> {
		                                                            datasets.setLevels(newBitfieldLevels);
		                                                            updateAllDatasetsList();
		                                                        },
		                                                        (newDurationType, newDuration) -> {
		                                                            sampleCountMode  = newDurationType == WidgetDatasetCheckboxes.AxisType.SAMPLE_COUNT;
		                                                            isTimestampsMode = newDurationType == WidgetDatasetCheckboxes.AxisType.TIMESTAMPS;
		                                                            if(sampleCountMode)
		                                                            	newDuration = Math.min(newDuration, Integer.MAX_VALUE / 16);
		                                                            duration = (int) (long) newDuration;
		                                                            plot = sampleCountMode ? new PlotSampleCount() : new PlotMilliseconds();
		                                                            if(triggerWidget != null)
		                                                                triggerWidget.resetTrigger(true);
		                                                            return newDuration;
		                                                        },
		                                                        true);
		
		triggerWidget = new WidgetTrigger(this,
		                                  isEnabled -> triggerEnabled = isEnabled);
		
		widgets.add(datasetsAndDurationWidget);
		widgets.add(legendCheckbox);
		widgets.add(cachedCheckbox);
		widgets.add(xAxisTicksCheckbox);
		widgets.add(xAxisTitleCheckbox);
		widgets.add(yAxisMinimumTextfield);
		widgets.add(yAxisMinimumAutomaticCheckbox);
		widgets.add(yAxisMaximumTextfield);
		widgets.add(yAxisMaximumAutomaticCheckbox);
		widgets.add(yAxisTicksCheckbox);
		widgets.add(yAxisTitleCheckbox);
		
		widgets.add(triggerWidget);
		trigger = triggerWidget;
		
	}
	
	@Override public void getConfigurationGui(JPanel gui) {
		
		JPanel dataPanel = Theme.newWidgetsPanel("Data");
		datasetsAndDurationWidget.appendToGui(dataPanel);
		dataPanel.add(legendCheckbox, "split 2");
		dataPanel.add(cachedCheckbox);
		
		JPanel xAxisPanel = Theme.newWidgetsPanel("X-Axis");
		xAxisPanel.add(xAxisTicksCheckbox, "split 2");
		xAxisPanel.add(xAxisTitleCheckbox);
		
		JPanel yAxisPanel = Theme.newWidgetsPanel("Y-Axis");
		yAxisPanel.add(yAxisMinimumTextfield, "split 2, grow");
		yAxisPanel.add(yAxisMinimumAutomaticCheckbox, "sizegroup 1");
		yAxisPanel.add(yAxisMaximumTextfield, "split 2, grow");
		yAxisPanel.add(yAxisMaximumAutomaticCheckbox, "sizegroup 1");
		yAxisPanel.add(Box.createVerticalStrut(Theme.padding));
		yAxisPanel.add(yAxisTicksCheckbox, "split 2");
		yAxisPanel.add(yAxisTitleCheckbox);
		
		JPanel triggerPanel = Theme.newWidgetsPanel("Trigger");
		triggerWidget.appendToGui(triggerPanel);
		
		gui.add(dataPanel);
		gui.add(xAxisPanel);
		gui.add(yAxisPanel);
		gui.add(triggerPanel);
		
	}
	
	@Override public EventHandler drawChart(GL2ES3 gl, float[] chartMatrix, int width, int height, long endTimestamp, int endSampleNumber, double zoomLevel, int mouseX, int mouseY) {
		
		EventHandler handler = null;
		
		// trigger logic
		if(triggerEnabled && datasets.hasNormals()) {
			if(sampleCountMode && triggeringPaused) {
				endSampleNumber = triggerWidget.checkForTriggerSampleCountMode(earlierEndSampleNumber, zoomLevel, true);
			} else if(sampleCountMode && !triggeringPaused) {
				if(!OpenGLChartsView.instance.isPausedView())
					endSampleNumber = datasets.connection.getSampleCount() - 1;
				endSampleNumber = triggerWidget.checkForTriggerSampleCountMode(endSampleNumber, zoomLevel, false);
				earlierEndSampleNumber = endSampleNumber;
			} else if(!sampleCountMode && triggeringPaused) {
				endTimestamp = triggerWidget.checkForTriggerMillisecondsMode(earlierEndTimestamp, zoomLevel, true);
			} else {
				if(!OpenGLChartsView.instance.isPausedView())
					endTimestamp = datasets.getTimestamp(datasets.connection.getSampleCount() - 1);
				endTimestamp = triggerWidget.checkForTriggerMillisecondsMode(endTimestamp, zoomLevel, false);
				earlierEndTimestamp = endTimestamp;
			}
		}
		
		int datasetsCount = allDatasets.size();
		
		plot.initialize(sampleCountMode ? endSampleNumber : endTimestamp, datasets, Math.round(duration * zoomLevel), cached, isTimestampsMode);
		
		// calculate the plot range
		StorageFloats.MinMax requiredRange = plot.getRange();
		autoscale.update(requiredRange.min, requiredRange.max);
		plotMinY = yAxisMinimumAutomatic ? autoscale.getMin() : yAxisMinimum;
		plotMaxY = yAxisMaximumAutomatic ? autoscale.getMax() : yAxisMaximum;
		if(triggerEnabled) {
			if(triggeringPaused) {
				plotMaxY = earlierPlotMaxY;
				plotMinY = earlierPlotMinY;
			} else {
				earlierPlotMaxY = plotMaxY;
				earlierPlotMinY = plotMinY;
			}
		}
		plotRange = plotMaxY - plotMinY;
		
		// calculate x and y positions of everything
		xPlotLeft = Theme.tilePadding;
		xPlotRight = width - Theme.tilePadding;
		plotWidth = xPlotRight - xPlotLeft;
		yPlotTop = height - Theme.tilePadding;
		yPlotBottom = Theme.tilePadding;
		plotHeight = yPlotTop - yPlotBottom;
		
		if(xAxisTitleVisible) {
			yXaxisTitleTextBasline = Theme.tilePadding;
			yXaxisTitleTextTop = yXaxisTitleTextBasline + OpenGL.largeTextHeight;
			xAxisTitle = plot.getTitle();
			xXaxisTitleTextLeft = xPlotLeft + (plotWidth  / 2.0f) - (OpenGL.largeTextWidth(gl, xAxisTitle)  / 2.0f);
			
			float temp = yXaxisTitleTextTop + Theme.tickTextPadding;
			if(yPlotBottom < temp) {
				yPlotBottom = temp;
				plotHeight = yPlotTop - yPlotBottom;
			}
		}
		
		if(legendVisible && datasetsCount > 0) {
			xLegendBorderLeft = Theme.tilePadding;
			yLegendBorderBottom = Theme.tilePadding;
			yLegendTextBaseline = yLegendBorderBottom + Theme.legendTextPadding;
			yLegendTextTop = yLegendTextBaseline + OpenGL.mediumTextHeight;
			yLegendBorderTop = yLegendTextTop + Theme.legendTextPadding;
			
			legendMouseoverCoordinates = new float[datasetsCount][4];
			legendBoxCoordinates = new float[datasetsCount][4];
			xLegendNameLeft = new float[datasetsCount];
			
			float xOffset = xLegendBorderLeft + (Theme.lineWidth / 2) + Theme.legendTextPadding;
			
			for(int i = 0; i < datasetsCount; i++) {
				legendMouseoverCoordinates[i][0] = xOffset - Theme.legendTextPadding;
				legendMouseoverCoordinates[i][1] = yLegendBorderBottom;
				
				legendBoxCoordinates[i][0] = xOffset;
				legendBoxCoordinates[i][1] = yLegendTextBaseline;
				legendBoxCoordinates[i][2] = xOffset + OpenGL.mediumTextHeight;
				legendBoxCoordinates[i][3] = yLegendTextTop;
				
				xOffset += OpenGL.mediumTextHeight + Theme.legendTextPadding;
				xLegendNameLeft[i] = xOffset;
				xOffset += OpenGL.mediumTextWidth(gl, allDatasets.get(i).name) + Theme.legendNamesPadding;
				
				legendMouseoverCoordinates[i][2] = xOffset - Theme.legendNamesPadding + Theme.legendTextPadding;
				legendMouseoverCoordinates[i][3] = yLegendBorderTop;
			}
			
			xLegendBorderRight = xOffset - Theme.legendNamesPadding + Theme.legendTextPadding + (Theme.lineWidth / 2);
			if(xAxisTitleVisible)
				xXaxisTitleTextLeft = xLegendBorderRight + ((xPlotRight - xLegendBorderRight) / 2) - (OpenGL.largeTextWidth(gl, xAxisTitle)  / 2.0f);
			
			float temp = yLegendBorderTop + Theme.legendTextPadding;
			if(yPlotBottom < temp) {
				yPlotBottom = temp;
				plotHeight = yPlotTop - yPlotBottom;
			}
		}
		
		if(xAxisTicksVisible) {
			yXaxisTickTextBaseline = yPlotBottom;
			yXaxisTickTextTop = yXaxisTickTextBaseline + OpenGL.smallTextHeight;
			if(isTimestampsMode && SettingsController.isTimeFormatTwoLines())
				yXaxisTickTextTop += 1.3 * OpenGL.smallTextHeight;
			yXaxisTickBottom = yXaxisTickTextTop + Theme.tickTextPadding;
			yXaxisTickTop = yXaxisTickBottom + Theme.tickLength;
			
			yPlotBottom = yXaxisTickTop;
			plotHeight = yPlotTop - yPlotBottom;
		}
		
		if(yAxisTitleVisible) {
			xYaxisTitleTextTop = xPlotLeft;
			xYaxisTitleTextBaseline = xYaxisTitleTextTop + OpenGL.largeTextHeight;
			yAxisTitle = (datasetsCount > 0) ? allDatasets.get(0).unit : "";
			yYaxisTitleTextLeft = yPlotBottom + (plotHeight / 2.0f) - (OpenGL.largeTextWidth(gl, yAxisTitle) / 2.0f);
			
			xPlotLeft = xYaxisTitleTextBaseline + Theme.tickTextPadding;
			plotWidth = xPlotRight - xPlotLeft;
			
			if(xAxisTitleVisible && !legendVisible)
				xXaxisTitleTextLeft = xPlotLeft + (plotWidth  / 2.0f) - (OpenGL.largeTextWidth(gl, xAxisTitle)  / 2.0f);
		}
		
		if(yAxisTicksVisible) {
			yDivisions = ChartUtils.getYdivisions125(plotHeight, plotMinY, plotMaxY);
			float maxTextWidth = 0;
			for(String text : yDivisions.values()) {
				float textWidth = OpenGL.smallTextWidth(gl, text);
				if(textWidth > maxTextWidth)
					maxTextWidth = textWidth;
					
			}
			
			xYaxisTickTextRight = xPlotLeft + maxTextWidth;
			xYaxisTickLeft = xYaxisTickTextRight + Theme.tickTextPadding;
			xYaxisTickRight = xYaxisTickLeft + Theme.tickLength;
			
			xPlotLeft = xYaxisTickRight;
			plotWidth = xPlotRight - xPlotLeft;
			
			if(xAxisTitleVisible && !legendVisible)
				xXaxisTitleTextLeft = xPlotLeft + (plotWidth  / 2.0f) - (OpenGL.largeTextWidth(gl, xAxisTitle)  / 2.0f);
		}
		
		// stop if the plot is too small
		if(plotWidth < 1 || plotHeight < 1)
			return handler;
		
		// force the plot to be an integer number of pixels
		xPlotLeft = (int) xPlotLeft;
		xPlotRight = (int) xPlotRight;
		yPlotBottom = (int) yPlotBottom;
		yPlotTop = (int) yPlotTop;
		plotWidth = xPlotRight - xPlotLeft;
		plotHeight = yPlotTop - yPlotBottom;
		
		// draw plot background
		OpenGL.drawQuad2D(gl, Theme.plotBackgroundColor, xPlotLeft, yPlotBottom, xPlotRight, yPlotTop);
		
		// draw the x-axis scale
		if(xAxisTicksVisible) {
			Map<Float, String> divisions = plot.getXdivisions(gl, (int) plotWidth);
			
			OpenGL.buffer.rewind();
			for(Float divisionLocation : divisions.keySet()) {
				float x = divisionLocation + xPlotLeft;
				OpenGL.buffer.put(x); OpenGL.buffer.put(yPlotTop);    OpenGL.buffer.put(Theme.divisionLinesColor);
				OpenGL.buffer.put(x); OpenGL.buffer.put(yPlotBottom); OpenGL.buffer.put(Theme.divisionLinesColor);
				
				OpenGL.buffer.put(x); OpenGL.buffer.put(yXaxisTickTop);    OpenGL.buffer.put(Theme.tickLinesColor);
				OpenGL.buffer.put(x); OpenGL.buffer.put(yXaxisTickBottom); OpenGL.buffer.put(Theme.tickLinesColor);
			}
			OpenGL.buffer.rewind();
			int vertexCount = divisions.keySet().size() * 4;
			OpenGL.drawLinesXyrgba(gl, GL3.GL_LINES, OpenGL.buffer, vertexCount);
			
			for(Map.Entry<Float,String> entry : divisions.entrySet()) {
				String[] lines = entry.getValue().split("\n");
				float x = 0;
				float y = yXaxisTickTextBaseline + ((lines.length - 1) * 1.3f * OpenGL.smallTextHeight);
				for(String line : lines) {
					x = entry.getKey() + xPlotLeft - (OpenGL.smallTextWidth(gl, line) / 2.0f);
					OpenGL.drawSmallText(gl, line, (int) x, (int) y, 0);
					y -= 1.3f * OpenGL.smallTextHeight;
				}
			}
		}
		
		// draw the y-axis scale
		if(yAxisTicksVisible) {
			OpenGL.buffer.rewind();
			for(Float entry : yDivisions.keySet()) {
				float y = (entry - plotMinY) / plotRange * plotHeight + yPlotBottom;
				OpenGL.buffer.put(xPlotLeft);  OpenGL.buffer.put(y); OpenGL.buffer.put(Theme.divisionLinesColor);
				OpenGL.buffer.put(xPlotRight); OpenGL.buffer.put(y); OpenGL.buffer.put(Theme.divisionLinesColor);
				
				OpenGL.buffer.put(xYaxisTickLeft);  OpenGL.buffer.put(y); OpenGL.buffer.put(Theme.tickLinesColor);
				OpenGL.buffer.put(xYaxisTickRight); OpenGL.buffer.put(y); OpenGL.buffer.put(Theme.tickLinesColor);
			}
			OpenGL.buffer.rewind();
			int vertexCount = yDivisions.keySet().size() * 4;
			OpenGL.drawLinesXyrgba(gl, GL3.GL_LINES, OpenGL.buffer, vertexCount);
			
			for(Map.Entry<Float,String> entry : yDivisions.entrySet()) {
				float x = xYaxisTickTextRight - OpenGL.smallTextWidth(gl, entry.getValue());
				float y = (entry.getKey() - plotMinY) / plotRange * plotHeight + yPlotBottom - (OpenGL.smallTextHeight / 2.0f);
				OpenGL.drawSmallText(gl, entry.getValue(), (int) x, (int) y, 0);
			}
		}
		
		// draw the legend, if space is available
		if(legendVisible && datasetsCount > 0 && xLegendBorderRight < width - Theme.tilePadding) {
			OpenGL.drawQuad2D(gl, Theme.legendBackgroundColor, xLegendBorderLeft, yLegendBorderBottom, xLegendBorderRight, yLegendBorderTop);
			
			for(int i = 0; i < datasetsCount; i++) {
				if(mouseX >= legendMouseoverCoordinates[i][0] && mouseX <= legendMouseoverCoordinates[i][2] && mouseY >= legendMouseoverCoordinates[i][1] && mouseY <= legendMouseoverCoordinates[i][3]) {
					OpenGL.drawQuadOutline2D(gl, Theme.tickLinesColor, legendMouseoverCoordinates[i][0], legendMouseoverCoordinates[i][1], legendMouseoverCoordinates[i][2], legendMouseoverCoordinates[i][3]);
					Dataset d = allDatasets.get(i);
					handler = EventHandler.onPress(event -> ConfigureView.instance.forDataset(d));
				}
				OpenGL.drawQuad2D(gl, allDatasets.get(i).glColor, legendBoxCoordinates[i][0], legendBoxCoordinates[i][1], legendBoxCoordinates[i][2], legendBoxCoordinates[i][3]);
				OpenGL.drawMediumText(gl, allDatasets.get(i).name, (int) xLegendNameLeft[i], (int) yLegendTextBaseline, 0);
			}
		}
		
		// draw the x-axis title, if space is available
		if(xAxisTitleVisible)
			if((!legendVisible && xXaxisTitleTextLeft > xPlotLeft) || (legendVisible && xXaxisTitleTextLeft > xLegendBorderRight + Theme.legendTextPadding))
				OpenGL.drawLargeText(gl, xAxisTitle, (int) xXaxisTitleTextLeft, (int) yXaxisTitleTextBasline, 0);
		
		// draw the y-axis title, if space is available
		if(yAxisTitleVisible && yYaxisTitleTextLeft > yPlotBottom)
			OpenGL.drawLargeText(gl, yAxisTitle, (int) xYaxisTitleTextBaseline, (int) yYaxisTitleTextLeft, 90);
		
		// acquire the samples
		plot.acquireSamples(plotMinY, plotMaxY, (int) plotWidth, (int) plotHeight);
		
		// draw the plot
		plot.draw(gl, chartMatrix, (int) xPlotLeft, (int) yPlotBottom, (int) plotWidth, (int) plotHeight, plotMinY, plotMaxY);
		
		// draw the trigger level and trigger point markers
		if(triggerEnabled) {
			
			float scalar = ChartsController.getDisplayScalingFactor();
			float markerThickness = 3*scalar;
			float markerLength = 5*scalar;
			float triggerLevel = triggerWidget.getTriggerLevel();
			float yTriggerLevel = (triggerLevel - plotMinY) / plotRange * plotHeight + yPlotBottom;
			
			int triggeredSampleNumber = triggerWidget.getTriggeredSampleNumber();
			float triggerPoint = triggeredSampleNumber >= 0 ? plot.getPixelXforSampleNumber(triggeredSampleNumber, plotWidth) : 0;
			float xTriggerPoint = xPlotLeft + triggerPoint;
			
			boolean mouseOver = false;
			
			// trigger level marker
			if(yTriggerLevel >= yPlotBottom && yTriggerLevel <= yPlotTop) {
				if(mouseX >= xPlotLeft && mouseX <= xPlotLeft + markerLength*1.5 && mouseY >= yTriggerLevel - markerThickness*1.5 && mouseY <= yTriggerLevel + markerThickness*1.5) {
					mouseOver = true;
					handler = EventHandler.onPressOrDrag(dragStarted -> triggeringPaused = true,
					                                     newLocation -> {
					                                         float newTriggerLevel = (newLocation.y - yPlotBottom) / plotHeight * plotRange + plotMinY;
					                                         if(newTriggerLevel < plotMinY)
					                                         	 newTriggerLevel = plotMinY;
					                                         if(newTriggerLevel > plotMaxY)
					                                        	 newTriggerLevel = plotMaxY;
					                                         triggerWidget.setLevel(newTriggerLevel, false);
					                                     },
					                                     dragEnded -> triggeringPaused = false,
					                                     this,
					                                     Theme.upDownCursor);
					OpenGL.drawTriangle2D(gl, Theme.plotOutlineColor, xPlotLeft, yTriggerLevel + markerThickness*1.5f,
					                                                  xPlotLeft + markerLength*1.5f, yTriggerLevel,
					                                                  xPlotLeft, yTriggerLevel - markerThickness*1.5f);
				} else {
					OpenGL.drawTriangle2D(gl, Theme.plotOutlineColor, xPlotLeft, yTriggerLevel + markerThickness,
					                                                  xPlotLeft + markerLength, yTriggerLevel,
					                                                  xPlotLeft, yTriggerLevel - markerThickness);
				}
			}
			
			// trigger point marker
			if(triggeredSampleNumber >= 0) {
				if(xTriggerPoint >= xPlotLeft && xTriggerPoint <= xPlotRight) {
					if(mouseX >= xTriggerPoint - 1.5*markerThickness && mouseX <= xTriggerPoint + 1.5*markerThickness && mouseY >= yPlotTop - 1.5*markerLength && mouseY <= yPlotTop) {
						mouseOver = true;
						handler = EventHandler.onPressOrDrag(dragStarted -> triggeringPaused = true,
						                                     newLocation -> {
						                                         float newPrePostRatio = (newLocation.x - xPlotLeft) / plotWidth;
						                                         if(newPrePostRatio < 0)
						                                        	 newPrePostRatio = 0;
						                                         if(newPrePostRatio > 1)
						                                        	 newPrePostRatio = 1;
						                                         triggerWidget.setPrePostRatio(Math.round(newPrePostRatio * 100));
						                                     },
						                                     dragEnded -> triggeringPaused = false,
						                                     this,
						                                     Theme.leftRigthCursor);
						OpenGL.drawTriangle2D(gl, Theme.plotOutlineColor, xTriggerPoint - markerThickness*1.5f, yPlotTop,
						                                                  xTriggerPoint + markerThickness*1.5f, yPlotTop,
						                                                  xTriggerPoint, yPlotTop - markerLength*1.5f);
					} else {
						OpenGL.drawTriangle2D(gl, Theme.plotOutlineColor, xTriggerPoint - markerThickness, yPlotTop,
						                                                  xTriggerPoint + markerThickness, yPlotTop,
						                                                  xTriggerPoint, yPlotTop - markerLength);
					}
				}
			}
			
			// draw lines to the trigger level and trigger point when the user is interacting with the markers
			if(mouseOver || triggeringPaused) {
				float xLeft = xPlotLeft;
				float xRight = xTriggerPoint > xPlotLeft ? xTriggerPoint : xPlotRight;
				float yTop = yPlotTop;
				float yBottom = yTriggerLevel;
				OpenGL.buffer.rewind();
				OpenGL.buffer.put(xLeft);   OpenGL.buffer.put(yBottom);  OpenGL.buffer.put(Theme.tickLinesColor);
				OpenGL.buffer.put(xRight);  OpenGL.buffer.put(yBottom);  OpenGL.buffer.put(Theme.tickLinesColor, 0, 3);  OpenGL.buffer.put(0.2f);
				OpenGL.buffer.put(xRight);  OpenGL.buffer.put(yTop);     OpenGL.buffer.put(Theme.tickLinesColor);
				OpenGL.buffer.put(xRight);  OpenGL.buffer.put(yBottom);  OpenGL.buffer.put(Theme.tickLinesColor, 0, 3);  OpenGL.buffer.put(0.2f);
				OpenGL.buffer.rewind();
				OpenGL.drawLinesXyrgba(gl, GL3.GL_LINES, OpenGL.buffer, 4);
			}
			
		}
		
		// draw the tooltip if the mouse is in the plot region and not over something clickable
		if(datasetsCount > 0 && SettingsController.getTooltipVisibility() && mouseX >= xPlotLeft && mouseX <= xPlotRight && mouseY >= yPlotBottom && mouseY <= yPlotTop && handler == null) {
			Plot.TooltipInfo tooltip = plot.getTooltip(mouseX - (int) xPlotLeft, plotWidth);
			if(tooltip.draw) {
				String[] tooltipLines = tooltip.label.split("\n");
				List<TooltipEntry> entries = new ArrayList<TooltipEntry>(datasetsCount + tooltipLines.length);
				for(String line : tooltipLines)
					entries.add(new TooltipEntry(null, line));
				for(int datasetN = 0; datasetN < datasetsCount; datasetN++) {
					Dataset dataset = allDatasets.get(datasetN);
					entries.add(new TooltipEntry(dataset.glColor, datasets.getSampleAsString(dataset, tooltip.sampleNumber)));
				}
				float anchorX = tooltip.pixelX + xPlotLeft;
				if(anchorX >= 0 && datasetsCount > 1) {
					OpenGL.buffer.rewind();
					OpenGL.buffer.put(anchorX); OpenGL.buffer.put(yPlotTop);
					OpenGL.buffer.put(anchorX); OpenGL.buffer.put(yPlotBottom);
					OpenGL.buffer.rewind();
					OpenGL.drawLinesXy(gl, GL3.GL_LINES, Theme.tooltipVerticalBarColor, OpenGL.buffer, 2);
					drawTooltip(gl, entries, anchorX, mouseY, xPlotLeft, yPlotTop, xPlotRight, yPlotBottom);
				} else if(anchorX >= 0) {
					float anchorY = (datasets.getSample(allDatasets.get(0), tooltip.sampleNumber) - plotMinY) / plotRange * plotHeight + yPlotBottom;
					anchorY = Math.max(anchorY, yPlotBottom);
					drawTooltip(gl, entries, anchorX, anchorY, xPlotLeft, yPlotTop, xPlotRight, yPlotBottom);
				}
			}
		}
		
		// draw the plot border
		OpenGL.drawQuadOutline2D(gl, Theme.plotOutlineColor, xPlotLeft, yPlotBottom, xPlotRight, yPlotTop);
		
		return handler;
		
	}
	
	@Override public void disposeGpu(GL2ES3 gl) {
		
		super.disposeGpu(gl);
		plot.freeResources(gl);
		
	}

}
