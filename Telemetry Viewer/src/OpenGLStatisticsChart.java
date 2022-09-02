import javax.swing.JPanel;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;

import com.jogamp.opengl.GL2ES3;

public class OpenGLStatisticsChart extends PositionedChart {
	
	// user settings
	private WidgetDatasetCheckboxes datasetsWidget;
	
	private WidgetTextfieldInt sampleCountTextfield;
	
	private boolean sampleCountVisible = true;
	private WidgetCheckbox sampleCountCheckbox;
	
	private boolean currentValuesVisible = true;
	private WidgetCheckbox currentValuesCheckbox;
	
	private boolean minimumsVisible = true;
	private WidgetCheckbox minimumsCheckbox;
	
	private boolean maximumsVisible = true;
	private WidgetCheckbox maximumsCheckbox;
	
	private boolean meansVisible = true;
	private WidgetCheckbox meansCheckbox;
	
	private boolean mediansVisible = true;
	private WidgetCheckbox mediansCheckbox;
	
	private boolean standardDeviationsVisible = true;
	private WidgetCheckbox standardDeviationCheckbox;
	
	private boolean percentileVisible = true;
	private WidgetCheckbox percentileCheckbox;
	
	// duration
	private int durationSampleCount;
	private long durationMilliseconds;
	private String showAs;
	
	@Override public String toString() {
		
		return "Statistics";
		
	}
	
	public OpenGLStatisticsChart(int x1, int y1, int x2, int y2) {
		
		super(x1, y1, x2, y2);
		
		datasetsWidget = new WidgetDatasetCheckboxes(newDatasets -> datasets.setNormals(newDatasets),
		                                             null,
		                                             null,
		                                             null,
		                                             false);
		
		sampleCountTextfield = new WidgetTextfieldInt("",
		                                              "duration",
		                                              "Samples",
		                                              1,
		                                              Integer.MAX_VALUE / 16,
		                                              ConnectionsController.getDefaultChartDuration(),
		                                              newDuration -> durationSampleCount = newDuration);
		
		sampleCountCheckbox = new WidgetCheckbox("Show Sample Count",
		                                         sampleCountVisible,
		                                         isVisible -> sampleCountVisible = isVisible);
		
		currentValuesCheckbox = new WidgetCheckbox("Current Value",
		                                           currentValuesVisible,
		                                           isVisible -> currentValuesVisible = isVisible);
		
		minimumsCheckbox = new WidgetCheckbox("Minimum",
		                                      minimumsVisible,
		                                      isVisible -> minimumsVisible = isVisible);
		
		maximumsCheckbox = new WidgetCheckbox("Maximum",
		                                      maximumsVisible,
		                                      isVisible -> maximumsVisible = isVisible);
		
		meansCheckbox = new WidgetCheckbox("Mean",
		                                   meansVisible,
		                                   isVisible -> meansVisible = isVisible);
		
		mediansCheckbox = new WidgetCheckbox("Median",
		                                     mediansVisible,
		                                     isVisible -> mediansVisible = isVisible);
		
		standardDeviationCheckbox = new WidgetCheckbox("Standard Deviation",
		                                               standardDeviationsVisible,
		                                               isVisible -> standardDeviationsVisible = isVisible);
		
		percentileCheckbox = new WidgetCheckbox("90th Percentile",
		                                        percentileVisible,
		                                        isVisible -> percentileVisible = isVisible);
		
		widgets.add(datasetsWidget);
		widgets.add(sampleCountTextfield);
		widgets.add(sampleCountCheckbox);
		widgets.add(currentValuesCheckbox);
		widgets.add(minimumsCheckbox);
		widgets.add(maximumsCheckbox);
		widgets.add(meansCheckbox);
		widgets.add(mediansCheckbox);
		widgets.add(standardDeviationCheckbox);
		widgets.add(percentileCheckbox);
		
	}
	
