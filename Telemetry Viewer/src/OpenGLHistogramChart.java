import java.nio.FloatBuffer;
import javax.swing.JLabel;
import javax.swing.JPanel;
import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL2ES3;
import com.jogamp.opengl.GL3;

public class OpenGLHistogramChart extends Chart {
	
	FloatBuffer[] binsAsTriangles; // [datasetN], filled with binN's, for drawing
	
	private DatasetsInterface.WidgetDatasets datasetsWidget;
	private WidgetTextfield<Integer> durationWidget;
	private WidgetCheckbox legendVisibility;
	private enum XAxisScale {
		MIN_MAX     { @Override public String toString() { return "Minimum/Maximum"; } },
		CENTER_SPAN { @Override public String toString() { return "Center/Span";     } };
	};
	private WidgetToggleButton<OpenGLPlot.AxisStyle> xAxisStyle;
	private WidgetToggleButton<XAxisScale> xAxisScale;
	private WidgetTextfield<Float> xAxisMinimum;
	private WidgetCheckbox xAxisMinimumAutomatic;
	private WidgetTextfield<Float> xAxisMaximum;
	private WidgetCheckbox xAxisMaximumAutomatic;
	private WidgetTextfield<Float> xAxisCenter;
	private JLabel xAxisCenterPlaceholder;
	private WidgetTextfield<Float> xAxisSpan;
	private WidgetCheckbox xAxisSpanAutomatic;
	private WidgetTextfield<Integer> binCount;
	private enum YAxisScale {
		SAMPLE_COUNT { @Override public String toString() { return "Sample Count"; } },
		PERCENTAGE   { @Override public String toString() { return "Percentage";   } },
		BOTH         { @Override public String toString() { return "Both";         } };
	};
	private WidgetToggleButton<OpenGLPlot.AxisStyle> yAxisStyle;
	private WidgetToggleButton<YAxisScale> yAxisScale;
	private WidgetTextfield<Integer> yAxisMinimumFrequency;
	private WidgetTextfield<Integer> yAxisMaximumFrequency;
	private WidgetTextfield<Float> yAxisMinimumRelativeFrequency;
	private WidgetTextfield<Float> yAxisMaximumRelativeFrequency;
	private WidgetCheckbox yAxisMinimumIsZero;
	private WidgetCheckbox yAxisMaximumIsAutomatic;
	
	private AutoScale yAutoscale;
	
