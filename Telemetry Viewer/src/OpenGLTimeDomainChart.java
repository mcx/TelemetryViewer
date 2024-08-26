import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.swing.JPanel;
import com.jogamp.opengl.GL2ES3;
import com.jogamp.opengl.GL3;

public class OpenGLTimeDomainChart extends PositionedChart {
	
	boolean isTimestampsMode;
	AutoScale autoscale;
	
	Plot plot;
	List<Field> allDatasets = new ArrayList<Field>(); // normal and bitfields
	
	// trigger
	boolean triggerEnabled = false;
	float earlierPlotMaxY = 1;
	float earlierPlotMinY = -1;
	
	public WidgetDatasetCheckboxes datasetsAndDurationWidget;
	private WidgetCheckbox legendVisibility;
	public WidgetCheckbox cacheEnabled;
	private WidgetCheckbox xAxisTicksVisibility;
	private WidgetCheckbox xAxisTitleVisibility;
	private WidgetTextfield<Float> yAxisMinimum;
	private WidgetCheckbox yAxisMinimumAutomatic;
	private WidgetTextfield<Float> yAxisMaximum;
	private WidgetCheckbox yAxisMaximumAutomatic;
	private WidgetCheckbox yAxisTicksVisibility;
	private WidgetCheckbox yAxisTitleVisibility;
	
	@Override public String toString() {
		
		return "Time Domain";
		
	}
	
	/**
	 * Updates the List of all datasets.
	 */
	private void updateAllDatasetsList() {
		
		allDatasets = new ArrayList<Field>(datasets.normalDatasets);
		
		datasets.edgeStates.forEach(state -> {
			if(!allDatasets.contains(state.dataset))
				allDatasets.add(state.dataset);
		});
		datasets.levelStates.forEach(state -> {
			if(!allDatasets.contains(state.dataset))
				allDatasets.add(state.dataset);
		});
		
	}
	
