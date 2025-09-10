import java.awt.Dimension;
import java.io.PrintWriter;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.swing.JPanel;

import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2ES3;
import com.jogamp.opengl.GL3;

/**
 * Charts and exportFiles() must access a connection's datasets through a "DatasetsInterface" which automatically manages caching.
 * 
 * All interactions are thread-safe *if* each thread creates its own DatasetsInterface,
 * therefore the charts and exportFiles() should each create their own DatasetsInterface.
 */
public class DatasetsInterface {
	
	public ConnectionTelemetry connection = null;
	public List<Field> normalDatasets = new ArrayList<>();
	public List<Field.Bitfield.State> edgeStates = new ArrayList<>();
	public List<Field.Bitfield.State> levelStates = new ArrayList<>();
	
	private Map<Field, StorageFloats.Cache> sampleCaches = new HashMap<>();
	private StorageTimestamps.Cache timestampsCache = null;
	private Map<Integer, Chart.Tooltip> edgesCache = new TreeMap<Integer, Chart.Tooltip>();
	private Map<Field.Bitfield.State, List<Field.Bitfield.LevelRange>> levelsCache  = new TreeMap<Field.Bitfield.State, List<Field.Bitfield.LevelRange>>();
	private int edgesLevelsCacheStartingSampleNumber = -1;
	private int edgesLevelsCacheEndingSampleNumber   = -1;
	
	public DatasetsInterface() { }
	
	public DatasetsInterface(ConnectionTelemetry connection) {
		
		this.connection = connection;
		timestampsCache = connection.createTimestampsCache();
		
	}
	
	private void flushCaches() {
		
		connection = !normalDatasets.isEmpty() ? normalDatasets.getFirst().connection :
		             !edgeStates.isEmpty()     ? edgeStates.getFirst().connection :
		             !levelStates.isEmpty()    ? levelStates.getFirst().connection :
		                                         null;
		
		timestampsCache = (connection == null) ? null : connection.createTimestampsCache();
		
		sampleCaches.clear();
		normalDatasets.forEach(dataset -> sampleCaches.put(dataset, dataset.createCache()));
		edgeStates.forEach(state -> sampleCaches.put(state.dataset, state.dataset.createCache()));
		levelStates.forEach(state -> sampleCaches.put(state.dataset, state.dataset.createCache()));
		edgesCache.clear();
		levelsCache.clear();
		levelStates.forEach(level -> levelsCache.put(level, new ArrayList<Field.Bitfield.LevelRange>()));
		edgesLevelsCacheStartingSampleNumber = -1;
		edgesLevelsCacheEndingSampleNumber = -1;
		
	}
	
	/**
	 * Gets the Tooltips for any edge events that have occurred within a sample range.
	 * 
	 * @param minSampleNumber    First sample number to check, inclusive.
	 * @param maxSampleNumber    Last sample number to check, inclusive.
	 * @param sampleCountMode    If true, the Tooltips will show their sample number.
	 *                           If false, the Tooltips will show their sample number and time.
	 * @returns                  A Map where the keys are sample numbers, and the values are the corresponding Tooltips to draw on screen.
	 */
	private Map<Integer, Chart.Tooltip> getEdgesBetween(int minSampleNumber, int maxSampleNumber, boolean sampleCountMode) {
		
		// sanity checks
		if(minSampleNumber < 0)
			return new TreeMap<Integer, Chart.Tooltip>();
		if(minSampleNumber >= maxSampleNumber)
			return new TreeMap<Integer, Chart.Tooltip>();
		
		// cache management
		if(minSampleNumber == edgesLevelsCacheStartingSampleNumber && maxSampleNumber == edgesLevelsCacheEndingSampleNumber) {
			// caches already contain the requested data
			return edgesCache;
		} else if(minSampleNumber == edgesLevelsCacheStartingSampleNumber && maxSampleNumber > edgesLevelsCacheEndingSampleNumber) {
			// caches contain the start of the requested data
			minSampleNumber = edgesLevelsCacheEndingSampleNumber;
			edgesLevelsCacheEndingSampleNumber = maxSampleNumber;
		} else {
			// flush caches
			edgesCache.clear();
			levelsCache.values().forEach(list -> list.clear());
			edgesLevelsCacheStartingSampleNumber = minSampleNumber;
			edgesLevelsCacheEndingSampleNumber   = maxSampleNumber;
		}
		
		// add new data to the caches
		final int min = minSampleNumber; // finals for the lambda below
		final int max = maxSampleNumber;
		Stream.concat(edgeStates.stream(), levelStates.stream())
		      .map(state -> state.bitfield)
		      .distinct()
		      .forEach(bitfield -> bitfield.getEdgesAndLevelsBetween(min, max, sampleCountMode, edgesCache, levelsCache, this));
		
		// provide the data
		return edgesCache;
		
	}
	
	/**
	 * Gets the details for any level events that have occurred within a sample range.
	 * 
	 * @param minSampleNumber    First sample number to check, inclusive.
	 * @param maxSampleNumber    Last sample number to check, inclusive.
	 * @param sampleCountMode    Not actually used by the level events, but this code also checks for edge events because both tasks can be done efficiently at the same time.
	 * @returns                  A Map where the keys are Bitfield States, and the values are the corresponding details for any level events to draw on screen.
	 */
	private Map<Field.Bitfield.State, List<Field.Bitfield.LevelRange>> getLevelsBetween(int minSampleNumber, int maxSampleNumber, boolean sampleCountMode) {
		
		// sanity checks
		if(minSampleNumber < 0)
			return new TreeMap<Field.Bitfield.State, List<Field.Bitfield.LevelRange>>();
		if(minSampleNumber >= maxSampleNumber)
			return new TreeMap<Field.Bitfield.State, List<Field.Bitfield.LevelRange>>();
		
		// cache management
		if(minSampleNumber == edgesLevelsCacheStartingSampleNumber && maxSampleNumber == edgesLevelsCacheEndingSampleNumber) {
			// caches already contain the requested data
			return levelsCache;
		} else if(minSampleNumber == edgesLevelsCacheStartingSampleNumber && maxSampleNumber > edgesLevelsCacheEndingSampleNumber) {
			// caches contain the start of the requested data
			minSampleNumber = edgesLevelsCacheEndingSampleNumber;
			edgesLevelsCacheEndingSampleNumber = maxSampleNumber;
		} else {
			// flush caches
			edgesCache.clear();
			levelsCache.values().forEach(list -> list.clear());
			edgesLevelsCacheStartingSampleNumber = minSampleNumber;
			edgesLevelsCacheEndingSampleNumber   = maxSampleNumber;
		}
		
		// add new data to the caches
		final int min = minSampleNumber; // finals for the lambda below
		final int max = maxSampleNumber;
		Stream.concat(edgeStates.stream(), levelStates.stream())
		      .map(state -> state.bitfield)
		      .distinct()
		      .forEach(bitfield -> bitfield.getEdgesAndLevelsBetween(min, max, sampleCountMode, edgesCache, levelsCache, this));
		
		// provide the data
		return levelsCache;
		
	}
	