	protected OpenGLHistogramChart(String name, int x1, int y1, int x2, int y2) {
		
		super(name, x1, y1, x2, y2);

		yAutoscale = new AutoScale(AutoScale.Mode.SMOOTH, 0.20f);
		
		durationWidget = WidgetTextfield.ofInt(2, Integer.MAX_VALUE / 16, Connections.getDefaultChartDuration())
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
		                                        for(int i = 0; i < datasets.normalsCount(); i++)
		                                            binsAsTriangles[i] = Buffers.newDirectFloatBuffer(newBinCount * 12);
		                                        return true;
		                                    });

		datasetsWidget = datasets.getCheckboxesWidget(newDatasets -> {
		                                                  int datasetsCount = datasets.normalsCount();
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
		                                              });
		
		xAxisStyle = new WidgetToggleButton<OpenGLPlot.AxisStyle>("", OpenGLPlot.AxisStyle.values(), OpenGLPlot.AxisStyle.OUTER)
		                 .setExportLabel("x-axis style");
		
		xAxisScale = new WidgetToggleButton<XAxisScale>("Scale", XAxisScale.values(), XAxisScale.MIN_MAX)
		                 .setExportLabel("x-axis scale")
		                 .onChange((newScale, oldScale) -> {
		                      xAxisMinimum.setVisible(newScale == XAxisScale.MIN_MAX);
		                      xAxisMinimumAutomatic.setVisible(newScale == XAxisScale.MIN_MAX);
		                      xAxisMaximum.setVisible(newScale == XAxisScale.MIN_MAX);
		                      xAxisMaximumAutomatic.setVisible(newScale == XAxisScale.MIN_MAX);
		                      xAxisCenter.setVisible(newScale == XAxisScale.CENTER_SPAN);
		                      xAxisCenterPlaceholder.setVisible(newScale == XAxisScale.CENTER_SPAN);
		                      xAxisSpan.setVisible(newScale == XAxisScale.CENTER_SPAN);
		                      xAxisSpanAutomatic.setVisible(newScale == XAxisScale.CENTER_SPAN);
		                      return true;
		                  });
		
		yAxisStyle = new WidgetToggleButton<OpenGLPlot.AxisStyle>("", OpenGLPlot.AxisStyle.values(), OpenGLPlot.AxisStyle.OUTER)
		                 .setExportLabel("y-axis style");
		
		yAxisMinimumFrequency = WidgetTextfield.ofInt(0, Integer.MAX_VALUE-1, 0)
		                                       .setPrefix("Minimum")
		                                       .setSuffix("samples")
		                                       .setExportLabel("y-axis minimum frequency")
		                                       .onChange((newMinimum, oldMinimum) -> {
		                                                     if(newMinimum >= yAxisMaximumFrequency.get())
		                                                         yAxisMaximumFrequency.set(newMinimum + 1);
		                                                     return true;
		                                                 });
		
		yAxisMaximumFrequency = WidgetTextfield.ofInt(1, Integer.MAX_VALUE, 1000)
		                                       .setPrefix("Maximum")
		                                       .setSuffix("samples")
		                                       .setExportLabel("y-axis maximum frequency")
		                                       .onChange((newMaximum, oldMaximum) -> {
		                                                     if(newMaximum <= yAxisMinimumFrequency.get())
		                                                         yAxisMinimumFrequency.set(newMaximum - 1);
		                                                     return true;
		                                                 });
		
		yAxisMinimumRelativeFrequency = WidgetTextfield.ofFloat(0, 99, 0)
		                                               .setPrefix("Minimum")
		                                               .setSuffix("%")
		                                               .setExportLabel("y-axis minimum relative frequency")
		                                               .onChange((newMinimum, oldMinimum) -> {
		                                                             if(newMinimum >= yAxisMaximumRelativeFrequency.get())
		                                                                 yAxisMaximumRelativeFrequency.set(newMinimum + 1);
		                                                             return true;
		                                                         });
		
		yAxisMaximumRelativeFrequency = WidgetTextfield.ofFloat(1, 100, 100)
		                                               .setPrefix("Maximum")
		                                               .setSuffix("%")
		                                               .setExportLabel("y-axis maximum relative frequency")
		                                               .onChange((newMaximum, oldMaximum) -> {
		                                                             if(newMaximum <= yAxisMinimumRelativeFrequency.get())
		                                                                 yAxisMinimumRelativeFrequency.set(newMaximum - 1);
		                                                             return true;
		                                                         });
		
		yAxisMinimumIsZero = new WidgetCheckbox("Zero", true)
		                         .setExportLabel("y-axis minimum is zero")
		                         .onChange(isZero -> {
		                                       if(isZero) {
		                                           yAxisMinimumFrequency.disableWithMessage("0 samples");
		                                           yAxisMinimumRelativeFrequency.disableWithMessage("0.0 %");
		                                       } else {
		                                           yAxisMinimumFrequency.setEnabled(true);
		                                           yAxisMinimumRelativeFrequency.setEnabled(true);
		                                       }
		                                   });
		
		yAxisMaximumIsAutomatic = new WidgetCheckbox("Automatic", true)
		                              .setExportLabel("y-axis maximum is automatic")
		                              .onChange(isAutomatic -> {
		                                            if(isAutomatic) {
		                                                yAxisMaximumFrequency.disableWithMessage("Automatic");
		                                                yAxisMaximumRelativeFrequency.disableWithMessage("Automatic");
		                                            } else {
		                                                yAxisMaximumFrequency.setEnabled(true);
		                                                yAxisMaximumRelativeFrequency.setEnabled(true);
		                                            }
		                                        });
		
		yAxisScale = new WidgetToggleButton<YAxisScale>("Scale", YAxisScale.values(), YAxisScale.PERCENTAGE)
		                 .setExportLabel("y-axis scale")
		                 .onChange((newScale, oldScale) -> {
		                      yAxisMinimumFrequency.setVisible(newScale != YAxisScale.PERCENTAGE);
		                      yAxisMinimumRelativeFrequency.setVisible(newScale == YAxisScale.PERCENTAGE);
		                      yAxisMaximumFrequency.setVisible(newScale != YAxisScale.PERCENTAGE);
		                      yAxisMaximumRelativeFrequency.setVisible(newScale == YAxisScale.PERCENTAGE);
		                      yAutoscale.reset();
		                      return true;
		                  });
		
		widgets.add(datasetsWidget);
		widgets.add(durationWidget);
		widgets.add(legendVisibility);
		widgets.add(xAxisStyle);
		widgets.add(xAxisScale);
		widgets.add(xAxisMinimum);
		widgets.add(xAxisMinimumAutomatic);
		widgets.add(xAxisMaximum);
		widgets.add(xAxisMaximumAutomatic);
		widgets.add(xAxisCenter);
		widgets.add(xAxisSpan);
		widgets.add(xAxisSpanAutomatic);
		widgets.add(binCount);
		widgets.add(yAxisStyle);
		widgets.add(yAxisScale);
		widgets.add(yAxisMinimumFrequency);
		widgets.add(yAxisMaximumFrequency);
		widgets.add(yAxisMinimumRelativeFrequency);
		widgets.add(yAxisMaximumRelativeFrequency);
		widgets.add(yAxisMinimumIsZero);
		widgets.add(yAxisMaximumIsAutomatic);
		
	}
	
	@Override public void appendConfigurationWidgets(JPanel gui) {
		
		gui.add(Theme.newWidgetsPanel("Data")
		             .with(datasetsWidget)
		             .with(durationWidget,   "split 2, sizegroup 0")
		             .with(legendVisibility, "sizegroup 0")
		             .getPanel());
		
		gui.add(Theme.newWidgetsPanel("X-Axis")
		             .with(xAxisStyle)
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
		             .getPanel());
		
		gui.add(Theme.newWidgetsPanel("Y-Axis")
		             .with(yAxisStyle)
		             .with(yAxisScale)
		             .with(yAxisMinimumFrequency,         "split 2, grow")
		             .with(yAxisMinimumRelativeFrequency, "split 2, grow")
		             .with(yAxisMinimumIsZero,            "sizegroup 1")
		             .with(yAxisMaximumFrequency,         "split 2, grow")
		             .with(yAxisMaximumRelativeFrequency, "split 2, grow")
		             .with(yAxisMaximumIsAutomatic,       "sizegroup 1")
		             .getPanel());
		
	}
	
	@Override public EventHandler drawChart(GL2ES3 gl, float[] chartMatrix, int width, int height, long endTimestamp, int endSampleNumber, double zoomLevel, int mouseX, int mouseY) {
		
		// determine the sample set
		int trueLastSampleNumber = datasets.hasNormals() ? datasets.connection.getSampleCount() - 1 : -1;
		int lastSampleNumber = Integer.min(trueLastSampleNumber, endSampleNumber);
		int firstSampleNumber = Integer.max(0, lastSampleNumber - (int) (duration * zoomLevel) + 1);
		int sampleCount = lastSampleNumber - firstSampleNumber + 1;
		int datasetsCount = datasets.normalsCount();

		// determine the x-axis range
		var trueXaxisRange = datasets.getRange(firstSampleNumber, lastSampleNumber);
		float centerSpanLeftHalf  = (float) Math.abs(xAxisCenter.get() - trueXaxisRange.min());
		float centerSpanRightHalf = (float) Math.abs(xAxisCenter.get() - trueXaxisRange.max());
		float centerSpanHalf = xAxisSpanAutomatic.get() ? Float.max(centerSpanLeftHalf, centerSpanRightHalf) : xAxisSpan.get() / 2;
		float xAxisMin = xAxisScale.is(XAxisScale.CENTER_SPAN) ? xAxisCenter.get() - centerSpanHalf :
		                 xAxisMinimumAutomatic.get()           ? trueXaxisRange.min() :
		                                                         xAxisMinimum.get();
		float xAxisMax = xAxisScale.is(XAxisScale.CENTER_SPAN) ? Math.nextUp(xAxisCenter.get() + centerSpanHalf) : // increment because the bins are >=min, <max
		                 xAxisMaximumAutomatic.get()           ? Math.nextUp(trueXaxisRange.max()) :
		                                                         Math.nextUp(xAxisMaximum.get());
		float xAxisRange = xAxisMax - xAxisMin;
		int binsCount = binCount.get();
		float binSize = xAxisRange / (float) binsCount;

		// calculate the histogram
		int[][] bins = new int[datasetsCount][binsCount]; // [datasetN][binN]
		int maxBinSize = 0;
		if(sampleCount > 0) {
			for(int datasetN = 0; datasetN < datasetsCount; datasetN++) {
				FloatBuffer samples = datasets.getSamplesBuffer(datasets.getNormal(datasetN), firstSampleNumber, lastSampleNumber);
				for(int sampleN = 0; sampleN < sampleCount; sampleN++) {
					float sample = samples.get();
					if(sample >= xAxisMin && sample < xAxisMax) {
						int binN = (int) Math.floor((sample - xAxisMin) / xAxisRange * binsCount);
						if(binN == binsCount) binN--; // needed because of float math imperfection
						bins[datasetN][binN]++;
					}
				}
			}
			
			for(int datasetN = 0; datasetN < datasetsCount; datasetN++)
				for(int binN = 0; binN < binsCount; binN++)
					maxBinSize = Math.max(maxBinSize, bins[datasetN][binN]);
		}
		
		// determine the y-axis range
		float yAxisMin = yAxisMinimumIsZero.get()               ? 0 :
		                 (yAxisScale.is(YAxisScale.PERCENTAGE)) ? yAxisMinimumRelativeFrequency.get() :
		                 /* SAMPLE COUNT or BOTH */               yAxisMinimumFrequency.get();
		yAutoscale.update(yAxisMin, (sampleCount < 1)                    ? 1 :
		                            yAxisScale.is(YAxisScale.PERCENTAGE) ? maxBinSize / (float) sampleCount * 100:
		                            /* SAMPLE COUNT or BOTH */             maxBinSize);
		float yAxisMax = yAxisMaximumIsAutomatic.isTrue()     ? yAutoscale.getMax() :
		                 yAxisScale.is(YAxisScale.PERCENTAGE) ? yAxisMaximumRelativeFrequency.get() :
		                 /* SAMPLE COUNT or BOTH */             yAxisMaximumFrequency.get();
		float yAxisRange = yAxisMax - yAxisMin;
		
		// determine the axis titles
		String xAxisTitle = (datasetsCount == 0) ? "" : datasets.getNormal(0).unit.get();
		String yAxisTitle = switch(yAxisScale.get()) { case PERCENTAGE   -> "Percentage";
		                                               case SAMPLE_COUNT -> "Sample Count";
		                                               case BOTH         -> "Sample Count (Percentage)"; };
		
		// draw the plot
		return new OpenGLPlot(chartMatrix, width, height, mouseX, mouseY)
		           .withLegend(legendVisibility.get(), datasets)
		           .withXaxis(xAxisStyle.get(), OpenGLPlot.AxisScale.LINEAR, xAxisMin, xAxisMax, xAxisTitle)
		           .withYaxis(yAxisStyle.get(), OpenGLPlot.AxisScale.LINEAR, yAxisMin, yAxisMax, yAxisTitle)
		           .withYaxisConvertedToPercentage(yAxisScale.is(YAxisScale.BOTH), sampleCount)
		           .withPlotDrawer(plot -> {
		                if(sampleCount > 0) {
		                    for(int datasetN = 0; datasetN < datasetsCount; datasetN++) {
		                        binsAsTriangles[datasetN].rewind();
		                        for(int binN = 0; binN < binsCount; binN++) {
		                            float min = xAxisMin + (binSize *  binN);      // inclusive
		                            float max = xAxisMin + (binSize * (binN + 1)); // exclusive
		                            float center = (max + min) / 2f;
		                            
		                            float value = yAxisScale.is(YAxisScale.PERCENTAGE) ? (float) bins[datasetN][binN] / (float) sampleCount * 100 :
		                                          /* SAMPLE COUNT or BOTH */             (float) bins[datasetN][binN];
		                            float xBarCenter = ((center - xAxisMin) / xAxisRange * plot.width());
		                            float yBarTop = (value - yAxisMin) / yAxisRange * plot.height();
		                            float halfBarWidth = plot.width() / binsCount / 2f;
		                            
		                            float x1 = xBarCenter - halfBarWidth; // top-left
		                            float y1 = yBarTop;
		                            float x2 = xBarCenter - halfBarWidth; // bottom-left
		                            float y2 = 0;
		                            float x3 = xBarCenter + halfBarWidth; // bottom-right
		                            float y3 = 0;
		                            float x4 = xBarCenter + halfBarWidth; // top-right
		                            float y4 = yBarTop;
		                            binsAsTriangles[datasetN].put(x1).put(y1).put(x2).put(y2).put(x3).put(y3);
		                            binsAsTriangles[datasetN].put(x3).put(y3).put(x4).put(y4).put(x1).put(y1);
		                        }
		                        binsAsTriangles[datasetN].rewind();
		                        OpenGL.drawTrianglesXY(gl, GL3.GL_TRIANGLES, datasets.getNormal(datasetN).color.getGl(), binsAsTriangles[datasetN], 6 * binsCount);
		                    }
		                }
		                return null;
		           })
		           .withTooltipDrawer(plot -> {
		                if(sampleCount > 0) {
		                    int binN = (int) Math.floor((float) plot.mouseX() / plot.width() * binsCount);
		                    if(binN > binsCount - 1)
		                        binN = binsCount - 1;
		                    float min = xAxisMin + (binSize *  binN);      // inclusive
		                    float max = xAxisMin + (binSize * (binN + 1)); // exclusive
		                    float xAnchor = ((binSize *  binN) + (binSize * (binN + 1))) / 2f / xAxisRange * plot.width();
		                    Tooltip tooltip = new Tooltip(Theme.getFloat(min, "", false) + " to " + Theme.getFloat(max, datasets.getNormal(0).unit.get(), false), xAnchor, -1);
		                    for(int datasetN = 0; datasetN < datasetsCount; datasetN++) {
		                        float value = yAxisScale.is(YAxisScale.PERCENTAGE) ? (float) bins[datasetN][binN] / (float) sampleCount * 100 :
		                                      /* SAMPLE COUNT or BOTH */             (float) bins[datasetN][binN];
		                        int pixelY = (int) ((value - yAxisMin) / yAxisRange * plot.height());
		                        tooltip.addRow(datasets.getNormal(datasetN).color.getGl(),
		                                       bins[datasetN][binN] + " samples (" + Theme.getFloatOrInteger((float) bins[datasetN][binN] / (float) sampleCount * 100f, "%)", true),
		                                       Math.clamp(pixelY, 0, plot.height()));
		                    }
		                    tooltip.draw(gl, plot.mouseX(), plot.mouseY(), plot.width(), plot.height());
		                }
		                return null;
		           })
		           .draw(gl);
		
	}

}
