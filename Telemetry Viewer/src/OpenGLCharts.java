import java.awt.BorderLayout;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.io.File;
import java.net.InetAddress;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import com.jogamp.opengl.GL2ES3;
import com.jogamp.opengl.GL3;
import com.jogamp.opengl.GLAnimatorControl;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.GLProfile;
import com.jogamp.opengl.awt.GLCanvas;
import com.jogamp.opengl.util.Animator;
import com.jogamp.opengl.util.FPSAnimator;

/**
 * Manages the grid region and all charts on the screen.
 * Users can click-and-drag in this region to create new charts or interact with existing charts.
 */
@SuppressWarnings("serial")
public class OpenGLCharts extends JPanel {
	
	static OpenGLCharts GUI = new OpenGLCharts();
	
	static boolean firstRun = true;
	
	boolean openGLproblem = false;
	int aaLevel;
	
	List<Chart> chartsToDispose = new ArrayList<Chart>();
	
	GLAnimatorControl animator;
	GLCanvas glCanvas;
	int canvasWidth;
	int canvasHeight;
	int notificationsHeight;
	float displayScalingFactor = 0;
	
	// grid size
	int tileColumns;
	int tileRows;
	
	// grid locations for the opposite corners of where a new chart will be placed
	int startX = -1;
	int startY = -1;
	int endX   = -1;
	int endY   = -1;
	
	// time and zoom settings
	// these may be accessed by different threads, so they must be volatile
	enum State {REWINDING, PAUSED, PLAYING, PLAYING_LIVE}
	public static volatile State state = State.PLAYING_LIVE;
	public static volatile int playSpeed = 0; // <0 when REWINDING, >0 when PLAYING, 0 and ignored in other states
	private long previousFrameTimestamp;
	
	private static double zoomLevel = 1;
	public static double scalingFactor = 1;
	private static long nonLiveTimestamp;
	private static ConnectionTelemetry nonLivePrimaryConnection; // if the mouse was over a chart while timeshifting, or if there was only one connection, we also track the corresponding connection and its sample number, to allow sub-millisecond time shifting.
	private static int nonLivePrimaryConnectionSampleNumber;
	public static volatile WidgetTrigger globalTrigger;
	
	// mouse pointer's current location (pixels, origin at bottom-left)
	int mouseX = -1;
	int mouseY = -1;
	EventHandler eventHandler;
	Chart chartUnderMouse;
	
	boolean maximizing;
	boolean demaximizing;
	boolean removing;
	Chart maximizedChart;
	Chart removingChart;
	long animationEndTimestamp;
	
	// benchmarks for the entire frame
	long cpuStartNanoseconds;
	long cpuStopNanoseconds;
	double previousCpuMilliseconds;
	double previousGpuMilliseconds;
	double previousFps;
	double cpuMillisecondsAccumulator;
	double gpuMillisecondsAccumulator;
	double fpsAccumulator;
	double averageCpuMilliseconds;
	double averageGpuMilliseconds;
	double averageFps;
	int count;
	long endAveragingTimestamp = System.currentTimeMillis() + 1000;
	int[] gpuQueryHandles = new int[2];
	long[] gpuTimes = new long[2];
	boolean openGLES;
	
	float[] screenMatrix = new float[16];
	
