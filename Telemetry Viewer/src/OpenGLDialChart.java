import java.util.List;

import javax.swing.JPanel;
import com.jogamp.opengl.GL2ES3;
import com.jogamp.opengl.GL3;

public class OpenGLDialChart extends PositionedChart {
	
	final int   dialResolution = 400; // how many quads to draw
	final float dialThickness = 0.4f; // percentage of the radius
	
	private DatasetsInterface.WidgetDatasets datasetWidget;
	private WidgetCheckbox datasetLabelVisibility;
	private WidgetTextfield<Float> dialMinimum;
	private WidgetTextfield<Float> dialMaximum;
	private WidgetCheckbox readingLabelVisibility;
	private WidgetCheckbox minMaxLabelsVisibility;
	
	@Override public String toString() {
		
		return "Dial";
		
	}
	
	public OpenGLDialChart() {
		
		datasetLabelVisibility = new WidgetCheckbox("Show Dataset Label", true);
		
		dialMinimum = WidgetTextfield.ofFloat(-Float.MAX_VALUE, Float.MAX_VALUE, -1)
		                             .setPrefix("Minimum")
		                             .setExportLabel("dial minimum")
		                             .onChange((newMinimum, oldMinimum) -> {
		                                           if(newMinimum > dialMaximum.get())
		                                               dialMaximum.set(newMinimum);
		                                           return true;
		                                       });
		
		dialMaximum = WidgetTextfield.ofFloat(-Float.MAX_VALUE, Float.MAX_VALUE, 1)
		                             .setPrefix("Maximum")
		                             .setExportLabel("dial maximum")
		                             .onChange((newMaximum, oldMaximum) -> {
		                                           if(newMaximum < dialMinimum.get())
		                                               dialMinimum.set(newMaximum);
		                                           return true;
		                                       });
		
		datasetWidget = datasets.getComboboxesWidget(List.of("Dataset"),
		                                             newDatasets -> {
		                                                 if(newDatasets.isEmpty()) // no telemetry connections
		                                                     return;
		                                                 dialMinimum.setSuffix(datasets.getNormal(0).unit.get());
		                                                 dialMaximum.setSuffix(datasets.getNormal(0).unit.get());
		                                             });
		
		readingLabelVisibility = new WidgetCheckbox("Show Reading Label", true);
		
		minMaxLabelsVisibility = new WidgetCheckbox("Show Min/Max Labels", true);

		widgets.add(datasetWidget);
		widgets.add(datasetLabelVisibility);
		widgets.add(dialMinimum);
		widgets.add(dialMaximum);
		widgets.add(readingLabelVisibility);
		widgets.add(minMaxLabelsVisibility);
		
	}
	
	@Override public void getConfigurationGui(JPanel gui) {
		
		gui.add(Theme.newWidgetsPanel("Data")
		             .with(datasetWidget)
		             .with(datasetLabelVisibility)
		             .getPanel());
		
		gui.add(Theme.newWidgetsPanel("Dial")
		             .with(dialMinimum)
		             .with(dialMaximum)
		             .with(readingLabelVisibility)
		             .with(minMaxLabelsVisibility)
		             .getPanel());
		
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
		Field dataset = datasets.getNormal(0);
		float sample = lastSampleNumber > 0 ? datasets.getSample(dataset, lastSampleNumber) : 0;
		
		// calculate x and y positions of everything
		float xPlotLeft = Theme.tilePadding;
		float xPlotRight = width - Theme.tilePadding;
		float plotWidth = xPlotRight - xPlotLeft;
		float yPlotTop = height - Theme.tilePadding;
		float yPlotBottom = Theme.tilePadding;
		float plotHeight = yPlotTop - yPlotBottom;
		
		float yMinMaxLabelsBaseline = Theme.tilePadding;
		float yMinMaxLabelsTop = yMinMaxLabelsBaseline + OpenGL.smallTextHeight;
		String minLabel = ChartUtils.formattedNumber(dialMinimum.get(), 6);
		String maxLabel = ChartUtils.formattedNumber(dialMaximum.get(), 6);
		float minLabelWidth = OpenGL.smallTextWidth(gl, minLabel);
		float maxLabelWidth = OpenGL.smallTextWidth(gl, maxLabel);
		if(minMaxLabelsVisibility.get()) {
			yPlotBottom = yMinMaxLabelsTop + Theme.tickTextPadding;
			plotHeight = yPlotTop - yPlotBottom;
		}
		
		float xCircleCenter = plotWidth / 2 + Theme.tilePadding;
		float yCircleCenter = yPlotBottom;
		float circleOuterRadius = Float.min(plotHeight, plotWidth / 2);
		float circleInnerRadius = circleOuterRadius * (1 - dialThickness);
		float xMinLabelLeft = xCircleCenter - circleOuterRadius;
		float xMaxLabelLeft = xCircleCenter + circleOuterRadius - maxLabelWidth;
		
		// stop if the dial is too small
		if(circleOuterRadius < 0)
			return handler;
		
		String readingLabel = ChartUtils.formattedNumber(sample, 6) + " " + dataset.unit.get();
		float readingLabelWidth = OpenGL.largeTextWidth(gl, readingLabel);
		float xReadingLabelLeft = xCircleCenter - (readingLabelWidth / 2);
		float yReadingLabelBaseline = yPlotBottom;
		float yReadingLabelTop = yReadingLabelBaseline + OpenGL.largeTextHeight;
		float readingLabelRadius = (float) Math.sqrt((readingLabelWidth / 2) * (readingLabelWidth / 2) + (yReadingLabelTop - yCircleCenter) * (yReadingLabelTop - yCircleCenter));
		if(readingLabelVisibility.get() && lastSampleNumber >= 0) {
			if(readingLabelRadius + Theme.tickTextPadding < circleInnerRadius)
				OpenGL.drawLargeText(gl, readingLabel, (int) xReadingLabelLeft, (int) yReadingLabelBaseline, 0);
		}
		
		if(minMaxLabelsVisibility.get() && lastSampleNumber >= 0) {
			if(xMinLabelLeft + minLabelWidth + Theme.tickTextPadding < xMaxLabelLeft - Theme.tickTextPadding) {
				OpenGL.drawSmallText(gl, minLabel, (int) xMinLabelLeft, (int) yMinMaxLabelsBaseline, 0);
				OpenGL.drawSmallText(gl, maxLabel, (int) xMaxLabelLeft, (int) yMinMaxLabelsBaseline, 0);
			}
		}
		
		String datasetLabel = dataset.name.get();
		float datasetLabelWidth = OpenGL.largeTextWidth(gl, datasetLabel);
		float yDatasetLabelBaseline = readingLabelVisibility.get() ? yReadingLabelTop + Theme.tickTextPadding + Theme.legendTextPadding : yPlotBottom;
		float yDatasetLabelTop = yDatasetLabelBaseline + OpenGL.largeTextHeight;
		float xDatasetLabelLeft = xCircleCenter - (datasetLabelWidth / 2);
		float datasetLabelRadius = (float) Math.sqrt((datasetLabelWidth / 2) * (datasetLabelWidth / 2) + (yDatasetLabelTop - yCircleCenter) * (yDatasetLabelTop - yCircleCenter)) + Theme.legendTextPadding;
		if(datasetLabelVisibility.get() && lastSampleNumber >= 0) {
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
		float dialPercentage = (sample - dialMinimum.get()) / (dialMaximum.get() - dialMinimum.get());
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
			
			float[] color = angle >= Math.PI * dialPercentage ? Theme.plotBackgroundColor : dataset.color.getGl();
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
