import java.util.List;
import java.util.Map;

import javax.swing.JPanel;

import com.jogamp.opengl.GL2ES3;
import com.jogamp.opengl.GL3;

public class OpenGLTimelineChart extends PositionedChart {
	
	// timeline region
	float yTimelineTickBottom;
	float yTimelineTickTop;
	float xTimelineLeft;
	float xTimelineRight;
	float timelineWidth;
	float yTimelineBottom;
	float yTimelineTop;
	float timelineHeight;
	
	// playback controls
	float yButtonsBottom;
	int yButtonsTextBaseline;
	
	long minTimestamp;
	long maxTimestamp;
	
	// time marker
	float markerWidth;
	
	// time label region
	float yTimeBaseline1;
	float yTimeBaseline2; // only used if label uses 2 lines
	float xTimeRight;
	
	BitfieldEvents events;
	
	// user settings
	WidgetCheckbox showControls;
	WidgetCheckbox showTime;
	WidgetCheckbox showTimeline;
	WidgetDatasetCheckboxes datasetsWidget;
	
	@Override public String toString() {
		
		return "Timeline";
		
	}
	
	public OpenGLTimelineChart() {
		
		datasetsWidget = new WidgetDatasetCheckboxes(null,
		                                             newBitfieldEdges  -> { datasets.setEdges(newBitfieldEdges);   events = null; },
		                                             newBitfieldLevels -> { datasets.setLevels(newBitfieldLevels); events = null; },
		                                             null,
		                                             false);
		
		showControls = new WidgetCheckbox("Show Controls", true);
		
		showTime = new WidgetCheckbox("Show Time", true);
		
		showTimeline = new WidgetCheckbox("Show Timeline", true)
		                   .onChange(isSelected -> datasetsWidget.setVisible(isSelected));
		
		widgets.add(showControls);
		widgets.add(showTime);
		widgets.add(showTimeline);
		widgets.add(datasetsWidget);
		
	}
	
	@Override public void getConfigurationGui(JPanel gui) {
		
		gui.add(Theme.newWidgetsPanel("Settings")
		             .with(showControls)
		             .with(showTime)
		             .with(showTimeline)
		             .with(datasetsWidget)
		             .getPanel());
		
	}
	
