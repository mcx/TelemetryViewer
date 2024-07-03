import java.nio.FloatBuffer;
import java.util.Map;
import javax.swing.JLabel;
import javax.swing.JPanel;
import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL2ES3;
import com.jogamp.opengl.GL3;

public class OpenGLHistogramChart extends PositionedChart {
	
	FloatBuffer[] samples; // [datasetN]
	int[][] bins; // [datasetN][binN]
	FloatBuffer[] binsAsTriangles; // [datasetN], filled with binN's, for drawing
	
	private WidgetDatasetCheckboxes datasetsWidget;
	private WidgetTextfield<Integer> durationWidget;
	private WidgetCheckbox legendVisibility;
	private enum XAxisScale {
		MIN_MAX     { @Override public String toString() { return "Minimum/Maximum"; } },
		CENTER_SPAN { @Override public String toString() { return "Center/Span";     } };
	};
	private WidgetToggleButtonEnum<XAxisScale> xAxisScale;
	private WidgetTextfield<Float> xAxisMinimum;
	private WidgetCheckbox xAxisMinimumAutomatic;
	private WidgetTextfield<Float> xAxisMaximum;
	private WidgetCheckbox xAxisMaximumAutomatic;
	private WidgetTextfield<Float> xAxisCenter;
	private JLabel xAxisCenterPlaceholder;
	private WidgetTextfield<Float> xAxisSpan;
	private WidgetCheckbox xAxisSpanAutomatic;
	private WidgetTextfield<Integer> binCount;
	private WidgetCheckbox xAxisTicksVisibility;
	private WidgetCheckbox xAxisTitleVisibility;
	private enum YAxisScale {
		FREQUENCY          { @Override public String toString() { return "Frequency";          } },
		RELATIVE_FREQUENCY { @Override public String toString() { return "Relative Frequency"; } },
		BOTH               { @Override public String toString() { return "Both";               } };
	};
	private WidgetToggleButtonEnum<YAxisScale> yAxisScale;
	private WidgetTextfield<Integer> yAxisMinimumFrequency;
	private WidgetCheckbox yAxisMinimumFrequencyIsZero;
	private WidgetTextfield<Integer> yAxisMaximumFrequency;
	private WidgetCheckbox yAxisMaximumFrequencyAutomatic;
	private WidgetTextfield<Float> yAxisMinimumRelativeFrequency;
	private WidgetCheckbox yAxisMinimumRelativeFrequencyIsZero;
	private WidgetTextfield<Float> yAxisMaximumRelativeFrequency;
	private WidgetCheckbox yAxisMaximumRelativeFrequencyAutomatic;
	private WidgetCheckbox yAxisTicksVisibility;
	private WidgetCheckbox yAxisTitleVisibility;
	
	private AutoScale  yAutoscaleRelativeFrequency;
	private AutoScale  yAutoscaleFrequency;
	
	@Override public String toString() {
		
		return "Histogram";
		
	}
	
