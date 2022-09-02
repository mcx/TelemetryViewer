import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.Box;
import javax.swing.JLabel;
import javax.swing.JPanel;
import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL2ES3;
import com.jogamp.opengl.GL3;

public class OpenGLHistogramChart extends PositionedChart {
	
	FloatBuffer[] samples; // [datasetN]
	int[][] bins; // [datasetN][binN]
	FloatBuffer[] binsAsTriangles; // [datasetN], filled with binN's, for drawing
	
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
	Map<Float, String> xDivisions;
	float yXaxisTickTextBaseline;
	float yXaxisTickTextTop;
	float yXaxisTickBottom;
	float yXaxisTickTop;
	
	// user settings
	private WidgetDatasetCheckboxes datasetsWidget;
	
	private WidgetTextfieldInt durationWidget;
	
	private boolean legendVisible = true;
	private WidgetCheckbox legendCheckbox;
	
	private enum XAxisScale {
		MIN_MAX     { @Override public String toString() { return "Minimum/Maximum"; } },
		CENTER_SPAN { @Override public String toString() { return "Center/Span";     } };
	};
	private XAxisScale xAxisScale = XAxisScale.MIN_MAX;
	private WidgetToggleButtonEnum<XAxisScale> xAxisScaleButtons;
	
	private float xAxisMinimum = -1;
	private WidgetTextfieldFloat xAxisMinimumTextfield;

	private boolean xAxisMinimumAutomatic = true;
	private WidgetCheckbox xAxisMinimumAutomaticCheckbox;
	
	private float xAxisMaximum = 1;
	private WidgetTextfieldFloat xAxisMaximumTextfield;
	
	private boolean xAxisMaximumAutomatic = true;
	private WidgetCheckbox xAxisMaximumAutomaticCheckbox;
	
	private float xAxisCenter = 0;
	private WidgetTextfieldFloat xAxisCenterTextfield;
	private JLabel xAxisCenterPlaceholder;
	
	private float xAxisSpan = 2;
	private WidgetTextfieldFloat xAxisSpanTextfield;
	
	private boolean xAxisSpanAutomatic = true;
	private WidgetCheckbox xAxisSpanAutomaticCheckbox;
	
	private int binCount = 60;
	private WidgetTextfieldInt binCountTextfield;
	
	private boolean xAxisTicksVisible = true;
	private WidgetCheckbox xAxisTicksCheckbox;
	
	private boolean xAxisTitleVisible = true;
	private WidgetCheckbox xAxisTitleCheckbox;
	
	private enum YAxisScale {
		FREQUENCY          { @Override public String toString() { return "Frequency";          } },
		RELATIVE_FREQUENCY { @Override public String toString() { return "Relative Frequency"; } },
		BOTH               { @Override public String toString() { return "Both";               } };
	};
	private YAxisScale yAxisScale = YAxisScale.RELATIVE_FREQUENCY;
	private WidgetToggleButtonEnum<YAxisScale> yAxisScaleButtons;
	
	private int yAxisMinimumFrequency = 0;
	private WidgetTextfieldInt yAxisMinimumFrequencyTextfield;
	
	private boolean yAxisMinimumFrequencyIsZero = true;
	private WidgetCheckbox yAxisMinimumFrequencyIsZeroCheckbox;
	
	private int yAxisMaximumFrequency = 1000;
	private WidgetTextfieldInt yAxisMaximumFrequencyTextfield;
	
	private boolean yAxisMaximumFrequencyAutomatic = true;
	private WidgetCheckbox yAxisMaximumFrequencyAutomaticCheckbox;
	
	private float yAxisMinimumRelativeFrequency = 0;
	private WidgetTextfieldFloat yAxisMinimumRelativeFrequencyTextfield;
	
	private boolean yAxisMinimumRelativeFrequencyIsZero = true;
	private WidgetCheckbox yAxisMinimumRelativeFrequencyIsZeroCheckbox;
	
	private float yAxisMaximumRelativeFrequency = 1;
	private WidgetTextfieldFloat yAxisMaximumRelativeFrequencyTextfield;
	
	private boolean yAxisMaximumRelativeFrequencyAutomatic = true;
	private WidgetCheckbox yAxisMaximumRelativeFrequencyAutomaticCheckbox;
	
	private boolean yAxisTicksVisible = true;
	private WidgetCheckbox yAxisTicksCheckbox;
	
	private boolean yAxisTitleVisible = true;
	private WidgetCheckbox yAxisTitleCheckbox;
	
	// y-axis title
	float xYaxisLeftTitleTextTop;
	float xYaxisLeftTitleTextBaseline;
	String yAxisLeftTitle;
	float yYaxisLeftTitleTextLeft;
	float xYaxisRightTitleTextTop;
	float xYaxisRightTitleTextBaseline;
	String yAxisRightTitle;
	float yYaxisRightTitleTextLeft;
	
	// y-axis scale
	float xYaxisLeftTickTextRight;
	float xYaxisLeftTickLeft;
	float xYaxisLeftTickRight;
	float xYaxisRightTickTextLeft;
	float xYaxisRightTickLeft;
	float xYaxisRightTickRight;
	private AutoScale  yAutoscaleRelativeFrequency;
	private AutoScale  yAutoscaleFrequency;
	