	@Override public void getConfigurationGui(JPanel gui) {
		
		JPanel dataPanel = Theme.newWidgetsPanel("Data");
		datasetsWidget.appendToGui(dataPanel);
		dataPanel.add(sampleCountTextfield, "span 4, split 2, sizegroup 0");
		dataPanel.add(sampleCountCheckbox, "sizegroup 0");
		
		JPanel statsPanel = Theme.newWidgetsPanel("Statistics");
		statsPanel.add(currentValuesCheckbox);
		statsPanel.add(minimumsCheckbox);
		statsPanel.add(maximumsCheckbox);
		statsPanel.add(meansCheckbox);
		statsPanel.add(mediansCheckbox);
		statsPanel.add(standardDeviationCheckbox);
		statsPanel.add(percentileCheckbox);
		
		gui.add(dataPanel);
		gui.add(statsPanel);
		
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
			firstSampleNumber = endSampleNumber - (int) Math.round(durationSampleCount * zoomLevel) + 1;
		} else {
			lastSampleNumber = datasets.getClosestSampleNumberAtOrBefore(endTimestamp, trueLastSampleNumber);
			firstSampleNumber = datasets.getClosestSampleNumberAfter(endTimestamp - Math.round(durationMilliseconds * zoomLevel));
		}
		
		// done if no telemetry
		if(lastSampleNumber < 0) {
			String text = "[waiting for telemetry]";
			int x = (width / 2) - (int) (OpenGL.largeTextWidth(gl, text) / 2);
			int y = (height / 2) - (int) (OpenGL.largeTextHeight / 2);
			OpenGL.drawLargeText(gl, text, x, y, 0);
			return handler;
		}

		if(firstSampleNumber < 0)
			firstSampleNumber = 0;
		if(firstSampleNumber > lastSampleNumber)
			firstSampleNumber = lastSampleNumber;
		int sampleCount = lastSampleNumber - firstSampleNumber + 1;

		String durationLabel = sampleCountMode             ? "(" + sampleCount + " Samples)" :
		                       showAs.equals("Timestamps") ? "(" + SettingsController.formatTimestampToMilliseconds(datasets.getTimestamp(firstSampleNumber)).replace('\n', ' ') + " to " + SettingsController.formatTimestampToMilliseconds(datasets.getTimestamp(lastSampleNumber)).replace('\n', ' ') + ")" :
		                                                     "(" + (datasets.getTimestamp(lastSampleNumber) - datasets.getTimestamp(firstSampleNumber)) + " ms)";
		
		// determine the text to display
		int lineCount = 1; // always show the dataset labels
		if(currentValuesVisible)      lineCount++;
		if(minimumsVisible)           lineCount++;
		if(maximumsVisible)           lineCount++;
		if(meansVisible)              lineCount++;
		if(mediansVisible)            lineCount++;
		if(standardDeviationsVisible) lineCount++;
		if(percentileVisible)         lineCount++;
		String[][] text = new String[datasetsCount + 1][lineCount];
		
		// first column of text are the labels, but don't label the dataset name or current value because that's obvious
		int line = 0;
		text[0][line++] = "";
		if(currentValuesVisible)      text[0][line++] = "";
		if(minimumsVisible)           text[0][line++] = "Minimum";
		if(maximumsVisible)           text[0][line++] = "Maximum";
		if(meansVisible)              text[0][line++] = "Mean";
		if(mediansVisible)            text[0][line++] = "Median";
		if(standardDeviationsVisible) text[0][line++] = "Std Dev";
		if(percentileVisible)         text[0][line++] = "90th Pctl";
		
		// subsequent columns of text are the dataset names and numeric values
		if(sampleCount > 0)
			for(int datasetN = 0; datasetN < datasetsCount; datasetN++) {
				Dataset dataset = datasets.getNormal(datasetN);
				float[] samples = datasets.getSamplesArray(dataset, firstSampleNumber, lastSampleNumber);
				double[] doubles = new double[samples.length];
				for(int i = 0; i < samples.length; i++)
					doubles[i] = (double) samples[i];
				
				DescriptiveStatistics stats = new DescriptiveStatistics(doubles);
				
				int column = datasetN + 1;
				line = 0;
				text[column][line++] = dataset.name;
				if(currentValuesVisible)      text[column][line++] = ChartUtils.formattedNumber(samples[samples.length - 1], 5) + " " + dataset.unit;
				if(minimumsVisible)           text[column][line++] = ChartUtils.formattedNumber(stats.getMin(), 5) + " " + dataset.unit;
				if(maximumsVisible)           text[column][line++] = ChartUtils.formattedNumber(stats.getMax(), 5) + " " + dataset.unit;
				if(meansVisible)              text[column][line++] = ChartUtils.formattedNumber(stats.getMean(), 5) + " " + dataset.unit;
				if(mediansVisible)            text[column][line++] = ChartUtils.formattedNumber(stats.getPercentile(50), 5) + " " + dataset.unit;
				if(standardDeviationsVisible) text[column][line++] = ChartUtils.formattedNumber(stats.getStandardDeviation(), 5) + " " + dataset.unit;
				if(percentileVisible)         text[column][line++] = ChartUtils.formattedNumber(stats.getPercentile(90), 5) + " " + dataset.unit;
			}
		
