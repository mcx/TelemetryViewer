import java.awt.Dimension;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URLConnection;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.AbstractMap.SimpleEntry;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.imageio.ImageIO;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.TitledBorder;

import org.libjpegturbo.turbojpeg.TJ;
import org.libjpegturbo.turbojpeg.TJCompressor;
import org.libjpegturbo.turbojpeg.TJDecompressor;

import com.github.sarxos.webcam.Webcam;
import com.jogamp.common.nio.Buffers;

import net.miginfocom.swing.MigLayout;

public class ConnectionCamera extends Connection {

	// camera configuration, if local camera
	public WidgetCombobox<String> resolution;
	
	// camera configuration, if network camera
	public final boolean isMjpeg;
	public WidgetTextfield<String>     url;
	private WidgetSlider<Integer>      qualitySlider;
	private WidgetToggleButton<String> cameraButtons;
	private WidgetToggleButton<String> resolutionButtons;
	private WidgetToggleButton<String> lightButtons;
	private WidgetSlider<Integer>      zoomSlider;
	private WidgetToggleButton<String> focusMode;
	private WidgetSlider<Integer>      focusDistance;
	
	static final String mjpegOverHttp = "Cam: MJPEG over HTTP";
	
	// threading
	private AtomicInteger liveJpegThreads = new AtomicInteger(0); // currently encoding or decoding JPEGs
	
	// images in memory
	private volatile Frame liveImage = new Frame(null, true, 1, 1, "[not connected]", 0);
	private volatile Frame oldImage  = new Frame(null, true, 1, 1, "[no image]", 0);
	
	// images archived to disk
	private class FrameInfo {
		long timestamp; // when this frame was captured, milliseconds since 1970-01-01
		long offset;    // byte offset in the file where the JPEG starts
		int  length;    // byte count of the JPEG
		public FrameInfo(long timestamp, long offset, int length) { this.timestamp = timestamp; this.offset = offset; this.length = length; }
	}
	private volatile List<FrameInfo> framesIndex = Collections.synchronizedList(new ArrayList<FrameInfo>()); // index = frame number
	private FileChannel file;
	private final Path filePath = Paths.get("cache/" + this.toString() + ".mjpg");
	
	public ConnectionCamera(String cameraName) {
		
		if(cameraName != null) {
			name.set(cameraName);
		} else {
			// default to the first available camera
			List<String> usedNames = Connections.cameraConnections.stream().map(connection -> connection.name.get()).toList();
			String firstAvailableName = Connections.cameras.stream().filter(camName -> !usedNames.contains(camName)).findFirst().orElse(mjpegOverHttp);
			name.set(firstAvailableName);
		}
		
		isMjpeg = name.is(mjpegOverHttp);
		
		if(!isMjpeg) {
			
			resolution = new WidgetCombobox<String>(null, List.of("640 x 480", "1280 x 720", "1920 x 1080", "3840 x 2160"), "640 x 480")
			                 .setExportLabel("requested resolution");
			
			configWidgets.add(name);
			configWidgets.add(resolution);
			
		} else {
			
			url = WidgetTextfield.ofText("http://example.com:8080/video")
			                     .setExportLabel("url")
			                     .onEnter(event -> connect(true))
			                     .onChange((newValue, oldValue) -> {
			                         Charts.forEach(chart -> {
			                             if(chart instanceof OpenGLCameraChart cc)
			                                 if(cc.cameraName.is(oldValue))
			                                     cc.cameraName.set(newValue);
			                         });
			                         return true;
			                     });
			
			String ip = ConnectionTelemetry.localIp;
			if(ip.split("\\.").length == 4) // crude attempt to make the default URL be the IP address of the default gateway
				url.set("http://" + ip.substring(0, ip.lastIndexOf(".")) + ".1:8080/video");
			
			configWidgets.add(name);
			configWidgets.add(url);
			
			qualitySlider = WidgetSlider.ofInt("JPEG Quality", 1, 100, 50)
			                            .onChange(newValue -> httpPost("/settings/quality?set=" + newValue, false));
			
			cameraButtons = new WidgetToggleButton<String>(null, new String[] {"Front Camera", "Rear Camera"}, "Rear Camera")
			                    .setExportLabel("camera")
			                    .onChange((newValue, oldValue) -> {
			                         httpPost(newValue.equals("Rear Camera") ? "/settings/ffc?set=off" : "/settings/ffc?set=on", true);
			                         // each camera has separate settings for resolution/light/zoom/focus
			                         // so call the other handlers to ensure their settings get applied to the currently active camera
			                         resolutionButtons.callHandler();
			                         lightButtons.callHandler();
			                         zoomSlider.callHandler();
			                         focusMode.callHandler();
			                         focusDistance.callHandler();
			                         return true;
			                    });
			
			resolutionButtons = new WidgetToggleButton<String>(null, new String[] {"2160p", "1080p", "720p", "480p"}, "2160p")
			                        .setExportLabel("resolution")
			                        .onChange((newValue, oldValue) -> {
			                             httpPost(switch(newValue) {
			                                 case "2160p" -> "/settings/video_size?set=3840x2160";
			                                 case "1080p" -> "/settings/video_size?set=1920x1080";
			                                 case "720p"  -> "/settings/video_size?set=1280x720";
			                                 default      -> "/settings/video_size?set=640x480";
			                             }, false);
			                             return true;
			                        });
			
			lightButtons = new WidgetToggleButton<String>(null, new String[] {"Light On", "Light Off"}, "Light Off")
			                   .setExportLabel("light")
			                   .onChange((newValue, oldValue) -> {
			                        httpPost(newValue.equals("Light On") ? "/enabletorch" : "/disabletorch", false);
			                        return true;
			                   });
			
			zoomSlider = WidgetSlider.ofInt("Zoom", 0, 100, 0)
			                         .onChange(newValue -> httpPost("/ptz?zoom=" + newValue, false));
			
			focusMode = new WidgetToggleButton<String>("Focus", new String[] {"Smooth", "Fast", "Locked", "Manual"}, "Smooth")
			                .setExportLabel("focus mode")
			                .onChange((newValue, oldValue) -> {
			                    httpPost(switch(newValue) {
			                        case "Smooth" -> "/settings/focusmode?set=continuous-video";
			                        case "Fast"   -> "/settings/focusmode?set=continuous-picture";
			                        case "Locked" -> "/settings/focusmode?set=auto";
			                        case "Manual" -> "/settings/focusmode?set=off";
			                        default       -> "";
			                    }, false);
			                    focusDistance.setEnabled(newValue.equals("Manual"));
			                    return true;
			                });
			
			focusDistance = WidgetSlider.ofInt(null, 0, 1000, 500)
			                            .setExportLabel("manual focus distance")
			                            .onChange(newValue -> httpPost("/settings/focus_distance?set=%1.2f".formatted((1000 - newValue) / 100.0), false));
			
			transmitWidgets.add(qualitySlider);
			transmitWidgets.add(cameraButtons);
			transmitWidgets.add(resolutionButtons);
			transmitWidgets.add(lightButtons);
			transmitWidgets.add(zoomSlider);
			transmitWidgets.add(focusMode);
			transmitWidgets.add(focusDistance);
			
		}
		
		// create the cache file
		try {
			file = FileChannel.open(filePath, StandardOpenOption.CREATE,
			                                  StandardOpenOption.TRUNCATE_EXISTING,
			                                  StandardOpenOption.READ,
			                                  StandardOpenOption.WRITE);
		} catch(Exception e) {
			Notifications.showCriticalFault("Unable the create the cache file for " + getName() + "\n" + e.getMessage());
			e.printStackTrace();
		}
		
	}
	
	@Override public String getName() { return isMjpeg ? url.get() : name.get().substring(5); } // trim leading "Cam: "
	
	/**
	 * Sends a setting to a network camera.
	 * 
	 * @param relativeUrl    An empty HTTP POST message will be sent to this URL.
	 * @param blocking       If true, this method will block until the message is sent or a 3 second timeout occurs.
	 */
	private void httpPost(String relativeUrl, boolean blocking) {
		
		if(!isMjpeg || !isConnected() || Connections.importing)
			return;
		
		try {
			String fullUrl = url.get().substring(0, url.get().lastIndexOf("/")) + relativeUrl;
			var request = HttpRequest.newBuilder(URI.create(fullUrl))
			                         .POST(HttpRequest.BodyPublishers.ofString(""))
			                         .timeout(Duration.ofMillis(3000))
			                         .build();
			var client = HttpClient.newBuilder()
			                       .connectTimeout(Duration.ofMillis(3000))
			                       .build();
			if(blocking)
				client.send(request, HttpResponse.BodyHandlers.ofString());
			else
				client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
		} catch(Exception e) { }
		
	}
	