	/**
	 * @param datasetN    Index of a normal dataset (this is NOT the dataset location, but the setNormals() list index.)
	 * @return            Corresponding normal dataset.
	 */
	public Field getNormal(int datasetN) {
		return normalDatasets.get(datasetN);
	}
	
	/**
	 * @param dataset    Dataset to check for.
	 * @return           True if this object references the Dataset (as a normal/edge/level.)
	 */
	public boolean contains(Field dataset) {
		return sampleCaches.keySet().contains(dataset);
	}
	
	/**
	 * @return    True if any normal datasets have been selected.
	 */
	public boolean hasNormals() {
		return !normalDatasets.isEmpty();
	}
	
	/**
	 * @return    True if any bitfield edges have been selected.
	 */
	public boolean hasEdges() {
		return !edgeStates.isEmpty();
	}
	
	/**
	 * @return    True if any bitfield levels have been selected.
	 */
	public boolean hasLevels() {
		return !levelStates.isEmpty();
	}
	
	/**
	 * @return    True if any normals/edges/levels have been selected.
	 */
	public boolean hasAnyType() {
		return connection != null;
	}
	
	/**
	 * @return    Number of normal datasets that have been selected.
	 */
	public int normalsCount() {
		return normalDatasets.size();
	}
	
	/**
	 * Gets a sample as a float32.
	 * 
	 * @param dataset         Dataset.
	 * @param sampleNumber    Sample number.
	 * @return                The sample, as a float32.
	 */
	public float getSample(Field dataset, int sampleNumber) {
		
		return dataset.getSample(sampleNumber, cacheFor(dataset));
		
	}
	
	/**
	 * Gets a sequence of samples as a float[].
	 * 
	 * @param dataset            Dataset.
	 * @param minSampleNumber    First sample number, inclusive.
	 * @param maxSampleNumber    Last sample number, inclusive.
	 * @return                   A float[] of the samples.
	 */
	public float[] getSamplesArray(Field dataset, int minSampleNumber, int maxSampleNumber) {
		
		return dataset.getSamplesArray(minSampleNumber, maxSampleNumber, cacheFor(dataset));
		
	}

	/**
	 * Gets a sequence of samples as a FloatBuffer.
	 * 
	 * @param dataset            Dataset.
	 * @param minSampleNumber    First sample number, inclusive.
	 * @param maxSampleNumber    Last sample number, inclusive.
	 * @return                   A FloatBuffer of the samples.
	 */
	public FloatBuffer getSamplesBuffer(Field dataset, int minSampleNumber, int maxSampleNumber) {
		
		return dataset.getSamplesBuffer(minSampleNumber, maxSampleNumber, cacheFor(dataset));
		
	}
	
	public int getClosestSampleNumberAtOrBefore(long timestamp, int maxSampleNumber) {
		
		return connection.getClosestSampleNumberAtOrBefore(timestamp, maxSampleNumber);
		
	}
	
	public int getClosestSampleNumberAfter(long timestamp) {
		
		return connection.getClosestSampleNumberAfter(timestamp);
		
	}
	
	public long getTimestamp(int sampleNumber) {
		
		return connection.getTimestamp(sampleNumber);
		
	}
	
	public FloatBuffer getTimestampsBuffer(int firstSampleNumber, int lastSampleNumber, long plotMinX) {
		
		return connection.getTimestampsBuffer(firstSampleNumber, lastSampleNumber, plotMinX, timestampsCache);
		
	}
	
	public LongBuffer getTimestampsBuffer(int firstSampleNumber, int lastSampleNumber) {
		
		return connection.getTimestampsBuffer(firstSampleNumber, lastSampleNumber, timestampsCache);
		
	}
	
	public record Range(float min, float max) {}
	
	/**
	 * Gets the range (y-axis region) occupied by all of the normal datasets.
	 *  
	 * @param minSampleNumber    Minimum sample number, inclusive.
	 * @param maxSampleNumber    Maximum sample number, inclusive.
	 * @return                   The range, as [0] = minY, [1] = maxY.
	 *                           If there are no normal datasets, [-1, 1] will be returned.
	 *                           If the range is a single value, [value +/- 0.001] will be returned.
	 */
	public Range getRange(int minSampleNumber, int maxSampleNumber) {
		
		float min = Float.MAX_VALUE;
		float max = -Float.MAX_VALUE;

		for(Field dataset : normalDatasets) {
			if(!dataset.isBitfield) {
				StorageFloats.MinMax range = dataset.getRange(minSampleNumber, maxSampleNumber, cacheFor(dataset));
				min = Math.min(min, range.min);
				max = Math.max(max, range.max);
			}
		}
		
		if(min == Float.MAX_VALUE && max == -Float.MAX_VALUE) {
			min = -1;
			max =  1;
		} else if(min == max) {
			float value = min;
			min = value - 0.001f;
			max = value + 0.001f;
		}
		
		return new Range(min, max);
		
	}
	
	/**
	 * Gets the range (y-axis region) occupied by a specific normal dataset.
	 * 
	 * @param dataset            Dataset to check.
	 * @param minSampleNumber    Minimum sample number, inclusive.
	 * @param maxSampleNumber    Maximum sample number, inclusive.
	 * @return                   The range, as [0] = minY, [1] = maxY.
	 *                           If there are no normal datasets, [-1, 1] will be returned.
	 *                           If the range is a single value, [value +/- 0.001] will be returned.
	 */
	public float[] getRange(Field dataset, int minSampleNumber, int maxSampleNumber) {
		
		float[] minMax = new float[] {Float.MAX_VALUE, -Float.MAX_VALUE};

		if(!dataset.isBitfield) {
			StorageFloats.MinMax range = dataset.getRange(minSampleNumber, maxSampleNumber, cacheFor(dataset));
			minMax[0] = Math.min(minMax[0], range.min);
			minMax[1] = Math.max(minMax[1], range.max);
		}
		
		if(minMax[0] == Float.MAX_VALUE && minMax[1] == -Float.MAX_VALUE) {
			minMax[0] = -1;
			minMax[1] = 1;
		} else if(minMax[0] == minMax[1]) {
			float value = minMax[0];
			minMax[0] = value - 0.001f;
			minMax[1] = value + 0.001f;
		}
		
		return minMax;
		
	}
	