		// determine the width of each piece of text, and track the max for each column
		float[] columnWidth = new float[datasetsCount + 1];
		float[][] textWidth = new float[datasetsCount + 1][lineCount];
		
		line = 0;
		columnWidth[0] = 0;
		textWidth[0][line++] = 0; // no label for the dataset name
		if(currentValuesVisible)
			textWidth[0][line++] = 0; // no label for the current value
		if(minimumsVisible) {
			textWidth[0][line] = OpenGL.smallTextWidth(gl, text[0][line]);
			if(columnWidth[0] < textWidth[0][line])
				columnWidth[0] = textWidth[0][line];
			line++;
		}
		if(maximumsVisible) {
			textWidth[0][line] = OpenGL.smallTextWidth(gl, text[0][line]);
			if(columnWidth[0] < textWidth[0][line])
				columnWidth[0] = textWidth[0][line];
			line++;
		}
		if(meansVisible) {
			textWidth[0][line] = OpenGL.smallTextWidth(gl, text[0][line]);
			if(columnWidth[0] < textWidth[0][line])
				columnWidth[0] = textWidth[0][line];
			line++;
		}
		if(mediansVisible) {
			textWidth[0][line] = OpenGL.smallTextWidth(gl, text[0][line]);
			if(columnWidth[0] < textWidth[0][line])
				columnWidth[0] = textWidth[0][line];
			line++;
		}
		if(standardDeviationsVisible) {
			textWidth[0][line] = OpenGL.smallTextWidth(gl, text[0][line]);
			if(columnWidth[0] < textWidth[0][line])
				columnWidth[0] = textWidth[0][line];
			line++;
		}
		if(percentileVisible) {
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
				if(currentValuesVisible) {
					textWidth[column][1] = OpenGL.largeTextWidth(gl, text[column][1]);
					if(columnWidth[column] < textWidth[column][1])
						columnWidth[column] = textWidth[column][1];
				}
				for(line = currentValuesVisible ? 2 : 1; line < lineCount; line++) {
					textWidth[column][line] = OpenGL.smallTextWidth(gl, text[column][line]);
					if(columnWidth[column] < textWidth[column][line])
						columnWidth[column] = textWidth[column][line];
				}
			}
		