	private OpenGLCharts() {
		
		super();
		
//		System.out.println(GLProfile.glAvailabilityToString());
//		System.setProperty("jogl.debug.GLSLCode", "");
//		System.setProperty("jogl.debug.DebugGL", "");
		GLCapabilities capabilities = null;
		try {
			// try to get normal OpenGL if we're not running on a Pi (Linux AArch64)
			if(System.getProperty("os.name").toLowerCase().equals("linux") && System.getProperty("os.arch").toLowerCase().equals("aarch64"))
				throw new Exception();
			capabilities = new GLCapabilities(GLProfile.get(GLProfile.GL3));
			openGLES = false;
			aaLevel = Settings.GUI.antialiasingLevel.get();
			if(aaLevel > 1) {
				capabilities.setSampleBuffers(true);
				capabilities.setNumSamples(aaLevel);
			}
		} catch(Error | Exception e) {
			try {
				// fall back to OpenGL ES
				capabilities = new GLCapabilities(GLProfile.get(GLProfile.GLES3));
				openGLES = true;
				aaLevel = Settings.GUI.antialiasingLevel.get();
				if(aaLevel > 1) {
					capabilities.setSampleBuffers(true);
					capabilities.setNumSamples(aaLevel);
				}
			} catch(Error | Exception e2) {
				Notifications.showCriticalFault("Unable to create the OpenGL context.\nThis may be due to a graphics driver problem, or an outdated graphics card.\n\"" + e.getMessage() + "\n\n" + e2.getMessage() + "\"");
				return;
			}
		}
		
		glCanvas = new GLCanvas(capabilities);
		glCanvas.addGLEventListener(new GLEventListener() {

			@Override public void init(GLAutoDrawable drawable) {
				
				GL2ES3 gl = drawable.getGL().getGL2ES3();
			
				gl.glEnable(GL3.GL_BLEND);
				gl.glBlendFunc(GL3.GL_SRC_ALPHA, GL3.GL_ONE_MINUS_SRC_ALPHA);
				
				// disable antialiasing when using OpenGL ES, because rendering to off-screen framebuffers doesn't seem to support MSAA in OpenGL ES 3.1
				if(!gl.isGL3() && Settings.GUI.antialiasingLevel.get() > 1) {
					Settings.GUI.antialiasingLevel.set(1);
					regenerate();
					openGLproblem = true;
					return;
				}
				
				// ensure the requested AA level is supported 
				if(aaLevel > 1) {
					int[] number = new int[1];
					gl.glGetIntegerv(GL3.GL_MAX_SAMPLES, number, 0);
					if(number[0] < Settings.GUI.antialiasingLevel.get()) {
						Settings.GUI.antialiasingLevel.set(number[0]);
						Notifications.showFailureForMilliseconds("Antialiasing level " + number[0] + " is the highest level supported by your GPU.", 5000, false);
					}
				}
				
				gl.setSwapInterval(1);
				
				// GPU benchmarking is not possible with OpenGL ES
				if(!openGLES) {
					gl.glGenQueries(2, gpuQueryHandles, 0);
					gl.glQueryCounter(gpuQueryHandles[0], GL3.GL_TIMESTAMP); // insert both queries to prevent a warning on the first time they are read
					gl.glQueryCounter(gpuQueryHandles[1], GL3.GL_TIMESTAMP);
				}
				
				OpenGL.makeAllPrograms(gl);
				
				if(firstRun) {
					
					firstRun = false;
					
					int[] number = new int[2];
					StringBuilder text = new StringBuilder(65536);
					DecimalFormat thousands = new DecimalFormat("#,###.0");
					
					try {
					text.append("Hostname                     = " + InetAddress.getLocalHost().getHostName() + "\n");
					} catch(Exception e) {}
					text.append("Local IP Address             = " + ConnectionTelemetry.localIp + "\n");
					text.append("java.vm.name                 = " + System.getProperty("java.vm.name") + "\n");
					text.append("java.vm.version              = " + System.getProperty("java.vm.version") + "\n");
					text.append("java.vendor.version          = " + System.getProperty("java.vendor.version") + "\n");
					text.append("os.name                      = " + System.getProperty("os.name") + "\n");
					text.append("os.version                   = " + System.getProperty("os.version") + "\n");
					text.append("os.arch                      = " + System.getProperty("os.arch") + "\n");
					text.append("java.home                    = " + System.getProperty("java.home") + "\n");
					text.append("user.dir                     = " + System.getProperty("user.dir") + "\n");
					text.append("Runtime.maxMemory()          = " + thousands.format(Runtime.getRuntime().maxMemory() / 1000000.0) + " MB\n");
					for(File partition : File.listRoots())
						text.append(String.format("%-28s = %s MB free\n", "Drive " + partition.getAbsolutePath(), thousands.format(partition.getFreeSpace() / 1000000.0)));
					if(System.getProperty("os.name").toLowerCase().startsWith("windows")) {
						try {
							List<String> lines = Runtime.getRuntime().exec(new String[] {"wmic", "cpu", "get", "name"}).inputReader().lines().toList();
							if(lines.size() > 2)
								text.append("CPU Name                     = " + lines.get(2) + "\n");
						} catch(Exception e) {}
					} else if(System.getProperty("os.name").toLowerCase().startsWith("linux")) {
						try {
							List<String> lines = Runtime.getRuntime().exec(new String[] {"cat", "/proc/cpuinfo"}).inputReader().lines().toList();
							String pcName = lines.stream().filter(line -> line.toLowerCase().startsWith("model name"))
							                              .map(line -> line.split(": ")[1])
							                              .findFirst().orElse("");
							String piName = lines.stream().filter(line -> line.toLowerCase().startsWith("hardware") || (line.toLowerCase().startsWith("model") && !line.toLowerCase().startsWith("model name")))
							                              .map(line -> line.split(": ")[1])
							                              .collect(Collectors.joining(" "));
							text.append("CPU Name                     = " + (!pcName.isEmpty() ? pcName + "\n" :
							                                                 !piName.isEmpty() ? piName + "\n" :
							                                                                     "unknown \n"));
						} catch(Exception e) {}
					}
					text.append("CPU Logical Processors       = " + Runtime.getRuntime().availableProcessors() + "\n");
					
					                                                               text.append("GL_VENDOR                    = " + gl.glGetString(GL3.GL_VENDOR) + "\n");
					                                                               text.append("GL_RENDERER                  = " + gl.glGetString(GL3.GL_RENDERER) + "\n");
					                                                               text.append("GL_VERSION                   = " + gl.glGetString(GL3.GL_VERSION) + "\n");
					                                                               text.append("GL_SHADING_LANGUAGE_VERSION  = " + gl.glGetString(GL3.GL_SHADING_LANGUAGE_VERSION) + "\n");
					gl.glGetIntegerv(GL3.GL_MAJOR_VERSION, number, 0);             text.append("GL_MAJOR_VERSION             = " + number[0] + "\n");
					gl.glGetIntegerv(GL3.GL_MINOR_VERSION, number, 0);             text.append("GL_MINOR_VERSION             = " + number[0] + "\n");
					gl.glGetIntegerv(GL3.GL_MAX_SAMPLES, number, 0);               text.append("GL_MAX_SAMPLES               = " + number[0] + "\n");
					gl.glGetIntegerv(GL3.GL_MAX_TEXTURE_SIZE, number, 0);          text.append("GL_MAX_TEXTURE_SIZE          = " + number[0] + "\n");
					gl.glGetIntegerv(GL3.GL_MAX_RENDERBUFFER_SIZE, number, 0);     text.append("GL_MAX_RENDERBUFFER_SIZE     = " + number[0] + "\n");
					gl.glGetIntegerv(GL3.GL_MAX_VIEWPORT_DIMS, number, 0);         text.append("GL_MAX_VIEWPORT_DIMS         = " + number[0] + " x " + number[1] + "\n");
					gl.glGetIntegerv(GL3.GL_MAX_DRAW_BUFFERS, number, 0);          text.append("GL_MAX_DRAW_BUFFERS          = " + number[0] + "\n");
					gl.glGetIntegerv(GL3.GL_MAX_COLOR_TEXTURE_SAMPLES, number, 0); text.append("GL_MAX_COLOR_TEXTURE_SAMPLES = " + number[0] + "\n");
					gl.glGetIntegerv(GL3.GL_NUM_EXTENSIONS, number, 0);            text.append(number[0] + " GL EXTENSIONS: " + gl.glGetStringi(GL3.GL_EXTENSIONS, 0));
					for(int i = 1; i < number[0]; i++)                             text.append(", " + gl.glGetStringi(GL3.GL_EXTENSIONS, i));
					Notifications.printInfo("System Information:\n" + text.toString());
					
					// also reset the creation time of any existing notifications, so they properly animate into existence
					long now = System.currentTimeMillis();
					Notifications.getNotifications().forEach(notification -> notification.creationTimestamp = now);
					
				}
				
			}
						
			@Override public void reshape(GLAutoDrawable drawable, int x, int y, int width, int height) {
				
				if(openGLproblem)
					return;
				
				Theme.osDpiScalingFactor = (float) ((Graphics2D) getGraphics()).getTransform().getScaleX();
				
				GL2ES3 gl = drawable.getGL().getGL2ES3();
				gl.glViewport(0, 0, width, height);
				OpenGL.makeOrthoMatrix(screenMatrix, 0, width, 0, height, -100000, 100000);
				OpenGL.useMatrix(gl, screenMatrix);
				
				canvasWidth = width;
				canvasHeight = height;
				
			}

			@Override public void display(GLAutoDrawable drawable) {
				
				if(openGLproblem)
					return;
				
				if(eventHandler != null && !eventHandler.dragInProgress)
					eventHandler = null;
				
				if(aaLevel != Settings.GUI.antialiasingLevel.get()) {
					regenerate();
					return;
				}
				
				// prepare OpenGL
				GL2ES3 gl = drawable.getGL().getGL2ES3();
				OpenGL.useMatrix(gl, screenMatrix);
				
				// if benchmarking, calculate CPU/GPU time for the *previous frame*
				// GPU benchmarking is not possible with OpenGL ES
				if(Settings.GUI.cpuGpuMeasurementsEnabled.isTrue()) {
					previousCpuMilliseconds = (cpuStopNanoseconds - cpuStartNanoseconds) / 1000000.0;
					if(!openGLES) {
						gl.glGetQueryObjecti64v(gpuQueryHandles[0], GL3.GL_QUERY_RESULT, gpuTimes, 0);
						gl.glGetQueryObjecti64v(gpuQueryHandles[1], GL3.GL_QUERY_RESULT, gpuTimes, 1);
					}
					previousGpuMilliseconds = (gpuTimes[1] - gpuTimes[0]) / 1000000.0;
					previousFps = 1000000000.0 / (System.nanoTime() - cpuStartNanoseconds);
					cpuMillisecondsAccumulator += previousCpuMilliseconds;
					gpuMillisecondsAccumulator += previousGpuMilliseconds;
					fpsAccumulator             += previousFps;
					count++;
					if(System.currentTimeMillis() >= endAveragingTimestamp) {
						averageCpuMilliseconds = cpuMillisecondsAccumulator / count;
						averageGpuMilliseconds = gpuMillisecondsAccumulator / count;
						averageFps             =             fpsAccumulator / count;
						cpuMillisecondsAccumulator = 0;
						gpuMillisecondsAccumulator = 0;
						fpsAccumulator             = 0;
						count = 0;
						endAveragingTimestamp = System.currentTimeMillis() + 1000;
					}
					
					// start timers for *this frame*
					cpuStartNanoseconds = System.nanoTime();
					if(!openGLES)
						gl.glQueryCounter(gpuQueryHandles[0], GL3.GL_TIMESTAMP);
				}
				
				gl.glClearColor(Theme.neutralColor[0], Theme.neutralColor[1], Theme.neutralColor[2], Theme.neutralColor[3]);
				gl.glClear(GL3.GL_COLOR_BUFFER_BIT);
				
				// dispose of any GPU resources used by charts that were recently removed
				chartsToDispose.forEach(chart -> {
					chart.disposeGpu(gl);
					if(chart == maximizedChart)
						maximizedChart = null;
					if(chart == removingChart)
						removingChart = null;
				});
				chartsToDispose.clear();
				
				// update the theme if the display scaling factor has changed
				float newDisplayScalingFactor = Settings.GUI.getChartScalingFactor();
				if(displayScalingFactor != newDisplayScalingFactor) {
					Theme.initialize(gl, newDisplayScalingFactor);
					displayScalingFactor = newDisplayScalingFactor;
				}
				
				// draw any notifications
				AtomicInteger top = new AtomicInteger(canvasHeight - (int) Theme.tilePadding); // have to use forEach() below for thread-safety, and lambdas can't write to a shared integer, so using AtomicInteger
				Notifications.getNotifications().forEach(notification -> {
					int lineCount = notification.lines.length;
					if(lineCount > 6) {
						notification.lines[5] = "[ ... see console for the rest ... ]";
						lineCount = 6;
					}
					
					double progressBarPercentage = 0;
					String progressBarPercentageText = null;
					if(notification.isProgressBar) {
						progressBarPercentage = Math.clamp((double) notification.currentAmount.get() / (double) notification.totalAmount, 0, 1);
						progressBarPercentageText = String.format(" %1.1f%%", progressBarPercentage * 100.0);
					}
					
					int lineHeight = OpenGL.largeTextHeight;
					int maxLineWidth = 0;
					for(int i = 0; i < lineCount; i++) {
						int width = (int) Math.ceil(OpenGL.largeTextWidth(gl, notification.isProgressBar ? notification.lines[i] + progressBarPercentageText : notification.lines[i]));
						if(width > maxLineWidth)
							maxLineWidth = width;
					}
					if(maxLineWidth > canvasWidth - (int) (Theme.tilePadding * 2f))
						maxLineWidth = canvasWidth - (int) (Theme.tilePadding * 2f);
					int lineSpacing = OpenGL.largeTextHeight / 2;
					
					// animate a slide-in / slide-out if new or expiring
					long now = System.currentTimeMillis();
					long age = now - notification.creationTimestamp;
					double animationPosition = 0;
					if(age < Theme.animationMilliseconds)
						animationPosition = 1.0 - (age / Theme.animationMillisecondsDouble);
					else if(notification.expiresAtTimestamp && now > notification.expirationTimestamp)
						animationPosition = (now - notification.expirationTimestamp) / Theme.animationMillisecondsDouble;
					animationPosition = smoothstep(animationPosition);
					int notificationHeight = (lineCount * lineHeight) + (lineSpacing * (lineCount - 1)) + (int) (3f * Theme.tilePadding);
					top.addAndGet((int) (animationPosition * (notificationHeight + Theme.tilePadding)));
					
					// draw the background
					int notificationWidth = canvasWidth - (int) (Theme.tilePadding * 2f);
					int backgroundWidth = notification.isProgressBar ? (int) (notificationWidth * progressBarPercentage) : notificationWidth;
					int xBackgroundLeft = (int) Theme.tilePadding;
					int yBackgroundBottom = top.get() - notificationHeight;
					double opacity = notification.isProgressBar || age >= 3.0 * Theme.animationMillisecondsDouble ? 0.2 :
						0.2 + 0.8 * smoothstep((age % Theme.animationMillisecondsDouble) / Theme.animationMillisecondsDouble);
					notification.glColor[3] = (float) opacity;
					OpenGL.drawBox(gl, notification.glColor, xBackgroundLeft, yBackgroundBottom, backgroundWidth, notificationHeight);
					
					// draw the text
					int yTextBastline = top.get() - (int) (1.5 * Theme.tilePadding) - lineHeight;
					int xTextLeft = (canvasWidth / 2) - (maxLineWidth / 2);
					if(xTextLeft < (int) (2.5 * Theme.tilePadding))
						xTextLeft = (int) (2.5 * Theme.tilePadding);
					gl.glEnable(GL3.GL_SCISSOR_TEST);
					gl.glScissor(xBackgroundLeft, yBackgroundBottom, notificationWidth, notificationHeight);
					for(int i = 0; i < lineCount; i++) {
						OpenGL.drawLargeText(gl, notification.isProgressBar ? notification.lines[i] + progressBarPercentageText : notification.lines[i], xTextLeft, yTextBastline, 0);
						yTextBastline -= lineSpacing + lineHeight;
					}
					gl.glDisable(GL3.GL_SCISSOR_TEST);
					
					// register an event handler if mouseOver this notification: clicking removes this notification
					if(mouseX >= xBackgroundLeft && mouseX <= xBackgroundLeft + backgroundWidth && mouseY >= yBackgroundBottom && mouseY <= yBackgroundBottom + notificationHeight && animationPosition == 0.0) {
						if(eventHandler == null)
							eventHandler = EventHandler.onPress(event -> {notification.expiresAtTimestamp = true;
							                                              notification.expirationTimestamp = now; });
					}
					
					top.addAndGet(-1 * (notificationHeight + (int) Theme.tilePadding));
				});
				notificationsHeight = canvasHeight - (top.get() + (int) Theme.tilePadding);
				
				tileColumns = Settings.GUI.tileColumns.get();
				tileRows    = Settings.GUI.tileRows.get();
				int tileWidth    = canvasWidth  / tileColumns;
				int tileHeight   = (canvasHeight - notificationsHeight) / tileRows;
				int tilesYoffset = (canvasHeight - notificationsHeight) - (tileHeight * tileRows);
				
				// draw tiles and charts
				if(Charts.exist() || Connections.exist()) {
				
					// if there are no charts, switch back to live view
					if(!Charts.exist())
						state = State.PLAYING_LIVE;
					
					// draw empty tiles
					if(removing || maximizing || demaximizing || maximizedChart == null) {
						float width = tileWidth - 2*Theme.tilePadding;
						float height = tileHeight - 2*Theme.tilePadding;
						if(width >= 4 && height >= 4) {
							// draw tile drop shadows
							OpenGL.buffer.rewind();
							for(int column = 0; column < tileColumns; column++) {
								for(int row = 0; row < tileRows; row++) {
									float xLeft   = (tileWidth * column)              + Theme.tilePadding + Theme.tileShadowOffset;
									float yBottom = (tileHeight * row + tilesYoffset) + Theme.tilePadding - Theme.tileShadowOffset;
									OpenGL.buffer.put(xLeft);         OpenGL.buffer.put(yBottom + height);
									OpenGL.buffer.put(xLeft);         OpenGL.buffer.put(yBottom);
									OpenGL.buffer.put(xLeft + width); OpenGL.buffer.put(yBottom + height);
									OpenGL.buffer.put(xLeft + width); OpenGL.buffer.put(yBottom + height);
									OpenGL.buffer.put(xLeft + width); OpenGL.buffer.put(yBottom);
									OpenGL.buffer.put(xLeft);         OpenGL.buffer.put(yBottom);
								}
							}
							OpenGL.drawTrianglesXY(gl, GL3.GL_TRIANGLES, Theme.tileShadowColor, OpenGL.buffer.rewind(), 6*tileColumns*tileRows);
							// draw tiles
							OpenGL.buffer.rewind();
							for(int column = 0; column < tileColumns; column++) {
								for(int row = 0; row < tileRows; row++) {
									float xLeft   = (tileWidth * column)              + Theme.tilePadding;
									float yBottom = (tileHeight * row + tilesYoffset) + Theme.tilePadding;
									OpenGL.buffer.put(xLeft);         OpenGL.buffer.put(yBottom + height);
									OpenGL.buffer.put(xLeft);         OpenGL.buffer.put(yBottom);
									OpenGL.buffer.put(xLeft + width); OpenGL.buffer.put(yBottom + height);
									OpenGL.buffer.put(xLeft + width); OpenGL.buffer.put(yBottom + height);
									OpenGL.buffer.put(xLeft + width); OpenGL.buffer.put(yBottom);
									OpenGL.buffer.put(xLeft);         OpenGL.buffer.put(yBottom);
								}
							}
							OpenGL.drawTrianglesXY(gl, GL3.GL_TRIANGLES, Theme.tileColor, OpenGL.buffer.rewind(), 6*tileColumns*tileRows);
						}
					}
					
					// draw a bounding box where the user is actively click-and-dragging to place a new chart
					OpenGL.drawBox(gl,
					               Theme.tileSelectedColor,
					               startX < endX ? startX * tileWidth : endX * tileWidth,
					               startY < endY ? (canvasHeight - notificationsHeight) - (endY + 1)*tileHeight : (canvasHeight - notificationsHeight) - (startY + 1)*tileHeight,
					               (Math.abs(endX - startX) + 1) * tileWidth,
					               (Math.abs(endY - startY) + 1) * tileHeight);
					
					// determine the timestamp and sample numbers corresponding to the right-edge of a time domain plot
					long now = System.currentTimeMillis();
					long endTimestamp = switch(state) {
					case PLAYING_LIVE       ->   Connections.getLastTimestamp();
					case PAUSED             ->   nonLiveTimestamp;
					case REWINDING, PLAYING -> { long delta = (now - previousFrameTimestamp) * playSpeed;
					                             long newTimestamp = nonLiveTimestamp + delta;
					                             long firstTimestamp = Connections.getFirstTimestamp();
					                             long lastTimestamp  = Connections.getLastTimestamp();
					                             if(newTimestamp < firstTimestamp) {
					                                 setPaused(firstTimestamp, null, -1);
					                                 yield firstTimestamp;
					                             } else if(newTimestamp > lastTimestamp) {
					                                 setPlayLive();
					                                 yield lastTimestamp;
					                             } else {
					                            	 nonLiveTimestamp = newTimestamp;
					                                 yield nonLiveTimestamp;
					                             }}
					};
					previousFrameTimestamp = now;
					
					Map<ConnectionTelemetry, Integer> endSampleNumbers = switch(state) {
					case PLAYING_LIVE               -> Connections.telemetryConnections.stream()
					                                              .collect(Collectors.toMap(connection -> connection,
					                                                                        connection -> connection.getSampleCount() - 1));
					case PAUSED, REWINDING, PLAYING -> Connections.telemetryConnections.stream()
					                                              .collect(Collectors.toMap(connection -> connection,
					                                                                        connection -> {
					                                                                            if(connection == nonLivePrimaryConnection) {
					                                                                                return nonLivePrimaryConnectionSampleNumber;
					                                                                            } else {
					                                                                                int sampleNumber = connection.getClosestSampleNumberAtOrBefore(nonLiveTimestamp, connection.getSampleCount() - 1);
					                                                                                long timestamp = connection.getTimestamp(sampleNumber);
					                                                                                if(timestamp != nonLiveTimestamp) {
					                                                                                    long errorMilliseconds = nonLiveTimestamp - timestamp;
					                                                                                    double samplesPerMillisecond = (double) connection.getSampleRate() / 1000.0;
					                                                                                    int errorSampleCount = (int) Math.round(samplesPerMillisecond * errorMilliseconds);
					                                                                                    sampleNumber += errorSampleCount;
					                                                                                }
					                                                                                return sampleNumber;
					                                                                            }
					                                                                        }));
					};
					
					// process the global trigger if enabled
					if(globalTrigger == null || globalTrigger.mode.is(WidgetTrigger.Mode.DISABLED)) {
						triggerDetails = new WidgetTrigger.Result(false, null, -1, endTimestamp, -1, endTimestamp, 0, endTimestamp);
					} else {
						int endSampleNumber = (globalTrigger.normalDataset != null) ? endSampleNumbers.get(globalTrigger.normalDataset.connection) :
						                      (globalTrigger.bitfieldState != null) ? endSampleNumbers.get(globalTrigger.bitfieldState.connection) :
						                                                              -1;
						
						triggerDetails = globalTrigger.checkForTrigger(endSampleNumber, endTimestamp, zoomLevel);
						
						endTimestamp = triggerDetails.chartEndTimestamp();
						endSampleNumbers = Connections.telemetryConnections.stream()
						                              .collect(Collectors.toMap(connection -> connection,
						                                                        connection -> {
						                                                            if(connection == triggerDetails.connection()) {
						                                                                return triggerDetails.chartEndSampleNumber();
						                                                            } else {
						                                                                int sampleNumber = connection.getClosestSampleNumberAtOrBefore(triggerDetails.chartEndTimestamp(), connection.getSampleCount() - 1);
						                                                                long timestamp = connection.getTimestamp(sampleNumber);
						                                                                if(timestamp != triggerDetails.chartEndTimestamp()) {
						                                                                    long errorMilliseconds = triggerDetails.chartEndTimestamp() - timestamp;
						                                                                    double samplesPerMillisecond = (double) connection.getSampleRate() / 1000.0;
						                                                                    int errorSampleCount = (int) Math.round(samplesPerMillisecond * errorMilliseconds);
						                                                                    sampleNumber += errorSampleCount;
						                                                                }
						                                                                return sampleNumber;
						                                                            }
						                                                        }));
					}
					
					// draw the charts
					//
					// the modelview matrix is translated so the origin will be at the bottom-left for each chart.
					// the scissor test is used to clip rendering to the region allocated for each chart.
					// if charts will be using off-screen framebuffers, they need to disable the scissor test when (and only when) drawing off-screen.
					chartUnderMouse = null;
					var endSamples = endSampleNumbers; // constants for lambda below
					var endTime    = endTimestamp;
					Charts.forEach(chart -> {
						
						int lastSampleNumber = (chart.datasets.connection == null) ? -1 : endSamples.get(chart.datasets.connection);
						
						// if there is a maximized chart, only draw that chart
						if(maximizedChart != null && maximizedChart != removingChart && chart != maximizedChart && !maximizing && !demaximizing)
							return;
						
						// size the chart
						int width  = tileWidth  * (chart.bottomRightX - chart.topLeftX + 1);
						int height = tileHeight * (chart.bottomRightY - chart.topLeftY + 1);
						int xOffset = chart.topLeftX * tileWidth;
						int yOffset = (canvasHeight - notificationsHeight) - (chart.topLeftY * tileHeight) - height;
						
						double percentComplete  = smoothstep(1.0 - (double) (animationEndTimestamp - System.currentTimeMillis()) / Theme.animationMilliseconds);
						double percentRemaining = 1.0 - percentComplete;
						
						if(chart == maximizedChart) {
							int maximizedWidth = tileWidth * tileColumns;
							int maximizedHeight = tileHeight * tileRows;
							int maximizedYoffset = (canvasHeight - notificationsHeight) - maximizedHeight;
							
							width   = maximizing   ? (int) Math.round(width * percentRemaining + (maximizedWidth * percentComplete )) :
							          demaximizing ? (int) Math.round(width * percentComplete  + (maximizedWidth * percentRemaining)) :
							                         maximizedWidth;
							height  = maximizing   ? (int) Math.round(height * percentRemaining + (maximizedHeight  * percentComplete )) :
							          demaximizing ? (int) Math.round(height * percentComplete  + (maximizedHeight  * percentRemaining)) :
							                         maximizedHeight;
							xOffset = maximizing   ? (int) Math.round(xOffset * percentRemaining) :
							          demaximizing ? (int) Math.round(xOffset * percentComplete ) :
							                         0;
							yOffset = maximizing   ? (int) Math.round(yOffset * percentRemaining + (maximizedYoffset * percentComplete )) :
							          demaximizing ? (int) Math.round(yOffset * percentComplete  + (maximizedYoffset * percentRemaining)) :
							                         maximizedYoffset;
							if(maximizing && percentComplete == 1.0) {
								maximizing = false;
							} else if(demaximizing && percentComplete == 1.0) {
								demaximizing = false;
								maximizedChart = null;
							}
						}
						if(chart == removingChart) {
							xOffset = (int) Math.round(xOffset + (0.5 * width  * percentComplete));
							yOffset = (int) Math.round(yOffset + (0.5 * height * percentComplete));
							width   = (int) Math.round(width  * (1.0 - percentComplete));
							height  = (int) Math.round(height * (1.0 - percentComplete));
						}
						
						// draw the chart
						drawTile(gl, xOffset, yOffset, width, height);
						xOffset += Theme.tilePadding;
						yOffset += Theme.tilePadding;
						width  -= 2 * Theme.tilePadding;
						height -= 2 * Theme.tilePadding;
						if(width < 1 || height < 1)
							return;
						
						// only give this chart the mouse location if an event doesn't already exist, or if the existing event belongs to this chart
						// this ensures a chart doesn't draw a tooltip if another chart's drag event is in progress
						boolean provideMouse = startX == -1 && endX == -1 && ((eventHandler == null) || (eventHandler != null && eventHandler.chart == chart));
						int chartMouseX = provideMouse ? mouseX - xOffset : -1;
						int chartMouseY = provideMouse ? mouseY - yOffset : -1;
						boolean mouseOverButtons = chartMouseX >= width + Theme.tileShadowOffset - 46.5f * Theme.lineWidth && chartMouseY >= height - 15.5f * Theme.lineWidth;
						
						float[] chartMatrix = Arrays.copyOf(screenMatrix, 16);
						OpenGL.translateMatrix(chartMatrix, xOffset, yOffset, 0);
						OpenGL.useMatrix(gl, chartMatrix);
						
						gl.glEnable(GL3.GL_SCISSOR_TEST);
						gl.glScissor(xOffset, yOffset, width, height);
						EventHandler chartEventHandler = chart.draw(gl, chartMatrix, width, height, endTime, lastSampleNumber, zoomLevel, mouseOverButtons ? -1 : chartMouseX, mouseOverButtons ? -1 : chartMouseY);
						gl.glDisable(GL3.GL_SCISSOR_TEST);
						
						// check if the mouse is over this chart
						width += (int) Theme.tileShadowOffset;
						if(mouseX >= xOffset && mouseX <= xOffset + width && mouseY >= yOffset && mouseY <= yOffset + height) {
							chartUnderMouse = chart;
							if(eventHandler == null && chartEventHandler != null)
								eventHandler = chartEventHandler;
							if(startX == -1 && endX == -1 && (eventHandler == null || !eventHandler.dragInProgress))
								drawChartButtons(gl, width, height, chartMouseX, chartMouseY);
						}
						
						// fade away if chart is closing
						width -= (int) Theme.tileShadowOffset;
						if(chart == removingChart) {
							float[] glColor = new float[] { Theme.tileColor[0], Theme.tileColor[1], Theme.tileColor[2], 0.2f + (float) percentComplete };
							OpenGL.drawBox(gl, glColor, 0, 0, width, height);
						}
						OpenGL.useMatrix(gl, screenMatrix);
						
					});
					
					if(maximizedChart != null) {
						// ensure a maximizing chart is drawn on top of all other charts
						Charts.drawChartLast(maximizedChart);
					} else if(chartUnderMouse != null && chartUnderMouse instanceof OpenGLTimelineChart) {
						// if the mouse is over a timeline chart, draw it last so timeline tooltips can overlap other charts
						Charts.drawChartLast(chartUnderMouse);
					}
					
					// remove a chart if necessary
					if(removing && animationEndTimestamp <= System.currentTimeMillis()) {
						Charts.remove(removingChart);
						if(maximizedChart == removingChart)
							maximizedChart = null;
						removingChart = null;
						removing = false;
					}
					
				}
				
				// update the mouse cursor
				setCursor(eventHandler == null || eventHandler.dragInProgress ? Theme.defaultCursor : eventHandler.cursor);
				
				// if benchmarking, draw the CPU/GPU benchmarks
				// GPU benchmarking is not possible with OpenGL ES
				if(Settings.GUI.cpuGpuMeasurementsEnabled.isTrue()) {
					// stop timers for *this frame*
					cpuStopNanoseconds = System.nanoTime();
					if(!openGLES)
						gl.glQueryCounter(gpuQueryHandles[1], GL3.GL_TIMESTAMP);
					
					// show times of *previous frame*
					float xBoxCenter = (canvasWidth / 2f);
					float yBoxTop = top.get();
					OpenGL.drawTextBox(gl, xBoxCenter, yBoxTop, true, "Entire Frame:", List.of(
					                                    String.format("CPU = %.3fms ", previousCpuMilliseconds),
					                                    String.format("(%.3fms)",      averageCpuMilliseconds ),
					                        !openGLES ? String.format("GPU = %.3fms ", previousGpuMilliseconds) : "GPU = unknown",
					                        !openGLES ? String.format("(%.3fms)",      averageGpuMilliseconds ) : "",
					                                    String.format("FPS = %.2f ",   previousFps),
					                                    String.format("(%.2f)",        averageFps)));
				}
				
			}
			
			@Override public void dispose(GLAutoDrawable drawable) {
				
				GL2ES3 gl = drawable.getGL().getGL2ES3();
				Charts.forEach(chart -> chart.disposeGpu(gl));
				if(!openGLES)
					gl.glDeleteQueries(2, gpuQueryHandles, 0);
				
				// also make the scaling factor invalid because we may be placed back on screen
				// this will make display() call Theme.initialize() which will regenerate the font textures
				displayScalingFactor = 0;
				
			}
			
		});
		
		setLayout(new BorderLayout());
		add(glCanvas, BorderLayout.CENTER);
		
		// note: a regular Animator seems to work slightly better than an FPSAnimator when not limiting the FPS 
		animator = Settings.GUI.fpsLimit.is(0) ? new Animator(glCanvas) : new FPSAnimator(glCanvas, Settings.GUI.fpsLimit.get());
		animator.start();
		
		glCanvas.addMouseListener(new MouseListener() {
			
			@Override public void mousePressed(MouseEvent me) {
				
				// if an event handler exists, call it
				if(eventHandler != null && eventHandler.forPressEvent) {
					eventHandler.handleDragStarted();
					eventHandler.handleMouseLocation(mouseXYtoChartXY(eventHandler.chart, me.getX(), me.getY()));
					return;
				}
				
				// if there are no connections and no charts, ignore this event
				if(!Charts.exist() && !Connections.exist())
					return;
				
				// don't start a new chart region if there is a maximized chart
				if(maximizedChart != null)
					return;
				
				// convert mouseXY to tileXY
				int x = (int) (me.getX() * Theme.osDpiScalingFactor);
				int y = (int) (me.getY() * Theme.osDpiScalingFactor) - notificationsHeight;
				int proposedStartX = Math.clamp(x * tileColumns / canvasWidth,                       0, tileColumns - 1);
				int proposedStartY = Math.clamp(y * tileRows / (canvasHeight - notificationsHeight), 0, tileRows - 1);
				
				// if an empty tile was clicked, start a new chart region
				if(Charts.stream().noneMatch(chart -> chart.intersects(proposedStartX, proposedStartY, proposedStartX, proposedStartY))) {
					startX = endX = proposedStartX;
					startY = endY = proposedStartY;
				}
				
			}
			
			@Override public void mouseReleased(MouseEvent me) {
				
				// if an event handler exists, call it
				if(eventHandler != null)
					eventHandler.handleDragEnded();
				
				// if there are no connections and no charts, ignore this event
				if(!Charts.exist() && !Connections.exist())
					return;

				// if the mouse press was ignored, also ignore this mouse release
				if(endX == -1 || endY == -1)
					return;
				
				// convert mouseXY to tileXY
				int x = (int) (me.getX() * Theme.osDpiScalingFactor);
				int y = (int) (me.getY() * Theme.osDpiScalingFactor) - notificationsHeight;
				int proposedEndX = Math.clamp(x * tileColumns / canvasWidth,                       0, tileColumns - 1);
				int proposedEndY = Math.clamp(y * tileRows / (canvasHeight - notificationsHeight), 0, tileRows - 1);
				
				// if the proposed chart region is available, use it
				if(Charts.stream().noneMatch(chart -> chart.intersects(startX, startY, proposedEndX, proposedEndY))) {
					endX = proposedEndX;
					endY = proposedEndY;
				}
				
				// create the new chart
				Configure.GUI.forNewChart(Charts.Type.TIME_DOMAIN.createAt(startX, startY, endX, endY));
				startX = startY = -1;
				endX   = endY   = -1;
				
			}

			@Override public void mouseExited (MouseEvent me) {
				
				// the mouse left the canvas, no longer need to show the chart close icon
				mouseX = -1;
				mouseY = -1;
				
			}
			
			@Override public void mouseClicked(MouseEvent me) { }
			@Override public void mouseEntered(MouseEvent me) { }
			
		});
		
		glCanvas.addMouseMotionListener(new MouseMotionListener() {
			
			@Override public void mouseDragged(MouseEvent me) {
				
				// log the mouse position so the charts can see the mouse location
				mouseX = (int) (me.getX() * Theme.osDpiScalingFactor);
				mouseY = (int) ((glCanvas.getHeight() - me.getY()) * Theme.osDpiScalingFactor);
				
				// if an event handler exists, call it
				if(eventHandler != null && eventHandler.forDragEvent) {
					eventHandler.handleMouseLocation(mouseXYtoChartXY(eventHandler.chart, me.getX(), me.getY()));
					return;
				}
				
				// if there are no connections and no charts, ignore this event
				if(!Charts.exist() && !Connections.exist())
					return;
				
				// if the mouse press was ignored, also ignore this mouse drag
				if(endX == -1 || endY == -1)
					return;
				
				// convert mouseXY to tileXY
				int x = (int) (me.getX() * Theme.osDpiScalingFactor);
				int y = (int) (me.getY() * Theme.osDpiScalingFactor) - notificationsHeight;
				int proposedEndX = Math.clamp(x * tileColumns / canvasWidth,                       0, tileColumns - 1);
				int proposedEndY = Math.clamp(y * tileRows / (canvasHeight - notificationsHeight), 0, tileRows - 1);
				
				// if the proposed chart region is available, use it
				if(Charts.stream().noneMatch(chart -> chart.intersects(startX, startY, proposedEndX, proposedEndY))) {
					endX = proposedEndX;
					endY = proposedEndY;
				}
				
			}
			
			@Override public void mouseMoved(MouseEvent me) {
				
				// log the mouse position so the charts can see the mouse location
				mouseX = (int) (me.getX() * Theme.osDpiScalingFactor);
				mouseY = (int) ((glCanvas.getHeight() - me.getY()) * Theme.osDpiScalingFactor);
				
			}
			
		});
		
		glCanvas.addMouseWheelListener(new MouseWheelListener() {
			
			// the mouse wheel was scrolled
			@Override public void mouseWheelMoved(MouseWheelEvent mwe) {
				
				// ignore scroll events while dragging
				if(eventHandler != null && eventHandler.dragInProgress)
					return;

				double scrollAmount = mwe.getPreciseWheelRotation();
				double zoomPerScroll = 0.1;
				
				if(!Charts.exist() && !mwe.isShiftDown())
					return;
				
				if(scrollAmount == 0)
					return;
				
				if(Charts.exist() && !mwe.isControlDown() && !mwe.isShiftDown()) {
					
					// no modifiers held down, so we're timeshifting
					
					// don't timeshift if there is no data
					int activeConnections = 0;
					for(Connection connection : Connections.allConnections)
						if(connection.getSampleCount() > 0)
							activeConnections++;
					if(activeConnections == 0)
						return;
					
					// can't fast-forward when live
					if(state == State.PLAYING_LIVE && scrollAmount > 0)
						return;
					
					// should we scroll by sample count?
					Chart chart = null;
					if(chartUnderMouse != null && chartUnderMouse.duration > 1 && chartUnderMouse.datasets.hasAnyType()) {
						chart = chartUnderMouse;
					} else if(activeConnections == 1) {
						chart = Charts.stream().filter(c -> c.duration > 1 && c.datasets.hasAnyType())
						                       .sorted((chart1, chart2) -> -1 * Integer.compare(chart1.duration, chart2.duration))
						                       .findFirst().orElse(null); // chart with largest duration
					}
					if(chart != null && chart.datasets.hasAnyType() && chart.datasets.connection.getSampleCount() < 1)
						chart = null;
					
					if(chart != null && chart.sampleCountMode) {
						
						// logic for rewinding and fast-forwarding based on sample count: 10% of the domain per scroll wheel notch
						ConnectionTelemetry connection = chart.datasets.connection;
						
						double samplesPerScroll = chart.duration * 0.10;
						double delta = scrollAmount * samplesPerScroll * zoomLevel;
						if(delta < -0.5 || delta > 0.5)
							delta = Math.round(delta);
						else if(delta < 0)
							delta = -1;
						else if(delta >= 0)
							delta = 1;
						
						int trueLastSampleNumber = connection.getSampleCount() - 1;
						int oldSampleNumber = (state == State.PLAYING_LIVE)            ? trueLastSampleNumber :
						                      (nonLivePrimaryConnection == connection) ? nonLivePrimaryConnectionSampleNumber :
						                      connection.getClosestSampleNumberAtOrBefore(nonLiveTimestamp, trueLastSampleNumber);
						int newSampleNumber = Guava.saturatedAdd(oldSampleNumber, (int) delta);
						boolean reachedStartOrEnd = newSampleNumber < 0 || newSampleNumber >= trueLastSampleNumber;
						if(newSampleNumber < 0)
							newSampleNumber = 0;
						if(newSampleNumber > trueLastSampleNumber)
							newSampleNumber = trueLastSampleNumber;

						long newTimestamp = connection.getTimestamp(newSampleNumber);
						
						boolean beforeStartOfData = (state != State.PLAYING_LIVE) && nonLiveTimestamp < connection.getFirstTimestamp();
						boolean afterEndOfData    = (state != State.PLAYING_LIVE) && nonLiveTimestamp > connection.getLastTimestamp();
						if(beforeStartOfData || afterEndOfData || (reachedStartOrEnd && activeConnections > 1)) {
							newTimestamp = nonLiveTimestamp + (long) (delta / connection.getSampleRate() * 1000.0);
							long firstTimestamp = Connections.getFirstTimestamp();
							if(newTimestamp < firstTimestamp)
								newTimestamp = firstTimestamp;
							setPaused(newTimestamp, null, 0);
						} else {
							setPaused(newTimestamp, connection, newSampleNumber);
						}
						if(newSampleNumber == trueLastSampleNumber && scrollAmount > 0)
							setPlayLive();
						
					} else if(chart != null && !chart.sampleCountMode) {
						
						// logic for rewinding and fast-forwarding based on a chart's time: 10% of the domain per scroll wheel notch
						double delta = (chart.duration * 0.10 * scrollAmount * zoomLevel);
						if(delta < -0.5 || delta > 0.5)
							delta = Math.round(delta);
						else if(delta < 0)
							delta = -1;
						else if(delta >= 0)
							delta = 1;
						long deltaMilliseconds = (long) delta;
						
						long firstTimestamp = Connections.getFirstTimestamp();
						long lastTimestamp = Connections.getLastTimestamp();
						long newTimestamp = (state == State.PLAYING_LIVE) ? lastTimestamp    + deltaMilliseconds :
						                                                    nonLiveTimestamp + deltaMilliseconds;
						if(newTimestamp < firstTimestamp)
							newTimestamp = firstTimestamp;
						if(newTimestamp > lastTimestamp)
							newTimestamp = lastTimestamp;
						
						setPaused(newTimestamp, null, 0);
						if(newTimestamp == lastTimestamp && scrollAmount > 0)
							setPlayLive();
						
					} else {
					
						// logic for rewinding and fast-forwarding based on global time: 100ms per scroll wheel notch
						double delta = (100.0 * scrollAmount * zoomLevel);
						if(delta < -0.5 || delta > 0.5)
							delta = Math.round(delta);
						else if(delta < 0)
							delta = -1;
						else if(delta >= 0)
							delta = 1;
						long deltaMilliseconds = (long) delta;
						
						long firstTimestamp = Connections.getFirstTimestamp();
						long lastTimestamp = Connections.getLastTimestamp();
						long newTimestamp = (state == State.PLAYING_LIVE) ? lastTimestamp    + deltaMilliseconds :
						                                                    nonLiveTimestamp + deltaMilliseconds;
						if(newTimestamp < firstTimestamp)
							newTimestamp = firstTimestamp;
						else if(newTimestamp > lastTimestamp)
							newTimestamp = lastTimestamp;
						
						// if the only connection is to a camera, snap to the closest camera frame
						if(Connections.telemetryConnections.isEmpty() && Connections.cameraConnections.size() == 1) {
							ConnectionCamera camera = Connections.cameraConnections.get(0);
							if(scrollAmount < 0)
								newTimestamp = camera.getClosestTimestampAtOrBefore(newTimestamp);
							else
								newTimestamp = camera.getClosestTimestampAtOrAfter(newTimestamp);
						}
						
						setPaused(newTimestamp, null, 0);
						if(newTimestamp == lastTimestamp && scrollAmount > 0)
							setPlayLive();
						
					}
				
				} else if(Charts.exist() && mwe.isControlDown()) {
					
					// ctrl is down, so we're zooming
					zoomLevel *= 1 + (scrollAmount * zoomPerScroll);
					zoomLevel = Math.clamp(zoomLevel, Double.MIN_VALUE, 1);
					
				} else if(mwe.isShiftDown()) {
					
					// shift is down, so we're adjusting the scaling factor
					// storing the scaling factor here as a Double, because a high-resolution scroll wheel might make very small changes 
					scalingFactor *= 1 - (scrollAmount * 0.1);
					scalingFactor = Math.clamp(scalingFactor, 1.0, 8.0);
					
					// but the scaling factor will only go into effect if it's at least 0.1 away from the slider's current value
					// because the slider has a resolution of just 0.1
					double delta = scalingFactor - Settings.GUI.scalingFactor.get();
					if(delta >= 0.1 || delta <= -0.1)
						Settings.GUI.scalingFactor.set((float) scalingFactor);
					
				}
				
			}
			
		});
		
	}
	
