import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import com.jogamp.opengl.GL2ES3;
import com.jogamp.opengl.GL3;

public class OpenGLPlot {
	
	enum AxisStyle {
		HIDDEN { @Override public String toString() { return "Hidden"; } },
		INNER  { @Override public String toString() { return "Inner";  } },
		OUTER  { @Override public String toString() { return "Outer";  } },
		TITLED { @Override public String toString() { return "Titled"; } }
	}
	enum AxisScale {LINEAR, LOG}
	record PlotDetails(int mouseX, int mouseY, int width, int height, int xLeft, int yBottom, float[] matrix, EventHandler existingHandler) {}
	
	final float[] chartMatrix;
	final int chartWidth;
	final int chartHeight;
	int mouseX;
	int mouseY;
	boolean drawLegend;
	DatasetsInterface datasets;
	boolean drawFftInfo;
	OpenGLFrequencyDomainChart.ChartStyle fftChartStyle;
	int fftWindowLength;
	int fftWindowCount;
	float fftMinPower;
	float fftMaxPower;
	boolean xAxisIsFloats;
	AxisStyle xAxisStyle = AxisStyle.HIDDEN;
	AxisScale xAxisType;
	String xAxisTitle;
	float xAxisMinFloat;
	float xAxisMaxFloat;
	long  xAxisMinLong;
	long  xAxisMaxLong;
	AxisStyle yAxisStyle = AxisStyle.HIDDEN;
	AxisScale yAxisType; // note: this will be null for the timeline chart
	String yAxisTitle;
	float yAxisMin;
	float yAxisMax;
	boolean convertToPercentage;
	int sampleCount;
	Function<PlotDetails, EventHandler> plotDrawer;
	Function<PlotDetails, EventHandler> tooltipDrawer;
	
	public OpenGLPlot(float[] chartMatrix, int chartWidth, int chartHeight, int mouseX, int mouseY) {
		this.chartMatrix = chartMatrix;
		this.chartWidth = chartWidth;
		this.chartHeight = chartHeight;
		this.mouseX = mouseX;
		this.mouseY = mouseY;
	}
	
	public OpenGLPlot withLegend(boolean drawLegend, DatasetsInterface datasets) {
		this.drawLegend = drawLegend;
		this.datasets = datasets;
		return this;
	}
	
	public OpenGLPlot withFftInfo(boolean drawFftInfo, OpenGLFrequencyDomainChart.ChartStyle chartStyle, int fftWindowLength, int fftWindowCount, float minPower, float maxPower) {
		this.drawFftInfo = drawFftInfo;
		this.fftChartStyle = chartStyle;
		this.fftWindowLength = fftWindowLength;
		this.fftWindowCount = fftWindowCount;
		this.fftMinPower = minPower;
		this.fftMaxPower = maxPower;
		return this;
	}
	
	public OpenGLPlot withXaxis(AxisStyle mode, AxisScale type, float min, float max, String title) {
		xAxisIsFloats = true;
		xAxisStyle = mode;
		xAxisType = type;
		xAxisTitle = title;
		xAxisMinFloat = min;
		xAxisMaxFloat = max;
		return this;
	}
	
	public OpenGLPlot withXaxis(AxisStyle mode, AxisScale type, long min, long max, String title) {
		xAxisIsFloats = false;
		xAxisStyle = mode;
		xAxisType = type;
		xAxisTitle = title;
		xAxisMinLong = min;
		xAxisMaxLong = max;
		return this;
	}
	
	public OpenGLPlot withYaxis(AxisStyle mode, AxisScale type, float min, float max, String title) {
		yAxisStyle = mode;
		yAxisType = type;
		yAxisTitle = title;
		yAxisMin = min;
		yAxisMax = max;
		return this;
	}
	
	public OpenGLPlot withYaxisConvertedToPercentage(boolean convert, int sampleCount) {
		this.convertToPercentage = convert;
		this.sampleCount = sampleCount;
		return this;
	}
	
	public OpenGLPlot withPlotDrawer(Function<PlotDetails, EventHandler> plotDrawer) {
		this.plotDrawer = plotDrawer;
		return this;
	}
	
	public OpenGLPlot withTooltipDrawer(Function<PlotDetails, EventHandler> tooltipDrawer) {
		this.tooltipDrawer = tooltipDrawer;
		return this;
	}
	