		// determine the gaps to leave above and to the left of the text
		boolean showingLabels = columnWidth[0] > 0;
		int occupiedHeight = 0;
		                              occupiedHeight += Theme.tilePadding + OpenGL.smallTextHeight; // dataset name
		if(currentValuesVisible)      occupiedHeight += Theme.tilePadding + OpenGL.largeTextHeight;
		if(minimumsVisible)           occupiedHeight += Theme.tilePadding + OpenGL.smallTextHeight;
		if(maximumsVisible)           occupiedHeight += Theme.tilePadding + OpenGL.smallTextHeight;
		if(meansVisible)              occupiedHeight += Theme.tilePadding + OpenGL.smallTextHeight;
		if(mediansVisible)            occupiedHeight += Theme.tilePadding + OpenGL.smallTextHeight;
		if(standardDeviationsVisible) occupiedHeight += Theme.tilePadding + OpenGL.smallTextHeight;
		if(percentileVisible)         occupiedHeight += Theme.tilePadding + OpenGL.smallTextHeight;
		if(sampleCountVisible)        occupiedHeight += 2 * (Theme.tilePadding + OpenGL.smallTextHeight);
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
		if(currentValuesVisible) {
			y -= (int) (Theme.tilePadding + OpenGL.largeTextHeight); // no label for current value
			line++;
		}
		if(minimumsVisible) {
			y -= (int) (Theme.tilePadding + OpenGL.smallTextHeight);
			OpenGL.drawSmallText(gl, text[0][line++], x, y, 0);
		}
		if(maximumsVisible) {
			y -= (int) (Theme.tilePadding + OpenGL.smallTextHeight);
			OpenGL.drawSmallText(gl, text[0][line++], x, y, 0);
		}
		if(meansVisible) {
			y -= (int) (Theme.tilePadding + OpenGL.smallTextHeight);
			OpenGL.drawSmallText(gl, text[0][line++], x, y, 0);
		}
		if(mediansVisible) {
			y -= (int) (Theme.tilePadding + OpenGL.smallTextHeight);
			OpenGL.drawSmallText(gl, text[0][line++], x, y, 0);
		}
		if(standardDeviationsVisible) {
			y -= (int) (Theme.tilePadding + OpenGL.smallTextHeight);
			OpenGL.drawSmallText(gl, text[0][line++], x, y, 0);
		}
		if(percentileVisible) {
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
				
				if(currentValuesVisible) {
					xCentered = (int) (x + (columnWidth[column] - textWidth[column][line]) / 2);
					xRightJustified = (int) (x + columnWidth[column] - textWidth[column][line]);
					y -= (int) (Theme.tilePadding + OpenGL.largeTextHeight);
					OpenGL.drawLargeText(gl, text[column][line++], lineCount == 2 ? xCentered : xRightJustified, y, 0);
				}
				
				if(minimumsVisible) {
					xCentered = (int) (x + (columnWidth[column] - textWidth[column][line]) / 2);
					xRightJustified = (int) (x + columnWidth[column] - textWidth[column][line]);
					y -= (int) (Theme.tilePadding + OpenGL.smallTextHeight);
					OpenGL.drawSmallText(gl, text[column][line++], lineCount == 2 ? xCentered : xRightJustified, y, 0);
				}
				
				if(maximumsVisible) {
					xCentered = (int) (x + (columnWidth[column] - textWidth[column][line]) / 2);
					xRightJustified = (int) (x + columnWidth[column] - textWidth[column][line]);
					y -= (int) (Theme.tilePadding + OpenGL.smallTextHeight);
					OpenGL.drawSmallText(gl, text[column][line++], lineCount == 2 ? xCentered : xRightJustified, y, 0);
				}
				
				if(meansVisible) {
					xCentered = (int) (x + (columnWidth[column] - textWidth[column][line]) / 2);
					xRightJustified = (int) (x + columnWidth[column] - textWidth[column][line]);
					y -= (int) (Theme.tilePadding + OpenGL.smallTextHeight);
					OpenGL.drawSmallText(gl, text[column][line++], lineCount == 2 ? xCentered : xRightJustified, y, 0);
				}
				
				if(mediansVisible) {
					xCentered = (int) (x + (columnWidth[column] - textWidth[column][line]) / 2);
					xRightJustified = (int) (x + columnWidth[column] - textWidth[column][line]);
					y -= (int) (Theme.tilePadding + OpenGL.smallTextHeight);
					OpenGL.drawSmallText(gl, text[column][line++], lineCount == 2 ? xCentered : xRightJustified, y, 0);
				}
				
				if(standardDeviationsVisible) {
					xCentered = (int) (x + (columnWidth[column] - textWidth[column][line]) / 2);
					xRightJustified = (int) (x + columnWidth[column] - textWidth[column][line]);
					y -= (int) (Theme.tilePadding + OpenGL.smallTextHeight);
					OpenGL.drawSmallText(gl, text[column][line++], lineCount == 2 ? xCentered : xRightJustified, y, 0);
				}
				
				if(percentileVisible) {
					xCentered = (int) (x + (columnWidth[column] - textWidth[column][line]) / 2);
					xRightJustified = (int) (x + columnWidth[column] - textWidth[column][line]);
					y -= (int) (Theme.tilePadding + OpenGL.smallTextHeight);
					OpenGL.drawSmallText(gl, text[column][line++], lineCount == 2 ? xCentered : xRightJustified, y, 0);
				}
				
				x += columnWidth[column];
				
			}
		
		// draw the duration if enabled and enough space
		if(sampleCountVisible) {
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