	public OpenGLTimeDomainChart() {
		
		autoscale = new AutoScale(AutoScale.MODE_EXPONENTIAL, 30, 0.10f);
		
		// create the control widgets and event handlers
		legendVisibility = new WidgetCheckbox("Show Legend", true);
		
		cacheEnabled = new WidgetCheckbox("Cached Mode", false)
		                   .onChange(isCached -> {
		                                autoscale = isCached ? new AutoScale(AutoScale.MODE_STICKY,       1, 0.10f) :
		                                                       new AutoScale(AutoScale.MODE_EXPONENTIAL, 30, 0.10f);
		                            });
		
		xAxisTicksVisibility = new WidgetCheckbox("Show Ticks", true)
		                           .setExportLabel("x-axis show ticks");
		
		xAxisTitleVisibility = new WidgetCheckbox("Show Title", true)
		                           .setExportLabel("x-axis show title");
		
		yAxisMinimum = WidgetTextfield.ofFloat(-Float.MAX_VALUE, Float.MAX_VALUE, -1)
		                              .setPrefix("Minimum")
		                              .setExportLabel("y-axis minimum")
		                              .onChange((newMinimum, oldMinimum) -> {
		                                            if(newMinimum > yAxisMaximum.get())
		                                                yAxisMaximum.set(newMinimum);
		                                            return true;
		                                        });
		
		yAxisMinimumAutomatic = new WidgetCheckbox("Automatic", true)
		                            .setExportLabel("y-axis minimum automatic")
		                            .onChange(isAutomatic -> {
		                                         if(isAutomatic)
		                                             yAxisMinimum.disableWithMessage("Automatic");
		                                         else
		                                             yAxisMinimum.setEnabled(true);
		                                     });
		
		yAxisMaximum = WidgetTextfield.ofFloat(-Float.MAX_VALUE, Float.MAX_VALUE, 1)
		                              .setPrefix("Maximum")
		                              .setExportLabel("y-axis maximum")
		                              .onChange((newMaximum, oldMaximum) -> {
		                                            if(newMaximum < yAxisMinimum.get())
		                                                yAxisMinimum.set(newMaximum);
		                                            return true;
		                                        });
		
		yAxisMaximumAutomatic = new WidgetCheckbox("Automatic", true)
		                            .setExportLabel("y-axis maximum automatic")
		                            .onChange(isAutomatic -> {
		                                         if(isAutomatic)
		                                             yAxisMaximum.disableWithMessage("Automatic");
		                                         else
		                                             yAxisMaximum.setEnabled(true);
		                                     });
		
		yAxisTicksVisibility = new WidgetCheckbox("Show Ticks", true)
		                           .setExportLabel("y-axis show ticks");
		
		yAxisTitleVisibility = new WidgetCheckbox("Show Title", true)
		                           .setExportLabel("y-axis show title");
		
		datasetsAndDurationWidget = new WidgetDatasetCheckboxes(newDatasets -> {
		                                                            datasets.setNormals(newDatasets);
		                                                            updateAllDatasetsList();
		                                                            if(datasets.normalsCount() == 1) {
		                                                                yAxisMinimum.setSuffix(datasets.getNormal(0).unit.get());
		                                                                yAxisMaximum.setSuffix(datasets.getNormal(0).unit.get());
		                                                                trigger.setDefaultChannel(datasets.getNormal(0));
		                                                            } else if(datasets.normalsCount() == 0) {
		                                                                yAxisMinimum.setSuffix("");
		                                                                yAxisMaximum.setSuffix("");
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
		                                                            if(trigger != null)
		                                                                trigger.resetTrigger();
		                                                            return newDuration;
		                                                        },
		                                                        true);
		
		trigger = new WidgetTrigger(this,
		                            isEnabled -> triggerEnabled = isEnabled);
		
		widgets.add(datasetsAndDurationWidget);
		widgets.add(legendVisibility);
		widgets.add(cacheEnabled);
		widgets.add(xAxisTicksVisibility);
		widgets.add(xAxisTitleVisibility);
		widgets.add(yAxisMinimum);
		widgets.add(yAxisMinimumAutomatic);
		widgets.add(yAxisMaximum);
		widgets.add(yAxisMaximumAutomatic);
		widgets.add(yAxisTicksVisibility);
		widgets.add(yAxisTitleVisibility);
		widgets.add(trigger);
		
	}
	
	@Override public void getConfigurationGui(JPanel gui) {
		
		gui.add(Theme.newWidgetsPanel("Data")
		             .with(datasetsAndDurationWidget)
		             .with(legendVisibility, "split 2")
		             .with(cacheEnabled)
		             .getPanel());
		
		gui.add(Theme.newWidgetsPanel("X-Axis")
		             .with(xAxisTicksVisibility, "split 2")
		             .with(xAxisTitleVisibility)
		             .getPanel());
		
		gui.add(Theme.newWidgetsPanel("Y-Axis")
		             .with(yAxisMinimum, "split 2, grow")
		             .with(yAxisMinimumAutomatic, "sizegroup 1")
		             .with(yAxisMaximum, "split 2, grow")
		             .with(yAxisMaximumAutomatic, "sizegroup 1")
		             .withGap(Theme.padding)
		             .with(yAxisTicksVisibility, "split 2")
		             .with(yAxisTitleVisibility)
		             .getPanel());
		
		boolean triggerDisabled = OpenGLChartsView.globalTrigger != null && OpenGLChartsView.globalTrigger != trigger;
		gui.add(Theme.newWidgetsPanel(triggerDisabled ? "Trigger [Disabled due to global trigger]" : "Trigger")
		             .with(trigger)
		             .getPanel());
		
	}
	
	@Override public EventHandler drawChart(GL2ES3 gl, float[] chartMatrix, int width, int height, long endTimestamp, int endSampleNumber, double zoomLevel, int mouseX, int mouseY) {
		
		EventHandler handler = null;
		
		// check for a trigger
		WidgetTrigger.Result point = trigger.checkForTrigger(endSampleNumber, endTimestamp, zoomLevel);
		endSampleNumber = point.chartEndSampleNumber();
		endTimestamp    = point.chartEndTimestamp();
		
		int datasetsCount = allDatasets.size();
		
		plot.initialize(sampleCountMode ? endSampleNumber : endTimestamp, datasets, Math.round(duration * zoomLevel), cacheEnabled.get(), isTimestampsMode);
		
		// calculate the plot range
		StorageFloats.MinMax requiredRange = plot.getRange();
		autoscale.update(requiredRange.min, requiredRange.max);
		float plotMinY = yAxisMinimumAutomatic.get() ? autoscale.getMin() : yAxisMinimum.get();
		float plotMaxY = yAxisMaximumAutomatic.get() ? autoscale.getMax() : yAxisMaximum.get();
		if(triggerEnabled) {
			if(trigger.isPaused()) {
				plotMaxY = earlierPlotMaxY;
				plotMinY = earlierPlotMinY;
			} else {
				earlierPlotMaxY = plotMaxY;
				earlierPlotMinY = plotMinY;
			}
		}
		float plotRange = plotMaxY - plotMinY;
		
		// calculate x and y positions of everything
		float xPlotLeft = Theme.tilePadding;
		float xPlotRight = width - Theme.tilePadding;
		float plotWidth = xPlotRight - xPlotLeft;
		float yPlotTop = height - Theme.tilePadding;
		float yPlotBottom = Theme.tilePadding;
		float plotHeight = yPlotTop - yPlotBottom;
		
		float yXaxisTitleTextBasline = Theme.tilePadding;
		float yXaxisTitleTextTop = yXaxisTitleTextBasline + OpenGL.largeTextHeight;
		String xAxisTitle = plot.getTitle();
		float xXaxisTitleTextLeft = xPlotLeft + (plotWidth  / 2.0f) - (OpenGL.largeTextWidth(gl, xAxisTitle)  / 2.0f);
		if(xAxisTitleVisibility.get()) {
			float temp = yXaxisTitleTextTop + Theme.tickTextPadding;
			if(yPlotBottom < temp) {
				yPlotBottom = temp;
				plotHeight = yPlotTop - yPlotBottom;
			}
		}
		
		float xLegendBorderLeft = Theme.tilePadding;
		float yLegendBorderBottom = Theme.tilePadding;
		float yLegendTextBaseline = yLegendBorderBottom + Theme.legendTextPadding;
		float yLegendTextTop = yLegendTextBaseline + OpenGL.mediumTextHeight;
		float yLegendBorderTop = yLegendTextTop + Theme.legendTextPadding;
		float[][] legendMouseoverCoordinates = new float[datasetsCount][4];
		float[][] legendBoxCoordinates = new float[datasetsCount][4];
		float[] xLegendNameLeft = new float[datasetsCount];
		float xLegendBorderRight = 0;
		if(legendVisibility.get() && datasetsCount > 0) {
			
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
				xOffset += OpenGL.mediumTextWidth(gl, allDatasets.get(i).name.get()) + Theme.legendNamesPadding;
				
				legendMouseoverCoordinates[i][2] = xOffset - Theme.legendNamesPadding + Theme.legendTextPadding;
				legendMouseoverCoordinates[i][3] = yLegendBorderTop;
			}
			
			xLegendBorderRight = xOffset - Theme.legendNamesPadding + Theme.legendTextPadding + (Theme.lineWidth / 2);
			if(xAxisTitleVisibility.get())
				xXaxisTitleTextLeft = xLegendBorderRight + ((xPlotRight - xLegendBorderRight) / 2) - (OpenGL.largeTextWidth(gl, xAxisTitle)  / 2.0f);
			
			float temp = yLegendBorderTop + Theme.legendTextPadding;
			if(yPlotBottom < temp) {
				yPlotBottom = temp;
				plotHeight = yPlotTop - yPlotBottom;
			}
		}
	
		float yXaxisTickTextBaseline = yPlotBottom;
		float yXaxisTickTextTop = yXaxisTickTextBaseline + OpenGL.smallTextHeight;
		if(isTimestampsMode && SettingsView.isTimeFormatTwoLines())
			yXaxisTickTextTop += 1.3 * OpenGL.smallTextHeight;
		float yXaxisTickBottom = yXaxisTickTextTop + Theme.tickTextPadding;
		float yXaxisTickTop = yXaxisTickBottom + Theme.tickLength;
		if(xAxisTicksVisibility.get()) {
			yPlotBottom = yXaxisTickTop;
			plotHeight = yPlotTop - yPlotBottom;
		}
	
		float xYaxisTitleTextTop = xPlotLeft;
		float xYaxisTitleTextBaseline = xYaxisTitleTextTop + OpenGL.largeTextHeight;
		String yAxisTitle = (datasetsCount > 0) ? allDatasets.get(0).unit.get() : "";
		float yYaxisTitleTextLeft = yPlotBottom + (plotHeight / 2.0f) - (OpenGL.largeTextWidth(gl, yAxisTitle) / 2.0f);
		if(yAxisTitleVisibility.get()) {
			xPlotLeft = xYaxisTitleTextBaseline + Theme.tickTextPadding;
			plotWidth = xPlotRight - xPlotLeft;
			if(xAxisTitleVisibility.get() && !legendVisibility.get())
				xXaxisTitleTextLeft = xPlotLeft + (plotWidth  / 2.0f) - (OpenGL.largeTextWidth(gl, xAxisTitle)  / 2.0f);
		}
		
		Map<Float, String> yDivisions = null;
		float xYaxisTickTextRight = 0;
		float xYaxisTickLeft = 0;
		float xYaxisTickRight = 0;
		if(yAxisTicksVisibility.get()) {
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
			
			if(xAxisTitleVisibility.get() && !legendVisibility.get())
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
		if(xAxisTicksVisibility.get()) {
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
		if(yAxisTicksVisibility.get()) {
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
		if(legendVisibility.get() && datasetsCount > 0 && xLegendBorderRight < width - Theme.tilePadding) {
			OpenGL.drawQuad2D(gl, Theme.legendBackgroundColor, xLegendBorderLeft, yLegendBorderBottom, xLegendBorderRight, yLegendBorderTop);
			
			for(int i = 0; i < datasetsCount; i++) {
				if(mouseX >= legendMouseoverCoordinates[i][0] && mouseX <= legendMouseoverCoordinates[i][2] && mouseY >= legendMouseoverCoordinates[i][1] && mouseY <= legendMouseoverCoordinates[i][3]) {
					OpenGL.drawQuadOutline2D(gl, Theme.tickLinesColor, legendMouseoverCoordinates[i][0], legendMouseoverCoordinates[i][1], legendMouseoverCoordinates[i][2], legendMouseoverCoordinates[i][3]);
					Field d = allDatasets.get(i);
					handler = EventHandler.onPress(event -> ConfigureView.instance.forDataset(d));
				}
				OpenGL.drawQuad2D(gl, allDatasets.get(i).color.getGl(), legendBoxCoordinates[i][0], legendBoxCoordinates[i][1], legendBoxCoordinates[i][2], legendBoxCoordinates[i][3]);
				OpenGL.drawMediumText(gl, allDatasets.get(i).name.get(), (int) xLegendNameLeft[i], (int) yLegendTextBaseline, 0);
			}
		}
		
		// draw the x-axis title, if space is available
		if(xAxisTitleVisibility.get())
			if((!legendVisibility.get() && xXaxisTitleTextLeft > xPlotLeft) || (legendVisibility.get() && xXaxisTitleTextLeft > xLegendBorderRight + Theme.legendTextPadding))
				OpenGL.drawLargeText(gl, xAxisTitle, (int) xXaxisTitleTextLeft, (int) yXaxisTitleTextBasline, 0);
		
		// draw the y-axis title, if space is available
		if(yAxisTitleVisibility.get() && yYaxisTitleTextLeft > yPlotBottom)
			OpenGL.drawLargeText(gl, yAxisTitle, (int) xYaxisTitleTextBaseline, (int) yYaxisTitleTextLeft, 90);
		
		// acquire the samples
		plot.acquireSamples(plotMinY, plotMaxY, (int) plotWidth, (int) plotHeight);
		
		// clip to the plot region
		int[] chartScissorArgs = new int[4];
		gl.glGetIntegerv(GL3.GL_SCISSOR_BOX, chartScissorArgs, 0);
		gl.glScissor(chartScissorArgs[0] + (int) xPlotLeft, chartScissorArgs[1] + (int) yPlotBottom, (int) plotWidth, (int) plotHeight);
		
		// update the matrix and mouse so the plot region starts at (0,0)
		// x = x + xPlotLeft;
		// y = y + yPlotBottom;
		float[] plotMatrix = Arrays.copyOf(chartMatrix, 16);
		OpenGL.translateMatrix(plotMatrix, xPlotLeft, yPlotBottom, 0);
		OpenGL.useMatrix(gl, plotMatrix);
		mouseX -= xPlotLeft;
		mouseY -= yPlotBottom;
		
		// draw the plot
		plot.draw(gl, mouseX, mouseY, plotMatrix, (int) plotWidth, (int) plotHeight, plotMinY, plotMaxY);
		
		// draw the trigger level and trigger point markers
		if(triggerEnabled) {
			
			float scalar = ChartsController.getDisplayScalingFactor();
			float markerThickness = 3*scalar;
			float markerLength = 5*scalar;
			float triggerLevel = trigger.level.get();
			float yTriggerLevel = (triggerLevel - plotMinY) / plotRange * plotHeight;
			
			int triggeredSampleNumber = point.triggeredSampleNumber();
			float xTriggerPoint = triggeredSampleNumber >= 0 ? plot.getPixelXforSampleNumber(triggeredSampleNumber, plotWidth) : 0;
			
			boolean mouseOver = false;
			
			// trigger level marker
			float plotWidthCopy = plotWidth;
			float plotHeightCopy = plotHeight;
			float plotMinYcopy = plotMinY;
			float plotMaxYcopy = plotMaxY;
			int   xPlotLeftCopy = (int) xPlotLeft;
			int   yPlotBottomCopy = (int) yPlotBottom;
			if(yTriggerLevel >= 0 && yTriggerLevel <= plotHeight) {
				if(mouseX >= 0 && mouseX <= markerLength*1.5 && mouseY >= yTriggerLevel - markerThickness*1.5 && mouseY <= yTriggerLevel + markerThickness*1.5) {
					mouseOver = true;
					handler = EventHandler.onPressOrDrag(dragStarted -> trigger.setPaused(true),
					                                     newLocation -> {
					                                         newLocation.x -= xPlotLeftCopy;
					                                         newLocation.y -= yPlotBottomCopy;
					                                         float newTriggerLevel = newLocation.y / plotHeightCopy * plotRange + plotMinYcopy;
					                                         if(newTriggerLevel < plotMinYcopy)
					                                             newTriggerLevel = plotMinYcopy;
					                                         if(newTriggerLevel > plotMaxYcopy)
					                                             newTriggerLevel = plotMaxYcopy;
					                                         trigger.level.set(newTriggerLevel);
					                                     },
					                                     dragEnded -> trigger.setPaused(false),
					                                     this,
					                                     Theme.upDownCursor);
					OpenGL.drawTriangle2D(gl, Theme.plotOutlineColor, 0, yTriggerLevel + markerThickness*1.5f,
					                                                  markerLength*1.5f, yTriggerLevel,
					                                                  0, yTriggerLevel - markerThickness*1.5f);
				} else {
					OpenGL.drawTriangle2D(gl, Theme.plotOutlineColor, 0, yTriggerLevel + markerThickness,
					                                                  markerLength, yTriggerLevel,
					                                                  0, yTriggerLevel - markerThickness);
				}
			}
			
			// trigger point marker
			if(triggeredSampleNumber >= 0) {
				if(xTriggerPoint >= 0 && xTriggerPoint <= plotWidth) {
					if(mouseX >= xTriggerPoint - 1.5*markerThickness && mouseX <= xTriggerPoint + 1.5*markerThickness && mouseY >= plotHeight - 1.5*markerLength && mouseY <= plotHeight) {
						mouseOver = true;
						handler = EventHandler.onPressOrDrag(dragStarted -> trigger.setPaused(true),
						                                     newLocation -> {
						                                         newLocation.x -= xPlotLeftCopy;
						                                         newLocation.y -= yPlotBottomCopy;
						                                         float newPrePostRatio = newLocation.x / plotWidthCopy;
						                                         if(newPrePostRatio < 0)
						                                             newPrePostRatio = 0;
						                                         if(newPrePostRatio > 1)
						                                             newPrePostRatio = 1;
						                                         trigger.prePostRatio.set(Math.round(newPrePostRatio * 100));
						                                     },
						                                     dragEnded -> trigger.setPaused(false),
						                                     this,
						                                     Theme.leftRigthCursor);
						OpenGL.drawTriangle2D(gl, Theme.plotOutlineColor, xTriggerPoint - markerThickness*1.5f, plotHeight,
						                                                  xTriggerPoint + markerThickness*1.5f, plotHeight,
						                                                  xTriggerPoint, plotHeight - markerLength*1.5f);
					} else {
						OpenGL.drawTriangle2D(gl, Theme.plotOutlineColor, xTriggerPoint - markerThickness, plotHeight,
						                                                  xTriggerPoint + markerThickness, plotHeight,
						                                                  xTriggerPoint, plotHeight - markerLength);
					}
				}
			}
			
			// draw lines to the trigger level and trigger point when the user is interacting with the markers
			if(mouseOver || trigger.isPaused()) {
				float xLeft = 0;
				float xRight = xTriggerPoint;
				float yTop = plotHeight;
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
		if(datasetsCount > 0 && SettingsView.instance.tooltipsVisibility.get() && mouseX >= 0 && mouseX <= plotWidth && mouseY >= 0 && mouseY <= plotHeight && handler == null)
			plot.drawTooltip(gl, mouseX, mouseY, plotWidth, plotHeight, plotMinY, plotMaxY);
		
		// stop clipping to the plot region
		gl.glScissor(chartScissorArgs[0], chartScissorArgs[1], chartScissorArgs[2], chartScissorArgs[3]);
		
		// switch back to the chart matrix
		OpenGL.useMatrix(gl, chartMatrix);
		
		// draw the plot border
		OpenGL.drawQuadOutline2D(gl, Theme.plotOutlineColor, xPlotLeft, yPlotBottom, xPlotRight, yPlotTop);
		
		return handler;
		
	}
	
	@Override public void disposeGpu(GL2ES3 gl) {
		super.disposeGpu(gl);
		plot.freeResources(gl);
	}

}
