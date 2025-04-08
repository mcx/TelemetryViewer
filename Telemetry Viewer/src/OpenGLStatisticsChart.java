import javax.swing.JPanel;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;

import com.jogamp.opengl.GL2ES3;

public class OpenGLStatisticsChart extends PositionedChart {
	
	private DatasetsInterface.WidgetDatasets datasetsWidget;
	private WidgetTextfield<Integer> sampleCountTextfield;
	private WidgetCheckbox sampleCountVisibility;
	private WidgetCheckbox currentValuesVisibility;
	private WidgetCheckbox minimumsVisibility;
	private WidgetCheckbox maximumsVisibility;
	private WidgetCheckbox meansVisibility;
	private WidgetCheckbox mediansVisibility;
	private WidgetCheckbox standardDeviationVisibility;
	private WidgetCheckbox percentileVisibility;
	
	// duration
	private long durationMilliseconds;
	private String showAs;
	
	@Override public String toString() {
		
		return "Statistics";
		
	}
	
	public OpenGLStatisticsChart() {
		
		datasetsWidget = datasets.getCheckboxesWidget(newDatasets -> {});
		
		sampleCountTextfield = WidgetTextfield.ofInt(1, Integer.MAX_VALUE / 16, ConnectionsController.getDefaultChartDuration())
		                                      .setSuffix("Samples")
		                                      .setExportLabel("duration");
		
		sampleCountVisibility       = new WidgetCheckbox("Show Sample Count", true);
		currentValuesVisibility     = new WidgetCheckbox("Current Value", true);
		minimumsVisibility          = new WidgetCheckbox("Minimum", true);
		maximumsVisibility          = new WidgetCheckbox("Maximum", true);
		meansVisibility             = new WidgetCheckbox("Mean", true);
		mediansVisibility           = new WidgetCheckbox("Median", true);
		standardDeviationVisibility = new WidgetCheckbox("Standard Deviation", true);
		percentileVisibility        = new WidgetCheckbox("90th Percentile", true);
		
		widgets.add(datasetsWidget);
		widgets.add(sampleCountTextfield);
		widgets.add(sampleCountVisibility);
		widgets.add(currentValuesVisibility);
		widgets.add(minimumsVisibility);
		widgets.add(maximumsVisibility);
		widgets.add(meansVisibility);
		widgets.add(mediansVisibility);
		widgets.add(standardDeviationVisibility);
		widgets.add(percentileVisibility);
		
	}
	
	@Override public void getConfigurationGui(JPanel gui) {
		
		gui.add(Theme.newWidgetsPanel("Data")
		             .with(datasetsWidget, "")
		             .with(sampleCountTextfield, "split 2, sizegroup 0")
		             .with(sampleCountVisibility, "sizegroup 0")
		             .getPanel());
		
		gui.add(Theme.newWidgetsPanel("Statistics")
		             .with(currentValuesVisibility)
		             .with(minimumsVisibility)
		             .with(maximumsVisibility)
		             .with(meansVisibility)
		             .with(mediansVisibility)
		             .with(standardDeviationVisibility)
		             .with(percentileVisibility)
		             .getPanel());
		
	}
	
