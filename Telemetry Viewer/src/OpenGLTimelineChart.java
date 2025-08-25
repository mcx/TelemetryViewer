import javax.swing.JPanel;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2ES3;

public class OpenGLTimelineChart extends Chart {
	
	private WidgetCheckbox showControls;
	private WidgetCheckbox showTime;
	private WidgetCheckbox showTimeline;
	private DatasetsInterface.WidgetDatasets datasetsWidget;
	
	// these need to be fields so the mouse event handler can see the *current* values
	// otherwise the handler will only see the values from when the handler was defined, and click-and-dragging will not work as expected!
	long minTimestamp;
	long maxTimestamp;
	long plotDomain;
	
	protected OpenGLTimelineChart(String name, int x1, int y1, int x2, int y2) {
		
		super(name, x1, y1, x2, y2);
		
		datasetsWidget = datasets.getButtonsWidget(newEdge   -> {},
		                                           newLevels -> {});
		
		showControls = new WidgetCheckbox("Show Controls", true);
		
		showTime = new WidgetCheckbox("Show Time", true);
		
		showTimeline = new WidgetCheckbox("Show Timeline", true)
		                   .onChange(isSelected -> datasetsWidget.setVisible(isSelected));
		
		widgets.add(showControls);
		widgets.add(showTime);
		widgets.add(showTimeline);
		widgets.add(datasetsWidget);
		
	}
	
	@Override public void appendConfigurationWidgets(JPanel gui) {
		
		gui.add(Theme.newWidgetsPanel("Settings")
		             .with(showControls)
		             .with(showTime)
		             .with(showTimeline)
		             .with(datasetsWidget)
		             .getPanel());
		
	}
	