	/**
	 * Iterates over all selected normal datasets.
	 * 
	 * @param consumer    BiConsumer that accepts a dataset and its corresponding samples cache.
	 */
	public void forEachNormal(BiConsumer<Field, StorageFloats.Cache> consumer) {
		for(int i = 0; i < normalDatasets.size(); i++) {
			Field dataset = normalDatasets.get(i);
			consumer.accept(dataset, sampleCaches.get(dataset));
		}
	}
	
	/**
	 * Draws bitfields (edge tooltips and level markers) onto a plot.
	 * It is assumed that the OpenGL matrix is already configured so (0,0) is the lower-left corner of the plot region.
	 * 
	 * @param gl                 The OpenGL context.
	 * @param mouseX             X location of the mouse, in pixels, relative to the plot region.
	 * @param mouseY             Y location of the mouse, in pixels, relative to the plot region.
	 * @param plotWidth          Width of the plot region, in pixels.
	 * @param plotHeight         Height of the plot region, in pixels.
	 * @param sampleCountMode    True if the x-axis shows sample numbers, false if the x-axis shows timestamps.
	 * @param plotMinX           X-axis value (sample number or timestamp) at the left edge of the plot region.
	 * @param plotDomain         With of the plot region, in x-axis values (sample count or milliseconds.)
	 * @param minSampleNumber    First sample number to check, inclusive.
	 * @param maxSampleNumber    Last sample number to check, inclusive.
	 * @param clickable          If true, and if the mouse is over a tooltip/level: generate an EventHandler, and draw that tooltip/level in a way that indicates it is clickable.
	 * @return                   An EventHandler that will jump to a corresponding sample number if the user clicks on a tooltip/marker,
	 *                           or null if the mouse is not over a tooltip/marker or if clickable is false.
	 */
	public EventHandler drawBitfields(GL2ES3 gl, int mouseX, int mouseY, float plotWidth, float plotHeight, boolean sampleCountMode, long plotMinX, long plotDomain, long minSampleNumber, long maxSampleNumber, boolean clickable) {
		
		// sanity checks
		if(!hasEdges() && !hasLevels())
			return null; // no bitfields to draw
		int trueMaxSampleNumber = connection.getSampleCount() - 1;
		if(trueMaxSampleNumber < 1)
			return null; // can't draw if <2 samples exist
		if(minSampleNumber < 0 || maxSampleNumber > trueMaxSampleNumber || minSampleNumber >= maxSampleNumber)
			return null; // invalid sample numbers
		
		Function<Long, Float>   xToPixelX = x      -> ((x - plotMinX) / (float) plotDomain * plotWidth);
		Function<Integer, Long> pixelXtoX = pixelX -> (Math.round((float) pixelX / plotWidth * plotDomain) + plotMinX);
		
		AtomicBoolean insufficientSpace = new AtomicBoolean(false);
		AtomicReference<EventHandler> clickHandler = new AtomicReference<EventHandler>();
		
		// prepare the tooltips for edge events
		List<Chart.Tooltip> tooltips = getEdgesBetween((int) minSampleNumber, (int) maxSampleNumber, sampleCountMode)
		                               .values().stream().toList();
		
		// check if the mouse is near a tooltip
		Chart.Tooltip closestTooltip = null;
		if(!tooltips.isEmpty() && mouseX != -1 && mouseY != -1) {
			if(sampleCountMode) {
				long mouseSampleNumber = Math.min(maxSampleNumber, pixelXtoX.apply(mouseX));
				closestTooltip = tooltips.stream().min((e1, e2) -> {
				                                            int e1error = (int) Math.abs(e1.sampleNumber - mouseSampleNumber);
				                                            int e2error = (int) Math.abs(e2.sampleNumber - mouseSampleNumber);
				                                            return Integer.compare(e1error, e2error);
				                                        }).get();
			} else {
				long mouseTimestamp = pixelXtoX.apply(mouseX);
				Chart.Tooltip closestTooltipAtOrBefore = null;
				Chart.Tooltip closestTooltipAfter      = null;
				for(Chart.Tooltip tooltip : tooltips) {
					if(tooltip.timestamp <= mouseTimestamp)
						closestTooltipAtOrBefore = tooltip;
					else if(closestTooltipAfter == null)
						closestTooltipAfter = tooltip;
					else if(closestTooltipAfter.timestamp == tooltip.timestamp)
						closestTooltipAfter = tooltip;
				}
				
				if(closestTooltipAtOrBefore != null && closestTooltipAfter == null) {
					closestTooltip = closestTooltipAtOrBefore;
				} else if(closestTooltipAtOrBefore == null && closestTooltipAfter != null) {
					closestTooltip = closestTooltipAfter;
				} else {
					int beforeError = mouseX - (int) (float) xToPixelX.apply(closestTooltipAtOrBefore.timestamp);
					int afterError = (int) (float) xToPixelX.apply(closestTooltipAfter.timestamp) - mouseX;
					closestTooltip = beforeError < afterError ? closestTooltipAtOrBefore : closestTooltipAfter;
				}
			}
		}
		
		// draw all tooltips
		List<Chart.Tooltip.Drawable> bakedTooltips = tooltips.stream().map(tooltip -> tooltip.bake(gl, plotWidth, plotHeight, -1, -1, xToPixelX.apply(sampleCountMode ? tooltip.sampleNumber : tooltip.timestamp))).toList();
		for(int i = 0; i < bakedTooltips.size(); i++) {
			Chart.Tooltip.Drawable next = (i == bakedTooltips.size() - 1) ? null : bakedTooltips.get(i + 1);
			if(!bakedTooltips.get(i).draw(gl, closestTooltip != null, next))
				insufficientSpace.set(true);
		}
		
		// draw the tooltip closest to the mouse on top
		// if clickable, and if the mouse is over that tooltip, it will be drawn with a black border
		if(closestTooltip != null) {
			Chart.Tooltip.Drawable baked = closestTooltip.bake(gl, plotWidth, plotHeight, clickable ? mouseX : -1, clickable ? mouseY : -1, xToPixelX.apply(sampleCountMode ? closestTooltip.sampleNumber : closestTooltip.timestamp));
			if(!baked.draw(gl, false, null))
				insufficientSpace.set(true);
			
			if(clickable && mouseX >= baked.xBoxLeft() && mouseX <= baked.xBoxRight() && mouseY >= baked.yBoxBottom() && mouseY <= baked.yBoxTop()) {
				int sampleNumber = closestTooltip.sampleNumber;
				long timestamp = closestTooltip.timestamp;
				clickHandler.set(EventHandler.onPress(event -> OpenGLCharts.GUI.setPaused(timestamp, connection, sampleNumber)));
			}
		}
		
		List<Field.Bitfield> bitfields = Stream.concat(edgeStates.stream(), levelStates.stream())
		                                       .map(state -> state.bitfield)
		                                       .distinct()
		                                       .sorted().toList();
		
		// draw the levels
		Map<Field.Bitfield.State, List<Field.Bitfield.LevelRange>> levels = getLevelsBetween((int) minSampleNumber, (int) maxSampleNumber, sampleCountMode);
		int maxQuadCount = levels.entrySet().stream().mapToInt(entry -> entry.getValue().size()).max().orElse(0);
		FloatBuffer mouseOverOutline   = Buffers.newDirectFloatBuffer(16);                // 4 lines per quad, 2 (x,y) vertices per line = 16 floats per quad
		FloatBuffer quadsAsGlTriangles = Buffers.newDirectFloatBuffer(maxQuadCount * 12); // 2 triangles per quad, 3 (x,y) vertices per triangle = 12 floats per quad
		FloatBuffer outlinesAsGlLines  = Buffers.newDirectFloatBuffer(maxQuadCount * 16); // 4 lines per quad, 2 (x,y) vertices per line = 16 floats per quad
		levels.forEach((state, pairs) -> {
			int quadCount = pairs.size();
			quadsAsGlTriangles.rewind();
			outlinesAsGlLines.rewind();
			List<int[]> labelPositions = new ArrayList<int[]>(); // [0] = xLeft, [1] = xRight of the corresponding quad
			int bitfieldN = bitfields.indexOf(state.bitfield);
			float padding       = 6f * Settings.GUI.getChartScalingFactor();
			float yBottom       = padding + ((bitfields.size() - 1 - bitfieldN) * (padding + OpenGL.smallTextHeight + padding));
			float yTop          = yBottom + OpenGL.smallTextHeight + padding;
			float yTextBaseline = yBottom + padding/2f;
			
			if(yTop > plotHeight) {
				insufficientSpace.set(true);
				return; // "continue"
			}
			
			pairs.forEach(range -> {
				long minX = sampleCountMode ? range.startingSampleNumber() : range.startingTimestamp();
				long maxX = sampleCountMode ? range.endingSampleNumber()   : range.endingTimestamp();
				float xLeft  = xToPixelX.apply(minX);
				float xRight = xToPixelX.apply(maxX);
				// populate the buffers, then draw the buffers, then if text can fit draw the text
				quadsAsGlTriangles.put(xLeft);  quadsAsGlTriangles.put(yBottom);
				quadsAsGlTriangles.put(xLeft);  quadsAsGlTriangles.put(yTop);
				quadsAsGlTriangles.put(xRight); quadsAsGlTriangles.put(yTop);
				quadsAsGlTriangles.put(xRight); quadsAsGlTriangles.put(yTop);
				quadsAsGlTriangles.put(xRight); quadsAsGlTriangles.put(yBottom);
				quadsAsGlTriangles.put(xLeft);  quadsAsGlTriangles.put(yBottom);
				outlinesAsGlLines.put(xLeft);  outlinesAsGlLines.put(yBottom);
				outlinesAsGlLines.put(xLeft);  outlinesAsGlLines.put(yTop);
				outlinesAsGlLines.put(xLeft);  outlinesAsGlLines.put(yTop);
				outlinesAsGlLines.put(xRight); outlinesAsGlLines.put(yTop);
				outlinesAsGlLines.put(xRight); outlinesAsGlLines.put(yTop);
				outlinesAsGlLines.put(xRight); outlinesAsGlLines.put(yBottom);
				outlinesAsGlLines.put(xRight); outlinesAsGlLines.put(yBottom);
				outlinesAsGlLines.put(xLeft);  outlinesAsGlLines.put(yBottom);
				if(xLeft + padding < xRight) // only draw a label if there is at least 1 pixel of space
					labelPositions.add(new int[] {(int) xLeft, (int) xRight});
				if(clickable && mouseX >= xLeft && mouseX <= xRight && mouseY >= yBottom && mouseY <= yTop) {
					clickHandler.set(EventHandler.onPress(event -> OpenGLCharts.GUI.setPaused(range.startingTimestamp(), connection, range.startingSampleNumber())));
					mouseOverOutline.rewind();
					mouseOverOutline.put(xLeft);  mouseOverOutline.put(yBottom);
					mouseOverOutline.put(xLeft);  mouseOverOutline.put(yTop);
					mouseOverOutline.put(xLeft);  mouseOverOutline.put(yTop);
					mouseOverOutline.put(xRight); mouseOverOutline.put(yTop);
					mouseOverOutline.put(xRight); mouseOverOutline.put(yTop);
					mouseOverOutline.put(xRight); mouseOverOutline.put(yBottom);
					mouseOverOutline.put(xRight); mouseOverOutline.put(yBottom);
					mouseOverOutline.put(xLeft);  mouseOverOutline.put(yBottom);
				}
			});
			OpenGL.drawTrianglesXY(gl, GL.GL_TRIANGLES, state.glColor, quadsAsGlTriangles.rewind(), quadCount * 6);
			OpenGL.drawLinesXy(gl, GL.GL_LINES, Theme.markerBorderColor, outlinesAsGlLines.rewind(), quadCount * 8);
			labelPositions.forEach(position -> {
				int xLeft = position[0];
				int xRight = Integer.min(position[1], (int) plotWidth);
				int[] originalScissorArgs = new int[4];
				gl.glGetIntegerv(GL3.GL_SCISSOR_BOX, originalScissorArgs, 0);
				gl.glScissor(originalScissorArgs[0] + xLeft, originalScissorArgs[1] + (int) yBottom, Integer.max(0, (int) (xRight - xLeft)), (int) (yTop - yBottom));
				OpenGL.drawSmallText(gl, state.name, (int) (xLeft + padding), (int) yTextBaseline, 0);
				gl.glScissor(originalScissorArgs[0], originalScissorArgs[1], originalScissorArgs[2], originalScissorArgs[3]);
			});
		});
		if(clickable && mouseOverOutline.position() != 0)
			OpenGL.drawLinesXy(gl, GL.GL_LINES, Theme.tooltipBorderColor, mouseOverOutline.flip(), 1 * 8);
		
		// draw a warning if there was not enough space for all edges or levels
		if(insufficientSpace.get() == true) {
			float gradientLength = 10 * Settings.GUI.getChartScalingFactor();
			float[] red            = new float[] {1, 0, 0, 1};
			float[] transparentRed = new float[] {1, 0, 0, 0};
			float padding = 6f * Settings.GUI.getChartScalingFactor();
			
			// top gradient
			OpenGL.buffer.rewind();
			OpenGL.buffer.put(0);         OpenGL.buffer.put(plotHeight);                  OpenGL.buffer.put(red);
			OpenGL.buffer.put(0);         OpenGL.buffer.put(plotHeight - gradientLength); OpenGL.buffer.put(transparentRed);
			OpenGL.buffer.put(plotWidth); OpenGL.buffer.put(plotHeight);                  OpenGL.buffer.put(red);
			OpenGL.buffer.put(plotWidth); OpenGL.buffer.put(plotHeight - gradientLength); OpenGL.buffer.put(transparentRed);
			OpenGL.buffer.rewind();
			OpenGL.drawTrianglesXYRGBA(gl, GL.GL_TRIANGLE_STRIP, OpenGL.buffer, 4);
			
			// bottom gradient
			OpenGL.buffer.rewind();
			OpenGL.buffer.put(0);         OpenGL.buffer.put(gradientLength); OpenGL.buffer.put(transparentRed);
			OpenGL.buffer.put(0);         OpenGL.buffer.put(0);              OpenGL.buffer.put(red);
			OpenGL.buffer.put(plotWidth); OpenGL.buffer.put(gradientLength); OpenGL.buffer.put(transparentRed);
			OpenGL.buffer.put(plotWidth); OpenGL.buffer.put(0);              OpenGL.buffer.put(red);
			OpenGL.buffer.rewind();
			OpenGL.drawTrianglesXYRGBA(gl, GL.GL_TRIANGLE_STRIP, OpenGL.buffer, 4);
			
			// left gradient
			OpenGL.buffer.rewind();
			OpenGL.buffer.put(0);              OpenGL.buffer.put(plotHeight); OpenGL.buffer.put(red);
			OpenGL.buffer.put(0);              OpenGL.buffer.put(0);          OpenGL.buffer.put(red);
			OpenGL.buffer.put(gradientLength); OpenGL.buffer.put(plotHeight); OpenGL.buffer.put(transparentRed);
			OpenGL.buffer.put(gradientLength); OpenGL.buffer.put(0);          OpenGL.buffer.put(transparentRed);
			OpenGL.buffer.rewind();
			OpenGL.drawTrianglesXYRGBA(gl, GL.GL_TRIANGLE_STRIP, OpenGL.buffer, 4);
			
			// right gradient
			OpenGL.buffer.rewind();
			OpenGL.buffer.put(plotWidth - gradientLength); OpenGL.buffer.put(plotHeight); OpenGL.buffer.put(transparentRed);
			OpenGL.buffer.put(plotWidth - gradientLength); OpenGL.buffer.put(0);          OpenGL.buffer.put(transparentRed);
			OpenGL.buffer.put(plotWidth);                  OpenGL.buffer.put(plotHeight); OpenGL.buffer.put(red);
			OpenGL.buffer.put(plotWidth);                  OpenGL.buffer.put(0);          OpenGL.buffer.put(red);
			OpenGL.buffer.rewind();
			OpenGL.drawTrianglesXYRGBA(gl, GL.GL_TRIANGLE_STRIP, OpenGL.buffer, 4);
			
			String text = "Insufficent Room for All Markers";
			float textWidth = OpenGL.smallTextWidth(gl, text);
			float textLeftX = plotWidth / 2f - (textWidth / 2f);
			float textRightX = plotWidth / 2f + (textWidth / 2f);
			float textBottomY = padding;
			float textTopY = textBottomY + OpenGL.smallTextHeight;
			
			// text background
			OpenGL.drawQuad2D(gl, red, textLeftX - padding,  textBottomY - padding,
			                           textRightX + padding, textTopY + padding);
			
			OpenGL.drawSmallText(gl, text, (int) textLeftX, (int) textBottomY, 0); 
		}
		
		return clickHandler.get();
		
	}
	