	public OpenGLHistogramChart() {

		yAutoscaleRelativeFrequency = new AutoScale(AutoScale.MODE_EXPONENTIAL, 30, 0.20f);
		yAutoscaleFrequency = new AutoScale(AutoScale.MODE_EXPONENTIAL, 30, 0.20f);
		
		// create the control widgets and event handlers
		durationWidget = WidgetTextfield.ofInt(2, Integer.MAX_VALUE / 16, ConnectionsController.getDefaultChartDuration())
		                                .setSuffix("Samples")
		                                .setExportLabel("sample count")
		                                .onChange((newDuration, oldDuration) -> {
		                                    duration = newDuration;
		                                    return true;
		                                });
		
		legendVisibility = new WidgetCheckbox("Show Legend", true);
		
		xAxisMinimum = WidgetTextfield.ofFloat(-Float.MAX_VALUE, Float.MAX_VALUE, -1)
		                              .setPrefix("Minimum")
		                              .setExportLabel("x-axis minimum")
		                              .onChange((newMinimum, oldMinumum) -> {
		                                            if(newMinimum > xAxisMaximum.get())
		                                                xAxisMaximum.set(newMinimum);
		                                            return true;
		                                        });
		
		xAxisMinimumAutomatic = new WidgetCheckbox("Automatic", true)
		                            .setExportLabel("x-axis minimum automatic")
		                            .onChange(isAutomatic -> {
		                                         if(isAutomatic)
		                                             xAxisMinimum.disableWithMessage("Automatic");
		                                         else
		                                             xAxisMinimum.setEnabled(true);
		                                     });
		
		xAxisMaximum = WidgetTextfield.ofFloat(-Float.MAX_VALUE, Float.MAX_VALUE, 1)
		                              .setPrefix("Maximum")
		                              .setExportLabel("x-axis maximum")
		                              .onChange((newMaximum, oldMaximum) -> {
		                                            if(newMaximum < xAxisMinimum.get())
		                                                xAxisMinimum.set(newMaximum);
		                                            return true;
		                                        });
		
		xAxisMaximumAutomatic = new WidgetCheckbox("Automatic", true)
		                            .setExportLabel("x-axis maximum automatic")
		                            .onChange(isAutomatic -> {
		                                         if(isAutomatic)
		                                             xAxisMaximum.disableWithMessage("Automatic");
		                                         else
		                                             xAxisMaximum.setEnabled(true);
		                                     });
		
		xAxisCenter = WidgetTextfield.ofFloat(-Float.MAX_VALUE, Float.MAX_VALUE, 0)
		                             .setPrefix("Center")
		                             .setExportLabel("x-axis center");
		
		xAxisCenterPlaceholder = new JLabel(" "); // used to keep the center textfield the same size as the span textfield
		
		xAxisSpan = WidgetTextfield.ofFloat(Float.MIN_VALUE, Float.MAX_VALUE, 2)
		                           .setPrefix("Span")
		                           .setExportLabel("x-axis span");
		
		xAxisSpanAutomatic = new WidgetCheckbox("Automatic", true)
		                         .setExportLabel("x-axis span automatic")
		                         .onChange(isAutomatic -> {
		                                      if(isAutomatic)
		                                          xAxisSpan.disableWithMessage("Automatic");
		                                      else
		                                          xAxisSpan.setEnabled(true);
		                                  });
		
		binCount = WidgetTextfield.ofInt(2, Integer.MAX_VALUE, 60)
		                          .setPrefix("Bins")
		                          .setExportLabel("x-axis bin count")
		                          .onChange((newBinCount, oldBinCount) -> {
		                                        bins = new int[datasets.normalsCount()][newBinCount];
		                                        for(int i = 0; i < datasets.normalsCount(); i++)
		                                            binsAsTriangles[i] = Buffers.newDirectFloatBuffer(newBinCount * 12);
		                                        return true;
		                                    });

		datasetsWidget = new WidgetDatasetCheckboxes(newDatasets -> {
		                                                 datasets.setNormals(newDatasets);
		                                                 int datasetsCount = datasets.normalsCount();
		                                                 samples = new FloatBuffer[datasetsCount];
		                                                 bins = new int[datasetsCount][binCount.get()];
		                                                 binsAsTriangles = new FloatBuffer[datasetsCount];
		                                                 for(int i = 0; i < datasetsCount; i++)
		                                                     binsAsTriangles[i] = Buffers.newDirectFloatBuffer(binCount.get() * 12);
		                                                 if(datasets.normalsCount() == 1) {
		                                                     xAxisMinimum.setSuffix(datasets.getNormal(0).unit.get());
		                                                     xAxisMaximum.setSuffix(datasets.getNormal(0).unit.get());
		                                                     xAxisCenter.setSuffix(datasets.getNormal(0).unit.get());
		                                                     xAxisSpan.setSuffix(datasets.getNormal(0).unit.get());
		                                                 } else if(datasets.normalsCount() == 0) {
		                                                     xAxisMinimum.setSuffix("");
		                                                     xAxisMaximum.setSuffix("");
		                                                     xAxisCenter.setSuffix("");
		                                                     xAxisSpan.setSuffix("");
		                                                 }
		                                             },
		                                             null,
		                                             null,
		                                             null,
		                                             false);
		
		xAxisScale = new WidgetToggleButtonEnum<XAxisScale>("Specify as",
		                                                    "x-axis scale",
		                                                    XAxisScale.values(),
		                                                    XAxisScale.MIN_MAX,
		                                                    newScale -> {
		                                                        xAxisMinimum.setVisible(newScale == XAxisScale.MIN_MAX);
		                                                        xAxisMinimumAutomatic.setVisible(newScale == XAxisScale.MIN_MAX);
		                                                        xAxisMaximum.setVisible(newScale == XAxisScale.MIN_MAX);
		                                                        xAxisMaximumAutomatic.setVisible(newScale == XAxisScale.MIN_MAX);
		                                                        xAxisCenter.setVisible(newScale == XAxisScale.CENTER_SPAN);
		                                                        xAxisCenterPlaceholder.setVisible(newScale == XAxisScale.CENTER_SPAN);
		                                                        xAxisSpan.setVisible(newScale == XAxisScale.CENTER_SPAN);
		                                                        xAxisSpanAutomatic.setVisible(newScale == XAxisScale.CENTER_SPAN);
		                                                    });
		
		xAxisTicksVisibility = new WidgetCheckbox("Show Ticks", true)
		                           .setExportLabel("x-axis show ticks");
		
		xAxisTitleVisibility = new WidgetCheckbox("Show Title", true)
		                           .setExportLabel("x-axis show title");
		
		yAxisMinimumFrequency = WidgetTextfield.ofInt(0, Integer.MAX_VALUE, 0)
		                                       .setPrefix("Minimum")
		                                       .setExportLabel("y-axis minimum frequency")
		                                       .onChange((newMinimum, oldMinimum) -> {
		                                                     if(newMinimum > yAxisMaximumFrequency.get())
		                                                         yAxisMaximumFrequency.set(newMinimum);
		                                                     return true;
		                                                 });
		
		yAxisMinimumFrequencyIsZero = new WidgetCheckbox("Zero", true)
		                                  .setExportLabel("y-axis minimum frequency is zero")
		                                  .onChange(isZero -> {
		                                                if(isZero)
		                                                    yAxisMinimumFrequency.disableWithMessage("0");
		                                                else
		                                                    yAxisMinimumFrequency.setEnabled(true);
		                                            });
		
		yAxisMaximumFrequency = WidgetTextfield.ofInt(0, Integer.MAX_VALUE, 1000)
		                                       .setPrefix("Maximum")
		                                       .setExportLabel("y-axis maximum frequency")
		                                       .onChange((newMaximum, oldMaximum) -> {
		                                                     if(newMaximum < yAxisMinimumFrequency.get())
		                                                         yAxisMinimumFrequency.set(newMaximum);
		                                                     return true;
		                                                 });
		
		yAxisMaximumFrequencyAutomatic = new WidgetCheckbox("Automatic", true)
		                                     .setExportLabel("y-axis maximum frequency automatic")
		                                     .onChange(isAutomatic -> {
		                                                   if(isAutomatic)
		                                                       yAxisMaximumFrequency.disableWithMessage("Automatic");
		                                                   else
		                                                       yAxisMaximumFrequency.setEnabled(true);
		                                               });
		
		yAxisMinimumRelativeFrequency = WidgetTextfield.ofFloat(0, 1, 0)
		                                               .setPrefix("Minimum")
		                                               .setExportLabel("y-axis minimum relative frequency")
		                                               .onChange((newMinimum, oldMinimum) -> {
		                                                             if(newMinimum > yAxisMaximumRelativeFrequency.get())
		                                                                 yAxisMaximumRelativeFrequency.set(newMinimum);
		                                                             return true;
		                                                         });
		
		yAxisMinimumRelativeFrequencyIsZero = new WidgetCheckbox("Zero", true)
		                                          .setExportLabel("y-axis minimum relative frequency is zero")
		                                          .onChange(isZero -> {
		                                                        if(isZero)
		                                                            yAxisMinimumRelativeFrequency.disableWithMessage("0");
		                                                        else
		                                                            yAxisMinimumRelativeFrequency.setEnabled(true);
		                                                    });
		
		yAxisMaximumRelativeFrequency = WidgetTextfield.ofFloat(0, 1, 1)
		                                               .setPrefix("Maximum")
		                                               .setExportLabel("y-axis maximum relative frequency")
		                                               .onChange((newMaximum, oldMaximum) -> {
		                                                             if(newMaximum < yAxisMinimumRelativeFrequency.get())
		                                                                 yAxisMinimumRelativeFrequency.set(newMaximum);
		                                                             return true;
		                                                         });
		
		yAxisMaximumRelativeFrequencyAutomatic = new WidgetCheckbox("Automatic", true)
		                                             .setExportLabel("y-axis maximum relative frequency automatic")
		                                             .onChange(isAutomatic -> {
		                                                           if(isAutomatic)
		                                                               yAxisMaximumRelativeFrequency.disableWithMessage("Automatic");
		                                                           else
		                                                               yAxisMaximumRelativeFrequency.setEnabled(true);
		                                                       });
		
		yAxisScale = new WidgetToggleButtonEnum<YAxisScale>("Scale",
		                                                    "y-axis scale",
		                                                    YAxisScale.values(),
		                                                    YAxisScale.RELATIVE_FREQUENCY,
		                                                    newScale -> {
		                                                        yAxisMinimumFrequency.setVisible(newScale == YAxisScale.FREQUENCY);
		                                                        yAxisMinimumFrequencyIsZero.setVisible(newScale == YAxisScale.FREQUENCY);
		                                                        yAxisMaximumFrequency.setVisible(newScale == YAxisScale.FREQUENCY);
		                                                        yAxisMaximumFrequencyAutomatic.setVisible(newScale == YAxisScale.FREQUENCY);
		                                                        yAxisMinimumRelativeFrequency.setVisible(newScale != YAxisScale.FREQUENCY);
		                                                        yAxisMinimumRelativeFrequencyIsZero.setVisible(newScale != YAxisScale.FREQUENCY);
		                                                        yAxisMaximumRelativeFrequency.setVisible(newScale != YAxisScale.FREQUENCY);
		                                                        yAxisMaximumRelativeFrequencyAutomatic.setVisible(newScale != YAxisScale.FREQUENCY);
		                                                    });
		
		yAxisTicksVisibility = new WidgetCheckbox("Show Ticks", true)
		                           .setExportLabel("y-axis show ticks");
		
		yAxisTitleVisibility = new WidgetCheckbox("Show Title", true)
		                           .setExportLabel("y-axis show title");
		
		widgets.add(datasetsWidget);
		widgets.add(durationWidget);
		widgets.add(legendVisibility);
		widgets.add(xAxisScale);
		widgets.add(xAxisMinimum);
		widgets.add(xAxisMinimumAutomatic);
		widgets.add(xAxisMaximum);
		widgets.add(xAxisMaximumAutomatic);
		widgets.add(xAxisCenter);
		widgets.add(xAxisSpan);
		widgets.add(xAxisSpanAutomatic);
		widgets.add(binCount);
		widgets.add(xAxisTicksVisibility);
		widgets.add(xAxisTitleVisibility);
		widgets.add(yAxisScale);
		widgets.add(yAxisMinimumFrequency);
		widgets.add(yAxisMinimumFrequencyIsZero);
		widgets.add(yAxisMaximumFrequency);
		widgets.add(yAxisMaximumFrequencyAutomatic);
		widgets.add(yAxisMinimumRelativeFrequency);
		widgets.add(yAxisMinimumRelativeFrequencyIsZero);
		widgets.add(yAxisMaximumRelativeFrequency);
		widgets.add(yAxisMaximumRelativeFrequencyAutomatic);
		widgets.add(yAxisTicksVisibility);
		widgets.add(yAxisTitleVisibility);
		
	}
	
