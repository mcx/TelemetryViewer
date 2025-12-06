import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.JPanel;

import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.jtransforms.fft.DoubleFFT_1D;

import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL2ES3;
import com.jogamp.opengl.GL3;

public class OpenGLFrequencyDomainChart extends Chart {
	
	public enum Type {
		SINGLE    { @Override public String toString() { return "Single";    } },
		HISTOGRAM { @Override public String toString() { return "Histogram"; } },
		WATERFALL { @Override public String toString() { return "Waterfall"; } };
	};
	
	private record FFTs(boolean exist,    // true if there were enough samples to calculate at least one FFT
	                    double binSizeHz, // bin size (in Hertz) is the reciprocal of the window size (in seconds.) example: 500ms window -> 1/0.5 = 2 Hz bin size
	                    int binCount,     // bin count is the sample count, divided by 2, plus 1
	                    float minHz,      // always 0
	                    float maxHz,      // Nyquist
	                    float minPower,   // minimum of the FFTs, not necessarily the plotted minimum
	                    float maxPower,   // maximum of the FFTs, not necessarily the plotted maximum
	                    int windowLength, // samples per FFT
	                    List<List<float[]>> windows) { // .get(windowN).get(datasetN)[binN]
		public FFTs() {
			this(false, 0, 0, 0, 1, -12, 0, 0, new ArrayList<List<float[]>>(0)); // if no FFTs, default to a range of 1pW to 1W, and 0 to 1Hz
		}
	}
	
	private FFTcache cache = new FFTcache();
	private int[][][] histogram = null; // [datasetN][yBin][xBin]
	private int fftBinsPerPlotBin = 1;
	
	// y-axis scale
	AutoScale autoscale;
	
	// textures
	private int[] histogramTexHandle;
	private int[] waterfallTexHandle;
	
	// user settings
	private DatasetsInterface.WidgetDatasets datasetsWidget;
	private WidgetTextfield<Integer> sampleCountTextfield;
	private WidgetToggleButton<OpenGLPlot.LegendStyle> legendStyle;
	private WidgetToggleButton<Type> chartType;
	private WidgetTextfield<Integer> fftCount;
	private WidgetTextfield<Integer> xAxisBinCount;
	private WidgetCheckbox xAxisBinsAutomatic;
	private WidgetTextfield<Integer> yAxisBinCount;
	private WidgetCheckbox yAxisBinsAutomatic;
	private WidgetSlider<Float> gammaSlider;
	private WidgetTextfield<Float> minPower;
	private WidgetCheckbox minPowerAutomatic;
	private WidgetTextfield<Float> maxPower;
	private WidgetCheckbox maxPowerAutomatic;
	private WidgetTextfield<Float> automaticIgnoresOutliers;
	private WidgetCheckbox fftInfoVisibility;
	private WidgetToggleButton<OpenGLPlot.AxisStyle> xAxisStyle;
	private WidgetToggleButton<OpenGLPlot.AxisStyle> yAxisStyle;
	