	/**
	 * @param dataset    Dataset (normal/edge/level.)
	 * @return           Corresponding samples cache.
	 */
	public StorageFloats.Cache cacheFor(Field dataset) {
		
		return sampleCaches.get(dataset);
		
	}
	
	enum DurationUnit {
		SAMPLES      { @Override public String toString() { return "Samples"; } },
		MILLISECONDS { @Override public String toString() { return "Seconds"; } } // shown to user as seconds, given to charts as milliseconds
	};
	
	/**
	 * A widget that allows the user select zero or more normal datasets (but not bitfields.)
	 */
	public WidgetDatasets getCheckboxesWidget(Consumer<List<Field>> datasetsHandler) {
		WidgetDatasets w = new WidgetDatasets();
		w.datasetsHandler = datasetsHandler;
		w.createAndUpdateWidgets();
		return w;
	}
	
	/**
	 * A widget that allows the user to select zero or more bitfield edges or levels (but not normal datasets.)
	 */
	public WidgetDatasets getButtonsWidget(Consumer<List<Field.Bitfield.State>> edgesHandler, Consumer<List<Field.Bitfield.State>> levelsHandler) {
		WidgetDatasets w = new WidgetDatasets();
		w.edgesHandler = edgesHandler;
		w.levelsHandler = levelsHandler;
		w.createAndUpdateWidgets();
		return w;
	}
	
	
	/**
	 * A widget that allows the user to select zero or more normal datasets, zero or more bitfield edges or levels, and specify the chart duration.
	 */
	public WidgetDatasets getCheckboxesAndButtonsWidget(Consumer<List<Field>> datasetsHandler, Consumer<List<Field.Bitfield.State>> edgesHandler, Consumer<List<Field.Bitfield.State>> levelsHandler, BiConsumer<DurationUnit, Long> durationHandler, boolean allowTime) {
		WidgetDatasets w = new WidgetDatasets();
		w.datasetsHandler = datasetsHandler;
		w.edgesHandler    = edgesHandler;
		w.levelsHandler   = levelsHandler;
		
		long defaultSampleCount = Connections.telemetryConnections.isEmpty() ? 10_000 :
		                          Long.min(Connections.telemetryConnections.get(0).getSampleRate() * 10L, Integer.MAX_VALUE / 16);
		
		w.duration = WidgetTextfield.ofText(Long.toString(defaultSampleCount))
		                            .setExportLabel("duration")
		                            .setSuffix(allowTime ? "" : "Samples")
		                            .onChange((newText, oldText) -> {
		                                 try {
		                                     if(w.durationUnit.is(DurationUnit.SAMPLES)) {
		                                         long sampleCount = Long.parseLong(newText);
		                                         if(sampleCount < 2 || sampleCount > Integer.MAX_VALUE / 16)
		                                             return false;
		                                     } else {
		                                         double seconds = Double.parseDouble(newText);
		                                         long milliseconds = Math.round(seconds * 1000.0);
		                                         if(milliseconds < 1 || milliseconds > Integer.MAX_VALUE / 16)
		                                             return false;
		                                     }
		                                 } catch(Exception e) {
		                                     return false;
		                                 }
		                                 durationHandler.accept(w.durationUnit.get(),
		                                                        w.durationUnit.is(DurationUnit.SAMPLES) ?
		                                                        Long.parseLong(w.duration.get()) :
		                                                        Math.round(Double.parseDouble(w.duration.get()) * 1000.0));
		                                 return true;
		                             });
		
		w.durationUnit = new WidgetToggleButton<DurationUnit>(null, DurationUnit.values(), DurationUnit.SAMPLES)
		                     .setExportLabel("duration unit")
		                     .setVisible(allowTime ? true : false)
		                     .onChange((newUnit, oldUnit) -> {
		                         if(oldUnit == DurationUnit.SAMPLES && newUnit == DurationUnit.MILLISECONDS) {
		                             // convert from sample count to seconds
		                             int sampleRateHz = (connection != null) ? connection.getSampleRate() : Connections.telemetryConnections.get(0).getSampleRate();
		                             double seconds = Long.parseLong(w.duration.get()) / (double) sampleRateHz;
		                             w.duration.set(Double.toString(seconds));
		                         } else if(oldUnit == DurationUnit.MILLISECONDS && newUnit == DurationUnit.SAMPLES) {
		                             // convert from seconds to sample count
		                             int sampleRateHz = (connection != null) ? connection.getSampleRate() : Connections.telemetryConnections.get(0).getSampleRate();
		                             long sampleCount = Math.round(Double.parseDouble(w.duration.get()) * (double) sampleRateHz);
		                             w.duration.set(Long.toString(sampleCount));
		                         }
		                         return true;
		                     });
		
		// immediately call the duration event handler with the default values
		// this is needed so the chart doesn't start off with a one frame glitch where duration is zero
		durationHandler.accept(DurationUnit.SAMPLES, defaultSampleCount);

		w.createAndUpdateWidgets();
		return w;
			
	}
	