	@Override public void getConfigurationGui(JPanel gui) {
		
		gui.add(Theme.newWidgetsPanel("Data")
		             .with(datasetsWidget)
		             .with(durationWidget, "split 2, sizegroup 0")
		             .with(legendVisibility, "sizegroup 0")
		             .getPanel());
		
		gui.add(Theme.newWidgetsPanel("X-Axis")
		             .with(xAxisScale)
		             .with(xAxisMinimum,           "split 2, grow")
		             .with(xAxisMinimumAutomatic,  "sizegroup 1")
		             .with(xAxisMaximum,           "split 2, grow")
		             .with(xAxisMaximumAutomatic,  "sizegroup 1")
		             .with(xAxisCenter,            "split 2, grow")
		             .with(xAxisCenterPlaceholder, "sizegroup 1")
		             .with(xAxisSpan,              "split 2, grow")
		             .with(xAxisSpanAutomatic,     "sizegroup 1")
		             .withGap(Theme.padding)
		             .with(binCount,               "split 2, grow")
		             .with(new JLabel(" "),        "sizegroup 1")
		             .withGap(Theme.padding)
		             .with(xAxisTicksVisibility,   "split 2")
		             .with(xAxisTitleVisibility)
		             .getPanel());
		
		gui.add(Theme.newWidgetsPanel("Y-Axis")
		             .with(yAxisScale)
		             .with(yAxisMinimumFrequency,                  "split 2, grow")
		             .with(yAxisMinimumFrequencyIsZero,            "sizegroup 1")
		             .with(yAxisMaximumFrequency,                  "split 2, grow")
		             .with(yAxisMaximumFrequencyAutomatic,         "sizegroup 1")
		             .with(yAxisMinimumRelativeFrequency,          "split 2, grow")
		             .with(yAxisMinimumRelativeFrequencyIsZero,    "sizegroup 2")
		             .with(yAxisMaximumRelativeFrequency,          "split 2, grow")
		             .with(yAxisMaximumRelativeFrequencyAutomatic, "sizegroup 2")
		             .withGap(Theme.padding)
		             .with(yAxisTicksVisibility,                   "split 2")
		             .with(yAxisTitleVisibility)
		             .getPanel());
		
	}
	