	public void setFpsLimit(int newValue) {
		
		animator.stop();
		animator.remove(glCanvas);
		animator = (newValue == 0) ? new Animator(glCanvas) : new FPSAnimator(glCanvas, newValue);
		animator.start();
		
	}
	
	public WidgetTrigger.Result triggerDetails;
	
	/**
	 * Converts mouse coordinates from a Swing MouseEvent to relative coordinates for a chart.
	 * 
	 * @param chart     Reference chart.
	 * @param mouseX    Mouse X location from the Swing MouseEvent object.
	 * @param mouseY    Mouse Y location from the Swing MouseEvent object.
	 * @return          A Point containing the mouse location relative to the chart.
	 */
	private Point mouseXYtoChartXY(Chart chart, int mouseX, int mouseY) {
		
		if(chart == null)
			return new Point(-1, -1);
		
		// convert from MouseEvent coordinates to glCanvas coordinates, and invert the y-axis so (0,0) is now the lower-left corner
		mouseX = (int) (mouseX * Theme.osDpiScalingFactor);
		mouseY = (int) ((glCanvas.getHeight() - mouseY) * Theme.osDpiScalingFactor);
		
		// determine the chart's coordinates relative to the glCanvas
		int tileWidth  = canvasWidth  / tileColumns;
		int tileHeight = (canvasHeight - notificationsHeight) / tileRows;
		int height = tileHeight * (chart.bottomRightY - chart.topLeftY + 1);
		int xOffset = chart.topLeftX * tileWidth;
		int yOffset = (canvasHeight - notificationsHeight) - (chart.topLeftY * tileHeight) - height;
		if(chart == maximizedChart) {
			height = tileHeight * tileRows;
			xOffset = 0;
			yOffset = (canvasHeight - notificationsHeight) - (tileHeight * tileRows);
		}
		
		// the chart is actually inset a little into its tile
		xOffset += Theme.tilePadding;
		yOffset += Theme.tilePadding;
		height -= 2 * Theme.tilePadding;
		
		// return the relative mouse location
		mouseX -= xOffset;
		mouseY -= yOffset;
		
		return new Point(mouseX, mouseY);
		
	}
	