	/**
	 * A Widget that lets the user select one dataset or one bitfield state.
	 */
	public WidgetDatasets getDatasetOrStateCombobox(Consumer<Field> datasetHandler, Consumer<Field.Bitfield.State> stateHandler) {
		List<String> options = Connections.telemetryConnections.stream()
		                                  .flatMap(connection -> connection.getDatasetsList().stream())
		                                  .flatMap(dataset -> {
		                                      if(!dataset.isBitfield)
		                                          return Stream.of(dataset.toString());
		                                      else
		                                          return dataset.bitfields.stream().flatMap(bitfield -> Stream.of(bitfield.states)).map(state -> state.toString());
		                                  })
		                                  .toList();
		String defaultOption = options.isEmpty() ? null : options.getFirst();
		
		WidgetDatasets w = new WidgetDatasets();
		w.triggerChannelCombobox = new WidgetCombobox<String>("Channel", options, defaultOption)
		                               .setExportLabel("trigger channel")
		                               .onChange((newChannelName, oldChannelName) -> {
		                                    if(newChannelName != null) {
		                                        var dataset = Connections.telemetryConnections.stream()
		                                                                 .flatMap(connection -> connection.getDatasetsList().stream())
		                                                                 .filter(d -> !d.isBitfield)
		                                                                 .filter(d -> d.toString().equals(newChannelName))
		                                                                 .findFirst().orElse(null);
		                                        var state   = Connections.telemetryConnections.stream()
		                                                                 .flatMap(connection -> connection.getDatasetsList().stream())
		                                                                 .filter(d -> d.isBitfield)
		                                                                 .flatMap(d -> d.bitfields.stream().flatMap(b -> Stream.of(b.states)))
		                                                                 .filter(s -> s.toString().equals(newChannelName))
		                                                                 .findFirst().orElse(null);
		                                        if(dataset != null) {
		                                            normalDatasets.clear();
		                                            normalDatasets.add(dataset);
		                                            flushCaches();
		                                            if(datasetHandler != null)
		                                                datasetHandler.accept(dataset);
		                                        } else if(state != null) {
		                                            edgeStates.clear();
		                                            edgeStates.add(state);
		                                            flushCaches();
		                                            if(stateHandler != null)
		                                                stateHandler.accept(state);
		                                        }
		                                    }
		                                    return true;
		                                });
		return w;
	}
	