	protected OpenGLFrequencyDomainChart(String name, int x1, int y1, int x2, int y2) {
		
		super(name, x1, y1, x2, y2);
		
		autoscale = new AutoScale(AutoScale.Mode.SMOOTH, 0.20f);
		
		datasetsWidget = datasets.getCheckboxesWidget(newDatasets -> {});
		
		sampleCountTextfield = WidgetTextfield.ofInt(10, Integer.MAX_VALUE / 16, Connections.getDefaultChartDuration())
		                                      .setSuffix("Samples")
		                                      .setExportLabel("sample count")
		                                      .onChange((newDuration, oldDuration) -> {
		                                          duration = newDuration;
		                                          return true;
		                                      });
		
		legendStyle = new WidgetToggleButton<OpenGLPlot.LegendStyle>("Legend", OpenGLPlot.LegendStyle.values(), OpenGLPlot.LegendStyle.OUTER)
		                  .setExportLabel("legend style");
		
		fftCount = WidgetTextfield.ofInt(2, 100, 20)
		                          .setSuffix("FFTs")
		                          .setExportLabel("fft count");
		
		xAxisBinCount = WidgetTextfield.ofInt(2, 4096, 128)
		                               .setPrefix("X-Axis Bins")
		                               .setExportLabel("histogram/waterfall x-axis bin count")
		                               .onChange((newCount, oldCount) -> {
		                                            if(newCount == 0)
		                                                xAxisBinsAutomatic.set(true);
		                                            return true;
		                                        });
		
		xAxisBinsAutomatic = new WidgetCheckbox("Auto", false)
		                         .setExportLabel("histogram/waterfall x-axis bin count automatic")
		                         .onChange(isAutomatic -> {
		                                      if(isAutomatic) {
		                                          xAxisBinCount.disableWithMessage("Automatic");
		                                      } else {
		                                          xAxisBinCount.setEnabled(true);
		                                      }
		                                  });
		
		yAxisBinCount = WidgetTextfield.ofInt(2, 4096, 128)
		                               .setPrefix("Y-Axis Bins")
		                               .setExportLabel("histogram y-axis bin count")
		                               .onChange((newCount, oldCount) -> {
		                                            if(newCount == 0)
		                                                yAxisBinsAutomatic.set(true);
		                                            return true;
		                                        });
		
		yAxisBinsAutomatic = new WidgetCheckbox("Auto", false)
		                         .setExportLabel("histogram y-axis bin count automatic")
		                         .onChange(isAutomatic -> {
		                                      if(isAutomatic) {
		                                          yAxisBinCount.disableWithMessage("Automatic");
		                                      } else {
		                                          yAxisBinCount.setEnabled(true);
		                                      }
		                                  });
		
		gammaSlider = WidgetSlider.ofFloat("Gamma", 0f, 1f, 1f)
		                          .setExportLabel("histogram/waterfall fft gamma")
		                          .withTickLabels(6);
		
		chartType = new WidgetToggleButton<Type>(null, Type.values(), Type.SINGLE)
		                .setExportLabel("fft style")
		                .onChange((newStyle, oldStyle) -> {
		                     if(newStyle == Type.SINGLE)
		                         fftCount.disableWithMessage("1 FFT");
		                     else
		                         fftCount.setEnabled(true);
		                     xAxisBinCount     .setVisible(newStyle != Type.SINGLE);
		                     xAxisBinsAutomatic.setVisible(newStyle != Type.SINGLE);
		                     yAxisBinCount     .setVisible(newStyle == Type.HISTOGRAM);
		                     yAxisBinsAutomatic.setVisible(newStyle == Type.HISTOGRAM);
		                     gammaSlider       .setVisible(newStyle != Type.SINGLE);
		                     Configure.GUI.redrawIfUsedFor(this); // important: need to revalidate/redraw the parent
		                     return true;
		                 });
		
		minPower = WidgetTextfield.ofFloat(Float.MIN_VALUE, Float.MAX_VALUE, 1e-15f)
		                          .setPrefix("Min Power")
		                          .setSuffix("Watts")
		                          .setExportLabel("fft minimum power")
		                          .onChange((newMinimum, oldMinimum) -> {
		                                        if(newMinimum > maxPower.get())
		                                            maxPower.set(newMinimum);
		                                        return true;
		                                    });
		
		minPowerAutomatic = new WidgetCheckbox("Auto", true)
		                        .setExportLabel("fft minimum power automatic")
		                        .onChange(isAutomatic -> {
		                                     if(isAutomatic)
		                                         minPower.disableWithMessage("Automatic");
		                                     else
		                                         minPower.setEnabled(true);
		                                     automaticIgnoresOutliers.setEnabled(minPowerAutomatic.isTrue() || maxPowerAutomatic.isTrue());
		                                 });
		
		maxPower = WidgetTextfield.ofFloat(Float.MIN_VALUE, Float.MAX_VALUE, 10f)
		                          .setPrefix("Max Power")
		                          .setSuffix("Watts")
		                          .setExportLabel("fft maximum power")
		                          .onChange((newMaximum, oldMaximum) -> {
		                                        if(newMaximum < minPower.get())
		                                            minPower.set(newMaximum);
		                                        return true;
		                                    });
		
		maxPowerAutomatic = new WidgetCheckbox("Auto", true)
		                        .setExportLabel("fft maximum power automatic")
		                        .onChange(isAutomatic -> {
		                                     if(isAutomatic)
		                                         maxPower.disableWithMessage("Automatic");
		                                     else
		                                         maxPower.setEnabled(true);
		                                     automaticIgnoresOutliers.setEnabled(minPowerAutomatic.isTrue() || maxPowerAutomatic.isTrue());
		                                 });
		
		automaticIgnoresOutliers = WidgetTextfield.ofFloat(0, 10, 0.5f)
		                                          .setPrefix("Autoscaling Ignores")
		                                          .setSuffix("% of Outliers")
		                                          .setToolTipText("If >0, autoscaling will ignore this amount of the extremes.")
		                                          .setExportLabel("fft autoscaling ignores outliers");
		
		fftInfoVisibility = new WidgetCheckbox("Show FFT Info", true)
		                        .setExportLabel("fft show info");
		
		xAxisStyle = new WidgetToggleButton<OpenGLPlot.AxisStyle>("", OpenGLPlot.AxisStyle.values(), OpenGLPlot.AxisStyle.OUTER)
		                 .setExportLabel("x-axis style");
		
		yAxisStyle = new WidgetToggleButton<OpenGLPlot.AxisStyle>("", OpenGLPlot.AxisStyle.values(), OpenGLPlot.AxisStyle.OUTER)
		                 .setExportLabel("y-axis style");
		
		widgets.add(datasetsWidget);
		widgets.add(sampleCountTextfield);
		widgets.add(legendStyle);
		widgets.add(chartType);
		widgets.add(fftCount);
		widgets.add(minPower);
		widgets.add(minPowerAutomatic);
		widgets.add(maxPower);
		widgets.add(maxPowerAutomatic);
		widgets.add(automaticIgnoresOutliers);
		widgets.add(xAxisBinCount);
		widgets.add(xAxisBinsAutomatic);
		widgets.add(yAxisBinCount);
		widgets.add(yAxisBinsAutomatic);
		widgets.add(gammaSlider);
		widgets.add(fftInfoVisibility);
		widgets.add(xAxisStyle);
		widgets.add(yAxisStyle);
		
	}
	