	/**
	 * Replaces the glCanvas. This method must be called when the antialiasing level changes.
	 */
	public static void regenerate() {
		
		// important: must run this code with invokeLater() because the GLPanel might change the antialiasing level
		// but we must let the GLPanel finish drawing before regenerating, or an exception will occur!
		
		SwingUtilities.invokeLater(() -> {
			boolean isOnScreen = GUI.getParent() != null;
			
			if(isOnScreen)
				Main.window.remove(GUI);
			
			Chart maximizedChart = GUI.maximizedChart;
	
			// regenerate
			GUI.animator.stop();
			GUI.animator.remove(GUI.glCanvas);
			GUI = new OpenGLCharts();
			GUI.maximizedChart = maximizedChart;
			
			if(isOnScreen) {
				Main.window.add(GUI, BorderLayout.CENTER);
				Main.window.revalidate();
				Main.window.repaint();
			}
		});
		
	}
	
	public void setPlayLive()   {
		
		state = State.PLAYING_LIVE;
		nonLiveTimestamp = Long.MIN_VALUE;
		nonLivePrimaryConnection = null;
		nonLivePrimaryConnectionSampleNumber = 0;
		playSpeed = 0;
		
	}
	
	public void setPaused(long timestamp, ConnectionTelemetry connection, int sampleNumber) {
		
		state = State.PAUSED;
		nonLiveTimestamp = timestamp;
		nonLivePrimaryConnection = connection;
		nonLivePrimaryConnectionSampleNumber = (connection == null) ? 0 : sampleNumber;
		playSpeed = 0;
		
		long endOfTime = Connections.getLastTimestamp();
		if(timestamp > endOfTime && endOfTime != Long.MIN_VALUE)
			setPlayLive();
		
	}
	
