import javax.swing.JPanel;
import com.jogamp.opengl.GL2ES3;
import com.jogamp.opengl.GL3;

public class OpenGLDialChart extends PositionedChart {
	
	final int   dialResolution = 400; // how many quads to draw
	final float dialThickness = 0.4f; // percentage of the radius
	
	// min max labels
	float yMinMaxLabelsBaseline;
	float yMinMaxLabelsTop;
	String minLabel;
	String maxLabel;
	float minLabelWidth;
	float maxLabelWidth;
	float xMinLabelLeft;
	float xMaxLabelLeft;
	
	// reading label
	String readingLabel;
	float readingLabelWidth;
	float xReadingLabelLeft;
	float yReadingLabelBaseline;
	float yReadingLabelTop;
	float readingLabelRadius;
	
	// dataset label
	String datasetLabel;
	float datasetLabelWidth;
	float yDatasetLabelBaseline;
	float yDatasetLabelTop;
	float xDatasetLabelLeft;
	float datasetLabelRadius;
	
	// user settings
	private WidgetDatasetComboboxes datasetWidget;
	
	private boolean datasetLabelVisible = true;
	private WidgetCheckbox datasetLabelCheckbox;
	
	private float dialMinimum = -1;
	private WidgetTextfieldFloat dialMinimumTextfield;
	
	private float dialMaximum = 1;
	private WidgetTextfieldFloat dialMaximumTextfield;
	
	private boolean readingLabelVisible = true;
	private WidgetCheckbox readingLabelCheckbox;
	
	private boolean minMaxLabelsVisible = true;
	private WidgetCheckbox minMaxLabelsCheckbox;
	
	@Override public String toString() {
		
		return "Dial";
		
	}
	
	public OpenGLDialChart(int x1, int y1, int x2, int y2) {
		
		super(x1, y1, x2, y2);
		
		datasetLabelCheckbox = new WidgetCheckbox("Show Dataset Label",
		                                          datasetLabelVisible,
		                                          newVisibility -> datasetLabelVisible = newVisibility);
		
		dialMinimumTextfield = new WidgetTextfieldFloat("Minimum",
		                                                "dial minimum",
		                                                "",
		                                                -Float.MAX_VALUE,
		                                                Float.MAX_VALUE,
		                                                dialMinimum,
		                                                newMinimum -> {
		                                                	dialMinimum = newMinimum;
		                                                	if(dialMinimum > dialMaximum)
		                                                		dialMaximumTextfield.setNumber(dialMinimum);
		                                                });
		
		dialMaximumTextfield = new WidgetTextfieldFloat("Maximum",
		                                                "dial maximum",
		                                                "",
		                                                -Float.MAX_VALUE,
		                                                Float.MAX_VALUE,
		                                                dialMaximum,
		                                                newMaximum -> {
		                                                	dialMaximum = newMaximum;
		                                                	if(dialMaximum < dialMinimum)
		                                                		dialMinimumTextfield.setNumber(dialMaximum);
		                                                });
		
		datasetWidget = new WidgetDatasetComboboxes(new String[] {"Dataset"},
		                                            newDatasets -> {
		                                                if(newDatasets.isEmpty()) // no telemetry connections
		                                                    return;
		                                                datasets.setNormals(newDatasets);
		                                                dialMinimumTextfield.setUnit(datasets.getNormal(0).unit);
		                                                dialMaximumTextfield.setUnit(datasets.getNormal(0).unit);
		                                            });
		
		readingLabelCheckbox = new WidgetCheckbox("Show Reading Label",
		                                          readingLabelVisible,
		                                          newVisibility -> readingLabelVisible = newVisibility);
		
		minMaxLabelsCheckbox = new WidgetCheckbox("Show Min/Max Labels",
		                                          minMaxLabelsVisible,
		                                          newVisibility -> minMaxLabelsVisible = newVisibility);

		widgets.add(datasetWidget);
		widgets.add(datasetLabelCheckbox);
		widgets.add(dialMinimumTextfield);
		widgets.add(dialMaximumTextfield);
		widgets.add(readingLabelCheckbox);
		widgets.add(minMaxLabelsCheckbox);
		
	}
	
