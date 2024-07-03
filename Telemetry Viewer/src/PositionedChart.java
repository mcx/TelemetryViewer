import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JPanel;

import com.jogamp.opengl.GL2ES3;
import com.jogamp.opengl.GL3;

public abstract class PositionedChart {
	
	// grid coordinates, not pixels
	int topLeftX     = -1;
	int topLeftY     = -1;
	int bottomRightX = -1;
	int bottomRightY = -1;
	
	int duration;
	boolean sampleCountMode = true;

	DatasetsInterface datasets = new DatasetsInterface();
	public WidgetTrigger trigger = null;
	List<Widget> widgets = new ArrayList<Widget>();
	
	public PositionedChart setPosition(int x1, int y1, int x2, int y2) {
		
		topLeftX     = x1 < x2 ? x1 : x2;
		topLeftY     = y1 < y2 ? y1 : y2;
		bottomRightX = x2 > x1 ? x2 : x1;
		bottomRightY = y2 > y1 ? y2 : y1;
		OpenGLChartsView.instance.updateTileOccupancy(null);
		return this;
		
	}
	
	public boolean regionOccupied(int startX, int startY, int endX, int endY) {
		
		if(endX < startX) {
			int temp = startX;
			startX = endX;
			endX = temp;
		}
		if(endY < startY) {
			int temp = startY;
			startY = endY;
			endY = temp;
		}

		for(int x = startX; x <= endX; x++)
			for(int y = startY; y <= endY; y++)
				if(x >= topLeftX && x <= bottomRightX && y >= topLeftY && y <= bottomRightY)
					return true;
		
		return false;
		
	}
	
	long cpuStartNanoseconds;
	long cpuStopNanoseconds;
	double previousCpuMilliseconds;
	double previousGpuMilliseconds;
	double cpuMillisecondsAccumulator;
	double gpuMillisecondsAccumulator;
	int count;
	final int SAMPLE_COUNT = 60;
	double averageCpuMilliseconds;
	double averageGpuMilliseconds;
	int[] gpuQueryHandles;
	long[] gpuTimes = new long[2];
	String line1;
	String line2;
	
	public final EventHandler draw(GL2ES3 gl, float[] chartMatrix, int width, int height, long nowTimestamp, int lastSampleNumber, double zoomLevel, int mouseX, int mouseY) {
		
		boolean openGLES = OpenGLChartsView.instance.openGLES;
		if(!openGLES && gpuQueryHandles == null) {
			gpuQueryHandles = new int[2];
			gl.glGenQueries(2, gpuQueryHandles, 0);
			gl.glQueryCounter(gpuQueryHandles[0], GL3.GL_TIMESTAMP); // insert both queries to prevent a warning on the first time they are read
			gl.glQueryCounter(gpuQueryHandles[1], GL3.GL_TIMESTAMP);
		}
		
		// if benchmarking, calculate CPU/GPU time for the *previous frame*
		// GPU benchmarking is not possible with OpenGL ES
		if(SettingsView.instance.benchmarkingCheckbox.get()) {
			previousCpuMilliseconds = (cpuStopNanoseconds - cpuStartNanoseconds) / 1000000.0;
			if(!openGLES) {
				gl.glGetQueryObjecti64v(gpuQueryHandles[0], GL3.GL_QUERY_RESULT, gpuTimes, 0);
				gl.glGetQueryObjecti64v(gpuQueryHandles[1], GL3.GL_QUERY_RESULT, gpuTimes, 1);
			}
			previousGpuMilliseconds = (gpuTimes[1] - gpuTimes[0]) / 1000000.0;
			if(count < SAMPLE_COUNT) {
				cpuMillisecondsAccumulator += previousCpuMilliseconds;
				gpuMillisecondsAccumulator += previousGpuMilliseconds;
				count++;
			} else {
				averageCpuMilliseconds = cpuMillisecondsAccumulator / 60.0;
				averageGpuMilliseconds = gpuMillisecondsAccumulator / 60.0;
				cpuMillisecondsAccumulator = 0;
				gpuMillisecondsAccumulator = 0;
				count = 0;
			}
			
			// start timers for *this frame*
			cpuStartNanoseconds = System.nanoTime();
			if(!openGLES)
				gl.glQueryCounter(gpuQueryHandles[0], GL3.GL_TIMESTAMP);
		}
		
		// draw the chart
		EventHandler handler = drawChart(gl, chartMatrix, width, height, nowTimestamp, lastSampleNumber, zoomLevel, mouseX, mouseY);
		
		// if benchmarking, draw the CPU/GPU benchmarks over this chart
		// GPU benchmarking is not possible with OpenGL ES
		if(SettingsView.instance.benchmarkingCheckbox.get()) {
			// stop timers for *this frame*
			cpuStopNanoseconds = System.nanoTime();
			if(!openGLES)
				gl.glQueryCounter(gpuQueryHandles[1], GL3.GL_TIMESTAMP);
			
			// show times of *previous frame*
			line1 =             String.format("CPU = %.3fms (Average = %.3fms)", previousCpuMilliseconds, averageCpuMilliseconds);
			line2 = !openGLES ? String.format("GPU = %.3fms (Average = %.3fms)", previousGpuMilliseconds, averageGpuMilliseconds) :
			                                  "GPU = unknown";
			float textHeight = 2 * OpenGL.smallTextHeight + Theme.tickTextPadding;
			float textWidth = Float.max(OpenGL.smallTextWidth(gl, line1), OpenGL.smallTextWidth(gl, line2));
			OpenGL.drawBox(gl, Theme.neutralColor, Theme.tileShadowOffset, 0, textWidth + Theme.tickTextPadding*2, textHeight + Theme.tickTextPadding*2);
			OpenGL.drawSmallText(gl, line1, (int) (Theme.tickTextPadding + Theme.tileShadowOffset), (int) (2 * Theme.tickTextPadding + OpenGL.smallTextHeight), 0);
			OpenGL.drawSmallText(gl, line2, (int) (Theme.tickTextPadding + Theme.tileShadowOffset), (int) Theme.tickTextPadding, 0);
		}
		
		return handler;
		
	}
	