	public void setPlayForwards() {
		
		if(state == State.PLAYING_LIVE)
			return;
		
		state = State.PLAYING;
		nonLivePrimaryConnection = null;
		nonLivePrimaryConnectionSampleNumber = 0;
		previousFrameTimestamp = System.currentTimeMillis();
		
		if(playSpeed < 0)
			playSpeed = 1;
		else if(playSpeed < 8)
			playSpeed++;
		
	}
	
	public void setPlayBackwards() {

		if(state == State.PLAYING_LIVE)
			nonLiveTimestamp = Connections.getLastTimestamp();
		state = State.REWINDING;
		nonLivePrimaryConnection = null;
		nonLivePrimaryConnectionSampleNumber = 0;
		previousFrameTimestamp = System.currentTimeMillis();
		
		if(playSpeed >= 0)
			playSpeed = -1;
		else if(playSpeed > -8)
			playSpeed--;
		
	}
	
	public int getPlaySpeed() {
		return playSpeed;
	}
	
	private State               prePausedState;
	private long                prePausedNonLiveTimestamp;
	private ConnectionTelemetry prePausedNonLivePrimaryConnection;
	private int                 prePausedNonLivePrimaryConnectionSampleNumber;
	private int                 prePausedPlaySpeed;
	
	public void pauseAndSaveState(long timestamp) {
		prePausedState                                = state;
		prePausedNonLiveTimestamp                     = nonLiveTimestamp;
		prePausedNonLivePrimaryConnection             = nonLivePrimaryConnection;
		prePausedNonLivePrimaryConnectionSampleNumber = nonLivePrimaryConnectionSampleNumber;
		prePausedPlaySpeed                            = playSpeed;
		setPaused(timestamp, null, 0);
	}
	
