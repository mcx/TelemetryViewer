import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.swing.JPanel;
import org.jtransforms.fft.DoubleFFT_1D;

import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL2ES3;
import com.jogamp.opengl.GL3;

public class OpenGLFrequencyDomainChart extends PositionedChart {
	
	private Cache cache = new Cache();
	private record FFTs(boolean exists,   // true if there are enough samples to calculate at least one FFT
	                    double binSizeHz, // bin size (in Hertz) is the reciprocal of the window size (in seconds.) example: 500ms window -> 1/0.5 = 2 Hz bin size
	                    int binCount,     // bin count is the sample count, divided by 2, plus 1
	                    float minHz,      // always 0
	                    float maxHz,      // Nyquist
	                    float minPower,   // actual minimum, not necessarily the same minimum used when drawing on screen
	                    float maxPower,   // actual maximum, not necessarily the same maximum used when drawing on screen
	                    int windowLength, // samples per FFT
	                    List<List<float[]>> windows) {} // .get(windowN).get(datasetN)[binN]
	
	// y-axis scale
	AutoScale autoscalePower;
	
	// textures
	private int[] histogramTexHandle;
	private int[] waterfallTexHandle;
	
	// user settings
	private DatasetsInterface.WidgetDatasets datasetsWidget;
	private WidgetTextfield<Integer> sampleCountTextfield;
	private WidgetCheckbox legendVisibility;
	
	public enum ChartStyle {
		SINGLE    { @Override public String toString() { return "Single";    } },
		HISTOGRAM { @Override public String toString() { return "Histogram"; } },
		WATERFALL { @Override public String toString() { return "Waterfall"; } };
	};
	private WidgetToggleButton<ChartStyle> chartStyle;
	private WidgetTextfield<Integer> fftCount;
	
	private int xAxisBins = 128;
	private WidgetTextfield<Integer> xAxisBinsTextfield;
	private int fftBinsPerPlotBin = 1;
	
	private WidgetCheckbox xAxisBinsAutomatic;
	
	private int yAxisBins = 128;
	private WidgetTextfield<Integer> yAxisBinsTextfield;
	
	private WidgetCheckbox yAxisBinsAutomatic;
	private WidgetSlider gamma;
	private WidgetTextfield<Float> minimumPower;
	private WidgetCheckbox minimumPowerAutomatic;
	private WidgetTextfield<Float> maximumPower;
	private WidgetCheckbox maximumPowerAutomatic;
	private WidgetCheckbox fftInfoVisibility;
	private WidgetCheckbox xAxisTicksVisibility;
	private WidgetCheckbox xAxisTitleVisibility;
	private WidgetCheckbox yAxisTicksVisibility;
	private WidgetCheckbox yAxisTitleVisibility;
	
	@Override public String toString() {
		
		return "Frequency Domain";
		
	}
	
	public OpenGLFrequencyDomainChart() {
		
		autoscalePower = new AutoScale(AutoScale.MODE_EXPONENTIAL, 90, 0.20f);
		
		// create the control widgets and event handlers
		datasetsWidget = datasets.getCheckboxesWidget(newDatasets -> {});
		
		sampleCountTextfield = WidgetTextfield.ofInt(10, Integer.MAX_VALUE / 16, ConnectionsController.getDefaultChartDuration())
		                                      .setSuffix("Samples")
		                                      .setExportLabel("sample count")
		                                      .onChange((newDuration, oldDuration) -> {
		                                          duration = newDuration;
		                                          return true;
		                                      });
		
		legendVisibility = new WidgetCheckbox("Show Legend", true);
		
		fftCount = WidgetTextfield.ofInt(2, 100, 20)
		                          .setSuffix("FFTs")
		                          .setExportLabel("histogram/waterfall fft count");
		
		xAxisBinsTextfield = WidgetTextfield.ofInt(2, 4096, xAxisBins, 0, "Automatic")
		                                    .setPrefix("X-Axis Bins")
		                                    .setExportLabel("histogram/waterfall x-axis bin count")
		                                    .onChange((newCount, oldCount) -> {
		                                                 if(newCount == 0)
		                                                     xAxisBinsAutomatic.set(true);
		                                                 else
		                                                     xAxisBins = newCount;
		                                                 return true;
		                                             });
		
		xAxisBinsAutomatic = new WidgetCheckbox("Auto", false)
		                         .setExportLabel("histogram/waterfall x-axis bin count automatic")
		                         .onChange(isAutomatic -> {
		                                      if(isAutomatic) {
		                                          xAxisBinsTextfield.disableWithMessage("Automatic");
		                                      } else {
		                                          xAxisBinsTextfield.set(xAxisBins);
		                                          xAxisBinsTextfield.setEnabled(true);
		                                      }
		                                  });
		
		yAxisBinsTextfield = WidgetTextfield.ofInt(2, 4096, yAxisBins, 0, "Automatic")
		                                    .setPrefix("Y-Axis Bins")
		                                    .setExportLabel("histogram y-axis bin count")
		                                    .onChange((newCount, oldCount) -> {
		                                                 if(newCount == 0)
		                                                     yAxisBinsAutomatic.set(true);
		                                                 else
		                                                     yAxisBins = newCount;
		                                                 return true;
		                                             });
		
		yAxisBinsAutomatic = new WidgetCheckbox("Auto", false)
		                         .setExportLabel("histogram y-axis bin count automatic")
		                         .onChange(isAutomatic -> {
		                                      if(isAutomatic) {
		                                          yAxisBinsTextfield.disableWithMessage("Automatic");
		                                      } else {
		                                          yAxisBinsTextfield.set(yAxisBins);
		                                          yAxisBinsTextfield.setEnabled(true);
		                                      }
		                                  });
		
		gamma = new WidgetSlider("Gamma", 0, 10, 5)
		            .setExportLabel("histogram/waterfall fft gamma")
		            .setDividedByTen();
		
		chartStyle = new WidgetToggleButton<ChartStyle>("Style", ChartStyle.values(), ChartStyle.SINGLE)
		             .setExportLabel("fft style")
		             .onChange((newStyle, oldStyle) -> {
		                  fftCount.setVisible(newStyle != ChartStyle.SINGLE);
		                  xAxisBinsTextfield.setVisible(newStyle != ChartStyle.SINGLE);
		                  xAxisBinsAutomatic.setVisible(newStyle != ChartStyle.SINGLE);
		                  yAxisBinsTextfield.setVisible(newStyle == ChartStyle.HISTOGRAM);
		                  yAxisBinsAutomatic.setVisible(newStyle == ChartStyle.HISTOGRAM);
		                  gamma.setVisible(newStyle == ChartStyle.HISTOGRAM);
		                  return true;
		              });
		
		maximumPower = WidgetTextfield.ofFloat(Float.MIN_VALUE, Float.MAX_VALUE, 1e9f)
		                              .setPrefix("Max Power")
		                              .setSuffix("Watts")
		                              .setExportLabel("fft maximum power")
		                              .onChange((newMaximum, oldMaximum) -> {
		                                            if(newMaximum < minimumPower.get())
		                                                minimumPower.set(newMaximum);
		                                            return true;
		                                        });
		
		maximumPowerAutomatic = new WidgetCheckbox("Auto", true)
		                            .setExportLabel("fft maximum power automatic")
		                            .onChange(isAutomatic -> {
		                                         if(isAutomatic)
		                                             maximumPower.disableWithMessage("Automatic");
		                                         else
		                                             maximumPower.setEnabled(true);
		                                     });
		
		minimumPower = WidgetTextfield.ofFloat(Float.MIN_VALUE, Float.MAX_VALUE, 1e-9f)
		                              .setPrefix("Min Power")
		                              .setSuffix("Watts")
		                              .setExportLabel("fft minimum power")
		                              .onChange((newMinimum, oldMinimum) -> {
		                                            if(newMinimum > maximumPower.get())
		                                                maximumPower.set(newMinimum);
		                                            return true;
		                                        });
		
		minimumPowerAutomatic = new WidgetCheckbox("Auto", true)
		                            .setExportLabel("fft minimum power automatic")
		                            .onChange(isAutomatic -> {
		                                         if(isAutomatic)
		                                             minimumPower.disableWithMessage("Automatic");
		                                         else
		                                             minimumPower.setEnabled(true);
		                                     });
		
		fftInfoVisibility = new WidgetCheckbox("Show FFT Info", true)
		                        .setExportLabel("fft show info");
		
		xAxisTicksVisibility = new WidgetCheckbox("Show Ticks", true)
		                           .setExportLabel("x-axis show ticks");
		
		xAxisTitleVisibility = new WidgetCheckbox("Show Title", true)
		                           .setExportLabel("x-axis show title");
		
		yAxisTicksVisibility = new WidgetCheckbox("Show Ticks", true)
		                           .setExportLabel("y-axis show ticks");
		
		yAxisTitleVisibility = new WidgetCheckbox("Show Title", true)
		                           .setExportLabel("y-axis show title");
		
		widgets.add(datasetsWidget);
		widgets.add(sampleCountTextfield);
		widgets.add(legendVisibility);
		widgets.add(chartStyle);
		widgets.add(maximumPower);
		widgets.add(maximumPowerAutomatic);
		widgets.add(minimumPower);
		widgets.add(minimumPowerAutomatic);
		widgets.add(fftCount);
		widgets.add(gamma);
		widgets.add(xAxisBinsTextfield);
		widgets.add(xAxisBinsAutomatic);
		widgets.add(yAxisBinsTextfield);
		widgets.add(yAxisBinsAutomatic);
		widgets.add(fftInfoVisibility);
		widgets.add(xAxisTicksVisibility);
		widgets.add(xAxisTitleVisibility);
		widgets.add(yAxisTicksVisibility);
		widgets.add(yAxisTitleVisibility);
		
	}
	
