import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ChartsController {
	
	private static List<PositionedChart> charts = Collections.synchronizedList(new ArrayList<PositionedChart>());
	
	private static float dpiScalingFactorOS   = 1; // will be updated dynamically
	private static float dpiScalingFactorUser = 1; // may be updated by the user
	
	/**
	 * @return    The display scaling factor. This takes into account the true DPI scaling requested by the OS, plus the user's modification (if any.)
	 */
	public static float getDisplayScalingFactor() {
		
		return dpiScalingFactorUser * dpiScalingFactorOS;
		
	}
	
	/**
	 * @return    The display scaling factor for GUI widgets.
	 */
	public static float getDisplayScalingFactorForGUI() {
		
		return dpiScalingFactorOS;
		
	}
	
	/**
	 * @return    The display scaling factor requested by the user.
	 */
	public static float getDisplayScalingFactorUser() {
		
		return dpiScalingFactorUser;
		
	}
	
	/**
	 * @param newFactor    The new display scaling factor specified by the user.
	 */
	public static void setDisplayScalingFactorUser(float newFactor) {
		
		if(newFactor < 1) newFactor = 1;
		if(newFactor > 10) newFactor = 10;
		dpiScalingFactorUser = newFactor;
		
	}
	
	/**
	 * @param newFactor    The new display scaling factor specified by the OS if using Java 9+.
	 */
	public static void setDisplayScalingFactorOS(float newFactor) {
		
		if(newFactor < 1) newFactor = 1;
		if(newFactor > 10) newFactor = 10;
		dpiScalingFactorOS = newFactor;
		
	}
	
	/**
	 * @return    An array of Strings, one for each possible chart type.
	 */
	public static String[] getChartTypes() {
		
		return new String[] {
			"Time Domain",
			"Frequency Domain",
			"Histogram",
			"Statistics",
			"Dial",
			"Quaternion",
			"Camera",
			"Timeline"
		};
		
	}
	
	/**
	 * Creates a PositionedChart and adds it to the charts list.
	 * 
	 * @param chartType    One of the Strings from ChartsController.getChartTypes()
	 * @return             That chart, or null if chartType is invalid.
	 */
	public static PositionedChart createAndAddChart(String chartType) {
		
		PositionedChart chart = switch(chartType) { case "Time Domain"      -> new OpenGLTimeDomainChart();
		                                            case "Frequency Domain" -> new OpenGLFrequencyDomainChart();
		                                            case "Histogram"        -> new OpenGLHistogramChart();
		                                            case "Statistics"       -> new OpenGLStatisticsChart();
		                                            case "Dial"             -> new OpenGLDialChart();
		                                            case "Quaternion"       -> new OpenGLQuaternionChart();
		                                            case "Camera"           -> new OpenGLCameraChart();
		                                            case "Timeline"         -> new OpenGLTimelineChart();
		                                            default                 -> null; };
		
		if(chart != null)
			charts.add(chart);
		
		return chart;
		
	}
	
	/**
	 * Reorders the list of charts so the specified chart will be rendered after all other charts.
	 * 
	 * @param chart    The chart to render last.
	 */
	public static void drawChartLast(PositionedChart chart) {
		
		if(charts.size() < 2)
			return;
		
		Collections.swap(charts, charts.indexOf(chart), charts.size() - 1);
		
	}
	
	/**
	 * Removes a specific chart.
	 * 
	 * @param chart    Chart to remove.
	 */
	public static void removeChart(PositionedChart chart) {

		ConfigureView.instance.closeIfUsedFor(chart);
		
		chart.dispose();
		charts.remove(chart);
		OpenGLChartsView.instance.updateTileOccupancy(null);
		
	}
	
	/**
	 * Removes all charts.
	 */
	public static void removeAllCharts() {
		
		// many a temporary copy of the list because you can't remove from a list that you are iterating over
		List<PositionedChart> list = new ArrayList<PositionedChart>(charts);
		
		for(PositionedChart chart : list)
			removeChart(chart);
		
	}
	
	/**
	 * @return    All charts.
	 */
	public static List<PositionedChart> getCharts() {
		
		return charts;
		
	}
	
	/**
	 * Checks if a region is available in the ChartsRegion.
	 * 
	 * @param x1    The x-coordinate of a bounding-box corner in the OpenGLChartsRegion grid.
	 * @param y1    The y-coordinate of a bounding-box corner in the OpenGLChartsRegion grid.
	 * @param x2    The x-coordinate of the opposite bounding-box corner in the OpenGLChartsRegion grid.
	 * @param y2    The y-coordinate of the opposite bounding-box corner in the OpenGLChartsRegion grid.
	 * @return      True if available, false if not.
	 */
	public static boolean gridRegionAvailable(int x1, int y1, int x2, int y2) {
		
		int topLeftX     = x1 < x2 ? x1 : x2;
		int topLeftY     = y1 < y2 ? y1 : y2;
		int bottomRightX = x2 > x1 ? x2 : x1;
		int bottomRightY = y2 > y1 ? y2 : y1;

		for(PositionedChart chart : charts)
			if(chart.regionOccupied(topLeftX, topLeftY, bottomRightX, bottomRightY))
				return false;
		
		return true;
		
	}
	
}