	/**
	 * Draws the chart on screen.
	 * 
	 * @param gl                  The OpenGL context.
	 * @param chartMatrix         The 4x4 matrix to use.
	 * @param width               Width of the chart, in pixels.
	 * @param height              Height of the chart, in pixels.
	 * @param endTimestamp        Timestamp corresponding with the right edge of a time-domain plot. NOTE: this might be in the future!
	 * @param endSampleNumber     Sample number corresponding with the right edge of a time-domain plot. NOTE: this sample might not exist yet!
	 * @param zoomLevel           Requested zoom level.
	 * @param mouseX              Mouse's x position, in pixels, relative to the chart.
	 * @param mouseY              Mouse's y position, in pixels, relative to the chart.
	 * @return                    An EventHandler if the mouse is over something that can be clicked or dragged.
	 */
	public abstract EventHandler drawChart(GL2ES3 gl, float[] chartMatrix, int width, int height, long endTimestamp, int endSampleNumber, double zoomLevel, int mouseX, int mouseY);
	
	public static final class Tooltip {
		private record Row(float[] glColor, String text, Float pixelY) {}
		private List<Row> rows = new ArrayList<Row>();
		
		public void addRow(float[] glColor, String text, Float pixelY) {
			if(text == null || text.isEmpty())
				text = " ";
			rows.add(new Row(glColor, text, pixelY));
		}
		
		public void addRow(float[] glColor, String text) {
			if(text == null || text.isEmpty())
				text = " ";
			rows.add(new Row(glColor, text, null));
		}
		
		public void addRow(String text) {
			if(text == null || text.isEmpty())
				text = " ";
			rows.add(new Row(null, text, null));
		}
		
		public void addRow(String text, Float pixelY) {
			if(text == null || text.isEmpty())
				text = " ";
			rows.add(new Row(null, text, pixelY));
		}
		
