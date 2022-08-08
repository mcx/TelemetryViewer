import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import javax.swing.JPanel;

import com.jogamp.opengl.GL2ES3;
import com.jogamp.opengl.GL3;

public abstract class PositionedChart {
	
	// grid coordinates, not pixels
	int topLeftX;
	int topLeftY;
	int bottomRightX;
	int bottomRightY;
	
	int duration;
	boolean sampleCountMode;

	DatasetsInterface datasets = new DatasetsInterface();
	public WidgetTrigger trigger = null;
	List<Widget> widgets = new ArrayList<Widget>();
	
	public PositionedChart(int x1, int y1, int x2, int y2) {
		
		topLeftX     = x1 < x2 ? x1 : x2;
		topLeftY     = y1 < y2 ? y1 : y2;
		bottomRightX = x2 > x1 ? x2 : x1;
		bottomRightY = y2 > y1 ? y2 : y1;
		sampleCountMode = true;
			
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
		if(SettingsController.getBenchmarking()) {
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
		if(SettingsController.getBenchmarking()) {
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
	
	/**
	 * @param glColor    Optional color to show to the left of the text. Use null to not show a color.
	 * @param text       Text to show. Must not be null.
	 */
	protected record TooltipEntry(float[] glColor, String text) {}
	
	/**
	 * Draws a tooltip. An anchor point specifies where the tooltip should point to.
	 * 
	 * @param gl              The OpenGL context.
	 * @param entries         List of entries (text + optional color) to draw on the tooltip.
	 * @param anchorX         X location to point to.
	 * @param anchorY         Y location to point to.
	 * @param topLeftX        Allowed bounding box's top-left x coordinate.
	 * @param topLeftY        Allowed bounding box's top-left y coordinate.
	 * @param bottomRightX    Allowed bounding box's bottom-right x coordinate.
	 * @param bottomRightY    Allowed bounding box's bottom-right y coordinate.
	 */
	protected static void drawTooltip(GL2ES3 gl, List<TooltipEntry> entries, float anchorX, float anchorY, float topLeftX, float topLeftY, float bottomRightX, float bottomRightY) {
			
		final int NORTH      = 0;
		final int SOUTH      = 1;
		final int WEST       = 2;
		final int EAST       = 3;
		final int NORTH_WEST = 4;
		final int NORTH_EAST = 5;
		final int SOUTH_WEST = 6;
		final int SOUTH_EAST = 7;
		
		float padding = 6f * ChartsController.getDisplayScalingFactor();
		
		float textHeight = OpenGL.smallTextHeight;
		float maxWidth = 0;
		for(TooltipEntry entry : entries)
			maxWidth = Float.max(maxWidth, (entry.glColor == null) ? OpenGL.smallTextWidth(gl, entry.text) : 
			                                                         OpenGL.smallTextWidth(gl, entry.text) + textHeight + Theme.tooltipTextPadding);
		
		float boxWidth = maxWidth + (2 * padding);
		float boxHeight = entries.size() * (textHeight + padding) + padding;
		
		// decide which orientation to draw the tooltip in, or return if there is not enough space
		int orientation = NORTH;
		if(anchorY + padding + boxHeight <= topLeftY) {
			// there is space above the anchor, so use NORTH or NORTH_WEST or NORTH_EAST if there is enough horizontal space
			if(anchorX - (boxWidth / 2f) >= topLeftX && anchorX + (boxWidth / 2f) <= bottomRightX)
				orientation = NORTH;
			else if(anchorX - boxWidth >= topLeftX && anchorX <= bottomRightX)
				orientation = NORTH_WEST;
			else if(anchorX >= topLeftX && anchorX + boxWidth <= bottomRightX)
				orientation = NORTH_EAST;
			else
				return;
		} else if(anchorY + (boxHeight / 2f) <= topLeftY && anchorY - (boxHeight / 2f) >= bottomRightY) {
			// there is some space above and below the anchor, so use WEST or EAST if there is enough horizontal space
			if(anchorX - padding - boxWidth >= topLeftX)
				orientation = WEST;
			else if(anchorX + padding + boxWidth <= bottomRightX)
				orientation = EAST;
			else
				return;
		} else if(anchorY - padding - boxHeight >= bottomRightY) {
			// there is space below the anchor, so use SOUTH or SOUTH_WEST or SOUTH_EAST if there is enough horizontal space
			if(anchorX - (boxWidth / 2f) >= topLeftX && anchorX + (boxWidth / 2f) <= bottomRightX)
				orientation = SOUTH;
			else if(anchorX - boxWidth >= topLeftX && anchorX <= bottomRightX)
				orientation = SOUTH_WEST;
			else if(anchorX >= topLeftX && anchorX + boxWidth <= bottomRightX)
				orientation = SOUTH_EAST;
			else
				return;
		} else {
			// there is not enough space anywhere
			return;
		}
		
		// draw the tooltip
		if(orientation == NORTH) {
			
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
			for(int i = 0; i < entries.size(); i++) {
				TooltipEntry entry = entries.get(i);
				float textX = (entry.glColor == null) ? anchorX - (boxWidth / 2f) + (boxWidth - OpenGL.smallTextWidth(gl, entry.text)) / 2 :
				                                        anchorX - (boxWidth / 2f) + padding + textHeight + Theme.tooltipTextPadding;
				float textY = anchorY + padding + boxHeight - ((i + 1) * (padding + textHeight));
				OpenGL.drawSmallText(gl, entry.text, (int) textX, (int) textY, 0);
				if(entry.glColor != null)
					OpenGL.drawQuad2D(gl, entry.glColor, textX - Theme.tooltipTextPadding - textHeight, textY,
					                                     textX - Theme.tooltipTextPadding,              textY + textHeight);
			}
			
		} else if(orientation == SOUTH) {
			
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
			for(int i = 0; i < entries.size(); i++) {
				TooltipEntry entry = entries.get(i);
				float textX = (entry.glColor == null) ? anchorX - (boxWidth / 2f) + (boxWidth - OpenGL.smallTextWidth(gl, entry.text)) / 2 :
				                                        anchorX - (boxWidth / 2f) + padding + textHeight + Theme.tooltipTextPadding;
				float textY = anchorY - padding - ((i + 1) * (padding + textHeight));
				OpenGL.drawSmallText(gl, entry.text, (int) textX, (int) textY, 0);
				if(entry.glColor != null)
					OpenGL.drawQuad2D(gl, entry.glColor, textX - Theme.tooltipTextPadding - textHeight, textY,
					                                     textX - Theme.tooltipTextPadding,              textY + textHeight);
			}
			
		} else if(orientation == WEST) {
			
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
			for(int i = 0; i < entries.size(); i++) {
				TooltipEntry entry = entries.get(i);
				float textX = (entry.glColor == null) ? anchorX - padding - boxWidth + (boxWidth - OpenGL.smallTextWidth(gl, entry.text)) / 2 :
				                                        anchorX - boxWidth + textHeight + Theme.tooltipTextPadding;
				float textY = anchorY + (boxHeight / 2f) - ((i + 1) * (padding + textHeight));
				OpenGL.drawSmallText(gl, entry.text, (int) textX, (int) textY, 0);
				if(entry.glColor != null)
					OpenGL.drawQuad2D(gl, entry.glColor, textX - Theme.tooltipTextPadding - textHeight, textY,
					                                     textX - Theme.tooltipTextPadding,              textY + textHeight);
			}
			
		} else if(orientation == EAST) {
			
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
			for(int i = 0; i < entries.size(); i++) {
				TooltipEntry entry = entries.get(i);
				float textX = (entry.glColor == null) ? anchorX + padding + (boxWidth - OpenGL.smallTextWidth(gl, entry.text)) / 2 :
				                                        anchorX + (2f * padding) + textHeight + Theme.tooltipTextPadding;
				float textY = anchorY + (boxHeight / 2f) - ((i + 1) * (padding + textHeight));
				OpenGL.drawSmallText(gl, entry.text, (int) textX, (int) textY, 0);
				if(entry.glColor != null)
					OpenGL.drawQuad2D(gl, entry.glColor, textX - Theme.tooltipTextPadding - textHeight, textY,
					                                     textX - Theme.tooltipTextPadding,              textY + textHeight);
			}
			
		} else if(orientation == NORTH_WEST) {
			
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
			for(int i = 0; i < entries.size(); i++) {
				TooltipEntry entry = entries.get(i);
				float textX = (entry.glColor == null) ? anchorX - boxWidth + (boxWidth - OpenGL.smallTextWidth(gl, entry.text)) / 2 :
				                                        anchorX - boxWidth + padding + textHeight + Theme.tooltipTextPadding;
				float textY = anchorY + padding + boxHeight - ((i + 1) * (padding + textHeight));
				OpenGL.drawSmallText(gl, entry.text, (int) textX, (int) textY, 0);
				if(entry.glColor != null)
					OpenGL.drawQuad2D(gl, entry.glColor, textX - Theme.tooltipTextPadding - textHeight, textY,
					                                     textX - Theme.tooltipTextPadding,              textY + textHeight);
			}
			
		} else if(orientation == NORTH_EAST) {
			
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
			for(int i = 0; i < entries.size(); i++) {
				TooltipEntry entry = entries.get(i);
				float textX = (entry.glColor == null) ? anchorX + (boxWidth - OpenGL.smallTextWidth(gl, entry.text)) / 2 :
				                                        anchorX + padding + textHeight + Theme.tooltipTextPadding;
				float textY = anchorY + padding + boxHeight - ((i + 1) * (padding + textHeight));
				OpenGL.drawSmallText(gl, entry.text, (int) textX, (int) textY, 0);
				if(entry.glColor != null)
					OpenGL.drawQuad2D(gl, entry.glColor, textX - Theme.tooltipTextPadding - textHeight, textY,
					                                     textX - Theme.tooltipTextPadding,              textY + textHeight);
			}
			
		} else if(orientation == SOUTH_WEST) {
			
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
			for(int i = 0; i < entries.size(); i++) {
				TooltipEntry entry = entries.get(i);
				float textX = (entry.glColor == null) ? anchorX - boxWidth + (boxWidth - OpenGL.smallTextWidth(gl, entry.text)) / 2 :
				                                        anchorX - boxWidth + padding + textHeight + Theme.tooltipTextPadding;
				float textY = anchorY - padding - ((i + 1) * (padding + textHeight));
				OpenGL.drawSmallText(gl, entry.text, (int) textX, (int) textY, 0);
				if(entry.glColor != null)
					OpenGL.drawQuad2D(gl, entry.glColor, textX - Theme.tooltipTextPadding - textHeight, textY,
					                                     textX - Theme.tooltipTextPadding,              textY + textHeight);
			}
			
		} else if(orientation == SOUTH_EAST) {
			
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
			for(int i = 0; i < entries.size(); i++) {
				TooltipEntry entry = entries.get(i);
				float textX = (entry.glColor == null) ? anchorX + (boxWidth - OpenGL.smallTextWidth(gl, entry.text)) / 2 :
				                                        anchorX + padding + textHeight + Theme.tooltipTextPadding;
				float textY = anchorY - padding - ((i + 1) * (padding + textHeight));
				OpenGL.drawSmallText(gl, entry.text, (int) textX, (int) textY, 0);
				if(entry.glColor != null)
					OpenGL.drawQuad2D(gl, entry.glColor, textX - Theme.tooltipTextPadding - textHeight, textY,
					                                     textX - Theme.tooltipTextPadding,              textY + textHeight);
			}
			
		}
		
	}
	
	public abstract void getConfigurationGui(JPanel gui);
	
	final public void importFrom(Queue<String> lines) {

		widgets.forEach(widget -> widget.importFrom(lines));
		
	}
	
	final public void exportTo(List<String> lines) {

		widgets.forEach(widget -> widget.exportTo(lines));
		
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