	@Override public EventHandler drawChart(GL2ES3 gl, float[] chartMatrix, int width, int height, long nowTimestamp, int lastSampleNumber, double zoomLevel, int mouseX, int mouseY) {
		
		EventHandler handler = null;

		boolean haveTelemetry = ConnectionsController.telemetryExists();
		if(!haveTelemetry)
			nowTimestamp = 0;
		
		minTimestamp = haveTelemetry ? ConnectionsController.getFirstTimestamp() : 0;
		maxTimestamp = haveTelemetry ? ConnectionsController.getLastTimestamp()  : 0;
		if(OpenGLChartsView.instance.isLiveView())
			maxTimestamp = nowTimestamp;
		
		boolean twoLineTimestamps = SettingsView.isTimeFormatTwoLines();
		String timeText = haveTelemetry ? SettingsView.formatTimestampToMilliseconds(nowTimestamp) : "[waiting for telemetry]";
		String[] timeTextLine = timeText.split("\n");
		boolean useTwoLines = haveTelemetry ? twoLineTimestamps && OpenGL.largeTextWidth(gl, timeText.replace('\n', ' ')) > (width - 2*Theme.tilePadding) : false;
		float timeHeight = useTwoLines ? 2.3f * OpenGL.largeTextHeight : OpenGL.largeTextHeight;
		
		if(showControls.isTrue()) {
			
			// x and y locations
			float buttonSize = OpenGL.largeTextHeight + 2 * Theme.tilePadding;
			yButtonsBottom = height - Theme.tilePadding - buttonSize;
			if(showTimeline.isFalse() && showTime.isFalse())
				yButtonsBottom = (height / 2f) - (buttonSize / 2f);
			else if(showTimeline.isFalse() && showTime.isTrue())
				yButtonsBottom = (height / 2f) - (buttonSize / 2f) + (Theme.tilePadding / 2f) + (timeHeight / 2f);
				
			yButtonsTextBaseline = (int) (yButtonsBottom + Theme.tilePadding);
			float yButtonsTop = yButtonsBottom + buttonSize;
			float xCenter = width / 2f;
			float xBeginButtonLeft   = xCenter - (0.5f * buttonSize) - Theme.tilePadding - buttonSize - Theme.tilePadding - buttonSize;
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
			
			if(OpenGLChartsView.playSpeed > 1) {
				String s = Integer.toString(OpenGLChartsView.playSpeed);
				float w = OpenGL.smallTextWidth(gl, s);
				OpenGL.drawSmallText(gl, s, (int) (xPlayButtonRight - Theme.lineWidth - w), (int) (yButtonsBottom + 2*Theme.lineWidth), 0);
			} else if(OpenGLChartsView.playSpeed < -1) {
				String s = Integer.toString(-1 * OpenGLChartsView.playSpeed);
				OpenGL.drawSmallText(gl, s, (int) (xRewindButtonLeft + Theme.lineWidth), (int) (yButtonsBottom + 2*Theme.lineWidth), 0);
			}
			
			// outline a button if the mouse is over it
			if(mouseX >= xBeginButtonLeft && mouseX <= xBeginButtonRight && mouseY >= yButtonsBottom && mouseY <= yButtonsTop) {
				OpenGL.drawBoxOutline(gl, Theme.tickLinesColor, xBeginButtonLeft, yButtonsBottom, buttonSize, buttonSize);
				handler = EventHandler.onPress(event -> OpenGLChartsView.instance.setPaused(ConnectionsController.getFirstTimestamp(), null, 0));
			} else if(mouseX >= xRewindButtonLeft && mouseX <= xRewindButtonRight && mouseY >= yButtonsBottom && mouseY <= yButtonsTop) {
				OpenGL.drawBoxOutline(gl, Theme.tickLinesColor, xRewindButtonLeft, yButtonsBottom, buttonSize, buttonSize);
				handler = EventHandler.onPress(event -> OpenGLChartsView.instance.setPlayBackwards());
			} else if(mouseX >= xPauseButtonLeft && mouseX <= xPauseButtonRight && mouseY >= yButtonsBottom && mouseY <= yButtonsTop) {
				OpenGL.drawBoxOutline(gl, Theme.tickLinesColor, xPauseButtonLeft, yButtonsBottom, buttonSize, buttonSize);
				long now = nowTimestamp;
				handler = EventHandler.onPress(event -> OpenGLChartsView.instance.setPaused(now, null, 0));
			} else if(mouseX >= xPlayButtonLeft && mouseX <= xPlayButtonRight && mouseY >= yButtonsBottom && mouseY <= yButtonsTop) {
				OpenGL.drawBoxOutline(gl, Theme.tickLinesColor, xPlayButtonLeft, yButtonsBottom, buttonSize, buttonSize);
				handler = EventHandler.onPress(event -> OpenGLChartsView.instance.setPlayForwards());
			} else if(mouseX >= xEndButtonLeft && mouseX <= xEndButtonRight && mouseY >= yButtonsBottom && mouseY <= yButtonsTop) {
				OpenGL.drawBoxOutline(gl, Theme.tickLinesColor, xEndButtonLeft, yButtonsBottom, buttonSize, buttonSize);
				handler = EventHandler.onPress(event -> OpenGLChartsView.instance.setPlayLive());
			}
			
			// highlight the currently active button if the mouse is not already over a button
			if(handler == null)
				switch(OpenGLChartsView.state) {
					case REWINDING    -> OpenGL.drawBoxOutline(gl, Theme.tickLinesColor, xRewindButtonLeft, yButtonsBottom, buttonSize, buttonSize);
					case PLAYING      -> OpenGL.drawBoxOutline(gl, Theme.tickLinesColor, xPlayButtonLeft,   yButtonsBottom, buttonSize, buttonSize);
					case PLAYING_LIVE -> OpenGL.drawBoxOutline(gl, Theme.tickLinesColor, xEndButtonLeft,    yButtonsBottom, buttonSize, buttonSize);
					case PAUSED       -> { if(nowTimestamp == ConnectionsController.getFirstTimestamp())
					                           OpenGL.drawBoxOutline(gl, Theme.tickLinesColor, xBeginButtonLeft, yButtonsBottom, buttonSize, buttonSize);
					                       else
					                           OpenGL.drawBoxOutline(gl, Theme.tickLinesColor, xPauseButtonLeft,  yButtonsBottom, buttonSize, buttonSize);}
					case TRIGGERED    -> OpenGL.drawBoxOutline(gl, Theme.tickLinesColor, xEndButtonLeft,    yButtonsBottom, buttonSize, buttonSize);
				}
			
		}
		
		if(showTime.isTrue()) {
			
			float yTimeTop = showControls.isTrue() ? yButtonsBottom - Theme.tilePadding :
			                 showTimeline.isTrue() ? height - Theme.tilePadding :
			                                         (height / 2f) + (timeHeight / 2f);
			yTimeBaseline1 = yTimeTop - OpenGL.largeTextHeight;
			yTimeBaseline2 = useTwoLines ? yTimeBaseline1 - (1.3f * OpenGL.largeTextHeight) : yTimeBaseline1;
			if(yTimeBaseline2 > 0)
				if(useTwoLines) {
					float xTimeLeft1 = (width / 2) - (OpenGL.largeTextWidth(gl, timeTextLine[0]) / 2);
					float xTimeLeft2 = (width / 2) - (OpenGL.largeTextWidth(gl, timeTextLine[1]) / 2);
					OpenGL.drawLargeText(gl, timeTextLine[0], (int) xTimeLeft1, (int) yTimeBaseline1, 0);
					OpenGL.drawLargeText(gl, timeTextLine[1], (int) xTimeLeft2, (int) yTimeBaseline2, 0);
				} else {
					timeText = timeText.replace('\n', ' ');
					float xTimeLeft1 = (width / 2) - (OpenGL.largeTextWidth(gl, timeText) / 2);
					OpenGL.drawLargeText(gl, timeText, (int) xTimeLeft1, (int) yTimeBaseline1, 0);
				}
			
		}
		
		if(showTimeline.isTrue() && width > 2*Theme.tilePadding) {
			
			// x and y locations of the timeline
			float yTimelineTextBaseline = Theme.tilePadding;
			float yTimelineTextTop = yTimelineTextBaseline + OpenGL.smallTextHeight;
			if(twoLineTimestamps)
				yTimelineTextTop += 1.3 * OpenGL.smallTextHeight;
			yTimelineTickBottom = yTimelineTextTop + Theme.tickTextPadding;
			yTimelineTickTop = yTimelineTickBottom + Theme.tickLength;
			xTimelineLeft = Theme.tilePadding;
			xTimelineRight = width - Theme.tilePadding;
			timelineWidth = xTimelineRight - xTimelineLeft;
			yTimelineBottom = yTimelineTickTop;
			yTimelineTop = yTimelineBottom + (Theme.lineWidth * 2);
			timelineHeight = yTimelineTop - yTimelineBottom;
			markerWidth = 6 * ChartsController.getDisplayScalingFactor();
			
			// only draw the timeline if there's space for it
			if(yTimelineTop > height || (showTime.isTrue() && yTimelineTop > yTimeBaseline2) || (showControls.isTrue() && yTimelineTop > yButtonsBottom))
				return handler;
			
			// get the divisions
			Map<Float, String> divisions = ChartUtils.getTimestampDivisions(gl, timelineWidth, minTimestamp, maxTimestamp);
			
			// draw the tick lines
			OpenGL.buffer.rewind();
			for(Float pixelX : divisions.keySet()) {
				float x = pixelX + xTimelineLeft;
				OpenGL.buffer.put(x); OpenGL.buffer.put(yTimelineTickTop);    OpenGL.buffer.put(Theme.tickLinesColor);
				OpenGL.buffer.put(x); OpenGL.buffer.put(yTimelineTickBottom); OpenGL.buffer.put(Theme.tickLinesColor);
			}
			OpenGL.buffer.rewind();
			int vertexCount = divisions.keySet().size() * 2;
			OpenGL.drawLinesXyrgba(gl, GL3.GL_LINES, OpenGL.buffer, vertexCount);
			
			// draw the tick text
			for(Map.Entry<Float,String> entry : divisions.entrySet()) {
				if(twoLineTimestamps) {
					String[] line = entry.getValue().split("\n");
					float x1 = entry.getKey() + xTimelineLeft - (OpenGL.smallTextWidth(gl, line[0]) / 2.0f);
					float x2 = entry.getKey() + xTimelineLeft - (OpenGL.smallTextWidth(gl, line[1]) / 2.0f);
					float y1 = yTimelineTextBaseline + 1.3f * OpenGL.smallTextHeight;
					float y2 = yTimelineTextBaseline;
					OpenGL.drawSmallText(gl, line[0], (int) x1, (int) y1, 0);
					OpenGL.drawSmallText(gl, line[1], (int) x2, (int) y2, 0);
				} else {
					float x = entry.getKey() + xTimelineLeft - (OpenGL.smallTextWidth(gl, entry.getValue()) / 2.0f);
					float y = yTimelineTextBaseline;
					OpenGL.drawSmallText(gl, entry.getValue(), (int) x, (int) y, 0);
				}
			}
			
			// draw the timeline
			OpenGL.drawBox(gl, Theme.tickLinesColor, xTimelineLeft, yTimelineBottom, timelineWidth, timelineHeight);
			
			// draw a marker at the current timestamp
			float x = (float) (nowTimestamp - minTimestamp) / (float) (maxTimestamp - minTimestamp) * timelineWidth + xTimelineLeft;
			float y = yTimelineTop;
			OpenGL.drawTriangle2D(gl, Theme.tickLinesColor, x, y, x + markerWidth/2, y+markerWidth, x - markerWidth/2, y+markerWidth);
			OpenGL.drawBox(gl, Theme.tickLinesColor, x - markerWidth/2, y+markerWidth, markerWidth, markerWidth);
			
			// draw any bitfield events
			if((datasets.hasEdges() || datasets.hasLevels()) && datasets.connection.getSampleCount() > 0) {
				int[] originalScissorArgs = new int[4];
				gl.glGetIntegerv(GL3.GL_SCISSOR_BOX, originalScissorArgs, 0);
				gl.glScissor(originalScissorArgs[0] + (int) xTimelineLeft, originalScissorArgs[1] + (int) (y + 2*markerWidth), (int) timelineWidth, (int) (height - yTimelineBottom));
				int trueLastSampleNumber = datasets.connection.getSampleCount() - 1;
				if(events == null)
					events = new BitfieldEvents(false, false, datasets, 0, trueLastSampleNumber);
				else
					events.update(0, trueLastSampleNumber);
				long min = minTimestamp;
				long max = maxTimestamp;
				List<BitfieldEvents.EdgeMarker>  edgeMarkers  = events.getEdgeMarkersMillisecondsMode(min, max - min, (int) timelineWidth);
				List<BitfieldEvents.LevelMarker> levelMarkers = events.getLevelMarkersMillisecondsMode(min, max - min, (int) timelineWidth);
				EventHandler h = ChartUtils.drawMarkers(gl, datasets, edgeMarkers, levelMarkers, xTimelineLeft, showTime.isTrue() ? yTimeBaseline2 - Theme.tickTextPadding - Theme.lineWidth : height - Theme.lineWidth, xTimelineRight, y + 2*markerWidth, mouseX, mouseY);
				if(handler == null)
					handler = h;
				gl.glScissor(originalScissorArgs[0], originalScissorArgs[1], originalScissorArgs[2], originalScissorArgs[3]);
			}
			
			// draw a tooltip if the mouse is not over a button or bitfield event
			if(handler == null && mouseX >= xTimelineLeft && mouseX <= xTimelineRight && mouseY >= 0 && mouseY <= height && haveTelemetry) {
				
				double mousePercentage = (mouseX - xTimelineLeft) / timelineWidth;
				long mouseTimestamp = minTimestamp + (long) (mousePercentage * (double) (maxTimestamp - minTimestamp));
				float anchorY = (yTimelineTop + yTimelineBottom) / 2;
				
				if(!ConnectionsController.telemetryConnections.isEmpty() && ConnectionsController.cameraConnections.isEmpty()) {

					// only telemetry connections exist, so find the closest sample number
					ConnectionsController.SampleDetails details = ConnectionsController.getClosestSampleDetailsFor(mouseTimestamp);
					
					mouseTimestamp = details.timestamp;
					Tooltip tooltip = new Tooltip();
					tooltip.addRow("Sample " + details.sampleNumber);
					if(twoLineTimestamps) {
						String[] timestampLine = SettingsView.formatTimestampToMilliseconds(mouseTimestamp).split("\n");
						tooltip.addRow(timestampLine[0]);
						tooltip.addRow(timestampLine[1], anchorY);
					} else {
						tooltip.addRow(SettingsView.formatTimestampToMilliseconds(mouseTimestamp), anchorY);
					}
					float tooltipX = (float) (mouseTimestamp - minTimestamp) / (float) (maxTimestamp - minTimestamp) * timelineWidth + xTimelineLeft;
					tooltip.draw(gl, tooltipX, mouseX, mouseY, 0, height, width, 0);
					
				} else {
					
					// cameras exist, so find the closest timestamp
					float tooltipX = (float) (mouseTimestamp - minTimestamp) / (float) (maxTimestamp - minTimestamp) * timelineWidth + xTimelineLeft;
					Tooltip tooltip = new Tooltip();
					if(twoLineTimestamps) {
						String[] timestampLine = SettingsView.formatTimestampToMilliseconds(mouseTimestamp).split("\n");
						tooltip.addRow(timestampLine[0]);
						tooltip.addRow(timestampLine[1], anchorY);
					} else {
						tooltip.addRow(SettingsView.formatTimestampToMilliseconds(mouseTimestamp), anchorY);
					}
					tooltip.draw(gl, tooltipX, mouseX, mouseY, 0, height, width, 0);
					
				}

				handler = EventHandler.onPressOrDrag(null, newMouseLocation -> {
						if(newMouseLocation.x < xTimelineLeft)
							newMouseLocation.x = (int) xTimelineLeft;
						if(newMouseLocation.x > xTimelineRight)
							newMouseLocation.x = (int) xTimelineRight;
							
						double newMousePercentage = (newMouseLocation.x - xTimelineLeft) / timelineWidth;
						long newMouseTimestamp = minTimestamp + (long) (newMousePercentage * (double) (maxTimestamp - minTimestamp));
						
						if(!ConnectionsController.telemetryConnections.isEmpty() && ConnectionsController.cameraConnections.isEmpty()) {
							
							// only telemetry connections exist, so find the closest sample number
							ConnectionsController.SampleDetails details = ConnectionsController.getClosestSampleDetailsFor(newMouseTimestamp);
							OpenGLChartsView.instance.setPaused(details.timestamp, details.connection, details.sampleNumber);
							
						} else {
							
							// cameras exist, so use the timestamp
							OpenGLChartsView.instance.setPaused(newMouseTimestamp, null, 0);
							
						}
					},
					null,
					this,
					Theme.clickableCursor);

			}
			
		}
		
		return handler;
		
	}

}
