import java.nio.FloatBuffer;
import java.util.Arrays;
import javax.swing.JPanel;
import com.jogamp.opengl.GL2ES3;
import com.jogamp.opengl.GL3;
import com.jogamp.opengl.math.Quaternion;

/**
 * Renders a 3D object that is rotated based on a quaternion.
 * 
 * User settings:
 *     Four quaternion datasets.
 *     The quaternion (as text) can be displayed.
 */
public class OpenGLQuaternionChart extends PositionedChart {

	FloatBuffer shape; // triangles: x1,y1,z1,u1,v1,w1,...
	
	private WidgetDatasetComboboxes datasetsWidget;
	private WidgetCheckbox quatLabelEnabled;
	
	@Override public String toString() {
		
		return "Quaternion";
		
	}
	
	public OpenGLQuaternionChart() {
		
		duration = 1;
		
		shape = ChartUtils.getShapeFromAsciiStl(getClass().getResourceAsStream("monkey.stl"));
		shape.rewind();
		
		datasetsWidget = new WidgetDatasetComboboxes(new String[] {"Q0", "Q1", "Q2", "Q3"},
		                                             newDatasets -> datasets.setNormals(newDatasets));
		
		quatLabelEnabled = new WidgetCheckbox("Show Quaternion Label", true);
		
		widgets.add(datasetsWidget);
		widgets.add(quatLabelEnabled);
		
	}
	
	@Override public void getConfigurationGui(JPanel gui) {
		
		gui.add(Theme.newWidgetsPanel("Data")
		             .with(datasetsWidget)
		             .withGap(Theme.padding)
		             .with(quatLabelEnabled)
		             .getPanel());
		
	}
	
	@Override public EventHandler drawChart(GL2ES3 gl, float[] chartMatrix, int width, int height, long endTimestamp, int endSampleNumber, double zoomLevel, int mouseX, int mouseY) {
		
		// sanity check
		if(datasets.normalsCount() != 4)
			return null;
		
		// determine which sample to use
		int sampleNumber = Math.min(endSampleNumber, datasets.connection.getSampleCount() - 1);

		// get the quaternion values
		float[] q = new float[4];
		for(int i = 0; i < 4; i++) {
			Field dataset = datasets.getNormal(i);
			q[i] = (sampleNumber < 0) ? 0 : datasets.getSample(dataset, sampleNumber);
		}
		
		// calculate x and y positions of everything
		float xPlotLeft = Theme.tilePadding;
		float xPlotRight = width - Theme.tilePadding;
		float plotWidth = xPlotRight - xPlotLeft;
		float yPlotBottom = Theme.tilePadding;
		float yPlotTop = height - Theme.tilePadding;
		float plotHeight = yPlotTop - yPlotBottom;

		String quatLabel = String.format("Quaternion (%+1.3f,%+1.3f,%+1.3f,%+1.3f)", q[0], q[1], q[2], q[3]);
		float yQuatLabelBaseline = Theme.tilePadding;
		float yQuatLabelTop = yQuatLabelBaseline + OpenGL.largeTextHeight;
		float xQuatLabelLeft = (width / 2f) - (OpenGL.largeTextWidth(gl, quatLabel) / 2f);
		float xQuatLabelRight = xQuatLabelLeft + OpenGL.largeTextWidth(gl, quatLabel);
		if(quatLabelEnabled.get()) {
			yPlotBottom = yQuatLabelTop + Theme.tickTextPadding;
			yPlotTop = height - Theme.tilePadding;
			plotHeight = yPlotTop - yPlotBottom;
		}
		
		// make the plot square so it doesn't stretch the 3D shape
		if(plotWidth > plotHeight) {
			float delta = plotWidth - plotHeight;
			xPlotLeft += delta / 2;
			xPlotRight -= delta / 2;
			plotWidth = xPlotRight - xPlotLeft;
		} else if(plotHeight > plotWidth) {
			float delta = plotHeight - plotWidth;
			yPlotBottom += delta / 2;
			yPlotTop -= delta / 2;
			plotHeight = yPlotTop - yPlotBottom;
		}
		
		float[] quatMatrix = new float[16];
		new Quaternion(q[1], q[2], q[3], q[0]).toMatrix(quatMatrix, 0); // x,y,z,w
		
		// adjust the modelview matrix to map the vertices' local space (-1 to +1) into chart space
		// x = x * (plotWidth / 2)  + (plotWidth / 2)
		// y = y * (plotHeight / 2) + (plotHeight / 2)
		// z = z * (plotHeight / 2) + (plotHeight / 2)
		float[] modelMatrix = Arrays.copyOf(chartMatrix, 16);
		OpenGL.translateMatrix(modelMatrix, (plotWidth/2f) + xPlotLeft, (plotHeight/2f) + yPlotBottom, (plotHeight/2f));
		OpenGL.scaleMatrix    (modelMatrix, (plotWidth/2f),             (plotHeight/2f),               (plotHeight/2f));
		
		// rotate the camera
		OpenGL.rotateMatrix(modelMatrix, 180, 0, 0, 1);
		OpenGL.rotateMatrix(modelMatrix,  90, 1, 0, 0);
		
		// apply the quaternion rotation
		OpenGL.multiplyMatrix(modelMatrix, quatMatrix);
		
		// invert direction of x-axis
		OpenGL.rotateMatrix(modelMatrix, 180, 1, 0, 0);
		
		// swap x and z axes
		OpenGL.rotateMatrix(modelMatrix, 90, 0, 0, 1);
		
		// draw the monkey
		OpenGL.useMatrix(gl, modelMatrix);
		OpenGL.drawTrianglesXYZUVW(gl, GL3.GL_TRIANGLES, shape, shape.capacity() / 6);
		
		OpenGL.useMatrix(gl, chartMatrix);

		// draw the text, on top of a background quad, if there is room
		if(quatLabelEnabled.get() && OpenGL.largeTextWidth(gl, quatLabel) < width - Theme.tilePadding * 2) {
			OpenGL.drawQuad2D(gl, Theme.tileShadowColor, xQuatLabelLeft - Theme.tickTextPadding, yQuatLabelBaseline - Theme.tickTextPadding, xQuatLabelRight + Theme.tickTextPadding, yQuatLabelTop + Theme.tickTextPadding);
			OpenGL.drawLargeText(gl, quatLabel, (int) xQuatLabelLeft, (int) yQuatLabelBaseline, 0);
		}
		
		return null;
		
	}

}
