import java.awt.BorderLayout;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import com.jogamp.opengl.GL2ES3;
import com.jogamp.opengl.GL3;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.GLProfile;
import com.jogamp.opengl.awt.GLCanvas;
import com.jogamp.opengl.util.Animator;

/**
 * Manages the grid region and all charts on the screen.
 * Users can click-and-drag in this region to create new charts or interact with existing charts.
 */
@SuppressWarnings("serial")
public class OpenGLChartsView extends JPanel {
	
	static OpenGLChartsView instance = new OpenGLChartsView();
	
	static boolean firstRun = true;
	
	boolean openGLproblem = false;
	
	List<Chart> chartsToDispose = new ArrayList<Chart>();
	
	Animator animator;
	GLCanvas glCanvas;
	int canvasWidth;
	int canvasHeight;
	int notificationsHeight;
	float displayScalingFactorJava9 = 1;
	float displayScalingFactor = 1;
	
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
	final int SAMPLE_COUNT = 60;
	int[] gpuQueryHandles = new int[2];
	long[] gpuTimes = new long[2];
	boolean openGLES;
	
	JFrame parentWindow;
	
	float[] screenMatrix = new float[16];
	
	private OpenGLChartsView() {
		
		super();
		
		parentWindow = (JFrame) SwingUtilities.windowForComponent(this);
		
//		System.out.println(GLProfile.glAvailabilityToString());
//		System.setProperty("jogl.debug.GLSLCode", "");
//		System.setProperty("jogl.debug.DebugGL", "");
		GLCapabilities capabilities = null;
		try {
			// try to get normal OpenGL
			capabilities = new GLCapabilities(GLProfile.get(GLProfile.GL3));
			openGLES = false;
			if(SettingsView.instance.antialiasingSlider.get() > 1) {
				capabilities.setSampleBuffers(true);
				capabilities.setNumSamples(SettingsView.instance.antialiasingSlider.get());
			}
		} catch(Error | Exception e) {
			try {
				// fall back to OpenGL ES
				capabilities = new GLCapabilities(GLProfile.get(GLProfile.GLES3));
				openGLES = true;
				if(SettingsView.instance.antialiasingSlider.get() > 1) {
					capabilities.setSampleBuffers(true);
					capabilities.setNumSamples(SettingsView.instance.antialiasingSlider.get());
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
				if(!gl.isGL3() && SettingsView.instance.antialiasingSlider.get() > 1) {
					SettingsView.instance.antialiasingSlider.set(1);
					openGLproblem = true;
					return;
				}
				
				// ensure the requested AA level is supported 
				if(SettingsView.instance.antialiasingSlider.get() > 1) {
					int[] number = new int[1];
					gl.glGetIntegerv(GL3.GL_MAX_SAMPLES, number, 0);
					if(number[0] < SettingsView.instance.antialiasingSlider.get())
						SettingsView.instance.antialiasingSlider.set(number[0]);
				}
				
				gl.setSwapInterval(1);
				
				// GPU benchmarking is not possible with OpenGL ES
				if(!openGLES) {
					gl.glGenQueries(2, gpuQueryHandles, 0);
					gl.glQueryCounter(gpuQueryHandles[0], GL3.GL_TIMESTAMP); // insert both queries to prevent a warning on the first time they are read
					gl.glQueryCounter(gpuQueryHandles[1], GL3.GL_TIMESTAMP);
				}
				
				OpenGL.makeAllPrograms(gl);
				
				displayScalingFactor = Charts.getDisplayScalingFactor();
				Theme.initialize(gl, displayScalingFactor);
				
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
							Process p = Runtime.getRuntime().exec("wmic cpu get name");
							List<String> lines = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)).lines().toList();
							if(lines.size() > 2)
								text.append("CPU Name                     = " + lines.get(2) + "\n");
						} catch(Exception e) {}
					} else if(System.getProperty("os.name").toLowerCase().startsWith("linux")) {
						try {
							Process p = Runtime.getRuntime().exec("cat /proc/cpuinfo");
							List<String> lines = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)).lines().toList();
							String hardware  = lines.stream().filter(line -> line.startsWith("hardware"))
							                                 .map(line -> line.split(": ")[1])
							                                 .findFirst().orElse("");
							String model     = lines.stream().filter(line -> line.startsWith("model") && !line.startsWith("model name"))
							                                 .map(line -> line.split(": ")[1])
							                                 .findFirst().orElse("");
							String modelName = lines.stream().filter(line -> line.startsWith("model name"))
							                                 .map(line -> line.split(": ")[1])
							                                 .findFirst().orElse("");
							text.append("CPU Name                     = " + (!modelName.isEmpty() ? (modelName + "\n") :              // "model name" is provided by PC's
							                                                 !hardware.isEmpty()  ? (hardware + " " + model + "\n") : // but model name is not provided on the Pi 4
							                                                 "unknown \n"));
						} catch(Exception e) {}