	@Override public void appendConfigurationWidgets(JPanel gui) {
		
		gui.add(Theme.newWidgetsPanel("Data")
		             .with(datasetsWidget)
		             .with(sampleCountTextfield)
		             .with(legendStyle)
		             .getPanel());
		
		gui.add(Theme.newWidgetsPanel("FFTs")
		             .with(chartType)
		             .with(fftCount,             "grow x, grow y")
		             .withGap(Theme.padding)
		             .with(minPower,             "split 2, grow x, grow y")
		             .with(minPowerAutomatic,    "sizegroup 1")
		             .with(maxPower,             "split 2, grow x, grow y")
		             .with(maxPowerAutomatic,    "sizegroup 1")
		             .with(automaticIgnoresOutliers)
		             .withGap(Theme.padding)
		             .with(xAxisBinCount,        "split 2, grow x, grow y")
		             .with(xAxisBinsAutomatic,   "sizegroup 1")
		             .with(yAxisBinCount,        "split 2, grow x, grow y")
		             .with(yAxisBinsAutomatic,   "sizegroup 1")
		             .with(gammaSlider)
		             .withGap(Theme.padding)
		             .with(fftInfoVisibility)
		             .getPanel());
		
		gui.add(Theme.newWidgetsPanel("X-Axis")
		             .with(xAxisStyle)
		             .getPanel());
		
		gui.add(Theme.newWidgetsPanel("Y-Axis")
		             .with(yAxisStyle)
		             .getPanel());
		
	}
	