	@Override protected void connectToDevice(boolean interactive) {
	
		if(isMjpeg) {
			
			receiverThread = new Thread(() -> {
				
				InputStream is;
				
				// connect
				try {
					setStatus(Status.CONNECTING, false);
					URLConnection stream = new URI(url.get()).toURL().openConnection();
					stream.setConnectTimeout(1000);
					stream.setReadTimeout(5000);
					stream.connect();
					is = stream.getInputStream();
					SwingUtilities.invokeLater(() -> {
						// connecting will reset everything, so reconfigure the camera
						qualitySlider.callHandler();
						cameraButtons.callHandler();
					});
				} catch(Exception e) {
					disconnect("Unable to connect to " + getName() + ".", false);
					return;
				}
			
				// connected, enter an infinite loop that gets the frames
				try {
					
					setStatus(Status.CONNECTED, false);
					final StringBuilder buffer = new StringBuilder(5000);
					
					while(true) {
						
						// stop if requested
						if(!isConnected())
							throw new Exception();
						
						// wait for (and skip over) the content-type and content-length fields
						buffer.setLength(0);
						while(true) {
							int i = is.read();
							if(i == -1)
								throw new Exception();
							if(buffer.length() > 5000)
								throw new Exception();
							buffer.append((char) i);
							String text = buffer.toString().toLowerCase();
							if(text.contains("content-type: image/jpeg") && text.endsWith("content-length: "))
								break;
						}
						
						// get the content-length
						buffer.setLength(0);
						while(true) {
							int i = is.read();
							if(i == -1)
								throw new Exception();
							if(buffer.length() > 5000)
								throw new Exception();
							buffer.append((char) i);
							if(buffer.toString().endsWith("\r\n\r\n"))
								break;
						}
						int contentLength = Integer.parseInt(buffer.toString().trim());
						
						// get the JPEG bytes
						byte[] jpegBytes = new byte[contentLength];
						int bytesReceivedCount = 0;
						while(bytesReceivedCount < contentLength)
							bytesReceivedCount += is.read(jpegBytes, bytesReceivedCount, contentLength - bytesReceivedCount);
						
						// save and show the JPEG
						long timestamp = System.currentTimeMillis();
						saveJpeg(jpegBytes, timestamp);
						showJpeg(jpegBytes, timestamp);
						
					}
					
				} catch (Exception e) {
					
					while(liveJpegThreads.get() > 0); // wait
					try { is.close(); } catch(Exception e2) {}
					liveImage = new Frame(null, true, 1, 1, "[not connected]", 0);
					if(isConnected())
						disconnect("Error while reading from " + getName() + ".", false);
					
				}
				
			});
			receiverThread.setName("Camera Thread for " + getName());
			receiverThread.start();
			
		} else {

			receiverThread = new Thread(() -> {
				
				// check if the camera exists
				setStatus(Status.CONNECTING, false);
				Webcam camera = Webcam.getWebcamByName(getName());
				if(camera == null) {
					disconnect("Unable to connect to " + getName() + ".", false);
					return;
				}
				
				// check if the camera is already being used
				if(camera.isOpen()) {
					disconnect(getName() + " already in use.", false);
					return;
				}
				
				// the webcam library requires the requested resolution to be one of the predefined "custom view sizes"
				// so we must predefine all of the options shown by WidgetCamera
				Dimension[] sizes = new Dimension[] {
					new Dimension(640, 480),
					new Dimension(1280, 720),
					new Dimension(1920, 1080),
					new Dimension(3840, 2160)
				};
				camera.setCustomViewSizes(sizes);
				
				// request a resolution, open the camera, then get the actual resolution
				Dimension requestedResolution = switch(resolution.get()) { case "640 x 480"   -> sizes[0];
				                                                           case "1280 x 720"  -> sizes[1];
				                                                           case "1920 x 1080" -> sizes[2];
				                                                           case "3840 x 2160" -> sizes[3];
				                                                           default            -> sizes[3];};
				camera.setViewSize(requestedResolution);
				
				Dimension actualResolution;
				try {
					camera.open();
					actualResolution = camera.getViewSize();
				} catch(Exception e) {
					camera.close();
					disconnect("Unable to connect to " + getName() + ".", false);
					return;
				}
				
				// connected, enter an infinite loop that gets the frames
				try {
					
					setStatus(Status.CONNECTED, false);
					int frameCount = framesIndex.size();
					while(true) {
						
						// stop if requested
						if(!isConnected())
							throw new Exception();
						
						// stop if the connection failed
						if(!camera.isOpen())
							throw new Exception();
						
						// acquire a new image
						ByteBuffer buffer = Buffers.newDirectByteBuffer(actualResolution.width * actualResolution.height * 3);
						camera.getImageBytes(buffer);
						
						// save and show the image
						long timestamp = System.currentTimeMillis();
						saveImage(frameCount, buffer, actualResolution, timestamp);
						showImage(buffer, actualResolution, timestamp);
						frameCount++;
						
					}
					
				} catch(Exception e) {
					
					while(liveJpegThreads.get() > 0); // wait
					camera.close();
					liveImage = new Frame(null, true, 1, 1, "[not connected]", 0);
					if(isConnected())
						disconnect("Error while reading from " + getName() + ".", false);
					
				}
				
			});
			receiverThread.setName("Camera Thread for " + getName());
			receiverThread.start();
			
		}
		
	}
	
	@Override public JPanel getUpdatedTransmitGUI() {
		
		if(Connections.importing || !isMjpeg)
			return null;
		
		JPanel gui = new JPanel(new MigLayout("hidemode 3, fillx, wrap 1, insets " + Theme.padding + ", gap " + Theme.padding, "[fill,grow]"));
		String title = url.get() + (isConnected() ? "" : " (disconnected)");
		gui.setBorder(new TitledBorder(title));
		
		qualitySlider.appendTo(gui, "");
		cameraButtons.appendTo(gui, "");
		resolutionButtons.appendTo(gui, "");
		lightButtons.appendTo(gui, "");
		zoomSlider.appendTo(gui, "");
		focusMode.appendTo(gui, "");
		focusDistance.appendTo(gui, "");
		
		qualitySlider.setEnabled(isConnected());
		zoomSlider.setEnabled(isConnected());
		lightButtons.setEnabled(isConnected());
		cameraButtons.setEnabled(isConnected());
		resolutionButtons.setEnabled(isConnected());
		focusMode.setEnabled(isConnected());
		focusDistance.setEnabled(isConnected() && focusMode.is("Manual"));
			
		return gui;
		
	}
	
	/**
	 * @return    Approximate number of bytes that can be exported to a file. If connected, more bytes may be ready by the time exporting begins.
	 */
	public long getFileSize() {
		
		int frameCount = getSampleCount();
		return framesIndex.get(frameCount - 1).offset + framesIndex.get(frameCount - 1).length;
		
	}

	@Override public void dispose() {
		
		if(!isDisconnected())
			disconnect(null, true);
		
		// remove charts showing this camera
		Charts.removeIf(chart -> chart instanceof OpenGLCameraChart c && c.connection == this);
		
		// if this is the only connection, remove all charts, because there may be a timeline chart
		if(Connections.allConnections.size() == 1)
			Charts.removeAll();
		
		try {
			file.close();
			Files.deleteIfExists(filePath);
			framesIndex.clear();
		} catch(Exception e) {
			Notifications.showCriticalFault("Unable the delete the cache file for " + getName() + "\n" + e.getMessage());
			e.printStackTrace();
		}
		
	}

	@Override public void removeAllData() {
		
		try {
			file.truncate(0);
			framesIndex.clear();
		} catch(Exception e) {
			Notifications.showCriticalFault("Unable the clear the cache file for " + getName() + "\n" + e.getMessage());
			e.printStackTrace();
		}
		
		Connections.GUI.redraw();
		OpenGLCharts.GUI.setPlayLive();
		
	}
	
	/**
	 * Gets the timestamp of the closest frame at or before a specified timestamp.
	 * 
	 * @param timestamp    Desired timestamp.
	 * @return             Closest matching timestamp at or before the desired timestamp,
	 *                     or 0 if there are no frames or every frame is *after* the desired timestamp.
	 */
	public long getClosestTimestampAtOrBefore(long timestamp) {

		// get the closest frame number
		for(int frameNumber = framesIndex.size() - 1; frameNumber >= 0; frameNumber--)
			if(framesIndex.get(frameNumber).timestamp <= timestamp)
				return framesIndex.get(frameNumber).timestamp;
		
		// edge cases: no frames, or every frame is *after* the desired timestamp
		return 0;
		
	}
	
