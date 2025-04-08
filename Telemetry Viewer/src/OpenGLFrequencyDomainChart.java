import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
	private WidgetToggleButton<OpenGLPlot.AxisStyle> xAxisStyle;
	private WidgetToggleButton<OpenGLPlot.AxisStyle> yAxisStyle;
	
	private int[][][] histogram = null; // only populated in Histogram mode
	private int actualYaxisBins = 0;
	
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
		                  ConfigureView.instance.redrawIfUsedFor(this); // important: may need to revalidate/redraw the parent!
		                  return true;
		              });
		
		minimumPower = WidgetTextfield.ofFloat(Float.MIN_VALUE, Float.MAX_VALUE, 1e-15f)
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
		
		maximumPower = WidgetTextfield.ofFloat(Float.MIN_VALUE, Float.MAX_VALUE, 10f)
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
		
		fftInfoVisibility = new WidgetCheckbox("Show FFT Info", true)
		                        .setExportLabel("fft show info");
		
		xAxisStyle = new WidgetToggleButton<OpenGLPlot.AxisStyle>("", OpenGLPlot.AxisStyle.values(), OpenGLPlot.AxisStyle.OUTER)
		                 .setExportLabel("x-axis style");
		
		yAxisStyle = new WidgetToggleButton<OpenGLPlot.AxisStyle>("", OpenGLPlot.AxisStyle.values(), OpenGLPlot.AxisStyle.OUTER)
		                 .setExportLabel("y-axis style");
		
		widgets.add(datasetsWidget);
		widgets.add(sampleCountTextfield);
		widgets.add(legendVisibility);
		widgets.add(chartStyle);
		widgets.add(minimumPower);
		widgets.add(minimumPowerAutomatic);
		widgets.add(maximumPower);
		widgets.add(maximumPowerAutomatic);
		widgets.add(fftCount);
		widgets.add(xAxisBinsTextfield);
		widgets.add(xAxisBinsAutomatic);
		widgets.add(yAxisBinsTextfield);
		widgets.add(yAxisBinsAutomatic);
		widgets.add(gamma);
		widgets.add(fftInfoVisibility);
		widgets.add(xAxisStyle);
		widgets.add(yAxisStyle);
		
	}
	
	@Override public void getConfigurationGui(JPanel gui) {
		
		gui.add(Theme.newWidgetsPanel("Data")
		             .with(datasetsWidget)
		             .with(sampleCountTextfield,  "split 2, grow x, grow y, sizegroup 0")
		             .with(legendVisibility,      "grow x, grow y, sizegroup 0")
		             .getPanel());
		
		gui.add(Theme.newWidgetsPanel("FFTs")
		             .with(chartStyle)
		             .with(minimumPower,          "split 2, grow x, grow y")
		             .with(minimumPowerAutomatic, "sizegroup 1")
		             .with(maximumPower,          "split 2, grow x, grow y")
		             .with(maximumPowerAutomatic, "sizegroup 1")
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
		             .with(xAxisStyle)
		             .getPanel());
		
		gui.add(Theme.newWidgetsPanel("Y-Axis")
		             .with(yAxisStyle)
		             .getPanel());
		
	}
	
	@Override public EventHandler drawChart(GL2ES3 gl, float[] chartMatrix, int width, int height, long endTimestamp, int endSampleNumber, double zoomLevel, int mouseX, int mouseY) {
		
		int datasetsCount = datasets.normalsCount();
		
		// calculate the FFTs
		FFTs fft = cache.getFFT(endSampleNumber, duration, fftCount.get(), datasets, chartStyle.get());
		
		// determine the x-axis range
		float plotMinX = fft.minHz;
		float plotMaxX = fft.maxHz;
		float domain = plotMaxX - plotMinX;
		
		// determine the y-axis range and ensure it's >0
		// the y-axis is power (single/histogram modes) or time (waterfall mode)
		float sampleRate = datasets.hasNormals() ? datasets.connection.getSampleRate() : 1;
		float plotMinTime = 0;
		float plotMaxTime = (float) (duration * fftCount.get()) / sampleRate;

		float minPower = fft.minPower;
		float maxPower = fft.maxPower;
		if(minPower == maxPower) {
			float value = minPower;
			minPower = value - 0.001f;
			maxPower = value + 0.001f;
		}
		autoscalePower.update(minPower, maxPower);
		
		float plotMinPower = (minimumPowerAutomatic.get() && !chartStyle.is(ChartStyle.WATERFALL)) ? autoscalePower.getMin() :
		                     (minimumPowerAutomatic.get() &&  chartStyle.is(ChartStyle.WATERFALL)) ? minPower :
		                                                                                             (float) Math.log10(minimumPower.get());
		float plotMaxPower = (maximumPowerAutomatic.get() && !chartStyle.is(ChartStyle.WATERFALL)) ? autoscalePower.getMax() :
		                     (maximumPowerAutomatic.get() &&  chartStyle.is(ChartStyle.WATERFALL)) ? maxPower :
		                                                                                             (float) Math.log10(maximumPower.get());
		float plotMinY = chartStyle.is(ChartStyle.WATERFALL) ? plotMinTime : plotMinPower;
		float plotMaxY = chartStyle.is(ChartStyle.WATERFALL) ? plotMaxTime : plotMaxPower;
		float plotRange = plotMaxY - plotMinY;
		
		// determine the axis titles
		String xAxisTitle = "Frequency";
		String yAxisTitle = chartStyle.is(ChartStyle.WATERFALL) ? "Time" : "Power";
		
		// draw the plot
		return new OpenGLPlot(chartMatrix, width, height, mouseX, mouseY)
		           .withLegend(legendVisibility.get(), datasets)
		           .withXaxis(xAxisStyle.get(), OpenGLPlot.AxisScale.LINEAR, plotMinX, plotMaxX, xAxisTitle)
		           .withYaxis(yAxisStyle.get(), chartStyle.is(ChartStyle.WATERFALL) ? OpenGLPlot.AxisScale.LINEAR : OpenGLPlot.AxisScale.LOG, plotMinY, plotMaxY, yAxisTitle)
		           .withFftInfo(fftInfoVisibility.get(), chartStyle.get(), fft.windowLength, fft.windows.size(), plotMinPower, plotMaxPower)
		           .withPlotDrawer(plot -> {
		               if(fft.exists && chartStyle.is(ChartStyle.SINGLE)) {
		                   // the matrix is currently configured so (0,0) is the bottom-left of the plot, with units of pixels
		                   // but x will be auto-generated, ranging from 0 to binCount-1
		                   // and y will be FFT results, ranging from plotMinPower to plotMaxPower
		                   // we can scale x into pixels with: x =            (x) / plotDomain * plotWidth
		                   // we can scale y into pixels with: y = (y - plotMinY) / plotRange  * plotHeight;
		                   float[] matrix = Arrays.copyOf(plot.matrix(), 16);
		                   OpenGL.scaleMatrix    (matrix, plot.width()/(float) (fft.binCount-1), plot.height()/(plotMaxPower - plotMinPower), 1);
		                   OpenGL.translateMatrix(matrix,                                     0,                              -plotMinPower,  0);
		                   OpenGL.useMatrix(gl, matrix);
		                   
		                   // draw the FFTs as line charts, and also draw points if there are relatively few bins on screen
		                   for(int datasetN = 0; datasetN < datasets.normalDatasets.size(); datasetN++) {
		                       FloatBuffer buffer = Buffers.newDirectFloatBuffer(fft.windows.get(0).get(datasetN));
		                       OpenGL.drawLinesY(gl, GL3.GL_LINE_STRIP, datasets.normalDatasets.get(datasetN).color.getGl(), buffer, fft.binCount, 0);
		                       if(width / fft.binCount > 2 * Theme.pointWidth)
		                           OpenGL.drawPointsY(gl, datasets.normalDatasets.get(datasetN).color.getGl(), buffer, fft.binCount, 0);
		                   }
		               } else if(fft.exists && chartStyle.is(ChartStyle.HISTOGRAM)) {
		                   
		                   int xBinCount = fft.binCount;
		                   int yBinCount = yAxisBinsAutomatic.get() ? (int) plot.height() / 2 : yAxisBins;
		                   
		                   fftBinsPerPlotBin = 1;
		                   while((xAxisBinsAutomatic.get() && xBinCount > plot.width() / 2) || (!xAxisBinsAutomatic.get() && xBinCount > xAxisBins)) {
		                       fftBinsPerPlotBin++;
		                       xBinCount = (int) Math.ceil((double) fft.binCount / (double) fftBinsPerPlotBin);
		                   }
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
		                           OpenGL.drawHistogram(gl, histogramTexHandle, datasets.normalDatasets.get(datasetN).color.getGl(), fullScale, gamma.getFloat(), 0, 0, (int) plot.width(), (int) plot.height(), 1f/xBinCount/2f);
		                       }
		                   }
		                   
		               } else if(fft.exists && chartStyle.is(ChartStyle.WATERFALL)) {
		                   
		                   int binCount = fft.binCount;
		                   
		                   fftBinsPerPlotBin = 1;
		                   while((xAxisBinsAutomatic.get() && binCount > plot.width() / 2) || (!xAxisBinsAutomatic.get() && binCount > xAxisBins)) {
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
		                       OpenGL.drawTexturedBox(gl, waterfallTexHandle, false, 0, 0, (int) plot.width(), (int) plot.height(), 1f/binCount/2f, false);
		                   }
		                   
		               }
		               return null;
		           })
		           .withTooltipDrawer(plot -> {
		               if(fft.exists && chartStyle.is(ChartStyle.SINGLE)) {
		                   
		                   // map mouseX to a frequency bin, and anchor the tooltip over that frequency bin
		                   int binN = (int) ((float) plot.mouseX() / plot.width() * (fft.binCount - 1) + 0.5f);
		                   if(binN > fft.binCount - 1)
		                       binN = fft.binCount - 1;
		                   float frequency = (float) (binN * fft.binSizeHz);
		                   int xAnchor = (int) ((frequency - plotMinX) / domain * plot.width());
		                   
		                   // get the power levels for each dataset
		                   Tooltip tooltip = new Tooltip(getFrequencyRangeString(binN, binN, fft), xAnchor, -1);
		                   List<float[]> fftOfDataset = fft.windows.get(0);
		                   for(int datasetN = 0; datasetN < datasetsCount; datasetN++)
		                       tooltip.addRow(datasets.getNormal(datasetN).color.getGl(),
		                                      Theme.getLog10float(fftOfDataset.get(datasetN)[binN], "Watts"),
		                                      (float) Math.max((int) ((fftOfDataset.get(datasetN)[binN] - plotMinY) / plotRange * plot.height()), 0));
		                   tooltip.draw(gl, plot.mouseX(), plot.mouseY(), plot.width(), plot.height());
		                   
		               } else if(fft.exists && chartStyle.is(ChartStyle.HISTOGRAM)) {
		                   
		                   // map mouseX to a frequency bin, and anchor the tooltip over that frequency bin
		                   // note: one histogram bin corresponds to 1 or >1 FFT bins
		                   int histogramBinCount = histogram[0][0].length;
		                   int histogramBinN = (int) ((float) plot.mouseX() / plot.width() * (histogramBinCount - 1) + 0.5f);
		                   if(histogramBinN > histogramBinCount - 1)
		                       histogramBinN = histogramBinCount - 1;
		                   int xAnchor = (int) (((float) histogramBinN / (float) (histogramBinCount - 1)) * plot.width());
		                   int firstFftBin = histogramBinN * fftBinsPerPlotBin;
		                   int lastFftBin  = Math.min(firstFftBin + fftBinsPerPlotBin - 1, fft.binCount - 1);
		                   
		                   // map mouseY to a power bin
		                   int powerBinN = Math.round((float) plot.mouseY() / plot.height() * actualYaxisBins - 0.5f);
		                   if(powerBinN > actualYaxisBins - 1)
		                       powerBinN = actualYaxisBins - 1;
		                   float minPow = (float) powerBinN / (float) actualYaxisBins * (plotMaxPower - plotMinPower) + plotMinPower;
		                   float maxPow = (float) (powerBinN + 1) / (float) actualYaxisBins * (plotMaxPower - plotMinPower) + plotMinPower;
		                   
		                   // get the bin value for each dataset
		                   int[] windowCountForDataset = new int[datasetsCount];
		                   for(int datasetN = 0; datasetN < datasetsCount; datasetN++)
		                       windowCountForDataset[datasetN] = (int) Math.ceil((double) histogram[datasetN][powerBinN][histogramBinN] / (double) fftBinsPerPlotBin);
		                   int windowCount = fft.windows.size();
		                   
		                   int yAnchor = (int) (((float) powerBinN + 0.5f) / (float) actualYaxisBins * plot.height());
		                   Tooltip tooltip = new Tooltip(getFrequencyRangeString(firstFftBin, lastFftBin, fft) + "\n" + getPowerRangeString(minPow, maxPow), xAnchor, yAnchor);
		                   for(int datasetN = 0; datasetN < datasetsCount; datasetN++)
		                       tooltip.addRow(datasets.getNormal(datasetN).color.getGl(),
		                                      Theme.getFloat((float) windowCountForDataset[datasetN] / (float) windowCount * 100f, "%", true) + " (" + windowCountForDataset[datasetN] + " of " + windowCount + " FFTs)");
		                   
		                   tooltip.draw(gl, plot.mouseX(), plot.mouseY(), plot.width(), plot.height());
		                   
		               } else if(fft.exists && chartStyle.is(ChartStyle.WATERFALL)) {
		                   
		                   // map mouseX to a frequency bin, and anchor the tooltip over that frequency bin
		                   // note: one histogram bin corresponds to 1 or >1 FFT bins
		                   int histogramBinCount = (int) Math.ceil((double) fft.binCount / (double) fftBinsPerPlotBin);
		                   int histogramBinN = (int) ((float) plot.mouseX() / plot.width() * (histogramBinCount - 1) + 0.5f);
		                   if(histogramBinN > histogramBinCount - 1)
		                       histogramBinN = histogramBinCount - 1;
		                   int xAnchor = (int) (((float) histogramBinN / (float) (histogramBinCount - 1)) * plot.width());
		                   int firstFftBin = histogramBinN * fftBinsPerPlotBin;
		                   int lastFftBin  = Math.min(firstFftBin + fftBinsPerPlotBin - 1, fft.binCount - 1);
		                   
		                   // map mouseY to a time
		                   int waterfallRowCount = fftCount.get();
		                   int waterfallRowN = Math.round((float) plot.mouseY() / plot.height() * waterfallRowCount - 0.5f);
		                   if(waterfallRowN > waterfallRowCount - 1)
		                       waterfallRowN = waterfallRowCount - 1;
		                   int trueLastSampleNumber = endSampleNumber - (endSampleNumber % fft.windowLength);
		                   int rowLastSampleNumber = trueLastSampleNumber - (waterfallRowN * fft.windowLength) - 1;
		                   int rowFirstSampleNumber = rowLastSampleNumber - fft.windowLength + 1;
		                   if(rowFirstSampleNumber >= 0) {
		                       // mouse is over an FFT, so proceed with the tooltip
		                       float secondsElapsed = ((float) waterfallRowN + 0.5f) / (float) waterfallRowCount * plotMaxTime;
		                       int yAnchor = (int) (((float) waterfallRowN + 0.5f) / (float) waterfallRowCount * plot.height());
		                       Tooltip tooltip = new Tooltip(getFrequencyRangeString(firstFftBin, lastFftBin, fft) + "\n" + Theme.getFloatOrInteger(secondsElapsed, "seconds ago", true), xAnchor, yAnchor);
		                       
		                       // get the power levels for each dataset
		                       for(int datasetN = 0; datasetN < datasetsCount; datasetN++) {
		                           float power = 0;
		                           for(int bin = firstFftBin; bin <= lastFftBin; bin++)
		                               power += fft.windows.get(fft.windows.size() - waterfallRowN - 1).get(datasetN)[bin];
		                           power /= lastFftBin - firstFftBin + 1;
		                           tooltip.addRow(datasets.getNormal(datasetN).color.getGl(),
		                                          Theme.getLog10float(power, "Watts"));
		                       }
		                       
		                       tooltip.draw(gl, plot.mouseX(), plot.mouseY(), plot.width(), plot.height());
		                   }
		               }
		               return null;
		           })
		           .draw(gl);
		
	}
	
	/**
	 * @param min    Smaller value, in log10 Watts.
	 * @param max    Larger value,  in log10 Watts.
	 * @return       The string, for example: "1.234 - 2.345 mW" etc.
	 */
	private String getPowerRangeString(float min, float max) {
		
		return Theme.getLog10float(min, "W") + " - " + Theme.getLog10float(max, "W");
		
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