	@Override public void getConfigurationGui(JPanel gui) {
		
		gui.add(Theme.newWidgetsPanel("Data")
		             .with(datasetsWidget)
		             .with(sampleCountTextfield,  "split 2, grow x, grow y, sizegroup 0")
		             .with(legendVisibility,      "grow x, grow y, sizegroup 0")
		             .getPanel());
		
		gui.add(Theme.newWidgetsPanel("FFTs")
		             .with(chartStyle)
		             .with(maximumPower,          "split 2, grow x, grow y")
		             .with(maximumPowerAutomatic, "sizegroup 1")
		             .with(minimumPower,          "split 2, grow x, grow y")
		             .with(minimumPowerAutomatic, "sizegroup 1")
		             .withGap(Theme.padding)
		             .with(fftCount,              "grow x, grow y")
		             .with(xAxisBinsTextfield,    "split 2, grow x, grow y")
		             .with(xAxisBinsAutomatic,    "sizegroup 1")
		             .with(yAxisBinsTextfield,    "split 2, grow x, grow y")
		             .with(yAxisBinsAutomatic,    "sizegroup 1")
		             .with(gamma)
		             .withGap(Theme.padding)
		             .with(fftInfoVisibility)
		             .getPanel());
		
		gui.add(Theme.newWidgetsPanel("X-Axis")
		             .with(xAxisTicksVisibility,  "split 2")
		             .with(xAxisTitleVisibility)
		             .getPanel());
		
		gui.add(Theme.newWidgetsPanel("Y-Axis")
		             .with(yAxisTicksVisibility,  "split 2")
		             .with(yAxisTitleVisibility)
		             .getPanel());
		
	}
	
	@Override public EventHandler drawChart(GL2ES3 gl, float[] chartMatrix, int width, int height, long endTimestamp, int endSampleNumber, double zoomLevel, int mouseX, int mouseY) {
		
		EventHandler handler = null;
		
		boolean haveDatasets = datasets.hasNormals();
		int datasetsCount = datasets.normalsCount();
		
		// calculate the FFTs
		FFTs fft = cache.getFFT(endSampleNumber, duration, fftCount.get(), datasets, chartStyle.get());
		
		// calculate the domain
		float plotMinX = fft.minHz;
		float plotMaxX = fft.maxHz;
		float domain = plotMaxX - plotMinX;
		
		// calculate the range and ensure it's >0
		// the y-axis is power (single/histogram modes) or time (waterfall mode)
		float sampleRate = haveDatasets ? datasets.connection.getSampleRate() : 1;
		float plotMinTime = 0;
		float plotMaxTime = (float) (duration * fftCount.get()) / sampleRate;

		float plotMinPower = fft.minPower;
		float plotMaxPower = fft.maxPower;
		if(plotMinPower == plotMaxPower) {
			float value = plotMinPower;
			plotMinPower = value - 0.001f;
			plotMaxPower = value + 0.001f;
		}
		autoscalePower.update(plotMinPower, plotMaxPower);
		
		if(!minimumPowerAutomatic.get())
			plotMinPower = (float) Math.log10(minimumPower.get());
		else if(minimumPowerAutomatic.get() && !chartStyle.is(ChartStyle.WATERFALL))
			plotMinPower = autoscalePower.getMin();
		
		if(!maximumPowerAutomatic.get())
			plotMaxPower = (float) Math.log10(maximumPower.get());
		else if(maximumPowerAutomatic.get() && !chartStyle.is(ChartStyle.WATERFALL))
			plotMaxPower = autoscalePower.getMax();

		float plotMinY = chartStyle.is(ChartStyle.WATERFALL) ? plotMinTime : plotMinPower;
		float plotMaxY = chartStyle.is(ChartStyle.WATERFALL) ? plotMaxTime : plotMaxPower;
		float plotRange = plotMaxY - plotMinY;
		
		// calculate x and y positions of everything
		float xPlotLeft = Theme.tilePadding;
		float xPlotRight = width - Theme.tilePadding;
		float plotWidth = xPlotRight - xPlotLeft;
		float yPlotTop = height - Theme.tilePadding;
		float yPlotBottom = Theme.tilePadding;
		float plotHeight = yPlotTop - yPlotBottom;
		
		float xLegendBorderLeft = Theme.tilePadding;
		float yLegendBorderBottom = Theme.tilePadding;
		float yLegendTextBaseline = yLegendBorderBottom + Theme.legendTextPadding;
		float yLegendTextTop = yLegendTextBaseline + OpenGL.mediumTextHeight;
		float yLegendBorderTop = yLegendTextTop + Theme.legendTextPadding;
		float[][] legendMouseoverCoordinates = new float[datasetsCount][4];
		float[][] legendBoxCoordinates = new float[datasetsCount][4];
		float[] xLegendNameLeft = new float[datasetsCount];
		float xLegendBorderRight = 0;
		if(legendVisibility.get() && haveDatasets) {
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
				xOffset += OpenGL.mediumTextWidth(gl, datasets.getNormal(i).name.get()) + Theme.legendNamesPadding;
				
				legendMouseoverCoordinates[i][2] = xOffset - Theme.legendNamesPadding + Theme.legendTextPadding;
				legendMouseoverCoordinates[i][3] = yLegendBorderTop;
			}
			
			xLegendBorderRight = xOffset - Theme.legendNamesPadding + Theme.legendTextPadding + (Theme.lineWidth / 2);

			float temp = yLegendBorderTop + Theme.legendTextPadding;
			if(yPlotBottom < temp) {
				yPlotBottom = temp;
				plotHeight = yPlotTop - yPlotBottom;
			}
		}
		
