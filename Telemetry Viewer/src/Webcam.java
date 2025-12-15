import java.io.File;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class Webcam {
	
	/**
	 * Important: All functions here are "synchronized" as a simple way to make things thread-safe.
	 * So far, this does not seem to cause any noticeable performance issues.
	 * 
	 * Making getCameras() NOT synchronized results in an intermittent bug:
	 * 
	 *    When the user closes the program, the call to System.exit() in Main.java
	 *    might deadlock when it tries to run the shutdown hooks.
	 *    This bug is intermittently reproducible, and seems to occur somewhat often when:
	 *        1. A camera was connected before the user tried to close the program, AND
	 *        2. getCameras() was in progress (in another thread) when System.exit() was called.
	 *        
	 *    Curiously, jstack.exe can't even connect to javaw.exe to get a stackdump when java is deadlocked.
	 *    
	 *    Debugging the DLL (with Visual Studio) seems to confirm that java is where the deadlock exists, not the DLL code:
	 *    If java deadlocks when attempting to exit, clicking "pause" in Visual Studio reveals:
	 *        "The application is in break mode. Your app has entered a break state,
	 *        but there is no code to show because all threads were executing external code
	 *        (typically system or framework code)."
	 *    
	 *    Making getCameras() synchronized seems to fix this problem. I have not been able to figure out why.
	 *    Perhaps a call to disconnect() while a call to getCameras() is in progress causes problems,
	 *    but I don't see why it would cause a deadlock.
	 *        
	 *    Curiously, if I put a Thread.sleep(1000) before System.exit() the intermittent problem seems to go away.
	 */
	
	static final boolean enabled;
	static {
//		System.load(System.getProperty("user.dir") + "/lib/WebcamDLL/x64/Debug/WebcamDLL.dll");
//		enabled = true;
		boolean success = false;
		try {
			String jarDirectory = new File(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath()).getParentFile().getPath();
			String osName = System.getProperty("os.name").toLowerCase();
			String osArch = System.getProperty("os.arch").toLowerCase();
			String libraryFilename = osName.startsWith("windows") && osArch.equals("amd64") ? "webcam-x64.dll"   :
			                         osName.startsWith("windows") && osArch.equals("arm64") ? "webcam-aarch64.dll" : // not yet implemented
			                         osName.startsWith("linux")   && osArch.equals("amd64") ? "webcam-x64.so"      : // not yet implemented
			                         osName.startsWith("linux")   && osArch.equals("arm64") ? "webcam-aarch64.so"  : // not yet implemented
			                                                                                  "";
			System.load(Paths.get(jarDirectory, "lib", libraryFilename).toString());
			success = true;
		} catch(Exception | Error e) {
			success = false;
		} finally {
			enabled = success;
		}
	}
	
	public static volatile List<Camera> list = getCameras();
	
	/**
	 * Represents a camera and all of its settings.
	 */
	public record Camera(String name,                        // from the DirectShow "Friendly Name" (will be shown to the user)
	                     MemorySegment handle,               // from the DirectShow "Device Path" (a wchar_t[] null-terminated string)
	                     Setting pan,                        // from the DirectShow "Camera Control" interface
	                     Setting tilt,                       // from the DirectShow "Camera Control" interface
	                     Setting roll,                       // from the DirectShow "Camera Control" interface
	                     Setting zoom,                       // from the DirectShow "Camera Control" interface
	                     Setting exposure,                   // from the DirectShow "Camera Control" interface
	                     Setting iris,                       // from the DirectShow "Camera Control" interface
	                     Setting focus,                      // from the DirectShow "Camera Control" interface
	                     Setting brightness,                 // from the DirectShow "Video Processor" interface
	                     Setting contrast,                   // from the DirectShow "Video Processor" interface
	                     Setting hue,                        // from the DirectShow "Video Processor" interface
	                     Setting saturation,                 // from the DirectShow "Video Processor" interface
	                     Setting sharpness,                  // from the DirectShow "Video Processor" interface
	                     Setting gamma,                      // from the DirectShow "Video Processor" interface
	                     Setting color,                      // from the DirectShow "Video Processor" interface
	                     Setting whiteBalance,               // from the DirectShow "Video Processor" interface
	                     Setting backlightCompensation,      // from the DirectShow "Video Processor" interface
	                     Setting gain,                       // from the DirectShow "Video Processor" interface
	                     List<Configuration> configurations, // from the DirectShow "Stream Configuration" interface
	                     AtomicReference<Arena> sharedMemory) {
		
		public List<Widget> createSettingsWidgets() {
			
			List<Widget> list = new ArrayList<Widget>();
			if(pan.supported)                   list.add(pan.createWidgetFor(this));
			if(tilt.supported)                  list.add(tilt.createWidgetFor(this));
			if(roll.supported)                  list.add(roll.createWidgetFor(this));
			if(zoom.supported)                  list.add(zoom.createWidgetFor(this));
			if(exposure.supported)              list.add(exposure.createWidgetFor(this));
			if(gain.supported)                  list.add(gain.createWidgetFor(this));
			if(iris.supported)                  list.add(iris.createWidgetFor(this));
			if(focus.supported)                 list.add(focus.createWidgetFor(this));
//			if(brightness.supported)            list.add(brightness.createWidgetFor(this));
//			if(contrast.supported)              list.add(contrast.createWidgetFor(this));
//			if(hue.supported)                   list.add(hue.createWidgetFor(this));
//			if(saturation.supported)            list.add(saturation.createWidgetFor(this));
//			if(sharpness.supported)             list.add(sharpness.createWidgetFor(this));
//			if(gamma.supported)                 list.add(gamma.createWidgetFor(this));
//			if(color.supported)                 list.add(color.createWidgetFor(this));
			if(whiteBalance.supported)          list.add(whiteBalance.createWidgetFor(this));
//			if(backlightCompensation.supported) list.add(backlightCompensation.createWidgetFor(this));
			return list;
			
		}
		
		@Override public String toString() {
			StringBuilder s = new StringBuilder();
			s.append("Name:                   " + name + "\n");
			s.append("Handle:                 " + handle.getString(0, StandardCharsets.UTF_16LE) + "\n");
			s.append("Pan:                    " + pan.toString() + "\n");
			s.append("Tilt:                   " + tilt.toString() + "\n");
			s.append("Roll:                   " + roll.toString() + "\n");
			s.append("Zoom:                   " + zoom.toString() + "\n");
			s.append("Exposure:               " + exposure.toString() + "\n");
			s.append("Iris:                   " + iris.toString() + "\n");
			s.append("Focus:                  " + focus.toString() + "\n");
			s.append("Brightness:             " + brightness.toString() + "\n");
			s.append("Contrast:               " + contrast.toString() + "\n");
			s.append("Hue:                    " + hue.toString() + "\n");
			s.append("Saturation:             " + saturation.toString() + "\n");
			s.append("Sharpness:              " + sharpness.toString() + "\n");
			s.append("Gamma:                  " + gamma.toString() + "\n");
			s.append("Color:                  " + color.toString() + "\n");
			s.append("White Balance:          " + whiteBalance.toString() + "\n");
			s.append("Backlight Compensation: " + backlightCompensation.toString() + "\n");
			s.append("Gain:                   " + gain.toString() + "\n");
			s.append("Configurations:         " + configurations.stream().map(config -> config.toString()).collect(Collectors.joining(", ")));
			return s.toString();
		}
		
	}
	
	/**
	 * A setting and all of its attributes.
	 */
	private record Setting(String name,
	                      boolean supported,
	                      int min,
	                      int max,
	                      int def,
	                      int stepSize,
	                      boolean automaticAllowed,       // example: focus can be automatic or manual
	                      boolean manualAllowed,          // example: backlight compensation can be turned on or off, but is not automatic
	                      boolean cameraControlInterface, // true = uses the camera control interface, false = uses the video processor interface
	                      int interfaceEnum) {            // enum value that gets passed to the DirectShow interface
		
		@Override public String toString() {
			return "Supported = %5s, Minimum = %5d, Maximum = %5d, Default = %5d, Step Size = %5d, Automatic Allowed = %5b, Manual Allowed = %5b".formatted(supported, min, max, def, stepSize, automaticAllowed, manualAllowed);
		}
		
		/**
		 * Creates a widget for adjusting this setting if the camera supports it.
		 * 
		 * Some settings (like zoom) are just a slider.
		 * Some settings (like exposure) can be automatic or manual, so there is a checkbox *and* a slider.
		 * Some settings (like backlight compensation) are just a checkbox.
		 * 
		 * @param camera    Camera that should be adjusted when the user interacts with this widget.
		 * @return          The widget, or null if the camera does not support this setting.
		 */
		public Widget createWidgetFor(Camera camera) {
			
			if(!supported)
				return null;
			else if(manualAllowed && stepSize == 0)
				return new WidgetCheckbox(name, def == 1)
				           .setExportLabel(name.toLowerCase())
				           .onChange(isChecked -> setSetting(camera, this, true, isChecked ? 1 : 0));
			else
				return WidgetSlider.ofInt(name, min, max, def)
				                   .setExportLabel(name.toLowerCase())
				                   .setStepSize(stepSize)
				                   .withEnableCheckbox(manualAllowed && automaticAllowed, false, "Automatic or manual.")
				                   .onChange((isManual, newManualValue) -> setSetting(camera, this, isManual, newManualValue));
			
		}
		
	}
	
	/**
	 * A stream configuration and all of its attributes.
	 */
	public record Configuration(int handle,       // MSBit = 0 for capture pin, or 1 for preview pin. Lower 31 bits = index for StreamConfig->GetStreamCaps()
	                            int width,        // pixels
	                            int height,       // pixels
	                            long interval,    // 1 = 100ns
	                            short colorDepth, // bits per pixel
	                            String fourCC) {  // FourCC image type
		
		@Override public String toString() {
			return "%dx%d  %d FPS  (%dbit %s)".formatted(width, height, Math.round(10000000.0 / interval), colorDepth, fourCC);
		}
		
		public static Configuration fromText(String text) {
			try {
				String resolution = text.split("  ")[0];
				String fps        = text.split("  ")[1];
				String codec      = text.split("  ")[2];
				codec = codec.substring(1, codec.length() - 1);
				int index        = -1; // unknown
				int width        = Integer.parseInt(resolution.split("x")[0]);
				int height       = Integer.parseInt(resolution.split("x")[1]);
				long interval    = Math.round(10000000.0 / Integer.parseInt(fps.split(" ")[0]));
				short colorDepth = Short.parseShort(codec.split("bit")[0]);
				String fourCC    = codec.split(" ")[1];
				return new Configuration(index, width, height, interval, colorDepth, fourCC);
			} catch(Exception e) {
				return null;
			}
		}
		
	}
	
	/**
	 * @return    Information on all of the cameras present on this device.
	 */
	public static synchronized List<Camera> getCameras() {
		
		List<Camera> cameras = new ArrayList<Camera>();
		if(!enabled)
			return cameras;
		
		try(Arena arena = Arena.ofConfined()) {
			
			// get details for up to 16 cameras
			int maxCameraCount = 16;
			MemorySegment cameraStructs = arena.allocate(camera.layout(), maxCameraCount);
			MemorySegment log           = MemorySegment.NULL; // arena.allocate(100_000);
			int cameraCount             = WebcamDLL.getCameras(cameraStructs, maxCameraCount, log, log.byteSize());
			
			if(log != MemorySegment.NULL)
				Notifications.printInfo(log.getString(0, StandardCharsets.UTF_16LE));
			
			// loop through the array of camera structs
			for(int i = 0; i < cameraCount; i++) {
				
				MemorySegment cameraStruct = camera.asSlice(cameraStructs, i);
				if(!camera.valid(cameraStruct))
					continue;
				
				// extract data from the struct and create java variables for all of the details
				String friendlyName  = camera.friendlyName(cameraStruct).getString(0, StandardCharsets.UTF_16LE);
				String devicePath    = camera.devicePath(cameraStruct).getString(0, StandardCharsets.UTF_16LE);
				Setting pan          = new Setting("Pan",
				                                   camera.panSupported(cameraStruct),
				                                   camera.panMinimum(cameraStruct),
				                                   camera.panMaximum(cameraStruct),
				                                   camera.panDefault(cameraStruct),
				                                   camera.panStepSize(cameraStruct),
				                                   camera.panAutomaticAllowed(cameraStruct),
				                                   camera.panManualAllowed(cameraStruct),
				                                   true, 0);
				Setting tilt         = new Setting("Tilt",
				                                   camera.tiltSupported(cameraStruct),
				                                   camera.tiltMinimum(cameraStruct),
				                                   camera.tiltMaximum(cameraStruct),
				                                   camera.tiltDefault(cameraStruct),
				                                   camera.tiltStepSize(cameraStruct),
				                                   camera.tiltAutomaticAllowed(cameraStruct),
				                                   camera.tiltManualAllowed(cameraStruct),
				                                   true, 1);
				Setting roll         = new Setting("Roll",
				                                   camera.rollSupported(cameraStruct),
				                                   camera.rollMinimum(cameraStruct),
				                                   camera.rollMaximum(cameraStruct),
				                                   camera.rollDefault(cameraStruct),
				                                   camera.rollStepSize(cameraStruct),
				                                   camera.rollAutomaticAllowed(cameraStruct),
				                                   camera.rollManualAllowed(cameraStruct),
				                                   true, 2);
				Setting zoom         = new Setting("Zoom",
				                                   camera.zoomSupported(cameraStruct),
				                                   camera.zoomMinimum(cameraStruct),
				                                   camera.zoomMaximum(cameraStruct),
				                                   camera.zoomDefault(cameraStruct),
				                                   camera.zoomStepSize(cameraStruct),
				                                   camera.zoomAutomaticAllowed(cameraStruct),
				                                   camera.zoomManualAllowed(cameraStruct),
				                                   true, 3);
				Setting exposure     = new Setting("Exposure",
				                                   camera.exposureSupported(cameraStruct),
				                                   camera.exposureMinimum(cameraStruct),
				                                   camera.exposureMaximum(cameraStruct),
				                                   camera.exposureDefault(cameraStruct),
				                                   camera.exposureStepSize(cameraStruct),
				                                   camera.exposureAutomaticAllowed(cameraStruct),
				                                   camera.exposureManualAllowed(cameraStruct),
				                                   true, 4);
				Setting iris         = new Setting("Iris",
				                                   camera.irisSupported(cameraStruct),
				                                   camera.irisMinimum(cameraStruct),
				                                   camera.irisMaximum(cameraStruct),
				                                   camera.irisDefault(cameraStruct),
				                                   camera.irisStepSize(cameraStruct),
				                                   camera.irisAutomaticAllowed(cameraStruct),
				                                   camera.irisManualAllowed(cameraStruct),
				                                   true, 5);
				Setting focus        = new Setting("Focus",
				                                   camera.focusSupported(cameraStruct),
				                                   camera.focusMinimum(cameraStruct),
				                                   camera.focusMaximum(cameraStruct),
				                                   camera.focusDefault(cameraStruct),
				                                   camera.focusStepSize(cameraStruct),
				                                   camera.focusAutomaticAllowed(cameraStruct),
				                                   camera.focusManualAllowed(cameraStruct),
				                                   true, 6);
				Setting brightness   = new Setting("Brightness",
				                                   camera.brightnessSupported(cameraStruct),
				                                   camera.brightnessMinimum(cameraStruct),
				                                   camera.brightnessMaximum(cameraStruct),
				                                   camera.brightnessDefault(cameraStruct),
				                                   camera.brightnessStepSize(cameraStruct),
				                                   camera.brightnessAutomaticAllowed(cameraStruct),
				                                   camera.brightnessManualAllowed(cameraStruct),
				                                   false, 0);
				Setting contrast     = new Setting("Contrast",
				                                   camera.contrastSupported(cameraStruct),
				                                   camera.contrastMinimum(cameraStruct),
				                                   camera.contrastMaximum(cameraStruct),
				                                   camera.contrastDefault(cameraStruct),
				                                   camera.contrastStepSize(cameraStruct),
				                                   camera.contrastAutomaticAllowed(cameraStruct),
				                                   camera.contrastManualAllowed(cameraStruct),
				                                   false, 1);
				Setting hue          = new Setting("Hue",
				                                   camera.hueSupported(cameraStruct),
				                                   camera.hueMinimum(cameraStruct),
				                                   camera.hueMaximum(cameraStruct),
				                                   camera.hueDefault(cameraStruct),
				                                   camera.hueStepSize(cameraStruct),
				                                   camera.hueAutomaticAllowed(cameraStruct),
				                                   camera.hueManualAllowed(cameraStruct),
				                                   false, 2);
				Setting saturation   = new Setting("Saturation",
				                                   camera.saturationSupported(cameraStruct),
				                                   camera.saturationMinimum(cameraStruct),
				                                   camera.saturationMaximum(cameraStruct),
				                                   camera.saturationDefault(cameraStruct),
				                                   camera.saturationStepSize(cameraStruct),
				                                   camera.saturationAutomaticAllowed(cameraStruct),
				                                   camera.saturationManualAllowed(cameraStruct),
				                                   false, 3);
				Setting sharpness    = new Setting("Sharpness",
				                                   camera.sharpnessSupported(cameraStruct),
				                                   camera.sharpnessMinimum(cameraStruct),
				                                   camera.sharpnessMaximum(cameraStruct),
				                                   camera.sharpnessDefault(cameraStruct),
				                                   camera.sharpnessStepSize(cameraStruct),
				                                   camera.sharpnessAutomaticAllowed(cameraStruct),
				                                   camera.sharpnessManualAllowed(cameraStruct),
				                                   false, 4);
				Setting gamma        = new Setting("Gamma",
				                                   camera.gammaSupported(cameraStruct),
				                                   camera.gammaMinimum(cameraStruct),
				                                   camera.gammaMaximum(cameraStruct),
				                                   camera.gammaDefault(cameraStruct),
				                                   camera.gammaStepSize(cameraStruct),
				                                   camera.gammaAutomaticAllowed(cameraStruct),
				                                   camera.gammaManualAllowed(cameraStruct),
				                                   false, 5);
				Setting color        = new Setting("Color",
				                                   camera.colorSupported(cameraStruct),
				                                   0,
				                                   0,
				                                   camera.colorSupported(cameraStruct) == true && camera.colorDefault(cameraStruct)  ? 1 : 0,
				                                   0,
				                                   false,
				                                   camera.colorSupported(cameraStruct),
				                                   false, 6);
				Setting whiteBalance = new Setting("White Balance",
				                                   camera.whiteBalanceSupported(cameraStruct),
				                                   camera.whiteBalanceMinimum(cameraStruct),
				                                   camera.whiteBalanceMaximum(cameraStruct),
				                                   camera.whiteBalanceDefault(cameraStruct),
				                                   camera.whiteBalanceStepSize(cameraStruct),
				                                   camera.whiteBalanceAutomaticAllowed(cameraStruct),
				                                   camera.whiteBalanceManualAllowed(cameraStruct),
				                                   false, 7);
				Setting backComp     = new Setting("Backlight Compensation",
				                                   camera.backlightCompensationSupported(cameraStruct),
				                                   0,
				                                   0,
				                                   camera.backlightCompensationSupported(cameraStruct) == true && camera.backlightCompensationDefault(cameraStruct) ? 1 : 0,
				                                   0,
				                                   false,
				                                   camera.backlightCompensationSupported(cameraStruct),
				                                   false, 8);
				Setting gain         = new Setting("Gain",
				                                   camera.gainSupported(cameraStruct),
				                                   camera.gainMinimum(cameraStruct),
				                                   camera.gainMaximum(cameraStruct),
				                                   camera.gainDefault(cameraStruct),
				                                   camera.gainStepSize(cameraStruct),
				                                   camera.gainAutomaticAllowed(cameraStruct),
				                                   camera.gainManualAllowed(cameraStruct),
				                                   false, 9);
				
				List<Configuration> configs = new ArrayList<Configuration>();
				int configsCount = camera.configsCount(cameraStruct);
				for(int j = 0; j < configsCount; j++) {
					int handle          = camera.configHandle(cameraStruct, j);
					int width           = camera.configWidth(cameraStruct, j);
					int height          = camera.configHeight(cameraStruct, j);
					long minInterval    = camera.configMinInterval(cameraStruct, j);
					long maxInterval    = camera.configMaxInterval(cameraStruct, j);
					short colorDepth    = camera.configColorDepth(cameraStruct, j);
					int fourCC          = camera.configFourCC(cameraStruct, j);
					String fourCCstring = new String(new char[] {(char) ((fourCC >>  0) & 0xFF),
					                                             (char) ((fourCC >>  8) & 0xFF),
					                                             (char) ((fourCC >> 16) & 0xFF),
					                                             (char) ((fourCC >> 24) & 0xFF)});
					
					if(minInterval == maxInterval) {
						// only one FPS is supported
						configs.add(new Configuration(handle, width, height, minInterval, colorDepth, fourCCstring));
					} else {
						// a range of FPSs are supported
						// ensure 30/50/60 FPS options are shown if they are inside the range
						configs.add(new Configuration(handle, width, height, minInterval, colorDepth, fourCCstring));
						configs.add(new Configuration(handle, width, height, maxInterval, colorDepth, fourCCstring));
						int interval30fps = 333333;
						int interval50fps = 200000;
						int interval60fps = 166666;
						if(interval30fps > minInterval && interval30fps < maxInterval)
							configs.add(new Configuration(handle, width, height, interval30fps, colorDepth, fourCCstring));
						if(interval50fps > minInterval && interval50fps < maxInterval)
							configs.add(new Configuration(handle, width, height, interval50fps, colorDepth, fourCCstring));
						if(interval60fps > minInterval && interval60fps < maxInterval)
							configs.add(new Configuration(handle, width, height, interval60fps, colorDepth, fourCCstring));
					}
				}
				
				// sort the configurations: widest first, then tallest, then highest FPS, then highest color depth
				configs.sort((a, b) -> b.width - a.width);
				configs.sort((a, b) -> (a.width != b.width) ? 0 : b.height - a.height);
				configs.sort((a, b) -> (a.width != b.width || a.height != b.height) ? 0 : (int) (a.interval - b.interval));
				configs.sort((a, b) -> (a.width != b.width || a.height != b.height || a.interval != b.interval) ? 0 : (int) (b.colorDepth - a.colorDepth));

				// create a record that represents this camera, and put it in the list of cameras
				Camera cam = new Camera(friendlyName,
				                        Arena.ofAuto().allocateFrom(devicePath, StandardCharsets.UTF_16LE),
				                        pan, tilt, roll, zoom, exposure, iris, focus,
				                        brightness, contrast, hue, saturation, sharpness, gamma, color, whiteBalance, backComp, gain,
				                        configs,
				                        new AtomicReference<Arena>());
				
				cameras.add(cam);
				
				// log details about this camera if it's new
				if(Webcam.list == null || Webcam.list.stream().noneMatch(existingCamera -> existingCamera.name.equals(friendlyName)))
					Notifications.printInfo("Camera Information:\n" + cameras.getLast().toString());
				
			}
			
		} catch(Exception e) {
			e.printStackTrace();
		}
		
		list = cameras;
		return list;
		
	}
	
	public record Image(ByteBuffer buffer, int width, int height, boolean isJpeg) {}
	
	/**
	 * Connects to a camera and starts receiving images.
	 * 
	 * @param camera           One of the Cameras from getCameras().
	 * @param configuration    One of the Configurations listed in the Camera record.
	 * @param handler          Will be called each time a new image is available. Must be thread-safe. The ByteBuffer will contain JPEG or BGR24 data.
	 * @return                 True on success.
	 *                         False if the camera is not present.
	 *                         False if the camera is already in use.
	 *                         False if the DLL was not loaded.
	 */
	public static synchronized boolean connect(Camera camera, Configuration configuration, Consumer<Image> handler) {
		
		if(!enabled)
			return false;
		
		try {
			if(camera.sharedMemory.get() == null)
				camera.sharedMemory.set(Arena.ofShared()); // thread-safe shared memory, will be released by disconnect()
			Arena sharedMemory = camera.sharedMemory.get();
			
			MemorySegment log          = MemorySegment.NULL; // sharedMemory.allocate(100_000);
			MemorySegment frameHandler = connectCamera$handler.allocate((buffer, bufferByteCount, width, height, isJpeg) -> {
				
				if(isJpeg) {
					// create a copy to ensure we own the memory
					ByteBuffer copy = ByteBuffer.allocateDirect(bufferByteCount);
					copy.put(buffer.asSlice(0, bufferByteCount).asByteBuffer());
					copy.rewind();
					handler.accept(new Image(copy, width, height, true));
				} else {
					// create a copy to ensure we own the memory, and vertically mirror it because DirectShow orders the lines "backwards"
					ByteBuffer original = buffer.asSlice(0, bufferByteCount).asByteBuffer();
					ByteBuffer mirrored = ByteBuffer.allocateDirect(width*height*3);
					int bytesPerLine = width*3;
					for(int line = 0; line < height; line++)
						mirrored.put(line * bytesPerLine, original, ((height-line-1) * bytesPerLine), bytesPerLine);
					handler.accept(new Image(mirrored, width, height, false));
				}
			}, sharedMemory);
			boolean success = WebcamDLL.connectCamera(camera.handle, configuration.handle, configuration.interval, frameHandler, log, log.byteSize());
			
			if(log != MemorySegment.NULL)
				Notifications.printInfo(log.getString(0, StandardCharsets.UTF_16LE));
			
			return success;
		} catch(Exception e) {
			e.printStackTrace();
			return false;
		}
		
	}
	
	/**
	 * Disconnects from a camera.
	 * 
	 * @param camera     One of the Cameras from getCameras().
	 * @return           True on success.
	 *                   False if the camera was not connected.
	 *                   False if the DLL was not loaded.
	 */
	public static synchronized boolean disconnect(Camera camera) {
		
		if(!enabled)
			return false;
		
		boolean success = WebcamDLL.disconnectCamera(camera.handle);
		
		// important: *MUST* disconnect() *BEFORE* freeing sharedMemory, or the JVM will crash!
		// because the frame event handler upcall uses sharedMemory, and the JVM will crash if an upcall occurs after closing that arena!
		camera.sharedMemory.get().close();
		camera.sharedMemory.set(null);
		return success;
		
	}
	/**
	 * Checks a camera for an event. An event usually indicates a fault condition.
	 * 
	 * @param camera     One of the Cameras from getCameras().
	 * @return           -1 if the camera is not connected.
	 *                   -1 if the DLL was not loaded.
	 *                   0  if no event has occurred.
	 *                   >0 (the event code) if an event has occurred.
	 *                   See https://learn.microsoft.com/en-us/windows/win32/directshow/event-notification-codes
	 */
	public static synchronized int checkForEvent(Camera camera) {
		
		if(!enabled)
			return -1;
		
		return WebcamDLL.checkForCameraEvent(camera.handle);
		
	}
	
	/**
	 * Adjusts a camera setting.
	 * 
	 * @param camera         One of the Cameras from getCameras().
	 * @param setting        One of the Settings in that Camera record.
	 * @param isManual       True to manually adjust this setting, or false to let the camera manage it automatically.
	 * @param manualValue    The new value to use if isManual is true.
	 * @return               True on success.
	 *                       False on error.
	 *                       False if the DLL was not loaded.
	 */
	public static synchronized boolean setSetting(Camera camera, Setting setting, boolean isManual, int manualValue) {
		
		if(!enabled)
			return false;
		
		return WebcamDLL.setCameraSetting(camera.handle, setting.cameraControlInterface ? 0 : 1, setting.interfaceEnum, isManual, manualValue);
		
	}
	
	/**
	 * Gets a camera setting.
	 * 
	 * @param camera     One of the Cameras from getCameras().
	 * @param setting    One of the Settings in that Camera record.
	 * @return           The low 32 bits contain the manualValue, and the high 32 bits contain the isManual boolean.
	 *                   Or -1 on error.
	 *                   Or -1 if the DLL was not loaded.
	 */
	public static synchronized long getSetting(Camera camera, Setting setting) {
		
		if(!enabled)
			return Integer.MIN_VALUE;
		
		return WebcamDLL.getCameraSetting(camera.handle, setting.cameraControlInterface ? 0 : 1, setting.interfaceEnum);
		
	}

}
