import java.util.List;

import javax.swing.JPanel;
import com.jogamp.opengl.GL2ES3;
import com.jogamp.opengl.GL3;

public class OpenGLCameraChart extends Chart {
	
	long previousFrameTimestamp = 0;
	int[] texHandle;
	
	WidgetCombobox<String> cameraName;
	WidgetCheckbox mirrorX;
	WidgetCheckbox mirrorY;
	WidgetCheckbox rotateClockwise;
	WidgetCheckbox label;
	
	public ConnectionCamera connection = null;
	
	protected OpenGLCameraChart(String name, int x1, int y1, int x2, int y2) {
		
		super(name, x1, y1, x2, y2);
		
		// important: this constructor allows *any* camera to be selected, even if not it's connected and has no images
		// this is required so a settings file can be imported even if it references a camera that does not exist on this computer
		List<String> cameraNames = Connections.cameraConnections.stream().map(connection -> connection.getName()).toList();
		
		if(cameraNames.isEmpty())
			cameraNames = List.of("[No cameras available]");
		
		cameraName = new WidgetCombobox<String>(null, cameraNames, cameraNames.get(0))
		                 .setExportLabel("camera name")
		                 .onChange((newName, oldName) -> {
		                               connection = Connections.cameraConnections.stream().filter(connection -> connection.getName().equals(newName)).findFirst().orElse(null);
		                               return true;
		                           });
		mirrorX = new WidgetCheckbox("Mirror X-Axis \u2194", false);
		mirrorY = new WidgetCheckbox("Mirror Y-Axis \u2195", false);
		rotateClockwise = new WidgetCheckbox("Rotate Clockwise \u21B7", false);
		label = new WidgetCheckbox("Show Label", true);
		
		widgets.add(cameraName);
		widgets.add(mirrorX);
		widgets.add(mirrorY);
		widgets.add(rotateClockwise);
		widgets.add(label);
		
	}
	
	@Override public void appendConfigurationWidgets(JPanel gui) {
		
		// regenerate the camera list because the available cameras may have changed
		// and only allow the user to select a camera that is actually connected or has images
		List<String> cameraNames = Connections.cameraConnections.stream()
		                                      .filter(connection -> connection.isConnected() || connection.getSampleCount() > 0)
		                                      .map(connection -> connection.getName())
		                                      .toList();
		boolean noCameras = false;
		if(cameraNames.isEmpty()) {
			cameraNames = List.of("[No cameras available]");
			noCameras = true;
		}
		
		cameraName = new WidgetCombobox<String>(null, cameraNames, (connection == null) ? cameraNames.get(0) : connection.getName())
		             .setExportLabel("camera name")
		             .onChange((newName, oldName) -> {
		                           connection = Connections.cameraConnections.stream().filter(connection -> connection.getName().equals(newName)).findFirst().orElse(null);
		                           return true;
		                       });
		cameraName.setEnabled(!noCameras);
		widgets.set(0, cameraName);
		
		gui.add(Theme.newWidgetsPanel("Camera")
		             .with(cameraName)
		             .with(mirrorX)
		             .with(mirrorY)
		             .with(rotateClockwise)
		             .with(label)
		             .getPanel());
		
	}