		String fftWindowLengthText = null;
		float yFftWindowLengthTextBaseline = 0;
		float xFftWindowLenghtTextLeft = 0;
		String fftWindowCountText = null;
		float yFftWindowCountTextBaseline = 0;
		float xFftWindowCountTextLeft = 0;
		String minPowerText = null;
		String maxPowerText = null;
		float yPowerTextBaseline = 0;
		float yPowerTextTop = 0;
		float xMaxPowerTextLeft = 0;
		float xPowerScaleRight = 0;
		float xPowerScaleLeft = 0;
		float xMinPowerTextLeft = 0;
		float xFftInfoTextLeft = 0;
		if(fftInfoVisibility.get()) {
			switch(chartStyle.get()) {
				case SINGLE -> {
					fftWindowLengthText = fft.windowLength + " sample rectangular window";
					yFftWindowLengthTextBaseline = Theme.tilePadding;
					xFftWindowLenghtTextLeft = width - Theme.tilePadding - OpenGL.smallTextWidth(gl, fftWindowLengthText);
					
					xFftInfoTextLeft = xFftWindowLenghtTextLeft;
					
					float temp = yFftWindowLengthTextBaseline + OpenGL.smallTextHeight + Theme.tickTextPadding;
					if(yPlotBottom < temp) {
						yPlotBottom = temp;
						plotHeight = yPlotTop - yPlotBottom;
					}
				}
				case HISTOGRAM -> {
					int windowCount = fft.windows.size();
					int windowLength = fft.windowLength;
					int trueTotalSampleCount = windowCount * windowLength;
					fftWindowCountText = windowCount + " windows (total of " + trueTotalSampleCount + " samples)";
					yFftWindowCountTextBaseline = Theme.tilePadding;
					xFftWindowCountTextLeft = width - Theme.tilePadding - OpenGL.smallTextWidth(gl, fftWindowCountText);
					
					fftWindowLengthText = windowLength + " sample rectangular window";
					yFftWindowLengthTextBaseline = yFftWindowCountTextBaseline + OpenGL.smallTextHeight + Theme.tickTextPadding;
					xFftWindowLenghtTextLeft = width - Theme.tilePadding - OpenGL.smallTextWidth(gl, fftWindowLengthText);
					
					xFftInfoTextLeft = Float.min(xFftWindowCountTextLeft, xFftWindowLenghtTextLeft);
					
					float temp = yFftWindowLengthTextBaseline + OpenGL.smallTextHeight + Theme.tickTextPadding;
					if(yPlotBottom < temp) {
						yPlotBottom = temp;
						plotHeight = yPlotTop - yPlotBottom;
					}
				}
				case WATERFALL -> {
					minPowerText = "Power Range: 1e" + Math.round(plotMinPower);
					maxPowerText = "1e" + Math.round(plotMaxPower);
					yPowerTextBaseline = Theme.tilePadding;
					yPowerTextTop = yPowerTextBaseline + OpenGL.smallTextHeight;
					xMaxPowerTextLeft = width - Theme.tilePadding - OpenGL.smallTextWidth(gl, maxPowerText);
					xPowerScaleRight = xMaxPowerTextLeft - Theme.tickTextPadding;
					xPowerScaleLeft = xPowerScaleRight - (100 * ChartsController.getDisplayScalingFactor());
					xMinPowerTextLeft = xPowerScaleLeft - Theme.tickTextPadding - OpenGL.smallTextWidth(gl, minPowerText);
					
					int windowCount = fft.windows.size();
					int windowLength = fft.windowLength;
					int trueTotalSampleCount = windowCount * windowLength;
					fftWindowCountText = windowCount + " windows (total of " + trueTotalSampleCount + " samples)";
					yFftWindowCountTextBaseline = yPowerTextTop + Theme.tickTextPadding;
					xFftWindowCountTextLeft = width - Theme.tilePadding - OpenGL.smallTextWidth(gl, fftWindowCountText);
					
					fftWindowLengthText = windowLength + " sample rectangular window";
					yFftWindowLengthTextBaseline = yFftWindowCountTextBaseline + OpenGL.smallTextHeight + Theme.tickTextPadding;
					xFftWindowLenghtTextLeft = width - Theme.tilePadding - OpenGL.smallTextWidth(gl, fftWindowLengthText);
					
					xFftInfoTextLeft = Float.min(xFftWindowCountTextLeft, xFftWindowLenghtTextLeft);
					xFftInfoTextLeft = Float.min(xMinPowerTextLeft, xFftInfoTextLeft);
					
					float temp = yFftWindowLengthTextBaseline + OpenGL.smallTextHeight + Theme.tickTextPadding;
					if(yPlotBottom < temp) {
						yPlotBottom = temp;
						plotHeight = yPlotTop - yPlotBottom;
					}
				}
			};
		}
		
		float xYaxisTitleTextTop = xPlotLeft;
		float xYaxisTitleTextBaseline = xYaxisTitleTextTop + OpenGL.largeTextHeight;
		String yAxisTitle = chartStyle.is(ChartStyle.WATERFALL) ? "Time (Seconds)" : "Power (Watts)";
		float yYaxisTitleTextLeft = yPlotBottom + (plotHeight / 2.0f) - (OpenGL.largeTextWidth(gl, yAxisTitle) / 2.0f);
		if(yAxisTitleVisibility.get()) {
			xPlotLeft = xYaxisTitleTextBaseline + Theme.tickTextPadding;
			plotWidth = xPlotRight - xPlotLeft;
		}
		
		float yXaxisTitleTextBasline = Theme.tilePadding;
		float yXaxisTitleTextTop = yXaxisTitleTextBasline + OpenGL.largeTextHeight;
		String xAxisTitle = "Frequency (Hertz)";
		float xXaxisTitleTextLeft = 0;
		if(xAxisTitleVisibility.get()) {
			if(!legendVisibility.get() && !fftInfoVisibility.get())
				xXaxisTitleTextLeft = xPlotLeft + (plotWidth / 2.0f) - (OpenGL.largeTextWidth(gl, xAxisTitle)  / 2.0f);
			else if(legendVisibility.get() && fftInfoVisibility.get())
				xXaxisTitleTextLeft = xLegendBorderRight + ((xFftInfoTextLeft - xLegendBorderRight) / 2.0f) - (OpenGL.largeTextWidth(gl, xAxisTitle)  / 2.0f);
			else if(legendVisibility.get())
				xXaxisTitleTextLeft = xLegendBorderRight + ((width - Theme.tilePadding - xLegendBorderRight)  / 2.0f) - (OpenGL.largeTextWidth(gl, xAxisTitle)  / 2.0f);
			else if(fftInfoVisibility.get())
				xXaxisTitleTextLeft = xPlotLeft + ((xFftInfoTextLeft - xPlotLeft) / 2.0f) - (OpenGL.largeTextWidth(gl, xAxisTitle)  / 2.0f);
			
			float temp = yXaxisTitleTextTop + Theme.tickTextPadding;
			if(yPlotBottom < temp) {
				yPlotBottom = temp;
				plotHeight = yPlotTop - yPlotBottom;
				if(yAxisTitleVisibility.get())
					yYaxisTitleTextLeft = yPlotBottom + (plotHeight / 2.0f) - (OpenGL.largeTextWidth(gl, yAxisTitle) / 2.0f);
			}
		}
		
		float yXaxisTickTextBaseline = yPlotBottom;
		float yXaxisTickTextTop = yXaxisTickTextBaseline + OpenGL.smallTextHeight;
		float yXaxisTickBottom = yXaxisTickTextTop + Theme.tickTextPadding;
		float yXaxisTickTop = yXaxisTickBottom + Theme.tickLength;
		if(xAxisTicksVisibility.get()) {
			yPlotBottom = yXaxisTickTop;
			plotHeight = yPlotTop - yPlotBottom;
			if(yAxisTitleVisibility.get())
				yYaxisTitleTextLeft = yPlotBottom + (plotHeight / 2.0f) - (OpenGL.largeTextWidth(gl, yAxisTitle) / 2.0f);
		}
		
