import java.util.Map;
import com.jogamp.opengl.GL2ES3;

public abstract class Plot {
	
	DatasetsInterface datasets;
	long maxSampleNumber;
	long minSampleNumber;
	long plotSampleCount;
	long plotMaxX;     // sample number or unix timestamp
	long plotMinX;     // sample number or unix timestamp
	long plotDomain;   // sample count  or milliseconds
	float samplesMinY; // of the samples, not necessarily of the plot
	float samplesMaxY; // of the samples, not necessarily of the plot
	String xAxisTitle = "";
	boolean cachedMode;
	
	/**
	 * Step 1: (Required) Calculate the domain and range of the plot.
	 * 
	 * @param maxX               Timestamp or sample number at the right edge of the plot. Must be >= 0, and it may be in the future!
	 * @param datasets           Normal/edge/level datasets to acquire from.
	 * @param duration           Number of milliseconds or samples to display. Must be >= 1ms or >= 2 samples.
	 * @param cachedMode         True to enable the cache.
	 * @param showTimestamps     True if the x-axis shows timestamps, false if the x-axis shows sample count or elapsed time.
	 */
	abstract void initialize(long maxX, DatasetsInterface datasets, long duration, boolean cachedMode, boolean showTimestamps);
	
	/**
	 * Step 2: Get the required range, assuming you want to see all samples on screen.
	 * 
	 * @return    The minimum and maximum Y-axis values.
	 */
	final StorageFloats.MinMax getRange() { return new StorageFloats.MinMax(samplesMinY, samplesMaxY); }
	
	/**
	 * Step 3: Get the x-axis title.
	 * 
	 * @return    The x-axis title.
	 */
	final String getTitle() { return xAxisTitle; }
	
	/**
	 * Step 4: Get the x-axis divisions.
	 * 
	 * @param gl           The OpenGL context.
	 * @param plotWidth    The width of the plot region, in pixels.
	 * @return             A Map where each value is a string to draw on screen, and each key is the pixelX location for it (0 = left edge of the plot)
	 */
	abstract Map<Float, String> getXdivisions(GL2ES3 gl, float plotWidth);
	
	/**
	 * Step 5: Acquire the samples.
	 * If you will call draw(), you must call this before it.
	 * 
	 * @param plotMinY      Y-axis value at the bottom of the plot.
	 * @param plotMaxY      Y-axis value at the top of the plot.
	 * @param plotWidth     Width of the plot region, in pixels.
	 * @param plotHeight    Height of the plot region, in pixels.
	 */
	final void acquireSamples(float plotMinY, float plotMaxY, int plotWidth, int plotHeight) {
		
		if(plotSampleCount < 2)
			return;
		
		if(cachedMode)
			acquireSamplesCachedMode(plotMinY, plotMaxY, plotWidth, plotHeight);
		else
			acquireSamplesNonCachedMode(plotMinY, plotMaxY, plotWidth, plotHeight);
		
	}
	abstract void acquireSamplesCachedMode   (float plotMinY, float plotMaxY, int plotWidth, int plotHeight);
	abstract void acquireSamplesNonCachedMode(float plotMinY, float plotMaxY, int plotWidth, int plotHeight);
	
	/**
	 * Step 6: Render the plot on screen.
	 * 
	 * @param gl             The OpenGL context.
	 * @param mouseX         X location of the mouse, in pixels, relative to the plot region.
	 * @param mouseY         Y location of the mouse, in pixels, relative to the plot region.
	 * @param plotMatrix     The current 4x4 matrix.
	 * @param plotWidth      Width of the plot region, in pixels.
	 * @param plotHeight     Height of the plot region, in pixels.
	 * @param plotMinY       Y-axis value at the bottom of the plot.
	 * @param plotMaxY       Y-axis value at the top of the plot.
	 */
	final void draw(GL2ES3 gl, int mouseX, int mouseY, float[] plotMatrix, int plotWidth, int plotHeight, float plotMinY, float plotMaxY) {
		
		if(plotSampleCount < 2)
			return;
		
		// ignore the mouse location if it's outside the plot region
		if(mouseX < 0 || mouseX > plotWidth || mouseY < 0 || mouseY > plotHeight) {
			mouseX = -1;
			mouseY = -1;
		}
		
		if(cachedMode)
			drawCachedMode(gl, mouseX, mouseY, plotMatrix, plotWidth, plotHeight, plotMinY, plotMaxY);
		else
			drawNonCachedMode(gl, mouseX, mouseY, plotMatrix, plotWidth, plotHeight, plotMinY, plotMaxY);
		
	}
	abstract void drawCachedMode   (GL2ES3 gl, int mouseX, int mouseY, float[] plotMatrix, int plotWidth, int plotHeight, float plotMinY, float plotMaxY);
	abstract void drawNonCachedMode(GL2ES3 gl, int mouseX, int mouseY, float[] plotMatrix, int plotWidth, int plotHeight, float plotMinY, float plotMaxY);
	
	/**
	 * Step 7: Create and draw a tooltip based on the mouse's current location.
	 * 
	 * @param gl             The OpenGL context.
	 * @param mouseX         X location of the mouse, in pixels, relative to the plot region.
	 * @param mouseY         Y location of the mouse, in pixels, relative to the plot region.
	 * @param plotWidth      Width of the plot region, in pixels.
	 * @param plotHeight     Height of the plot region, in pixels.
	 * @param plotMinY       Y-axis value at the bottom of the plot.
	 * @param plotMaxY       Y-axis value at the top of the plot.
	 */
	abstract public void drawTooltip(GL2ES3 gl, int mouseX, int mouseY, float plotWidth, float plotHeight, float plotMinY, float plotMaxY);
	
	/**
	 * Gets the horizontal location, relative to the plot, for a sample number.
	 * 
	 * @param sampleNumber    The sample number.
	 * @param plotWidth       Width of the plot region, in pixels.
	 * @return                Corresponding horizontal location on the plot, in pixels, with 0 = left edge of the plot.
	 */
	abstract float getPixelXforSampleNumber(long sampleNumber, float plotWidth);
	
	/**
	 * @return    Domain (interval of x-axis values) of the plot.
	 */
	final long getPlotDomain() { return plotDomain; }
	
	abstract public void freeResources(GL2ES3 gl);

}