	@Override public void getConfigurationGui(JPanel gui) {
		
		JPanel dataPanel = Theme.newWidgetsPanel("Data");
		datasetWidget.appendToGui(dataPanel);
		dataPanel.add(datasetLabelCheckbox);
		
		JPanel dialPanel = Theme.newWidgetsPanel("Dial");
		dialPanel.add(dialMinimumTextfield);
		dialPanel.add(dialMaximumTextfield);
		dialPanel.add(readingLabelCheckbox);
		dialPanel.add(minMaxLabelsCheckbox);
		
		gui.add(dataPanel);
		gui.add(dialPanel);
		
	}
	
	@Override public EventHandler drawChart(GL2ES3 gl, float[] chartMatrix, int width, int height, long endTimestamp, int endSampleNumber, double zoomLevel, int mouseX, int mouseY) {
		
		EventHandler handler = null;
		
		// sanity check
		if(datasets.normalsCount() != 1)
			return handler;
		
		// get the sample
		int lastSampleNumber = endSampleNumber;
		int trueLastSampleNumber = datasets.connection.getSampleCount() - 1;
		if(lastSampleNumber > trueLastSampleNumber)
			lastSampleNumber = trueLastSampleNumber;
		Dataset dataset = datasets.getNormal(0);
		float sample = lastSampleNumber > 0 ? datasets.getSample(dataset, lastSampleNumber) : 0;
		
		// calculate x and y positions of everything
		float xPlotLeft = Theme.tilePadding;
		float xPlotRight = width - Theme.tilePadding;
		float plotWidth = xPlotRight - xPlotLeft;
		float yPlotTop = height - Theme.tilePadding;
		float yPlotBottom = Theme.tilePadding;
		float plotHeight = yPlotTop - yPlotBottom;
		
		if(minMaxLabelsVisible) {
			yMinMaxLabelsBaseline = Theme.tilePadding;
			yMinMaxLabelsTop = yMinMaxLabelsBaseline + OpenGL.smallTextHeight;
			minLabel = ChartUtils.formattedNumber(dialMinimum, 6);
			maxLabel = ChartUtils.formattedNumber(dialMaximum, 6);
			minLabelWidth = OpenGL.smallTextWidth(gl, minLabel);
			maxLabelWidth = OpenGL.smallTextWidth(gl, maxLabel);
			
			yPlotBottom = yMinMaxLabelsTop + Theme.tickTextPadding;
			plotHeight = yPlotTop - yPlotBottom;
		}
		
		float xCircleCenter = plotWidth / 2 + Theme.tilePadding;
		float yCircleCenter = yPlotBottom;
		float circleOuterRadius = Float.min(plotHeight, plotWidth / 2);
		float circleInnerRadius = circleOuterRadius * (1 - dialThickness);
		
		// stop if the dial is too small
		if(circleOuterRadius < 0)
			return handler;
		
		if(readingLabelVisible && lastSampleNumber >= 0) {
			readingLabel = ChartUtils.formattedNumber(sample, 6) + " " + dataset.unit;
			readingLabelWidth = OpenGL.largeTextWidth(gl, readingLabel);
			xReadingLabelLeft = xCircleCenter - (readingLabelWidth / 2);
			yReadingLabelBaseline = yPlotBottom;
			yReadingLabelTop = yReadingLabelBaseline + OpenGL.largeTextHeight;
			readingLabelRadius = (float) Math.sqrt((readingLabelWidth / 2) * (readingLabelWidth / 2) + (yReadingLabelTop - yCircleCenter) * (yReadingLabelTop - yCircleCenter));
			
			if(readingLabelRadius + Theme.tickTextPadding < circleInnerRadius)
				OpenGL.drawLargeText(gl, readingLabel, (int) xReadingLabelLeft, (int) yReadingLabelBaseline, 0);
		}
		
		if(minMaxLabelsVisible && lastSampleNumber >= 0) {
			xMinLabelLeft = xCircleCenter - circleOuterRadius;
			xMaxLabelLeft = xCircleCenter + circleOuterRadius - maxLabelWidth;
			
			if(xMinLabelLeft + minLabelWidth + Theme.tickTextPadding < xMaxLabelLeft - Theme.tickTextPadding) {
				OpenGL.drawSmallText(gl, minLabel, (int) xMinLabelLeft, (int) yMinMaxLabelsBaseline, 0);
				OpenGL.drawSmallText(gl, maxLabel, (int) xMaxLabelLeft, (int) yMinMaxLabelsBaseline, 0);
			}
		}
		
		if(datasetLabelVisible && lastSampleNumber >= 0) {
			datasetLabel = dataset.name;
			datasetLabelWidth = OpenGL.largeTextWidth(gl, datasetLabel);
			yDatasetLabelBaseline = readingLabelVisible ? yReadingLabelTop + Theme.tickTextPadding + Theme.legendTextPadding : yPlotBottom;
			yDatasetLabelTop = yDatasetLabelBaseline + OpenGL.largeTextHeight;
			xDatasetLabelLeft = xCircleCenter - (datasetLabelWidth / 2);
			datasetLabelRadius = (float) Math.sqrt((datasetLabelWidth / 2) * (datasetLabelWidth / 2) + (yDatasetLabelTop - yCircleCenter) * (yDatasetLabelTop - yCircleCenter)) + Theme.legendTextPadding;
			
			if(datasetLabelRadius + Theme.tickTextPadding < circleInnerRadius) {
				float xMouseoverLeft = xDatasetLabelLeft - Theme.legendTextPadding;
				float xMouseoverRight = xDatasetLabelLeft + datasetLabelWidth + Theme.legendTextPadding;
				float yMouseoverBottom = yDatasetLabelBaseline - Theme.legendTextPadding;
				float yMouseoverTop = yDatasetLabelTop + Theme.legendTextPadding;
				if(mouseX >= xMouseoverLeft && mouseX <= xMouseoverRight && mouseY >= yMouseoverBottom && mouseY <= yMouseoverTop) {
					OpenGL.drawQuad2D(gl, Theme.legendBackgroundColor, xMouseoverLeft, yMouseoverBottom, xMouseoverRight, yMouseoverTop);
					OpenGL.drawQuadOutline2D(gl, Theme.tickLinesColor, xMouseoverLeft, yMouseoverBottom, xMouseoverRight, yMouseoverTop);
					handler = EventHandler.onPress(event -> ConfigureView.instance.forDataset(dataset));
				}
				OpenGL.drawLargeText(gl, datasetLabel, (int) xDatasetLabelLeft, (int) yDatasetLabelBaseline, 0);
			}
		}
		
		// draw the dial
		float dialPercentage = (sample - dialMinimum) / (dialMaximum - dialMinimum);
		OpenGL.buffer.rewind();
		for(float angle = 0; angle < Math.PI; angle += Math.PI / dialResolution) {
			
			float x1 = -1f * circleOuterRadius *                       (float) Math.cos(angle)                            + xCircleCenter; // top-left
			float y1 =       circleOuterRadius *                       (float) Math.sin(angle)                            + yCircleCenter;
			float x2 = -1f * circleOuterRadius *                       (float) Math.cos(angle + Math.PI / dialResolution) + xCircleCenter; // top-right
			float y2 =       circleOuterRadius *                       (float) Math.sin(angle + Math.PI / dialResolution) + yCircleCenter;
			float x4 = -1f * circleOuterRadius * (1 - dialThickness) * (float) Math.cos(angle)                            + xCircleCenter; // bottom-left
			float y4 =       circleOuterRadius * (1 - dialThickness) * (float) Math.sin(angle)                            + yCircleCenter;
			float x3 = -1f * circleOuterRadius * (1 - dialThickness) * (float) Math.cos(angle + Math.PI / dialResolution) + xCircleCenter; // bottom-right
			float y3 =       circleOuterRadius * (1 - dialThickness) * (float) Math.sin(angle + Math.PI / dialResolution) + yCircleCenter;
			
			float[] color = angle >= Math.PI * dialPercentage ? Theme.plotBackgroundColor : dataset.glColor;
			OpenGL.buffer.put(x1); OpenGL.buffer.put(y1); OpenGL.buffer.put(color);
			OpenGL.buffer.put(x2); OpenGL.buffer.put(y2); OpenGL.buffer.put(color);
			OpenGL.buffer.put(x4); OpenGL.buffer.put(y4); OpenGL.buffer.put(color);
			
			OpenGL.buffer.put(x4); OpenGL.buffer.put(y4); OpenGL.buffer.put(color);
			OpenGL.buffer.put(x2); OpenGL.buffer.put(y2); OpenGL.buffer.put(color);
			OpenGL.buffer.put(x3); OpenGL.buffer.put(y3); OpenGL.buffer.put(color);
			
		}
		OpenGL.buffer.rewind();
		OpenGL.drawTrianglesXYRGBA(gl, GL3.GL_TRIANGLES, OpenGL.buffer, 6 * dialResolution);
		
		return handler;
		
	}

}