	@Override public String toString() {
		
		return "Histogram";
		
	}
	
	public OpenGLHistogramChart(int x1, int y1, int x2, int y2) {
		
		super(x1, y1, x2, y2);

		yAutoscaleRelativeFrequency = new AutoScale(AutoScale.MODE_EXPONENTIAL, 30, 0.20f);
		yAutoscaleFrequency = new AutoScale(AutoScale.MODE_EXPONENTIAL, 30, 0.20f);
		
		// create the control widgets and event handlers
		durationWidget = new WidgetTextfieldInt("",
		                                        "sample count",
		                                        "Samples",
		                                        2,
		                                        Integer.MAX_VALUE / 16,
		                                        ConnectionsController.getDefaultChartDuration(),
		                                        newDuration -> duration = newDuration);
		
		legendCheckbox = new WidgetCheckbox("Show Legend",
		                                    legendVisible,
		                                    newVisibility -> legendVisible = newVisibility);
		
		xAxisMinimumTextfield = new WidgetTextfieldFloat("Minimum",
		                                                 "x-axis minimum",
		                                                 "",
		                                                 -Float.MAX_VALUE,
		                                                 Float.MAX_VALUE,
		                                                 xAxisMinimum,
		                                                 newMinimum -> {
		                                                     xAxisMinimum = newMinimum;
		                                                     if(xAxisMinimum > xAxisMaximum)
		                                                         xAxisMaximumTextfield.setNumber(xAxisMinimum);
		                                                 });
		
		xAxisMinimumAutomaticCheckbox = new WidgetCheckbox("Automatic",
		                                                   "x-axis minimum automatic",
		                                                   xAxisMinimumAutomatic,
		                                                   isAutomatic -> {
		                                                       xAxisMinimumAutomatic = isAutomatic;
		                                                       if(isAutomatic)
		                                                           xAxisMinimumTextfield.disableWithMessage("Automatic");
		                                                       else
		                                                           xAxisMinimumTextfield.setEnabled(true);
		                                                   });
		
		xAxisMaximumTextfield = new WidgetTextfieldFloat("Maximum",
		                                                 "x-axis maximum",
		                                                 "",
		                                                 -Float.MAX_VALUE,
		                                                 Float.MAX_VALUE,
		                                                 xAxisMaximum,
		                                                 newMaximum -> {
		                                                     xAxisMaximum = newMaximum;
		                                                     if(xAxisMaximum < xAxisMinimum)
		                                                         xAxisMinimumTextfield.setNumber(xAxisMaximum);
		                                                 });
		
		xAxisMaximumAutomaticCheckbox = new WidgetCheckbox("Automatic",
		                                                   "x-axis maximum automatic",
		                                                   xAxisMaximumAutomatic,
		                                                   isAutomatic -> {
		                                                       xAxisMaximumAutomatic = isAutomatic;
		                                                       if(isAutomatic)
		                                                           xAxisMaximumTextfield.disableWithMessage("Automatic");
		                                                       else
		                                                           xAxisMaximumTextfield.setEnabled(true);
		                                                   });
		
		xAxisCenterTextfield = new WidgetTextfieldFloat("Center",
		                                                "x-axis center",
		                                                "",
		                                                -Float.MAX_VALUE,
		                                                Float.MAX_VALUE,
		                                                xAxisCenter,
		                                                newCenter -> xAxisCenter = newCenter);
		
		xAxisCenterPlaceholder = new JLabel(" "); // used to keep the center textfield the same size as the span textfield
		
		xAxisSpanTextfield = new WidgetTextfieldFloat("Span",
		                                              "x-axis span",
		                                              "",
		                                              Float.MIN_VALUE,
		                                              Float.MAX_VALUE,
		                                              xAxisSpan,
		                                              newSpan -> xAxisSpan = newSpan);
		
		xAxisSpanAutomaticCheckbox = new WidgetCheckbox("Automatic",
		                                                "x-axis span automatic",
		                                                xAxisSpanAutomatic,
		                                                isAutomatic -> {
		                                                    xAxisSpanAutomatic = isAutomatic;
		                                                    if(isAutomatic)
		                                                        xAxisSpanTextfield.disableWithMessage("Automatic");
		                                                    else
		                                                        xAxisSpanTextfield.setEnabled(true);
		                                                });

		datasetsWidget = new WidgetDatasetCheckboxes(newDatasets -> {
		                                                 datasets.setNormals(newDatasets);
		                                                 int datasetsCount = datasets.normalsCount();
		                                                 samples = new FloatBuffer[datasetsCount];
		                                                 bins = new int[datasetsCount][binCount];
		                                                 binsAsTriangles = new FloatBuffer[datasetsCount];
		                                                 for(int i = 0; i < datasetsCount; i++)
		                                                     binsAsTriangles[i] = Buffers.newDirectFloatBuffer(binCount * 12);
		                                                 if(datasets.normalsCount() == 1) {
		                                                     xAxisMinimumTextfield.setUnit(datasets.getNormal(0).unit);
		                                                     xAxisMaximumTextfield.setUnit(datasets.getNormal(0).unit);
		                                                     xAxisCenterTextfield.setUnit(datasets.getNormal(0).unit);
		                                                     xAxisSpanTextfield.setUnit(datasets.getNormal(0).unit);
		                                                 } else if(datasets.normalsCount() == 0) {
		                                                     xAxisMinimumTextfield.setUnit("");
		                                                     xAxisMaximumTextfield.setUnit("");
		                                                     xAxisCenterTextfield.setUnit("");
		                                                     xAxisSpanTextfield.setUnit("");
		                                                 }
		                                             },
		                                             null,
		                                             null,
		                                             null,
		                                             false);
		
		xAxisScaleButtons = new WidgetToggleButtonEnum<XAxisScale>("Specify as",
		                                                           "x-axis scale",
		                                                           XAxisScale.values(),
		                                                           xAxisScale,
		                                                           newScale -> {
		                                                               xAxisScale = newScale;
		                                                               xAxisMinimumTextfield.setVisible(xAxisScale == XAxisScale.MIN_MAX);
		                                                               xAxisMinimumAutomaticCheckbox.setVisible(xAxisScale == XAxisScale.MIN_MAX);
		                                                               xAxisMaximumTextfield.setVisible(xAxisScale == XAxisScale.MIN_MAX);
		                                                               xAxisMaximumAutomaticCheckbox.setVisible(xAxisScale == XAxisScale.MIN_MAX);
		                                                               xAxisCenterTextfield.setVisible(xAxisScale == XAxisScale.CENTER_SPAN);
		                                                               xAxisCenterPlaceholder.setVisible(xAxisScale == XAxisScale.CENTER_SPAN);
		                                                               xAxisSpanTextfield.setVisible(xAxisScale == XAxisScale.CENTER_SPAN);
		                                                               xAxisSpanAutomaticCheckbox.setVisible(xAxisScale == XAxisScale.CENTER_SPAN);
		                                                           });
		
		binCountTextfield = new WidgetTextfieldInt("Bins",
		                                           "x-axis bin count",
		                                           "",
		                                           2,
		                                           Integer.MAX_VALUE,
		                                           binCount,
		                                           false,
		                                           0,
		                                           null,
		                                           newBinCount -> {
		                                               binCount = newBinCount;
		                                               bins = new int[datasets.normalsCount()][binCount];
		                                               for(int i = 0; i < datasets.normalsCount(); i++)
		                                                   binsAsTriangles[i] = Buffers.newDirectFloatBuffer(binCount * 12);
		                                           });
		
		xAxisTicksCheckbox = new WidgetCheckbox("Show Ticks",
		                                        "x-axis show ticks",
		                                        xAxisTicksVisible,
		                                        isVisible -> xAxisTicksVisible = isVisible);
		
		xAxisTitleCheckbox = new WidgetCheckbox("Show Title",
		                                        "x-axis show title",
		                                        xAxisTitleVisible,
		                                        isVisible -> xAxisTitleVisible = isVisible);
		
		yAxisMinimumFrequencyTextfield = new WidgetTextfieldInt("Minimum",
		                                                        "y-axis minimum frequency",
		                                                        null,
		                                                        0,
		                                                        Integer.MAX_VALUE,
		                                                        yAxisMinimumFrequency,
		                                                        newMinimum -> {
		                                                            yAxisMinimumFrequency = newMinimum;
		                                                            if(yAxisMinimumFrequency > yAxisMaximumFrequency)
		                                                                yAxisMaximumFrequencyTextfield.setNumber(yAxisMinimumFrequency);
		                                                        });
		
		yAxisMinimumFrequencyIsZeroCheckbox = new WidgetCheckbox("Zero",
		                                                         "y-axis minimum frequency is zero",
		                                                         yAxisMinimumFrequencyIsZero,
		                                                         isZero -> {
		                                                             yAxisMinimumFrequencyIsZero = isZero;
		                                                             if(yAxisMinimumFrequencyIsZero)
		                                                                 yAxisMinimumFrequencyTextfield.disableWithMessage("0");
		                                                             else
		                                                                 yAxisMinimumFrequencyTextfield.setEnabled(true);
		                                                         });
		
		yAxisMaximumFrequencyTextfield = new WidgetTextfieldInt("Maximum",
		                                                        "y-axis maximum frequency",
		                                                        null,
		                                                        0,
		                                                        Integer.MAX_VALUE,
		                                                        yAxisMaximumFrequency,
		                                                        newMaximum -> {
		                                                            yAxisMaximumFrequency = newMaximum;
		                                                            if(yAxisMaximumFrequency < yAxisMinimumFrequency)
		                                                                yAxisMinimumFrequencyTextfield.setNumber(yAxisMaximumFrequency);
		                                                        });
		
		yAxisMaximumFrequencyAutomaticCheckbox = new WidgetCheckbox("Automatic",
		                                                            "y-axis maximum frequency automatic",
		                                                            yAxisMaximumFrequencyAutomatic,
		                                                            isAutomatic -> {
		                                                                yAxisMaximumFrequencyAutomatic = isAutomatic;
		                                                                if(yAxisMaximumFrequencyAutomatic)
		                                                                    yAxisMaximumFrequencyTextfield.disableWithMessage("Automatic");
		                                                                else
		                                                                    yAxisMaximumFrequencyTextfield.setEnabled(true);
		                                                            });
		
		yAxisMinimumRelativeFrequencyTextfield = new WidgetTextfieldFloat("Minimum",
		                                                                  "y-axis minimum relative frequency",
		                                                                  null,
		                                                                  0,
		                                                                  1,
		                                                                  yAxisMinimumRelativeFrequency,
		                                                                  newMinimum -> {
		                                                                      yAxisMinimumRelativeFrequency = newMinimum;
		                                                                      if(yAxisMinimumRelativeFrequency > yAxisMaximumRelativeFrequency)
		                                                                          yAxisMaximumRelativeFrequencyTextfield.setNumber(yAxisMinimumRelativeFrequency);
		                                                                  });
		
		yAxisMinimumRelativeFrequencyIsZeroCheckbox = new WidgetCheckbox("Zero",
		                                                                 "y-axis minimum relative frequency is zero",
		                                                                 yAxisMinimumRelativeFrequencyIsZero,
		                                                                 isZero -> {
		                                                                     yAxisMinimumRelativeFrequencyIsZero = isZero;
		                                                                     if(yAxisMinimumRelativeFrequencyIsZero)
		                                                                         yAxisMinimumRelativeFrequencyTextfield.disableWithMessage("0");
		                                                                     else
		                                                                         yAxisMinimumRelativeFrequencyTextfield.setEnabled(true);
		                                                                 });
		
		yAxisMaximumRelativeFrequencyTextfield = new WidgetTextfieldFloat("Maximum",
		                                                                  "y-axis maximum relative frequency",
		                                                                  null,
		                                                                  0,
		                                                                  1,
		                                                                  yAxisMaximumRelativeFrequency,
		                                                                  newMaximum -> {
		                                                                      yAxisMaximumRelativeFrequency = newMaximum;
		                                                                      if(yAxisMaximumRelativeFrequency < yAxisMinimumRelativeFrequency)
		                                                                          yAxisMinimumRelativeFrequencyTextfield.setNumber(yAxisMaximumRelativeFrequency);
		                                                                  });
		
		yAxisMaximumRelativeFrequencyAutomaticCheckbox = new WidgetCheckbox("Automatic",
		                                                                    "y-axis maximum relative frequency automatic",
		                                                                    yAxisMaximumRelativeFrequencyAutomatic,
		                                                                    isAutomatic -> {
		                                                                        yAxisMaximumRelativeFrequencyAutomatic = isAutomatic;
		                                                                        if(yAxisMaximumRelativeFrequencyAutomatic)
		                                                                            yAxisMaximumRelativeFrequencyTextfield.disableWithMessage("Automatic");
		                                                                        else
		                                                                            yAxisMaximumRelativeFrequencyTextfield.setEnabled(true);
		                                                                    });
		
		yAxisScaleButtons = new WidgetToggleButtonEnum<YAxisScale>("Scale",
		                                                           "y-axis scale",
		                                                           YAxisScale.values(),
		                                                           yAxisScale,
		                                                           newScale -> {
		                                                               yAxisScale = newScale;
		                                                               yAxisMinimumFrequencyTextfield.setVisible(yAxisScale == YAxisScale.FREQUENCY);
		                                                               yAxisMinimumFrequencyIsZeroCheckbox.setVisible(yAxisScale == YAxisScale.FREQUENCY);
		                                                               yAxisMaximumFrequencyTextfield.setVisible(yAxisScale == YAxisScale.FREQUENCY);
		                                                               yAxisMaximumFrequencyAutomaticCheckbox.setVisible(yAxisScale == YAxisScale.FREQUENCY);
		                                                               yAxisMinimumRelativeFrequencyTextfield.setVisible(yAxisScale != YAxisScale.FREQUENCY);
		                                                               yAxisMinimumRelativeFrequencyIsZeroCheckbox.setVisible(yAxisScale != YAxisScale.FREQUENCY);
		                                                               yAxisMaximumRelativeFrequencyTextfield.setVisible(yAxisScale != YAxisScale.FREQUENCY);
		                                                               yAxisMaximumRelativeFrequencyAutomaticCheckbox.setVisible(yAxisScale != YAxisScale.FREQUENCY);
		                                                           });
		
		yAxisTicksCheckbox = new WidgetCheckbox("Show Ticks",
		                                        "y-axis show ticks",
		                                        yAxisTicksVisible,
		                                        isVisible -> yAxisTicksVisible = isVisible);
		
		yAxisTitleCheckbox = new WidgetCheckbox("Show Title",
		                                        "y-axis show title",
		                                        yAxisTitleVisible,
		                                        isVisible -> yAxisTitleVisible = isVisible);
		
		widgets.add(datasetsWidget);
		widgets.add(durationWidget);
		widgets.add(legendCheckbox);
		widgets.add(xAxisScaleButtons);
		widgets.add(xAxisMinimumTextfield);
		widgets.add(xAxisMinimumAutomaticCheckbox);
		widgets.add(xAxisMaximumTextfield);
		widgets.add(xAxisMaximumAutomaticCheckbox);
		widgets.add(xAxisCenterTextfield);
		widgets.add(xAxisSpanTextfield);
		widgets.add(xAxisSpanAutomaticCheckbox);
		widgets.add(binCountTextfield);
		widgets.add(xAxisTicksCheckbox);
		widgets.add(xAxisTitleCheckbox);
		widgets.add(yAxisScaleButtons);
		widgets.add(yAxisMinimumFrequencyTextfield);
		widgets.add(yAxisMinimumFrequencyIsZeroCheckbox);
		widgets.add(yAxisMaximumFrequencyTextfield);
		widgets.add(yAxisMaximumFrequencyAutomaticCheckbox);
		widgets.add(yAxisMinimumRelativeFrequencyTextfield);
		widgets.add(yAxisMinimumRelativeFrequencyIsZeroCheckbox);
		widgets.add(yAxisMaximumRelativeFrequencyTextfield);
		widgets.add(yAxisMaximumRelativeFrequencyAutomaticCheckbox);
		widgets.add(yAxisTicksCheckbox);
		widgets.add(yAxisTitleCheckbox);
		
	}
	