	/**
	 * A widget that allows the user to select a fixed number of normal datasets (but not bitfields.)
	 */
	public WidgetDatasets getComboboxesWidget(List<String> labels, Consumer<List<Field>> eventHandler) {
		List<Field> fields = Connections.telemetryConnections.stream()
		                                .flatMap(connection -> connection.getDatasetsList().stream())
		                                .filter(field -> !field.isBitfield)
		                                .toList();
		if(normalDatasets.isEmpty() && !fields.isEmpty()) {
			for(int i = 0; i < labels.size(); i++)
				normalDatasets.add(fields.getFirst());
			flushCaches();
		}
		
		WidgetDatasets w = new WidgetDatasets();
		w.datasetsHandler = eventHandler;
		for(int i = 0; i < labels.size(); i++) {
			int num = i; // constant for lambda below
			w.comboboxes.add(new WidgetCombobox<Field>(labels.get(i), fields, fields.isEmpty() ? null : fields.getFirst())
			                     .setExportLabel(labels.get(i).toLowerCase())
			                     .onChange((newValue, oldValue) -> {
			                          // force all selected datasets to be from the same connection
			                          w.comboboxes.forEach(combobox -> {
			                              Field f = combobox.get();
			                              if(f != null && f.connection != newValue.connection)
			                                  combobox.set(newValue);
			                          });
			                          
			                          if(newValue != null)
			                              normalDatasets.set(num, newValue);
			                          flushCaches();
			                          
			                          if(w.datasetsHandler != null)
			                              w.datasetsHandler.accept(normalDatasets);
			                          
			                          return true;
			                      }));
		}
		return w;
	}
	
