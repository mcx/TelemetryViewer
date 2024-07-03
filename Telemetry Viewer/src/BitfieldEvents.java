import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class BitfieldEvents {
	
	boolean showSampleNumbers;
	boolean showTimestamps;
	DatasetsInterface datasets;
	int minSampleNumber;
	int maxSampleNumber;
	
	Map<Integer, EdgeMarker> edgeMarkers; // key is a sample number, value is an object describing all edge events at that sample number
	Map<Field.Bitfield, LevelMarker> levelMarkers; // key is a bitfield, value is a list of level markers for the chosen states
	
	/**
	 * Creates a list of all bitfield events that should be displayed on a chart.
	 * 
	 * @param showSampleNumbers    True if the sample number should be displayed at the top of each marker.
	 * @param showTimestamps       True if the date/time should be displayed at the top of each marker.
	 * @param datasets             Bitfield edges and levels to check for.
	 * @param minSampleNumber      Range of samples numbers to check (inclusive.)
	 * @param maxSampleNumber      Range of samples numbers to check (inclusive.)
	 */
	public BitfieldEvents(boolean showSampleNumbers, boolean showTimestamps, DatasetsInterface datasets, int minSampleNumber, int maxSampleNumber) {
		
		this.showSampleNumbers = showSampleNumbers;
		this.showTimestamps = showTimestamps;
		this.datasets = datasets;
		this.minSampleNumber = minSampleNumber;
		this.maxSampleNumber = maxSampleNumber;
		
		edgeMarkers = new TreeMap<Integer, EdgeMarker>();
		levelMarkers = new TreeMap<Field.Bitfield, LevelMarker>((a, b) -> {return a.dataset.location.get() - b.dataset.location.get();}); // use a comparator that only tests the parent Field, not the Bitfield value, so we get one LevelMarker per Field
		
		if(maxSampleNumber <= minSampleNumber)
			return;
		
		// check for edge events
		datasets.forEachEdge(minSampleNumber, maxSampleNumber, (state, eventSampleNumber) -> {
			if(edgeMarkers.containsKey(eventSampleNumber)) {
				// a marker already exists for this sample number, so append to it
				EdgeMarker event = edgeMarkers.get(eventSampleNumber);
				event.text.add(state.name);
				event.glColors.add(state.glColor);
			} else {
				// a marker does not exist, so create a new one
				edgeMarkers.put(eventSampleNumber, new EdgeMarker(datasets, state, eventSampleNumber));
			}
		});
		
		// check for levels
		datasets.forEachLevel(minSampleNumber, maxSampleNumber, (state, range) -> {
			LevelMarker marker;
			if(levelMarkers.containsKey(state.bitfield)) {
				marker = levelMarkers.get(state.bitfield);
			} else {
				marker = new LevelMarker(state.bitfield);
				levelMarkers.put(state.bitfield, marker);
			}
			marker.sampleNumberRanges.add(new int[] {range[0], range[1]});
			marker.timestampRanges.add(new long[] {datasets.getTimestamp(range[0]), datasets.getTimestamp(range[1])});
			marker.labels.add(state.name);
			marker.glColors.add(state.glColor);
		});
		
	}
	
	/**
	 * Updates the list of all bitfield events that should be displayed on a chart.
	 * 
	 * @param minSampleNumber      Range of samples numbers to check (inclusive.)
	 * @param maxSampleNumber      Range of samples numbers to check (inclusive.)
	 */
	public void update(int minSampleNumber, int maxSampleNumber) {
		
		// flush the existing data if necessary
		if(this.minSampleNumber != minSampleNumber || this.maxSampleNumber > maxSampleNumber) {
			edgeMarkers.clear();
			levelMarkers.clear();
		}
		
		// done if nothing changed
		if(this.minSampleNumber == minSampleNumber && this.maxSampleNumber == maxSampleNumber)
			return;
		
		// check the new range
		
		// check for new edge events
		datasets.forEachEdge(this.maxSampleNumber, maxSampleNumber, (state, eventSampleNumber) -> {
			if(edgeMarkers.containsKey(eventSampleNumber)) {
				// a marker already exists for this sample number, so append to it
				EdgeMarker event = edgeMarkers.get(eventSampleNumber);
				event.text.add(state.name);
				event.glColors.add(state.glColor);
			} else {
				// a marker does not exist, so create a new one
				edgeMarkers.put(eventSampleNumber, new EdgeMarker(datasets, state, eventSampleNumber));
			}
		});
		
		// check for new or extended levels
		datasets.forEachLevel(this.maxSampleNumber, maxSampleNumber, (state, range) -> {
			LevelMarker marker;
			if(levelMarkers.containsKey(state.bitfield)) {
				marker = levelMarkers.get(state.bitfield);
			} else {
				marker = new LevelMarker(state.bitfield);
				levelMarkers.put(state.bitfield, marker);
			}
			
			if(!marker.sampleNumberRanges.isEmpty() && marker.sampleNumberRanges.get(marker.sampleNumberRanges.size() - 1)[1] == this.maxSampleNumber && range[0] == this.maxSampleNumber) {
				// level already exists and should be extended to the new end point
				marker.sampleNumberRanges.get(marker.sampleNumberRanges.size() - 1)[1] = range[1];
				marker.timestampRanges.get(marker.timestampRanges.size() - 1)[1] = datasets.getTimestamp(range[1]);
			} else {
				// add new level
				marker.sampleNumberRanges.add(new int[] {range[0], range[1]});
				marker.timestampRanges.add(new long[] {datasets.getTimestamp(range[0]), datasets.getTimestamp(range[1])});
				marker.labels.add(state.name);
				marker.glColors.add(state.glColor);
			}
		});
		
		// update range
		this.maxSampleNumber = maxSampleNumber;
		
	}
	
	/**
	 * Calculates the pixelX values for each edge marker, then returns the List of markers.
	 * 
	 * @param plotMinX      Sample number that would be at the plot's left edge.
	 * @param plotDomain    Number of samples that make up the x-axis.
	 * @param plotWidth     Width of the plot region, in pixels.
	 * @return              List of all the edge markers.
	 */
	public List<EdgeMarker> getEdgeMarkersSampleCountMode(int plotMinX, int plotDomain, int plotWidth) {
		
		List<EdgeMarker> list = new ArrayList<EdgeMarker>(edgeMarkers.values());
		for(EdgeMarker marker : list)
			marker.pixelX = (marker.sampleNumber - plotMinX) / (float) plotDomain * plotWidth;
		
		return list;
		
	}
	
	/**
	 * Calculates the pixelX values for each edge marker, then returns the List of markers.
	 * 
	 * @param plotMinX      Timestamp that would be at the plot's left edge.
	 * @param plotDomain    Number of milliseconds that make up the x-axis.
	 * @param plotWidth     Width of the plot region, in pixels.
	 * @return              List of all the edge markers.
	 */
	public List<EdgeMarker> getEdgeMarkersMillisecondsMode(long plotMinX, long plotDomain, int plotWidth) {
		
		List<EdgeMarker> list = new ArrayList<EdgeMarker>(edgeMarkers.values());
		for(EdgeMarker marker : list)
			marker.pixelX = (marker.timestamp - plotMinX) / (float) plotDomain * plotWidth;
		
		return list;
		
	}
	
	/**
	 * Calculates the pixelX values for each level marker, then returns the List of markers.
	 * 
	 * @param plotMinX      Sample number that would be at the plot's left edge.
	 * @param plotDomain    Number of samples that make up the x-axis.
	 * @param plotWidth     Width of the plot region, in pixels.
	 * @return              List of all the level markers.
	 */
	public List<LevelMarker> getLevelMarkersSampleCountMode(int plotMinX, int plotDomain, int plotWidth) {
		
		List<LevelMarker> list = new ArrayList<LevelMarker>(levelMarkers.values());
		for(LevelMarker marker : list) {
			marker.pixelXranges.clear();
			for(int[] range : marker.sampleNumberRanges)
				marker.pixelXranges.add(new float[] {(range[0] - plotMinX) / (float) plotDomain * plotWidth,
				                                     (range[1] - plotMinX) / (float) plotDomain * plotWidth});
		}
		
		return list;
		
	}
	
	/**
	 * Calculates the pixelX values for each level marker, then returns the List of markers.
	 * 
	 * @param plotMinX      Timestamp that would be at the plot's left edge.
	 * @param plotDomain    Number of milliseconds that make up the x-axis.
	 * @param plotWidth     Width of the plot region, in pixels.
	 * @return              List of all the level markers.
	 */
	public List<LevelMarker> getLevelMarkersMillisecondsMode(long plotMinX, long plotDomain, int plotWidth) {
		
		List<LevelMarker> list = new ArrayList<LevelMarker>(levelMarkers.values());
		for(LevelMarker marker : list) {
			marker.pixelXranges.clear();
			for(long[] range : marker.timestampRanges)
				marker.pixelXranges.add(new float[] {(range[0] - plotMinX) / (float) plotDomain * plotWidth,
				                                     (range[1] - plotMinX) / (float) plotDomain * plotWidth});
		}
		
		return list;
		
	}

	/**
	 * Represents a single *sample number*, and contains all of the bitfield edges that occurred at that sample number.
	 */
	public class EdgeMarker {
		
		ConnectionTelemetry connection;
		int sampleNumber;
		long timestamp;
		float pixelX;
		List<String> text;
		List<float[]> glColors;
		
		public EdgeMarker(DatasetsInterface datasets, Field.Bitfield.State state, int sampleNumber) {
			
			this.connection = state.dataset.connection;
			this.sampleNumber = sampleNumber;
			this.timestamp = datasets.getTimestamp(sampleNumber);
			pixelX = 0;
			text = new ArrayList<String>();
			glColors = new ArrayList<float[]>();
			
			if(showSampleNumbers) {
				text.add("Sample " + sampleNumber);
				glColors.add(null);
			}
			
			if(showTimestamps) {
				String[] lines = SettingsView.formatTimestampToMilliseconds(datasets.getTimestamp(sampleNumber)).split("\n");
				for(String line : lines) {
					text.add(line);
					glColors.add(null);
				}
			}
			
			text.add(state.name);
			glColors.add(state.glColor);
			
		}
		
	}
	
	/**
	 * Represents a single *bitfield*, and contains all of its levels that should be displayed on screen.
	 */
	public class LevelMarker {
		
		Field.Bitfield bitfield;           // this object contains a List of all the level markers for this bitfield
		List<String> labels;               // name of the state
		List<float[]> glColors;            // color for the state
		List<int[]> sampleNumberRanges;    // sample number ranges for the state
		List<long[]> timestampRanges;      // timestamp ranges for the state
		List<float[]> pixelXranges;        // corresponding pixelX values for those sample number ranges
		
		public LevelMarker(Field.Bitfield bitfield) {
			
			this.bitfield = bitfield;
			labels = new ArrayList<String>();
			glColors = new ArrayList<float[]>();
			sampleNumberRanges = new ArrayList<int[]>();
			timestampRanges = new ArrayList<long[]>();
			pixelXranges = new ArrayList<float[]>();
			
		}
		
	}
	
}
