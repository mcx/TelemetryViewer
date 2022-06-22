import java.awt.Color;
import java.util.Map;

import javax.swing.Box;
import javax.swing.JPanel;
import com.jogamp.opengl.GL2ES3;
import com.jogamp.opengl.GL3;

public class OpenGLFrequencyDomainChart extends PositionedChart {
	
	OpenGLFrequencyDomainCache cache;
	
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
	
	// dft info
	String dftWindowLengthText;
	float yDftWindowLengthTextBaseline;
	float xDftWindowLenghtTextLeft;
	String dftWindowCountText;
	float yDftWindowCountTextBaseline;
	float xDftWindowCountTextLeft;
	String minPowerText;
	String maxPowerText;
	float yPowerTextBaseline;
	float yPowerTextTop;
	float xMaxPowerTextLeft;
	float xPowerScaleRight;
	float xPowerScaleLeft;
	float xMinPowerTextLeft;
	float xDftInfoTextLeft;
	
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
	
	// user settings
	private WidgetDatasetCheckboxes datasetsWidget;
	
	private WidgetTextfieldInt sampleCountTextfield;

	private boolean legendVisible = true;
	private WidgetCheckbox legendCheckbox;
	
	private enum ChartStyle {
		SINGLE    { @Override public String toString() { return "Single";    } },
		MULTIPLE  { @Override public String toString() { return "Multiple";  } },
		WATERFALL { @Override public String toString() { return "Waterfall"; } };
	};
	private ChartStyle chartStyle = ChartStyle.SINGLE;
	private WidgetToggleButtonEnum<ChartStyle> chartStyleButtons;
	
	private int fftCount = 20;
	private WidgetTextfieldInt fftCountTextfield;
	