		/**
		 * Draws this tooltip on screen. An anchor point specifies where the tooltip should point to.
		 * 
		 * @param gl              The OpenGL context.
		 * @param anchorX         X location to point at.
		 * @param mouseX          X location of the mouse pointer.
		 * @param mouseY          Y location of the mouse pointer.
		 * @param topLeftX        Allowed bounding box's top-left x coordinate.
		 * @param topLeftY        Allowed bounding box's top-left y coordinate.
		 * @param bottomRightX    Allowed bounding box's bottom-right x coordinate.
		 * @param bottomRightY    Allowed bounding box's bottom-right y coordinate.
		 */
		public void draw(GL2ES3 gl, float anchorX, int mouseX, int mouseY, float topLeftX, float topLeftY, float bottomRightX, float bottomRightY) {
			
			// sanity checks
			if(anchorX < 0)
				return;
			if(rows.isEmpty())
				return;
			
			// if only one row has a pixelY defined, anchor the tooltip to that y location
			// otherwise anchor the tooltip to the mouse's y location, and draw a vertical line across the entire plot
			long pixelYdefinedCount = rows.stream().filter(row -> row.pixelY != null).count();
			float anchorY = 0;
			if(pixelYdefinedCount == 1) {
				anchorY = rows.stream().filter(row -> row.pixelY != null).findFirst().get().pixelY;
				anchorY = Math.max(anchorY, bottomRightY);
			} else {
				anchorY = mouseY;
				OpenGL.buffer.rewind();
				OpenGL.buffer.put(anchorX); OpenGL.buffer.put(topLeftY);
				OpenGL.buffer.put(anchorX); OpenGL.buffer.put(bottomRightY);
				OpenGL.buffer.rewind();
				OpenGL.drawLinesXy(gl, GL3.GL_LINES, Theme.tooltipVerticalBarColor, OpenGL.buffer, 2);
			}
			
			// calculate the bounding box of the tooltip
			float padding = 6f * ChartsController.getDisplayScalingFactor();
			
			float textHeight = OpenGL.smallTextHeight;
			float maxWidth = (float) rows.stream().mapToDouble(row -> (row.glColor == null) ?
			                                                          OpenGL.smallTextWidth(gl, row.text) : 
			                                                          OpenGL.smallTextWidth(gl, row.text) + textHeight + Theme.tooltipTextPadding).max().orElse(0);
			float boxWidth = maxWidth + (2 * padding);
			float boxHeight = rows.size() * (textHeight + padding) + padding;
			
			// determine which orientation to draw the tooltip in, or return if there is not enough space
			enum Orientation {NORTH, SOUTH, WEST, EAST, NORTH_WEST, NORTH_EAST, SOUTH_WEST, SOUTH_EAST};
			Orientation orientation = Orientation.NORTH;
			if(anchorY + padding + boxHeight <= topLeftY) {
				// there is space above the anchor, so use NORTH or NORTH_WEST or NORTH_EAST if there is enough horizontal space
				if(anchorX - (boxWidth / 2f) >= topLeftX && anchorX + (boxWidth / 2f) <= bottomRightX)
					orientation = Orientation.NORTH;
				else if(anchorX - boxWidth >= topLeftX && anchorX <= bottomRightX)
					orientation = Orientation.NORTH_WEST;
				else if(anchorX >= topLeftX && anchorX + boxWidth <= bottomRightX)
					orientation = Orientation.NORTH_EAST;
				else
					return;
			} else if(anchorY + (boxHeight / 2f) <= topLeftY && anchorY - (boxHeight / 2f) >= bottomRightY) {
				// there is some space above and below the anchor, so use WEST or EAST if there is enough horizontal space
				if(anchorX - padding - boxWidth >= topLeftX)
					orientation = Orientation.WEST;
				else if(anchorX + padding + boxWidth <= bottomRightX)
					orientation = Orientation.EAST;
				else
					return;
			} else if(anchorY - padding - boxHeight >= bottomRightY) {
				// there is space below the anchor, so use SOUTH or SOUTH_WEST or SOUTH_EAST if there is enough horizontal space
				if(anchorX - (boxWidth / 2f) >= topLeftX && anchorX + (boxWidth / 2f) <= bottomRightX)
					orientation = Orientation.SOUTH;
				else if(anchorX - boxWidth >= topLeftX && anchorX <= bottomRightX)
					orientation = Orientation.SOUTH_WEST;
				else if(anchorX >= topLeftX && anchorX + boxWidth <= bottomRightX)
					orientation = Orientation.SOUTH_EAST;
				else
					return;
			} else {
				// there is not enough space anywhere
				return;
			}
			
			// draw the tooltip
			if(orientation == Orientation.NORTH) {
				
				OpenGL.drawTriangle2D(gl, Theme.tooltipBackgroundColor, anchorX,                  anchorY,
				                                                        anchorX + (padding / 2f), anchorY + padding,
				                                                        anchorX - (padding / 2f), anchorY + padding);
				OpenGL.drawQuad2D(gl, Theme.tooltipBackgroundColor, anchorX - (boxWidth / 2f), anchorY + padding,
				                                                    anchorX + (boxWidth / 2f), anchorY + padding + boxHeight);
				
				OpenGL.buffer.rewind();
				OpenGL.buffer.put(anchorX - (boxWidth / 2f)); OpenGL.buffer.put(anchorY + padding + boxHeight);
				OpenGL.buffer.put(anchorX - (boxWidth / 2f)); OpenGL.buffer.put(anchorY + padding);
				OpenGL.buffer.put(anchorX - (padding / 2f));  OpenGL.buffer.put(anchorY + padding);
				OpenGL.buffer.put(anchorX);                   OpenGL.buffer.put(anchorY);
				OpenGL.buffer.put(anchorX + (padding / 2f));  OpenGL.buffer.put(anchorY + padding);
				OpenGL.buffer.put(anchorX + (boxWidth / 2f)); OpenGL.buffer.put(anchorY + padding);
				OpenGL.buffer.put(anchorX + (boxWidth / 2f)); OpenGL.buffer.put(anchorY + padding + boxHeight);
				OpenGL.buffer.rewind();
				OpenGL.drawLinesXy(gl, GL3.GL_LINE_LOOP, Theme.tooltipBorderColor, OpenGL.buffer, 7);
				
				// draw the text and color boxes
				for(int i = 0; i < rows.size(); i++) {
					Row row = rows.get(i);
					float textX = (row.glColor == null) ? anchorX - (boxWidth / 2f) + (boxWidth - OpenGL.smallTextWidth(gl, row.text)) / 2 :
					                                      anchorX - (boxWidth / 2f) + padding + textHeight + Theme.tooltipTextPadding;
					float textY = anchorY + padding + boxHeight - ((i + 1) * (padding + textHeight));
					OpenGL.drawSmallText(gl, row.text, (int) textX, (int) textY, 0);
					if(row.glColor != null)
						OpenGL.drawQuad2D(gl, row.glColor, textX - Theme.tooltipTextPadding - textHeight, textY,
						                                   textX - Theme.tooltipTextPadding,              textY + textHeight);
				}
				
			} else if(orientation == Orientation.SOUTH) {
				
				OpenGL.drawTriangle2D(gl, Theme.tooltipBackgroundColor, anchorX,                  anchorY, 
				                                                        anchorX - (padding / 2f), anchorY - padding,
				                                                        anchorX + (padding / 2f), anchorY - padding);
				OpenGL.drawQuad2D(gl, Theme.tooltipBackgroundColor, anchorX - (boxWidth / 2f), anchorY - padding - boxHeight,
				                                                    anchorX + (boxWidth / 2f), anchorY - padding);
				
				OpenGL.buffer.rewind();
				OpenGL.buffer.put(anchorX - (boxWidth / 2f)); OpenGL.buffer.put(anchorY - padding);
				OpenGL.buffer.put(anchorX - (boxWidth / 2f)); OpenGL.buffer.put(anchorY - padding - boxHeight);
				OpenGL.buffer.put(anchorX + (boxWidth / 2f)); OpenGL.buffer.put(anchorY - padding - boxHeight);
				OpenGL.buffer.put(anchorX + (boxWidth / 2f)); OpenGL.buffer.put(anchorY - padding);
				OpenGL.buffer.put(anchorX + (padding / 2f));  OpenGL.buffer.put(anchorY - padding);
				OpenGL.buffer.put(anchorX);                   OpenGL.buffer.put(anchorY);
				OpenGL.buffer.put(anchorX - (padding / 2f));  OpenGL.buffer.put(anchorY - padding);
				OpenGL.buffer.rewind();
				OpenGL.drawLinesXy(gl, GL3.GL_LINE_LOOP, Theme.tooltipBorderColor, OpenGL.buffer, 7);
				
				// draw the text and color boxes
				for(int i = 0; i < rows.size(); i++) {
					Row row = rows.get(i);
					float textX = (row.glColor == null) ? anchorX - (boxWidth / 2f) + (boxWidth - OpenGL.smallTextWidth(gl, row.text)) / 2 :
					                                      anchorX - (boxWidth / 2f) + padding + textHeight + Theme.tooltipTextPadding;
					float textY = anchorY - padding - ((i + 1) * (padding + textHeight));
					OpenGL.drawSmallText(gl, row.text, (int) textX, (int) textY, 0);
					if(row.glColor != null)
						OpenGL.drawQuad2D(gl, row.glColor, textX - Theme.tooltipTextPadding - textHeight, textY,
						                                   textX - Theme.tooltipTextPadding,              textY + textHeight);
				}
				
			} else if(orientation == Orientation.WEST) {
				
				OpenGL.drawTriangle2D(gl, Theme.tooltipBackgroundColor, anchorX, anchorY,
				                                                        anchorX - padding, anchorY + (padding / 2f),
				                                                        anchorX - padding, anchorY - (padding / 2f));
				OpenGL.drawQuad2D(gl, Theme.tooltipBackgroundColor, anchorX - padding - boxWidth, anchorY - (boxHeight / 2f),
				                                                    anchorX - padding,            anchorY + (boxHeight / 2f));
				
				OpenGL.buffer.rewind();
				OpenGL.buffer.put(anchorX - padding - boxWidth); OpenGL.buffer.put(anchorY + (boxHeight / 2f));
				OpenGL.buffer.put(anchorX - padding - boxWidth); OpenGL.buffer.put(anchorY - (boxHeight / 2f));
				OpenGL.buffer.put(anchorX - padding);            OpenGL.buffer.put(anchorY - (boxHeight / 2f));
				OpenGL.buffer.put(anchorX - padding);            OpenGL.buffer.put(anchorY - (padding / 2f));
				OpenGL.buffer.put(anchorX);                      OpenGL.buffer.put(anchorY);
				OpenGL.buffer.put(anchorX - padding);            OpenGL.buffer.put(anchorY + (padding / 2f));
				OpenGL.buffer.put(anchorX - padding);            OpenGL.buffer.put(anchorY + (boxHeight / 2f));
				OpenGL.buffer.rewind();
				OpenGL.drawLinesXy(gl, GL3.GL_LINE_LOOP, Theme.tooltipBorderColor, OpenGL.buffer, 7);
				
				// draw the text and color boxes
				for(int i = 0; i < rows.size(); i++) {
					Row row = rows.get(i);
					float textX = (row.glColor == null) ? anchorX - padding - boxWidth + (boxWidth - OpenGL.smallTextWidth(gl, row.text)) / 2 :
					                                      anchorX - boxWidth + textHeight + Theme.tooltipTextPadding;
					float textY = anchorY + (boxHeight / 2f) - ((i + 1) * (padding + textHeight));
					OpenGL.drawSmallText(gl, row.text, (int) textX, (int) textY, 0);
					if(row.glColor != null)
						OpenGL.drawQuad2D(gl, row.glColor, textX - Theme.tooltipTextPadding - textHeight, textY,
						                                   textX - Theme.tooltipTextPadding,              textY + textHeight);
				}
				
			} else if(orientation == Orientation.EAST) {
				
				OpenGL.drawTriangle2D(gl, Theme.tooltipBackgroundColor, anchorX,           anchorY,
				                                                        anchorX + padding, anchorY - (padding / 2f),
				                                                        anchorX + padding, anchorY + (padding / 2f));
				OpenGL.drawQuad2D(gl, Theme.tooltipBackgroundColor, anchorX + padding,            anchorY - (boxHeight / 2f),
				                                                    anchorX + padding + boxWidth, anchorY + (boxHeight / 2f));
				
				OpenGL.buffer.rewind();
				OpenGL.buffer.put(anchorX + padding);            OpenGL.buffer.put(anchorY + (boxHeight / 2f));
				OpenGL.buffer.put(anchorX + padding);            OpenGL.buffer.put(anchorY + (padding / 2f));
				OpenGL.buffer.put(anchorX);                      OpenGL.buffer.put(anchorY);
				OpenGL.buffer.put(anchorX + padding);            OpenGL.buffer.put(anchorY - (padding / 2f));
				OpenGL.buffer.put(anchorX + padding);            OpenGL.buffer.put(anchorY - (boxHeight / 2f));
				OpenGL.buffer.put(anchorX + padding + boxWidth); OpenGL.buffer.put(anchorY - (boxHeight / 2f));
				OpenGL.buffer.put(anchorX + padding + boxWidth); OpenGL.buffer.put(anchorY + (boxHeight / 2f));
				OpenGL.buffer.rewind();
				OpenGL.drawLinesXy(gl, GL3.GL_LINE_LOOP, Theme.tooltipBorderColor, OpenGL.buffer, 7);
				
				// draw the text and color boxes
				for(int i = 0; i < rows.size(); i++) {
					Row row = rows.get(i);
					float textX = (row.glColor == null) ? anchorX + padding + (boxWidth - OpenGL.smallTextWidth(gl, row.text)) / 2 :
					                                      anchorX + (2f * padding) + textHeight + Theme.tooltipTextPadding;
					float textY = anchorY + (boxHeight / 2f) - ((i + 1) * (padding + textHeight));
					OpenGL.drawSmallText(gl, row.text, (int) textX, (int) textY, 0);
					if(row.glColor != null)
						OpenGL.drawQuad2D(gl, row.glColor, textX - Theme.tooltipTextPadding - textHeight, textY,
						                                   textX - Theme.tooltipTextPadding,              textY + textHeight);
				}
				
			} else if(orientation == Orientation.NORTH_WEST) {
				
				OpenGL.drawTriangle2D(gl, Theme.tooltipBackgroundColor, anchorX,                     anchorY,
				                                                        anchorX,                     anchorY + padding,
				                                                        anchorX - (0.85f * padding), anchorY + padding);
				OpenGL.drawQuad2D(gl, Theme.tooltipBackgroundColor, anchorX - boxWidth, anchorY + padding,
				                                                    anchorX,            anchorY + padding + boxHeight);
				
				OpenGL.buffer.rewind();
				OpenGL.buffer.put(anchorX - boxWidth);          OpenGL.buffer.put(anchorY + padding + boxHeight);
				OpenGL.buffer.put(anchorX - boxWidth);          OpenGL.buffer.put(anchorY + padding);
				OpenGL.buffer.put(anchorX - (0.85f * padding)); OpenGL.buffer.put(anchorY + padding);
				OpenGL.buffer.put(anchorX);                     OpenGL.buffer.put(anchorY);
				OpenGL.buffer.put(anchorX);                     OpenGL.buffer.put(anchorY + padding + boxHeight);
				OpenGL.buffer.rewind();
				OpenGL.drawLinesXy(gl, GL3.GL_LINE_LOOP, Theme.tooltipBorderColor, OpenGL.buffer, 5);
				
				// draw the text and color boxes
				for(int i = 0; i < rows.size(); i++) {
					Row row = rows.get(i);
					float textX = (row.glColor == null) ? anchorX - boxWidth + (boxWidth - OpenGL.smallTextWidth(gl, row.text)) / 2 :
					                                        anchorX - boxWidth + padding + textHeight + Theme.tooltipTextPadding;
					float textY = anchorY + padding + boxHeight - ((i + 1) * (padding + textHeight));
					OpenGL.drawSmallText(gl, row.text, (int) textX, (int) textY, 0);
					if(row.glColor != null)
						OpenGL.drawQuad2D(gl, row.glColor, textX - Theme.tooltipTextPadding - textHeight, textY,
						                                   textX - Theme.tooltipTextPadding,              textY + textHeight);
				}
				
			} else if(orientation == Orientation.NORTH_EAST) {
				
				OpenGL.drawTriangle2D(gl, Theme.tooltipBackgroundColor, anchorX,                     anchorY + padding,
				                                                        anchorX,                     anchorY,
				                                                        anchorX + (0.85f * padding), anchorY + padding);
				OpenGL.drawQuad2D(gl, Theme.tooltipBackgroundColor, anchorX,            anchorY + padding,
				                                                    anchorX + boxWidth, anchorY + padding + boxHeight);
				
				OpenGL.buffer.rewind();
				OpenGL.buffer.put(anchorX);                     OpenGL.buffer.put(anchorY + padding + boxHeight);
				OpenGL.buffer.put(anchorX);                     OpenGL.buffer.put(anchorY);
				OpenGL.buffer.put(anchorX + (0.85f * padding)); OpenGL.buffer.put(anchorY + padding);
				OpenGL.buffer.put(anchorX + boxWidth);          OpenGL.buffer.put(anchorY + padding);
				OpenGL.buffer.put(anchorX + boxWidth);          OpenGL.buffer.put(anchorY + padding + boxHeight);
				OpenGL.buffer.rewind();
				OpenGL.drawLinesXy(gl, GL3.GL_LINE_LOOP, Theme.tooltipBorderColor, OpenGL.buffer, 5);
				
				// draw the text and color boxes
				for(int i = 0; i < rows.size(); i++) {
					Row row = rows.get(i);
					float textX = (row.glColor == null) ? anchorX + (boxWidth - OpenGL.smallTextWidth(gl, row.text)) / 2 :
					                                      anchorX + padding + textHeight + Theme.tooltipTextPadding;
					float textY = anchorY + padding + boxHeight - ((i + 1) * (padding + textHeight));
					OpenGL.drawSmallText(gl, row.text, (int) textX, (int) textY, 0);
					if(row.glColor != null)
						OpenGL.drawQuad2D(gl, row.glColor, textX - Theme.tooltipTextPadding - textHeight, textY,
						                                   textX - Theme.tooltipTextPadding,              textY + textHeight);
				}
				
			} else if(orientation == Orientation.SOUTH_WEST) {
				
				OpenGL.drawTriangle2D(gl, Theme.tooltipBackgroundColor, anchorX,                     anchorY,
				                                                        anchorX - (0.85f * padding), anchorY - padding,
				                                                        anchorX,                     anchorY - padding);
				OpenGL.drawQuad2D(gl, Theme.tooltipBackgroundColor, anchorX - boxWidth, anchorY - padding - boxHeight,
				                                                    anchorX,            anchorY - padding);
				
				OpenGL.buffer.rewind();
				OpenGL.buffer.put(anchorX - boxWidth);          OpenGL.buffer.put(anchorY - padding);
				OpenGL.buffer.put(anchorX - boxWidth);          OpenGL.buffer.put(anchorY - padding - boxHeight);
				OpenGL.buffer.put(anchorX);                     OpenGL.buffer.put(anchorY - padding - boxHeight);
				OpenGL.buffer.put(anchorX);                     OpenGL.buffer.put(anchorY);
				OpenGL.buffer.put(anchorX - (0.85f * padding)); OpenGL.buffer.put(anchorY - padding);
				OpenGL.buffer.rewind();
				OpenGL.drawLinesXy(gl, GL3.GL_LINE_LOOP, Theme.tooltipBorderColor, OpenGL.buffer, 5);
				
				// draw the text and color boxes
				for(int i = 0; i < rows.size(); i++) {
					Row row = rows.get(i);
					float textX = (row.glColor == null) ? anchorX - boxWidth + (boxWidth - OpenGL.smallTextWidth(gl, row.text)) / 2 :
					                                        anchorX - boxWidth + padding + textHeight + Theme.tooltipTextPadding;
					float textY = anchorY - padding - ((i + 1) * (padding + textHeight));
					OpenGL.drawSmallText(gl, row.text, (int) textX, (int) textY, 0);
					if(row.glColor != null)
						OpenGL.drawQuad2D(gl, row.glColor, textX - Theme.tooltipTextPadding - textHeight, textY,
						                                   textX - Theme.tooltipTextPadding,              textY + textHeight);
				}
				
			} else if(orientation == Orientation.SOUTH_EAST) {
				
				OpenGL.drawTriangle2D(gl, Theme.tooltipBackgroundColor, anchorX,                     anchorY,
				                                                        anchorX,                     anchorY - padding,
				                                                        anchorX + (0.85f * padding), anchorY - padding);
				OpenGL.drawQuad2D(gl, Theme.tooltipBackgroundColor, anchorX,            anchorY - padding - boxHeight,
				                                                    anchorX + boxWidth, anchorY - padding);
				
				OpenGL.buffer.rewind();
				OpenGL.buffer.put(anchorX);                     OpenGL.buffer.put(anchorY);
				OpenGL.buffer.put(anchorX);                     OpenGL.buffer.put(anchorY - padding - boxHeight);
				OpenGL.buffer.put(anchorX + boxWidth);          OpenGL.buffer.put(anchorY - padding - boxHeight);
				OpenGL.buffer.put(anchorX + boxWidth);          OpenGL.buffer.put(anchorY - padding);
				OpenGL.buffer.put(anchorX + (0.85f * padding)); OpenGL.buffer.put(anchorY - padding);
				OpenGL.buffer.rewind();
				OpenGL.drawLinesXy(gl, GL3.GL_LINE_LOOP, Theme.tooltipBorderColor, OpenGL.buffer, 5);
				
				// draw the text and color boxes
				for(int i = 0; i < rows.size(); i++) {
					Row row = rows.get(i);
					float textX = (row.glColor == null) ? anchorX + (boxWidth - OpenGL.smallTextWidth(gl, row.text)) / 2 :
					                                        anchorX + padding + textHeight + Theme.tooltipTextPadding;
					float textY = anchorY - padding - ((i + 1) * (padding + textHeight));
					OpenGL.drawSmallText(gl, row.text, (int) textX, (int) textY, 0);
					if(row.glColor != null)
						OpenGL.drawQuad2D(gl, row.glColor, textX - Theme.tooltipTextPadding - textHeight, textY,
						                                   textX - Theme.tooltipTextPadding,              textY + textHeight);
				}
				
			}
			
		}
	}
	