	@Override public EventHandler drawChart(GL2ES3 gl, float[] chartMatrix, int width, int height, long nowTimestamp, int lastSampleNumber, double zoomLevel, int mouseX, int mouseY) {
		
		WidgetTrigger.Result triggerDetails = OpenGLCharts.GUI.triggerDetails;

		// determine the x-axis range
		boolean haveTelemetry = Connections.telemetryExists();
		minTimestamp = haveTelemetry ? Connections.getFirstTimestamp() : 0;
		maxTimestamp = haveTelemetry ? Connections.getLastTimestamp()  : 0;
		plotDomain = maxTimestamp - minTimestamp;
		
		float timelineThickness = Theme.lineWidth*2;
		
		return new OpenGLPlot(chartMatrix, width, height, mouseX, mouseY)
		                      .withXaxis(showTimeline.isTrue() ? OpenGLPlot.AxisStyle.OUTER : OpenGLPlot.AxisStyle.HIDDEN, OpenGLPlot.AxisScale.LINEAR, minTimestamp, maxTimestamp, "Time")
		                      .withPlotDrawer(plot -> {
		                           EventHandler handler = null;
		                           float yTop = plot.height();
		                           
		                           String timeText = !haveTelemetry                      ? "[waiting for telemetry]" :
		                                             triggerDetails.connection() == null ? Settings.formatTimestampToMilliseconds(nowTimestamp) :
		                                             triggerDetails.isTriggered()        ? "Triggered " + Settings.formatTimestampToMilliseconds(nowTimestamp) :
		                                                                                   "[Not Triggered] " + Settings.formatTimestampToMilliseconds(triggerDetails.nonTriggeredEndTimestamp());
		                           
		                           boolean useTwoLines = haveTelemetry ? Settings.isTimeFormatTwoLines() && OpenGL.largeTextWidth(gl, timeText.replace('\n', ' ')) > plot.width() : false;
		                           float timeHeight = useTwoLines ? 2.3f * OpenGL.largeTextHeight : OpenGL.largeTextHeight;
		                           
		                           if(showControls.isTrue()) {
		                               // x and y locations
		                               float buttonSize = OpenGL.largeTextHeight + 2 * Theme.tilePadding;
		                               float yButtonsBottom = plot.height() - Theme.tilePadding - buttonSize;
		                               if(showTimeline.isFalse() && showTime.isFalse())
		                                   yButtonsBottom = (plot.height() / 2f) - (buttonSize / 2f);
		                               else if(showTimeline.isFalse() && showTime.isTrue())
		                                   yButtonsBottom = (plot.height() / 2f) - (buttonSize / 2f) + (Theme.tilePadding / 2f) + (timeHeight / 2f);
		                               
		                               float yButtonsTop = yButtonsBottom + buttonSize;
		                               float xBeginButtonLeft   = (plot.width() / 2f) - (0.5f * buttonSize) - Theme.tilePadding - buttonSize - Theme.tilePadding - buttonSize;
		                               float xBeginButtonRight  = xBeginButtonLeft + buttonSize;
		                               float xRewindButtonLeft  = xBeginButtonRight + Theme.tilePadding;
		                               float xRewindButtonRight = xRewindButtonLeft + buttonSize;
		                               float xPauseButtonLeft   = xRewindButtonRight + Theme.tilePadding;
		                               float xPauseButtonRight  = xPauseButtonLeft + buttonSize;
		                               float xPlayButtonLeft    = xPauseButtonRight + Theme.tilePadding;
		                               float xPlayButtonRight   = xPlayButtonLeft + buttonSize;
		                               float xEndButtonLeft     = xPlayButtonRight + Theme.tilePadding;
		                               float xEndButtonRight    = xEndButtonLeft + buttonSize;
		                               
		                               // draw the buttons
		                               OpenGL.drawBox(gl, Theme.legendBackgroundColor, xBeginButtonLeft,  yButtonsBottom, buttonSize, buttonSize);
		                               OpenGL.drawBox(gl, Theme.legendBackgroundColor, xRewindButtonLeft, yButtonsBottom, buttonSize, buttonSize);
		                               OpenGL.drawBox(gl, Theme.legendBackgroundColor, xPauseButtonLeft,  yButtonsBottom, buttonSize, buttonSize);
		                               OpenGL.drawBox(gl, Theme.legendBackgroundColor, xPlayButtonLeft,   yButtonsBottom, buttonSize, buttonSize);
		                               OpenGL.drawBox(gl, Theme.legendBackgroundColor, xEndButtonLeft,    yButtonsBottom, buttonSize, buttonSize);
		                               
		                               OpenGL.drawBox(gl, Theme.tickLinesColor, xBeginButtonLeft + buttonSize*3f/12f - buttonSize/10f, yButtonsBottom + buttonSize/5f, buttonSize/10f, buttonSize*3f/5f);
		                               OpenGL.drawTriangle2D(gl, Theme.tickLinesColor, xBeginButtonLeft + buttonSize*3f/12f,   yButtonsBottom + buttonSize/2f,
		                                                                               xBeginButtonLeft + buttonSize*6.5f/12f, yButtonsBottom + buttonSize/5f,
		                                                                               xBeginButtonLeft + buttonSize*6.5f/12f, yButtonsBottom + buttonSize*4f/5f);
		                               OpenGL.drawTriangle2D(gl, Theme.tickLinesColor, xBeginButtonLeft + buttonSize*6.5f/12f, yButtonsBottom + buttonSize/2f,
		                                                                               xBeginButtonLeft + buttonSize*10f/12f,  yButtonsBottom + buttonSize/5f,
		                                                                               xBeginButtonLeft + buttonSize*10f/12f,  yButtonsBottom + buttonSize*4f/5f);
		                               
		                               OpenGL.drawTriangle2D(gl, Theme.tickLinesColor, xRewindButtonLeft  + buttonSize/3f, yButtonsBottom + buttonSize/2f,
		                                                                               xRewindButtonRight - buttonSize/3f, yButtonsBottom + 4f/5f*buttonSize,
		                                                                               xRewindButtonRight - buttonSize/3f, yButtonsBottom + buttonSize/5f);
		                               
		                               OpenGL.drawBox(gl, Theme.tickLinesColor, xPauseButtonLeft  + buttonSize/3f, yButtonsBottom + buttonSize/4f, buttonSize/8f, buttonSize/2f);
		                               OpenGL.drawBox(gl, Theme.tickLinesColor, xPauseButtonRight - buttonSize/3f, yButtonsBottom + buttonSize/4f, -buttonSize/8f, buttonSize/2f);
		                               
		                               OpenGL.drawTriangle2D(gl, Theme.tickLinesColor, xPlayButtonLeft + buttonSize/3f,  yButtonsBottom + 4f/5f*buttonSize,
		                                                                               xPlayButtonRight - buttonSize/3f, yButtonsBottom + buttonSize/2f,
		                                                                               xPlayButtonLeft + buttonSize/3f,  yButtonsBottom + buttonSize/5f);
		                               
		                               OpenGL.drawBox(gl, Theme.tickLinesColor, xEndButtonRight - buttonSize*3f/12f + buttonSize/10f, yButtonsBottom + buttonSize/5f, -buttonSize/10f, buttonSize*3f/5f);
		                               OpenGL.drawTriangle2D(gl, Theme.tickLinesColor, xEndButtonRight - buttonSize*3f/12f,   yButtonsBottom + buttonSize/2f,
		                                                                               xEndButtonRight - buttonSize*6.5f/12f, yButtonsBottom + buttonSize/5f,
		                                                                               xEndButtonRight - buttonSize*6.5f/12f, yButtonsBottom + buttonSize*4f/5f);
		                               OpenGL.drawTriangle2D(gl, Theme.tickLinesColor, xEndButtonRight - buttonSize*6.5f/12f, yButtonsBottom + buttonSize/2f,
		                                                                               xEndButtonRight - buttonSize*10f/12f,  yButtonsBottom + buttonSize/5f,
		                                                                               xEndButtonRight - buttonSize*10f/12f,  yButtonsBottom + buttonSize*4f/5f);
		                               
		                               if(OpenGLCharts.playSpeed > 1) {
		                                   String s = Integer.toString(OpenGLCharts.playSpeed);
		                                   float w = OpenGL.smallTextWidth(gl, s);
		                                   OpenGL.drawSmallText(gl, s, (int) (xPlayButtonRight - Theme.lineWidth - w), (int) (yButtonsBottom + 2*Theme.lineWidth), 0);
		                               } else if(OpenGLCharts.playSpeed < -1) {
		                                   String s = Integer.toString(-1 * OpenGLCharts.playSpeed);
		                                   OpenGL.drawSmallText(gl, s, (int) (xRewindButtonLeft + Theme.lineWidth), (int) (yButtonsBottom + 2*Theme.lineWidth), 0);
		                               }
		                               
		                               // outline a button if the mouse is over it
		                               if(plot.mouseX() >= xBeginButtonLeft && plot.mouseX() <= xBeginButtonRight && plot.mouseY() >= yButtonsBottom && plot.mouseY() <= yButtonsTop) {
		                                   OpenGL.drawBoxOutline(gl, Theme.tickLinesColor, xBeginButtonLeft, yButtonsBottom, buttonSize, buttonSize);
		                                   handler = EventHandler.onPress(event -> OpenGLCharts.GUI.setPaused(Connections.getFirstTimestamp(), null, 0));
		                               } else if(plot.mouseX() >= xRewindButtonLeft && plot.mouseX() <= xRewindButtonRight && plot.mouseY() >= yButtonsBottom && plot.mouseY() <= yButtonsTop) {
		                                   OpenGL.drawBoxOutline(gl, Theme.tickLinesColor, xRewindButtonLeft, yButtonsBottom, buttonSize, buttonSize);
		                                   handler = EventHandler.onPress(event -> OpenGLCharts.GUI.setPlayBackwards());
		                               } else if(plot.mouseX() >= xPauseButtonLeft && plot.mouseX() <= xPauseButtonRight && plot.mouseY() >= yButtonsBottom && plot.mouseY() <= yButtonsTop) {
		                                   OpenGL.drawBoxOutline(gl, Theme.tickLinesColor, xPauseButtonLeft, yButtonsBottom, buttonSize, buttonSize);
		                                   handler = EventHandler.onPress(event -> OpenGLCharts.GUI.setPaused(triggerDetails.nonTriggeredEndTimestamp(), null, 0));
		                               } else if(plot.mouseX() >= xPlayButtonLeft && plot.mouseX() <= xPlayButtonRight && plot.mouseY() >= yButtonsBottom && plot.mouseY() <= yButtonsTop) {
		                                   OpenGL.drawBoxOutline(gl, Theme.tickLinesColor, xPlayButtonLeft, yButtonsBottom, buttonSize, buttonSize);
		                                   handler = EventHandler.onPress(event -> OpenGLCharts.GUI.setPlayForwards());
		                               } else if(plot.mouseX() >= xEndButtonLeft && plot.mouseX() <= xEndButtonRight && plot.mouseY() >= yButtonsBottom && plot.mouseY() <= yButtonsTop) {
		                                   OpenGL.drawBoxOutline(gl, Theme.tickLinesColor, xEndButtonLeft, yButtonsBottom, buttonSize, buttonSize);
		                                   handler = EventHandler.onPress(event -> OpenGLCharts.GUI.setPlayLive());
		                               }
		                               
		                               // highlight the currently active button if the mouse is not already over a button
		                               if(handler == null)
		                                   switch(OpenGLCharts.state) {
		                                       case REWINDING    -> OpenGL.drawBoxOutline(gl, Theme.tickLinesColor, xRewindButtonLeft, yButtonsBottom, buttonSize, buttonSize);
		                                       case PLAYING      -> OpenGL.drawBoxOutline(gl, Theme.tickLinesColor, xPlayButtonLeft,   yButtonsBottom, buttonSize, buttonSize);
		                                       case PLAYING_LIVE -> OpenGL.drawBoxOutline(gl, Theme.tickLinesColor, xEndButtonLeft,    yButtonsBottom, buttonSize, buttonSize);
		                                       case PAUSED       -> { if(nowTimestamp == Connections.getFirstTimestamp())
		                                                                  OpenGL.drawBoxOutline(gl, Theme.tickLinesColor, xBeginButtonLeft, yButtonsBottom, buttonSize, buttonSize);
		                                                              else
		                                                                  OpenGL.drawBoxOutline(gl, Theme.tickLinesColor, xPauseButtonLeft,  yButtonsBottom, buttonSize, buttonSize);}
		                                   }
		                               
		                               yTop = yButtonsBottom;
		                           }
		                           
		                           if(showTime.isTrue()) {
		                               float yTimeTop = showControls.isTrue() || showTimeline.isTrue() ? yTop - Theme.tilePadding : (plot.height() / 2f) + (timeHeight / 2f);
		                               float yTimeBaseline1 = yTimeTop - OpenGL.largeTextHeight;
		                               float yTimeBaseline2 = useTwoLines ? yTimeBaseline1 - (1.3f * OpenGL.largeTextHeight) : yTimeBaseline1;
		                               if(useTwoLines) {
		                                   String[] timeTextLine = timeText.split("\n");
		                                   float xTimeLeft1 = (plot.width() / 2f) - (OpenGL.largeTextWidth(gl, timeTextLine[0]) / 2);
		                                   float xTimeLeft2 = (plot.width() / 2f) - (OpenGL.largeTextWidth(gl, timeTextLine[1]) / 2);
		                                   OpenGL.drawLargeText(gl, timeTextLine[0], (int) xTimeLeft1, (int) yTimeBaseline1, 0);
		                                   OpenGL.drawLargeText(gl, timeTextLine[1], (int) xTimeLeft2, (int) yTimeBaseline2, 0);
		                               } else {
		                                   timeText = timeText.replace('\n', ' ');
		                                   float xTimeLeft1 = (plot.width() / 2f) - (OpenGL.largeTextWidth(gl, timeText) / 2);
		                                   OpenGL.drawLargeText(gl, timeText, (int) xTimeLeft1, (int) yTimeBaseline1, 0);
		                               }
		                               yTop = yTimeBaseline2;
		                           }
		                           
		                           if(showTimeline.isTrue()) {
		                               // draw any bitfield events
		                               int trueLastSampleNumber = datasets.connection == null ? -1 : datasets.connection.getSampleCount() - 1;
		                               EventHandler h = datasets.drawBitfields(gl, plot.mouseX(), plot.mouseY(), plot.width(), yTop, false, minTimestamp, plotDomain, 0, trueLastSampleNumber, true);
		                               if(handler == null)
		                                   handler = h;
		                               
		                               // draw the timeline
		                               OpenGL.drawBox(gl, Theme.tickLinesColor, 0, 0, plot.width(), timelineThickness);
		                               
		                               // draw a marker at the current (non-triggered) timestamp
		                               float markerWidth = 6 * Settings.GUI.getChartScalingFactor();
		                               float x = (float) (triggerDetails.nonTriggeredEndTimestamp() - minTimestamp) / (float) plotDomain * plot.width();
		                               float y = timelineThickness;
		                               OpenGL.drawTriangle2D(gl, Theme.tickLinesColor, x, y, x + markerWidth/2, y+markerWidth, x - markerWidth/2, y+markerWidth);
		                               OpenGL.drawBox(gl, Theme.tickLinesColor, x - markerWidth/2, y+markerWidth, markerWidth, markerWidth);
		                               
		                               // if triggered, draw a "T" at the triggered timestamp
		                               if(triggerDetails.isTriggered()) {
		                                   float xTrig = (float) (triggerDetails.triggeredTimestamp() - minTimestamp) / (float) plotDomain * plot.width();
		                                   float yTrigTop = y + markerWidth*2 + Theme.lineWidth*2;
		                                   OpenGL.buffer.rewind();
		                                   OpenGL.buffer.put(xTrig);                 OpenGL.buffer.put(y);
		                                   OpenGL.buffer.put(xTrig);                 OpenGL.buffer.put(yTrigTop);
		                                   OpenGL.buffer.put(xTrig - markerWidth/2); OpenGL.buffer.put(yTrigTop);
		                                   OpenGL.buffer.put(xTrig + markerWidth/2); OpenGL.buffer.put(yTrigTop);
		                                   OpenGL.drawLinesXy(gl, GL.GL_LINES, Theme.tickLinesColor, OpenGL.buffer.rewind(), 4);
		                               }
		                           }
		                           
		                           return handler;
		                      })
		                      .withTooltipDrawer(plot -> {
		                           // don't draw a tooltip if the mouse is already over a button or bitfield event, or if the timeline is disabled
		                           if(plot.existingHandler() != null)
		                               return null;
		                           if(showTimeline.isFalse() || !haveTelemetry)
		                               return null;
		                           
		                           double mousePercentage = (double) plot.mouseX() / plot.width();
		                           long mouseTimestamp = minTimestamp + (long) (mousePercentage * (double) plotDomain);
		                           float yAnchor = timelineThickness / 2;
		                           
		                           if(!Connections.telemetryConnections.isEmpty() && Connections.cameraConnections.isEmpty()) {
		                               // only telemetry connections exist, so find the closest sample number
		                               var details = Connections.getClosestSampleDetailsFor(mouseTimestamp);
		                               float xAnchor = (float) (details.timestamp() - minTimestamp) / (float) plotDomain * plot.width();
		                               Tooltip tooltip = new Tooltip(details.sampleNumber(), details.timestamp(), xAnchor, yAnchor);
		                               tooltip.draw(gl, plot.mouseX(), plot.mouseY(), plot.width(), plot.height());
		                           } else {
		                               // cameras exist, so find the closest timestamp
		                               float xAnchor = (float) (mouseTimestamp - minTimestamp) / (float) plotDomain * plot.width();
		                               Tooltip tooltip = new Tooltip(-1, mouseTimestamp, xAnchor, yAnchor);
		                               tooltip.draw(gl, plot.mouseX(), plot.mouseY(), plot.width(), plot.height());
		                           }
		                           
		                           return EventHandler.onPressOrDrag(null, newMouseLocation -> {
		                                   float newMouseX = Math.clamp(newMouseLocation.x - plot.xLeft(), 0, plot.width());
		                                   double newMousePercentage = newMouseX / plot.width();
		                                   long newMouseTimestamp = minTimestamp + (long) (newMousePercentage * (double) plotDomain);
		                                   
		                                   if(!Connections.telemetryConnections.isEmpty() && Connections.cameraConnections.isEmpty()) {
		                                       // only telemetry connections exist, so find the closest sample number
		                                       var details = Connections.getClosestSampleDetailsFor(newMouseTimestamp);
		                                       OpenGLCharts.GUI.setPaused(details.timestamp(), details.connection(), details.sampleNumber());
		                                   } else {
		                                       // cameras exist, so use the timestamp
		                                       OpenGLCharts.GUI.setPaused(newMouseTimestamp, null, 0);
		                                   }
		                               },
		                           null,
		                           this,
		                           Theme.clickableCursor);
		                      })
		                      .draw(gl);
		
	}

}