	/**
	 * Gets the timestamp of the closest frame at or after a specified timestamp.
	 * 
	 * @param timestamp    Desired timestamp.
	 * @return             Closest matching timestamp at or after the desired timestamp,
	 *                     or 0 if there are no frames or every frame is *before* the desired timestamp.
	 */
	public long getClosestTimestampAtOrAfter(long timestamp) {

		// get the closest frame number
		for(int frameNumber = 0; frameNumber < framesIndex.size(); frameNumber++)
			if(framesIndex.get(frameNumber).timestamp >= timestamp)
				return framesIndex.get(frameNumber).timestamp;
		
		// edge cases: no frames, or every frame is *before* the desired timestamp
		return 0;
		
	}
	
	/**
	 * Gets the closest image at or just before a certain moment in time.
	 * 
	 * @param timestamp    The moment in time (milliseconds since 1970-01-01.)
	 * @return             The image and related information, as a Frame object.
	 */
	public Frame getImageAtOrBeforeTimestamp(long timestamp) {
		
		// give up if there's no images
		if(framesIndex.isEmpty())
			return new Frame(null, true, 1, 1, isConnected() ? "[no image]" : "[not connected]", 0);
		
		// determine the frame index
		int frameIndex = framesIndex.size() - 1;
		long frameTimestamp = framesIndex.get(frameIndex).timestamp;
		
		for(int i = frameIndex; i >= 0; i--) {
			long timestamp2 = framesIndex.get(i).timestamp;
			if(timestamp2 <= timestamp) {
				frameIndex = i;
				frameTimestamp = timestamp2;
				break;
			}
		}
		
		// give up if there's no frame before the specified timestamp
		if(frameTimestamp > timestamp)
			return new Frame(null, true, 1, 1, "[no image]", 0);
		
		// return the live image if appropriate
		// when importing we always want the label to show that frame's timestamp, so the liveImage is never updated or used while importing
		if(!Connections.importing && (frameTimestamp == liveImage.timestamp || frameIndex == framesIndex.size() - 1))
			return liveImage;
		
		// return the live image even if it's up to 100ms in the past or future
		// this prevents flickering between the live image and the previous frame when the requested timestamp is really close
		if(!Connections.importing && Math.abs(timestamp - liveImage.timestamp) <= 100)
			return liveImage;
		
		// return cached image if appropriate
		if(frameTimestamp == oldImage.timestamp)
			return oldImage;
		
		// obtain and decompress the jpeg
		FrameInfo info = framesIndex.get(frameIndex);
		byte[] jpegBytes = new byte[info.length];
		try {
			file.read(ByteBuffer.wrap(jpegBytes), info.offset);
		} catch(Exception e) {
			e.printStackTrace();
			return new Frame(null, true, 1, 1, "[error reading image from disk]", 0);
		}
		
		int width = 0;
		int height = 0;
		byte[] bgrBytes = null;
		String label = String.format("%s (%s)", getName(), Settings.formatCameraTimestamp(info.timestamp));
		
		try {
			// try to use the libjpeg-turbo library
			TJDecompressor tjd = new TJDecompressor(jpegBytes);
			width = tjd.getWidth();
			height = tjd.getHeight();
			bgrBytes = new byte[width * height * 3];
			tjd.decompress(bgrBytes, 0, 0, width, 0, height, TJ.PF_BGR, 0);
			tjd.close();
			oldImage = new Frame(bgrBytes, true, width, height, label, info.timestamp);
			return oldImage;
		} catch(Error | Exception e) {
			// fallback to the JRE library
			try {
				BufferedImage bi = ImageIO.read(new ByteArrayInputStream(jpegBytes));
				width = bi.getWidth();
				height = bi.getHeight();
				bgrBytes = ((DataBufferByte)bi.getRaster().getDataBuffer()).getData();
				oldImage = new Frame(bgrBytes, true, width, height, label, info.timestamp);
				return oldImage;
			} catch(Exception e2) {
				e.printStackTrace();
				return new Frame(null, true, 1, 1, "[error decoding image]", 0);
			}
		}
		
	}

	@Override public void importFrom(Connections.QueueOfLines lines) throws AssertionError {
		
		configWidgets.stream().skip(1).forEach(widget -> widget.importFrom(lines));
		transmitWidgets.forEach(widget -> widget.importFrom(lines));
		Connections.GUI.redraw();
		
	}

	@Override public void exportTo(PrintWriter file) {
		
		configWidgets.forEach(widget -> widget.exportTo(file));
		transmitWidgets.forEach(widget -> widget.exportTo(file));
		file.println("");
		
	}

	@Override public long readFirstTimestamp(String path) {
		
		long timestamp = Long.MAX_VALUE;
		
		try {
			timestamp = new Mkv().parseFile(path).indexBuffer.getLong();
		} catch(AssertionError | Exception e) {
			Notifications.showFailureForMilliseconds("Error while parsing the MKV file " + path + "\n" + e.getMessage(), 5000, true);
		}
		
		return timestamp;
		
	}

	@Override public long getTimestamp(int sampleNumber) {

		return framesIndex.get(sampleNumber).timestamp;
		
	}
	
	@Override public long getFirstTimestamp() {
		
		return framesIndex.isEmpty() ? 0 : framesIndex.get(0).timestamp;
		
	}
	
	@Override public long getLastTimestamp() {
		
		return framesIndex.isEmpty() ? 0 : framesIndex.get(framesIndex.size() - 1).timestamp;
		
	}
	
	@Override public int getSampleCount() {
		
		return framesIndex.size();
		
	}

	@Override public void connectToFile(String path, long firstTimestamp, long beginImportingTimestamp, AtomicLong completedByteCount) {
		
		receiverThread = new Thread(() -> {
			
			// open the MKV file and read the frames index from it
			setStatus(Status.CONNECTING, false);
			ByteBuffer framesIndexBuffer;
			FileChannel mkv;
			try {
				MkvDetails details = new Mkv().parseFile(path);
				framesIndexBuffer = details.indexBuffer;
				name.set(details.connectionName);
				mkv = FileChannel.open(Paths.get(path), StandardOpenOption.READ);
			} catch(Exception e) {
				disconnect("Error while reading MKV file for " + getName() + ".", false);
				return;
			}
			setStatus(Status.CONNECTED, false);
			
			try {
				
				int frameCount = framesIndexBuffer.capacity() / 20;
				for(int i = 0; i < frameCount; i++) {
					
					// stop if requested
					if(!isConnected())
						break;
					
					long timestamp = framesIndexBuffer.getLong();
					long offset = framesIndexBuffer.getLong();
					int length = framesIndexBuffer.getInt();
					
					if(Connections.realtimeImporting) {
						long delay = (timestamp - firstTimestamp) - (System.currentTimeMillis() - beginImportingTimestamp);
						if(delay > 0)
							try { Thread.sleep(delay); } catch(Exception e) { }
					}
					
					byte[] buffer = new byte[length];
					mkv.read(ByteBuffer.wrap(buffer), offset);
					saveJpeg(buffer, timestamp);
					completedByteCount.addAndGet(length);
					
				}
				
				// done
				mkv.close();
				disconnect(null, false);
				
			} catch(Exception e) {
				
				try { mkv.close(); } catch(Exception e2) {}
				disconnect("Error while reading MKV file for " + getName() + ".", false);
				
			}
		});
		receiverThread.setPriority(Thread.MAX_PRIORITY);
		receiverThread.setName("MKV File Import Thread");
		receiverThread.start();
		
	}

	/**
	 * Saves all images to a MJPG file, and saves the corresponding index data to a BIN file.
	 * 
	 * @param path                  Path to the file.
	 * @param completedByteCount    Variable to increment as progress is made (this is periodically queried by a progress bar.)
	 */
	@Override public void exportDataFile(String path, AtomicLong completedByteCount) {
		
		new Mkv().exportFile(framesIndex, liveImage.width, liveImage.height, getName(), file, path, completedByteCount);
		
	}
	
	/**
	 * Appends a new image to the dataset.
	 * 
	 * @param jpegBytes    The image, as a JPEG.
	 * @param timestamp    When the image was captured (milliseconds since 1970-01-01.)
	 */
	private void saveJpeg(byte[] jpegBytes, long timestamp) {
		
		try {
			long offset = file.size();
			if(getSampleCount() == 1)
				Connections.GUI.redraw();
			file.write(ByteBuffer.wrap(jpegBytes));
//			file.force(true); // not necessary, and massively slows down importing
			framesIndex.add(new FrameInfo(timestamp, offset, jpegBytes.length));
		} catch(Exception e) {
			Notifications.showCriticalFault("Unable to save to the cache file for " + getName() + "\n" + e.getMessage());
			e.printStackTrace();
			return;
		}
		
	}
	