	private int waveformRowCount = 60;
	private WidgetTextfieldInt waveformRowCountTextfield;
	
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
		                                              1048576,
		                                              ConnectionsController.getDefaultChartDuration(),
		                                              newDuration -> duration = newDuration);
		
		legendCheckbox = new WidgetCheckbox("Show Legend",
		                                    legendVisible,
		                                    newVisibility -> legendVisible = newVisibility);
		
		fftCountTextfield = new WidgetTextfieldInt("",
		                                           "fft count",
		                                           "FFTs",
		                                           2,
		                                           100,
		                                           fftCount,
		                                           newCount -> fftCount = newCount);
		
		waveformRowCountTextfield = new WidgetTextfieldInt("",
		                                                   "fft row count",
		                                                   "Rows",
		                                                   2,
		                                                   1000,
		                                                   waveformRowCount,
		                                                   newCount -> waveformRowCount = newCount);
		
		chartStyleButtons = new WidgetToggleButtonEnum<ChartStyle>("Style",
		                                                           "fft style",
		                                                           ChartStyle.values(),
		                                                           chartStyle,
		                                                           newStyle -> {
		                                                               chartStyle = newStyle;
		                                                               fftCountTextfield.setVisible(chartStyle != ChartStyle.SINGLE);
		                                                               waveformRowCountTextfield.setVisible(chartStyle == ChartStyle.MULTIPLE);
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
		widgets.add(waveformRowCountTextfield);
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
		fftPanel.add(waveformRowCountTextfield, "grow x, grow y");
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
		boolean haveTelemetry = haveDatasets && endSampleNumber > 5;
		
		// calculate the DFTs
		if(cache == null)
			cache = new OpenGLFrequencyDomainCache();
		cache.calculateDfts(endSampleNumber, duration, fftCount, datasets, chartStyle.toString());
		
		// calculate the domain
		float plotMinX = cache.getMinHz();
		float plotMaxX = cache.getMaxHz();
		float domain = plotMaxX - plotMinX;
		
		// calculate the range and ensure it's >0
		// for "Waterfall View" the y-axis is time
		// for "Live View" and "Waveform View" the y-axis is power
		float sampleRate = haveDatasets ? datasets.connection.getSampleRate() : 1;
		float plotMinTime = 0;
		float plotMaxTime = (float) (duration * fftCount) / sampleRate;

		float plotMinPower = haveTelemetry ? cache.getMinPower() : -12;
		float plotMaxPower = haveTelemetry ? cache.getMaxPower() : 1;
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
				
				dftWindowLengthText = cache.getWindowLength() + " sample rectangular window";
				yDftWindowLengthTextBaseline = Theme.tilePadding;
				xDftWindowLenghtTextLeft = width - Theme.tilePadding - OpenGL.smallTextWidth(gl, dftWindowLengthText);
				
				xDftInfoTextLeft = xDftWindowLenghtTextLeft;
				
				float temp = yDftWindowLengthTextBaseline + OpenGL.smallTextHeight + Theme.tickTextPadding;
				if(yPlotBottom < temp) {
					yPlotBottom = temp;
					plotHeight = yPlotTop - yPlotBottom;
				}
				
			} else if(chartStyle == ChartStyle.MULTIPLE) {
				
				int windowCount = cache.getActualWindowCount();
				int windowLength = cache.getWindowLength();
				int trueTotalSampleCount = windowCount * windowLength;
				dftWindowCountText = windowCount + " windows (total of " + trueTotalSampleCount + " samples)";
				yDftWindowCountTextBaseline = Theme.tilePadding;
				xDftWindowCountTextLeft = width - Theme.tilePadding - OpenGL.smallTextWidth(gl, dftWindowCountText);
				
				dftWindowLengthText = windowLength + " sample rectangular window";
				yDftWindowLengthTextBaseline = yDftWindowCountTextBaseline + OpenGL.smallTextHeight + Theme.tickTextPadding;
				xDftWindowLenghtTextLeft = width - Theme.tilePadding - OpenGL.smallTextWidth(gl, dftWindowLengthText);
				
				xDftInfoTextLeft = Float.min(xDftWindowCountTextLeft, xDftWindowLenghtTextLeft);
				
				float temp = yDftWindowLengthTextBaseline + OpenGL.smallTextHeight + Theme.tickTextPadding;
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
				
				int windowCount = cache.getActualWindowCount();
				int windowLength = cache.getWindowLength();
				int trueTotalSampleCount = windowCount * windowLength;
				dftWindowCountText = windowCount + " windows (total of " + trueTotalSampleCount + " samples)";
				yDftWindowCountTextBaseline = yPowerTextTop + Theme.tickTextPadding;
				xDftWindowCountTextLeft = width - Theme.tilePadding - OpenGL.smallTextWidth(gl, dftWindowCountText);
				
				dftWindowLengthText = windowLength + " sample rectangular window";
				yDftWindowLengthTextBaseline = yDftWindowCountTextBaseline + OpenGL.smallTextHeight + Theme.tickTextPadding;
				xDftWindowLenghtTextLeft = width - Theme.tilePadding - OpenGL.smallTextWidth(gl, dftWindowLengthText);
				
				xDftInfoTextLeft = Float.min(xDftWindowCountTextLeft, xDftWindowLenghtTextLeft);
				xDftInfoTextLeft = Float.min(xMinPowerTextLeft, xDftInfoTextLeft);
				
				float temp = yDftWindowLengthTextBaseline + OpenGL.smallTextHeight + Theme.tickTextPadding;
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
				xXaxisTitleTextLeft = xLegendBorderRight + ((xDftInfoTextLeft - xLegendBorderRight) / 2.0f) - (OpenGL.largeTextWidth(gl, xAxisTitle)  / 2.0f);
			else if(legendVisible)
				xXaxisTitleTextLeft = xLegendBorderRight + ((width - Theme.tilePadding - xLegendBorderRight)  / 2.0f) - (OpenGL.largeTextWidth(gl, xAxisTitle)  / 2.0f);
			else if(fftInfoVisible)
				xXaxisTitleTextLeft = xPlotLeft + ((xDftInfoTextLeft - xPlotLeft) / 2.0f) - (OpenGL.largeTextWidth(gl, xAxisTitle)  / 2.0f);
			
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
				xXaxisTitleTextLeft = xLegendBorderRight + ((xDftInfoTextLeft - xLegendBorderRight) / 2.0f) - (OpenGL.largeTextWidth(gl, xAxisTitle)  / 2.0f);
			else if(xAxisTitleVisible && legendVisible)
				xXaxisTitleTextLeft = xLegendBorderRight + ((width - Theme.tilePadding - xLegendBorderRight)  / 2.0f) - (OpenGL.largeTextWidth(gl, xAxisTitle)  / 2.0f);
			else if(xAxisTitleVisible && fftInfoVisible)
				xXaxisTitleTextLeft = xPlotLeft + ((xDftInfoTextLeft - xPlotLeft) / 2.0f) - (OpenGL.largeTextWidth(gl, xAxisTitle)  / 2.0f);
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
		
		// draw the DFT info text if space is available
		boolean spaceForDftInfoText = legendVisible ? xDftInfoTextLeft > xLegendBorderRight + Theme.legendTextPadding : xDftInfoTextLeft > 0;
		if(fftInfoVisible && spaceForDftInfoText && haveDatasets) {
			if(chartStyle == ChartStyle.SINGLE) {
				
				OpenGL.drawSmallText(gl, dftWindowLengthText, (int) xDftWindowLenghtTextLeft, (int) yDftWindowLengthTextBaseline, 0);
				
			} else if(chartStyle == ChartStyle.MULTIPLE) {
				
				OpenGL.drawSmallText(gl, dftWindowLengthText, (int) xDftWindowLenghtTextLeft, (int) yDftWindowLengthTextBaseline, 0);
				OpenGL.drawSmallText(gl, dftWindowCountText, (int) xDftWindowCountTextLeft, (int) yDftWindowCountTextBaseline, 0);
				
			} else if(chartStyle == ChartStyle.WATERFALL) {
				
				OpenGL.drawSmallText(gl, dftWindowLengthText, (int) xDftWindowLenghtTextLeft, (int) yDftWindowLengthTextBaseline, 0);
				OpenGL.drawSmallText(gl, dftWindowCountText, (int) xDftWindowCountTextLeft, (int) yDftWindowCountTextBaseline, 0);
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
		
		
		// draw the DFTs
		if(haveTelemetry) {
			if(chartStyle == ChartStyle.SINGLE)
				cache.renderSingle(chartMatrix, (int) xPlotLeft, (int) yPlotBottom, (int) plotWidth, (int) plotHeight, plotMinPower, plotMaxPower, gl, datasets.normalDatasets);
			else if(chartStyle == ChartStyle.MULTIPLE)
				cache.renderMultiple(chartMatrix, (int) xPlotLeft, (int) yPlotBottom, (int) plotWidth, (int) plotHeight, plotMinPower, plotMaxPower, gl, datasets.normalDatasets, waveformRowCount);
			else if(chartStyle == ChartStyle.WATERFALL)
				cache.renderWaterfall(chartMatrix, (int) xPlotLeft, (int) yPlotBottom, (int) plotWidth, (int) plotHeight, plotMinPower, plotMaxPower, gl, datasets.normalDatasets);
		}
		
		// draw the tooltip if the mouse is in the plot region
		if(haveDatasets && SettingsController.getTooltipVisibility() && mouseX >= xPlotLeft && mouseX <= xPlotRight && mouseY >= yPlotBottom && mouseY <= yPlotTop) {
			// map mouseX to a frequency
			double binSizeHz = cache.getBinSizeHz();
			int binCount = cache.getBinCount();
			int binN = (int) (((float) mouseX - xPlotLeft) / plotWidth * (binCount - 1) + 0.5f);
			if(binN > binCount - 1)
				binN = binCount - 1;
			float frequency = (float) (binN * binSizeHz);
			float anchorX = (frequency - plotMinX) / domain * plotWidth + xPlotLeft;
			
			String[] text = null;
			Color[] colors = null;
			int anchorY = 0;
			
			if(chartStyle == ChartStyle.SINGLE) {
				// for live view, get the power levels (one per dataset) for the mouseX frequency
				float[] binValues = cache.getPowerLevelsForLiveViewBin(binN);
				if(binValues != null) {
					text = new String[datasetsCount + 1];
					colors = new Color[datasetsCount + 1];
					text[0] = (int) frequency + " Hz";
					colors[0] = new Color(Theme.tooltipBackgroundColor[0], Theme.tooltipBackgroundColor[1], Theme.tooltipBackgroundColor[2], Theme.tooltipBackgroundColor[3]);
					for(int i = 0; i < datasetsCount; i++) {
						text[i + 1] = "1e" + ChartUtils.formattedNumber(binValues[i], 4) + " Watts";
						colors[i + 1] = datasets.getNormal(i).color;
					}
					anchorY = (int) ((binValues[0] - plotMinY) / plotRange * plotHeight + yPlotBottom);
				}
			} else if(chartStyle == ChartStyle.MULTIPLE) {
				// map mouseY to a power bin
				int powerBinN = Math.round(((float) mouseY - yPlotBottom) / plotHeight * waveformRowCount - 0.5f);
				if(powerBinN > waveformRowCount - 1)
					powerBinN = waveformRowCount - 1;
				float minPower = (float) powerBinN / (float) waveformRowCount * (plotMaxPower - plotMinPower) + plotMinPower;
				float maxPower = (float) (powerBinN + 1) / (float) waveformRowCount * (plotMaxPower - plotMinPower) + plotMinPower;
				// for waveform view, get the percentages (one per dataset) for the mouseX frequency and mouseY power range
				int[] waveformCounts = cache.getWaveformCountsForBin(binN, powerBinN);
				if(waveformCounts != null) {
					int windowCount = cache.getActualWindowCount();
					text = new String[datasetsCount + 1];
					colors = new Color[datasetsCount + 1];
					text[0] = (int) frequency + " Hz, 1e" + ChartUtils.formattedNumber(minPower, 4) + " to 1e" + ChartUtils.formattedNumber(maxPower, 4) + " Watts";
					colors[0] = new Color(Theme.tooltipBackgroundColor[0], Theme.tooltipBackgroundColor[1], Theme.tooltipBackgroundColor[2], Theme.tooltipBackgroundColor[3]);
					for(int i = 0; i < datasetsCount; i++) {
						text[i + 1] = waveformCounts[i] + " of " + windowCount + " DFTs (" + ChartUtils.formattedNumber((double) waveformCounts[i] / (double) windowCount * 100.0, 4) + "%)";
						colors[i + 1] = datasets.getNormal(i).color;
					}
					anchorY = (int) (((float) powerBinN + 0.5f) / (float) waveformRowCount * plotHeight + yPlotBottom);
				}
			} else if(chartStyle == ChartStyle.WATERFALL) {
				// map mouseY to a time
				int waterfallRowCount = fftCount;
				int waterfallRowN = Math.round(((float) mouseY - yPlotBottom) / plotHeight * waterfallRowCount - 0.5f);
				if(waterfallRowN > waterfallRowCount - 1)
					waterfallRowN = waterfallRowCount - 1;
				int windowLength = cache.getWindowLength();
				int trueLastSampleNumber = endSampleNumber - (endSampleNumber % windowLength);
				int rowLastSampleNumber = trueLastSampleNumber - (waterfallRowN * windowLength) - 1;
				int rowFirstSampleNumber = rowLastSampleNumber - windowLength + 1;
				if(rowFirstSampleNumber >= 0) {
					// for waterfall view, get the power levels (one per dataset) for the mouseX frequency and mouseY time
					float[] binValues = cache.getWaterfallPowerLevelsForBin(binN, waterfallRowN);
					if(binValues != null) {
						text = new String[datasetsCount + 2];
						colors = new Color[datasetsCount + 2];
						float secondsElapsed = ((float) waterfallRowN + 0.5f) / (float) waterfallRowCount * plotMaxTime;
						text[0] = (int) frequency + " Hz, " + ChartUtils.formattedNumber(secondsElapsed, 4) + " Seconds Ago";
						colors[0] = new Color(Theme.tooltipBackgroundColor[0], Theme.tooltipBackgroundColor[1], Theme.tooltipBackgroundColor[2], Theme.tooltipBackgroundColor[3]);
						text[1] = "(Samples " + rowFirstSampleNumber + " to " + rowLastSampleNumber + ")";
						colors[1] = new Color(Theme.tooltipBackgroundColor[0], Theme.tooltipBackgroundColor[1], Theme.tooltipBackgroundColor[2], Theme.tooltipBackgroundColor[3]);
						for(int i = 0; i < datasetsCount; i++) {
							text[i + 2] = "1e" + ChartUtils.formattedNumber(binValues[i], 4) + " Watts";
							colors[i + 2] = datasets.getNormal(i).color;
						}
						anchorY = (int) (((float) waterfallRowN + 0.5f) / (float) waterfallRowCount * plotHeight + yPlotBottom);
					}
				}
			}

			if(text != null && colors != null) {
				if(datasetsCount > 1 && chartStyle == ChartStyle.SINGLE) {
					OpenGL.buffer.rewind();
					OpenGL.buffer.put(anchorX); OpenGL.buffer.put(yPlotTop);
					OpenGL.buffer.put(anchorX); OpenGL.buffer.put(yPlotBottom);
					OpenGL.buffer.rewind();
					OpenGL.drawLinesXy(gl, GL3.GL_LINES, Theme.tooltipVerticalBarColor, OpenGL.buffer, 2);
					ChartUtils.drawTooltip(gl, text, colors, (int) anchorX, mouseY, xPlotLeft, yPlotTop, xPlotRight, yPlotBottom);
				} else {
					ChartUtils.drawTooltip(gl, text, colors, (int) anchorX, anchorY, xPlotLeft, yPlotTop, xPlotRight, yPlotBottom);
				}
			}
		}

		// draw the plot border
		OpenGL.drawQuadOutline2D(gl, Theme.plotOutlineColor, xPlotLeft, yPlotBottom, xPlotRight, yPlotTop);
		
		return handler;
		
	}
	
	@Override public void disposeGpu(GL2ES3 gl) {
		
		super.disposeGpu(gl);
		if(cache != null)
			cache.freeResources(gl);
		cache = null;
		
	}

}