	@Override public EventHandler drawChart(GL2ES3 gl, float[] chartMatrix, int width, int height, long endTimestamp, int endSampleNumber, double zoomLevel, int mouseX, int mouseY) {
		
		EventHandler handler = null;
		
		int datasetsCount = datasets.normalsCount();
		
		// done if no datasets are selected
		if(datasetsCount < 1) {
			String text = "[no datasets selected]";
			int x = (width / 2) - (int) (OpenGL.largeTextWidth(gl, text) / 2);
			int y = (height / 2) - (int) (OpenGL.largeTextHeight / 2);
			OpenGL.drawLargeText(gl, text, x, y, 0);
			return handler;
		}
		
		// get the samples
		int trueLastSampleNumber = datasets.connection.getSampleCount() - 1;
		int lastSampleNumber = -1;
		int firstSampleNumber = -1;
		if(sampleCountMode) {
			lastSampleNumber = Integer.min(endSampleNumber, trueLastSampleNumber);
			firstSampleNumber = endSampleNumber - (int) Math.round(sampleCountTextfield.get() * zoomLevel) + 1;
		} else {
			lastSampleNumber = datasets.getClosestSampleNumberAtOrBefore(endTimestamp, trueLastSampleNumber);
			firstSampleNumber = datasets.getClosestSampleNumberAfter(endTimestamp - Math.round(durationMilliseconds * zoomLevel));
		}
		
		// done if no telemetry
		if(lastSampleNumber < 0) {
			String text = "[waiting for telemetry]";
			int x = (width / 2) - (int) (OpenGL.smallTextWidth(gl, text) / 2);
			int y = (height / 2) - (int) (OpenGL.smallTextHeight / 2);
			OpenGL.drawSmallText(gl, text, x, y, 0);
			return handler;
		}

		if(firstSampleNumber < 0)
			firstSampleNumber = 0;
		if(firstSampleNumber > lastSampleNumber)
			firstSampleNumber = lastSampleNumber;
		int sampleCount = lastSampleNumber - firstSampleNumber + 1;

		String durationLabel = sampleCountMode             ? "(" + sampleCount + " Samples)" :
		                       showAs.equals("Timestamps") ? "(" + SettingsView.formatTimestampToMilliseconds(datasets.getTimestamp(firstSampleNumber)).replace('\n', ' ') + " to " + SettingsView.formatTimestampToMilliseconds(datasets.getTimestamp(lastSampleNumber)).replace('\n', ' ') + ")" :
		                                                     "(" + (datasets.getTimestamp(lastSampleNumber) - datasets.getTimestamp(firstSampleNumber)) + " ms)";
		
		// determine the text to display
		int lineCount = 1; // always show the dataset labels
		if(currentValuesVisibility.get())     lineCount++;
		if(minimumsVisibility.get())          lineCount++;
		if(maximumsVisibility.get())          lineCount++;
		if(meansVisibility.get())             lineCount++;
		if(mediansVisibility.get())           lineCount++;
		if(standardDeviationVisibility.get()) lineCount++;
		if(percentileVisibility.get())        lineCount++;
		String[][] text = new String[datasetsCount + 1][lineCount];
		
		// first column of text are the labels, but don't label the dataset name or current value because that's obvious
		int line = 0;
		text[0][line++] = "";
		if(currentValuesVisibility.get())     text[0][line++] = "";
		if(minimumsVisibility.get())          text[0][line++] = "Minimum";
		if(maximumsVisibility.get())          text[0][line++] = "Maximum";
		if(meansVisibility.get())             text[0][line++] = "Mean";
		if(mediansVisibility.get())           text[0][line++] = "Median";
		if(standardDeviationVisibility.get()) text[0][line++] = "Std Dev";
		if(percentileVisibility.get())        text[0][line++] = "90th Pctl";
		
		// subsequent columns of text are the dataset names and numeric values
		if(sampleCount > 0)
			for(int datasetN = 0; datasetN < datasetsCount; datasetN++) {
				Field dataset = datasets.getNormal(datasetN);
				float[] samples = datasets.getSamplesArray(dataset, firstSampleNumber, lastSampleNumber);
				double[] doubles = new double[samples.length];
				for(int i = 0; i < samples.length; i++)
					doubles[i] = (double) samples[i];
				
				DescriptiveStatistics stats = new DescriptiveStatistics(doubles);
				
				int column = datasetN + 1;
				line = 0;
				text[column][line++] = dataset.name.get();
				String unit = dataset.unit.get();
				if(currentValuesVisibility.get())     text[column][line++] = Theme.getFloat(samples[samples.length - 1],          unit, false);
				if(minimumsVisibility.get())          text[column][line++] = Theme.getFloat((float) stats.getMin(),               unit, false);
				if(maximumsVisibility.get())          text[column][line++] = Theme.getFloat((float) stats.getMax(),               unit, false);
				if(meansVisibility.get())             text[column][line++] = Theme.getFloat((float) stats.getMean(),              unit, false);
				if(mediansVisibility.get())           text[column][line++] = Theme.getFloat((float) stats.getPercentile(50),      unit, false);
				if(standardDeviationVisibility.get()) text[column][line++] = Theme.getFloat((float) stats.getStandardDeviation(), unit, false);
				if(percentileVisibility.get())        text[column][line++] = Theme.getFloat((float) stats.getPercentile(90),      unit, false);
			}
		
		// determine the width of each piece of text, and track the max for each column
		float[] columnWidth = new float[datasetsCount + 1];
		float[][] textWidth = new float[datasetsCount + 1][lineCount];
		
		line = 0;
		columnWidth[0] = 0;
		textWidth[0][line++] = 0; // no label for the dataset name
		if(currentValuesVisibility.get())
			textWidth[0][line++] = 0; // no label for the current value
		if(minimumsVisibility.get()) {
			textWidth[0][line] = OpenGL.smallTextWidth(gl, text[0][line]);
			if(columnWidth[0] < textWidth[0][line])
				columnWidth[0] = textWidth[0][line];
			line++;
		}
		if(maximumsVisibility.get()) {
			textWidth[0][line] = OpenGL.smallTextWidth(gl, text[0][line]);
			if(columnWidth[0] < textWidth[0][line])
				columnWidth[0] = textWidth[0][line];
			line++;
		}
		if(meansVisibility.get()) {
			textWidth[0][line] = OpenGL.smallTextWidth(gl, text[0][line]);
			if(columnWidth[0] < textWidth[0][line])
				columnWidth[0] = textWidth[0][line];
			line++;
		}
		if(mediansVisibility.get()) {
			textWidth[0][line] = OpenGL.smallTextWidth(gl, text[0][line]);
			if(columnWidth[0] < textWidth[0][line])
				columnWidth[0] = textWidth[0][line];
			line++;
		}
		if(standardDeviationVisibility.get()) {
			textWidth[0][line] = OpenGL.smallTextWidth(gl, text[0][line]);
			if(columnWidth[0] < textWidth[0][line])
				columnWidth[0] = textWidth[0][line];
			line++;
		}
		if(percentileVisibility.get()) {
			textWidth[0][line] = OpenGL.smallTextWidth(gl, text[0][line]);
			if(columnWidth[0] < textWidth[0][line])
				columnWidth[0] = textWidth[0][line];
			line++;
		}
		
		if(sampleCount > 0)
			for(int datasetN = 0; datasetN < datasetsCount; datasetN++) {
				int column = datasetN + 1;
				textWidth[column][0] = OpenGL.smallTextWidth(gl, text[column][0]);
				columnWidth[column] = textWidth[column][0];
				if(currentValuesVisibility.get()) {
					textWidth[column][1] = OpenGL.largeTextWidth(gl, text[column][1]);
					if(columnWidth[column] < textWidth[column][1])
						columnWidth[column] = textWidth[column][1];
				}
				for(line = currentValuesVisibility.get() ? 2 : 1; line < lineCount; line++) {
					textWidth[column][line] = OpenGL.smallTextWidth(gl, text[column][line]);
					if(columnWidth[column] < textWidth[column][line])
						columnWidth[column] = textWidth[column][line];
				}
			}
		
		// determine the gaps to leave above and to the left of the text
		boolean showingLabels = columnWidth[0] > 0;
		int occupiedHeight = 0;
		                                      occupiedHeight += Theme.tilePadding + OpenGL.smallTextHeight; // dataset name
		if(currentValuesVisibility.get())     occupiedHeight += Theme.tilePadding + OpenGL.largeTextHeight;
		if(minimumsVisibility.get())          occupiedHeight += Theme.tilePadding + OpenGL.smallTextHeight;
		if(maximumsVisibility.get())          occupiedHeight += Theme.tilePadding + OpenGL.smallTextHeight;
		if(meansVisibility.get())             occupiedHeight += Theme.tilePadding + OpenGL.smallTextHeight;
		if(mediansVisibility.get())           occupiedHeight += Theme.tilePadding + OpenGL.smallTextHeight;
		if(standardDeviationVisibility.get()) occupiedHeight += Theme.tilePadding + OpenGL.smallTextHeight;
		if(percentileVisibility.get())        occupiedHeight += Theme.tilePadding + OpenGL.smallTextHeight;
		if(sampleCountVisibility.get())       occupiedHeight += 2 * (Theme.tilePadding + OpenGL.smallTextHeight);
		occupiedHeight += Theme.tilePadding;
		int occupiedWidth = (int) columnWidth[0];
		for(int i = 1; i < columnWidth.length; i++) {
			if(occupiedWidth > 0)
				occupiedWidth += (int) (2*Theme.tilePadding);
			occupiedWidth += columnWidth[i];
		}
		occupiedWidth += 2*Theme.tilePadding;
		int xOffset = (width - occupiedWidth) / 2;
		if(xOffset < 0)
			xOffset = 0;
		int yOffset = (height - occupiedHeight) / 2;
		if(yOffset < 0)
			yOffset = 0;
		
		// draw the labels
		int x = (int) Theme.tilePadding + xOffset;
		int y = height - yOffset;
		y -= (int) Theme.tilePadding + OpenGL.smallTextHeight; // no label for dataset name
		line = 1;
		if(currentValuesVisibility.get()) {
			y -= (int) (Theme.tilePadding + OpenGL.largeTextHeight); // no label for current value
			line++;
		}
		if(minimumsVisibility.get()) {
			y -= (int) (Theme.tilePadding + OpenGL.smallTextHeight);
			OpenGL.drawSmallText(gl, text[0][line++], x, y, 0);
		}
		if(maximumsVisibility.get()) {
			y -= (int) (Theme.tilePadding + OpenGL.smallTextHeight);
			OpenGL.drawSmallText(gl, text[0][line++], x, y, 0);
		}
		if(meansVisibility.get()) {
			y -= (int) (Theme.tilePadding + OpenGL.smallTextHeight);
			OpenGL.drawSmallText(gl, text[0][line++], x, y, 0);
		}
		if(mediansVisibility.get()) {
			y -= (int) (Theme.tilePadding + OpenGL.smallTextHeight);
			OpenGL.drawSmallText(gl, text[0][line++], x, y, 0);
		}
		if(standardDeviationVisibility.get()) {
			y -= (int) (Theme.tilePadding + OpenGL.smallTextHeight);
			OpenGL.drawSmallText(gl, text[0][line++], x, y, 0);
		}
		if(percentileVisibility.get()) {
			y -= (int) (Theme.tilePadding + OpenGL.smallTextHeight);
			OpenGL.drawSmallText(gl, text[0][line++], x, y, 0);
		}
		
		// draw the dataset names and numbers
		x = (int) (Theme.tilePadding + xOffset + columnWidth[0]);
		if(sampleCount > 0)
			for(int datasetN = 0; datasetN < datasetsCount; datasetN++) {
				
				int column = datasetN + 1;
				
				x += (int) (2*Theme.tilePadding);
				if(!showingLabels && datasetN == 0)
					x = (int) Theme.tilePadding + xOffset;
				
				y = height - yOffset;
				line = 0;
				
				y -= (int) Theme.tilePadding + OpenGL.smallTextHeight;
				int xCentered = (int) (x + (columnWidth[column] - textWidth[column][line]) / 2);
				int xRightJustified = (int) (x + columnWidth[column] - textWidth[column][line]);
				OpenGL.drawSmallText(gl, text[column][line++], (int) xCentered, y, 0);
				
				if(currentValuesVisibility.get()) {
					xCentered = (int) (x + (columnWidth[column] - textWidth[column][line]) / 2);
					xRightJustified = (int) (x + columnWidth[column] - textWidth[column][line]);
					y -= (int) (Theme.tilePadding + OpenGL.largeTextHeight);
					OpenGL.drawLargeText(gl, text[column][line++], lineCount == 2 ? xCentered : xRightJustified, y, 0);
				}
				
				if(minimumsVisibility.get()) {
					xCentered = (int) (x + (columnWidth[column] - textWidth[column][line]) / 2);
					xRightJustified = (int) (x + columnWidth[column] - textWidth[column][line]);
					y -= (int) (Theme.tilePadding + OpenGL.smallTextHeight);
					OpenGL.drawSmallText(gl, text[column][line++], lineCount == 2 ? xCentered : xRightJustified, y, 0);
				}
				
				if(maximumsVisibility.get()) {
					xCentered = (int) (x + (columnWidth[column] - textWidth[column][line]) / 2);
					xRightJustified = (int) (x + columnWidth[column] - textWidth[column][line]);
					y -= (int) (Theme.tilePadding + OpenGL.smallTextHeight);
					OpenGL.drawSmallText(gl, text[column][line++], lineCount == 2 ? xCentered : xRightJustified, y, 0);
				}
				
				if(meansVisibility.get()) {
					xCentered = (int) (x + (columnWidth[column] - textWidth[column][line]) / 2);
					xRightJustified = (int) (x + columnWidth[column] - textWidth[column][line]);
					y -= (int) (Theme.tilePadding + OpenGL.smallTextHeight);
					OpenGL.drawSmallText(gl, text[column][line++], lineCount == 2 ? xCentered : xRightJustified, y, 0);
				}
				
				if(mediansVisibility.get()) {
					xCentered = (int) (x + (columnWidth[column] - textWidth[column][line]) / 2);
					xRightJustified = (int) (x + columnWidth[column] - textWidth[column][line]);
					y -= (int) (Theme.tilePadding + OpenGL.smallTextHeight);
					OpenGL.drawSmallText(gl, text[column][line++], lineCount == 2 ? xCentered : xRightJustified, y, 0);
				}
				
				if(standardDeviationVisibility.get()) {
					xCentered = (int) (x + (columnWidth[column] - textWidth[column][line]) / 2);
					xRightJustified = (int) (x + columnWidth[column] - textWidth[column][line]);
					y -= (int) (Theme.tilePadding + OpenGL.smallTextHeight);
					OpenGL.drawSmallText(gl, text[column][line++], lineCount == 2 ? xCentered : xRightJustified, y, 0);
				}
				
				if(percentileVisibility.get()) {
					xCentered = (int) (x + (columnWidth[column] - textWidth[column][line]) / 2);
					xRightJustified = (int) (x + columnWidth[column] - textWidth[column][line]);
					y -= (int) (Theme.tilePadding + OpenGL.smallTextHeight);
					OpenGL.drawSmallText(gl, text[column][line++], lineCount == 2 ? xCentered : xRightJustified, y, 0);
				}
				
				x += columnWidth[column];
				
			}
		
		// draw the duration if enabled and enough space
		if(sampleCountVisibility.get()) {
			y -= 2 * (Theme.tilePadding + OpenGL.smallTextHeight);
			float durationLabelWidth = OpenGL.smallTextWidth(gl, durationLabel);
			if(y > 0 && durationLabelWidth < width - 2*Theme.tilePadding) {
				x = (int) ((width / 2) - (durationLabelWidth / 2));
				if(x < Theme.tilePadding)
					x = (int) Theme.tilePadding;
				OpenGL.drawSmallText(gl, durationLabel, x, y, 0);
			}
		}
		
		return handler;
		
	}

}