	/**
	 * Spawns a new thread that will decode a JPEG image and update the liveImage object.
	 * If there is a backlog of images to decode, this image may be skipped.
	 * 
	 * @param jpegBytes    The image to decode.
	 * @param timestamp    When the image was captured (milliseconds since 1970-01-01.)
	 */
	private void showJpeg(byte[] jpegBytes, long timestamp) {
		
		// skip this frame if backlogged
		int threadCount = liveJpegThreads.incrementAndGet();
		if(threadCount > 5) {
			liveJpegThreads.decrementAndGet();
			return;
		}
		
		new Thread(() -> {
			try {
				
				int width = 0;
				int height = 0;
				byte[] bgrBytes = null;
				
				try {
					// try to use the libjpeg-turbo library
					TJDecompressor tjd = new TJDecompressor(jpegBytes);
					width = tjd.getWidth();
					height = tjd.getHeight();
					bgrBytes = new byte[width * height * 3];
					tjd.decompress(bgrBytes, 0, 0, width, 0, height, TJ.PF_BGR, 0);
					tjd.close();
				} catch(Error | Exception e) {
					// fallback to the JRE library
					BufferedImage bi = ImageIO.read(new ByteArrayInputStream(jpegBytes));
					width = bi.getWidth();
					height = bi.getHeight();
					bgrBytes = ((DataBufferByte)bi.getRaster().getDataBuffer()).getData();
				}
				
				// update the liveImage object
				int frameCount = framesIndex.size();
				double fps = 0;
				if(frameCount > 30)
					fps = 30000.0 / (double) (framesIndex.get(frameCount - 1).timestamp - framesIndex.get(frameCount - 30).timestamp);
				String label = String.format("%s (%d x %d, %01.1f FPS)", getName(), width, height, fps);
				liveImage = new Frame(bgrBytes, true, width, height, label, timestamp);
				
				liveJpegThreads.decrementAndGet();
				
			} catch(Exception e) {
				
				Notifications.showFailureForMilliseconds("Unable to decode one of the frames from " + getName() + "\n" + e.getMessage(), 5000, true);
				e.printStackTrace();
				liveJpegThreads.decrementAndGet();
				
			}
		}).start();
		
	}
	
	/**
	 * Spawns a new thread that will encode a raw image into a JPEG and store that in the dataset.
	 * 
	 * @param frameNumber    The frame number.
	 * @param image          The image.
	 * @param resolution     Size of the image, in pixels.
	 * @param timestamp      When the image was captured (milliseconds since 1970-01-01.)
	 */
	private void saveImage(int frameNumber, ByteBuffer image, Dimension resolution, long timestamp) {
		
		byte[] bytes = new byte[image.capacity()];
		image.get(bytes);
		liveJpegThreads.incrementAndGet();
		
		new Thread(() -> {
			
			byte[] jpegBytes = null;
			int jpegBytesLength = 0;
			
			try {
				// try to use the libjpeg-turbo library
				TJCompressor tjc = new TJCompressor(bytes, 0, 0, resolution.width, 0, resolution.height, TJ.PF_RGB);
				tjc.setJPEGQuality(80);
				tjc.setSubsamp(TJ.SAMP_422);
				jpegBytes = tjc.compress(0);
				jpegBytesLength = tjc.getCompressedSize();
				tjc.close();
			} catch(Error | Exception e) {
				// fallback to the JRE library
				try {
					// convert rgb to bgr
					for(int i = 0; i < bytes.length; i +=3) {
						byte red  = bytes[i];
						byte blue = bytes[i+2];
						bytes[i]   = blue;
						bytes[i+2] = red;
					}
					BufferedImage bi = new BufferedImage(resolution.width, resolution.height, BufferedImage.TYPE_3BYTE_BGR);
					bi.setData(Raster.createRaster(bi.getSampleModel(), new DataBufferByte(bytes, bytes.length), new Point()));
					ByteArrayOutputStream baos = new ByteArrayOutputStream();
					ImageIO.write(bi, "jpg", baos);
					jpegBytes = baos.toByteArray();
					jpegBytesLength = jpegBytes.length;
					baos.close();
				} catch(Exception e2) {
					Notifications.showFailureForMilliseconds("Unable to encode one of the frames from " + getName() + "\n" + e.getMessage(), 5000, true);
					e.printStackTrace();
					liveJpegThreads.decrementAndGet();
					return;
				}
			}
			
			// wait for previous encoding threads to finish before inserting this frame
			while(framesIndex.size() < frameNumber);
			
			// save to disk
			try {
				long offset = file.size();
				if(getSampleCount() == 1)
					Connections.GUI.redraw();
				file.write(ByteBuffer.wrap(jpegBytes, 0, jpegBytesLength));
				file.force(true);
				framesIndex.add(new FrameInfo(timestamp, offset, jpegBytesLength));
			} catch (Exception e) {
				Notifications.showCriticalFault("Unable to save one of the frames from " + getName() + "\n" + e.getMessage());
				e.printStackTrace();
				liveJpegThreads.decrementAndGet();
				return;
			}
			
			liveJpegThreads.decrementAndGet();
			
		}).start();
		
	}
	
	/**
	 * Updates the liveImage object with a new image.
	 * 
	 * @param image         The image.
	 * @param resolution    Size of the image, in pixels.
	 * @param timestamp     When the image was captured (milliseconds since 1970-01-01.)
	 */
	private void showImage(ByteBuffer image, Dimension resolution, long timestamp) {
		
		int frameCount = framesIndex.size();
		double fps = 0;
		if(frameCount > 30)
			fps = 30000.0 / (double) (framesIndex.get(frameCount - 1).timestamp - framesIndex.get(frameCount - 30).timestamp);
		String label = String.format("%s (%d x %d, %01.1f FPS)", getName(), resolution.width, resolution.height, fps);
		
		image.rewind();
		liveImage = new Frame(image, resolution.width, resolution.height, label, timestamp);
		
	}
	
	/**
	 * Frames to be shown on screen are stored in an object with all relevant data.
	 */
	public static class Frame {
		ByteBuffer buffer;
		boolean isBgr; // if buffer uses the BGR or RGB pixel format
		int width;
		int height;
		String label;
		long timestamp;
		
		public Frame(byte[] bytes, boolean isBgr, int width, int height, String label, long timestamp) {
			this.buffer = Buffers.newDirectByteBuffer(width * height * 3);
			this.isBgr = isBgr;
			this.width = width;
			this.height = height;
			this.label = label;
			this.timestamp = timestamp;
			if(bytes != null) {
				buffer.put(bytes);
				buffer.rewind();
			} else {
				byte black = 0;
				for(int i = 0; i < width*height*3; i++)
					buffer.put(black);
				buffer.rewind();
			}
		}
		
		public Frame(ByteBuffer bytes, int width, int height, String label, long timestamp) {
			this.buffer = bytes;
			this.isBgr = false;
			this.width = width;
			this.height = height;
			this.label = label;
			this.timestamp = timestamp;
		}
	}
	
	public static class MkvDetails {
		
		String connectionName;
		ByteBuffer indexBuffer;
		
	}
	
	public static class Mkv {
		
		private FileChannel inputFile;
		private FileChannel outputFile;
		private Stack<Map.Entry<Long, Long>> exportTagSizes = new Stack<Map.Entry<Long, Long>>(); // keys are byte offsets into the MKV file
		                                                                                          // values are tag sizes (byte counts, not EBML encoded.)
		private Stack<Long> importTagSizes = new Stack<Long>();
		
