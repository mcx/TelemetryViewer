import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.List;
import javax.swing.JPanel;
import com.jogamp.opengl.GL2ES3;
import com.jogamp.opengl.GL3;

public class OpenGLTimeDomainChart extends Chart {
	
	AutoScale autoscale;
	
	// for the trigger
	float earlierPlotMaxY =  1;
	float earlierPlotMinY = -1;
	boolean mouseOverTriggerMarkers = false;
	
	// for cached mode
	int[] fbHandle;
	int[] texHandle;
	List<Field>                cachedNormalDatasets;
	List<Field.Bitfield.State> cachedEdgeStates;
	List<Field.Bitfield.State> cachedLevelStates;
	long  cachedPlotMinX;
	long  cachedPlotMaxX;
	float cachedPlotMinY;
	float cachedPlotMaxY;
	long  cachedPlotDomain;
	int   cachedPlotWidth;
	int   cachedPlotHeight;
	float cachedLineWidth;
	long  cachedMaxX;
	
	public DatasetsInterface.WidgetDatasets datasetsAndDurationWidget;
	public WidgetCheckbox cacheEnabled;
	private WidgetToggleButton<OpenGLPlot.LegendStyle> legendStyle;
	private WidgetToggleButton<OpenGLPlot.AxisStyle> xAxisStyle;
	private WidgetToggleButton<OpenGLPlot.AxisStyle> yAxisStyle;
	private WidgetTextfield<Float> yAxisMinimum;
	private WidgetCheckbox yAxisMinimumAutomatic;
	private WidgetTextfield<Float> yAxisMaximum;
	private WidgetCheckbox yAxisMaximumAutomatic;
	
	protected OpenGLTimeDomainChart(String name, int x1, int y1, int x2, int y2) {
		
		super(name, x1, y1, x2, y2);
		
		autoscale = new AutoScale(AutoScale.Mode.SMOOTH, 0.10f);
		
		cacheEnabled = new WidgetCheckbox("Cached Mode", false)
		                   .onChange(isCached -> autoscale.setMode(isCached ? AutoScale.Mode.JUMPY : AutoScale.Mode.SMOOTH));
		
		legendStyle = new WidgetToggleButton<OpenGLPlot.LegendStyle>("Legend", OpenGLPlot.LegendStyle.values(), OpenGLPlot.LegendStyle.OUTER)
		                  .setExportLabel("legend style");
		
		xAxisStyle = new WidgetToggleButton<OpenGLPlot.AxisStyle>("", OpenGLPlot.AxisStyle.values(), OpenGLPlot.AxisStyle.OUTER)
		                 .setExportLabel("x-axis style");
		
		yAxisStyle = new WidgetToggleButton<OpenGLPlot.AxisStyle>("", OpenGLPlot.AxisStyle.values(), OpenGLPlot.AxisStyle.OUTER)
		                 .setExportLabel("y-axis style");
		
		yAxisMinimum = WidgetTextfield.ofFloat(-Float.MAX_VALUE, Float.MAX_VALUE, -1)
		                              .setPrefix("Minimum")
		                              .setExportLabel("y-axis minimum")
		                              .onChange((newMinimum, oldMinimum) -> {
		                                            if(newMinimum > yAxisMaximum.get())
		                                                yAxisMaximum.set(newMinimum);
		                                            return true;
		                                        });
		
		yAxisMinimumAutomatic = new WidgetCheckbox("Automatic", true)
		                            .setExportLabel("y-axis minimum automatic")
		                            .onChange(isAutomatic -> {
		                                         if(isAutomatic)
		                                             yAxisMinimum.disableWithMessage("Automatic");
		                                         else
		                                             yAxisMinimum.setEnabled(true);
		                                     });
		
		yAxisMaximum = WidgetTextfield.ofFloat(-Float.MAX_VALUE, Float.MAX_VALUE, 1)
		                              .setPrefix("Maximum")
		                              .setExportLabel("y-axis maximum")
		                              .onChange((newMaximum, oldMaximum) -> {
		                                            if(newMaximum < yAxisMinimum.get())
		                                                yAxisMinimum.set(newMaximum);
		                                            return true;
		                                        });
		
		yAxisMaximumAutomatic = new WidgetCheckbox("Automatic", true)
		                            .setExportLabel("y-axis maximum automatic")
		                            .onChange(isAutomatic -> {
		                                         if(isAutomatic)
		                                             yAxisMaximum.disableWithMessage("Automatic");
		                                         else
		                                             yAxisMaximum.setEnabled(true);
		                                     });
		
		datasetsAndDurationWidget = datasets.getCheckboxesAndButtonsWidget(newDatasets -> {
		                                         if(datasets.normalsCount() > 0) {
		                                             yAxisMinimum.setSuffix(datasets.getNormal(0).unit.get());
		                                             yAxisMaximum.setSuffix(datasets.getNormal(0).unit.get());
		                                             trigger.setDefaultChannel(datasets.getNormal(0));
		                                         } else {
		                                             yAxisMinimum.setSuffix("");
		                                             yAxisMaximum.setSuffix("");
		                                         }
		                                     },
		                                     newEdges  -> {},
		                                     newLevels -> {},
		                                     (newDurationType, newDuration) -> {
		                                         sampleCountMode  = newDurationType == DatasetsInterface.DurationUnit.SAMPLES;
		                                         duration = (int) (long) newDuration;
		                                         if(trigger != null)
		                                             trigger.resetTrigger();
		                                     },
		                                     true);
		
		trigger = new WidgetTrigger(this, null);
		
		widgets.add(datasetsAndDurationWidget);
		widgets.add(cacheEnabled);
		widgets.add(legendStyle);
		widgets.add(xAxisStyle);
		widgets.add(yAxisStyle);
		widgets.add(yAxisMinimum);
		widgets.add(yAxisMinimumAutomatic);
		widgets.add(yAxisMaximum);
		widgets.add(yAxisMaximumAutomatic);
		widgets.add(trigger);
		
	}
	
