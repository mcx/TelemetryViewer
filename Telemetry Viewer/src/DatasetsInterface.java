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
import java.util.function.Function;
import java.util.stream.Stream;

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
	private Map<Integer, PositionedChart.Tooltip> edgesCache = new TreeMap<Integer, PositionedChart.Tooltip>();
	private Map<Field.Bitfield.State, List<Field.Bitfield.LevelRange>> levelsCache  = new TreeMap<Field.Bitfield.State, List<Field.Bitfield.LevelRange>>();
	private int edgesLevelsCacheStartingSampleNumber = -1;
	private int edgesLevelsCacheEndingSampleNumber   = -1;
	
	public DatasetsInterface() { }
	
	public DatasetsInterface(ConnectionTelemetry connection) {
		
		this.connection = connection;
		timestampsCache = connection.createTimestampsCache();
		
	}
	
	/**
	 * Sets the normal (non-bitfield) datasets that can be subsequently accessed, replacing any existing ones.
	 * 
	 * @param newDatasets    Normal datasets to use.
	 */
	public void setNormals(List<Field> newDatasets) {
		
		// update normal datasets
		normalDatasets.clear();
		normalDatasets.addAll(newDatasets);
		
		// update caches
		sampleCaches.clear();
		normalDatasets.forEach(dataset -> sampleCaches.put(dataset, dataset.createCache()));
		edgeStates.forEach(state -> sampleCaches.put(state.dataset, state.dataset.createCache()));
		levelStates.forEach(state -> sampleCaches.put(state.dataset, state.dataset.createCache()));
		edgesCache.clear();
		levelsCache.clear();
		levelStates.forEach(level -> levelsCache.put(level, new ArrayList<Field.Bitfield.LevelRange>()));
		edgesLevelsCacheStartingSampleNumber = -1;
		edgesLevelsCacheEndingSampleNumber = -1;
		
		// update the connection
		connection = !normalDatasets.isEmpty() ? normalDatasets.get(0).connection :
		             !edgeStates.isEmpty()     ? edgeStates.get(0).connection :
		             !levelStates.isEmpty()    ? levelStates.get(0).connection :
		                                         null;
		
		// update timestamps cache
		timestampsCache = (connection == null) ? null : connection.createTimestampsCache();
		
	}
	
	/**
	 * Sets the bitfield edge states that can be subsequently accessed, replacing any existing ones.
	 * 
	 * @param newEdges    Bitfield edge states to use.
	 */
	public void setEdges(List<Field.Bitfield.State> newEdges) {
		
		// update edge datasets
		edgeStates.clear();
		edgeStates.addAll(newEdges);
		
		// update caches
		sampleCaches.clear();
		normalDatasets.forEach(dataset -> sampleCaches.put(dataset, dataset.createCache()));
		edgeStates.forEach(state -> sampleCaches.put(state.dataset, state.dataset.createCache()));
		levelStates.forEach(state -> sampleCaches.put(state.dataset, state.dataset.createCache()));
		edgesCache.clear();
		levelsCache.clear();
		levelStates.forEach(level -> levelsCache.put(level, new ArrayList<Field.Bitfield.LevelRange>()));
		edgesLevelsCacheStartingSampleNumber = -1;
		edgesLevelsCacheEndingSampleNumber = -1;
		
		// update the connection
		connection = !normalDatasets.isEmpty() ? normalDatasets.get(0).connection :
		             !edgeStates.isEmpty()     ? edgeStates.get(0).connection :
		             !levelStates.isEmpty()    ? levelStates.get(0).connection :
		                                         null;
		
		// update timestamps cache
		timestampsCache = (connection == null) ? null : connection.createTimestampsCache();
		
	}
	
	/**
	 * Sets the bitfield level states that can be subsequently accessed, replacing any existing ones.
	 * 
	 * @param newLevels    Bitfield level states to use.
	 */
	public void setLevels(List<Field.Bitfield.State> newLevels) {
		
		// update level datasets
		levelStates.clear();
		levelStates.addAll(newLevels);
		
		// update caches
		sampleCaches.clear();
		normalDatasets.forEach(dataset -> sampleCaches.put(dataset, dataset.createCache()));
		edgeStates.forEach(state -> sampleCaches.put(state.dataset, state.dataset.createCache()));
		levelStates.forEach(state -> sampleCaches.put(state.dataset, state.dataset.createCache()));
		edgesCache.clear();
		levelsCache.clear();
		levelStates.forEach(level -> levelsCache.put(level, new ArrayList<Field.Bitfield.LevelRange>()));
		edgesLevelsCacheStartingSampleNumber = -1;
		edgesLevelsCacheEndingSampleNumber = -1;
		
		// update the connection
		connection = !normalDatasets.isEmpty() ? normalDatasets.get(0).connection :
		             !edgeStates.isEmpty()     ? edgeStates.get(0).connection :
		             !levelStates.isEmpty()    ? levelStates.get(0).connection :
		                                         null;
		
		// update timestamps cache
		timestampsCache = (connection == null) ? null : connection.createTimestampsCache();
		
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
	public Map<Integer, PositionedChart.Tooltip> getEdgesBetween(int minSampleNumber, int maxSampleNumber, boolean sampleCountMode) {
		
		// sanity checks
		if(minSampleNumber < 0)
			return new TreeMap<Integer, PositionedChart.Tooltip>();
		if(minSampleNumber >= maxSampleNumber)
			return new TreeMap<Integer, PositionedChart.Tooltip>();
		
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
	public Map<Field.Bitfield.State, List<Field.Bitfield.LevelRange>> getLevelsBetween(int minSampleNumber, int maxSampleNumber, boolean sampleCountMode) {
		
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
	 * Gets a sample as a String.
	 * 
	 * @param dataset         Dataset.
	 * @param sampleNumber    Sample number.
	 * @return                The sample, as a String.
	 */
	public String getSampleAsString(Field dataset, int sampleNumber) {
		
		return dataset.getSampleAsString(sampleNumber, cacheFor(dataset));
		
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
		
		return connection.getClosestSampleNumberAtOrBefore(timestamp, maxSampleNumber, timestampsCache);
		
	}
	
	public int getClosestSampleNumberAfter(long timestamp) {
		
		return connection.getClosestSampleNumberAfter(timestamp, timestampsCache);
		
	}
	
	public long getTimestamp(int sampleNumber) {
		
		return connection.getTimestamp(sampleNumber, timestampsCache);
		
	}
	
	public FloatBuffer getTimestampsBuffer(int firstSampleNumber, int lastSampleNumber, long plotMinX) {
		
		return connection.getTimestampsBuffer(firstSampleNumber, lastSampleNumber, plotMinX, timestampsCache);
		
	}
	
	public LongBuffer getTimestampsBuffer(int firstSampleNumber, int lastSampleNumber) {
		
		return connection.getTimestampsBuffer(firstSampleNumber, lastSampleNumber, timestampsCache);
		
	}
	
	/**
	 * Gets the range (y-axis region) occupied by all of the normal datasets.
	 *  
	 * @param minSampleNumber    Minimum sample number, inclusive.
	 * @param maxSampleNumber    Maximum sample number, inclusive.
	 * @return                   The range, as [0] = minY, [1] = maxY.
	 *                           If there are no normal datasets, [-1, 1] will be returned.
	 *                           If the range is a single value, [value +/- 0.001] will be returned.
	 */
	public float[] getRange(int minSampleNumber, int maxSampleNumber) {
		
		float[] minMax = new float[] {Float.MAX_VALUE, -Float.MAX_VALUE};

		normalDatasets.forEach(dataset -> {
			if(!dataset.isBitfield) {
				StorageFloats.MinMax range = dataset.getRange(minSampleNumber, maxSampleNumber, cacheFor(dataset));
				minMax[0] = Math.min(minMax[0], range.min);
				minMax[1] = Math.max(minMax[1], range.max);
			}
		});
		
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
		List<PositionedChart.Tooltip> tooltips = getEdgesBetween((int) minSampleNumber, (int) maxSampleNumber, sampleCountMode)
		                                         .values().stream().toList();
		
		// check if the mouse is near a tooltip
		PositionedChart.Tooltip closestTooltip = null;
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
				PositionedChart.Tooltip closestTooltipAtOrBefore = null;
				PositionedChart.Tooltip closestTooltipAfter      = null;
				for(PositionedChart.Tooltip tooltip : tooltips) {
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
		List<PositionedChart.Tooltip.Drawable> bakedTooltips = tooltips.stream().map(tooltip -> tooltip.bake(gl, plotWidth, plotHeight, -1, -1, xToPixelX.apply(sampleCountMode ? tooltip.sampleNumber : tooltip.timestamp))).toList();
		for(int i = 0; i < bakedTooltips.size(); i++) {
			PositionedChart.Tooltip.Drawable next = (i == bakedTooltips.size() - 1) ? null : bakedTooltips.get(i + 1);
			if(!bakedTooltips.get(i).draw(gl, closestTooltip != null, next))
				insufficientSpace.set(true);
		}
		
		// draw the tooltip closest to the mouse on top
		// if clickable, and if the mouse is over that tooltip, it will be drawn with a black border
		if(closestTooltip != null) {
			PositionedChart.Tooltip.Drawable baked = closestTooltip.bake(gl, plotWidth, plotHeight, clickable ? mouseX : -1, clickable ? mouseY : -1, xToPixelX.apply(sampleCountMode ? closestTooltip.sampleNumber : closestTooltip.timestamp));
			if(!baked.draw(gl, false, null))
				insufficientSpace.set(true);
			
			if(clickable && mouseX >= baked.xBoxLeft() && mouseX <= baked.xBoxRight() && mouseY >= baked.yBoxBottom() && mouseY <= baked.yBoxTop()) {
				int sampleNumber = closestTooltip.sampleNumber;
				long timestamp = closestTooltip.timestamp;
				clickHandler.set(EventHandler.onPress(event -> OpenGLChartsView.instance.setPaused(timestamp, connection, sampleNumber)));
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
			float padding       = 6f * ChartsController.getDisplayScalingFactor();
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
					clickHandler.set(EventHandler.onPress(event -> OpenGLChartsView.instance.setPaused(range.startingTimestamp(), connection, range.startingSampleNumber())));
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
			float gradientLength = 10 * ChartsController.getDisplayScalingFactor();
			float[] red            = new float[] {1, 0, 0, 1};
			float[] transparentRed = new float[] {1, 0, 0, 0};
			float padding = 6f * ChartsController.getDisplayScalingFactor();
			
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
	
}