		// EBML tags
		private static final byte[] EBML                 = new byte[] {(byte) 0x1A, (byte) 0x45, (byte) 0xDF, (byte) 0xA3};
		private static final byte[] EBML_VERSION         = new byte[] {(byte) 0x42, (byte) 0x86};
		private static final byte[] EBML_READ_VERSION    = new byte[] {(byte) 0x42, (byte) 0xF7};
		private static final byte[] EBML_MAX_ID_LENGTH   = new byte[] {(byte) 0x42, (byte) 0xF2};
		private static final byte[] EBML_MAX_SIZE_LENGTH = new byte[] {(byte) 0x42, (byte) 0xF3};
		private static final byte[] DOCTYPE              = new byte[] {(byte) 0x42, (byte) 0x82};
		private static final byte[] DOCTYPE_VERSION      = new byte[] {(byte) 0x42, (byte) 0x87};
		private static final byte[] DOCTYPE_READ_VERSION = new byte[] {(byte) 0x42, (byte) 0x85};
		private static final byte[] SEGMENT              = new byte[] {(byte) 0x18, (byte) 0x53, (byte) 0x80, (byte) 0x67};
		private static final byte[] SEEK_HEAD            = new byte[] {(byte) 0x11, (byte) 0x4D, (byte) 0x9B, (byte) 0x74};
		private static final byte[] SEEK                 = new byte[] {(byte) 0x4D, (byte) 0xBB};
		private static final byte[] SEEK_ID              = new byte[] {(byte) 0x53, (byte) 0xAB};
		private static final byte[] SEEK_POSITION        = new byte[] {(byte) 0x53, (byte) 0xAC};
		private static final byte[] INFO                 = new byte[] {(byte) 0x15, (byte) 0x49, (byte) 0xA9, (byte) 0x66};
		private static final byte[] TIMPSTAMP_SCALE      = new byte[] {(byte) 0x2A, (byte) 0xD7, (byte) 0xB1};
		private static final byte[] MUXING_APP           = new byte[] {(byte) 0x4D, (byte) 0x80};
		private static final byte[] WRITING_APP          = new byte[] {(byte) 0x57, (byte) 0x41};
		private static final byte[] DURATION             = new byte[] {(byte) 0x44, (byte) 0x89};
		private static final byte[] TRACKS               = new byte[] {(byte) 0x16, (byte) 0x54, (byte) 0xAE, (byte) 0x6B};
		private static final byte[] TRACK_ENTRY          = new byte[] {(byte) 0xAE};
		private static final byte[] TRACK_NUMBER         = new byte[] {(byte) 0xD7};
		private static final byte[] TRACK_UID            = new byte[] {(byte) 0x73, (byte) 0xC5};
		private static final byte[] TRACK_TYPE           = new byte[] {(byte) 0x83};
		private static final byte[] FLAG_LACING          = new byte[] {(byte) 0x9C};
		private static final byte[] CODEC_ID             = new byte[] {(byte) 0x86};
		private static final byte[] VIDEO                = new byte[] {(byte) 0xE0};
		private static final byte[] FLAG_INTERLACED      = new byte[] {(byte) 0x9A};
		private static final byte[] FIELD_ORDER          = new byte[] {(byte) 0x9D};
		private static final byte[] PIXEL_WIDTH          = new byte[] {(byte) 0xB0};
		private static final byte[] PIXEL_HEIGHT         = new byte[] {(byte) 0xBA};
		private static final byte[] ATTACHMENTS          = new byte[] {(byte) 0x19, (byte) 0x41, (byte) 0xA4, (byte) 0x69};
		private static final byte[] ATTACHED_FILE        = new byte[] {(byte) 0x61, (byte) 0xA7};
		private static final byte[] FILE_DESCRIPTION     = new byte[] {(byte) 0x46, (byte) 0x7E};
		private static final byte[] FILE_NAME            = new byte[] {(byte) 0x46, (byte) 0x6E};
		private static final byte[] MIME_TYPE            = new byte[] {(byte) 0x46, (byte) 0x60};
		private static final byte[] FILE_UID             = new byte[] {(byte) 0x46, (byte) 0xAE};
		private static final byte[] FILE_DATA            = new byte[] {(byte) 0x46, (byte) 0x5C};
		private static final byte[] CLUSTER              = new byte[] {(byte) 0x1F, (byte) 0x43, (byte) 0xB6, (byte) 0x75};
		private static final byte[] TIMESTAMP            = new byte[] {(byte) 0xE7};
		private static final byte[] CUES                 = new byte[] {(byte) 0x1C, (byte) 0x53, (byte) 0xBB, (byte) 0x6B};
		private static final byte[] CUE_POINT            = new byte[] {(byte) 0xBB};
		private static final byte[] CUE_TIME             = new byte[] {(byte) 0xB3};
		private static final byte[] CUE_TRACK_POSITIONS  = new byte[] {(byte) 0xB7};
		private static final byte[] CUE_TRACK            = new byte[] {(byte) 0xF7};
		private static final byte[] CUE_CLUSTER_POSITION = new byte[] {(byte) 0xF1};
		
		/**
		 * Exports the acquired images to an MKV file (containing an MJPEG video stream with no audio.)
		 * 
		 * @param frameCount            How many frames to export.
		 * @param path                  Destination file path.
		 * @param completedByteCount    Variable to increment as progress is made (this is periodically queried by a progress bar.)
		 */
		public void exportFile(List<FrameInfo> framesIndex, int widthPixels, int heightPixels, String connectionName, FileChannel sourceFile, String path, AtomicLong completedByteCount) {
			
			// dev notes:
			//
			// use "mkvalidator.exe file.mkv" to test files:
			// https://www.matroska.org/downloads/mkvalidator.html
			//
			// use "mkvinfo.exe file.mkv -a -p" to print a tree interpretation of a file:
			// https://www.matroska.org/downloads/mkvtoolnix.html
			//
			// information about the MKV file format:
			// https://matroska-org.github.io/libebml/specs.html
			// https://www.matroska.org/technical/diagram.html
			// https://www.matroska.org/technical/elements.html
			
			// sanity check
			int frameCount = framesIndex.size();
			if(frameCount < 1)
				return;
			
			long duration = framesIndex.get(frameCount - 1).timestamp - framesIndex.get(0).timestamp;
			path += ".mkv";
			
			// general purpose buffer used for several things
			ByteBuffer buffer = Buffers.newDirectByteBuffer(frameCount * 20);
			buffer.order(ByteOrder.BIG_ENDIAN);
			
			// write the file
			try {
			
				outputFile = FileChannel.open(Paths.get(path), StandardOpenOption.CREATE,
				                                               StandardOpenOption.TRUNCATE_EXISTING,
				                                               StandardOpenOption.WRITE);
				
				// trying to visually separate the Java logic from the MKV logic, so bear with the weird whitespace below
				// --- Java logic here ----------- | --- MKV logic here --------------------------------------------------
				                                     openTag(EBML);
				                                         putTag(EBML_VERSION, 1);
				                                         putTag(EBML_READ_VERSION, 1);
				                                         putTag(EBML_MAX_ID_LENGTH, 4);
				                                         putTag(EBML_MAX_SIZE_LENGTH, 8);
				                                         putTag(DOCTYPE, "matroska".getBytes());
				                                         putTag(DOCTYPE_VERSION, 4);
				                                         putTag(DOCTYPE_READ_VERSION, 2);
				                                     closeTag();
				
				long segmentContentsOffset =         openTag(SEGMENT);
				                                         openTag(SEEK_HEAD);
				                                             openTag(SEEK);
				                                                 putTag(SEEK_ID, INFO);
				long infoSeekPositionOffset =                    putTag(SEEK_POSITION, 0xFFFFFFFFFFFFFFFFL); // placeholder
				                                             closeTag();
				                                             openTag(SEEK);
				                                                 putTag(SEEK_ID, TRACKS);
				long tracksSeekPositionOffset =                  putTag(SEEK_POSITION, 0xFFFFFFFFFFFFFFFFL); // placeholder
				                                             closeTag();
				                                             openTag(SEEK);
				                                                 putTag(SEEK_ID, ATTACHMENTS);
				long attachmentsSeekPositionOffset =             putTag(SEEK_POSITION, 0xFFFFFFFFFFFFFFFFL); // placeholder
				                                             closeTag();
				                                             openTag(SEEK);
				                                                 putTag(SEEK_ID, CUES);
				long cuesSeekPositionOffset =                    putTag(SEEK_POSITION, 0xFFFFFFFFFFFFFFFFL); // placeholder
				                                             closeTag();
				long infoOffset =                        closeTag();
				                                         openTag(INFO);
				                                             putTag(TIMPSTAMP_SCALE, 1000000);
				                                             putTag(MUXING_APP, Main.versionString.getBytes());
				                                             putTag(WRITING_APP, Main.versionString.getBytes());
				                                             putTag(DURATION, buffer.putFloat((float) duration).flip());
				long tracksOffset =                      closeTag();
				                                         openTag(TRACKS);
				                                             openTag(TRACK_ENTRY);
				                                                 putTag(TRACK_NUMBER, 1);
				                                                 putTag(TRACK_UID, 1);
				                                                 putTag(TRACK_TYPE, 1);
				                                                 putTag(FLAG_LACING, 0);
				                                                 putTag(CODEC_ID, "V_MJPEG".getBytes());
				                                                 openTag(VIDEO);
				                                                     putTag(FLAG_INTERLACED, 2);
				                                                     putTag(FIELD_ORDER, 0);
				                                                     putTag(PIXEL_WIDTH,  widthPixels);
				                                                     putTag(PIXEL_HEIGHT, heightPixels);
				                                                 closeTag();
				                                             closeTag();
				long attachmentsOffset =                 closeTag();
				                                         openTag(ATTACHMENTS);
				                                             openTag(ATTACHED_FILE);
				                                                 putTag(FILE_DESCRIPTION, "connection name".getBytes());
				                                                 putTag(FILE_NAME, "name.bin".getBytes());
				                                                 putTag(MIME_TYPE, "application/x-telemetryviewer".getBytes());
				                                                 putTag(FILE_UID, 1);
				                                                 putTag(FILE_DATA, connectionName.getBytes());
				                                             closeTag();
				                                             openTag(ATTACHED_FILE);
				                                                 putTag(FILE_DESCRIPTION, "frames index data".getBytes());
				                                                 putTag(FILE_NAME, "index.bin".getBytes());
				                                                 putTag(MIME_TYPE, "application/x-telemetryviewer".getBytes());
				                                                 putTag(FILE_UID, 2);
				long framesIndexOffset =                         putTag(FILE_DATA, buffer.limit(frameCount * 20).position(frameCount * 20).flip()); // placeholder
				                                             closeTag();
				                                         closeTag();
				
				long firstTimestamp = framesIndex.get(0).timestamp;
					
				// using one Cluster per frame, and one CuePoint per Cluster
				long[] clusterOffset  = new long[frameCount];
				long[] frameTimestamp = new long[frameCount];
				int frameN = 0;
				long cuesOffset = 0;
				buffer.flip();
				while(frameN < frameCount) {
					FrameInfo frame = framesIndex.get(frameN);
					clusterOffset[frameN] = outputFile.size() - segmentContentsOffset;
					frameTimestamp[frameN] = frame.timestamp - firstTimestamp;
					
					                                     openTag(CLUSTER);
					                                         putTag(TIMESTAMP, frameTimestamp[frameN]);
					long mkvFileOffset =                     putSimpleBlock(0, frame.length, frame.offset, sourceFile);
					cuesOffset =                         closeTag();
					
					buffer.putLong(frame.timestamp);
					buffer.putLong(mkvFileOffset);
					buffer.putInt(frame.length);
					
					completedByteCount.addAndGet(frame.length);
					frameN++;
					if(frameN % 30 == 0)
						outputFile.force(true);
				}
					
				                                         openTag(CUES);
				frameN = 0;
				while(frameN < frameCount) {
				                                             openTag(CUE_POINT);
				                                                 putTag(CUE_TIME, frameTimestamp[frameN]);
				                                                 openTag(CUE_TRACK_POSITIONS);
				                                                     putTag(CUE_TRACK, 1);
				                                                     putTag(CUE_CLUSTER_POSITION, clusterOffset[frameN++]);
				                                                 closeTag();
				                                             closeTag();
				}
				                                         closeTag();
				                                     closeTag();
				
				// update the placeholders for framesIndex and SeekHead
				outputFile.write(buffer.flip(),                                                           framesIndexOffset);
				outputFile.write(buffer.flip().putLong(infoOffset        - segmentContentsOffset).flip(), infoSeekPositionOffset);
				outputFile.write(buffer.flip().putLong(tracksOffset      - segmentContentsOffset).flip(), tracksSeekPositionOffset);
				outputFile.write(buffer.flip().putLong(attachmentsOffset - segmentContentsOffset).flip(), attachmentsSeekPositionOffset);
				outputFile.write(buffer.flip().putLong(cuesOffset        - segmentContentsOffset).flip(), cuesSeekPositionOffset);
				
				// done
				outputFile.close();
			
			} catch(IOException e) {
				
				Notifications.showFailureForMilliseconds("Error while exporting file " + path + "\n" + e.getMessage(), 5000, false);
				e.printStackTrace();
				try { outputFile.close(); } catch(Exception e2) { }
				
			}
			
		}
		
