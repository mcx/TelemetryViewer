import java.io.PrintWriter;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javax.swing.JPanel;

import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL2ES3;
import com.jogamp.opengl.GL3;

public abstract class Chart {
	
	public final String name;
	public final int topLeftX;
	public final int topLeftY;
	public final int bottomRightX;
	public final int bottomRightY;
	protected int duration;
	protected boolean sampleCountMode = true;
	protected DatasetsInterface datasets = new DatasetsInterface();
	protected List<Widget> widgets = new ArrayList<Widget>();
	protected WidgetTrigger trigger;
	
	/**
	 * Creates a chart.
	 * 
	 * @param name    User-friendly name describing this chart. This will be displayed on chart type buttons, and used when importing/exporting.
	 * @param x1      X tile coordinate of one corner.
	 * @param y1      Y tile coordinate of one corner.
	 * @param x2      X tile coordinate of the opposite corner.
	 * @param y2      Y tile coordinate of the opposite corner.
	 */
	protected Chart(String name, int x1, int y1, int x2, int y2) {
		
		this.name = name;
		topLeftX     = x1 < x2 ? x1 : x2;
		topLeftY     = y1 < y2 ? y1 : y2;
		bottomRightX = x2 > x1 ? x2 : x1;
		bottomRightY = y2 > y1 ? y2 : y1;
		
	}
	
	@Override public String toString() { return name; }
	
	/**
	 * Checks if a certain tile region would overlap this chart.
	 * 
	 * @param x1    X tile coordinate of one corner.
	 * @param y1    Y tile coordinate of one corner.
	 * @param x2    X tile coordinate of the opposite corner.
	 * @param y2    Y tile coordinate of the opposite corner.
	 * @return      True if that tile region would overlap with this chart.
	 */
	final public boolean intersects(int x1, int y1, int x2, int y2) {
		
		if(x2 < x1) {
			int temp = x1;
			x1 = x2;
			x2 = temp;
		}
		if(y2 < y1) {
			int temp = y1;
			y1 = y2;
			y2 = temp;
		}
		
		for(int x = x1; x <= x2; x++)
			for(int y = y1; y <= y2; y++)
				if(x >= topLeftX && x <= bottomRightX && y >= topLeftY && y <= bottomRightY)
					return true;
		
		return false;
		
	}
	
	private long cpuStartNanoseconds;
	private long cpuStopNanoseconds;
	private double previousCpuMilliseconds;
	private double previousGpuMilliseconds;
	private double cpuMillisecondsAccumulator;
	private double gpuMillisecondsAccumulator;
	private int count;
	private long endAveragingTimestamp = System.currentTimeMillis() + 1000;
	private double averageCpuMilliseconds;
	private double averageGpuMilliseconds;
	private int[] gpuQueryHandles;
	
	/**
	 * Draws this chart on screen, and measures the CPU/GPU usage of this chart if that feature is enabled.
	 * GPU usage measurements are only possible with OpenGL, not OpenGL ES.
	 * 
	 * @param gl                 The OpenGL context.
	 * @param chartMatrix        The 4x4 matrix to use.
	 * @param width              Width of the chart, in pixels.
	 * @param height             Height of the chart, in pixels.
	 * @param endTimestamp       Timestamp corresponding with the right edge of a time-domain plot. NOTE: this might be in the future!
	 * @param endSampleNumber    Sample number corresponding with the right edge of a time-domain plot. NOTE: this might be in the future!
	 * @param zoomLevel          Requested zoom level.
	 * @param mouseX             Mouse's x position, in pixels, relative to the chart.
	 * @param mouseY             Mouse's y position, in pixels, relative to the chart.
	 * @return                   An EventHandler if the mouse is over something that can be clicked or dragged.
	 */
	final public EventHandler draw(GL2ES3 gl, float[] chartMatrix, int width, int height, long endTimestamp, int endSampleNumber, double zoomLevel, int mouseX, int mouseY) {
		
		boolean openGLES = OpenGLCharts.GUI.openGLES;
		
		// create the OpenGL timer queries if they don't already exist
		if(!openGLES && gpuQueryHandles == null) {
			gpuQueryHandles = new int[2];
			gl.glGenQueries(2, gpuQueryHandles, 0);
			gl.glQueryCounter(gpuQueryHandles[0], GL3.GL_TIMESTAMP); // insert both queries to prevent a warning on the first time they are read
			gl.glQueryCounter(gpuQueryHandles[1], GL3.GL_TIMESTAMP);
		}
		
		// if measuring CPU/GPU usage, calculate CPU/GPU time for the *previous frame*
		if(Settings.GUI.cpuGpuMeasurementsEnabled.isTrue()) {
			previousCpuMilliseconds = (cpuStopNanoseconds - cpuStartNanoseconds) / 1000000.0;
			if(!openGLES) {
				long[] gpuTimes = new long[2];
				gl.glGetQueryObjecti64v(gpuQueryHandles[0], GL3.GL_QUERY_RESULT, gpuTimes, 0);
				gl.glGetQueryObjecti64v(gpuQueryHandles[1], GL3.GL_QUERY_RESULT, gpuTimes, 1);
				previousGpuMilliseconds = (gpuTimes[1] - gpuTimes[0]) / 1000000.0;
			}
			cpuMillisecondsAccumulator += previousCpuMilliseconds;
			gpuMillisecondsAccumulator += previousGpuMilliseconds;
			count++;
			if(System.currentTimeMillis() >= endAveragingTimestamp) {
				averageCpuMilliseconds = cpuMillisecondsAccumulator / count;
				averageGpuMilliseconds = gpuMillisecondsAccumulator / count;
				cpuMillisecondsAccumulator = 0;
				gpuMillisecondsAccumulator = 0;
				count = 0;
				endAveragingTimestamp = System.currentTimeMillis() + 1000;
			}
			
			// start timers for *this frame*
			cpuStartNanoseconds = System.nanoTime();
			if(!openGLES)
				gl.glQueryCounter(gpuQueryHandles[0], GL3.GL_TIMESTAMP);
		}
		
		// draw the chart
		EventHandler handler = drawChart(gl, chartMatrix, width, height, endTimestamp, endSampleNumber, zoomLevel, mouseX, mouseY);
		
		// if measuring CPU/GPU usage, draw the CPU/GPU times over this chart
		if(Settings.GUI.cpuGpuMeasurementsEnabled.isTrue()) {
			// stop timers for *this frame*
			cpuStopNanoseconds = System.nanoTime();
			if(!openGLES)
				gl.glQueryCounter(gpuQueryHandles[1], GL3.GL_TIMESTAMP);
			
			// show times of *previous frame*
			OpenGL.drawTextBox(gl, 0, 0, false, "This Chart:", List.of(
			                                     String.format("CPU = %.3fms ", previousCpuMilliseconds),
			                                     String.format("(%.3fms)",      averageCpuMilliseconds ),
			                         !openGLES ? String.format("GPU = %.3fms ", previousGpuMilliseconds) : "GPU = unknown",
			                         !openGLES ? String.format("(%.3fms)",      averageGpuMilliseconds ) : ""));
		}
		
		// return the mouse event handler
		return handler;
		
	}
	
