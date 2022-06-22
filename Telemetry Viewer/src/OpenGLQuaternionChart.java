import java.nio.FloatBuffer;
import java.util.Arrays;
import javax.swing.Box;
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
	
	// plot region
	float xPlotLeft;
	float xPlotRight;
	float plotWidth;
	float yPlotTop;
	float yPlotBottom;
	float plotHeight;
	
	// quaternion label
	String quatLabel;
	float yQuatLabelBaseline;
	float yQuatLabelTop;
	float xQuatLabelLeft;
	float xQuatLabelRight;
	
	// user settings
	private WidgetDatasetComboboxes datasetsWidget;
	
	private boolean quatLabelVisible = true;
	private WidgetCheckbox quatLabelCheckbox;
	
	@Override public String toString() {
		
		return "Quaternion";
		
	}
	
	public OpenGLQuaternionChart(int x1, int y1, int x2, int y2) {
		
		super(x1, y1, x2, y2);
		
		duration = 1;
		
		shape = ChartUtils.getShapeFromAsciiStl(getClass().getResourceAsStream("monkey.stl"));
		
		// create the control widgets and event handlers
		datasetsWidget = new WidgetDatasetComboboxes(new String[] {"Q0", "Q1", "Q2", "Q3"},
		                                             newDatasets -> datasets.setNormals(newDatasets));
		
		quatLabelCheckbox = new WidgetCheckbox("Show Quaternion Label",
		                                       quatLabelVisible,
		                                       newVisibility -> quatLabelVisible = newVisibility);
		
		widgets.add(datasetsWidget);
		widgets.add(quatLabelCheckbox);
		
	}
	
	@Override public void getConfigurationGui(JPanel gui) {
		
		JPanel dataPanel = Theme.newWidgetsPanel("Data");
		datasetsWidget.appendToGui(dataPanel);
		dataPanel.add(Box.createVerticalStrut(Theme.padding));
		dataPanel.add(quatLabelCheckbox);
		
		gui.add(dataPanel);
		
	}
	
	@Override public EventHandler drawChart(GL2ES3 gl, float[] chartMatrix, int width, int height, long endTimestamp, int endSampleNumber, double zoomLevel, int mouseX, int mouseY) {
		
		// sanity check
		if(datasets.normalsCount() != 4)
			return null;
		
		// determine which sample to use
		int lastSampleNumber = datasets.connection.getSampleCount() - 1;
		if(endSampleNumber < lastSampleNumber)
			lastSampleNumber = endSampleNumber;

		// get the quaternion values
		float[] q = new float[4];
		for(int i = 0; i < 4; i++) {
			Dataset dataset = datasets.getNormal(i);
			q[i] = lastSampleNumber < 0 ? 0 : datasets.getSample(dataset, lastSampleNumber);
		}
		
		// calculate x and y positions of everything
		xPlotLeft = Theme.tilePadding;
		xPlotRight = width - Theme.tilePadding;
		plotWidth = xPlotRight - xPlotLeft;
		yPlotBottom = Theme.tilePadding;
		yPlotTop = height - Theme.tilePadding;
		plotHeight = yPlotTop - yPlotBottom;

		if(quatLabelVisible) {
			quatLabel = String.format("Quaternion (%+1.3f,%+1.3f,%+1.3f,%+1.3f)", q[0], q[1], q[2], q[3]);
			yQuatLabelBaseline = Theme.tilePadding;
			yQuatLabelTop = yQuatLabelBaseline + OpenGL.largeTextHeight;
			xQuatLabelLeft = (width / 2f) - (OpenGL.largeTextWidth(gl, quatLabel) / 2f);
			xQuatLabelRight = xQuatLabelLeft + OpenGL.largeTextWidth(gl, quatLabel);
		
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
		OpenGL.drawTrianglesXYZUVW(gl, GL3.GL_TRIANGLES, shape.position(0), shape.capacity() / 6);
		
		OpenGL.useMatrix(gl, chartMatrix);

		// draw the text, on top of a background quad, if there is room
		if(quatLabelVisible && OpenGL.largeTextWidth(gl, quatLabel) < width - Theme.tilePadding * 2) {
			OpenGL.drawQuad2D(gl, Theme.tileShadowColor, xQuatLabelLeft - Theme.tickTextPadding, yQuatLabelBaseline - Theme.tickTextPadding, xQuatLabelRight + Theme.tickTextPadding, yQuatLabelTop + Theme.tickTextPadding);
			OpenGL.drawLargeText(gl, quatLabel, (int) xQuatLabelLeft, (int) yQuatLabelBaseline, 0);
		}
		
		return null;
		
	}

}