		/**
		 * Parses the connection name and frames index from an MKV file that was previously exported from TelemetryViewer.
		 * This is NOT a general-purpose parser, it is only intended to be used with files generated by TelemetryViewer.
		 */
		public MkvDetails parseFile(String path) throws AssertionError, IOException {
			
			inputFile = FileChannel.open(Paths.get(path), StandardOpenOption.READ);
			
			// trying to visually separate the Java logic from the MKV logic, so bear with the weird whitespace below
			// --- Java logic here ----------- | --- MKV logic here --------------------------------------------------
			                                     assertTagOpened(EBML);
			                                         assertTagFound(EBML_VERSION, 1);
			                                         assertTagFound(EBML_READ_VERSION, 1);
			                                         assertTagFound(EBML_MAX_ID_LENGTH, 4);
			                                         assertTagFound(EBML_MAX_SIZE_LENGTH, 8);
			                                         assertTagFound(DOCTYPE, "matroska".getBytes());
			                                         assertTagFound(DOCTYPE_VERSION, 4);
			                                         assertTagFound(DOCTYPE_READ_VERSION, 2);
			                                     assertTagClosed();
			                                     
			                                     assertTagOpened(SEGMENT);
			                                         assertTagOpened(SEEK_HEAD); // 168
			                                             assertTagOpened(SEEK); // 10
			                                                 assertTagFound(SEEK_ID, INFO); // 14
			                                                 assertTagFound(SEEK_POSITION); // 18
			                                             assertTagClosed();
			                                             assertTagOpened(SEEK);
			                                                 assertTagFound(SEEK_ID, TRACKS);
			                                                 assertTagFound(SEEK_POSITION);
			                                             assertTagClosed();
			                                             assertTagOpened(SEEK);
			                                                 assertTagFound(SEEK_ID, ATTACHMENTS);
			                                                 assertTagFound(SEEK_POSITION);
			                                             assertTagClosed();
			                                             assertTagOpened(SEEK);
			                                                 assertTagFound(SEEK_ID, CUES);
			                                                 assertTagFound(SEEK_POSITION);
			                                             assertTagClosed();
			                                         assertTagClosed();
			                                         assertTagOpened(INFO);
			                                             assertTagFound(TIMPSTAMP_SCALE, 1000000);
			                                             assertTagFound(MUXING_APP, Main.versionString.getBytes());
			                                             assertTagFound(WRITING_APP, Main.versionString.getBytes());
			                                             assertTagFound(DURATION);
			                                         assertTagClosed();
			                                         assertTagOpened(TRACKS);
			                                             assertTagOpened(TRACK_ENTRY);
			                                                 assertTagFound(TRACK_NUMBER, 1);
			                                                 assertTagFound(TRACK_UID, 1);
			                                                 assertTagFound(TRACK_TYPE, 1);
			                                                 assertTagFound(FLAG_LACING, 0);
			                                                 assertTagFound(CODEC_ID, "V_MJPEG".getBytes());
			                                                 assertTagOpened(VIDEO);
			                                                     assertTagFound(FLAG_INTERLACED, 2);
			                                                     assertTagFound(FIELD_ORDER, 0);
			                                                     assertTagFound(PIXEL_WIDTH);
			                                                     assertTagFound(PIXEL_HEIGHT);
			                                                 assertTagClosed();
			                                             assertTagClosed();
			                                         assertTagClosed();
			                                         assertTagOpened(ATTACHMENTS);
			                                             assertTagOpened(ATTACHED_FILE);
			                                                 assertTagFound(FILE_DESCRIPTION, "connection name".getBytes());
			                                                 assertTagFound(FILE_NAME, "name.bin".getBytes());
			                                                 assertTagFound(MIME_TYPE, "application/x-telemetryviewer".getBytes());
			                                                 assertTagFound(FILE_UID, 1);
			ByteBuffer nameBuffer =                          assertTagFoundData(FILE_DATA);
			                                             assertTagClosed();
			                                             assertTagOpened(ATTACHED_FILE);
			                                                 assertTagFound(FILE_DESCRIPTION, "frames index data".getBytes());
			                                                 assertTagFound(FILE_NAME, "index.bin".getBytes());
			                                                 assertTagFound(MIME_TYPE, "application/x-telemetryviewer".getBytes());
			                                                 assertTagFound(FILE_UID, 2);
			ByteBuffer indexBuffer =                         assertTagFoundData(FILE_DATA);
			
			inputFile.close();
			
			byte[] text = new byte[nameBuffer.capacity()];
			nameBuffer.get(text);
			
			MkvDetails details = new MkvDetails();
			details.connectionName = new String(text);
			details.indexBuffer = indexBuffer;
			return details;
			
		}
		