	@Override public void appendConfigurationWidgets(JPanel gui) {
		
		gui.add(Theme.newWidgetsPanel("Data")
		             .with(datasetsAndDurationWidget)
		             .with(cacheEnabled)
		             .with(legendStyle)
		             .getPanel());
		
		gui.add(Theme.newWidgetsPanel("X-Axis")
		             .with(xAxisStyle)
		             .getPanel());
		
		gui.add(Theme.newWidgetsPanel("Y-Axis")
		             .with(yAxisStyle)
		             .with(yAxisMinimum, "split 2, grow")
		             .with(yAxisMinimumAutomatic, "sizegroup 1")
		             .with(yAxisMaximum, "split 2, grow")
		             .with(yAxisMaximumAutomatic, "sizegroup 1")
		             .getPanel());
		
		boolean triggerDisabled = OpenGLCharts.globalTrigger != null && OpenGLCharts.globalTrigger != trigger;
		gui.add(Theme.newWidgetsPanel(triggerDisabled ? "Trigger [Disabled due to global trigger]" : "Trigger")
		             .with(trigger)
		             .getPanel());
		
	}
	
	@Override public EventHandler drawChart(GL2ES3 gl, float[] chartMatrix, int width, int height, long endTimestamp, int endSampleNumber, double zoomLevel, int mouseX, int mouseY) {
		
		// check for a trigger
		WidgetTrigger.Result point = trigger.checkForTrigger(endSampleNumber, endTimestamp, zoomLevel);
		endSampleNumber = point.chartEndSampleNumber();
		endTimestamp    = point.chartEndTimestamp();
		
		// determine the x-axis range
		long plotDomain = Math.max(1, Math.round(duration * zoomLevel));                 // enforce at least 2 samples or 1 millisecond
		long plotMaxX   = Math.max(0, sampleCountMode ? endSampleNumber : endTimestamp); // enforce no rewinding before 0
		long plotMinX   = plotMaxX - plotDomain;
		
		// determine which samples to draw
		int sampleCount = datasets.hasAnyType() ? datasets.connection.getSampleCount() : 0;
		long maxSampleNumber = (sampleCount > 0 &&  sampleCountMode) ? Long.min(plotMaxX, sampleCount - 1) :
		                       (sampleCount > 0 && !sampleCountMode) ? datasets.getClosestSampleNumberAfter(plotMaxX) :
		                                                               -1;
		long minSampleNumber = (sampleCount > 0 &&  sampleCountMode) ? Long.max(plotMinX, 0) :
		                       (sampleCount > 0 && !sampleCountMode) ? Long.max(datasets.getClosestSampleNumberAtOrBefore(plotMinX, sampleCount - 1), 0) :
		                                                               -1;
		long plotSampleCount = (sampleCount > 0) ? maxSampleNumber - minSampleNumber + 1 : 0;
		
		// determine the y-axis range
		var range = datasets.getRange((int) minSampleNumber, (int) maxSampleNumber);
		autoscale.update(range.min(), range.max());
		float plotMinY = (trigger.isEnabled() && trigger.isPaused()) ? earlierPlotMinY :
		                 yAxisMinimumAutomatic.get()                 ? autoscale.getMin() :
		                	                                           yAxisMinimum.get();
		float plotMaxY = (trigger.isEnabled() && trigger.isPaused()) ? earlierPlotMaxY :
			             yAxisMaximumAutomatic.get()                 ? autoscale.getMax() :
			            	                                           yAxisMaximum.get();
		if(trigger.isEnabled() && !trigger.isPaused()) {
			earlierPlotMaxY = plotMaxY;
			earlierPlotMinY = plotMinY;
		}
		float plotRange = plotMaxY - plotMinY;
		
		// determine the axis titles
		int datasetsCount = datasets.normalsCount();
		String xAxisTitle = sampleCountMode ? "Sample Number" : "Time";
		String yAxisTitle = (datasetsCount > 0) ? datasets.getNormal(0).unit.get() : "";
		
		// draw the plot
		return new OpenGLPlot(chartMatrix, width, height, mouseX, mouseY)
		           .withLegend(legendStyle.get(), datasets)
		           .withXaxis(xAxisStyle.get(), OpenGLPlot.AxisScale.LINEAR, plotMinX, plotMaxX, xAxisTitle)
		           .withYaxis(yAxisStyle.get(), OpenGLPlot.AxisScale.LINEAR, plotMinY, plotMaxY, yAxisTitle)
		           .withPlotDrawer(plot -> {
		                if(plotSampleCount < 2)
		                    return null;
		                
		                if(!cacheEnabled.get()) {
		                    
		                    // cache disabled, so acquire all samples
		                    FloatBuffer   bufferX = sampleCountMode ? null : datasets.getTimestampsBuffer((int) minSampleNumber, (int) maxSampleNumber, plotMinX);
		                    FloatBuffer[] bufferY = new FloatBuffer[datasetsCount];
		                    for(int i = 0; i < datasetsCount; i++)
		                        bufferY[i] = datasets.getSamplesBuffer(datasets.getNormal(i), (int) minSampleNumber, (int) maxSampleNumber);
		                    
		                    // adjust so: x = (x - plotMinX) /    domain * plotWidth;
		                    // adjust so: y = (y - plotMinY) / plotRange * plotHeight;
		                    // edit: now doing the "x - plotMinX" part before putting data into the buffers, to improve float32 precision when x is very large
		                    float[] plotMatrix2 = Arrays.copyOf(plot.matrix(), 16);
		                    OpenGL.scaleMatrix    (plotMatrix2, (float) plot.width()/plotDomain, (float) plot.height()/plotRange, 1);
		                    OpenGL.translateMatrix(plotMatrix2,                               0,                       -plotMinY, 0);
		                    OpenGL.useMatrix(gl, plotMatrix2);
		                    
		                    // draw the line plot
		                    for(int i = 0; i < datasetsCount; i++) {
		                        float[] glColor = datasets.getNormal(i).color.getGl();
		                        boolean fewSamplesOnScreen = (plot.width() / (float) plotDomain) > (2 * Theme.pointWidth);
		                        if(sampleCountMode) {
		                            OpenGL.drawLinesY(gl, GL3.GL_LINE_STRIP, glColor, bufferY[i], (int) plotSampleCount, (int) (plotMinX >= 0 ? 0 : plotMinX * -1));
		                            if(fewSamplesOnScreen)
		                                OpenGL.drawPointsY(gl, glColor, bufferY[i], (int) plotSampleCount, (int) (plotMinX >= 0 ? 0 : plotMinX * -1));
		                        } else {
		                            OpenGL.drawLinesX_Y(gl, GL3.GL_LINE_STRIP, glColor, bufferX, bufferY[i], (int) plotSampleCount);
		                            if(fewSamplesOnScreen)
		                                OpenGL.drawPointsX_Y(gl, glColor, bufferX, bufferY[i], (int) plotSampleCount);
		                        }
		                    }
		                    
		                    // switch back to the original matrix
		                    OpenGL.useMatrix(gl, plot.matrix());
		                    
		                } else {
		                	
		                    // cache enabled, so start off assuming we need to draw the entire x-axis range
		                    long firstX = plotMinX;
		                    long lastX  = plotMaxX;
		                    
		                    // if the cache can be used, reduce the x-axis draw range accordingly
		                    boolean cacheIsValid = datasets.normalDatasets.equals(cachedNormalDatasets) && // flush if datasets changed
		                                           datasets.edgeStates.equals(cachedEdgeStates) &&         // flush if datasets changed
		                                           datasets.levelStates.equals(cachedLevelStates) &&       // flush if datasets changed
		                                           (Theme.lineWidth == cachedLineWidth) &&                 // flush if display scaling changed
		                                           (plot.width() == cachedPlotWidth) &&                    // flush if plot size changed
		                                           (plot.height() == cachedPlotHeight) &&                  // flush if plot size changed
		                                           (plotMinX < cachedPlotMaxX) &&                          // flush if rewound to before cached data
		                                           (plotMaxX > cachedPlotMinX) &&                          // flush if advanced to after cached data
		                                           (plotMinY == cachedPlotMinY) &&                         // flush if y-axis range changed
		                                           (plotMaxY == cachedPlotMaxY) &&                         // flush if y-axis range changed
		                                           (plotDomain == cachedPlotDomain) &&                     // flush if zoom changed
		                                           (fbHandle != null) &&                                   // flush if cache doesn't even exist
		                                           (texHandle != null);                                    // flush if cache doesn't even exist
		                    
		                    if(cacheIsValid) {
		                        if(firstX == cachedPlotMinX && lastX <= cachedPlotMaxX) {
		                            // no change, nothing to draw
		                            firstX = lastX;
		                        } else if(firstX > cachedPlotMinX) {
		                            // moving forward in time
		                            firstX = cachedPlotMaxX;
		                        } else if(firstX < cachedPlotMinX) {
		                            // moving backwards in time
		                            lastX = cachedPlotMinX;
		                        } else if(firstX == cachedPlotMinX && lastX > cachedPlotMaxX) {
		                            // moving forward in time while x=0 is still on screen
		                            firstX = cachedPlotMaxX;
		                        } else {
		                            // moving backwards in time while x=0 is still on screen, nothing to draw
		                            firstX = lastX;
		                        }
		                    }
		                    
		                    // further reduce the x-axis draw range to sample numbers or timestamps that actually exist
		                    long firstValidX = sampleCountMode ? 0               : datasets.connection.getFirstTimestamp();
		                    long  lastValidX = sampleCountMode ? maxSampleNumber : datasets.getTimestamp((int) maxSampleNumber);
		                    firstX = Math.clamp(firstX, firstValidX, lastValidX);
		                    lastX  = Math.clamp(lastX,  firstValidX, lastValidX);
		                    
		                    // it's possible for plotMaxX to be in the future (when triggering or >1 connection)
		                    // so we may need to grow the x-axis draw range to start where the *previous* draw ended
		                    if(cacheIsValid && firstX > cachedMaxX)
		                    	firstX = cachedMaxX;
		                    
		                    // the texture is used as a ring buffer. since the pixels wrap around from the right edge back to the left edge,
		                    // we may need to split the rendering into 2 draw calls (splitting it at the right edge of the texture)
		                    long xAmountElapsed = plotMaxX - firstValidX;
		                    long xSplittingValue = xAmountElapsed - (xAmountElapsed % plotDomain) + firstValidX;
		                    
		                    record DrawDetails(int[] scissor, long xOffset, FloatBuffer bufferX, FloatBuffer[] buffersY) {
		                    	
		                    	public static DrawDetails calculate(long firstX, long lastX, long firstValidX, long lastValidX, long plotDomain, int plotWidth, int plotHeight, float lineWidth, boolean sampleCountMode, DatasetsInterface datasets) {
		                    		
				                    // in theory, we only need to draw from firstX to lastX, inclusive, but...
				                    
				                    // we need to draw the corresponding range of *pixels*, being sure to round down and round up to fully enclose them
				                    int firstPixel = (int) Math.floor((double) ((firstX - firstValidX) % plotDomain) / (double) plotDomain * (double) plotWidth);
				                    int lastPixel  = (int) Math.ceil ((double) ((lastX  - firstValidX) % plotDomain) / (double) plotDomain * (double) plotWidth);
				                    if(lastPixel == 0) // lastX is at the wrap around point
				                    	lastPixel = plotWidth;
				                    lastPixel--; // this prevents a 1px gap at the edge of the plot, but i don't know why
				                    
				                    // and we need to draw (lineWidth/2)'s amount of extra pixels on each side to ensure the full stroke width can be drawn
				                    int halfLineWidth = (int) Math.ceil(lineWidth / 2);
				                    firstPixel -= halfLineWidth;
				                    lastPixel  += halfLineWidth;
				                    
				                    // now we know what *pixels* need to be redrawn
				                    firstPixel = Math.clamp(firstPixel, 0, plotWidth);
				                    lastPixel  = Math.clamp(lastPixel,  0, plotWidth);
				                    int[] scissor = new int[] {firstPixel, 0, (lastPixel - firstPixel), plotHeight};
			                        
			                        // we'll need to draw extra samples before and after, because adjacent samples affect the edges
			                        double unitsPerPixel = Math.ceil((double) plotDomain / (double) plotWidth); // samples or milliseconds
			                        long extraUnitsNeeded = (long) Math.ceil(unitsPerPixel * lineWidth * 2);    // samples or milliseconds
			                        
			                        // when drawing, the x values will be auto-generated, starting at 0.
			                        // but we're drawing a ring buffer, so we need to apply an offset instead of starting at 0.
			                        long xOffset = sampleCountMode ? (firstX % plotDomain) - extraUnitsNeeded:
			                                                         firstValidX + ((firstX - firstValidX) / plotDomain * plotDomain);
			                        
			                        firstX = Guava.saturatedSubtract(firstX, extraUnitsNeeded);
			                        lastX  = Math.min(lastValidX,  Guava.saturatedAdd(lastX, extraUnitsNeeded));
			                        if(firstX < firstValidX) {
			                            firstX = firstValidX;
			                            xOffset = firstValidX;
			                        }
			                        
			                        // acquire the samples
			                        int firstSampleNumber = sampleCountMode ? (int) firstX : datasets.getClosestSampleNumberAtOrBefore(firstX, (int) lastValidX);
			                        int  lastSampleNumber = sampleCountMode ? (int)  lastX : datasets.getClosestSampleNumberAfter(lastX);
			                        FloatBuffer bufferX   = sampleCountMode ?         null : datasets.getTimestampsBuffer(firstSampleNumber, lastSampleNumber, xOffset);
			                        FloatBuffer[] buffersY = new FloatBuffer[datasets.normalsCount()];
			                        for(int i = 0; i < datasets.normalsCount(); i++)
			                            buffersY[i] = datasets.getSamplesBuffer(datasets.getNormal(i), firstSampleNumber, lastSampleNumber);
				                    
				                    return new DrawDetails(scissor, xOffset, bufferX, buffersY);
				                    
		                    	}
		                    	
		                    }
		                    
		                    // get the samples
		                    DrawDetails draw1 = null;
		                    DrawDetails draw2 = null;
		                    
		                    if(firstX == lastX) {
		                        
		                        // nothing to draw
		                        
		                    } else if(lastX <= xSplittingValue || firstX >= xSplittingValue) {
		                    	
		                    	// only 1 draw call needed
		                    	draw1 = DrawDetails.calculate(firstX, lastX, firstValidX, lastValidX, plotDomain, plot.width(), plot.height(), Theme.lineWidth, sampleCountMode, datasets);
		                        cachedMaxX = Math.min(lastValidX, plotMaxX);
		                        
		                    } else {
		                    	
		                        // 2 draw calls required because we need to wrap around the ring buffer
		                    	draw1 = DrawDetails.calculate(firstX, xSplittingValue, firstValidX, lastValidX, plotDomain, plot.width(), plot.height(), Theme.lineWidth, sampleCountMode, datasets);
		                    	draw2 = DrawDetails.calculate(xSplittingValue, lastX, firstValidX, lastValidX, plotDomain, plot.width(), plot.height(), Theme.lineWidth, sampleCountMode, datasets);
		                        cachedMaxX = Math.min(lastValidX, plotMaxX);
		                        
		                    }
		                    
		                    // update the cache state
		                    cachedNormalDatasets = List.copyOf(datasets.normalDatasets);
		                    cachedEdgeStates     = List.copyOf(datasets.edgeStates);
		                    cachedLevelStates    = List.copyOf(datasets.levelStates);
		                    cachedLineWidth      = Theme.lineWidth;
		                    cachedPlotWidth      = plot.width();
		                    cachedPlotHeight     = plot.height();
		                    cachedPlotMinX       = plotMinX;
		                    cachedPlotMaxX       = plotMaxX;
		                    cachedPlotMinY       = plotMinY;
		                    cachedPlotMaxY       = plotMaxY;
		                    cachedPlotDomain     = plotDomain;
		                    
		                    // create the off-screen framebuffer if this is the first draw call
		                    if(fbHandle == null) {
		                        fbHandle = new int[1];
		                        texHandle = new int[1];
		                        OpenGL.createOffscreenFramebuffer(gl, fbHandle, texHandle);
		                    }
		                    
		                    // draw on the off-screen framebuffer
		                    float[] offscreenMatrix = new float[16];
		                    OpenGL.makeOrthoMatrix(offscreenMatrix, 0, plot.width(), 0, plot.height(), -1, 1);
		                    OpenGL.startDrawingOffscreen(gl, offscreenMatrix, fbHandle, texHandle, plot.width(), plot.height(), !cacheIsValid);
		                    
		                    // erase the invalid parts of the framebuffer
		                    gl.glClearColor(0, 0, 0, 0);
		                    if(plotMinX < firstValidX) {
		                        // if x<firstSample is on screen, erase the x<firstSample region because it may have old data on it
		                        int[] args = calculateScissorArgs(plotMaxX, plotMaxX + plotDomain, plotDomain, plot.width(), plot.height());
		                        gl.glScissor(args[0], args[1], args[2], args[3]);
		                        gl.glClear(GL3.GL_COLOR_BUFFER_BIT);
		                    }
		                    if(plotMaxX > lastValidX) {
		                        // if x>lastSample is on screen, erase the x>lastSample region because it may have old data on it
		                        int[] args = calculateScissorArgs(lastValidX, plotMaxX, plotDomain, plot.width(), plot.height());
		                        gl.glScissor(args[0], args[1], args[2], args[3]);
		                        gl.glClear(GL3.GL_COLOR_BUFFER_BIT);
		                        if((plotMaxX - firstValidX) % plotDomain < (lastValidX - firstValidX) % plotDomain) {
		                            args = calculateScissorArgs(plotMaxX - ((plotMaxX - firstValidX) % plotDomain), plotMaxX, plotDomain, plot.width(), plot.height());
		                            gl.glScissor(args[0], args[1], args[2], args[3]);
		                            gl.glClear(GL3.GL_COLOR_BUFFER_BIT);
		                        }
		                    }
		                    if(draw1 != null) {
                                gl.glScissor(draw1.scissor[0], draw1.scissor[1], draw1.scissor[2], draw1.scissor[3]);
                                gl.glClear(GL3.GL_COLOR_BUFFER_BIT);
		                    }
		                    if(draw2 != null) {
                                gl.glScissor(draw2.scissor[0], draw2.scissor[1], draw2.scissor[2], draw2.scissor[3]);
                                gl.glClear(GL3.GL_COLOR_BUFFER_BIT);
		                    }
		                    
		                    // adjust so: x = (x - plotMinX) / domain    * plotWidth;
		                    // adjust so: y = (y - plotMinY) / plotRange * plotHeight;
		                    // edit: now doing the "x - plotMinX" part before putting data into the buffers, to improve float32 precision when x is very large
		                    OpenGL.scaleMatrix    (offscreenMatrix, (float) plot.width()/plotDomain, (float) plot.height()/plotRange, 1);
		                    OpenGL.translateMatrix(offscreenMatrix,                               0,                       -plotMinY, 0);
		                    OpenGL.useMatrix(gl, offscreenMatrix);
		                    
		                    // draw each dataset
		                    if(draw1 != null || draw2 != null) {
		                        boolean fewSamplesOnScreen = (plot.width() / (float) plotDomain) > (2 * Theme.pointWidth);
		                        for(int i = 0; i < datasetsCount; i++) {
		                            float[] glColor = datasets.getNormal(i).color.getGl();
		                            if(draw1 != null) {
		                                gl.glScissor(draw1.scissor[0], draw1.scissor[1], draw1.scissor[2], draw1.scissor[3]);
		                                if(sampleCountMode) {
		                                    OpenGL.drawLinesY(gl, GL3.GL_LINE_STRIP, glColor, draw1.buffersY[i], draw1.buffersY[i].capacity(), (int) draw1.xOffset);
		                                    if(fewSamplesOnScreen)
		                                        OpenGL.drawPointsY(gl, glColor, draw1.buffersY[i], draw1.buffersY[i].capacity(), (int) draw1.xOffset);
		                                } else {
		                                    OpenGL.drawLinesX_Y(gl, GL3.GL_LINE_STRIP, glColor, draw1.bufferX, draw1.buffersY[i], draw1.buffersY[i].capacity());
		                                    if(fewSamplesOnScreen)
		                                        OpenGL.drawPointsX_Y(gl, glColor, draw1.bufferX, draw1.buffersY[i], draw1.buffersY[i].capacity());
		                                }
		                            }
		                            if(draw2 != null) {
		                                gl.glScissor(draw2.scissor[0], draw2.scissor[1], draw2.scissor[2], draw2.scissor[3]);
		                                if(sampleCountMode) {
		                                    OpenGL.drawLinesY(gl, GL3.GL_LINE_STRIP, glColor, draw2.buffersY[i], draw2.buffersY[i].capacity(), (int) draw2.xOffset);
		                                    if(fewSamplesOnScreen)
		                                        OpenGL.drawPointsY(gl, glColor, draw2.buffersY[i], draw2.buffersY[i].capacity(), (int) draw2.xOffset);
		                                } else {
		                                    OpenGL.drawLinesX_Y(gl, GL3.GL_LINE_STRIP, glColor, draw2.bufferX, draw2.buffersY[i], draw2.buffersY[i].capacity());
		                                    if(fewSamplesOnScreen)
		                                        OpenGL.drawPointsX_Y(gl, glColor, draw2.bufferX, draw2.buffersY[i], draw2.buffersY[i].capacity());
		                                }
		                            }
		                        }
		                    }
		                    
//		                    // draw color bars at the bottom edge of the plot to indicate draw call regions
//		                    gl.glDisable(GL3.GL_SCISSOR_TEST);
//		                    OpenGL.makeOrthoMatrix(offscreenMatrix, 0, plot.width(), 0, plot.height(), -1, 1);
//		                    OpenGL.useMatrix(gl, offscreenMatrix);
//		                    float[] randomColor1 = new float[] {(float) Math.random(), (float) Math.random(), (float) Math.random(), 0.5f};
//		                    float[] randomColor2 = new float[] {(float) Math.random(), (float) Math.random(), (float) Math.random(), 0.5f};
//		                    if(draw1 != null)
//		                        OpenGL.drawBox(gl, randomColor1, draw1.scissor[0], 0, draw1.scissor[2], 10);
//		                    if(draw2 != null)
//		                        OpenGL.drawBox(gl, randomColor2, draw2.scissor[0], 0, draw2.scissor[2], 10);
//		                    gl.glEnable(GL3.GL_SCISSOR_TEST);
		                    
		                    // switch back to the screen framebuffer and draw the texture on screen
		                    OpenGL.stopDrawingOffscreen(gl, plot.matrix());
		                    int firstPixel = (int) Math.floor((double) ((plotMinX - firstValidX) % plotDomain) / (double) plotDomain * (double) plot.width());
		                    float firstPixelPercentage = (float) firstPixel / (float) plot.width();
		                    if(firstPixelPercentage < 0)
		                    	firstPixelPercentage += 1; // because modulo returns a negative number when plotMinX is negative
		                    OpenGL.drawRingbufferTexturedBox(gl, texHandle, 0, 0, plot.width(), plot.height(), firstPixelPercentage);
		                    
//		                    // draw the framebuffer without ringbuffer wrapping, 10 pixels above the plot
//		                    gl.glDisable(GL3.GL_SCISSOR_TEST);
//		                    OpenGL.drawTexturedBox(gl, texHandle, true, 0, plot.height() + 10, plot.width(), plot.height(), 0, false);
//		                    gl.glEnable(GL3.GL_SCISSOR_TEST);
		                    
		                }
		                
		                // draw any bitfield events
		                datasets.drawBitfields(gl, plot.mouseX(), plot.mouseY(), plot.width(), plot.height(), sampleCountMode, plotMinX, plotDomain, minSampleNumber, maxSampleNumber, false);
		                
		                // draw the trigger level and trigger point markers
		                EventHandler handler = null;
		                mouseOverTriggerMarkers = false;
		                if(trigger.isEnabled()) {
		                    
		                    float scalar = Settings.GUI.getChartScalingFactor();
		                    float markerThickness = 3*scalar;
		                    float markerLength = 5*scalar;
		                    float yTriggerLevel = (trigger.level.get() - plotMinY) / (plotMaxY - plotMinY) * plot.height();
		                    int triggeredSampleNumber = point.triggeredSampleNumber();
		                    float xTriggerPoint = triggeredSampleNumber >= 0 ? getPixelXforSampleNumber(triggeredSampleNumber, plot.width(), plotMinX, plotDomain) : 0;
		                    
		                    // trigger level marker is only drawn if the trigger channel is a normal dataset
		                    if(yTriggerLevel >= 0 && yTriggerLevel <= plot.height() && trigger.normalDataset != null) {
		                        if(plot.mouseX() >= 0 && plot.mouseX() <= markerLength*1.5 && plot.mouseY() >= yTriggerLevel - markerThickness*1.5 && plot.mouseY() <= yTriggerLevel + markerThickness*1.5) {
		                            mouseOverTriggerMarkers = true;
		                            handler = EventHandler.onPressOrDrag(dragStarted -> trigger.setPaused(true),
		                                                                 newLocation -> {
		                                                                     float newTriggerLevel = Math.clamp((newLocation.y - plot.yBottom()) / (float) plot.height() * plotRange + plotMinY, plotMinY, plotMaxY);
		                                                                     trigger.level.set(newTriggerLevel);
		                                                                 },
		                                                                 dragEnded -> trigger.setPaused(false),
		                                                                 this,
		                                                                 Theme.upDownCursor);
		                            OpenGL.drawTriangle2D(gl, Theme.plotOutlineColor, 0, yTriggerLevel + markerThickness*1.5f,
		                                                                              markerLength*1.5f, yTriggerLevel,
		                                                                              0, yTriggerLevel - markerThickness*1.5f);
		                        } else {
		                            OpenGL.drawTriangle2D(gl, Theme.plotOutlineColor, 0, yTriggerLevel + markerThickness,
		                                                                              markerLength, yTriggerLevel,
		                                                                              0, yTriggerLevel - markerThickness);
		                        }
		                    }
		                    
		                    // trigger point marker
		                    if(triggeredSampleNumber >= 0) {
		                        if(xTriggerPoint >= 0 && xTriggerPoint <= plot.width()) {
		                            if(plot.mouseX() >= xTriggerPoint - 1.5*markerThickness && plot.mouseX() <= xTriggerPoint + 1.5*markerThickness && plot.mouseY() >= plot.height() - 1.5*markerLength && plot.mouseY() <= plot.height()) {
		                                mouseOverTriggerMarkers = true;
		                                handler = EventHandler.onPressOrDrag(dragStarted -> trigger.setPaused(true),
		                                                                     newLocation -> {
		                                                                         float newPrePostRatio = Math.clamp((newLocation.x - plot.xLeft()) / (float) plot.width(), 0, 1);
		                                                                         trigger.prePostRatio.set(Math.round(newPrePostRatio * 10000));
		                                                                     },
		                                                                     dragEnded -> trigger.setPaused(false),
		                                                                     this,
		                                                                     Theme.leftRigthCursor);
		                                OpenGL.drawTriangle2D(gl, Theme.plotOutlineColor, xTriggerPoint - markerThickness*1.5f, plot.height(),
		                                                                                  xTriggerPoint + markerThickness*1.5f, plot.height(),
		                                                                                  xTriggerPoint, plot.height() - markerLength*1.5f);
		                            } else {
		                                OpenGL.drawTriangle2D(gl, Theme.plotOutlineColor, xTriggerPoint - markerThickness, plot.height(),
		                                                                                  xTriggerPoint + markerThickness, plot.height(),
		                                                                                  xTriggerPoint, plot.height() - markerLength);
		                            }
		                        }
		                    }
		                    
		                    if(mouseOverTriggerMarkers || trigger.isPaused()) {
		                    	if(trigger.normalDataset != null) {
		                    		// draw lines to the trigger level and trigger point when the user is interacting with the markers
			                        OpenGL.buffer.rewind();
			                        OpenGL.buffer.put(0);              OpenGL.buffer.put(yTriggerLevel);  OpenGL.buffer.put(Theme.tickLinesColor);
			                        OpenGL.buffer.put(xTriggerPoint);  OpenGL.buffer.put(yTriggerLevel);  OpenGL.buffer.put(Theme.tickLinesColor, 0, 3);  OpenGL.buffer.put(0.2f);
			                        OpenGL.buffer.put(xTriggerPoint);  OpenGL.buffer.put(plot.height());  OpenGL.buffer.put(Theme.tickLinesColor);
			                        OpenGL.buffer.put(xTriggerPoint);  OpenGL.buffer.put(yTriggerLevel);  OpenGL.buffer.put(Theme.tickLinesColor, 0, 3);  OpenGL.buffer.put(0.2f);
			                        OpenGL.buffer.rewind();
			                        OpenGL.drawLinesXyrgba(gl, GL3.GL_LINES, OpenGL.buffer, 4);
		                    	} else {
		                    		// draw a line to the trigger point when the user is interacting with the marker
			                        OpenGL.buffer.rewind();
			                        OpenGL.buffer.put(xTriggerPoint);  OpenGL.buffer.put(plot.height());  OpenGL.buffer.put(Theme.tickLinesColor);
			                        OpenGL.buffer.put(xTriggerPoint);  OpenGL.buffer.put(0);              OpenGL.buffer.put(Theme.tickLinesColor);
			                        OpenGL.buffer.rewind();
			                        OpenGL.drawLinesXyrgba(gl, GL3.GL_LINES, OpenGL.buffer, 2);
		                    	}
		                    }
		                    
		                }
		                
		                // done
		                return handler;
		           })
		           .withTooltipDrawer(plot -> {
		                // determine the x-axis value corresponding to mouseX
		                long mousePlotX = (long) Math.round((float) plot.mouseX() / plot.width() * plotDomain) + plotMinX;
		                
		                // sanity checks
		                if(!datasets.hasNormals() && !datasets.hasLevels())
		                    return null;
		                if(plotSampleCount < 2)
		                    return null;
		                if(mousePlotX < (sampleCountMode ? 0 : datasets.connection.getFirstTimestamp()))
		                    return null;
		                if(mouseOverTriggerMarkers || trigger.isPaused())
		                    return null;
		                
		                // determine the sample number closest to the mouse
		                int sampleNumber;
		                if(sampleCountMode) {
		                    sampleNumber = (int) Math.min(maxSampleNumber, mousePlotX);
		                } else {
		                    long closestSampleNumberBefore = datasets.getClosestSampleNumberAtOrBefore(mousePlotX, (int) maxSampleNumber - 1);
		                    long closestSampleNumberAfter = Math.min(maxSampleNumber, closestSampleNumberBefore + 1);
		                    double beforeError = (double) (((float) plot.mouseX() / plot.width()) * plotDomain) - (double) (datasets.getTimestamp((int) closestSampleNumberBefore) - plotMinX);
		                    double afterError = (double) (datasets.getTimestamp((int) closestSampleNumberAfter) - plotMinX) - (double) (((float) plot.mouseX() / plot.width()) * plotDomain);
		                    sampleNumber = (beforeError < afterError) ? (int) closestSampleNumberBefore : (int) closestSampleNumberAfter;
		                }
		                
		                // create the tooltip
		                float xAnchor = getPixelXforSampleNumber(sampleNumber, plot.width(), plotMinX, plotDomain);
		                Tooltip tooltip = new Tooltip(sampleNumber, datasets.getTimestamp(sampleNumber), xAnchor, -1);
		                datasets.normalDatasets.forEach(field -> tooltip.addRow(field.color.getGl(),
		                                                                        Theme.getFloat(datasets.getSample(field, sampleNumber), field.unit.get(), false),
		                                                                        (datasets.getSample(field, sampleNumber) - plotMinY) / plotRange * plot.height()));
		                
		                var activeLevels = datasets.levelStates.stream()
		                                                       .filter(level -> {
		                                                            int activeState = level.bitfield.getStateAt(sampleNumber, datasets.cacheFor(level.dataset));
		                                                            return level == level.bitfield.states[activeState];
		                                                        })
		                                                       .toList();
		                for(int i = 0; i < activeLevels.size(); i++) {
		                    // following 3 lines from ChartUtils.drawMarkers()
		                    float padding = 6f * Settings.GUI.getChartScalingFactor();
		                    float yBottom = padding + ((activeLevels.size() - 1 - i) * (padding + OpenGL.smallTextHeight + padding));
		                    float yTop    = yBottom + OpenGL.smallTextHeight + padding;
		                    
		                	Field.Bitfield.State state = activeLevels.get(i);
		                	tooltip.addRow(state.glColor, state.name, yTop);
		                }
		                
		                // draw the tooltip
		                tooltip.draw(gl, mouseX, mouseY, plot.width(), plot.height(), false);
		                return null;
		           })
		           .draw(gl);
		
	}
	