	/**
	 * Draws this chart on screen.
	 * 
	 * @param gl                 The OpenGL context.
	 * @param chartMatrix        The 4x4 matrix to use.
	 * @param width              Width of the chart, in pixels.
	 * @param height             Height of the chart, in pixels.
	 * @param endTimestamp       Timestamp corresponding with the right edge of a time-domain plot. NOTE: this might be in the future!
	 * @param endSampleNumber    Sample number corresponding with the right edge of a time-domain plot. NOTE: this might be in the future!
	 * @param zoomLevel          Requested zoom level.
	 * @param mouseX             Mouse's x position, in pixels, relative to the chart.
	 * @param mouseY             Mouse's y position, in pixels, relative to the chart.
	 * @return                   An EventHandler if the mouse is over something that can be clicked or dragged.
	 */
	public abstract EventHandler drawChart(GL2ES3 gl, float[] chartMatrix, int width, int height, long endTimestamp, int endSampleNumber, double zoomLevel, int mouseX, int mouseY);
	
	/**
	 * Appends configuration widgets for this chart to a preexisting JPanel.
	 * 
	 * @param gui    The JPanel that receives the widgets. This will be shown to the user when they create the chart or when they click the gear icon to configure the chart.
	 */
	public abstract void appendConfigurationWidgets(JPanel gui);
	
	/**
	 * Imports a chart and all of its settings.
	 * 
	 * @param lines    Lines of text from the settings file.
	 * @return         The fully configured chart.
	 */
	final public static Chart importFrom(Connections.QueueOfLines lines) {
		
		lines.parseExact("");
		String type = lines.parseString("chart type = %s");
		Charts.Type chartType = Stream.of(Charts.Type.values()).filter(t -> t.toString().equals(type)).findFirst().orElse(null);
		if(chartType == null)
			throw new AssertionError("Invalid chart type.");

		int topLeftX = lines.parseInteger("top left x = %d");
		if(topLeftX < 0 || topLeftX >= Settings.GUI.tileColumns.get())
			throw new AssertionError("Invalid chart position.");
		
		int topLeftY = lines.parseInteger("top left y = %d");
		if(topLeftY < 0 || topLeftY >= Settings.GUI.tileRows.get())
			throw new AssertionError("Invalid chart position.");

		int bottomRightX = lines.parseInteger("bottom right x = %d");
		if(bottomRightX < 0 || bottomRightX >= Settings.GUI.tileColumns.get())
			throw new AssertionError("Invalid chart position.");

		int bottomRightY = lines.parseInteger("bottom right y = %d");
		if(bottomRightY < 0 || bottomRightY >= Settings.GUI.tileRows.get())
			throw new AssertionError("Invalid chart position.");
		
		Charts.forEach(existingChart -> {
			if(existingChart.intersects(topLeftX, topLeftY, bottomRightX, bottomRightY))
				throw new AssertionError("Chart overlaps an existing chart.");
		});

		Chart chart = chartType.createAt(topLeftX, topLeftY, bottomRightX, bottomRightY);
		chart.widgets.forEach(widget -> widget.importFrom(lines));
		return chart;
		
	}
	