		/**
		 * "Opens" a tag. This writes the tag ID to the file, and an empty placeholder where the size will be written after the tag is "closed."
		 * 
		 * @param tagId    The tag ID.
		 * @return         File offset for where the the NEXT tag starts.
		 */
		private long openTag(byte[] tagId) throws IOException {
			
			int idByteCount = tagId.length;
			ByteBuffer buffer = Buffers.newDirectByteBuffer(idByteCount + 8);
			buffer.order(ByteOrder.BIG_ENDIAN);
			
			buffer.put(tagId); // tag ID
			buffer.putLong(0); // placeholder for tag size
			outputFile.write(buffer.flip());
			
			// if any parent tags exist, update their sizes
			if(!exportTagSizes.isEmpty())
				for(Map.Entry<Long, Long> entry : exportTagSizes)
					entry.setValue(entry.getValue() + idByteCount + 8);
			
			// start tracking this tag's size
			exportTagSizes.push(new SimpleEntry<Long, Long>((long) outputFile.size() - 8, 0L));
			
			return outputFile.size();
			
		}
		
		/**
		 * "Closes" a tag. This just writes the tag's size into the placeholder created when the tag was "opened."
		 * 
		 * @return    File offset for where the NEXT tag starts.
		 */
		private long closeTag() throws IOException {
			
			// get the file offset for this tag's size, and the size to write into that location
			Map.Entry<Long, Long> entry = exportTagSizes.pop();
			long offset = entry.getKey();
			long byteCount = entry.getValue();
			
			ByteBuffer buffer = Buffers.newDirectByteBuffer(8);
			buffer.order(ByteOrder.BIG_ENDIAN);
			
			// tag size
			buffer.putLong(byteCount | (1L << 56L));
			
			// write to file
			outputFile.write(buffer.flip(), offset);
			
			return outputFile.size();
			
		}
		
		/**
		 * Appends a tag to the file.
		 * 
		 * @param tagId    The tag ID.
		 * @param data     The tag data, as a long.
		 * @return         File offset for where the data portion of THIS tag starts.
		 */
		private long putTag(byte[] tagId, long data) throws IOException {
			
			int idByteCount = tagId.length;
			int dataByteCount = (data & 0xFF00000000000000L) != 0 ? 8 :
			                    (data & 0x00FF000000000000L) != 0 ? 7 :
			                    (data & 0x0000FF0000000000L) != 0 ? 6 :
			                    (data & 0x000000FF00000000L) != 0 ? 5 :
			                    (data & 0x00000000FF000000L) != 0 ? 4 :
			                    (data & 0x0000000000FF0000L) != 0 ? 3 :
			                    (data & 0x000000000000FF00L) != 0 ? 2 :
			                                                        1;
			ByteBuffer buffer = Buffers.newDirectByteBuffer(idByteCount + 8 + dataByteCount);
			buffer.order(ByteOrder.BIG_ENDIAN);
			
			buffer.put(tagId); // tag ID
			buffer.putLong(dataByteCount | (1L << 56L)); // tag size
			
			// tag data
			for(int i = 0; i < dataByteCount; i++) {
				int bits = (int) (dataByteCount - i - 1) * 8;
				buffer.put((byte) ((data >> bits) & 0xFF));
			}
			
			// write to file
			outputFile.write(buffer.flip());
			
			// if any parent tags exist, update their sizes
			if(!exportTagSizes.isEmpty())
				for(Map.Entry<Long, Long> entry : exportTagSizes)
					entry.setValue(entry.getValue() + idByteCount + 8 + dataByteCount);
			
			return outputFile.size() - dataByteCount;
			
		}
		
		/**
		 * Appends a tag to the file.
		 * 
		 * @param tagId        The tag ID.
		 * @param dataBytes    The tag data, as a byte[].
		 */
		private void putTag(byte[] tagId, byte[] dataBytes) throws IOException {
			
			int idByteCount = tagId.length;
			int dataBytesCount = dataBytes.length;
			ByteBuffer buffer = Buffers.newDirectByteBuffer(idByteCount + 8 + dataBytesCount);
			buffer.order(ByteOrder.BIG_ENDIAN);
			
			buffer.put(tagId); // tag ID
			buffer.putLong(dataBytesCount | (1L << 56L)); // tag size
			buffer.put(dataBytes); // tag data
			outputFile.write(buffer.flip());
			
			// if any parent tags exist, update their sizes
			if(!exportTagSizes.isEmpty())
				for(Map.Entry<Long, Long> entry : exportTagSizes)
					entry.setValue(entry.getValue() + idByteCount + 8 + dataBytesCount);
			
		}
		
		/**
		 * Appends a tag to the file.
		 * 
		 * @param tagId        The tag ID.
		 * @param dataBytes    The tag data, as a ByteBuffer.
		 * @return             File offset for where the data portion of THIS tag starts.
		 */
		private long putTag(byte[] tagId, ByteBuffer dataBytes) throws IOException {
			
			int idByteCount = tagId.length;
			int dataBytesCount = dataBytes.limit();
			ByteBuffer buffer = Buffers.newDirectByteBuffer(idByteCount + 8);
			buffer.order(ByteOrder.BIG_ENDIAN);
			
			buffer.put(tagId); // tag ID
			buffer.putLong(dataBytesCount | (1L << 56L)); // tag size
			outputFile.write(buffer.flip());
			outputFile.write(dataBytes); // tag data
			
			// if any parent tags exist, update their sizes
			if(!exportTagSizes.isEmpty())
				for(Map.Entry<Long, Long> entry : exportTagSizes)
					entry.setValue(entry.getValue() + idByteCount + 8 + dataBytesCount);
			
			return outputFile.size() - dataBytesCount;
			
		}
		
		/**
		 * Appends one frame to the file.
		 * 
		 * @param relativeTimestamp    Milliseconds since the enclosing Cluster's timestamp. This is stored as an int16.
		 * @param imageByteCount       Size of the JPEG.
		 * @param fileOffset           Where the JPEG starts in the source file.
		 * @param sourceFile           File that contains the JPEG.
		 * @return                     File offset where the image of this tag starts.
		 */
		private long putSimpleBlock(long relativeTimestamp, long imageByteCount, long fileOffset, FileChannel sourceFile) throws IOException {
			
			ByteBuffer buffer = Buffers.newDirectByteBuffer(13);
			buffer.order(ByteOrder.BIG_ENDIAN);
			
			// tag ID
			buffer.put((byte) 0xA3);
			
			// tag size
			long byteCount = 4 + imageByteCount; // SimpleBlock has a 4-byte header (if track number < 128)
			buffer.putLong(byteCount | (1L << 56L));
			
			// SimpleBlock header
			buffer.put((byte) 0x81); // track number, EBML encoded, but this can NOT simply be padded to 8-bytes like the tag size
			buffer.put((byte) ((relativeTimestamp >> 8) & 0xFF)); // relative timestamp (int16 relative to the enclosing cluster's timestamp)
			buffer.put((byte) ((relativeTimestamp >> 0) & 0xFF));
			buffer.put((byte) 0); // lacing option (no lacing)
			
			// write to file
			outputFile.write(buffer.flip());
			sourceFile.transferTo(fileOffset, imageByteCount, outputFile);
			
			// if any parent tags exist, update their sizes
			if(!exportTagSizes.isEmpty())
				for(Map.Entry<Long, Long> entry : exportTagSizes)
					entry.setValue(entry.getValue() + 13 + imageByteCount);
			
			return outputFile.size() - imageByteCount;
			
		}
		
		private void assertTagOpened(byte[] tagId) throws AssertionError, IOException {
			
			int idByteCount = tagId.length;
			
			// read enough of the file for this tag and its size
			ByteBuffer buffer = Buffers.newDirectByteBuffer(idByteCount + 8);
			buffer.order(ByteOrder.BIG_ENDIAN);
			inputFile.read(buffer);
			
			// ensure the tag id matches
			for(int i = 0; i < idByteCount; i++)
				if(buffer.get(i) != tagId[i]) {
					String message = "Expected tag: ";
					for(int j = 0; j < idByteCount; j++)
						message += String.format("%02X ", tagId[j]);
					message = message.trim();
					message += ", found: ";
					for(int j = 0; j < idByteCount; j++)
						message += String.format("%02X ", buffer.get(j));
					message = message.trim();
					throw new AssertionError(message);
				}
			
			// if any parent tags exist, update their sizes
			if(!importTagSizes.isEmpty())
				for(int i = 0; i < importTagSizes.size(); i++)
					importTagSizes.set(i, importTagSizes.get(i) - idByteCount - 8);
			
			// read the tag size
			long byteCount = buffer.position(idByteCount).getLong() & ~(1L << 56L);
			importTagSizes.push(byteCount);
			
		}
		