	@Override public EventHandler drawChart(GL2ES3 gl, float[] chartMatrix, int width, int height, long endTimestamp, int endSampleNumber, double zoomLevel, int mouseX, int mouseY) {

		// if there's a global trigger, use the timestamp of the trigger point instead of the endTimestamp
		if(OpenGLChartsView.instance.triggerDetails.isTriggered())
			endTimestamp = OpenGLChartsView.instance.triggerDetails.triggeredTimestamp();
		
		// get the image
		ConnectionCamera.Frame f = (connection == null) ? new ConnectionCamera.Frame(null, true, 1, 1, "[select a camera]", 0) :
		                                                  connection.getImageAtOrBeforeTimestamp(endTimestamp);
		
		// calculate x and y positions of everything
		float xDisplayLeft = Theme.tilePadding;
		float xDisplayRight = width - Theme.tilePadding;
		float displayWidth = xDisplayRight - xDisplayLeft;
		float yDisplayBottom = Theme.tilePadding;
		float yDisplayTop = height - Theme.tilePadding;
		float displayHeight = yDisplayTop - yDisplayBottom;

		float labelWidth = OpenGL.largeTextWidth(gl, f.label);
		float yLabelBaseline = Theme.tilePadding;
		float yLabelTop = yLabelBaseline + OpenGL.largeTextHeight;
		float xLabelLeft = (width / 2f) - (labelWidth / 2f);
		float xLabelRight = xLabelLeft + labelWidth;
		if(label.get()) {
			yDisplayBottom = yLabelTop + Theme.tickTextPadding + Theme.tilePadding;
			displayHeight = yDisplayTop - yDisplayBottom;
		}
		
		// maintain the image aspect ratio, so it doesn't stretch
		float desiredAspectRatio = rotateClockwise.get() ? (float) f.height / (float) f.width : (float) f.width / (float) f.height;
		float currentAspectRatio = displayWidth / displayHeight;
		if(currentAspectRatio != desiredAspectRatio) {
			if(desiredAspectRatio > currentAspectRatio) {
				// need to make image shorter
				float desiredHeight = displayWidth / desiredAspectRatio;
				float delta = displayHeight - desiredHeight;
				yDisplayTop    -= delta / 2;
				yDisplayBottom += delta / 2;
				displayHeight = yDisplayTop - yDisplayBottom;
			} else {
				// need to make image narrower
				float desiredWidth = displayHeight * desiredAspectRatio;
				float delta = displayWidth - desiredWidth;
				xDisplayLeft  += delta / 2;
				xDisplayRight -= delta / 2;
				displayWidth = xDisplayRight - xDisplayLeft;
			}
		}
		
		// draw the image
		if(texHandle == null) {
			texHandle = new int[1];
			OpenGL.createTexture(gl, texHandle, f.width, f.height, f.isBgr ? GL3.GL_BGR : GL3.GL_RGB, GL3.GL_UNSIGNED_BYTE, true);
			OpenGL.writeTexture (gl, texHandle, f.width, f.height, f.isBgr ? GL3.GL_BGR : GL3.GL_RGB, GL3.GL_UNSIGNED_BYTE, f.buffer);
			previousFrameTimestamp = f.timestamp;
		} else if(f.timestamp != previousFrameTimestamp) {
			// only replace the texture if a new image is available
			OpenGL.writeTexture(gl, texHandle, f.width, f.height, f.isBgr ? GL3.GL_BGR : GL3.GL_RGB, GL3.GL_UNSIGNED_BYTE, f.buffer);
			previousFrameTimestamp = f.timestamp;
		}
		
		     if(!mirrorX.get() && !mirrorY.get()) OpenGL.drawTexturedBox(gl, texHandle, false, xDisplayLeft,  yDisplayTop,     displayWidth, -displayHeight, 0, rotateClockwise.get());
		else if( mirrorX.get() && !mirrorY.get()) OpenGL.drawTexturedBox(gl, texHandle, false, xDisplayRight, yDisplayTop,    -displayWidth, -displayHeight, 0, rotateClockwise.get());
		else if(!mirrorX.get() &&  mirrorY.get()) OpenGL.drawTexturedBox(gl, texHandle, false, xDisplayLeft,  yDisplayBottom,  displayWidth,  displayHeight, 0, rotateClockwise.get());
		else if( mirrorX.get() &&  mirrorY.get()) OpenGL.drawTexturedBox(gl, texHandle, false, xDisplayRight, yDisplayBottom, -displayWidth,  displayHeight, 0, rotateClockwise.get());
		
		// draw the label, on top of a background quad, if there is room
		if(label.get() && labelWidth < width - Theme.tilePadding * 2) {
			OpenGL.drawQuad2D(gl, Theme.tileShadowColor, xLabelLeft - Theme.tickTextPadding, yLabelBaseline - Theme.tickTextPadding, xLabelRight + Theme.tickTextPadding, yLabelTop + Theme.tickTextPadding);
			OpenGL.drawLargeText(gl, f.label, (int) xLabelLeft, (int) yLabelBaseline, 0);
		}
		
		return null;
		
	}
	
	@Override public void disposeGpu(GL2ES3 gl) {
		
		super.disposeGpu(gl);
		if(texHandle != null)
			gl.glDeleteTextures(1, texHandle, 0);
		texHandle = null;
		
	}

}