	@Override public void getConfigurationGui(JPanel gui) {
		
		JPanel dataPanel = Theme.newWidgetsPanel("Data");
		datasetsWidget.appendToGui(dataPanel);
		dataPanel.add(durationWidget, "split 2, sizegroup 0");
		dataPanel.add(legendCheckbox, "sizegroup 0");
		
		JPanel xAxisPanel = Theme.newWidgetsPanel("X-Axis");
		xAxisScaleButtons.appendToGui(xAxisPanel);
		xAxisPanel.add(xAxisMinimumTextfield, "split 2, grow");
		xAxisPanel.add(xAxisMinimumAutomaticCheckbox, "sizegroup 1");
		xAxisPanel.add(xAxisMaximumTextfield, "split 2, grow");
		xAxisPanel.add(xAxisMaximumAutomaticCheckbox, "sizegroup 1");
		xAxisPanel.add(xAxisCenterTextfield, "split 2, grow");
		xAxisPanel.add(xAxisCenterPlaceholder, "sizegroup 1");
		xAxisPanel.add(xAxisSpanTextfield, "split 2, grow");
		xAxisPanel.add(xAxisSpanAutomaticCheckbox, "sizegroup 1");
		xAxisPanel.add(Box.createVerticalStrut(Theme.padding));
		xAxisPanel.add(binCountTextfield, "split 2, grow");
		xAxisPanel.add(new JLabel(" "), "sizegroup 1");
		xAxisPanel.add(Box.createVerticalStrut(Theme.padding));
		xAxisPanel.add(xAxisTicksCheckbox, "split 2");
		xAxisPanel.add(xAxisTitleCheckbox);
		
		JPanel yAxisPanel = Theme.newWidgetsPanel("Y-Axis");
		yAxisScaleButtons.appendToGui(yAxisPanel);
		yAxisPanel.add(yAxisMinimumFrequencyTextfield, "split 2, grow");
		yAxisPanel.add(yAxisMinimumFrequencyIsZeroCheckbox, "sizegroup 1");
		yAxisPanel.add(yAxisMaximumFrequencyTextfield, "split 2, grow");
		yAxisPanel.add(yAxisMaximumFrequencyAutomaticCheckbox, "sizegroup 1");
		yAxisPanel.add(yAxisMinimumRelativeFrequencyTextfield, "split 2, grow");
		yAxisPanel.add(yAxisMinimumRelativeFrequencyIsZeroCheckbox, "sizegroup 2");
		yAxisPanel.add(yAxisMaximumRelativeFrequencyTextfield, "split 2, grow");
		yAxisPanel.add(yAxisMaximumRelativeFrequencyAutomaticCheckbox, "sizegroup 2");
		yAxisPanel.add(Box.createVerticalStrut(Theme.padding));
		yAxisPanel.add(yAxisTicksCheckbox, "split 2");
		yAxisPanel.add(yAxisTitleCheckbox);
		
		gui.add(dataPanel);
		gui.add(xAxisPanel);
		gui.add(yAxisPanel);
		
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
				Dataset dataset = datasets.getNormal(datasetN);
				samples[datasetN] = datasets.getSamplesBuffer(dataset, firstSampleNumber, lastSampleNumber);
			}