	public void unpauseAndRestoreState() {
		state                                = prePausedState;
		nonLiveTimestamp                     = prePausedNonLiveTimestamp;
		nonLivePrimaryConnection             = prePausedNonLivePrimaryConnection;
		nonLivePrimaryConnectionSampleNumber = prePausedNonLivePrimaryConnectionSampleNumber;
		playSpeed                            = prePausedPlaySpeed;
	}
	
	/**
	 * Implements the smoothstep algorithm, with a "left edge" of 0 and a "right edge" of 1.
	 * 
	 * @param x    Input, in the range of 0-1 inclusive.
	 * @return     Output, in the range of 0-1 inclusive.
	 */
	private double smoothstep(double x) {
		
		if(x < 0) {
			return 0;
		} else if(x > 1) {
			return 1;
		} else {
			return x * x * (3 - 2 * x);
		}
		
	}
	
	/**
	 * Draws a tile, the tile's drop-shadow, and a margin around the tile.
	 * 
	 * @param gl            The OpenGL context.
	 * @param lowerLeftX    Lower-left x location.
	 * @param lowerLeftY    Lower-left y location.
	 * @param width         Total region width, including the tile, drop-shadow and margin.
	 * @param height        Total region height, including the tile, drop-shadow and margin.
	 */
	private void drawTile(GL2ES3 gl, int lowerLeftX, int lowerLeftY, int width, int height) {
		
		float tileWidth  = width - 2*Theme.tilePadding;
		float tileHeight = height - 2*Theme.tilePadding;
		if(tileWidth < 4 || tileHeight < 4)
			return;
		
		// draw the tile's drop-shadow
		OpenGL.drawBox(gl,
		               Theme.tileShadowColor,
		               lowerLeftX + Theme.tilePadding + Theme.tileShadowOffset,
		               lowerLeftY + Theme.tilePadding - Theme.tileShadowOffset,
		               tileWidth,
		               tileHeight);

		// draw the tile
		OpenGL.drawBox(gl,
		               Theme.tileColor,
		               lowerLeftX + Theme.tilePadding,
		               lowerLeftY + Theme.tilePadding,
		               tileWidth,
		               tileHeight);
		
	}
	