	public abstract void getConfigurationGui(JPanel gui);
	
	final public void importFrom(ConnectionsController.QueueOfLines lines) {

		int topLeftX = lines.parseInteger("top left x = %d");
		if(topLeftX < 0 || topLeftX >= SettingsView.instance.tileColumnsTextfield.get())
			throw new AssertionError("Invalid chart position.");
		
		int topLeftY = lines.parseInteger("top left y = %d");
		if(topLeftY < 0 || topLeftY >= SettingsView.instance.tileRowsTextfield.get())
			throw new AssertionError("Invalid chart position.");

		int bottomRightX = lines.parseInteger("bottom right x = %d");
		if(bottomRightX < 0 || bottomRightX >= SettingsView.instance.tileColumnsTextfield.get())
			throw new AssertionError("Invalid chart position.");

		int bottomRightY = lines.parseInteger("bottom right y = %d");
		if(bottomRightY < 0 || bottomRightY >= SettingsView.instance.tileRowsTextfield.get())
			throw new AssertionError("Invalid chart position.");
		
		for(PositionedChart existingChart : ChartsController.getCharts())
			if(existingChart.regionOccupied(topLeftX, topLeftY, bottomRightX, bottomRightY))
				throw new AssertionError("Chart overlaps an existing chart.");
		
		setPosition(topLeftX, topLeftY, bottomRightX, bottomRightY);
		
		widgets.forEach(widget -> widget.importFrom(lines));
		
	}
	