	/**
	 * Calculates the (x,y,w,h) arguments for glScissor() based on what region the samples will occupy in the framebuffer.
	 * 
	 * @param minX          The first x-axis value (sample number or timestamp.)
	 * @param maxX          The last  x-axis value (sample number or timestamp.)
	 * @param plotWidth     Width of the plot region, in pixels.
	 * @param plotHeight    Height of the plot region, in pixels.
	 * @return              An int[4] of {x,y,w,h}
	 */
	private int[] calculateScissorArgs(long minX, long maxX, long plotDomain, int plotWidth, int plotHeight) {
		
		// convert timestamps into milliseconds elapsed
		if(!sampleCountMode) {
			minX -= datasets.connection.getFirstTimestamp();
			maxX -= datasets.connection.getFirstTimestamp();
		}
		
		// convert the minX (sample number or milliseconds elapsed) into a pixel number on the framebuffer, keeping in mind that it's a ring buffer
		long rbSampleNumber = minX % plotDomain;
		int rbPixelX = (int) (rbSampleNumber * plotWidth / plotDomain);
		
		// convert the range (sample count or milliseconds) into a pixel count
		int pixelWidth = (int) Math.ceil((double) (maxX - minX) * (double) plotWidth / (double) plotDomain);
		
		int[] args = new int[4];
		args[0] = rbPixelX;
		args[1] = 0;
		args[2] = pixelWidth;
		args[3] = plotHeight;
		return args;
		
	}
	
	/**
	 * Gets the horizontal location, relative to the plot, for a sample number.
	 * 
	 * @param sampleNumber    The sample number.
	 * @param plotWidth       Width of the plot region, in pixels.
	 * @return                Corresponding horizontal location on the plot, in pixels, with 0 = left edge of the plot.
	 */
	private float getPixelXforSampleNumber(long sampleNumber, float plotWidth, long plotMinX, long plotDomain) {
		
		return sampleCountMode ? (float) (sampleNumber - plotMinX)                              / (float) plotDomain * plotWidth :
		                         (float) (datasets.getTimestamp((int) sampleNumber) - plotMinX) / (float) plotDomain * plotWidth;
		
	}
	
	@Override public void disposeGpu(GL2ES3 gl) {
		super.disposeGpu(gl);
		if(texHandle != null && fbHandle != null) {
			gl.glDeleteTextures(1, texHandle, 0);
			texHandle = null;
			gl.glDeleteFramebuffers(1, fbHandle, 0);
			fbHandle = null;
		}
	}

}