	@Override public EventHandler drawChart(GL2ES3 gl, float[] chartMatrix, int width, int height, long endTimestamp, int endSampleNumber, double zoomLevel, int mouseX, int mouseY) {
		
		int datasetsCount = datasets.normalsCount();
		
		// calculate the FFTs
		FFTs ffts = cache.getFFTs(endSampleNumber, duration, chartType.is(Type.SINGLE) ? 1 : fftCount.get(), automaticIgnoresOutliers.get(), datasets);
		
		// determine the x-axis range
		float plotMinX = ffts.minHz;
		float plotMaxX = ffts.maxHz;
		float domain = plotMaxX - plotMinX;
		
		// determine the y-axis range and ensure it's >0
		// the y-axis is power (in single/histogram modes) or time (in waterfall mode)
		autoscale.update(ffts.minPower, ffts.maxPower);
		
		float plotMinTime = 0;
		float plotMaxTime = (float) (duration * fftCount.get()) / datasets.getSampleRate();
		
		float plotMinPower = minPowerAutomatic.isFalse()   ? (float) Math.log10(minPower.get()) : // user-specified min value
		                     !chartType.is(Type.WATERFALL) ? autoscale.getMin() :                 // autoscaled min for single/histogram modes
		                                                     ffts.minPower;                       // true min for waterfall mode
		float plotMaxPower = maxPowerAutomatic.isFalse()   ? (float) Math.log10(maxPower.get()) : // user-specified max value
		                     !chartType.is(Type.WATERFALL) ? autoscale.getMax():                  // autoscaled max for single/histogram modes
		                                                     ffts.maxPower;                       // true max for waterfall mode
		float plotMinY = chartType.is(Type.WATERFALL) ? plotMinTime : plotMinPower;
		float plotMaxY = chartType.is(Type.WATERFALL) ? plotMaxTime : plotMaxPower;
		float plotRange = plotMaxY - plotMinY;
		
		// determine the axis titles
		String xAxisTitle = "Frequency";
		String yAxisTitle = chartType.is(Type.WATERFALL) ? "Time" : "Power";
		
		// draw the plot
		return new OpenGLPlot(chartMatrix, width, height, mouseX, mouseY)
		           .withLegend(legendStyle.get(), datasets)
		           .withXaxis(xAxisStyle.get(), OpenGLPlot.AxisScale.LINEAR, plotMinX, plotMaxX, xAxisTitle)
		           .withYaxis(yAxisStyle.get(), chartType.is(Type.WATERFALL) ? OpenGLPlot.AxisScale.LINEAR : OpenGLPlot.AxisScale.LOG, plotMinY, plotMaxY, yAxisTitle)
		           .withFftInfo(fftInfoVisibility.get(), chartType.get(), ffts.windowLength == 0 ? duration : ffts.windowLength, ffts.windows.size(), plotMinPower, plotMaxPower)
		           .withPlotDrawer(plot -> {
		        	   if(!ffts.exist)
		        		   return null;
		               if(chartType.is(Type.SINGLE)) {
		                   // the matrix is currently configured so (0,0) is the bottom-left of the plot, with units of pixels
		                   // but x will be auto-generated, ranging from 0 to binCount-1
		                   // and y will be FFT results, ranging from plotMinPower to plotMaxPower
		                   // we can scale x into pixels with: x =            (x) / plotDomain * plotWidth
		                   // we can scale y into pixels with: y = (y - plotMinY) / plotRange  * plotHeight;
		                   float[] matrix = Arrays.copyOf(plot.matrix(), 16);
		                   OpenGL.scaleMatrix    (matrix, plot.width()/(float) (ffts.binCount-1), plot.height()/(plotMaxY - plotMinY), 1);
		                   OpenGL.translateMatrix(matrix,                                     0,                          - plotMinY,  0);
		                   OpenGL.useMatrix(gl, matrix);
		                   
		                   // draw the FFTs as line charts, and also draw points if there are relatively few bins on screen
		                   for(int datasetN = 0; datasetN < datasets.normalDatasets.size(); datasetN++) {
		                       FloatBuffer buffer = Buffers.newDirectFloatBuffer(ffts.windows.get(0).get(datasetN));
		                       OpenGL.drawLinesY(gl, GL3.GL_LINE_STRIP, datasets.normalDatasets.get(datasetN).color.getGl(), buffer, ffts.binCount, 0);
		                       if(width / ffts.binCount > 2 * Theme.pointWidth)
		                           OpenGL.drawPointsY(gl, datasets.normalDatasets.get(datasetN).color.getGl(), buffer, ffts.binCount, 0);
		                   }
		               } else if(chartType.is(Type.HISTOGRAM)) {
		                   
		                   fftBinsPerPlotBin = xAxisBinsAutomatic.isFalse() ? Math.ceilDiv(ffts.binCount, xAxisBinCount.get()) :
		                                                                      Math.ceilDiv(ffts.binCount, (plot.width() / 2));
		                   int xBinCount = Math.ceilDiv(ffts.binCount, fftBinsPerPlotBin);
		                   int yBinCount = yAxisBinsAutomatic.get() ? plot.height() / 2 : yAxisBinCount.get();
		                   
	                       histogram = new int[datasetsCount][yBinCount][xBinCount];
	                       ByteBuffer bytes = Buffers.newDirectByteBuffer(yBinCount * xBinCount * 4); // pixelCount * one int32 per pixel
	                       IntBuffer ints = bytes.asIntBuffer();
	                       
	                       for(int datasetN = 0; datasetN < datasetsCount; datasetN++) {
	                           for(int windowN = 0; windowN < ffts.windows.size(); windowN++) {
	                               float[] fft = ffts.windows.get(windowN).get(datasetN);
	                               for(int xBin = 0; xBin < ffts.binCount; xBin++) {
	                                   int yBin = (int) Math.floor((fft[xBin] - plotMinY) / (plotMaxY - plotMinY) * yBinCount);
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
	                           OpenGL.drawHistogram(gl, histogramTexHandle, datasets.normalDatasets.get(datasetN).color.getGl(), fullScale, gammaSlider.get(), 0, 0, plot.width(), plot.height(), 1f/xBinCount/2f);
	                       }
		                   
		               } else if(chartType.is(Type.WATERFALL)) {
		                   
		                   fftBinsPerPlotBin = xAxisBinsAutomatic.isFalse() ? Math.ceilDiv(ffts.binCount, xAxisBinCount.get()) :
		                                                                      Math.ceilDiv(ffts.binCount, (plot.width() / 2));
		                   int xBinCount = Math.ceilDiv(ffts.binCount, fftBinsPerPlotBin);
		                   int yBinCount = fftCount.get();
		                   float gamma = Math.max(0.01f, gammaSlider.get()); // clipping to 0.01f because pow(a, 0) is always 1
		                   
	                       for(int datasetN = 0; datasetN < datasetsCount; datasetN++) {
	                           
	                           ByteBuffer bytes = Buffers.newDirectByteBuffer(xBinCount * yBinCount * 16); // pixelCount * four float32 per pixel
	                           FloatBuffer floats = bytes.asFloatBuffer();
	                           float datasetR = datasets.normalDatasets.get(datasetN).color.getGl()[0];
	                           float datesetG = datasets.normalDatasets.get(datasetN).color.getGl()[1];
	                           float datasetB = datasets.normalDatasets.get(datasetN).color.getGl()[2];
	                           
	                           for(int windowN = 0; windowN < ffts.windows.size(); windowN++) {
	                               
	                               int fftN = ffts.windows.size() - 1 - windowN;						
	                               float[] fft = ffts.windows.get(fftN).get(datasetN);
	                               
	                               for(int binN = 0; binN < ffts.binCount; binN += fftBinsPerPlotBin) {
	                                   
	                                   float alpha = (fft[binN] - plotMinPower) / (plotMaxPower - plotMinPower);
	                                   if(fftBinsPerPlotBin > 1) {
	                                       int lastBin = Math.min(binN + fftBinsPerPlotBin, ffts.binCount - 1);
	                                       for(int bin = binN+1; bin <= lastBin; bin++)
	                                           alpha += (fft[bin] - plotMinPower) / (plotMaxPower - plotMinPower);
	                                       alpha /= lastBin - binN + 1;
	                                   }
	                                   if(gamma != 1)
	                                	   alpha = (float) Math.pow(alpha, gamma);
	                                   
	                                   // simulating glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
	                                   int index = ((binN / fftBinsPerPlotBin) + (windowN * xBinCount)) * 4; // 4 floats per pixel
	                                   float r = (datasetR * alpha) + (floats.get(index + 0) * (1f - alpha));
	                                   float g = (datesetG * alpha) + (floats.get(index + 1) * (1f - alpha));
	                                   float b = (datasetB * alpha) + (floats.get(index + 2) * (1f - alpha));
	                                   float a = (      1f * alpha) + (floats.get(index + 3) * (1f - alpha));
	                                   
	                                   floats.put(index + 0, r);
	                                   floats.put(index + 1, g);
	                                   floats.put(index + 2, b);
	                                   floats.put(index + 3, a);
	                                   
	                               }
	                               
	                           }
	                           
		                       if(waterfallTexHandle == null) {
		                           waterfallTexHandle = new int[1];
		                           OpenGL.createTexture(gl, waterfallTexHandle, xBinCount, yBinCount, GL3.GL_RGBA, GL3.GL_FLOAT, false);
		                       }
		                       OpenGL.writeTexture(gl, waterfallTexHandle, xBinCount, yBinCount, GL3.GL_RGBA, GL3.GL_FLOAT, bytes);
		                       OpenGL.drawTexturedBox(gl, waterfallTexHandle, false, 0, 0, plot.width(), plot.height(), 1f/xBinCount/2f, false);
	                           
	                       }
		                   
		               }
		               return null;
		           })
		           .withTooltipDrawer(plot -> {
		        	   if(!ffts.exist)
		        		   return null;
		               if(chartType.is(Type.SINGLE)) {
		                   
		                   // map mouseX to a frequency bin, and anchor the tooltip over that frequency bin
		                   int binN = (int) ((float) plot.mouseX() / plot.width() * (ffts.binCount - 1) + 0.5f);
		                   if(binN > ffts.binCount - 1)
		                       binN = ffts.binCount - 1;
		                   float frequency = (float) (binN * ffts.binSizeHz);
		                   int xAnchor = (int) ((frequency - plotMinX) / domain * plot.width());
		                   
		                   // get the power levels for each dataset
		                   Tooltip tooltip = new Tooltip(getFrequencyRangeString(binN, binN, ffts), xAnchor, -1);
		                   List<float[]> fftOfDataset = ffts.windows.get(0);
		                   for(int datasetN = 0; datasetN < datasetsCount; datasetN++)
		                       tooltip.addRow(datasets.getNormal(datasetN).color.getGl(),
		                                      Theme.getLog10float(fftOfDataset.get(datasetN)[binN], "Watts"),
		                                      (float) Math.max((int) ((fftOfDataset.get(datasetN)[binN] - plotMinY) / plotRange * plot.height()), 0));
		                   tooltip.draw(gl, plot.mouseX(), plot.mouseY(), plot.width(), plot.height(), false);
		                   
		               } else if(chartType.is(Type.HISTOGRAM)) {
		                   
		                   int xBinCount = histogram[0][0].length - 1; // -1 because the histogram is stretched so the first and last bins are only half width
		                   int yBinCount = histogram[0].length;
		                   
		                   // map mouse (x,y) to a frequency bin and power bin
		                   int xBin =      (int) Math.min(xBinCount,     (float) plot.mouseX() / plot.width()  * xBinCount + 0.5f);
		                   int yBin = Math.round(Math.min(yBinCount - 1, (float) plot.mouseY() / plot.height() * yBinCount - 0.5f));
		                   
		                   // draw an outline around the histogram bin if it's not too small
		                   float binWidthPixels  = (float) plot.width()  / (float) xBinCount;
		                   float binHeightPixels = (float) plot.height() / (float) yBinCount;
		                   if(binWidthPixels > 4 * Theme.lineWidth && binHeightPixels > 4 * Theme.lineWidth) {
		                       int xBoxLeft   = Math.round(xBin * binWidthPixels - (binWidthPixels / 2));
		                       int yBoxBottom = Math.round(yBin * binHeightPixels);
		                       OpenGL.drawBoxOutline(gl, Theme.plotOutlineColor, xBoxLeft, yBoxBottom, binWidthPixels, binHeightPixels);
		                   }
		                   
		                   // draw the tooltip
		                   int xAnchor = (int)  ((float) xBin         / (float) xBinCount * plot.width());
		                   int yAnchor = (int) (((float) yBin + 0.5f) / (float) yBinCount * plot.height());
		                   int firstFftBin = xBin * fftBinsPerPlotBin;
		                   int lastFftBin  = Math.min(firstFftBin + fftBinsPerPlotBin - 1, ffts.binCount - 1);
		                   float minPow = (float)  yBin      / (float) yBinCount * (plotMaxY - plotMinY) + plotMinY;
		                   float maxPow = (float) (yBin + 1) / (float) yBinCount * (plotMaxY - plotMinY) + plotMinY;
		                   String tooltipLabel = getFrequencyRangeString(firstFftBin, lastFftBin, ffts) + "\n" +
		                                         Theme.getLog10float(minPow, "W") + " - " + Theme.getLog10float(maxPow, "W");
		                   Tooltip tooltip = new Tooltip(tooltipLabel, xAnchor, yAnchor);
		                   for(int datasetN = 0; datasetN < datasetsCount; datasetN++)
		                       tooltip.addRow(datasets.getNormal(datasetN).color.getGl(),
		                                      Theme.getFloat((float) histogram[datasetN][yBin][xBin] / (float) (ffts.windows.size() * fftBinsPerPlotBin) * 100f, "%", true));
		                   
		                   tooltip.draw(gl, plot.mouseX(), plot.mouseY(), plot.width(), plot.height(), false);
		                   
		               } else if(chartType.is(Type.WATERFALL)) {
		                   
		                   int xBinCount = Math.ceilDiv(ffts.binCount, fftBinsPerPlotBin) - 1; // -1 because the waterfall is stretched so the first and last bins are only half width
		                   int yBinCount = fftCount.get();
		                   
		                   // map mouse (x,y) to a frequency bin and FFT
		                   int xBin =      (int) Math.min(xBinCount,     (float) plot.mouseX() / plot.width()  * xBinCount + 0.5f);
		                   int yBin = Math.round(Math.min(yBinCount - 1, (float) plot.mouseY() / plot.height() * yBinCount - 0.5f));
		                   
		                   if(yBin < ffts.windows.size()) {
			                   // draw an outline around the waterfall bin if it's not too small
			                   float binWidthPixels  = (float) plot.width()  / (float) xBinCount;
			                   float binHeightPixels = (float) plot.height() / (float) yBinCount;
			                   if(binWidthPixels > 4 * Theme.lineWidth && binHeightPixels > 4 * Theme.lineWidth) {
			                       int xBoxLeft   = Math.round(xBin * binWidthPixels - (binWidthPixels / 2));
			                       int yBoxBottom = Math.round(yBin * binHeightPixels);
			                       OpenGL.drawBoxOutline(gl, Theme.plotOutlineColor, xBoxLeft, yBoxBottom, binWidthPixels, binHeightPixels);
			                   }
		                	   
			                   // draw the tooltip
		                       int xAnchor =   (int) ((float)  xBin         / (float) xBinCount * plot.width());
		                       int yAnchor =   (int) ((float) (yBin + 0.5f) / (float) yBinCount * plot.height());
		                       float secondsElapsed = (float) (yBin + 0.5f) / (float) yBinCount * plotMaxTime;
		                       int firstFftBin = xBin * fftBinsPerPlotBin;
		                       int lastFftBin  = Math.min(firstFftBin + fftBinsPerPlotBin - 1, ffts.binCount - 1);
		                       Tooltip tooltip = new Tooltip(getFrequencyRangeString(firstFftBin, lastFftBin, ffts) + "\n" + Theme.getFloatOrInteger(secondsElapsed, "seconds ago", true), xAnchor, yAnchor);
		                       for(int datasetN = 0; datasetN < datasetsCount; datasetN++) {
		                           float power = 0;
		                           for(int bin = firstFftBin; bin <= lastFftBin; bin++)
		                               power += ffts.windows.get(ffts.windows.size() - yBin - 1).get(datasetN)[bin];
		                           power /= lastFftBin - firstFftBin + 1;
		                           tooltip.addRow(datasets.getNormal(datasetN).color.getGl(),
		                                          Theme.getLog10float(power, "Watts"));
		                       }
		                       tooltip.draw(gl, plot.mouseX(), plot.mouseY(), plot.width(), plot.height(), false);
		                   }
		               }
		               return null;
		           })
		           .draw(gl);
		
	}
	
	/**
	 * @param firstBin    First bin, inclusive.
	 * @param lastBin     Last bin, inclusive.
	 * @param fft         The FFTs.
	 * @return            The string, for example "123.45 - 123.56 Hz" etc.
	 */
	private String getFrequencyRangeString(int firstBin, int lastBin, FFTs fft) {
		
		float minFrequency = (float) (firstBin * fft.binSizeHz) - (float) (fft.binSizeHz / 2);
		float maxFrequency = (float) (lastBin  * fft.binSizeHz) + (float) (fft.binSizeHz / 2);
		minFrequency = Math.max(minFrequency, 0);
		maxFrequency = Math.min(maxFrequency, fft.maxHz);
		return Theme.getFloat(minFrequency, "", true) + " - " + Theme.getFloat(maxFrequency, "Hz", true);
		
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
	
	private class FFTcache {
		
		private FFT[]       cachedFFT = new FFT[0]; // used as a ring buffer
		private int         previousSampleCount = 0;
		private int         previousFFTsCount = 0;
		private float       previousIgnoreOutliersAmount = 0;
		private List<Field> previousDatasets = new ArrayList<Field>();
		
		/**
		 * Calculates the FFTs.
		 * 
		 * For Single mode, the FFT will be of the most recent samples, not aligned to the window size.
		 * Example: a window size of 1000, with most recent sample being 1234, will make an FFT of samples 235-1234.
		 * 
		 * For Histogram or Waterfall mode, the FFTs will be aligned to their window size.
		 * Example: a window size of 1000 will make FFTs of samples 0-999, 1000-1999, etc.
		 * 
		 * @param endSampleNumber         Sample number corresponding with the right edge of a time-domain plot. NOTE: this sample might not exist yet!
		 * @param sampleCount             How many samples make up each FFT window.
		 * @param fftsCount               Number of FFTs to calculate.
		 * @param ignoreOutliersAmount    If >0, the min and max power calculations should ignore outliers. Example: 1 = ignore bottom 1% and top 1%.
		 * @param datasets                Datasets to FFT.
		 */
		public FFTs getFFTs(int endSampleNumber, int sampleCount, int fftsCount, float ignoreOutliersAmount, DatasetsInterface datasets) {
			
			// flush the cache if necessary
			if(previousSampleCount != sampleCount || !previousDatasets.equals(datasets.normalDatasets) || previousFFTsCount != fftsCount || previousIgnoreOutliersAmount != ignoreOutliersAmount) {
				
				cachedFFT = new FFT[fftsCount];
				previousSampleCount = sampleCount;
				previousFFTsCount = fftsCount;
				previousIgnoreOutliersAmount = ignoreOutliersAmount;
				previousDatasets = new ArrayList<Field>(datasets.normalDatasets); // must copy the list so we can detect changes
				
			}
			
			// stop if nothing to do
			if(!datasets.hasNormals() || sampleCount < 2)
				return new FFTs();
			
			// calculate the FFTs
			if(fftsCount == 1) {
				
				int trueLastSampleNumber = datasets.hasNormals() ? datasets.connection.getSampleCount() - 1 : 0;
				int lastSampleNumber     = Math.max(0, Integer.min(endSampleNumber, trueLastSampleNumber));
				int firstSampleNumber    = Math.max(0, lastSampleNumber - sampleCount + 1);
				sampleCount = lastSampleNumber - firstSampleNumber + 1;
				
				// stop if nothing to do
				if(sampleCount < 2)
					return new FFTs();
				
				// calculate the FFT for each dataset
				FFT fft = new FFT(datasets, firstSampleNumber, lastSampleNumber, ignoreOutliersAmount);
				
				// calculate the domain
				// the FFT is calculated from DC to Nyquist
				// but the user can specify an arbitrary window length, so the max frequency may actually be a little below Nyquist
				int sampleRate = datasets.connection.getSampleRate();
				double binSizeHz = (double) sampleRate / (double) sampleCount;
				int binCount = sampleCount / 2 + 1;
				float minHz = 0;
				float maxHz = (float) ((double) (fft.ofDatasets.getFirst().length - 1) * (double) sampleRate / (double) sampleCount);
				float minPower = fft.minPower;
				float maxPower = fft.maxPower;
				if(minPower == maxPower) {
					float value = minPower;
					minPower = value - 0.001f;
					maxPower = value + 0.001f;
				}
				return new FFTs(true,
				                binSizeHz,
				                binCount,
				                minHz,
				                maxHz,
				                minPower,
				                maxPower,
				                sampleCount,
				                List.of(fft.ofDatasets));
				
			} else {
				
				int trueLastSampleNumber = datasets.connection.getSampleCount() - 1;
				int lastFFT  = (endSampleNumber + 1) / sampleCount - 1;
				int firstFFT = Math.max(0, lastFFT - fftsCount + 1);
				
				// stop if nothing to do
				if(lastFFT < 0)
					return new FFTs();

				// calculate the FFTs for each dataset
				for(int fftN = firstFFT; fftN <= lastFFT; fftN++) {
					int firstSampleNumber = fftN * sampleCount;
					int lastSampleNumber = firstSampleNumber + sampleCount - 1;
					FFT fft = cachedFFT[fftN % fftsCount];
					if(fft == null || fft.firstSampleNumber != firstSampleNumber) {
						cachedFFT[fftN % fftsCount] = null;
						if(lastSampleNumber <= trueLastSampleNumber)
							cachedFFT[fftN % fftsCount] = new FFT(datasets, firstSampleNumber, lastSampleNumber, ignoreOutliersAmount);
					}
				}
				
				// calculate the domain and range
				// the FFTs are calculated from DC to Nyquist
				// but the user can specify an arbitrary window length, so the max frequency may actually be a little below Nyquist
				FFT fft = cachedFFT[firstFFT % fftsCount];
				int sampleRate = datasets.connection.getSampleRate();
				double binSizeHz = (double) sampleRate / (double) sampleCount;
				int binCount = sampleCount / 2 + 1;
				float minHz = 0;
				float maxHz = (float) ((double) (fft.ofDatasets.getFirst().length - 1) * (double) sampleRate / (double) sampleCount);
				float minPower = fft.minPower;
				float maxPower = fft.maxPower;
				for(int dftN = firstFFT + 1; dftN <= lastFFT; dftN++) {
					fft = cachedFFT[dftN % fftsCount];
					if(fft != null) {
						minPower = Math.min(minPower, fft.minPower);
						maxPower = Math.max(maxPower, fft.maxPower);
					}
				}
				if(minPower == maxPower) {
					float value = minPower;
					minPower = value - 0.001f;
					maxPower = value + 0.001f;
				}
				
				List<List<float[]>> windows = new ArrayList<List<float[]>>(lastFFT - firstFFT + 1);
				for(int fftN = firstFFT; fftN <= lastFFT; fftN++)
					windows.add(cachedFFT[fftN % fftsCount].ofDatasets);
				
				return new FFTs(true,
				                binSizeHz,
				                binCount,
				                minHz,
				                maxHz,
				                minPower,
				                maxPower,
				                sampleCount,
				                windows);
				
			}
			
		}
		
		private static class FFT {
			
			private static DoubleFFT_1D fft = null;
			private static int fftSampleCount = 0;
			
			final List<float[]> ofDatasets = new ArrayList<float[]>();  // .get(datasetN)[binN]
			final int firstSampleNumber;
			float minPower =  Float.MAX_VALUE;
			float maxPower = -Float.MAX_VALUE;
			
			public FFT(DatasetsInterface datasets, int firstSampleNumber, int lastSampleNumber, float ignoreOutliersAmount) {
				
				this.firstSampleNumber = firstSampleNumber;
				datasets.forEachNormal((dataset, cache) -> {
					
					double[] samples = dataset.getSamplesArray(firstSampleNumber, lastSampleNumber, cache);
					
					// important: use float64, not float32, for better FFT resolution
					if(fft == null || fftSampleCount != samples.length) {
						fft = new DoubleFFT_1D(samples.length);
						fftSampleCount = samples.length;
					}
					fft.realForward(samples);
					int binCount = samples.length / 2 + 1;
					
					// if we should ignore outliers when autoscaling, we will need to calculate percentiles
					DescriptiveStatistics stats = null;
					if(ignoreOutliersAmount > 0)
						stats = new DescriptiveStatistics(binCount);
					
					// the FFT provides real/imaginary magnitudes
					// convert magnitudes to powers, and keep track of the min and max values
					double sampleCount = samples.length;
					float[]  powerLevels = new float[binCount];
					
					for(int binN = 0; binN < binCount; binN++) {
						double realV;
						double imaginaryV;
						if(binN == 0) {
							realV = samples[2*binN + 0];
							imaginaryV = 0;
						} else if(binN == binCount - 1) {
							realV = samples[1];
							imaginaryV = 0;
						} else {
							realV      = samples[2*binN + 0];
							imaginaryV = samples[2*binN + 1];
						}
						realV      /= sampleCount;
						imaginaryV /= sampleCount;
						double powerW = (realV * realV) + (imaginaryV * imaginaryV);
						powerW *= 2; // because DFT is from -Fs to +Fs
						
						// ensure powerW != 0, which would cause the Math.log10() below to return -Infinity
						if(powerW == 0)
							powerW = Math.pow(10, -36); // arbitrary, looks reasonable
						float log10power = (float) Math.log10(powerW);
						minPower = Math.min(minPower, log10power);
						maxPower = Math.max(maxPower, log10power);
						
						powerLevels[binN] = log10power;
						
						if(ignoreOutliersAmount != 0)
							stats.addValue(log10power);
					}
					
					ofDatasets.add(powerLevels);
					
					// if we should ignore outliers when autoscaling, recalculate the range accordingly
					if(ignoreOutliersAmount > 0) {
						float lower = (float) stats.getPercentile(  0 + ignoreOutliersAmount);
						float upper = (float) stats.getPercentile(100 - ignoreOutliersAmount);
						if(Float.isFinite(lower) && Float.isFinite(upper)) {
							minPower = lower;
							maxPower = upper;
						}
					}
					
				});
				
			}
			
		}
		
	}

}