	class WidgetDatasets implements Widget {
		
		public  Map<Field, WidgetCheckbox>                     datasets = new TreeMap<Field, WidgetCheckbox>();
		private Consumer<List<Field>>                          datasetsHandler;
		public  List<WidgetToggleButton<Field.Bitfield.State>> edges = new ArrayList<WidgetToggleButton<Field.Bitfield.State>>();
		private Consumer<List<Field.Bitfield.State>>           edgesHandler;
		public  List<WidgetToggleButton<Field.Bitfield.State>> levels = new ArrayList<WidgetToggleButton<Field.Bitfield.State>>();
		private Consumer<List<Field.Bitfield.State>>           levelsHandler;
		public  WidgetTextfield<String>                        duration;
		public  WidgetToggleButton<DurationUnit>               durationUnit;

		public List<WidgetCombobox<Field>> comboboxes = new ArrayList<WidgetCombobox<Field>>();
		public WidgetCombobox<String> triggerChannelCombobox = null;
		private boolean isEnabled = true;
		private boolean isVisible = true;
		
		/**
		 * Ensures the widgets reflect the currently present datasets.
		 * This function must be called any time a connection or dataset may have been added or removed.
		 */
		private void createAndUpdateWidgets() {
			
			List<Field>                currentDatasets  = Connections.telemetryConnections.stream().flatMap(connection -> connection.getDatasetsList().stream()).filter(field -> !field.isBitfield).toList();
			List<Field>                currentBitfields = Connections.telemetryConnections.stream().flatMap(connection -> connection.getDatasetsList().stream()).filter(field -> field.isBitfield).toList();
			List<Field.Bitfield.State> currentStates    = Connections.telemetryConnections.stream().flatMap(connection -> connection.getDatasetsList().stream()).filter(field -> field.isBitfield).flatMap(field -> field.bitfields.stream()).flatMap(bitfield -> Stream.of(bitfield.states)).filter(state -> !state.name.isEmpty()).toList();
			
			if(triggerChannelCombobox != null) {
				// trigger channel combobox
				List<String> currentOptions = Connections.telemetryConnections.stream()
				                                         .flatMap(connection -> connection.getDatasetsList().stream())
				                                         .flatMap(dataset -> {
				                                             if(!dataset.isBitfield)
				                                                 return Stream.of(dataset.toString());
				                                             else
				                                                 return dataset.bitfields.stream().flatMap(bitfield -> Stream.of(bitfield.states)).map(state -> state.toString());
				                                         })
				                                         .toList();
				String selectedChannel = triggerChannelCombobox.get();
				triggerChannelCombobox.resetValues(currentOptions, selectedChannel);
				triggerChannelCombobox.setEnabled(currentOptions.isEmpty() ? false : isEnabled);
			} else if(!comboboxes.isEmpty()) {
				// dataset comboboxes
				comboboxes.forEach(combobox -> {
					Field selectedField = combobox.get();
					combobox.resetValues(currentDatasets, selectedField);
					combobox.setEnabled(currentDatasets.isEmpty() ? false : isEnabled);
				});
			} else if(datasetsHandler != null) {
				// dataset checkboxes
				datasets.entrySet().removeIf(entry -> !currentDatasets.contains(entry.getKey()));
				currentDatasets.forEach(field -> {
				    if(datasets.containsKey(field))
				        datasets.get(field).setText(field.toString());
				    else
				        datasets.put(field, new WidgetCheckbox(field.toString(), false)
				                                .onChange(isSelected -> {
				                                     if(isSelected)
				                                         normalDatasets.add(field);
				                                     else
				                                         normalDatasets.remove(field);
				                                     flushCaches();
				                                     disableWidgetsForOtherConnections();
				                                     if(datasetsHandler != null)
				                                         datasetsHandler.accept(normalDatasets);
				                                }));
				});
			}
			
			// bitfield edges and levels
			if(edgesHandler != null && levelsHandler != null) {
				edges.removeIf(button -> !currentBitfields.contains(button.get().dataset));
				levels.removeIf(button -> !currentBitfields.contains(button.get().dataset));
				currentStates.forEach(state -> {
				    if(edges.stream().noneMatch(button -> button.get() == state))
				        edges.add(new WidgetToggleButton<Field.Bitfield.State>(false, "Show as tooltip", null, state, isSelected -> {
				                          if(isSelected)
				                              edgeStates.add(state);
				                          else
				                              edgeStates.remove(state);
				                          flushCaches();
				                          disableWidgetsForOtherConnections();
				                          if(edgesHandler != null)
				                              edgesHandler.accept(edgeStates);
				                      }));
				    if(levels.stream().noneMatch(button -> button.get() == state))
				        levels.add(new WidgetToggleButton<Field.Bitfield.State>(false, "Show as level", state.name, state, isSelected -> {
				                           if(isSelected)
				                               levelStates.add(state);
				                           else
				                               levelStates.remove(state);
				                           flushCaches();
				                           disableWidgetsForOtherConnections();
				                           if(levelsHandler != null)
				                               levelsHandler.accept(levelStates);
				                       }));
				});
			}
			
			 edges.sort((a, b) -> a.get().compareTo(b.get()));
			levels.sort((a, b) -> a.get().compareTo(b.get()));
			disableWidgetsForOtherConnections();
			
		}
		