	public EventHandler draw(GL2ES3 gl) {
		
		EventHandler handler = null;
		
		// start assuming only the plot is drawn
		// if this is a timeline chart, don't use top or bottom padding
		float xPlotLeft = Theme.tilePadding;
		float xPlotRight = chartWidth - Theme.tilePadding;
		float yPlotTop = (yAxisType == null) ? chartHeight : chartHeight - Theme.tilePadding;
		float yPlotBottom = (yAxisType == null && xAxisStyle == AxisStyle.HIDDEN) ? 0 : Theme.tilePadding;
		
		// if the legend should be drawn, draw it if there's space, and adjust yPlotBottom accordingly
		float xLegendBorderRight = xPlotLeft;
		if(drawLegend && datasets.hasAnyType()) {
			List<Field> allDatasets = Stream.of(datasets.normalDatasets.stream(),
			                                    datasets.edgeStates.stream().map(state -> state.dataset),
			                                    datasets.levelStates.stream().map(state -> state.dataset))
			                                .flatMap(stream -> stream)
			                                .distinct()
			                                .toList();
			int datasetsCount = allDatasets.size();
			
			float xLegendBorderLeft = Theme.tilePadding;
			float yLegendBorderBottom = Theme.tilePadding;
			float yLegendTextBaseline = yLegendBorderBottom + Theme.legendTextPadding;
			float yLegendTextTop = yLegendTextBaseline + OpenGL.mediumTextHeight;
			float yLegendBorderTop = yLegendTextTop + Theme.legendTextPadding;
			float[][] legendMouseoverCoordinates = new float[datasetsCount][4];
			float[][] legendBoxCoordinates = new float[datasetsCount][4];
			float[] xLegendNameLeft = new float[datasetsCount];
			
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
			yPlotBottom = Float.max(yPlotBottom, yLegendBorderTop + Theme.legendTextPadding);
			
			if(xLegendBorderRight < chartWidth - Theme.tilePadding) {
				OpenGL.drawQuad2D(gl, Theme.legendBackgroundColor, xLegendBorderLeft, yLegendBorderBottom, xLegendBorderRight, yLegendBorderTop);
				
				for(int i = 0; i < datasetsCount; i++) {
					Field d = allDatasets.get(i);
					if(mouseX >= legendMouseoverCoordinates[i][0] && mouseX <= legendMouseoverCoordinates[i][2] && mouseY >= legendMouseoverCoordinates[i][1] && mouseY <= legendMouseoverCoordinates[i][3]) {
						OpenGL.drawQuadOutline2D(gl, Theme.tickLinesColor, legendMouseoverCoordinates[i][0], legendMouseoverCoordinates[i][1], legendMouseoverCoordinates[i][2], legendMouseoverCoordinates[i][3]);
						handler = EventHandler.onPress(event -> Configure.GUI.forDataset(d));
					}
					OpenGL.drawQuad2D(gl, d.color.getGl(), legendBoxCoordinates[i][0], legendBoxCoordinates[i][1], legendBoxCoordinates[i][2], legendBoxCoordinates[i][3]);
					OpenGL.drawMediumText(gl, d.name.get(), (int) xLegendNameLeft[i], (int) yLegendTextBaseline, 0);
				}
			}
		}
		
		// if FFT info should be drawn, draw it if there's space, and adjust yPlotBottom accordingly
		float xFftInfoAreaLeft = xPlotRight;
		if(drawFftInfo) {
			if(fftChartStyle == OpenGLFrequencyDomainChart.ChartStyle.SINGLE) {
				String text = fftWindowLength + " sample rectangular window";
				float yText = Theme.tilePadding;
				float xText = chartWidth - Theme.tilePadding - OpenGL.smallTextWidth(gl, text);
				
				xFftInfoAreaLeft = xText - Theme.tilePadding;
				yPlotBottom = Float.max(yPlotBottom, yText + OpenGL.smallTextHeight + Theme.tickTextPadding);
				
				if(xFftInfoAreaLeft > xLegendBorderRight)
					OpenGL.drawSmallText(gl, text, (int) xText, (int) yText, 0);
			} else if(fftChartStyle == OpenGLFrequencyDomainChart.ChartStyle.HISTOGRAM) {
				String text1 = fftWindowCount + " windows (total of " + (fftWindowCount * fftWindowLength) + " samples)";
				float yText1 = Theme.tilePadding;
				float xText1 = chartWidth - Theme.tilePadding - OpenGL.smallTextWidth(gl, text1);
				
				String text2 = fftWindowLength + " sample rectangular windows";
				float yText2 = yText1 + OpenGL.smallTextHeight + Theme.tickTextPadding;
				float xText2 = chartWidth - Theme.tilePadding - OpenGL.smallTextWidth(gl, text2);
				
				xFftInfoAreaLeft = Float.min(xText1 - Theme.padding, xText2 - Theme.padding);
				yPlotBottom = Float.max(yPlotBottom, yText2 + OpenGL.smallTextHeight + Theme.tickTextPadding);
				
				if(xFftInfoAreaLeft > xLegendBorderRight) {
					OpenGL.drawSmallText(gl, text1, (int) xText1, (int) yText1, 0);
					OpenGL.drawSmallText(gl, text2, (int) xText2, (int) yText2, 0);
				}
			} else if(fftChartStyle == OpenGLFrequencyDomainChart.ChartStyle.WATERFALL) {
				String text1a = Theme.getLog10float(fftMinPower, "Watts");
				String text1b = Theme.getLog10float(fftMaxPower, "Watts");
				float yText1 = Theme.tilePadding;
				float xText1b = chartWidth - Theme.tilePadding - OpenGL.smallTextWidth(gl, text1b);
				float xPowerScaleRight = xText1b - Theme.tickTextPadding;
				float xPowerScaleLeft  = xPowerScaleRight - (100 * Settings.GUI.getChartScalingFactor());
				float xText1a = xPowerScaleLeft - Theme.tickTextPadding - OpenGL.smallTextWidth(gl, text1a);
				float yText1Top = yText1 + OpenGL.smallTextHeight;
				
				String text2 = fftWindowCount + " windows (total of " + (fftWindowCount * fftWindowLength) + " samples)";
				float yText2 = yText1Top + Theme.tickTextPadding;
				float xText2 = chartWidth - Theme.tilePadding - OpenGL.smallTextWidth(gl, text2);
				
				String text3 = fftWindowLength + " sample rectangular windows";
				float yText3 = yText2 + OpenGL.smallTextHeight + Theme.tickTextPadding;
				float xText3 = chartWidth - Theme.tilePadding - OpenGL.smallTextWidth(gl, text3);
				
				xFftInfoAreaLeft = Float.min(xText1a, xText2);
				xFftInfoAreaLeft = Float.min(xText3, xFftInfoAreaLeft);
				yPlotBottom = Float.max(yPlotBottom, yText3 + OpenGL.smallTextHeight + Theme.tickTextPadding);
				
				OpenGL.drawSmallText(gl, text1a, (int) xText1a, (int) yText1, 0);
				OpenGL.drawSmallText(gl, text1b, (int) xText1b, (int) yText1, 0);
				OpenGL.drawSmallText(gl, text2,  (int) xText2,  (int) yText2, 0);
				OpenGL.drawSmallText(gl, text3,  (int) xText3,  (int) yText3, 0);
				OpenGL.drawQuad2D(gl, Theme.plotBackgroundColor, xPowerScaleLeft, yText1, xPowerScaleRight, yText1Top);
				int datasetsCount = datasets.normalsCount();
				for(int datasetN = 0; datasetN < datasetsCount; datasetN++) {
					Field dataset = datasets.getNormal(datasetN);
					float top = yText1Top - (yText1Top - yText1) * datasetN / datasetsCount;
					float bottom = top - (yText1Top - yText1) / datasetsCount;
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
				OpenGL.drawQuadOutline2D(gl, Theme.legendBackgroundColor, xPowerScaleLeft, yText1, xPowerScaleRight, yText1Top);
			}
		}
		
		// if the x-axis title should be drawn, draw it if there's space, and adjust yPlotBottom accordingly
		if(xAxisStyle == AxisStyle.TITLED && xAxisTitle != null && !xAxisTitle.isEmpty()) {
			float yText = Math.max(yPlotBottom - Theme.padding - OpenGL.largeTextHeight, Theme.padding); // legend or fft info may have raised the plot
			float xText = xLegendBorderRight + ((xFftInfoAreaLeft - xLegendBorderRight) / 2) - (OpenGL.largeTextWidth(gl, xAxisTitle) / 2);
			
			yPlotBottom = Float.max(yPlotBottom, yText + OpenGL.largeTextHeight + Theme.padding);
			
			if(xFftInfoAreaLeft - xLegendBorderRight >= OpenGL.largeTextWidth(gl, xAxisTitle))
				OpenGL.drawLargeText(gl, xAxisTitle, (int) xText, (int) yText, 0);
		}
		
		// if the x-axis ticks should be drawn below the plot, reserve space for them, and adjust yPlotBottom accordingly
		boolean xAxisTicksTwoLines = xAxisTitle.equals("Time") && Settings.isTimeFormatTwoLines();
		if(xAxisStyle == AxisStyle.TITLED || xAxisStyle == AxisStyle.OUTER)
			yPlotBottom += xAxisTicksTwoLines ? Theme.tickLength + Theme.tickTextPadding + 2.3f*OpenGL.smallTextHeight:
			                                    Theme.tickLength + Theme.tickTextPadding +      OpenGL.smallTextHeight;
		
		// the plot height is now constrained
		yPlotTop    = Math.round(yPlotTop);
		yPlotBottom = Math.round(yPlotBottom);
		float plotHeight = yPlotTop - yPlotBottom;
		
		// if the y-axis title should be drawn, draw it if there's space, and adjust xPlotLeft accordingly
		if(yAxisStyle == AxisStyle.TITLED && yAxisTitle != null && !yAxisTitle.isEmpty()) {
			float xText = xPlotLeft + OpenGL.largeTextHeight;
			float yText = yPlotBottom + (plotHeight / 2.0f) - (OpenGL.largeTextWidth(gl, yAxisTitle) / 2.0f);
			
			xPlotLeft = xText + Theme.tickTextPadding;
			
			if(yText > yPlotBottom)
				OpenGL.drawLargeText(gl, yAxisTitle, (int) xText, (int) yText, 90);
		}
		
		// if the y-axis divisions should be drawn, get them, reserve space for them, and adjust xPlotLeft accordingly
		Map<Float, String> yDivisions = null;
		if(yAxisStyle != AxisStyle.HIDDEN)
			yDivisions = (yAxisType == AxisScale.LINEAR) ? getYdivisions125(plotHeight, yAxisMin, yAxisMax, yAxisTitle) :
			                                               getLogYdivisions(plotHeight, yAxisMin, yAxisMax, yAxisTitle);
		if(yAxisStyle == AxisStyle.TITLED || yAxisStyle == AxisStyle.OUTER) {
			float maxTextWidthLeft = (float) yDivisions.values().stream().mapToDouble(text -> OpenGL.smallTextWidth(gl, text)).max().orElse(0);
			xPlotLeft = xPlotLeft + maxTextWidthLeft + Theme.tickTextPadding + Theme.tickLength;
		}
		
		// the plot width is now constrained
		xPlotLeft = Math.round(xPlotLeft);
		xPlotRight = Math.round(xPlotRight);
		float plotWidth = xPlotRight - xPlotLeft;
		
		// stop if the plot is too small to draw
		if(plotWidth < 1 || plotHeight < 1)
			return handler;
		
		// draw the plot background if this isn't a timeline chart
		if(yAxisType != null)
			OpenGL.drawQuad2D(gl, Theme.plotBackgroundColor, xPlotLeft, yPlotBottom, xPlotRight, yPlotTop);
		
		// if the x-axis divisions should be drawn, get them and draw them
		Map<Float, String> xDivisions = null;
		if(xAxisStyle != AxisStyle.HIDDEN) {
			if(xAxisIsFloats) {
				xDivisions = getFloatXdivisions125(gl, plotWidth, xAxisMinFloat, xAxisMaxFloat, xAxisTitle);
			} else if(xAxisTitle.equals("Sample Number")) {
				xDivisions = new HashMap<Float, String>();
				Map<Integer, String> mapOfSampleNumbers = getIntegerXdivisions125(gl, plotWidth, (int) xAxisMinLong, (int) xAxisMaxLong);
				for(Map.Entry<Integer, String> entry : mapOfSampleNumbers.entrySet()) {
					float pixel = (float) (entry.getKey() - xAxisMinLong) / (float) (xAxisMaxLong - xAxisMinLong) * plotWidth;
					String text = entry.getValue();
					xDivisions.put(pixel, text);
				}
				xAxisMinFloat = 0;
				xAxisMaxFloat = plotWidth;
			} else if(xAxisTitle.equals("Time")) {
				xDivisions = getTimestampDivisions(gl, plotWidth, xAxisMinLong, xAxisMaxLong);
				xAxisMinFloat = 0;
				xAxisMaxFloat = plotWidth;
			}
			if(yAxisType != null) {
				float domain = xAxisMaxFloat - xAxisMinFloat;
				int vertexCount = 0;
				OpenGL.buffer.rewind();
				for(Float xValue : xDivisions.keySet()) {
					float pixelX = ((xValue - xAxisMinFloat) / domain * plotWidth) + xPlotLeft;
					if(pixelX < xPlotLeft)
						continue;
					OpenGL.buffer.put(pixelX); OpenGL.buffer.put(yPlotTop);
					OpenGL.buffer.put(pixelX); OpenGL.buffer.put(yPlotBottom);
					vertexCount += 2;
				}
				OpenGL.drawLinesXy(gl, GL3.GL_LINES, Theme.divisionLinesColor, OpenGL.buffer.rewind(), vertexCount);
			}
		}
		
		// if the y-axis divisions should be drawn, draw them
		if(yAxisStyle != AxisStyle.HIDDEN) {
			float range = yAxisMax - yAxisMin;
			OpenGL.buffer.rewind();
			for(Float yValue : yDivisions.keySet()) {
				float y = (yValue - yAxisMin) / range * plotHeight + yPlotBottom;
				OpenGL.buffer.put(xPlotLeft);  OpenGL.buffer.put(y);
				OpenGL.buffer.put(xPlotRight); OpenGL.buffer.put(y);
			}
			OpenGL.drawLinesXy(gl, GL3.GL_LINES, Theme.divisionLinesColor, OpenGL.buffer.rewind(), yDivisions.size() * 2);
		}
		
		// clip to the plot region
		int[] chartScissorArgs = new int[4];
		gl.glGetIntegerv(GL3.GL_SCISSOR_BOX, chartScissorArgs, 0);
		if(yAxisType == null)
			gl.glScissor(chartScissorArgs[0] +               0, chartScissorArgs[1] + (int) yPlotBottom,      chartWidth, (int) plotHeight);
		else
			gl.glScissor(chartScissorArgs[0] + (int) xPlotLeft, chartScissorArgs[1] + (int) yPlotBottom, (int) plotWidth, (int) plotHeight);
		
		// update the matrix and mouse so the plot region starts at (0,0)
		// x = x + xPlotLeft;
		// y = y + yPlotBottom;
		float[] plotMatrix = Arrays.copyOf(chartMatrix, 16);
		OpenGL.translateMatrix(plotMatrix, xPlotLeft, yPlotBottom, 0);
		OpenGL.useMatrix(gl, plotMatrix);
		mouseX -= xPlotLeft;
		mouseY -= yPlotBottom;
		
		// let the calling code draw the plot
		PlotDetails details = new PlotDetails(mouseX, mouseY, (int) plotWidth, (int) plotHeight, (int) xPlotLeft, (int) yPlotBottom, plotMatrix, null);
		if(plotDrawer != null) {
			EventHandler h = plotDrawer.apply(details);
			if(h != null)
				handler = h;
		}
		
		// stop clipping to the plot region
		gl.glScissor(chartScissorArgs[0], chartScissorArgs[1], chartScissorArgs[2], chartScissorArgs[3]);
		
		// switch back to the chart matrix
		OpenGL.useMatrix(gl, chartMatrix);
		
		// if the y-axis ticks should be drawn, draw them
		float occupiedMaxPixelX = -Float.MAX_VALUE;
		if(yAxisStyle != AxisStyle.HIDDEN) {
			float range = yAxisMax - yAxisMin;
			float xTickA = xPlotLeft;
			float xTickB = (yAxisStyle == AxisStyle.TITLED || yAxisStyle == AxisStyle.OUTER) ? xPlotLeft - Theme.tickLength :
			                                                                                   xPlotLeft + 2*OpenGL.smallTextHeight;
			OpenGL.buffer.rewind();
			for(Float yValue : yDivisions.keySet()) {
				float y = (yValue - yAxisMin) / range * plotHeight + yPlotBottom;
				OpenGL.buffer.put(xTickA); OpenGL.buffer.put(y); OpenGL.buffer.put(Theme.tickLinesColor);
				OpenGL.buffer.put(xTickB); OpenGL.buffer.put(y); OpenGL.buffer.put(Theme.tickLinesColor, 0, 3); OpenGL.buffer.put(yAxisStyle == AxisStyle.INNER ? 0 : 1);
			}
			OpenGL.drawLinesXyrgba(gl, GL3.GL_LINES, OpenGL.buffer.rewind(), yDivisions.size() * 2);
			
			if(yAxisStyle == AxisStyle.INNER)
				gl.glScissor(chartScissorArgs[0] + (int) xPlotLeft, chartScissorArgs[1] + (int) yPlotBottom, (int) plotWidth, (int) plotHeight);
			
			for(Map.Entry<Float,String> entry : yDivisions.entrySet()) {
				float yValue = entry.getKey();
				String text  = entry.getValue();
				float x = (yAxisStyle == AxisStyle.TITLED || yAxisStyle == AxisStyle.OUTER) ? xTickB - Theme.tickTextPadding - OpenGL.smallTextWidth(gl, text) :
				                                                                              xPlotLeft + 2*Theme.lineWidth;
				float y = (yAxisStyle == AxisStyle.TITLED || yAxisStyle == AxisStyle.OUTER) ? (yValue - yAxisMin) / range * plotHeight + yPlotBottom - (OpenGL.smallTextHeight / 2.0f) :
				                                                                              (yValue - yAxisMin) / range * plotHeight + yPlotBottom + 2*Theme.lineWidth;
				OpenGL.drawSmallText(gl, text, (int) x, (int) y, 0);
				float yXaxisTickTextTop = xAxisTicksTwoLines ? yPlotBottom + 2*Theme.lineWidth + 2.3f*OpenGL.smallTextHeight :
				                                               yPlotBottom + 2*Theme.lineWidth +      OpenGL.smallTextHeight;
				if(yAxisStyle == AxisStyle.INNER && xAxisStyle == AxisStyle.INNER && y - 2*Theme.lineWidth <= yXaxisTickTextTop)
					occupiedMaxPixelX = x + OpenGL.smallTextWidth(gl, text);
			}
			
			if(yAxisStyle == AxisStyle.INNER)
				gl.glScissor(chartScissorArgs[0], chartScissorArgs[1], chartScissorArgs[2], chartScissorArgs[3]);
		}
		
		// if the x-axis ticks should be drawn, draw them
		if(xAxisStyle != AxisStyle.HIDDEN) {
			float domain = xAxisMaxFloat - xAxisMinFloat;
			float yTickA = yPlotBottom;
			float yTickB = (xAxisStyle == AxisStyle.TITLED || xAxisStyle == AxisStyle.OUTER) ? yPlotBottom - Theme.tickLength :
			                                                                                   yPlotBottom + 2*OpenGL.smallTextHeight;
			float yText1 = (xAxisStyle == AxisStyle.TITLED || xAxisStyle == AxisStyle.OUTER) ? yPlotBottom - Theme.tickLength - Theme.tickTextPadding -      OpenGL.smallTextHeight :
			                                                                                   yPlotBottom + 2*Theme.lineWidth;
			float yText2 = (xAxisStyle == AxisStyle.TITLED || xAxisStyle == AxisStyle.OUTER) ? yPlotBottom - Theme.tickLength - Theme.tickTextPadding - 2.3f*OpenGL.smallTextHeight :
			                                                                                   yPlotBottom + 2*Theme.lineWidth + 1.3f*OpenGL.smallTextHeight;
			int vertexCount = 0;
			OpenGL.buffer.rewind();
			for(Float xValue : xDivisions.keySet()) {
				float x = ((xValue - xAxisMinFloat) / domain * plotWidth) + xPlotLeft;
				if(x < xPlotLeft)
					continue;
				OpenGL.buffer.put(x); OpenGL.buffer.put(yTickA); OpenGL.buffer.put(Theme.tickLinesColor);
				OpenGL.buffer.put(x); OpenGL.buffer.put(yTickB); OpenGL.buffer.put(Theme.tickLinesColor, 0, 3); OpenGL.buffer.put(xAxisStyle == AxisStyle.INNER ? 0 : 1);
				vertexCount += 2;
			}
			OpenGL.drawLinesXyrgba(gl, GL3.GL_LINES, OpenGL.buffer.rewind(), vertexCount);
			
			if(xAxisStyle == AxisStyle.INNER)
				gl.glScissor(chartScissorArgs[0] + (int) xPlotLeft, chartScissorArgs[1] + (int) yPlotBottom, (int) plotWidth, (int) plotHeight);
			
			for(Map.Entry<Float,String> entry : xDivisions.entrySet()) {
				float xValue = entry.getKey();
				String text  = entry.getValue();
				String[] line = text.split("\n");
				float x1 = (xAxisStyle == AxisStyle.TITLED || xAxisStyle == AxisStyle.OUTER) ? xPlotLeft + ((xValue - xAxisMinFloat) / domain * plotWidth) - (OpenGL.smallTextWidth(gl, line[0]) / 2.0f) :
				                                                                               xPlotLeft + ((xValue - xAxisMinFloat) / domain * plotWidth) + 2.5f*Theme.lineWidth; // 2.5 looks better than 2 when there's a negative sign
				float x2 = !xAxisTicksTwoLines                                               ? x1 :
				           (xAxisStyle == AxisStyle.TITLED || xAxisStyle == AxisStyle.OUTER) ? xPlotLeft + ((xValue - xAxisMinFloat) / domain * plotWidth) - (OpenGL.smallTextWidth(gl, line[1]) / 2.0f) :
				                                                                               xPlotLeft + ((xValue - xAxisMinFloat) / domain * plotWidth) + 2.5f*Theme.lineWidth; // 2.5 looks better than 2 when there's a negative sign
				boolean draw = (xAxisStyle == AxisStyle.INNER && yAxisStyle == AxisStyle.INNER) ? x1 >= occupiedMaxPixelX + 2*Theme.lineWidth && x2 >= occupiedMaxPixelX + 2*Theme.lineWidth :
				                                                                                  true;
				if(draw)
					if(xAxisTicksTwoLines) {
						OpenGL.drawSmallText(gl, line[0], (int) x1, (xAxisStyle == AxisStyle.INNER) ? (int) yText2 : (int) yText1, 0);
						OpenGL.drawSmallText(gl, line[1], (int) x2, (xAxisStyle == AxisStyle.INNER) ? (int) yText1 : (int) yText2, 0);
					} else {
						OpenGL.drawSmallText(gl, line[0], (int) x1, (int) yText1, 0);
					}
			}
			
			if(xAxisStyle == AxisStyle.INNER)
				gl.glScissor(chartScissorArgs[0], chartScissorArgs[1], chartScissorArgs[2], chartScissorArgs[3]);
		}
		
		// let the calling code draw the tooltip
		details = new PlotDetails(mouseX, mouseY, (int) plotWidth, (int) plotHeight, (int) xPlotLeft, (int) yPlotBottom, plotMatrix, handler);
		if(yAxisType == null && mouseX >= 0 && mouseX <= plotWidth && mouseY >= -yPlotBottom && mouseY <= plotHeight) {
			// timeline chart, so don't clip to the plot region, and allow the mouse to be below the plot region
			OpenGL.useMatrix(gl, plotMatrix);
			if(tooltipDrawer != null) {
				EventHandler h = tooltipDrawer.apply(details);
				if(h != null)
					handler = h;
			}
			OpenGL.useMatrix(gl, chartMatrix);
		} else if(Settings.GUI.tooltipsEnabled.isTrue() && mouseX >= 0 && mouseX <= plotWidth && mouseY >= 0 && mouseY <= plotHeight) {
			// regular chart
			gl.glScissor(chartScissorArgs[0] + (int) xPlotLeft, chartScissorArgs[1] + (int) yPlotBottom, (int) plotWidth, (int) plotHeight);
			OpenGL.useMatrix(gl, plotMatrix);
			if(tooltipDrawer != null) {
				EventHandler h = tooltipDrawer.apply(details);
				if(h != null)
					handler = h;
			}
			OpenGL.useMatrix(gl, chartMatrix);
			gl.glScissor(chartScissorArgs[0], chartScissorArgs[1], chartScissorArgs[2], chartScissorArgs[3]);
		}
		
		// draw the plot border
		if(yAxisType != null)
			OpenGL.drawQuadOutline2D(gl, Theme.plotOutlineColor, xPlotLeft, yPlotBottom, xPlotRight, yPlotTop);
		
		// done
		return handler;
	}
	
	/**
	 * Determines the best y values to use for vertical divisions. The 1/2/5 pattern is used (1,2,5,10,20,50,100,200,500...)
	 * 
	 * @param plotHeight    Number of pixels for the y-axis
	 * @param minY          Y value at the bottom of the plot
	 * @param maxY          Y value at the top of the plot
	 * @param axisTitle     Title, which is used to determine the suffix.
	 * @return              A Map of the y values for each division, keys are Floats and values are formatted Strings
	 */
	private Map<Float, String> getYdivisions125(float plotHeight, float minY, float maxY, String axisTitle) {
		
		Map<Float, String> yValues = new HashMap<Float, String>();
		
		// sanity check
		if(plotHeight < 1 || minY >= maxY)
			return yValues;
		
		// calculate the best division size
		float minSpacingBetweenText = 2.0f * OpenGL.smallTextHeight;
		float maxDivisionsCount = plotHeight / (OpenGL.smallTextHeight + minSpacingBetweenText) + 1.0f;
		float divisionSize = (maxY - minY) / maxDivisionsCount;
		float closestDivSize1 = (float) Math.pow(10.0, Math.ceil(Math.log10(divisionSize/1.0))) * 1.0f; // closest (10^n)*1 that is >= divisionSize, such as 1,10,100,1000
		float closestDivSize2 = (float) Math.pow(10.0, Math.ceil(Math.log10(divisionSize/2.0))) * 2.0f; // closest (10^n)*2 that is >= divisionSize, such as 2,20,200,2000
		float closestDivSize5 = (float) Math.pow(10.0, Math.ceil(Math.log10(divisionSize/5.0))) * 5.0f; // closest (10^n)*5 that is >= divisionSize, such as 5,50,500,5000
		float error1 = closestDivSize1 - divisionSize;
		float error2 = closestDivSize2 - divisionSize;
		float error5 = closestDivSize5 - divisionSize;
		if(error1 < error2 && error1 < error5)
			divisionSize = closestDivSize1;
		else if(error2 < error1 && error2 < error5)
			divisionSize = closestDivSize2;
		else
			divisionSize = closestDivSize5;
		
		// calculate the number of divisions
		float firstDivision = maxY - (maxY % divisionSize);
		float lastDivision  = minY - (minY % divisionSize);
		if(firstDivision > maxY)
			firstDivision -= divisionSize;
		if(lastDivision < minY)
			lastDivision += divisionSize;
		int divisionCount = (int) Math.round((firstDivision - lastDivision) / divisionSize) + 1;
		
		// calculate each division, as both a number and a String
		if(divisionSize >= 0.99) {
			for(int i = 0; i < divisionCount; i++) {
				float number = firstDivision - (i * divisionSize);
				if(convertToPercentage)
					yValues.put(number, Theme.getAmountAndPercentage((int) number, sampleCount));
				else
					yValues.put(number, Theme.getInteger((int) number, axisTitle));
			}
		} else {
			int decimalPlaces = (int) Math.ceil(Math.log10(1.0 / divisionSize));
			for(int i = 0; i < divisionCount; i++) {
				float number = firstDivision - (i * divisionSize);
				yValues.put(number, Theme.getFloat(number, decimalPlaces, axisTitle));
			}
		}
		
		return yValues;
		
	}
	
	/**
	 * Determines the best Log10 y values to use for vertical divisions. Division size will be either 1e1, 1e3 or 1e9.
	 * 
	 * @param plotHeight    Number of pixels for the y-axis
	 * @param minY          Y value at the bottom of the plot
	 * @param maxY          Y value at the top of the plot
	 * @param axisTitle     Title, which is used to determine the suffix.
	 * @return              A Map of the y values for each division, keys are Floats and values are formatted Strings
	 */
	private Map<Float, String> getLogYdivisions(float plotHeight, float minY, float maxY, String axisTitle) {
		
		Map<Float, String> yValues = new HashMap<Float, String>();
		
		// sanity check
		if(plotHeight < 1 || minY >= maxY)
			return yValues;
		
		// calculate the best vertical division size
		float minSpacingBetweenText = 2.0f * OpenGL.smallTextHeight;
		float maxDivisionsCount = plotHeight / (OpenGL.smallTextHeight + minSpacingBetweenText) + 1.0f;
		float divisionSize = (maxY - minY) / maxDivisionsCount;
		float divSize1 = 1.0f; // 1W, 100mW, 10mW, 1mW, 100uW, ...
		float divSize3 = 3.0f; // 1W, 1mW, 1uW, ...
		float divSize9 = 9.0f; // 1W, 1nW, ...
		float error1 = divSize1 - divisionSize;
		float error3 = divSize3 - divisionSize;
		float error9 = divSize9 - divisionSize;
		if(error1 > 0 && error1 < error3 && error1 < error9)
			divisionSize = divSize1;
		else if(error3 > 0 && error3 < error9)
			divisionSize = divSize3;
		else if(error9 > 0)
			divisionSize = divSize9;
		else
			return new HashMap<Float, String>();
		
		// calculate the values for each vertical division
		float firstDivision = maxY - (maxY % divisionSize);
		float lastDivision  = minY - (minY % divisionSize);
		if(firstDivision > maxY)
			firstDivision -= divisionSize;
		if(lastDivision < minY)
			lastDivision += divisionSize;
		int divisionCount = (int) Math.round((firstDivision - lastDivision) / divisionSize) + 1;
		
		// calculate each division, as both a number and a String
		for(int i = 0; i < divisionCount; i++) {
			float number = firstDivision - (i * divisionSize);
			yValues.put(number, Theme.getScientificNotation(1, (int) number, axisTitle));
		}
		
		return yValues;
		
	}
	
	/**
	 * Determines the best integer x values to use for horizontal divisions. The 1/2/5 pattern is used (1,2,5,10,20,50,100,200,500...)
	 * 
	 * @param gl           The OpenGL context.
	 * @param plotWidth    Number of pixels for the x-axis
	 * @param minX         X value at the left of the plot
	 * @param maxX         X value at the right of the plot
	 * @return             A Map of the x values for each division, keys are Integers and values are formatted Strings
	 */
	private Map<Integer, String> getIntegerXdivisions125(GL2ES3 gl, float plotWidth, int minX, int maxX) {
		
		Map<Integer, String> xValues = new HashMap<Integer, String>();
		
		// sanity check
		if(plotWidth < 1 || minX >= maxX)
			return xValues;
		
		// calculate the best horizontal division size
		int textWidth = (int) Float.max(OpenGL.smallTextWidth(gl, Integer.toString(maxX)), OpenGL.smallTextWidth(gl, Integer.toString(minX)));
		int minSpacingBetweenText = textWidth;
		float maxDivisionsCount = plotWidth / (textWidth + minSpacingBetweenText);
		int divisionSize = (int) Math.ceil((maxX - minX) / maxDivisionsCount);
		if(divisionSize == 0) divisionSize = 1;
		int closestDivSize1 = (int) Math.pow(10.0, Math.ceil(Math.log10(divisionSize/1.0))) * 1; // closest (10^n)*1 that is >= divisionSize, such as 1,10,100,1000
		int closestDivSize2 = (int) Math.pow(10.0, Math.ceil(Math.log10(divisionSize/2.0))) * 2; // closest (10^n)*2 that is >= divisionSize, such as 2,20,200,2000
		int closestDivSize5 = (int) Math.pow(10.0, Math.ceil(Math.log10(divisionSize/5.0))) * 5; // closest (10^n)*5 that is >= divisionSize, such as 5,50,500,5000
		int error1 = closestDivSize1 - divisionSize;
		int error2 = closestDivSize2 - divisionSize;
		int error5 = closestDivSize5 - divisionSize;
		if(error1 < error2 && error1 < error5)
			divisionSize = closestDivSize1;
		else if(error2 < error1 && error2 < error5)
			divisionSize = closestDivSize2;
		else
			divisionSize= closestDivSize5;
		
		// calculate the values for each horizontal division
		int firstDivision = maxX - (maxX % divisionSize);
		int lastDivision  = minX - (minX % divisionSize);
		if(firstDivision > maxX)
			firstDivision -= divisionSize;
		if(lastDivision < minX)
			lastDivision += divisionSize;
		int divisionCount = ((firstDivision - lastDivision) / divisionSize + 1);
		
		int start = (xAxisStyle == AxisStyle.INNER) ? -1 : 0;
		for(int i = start; i < divisionCount; i++) {
			int number = lastDivision + (i * divisionSize);
			String text = Integer.toString(number);
			xValues.put(number, text);
		}
		
		return xValues;
		
	}
	
	/**
	 * Determines the best floating point x values to use for horizontal divisions. The 1/2/5 pattern is used (.1,.2,.5,1,2,5,10,20,50...)
	 * 
	 * @param gl           The OpenGL context.
	 * @param plotWidth    Number of pixels for the x-axis
	 * @param minX         X value at the left of the plot
	 * @param maxX         X value at the right of the plot
	 * @param axisTitle    Title, which is used to determine the suffix.
	 * @return             A Map of the x values for each division, keys are Floats and values are formatted Strings
	 */
	private Map<Float, String> getFloatXdivisions125(GL2ES3 gl, float plotWidth, float minX, float maxX, String axisTitle) {
		
		Map<Float, String> xValues = new HashMap<Float, String>();
		
		// sanity check
		if(plotWidth < 1 || minX >= maxX)
			return xValues;
		
		for(int maxDivisionsCount = 1; maxDivisionsCount < 100; maxDivisionsCount++) {
			
			float divisionSize = (maxX - minX) / (float) maxDivisionsCount;
			float closestDivSize1 = (float) Math.pow(10.0, Math.ceil(Math.log10(divisionSize/1.0))) * 1; // closest (10^n)*1 that is >= divisionSize, such as 1,10,100,1000
			float closestDivSize2 = (float) Math.pow(10.0, Math.ceil(Math.log10(divisionSize/2.0))) * 2; // closest (10^n)*2 that is >= divisionSize, such as 2,20,200,2000
			float closestDivSize5 = (float) Math.pow(10.0, Math.ceil(Math.log10(divisionSize/5.0))) * 5; // closest (10^n)*5 that is >= divisionSize, such as 5,50,500,5000
			float error1 = closestDivSize1 - divisionSize;
			float error2 = closestDivSize2 - divisionSize;
			float error5 = closestDivSize5 - divisionSize;
			if(error1 < error2 && error1 < error5)
				divisionSize = closestDivSize1;
			else if(error2 < error1 && error2 < error5)
				divisionSize = closestDivSize2;
			else
				divisionSize= closestDivSize5;
			
			// calculate the number of divisions
			float firstDivision = maxX - (maxX % divisionSize);
			float lastDivision  = minX - (minX % divisionSize);
			firstDivision += divisionSize; // compensating for floating point error that may skip the end points
			lastDivision -= divisionSize;
			while(firstDivision > maxX)
				firstDivision -= divisionSize;
			while(lastDivision < minX)
				lastDivision += divisionSize;
			int divisionCount = (int) Math.round((firstDivision - lastDivision) / divisionSize) + 1;
			
			// calculate each division, as both a number and a String
			Map<Float, String> proposedXvalues = new HashMap<Float, String>();
			int end = (xAxisStyle == AxisStyle.INNER) ? divisionCount+1 : divisionCount;
			if(divisionSize >= 0.99) {
				for(int i = 0; i < end; i++) {
					float number = firstDivision - (i * divisionSize);
					proposedXvalues.put(number, Theme.getInteger((int) number, axisTitle));
				}
			} else {
				int decimalPlaces = (int) Math.ceil(Math.log10(1.0 / divisionSize));
				for(int i = 0; i < end; i++) {
					float number = firstDivision - (i * divisionSize);
					proposedXvalues.put(number, Theme.getFloat(number, decimalPlaces, axisTitle));
				}
			}
			
			// calculate how much width is taken up by the text
			float width = 0;
			for(String s : proposedXvalues.values())
				width += OpenGL.smallTextWidth(gl, s);
			
			// stop and don't use this iteration if we're using more than half of the width
			if(width > plotWidth / 2.0f)
				break;
			
			xValues = proposedXvalues;
			
		}
		
		return xValues;
		
	}
	
	/**
	 * Determines the best timestamp values to use for horizontal divisions.
	 * 
	 * @param gl              The OpenGL context.
	 * @param width           Number of horizontal pixels available for displaying divisions.
	 * @param minTimestamp    Timestamp at the left edge (milliseconds since 1970-01-01).
	 * @param maxTimestamp    Timestamp at the right edge (milliseconds since 1970-01-01).
	 * @return                A Map of divisions: keys are Float pixelX locations, and values are formatted Strings.
	 */
	@SuppressWarnings("deprecation")
	private Map<Float, String> getTimestampDivisions(GL2ES3 gl, float width, long minTimestamp, long maxTimestamp) {
		
		Map<Float, String> divisions = new HashMap<Float, String>();
		
		// sanity check
		if(width < 1 || minTimestamp >= maxTimestamp)
			return divisions;
		
		// determine how many divisions can fit on screen
		// first try with milliseconds resolution
		String leftLabel  = Settings.formatTimestampToMilliseconds(minTimestamp);
		String rightLabel = Settings.formatTimestampToMilliseconds(maxTimestamp);
		float maxLabelWidth = 0;
		if(Settings.isTimeFormatTwoLines()) {
			String[] leftLine = leftLabel.split("\n");
			String[] rightLine = rightLabel.split("\n");
			float leftMax  = Float.max(OpenGL.smallTextWidth(gl, leftLine[0]),  OpenGL.smallTextWidth(gl, leftLine[1]));
			float rightMax = Float.max(OpenGL.smallTextWidth(gl, rightLine[0]), OpenGL.smallTextWidth(gl, rightLine[1]));
			maxLabelWidth = Float.max(leftMax, rightMax);
		} else {
			maxLabelWidth = Float.max(OpenGL.smallTextWidth(gl, leftLabel), OpenGL.smallTextWidth(gl, rightLabel));
		}
		float padding = maxLabelWidth / 2f;
		int divisionCount = (int) (width / (maxLabelWidth + padding));
		long millisecondsOnScreen = maxTimestamp - minTimestamp;
		long millisecondsPerDivision = (long) Math.ceil((double) millisecondsOnScreen / (double) divisionCount);
		
		// if the divisions are >1000ms apart, change to seconds resolution instead
		if(millisecondsPerDivision > 1000) {
			leftLabel  = Settings.formatTimestampToSeconds(minTimestamp);
			rightLabel = Settings.formatTimestampToSeconds(maxTimestamp);
			maxLabelWidth = 0;
			if(Settings.isTimeFormatTwoLines()) {
				String[] leftLine = leftLabel.split("\n");
				String[] rightLine = rightLabel.split("\n");
				float leftMax  = Float.max(OpenGL.smallTextWidth(gl, leftLine[0]),  OpenGL.smallTextWidth(gl, leftLine[1]));
				float rightMax = Float.max(OpenGL.smallTextWidth(gl, rightLine[0]), OpenGL.smallTextWidth(gl, rightLine[1]));
				maxLabelWidth = Float.max(leftMax, rightMax);
			} else {
				maxLabelWidth = Float.max(OpenGL.smallTextWidth(gl, leftLabel), OpenGL.smallTextWidth(gl, rightLabel));
			}
			padding = maxLabelWidth / 2f;
			divisionCount = (int) (width / (maxLabelWidth + padding));
			millisecondsOnScreen = maxTimestamp - minTimestamp;
			millisecondsPerDivision = (long) Math.ceil((double) millisecondsOnScreen / (double) divisionCount);
			if(millisecondsPerDivision < 1000)
				millisecondsPerDivision = 1000;
		}
		
		// if the divisions are >60000ms apart, change to minutes resolution instead
		if(millisecondsPerDivision > 60000) {
			leftLabel  = Settings.formatTimestampToMinutes(minTimestamp);
			rightLabel = Settings.formatTimestampToMinutes(maxTimestamp);
			maxLabelWidth = 0;
			if(Settings.isTimeFormatTwoLines()) {
				String[] leftLine = leftLabel.split("\n");
				String[] rightLine = rightLabel.split("\n");
				float leftMax  = Float.max(OpenGL.smallTextWidth(gl, leftLine[0]),  OpenGL.smallTextWidth(gl, leftLine[1]));
				float rightMax = Float.max(OpenGL.smallTextWidth(gl, rightLine[0]), OpenGL.smallTextWidth(gl, rightLine[1]));
				maxLabelWidth = Float.max(leftMax, rightMax);
			} else {
				maxLabelWidth = Float.max(OpenGL.smallTextWidth(gl, leftLabel), OpenGL.smallTextWidth(gl, rightLabel));
			}
			padding = maxLabelWidth / 2f;
			divisionCount = (int) (width / (maxLabelWidth + padding));
			millisecondsOnScreen = maxTimestamp - minTimestamp;
			millisecondsPerDivision = (long) Math.ceil((double) millisecondsOnScreen / (double) divisionCount);
			if(millisecondsPerDivision < 60000)
				millisecondsPerDivision = 60000;
		}
		
		Date minDate = new Date(minTimestamp);
		long firstDivisionTimestamp = minTimestamp;
		if(millisecondsPerDivision < 1000) {
			// <1s per div, so use 1/2/5/10/20/50/100/200/250/500/1000ms per div, relative to the nearest second
			millisecondsPerDivision = (millisecondsPerDivision <= 1)   ? 1 :
			                          (millisecondsPerDivision <= 2)   ? 2 :
			                          (millisecondsPerDivision <= 5)   ? 5 :
			                          (millisecondsPerDivision <= 10)  ? 10 :
			                          (millisecondsPerDivision <= 20)  ? 20 :
			                          (millisecondsPerDivision <= 50)  ? 50 :
			                          (millisecondsPerDivision <= 100) ? 100 :
			                          (millisecondsPerDivision <= 200) ? 200 :
			                          (millisecondsPerDivision <= 250) ? 250 :
			                          (millisecondsPerDivision <= 500) ? 500 :
			                                                             1000;
			firstDivisionTimestamp = new Date(minDate.getYear(), minDate.getMonth(), minDate.getDate(), minDate.getHours(), minDate.getMinutes(), minDate.getSeconds()).getTime() - 1000;
		} else if(millisecondsPerDivision < 60000) {
			// <1m per div, so use 1/2/5/10/15/20/30/60s per div, relative to the nearest minute
			millisecondsPerDivision = (millisecondsPerDivision <= 1000)  ? 1000 :
			                          (millisecondsPerDivision <= 2000)  ? 2000 :
			                          (millisecondsPerDivision <= 5000)  ? 5000 :
			                          (millisecondsPerDivision <= 10000) ? 10000 :
			                          (millisecondsPerDivision <= 15000) ? 15000 :
			                          (millisecondsPerDivision <= 20000) ? 20000 :
			                          (millisecondsPerDivision <= 30000) ? 30000 :
			                                                               60000;
			firstDivisionTimestamp = new Date(minDate.getYear(), minDate.getMonth(), minDate.getDate(), minDate.getHours(), minDate.getMinutes(), 0).getTime() - 60000;
		} else if(millisecondsPerDivision < 3600000) {
			// <1h per div, so use 1/2/5/10/15/20/30/60m per div, relative to the nearest hour
			millisecondsPerDivision = (millisecondsPerDivision <= 60000)   ? 60000 :
			                          (millisecondsPerDivision <= 120000)  ? 120000 :
			                          (millisecondsPerDivision <= 300000)  ? 300000 :
			                          (millisecondsPerDivision <= 600000)  ? 600000 :
			                          (millisecondsPerDivision <= 900000)  ? 900000 :
			                          (millisecondsPerDivision <= 1200000) ? 1200000 :
			                          (millisecondsPerDivision <= 1800000) ? 1800000 :
			                                                                 3600000;
			firstDivisionTimestamp = new Date(minDate.getYear(), minDate.getMonth(), minDate.getDate(), minDate.getHours(), 0, 0).getTime() - 3600000;
		} else if(millisecondsPerDivision < 86400000) {
			// <1d per div, so use 1/2/3/4/6/8/12/24 hours per div, relative to the nearest day
			millisecondsPerDivision = (millisecondsPerDivision <= 3600000)  ? 3600000 :
			                          (millisecondsPerDivision <= 7200000)  ? 7200000 :
			                          (millisecondsPerDivision <= 10800000) ? 10800000 :
			                          (millisecondsPerDivision <= 14400000) ? 14400000 :
			                          (millisecondsPerDivision <= 21600000) ? 21600000 :
			                          (millisecondsPerDivision <= 28800000) ? 28800000 :
			                          (millisecondsPerDivision <= 43200000) ? 43200000 :
			                                                                  86400000;
			firstDivisionTimestamp = new Date(minDate.getYear(), minDate.getMonth(), minDate.getDate(), 0, 0, 0).getTime() - 86400000;
		} else {
			// >=1d per div, so use an integer number of days, relative to the nearest day
			if(millisecondsPerDivision != 86400000)
				millisecondsPerDivision += 86400000 - (millisecondsPerDivision % 86400000);
			firstDivisionTimestamp = new Date(minDate.getYear(), minDate.getMonth(), 1, 0, 0, 0).getTime() - 86400000;
		}
		while(firstDivisionTimestamp < minTimestamp)
			firstDivisionTimestamp += millisecondsPerDivision;
		
		// populate the Map
		int start = (xAxisStyle == AxisStyle.INNER) ? -1 : 0;
		for(int divisionN = start; divisionN < divisionCount; divisionN++) {
			long timestampN = firstDivisionTimestamp + (divisionN * millisecondsPerDivision);
			float pixelX = (float) (timestampN - minTimestamp) / (float) millisecondsOnScreen * width;
			String label = millisecondsPerDivision < 1000  ? Settings.formatTimestampToMilliseconds(timestampN) :
			               millisecondsPerDivision < 60000 ? Settings.formatTimestampToSeconds(timestampN) :
			                                                 Settings.formatTimestampToMinutes(timestampN);
			if(pixelX <= width)
				divisions.put(pixelX, label);
			else
				break;
		}
		
		return divisions;
		
	}

}