		private void assertTagClosed() throws AssertionError {
			
			if(importTagSizes.isEmpty())
				throw new AssertionError("Expected the end of a tag, but no tags are currently open.");
			
			long remainingByteCount = importTagSizes.pop();
			if(remainingByteCount != 0)
				throw new AssertionError("Expected the end of a tag, but " + remainingByteCount + " bytes of remain.");
			
		}
		
		private void assertTagFound(byte[] tagId, long tagData) throws AssertionError, IOException {
			
			int idByteCount = tagId.length;
			int dataByteCount = (tagData & 0xFF00000000000000L) != 0 ? 8 :
			                    (tagData & 0x00FF000000000000L) != 0 ? 7 :
			                    (tagData & 0x0000FF0000000000L) != 0 ? 6 :
			                    (tagData & 0x000000FF00000000L) != 0 ? 5 :
			                    (tagData & 0x00000000FF000000L) != 0 ? 4 :
			                    (tagData & 0x0000000000FF0000L) != 0 ? 3 :
			                    (tagData & 0x000000000000FF00L) != 0 ? 2 :
			                                                           1;
			
			// read enough of the file for this id/size/data
			ByteBuffer buffer = Buffers.newDirectByteBuffer(idByteCount + 8 + dataByteCount);
			buffer.order(ByteOrder.BIG_ENDIAN);
			inputFile.read(buffer);
			
			// ensure the tag id matches
			for(int i = 0; i < idByteCount; i++)
				if(buffer.get(i) != tagId[i]) {
					String message = "Expected tag: ";
					for(int j = 0; j < idByteCount; j++)
						message += String.format("%02X ", tagId[j]);
					message = message.trim();
					message += ", found: ";
					for(int j = 0; j < idByteCount; j++)
						message += String.format("%02X ", buffer.get(j));
					message = message.trim();
					throw new AssertionError(message);
				}
			
			// read the tag size
			long byteCount = buffer.position(idByteCount).getLong() & ~(1L << 56L);
			if(byteCount != dataByteCount) {
				String message = "Expected tag ";
				for(int i = 0; i < idByteCount; i++)
					message += String.format("%02X ", tagId[i]);
				message = message.trim();
				message += " to contain " + dataByteCount + " bytes, but it contains " + byteCount + " bytes.";
				throw new AssertionError(message);
			}
			
			// ensure the tag data matches
			for(int i = 0; i < dataByteCount; i++) {
				int bits = (int) (dataByteCount - i - 1) * 8;
				if(buffer.get(idByteCount + 8 + i) != (byte) ((tagData >> bits) & 0xFF)) {
					String message = "Expected value: " + tagData + ", found: ";
					for(int j = 0; j < dataByteCount; j++)
						message += String.format("%02X ", buffer.get(idByteCount + 8 + j));
					message = message.trim();
					throw new AssertionError(message);
				}
			}
			
			// if any parent tags exist, update their sizes
			if(!importTagSizes.isEmpty())
				for(int i = 0; i < importTagSizes.size(); i++)
					importTagSizes.set(i, importTagSizes.get(i) - idByteCount - 8 - dataByteCount);
			
		}
		
		private long assertTagFound(byte[] tagId) throws AssertionError, IOException {
			
			int idByteCount = tagId.length;
			
			// read enough of the file for this id/size
			ByteBuffer buffer = Buffers.newDirectByteBuffer(idByteCount + 8);
			buffer.order(ByteOrder.BIG_ENDIAN);
			inputFile.read(buffer);
			
			// ensure the tag id matches
			for(int i = 0; i < idByteCount; i++)
				if(buffer.get(i) != tagId[i]) {
					String message = "Expected tag: ";
					for(int j = 0; j < idByteCount; j++)
						message += String.format("%02X ", tagId[j]);
					message = message.trim();
					message += ", found: ";
					for(int j = 0; j < idByteCount; j++)
						message += String.format("%02X ", buffer.get(j));
					message = message.trim();
					throw new AssertionError(message);
				}
			
			// read the tag size
			long dataByteCount = buffer.position(idByteCount).getLong() & ~(1L << 56L);
			
			// read the tag data
			buffer.clear();
			buffer.limit((int) dataByteCount);
			inputFile.read(buffer);
			
			buffer.flip();
			long data = 0;
			for(int i = 0; i < dataByteCount; i++)
				data = (data << 8) | buffer.get();
			
			// if any parent tags exist, update their sizes
			if(!importTagSizes.isEmpty())
				for(int i = 0; i < importTagSizes.size(); i++)
					importTagSizes.set(i, importTagSizes.get(i) - idByteCount - 8 - dataByteCount);
			
			return data;
			
		}
		
		private void assertTagFound(byte[] tagId, byte[] tagData) throws AssertionError, IOException {
			
			int idByteCount = tagId.length;
			int dataByteCount = tagData.length;
			
			// read enough of the file for this id/size/data
			ByteBuffer buffer = Buffers.newDirectByteBuffer(idByteCount + 8 + dataByteCount);
			buffer.order(ByteOrder.BIG_ENDIAN);
			inputFile.read(buffer);
			
			// ensure the tag id matches
			for(int i = 0; i < idByteCount; i++)
				if(buffer.get(i) != tagId[i]) {
					String message = "Expected tag: ";
					for(int j = 0; j < idByteCount; j++)
						message += String.format("%02X ", tagId[j]);
					message = message.trim();
					message += ", found: ";
					for(int j = 0; j < idByteCount; j++)
						message += String.format("%02X ", buffer.get(j));
					message = message.trim();
					throw new AssertionError(message);
				}
			
			// read the tag size
			long byteCount = buffer.position(idByteCount).getLong() & ~(1L << 56L);
			if(byteCount != dataByteCount) {
				String message = "Expected tag ";
				for(int i = 0; i < idByteCount; i++)
					message += String.format("%02X ", tagId[i]);
				message = message.trim();
				message += " to contain " + dataByteCount + " bytes, but it contains " + byteCount + " bytes.";
				throw new AssertionError(message);
			}
			
			// ensure the tag data matches
			for(int i = 0; i < dataByteCount; i++) {
				if(buffer.get(idByteCount + 8 + i) != tagData[i]) {
					String message = "Expected value: ";
					for(int j = 0; j < dataByteCount; j++)
						message += String.format("%02X ", tagData[j]);
					message = message.trim();
					message += ", found: ";
					for(int j = 0; j < dataByteCount; j++)
						message += String.format("%02X ", buffer.get(idByteCount + 8 + j));
					message = message.trim();
					throw new AssertionError(message);
				}
			}
			
			// if any parent tags exist, update their sizes
			if(!importTagSizes.isEmpty())
				for(int i = 0; i < importTagSizes.size(); i++)
					importTagSizes.set(i, importTagSizes.get(i) - idByteCount - 8 - dataByteCount);
			
		}
		
		private ByteBuffer assertTagFoundData(byte[] tagId) throws AssertionError, IOException {
			
			int idByteCount = tagId.length;
			
			// read enough of the file for this id/size
			ByteBuffer buffer = Buffers.newDirectByteBuffer(idByteCount + 8);
			buffer.order(ByteOrder.BIG_ENDIAN);
			inputFile.read(buffer);
			
			// ensure the tag id matches
			for(int i = 0; i < idByteCount; i++)
				if(buffer.get(i) != tagId[i]) {
					String message = "Expected tag: ";
					for(int j = 0; j < idByteCount; j++)
						message += String.format("%02X ", tagId[j]);
					message = message.trim();
					message += ", found: ";
					for(int j = 0; j < idByteCount; j++)
						message += String.format("%02X ", buffer.get(j));
					message = message.trim();
					throw new AssertionError(message);
				}
			
			// read the tag size
			long dataByteCount = buffer.position(idByteCount).getLong() & ~(1L << 56L);
			
			// read the data
			buffer = Buffers.newDirectByteBuffer((int) dataByteCount);
			buffer.order(ByteOrder.BIG_ENDIAN);
			inputFile.read(buffer);
			
			// if any parent tags exist, update their sizes
			if(!importTagSizes.isEmpty())
				for(int i = 0; i < importTagSizes.size(); i++)
					importTagSizes.set(i, importTagSizes.get(i) - idByteCount - 8 - dataByteCount);
			
			return buffer.flip();
			
		}
		
	}

}