	/**
	 * Exports a chart and all of its settings.
	 * 
	 * @param file    Destination file.
	 */
	final public void exportTo(PrintWriter file) {

		file.println("");
		file.println("\tchart type = " + name);
		file.println("\ttop left x = " + topLeftX);
		file.println("\ttop left y = " + topLeftY);
		file.println("\tbottom right x = " + bottomRightX);
		file.println("\tbottom right y = " + bottomRightY);
		widgets.forEach(widget -> widget.exportTo(file));
		
	}
	
	/**
	 * Permanently destroys this chart.
	 * If the configuration GUI is on screen, it will be closed.
	 * If this chart contains the global trigger, the global trigger will be removed. 
	 * GPU resources will be released the next time the OpenGLChartsRegion is drawn (the next vsync, if it's on screen.)
	 */
	final public void dispose() {
		
		Configure.GUI.closeIfUsedFor(this);
		if(OpenGLCharts.globalTrigger == trigger)
			OpenGLCharts.globalTrigger = null;
		OpenGLCharts.GUI.chartsToDispose.add(this);
		
	}
	
	/**
	 * Charts that create any OpenGL FBOs/textures/etc. must dispose of them when this method is called.
	 * If a chart overrides this method, it must call super.disposeGpu(gl).
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
			String title = (sampleNumber >= 0) ? "Sample " + sampleNumber + "\n" + Settings.formatTimestampToMilliseconds(timestamp) :
			                                     Settings.formatTimestampToMilliseconds(timestamp);
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
			float padding = 6f * Settings.GUI.getChartScalingFactor();
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
	
	/**
	 * Auto-scales an axis.
	 */
	public static class AutoScale {
		
		public enum Mode {SMOOTH, STICKY, JUMPY};
		private Mode mode;
		final float hysteresis;
		
		private float min = Float.MAX_VALUE;
		private float max = Float.MIN_VALUE;
		private float idealMin;
		private float idealMax;
		private long previousFrameTimestamp = System.currentTimeMillis();
		private long animationEndTimestamp  = 0;
		
		/**
		 * Creates an object that takes the true min/max values for an axis, and outputs new min/max values that re-scale the axis based on some settings.
		 * 
		 * @param mode          SMOOTH to have the axis smoothly rescale, at all times.
		 *                      STICKY to have the axis smoothly rescale, only when required.
		 *                      JUMPY  to have the axis rescale only when required, with no animation.
		 * @param hysteresis    When hitting an existing min/max, re-scale to leave this much extra room (relative to the new range),
		 *                      When 1.5*this far from an existing min/max (relative to the new range), re-scale to leave only this much room.
		 */
		public AutoScale(Mode mode, float hysteresis) {
			
			this.mode = mode;
			this.hysteresis = hysteresis;
			
		}
		
		/**
		 * Changes the autoscaling mode and sets the min and max values to their ideal values.
		 */
		public void setMode(Mode newMode) {
			
			mode = newMode;
			min = idealMin;
			max = idealMax;
			
		}
		
		/**
		 * Resets everything. This is useful if the axis is being reconfigured and you don't want the transition to be animated.
		 */
		public void reset() {
			
			min = Float.MAX_VALUE;
			max = Float.MIN_VALUE;
			
		}
		
		/**
		 * Updates state with the current min and max values. This method should be called every frame, before calling getMin() or getMax().
		 * 
		 * @param newMin    Current minimum.
		 * @param newMax    Current maximum.
		 */
		public void update(float newMin, float newMax) {
			
			long now = System.currentTimeMillis();
			
			float newRange = Math.abs(newMax - newMin);
			idealMin = newMin - (newRange * hysteresis);
			idealMax = newMax + (newRange * hysteresis);
				
			if(newMin < min) {
				min = (mode == Mode.JUMPY) ? idealMin : newMin;
				animationEndTimestamp = now + Theme.animationMilliseconds;
			} else if(newMin > min + 1.5f*hysteresis*newRange) {
				min = (mode == Mode.JUMPY) ? idealMin : min;
				animationEndTimestamp = now + Theme.animationMilliseconds;
			}
			
			if(newMax > max) {
				max = (mode == Mode.JUMPY) ? idealMax : newMax;
				animationEndTimestamp = now + Theme.animationMilliseconds;
			} else if(newMax < max - 1.5f*hysteresis*newRange) {
				max = (mode == Mode.JUMPY) ? idealMax : max;
				animationEndTimestamp = now + Theme.animationMilliseconds;
			}
			
			if(mode == Mode.SMOOTH || (mode == Mode.STICKY && animationEndTimestamp >= now)) {
				float percent = Math.clamp(2 * (float) ((now - previousFrameTimestamp) / Theme.animationMillisecondsDouble), 0, 1);
				min += (idealMin - min) * percent;
				max += (idealMax - max) * percent;
			}
				
			previousFrameTimestamp = now;
			
		}
		
		public float getMin() { return min; }
		public float getMax() { return max; }

	}

	
}