		Map<Float, String> yDivisions = null;
		float xYaxisTickTextRight = 0;
		float xYaxisTickLeft = 0;
		float xYaxisTickRight = 0;
		if(yAxisTicksVisibility.get()) {
			yDivisions = chartStyle.is(ChartStyle.WATERFALL) ? ChartUtils.getYdivisions125(plotHeight, plotMinY, plotMaxY) :
			                                                   ChartUtils.getLogYdivisions(plotHeight, plotMinY, plotMaxY);
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
			
			if(xAxisTitleVisibility.get() && !legendVisibility.get() && !fftInfoVisibility.get())
				xXaxisTitleTextLeft = xPlotLeft + (plotWidth / 2.0f) - (OpenGL.largeTextWidth(gl, xAxisTitle)  / 2.0f);
			else if(xAxisTitleVisibility.get() && legendVisibility.get() && fftInfoVisibility.get())
				xXaxisTitleTextLeft = xLegendBorderRight + ((xFftInfoTextLeft - xLegendBorderRight) / 2.0f) - (OpenGL.largeTextWidth(gl, xAxisTitle)  / 2.0f);
			else if(xAxisTitleVisibility.get() && legendVisibility.get())
				xXaxisTitleTextLeft = xLegendBorderRight + ((width - Theme.tilePadding - xLegendBorderRight)  / 2.0f) - (OpenGL.largeTextWidth(gl, xAxisTitle)  / 2.0f);
			else if(xAxisTitleVisibility.get() && fftInfoVisibility.get())
				xXaxisTitleTextLeft = xPlotLeft + ((xFftInfoTextLeft - xPlotLeft) / 2.0f) - (OpenGL.largeTextWidth(gl, xAxisTitle)  / 2.0f);
		}
		
		// get the x divisions now that we know the final plot width
		Map<Float, String> xDivisions = ChartUtils.getFloatXdivisions125(gl, plotWidth, plotMinX, plotMaxX);
		
		// stop if the plot is too small
		if(plotWidth < 1 || plotHeight < 1)
			return handler;
		
		// draw plot background
		OpenGL.drawQuad2D(gl, Theme.plotBackgroundColor, xPlotLeft, yPlotBottom, xPlotRight, yPlotTop);
		