//						try {
//							Process p = Runtime.getRuntime().exec("cat /proc/cpuinfo");
//							List<String> lines = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)).lines().toList();
//							String hardware = "";
//							String model = "";
//							String modelName = "";
//							for(String line : lines)
//								if(line.toLowerCase().startsWith("hardware"))
//									hardware = line.split(": ")[1];
//								else if(line.toLowerCase().startsWith("model") && !line.toLowerCase().startsWith("model name"))
//									model = line.split(": ")[1];
//								else if(line.toLowerCase().startsWith("model name"))
//									modelName = line.split(": ")[1];
//							if(!modelName.isEmpty())
//								text.append("CPU Name                     = " + modelName + "\n"); // this style used on PC's
//							else if(!hardware.isEmpty())
//								text.append("CPU Name                     = " + hardware + " " + model + "\n"); // this style used on Pi 4
//						} catch(Exception e) {}
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
				
				GL2ES3 gl = drawable.getGL().getGL2ES3();
				
				// work around java 9+ DPI scaling problem with JOGL
				displayScalingFactorJava9 = (float) ((Graphics2D) getGraphics()).getTransform().getScaleX();
				width = (int) (width * displayScalingFactorJava9);
				height = (int) (height * displayScalingFactorJava9);
				gl.glViewport(0, 0, width, height);
				
				OpenGL.makeOrthoMatrix(screenMatrix, 0, width, 0, height, -100000, 100000);
				OpenGL.useMatrix(gl, screenMatrix);
				
				canvasWidth = width;
				canvasHeight = height;
				
				Charts.setDisplayScalingFactorOS(displayScalingFactorJava9);
				
			}

			@Override public void display(GLAutoDrawable drawable) {
				
				if(openGLproblem)
					return;
				
				if(eventHandler != null && !eventHandler.dragInProgress)
					eventHandler = null;
				
				// prepare OpenGL
				GL2ES3 gl = drawable.getGL().getGL2ES3();
				OpenGL.useMatrix(gl, screenMatrix);
				
				// if benchmarking, calculate CPU/GPU time for the *previous frame*
				// GPU benchmarking is not possible with OpenGL ES
				if(SettingsView.instance.benchmarkingCheckbox.get()) {
					previousCpuMilliseconds = (cpuStopNanoseconds - cpuStartNanoseconds) / 1000000.0;
					if(!openGLES) {
						gl.glGetQueryObjecti64v(gpuQueryHandles[0], GL3.GL_QUERY_RESULT, gpuTimes, 0);
						gl.glGetQueryObjecti64v(gpuQueryHandles[1], GL3.GL_QUERY_RESULT, gpuTimes, 1);
					}
					previousGpuMilliseconds = (gpuTimes[1] - gpuTimes[0]) / 1000000.0;
					previousFps = 1000000000.0 / (System.nanoTime() - cpuStartNanoseconds);
					if(count < SAMPLE_COUNT) {
						cpuMillisecondsAccumulator += previousCpuMilliseconds;
						gpuMillisecondsAccumulator += previousGpuMilliseconds;
						fpsAccumulator += previousFps;
						count++;
					} else {
						averageCpuMilliseconds = cpuMillisecondsAccumulator / 60.0;
						averageGpuMilliseconds = gpuMillisecondsAccumulator / 60.0;
						averageFps = fpsAccumulator / 60.0;
						cpuMillisecondsAccumulator = 0;
						gpuMillisecondsAccumulator = 0;
						fpsAccumulator = 0;
						count = 0;
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
				float newDisplayScalingFactor = Charts.getDisplayScalingFactor();
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
				
				tileColumns = SettingsView.instance.tileColumnsTextfield.get();
				tileRows    = SettingsView.instance.tileRowsTextfield.get();
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
						for(int column = 0; column < tileColumns; column++) {
							for(int row = 0; row < tileRows; row++) {
								int lowerLeftX = tileWidth * column;
								int lowerLeftY = tileHeight * row + tilesYoffset;
								drawTile(gl, lowerLeftX, lowerLeftY, tileWidth, tileHeight);
							}
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
					case PLAYING_LIVE       ->   Connections.allConnections.stream()
					                                        .filter(connection -> connection.getSampleCount() > 0)
					                                        .mapToLong(connection -> connection.getLastTimestamp())
					                                        .max().orElse(Long.MIN_VALUE);
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
						triggerDetails = new WidgetTrigger.Result(false, null, -1, -1, -1, -1, 0, endTimestamp);
					} else {
						int endSampleNumber = -1;
						if(globalTrigger.normalDataset != null && endSampleNumbers.containsKey(globalTrigger.normalDataset.connection))
							endSampleNumber = endSampleNumbers.get(globalTrigger.normalDataset.connection);
						else if(globalTrigger.bitfieldState != null && endSampleNumbers.containsKey(globalTrigger.bitfieldState.connection))
							endSampleNumber = endSampleNumbers.get(globalTrigger.bitfieldState.connection);
						
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
						
						int lastSampleNumber = -1;
						if(chart.datasets.connection != null && endSamples.containsKey(chart.datasets.connection))
							lastSampleNumber = endSamples.get(chart.datasets.connection);
						
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
						
						gl.glEnable(GL3.GL_SCISSOR_TEST);
						gl.glScissor(xOffset, yOffset, width, height);
						
						float[] chartMatrix = Arrays.copyOf(screenMatrix, 16);
						OpenGL.translateMatrix(chartMatrix, xOffset, yOffset, 0);
						OpenGL.useMatrix(gl, chartMatrix);
						
						EventHandler chartEventHandler = chart.draw(gl, chartMatrix, width, height, endTime, lastSampleNumber, zoomLevel, (eventHandler != null) ? -1 : mouseX - xOffset, (eventHandler != null) ? -1 :mouseY - yOffset);
						
						OpenGL.useMatrix(gl, screenMatrix);
						gl.glDisable(GL3.GL_SCISSOR_TEST);
	
						// check if the mouse is over this chart
						width += (int) Theme.tileShadowOffset;
						if(mouseX >= xOffset && mouseX <= xOffset + width && mouseY >= yOffset && mouseY <= yOffset + height) {
							chartUnderMouse = chart;
							if(eventHandler == null && chartEventHandler != null)
								eventHandler = chartEventHandler;
							if(eventHandler == null || !eventHandler.dragInProgress) {
								drawChartCloseButton(gl, xOffset, yOffset, width, height);
								drawChartMaximizeButton(gl, xOffset, yOffset, width, height);
								drawChartSettingsButton(gl, xOffset, yOffset, width, height);
							}
						}
						
						// fade away if chart is closing
						if(chart == removingChart) {
							float[] glColor = new float[] { Theme.tileColor[0], Theme.tileColor[1], Theme.tileColor[2], 0.2f + (float) percentComplete };
							OpenGL.drawBox(gl, glColor, xOffset, yOffset, width, height);
						}
						
					});
					
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
				setCursor(eventHandler == null ? Theme.defaultCursor : eventHandler.cursor);
				
				// if benchmarking, draw the CPU/GPU benchmarks
				// GPU benchmarking is not possible with OpenGL ES
				if(SettingsView.instance.benchmarkingCheckbox.get()) {
					// stop timers for *this frame*
					cpuStopNanoseconds = System.nanoTime();
					if(!openGLES)
						gl.glQueryCounter(gpuQueryHandles[1], GL3.GL_TIMESTAMP);
					
					// show times of *previous frame*
					String line1 = "Entire Frame:";
					String line2 =             String.format("CPU = %.3fms (Average = %.3fms)", previousCpuMilliseconds, averageCpuMilliseconds);
					String line3 = !openGLES ? String.format("GPU = %.3fms (Average = %.3fms)", previousGpuMilliseconds, averageGpuMilliseconds) :
					                                         "GPU = unknown";
					String line4 =             String.format("FPS = %2.2f (Average = %2.2f)", previousFps, averageFps);
					float textHeight = 4*OpenGL.smallTextHeight + 3*Theme.tickTextPadding;
					float textWidth = Float.max(OpenGL.smallTextWidth(gl, line1), OpenGL.smallTextWidth(gl, line2));
					textWidth = Float.max(textWidth, OpenGL.smallTextWidth(gl, line3));
					textWidth = Float.max(textWidth, OpenGL.smallTextWidth(gl, line4));
					float boxWidth = textWidth + 2*Theme.tickTextPadding;
					float boxHeight = textHeight + 2*Theme.tickTextPadding;
					int xBoxLeft = (canvasWidth / 2) - (int) (textWidth / 2);
					int yBoxBottom = top.get() - (int) boxHeight;
					int xTextLeft = xBoxLeft + (int) Theme.tickTextPadding;
					int lineSpacing = (int) (Theme.tickTextPadding + OpenGL.smallTextHeight);
					int yTextBaseline = top.get() - lineSpacing;
					OpenGL.drawBox(gl, Theme.neutralColor, xBoxLeft, yBoxBottom, boxWidth, boxHeight);
					OpenGL.drawSmallText(gl, line1, (canvasWidth / 2) - (int) (OpenGL.smallTextWidth(gl, line1) / 2), yTextBaseline, 0);
					OpenGL.drawSmallText(gl, line2, xTextLeft, yTextBaseline - (lineSpacing * 1), 0);
					OpenGL.drawSmallText(gl, line3, xTextLeft, yTextBaseline - (lineSpacing * 2), 0);
					OpenGL.drawSmallText(gl, line4, xTextLeft, yTextBaseline - (lineSpacing * 3), 0);
					
//					String message = "Entire Frame: " + line2 + " " + line3;
//					for(int i = 0; i < charts.size(); i++) {
//						PositionedChart chart = charts.get(i);
//						message += ",     Chart " + i + ": " + chart.line1 + " " + line3;
//					}
//					NotificationsController.showDebugMessage(message);
				}
				
			}
			
			@Override public void dispose(GLAutoDrawable drawable) {
				
				GL2ES3 gl = drawable.getGL().getGL2ES3();
				Charts.forEach(chart -> chart.disposeGpu(gl));
				if(!openGLES)
					gl.glDeleteQueries(2, gpuQueryHandles, 0);
				
			}
			
		});
		
		setLayout(new BorderLayout());
		add(glCanvas, BorderLayout.CENTER);
	
		animator = new Animator(glCanvas);
		animator.setUpdateFPSFrames(1, null);
		animator.start();
		
		glCanvas.addMouseListener(new MouseListener() {
			
			// the mouse was pressed, attempting to start a new chart region, or to interact with an existing chart
			@Override public void mousePressed(MouseEvent me) {
				
				if(eventHandler != null && eventHandler.forPressEvent) {
					eventHandler.handleDragStarted();
					eventHandler.handleMouseLocation(mouseXYtoChartXY(eventHandler.chart, me.getX(), me.getY()));
					return;
				}
				
				// if there are no connections and no charts, ignore the event
				if(!Charts.exist() && !Connections.exist())
					return;
				
				// don't start a new chart region if there is a maximized chart
				if(maximizedChart != null)
					return;
				
				int x = (int) (me.getX() * displayScalingFactorJava9);
				int y = (int) (me.getY() * displayScalingFactorJava9);
				y -= notificationsHeight;
				if(x < 0 || y < 0)
					return;
				int proposedStartX = x * tileColumns / canvasWidth;
				int proposedStartY = y * tileRows / (canvasHeight - notificationsHeight);
				
				if(proposedStartX < tileColumns && proposedStartY < tileRows && Charts.stream().noneMatch(chart -> chart.intersects(proposedStartX, proposedStartY, proposedStartX, proposedStartY))) {
					startX = endX = proposedStartX;
					startY = endY = proposedStartY;
				}
				
			}
			
			// the mouse was released, attempting to create a new chart
			@Override public void mouseReleased(MouseEvent me) {
				
				if(eventHandler != null)
					eventHandler.handleDragEnded();
				
				// if there are no connections and no charts, ignore the event
				if(!Charts.exist() && !Connections.exist())
					return;

				if(endX == -1 || endY == -1)
					return;
			
				int x = (int) (me.getX() * displayScalingFactorJava9);
				int y = (int) (me.getY() * displayScalingFactorJava9);
				y -= notificationsHeight;
				if(x < 0 || y < 0)
					return;
				int proposedEndX = x * tileColumns / canvasWidth;
				int proposedEndY = y * tileRows / (canvasHeight - notificationsHeight);
				
				if(proposedEndX < tileColumns && proposedEndY < tileRows && Charts.stream().noneMatch(chart -> chart.intersects(startX, startY, proposedEndX, proposedEndY))) {
					endX = proposedEndX;
					endY = proposedEndY;
				}
				
				int x1 = startX;
				int y1 = startY;
				int x2 = endX;
				int y2 = endY;
				
				startX = startY = -1;
				endX   = endY   = -1;
				
				ConfigureView.instance.forNewChart(Charts.Type.TIME_DOMAIN.createAt(x1, y1, x2, y2));
				
			}

			// the mouse left the canvas, no longer need to show the chart close icon
			@Override public void mouseExited (MouseEvent me) {
				
				mouseX = -1;
				mouseY = -1;
				
			}
			
			@Override public void mouseClicked(MouseEvent me) { }
			
			@Override public void mouseEntered(MouseEvent me) { }
			
		});
		
		glCanvas.addMouseMotionListener(new MouseMotionListener() {
			
			// the mouse was dragged while attempting to create a new chart
			@Override public void mouseDragged(MouseEvent me) {
				
				// if there are no connections and no charts, ignore the event
				if(!Charts.exist() && !Connections.exist())
					return;
				
				mouseX = (int) (me.getX() * displayScalingFactorJava9);
				mouseY = (int) ((glCanvas.getHeight() - me.getY()) * displayScalingFactorJava9);
				
				if(eventHandler != null && eventHandler.forDragEvent) {
					eventHandler.handleMouseLocation(mouseXYtoChartXY(eventHandler.chart, me.getX(), me.getY()));
					return;
				}
				
				if(endX == -1 || endY == -1)
					return;
				
				int x = (int) (me.getX() * displayScalingFactorJava9);
				int y = (int) (me.getY() * displayScalingFactorJava9);
				y -= notificationsHeight;
				if(x < 0 || y < 0)
					return;
				int proposedEndX = x * tileColumns / canvasWidth;
				int proposedEndY = y * tileRows / (canvasHeight - notificationsHeight);
				
				if(proposedEndX < tileColumns && proposedEndY < tileRows && Charts.stream().noneMatch(chart -> chart.intersects(startX, startY, proposedEndX, proposedEndY))) {
					endX = proposedEndX;
					endY = proposedEndY;
				}
				
			}
			
			// log the mouse position so the chart close/maximize/configure icons can be drawn correctly
			@Override public void mouseMoved(MouseEvent me) {
				
				mouseX = (int) (me.getX() * displayScalingFactorJava9);
				mouseY = (int) ((glCanvas.getHeight() - me.getY()) * displayScalingFactorJava9);
				
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
				float  displayScalingPerScroll = 0.1f;
				
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
					
					if(zoomLevel > 1)
						zoomLevel = 1;
					else if(zoomLevel < 0)
						zoomLevel = Double.MIN_VALUE;
					
				} else if(mwe.isShiftDown()) {
					
					// shift is down, so we're adjusting the display scaling factor
					float newFactor = Charts.getDisplayScalingFactorUser() * (1 - ((float)scrollAmount * displayScalingPerScroll));
					Charts.setDisplayScalingFactorUser(newFactor);
					
				}
				
			}
			
		});
		
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
		mouseX = (int) (mouseX * displayScalingFactorJava9);
		mouseY = (int) ((glCanvas.getHeight() - mouseY) * displayScalingFactorJava9);
		
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
		
		boolean isOnScreen = instance.getParent() != null;
		
		if(isOnScreen)
			Main.window.remove(instance);

		// regenerate
		instance.animator.stop();
		instance = new OpenGLChartsView();
		
		if(isOnScreen) {
			Main.window.add(instance, BorderLayout.CENTER);
			Main.window.revalidate();
			Main.window.repaint();
		}
		
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
		
		// draw the tile's drop-shadow
		OpenGL.drawBox(gl,
		               Theme.tileShadowColor,
		               lowerLeftX + Theme.tilePadding + Theme.tileShadowOffset,
		               lowerLeftY + Theme.tilePadding - Theme.tileShadowOffset,
		               width - 2*Theme.tilePadding,
		               height - 2*Theme.tilePadding);

		// draw the tile
		OpenGL.drawBox(gl,
		               Theme.tileColor,
		               lowerLeftX + Theme.tilePadding,
		               lowerLeftY + Theme.tilePadding,
		               width - 2*Theme.tilePadding,
		               height - 2*Theme.tilePadding);
		
	}
	
	/**
	 * Draws an "X" close chart button for the user to click on.
	 * If the mouse is over the button, also registers a click handler.
	 * 
	 * @param gl         The OpenGL context.
	 * @param xOffset    Chart region lower-left x location.
	 * @param yOffset    Chart region lower-left y location.
	 * @param width      Chart region width.
	 * @param height     Chart region height.
	 */
	private void drawChartCloseButton(GL2ES3 gl, int xOffset, int yOffset, int width, int height) {
		
		float buttonWidth = 15f * Charts.getDisplayScalingFactor();
		float inset = buttonWidth * 0.2f;
		float buttonXleft = xOffset + width - buttonWidth;
		float buttonXright = xOffset + width;
		float buttonYtop = yOffset + height;
		float buttonYbottom = yOffset + height - buttonWidth;
		boolean mouseOverButton = mouseX >= buttonXleft && mouseX <= buttonXright && mouseY >= buttonYbottom && mouseY <= buttonYtop;
		float[] white = new float[] {1, 1, 1, 1};
		float[] black = new float[] {0, 0, 0, 1};
		
		// draw button background and outline
		OpenGL.drawBox       (gl, mouseOverButton ? black : white, buttonXleft, buttonYbottom, buttonWidth, buttonWidth);
		OpenGL.drawBoxOutline(gl, mouseOverButton ? white : black, buttonXleft, buttonYbottom, buttonWidth, buttonWidth);
		
		// draw the "X"
		OpenGL.buffer.rewind();
		OpenGL.buffer.put(buttonXleft  + inset); OpenGL.buffer.put(buttonYtop    - inset);
		OpenGL.buffer.put(buttonXright - inset); OpenGL.buffer.put(buttonYbottom + inset);
		OpenGL.buffer.put(buttonXleft  + inset); OpenGL.buffer.put(buttonYbottom + inset);
		OpenGL.buffer.put(buttonXright - inset); OpenGL.buffer.put(buttonYtop    - inset);
		OpenGL.buffer.rewind();
		OpenGL.drawLinesXy(gl, GL3.GL_LINES, mouseOverButton ? white : black, OpenGL.buffer, 4);
		
		// event handler
		if(mouseOverButton)
			eventHandler = EventHandler.onPress(event -> {
				removing = true;
				removingChart = chartUnderMouse;
				animationEndTimestamp = System.currentTimeMillis() + Theme.animationMilliseconds;
			});
		
	}
	
	/**
	 * Draws a chart maximize button (rectangle icon) for the user to click on.
	 * If the mouse is over the button, also registers a click handler.
	 * 
	 * @param gl         The OpenGL context.
	 * @param xOffset    Chart region lower-left x location.
	 * @param yOffset    Chart region lower-left y location.
	 * @param width      Chart region width.
	 * @param height     Chart region height.
	 */
	private void drawChartMaximizeButton(GL2ES3 gl, int xOffset, int yOffset, int width, int height) {
		
		float buttonWidth = 15f * Charts.getDisplayScalingFactor();
		float inset = buttonWidth * 0.2f;
		float offset = buttonWidth + 1;
		float buttonXleft = xOffset + width - buttonWidth - offset;
		float buttonXright = xOffset + width - offset;
		float buttonYtop = yOffset + height;
		float buttonYbottom = yOffset + height - buttonWidth;
		boolean mouseOverButton = mouseX >= buttonXleft && mouseX <= buttonXright && mouseY >= buttonYbottom && mouseY <= buttonYtop;
		float[] white = new float[] {1, 1, 1, 1};
		float[] black = new float[] {0, 0, 0, 1};
		
		// draw button background and outline
		OpenGL.drawBox       (gl, mouseOverButton ? black : white, buttonXleft, buttonYbottom, buttonWidth, buttonWidth);
		OpenGL.drawBoxOutline(gl, mouseOverButton ? white : black, buttonXleft, buttonYbottom, buttonWidth, buttonWidth);
		
		// draw the rectangle
		OpenGL.drawBoxOutline(gl, mouseOverButton ? white : black, buttonXleft + inset, buttonYbottom + inset, buttonWidth - 2*inset, buttonWidth - 2*inset);
		OpenGL.drawBox(gl, mouseOverButton ? white : black, buttonXleft + inset, buttonYtop - inset - (inset / 1.5f), buttonWidth - 2*inset, inset / 1.5f);
		
		// event handler
		if(mouseOverButton)
			eventHandler = EventHandler.onPress(event -> {
				if(maximizing || demaximizing)
					return;
				if(maximizedChart == null) {
					maximizing = true;
					maximizedChart = chartUnderMouse;
					animationEndTimestamp = System.currentTimeMillis() + Theme.animationMilliseconds;
					Charts.drawChartLast(maximizedChart); // ensure the chart is drawn on top of the others during the maximize animation
				} else {
					demaximizing = true;
					animationEndTimestamp = System.currentTimeMillis() + Theme.animationMilliseconds;
				}
			});
		
	}
	
	/**
	 * Draws a chart settings button (gear icon) for the user to click on.
	 * If the mouse is over the button, also registers a click handler.
	 * 
	 * @param gl         The OpenGL context.
	 * @param xOffset    Chart region lower-left x location.
	 * @param yOffset    Chart region lower-left y location.
	 * @param width      Chart region width.
	 * @param height     Chart region height.
	 */
	private void drawChartSettingsButton(GL2ES3 gl, int xOffset, int yOffset, int width, int height) {
		
		float buttonWidth = 15f * Charts.getDisplayScalingFactor();
		float offset = (buttonWidth + 1) * 2;
		float buttonXleft = xOffset + width - buttonWidth - offset;
		float buttonXright = xOffset + width - offset;
		float buttonYtop = yOffset + height;
		float buttonYbottom = yOffset + height - buttonWidth;
		boolean mouseOverButton = mouseX >= buttonXleft && mouseX <= buttonXright && mouseY >= buttonYbottom && mouseY <= buttonYtop;
		float[] white = new float[] {1, 1, 1, 1};
		float[] black = new float[] {0, 0, 0, 1};
		
		int teethCount = 7;
		int vertexCount = teethCount * 4;
		float gearCenterX = buttonXright - (buttonWidth / 2);
		float gearCenterY = buttonYtop - (buttonWidth / 2);
		float outerRadius = buttonWidth * 0.35f;
		float innerRadius = buttonWidth * 0.25f;
		float holeRadius  = buttonWidth * 0.10f;
		
		// draw button background and outline
		OpenGL.drawBox       (gl, mouseOverButton ? black : white, buttonXleft, buttonYbottom, buttonWidth, buttonWidth);
		OpenGL.drawBoxOutline(gl, mouseOverButton ? white : black, buttonXleft, buttonYbottom, buttonWidth, buttonWidth);
		
		// draw the gear teeth
		OpenGL.buffer.rewind();
		for(int vertex = 0; vertex < vertexCount; vertex++) {
			float x = gearCenterX + (float) Math.cos((double) vertex / (double)vertexCount * 2 * Math.PI) * (vertex % 4 < 2 ? outerRadius : innerRadius);
			float y = gearCenterY + (float) Math.sin((double) vertex / (double)vertexCount * 2 * Math.PI) * (vertex % 4 < 2 ? outerRadius : innerRadius);
			OpenGL.buffer.put(x);
			OpenGL.buffer.put(y);
		}
		OpenGL.drawLinesXy(gl, GL3.GL_LINE_LOOP, mouseOverButton ? white : black, OpenGL.buffer.rewind(), vertexCount);
		
		// draw the hole
		OpenGL.buffer.rewind();
		for(int vertex = 0; vertex < vertexCount; vertex++) {
			float x = gearCenterX + (float) Math.cos((double) vertex / (double)vertexCount * 2 * Math.PI) * holeRadius;
			float y = gearCenterY + (float) Math.sin((double) vertex / (double)vertexCount * 2 * Math.PI) * holeRadius;
			OpenGL.buffer.put(x);
			OpenGL.buffer.put(y);
		}
		OpenGL.drawLinesXy(gl, GL3.GL_LINE_LOOP, mouseOverButton ? white : black, OpenGL.buffer.rewind(), vertexCount);
		
		// event handler
		if(mouseOverButton)
			eventHandler = EventHandler.onPress(event -> ConfigureView.instance.forExistingChart(chartUnderMouse));
		
	}
	
}