	/**
	 * Draws the chart close/maximize/settings buttons for the user to click on.
	 * If the mouse is over a button, also registers a click handler.
	 * 
	 * @param gl         The OpenGL context.
	 * @param width      Chart region width.
	 * @param height     Chart region height.
	 */
	private void drawChartButtons(GL2ES3 gl, int width, int height, int mouseX, int mouseY) {
		
		float buttonWidth = 15f * Theme.lineWidth;
		float inset = buttonWidth * 0.2f;
		float yButtonTop = height - 0.5f*Theme.lineWidth;
		float yButtonBottom = yButtonTop - buttonWidth;
		float[] white = new float[] {1, 1, 1, 1};
		float[] black = new float[] {0, 0, 0, 1};
		
		float xCloseButtonRight = width - 0.5f*Theme.lineWidth;
		float xCloseButtonLeft = xCloseButtonRight - buttonWidth;
		boolean mouseOverCloseButton = mouseX >= xCloseButtonLeft && mouseX <= xCloseButtonRight && mouseY >= yButtonBottom && mouseY <= yButtonTop;
		
		float xMaxButtonRight = xCloseButtonLeft - 0.5f*Theme.lineWidth;
		float xMaxButtonLeft = xMaxButtonRight - buttonWidth;
		boolean mouseOverMaxButton = mouseX >= xMaxButtonLeft && mouseX <= xMaxButtonRight && mouseY >= yButtonBottom && mouseY <= yButtonTop;

		float xSettingsButtonRight = xMaxButtonLeft - 0.5f*Theme.lineWidth;
		float xSettingsButtonLeft = xSettingsButtonRight - buttonWidth;
		boolean mouseOverSettingsButton = mouseX >= xSettingsButtonLeft && mouseX <= xSettingsButtonRight && mouseY >= yButtonBottom && mouseY <= yButtonTop;
		
		// draw button backgrounds and outlines
		OpenGL.drawBox       (gl, mouseOverCloseButton    ? black : white, xCloseButtonLeft,    yButtonBottom, buttonWidth, buttonWidth);
		OpenGL.drawBoxOutline(gl, mouseOverCloseButton    ? white : black, xCloseButtonLeft,    yButtonBottom, buttonWidth, buttonWidth);
		OpenGL.drawBox       (gl, mouseOverMaxButton      ? black : white, xMaxButtonLeft,      yButtonBottom, buttonWidth, buttonWidth);
		OpenGL.drawBoxOutline(gl, mouseOverMaxButton      ? white : black, xMaxButtonLeft,      yButtonBottom, buttonWidth, buttonWidth);
		OpenGL.drawBox       (gl, mouseOverSettingsButton ? black : white, xSettingsButtonLeft, yButtonBottom, buttonWidth, buttonWidth);
		OpenGL.drawBoxOutline(gl, mouseOverSettingsButton ? white : black, xSettingsButtonLeft, yButtonBottom, buttonWidth, buttonWidth);
		
		// draw the close button's "X"
		OpenGL.buffer.rewind();
		OpenGL.buffer.put(xCloseButtonLeft  + inset); OpenGL.buffer.put(yButtonTop    - inset);
		OpenGL.buffer.put(xCloseButtonRight - inset); OpenGL.buffer.put(yButtonBottom + inset);
		OpenGL.buffer.put(xCloseButtonLeft  + inset); OpenGL.buffer.put(yButtonBottom + inset);
		OpenGL.buffer.put(xCloseButtonRight - inset); OpenGL.buffer.put(yButtonTop    - inset);
		OpenGL.buffer.rewind();
		OpenGL.drawLinesXy(gl, GL3.GL_LINES, mouseOverCloseButton ? white : black, OpenGL.buffer, 4);
		
		// draw the maximize button's rectangle
		OpenGL.drawBoxOutline(gl, mouseOverMaxButton ? white : black, xMaxButtonLeft + inset, yButtonBottom + inset, buttonWidth - 2*inset, buttonWidth - 2*inset);
		OpenGL.drawBox       (gl, mouseOverMaxButton ? white : black, xMaxButtonLeft + inset, yButtonTop - inset - (inset / 1.5f), buttonWidth - 2*inset, inset / 1.5f);
		
		// draw the settings button's gear teeth
		int teethCount = 7;
		int vertexCount = teethCount * 4;
		float gearCenterX = xSettingsButtonRight - (buttonWidth / 2);
		float gearCenterY = yButtonTop - (buttonWidth / 2);
		float outerRadius = buttonWidth * 0.35f;
		float innerRadius = buttonWidth * 0.25f;
		float holeRadius  = buttonWidth * 0.10f;
		OpenGL.buffer.rewind();
		for(int vertex = 0; vertex < vertexCount; vertex++) {
			float x = gearCenterX + (float) Math.cos((double) vertex / (double)vertexCount * 2 * Math.PI) * (vertex % 4 < 2 ? outerRadius : innerRadius);
			float y = gearCenterY + (float) Math.sin((double) vertex / (double)vertexCount * 2 * Math.PI) * (vertex % 4 < 2 ? outerRadius : innerRadius);
			OpenGL.buffer.put(x);
			OpenGL.buffer.put(y);
		}
		OpenGL.drawLinesXy(gl, GL3.GL_LINE_LOOP, mouseOverSettingsButton ? white : black, OpenGL.buffer.rewind(), vertexCount);
		
		// draw the hole
		OpenGL.buffer.rewind();
		for(int vertex = 0; vertex < vertexCount; vertex++) {
			float x = gearCenterX + (float) Math.cos((double) vertex / (double)vertexCount * 2 * Math.PI) * holeRadius;
			float y = gearCenterY + (float) Math.sin((double) vertex / (double)vertexCount * 2 * Math.PI) * holeRadius;
			OpenGL.buffer.put(x);
			OpenGL.buffer.put(y);
		}
		OpenGL.drawLinesXy(gl, GL3.GL_LINE_LOOP, mouseOverSettingsButton ? white : black, OpenGL.buffer.rewind(), vertexCount);
		
		// event handler
		if(mouseOverCloseButton) {
			eventHandler = EventHandler.onPress(event -> {
				removing = true;
				removingChart = chartUnderMouse;
				animationEndTimestamp = System.currentTimeMillis() + Theme.animationMilliseconds;
			});
		} else if(mouseOverMaxButton) {
			eventHandler = EventHandler.onPress(event -> {
				if(maximizing || demaximizing)
					return;
				if(maximizedChart == null) {
					maximizing = true;
					maximizedChart = chartUnderMouse;
					animationEndTimestamp = System.currentTimeMillis() + Theme.animationMilliseconds;
				} else {
					demaximizing = true;
					animationEndTimestamp = System.currentTimeMillis() + Theme.animationMilliseconds;
				}
			});
		} else if(mouseOverSettingsButton) {
			eventHandler = EventHandler.onPress(event -> Configure.GUI.forExistingChart(chartUnderMouse));
		}
		
	}
	
}