		// draw the x-axis scale
		if(xAxisTicksVisibility.get()) {
			OpenGL.buffer.rewind();
			for(Float xValue : xDivisions.keySet()) {
				float x = (xValue - plotMinX) / domain * plotWidth + xPlotLeft;
				OpenGL.buffer.put(x); OpenGL.buffer.put(yPlotTop);    OpenGL.buffer.put(Theme.divisionLinesColor);
				OpenGL.buffer.put(x); OpenGL.buffer.put(yPlotBottom); OpenGL.buffer.put(Theme.divisionLinesColor);
				
				OpenGL.buffer.put(x); OpenGL.buffer.put(yXaxisTickTop);    OpenGL.buffer.put(Theme.tickLinesColor);
				OpenGL.buffer.put(x); OpenGL.buffer.put(yXaxisTickBottom); OpenGL.buffer.put(Theme.tickLinesColor);
			}
			OpenGL.buffer.rewind();
			int vertexCount = xDivisions.keySet().size() * 4;
			OpenGL.drawLinesXyrgba(gl, GL3.GL_LINES, OpenGL.buffer, vertexCount);
			
			for(Map.Entry<Float,String> entry : xDivisions.entrySet()) {
				float x = (entry.getKey() - plotMinX) / domain * plotWidth + xPlotLeft - (OpenGL.smallTextWidth(gl, entry.getValue()) / 2.0f);
				float y = yXaxisTickTextBaseline;
				OpenGL.drawSmallText(gl, entry.getValue(), (int) x, (int) y, 0);
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
		if(legendVisibility.get() && haveDatasets && xLegendBorderRight < width - Theme.tilePadding) {
			OpenGL.drawQuad2D(gl, Theme.legendBackgroundColor, xLegendBorderLeft, yLegendBorderBottom, xLegendBorderRight, yLegendBorderTop);
			
			for(int i = 0; i < datasetsCount; i++) {
				Field dataset = datasets.getNormal(i);
				if(mouseX >= legendMouseoverCoordinates[i][0] && mouseX <= legendMouseoverCoordinates[i][2] && mouseY >= legendMouseoverCoordinates[i][1] && mouseY <= legendMouseoverCoordinates[i][3]) {
					OpenGL.drawQuadOutline2D(gl, Theme.tickLinesColor, legendMouseoverCoordinates[i][0], legendMouseoverCoordinates[i][1], legendMouseoverCoordinates[i][2], legendMouseoverCoordinates[i][3]);
					handler = EventHandler.onPress(event -> ConfigureView.instance.forDataset(dataset));
				}
				OpenGL.drawQuad2D(gl, dataset.color.getGl(), legendBoxCoordinates[i][0], legendBoxCoordinates[i][1], legendBoxCoordinates[i][2], legendBoxCoordinates[i][3]);
				OpenGL.drawMediumText(gl, dataset.name.get(), (int) xLegendNameLeft[i], (int) yLegendTextBaseline, 0);
			}
		}
		
		// draw the FFT info text if space is available
		boolean spaceForFftInfoText = legendVisibility.get() ? xFftInfoTextLeft > xLegendBorderRight + Theme.legendTextPadding : xFftInfoTextLeft > 0;
		if(fftInfoVisibility.get() && spaceForFftInfoText && haveDatasets) {
			switch(chartStyle.get()) {
				case SINGLE -> {
					OpenGL.drawSmallText(gl, fftWindowLengthText, (int) xFftWindowLenghtTextLeft, (int) yFftWindowLengthTextBaseline, 0);
				}
				case HISTOGRAM -> {
					OpenGL.drawSmallText(gl, fftWindowLengthText, (int) xFftWindowLenghtTextLeft, (int) yFftWindowLengthTextBaseline, 0);
					OpenGL.drawSmallText(gl, fftWindowCountText, (int) xFftWindowCountTextLeft, (int) yFftWindowCountTextBaseline, 0);
				}
				case WATERFALL -> {
					OpenGL.drawSmallText(gl, fftWindowLengthText, (int) xFftWindowLenghtTextLeft, (int) yFftWindowLengthTextBaseline, 0);
					OpenGL.drawSmallText(gl, fftWindowCountText, (int) xFftWindowCountTextLeft, (int) yFftWindowCountTextBaseline, 0);
					OpenGL.drawSmallText(gl, minPowerText, (int) xMinPowerTextLeft, (int) yPowerTextBaseline, 0);
					OpenGL.drawSmallText(gl, maxPowerText, (int) xMaxPowerTextLeft, (int) yPowerTextBaseline, 0);
					OpenGL.drawQuad2D(gl, Theme.plotBackgroundColor, xPowerScaleLeft, yPowerTextBaseline, xPowerScaleRight, yPowerTextTop);
					for(int datasetN = 0; datasetN < datasetsCount; datasetN++) {
						Field dataset = datasets.getNormal(datasetN);
						float top = yPowerTextTop - (yPowerTextTop - yPowerTextBaseline) * datasetN / datasetsCount;
						float bottom = top - (yPowerTextTop - yPowerTextBaseline) / datasetsCount;
						float r = dataset.color.getGl()[0];
						float g = dataset.color.getGl()[1];
						float b = dataset.color.getGl()[2];
						OpenGL.buffer.rewind();
						OpenGL.buffer.put(xPowerScaleLeft);  OpenGL.buffer.put(top);    OpenGL.buffer.put(r); OpenGL.buffer.put(g); OpenGL.buffer.put(b); OpenGL.buffer.put(0);
						OpenGL.buffer.put(xPowerScaleLeft);  OpenGL.buffer.put(bottom); OpenGL.buffer.put(r); OpenGL.buffer.put(g); OpenGL.buffer.put(b); OpenGL.buffer.put(0);
						OpenGL.buffer.put(xPowerScaleRight); OpenGL.buffer.put(top);    OpenGL.buffer.put(r); OpenGL.buffer.put(g); OpenGL.buffer.put(b); OpenGL.buffer.put(1);
						OpenGL.buffer.put(xPowerScaleRight); OpenGL.buffer.put(bottom); OpenGL.buffer.put(r); OpenGL.buffer.put(g); OpenGL.buffer.put(b); OpenGL.buffer.put(1);
						OpenGL.buffer.rewind();
						OpenGL.drawTrianglesXYRGBA(gl, GL3.GL_TRIANGLE_STRIP, OpenGL.buffer, 4);
					}
					OpenGL.drawQuadOutline2D(gl, Theme.legendBackgroundColor, xPowerScaleLeft, yPowerTextBaseline, xPowerScaleRight, yPowerTextTop);
				}
			}
		}
		
		// draw the x-axis title if space is available
		if(xAxisTitleVisibility.get())
			if((!legendVisibility.get() && xXaxisTitleTextLeft > xPlotLeft) || (legendVisibility.get() && xXaxisTitleTextLeft > xLegendBorderRight + Theme.legendTextPadding))
				OpenGL.drawLargeText(gl, xAxisTitle, (int) xXaxisTitleTextLeft, (int) yXaxisTitleTextBasline, 0);
		
		// draw the y-axis title if space is available
		if(yAxisTitleVisibility.get() && yYaxisTitleTextLeft > yPlotBottom)
			OpenGL.drawLargeText(gl, yAxisTitle, (int) xYaxisTitleTextBaseline, (int) yYaxisTitleTextLeft, 90);
		
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
		
		// draw the FFTs
		int[][][] histogram = null; // only populated in Histogram mode
		int actualYaxisBins = 0;
		if(fft.exists) {
			if(chartStyle.is(ChartStyle.SINGLE)) {
				
				// adjust so: x = (x - plotMinX) / domain * plotWidth;
				// adjust so: y = (y - plotMinY) / plotRange * plotHeight;
				int fftBinCount = fft.binCount;
				float[] plotMatrix2 = Arrays.copyOf(plotMatrix, 16);
				OpenGL.scaleMatrix    (plotMatrix2, plotWidth/(float) (fftBinCount-1), plotHeight/(plotMaxPower - plotMinPower), 1);
				OpenGL.translateMatrix(plotMatrix2, 0,                                 -plotMinPower,                            0);
				OpenGL.useMatrix(gl, plotMatrix2);
				
				// draw the FFT line charts, and also draw points if there are relatively few bins on screen
				for(int datasetN = 0; datasetN < datasets.normalDatasets.size(); datasetN++) {
					FloatBuffer buffer = Buffers.newDirectFloatBuffer(fft.windows.get(0).get(datasetN));
					OpenGL.drawLinesY(gl, GL3.GL_LINE_STRIP, datasets.normalDatasets.get(datasetN).color.getGl(), buffer, fftBinCount, 0);
					if(width / fftBinCount > 2 * Theme.pointWidth)
						OpenGL.drawPointsY(gl, datasets.normalDatasets.get(datasetN).color.getGl(), buffer, fftBinCount, 0);
				}
				
				// restore the old matrix
				OpenGL.useMatrix(gl, plotMatrix);
				
			} else if(chartStyle.is(ChartStyle.HISTOGRAM)) {
				
				int xBinCount = fft.binCount;
				int yBinCount = yAxisBins;
				
				fftBinsPerPlotBin = 1;
				while((xAxisBinsAutomatic.get() && xBinCount > plotWidth / 2) || (!xAxisBinsAutomatic.get() && xBinCount > xAxisBins)) {
					fftBinsPerPlotBin++;
					xBinCount = (int) Math.ceil((double) fft.binCount / (double) fftBinsPerPlotBin);
				}
				
				if(yAxisBinsAutomatic.get())
					yBinCount = (int) plotHeight / 2;
				actualYaxisBins = yBinCount;
				
				if(xBinCount > 0 && yBinCount > 0) {
					histogram = new int[datasetsCount][yBinCount][xBinCount];
					ByteBuffer bytes = Buffers.newDirectByteBuffer(yBinCount * xBinCount * 4); // pixelCount * one int32 per pixel
					IntBuffer ints = bytes.asIntBuffer();
					
					for(int datasetN = 0; datasetN < datasetsCount; datasetN++) {
						for(int windowN = 0; windowN < fft.windows.size(); windowN++) {
							float[] dft = fft.windows.get(windowN).get(datasetN);
							for(int xBin = 0; xBin < fft.binCount; xBin++) {
								int yBin = (int) ((dft[xBin] - plotMinPower) / (plotMaxPower - plotMinPower) * yBinCount);
								if(yBin >= 0 && yBin < yBinCount)
									histogram[datasetN][yBin][xBin / fftBinsPerPlotBin]++;
							}
						}
						
						float fullScale = 0;
						for(int y = 0; y < yBinCount; y++)
							for(int x = 0; x < xBinCount; x++)
								fullScale = Math.max(fullScale, histogram[datasetN][y][x]);
						
						ints.rewind();
						for(int y = 0; y < yBinCount; y++)
							ints.put(histogram[datasetN][y]);
						
						if(histogramTexHandle == null) {
							histogramTexHandle = new int[1];
							OpenGL.createHistogramTexture(gl, histogramTexHandle, xBinCount, yBinCount);
						}
						OpenGL.writeHistogramTexture(gl, histogramTexHandle, xBinCount, yBinCount, bytes.rewind());
						OpenGL.drawHistogram(gl, histogramTexHandle, datasets.normalDatasets.get(datasetN).color.getGl(), fullScale, gamma.getFloat(), 0, 0, (int) plotWidth, (int) plotHeight, 1f/xBinCount/2f);
					}
				}
				
			} else if(chartStyle.is(ChartStyle.WATERFALL)) {
				
				int binCount = fft.binCount;
				
				fftBinsPerPlotBin = 1;
				while((xAxisBinsAutomatic.get() && binCount > plotWidth / 2) || (!xAxisBinsAutomatic.get() && binCount > xAxisBins)) {
					fftBinsPerPlotBin++;
					binCount = (int) Math.ceil((double) fft.binCount / (double) fftBinsPerPlotBin);
				}
				
				if(binCount > 0) {
					ByteBuffer bytes = Buffers.newDirectByteBuffer(binCount * fftCount.get() * 4 * 4); // pixelCount * four float32 per pixel
					FloatBuffer pixels = bytes.asFloatBuffer();
					
					// populate the pixels, simulating glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
					for(int datasetN = 0; datasetN < datasetsCount; datasetN++) {
						float newR = datasets.normalDatasets.get(datasetN).color.getGl()[0];
						float newG = datasets.normalDatasets.get(datasetN).color.getGl()[1];
						float newB = datasets.normalDatasets.get(datasetN).color.getGl()[2];
						
						for(int windowN = 0; windowN < fft.windows.size(); windowN++) {
							
							int fftN = fft.windows.size() - 1 - windowN;						
							float[] dft = fft.windows.get(fftN).get(datasetN);
							
							for(int binN = 0; binN < fft.binCount; binN += fftBinsPerPlotBin) {
								int index = ((binN / fftBinsPerPlotBin) + (windowN * binCount)) * 4; // 4 floats per pixel
								
								float r = pixels.get(index + 0);
								float g = pixels.get(index + 1);
								float b = pixels.get(index + 2);
								float a = pixels.get(index + 3);
								
								float newA = 0;
								if(fftBinsPerPlotBin == 1) {
									newA = (dft[binN] - plotMinPower) / (plotMaxPower - plotMinPower);
								} else {
									int firstBin = binN;
									int lastBin = Math.min(binN + fftBinsPerPlotBin, fft.binCount - 1);
									for(int bin = firstBin; bin <= lastBin; bin++)
										newA += (dft[bin] - plotMinPower) / (plotMaxPower - plotMinPower);
									newA /= lastBin - firstBin + 1;
								}
								
								r = (newR * newA) + (r * (1f - newA));
								g = (newG * newA) + (g * (1f - newA));
								b = (newB * newA) + (b * (1f - newA));
								a = (newA * 1f)   + (a * (1f - newA));
								
								pixels.put(index + 0, r);
								pixels.put(index + 1, g);
								pixels.put(index + 2, b);
								pixels.put(index + 3, a);
							}
							
						}
					}
					
					if(waterfallTexHandle == null) {
						waterfallTexHandle = new int[1];
						OpenGL.createTexture(gl, waterfallTexHandle, binCount, fftCount.get(), GL3.GL_RGBA, GL3.GL_FLOAT, false);
					}
					OpenGL.writeTexture(gl, waterfallTexHandle, binCount, fftCount.get(), GL3.GL_RGBA, GL3.GL_FLOAT, bytes);
					OpenGL.drawTexturedBox(gl, waterfallTexHandle, false, 0, 0, (int) plotWidth, (int) plotHeight, 1f/binCount/2f, false);
				}
			}
		}
		
		// draw the tooltip if the mouse is in the plot region
		if(fft.exists && SettingsView.instance.tooltipsVisibility.get() && mouseX >= 0 && mouseX <= plotWidth && mouseY >= 0 && mouseY <= plotHeight) {
			
			if(chartStyle.is(ChartStyle.SINGLE)) {
				
				// map mouseX to a frequency bin, and anchor the tooltip over that frequency bin
				int binN = (int) ((float) mouseX / plotWidth * (fft.binCount - 1) + 0.5f);
				if(binN > fft.binCount - 1)
					binN = fft.binCount - 1;
				float frequency = (float) (binN * fft.binSizeHz);
				int anchorX = (int) ((frequency - plotMinX) / domain * plotWidth);
				
				// get the power levels for each dataset
				Tooltip tooltip = new Tooltip();
				tooltip.addRow(convertFrequencyRangeToString(binN, binN, fft));
				List<float[]> fftOfDataset = fft.windows.get(0);
				for(int datasetN = 0; datasetN < datasetsCount; datasetN++)
					tooltip.addRow(datasets.getNormal(datasetN).color.getGl(),
					               convertPowerToString(fftOfDataset.get(datasetN)[binN]),
					               (float) Math.max((int) ((fftOfDataset.get(datasetN)[binN] - plotMinY) / plotRange * plotHeight), 0));

				tooltip.draw(gl, mouseX, mouseY, plotWidth, plotHeight, anchorX);
				
			} else if(chartStyle.is(ChartStyle.HISTOGRAM)) {
				
				// map mouseX to a frequency bin, and anchor the tooltip over that frequency bin
				// note: one histogram bin corresponds to 1 or >1 FFT bins
				int histogramBinCount = histogram[0][0].length;
				int histogramBinN = (int) ((float) mouseX / plotWidth * (histogramBinCount - 1) + 0.5f);
				if(histogramBinN > histogramBinCount - 1)
					histogramBinN = histogramBinCount - 1;
				int anchorX = (int) (((float) histogramBinN / (float) (histogramBinCount - 1)) * plotWidth);
				int firstFftBin = histogramBinN * fftBinsPerPlotBin;
				int lastFftBin  = Math.min(firstFftBin + fftBinsPerPlotBin - 1, fft.binCount - 1);
				
				// map mouseY to a power bin
				int powerBinN = Math.round((float) mouseY / plotHeight * actualYaxisBins - 0.5f);
				if(powerBinN > actualYaxisBins - 1)
					powerBinN = actualYaxisBins - 1;
				float minPower = (float) powerBinN / (float) actualYaxisBins * (plotMaxPower - plotMinPower) + plotMinPower;
				float maxPower = (float) (powerBinN + 1) / (float) actualYaxisBins * (plotMaxPower - plotMinPower) + plotMinPower;
				
				// get the bin value for each dataset
				int[] windowCountForDataset = new int[datasetsCount];
				for(int datasetN = 0; datasetN < datasetsCount; datasetN++)
					windowCountForDataset[datasetN] = (int) Math.ceil((double) histogram[datasetN][powerBinN][histogramBinN] / (double) fftBinsPerPlotBin);
				int windowCount = fft.windows.size();
				
				int anchorY = (int) (((float) powerBinN + 0.5f) / (float) actualYaxisBins * plotHeight);
				Tooltip tooltip = new Tooltip();
				tooltip.addRow(convertFrequencyRangeToString(firstFftBin, lastFftBin, fft));
				tooltip.addRow(convertPowerRangeToString(minPower, maxPower));
				for(int datasetN = 0; datasetN < datasetsCount; datasetN++)
					if(datasetN == 0)
						tooltip.addRow(datasets.getNormal(datasetN).color.getGl(),
						               ChartUtils.formattedNumber((double) windowCountForDataset[datasetN] / (double) windowCount * 100.0, 3) + "% (" + windowCountForDataset[datasetN] + " of " + windowCount + " FFTs)",
						               (float) anchorY);
					else
						tooltip.addRow(datasets.getNormal(datasetN).color.getGl(),
						               ChartUtils.formattedNumber((double) windowCountForDataset[datasetN] / (double) windowCount * 100.0, 3) + "% (" + windowCountForDataset[datasetN] + " of " + windowCount + " FFTs)");
				
				tooltip.draw(gl, mouseX, mouseY, plotWidth, plotHeight, anchorX);
				
			} else if(chartStyle.is(ChartStyle.WATERFALL)) {
				
				// map mouseX to a frequency bin, and anchor the tooltip over that frequency bin
				// note: one histogram bin corresponds to 1 or >1 FFT bins
				int histogramBinCount = (int) Math.ceil((double) fft.binCount / (double) fftBinsPerPlotBin);
				int histogramBinN = (int) ((float) mouseX / plotWidth * (histogramBinCount - 1) + 0.5f);
				if(histogramBinN > histogramBinCount - 1)
					histogramBinN = histogramBinCount - 1;
				int anchorX = (int) (((float) histogramBinN / (float) (histogramBinCount - 1)) * plotWidth);
				int firstFftBin = histogramBinN * fftBinsPerPlotBin;
				int lastFftBin  = Math.min(firstFftBin + fftBinsPerPlotBin - 1, fft.binCount - 1);
				
				// map mouseY to a time
				int waterfallRowCount = fftCount.get();
				int waterfallRowN = Math.round((float) mouseY / plotHeight * waterfallRowCount - 0.5f);
				if(waterfallRowN > waterfallRowCount - 1)
					waterfallRowN = waterfallRowCount - 1;
				int trueLastSampleNumber = endSampleNumber - (endSampleNumber % fft.windowLength);
				int rowLastSampleNumber = trueLastSampleNumber - (waterfallRowN * fft.windowLength) - 1;
				int rowFirstSampleNumber = rowLastSampleNumber - fft.windowLength + 1;
				if(rowFirstSampleNumber >= 0) {
					// mouse is over an FFT, so proceed with the tooltip
					float secondsElapsed = ((float) waterfallRowN + 0.5f) / (float) waterfallRowCount * plotMaxTime;
					int anchorY = (int) (((float) waterfallRowN + 0.5f) / (float) waterfallRowCount * plotHeight);
					Tooltip tooltip = new Tooltip();
					tooltip.addRow(convertFrequencyRangeToString(firstFftBin, lastFftBin, fft));
					tooltip.addRow(ChartUtils.formattedNumber(secondsElapsed, 4) + " seconds ago");
					
					// get the power levels for each dataset
					for(int datasetN = 0; datasetN < datasetsCount; datasetN++) {
						float power = 0;
						for(int bin = firstFftBin; bin <= lastFftBin; bin++)
							power += fft.windows.get(fft.windows.size() - waterfallRowN - 1).get(datasetN)[bin];
						power /= lastFftBin - firstFftBin + 1;
						if(datasetN == 0)
							tooltip.addRow(datasets.getNormal(datasetN).color.getGl(),
							               convertPowerToString(power),
							               (float) anchorY);
						else
							tooltip.addRow(datasets.getNormal(datasetN).color.getGl(),
							               convertPowerToString(power));
					}
					
					tooltip.draw(gl, mouseX, mouseY, plotWidth, plotHeight, anchorX);
				}
			}
			
		}

		// stop clipping to the plot region
		gl.glScissor(chartScissorArgs[0], chartScissorArgs[1], chartScissorArgs[2], chartScissorArgs[3]);
		
		// switch back to the chart matrix
		OpenGL.useMatrix(gl, chartMatrix);

		// draw the plot border
		OpenGL.drawQuadOutline2D(gl, Theme.plotOutlineColor, xPlotLeft, yPlotBottom, xPlotRight, yPlotTop);
		
		return handler;
		
	}
	
	/**
	 * @param value    Value, in log10 Watts.
	 * @return         The string, for example: "1.234 mW" etc.
	 */
	private String convertPowerToString(float value) {
		
		String unit = null;
		
		     if(value >= 24)  { value -= 24; unit = "YW";      } // yotta
		else if(value >= 21)  { value -= 21; unit = "ZW";      } // zetta
		else if(value >= 18)  { value -= 18; unit = "EW";      } // exa
		else if(value >= 15)  { value -= 15; unit = "PW";      } // peta
		else if(value >= 12)  { value -= 12; unit = "TW";      } // tera
		else if(value >= 9)   { value -=  9; unit = "GW";      } // giga
		else if(value >= 6)   { value -=  6; unit = "MW";      } // mega
		else if(value >= 3)   { value -=  3; unit = "kW";      } // kilo
		else if(value >= 0)   { value -=  0; unit =  "W";      }
		else if(value >= -3)  { value +=  3; unit = "mW";      } // milli
		else if(value >= -6)  { value +=  6; unit = "\u00B5W"; } // micro
		else if(value >= -9)  { value +=  9; unit = "nW";      } // nano
		else if(value >= -12) { value += 12; unit = "pW";      } // pico
		else if(value >= -15) { value += 15; unit = "fW";      } // femto
		else if(value >= -18) { value += 18; unit = "aW";      } // atto
		else if(value >= -21) { value += 21; unit = "zW";      } // zepto
		else                  { value += 24; unit = "yW";      } // yocto
		
		return ChartUtils.formattedNumber(Math.pow(10, value), 4) + " " + unit;
		
	}
	
	/**
	 * @param min    Smaller value, in log10 Watts.
	 * @param max    Larger value,  in log10 Watts.
	 * @return       The string, for example: "1.234 - 2.345 mW" etc.
	 */
	private String convertPowerRangeToString(float min, float max) {
		
		String unit = null;
		
		     if(min >= 24  && max >= 24)  { min -= 24; max -= 24; unit = "YW";      } // yotta
		else if(min >= 21  && max >= 21)  { min -= 21; max -= 21; unit = "ZW";      } // zetta
		else if(min >= 18  && max >= 18)  { min -= 18; max -= 18; unit = "EW";      } // exa
		else if(min >= 15  && max >= 15)  { min -= 15; max -= 15; unit = "PW";      } // peta
		else if(min >= 12  && max >= 12)  { min -= 12; max -= 12; unit = "TW";      } // tera
		else if(min >= 9   && max >= 9)   { min -=  9; max -=  9; unit = "GW";      } // giga
		else if(min >= 6   && max >= 6)   { min -=  6; max -=  6; unit = "MW";      } // mega
		else if(min >= 3   && max >= 3)   { min -=  3; max -=  3; unit = "kW";      } // kilo
		else if(min >= 0   && max >= 0)   { min -=  0; max -=  0; unit =  "W";      }
		else if(min >= -3  && max >= -3)  { min +=  3; max +=  3; unit = "mW";      } // milli
		else if(min >= -6  && max >= -6)  { min +=  6; max +=  6; unit = "\u00B5W"; } // micro
		else if(min >= -9  && max >= -9)  { min +=  9; max +=  9; unit = "nW";      } // nano
		else if(min >= -12 && max >= -12) { min += 12; max += 12; unit = "pW";      } // pico
		else if(min >= -15 && max >= -15) { min += 15; max += 15; unit = "fW";      } // femto
		else if(min >= -18 && max >= -18) { min += 18; max += 18; unit = "aW";      } // atto
		else if(min >= -21 && max >= -21) { min += 21; max += 21; unit = "zW";      } // zepto
		else                              { min += 24; max += 24; unit = "yW";      } // yocto
		
		return ChartUtils.formattedNumber(Math.pow(10, min), 4) + " - " + ChartUtils.formattedNumber(Math.pow(10, max), 4) + " " + unit;
		
	}
	
	/**
	 * @param firstBin    First bin, inclusive.
	 * @param lastBin     Last bin, inclusive.
	 * @param fft         The FFTs.
	 * @return            The string, for example "123.45 - 123.56 Hz" etc.
	 */
	private String convertFrequencyRangeToString(int firstBin, int lastBin, FFTs fft) {
		
		float minFrequency = (float) (firstBin * fft.binSizeHz) - (float) (fft.binSizeHz / 2);
		float maxFrequency = (float) (lastBin  * fft.binSizeHz) + (float) (fft.binSizeHz / 2);
		minFrequency = Math.max(minFrequency, 0);
		maxFrequency = Math.min(maxFrequency, fft.maxHz);
		return ChartUtils.formattedNumber(minFrequency, 5) + " - " + ChartUtils.formattedNumber(maxFrequency, 5) + " Hz";
		
	}
	
	@Override public void disposeGpu(GL2ES3 gl) {
		
		super.disposeGpu(gl);
		
		if(histogramTexHandle != null) {
			gl.glDeleteTextures(1, histogramTexHandle, 0);
			histogramTexHandle = null;
		}
		
		if(waterfallTexHandle != null) {
			gl.glDeleteTextures(1, waterfallTexHandle, 0);
			waterfallTexHandle = null;
		}
		
	}
	
	private class Cache {
		
		private int previousSampleCount = 0;
		private int previousFftsCount = 0;
		private List<Field> previousDatasets = new ArrayList<Field>();
		private ChartStyle previousChartStyle = ChartStyle.SINGLE;
		
		private static class DFT {
			List<float[]> forDataset = new ArrayList<>(); // .get(datasetN)[binN]
			int firstSampleNumber = -1;
			boolean populated;
		}
			
		private int firstDft = 0;
		private int lastDft = 0;
		private DFT[] dft = new DFT[0]; // ring buffer
		
		/**
		 * Calculates the FFTs, and also caches them if using the Histogram or Waterfall mode.
		 * For Histogram or Waterfall mode, the FFTs will be aligned to their window size (e.g. a window size of 1000 will make FFTs of samples 0-999, 1000-1999, etc.)
		 * For Single mode, the FFT will be of the most recent samples, not aligned to the window size (e.g. if the most recent sample is 1234, the FFT would be of samples 235-1234.)
		 * 
		 * @param endSampleNumber    Sample number corresponding with the right edge of a time-domain plot. NOTE: this sample might not exist yet!
		 * @param sampleCount        How many samples make up each FFT.
		 * @param fftsCount          Number of FFTs for Histogram and Waterfall modes. This is ignored in Single mode.
		 * @param datasets           Datasets to visualize, along with their sample caches.
		 * @param chartStyle         Single/Histogram/Waterfall
		 */
		public FFTs getFFT(int endSampleNumber, int sampleCount, int fftsCount, DatasetsInterface datasets, ChartStyle chartStyle) {
			
			// flush the cache if necessary
			if(previousSampleCount != sampleCount || !previousDatasets.equals(datasets.normalDatasets) || previousFftsCount != fftsCount || previousChartStyle != chartStyle) {
				
				dft = new DFT[fftsCount];
				for(int dftN = 0; dftN < fftsCount; dftN++)
					dft[dftN] = new DFT();

				previousSampleCount = sampleCount;
				previousFftsCount = fftsCount;
				previousDatasets = new ArrayList<Field>(datasets.normalDatasets); // must *duplicate* the list so we can detect changes
				previousChartStyle = chartStyle;
				
			}
			
			// starting with defaults
			double binSizeHz = 0;
			int binCount = 0;
			float minHz = 0;
			float maxHz = !datasets.hasNormals() ? 1 : datasets.connection.getSampleRate() / 2;
			float minPower = -12;
			float maxPower = 1;
			int windowLength = 0;
			List<List<float[]>> windows = new ArrayList<List<float[]>>(datasets.normalsCount());
			
			// calculate the FFTs
			if(chartStyle == ChartStyle.SINGLE) {
				
				int trueLastSampleNumber = datasets.hasNormals() ? datasets.connection.getSampleCount() - 1 : 0;
				int lastSampleNumber = Integer.min(endSampleNumber, trueLastSampleNumber);
				int firstSampleNumber = lastSampleNumber - sampleCount + 1;
				if(firstSampleNumber < 0)
					firstSampleNumber = 0;
				if(lastSampleNumber < firstSampleNumber)
					lastSampleNumber = firstSampleNumber;
				sampleCount = lastSampleNumber - firstSampleNumber + 1;
				
				
				// stop if nothing to do
				if(!datasets.hasNormals() || sampleCount < 2)
					return new FFTs(false,
					                binSizeHz,
					                binCount,
					                minHz,
					                maxHz,
					                minPower,
					                maxPower,
					                windowLength,
					                windows);
				
				// calculate the FFT for each dataset
				DFT fft = dft[0];
				int sampleRate = datasets.connection.getSampleRate();
				final int first = firstSampleNumber;
				final int last = lastSampleNumber;
				fft.forDataset.clear();
				datasets.forEachNormal((dataset, cache) -> {
					float[] samples = dataset.getSamplesArray(first, last, cache);
					fft.forDataset.add(calculateFFT(samples, sampleRate));
				});
				fft.firstSampleNumber = firstSampleNumber;
				fft.populated = true;
				
				// calculate the domain and range
				// the FFT is calculated from DC to Nyquist
				// but the user can specify an arbitrary window length, so the max frequency may actually be a little below Nyquist
				float[] firstFft = fft.forDataset.get(0);
				binSizeHz = (double) sampleRate / (double) sampleCount;
				binCount = sampleCount / 2 + 1;
				maxHz = (float) ((double) (firstFft.length - 1) * (double) sampleRate / (double) sampleCount);
				minPower = firstFft[0];
				maxPower = firstFft[0];
				
				for(float[] datasetsFft : fft.forDataset)
					for(int i = 0; i < datasetsFft.length; i++) {
						float y = datasetsFft[i];
						minPower = Math.min(minPower, y);
						maxPower = Math.max(maxPower, y);
					}
				
				windows.add(fft.forDataset);
				windowLength = sampleCount;
				
				return new FFTs(true,
				                binSizeHz,
				                binCount,
				                minHz,
				                maxHz,
				                minPower,
				                maxPower,
				                windowLength,
				                windows);
				
			} else {
				
				lastDft = (endSampleNumber + 1) / sampleCount - 1;
				firstDft = lastDft - fftsCount + 1;
				if(firstDft < 0)
					firstDft = 0;
				if(lastDft < 0)
					return new FFTs(false,
					                binSizeHz,
					                binCount,
					                minHz,
					                maxHz,
					                minPower,
					                maxPower,
					                windowLength,
					                new ArrayList<List<float[]>>(0));

				// calculate the FFTs for each dataset
				int sampleRate = datasets.connection.getSampleRate();
				int trueLastSampleNumber = datasets.connection.getSampleCount() - 1;
				for(int dftN = firstDft; dftN <= lastDft; dftN++) {
					int firstSampleNumber = dftN * sampleCount;
					int lastSampleNumber = firstSampleNumber + sampleCount - 1;
					DFT fft = dft[dftN % fftsCount];
					if(fft.firstSampleNumber != firstSampleNumber || !fft.populated) {
						fft.firstSampleNumber = firstSampleNumber;
						fft.populated = false;
						if(lastSampleNumber <= trueLastSampleNumber) {
							fft.forDataset.clear();
							datasets.forEachNormal((dataset, cache) -> {
								float[] samples = dataset.getSamplesArray(firstSampleNumber, lastSampleNumber, cache);
								fft.forDataset.add(calculateFFT(samples, sampleRate));
							});
							fft.populated = true;
						}
					}
				}
				
				// calculate the domain and range
				// the FFTs are calculated from DC to Nyquist
				// but the user can specify an arbitrary window length, so the max frequency may actually be a little below Nyquist
				DFT fft = dft[firstDft % fftsCount];
				float[] firstFft = fft.forDataset.get(0);
				binSizeHz = (double) sampleRate / (double) sampleCount;
				binCount = sampleCount / 2 + 1;
				maxHz    = (float) ((double) (firstFft.length - 1) * (double) sampleRate / (double) sampleCount);
				minPower = firstFft[0];
				maxPower = firstFft[0];
				for(int dftN = firstDft; dftN <= lastDft; dftN++) {
					fft = dft[dftN % fftsCount];
					if(fft.populated)
						for(float[] datasetsDft : fft.forDataset) {
							for(int i = 0; i < datasetsDft.length; i++) {
								float y = datasetsDft[i];
								minPower = Math.min(minPower, y);
								maxPower = Math.max(maxPower, y);
							}
						}
				}
				
				for(int fftN = firstDft; fftN <= lastDft; fftN++)
					windows.add(dft[fftN % fftsCount].forDataset);
				windowLength = sampleCount;
				
				return new FFTs(true,
				                binSizeHz,
				                binCount,
				                minHz,
				                maxHz,
				                minPower,
				                maxPower,
				                windowLength,
				                windows);
				
			}
			
		}
		
		private DoubleFFT_1D fft = null;
		private int fftSampleCount = 0;
		
		/**
		 * Calculates an FFT. The returned FFT will contain the sequence of power levels. The corresponding frequencies are *not* included.
		 * 
		 * @param samples       A series of samples, as a float[].
		 * @param sampleRate    Sample rate, in Hz.
		 * @returns             The FFT. If the samples have units of Volts, these numbers will have units of log10(Watts).
		 */
		private float[] calculateFFT(float[] samples, int sampleRate) {
				
			if(fft == null || fftSampleCount != samples.length) {
				fft = new DoubleFFT_1D(samples.length);
				fftSampleCount = samples.length;
			}
			
			double[] samplesD = new double[samples.length];
			for(int i = 0; i < samples.length; i++)
				samplesD[i] = samples[i];
			fft.realForward(samplesD);
			
			int sampleCount = samples.length;
			int binCount = sampleCount / 2 + 1;
			
			float[] powerLevels = new float[binCount];
			
			for(int binN = 0; binN < binCount; binN++) {
				double realV;
				double imaginaryV;
				if(binN == 0) {
					realV = samplesD[2*binN + 0];
					imaginaryV = 0;
				} else if(binN == binCount - 1) {
					realV = samplesD[1];
					imaginaryV = 0;
				} else {
					realV      = samplesD[2*binN + 0];
					imaginaryV = samplesD[2*binN + 1];
				}
				realV      /= (double) sampleCount;
				imaginaryV /= (double) sampleCount;
				double powerW = (realV * realV) + (imaginaryV * imaginaryV);
				powerW *= 2; // because DFT is from -Fs to +Fs
				
				// ensure powerW != 0, which would cause the Math.log10() below to return -Infinity
				if(powerW == 0)
					powerW = Math.pow(10, -36); // arbitrarily picked because it looks like a reasonable min
				
				powerLevels[binN] = (float) Math.log10(powerW);
			}
			
			return powerLevels;
			
		}
		
	}

}
