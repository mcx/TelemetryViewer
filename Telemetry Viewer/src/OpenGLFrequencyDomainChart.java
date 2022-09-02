import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.swing.Box;
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
	
	// plot region
	float xPlotLeft;
	float xPlotRight;
	float plotWidth;
	float yPlotTop;
	float yPlotBottom;
	float plotHeight;
	
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
	
	// FFT info
	String fftWindowLengthText;
	float yFftWindowLengthTextBaseline;
	float xFftWindowLenghtTextLeft;
	String fftWindowCountText;
	float yFftWindowCountTextBaseline;
	float xFftWindowCountTextLeft;
	String minPowerText;
	String maxPowerText;
	float yPowerTextBaseline;
	float yPowerTextTop;
	float xMaxPowerTextLeft;
	float xPowerScaleRight;
	float xPowerScaleLeft;
	float xMinPowerTextLeft;
	float xFftInfoTextLeft;
	
	// y-axis title
	float xYaxisTitleTextTop;
	float xYaxisTitleTextBaseline;
	String yAxisTitle;
	float yYaxisTitleTextLeft;
	
	// x-axis title
	float yXaxisTitleTextBasline;
	float yXaxisTitleTextTop;
	String xAxisTitle;
	float xXaxisTitleTextLeft;
	
	// x-axis scale
	Map<Float, String> xDivisions;
	float yXaxisTickTextBaseline;
	float yXaxisTickTextTop;
	float yXaxisTickBottom;
	float yXaxisTickTop;
	
	// y-axis scale
	Map<Float, String> yDivisions;
	float xYaxisTickTextRight;
	float xYaxisTickLeft;
	float xYaxisTickRight;
	AutoScale autoscalePower;
	
	// textures
	private int[] histogramTexHandle;
	private int[] waterfallTexHandle;
	
	// user settings
	private WidgetDatasetCheckboxes datasetsWidget;
	
	private WidgetTextfieldInt sampleCountTextfield;

	private boolean legendVisible = true;
	private WidgetCheckbox legendCheckbox;
	
	public enum ChartStyle {
		SINGLE    { @Override public String toString() { return "Single";    } },
		HISTOGRAM { @Override public String toString() { return "Histogram"; } },
		WATERFALL { @Override public String toString() { return "Waterfall"; } };
	};
	private ChartStyle chartStyle = ChartStyle.SINGLE;
	private WidgetToggleButtonEnum<ChartStyle> chartStyleButtons;
	
	private int fftCount = 20;
	private WidgetTextfieldInt fftCountTextfield;
	
	private int xAxisBins = 128;
	private WidgetTextfieldInt xAxisBinsTextfield;
	private int fftBinsPerPlotBin = 1;
	
	private boolean xAxisBinsAutomatic = false;
	private WidgetCheckbox xAxisBinsAutomaticCheckbox;
	
	private int yAxisBins = 128;
	private WidgetTextfieldInt yAxisBinsTextfield;
	private int actualYaxisBins = 128;
	
	private boolean yAxisBinsAutomatic = false;
	private WidgetCheckbox yAxisBinsAutomaticCheckbox;
	
	private int gamma = 50;
	private WidgetSlider gammaSlider;
	
	private float minimumPower = 1e-9f;
	private WidgetTextfieldFloat minimumPowerTextfield;
	
	private boolean minimumPowerAutomatic = true;
	private WidgetCheckbox minimumPowerAutomaticCheckbox;
	
	private float maximumPower = 1e9f;
	private WidgetTextfieldFloat maximumPowerTextfield;
	
	private boolean maximumPowerAutomatic = true;
	private WidgetCheckbox maximumPowerAutomaticCheckbox;
	
	private boolean fftInfoVisible = true;
	private WidgetCheckbox fftInfoCheckbox;
	
	private boolean xAxisTicksVisible = true;
	private WidgetCheckbox xAxisTicksCheckbox;
	
	private boolean xAxisTitleVisible = true;
	private WidgetCheckbox xAxisTitleCheckbox;
	
	private boolean yAxisTicksVisible = true;
	private WidgetCheckbox yAxisTicksCheckbox;
	
	private boolean yAxisTitleVisible = true;
	private WidgetCheckbox yAxisTitleCheckbox;
	
	@Override public String toString() {
		
		return "Frequency Domain";
		
	}
	
	public OpenGLFrequencyDomainChart(int x1, int y1, int x2, int y2) {
		
		super(x1, y1, x2, y2);
		
		autoscalePower = new AutoScale(AutoScale.MODE_EXPONENTIAL, 90, 0.20f);
		
		// create the control widgets and event handlers
		datasetsWidget = new WidgetDatasetCheckboxes(newDatasets -> datasets.setNormals(newDatasets),
		                                             null,
		                                             null,
		                                             null,
		                                             false);
		
		sampleCountTextfield = new WidgetTextfieldInt("",
		                                              "sample count",
		                                              "Samples",
		                                              10,
		                                              Integer.MAX_VALUE / 16,
		                                              ConnectionsController.getDefaultChartDuration(),
		                                              newDuration -> duration = newDuration);
		
		legendCheckbox = new WidgetCheckbox("Show Legend",
		                                    legendVisible,
		                                    newVisibility -> legendVisible = newVisibility);
		
		fftCountTextfield = new WidgetTextfieldInt("",
		                                           "histogram/waterfall fft count",
		                                           "FFTs",
		                                           2,
		                                           100,
		                                           fftCount,
		                                           newCount -> fftCount = newCount);
		
		xAxisBinsTextfield = new WidgetTextfieldInt("X-Axis Bins",
		                                            "histogram/waterfall x-axis bin count",
		                                            "",
		                                            2,
		                                            4096,
		                                            xAxisBins,
		                                            true,
		                                            0,
		                                            "Automatic",
		                                            newCount -> {
		                                                if(newCount == 0)
		                                                    xAxisBinsAutomaticCheckbox.setSelected(true);
		                                                else
		                                                    xAxisBins = newCount;
		                                            });
		
		xAxisBinsAutomaticCheckbox = new WidgetCheckbox("Automatic",
		                                                "histogram/waterfall x-axis bin count automatic",
		                                                xAxisBinsAutomatic,
		                                                isAutomatic -> {
		                                                    xAxisBinsAutomatic = isAutomatic;
		                                                    if(isAutomatic) {
		                                                        xAxisBinsTextfield.disableWithMessage("Automatic");
		                                                    } else {
		                                                        xAxisBinsTextfield.setNumber(xAxisBins);
		                                                        xAxisBinsTextfield.setEnabled(true);
		                                                    }
		                                                });
		
		yAxisBinsTextfield = new WidgetTextfieldInt("Y-Axis Bins",
		                                            "histogram y-axis bin count",
		                                            "",
		                                            2,
		                                            4096,
		                                            yAxisBins,
		                                            true,
		                                            0,
		                                            "Automatic",
		                                            newCount -> {
		                                                if(newCount == 0)
		                                                    yAxisBinsAutomaticCheckbox.setSelected(true);
		                                                else
		                                                    yAxisBins = newCount;
		                                            });
		
		yAxisBinsAutomaticCheckbox = new WidgetCheckbox("Automatic",
		                                                "histogram y-axis bin count automatic",
		                                                yAxisBinsAutomatic,
		                                                isAutomatic -> {
		                                                    yAxisBinsAutomatic = isAutomatic;
		                                                    if(isAutomatic) {
		                                                        yAxisBinsTextfield.disableWithMessage("Automatic");
		                                                    } else {
		                                                        yAxisBinsTextfield.setNumber(yAxisBins);
		                                                        yAxisBinsTextfield.setEnabled(true);
		                                                    }
		                                                });
		
		gammaSlider = new WidgetSlider("Gamma",
		                               "histogram/waterfall fft gamma",
		                               "%",
		                               0,
		                               100,
		                               gamma,
		                               newGamma -> gamma = newGamma);
		
		chartStyleButtons = new WidgetToggleButtonEnum<ChartStyle>("Style",
		                                                           "fft style",
		                                                           ChartStyle.values(),
		                                                           chartStyle,
		                                                           newStyle -> {
		                                                               chartStyle = newStyle;
		                                                               fftCountTextfield.setVisible(chartStyle != ChartStyle.SINGLE);
		                                                               xAxisBinsTextfield.setVisible(chartStyle != ChartStyle.SINGLE);
		                                                               xAxisBinsAutomaticCheckbox.setVisible(chartStyle != ChartStyle.SINGLE);
		                                                               yAxisBinsTextfield.setVisible(chartStyle == ChartStyle.HISTOGRAM);
		                                                               yAxisBinsAutomaticCheckbox.setVisible(chartStyle == ChartStyle.HISTOGRAM);
		                                                               gammaSlider.setVisible(chartStyle == ChartStyle.HISTOGRAM);
		                                                           });
		
		minimumPowerTextfield = new WidgetTextfieldFloat("Minimum Power",
		                                                 "fft minimum power",
		                                                 "Watts",
		                                                 Float.MIN_VALUE,
		                                                 Float.MAX_VALUE,
		                                                 minimumPower,
		                                                 newMinimum -> {
		                                                     minimumPower = newMinimum;
		                                                     if(minimumPower > maximumPower)
		                                                         maximumPowerTextfield.setNumber(minimumPower);
		                                                 });
		
		minimumPowerAutomaticCheckbox = new WidgetCheckbox("Automatic",
		                                                   "fft minimum power automatic",
		                                                   minimumPowerAutomatic,
		                                                   isAutomatic -> {
		                                                       minimumPowerAutomatic = isAutomatic;
		                                                       if(isAutomatic)
		                                                           minimumPowerTextfield.disableWithMessage("Automatic");
		                                                       else
		                                                           minimumPowerTextfield.setEnabled(true);
		                                                   });
		
		maximumPowerTextfield = new WidgetTextfieldFloat("Maximum Power",
		                                                 "fft maximum power",
		                                                 "Watts",
		                                                 Float.MIN_VALUE,
		                                                 Float.MAX_VALUE,
		                                                 maximumPower,
		                                                 newMaximum -> {
		                                                     maximumPower = newMaximum;
		                                                     if(maximumPower < minimumPower)
		                                                         minimumPowerTextfield.setNumber(maximumPower);
		                                                 });
		
		maximumPowerAutomaticCheckbox = new WidgetCheckbox("Automatic",
		                                                   "fft maximum power automatic",
		                                                   maximumPowerAutomatic,
		                                                   isAutomatic -> {
		                                                       maximumPowerAutomatic = isAutomatic;
		                                                       if(isAutomatic)
		                                                           maximumPowerTextfield.disableWithMessage("Automatic");
		                                                       else
		                                                           maximumPowerTextfield.setEnabled(true);
		                                                   });
		
		fftInfoCheckbox = new WidgetCheckbox("Show FFT Info",
		                                     "fft show info",
		                                     fftInfoVisible,
		                                     newVisibility -> fftInfoVisible = newVisibility);
		
		xAxisTicksCheckbox = new WidgetCheckbox("Show Ticks",
		                                        "x-axis show ticks",
		                                        xAxisTicksVisible,
		                                        newVisibility -> xAxisTicksVisible = newVisibility);
		
		xAxisTitleCheckbox = new WidgetCheckbox("Show Title",
		                                        "x-axis show title",
		                                        xAxisTitleVisible,
		                                        newVisibility -> xAxisTitleVisible = newVisibility);
		
		yAxisTicksCheckbox = new WidgetCheckbox("Show Ticks",
		                                        "y-axis show ticks",
		                                        yAxisTicksVisible,
		                                        newVisibility -> yAxisTicksVisible = newVisibility);
		
		yAxisTitleCheckbox = new WidgetCheckbox("Show Title",
		                                        "y-axis show title",
		                                        yAxisTitleVisible,
		                                        newVisibility -> yAxisTitleVisible = newVisibility);
		
		widgets.add(datasetsWidget);
		widgets.add(sampleCountTextfield);
		widgets.add(legendCheckbox);
		widgets.add(chartStyleButtons);
		widgets.add(fftCountTextfield);
		widgets.add(gammaSlider);
		widgets.add(xAxisBinsTextfield);
		widgets.add(xAxisBinsAutomaticCheckbox);
		widgets.add(yAxisBinsTextfield);
		widgets.add(yAxisBinsAutomaticCheckbox);
		widgets.add(minimumPowerTextfield);
		widgets.add(minimumPowerAutomaticCheckbox);
		widgets.add(maximumPowerTextfield);
		widgets.add(maximumPowerAutomaticCheckbox);
		widgets.add(fftInfoCheckbox);
		widgets.add(xAxisTicksCheckbox);
		widgets.add(xAxisTitleCheckbox);
		widgets.add(yAxisTicksCheckbox);
		widgets.add(yAxisTitleCheckbox);
		
	}
	
	@Override public void getConfigurationGui(JPanel gui) {
		
		JPanel dataPanel = Theme.newWidgetsPanel("Data");
		datasetsWidget.appendToGui(dataPanel);
		dataPanel.add(sampleCountTextfield, "span 4, split 2, grow x, grow y, sizegroup 0");
		dataPanel.add(legendCheckbox, "grow x, grow y, sizegroup 0");
		
		JPanel fftPanel = Theme.newWidgetsPanel("FFTs");
		chartStyleButtons.appendToGui(fftPanel);
		fftPanel.add(fftCountTextfield, "grow x, grow y");
		fftPanel.add(xAxisBinsTextfield, "split 2, grow x, grow y");
		fftPanel.add(xAxisBinsAutomaticCheckbox, "sizegroup 1");
		fftPanel.add(yAxisBinsTextfield, "split 2, grow x, grow y");
		fftPanel.add(yAxisBinsAutomaticCheckbox, "sizegroup 1");
		gammaSlider.appendToGui(fftPanel);
		fftPanel.add(Box.createVerticalStrut(Theme.padding), "span 4");
		fftPanel.add(minimumPowerTextfield, "split 2, grow x, grow y");
		fftPanel.add(minimumPowerAutomaticCheckbox, "sizegroup 1");
		fftPanel.add(maximumPowerTextfield, "split 2, grow x, grow y");
		fftPanel.add(maximumPowerAutomaticCheckbox, "sizegroup 1");
		fftPanel.add(Box.createVerticalStrut(Theme.padding), "span 4");
		fftPanel.add(fftInfoCheckbox, "grow x, grow y");
		
		JPanel xAxisPanel = Theme.newWidgetsPanel("X-Axis");
		xAxisPanel.add(xAxisTicksCheckbox, "split 2, grow x");
		xAxisPanel.add(xAxisTitleCheckbox, "grow x");
		
		JPanel yAxisPanel = Theme.newWidgetsPanel("Y-Axis");
		yAxisPanel.add(yAxisTicksCheckbox, "split 2, grow x");
		yAxisPanel.add(yAxisTitleCheckbox, "grow x");
		
		gui.add(dataPanel);
		gui.add(fftPanel);
		gui.add(xAxisPanel);
		gui.add(yAxisPanel);
		
	}
	
	@Override public EventHandler drawChart(GL2ES3 gl, float[] chartMatrix, int width, int height, long endTimestamp, int endSampleNumber, double zoomLevel, int mouseX, int mouseY) {
		
		EventHandler handler = null;
		
		boolean haveDatasets = datasets.hasNormals();
		int datasetsCount = datasets.normalsCount();
		
		// calculate the FFTs
		FFTs fft = cache.getFFT(endSampleNumber, duration, fftCount, datasets, chartStyle);
		
		// calculate the domain
		float plotMinX = fft.minHz;
		float plotMaxX = fft.maxHz;
		float domain = plotMaxX - plotMinX;
		
		// calculate the range and ensure it's >0
		// the y-axis is power (single/histogram modes) or time (waterfall mode)
		float sampleRate = haveDatasets ? datasets.connection.getSampleRate() : 1;
		float plotMinTime = 0;
		float plotMaxTime = (float) (duration * fftCount) / sampleRate;

		float plotMinPower = fft.minPower;
		float plotMaxPower = fft.maxPower;
		if(plotMinPower == plotMaxPower) {
			float value = plotMinPower;
			plotMinPower = value - 0.001f;
			plotMaxPower = value + 0.001f;
		}
		autoscalePower.update(plotMinPower, plotMaxPower);
		
		if(!minimumPowerAutomatic)
			plotMinPower = (float) Math.log10(minimumPower);
		else if(minimumPowerAutomatic && chartStyle != ChartStyle.WATERFALL)
			plotMinPower = autoscalePower.getMin();
		
		if(!maximumPowerAutomatic)
			plotMaxPower = (float) Math.log10(maximumPower);
		else if(maximumPowerAutomatic && chartStyle != ChartStyle.WATERFALL)
			plotMaxPower = autoscalePower.getMax();

		float plotMinY = chartStyle == ChartStyle.WATERFALL ? plotMinTime : plotMinPower;
		float plotMaxY = chartStyle == ChartStyle.WATERFALL ? plotMaxTime : plotMaxPower;
		float plotRange = plotMaxY - plotMinY;
		
		// calculate x and y positions of everything
		xPlotLeft = Theme.tilePadding;
		xPlotRight = width - Theme.tilePadding;
		plotWidth = xPlotRight - xPlotLeft;
		yPlotTop = height - Theme.tilePadding;
		yPlotBottom = Theme.tilePadding;
		plotHeight = yPlotTop - yPlotBottom;
		
		if(legendVisible && haveDatasets) {
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
				xOffset += OpenGL.mediumTextWidth(gl, datasets.getNormal(i).name) + Theme.legendNamesPadding;
				
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
		
		if(fftInfoVisible) {
			if(chartStyle == ChartStyle.SINGLE) {
				
				fftWindowLengthText = fft.windowLength + " sample rectangular window";
				yFftWindowLengthTextBaseline = Theme.tilePadding;
				xFftWindowLenghtTextLeft = width - Theme.tilePadding - OpenGL.smallTextWidth(gl, fftWindowLengthText);
				
				xFftInfoTextLeft = xFftWindowLenghtTextLeft;
				
				float temp = yFftWindowLengthTextBaseline + OpenGL.smallTextHeight + Theme.tickTextPadding;
				if(yPlotBottom < temp) {
					yPlotBottom = temp;
					plotHeight = yPlotTop - yPlotBottom;
				}
				
			} else if(chartStyle == ChartStyle.HISTOGRAM) {
				
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
				
			} else if(chartStyle == ChartStyle.WATERFALL) {
				
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
		}
		
		if(yAxisTitleVisible) {
			xYaxisTitleTextTop = xPlotLeft;
			xYaxisTitleTextBaseline = xYaxisTitleTextTop + OpenGL.largeTextHeight;
			yAxisTitle = (chartStyle == ChartStyle.WATERFALL) ? "Time (Seconds)" : "Power (Watts)";
			yYaxisTitleTextLeft = yPlotBottom + (plotHeight / 2.0f) - (OpenGL.largeTextWidth(gl, yAxisTitle) / 2.0f);
			
			xPlotLeft = xYaxisTitleTextBaseline + Theme.tickTextPadding;
			plotWidth = xPlotRight - xPlotLeft;
		}
		
		if(xAxisTitleVisible) {
			yXaxisTitleTextBasline = Theme.tilePadding;
			yXaxisTitleTextTop = yXaxisTitleTextBasline + OpenGL.largeTextHeight;
			xAxisTitle = "Frequency (Hertz)";
			
			if(!legendVisible && !fftInfoVisible)
				xXaxisTitleTextLeft = xPlotLeft + (plotWidth / 2.0f) - (OpenGL.largeTextWidth(gl, xAxisTitle)  / 2.0f);
			else if(legendVisible && fftInfoVisible)
				xXaxisTitleTextLeft = xLegendBorderRight + ((xFftInfoTextLeft - xLegendBorderRight) / 2.0f) - (OpenGL.largeTextWidth(gl, xAxisTitle)  / 2.0f);
			else if(legendVisible)
				xXaxisTitleTextLeft = xLegendBorderRight + ((width - Theme.tilePadding - xLegendBorderRight)  / 2.0f) - (OpenGL.largeTextWidth(gl, xAxisTitle)  / 2.0f);
			else if(fftInfoVisible)
				xXaxisTitleTextLeft = xPlotLeft + ((xFftInfoTextLeft - xPlotLeft) / 2.0f) - (OpenGL.largeTextWidth(gl, xAxisTitle)  / 2.0f);
			
			float temp = yXaxisTitleTextTop + Theme.tickTextPadding;
			if(yPlotBottom < temp) {
				yPlotBottom = temp;
				plotHeight = yPlotTop - yPlotBottom;
				if(yAxisTitleVisible)
					yYaxisTitleTextLeft = yPlotBottom + (plotHeight / 2.0f) - (OpenGL.largeTextWidth(gl, yAxisTitle) / 2.0f);
			}
		}
		
		if(xAxisTicksVisible) {
			yXaxisTickTextBaseline = yPlotBottom;
			yXaxisTickTextTop = yXaxisTickTextBaseline + OpenGL.smallTextHeight;
			yXaxisTickBottom = yXaxisTickTextTop + Theme.tickTextPadding;
			yXaxisTickTop = yXaxisTickBottom + Theme.tickLength;
			
			yPlotBottom = yXaxisTickTop;
			plotHeight = yPlotTop - yPlotBottom;
			if(yAxisTitleVisible)
				yYaxisTitleTextLeft = yPlotBottom + (plotHeight / 2.0f) - (OpenGL.largeTextWidth(gl, yAxisTitle) / 2.0f);
		}
		
		if(yAxisTicksVisible) {
			yDivisions = (chartStyle == ChartStyle.WATERFALL) ? ChartUtils.getYdivisions125(plotHeight, plotMinY, plotMaxY) :
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
			
			if(xAxisTitleVisible && !legendVisible && !fftInfoVisible)
				xXaxisTitleTextLeft = xPlotLeft + (plotWidth / 2.0f) - (OpenGL.largeTextWidth(gl, xAxisTitle)  / 2.0f);
			else if(xAxisTitleVisible && legendVisible && fftInfoVisible)
				xXaxisTitleTextLeft = xLegendBorderRight + ((xFftInfoTextLeft - xLegendBorderRight) / 2.0f) - (OpenGL.largeTextWidth(gl, xAxisTitle)  / 2.0f);
			else if(xAxisTitleVisible && legendVisible)
				xXaxisTitleTextLeft = xLegendBorderRight + ((width - Theme.tilePadding - xLegendBorderRight)  / 2.0f) - (OpenGL.largeTextWidth(gl, xAxisTitle)  / 2.0f);
			else if(xAxisTitleVisible && fftInfoVisible)
				xXaxisTitleTextLeft = xPlotLeft + ((xFftInfoTextLeft - xPlotLeft) / 2.0f) - (OpenGL.largeTextWidth(gl, xAxisTitle)  / 2.0f);
		}
		
		// get the x divisions now that we know the final plot width
		xDivisions = ChartUtils.getFloatXdivisions125(gl, plotWidth, plotMinX, plotMaxX);
		
		// stop if the plot is too small
		if(plotWidth < 1 || plotHeight < 1)
			return handler;
		
		// draw plot background
		OpenGL.drawQuad2D(gl, Theme.plotBackgroundColor, xPlotLeft, yPlotBottom, xPlotRight, yPlotTop);
		
		// draw the x-axis scale
		if(xAxisTicksVisible) {
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
		if(legendVisible && haveDatasets && xLegendBorderRight < width - Theme.tilePadding) {
			OpenGL.drawQuad2D(gl, Theme.legendBackgroundColor, xLegendBorderLeft, yLegendBorderBottom, xLegendBorderRight, yLegendBorderTop);
			
			for(int i = 0; i < datasetsCount; i++) {
				Dataset dataset = datasets.getNormal(i);
				if(mouseX >= legendMouseoverCoordinates[i][0] && mouseX <= legendMouseoverCoordinates[i][2] && mouseY >= legendMouseoverCoordinates[i][1] && mouseY <= legendMouseoverCoordinates[i][3]) {
					OpenGL.drawQuadOutline2D(gl, Theme.tickLinesColor, legendMouseoverCoordinates[i][0], legendMouseoverCoordinates[i][1], legendMouseoverCoordinates[i][2], legendMouseoverCoordinates[i][3]);
					handler = EventHandler.onPress(event -> ConfigureView.instance.forDataset(dataset));
				}
				OpenGL.drawQuad2D(gl, dataset.glColor, legendBoxCoordinates[i][0], legendBoxCoordinates[i][1], legendBoxCoordinates[i][2], legendBoxCoordinates[i][3]);
				OpenGL.drawMediumText(gl, dataset.name, (int) xLegendNameLeft[i], (int) yLegendTextBaseline, 0);
			}
		}
		
		// draw the FFT info text if space is available
		boolean spaceForFftInfoText = legendVisible ? xFftInfoTextLeft > xLegendBorderRight + Theme.legendTextPadding : xFftInfoTextLeft > 0;
		if(fftInfoVisible && spaceForFftInfoText && haveDatasets) {
			if(chartStyle == ChartStyle.SINGLE) {
				
				OpenGL.drawSmallText(gl, fftWindowLengthText, (int) xFftWindowLenghtTextLeft, (int) yFftWindowLengthTextBaseline, 0);
				
			} else if(chartStyle == ChartStyle.HISTOGRAM) {
				
				OpenGL.drawSmallText(gl, fftWindowLengthText, (int) xFftWindowLenghtTextLeft, (int) yFftWindowLengthTextBaseline, 0);
				OpenGL.drawSmallText(gl, fftWindowCountText, (int) xFftWindowCountTextLeft, (int) yFftWindowCountTextBaseline, 0);
				
			} else if(chartStyle == ChartStyle.WATERFALL) {
				
				OpenGL.drawSmallText(gl, fftWindowLengthText, (int) xFftWindowLenghtTextLeft, (int) yFftWindowLengthTextBaseline, 0);
				OpenGL.drawSmallText(gl, fftWindowCountText, (int) xFftWindowCountTextLeft, (int) yFftWindowCountTextBaseline, 0);
				OpenGL.drawSmallText(gl, minPowerText, (int) xMinPowerTextLeft, (int) yPowerTextBaseline, 0);
				OpenGL.drawSmallText(gl, maxPowerText, (int) xMaxPowerTextLeft, (int) yPowerTextBaseline, 0);
				
				OpenGL.drawQuad2D(gl, Theme.plotBackgroundColor, xPowerScaleLeft, yPowerTextBaseline, xPowerScaleRight, yPowerTextTop);
				
				for(int datasetN = 0; datasetN < datasetsCount; datasetN++) {
					Dataset dataset = datasets.getNormal(datasetN);
					float top = yPowerTextTop - (yPowerTextTop - yPowerTextBaseline) * datasetN / datasetsCount;
					float bottom = top - (yPowerTextTop - yPowerTextBaseline) / datasetsCount;
					float r = dataset.glColor[0];
					float g = dataset.glColor[1];
					float b = dataset.glColor[2];
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
		
		// draw the x-axis title if space is available
		if(xAxisTitleVisible)
			if((!legendVisible && xXaxisTitleTextLeft > xPlotLeft) || (legendVisible && xXaxisTitleTextLeft > xLegendBorderRight + Theme.legendTextPadding))
				OpenGL.drawLargeText(gl, xAxisTitle, (int) xXaxisTitleTextLeft, (int) yXaxisTitleTextBasline, 0);
		
		// draw the y-axis title if space is available
		if(yAxisTitleVisible && yYaxisTitleTextLeft > yPlotBottom)
			OpenGL.drawLargeText(gl, yAxisTitle, (int) xYaxisTitleTextBaseline, (int) yYaxisTitleTextLeft, 90);
		
		
		// draw the FFTs
		int[][][] histogram = null; // only populated in Histogram mode
		if(fft.exists) {
			if(chartStyle == ChartStyle.SINGLE) {
				
				// clip to the plot region
				int[] originalScissorArgs = new int[4];
				gl.glGetIntegerv(GL3.GL_SCISSOR_BOX, originalScissorArgs, 0);
				gl.glScissor(originalScissorArgs[0] + (int) xPlotLeft, originalScissorArgs[1] + (int) yPlotBottom, width, height);
				
				// adjust so: x = (x - plotMinX) / domain * plotWidth + xPlotLeft;
				// adjust so: y = (y - plotMinY) / plotRange * plotHeight + yPlotBottom;
				int fftBinCount = fft.binCount;
				float[] plotMatrix = Arrays.copyOf(chartMatrix, 16);
				OpenGL.translateMatrix(plotMatrix, xPlotLeft,                         yPlotBottom,                              0);
				OpenGL.scaleMatrix    (plotMatrix, plotWidth/(float) (fftBinCount-1), plotHeight/(plotMaxPower - plotMinPower), 1);
				OpenGL.translateMatrix(plotMatrix, 0,                                 -plotMinPower,                            0);
				OpenGL.useMatrix(gl, plotMatrix);
				
				// draw the FFT line charts, and also draw points if there are relatively few bins on screen
				for(int datasetN = 0; datasetN < datasets.normalDatasets.size(); datasetN++) {
					FloatBuffer buffer = Buffers.newDirectFloatBuffer(fft.windows.get(0).get(datasetN));
					OpenGL.drawLinesY(gl, GL3.GL_LINE_STRIP, datasets.normalDatasets.get(datasetN).glColor, buffer, fftBinCount, 0);
					if(width / fftBinCount > 2 * Theme.pointWidth)
						OpenGL.drawPointsY(gl, datasets.normalDatasets.get(datasetN).glColor, buffer, fftBinCount, 0);
				}
				
				// restore the old matrix and stop clipping to the plot region
				OpenGL.useMatrix(gl, chartMatrix);
				gl.glScissor(originalScissorArgs[0], originalScissorArgs[1], originalScissorArgs[2], originalScissorArgs[3]);
				
			} else if(chartStyle == ChartStyle.HISTOGRAM) {
				
				int xBinCount = fft.binCount;
				int yBinCount = yAxisBins;
				
				fftBinsPerPlotBin = 1;
				while((xAxisBinsAutomatic && xBinCount > plotWidth / 2) || (!xAxisBinsAutomatic && xBinCount > xAxisBins)) {
					fftBinsPerPlotBin++;
					xBinCount = (int) Math.ceil((double) fft.binCount / (double) fftBinsPerPlotBin);
				}
				
				if(yAxisBinsAutomatic)
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
						OpenGL.drawHistogram(gl, histogramTexHandle, datasets.normalDatasets.get(datasetN).glColor, fullScale, (float) gamma / 100f, (int) xPlotLeft, (int) yPlotBottom, (int) plotWidth, (int) plotHeight, 1f/xBinCount/2f);
					}
				}
				
			} else if(chartStyle == ChartStyle.WATERFALL) {
				
				int binCount = fft.binCount;
				
				fftBinsPerPlotBin = 1;
				while((xAxisBinsAutomatic && binCount > plotWidth / 2) || (!xAxisBinsAutomatic && binCount > xAxisBins)) {
					fftBinsPerPlotBin++;
					binCount = (int) Math.ceil((double) fft.binCount / (double) fftBinsPerPlotBin);
				}
				
				if(binCount > 0) {
					ByteBuffer bytes = Buffers.newDirectByteBuffer(binCount * fftCount * 4 * 4); // pixelCount * four float32 per pixel
					FloatBuffer pixels = bytes.asFloatBuffer();
					
					// populate the pixels, simulating glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
					for(int datasetN = 0; datasetN < datasetsCount; datasetN++) {
						float newR = datasets.normalDatasets.get(datasetN).glColor[0];
						float newG = datasets.normalDatasets.get(datasetN).glColor[1];
						float newB = datasets.normalDatasets.get(datasetN).glColor[2];
						
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
						OpenGL.createTexture(gl, waterfallTexHandle, binCount, fftCount, GL3.GL_RGBA, GL3.GL_FLOAT, false);
					}
					OpenGL.writeTexture(gl, waterfallTexHandle, binCount, fftCount, GL3.GL_RGBA, GL3.GL_FLOAT, bytes);
					OpenGL.drawTexturedBox(gl, waterfallTexHandle, false, (int) xPlotLeft, (int) yPlotBottom, (int) plotWidth, (int) plotHeight, 1f/binCount/2f, false);
				}
			}
		}
		
		// draw the tooltip if the mouse is in the plot region
		if(fft.exists && SettingsController.getTooltipVisibility() && mouseX >= xPlotLeft && mouseX <= xPlotRight && mouseY >= yPlotBottom && mouseY <= yPlotTop) {
			
			if(chartStyle == ChartStyle.SINGLE) {
				
				// map mouseX to a frequency bin, and anchor the tooltip over that frequency bin
				int binN = (int) (((float) mouseX - xPlotLeft) / plotWidth * (fft.binCount - 1) + 0.5f);
				if(binN > fft.binCount - 1)
					binN = fft.binCount - 1;
				float frequency = (float) (binN * fft.binSizeHz);
				int anchorX = (int) ((frequency - plotMinX) / domain * plotWidth + xPlotLeft);
				
				// get the power levels for each dataset
				List<TooltipEntry> entries = new ArrayList<TooltipEntry>();
				List<float[]> fftOfDataset = fft.windows.get(0);
				entries.add(new TooltipEntry(null, convertFrequencyRangeToString(binN, binN, fft)));
				for(int datasetN = 0; datasetN < datasetsCount; datasetN++)
					entries.add(new TooltipEntry(datasets.getNormal(datasetN).glColor, convertPowerToString(fftOfDataset.get(datasetN)[binN])));
				
				// draw the tooltip
				if(datasetsCount > 1) {
					OpenGL.buffer.rewind();
					OpenGL.buffer.put(anchorX); OpenGL.buffer.put(yPlotTop);
					OpenGL.buffer.put(anchorX); OpenGL.buffer.put(yPlotBottom);
					OpenGL.buffer.rewind();
					OpenGL.drawLinesXy(gl, GL3.GL_LINES, Theme.tooltipVerticalBarColor, OpenGL.buffer, 2);
					drawTooltip(gl, entries, anchorX, mouseY, xPlotLeft, yPlotTop, xPlotRight, yPlotBottom);
				} else {
					int anchorY = (int) ((fftOfDataset.get(0)[binN] - plotMinY) / plotRange * plotHeight + yPlotBottom);
					anchorY = Math.max(anchorY, (int) yPlotBottom);
					drawTooltip(gl, entries, anchorX, anchorY, xPlotLeft, yPlotTop, xPlotRight, yPlotBottom);
				}
				
			} else if(chartStyle == ChartStyle.HISTOGRAM) {
				
				// map mouseX to a frequency bin, and anchor the tooltip over that frequency bin
				// note: one histogram bin corresponds to 1 or >1 FFT bins
				int histogramBinCount = histogram[0][0].length;
				int histogramBinN = (int) (((float) mouseX - xPlotLeft) / plotWidth * (histogramBinCount - 1) + 0.5f);
				if(histogramBinN > histogramBinCount - 1)
					histogramBinN = histogramBinCount - 1;
				int anchorX = (int) (((float) histogramBinN / (float) (histogramBinCount - 1)) * plotWidth + xPlotLeft);
				int firstFftBin = histogramBinN * fftBinsPerPlotBin;
				int lastFftBin  = Math.min(firstFftBin + fftBinsPerPlotBin - 1, fft.binCount - 1);
				
				// map mouseY to a power bin
				int powerBinN = Math.round(((float) mouseY - yPlotBottom) / plotHeight * actualYaxisBins - 0.5f);
				if(powerBinN > actualYaxisBins - 1)
					powerBinN = actualYaxisBins - 1;
				float minPower = (float) powerBinN / (float) actualYaxisBins * (plotMaxPower - plotMinPower) + plotMinPower;
				float maxPower = (float) (powerBinN + 1) / (float) actualYaxisBins * (plotMaxPower - plotMinPower) + plotMinPower;
				
				// get the bin value for each dataset
				int[] windowCountForDataset = new int[datasetsCount];
				for(int datasetN = 0; datasetN < datasetsCount; datasetN++)
					windowCountForDataset[datasetN] = (int) Math.ceil((double) histogram[datasetN][powerBinN][histogramBinN] / (double) fftBinsPerPlotBin);
				int windowCount = fft.windows.size();
				List<TooltipEntry> entries = new ArrayList<TooltipEntry>();
				entries.add(new TooltipEntry(null, convertFrequencyRangeToString(firstFftBin, lastFftBin, fft)));
				entries.add(new TooltipEntry(null, convertPowerRangeToString(minPower, maxPower)));
				for(int datasetN = 0; datasetN < datasetsCount; datasetN++)
					entries.add(new TooltipEntry(datasets.getNormal(datasetN).glColor, ChartUtils.formattedNumber((double) windowCountForDataset[datasetN] / (double) windowCount * 100.0, 3) + "% (" + windowCountForDataset[datasetN] + " of " + windowCount + " FFTs)"));
				
				// draw the tooltip
				int anchorY = (int) (((float) powerBinN + 0.5f) / (float) actualYaxisBins * plotHeight + yPlotBottom);
				drawTooltip(gl, entries, anchorX, anchorY, xPlotLeft, yPlotTop, xPlotRight, yPlotBottom);
				
			} else if(chartStyle == ChartStyle.WATERFALL) {
				
				// map mouseX to a frequency bin, and anchor the tooltip over that frequency bin
				// note: one histogram bin corresponds to 1 or >1 FFT bins
				int histogramBinCount = (int) Math.ceil((double) fft.binCount / (double) fftBinsPerPlotBin);
				int histogramBinN = (int) (((float) mouseX - xPlotLeft) / plotWidth * (histogramBinCount - 1) + 0.5f);
				if(histogramBinN > histogramBinCount - 1)
					histogramBinN = histogramBinCount - 1;
				int anchorX = (int) (((float) histogramBinN / (float) (histogramBinCount - 1)) * plotWidth + xPlotLeft);
				int firstFftBin = histogramBinN * fftBinsPerPlotBin;
				int lastFftBin  = Math.min(firstFftBin + fftBinsPerPlotBin - 1, fft.binCount - 1);
				
				// map mouseY to a time
				int waterfallRowCount = fftCount;
				int waterfallRowN = Math.round(((float) mouseY - yPlotBottom) / plotHeight * waterfallRowCount - 0.5f);
				if(waterfallRowN > waterfallRowCount - 1)
					waterfallRowN = waterfallRowCount - 1;
				int trueLastSampleNumber = endSampleNumber - (endSampleNumber % fft.windowLength);
				int rowLastSampleNumber = trueLastSampleNumber - (waterfallRowN * fft.windowLength) - 1;
				int rowFirstSampleNumber = rowLastSampleNumber - fft.windowLength + 1;
				if(rowFirstSampleNumber >= 0) {
					// mouse is over an FFT, so proceed with the tooltip
					float secondsElapsed = ((float) waterfallRowN + 0.5f) / (float) waterfallRowCount * plotMaxTime;
					List<TooltipEntry> entries = new ArrayList<TooltipEntry>();
					entries.add(new TooltipEntry(null, convertFrequencyRangeToString(firstFftBin, lastFftBin, fft)));
					entries.add(new TooltipEntry(null, ChartUtils.formattedNumber(secondsElapsed, 4) + " seconds ago"));
					
					// get the power levels for each dataset
					for(int datasetN = 0; datasetN < datasetsCount; datasetN++) {
						float power = 0;
						for(int bin = firstFftBin; bin <= lastFftBin; bin++)
							power += fft.windows.get(fft.windows.size() - waterfallRowN - 1).get(datasetN)[bin];
						power /= lastFftBin - firstFftBin + 1;
						entries.add(new TooltipEntry(datasets.getNormal(datasetN).glColor, convertPowerToString(power)));
					}
					
					// draw the tooltip
					int anchorY = (int) (((float) waterfallRowN + 0.5f) / (float) waterfallRowCount * plotHeight + yPlotBottom);
					drawTooltip(gl, entries, anchorX, anchorY, xPlotLeft, yPlotTop, xPlotRight, yPlotBottom);
				}
			}
			
		}

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
		private List<Dataset> previousDatasets = new ArrayList<Dataset>();
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
				previousDatasets = new ArrayList<Dataset>(datasets.normalDatasets); // must *duplicate* the list so we can detect changes
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