		// determine the true x-axis range
		float[] minMax = datasets.getRange(firstSampleNumber, lastSampleNumber);
		float trueMinX = minMax[0];
		float trueMaxX = minMax[1];
		
		// determine the plotted x-axis range
		float minX = 0;
		float maxX = 0;
		if(xAxisScale == XAxisScale.CENTER_SPAN) {
			float leftHalf  = (float) Math.abs(xAxisCenter - trueMinX);
			float rightHalf = (float) Math.abs(xAxisCenter - trueMaxX);
			float half = xAxisSpanAutomatic ? Float.max(leftHalf, rightHalf) : xAxisSpan / 2;
			minX = xAxisCenter - half;
			maxX = Math.nextUp(xAxisCenter + half); // increment because the bins are >=min, <max
		} else {
			minX = xAxisMinimumAutomatic ?             trueMinX  :             xAxisMinimum;
			maxX = xAxisMaximumAutomatic ? Math.nextUp(trueMaxX) : Math.nextUp(xAxisMaximum); // increment because the bins are >=min, <max
		}
		float range = maxX - minX;
		float binSize = range / (float) binCount;

		// calculate the histogram
		int maxBinSize = 0;
		if(sampleCount > 0) {
			// empty the bins
			for(int datasetN = 0; datasetN < datasetsCount; datasetN++)
				for(int binN = 0; binN < binCount; binN++)
					bins[datasetN][binN] = 0;
			
			// fill the bins
			for(int datasetN = 0; datasetN < datasetsCount; datasetN++) {
				for(int sampleN = 0; sampleN < sampleCount; sampleN++) {
					float sample = samples[datasetN].get();
					if(sample >= minX && sample < maxX) {
						int binN = (int) Math.floor((sample - minX) / range * binCount);
						if(binN == binCount) binN--; // needed because of float math imperfection
						bins[datasetN][binN]++;
					}
				}
			}
			
			// get the max
			for(int datasetN = 0; datasetN < datasetsCount; datasetN++)
				for(int binN = 0; binN < binCount; binN++)
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
		if(yAxisScale == YAxisScale.RELATIVE_FREQUENCY || yAxisScale == YAxisScale.BOTH) {
			
			// the range is determined by relative frequency, and frequency is forced to match it
			minYrelFreq = yAxisMinimumRelativeFrequencyIsZero ? 0 : yAxisMinimumRelativeFrequency;
			yAutoscaleRelativeFrequency.update(minYrelFreq, trueMaxYrelFreq);
			maxYrelFreq = yAxisMaximumRelativeFrequencyAutomatic ? yAutoscaleRelativeFrequency.getMax() : yAxisMaximumRelativeFrequency;
			minYfreq = sampleCount < 1 ? minYrelFreq : minYrelFreq * (float) sampleCount;
			maxYfreq = sampleCount < 1 ? maxYrelFreq : maxYrelFreq * (float) sampleCount;
			
		} else {
			
			// the range is determined by frequency, and relative frequency is forced to match it
			minYfreq = yAxisMinimumFrequencyIsZero ? 0 : yAxisMinimumFrequency;
			yAutoscaleFrequency.update(minYfreq, trueMaxYfreq);
			maxYfreq = yAxisMaximumFrequencyAutomatic ? yAutoscaleFrequency.getMax() : yAxisMaximumFrequency;
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
		
		if(xAxisTitleVisible) {
			yXaxisTitleTextBasline = Theme.tilePadding;
			yXaxisTitleTextTop = yXaxisTitleTextBasline + OpenGL.largeTextHeight;
			xAxisTitle = datasetsCount == 0 ? "(0 Samples)" : datasets.getNormal(0).unit + " (" + sampleCount + " Samples)";
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
				xOffset += OpenGL.mediumTextWidth(gl, datasets.getNormal(i).name) + Theme.legendNamesPadding;
				
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
			yXaxisTickBottom = yXaxisTickTextTop + Theme.tickTextPadding;
			yXaxisTickTop = yXaxisTickBottom + Theme.tickLength;
			
			yPlotBottom = yXaxisTickTop;
			plotHeight = yPlotTop - yPlotBottom;
		}
		
		// get the y divisions now that we know the final plot height
		Map<Float, String> yDivisionsFrequency = ChartUtils.getYdivisions125(plotHeight, minYfreq, maxYfreq);
		Map<Float, String> yDivisionsRelativeFrequency = ChartUtils.getYdivisions125(plotHeight, minYrelFreq, maxYrelFreq);
		
		if(yAxisTitleVisible) {
			// the left y-axis is for Relative Frequency unless only Frequency will be shown
			xYaxisLeftTitleTextTop = xPlotLeft;
			xYaxisLeftTitleTextBaseline = xYaxisLeftTitleTextTop + OpenGL.largeTextHeight;
			yAxisLeftTitle = (yAxisScale == YAxisScale.RELATIVE_FREQUENCY || yAxisScale == YAxisScale.BOTH) ? "Relative Frequency" : "Frequency";
			yYaxisLeftTitleTextLeft = yPlotBottom + (plotHeight / 2.0f) - (OpenGL.largeTextWidth(gl, yAxisLeftTitle) / 2.0f);
			
			xPlotLeft = xYaxisLeftTitleTextBaseline + Theme.tickTextPadding;
			plotWidth = xPlotRight - xPlotLeft;
			
			if(xAxisTitleVisible && !legendVisible)
				xXaxisTitleTextLeft = xPlotLeft + (plotWidth  / 2.0f) - (OpenGL.largeTextWidth(gl, xAxisTitle)  / 2.0f);
			
			// the right y-axis is always for Frequency
			if(yAxisScale == YAxisScale.BOTH) {
				xYaxisRightTitleTextTop = xPlotRight;
				xYaxisRightTitleTextBaseline = xYaxisRightTitleTextTop - OpenGL.largeTextHeight;
				yAxisRightTitle = "Frequency";
				yYaxisRightTitleTextLeft = yPlotTop - (plotHeight / 2.0f) + (OpenGL.largeTextWidth(gl, yAxisRightTitle) / 2.0f);
				
				xPlotRight = xYaxisRightTitleTextBaseline - Theme.tickTextPadding;
				plotWidth = xPlotRight - xPlotLeft;
				
				if(xAxisTitleVisible && !legendVisible)
					xXaxisTitleTextLeft = xPlotLeft + (plotWidth  / 2.0f) - (OpenGL.largeTextWidth(gl, xAxisTitle)  / 2.0f);
			}
		}
		
		if(yAxisTicksVisible) {
			// the left y-axis is for Relative Frequency unless only Frequency will be shown
			float maxTextWidth = 0;
			for(String text : (yAxisScale == YAxisScale.FREQUENCY) ? yDivisionsFrequency.values() : yDivisionsRelativeFrequency.values())
				maxTextWidth = Math.max(maxTextWidth, OpenGL.smallTextWidth(gl, text));
			
			xYaxisLeftTickTextRight = xPlotLeft + maxTextWidth;
			xYaxisLeftTickLeft = xYaxisLeftTickTextRight + Theme.tickTextPadding;
			xYaxisLeftTickRight = xYaxisLeftTickLeft + Theme.tickLength;
			
			xPlotLeft = xYaxisLeftTickRight;
			plotWidth = xPlotRight - xPlotLeft;
			
			if(xAxisTitleVisible && !legendVisible)
				xXaxisTitleTextLeft = xPlotLeft + (plotWidth  / 2.0f) - (OpenGL.largeTextWidth(gl, xAxisTitle)  / 2.0f);
			
			// the right y-axis is always for Frequency
			if(yAxisScale == YAxisScale.BOTH) {
				maxTextWidth = 0;
				for(String text : yDivisionsFrequency.values())
					maxTextWidth = Math.max(maxTextWidth, OpenGL.smallTextWidth(gl, text));
				
				xYaxisRightTickTextLeft = xPlotRight - maxTextWidth;
				xYaxisRightTickRight = xYaxisRightTickTextLeft - Theme.tickTextPadding;
				xYaxisRightTickLeft = xYaxisRightTickRight - Theme.tickLength;
				
				xPlotRight = xYaxisRightTickLeft;
				plotWidth = xPlotRight - xPlotLeft;
				
				if(xAxisTitleVisible && !legendVisible)
					xXaxisTitleTextLeft = xPlotLeft + (plotWidth  / 2.0f) - (OpenGL.largeTextWidth(gl, xAxisTitle)  / 2.0f);
			}
		}
		
		// get the x divisions now that we know the final plot width
		xDivisions = ChartUtils.getFloatXdivisions125(gl, plotWidth, minX, maxX);
		
		// stop if the plot is too small
		if(plotWidth < 1 || plotHeight < 1)
			return handler;
		
		// draw plot background
		OpenGL.drawQuad2D(gl, Theme.plotBackgroundColor, xPlotLeft, yPlotBottom, xPlotRight, yPlotTop);
		
		// draw the x-axis scale
		if(xAxisTicksVisible) {
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
		if(yAxisTicksVisible) {
		
			// draw right y-axis scale if showing both frequency and relative frequency
			if(yAxisScale == YAxisScale.BOTH) {
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
			if(yAxisScale == YAxisScale.FREQUENCY) {
				
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
					OpenGL.buffer.put(xPlotRight); OpenGL.buffer.put(y); OpenGL.buffer.put(yAxisScale == YAxisScale.BOTH ? Theme.divisionLinesFadedColor : Theme.divisionLinesColor);

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
		if(legendVisible && datasetsCount > 0 && xLegendBorderRight < width - Theme.tilePadding) {
			OpenGL.drawQuad2D(gl, Theme.legendBackgroundColor, xLegendBorderLeft, yLegendBorderBottom, xLegendBorderRight, yLegendBorderTop);
			
			for(int i = 0; i < datasetsCount; i++) {
				Dataset d = datasets.getNormal(i);
				if(mouseX >= legendMouseoverCoordinates[i][0] && mouseX <= legendMouseoverCoordinates[i][2] && mouseY >= legendMouseoverCoordinates[i][1] && mouseY <= legendMouseoverCoordinates[i][3]) {
					OpenGL.drawQuadOutline2D(gl, Theme.tickLinesColor, legendMouseoverCoordinates[i][0], legendMouseoverCoordinates[i][1], legendMouseoverCoordinates[i][2], legendMouseoverCoordinates[i][3]);
					handler = EventHandler.onPress(event -> ConfigureView.instance.forDataset(d));
				}
				OpenGL.drawQuad2D(gl, d.glColor, legendBoxCoordinates[i][0], legendBoxCoordinates[i][1], legendBoxCoordinates[i][2], legendBoxCoordinates[i][3]);
				OpenGL.drawMediumText(gl, d.name, (int) xLegendNameLeft[i], (int) yLegendTextBaseline, 0);
			}
		}
					
		// draw the x-axis title, if space is available
		if(xAxisTitleVisible)
			if((!legendVisible && xXaxisTitleTextLeft > xPlotLeft) || (legendVisible && xXaxisTitleTextLeft > xLegendBorderRight + Theme.legendTextPadding))
				OpenGL.drawLargeText(gl, xAxisTitle, (int) xXaxisTitleTextLeft, (int) yXaxisTitleTextBasline, 0);
		
		// draw the left y-axis title, if space is available
		if(yAxisTitleVisible && yYaxisLeftTitleTextLeft >= yPlotBottom)
			OpenGL.drawLargeText(gl, yAxisLeftTitle, (int) xYaxisLeftTitleTextBaseline, (int) yYaxisLeftTitleTextLeft, 90);
		
		// draw the right y-axis title, if applicable, and if space is available
		if(yAxisTitleVisible && yAxisScale == YAxisScale.BOTH && yYaxisRightTitleTextLeft <= yPlotTop)
			OpenGL.drawLargeText(gl, yAxisRightTitle, (int) xYaxisRightTitleTextBaseline, (int) yYaxisRightTitleTextLeft, -90);

		// clip to the plot region
		int[] originalScissorArgs = new int[4];
		gl.glGetIntegerv(GL3.GL_SCISSOR_BOX, originalScissorArgs, 0);
		gl.glScissor(originalScissorArgs[0] + (int) xPlotLeft, originalScissorArgs[1] + (int) yPlotBottom, (int) plotWidth, (int) plotHeight);
		
		// draw the bins
		if(sampleCount > 0) {
			for(int datasetN = 0; datasetN < datasetsCount; datasetN++) {
				binsAsTriangles[datasetN].rewind();
				for(int binN = 0; binN < binCount; binN++) {
					
					float min = minX + (binSize *  binN);      // inclusive
					float max = minX + (binSize * (binN + 1)); // exclusive
					float center = (max + min) / 2f;
					
					float xBarCenter = ((center - minX) / range * plotWidth) + xPlotLeft;
					float yBarTop = ((float) bins[datasetN][binN] - minYfreq) / yFreqRange * plotHeight + yPlotBottom;
					float halfBarWidth = plotWidth / binCount / 2f;
					
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
				OpenGL.drawTrianglesXY(gl, GL3.GL_TRIANGLES, datasets.getNormal(datasetN).glColor, binsAsTriangles[datasetN], 6 * binCount);
			}
		}

		// stop clipping to the plot region
		gl.glScissor(originalScissorArgs[0], originalScissorArgs[1], originalScissorArgs[2], originalScissorArgs[3]);
		
		// draw the tooltip if the mouse is in the plot region
		if(sampleCount > 0 && SettingsController.getTooltipVisibility() && mouseX >= xPlotLeft && mouseX <= xPlotRight && mouseY >= yPlotBottom && mouseY <= yPlotTop) {
			int binN = (int) Math.floor(((float) mouseX - xPlotLeft) / plotWidth * binCount);
			if(binN > binCount - 1)
				binN = binCount - 1;
			float min = minX + (binSize *  binN);      // inclusive
			float max = minX + (binSize * (binN + 1)); // exclusive
			List<TooltipEntry> entries = new ArrayList<TooltipEntry>(datasetsCount + 1);
			entries.add(new TooltipEntry(null, ChartUtils.formattedNumber(min, 5) + " to " + ChartUtils.formattedNumber(max, 5) + " " + datasets.getNormal(0).unit));
			for(int datasetN = 0; datasetN < datasetsCount; datasetN++)
				entries.add(new TooltipEntry(datasets.getNormal(datasetN).glColor, bins[datasetN][binN] + " samples (" + ChartUtils.formattedNumber((double) bins[datasetN][binN] / (double) sampleCount * 100f, 4) + "%)"));
			float xBarCenter = ((binSize *  binN) + (binSize * (binN + 1))) / 2f / range * plotWidth + xPlotLeft;
			if(datasetsCount > 1) {
				OpenGL.buffer.rewind();
				OpenGL.buffer.put(xBarCenter); OpenGL.buffer.put(yPlotTop);
				OpenGL.buffer.put(xBarCenter); OpenGL.buffer.put(yPlotBottom);
				OpenGL.buffer.rewind();
				OpenGL.drawLinesXy(gl, GL3.GL_LINES, Theme.tooltipVerticalBarColor, OpenGL.buffer, 2);
				drawTooltip(gl, entries, (int) xBarCenter, mouseY, xPlotLeft, yPlotTop, xPlotRight, yPlotBottom);
			} else {
				int anchorY = (int) (((float) bins[0][binN] - minYfreq) / yFreqRange * plotHeight + yPlotBottom);
				anchorY = Math.max(anchorY, (int) yPlotBottom);
				drawTooltip(gl, entries, (int) xBarCenter, anchorY, xPlotLeft, yPlotTop, xPlotRight, yPlotBottom);				
			}
		}
		
		// draw the plot border
		OpenGL.drawQuadOutline2D(gl, Theme.plotOutlineColor, xPlotLeft, yPlotBottom, xPlotRight, yPlotTop);
		
		return handler;
		
	}

}
