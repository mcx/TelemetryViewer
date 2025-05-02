import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

import javax.swing.JPanel;

import com.jogamp.common.nio.Buffers;
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
public class OpenGLQuaternionChart extends Chart {

	FloatBuffer shape; // triangles: x1,y1,z1,u1,v1,w1,...
	
	private DatasetsInterface.WidgetDatasets datasetsWidget;
	private WidgetCheckbox quatLabelEnabled;
	
	protected OpenGLQuaternionChart(String name, int x1, int y1, int x2, int y2) {
		
		super(name, x1, y1, x2, y2);
		
		duration = 1;
		
		shape = getShapeFromAsciiStl(getClass().getResourceAsStream("monkey.stl"));
		
		datasetsWidget = datasets.getComboboxesWidget(List.of("Q0", "Q1", "Q2", "Q3"),
		                                              null);
		
		quatLabelEnabled = new WidgetCheckbox("Show Quaternion Label", true);
		
		widgets.add(datasetsWidget);
		widgets.add(quatLabelEnabled);
		
	}
	
	@Override public void appendConfigurationWidgets(JPanel gui) {
		
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
		new Quaternion(q[1], q[2], q[3], q[0]).toMatrix(quatMatrix); // x,y,z,w
		
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
	
	/**
	 * Parses an ASCII STL file to extract it's vertices and normal vectors.
	 * 
	 * Blender users: when exporting the STL file (File > Export > Stl) ensure the "Ascii" checkbox is selected,
	 * and ensure your model fits in a bounding box from -1 to +1 Blender units, centered at the origin.
	 * 
	 * @param fileStream    InputStream of an ASCII STL file.
	 * @returns             A FloatBuffer with a layout of x1,y1,z1,u1,v1,w1... or null if the InputStream could not be parsed.
	 */
	private static FloatBuffer getShapeFromAsciiStl(InputStream fileStream) {
		
		try {
			
			// get the lines of text
			List<String> lines = new ArrayList<String>();
			Scanner s = new Scanner(fileStream);
			while(s.hasNextLine())
				lines.add(s.nextLine());
			s.close();
			
			// count the vertices
			int vertexCount = 0;
			for(String line : lines)
				if(line.startsWith("vertex"))
					vertexCount++;
			
			
			// write the vertices into the FloatBuffer
			FloatBuffer buffer = Buffers.newDirectFloatBuffer(vertexCount * 6);
			float u = 0;
			float v = 0;
			float w = 0;
			for(String line : lines) {
				if(line.startsWith("facet normal")) {
					String[] token = line.split(" ");
					u = Float.parseFloat(token[2]);
					v = Float.parseFloat(token[3]);
					w = Float.parseFloat(token[4]);
				} else if(line.startsWith("vertex")) {
					String[] token = line.split(" ");
					buffer.put(Float.parseFloat(token[1]));
					buffer.put(Float.parseFloat(token[2]));
					buffer.put(Float.parseFloat(token[3]));
					buffer.put(u);
					buffer.put(v);
					buffer.put(w);
				}
			}
			
			return buffer.rewind();
			
		} catch (Exception e) {
			
			e.printStackTrace();
			return null;
			
		}
		
	}

}