	final public void exportTo(PrintWriter file) {

		file.println("");
		file.println("\tchart type = " + toString());
		file.println("\ttop left x = " + topLeftX);
		file.println("\ttop left y = " + topLeftY);
		file.println("\tbottom right x = " + bottomRightX);
		file.println("\tbottom right y = " + bottomRightY);
		
		widgets.forEach(widget -> widget.exportTo(file));
		
	}
	
	public abstract String toString();
	
	/**
	 * Schedules the chart to be disposed.
	 * Non-GPU resources (cache files, etc.) will be released immediately.
	 * GPU resources will be released the next time the OpenGLChartsRegion is drawn. (the next vsync, if it's on screen.)
	 */
	final public void dispose() {
		
		disposeNonGpu();
		OpenGLChartsView.instance.chartsToDispose.add(this);
		
	}
	
	/**
	 * Charts that create cache files or other non-GPU resources must dispose of them when this method is called.
	 * The chart may be drawn after this call, so the chart must be able to automatically regenerate any needed caches.
	 */
	public void disposeNonGpu() {
		
	}
	
	/**
	 * Charts that create any OpenGL FBOs/textures/etc. must dispose of them when this method is called.
	 * The chart may be drawn after this call, so the chart must be able to automatically regenerate any needed FBOs/textures/etc.
	 * 
	 * @param gl    The OpenGL context.
	 */
	public void disposeGpu(GL2ES3 gl) {
		
		if(gpuQueryHandles != null) {
			gl.glDeleteQueries(2, gpuQueryHandles, 0);
			gpuQueryHandles = null;
		}
		
	}
	
}