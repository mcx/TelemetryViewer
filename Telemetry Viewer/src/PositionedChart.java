import java.io.PrintWriter;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JPanel;

import com.jogamp.common.nio.Buffers;
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
		private record Row(float[] glColor, String text) {}
		private List<Row> rows = new ArrayList<Row>();
		public final int sampleNumber;
		public final long timestamp;
		private final float xAnchor;
		private final List<Float> yAnchors = new ArrayList<Float>();
		
		/**
		 * Creates a tooltip that does not correspond to a specific sample.
		 * (This is used by Frequency Domain and Histogram charts.)
		 * 
		 * @param title      Text to show at the top of the tooltip. May be multiple lines separated by \n characters.
		 * @param xAnchor    The x pixel to anchor this tooltip to.
		 * @param yAnchor    The y pixel to anchor this tooltip to. Use <0 if not yet determined.
		 */
		public Tooltip(String title, float xAnchor, float yAnchor) {
			this.sampleNumber = -1;
			this.timestamp = -1;
			this.xAnchor = xAnchor;
			if(yAnchor >= 0)
				this.yAnchors.add(yAnchor);
			List.of(title.split("\n")).forEach(line -> rows.add(new Row(null, line)));
		}
		
		/**
		 * Creates a tooltip that corresponds to a specific sample.
		 * A title showing the sample number and timestamp will be automatically created.
		 * (This is used by Time Domain charts.)
		 * 
		 * @param sampleNumber    Sample number for this tooltip.
		 * @param timestamp       Timestamp for this tooltip.
		 * @param xAnchor         The x pixel to anchor this tooltip to.
		 * @param yAnchor         The y pixel to anchor this tooltip to. Use <0 if not yet determined.
		 */
		public Tooltip(int sampleNumber, long timestamp, float xAnchor, float yAnchor) {
			this.sampleNumber = sampleNumber;
			this.timestamp = timestamp;
			this.xAnchor = xAnchor;
			if(yAnchor >= 0)
				this.yAnchors.add(yAnchor);
			String title = (sampleNumber >= 0) ? "Sample " + sampleNumber + "\n" + SettingsView.formatTimestampToMilliseconds(timestamp) :
			                                     SettingsView.formatTimestampToMilliseconds(timestamp);
			List.of(title.split("\n")).forEach(line -> rows.add(new Row(null, line)));
		}
		
		public Tooltip addRow(float[] glColor, String text, float yAnchor) {
			rows.add(new Row(glColor, text));
			yAnchors.add(yAnchor);
			return this;
		}
		
		public Tooltip addRow(float[] glColor, String text) {
			rows.add(new Row(glColor, text));
			return this;
		}
		
		record Drawable(List<Row>   rows,
		                float       xBoxLeft,
		                float       xBoxRight,
		                float       yBoxBottom,
		                float       yBoxTop,
		                float       xTriangleA,
		                float       yTriangleA,
		                float       xTriangleB,
		                float       yTriangleB,
		                float       xTriangleC,
		                float       yTriangleC,
		                float       padding,
		                FloatBuffer outline,
		                float[]     anchorLineDots,
		                boolean     isDrawable) {
			
			Drawable(List<Row> rows, float xBoxLeft, float xBoxRight, float yBoxBottom, float yBoxTop, float xTriangleA, float yTriangleA, float xTriangleB, float yTriangleB, float xTriangleC, float yTriangleC, float padding, FloatBuffer outline, float[] anchorLineDots) {
				// important: rounding xBoxLeft and xBoxRight to integers reduces text "jiggling"
				this(rows, Math.round(xBoxLeft), Math.round(xBoxRight), yBoxBottom, yBoxTop, xTriangleA, yTriangleA, xTriangleB, yTriangleB, xTriangleC, yTriangleC, padding, outline, anchorLineDots, true);
			}
			
			Drawable(boolean isDrawable) {
				this(null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, null, false);
			}
			
			private boolean partlyObscuredBy(Drawable other) {
				if(other == null)
					return false;
				boolean xOverlapped = (xBoxLeft + padding >= other.xBoxLeft) && (xBoxRight  - padding <= other.xBoxRight );
				boolean yOverlapped = (yBoxTop  - padding <= other.yBoxTop ) && (yBoxBottom + padding >= other.yBoxBottom);
				return xOverlapped && yOverlapped;
			}
			
			private boolean fullyObscuredBy(Drawable other) {
				if(other == null)
					return false;
				boolean xOverlapped = (xBoxLeft + Theme.lineWidth/2 >= other.xBoxLeft - Theme.lineWidth/2) && (xBoxRight  - Theme.lineWidth/2 <= other.xBoxRight  + Theme.lineWidth/2);
				boolean yOverlapped = (yBoxTop  - Theme.lineWidth/2 <= other.yBoxTop  + Theme.lineWidth/2) && (yBoxBottom + Theme.lineWidth/2 >= other.yBoxBottom - Theme.lineWidth/2);
				return xOverlapped && yOverlapped;
			}
			
			boolean draw(GL2ES3 gl, boolean faded, Drawable nextTooltip) {
				if(!isDrawable)
					return false;
				
				if(anchorLineDots != null)
					OpenGL.drawPointsXy(gl, Theme.tooltipVerticalBarColor, OpenGL.buffer.rewind().put(anchorLineDots).rewind(), anchorLineDots.length / 2);
				
				// draw the background
				if(!fullyObscuredBy(nextTooltip)) {
					OpenGL.buffer.rewind();
					OpenGL.buffer.put(xBoxLeft);   OpenGL.buffer.put(yBoxTop);
					OpenGL.buffer.put(xBoxLeft);   OpenGL.buffer.put(yBoxBottom);
					OpenGL.buffer.put(xBoxRight);  OpenGL.buffer.put(yBoxTop);
					OpenGL.buffer.put(xBoxRight);  OpenGL.buffer.put(yBoxTop);
					OpenGL.buffer.put(xBoxRight);  OpenGL.buffer.put(yBoxBottom);
					OpenGL.buffer.put(xBoxLeft);   OpenGL.buffer.put(yBoxBottom);
					OpenGL.buffer.put(xTriangleA); OpenGL.buffer.put(yTriangleA);
					OpenGL.buffer.put(xTriangleB); OpenGL.buffer.put(yTriangleB);
					OpenGL.buffer.put(xTriangleC); OpenGL.buffer.put(yTriangleC);
					OpenGL.drawTrianglesXY(gl, GL3.GL_TRIANGLES, faded ? Theme.plotBackgroundColor : Theme.tooltipBackgroundColor, OpenGL.buffer.rewind(), 9);
				}
				
				// draw the outline
				OpenGL.drawLinesXyrgba(gl, GL3.GL_LINES, outline.rewind(), outline.capacity() / 6);
				
				// draw the text and color boxes if they would be visible
				if(!partlyObscuredBy(nextTooltip)) {
					for(int i = 0; i < rows.size(); i++) {
						Row row = rows.get(i);
						float[] color = row.glColor;
						if(faded && color != null)
							color = new float[] {color[0], color[1], color[2], 0.3f};
						float textX = (row.glColor == null) ? xBoxLeft + (xBoxRight - xBoxLeft - OpenGL.smallTextWidth(gl, row.text)) / 2 :
						                                      xBoxLeft + padding + OpenGL.smallTextHeight + Theme.tooltipTextPadding;
						float textY = yBoxTop - ((i + 1) * (padding + OpenGL.smallTextHeight));
						OpenGL.drawSmallTextTransparent(gl, row.text, (int) textX, (int) textY, 0, faded ? 0.3f : 1f);
						if(row.glColor != null)
							OpenGL.drawQuad2D(gl, color, textX - Theme.tooltipTextPadding - OpenGL.smallTextHeight, textY,
							                             textX - Theme.tooltipTextPadding,                          textY + OpenGL.smallTextHeight);
					}
				}
				
				return true;
			}
			
		}
		
		public Drawable bake(GL2ES3 gl, float plotWidth, float plotHeight, int mouseX, int mouseY, float xAnchor) {
			
			// sanity checks
			if(rows.isEmpty())
				return new Drawable(false);
			if(xAnchor < 0 || xAnchor > plotWidth)
				return new Drawable(false);
			
			// calculate the bounding box
			float maxWidth = (float) rows.stream().mapToDouble(row -> (row.glColor == null) ?
			                                                          OpenGL.smallTextWidth(gl, row.text) : 
			                                                          OpenGL.smallTextWidth(gl, row.text) + OpenGL.smallTextHeight + Theme.tooltipTextPadding).max().orElse(0);
			float padding = 6f * ChartsController.getDisplayScalingFactor();
			float boxWidth = maxWidth + (2 * padding);
			float boxHeight = rows.size() * (OpenGL.smallTextHeight + padding) + padding;
			
			// determine the outline color and where/how to anchor
			boolean edgeMarker = yAnchors.isEmpty();
			boolean multipleDatasets = yAnchors.size() > 1;
			boolean anchoredAtTop = edgeMarker || multipleDatasets;
			float yAnchor = anchoredAtTop ? plotHeight - boxHeight - padding : Math.max(0, yAnchors.getFirst());
			float[] outlineColor = edgeMarker ? Theme.markerBorderColor : Theme.tooltipBorderColor;
			float[] anchorLineDots = multipleDatasets ? new float[yAnchors.size() * 2] : null;
			if(multipleDatasets) {
				for(int i = 0; i < yAnchors.size(); i++) {
					anchorLineDots[2*i]     = xAnchor;
					anchorLineDots[2*i + 1] = yAnchors.get(i);
				}
			}
			
			// determine which orientation to use
			// vertex order was arbitrarily chosen to start at the top-left corner, then go clockwise, then do the anchor line if applicable
			if(yAnchor + padding + boxHeight <= plotHeight) {
				if(xAnchor - (boxWidth / 2f) >= 0 && xAnchor + (boxWidth / 2f) <= plotWidth) {
					// space above and beside the anchor, so point south
					float xBoxLeft   = xAnchor - (boxWidth / 2f);
					float xBoxRight  = xAnchor + (boxWidth / 2f);
					float yBoxBottom = yAnchor + padding;
					float yBoxTop    = yAnchor + padding + boxHeight;
					float xTriangleA = xAnchor + (padding / 2f);
					float yTriangleA = yBoxBottom;
					float xTriangleB = xAnchor;
					float yTriangleB = yAnchor;
					float xTriangleC = xAnchor - (padding / 2f);
					float yTriangleC = yBoxBottom;
					if(mouseX >= xBoxLeft && mouseX <= xBoxRight && mouseY >= yBoxBottom && mouseY <= yBoxTop)
						outlineColor = Theme.tooltipBorderColor; // force outline to black if mouseOver
					FloatBuffer outline = Buffers.newDirectFloatBuffer(6*14 + (anchoredAtTop ? 6*2 : 0));
					outline.put(xBoxLeft);   outline.put(yBoxTop);    outline.put(outlineColor);
					outline.put(xBoxRight);  outline.put(yBoxTop);    outline.put(outlineColor);
					outline.put(xBoxRight);  outline.put(yBoxTop);    outline.put(outlineColor);
					outline.put(xBoxRight);  outline.put(yBoxBottom); outline.put(outlineColor);
					outline.put(xBoxRight);  outline.put(yBoxBottom); outline.put(outlineColor);
					outline.put(xTriangleA); outline.put(yTriangleA); outline.put(outlineColor);
					outline.put(xTriangleA); outline.put(yTriangleA); outline.put(outlineColor);
					outline.put(xTriangleB); outline.put(yTriangleB); outline.put(outlineColor);
					outline.put(xTriangleB); outline.put(yTriangleB); outline.put(outlineColor);
					outline.put(xTriangleC); outline.put(yTriangleC); outline.put(outlineColor);
					outline.put(xTriangleC); outline.put(yTriangleC); outline.put(outlineColor);
					outline.put(xBoxLeft);   outline.put(yBoxBottom); outline.put(outlineColor);
					outline.put(xBoxLeft);   outline.put(yBoxBottom); outline.put(outlineColor);
					outline.put(xBoxLeft);   outline.put(yBoxTop);    outline.put(outlineColor);
					if(edgeMarker) { // draw a fading downward line
						outline.put(xAnchor); outline.put(yAnchor);               outline.put(outlineColor);
						outline.put(xAnchor); outline.put(yAnchor - padding * 6); outline.put(outlineColor, 0, 3); outline.put(0);
					} else if(multipleDatasets) { // draw a line down to the lowest piece of data
						float minY = Math.min(yAnchor, yAnchors.stream().min(Float::compare).get());
						outline.put(xAnchor); outline.put(yAnchor); outline.put(Theme.tooltipVerticalBarColor);
						outline.put(xAnchor); outline.put(minY);    outline.put(Theme.tooltipVerticalBarColor);
					}
					return new Drawable(rows, xBoxLeft, xBoxRight, yBoxBottom, yBoxTop, xTriangleA, yTriangleA, xTriangleB, yTriangleB, xTriangleC, yTriangleC, padding, outline, anchorLineDots);
				} else if(xAnchor - boxWidth >= 0 && xAnchor <= plotWidth) {
					// space above and to the left, so point south-east
					float xBoxLeft   = xAnchor - boxWidth;
					float xBoxRight  = xAnchor;
					float yBoxBottom = yAnchor + padding;
					float yBoxTop    = yAnchor + padding + boxHeight;
					float xTriangleA = xAnchor;
					float yTriangleA = yAnchor + padding;
					float xTriangleB = xAnchor;
					float yTriangleB = yAnchor;
					float xTriangleC = xAnchor - (0.85f * padding);
					float yTriangleC = yAnchor + padding;
					if(mouseX >= xBoxLeft && mouseX <= xBoxRight && mouseY >= yBoxBottom && mouseY <= yBoxTop)
						outlineColor = Theme.tooltipBorderColor; // force outline to black if mouseOver
					FloatBuffer outline = Buffers.newDirectFloatBuffer(6*10 + (anchoredAtTop ? 6*2 : 0));
					outline.put(xBoxLeft);   outline.put(yBoxTop);     outline.put(outlineColor);
					outline.put(xBoxRight);  outline.put(yBoxTop);     outline.put(outlineColor);
					outline.put(xBoxRight);  outline.put(yBoxTop);     outline.put(outlineColor);
					outline.put(xTriangleB); outline.put(yTriangleB);  outline.put(outlineColor);
					outline.put(xTriangleB); outline.put(yTriangleB);  outline.put(outlineColor);
					outline.put(xTriangleC); outline.put(yTriangleC);  outline.put(outlineColor);
					outline.put(xTriangleC); outline.put(yTriangleC);  outline.put(outlineColor);
					outline.put(xBoxLeft);   outline.put(yBoxBottom);  outline.put(outlineColor);
					outline.put(xBoxLeft);   outline.put(yBoxBottom);  outline.put(outlineColor);
					outline.put(xBoxLeft);   outline.put(yBoxTop);     outline.put(outlineColor);
					if(edgeMarker) { // draw a fading downward line
						outline.put(xAnchor); outline.put(yAnchor);               outline.put(outlineColor);
						outline.put(xAnchor); outline.put(yAnchor - padding * 6); outline.put(outlineColor, 0, 3); outline.put(0);
					} else if(multipleDatasets) { // draw a line down to the lowest piece of data
						float minY = Math.min(yAnchor, yAnchors.stream().min(Float::compare).get());
						outline.put(xAnchor); outline.put(yAnchor); outline.put(Theme.tooltipVerticalBarColor);
						outline.put(xAnchor); outline.put(minY);    outline.put(Theme.tooltipVerticalBarColor);
					}
					return new Drawable(rows, xBoxLeft, xBoxRight, yBoxBottom, yBoxTop, xTriangleA, yTriangleA, xTriangleB, yTriangleB, xTriangleC, yTriangleC, padding, outline, anchorLineDots);
				} else if(xAnchor >= 0 && xAnchor + boxWidth <= plotWidth) {
					// space above and to the right, so point south-west
					float xBoxLeft   = xAnchor;
					float xBoxRight  = xAnchor + boxWidth;
					float yBoxBottom = yAnchor + padding;
					float yBoxTop    = yAnchor + padding + boxHeight;
					float xTriangleA = xAnchor + (0.85f * padding);
					float yTriangleA = yAnchor + padding;
					float xTriangleB = xAnchor;
					float yTriangleB = yAnchor;
					float xTriangleC = xAnchor;
					float yTriangleC = yAnchor + padding;
					if(mouseX >= xBoxLeft && mouseX <= xBoxRight && mouseY >= yBoxBottom && mouseY <= yBoxTop)
						outlineColor = Theme.tooltipBorderColor; // force outline to black if mouseOver
					FloatBuffer outline = Buffers.newDirectFloatBuffer(6*10 + (anchoredAtTop ? 6*2 : 0));
					outline.put(xBoxLeft);   outline.put(yBoxTop);    outline.put(outlineColor);
					outline.put(xBoxRight);  outline.put(yBoxTop);    outline.put(outlineColor);
					outline.put(xBoxRight);  outline.put(yBoxTop);    outline.put(outlineColor);
					outline.put(xBoxRight);  outline.put(yBoxBottom); outline.put(outlineColor);
					outline.put(xBoxRight);  outline.put(yBoxBottom); outline.put(outlineColor);
					outline.put(xTriangleA); outline.put(yTriangleA); outline.put(outlineColor);
					outline.put(xTriangleA); outline.put(yTriangleA); outline.put(outlineColor);
					outline.put(xTriangleB); outline.put(yTriangleB); outline.put(outlineColor);
					outline.put(xTriangleB); outline.put(yTriangleB); outline.put(outlineColor);
					outline.put(xBoxLeft);   outline.put(yBoxTop);    outline.put(outlineColor);
					if(edgeMarker) { // draw a fading downward line
						outline.put(xAnchor); outline.put(yAnchor);               outline.put(outlineColor);
						outline.put(xAnchor); outline.put(yAnchor - padding * 6); outline.put(outlineColor, 0, 3); outline.put(0);
					} else if(multipleDatasets) { // draw a line down to the lowest piece of data
						float minY = Math.min(yAnchor, yAnchors.stream().min(Float::compare).get());
						outline.put(xAnchor); outline.put(yAnchor); outline.put(Theme.tooltipVerticalBarColor);
						outline.put(xAnchor); outline.put(minY);    outline.put(Theme.tooltipVerticalBarColor);
					}
					return new Drawable(rows, xBoxLeft, xBoxRight, yBoxBottom, yBoxTop, xTriangleA, yTriangleA, xTriangleB, yTriangleB, xTriangleC, yTriangleC, padding, outline, anchorLineDots);
				} else {
					// space above but not enough space on either side
					return new Drawable(false);
				}
			} else if(yAnchor + (boxHeight / 2f) <= plotHeight && yAnchor - (boxHeight / 2f) >= 0) {
				if(xAnchor - padding - boxWidth >= 0) {
					// some space above and below the anchor, and space to the left, so point east
					float xBoxLeft   = xAnchor - padding - boxWidth;
					float xBoxRight  = xAnchor - padding;
					float yBoxBottom = yAnchor - (boxHeight / 2f);
					float yBoxTop    = yAnchor + (boxHeight / 2f);
					float xTriangleA = xAnchor - padding;
					float yTriangleA = yAnchor + (padding / 2f);
					float xTriangleB = xAnchor;
					float yTriangleB = yAnchor;
					float xTriangleC = xAnchor - padding;
					float yTriangleC = yAnchor - (padding / 2f);
					if(mouseX >= xBoxLeft && mouseX <= xBoxRight && mouseY >= yBoxBottom && mouseY <= yBoxTop)
						outlineColor = Theme.tooltipBorderColor; // force outline to black if mouseOver
					FloatBuffer outline = Buffers.newDirectFloatBuffer(6*14 + (anchoredAtTop ? 6*2 : 0));
					outline.put(xBoxLeft);   outline.put(yBoxTop);    outline.put(outlineColor);
					outline.put(xBoxRight);  outline.put(yBoxTop);    outline.put(outlineColor);
					outline.put(xBoxRight);  outline.put(yBoxTop);    outline.put(outlineColor);
					outline.put(xTriangleA); outline.put(yTriangleA); outline.put(outlineColor);
					outline.put(xTriangleA); outline.put(yTriangleA); outline.put(outlineColor);
					outline.put(xTriangleB); outline.put(yTriangleB); outline.put(outlineColor);
					outline.put(xTriangleB); outline.put(yTriangleB); outline.put(outlineColor);
					outline.put(xTriangleC); outline.put(yTriangleC); outline.put(outlineColor);
					outline.put(xTriangleC); outline.put(yTriangleC); outline.put(outlineColor);
					outline.put(xBoxRight);  outline.put(yBoxBottom); outline.put(outlineColor);
					outline.put(xBoxRight);  outline.put(yBoxBottom); outline.put(outlineColor);
					outline.put(xBoxLeft);   outline.put(yBoxBottom); outline.put(outlineColor);
					outline.put(xBoxLeft);   outline.put(yBoxBottom); outline.put(outlineColor);
					outline.put(xBoxLeft);   outline.put(yBoxTop);    outline.put(outlineColor);
					if(edgeMarker) { // draw a fading downward line
						outline.put(xAnchor); outline.put(yAnchor);               outline.put(outlineColor);
						outline.put(xAnchor); outline.put(yAnchor - padding * 6); outline.put(outlineColor, 0, 3); outline.put(0);
					} else if(multipleDatasets) { // draw a line down to the lowest piece of data
						float minY = Math.min(yAnchor, yAnchors.stream().min(Float::compare).get());
						outline.put(xAnchor); outline.put(yAnchor); outline.put(Theme.tooltipVerticalBarColor);
						outline.put(xAnchor); outline.put(minY);    outline.put(Theme.tooltipVerticalBarColor);
					}
					return new Drawable(rows, xBoxLeft, xBoxRight, yBoxBottom, yBoxTop, xTriangleA, yTriangleA, xTriangleB, yTriangleB, xTriangleC, yTriangleC, padding, outline, anchorLineDots);
				} else if(xAnchor + padding + boxWidth <= plotWidth) {
					// some space above and below the anchor, and space to the right, so point west
					float xBoxLeft   = xAnchor + padding;
					float xBoxRight  = xAnchor + padding + boxWidth;
					float yBoxBottom = yAnchor - (boxHeight / 2f);
					float yBoxTop    = yAnchor + (boxHeight / 2f);
					float xTriangleA = xAnchor + padding;
					float yTriangleA = yAnchor - (padding / 2f);
					float xTriangleB = xAnchor;
					float yTriangleB = yAnchor;
					float xTriangleC = xAnchor + padding;
					float yTriangleC = yAnchor + (padding / 2f);
					if(mouseX >= xBoxLeft && mouseX <= xBoxRight && mouseY >= yBoxBottom && mouseY <= yBoxTop)
						outlineColor = Theme.tooltipBorderColor; // force outline to black if mouseOver
					FloatBuffer outline = Buffers.newDirectFloatBuffer(6*14 + (anchoredAtTop ? 6*2 : 0));
					outline.put(xBoxLeft);   outline.put(yBoxTop);    outline.put(outlineColor);
					outline.put(xBoxRight);  outline.put(yBoxTop);    outline.put(outlineColor);
					outline.put(xBoxRight);  outline.put(yBoxTop);    outline.put(outlineColor);
					outline.put(xBoxRight);  outline.put(yBoxBottom); outline.put(outlineColor);
					outline.put(xBoxRight);  outline.put(yBoxBottom); outline.put(outlineColor);
					outline.put(xBoxLeft);   outline.put(yBoxBottom); outline.put(outlineColor);
					outline.put(xBoxLeft);   outline.put(yBoxBottom); outline.put(outlineColor);
					outline.put(xTriangleA); outline.put(yTriangleA); outline.put(outlineColor);
					outline.put(xTriangleA); outline.put(yTriangleA); outline.put(outlineColor);
					outline.put(xTriangleB); outline.put(yTriangleB); outline.put(outlineColor);
					outline.put(xTriangleB); outline.put(yTriangleB); outline.put(outlineColor);
					outline.put(xTriangleC); outline.put(yTriangleC); outline.put(outlineColor);
					outline.put(xTriangleC); outline.put(yTriangleC); outline.put(outlineColor);
					outline.put(xBoxLeft);   outline.put(yBoxTop);    outline.put(outlineColor);
					if(edgeMarker) { // draw a fading downward line
						outline.put(xAnchor); outline.put(yAnchor);               outline.put(outlineColor);
						outline.put(xAnchor); outline.put(yAnchor - padding * 6); outline.put(outlineColor, 0, 3); outline.put(0);
					} else if(multipleDatasets) { // draw a line down to the lowest piece of data
						float minY = Math.min(yAnchor, yAnchors.stream().min(Float::compare).get());
						outline.put(xAnchor); outline.put(yAnchor); outline.put(Theme.tooltipVerticalBarColor);
						outline.put(xAnchor); outline.put(minY);    outline.put(Theme.tooltipVerticalBarColor);
					}
					return new Drawable(rows, xBoxLeft, xBoxRight, yBoxBottom, yBoxTop, xTriangleA, yTriangleA, xTriangleB, yTriangleB, xTriangleC, yTriangleC, padding, outline, anchorLineDots);
				} else {
					// some space above and below the anchor, but not enough space on either side
					return new Drawable(false);
				}
			} else if(yAnchor - padding - boxHeight >= 0) {
				// there is space below the anchor, so use SOUTH or SOUTH_WEST or SOUTH_EAST if there is enough horizontal space
				if(xAnchor - (boxWidth / 2f) >= 0 && xAnchor + (boxWidth / 2f) <= plotWidth) {
					// space below and beside the anchor, so point north
					float xBoxLeft   = xAnchor - (boxWidth / 2f);
					float xBoxRight  = xAnchor + (boxWidth / 2f);
					float yBoxBottom = yAnchor - padding - boxHeight;
					float yBoxTop    = yAnchor - padding;
					float xTriangleA = xAnchor - (padding / 2f);
					float yTriangleA = yBoxTop;
					float xTriangleB = xAnchor;
					float yTriangleB = yAnchor;
					float xTriangleC = xAnchor + (padding / 2f);
					float yTriangleC = yBoxTop;
					if(mouseX >= xBoxLeft && mouseX <= xBoxRight && mouseY >= yBoxBottom && mouseY <= yBoxTop)
						outlineColor = Theme.tooltipBorderColor; // force outline to black if mouseOver
					FloatBuffer outline = Buffers.newDirectFloatBuffer(6*14 + (anchoredAtTop ? 6*2 : 0));
					outline.put(xBoxLeft);   outline.put(yBoxTop);    outline.put(outlineColor);
					outline.put(xTriangleA); outline.put(yTriangleA); outline.put(outlineColor);
					outline.put(xTriangleA); outline.put(yTriangleA); outline.put(outlineColor);
					outline.put(xTriangleB); outline.put(yTriangleB); outline.put(outlineColor);
					outline.put(xTriangleB); outline.put(yTriangleB); outline.put(outlineColor);
					outline.put(xTriangleC); outline.put(yTriangleC); outline.put(outlineColor);
					outline.put(xTriangleC); outline.put(yTriangleC); outline.put(outlineColor);
					outline.put(xBoxRight);  outline.put(yBoxTop);    outline.put(outlineColor);
					outline.put(xBoxRight);  outline.put(yBoxTop);    outline.put(outlineColor);
					outline.put(xBoxRight);  outline.put(yBoxBottom); outline.put(outlineColor);
					outline.put(xBoxRight);  outline.put(yBoxBottom); outline.put(outlineColor);
					outline.put(xBoxLeft);   outline.put(yBoxBottom); outline.put(outlineColor);
					outline.put(xBoxLeft);   outline.put(yBoxBottom); outline.put(outlineColor);
					outline.put(xBoxLeft);   outline.put(yBoxTop);    outline.put(outlineColor);
					if(edgeMarker) { // draw a fading downward line
						outline.put(xAnchor); outline.put(yAnchor);               outline.put(outlineColor);
						outline.put(xAnchor); outline.put(yAnchor - padding * 6); outline.put(outlineColor, 0, 3); outline.put(0);
					} else if(multipleDatasets) { // draw a line down to the lowest piece of data
						float minY = Math.min(yAnchor, yAnchors.stream().min(Float::compare).get());
						outline.put(xAnchor); outline.put(yAnchor); outline.put(Theme.tooltipVerticalBarColor);
						outline.put(xAnchor); outline.put(minY);    outline.put(Theme.tooltipVerticalBarColor);
					}
					return new Drawable(rows, xBoxLeft, xBoxRight, yBoxBottom, yBoxTop, xTriangleA, yTriangleA, xTriangleB, yTriangleB, xTriangleC, yTriangleC, padding, outline, anchorLineDots);
				} else if(xAnchor - boxWidth >= 0 && xAnchor <= plotWidth) {
					// space below and to the left, so point north-east
					float xBoxLeft   = xAnchor - boxWidth;
					float xBoxRight  = xAnchor;
					float yBoxBottom = yAnchor - padding - boxHeight;
					float yBoxTop    = yAnchor - padding;
					float xTriangleA = xAnchor - (0.85f * padding);
					float yTriangleA = yAnchor - padding;
					float xTriangleB = xAnchor;
					float yTriangleB = yAnchor;
					float xTriangleC = xAnchor;
					float yTriangleC = yAnchor - padding;
					if(mouseX >= xBoxLeft && mouseX <= xBoxRight && mouseY >= yBoxBottom && mouseY <= yBoxTop)
						outlineColor = Theme.tooltipBorderColor; // force outline to black if mouseOver
					FloatBuffer outline = Buffers.newDirectFloatBuffer(6*10 + (anchoredAtTop ? 6*2 : 0));
					outline.put(xBoxLeft);   outline.put(yBoxTop);    outline.put(outlineColor);
					outline.put(xTriangleA); outline.put(yTriangleA); outline.put(outlineColor);
					outline.put(xTriangleA); outline.put(yTriangleA); outline.put(outlineColor);
					outline.put(xTriangleB); outline.put(yTriangleB); outline.put(outlineColor);
					outline.put(xTriangleB); outline.put(yTriangleB); outline.put(outlineColor);
					outline.put(xBoxRight);  outline.put(yBoxBottom); outline.put(outlineColor);
					outline.put(xBoxRight);  outline.put(yBoxBottom); outline.put(outlineColor);
					outline.put(xBoxLeft);   outline.put(yBoxBottom); outline.put(outlineColor);
					outline.put(xBoxLeft);   outline.put(yBoxBottom); outline.put(outlineColor);
					outline.put(xBoxLeft);   outline.put(yBoxTop);    outline.put(outlineColor);
					if(edgeMarker) { // draw a fading downward line
						outline.put(xAnchor); outline.put(yAnchor);               outline.put(outlineColor);
						outline.put(xAnchor); outline.put(yAnchor - padding * 6); outline.put(outlineColor, 0, 3); outline.put(0);
					} else if(multipleDatasets) { // draw a line down to the lowest piece of data
						float minY = Math.min(yAnchor, yAnchors.stream().min(Float::compare).get());
						outline.put(xAnchor); outline.put(yAnchor); outline.put(Theme.tooltipVerticalBarColor);
						outline.put(xAnchor); outline.put(minY);    outline.put(Theme.tooltipVerticalBarColor);
					}
					return new Drawable(rows, xBoxLeft, xBoxRight, yBoxBottom, yBoxTop, xTriangleA, yTriangleA, xTriangleB, yTriangleB, xTriangleC, yTriangleC, padding, outline, anchorLineDots);
				} else if(xAnchor >= 0 && xAnchor + boxWidth <= plotWidth) {
					// space below and to the right, so point north-west
					float xBoxLeft   = xAnchor;
					float xBoxRight  = xAnchor + boxWidth;
					float yBoxBottom = yAnchor - padding - boxHeight;
					float yBoxTop    = yAnchor - padding;
					float xTriangleA = xAnchor;
					float yTriangleA = yAnchor - padding;
					float xTriangleB = xAnchor;
					float yTriangleB = yAnchor;
					float xTriangleC = xAnchor + (0.85f * padding);
					float yTriangleC = yAnchor - padding;
					if(mouseX >= xBoxLeft && mouseX <= xBoxRight && mouseY >= yBoxBottom && mouseY <= yBoxTop)
						outlineColor = Theme.tooltipBorderColor; // force outline to black if mouseOver
					FloatBuffer outline = Buffers.newDirectFloatBuffer(6*10 + (anchoredAtTop ? 6*2 : 0));
					outline.put(xTriangleB); outline.put(yTriangleB); outline.put(outlineColor);
					outline.put(xTriangleC); outline.put(yTriangleC); outline.put(outlineColor);
					outline.put(xTriangleC); outline.put(yTriangleC); outline.put(outlineColor);
					outline.put(xBoxRight);  outline.put(yBoxTop);    outline.put(outlineColor);
					outline.put(xBoxRight);  outline.put(yBoxTop);    outline.put(outlineColor);
					outline.put(xBoxRight);  outline.put(yBoxBottom); outline.put(outlineColor);
					outline.put(xBoxRight);  outline.put(yBoxBottom); outline.put(outlineColor);
					outline.put(xBoxLeft);   outline.put(yBoxBottom); outline.put(outlineColor);
					outline.put(xBoxLeft);   outline.put(yBoxBottom); outline.put(outlineColor);
					outline.put(xTriangleB); outline.put(yTriangleB); outline.put(outlineColor);
					if(edgeMarker) { // draw a fading downward line
						outline.put(xAnchor); outline.put(yAnchor);               outline.put(outlineColor);
						outline.put(xAnchor); outline.put(yAnchor - padding * 6); outline.put(outlineColor, 0, 3); outline.put(0);
					} else if(multipleDatasets) { // draw a line down to the lowest piece of data
						float minY = Math.min(yAnchor, yAnchors.stream().min(Float::compare).get());
						outline.put(xAnchor); outline.put(yAnchor); outline.put(Theme.tooltipVerticalBarColor);
						outline.put(xAnchor); outline.put(minY);    outline.put(Theme.tooltipVerticalBarColor);
					}
					return new Drawable(rows, xBoxLeft, xBoxRight, yBoxBottom, yBoxTop, xTriangleA, yTriangleA, xTriangleB, yTriangleB, xTriangleC, yTriangleC, padding, outline, anchorLineDots);
				} else {
					// space below but not enough space on either side
					return new Drawable(false);
				}
			} else {
				// not enough space anywhere
				return new Drawable(false);
			}
			
		}
		
		/**
		 * Draws this tooltip on screen. An anchor point specifies where the tooltip should point to.
		 * 
		 * @param gl            The OpenGL context.
		 * @param mouseX        X location of the mouse, in pixels, relative to the plot region.
		 * @param mouseY        Y location of the mouse, in pixels, relative to the plot region.
		 * @param plotWidth     Width of the plot region, in pixels.
		 * @param plotHeight    Height of the plot region, in pixels.
		 * @returns             True if the tooltip was drawn, or false there wasn't enough space to draw it.
		 */
		public boolean draw(GL2ES3 gl, int mouseX, int mouseY, float plotWidth, float plotHeight) {
			
			return bake(gl, plotWidth, plotHeight, mouseX, mouseY, xAnchor).draw(gl, false, null);
			
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
	 */
	public void disposeNonGpu() {
		
		if(OpenGLChartsView.globalTrigger == trigger)
			OpenGLChartsView.globalTrigger = null;
		
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