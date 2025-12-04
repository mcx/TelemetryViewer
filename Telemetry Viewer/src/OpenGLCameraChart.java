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
	WidgetCheckbox showLabel;
	
	public ConnectionCamera connection = null;
	
	protected OpenGLCameraChart(String name, int x1, int y1, int x2, int y2) {
		
		super(name, x1, y1, x2, y2);
		
		// important: this constructor allows *any* camera to be selected, even if it's not connected or has no images
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
		showLabel = new WidgetCheckbox("Show Label", true);
		
		widgets.add(cameraName);
		widgets.add(mirrorX);
		widgets.add(mirrorY);
		widgets.add(rotateClockwise);
		widgets.add(showLabel);
		
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
		             .with(showLabel)
		             .getPanel());
		
	}

	@Override public EventHandler drawChart(GL2ES3 gl, float[] chartMatrix, int width, int height, long endTimestamp, int endSampleNumber, double zoomLevel, int mouseX, int mouseY) {
		
		// get the image
		ConnectionCamera.Frame f = (connection == null) ? new ConnectionCamera.Frame("[select a camera]") :
		                                                  connection.getImageAtOrBeforeTimestamp(endTimestamp);
		float aspectRatio = rotateClockwise.isTrue() ? (float) f.height / (float) f.width :
		                                               (float) f.width  / (float) f.height;
		
		// draw the label if enabled
		float yPlotTop = height - Theme.tilePadding;
		float yPlotBottom = Theme.tilePadding;
		float plotHeight = yPlotTop - yPlotBottom;
		if(showLabel.isTrue()) {
			float labelWidth = OpenGL.largeTextWidth(gl, f.label);
			float yLabelBaseline = Theme.tilePadding;
			float yLabelTop = yLabelBaseline + OpenGL.largeTextHeight;
			float xLabelLeft = (width / 2f) - (labelWidth / 2f);
			float xLabelRight = xLabelLeft + labelWidth;
			OpenGL.drawQuad2D(gl, Theme.tileShadowColor, xLabelLeft - Theme.tickTextPadding, yLabelBaseline - Theme.tickTextPadding, xLabelRight + Theme.tickTextPadding, yLabelTop + Theme.tickTextPadding);
			OpenGL.drawLargeText(gl, f.label, (int) xLabelLeft, (int) yLabelBaseline, 0);
			yPlotBottom = yLabelTop + Theme.tickTextPadding + Theme.tilePadding;
			plotHeight = yPlotTop - yPlotBottom;
		}
		
		// stop if there's not enough space to draw the image
		float xPlotLeft = Theme.tilePadding;
		float xPlotRight = width - Theme.tilePadding;
		float plotWidth = xPlotRight - xPlotLeft;
		if(plotWidth < 2 || plotHeight < 2)
			return null;
		
		// draw the image
		float currentAspectRatio = plotWidth / plotHeight;
		if(currentAspectRatio != aspectRatio) {
			if(aspectRatio > currentAspectRatio) {
				// need to make image shorter
				float desiredHeight = plotWidth / aspectRatio;
				float delta = plotHeight - desiredHeight;
				yPlotTop    -= delta / 2;
				yPlotBottom += delta / 2;
				plotHeight = yPlotTop - yPlotBottom;
			} else {
				// need to make image narrower
				float desiredWidth = plotHeight * aspectRatio;
				float delta = plotWidth - desiredWidth;
				xPlotLeft  += delta / 2;
				xPlotRight -= delta / 2;
				plotWidth = xPlotRight - xPlotLeft;
			}
		}
		if(texHandle == null) {
			texHandle = new int[1];
			OpenGL.createTexture(gl, texHandle, f.width, f.height, f.isRGB ? GL3.GL_RGB : GL3.GL_BGR, GL3.GL_UNSIGNED_BYTE, true);
			OpenGL.writeTexture (gl, texHandle, f.width, f.height, f.isRGB ? GL3.GL_RGB : GL3.GL_BGR, GL3.GL_UNSIGNED_BYTE, f.buffer);
			previousFrameTimestamp = f.timestamp;
		} else if(f.timestamp != previousFrameTimestamp) {
			// only replace the texture if a new image is available
			OpenGL.writeTexture(gl, texHandle, f.width, f.height, f.isRGB ? GL3.GL_RGB : GL3.GL_BGR, GL3.GL_UNSIGNED_BYTE, f.buffer);
			previousFrameTimestamp = f.timestamp;
		}
		
		     if(mirrorX.isFalse() && mirrorY.isFalse()) OpenGL.drawTexturedBox(gl, texHandle, false, xPlotLeft,  yPlotTop,     plotWidth, -plotHeight, 0, rotateClockwise.get());
		else if(mirrorX.isTrue()  && mirrorY.isFalse()) OpenGL.drawTexturedBox(gl, texHandle, false, xPlotRight, yPlotTop,    -plotWidth, -plotHeight, 0, rotateClockwise.get());
		else if(mirrorX.isFalse() && mirrorY.isTrue() ) OpenGL.drawTexturedBox(gl, texHandle, false, xPlotLeft,  yPlotBottom,  plotWidth,  plotHeight, 0, rotateClockwise.get());
		else if(mirrorX.isTrue()  && mirrorY.isTrue() ) OpenGL.drawTexturedBox(gl, texHandle, false, xPlotRight, yPlotBottom, -plotWidth,  plotHeight, 0, rotateClockwise.get());
		
		return null;
		
	}
	
	@Override public void disposeGpu(GL2ES3 gl) {
		
		super.disposeGpu(gl);
		if(texHandle != null)
			gl.glDeleteTextures(1, texHandle, 0);
		texHandle = null;
		
	}

}