		@Override public void appendTo(JPanel panel, String constraints) {
			
			if(!isVisible)
				return;
			
			createAndUpdateWidgets();
			
			if(triggerChannelCombobox != null)
				triggerChannelCombobox.appendTo(panel, "");
			comboboxes.forEach(combobox -> combobox.appendTo(panel, ""));
			datasets.values().forEach(checkbox -> checkbox.appendTo(panel, ""));
			for(int i = 0; i < edges.size(); i++) {
				edges.get(i).appendTo(panel, "split 3");
				levels.get(i).appendTo(panel, "");
			}
			if(duration != null) {
				duration.appendTo(panel, "split 3, grow");
				durationUnit.appendTo(panel, "shrink");
			}
			
		}
		
		@Override public WidgetDatasets setVisible(boolean isVisible) {
			
			this.isVisible = isVisible;
			
			if(triggerChannelCombobox != null)
				triggerChannelCombobox.setVisible(isVisible);
			comboboxes.forEach(combobox -> combobox.setVisible(isVisible));
			datasets.values().forEach(checkbox -> checkbox.setVisible(isVisible));
			edges.forEach(button -> button.setVisible(isVisible));
			levels.forEach(button -> button.setVisible(isVisible));
			if(duration != null) {
				duration.setVisible(isVisible);
				durationUnit.setVisible(isVisible);
			}
			
			// also resize the ConfigureView if it's on screen
			if(!Configure.GUI.getPreferredSize().equals(new Dimension(0, 0))) {
				Configure.GUI.setPreferredSize(null);
				Configure.GUI.revalidate();
				Configure.GUI.repaint();
			}
			
			return this;
			
		}
		
		public WidgetDatasets setEnabled(boolean isEnabled) {
			this.isEnabled = isEnabled;
			if(triggerChannelCombobox != null)
				triggerChannelCombobox.setEnabled(isEnabled);
			comboboxes.forEach(combobox -> combobox.setEnabled(isEnabled));
			return this;
		}

		private void disableWidgetsForOtherConnections() {
			
			if(connection == null) {
				// nothing selected, so re-enable all widgets
				datasets.values().forEach(checkbox -> checkbox.setEnabled(true));
				edges.forEach(button -> button.setEnabled(true));
				levels.forEach(button -> button.setEnabled(true));
			} else {
				// only enable widgets for the selected connection
				datasets.entrySet().forEach(entry -> entry.getValue().setEnabled( entry.getKey().connection == connection ));
				edges.forEach(button -> button.setEnabled( button.get().connection == connection ));
				levels.forEach(button -> button.setEnabled( button.get().connection == connection ));
			}
			
		}
		
		@Override public void importFrom(Connections.QueueOfLines lines) throws AssertionError {
			
			if(triggerChannelCombobox != null) {
				triggerChannelCombobox.importFrom(lines);
				return;
			}
			
			List<Field> fields = Connections.telemetryConnections.stream().flatMap(connection -> connection.getDatasetsList().stream()).toList();
			
			if(!comboboxes.isEmpty()) {
				List<String> list = List.of(lines.parseString("datasets = %s").split(","));
				if(list.size() != comboboxes.size())
					throw new AssertionError("Invalid datasets list.");
				for(int i = 0; i < list.size(); i++) {
					String text = list.get(i);
					comboboxes.get(i).set(fields.stream()
					                            .filter(field -> field.toString().equals(text))
					                            .findFirst().orElseThrow(() -> new AssertionError("Invalid datasets list.")));
				}
				return;
			}
			
			Stream.of(lines.parseString("datasets = %s").split(","))
			      .filter(text -> !text.isEmpty())
			      .forEach(text -> datasets.entrySet().stream()
			                                          .filter(entry -> entry.getKey().toString().equals(text))
			                                          .findFirst().orElseThrow(() -> new AssertionError("Invalid datasets list."))
			                                          .getValue().set(true));
			
			Stream.of(lines.parseString("bitfield edge states = %s").split(","))
			      .filter(text -> !text.isEmpty())
			      .forEach(text -> edges.stream()
			                            .filter(button -> button.get().toString().equals(text))
			                            .findFirst().orElseThrow(() -> new AssertionError("Invalid bitfield edge states list."))
			                            .setSelected(true));
			
			Stream.of(lines.parseString("bitfield level states = %s").split(","))
			      .filter(text -> !text.isEmpty())
			      .forEach(text -> levels.stream()
			                             .filter(button -> button.get().toString().equals(text))
			                             .findFirst().orElseThrow(() -> new AssertionError("Invalid bitfield level states list."))
			                             .setSelected(true));
			
			if(duration != null) {
				duration.importFrom(lines);
				durationUnit.importFrom(lines);
			}
			
		}
		
		@Override public void exportTo(PrintWriter file) {
			
			if(triggerChannelCombobox != null) {
				triggerChannelCombobox.exportTo(file);
				return;
			}
			
			if(!comboboxes.isEmpty()) {
				file.println("\t" + "datasets = " + comboboxes.stream()
				                                              .map(combobox -> combobox.get())
				                                              .filter(field -> field != null)
				                                              .map(field -> field.toString())
				                                              .collect(Collectors.joining(",")));
				return;
			}
			
			file.println("\t" + "datasets = " + datasets.entrySet().stream()
			                                                       .filter(entry -> entry.getValue().isTrue())
			                                                       .map(entry -> entry.getKey())
			                                                       .map(field -> field.toString())
			                                                       .collect(Collectors.joining(",")));
			
			file.println("\t" + "bitfield edge states = " + edges.stream()
			                                                     .filter(button -> button.isSelected())
			                                                     .map(button -> button.get().toString())
			                                                     .collect(Collectors.joining(",")));
			
			file.println("\t" + "bitfield level states = " + levels.stream()
			                                                       .filter(button -> button.isSelected())
			                                                       .map(button -> button.get().toString())
			                                                       .collect(Collectors.joining(",")));
			
			if(duration != null) {
				duration.exportTo(file);
				durationUnit.exportTo(file);
			}
			
		}

	}
	
}