	@Override public EventHandler drawChart(GL2ES3 gl, float[] chartMatrix, int width, int height, long endTimestamp, int endSampleNumber, double zoomLevel, int mouseX, int mouseY) {
		
		EventHandler handler = null;
		
		// get the samples
		int trueLastSampleNumber = datasets.hasNormals() ? datasets.connection.getSampleCount() - 1 : -1;
		int lastSampleNumber = Integer.min(trueLastSampleNumber, endSampleNumber);
		int firstSampleNumber = lastSampleNumber - (int) (duration * zoomLevel) + 1;
		if(firstSampleNumber < 0)
			firstSampleNumber = 0;
		
		int sampleCount = lastSampleNumber - firstSampleNumber + 1;
		int datasetsCount = datasets.normalsCount();
		if(sampleCount > 0)
			for(int datasetN = 0; datasetN < datasetsCount; datasetN++) {
				Field dataset = datasets.getNormal(datasetN);
				samples[datasetN] = datasets.getSamplesBuffer(dataset, firstSampleNumber, lastSampleNumber);
			}

		// determine the true x-axis range
		float[] minMax = datasets.getRange(firstSampleNumber, lastSampleNumber);
		float trueMinX = minMax[0];
		float trueMaxX = minMax[1];
		
		// determine the plotted x-axis range
		float minX = 0;
		float maxX = 0;
		if(xAxisScale.is(XAxisScale.CENTER_SPAN)) {
			float leftHalf  = (float) Math.abs(xAxisCenter.get() - trueMinX);
			float rightHalf = (float) Math.abs(xAxisCenter.get() - trueMaxX);
			float half = xAxisSpanAutomatic.get() ? Float.max(leftHalf, rightHalf) : xAxisSpan.get() / 2;
			minX = xAxisCenter.get() - half;
			maxX = Math.nextUp(xAxisCenter.get() + half); // increment because the bins are >=min, <max
		} else {
			minX = xAxisMinimumAutomatic.get() ?             trueMinX  :             xAxisMinimum.get();
			maxX = xAxisMaximumAutomatic.get() ? Math.nextUp(trueMaxX) : Math.nextUp(xAxisMaximum.get()); // increment because the bins are >=min, <max
		}
		float range = maxX - minX;
		int binsCount = binCount.get();
		float binSize = range / (float) binsCount;

		// calculate the histogram
		int maxBinSize = 0;
		if(sampleCount > 0) {
			// empty the bins
			for(int datasetN = 0; datasetN < datasetsCount; datasetN++)
				for(int binN = 0; binN < binsCount; binN++)
					bins[datasetN][binN] = 0;
			
			// fill the bins
			for(int datasetN = 0; datasetN < datasetsCount; datasetN++) {
				for(int sampleN = 0; sampleN < sampleCount; sampleN++) {
					float sample = samples[datasetN].get();
					if(sample >= minX && sample < maxX) {
						int binN = (int) Math.floor((sample - minX) / range * binsCount);
						if(binN == binsCount) binN--; // needed because of float math imperfection
						bins[datasetN][binN]++;
					}
				}
			}
			
			// get the max
			for(int datasetN = 0; datasetN < datasetsCount; datasetN++)
				for(int binN = 0; binN < binsCount; binN++)
					if(bins[datasetN][binN] > maxBinSize)
						maxBinSize = bins[datasetN][binN];
		}
		
		// determine the true y-axis range
		float trueMaxYfreq    = sampleCount < 1 ? 1 : maxBinSize;
		float trueMaxYrelFreq = sampleCount < 1 ? 1 : trueMaxYfreq / (float) sampleCount;
		
		// determine the plotted y-axis range
		float minYrelFreq = 0;
		float maxYrelFreq = 0;
		float minYfreq = 0;
		float maxYfreq = 0;
		float yRelFreqRange = 0;
		float yFreqRange = 0;
		if(yAxisScale.is(YAxisScale.RELATIVE_FREQUENCY) || yAxisScale.is(YAxisScale.BOTH)) {
			
			// the range is determined by relative frequency, and frequency is forced to match it
			minYrelFreq = yAxisMinimumRelativeFrequencyIsZero.get() ? 0 : yAxisMinimumRelativeFrequency.get();
			yAutoscaleRelativeFrequency.update(minYrelFreq, trueMaxYrelFreq);
			maxYrelFreq = yAxisMaximumRelativeFrequencyAutomatic.get() ? yAutoscaleRelativeFrequency.getMax() : yAxisMaximumRelativeFrequency.get();
			minYfreq = sampleCount < 1 ? minYrelFreq : minYrelFreq * (float) sampleCount;
			maxYfreq = sampleCount < 1 ? maxYrelFreq : maxYrelFreq * (float) sampleCount;
			
		} else {
			
			// the range is determined by frequency, and relative frequency is forced to match it
			minYfreq = yAxisMinimumFrequencyIsZero.get() ? 0 : yAxisMinimumFrequency.get();
			yAutoscaleFrequency.update(minYfreq, trueMaxYfreq);
			maxYfreq = yAxisMaximumFrequencyAutomatic.get() ? yAutoscaleFrequency.getMax() : yAxisMaximumFrequency.get();
			minYrelFreq = minYfreq / (float) sampleCount;
			maxYrelFreq = maxYfreq / (float) sampleCount;
			
		}
		yRelFreqRange = maxYrelFreq - minYrelFreq;
		yFreqRange = maxYfreq - minYfreq;
		
		// calculate x and y positions of everything
		float xPlotLeft = Theme.tilePadding;
		float xPlotRight = width - Theme.tilePadding;
		float plotWidth = xPlotRight - xPlotLeft;
		float yPlotTop = height - Theme.tilePadding;
		float yPlotBottom = Theme.tilePadding;
		float plotHeight = yPlotTop - yPlotBottom;
		
		float yXaxisTitleTextBasline = Theme.tilePadding;
		float yXaxisTitleTextTop = yXaxisTitleTextBasline + OpenGL.largeTextHeight;
		String xAxisTitle = datasetsCount == 0 ? "(0 Samples)" : datasets.getNormal(0).unit.get() + " (" + sampleCount + " Samples)";
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
				xOffset += OpenGL.mediumTextWidth(gl, datasets.getNormal(i).name.get()) + Theme.legendNamesPadding;
				
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
		float yXaxisTickBottom = yXaxisTickTextTop + Theme.tickTextPadding;
		float yXaxisTickTop = yXaxisTickBottom + Theme.tickLength;
		if(xAxisTicksVisibility.get()) {
			yPlotBottom = yXaxisTickTop;
			plotHeight = yPlotTop - yPlotBottom;
		}
		
		// get the y divisions now that we know the final plot height
		Map<Float, String> yDivisionsFrequency = ChartUtils.getYdivisions125(plotHeight, minYfreq, maxYfreq);
		Map<Float, String> yDivisionsRelativeFrequency = ChartUtils.getYdivisions125(plotHeight, minYrelFreq, maxYrelFreq);
		
		float xYaxisLeftTitleTextTop = 0;
		float xYaxisLeftTitleTextBaseline = 0;
		String yAxisLeftTitle = null;
		float yYaxisLeftTitleTextLeft = 0;
		float xYaxisRightTitleTextTop = 0;
		float xYaxisRightTitleTextBaseline = 0;
		String yAxisRightTitle = null;
		float yYaxisRightTitleTextLeft = 0;
		if(yAxisTitleVisibility.get()) {
			// the left y-axis is for Relative Frequency unless only Frequency will be shown
			xYaxisLeftTitleTextTop = xPlotLeft;
			xYaxisLeftTitleTextBaseline = xYaxisLeftTitleTextTop + OpenGL.largeTextHeight;
			yAxisLeftTitle = (yAxisScale.is(YAxisScale.RELATIVE_FREQUENCY) || yAxisScale.is(YAxisScale.BOTH)) ? "Relative Frequency" : "Frequency";
			yYaxisLeftTitleTextLeft = yPlotBottom + (plotHeight / 2.0f) - (OpenGL.largeTextWidth(gl, yAxisLeftTitle) / 2.0f);
			
			xPlotLeft = xYaxisLeftTitleTextBaseline + Theme.tickTextPadding;
			plotWidth = xPlotRight - xPlotLeft;
			
			if(xAxisTitleVisibility.get() && !legendVisibility.get())
				xXaxisTitleTextLeft = xPlotLeft + (plotWidth  / 2.0f) - (OpenGL.largeTextWidth(gl, xAxisTitle)  / 2.0f);
			
			// the right y-axis is always for Frequency
			if(yAxisScale.is(YAxisScale.BOTH)) {
				xYaxisRightTitleTextTop = xPlotRight;
				xYaxisRightTitleTextBaseline = xYaxisRightTitleTextTop - OpenGL.largeTextHeight;
				yAxisRightTitle = "Frequency";
				yYaxisRightTitleTextLeft = yPlotTop - (plotHeight / 2.0f) + (OpenGL.largeTextWidth(gl, yAxisRightTitle) / 2.0f);
				
				xPlotRight = xYaxisRightTitleTextBaseline - Theme.tickTextPadding;
				plotWidth = xPlotRight - xPlotLeft;
				
				if(xAxisTitleVisibility.get() && !legendVisibility.get())
					xXaxisTitleTextLeft = xPlotLeft + (plotWidth  / 2.0f) - (OpenGL.largeTextWidth(gl, xAxisTitle)  / 2.0f);
			}
		}
		
		float xYaxisLeftTickTextRight = 0;
		float xYaxisLeftTickLeft = 0;
		float xYaxisLeftTickRight = 0;
		float xYaxisRightTickTextLeft = 0;
		float xYaxisRightTickLeft = 0;
		float xYaxisRightTickRight = 0;
		if(yAxisTicksVisibility.get()) {
			// the left y-axis is for Relative Frequency unless only Frequency will be shown
			float maxTextWidth = 0;
			for(String text : yAxisScale.is(YAxisScale.FREQUENCY) ? yDivisionsFrequency.values() : yDivisionsRelativeFrequency.values())
				maxTextWidth = Math.max(maxTextWidth, OpenGL.smallTextWidth(gl, text));
			
			xYaxisLeftTickTextRight = xPlotLeft + maxTextWidth;
			xYaxisLeftTickLeft = xYaxisLeftTickTextRight + Theme.tickTextPadding;
			xYaxisLeftTickRight = xYaxisLeftTickLeft + Theme.tickLength;
			
			xPlotLeft = xYaxisLeftTickRight;
			plotWidth = xPlotRight - xPlotLeft;
			
			if(xAxisTitleVisibility.get() && !legendVisibility.get())
				xXaxisTitleTextLeft = xPlotLeft + (plotWidth  / 2.0f) - (OpenGL.largeTextWidth(gl, xAxisTitle)  / 2.0f);
			
			// the right y-axis is always for Frequency
			if(yAxisScale.is(YAxisScale.BOTH)) {
				maxTextWidth = 0;
				for(String text : yDivisionsFrequency.values())
					maxTextWidth = Math.max(maxTextWidth, OpenGL.smallTextWidth(gl, text));
				
				xYaxisRightTickTextLeft = xPlotRight - maxTextWidth;
				xYaxisRightTickRight = xYaxisRightTickTextLeft - Theme.tickTextPadding;
				xYaxisRightTickLeft = xYaxisRightTickRight - Theme.tickLength;
				
				xPlotRight = xYaxisRightTickLeft;
				plotWidth = xPlotRight - xPlotLeft;
				
				if(xAxisTitleVisibility.get() && !legendVisibility.get())
					xXaxisTitleTextLeft = xPlotLeft + (plotWidth  / 2.0f) - (OpenGL.largeTextWidth(gl, xAxisTitle)  / 2.0f);
			}
		}
		
		// get the x divisions now that we know the final plot width
		Map<Float, String> xDivisions = ChartUtils.getFloatXdivisions125(gl, plotWidth, minX, maxX);
		
		// stop if the plot is too small
		if(plotWidth < 1 || plotHeight < 1)
			return handler;
		
		// draw plot background
		OpenGL.drawQuad2D(gl, Theme.plotBackgroundColor, xPlotLeft, yPlotBottom, xPlotRight, yPlotTop);
		
		// draw the x-axis scale
		if(xAxisTicksVisibility.get()) {
			OpenGL.buffer.rewind();
			for(Float xValue : xDivisions.keySet()) {
				float x = ((xValue - minX) / range * plotWidth) + xPlotLeft;
				OpenGL.buffer.put(x); OpenGL.buffer.put(yPlotTop);    OpenGL.buffer.put(Theme.divisionLinesColor);
				OpenGL.buffer.put(x); OpenGL.buffer.put(yPlotBottom); OpenGL.buffer.put(Theme.divisionLinesColor);
				
				OpenGL.buffer.put(x); OpenGL.buffer.put(yXaxisTickTop);    OpenGL.buffer.put(Theme.tickLinesColor);
				OpenGL.buffer.put(x); OpenGL.buffer.put(yXaxisTickBottom); OpenGL.buffer.put(Theme.tickLinesColor);
			}
			OpenGL.buffer.rewind();
			int vertexCount = xDivisions.keySet().size() * 4;
			OpenGL.drawLinesXyrgba(gl, GL3.GL_LINES, OpenGL.buffer, vertexCount);
			
			for(Map.Entry<Float,String> entry : xDivisions.entrySet()) {
				float x = ((entry.getKey() - minX) / range * plotWidth) + xPlotLeft - (OpenGL.smallTextWidth(gl, entry.getValue()) / 2.0f);
				float y = yXaxisTickTextBaseline;
				OpenGL.drawSmallText(gl, entry.getValue(), (int) x, (int) y, 0);
			}
		}
		
		// draw the y-axis scale
		if(yAxisTicksVisibility.get()) {
		
			// draw right y-axis scale if showing both frequency and relative frequency
			if(yAxisScale.is(YAxisScale.BOTH)) {
				OpenGL.buffer.rewind();
				for(Float entry : yDivisionsFrequency.keySet()) {
					float y = (entry - minYfreq) / yFreqRange * plotHeight + yPlotBottom;
					OpenGL.buffer.put(xPlotRight); OpenGL.buffer.put(y); OpenGL.buffer.put(Theme.divisionLinesColor);
					OpenGL.buffer.put(xPlotLeft);  OpenGL.buffer.put(y); OpenGL.buffer.put(Theme.divisionLinesFadedColor);

					OpenGL.buffer.put(xYaxisRightTickLeft);  OpenGL.buffer.put(y); OpenGL.buffer.put(Theme.tickLinesColor);
					OpenGL.buffer.put(xYaxisRightTickRight); OpenGL.buffer.put(y); OpenGL.buffer.put(Theme.tickLinesColor);
				}
				OpenGL.buffer.rewind();
				int vertexCount = yDivisionsFrequency.keySet().size() * 4;
				OpenGL.drawLinesXyrgba(gl, GL3.GL_LINES, OpenGL.buffer, vertexCount);
	
				for(Map.Entry<Float,String> entry : yDivisionsFrequency.entrySet()) {
					float x = xYaxisRightTickTextLeft;
					float y = (entry.getKey() - minYfreq) / yFreqRange * plotHeight + yPlotBottom - (OpenGL.smallTextHeight / 2.0f);
					OpenGL.drawSmallText(gl, entry.getValue(), (int) x, (int) y, 0);
				}
				
			}
			
			// relative frequency is drawn on the left unless only frequency is to be drawn
			if(yAxisScale.is(YAxisScale.FREQUENCY)) {
				
				OpenGL.buffer.rewind();
				for(Float entry : yDivisionsFrequency.keySet()) {
					float y = (entry - minYfreq) / yFreqRange * plotHeight + yPlotBottom;
					OpenGL.buffer.put(xPlotLeft);  OpenGL.buffer.put(y); OpenGL.buffer.put(Theme.divisionLinesColor);
					OpenGL.buffer.put(xPlotRight); OpenGL.buffer.put(y); OpenGL.buffer.put(Theme.divisionLinesColor);

					OpenGL.buffer.put(xYaxisLeftTickLeft);  OpenGL.buffer.put(y); OpenGL.buffer.put(Theme.tickLinesColor);
					OpenGL.buffer.put(xYaxisLeftTickRight); OpenGL.buffer.put(y); OpenGL.buffer.put(Theme.tickLinesColor);
				}
				OpenGL.buffer.rewind();
				int vertexCount = yDivisionsFrequency.keySet().size() * 4;
				OpenGL.drawLinesXyrgba(gl, GL3.GL_LINES, OpenGL.buffer, vertexCount);
				
				for(Map.Entry<Float,String> entry : yDivisionsFrequency.entrySet()) {
					float x = xYaxisLeftTickTextRight - OpenGL.smallTextWidth(gl, entry.getValue());
					float y = (entry.getKey() - minYfreq) / yFreqRange * plotHeight + yPlotBottom - (OpenGL.smallTextHeight / 2.0f);
					OpenGL.drawSmallText(gl, entry.getValue(), (int) x, (int) y, 0);
				}
			
			} else {
				
				OpenGL.buffer.rewind();
				for(Float entry : yDivisionsRelativeFrequency.keySet()) {
					float y = (entry - minYrelFreq) / yRelFreqRange * plotHeight + yPlotBottom;
					OpenGL.buffer.put(xPlotLeft);  OpenGL.buffer.put(y); OpenGL.buffer.put(Theme.divisionLinesColor);
					OpenGL.buffer.put(xPlotRight); OpenGL.buffer.put(y); OpenGL.buffer.put(yAxisScale.is(YAxisScale.BOTH) ? Theme.divisionLinesFadedColor : Theme.divisionLinesColor);

					OpenGL.buffer.put(xYaxisLeftTickLeft);  OpenGL.buffer.put(y); OpenGL.buffer.put(Theme.tickLinesColor);
					OpenGL.buffer.put(xYaxisLeftTickRight); OpenGL.buffer.put(y); OpenGL.buffer.put(Theme.tickLinesColor);
				}
				OpenGL.buffer.rewind();
				int vertexCount = yDivisionsRelativeFrequency.keySet().size() * 4;
				OpenGL.drawLinesXyrgba(gl, GL3.GL_LINES, OpenGL.buffer, vertexCount);
				
				for(Map.Entry<Float,String> entry : yDivisionsRelativeFrequency.entrySet()) {
					float x = xYaxisLeftTickTextRight - OpenGL.smallTextWidth(gl, entry.getValue());
					float y = (entry.getKey() - minYrelFreq) / yRelFreqRange * plotHeight + yPlotBottom - (OpenGL.smallTextHeight / 2.0f);
					OpenGL.drawSmallText(gl, entry.getValue(), (int) x, (int) y, 0);
				}
				
			}
			
		}
		
		// draw the legend, if space is available
		if(legendVisibility.get() && datasetsCount > 0 && xLegendBorderRight < width - Theme.tilePadding) {
			OpenGL.drawQuad2D(gl, Theme.legendBackgroundColor, xLegendBorderLeft, yLegendBorderBottom, xLegendBorderRight, yLegendBorderTop);
			
			for(int i = 0; i < datasetsCount; i++) {
				Field d = datasets.getNormal(i);
				if(mouseX >= legendMouseoverCoordinates[i][0] && mouseX <= legendMouseoverCoordinates[i][2] && mouseY >= legendMouseoverCoordinates[i][1] && mouseY <= legendMouseoverCoordinates[i][3]) {
					OpenGL.drawQuadOutline2D(gl, Theme.tickLinesColor, legendMouseoverCoordinates[i][0], legendMouseoverCoordinates[i][1], legendMouseoverCoordinates[i][2], legendMouseoverCoordinates[i][3]);
					handler = EventHandler.onPress(event -> ConfigureView.instance.forDataset(d));
				}
				OpenGL.drawQuad2D(gl, d.color.getGl(), legendBoxCoordinates[i][0], legendBoxCoordinates[i][1], legendBoxCoordinates[i][2], legendBoxCoordinates[i][3]);
				OpenGL.drawMediumText(gl, d.name.get(), (int) xLegendNameLeft[i], (int) yLegendTextBaseline, 0);
			}
		}
					
		// draw the x-axis title, if space is available
		if(xAxisTitleVisibility.get())
			if((!legendVisibility.get() && xXaxisTitleTextLeft > xPlotLeft) || (legendVisibility.get() && xXaxisTitleTextLeft > xLegendBorderRight + Theme.legendTextPadding))
				OpenGL.drawLargeText(gl, xAxisTitle, (int) xXaxisTitleTextLeft, (int) yXaxisTitleTextBasline, 0);
		
		// draw the left y-axis title, if space is available
		if(yAxisTitleVisibility.get() && yYaxisLeftTitleTextLeft >= yPlotBottom)
			OpenGL.drawLargeText(gl, yAxisLeftTitle, (int) xYaxisLeftTitleTextBaseline, (int) yYaxisLeftTitleTextLeft, 90);
		
		// draw the right y-axis title, if applicable, and if space is available
		if(yAxisTitleVisibility.get() && yAxisScale.is(YAxisScale.BOTH) && yYaxisRightTitleTextLeft <= yPlotTop)
			OpenGL.drawLargeText(gl, yAxisRightTitle, (int) xYaxisRightTitleTextBaseline, (int) yYaxisRightTitleTextLeft, -90);

		// clip to the plot region
		int[] originalScissorArgs = new int[4];
		gl.glGetIntegerv(GL3.GL_SCISSOR_BOX, originalScissorArgs, 0);
		gl.glScissor(originalScissorArgs[0] + (int) xPlotLeft, originalScissorArgs[1] + (int) yPlotBottom, (int) plotWidth, (int) plotHeight);
		
		// draw the bins
		if(sampleCount > 0) {
			for(int datasetN = 0; datasetN < datasetsCount; datasetN++) {
				binsAsTriangles[datasetN].rewind();
				for(int binN = 0; binN < binsCount; binN++) {
					
					float min = minX + (binSize *  binN);      // inclusive
					float max = minX + (binSize * (binN + 1)); // exclusive
					float center = (max + min) / 2f;
					
					float xBarCenter = ((center - minX) / range * plotWidth) + xPlotLeft;
					float yBarTop = ((float) bins[datasetN][binN] - minYfreq) / yFreqRange * plotHeight + yPlotBottom;
					float halfBarWidth = plotWidth / binsCount / 2f;
					
					float x1 = xBarCenter - halfBarWidth; // top-left
					float y1 = yBarTop;
					float x2 = xBarCenter - halfBarWidth; // bottom-left
					float y2 = yPlotBottom;
					float x3 = xBarCenter + halfBarWidth; // bottom-right
					float y3 = yPlotBottom;
					float x4 = xBarCenter + halfBarWidth; // top-right
					float y4 = yBarTop;
					binsAsTriangles[datasetN].put(x1).put(y1).put(x2).put(y2).put(x3).put(y3);
					binsAsTriangles[datasetN].put(x3).put(y3).put(x4).put(y4).put(x1).put(y1);
					
				}
				binsAsTriangles[datasetN].rewind();
				OpenGL.drawTrianglesXY(gl, GL3.GL_TRIANGLES, datasets.getNormal(datasetN).color.getGl(), binsAsTriangles[datasetN], 6 * binsCount);
			}
		}

		// stop clipping to the plot region
		gl.glScissor(originalScissorArgs[0], originalScissorArgs[1], originalScissorArgs[2], originalScissorArgs[3]);
		
		// draw the tooltip if the mouse is in the plot region
		if(sampleCount > 0 && SettingsView.instance.tooltipsVisibility.get() && mouseX >= xPlotLeft && mouseX <= xPlotRight && mouseY >= yPlotBottom && mouseY <= yPlotTop) {
			int binN = (int) Math.floor(((float) mouseX - xPlotLeft) / plotWidth * binsCount);
			if(binN > binsCount - 1)
				binN = binsCount - 1;
			float min = minX + (binSize *  binN);      // inclusive
			float max = minX + (binSize * (binN + 1)); // exclusive
			Tooltip tooltip = new Tooltip();
			tooltip.addRow(ChartUtils.formattedNumber(min, 5) + " to " + ChartUtils.formattedNumber(max, 5) + " " + datasets.getNormal(0).unit.get());
			for(int datasetN = 0; datasetN < datasetsCount; datasetN++) {
				tooltip.addRow(datasets.getNormal(datasetN).color.getGl(),
				               bins[datasetN][binN] + " samples (" + ChartUtils.formattedNumber((double) bins[datasetN][binN] / (double) sampleCount * 100f, 4) + "%)",
				               (float) Math.max((int) (((float) bins[datasetN][binN] - minYfreq) / yFreqRange * plotHeight + yPlotBottom), (int) yPlotBottom));
			}
			float xBarCenter = ((binSize *  binN) + (binSize * (binN + 1))) / 2f / range * plotWidth + xPlotLeft;
			tooltip.draw(gl, xBarCenter, mouseX, mouseY, xPlotLeft, yPlotTop, xPlotRight, yPlotBottom);
		}
		
		// draw the plot border
		OpenGL.drawQuadOutline2D(gl, Theme.plotOutlineColor, xPlotLeft, yPlotBottom, xPlotRight, yPlotTop);
		
		return handler;
		
	}

}
