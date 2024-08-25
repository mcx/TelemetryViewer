import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import com.jogamp.common.nio.Buffers;
import com.jogamp.opengl.GL2ES3;

public class ChartUtils {

	/**
	 * Determines the best y values to use for vertical divisions. The 1/2/5 pattern is used (1,2,5,10,20,50,100,200,500...)
	 * 
	 * @param plotHeight    Number of pixels for the y-axis
	 * @param minY          Y value at the bottom of the plot
	 * @param maxY          Y value at the top of the plot
	 * @return              A Map of the y values for each division, keys are Floats and values are formatted Strings
	 */
	public static Map<Float, String> getYdivisions125(float plotHeight, float minY, float maxY) {
		
		Map<Float, String> yValues = new HashMap<Float, String>();
		
		// sanity check
		if(plotHeight < 1 || minY >= maxY)
			return yValues;
		
		// calculate the best vertical division size
		float minSpacingBetweenText = 2.0f * OpenGL.smallTextHeight;
		float maxDivisionsCount = plotHeight / (OpenGL.smallTextHeight + minSpacingBetweenText) + 1.0f;
		float divisionSize = (maxY - minY) / maxDivisionsCount;
		float closestDivSize1 = (float) Math.pow(10.0, Math.ceil(Math.log10(divisionSize/1.0))) * 1.0f; // closest (10^n)*1 that is >= divisionSize, such as 1,10,100,1000
		float closestDivSize2 = (float) Math.pow(10.0, Math.ceil(Math.log10(divisionSize/2.0))) * 2.0f; // closest (10^n)*2 that is >= divisionSize, such as 2,20,200,2000
		float closestDivSize5 = (float) Math.pow(10.0, Math.ceil(Math.log10(divisionSize/5.0))) * 5.0f; // closest (10^n)*5 that is >= divisionSize, such as 5,50,500,5000
		float error1 = closestDivSize1 - divisionSize;
		float error2 = closestDivSize2 - divisionSize;
		float error5 = closestDivSize5 - divisionSize;
		if(error1 < error2 && error1 < error5)
			divisionSize = closestDivSize1;
		else if(error2 < error1 && error2 < error5)
			divisionSize = closestDivSize2;
		else
			divisionSize = closestDivSize5;
		
		// decide if the numbers should be displayed as integers, or as floats to one significant decimal place
		int precision = 0;
		String format = "";
		if(divisionSize < 0.99) {
			precision = 1;
			float size = divisionSize;
			while(size * (float) Math.pow(10, precision) < 1.0f)
				precision++;
			format = "%." + precision + "f";
		}
		
		// calculate the values for each vertical division
		float firstDivision = maxY - (maxY % divisionSize);
		float lastDivision  = minY - (minY % divisionSize);
		if(firstDivision > maxY)
			firstDivision -= divisionSize;
		if(lastDivision < minY)
			lastDivision += divisionSize;
		int divisionCount = (int) Math.round((firstDivision - lastDivision) / divisionSize) + 1;
		
		for(int i = 0; i < divisionCount; i++) {
			float number = firstDivision - (i * divisionSize);
			String text;
			if(precision == 0) {
				text = Integer.toString((int) number);
			} else {
				text = String.format(format, number);
			}
			yValues.put(number, text);
		}
		
		return yValues;
		
	}
	
	/**
	 * Determines the best Log10 y values to use for vertical divisions. Division size will be either 1e1, 1e3 or 1e9.
	 * 
	 * @param plotHeight    Number of pixels for the y-axis
	 * @param minY          Y value at the bottom of the plot
	 * @param maxY          Y value at the top of the plot
	 * @return              A Map of the y values for each division, keys are Floats and values are formatted Strings
	 */
	public static Map<Float, String> getLogYdivisions(float plotHeight, float minY, float maxY) {
		
		Map<Float, String> yValues = new HashMap<Float, String>();
		
		// sanity check
		if(plotHeight < 1 || minY >= maxY)
			return yValues;
		
		// calculate the best vertical division size
		float minSpacingBetweenText = 2.0f * OpenGL.smallTextHeight;
		float maxDivisionsCount = plotHeight / (OpenGL.smallTextHeight + minSpacingBetweenText) + 1.0f;
		float divisionSize = (maxY - minY) / maxDivisionsCount;
		float divSize1 = 1.0f; // 1W, 100mW, 10mW, 1mW, 100uW, ...
		float divSize3 = 3.0f; // 1W, 1mW, 1uW, ...
		float divSize9 = 9.0f; // 1W, 1nW, ...
		float error1 = divSize1 - divisionSize;
		float error3 = divSize3 - divisionSize;
		float error9 = divSize9 - divisionSize;
		if(error1 > 0 && error1 < error3 && error1 < error9)
			divisionSize = divSize1;
		else if(error3 > 0 && error3 < error9)
			divisionSize = divSize3;
		else if(error9 > 0)
			divisionSize = divSize9;
		else
			return new HashMap<Float, String>();
		
		// calculate the values for each vertical division
		float firstDivision = maxY - (maxY % divisionSize);
		float lastDivision  = minY - (minY % divisionSize);
		if(firstDivision > maxY)
			firstDivision -= divisionSize;
		if(lastDivision < minY)
			lastDivision += divisionSize;
		int divisionCount = (int) Math.round((firstDivision - lastDivision) / divisionSize) + 1;
//		if(divisionCount > Math.floor(maxDivisionsCount))
//			divisionCount = (int) Math.floor(maxDivisionsCount);
		
		for(int i = 0; i < divisionCount; i++) {
			float number = firstDivision - (i * divisionSize);
			String text = "1e" + Integer.toString((int) number);
			yValues.put(number, text);
		}
		
		return yValues;
		
	}
	
	/**
	 * Determines the best integer x values to use for horizontal divisions. The 1/2/5 pattern is used (1,2,5,10,20,50,100,200,500...)
	 * 
	 * @param gl           The OpenGL context.
	 * @param plotWidth    Number of pixels for the x-axis
	 * @param minX         X value at the left of the plot
	 * @param maxX         X value at the right of the plot
	 * @return             A Map of the x values for each division, keys are Integers and values are formatted Strings
	 */
	public static Map<Integer, String> getXdivisions125(GL2ES3 gl, float plotWidth, int minX, int maxX) {
		
		Map<Integer, String> xValues = new HashMap<Integer, String>();
		
		// sanity check
		if(plotWidth < 1 || minX >= maxX)
			return xValues;
		
		// calculate the best horizontal division size
		int textWidth = (int) Float.max(OpenGL.smallTextWidth(gl, Integer.toString(maxX)), OpenGL.smallTextWidth(gl, Integer.toString(minX)));
		int minSpacingBetweenText = textWidth;
		float maxDivisionsCount = plotWidth / (textWidth + minSpacingBetweenText);
		int divisionSize = (int) Math.ceil((maxX - minX) / maxDivisionsCount);
		if(divisionSize == 0) divisionSize = 1;
		int closestDivSize1 = (int) Math.pow(10.0, Math.ceil(Math.log10(divisionSize/1.0))) * 1; // closest (10^n)*1 that is >= divisionSize, such as 1,10,100,1000
		int closestDivSize2 = (int) Math.pow(10.0, Math.ceil(Math.log10(divisionSize/2.0))) * 2; // closest (10^n)*2 that is >= divisionSize, such as 2,20,200,2000
		int closestDivSize5 = (int) Math.pow(10.0, Math.ceil(Math.log10(divisionSize/5.0))) * 5; // closest (10^n)*5 that is >= divisionSize, such as 5,50,500,5000
		int error1 = closestDivSize1 - divisionSize;
		int error2 = closestDivSize2 - divisionSize;
		int error5 = closestDivSize5 - divisionSize;
		if(error1 < error2 && error1 < error5)
			divisionSize = closestDivSize1;
		else if(error2 < error1 && error2 < error5)
			divisionSize = closestDivSize2;
		else
			divisionSize= closestDivSize5;
		
		// calculate the values for each horizontal division
		int firstDivision = maxX - (maxX % divisionSize);
		int lastDivision  = minX - (minX % divisionSize);
		if(firstDivision > maxX)
			firstDivision -= divisionSize;
		if(lastDivision < minX)
			lastDivision += divisionSize;
		int divisionCount = ((firstDivision - lastDivision) / divisionSize + 1);
		
		for(int i = 0; i < divisionCount; i++) {
			int number = lastDivision + (i * divisionSize);
			String text = Integer.toString(number);
			xValues.put(number, text);
		}
		
		return xValues;
		
	}
	
	/**
	 * Determines the best floating point x values to use for horizontal divisions. The 1/2/5 pattern is used (.1,.2,.5,1,2,5,10,20,50...)
	 * 
	 * @param gl           The OpenGL context.
	 * @param plotWidth    Number of pixels for the x-axis
	 * @param minX         X value at the left of the plot
	 * @param maxX         X value at the right of the plot
	 * @return             A Map of the x values for each division, keys are Floats and values are formatted Strings
	 */
	public static Map<Float, String> getFloatXdivisions125(GL2ES3 gl, float plotWidth, float minX, float maxX) {
		
		Map<Float, String> xValues = new HashMap<Float, String>();
		
		// sanity check
		if(plotWidth < 1 || minX >= maxX)
			return xValues;
		
		for(int maxDivisionsCount = 1; maxDivisionsCount < 100; maxDivisionsCount++) {
			
			float divisionSize = (maxX - minX) / (float) maxDivisionsCount;
			float closestDivSize1 = (float) Math.pow(10.0, Math.ceil(Math.log10(divisionSize/1.0))) * 1; // closest (10^n)*1 that is >= divisionSize, such as 1,10,100,1000
			float closestDivSize2 = (float) Math.pow(10.0, Math.ceil(Math.log10(divisionSize/2.0))) * 2; // closest (10^n)*2 that is >= divisionSize, such as 2,20,200,2000
			float closestDivSize5 = (float) Math.pow(10.0, Math.ceil(Math.log10(divisionSize/5.0))) * 5; // closest (10^n)*5 that is >= divisionSize, such as 5,50,500,5000
			float error1 = closestDivSize1 - divisionSize;
			float error2 = closestDivSize2 - divisionSize;
			float error5 = closestDivSize5 - divisionSize;
			if(error1 < error2 && error1 < error5)
				divisionSize = closestDivSize1;
			else if(error2 < error1 && error2 < error5)
				divisionSize = closestDivSize2;
			else
				divisionSize= closestDivSize5;
			
			// decide if the numbers should be displayed as integers, or as floats to one significant decimal place
			int precision = 0;
			String format = "";
			if(divisionSize < 0.99) {
				precision = 1;
				float size = divisionSize;
				while(size * (float) Math.pow(10, precision) < 1.0f)
					precision++;
				format = "%." + precision + "f";
			}
			
			// calculate the values for each vertical division
			float firstDivision = maxX - (maxX % divisionSize);
			float lastDivision  = minX - (minX % divisionSize);
			firstDivision += divisionSize; // compensating for floating point error that may skip the end points
			lastDivision -= divisionSize;
			while(firstDivision > maxX)
				firstDivision -= divisionSize;
			while(lastDivision < minX)
				lastDivision += divisionSize;
			int divisionCount = (int) Math.round((firstDivision - lastDivision) / divisionSize) + 1;
			
			Map<Float, String> proposedXvalues = new HashMap<Float, String>();
			for(int i = 0; i < divisionCount; i++) {
				float number = firstDivision - (i * divisionSize);
				String text;
				if(precision == 0) {
					text = Integer.toString((int) number);
				} else {
					text = String.format(format, number);
				}
				proposedXvalues.put(number, text);
			}
			
			// calculate how much width is taken up by the text
			float width = 0;
			for(String s : proposedXvalues.values())
				width += OpenGL.smallTextWidth(gl, s);
			
			// stop and don't use this iteration if we're using more than half of the width
			if(width > plotWidth / 2.0f)
				break;
			
			xValues = proposedXvalues;
			
		}
		
		return xValues;
		
	}
	
	/**
	 * Determines the best timestamp values to use for horizontal divisions.
	 * 
	 * @param gl              The OpenGL context.
	 * @param width           Number of horizontal pixels available for displaying divisions.
	 * @param minTimestamp    Timestamp at the left edge (milliseconds since 1970-01-01).
	 * @param maxTimestamp    Timestamp at the right edge (milliseconds since 1970-01-01).
	 * @return                A Map of divisions: keys are Float pixelX locations, and values are formatted Strings.
	 */
	@SuppressWarnings("deprecation")
	public static Map<Float, String> getTimestampDivisions(GL2ES3 gl, float width, long minTimestamp, long maxTimestamp) {
		
		Map<Float, String> divisions = new HashMap<Float, String>();
		
		// sanity check
		if(width < 1 || minTimestamp >= maxTimestamp)
			return divisions;
		
		// determine how many divisions can fit on screen
		// first try with milliseconds resolution
		String leftLabel  = SettingsView.formatTimestampToMilliseconds(minTimestamp);
		String rightLabel = SettingsView.formatTimestampToMilliseconds(maxTimestamp);
		float maxLabelWidth = 0;
		if(SettingsView.isTimeFormatTwoLines()) {
			String[] leftLine = leftLabel.split("\n");
			String[] rightLine = rightLabel.split("\n");
			float leftMax  = Float.max(OpenGL.smallTextWidth(gl, leftLine[0]),  OpenGL.smallTextWidth(gl, leftLine[1]));
			float rightMax = Float.max(OpenGL.smallTextWidth(gl, rightLine[0]), OpenGL.smallTextWidth(gl, rightLine[1]));
			maxLabelWidth = Float.max(leftMax, rightMax);
		} else {
			maxLabelWidth = Float.max(OpenGL.smallTextWidth(gl, leftLabel), OpenGL.smallTextWidth(gl, rightLabel));
		}
		float padding = maxLabelWidth / 2f;
		int divisionCount = (int) (width / (maxLabelWidth + padding));
		long millisecondsOnScreen = maxTimestamp - minTimestamp;
		long millisecondsPerDivision = (long) Math.ceil((double) millisecondsOnScreen / (double) divisionCount);
		
		// if the divisions are >1000ms apart, change to seconds resolution instead
		if(millisecondsPerDivision > 1000) {
			leftLabel  = SettingsView.formatTimestampToSeconds(minTimestamp);
			rightLabel = SettingsView.formatTimestampToSeconds(maxTimestamp);
			maxLabelWidth = 0;
			if(SettingsView.isTimeFormatTwoLines()) {
				String[] leftLine = leftLabel.split("\n");
				String[] rightLine = rightLabel.split("\n");
				float leftMax  = Float.max(OpenGL.smallTextWidth(gl, leftLine[0]),  OpenGL.smallTextWidth(gl, leftLine[1]));
				float rightMax = Float.max(OpenGL.smallTextWidth(gl, rightLine[0]), OpenGL.smallTextWidth(gl, rightLine[1]));
				maxLabelWidth = Float.max(leftMax, rightMax);
			} else {
				maxLabelWidth = Float.max(OpenGL.smallTextWidth(gl, leftLabel), OpenGL.smallTextWidth(gl, rightLabel));
			}
			padding = maxLabelWidth / 2f;
			divisionCount = (int) (width / (maxLabelWidth + padding));
			millisecondsOnScreen = maxTimestamp - minTimestamp;
			millisecondsPerDivision = (long) Math.ceil((double) millisecondsOnScreen / (double) divisionCount);
			if(millisecondsPerDivision < 1000)
				millisecondsPerDivision = 1000;
		}
		
		// if the divisions are >60000ms apart, change to minutes resolution instead
		if(millisecondsPerDivision > 60000) {
			leftLabel  = SettingsView.formatTimestampToMinutes(minTimestamp);
			rightLabel = SettingsView.formatTimestampToMinutes(maxTimestamp);
			maxLabelWidth = 0;
			if(SettingsView.isTimeFormatTwoLines()) {
				String[] leftLine = leftLabel.split("\n");
				String[] rightLine = rightLabel.split("\n");
				float leftMax  = Float.max(OpenGL.smallTextWidth(gl, leftLine[0]),  OpenGL.smallTextWidth(gl, leftLine[1]));
				float rightMax = Float.max(OpenGL.smallTextWidth(gl, rightLine[0]), OpenGL.smallTextWidth(gl, rightLine[1]));
				maxLabelWidth = Float.max(leftMax, rightMax);
			} else {
				maxLabelWidth = Float.max(OpenGL.smallTextWidth(gl, leftLabel), OpenGL.smallTextWidth(gl, rightLabel));
			}
			padding = maxLabelWidth / 2f;
			divisionCount = (int) (width / (maxLabelWidth + padding));
			millisecondsOnScreen = maxTimestamp - minTimestamp;
			millisecondsPerDivision = (long) Math.ceil((double) millisecondsOnScreen / (double) divisionCount);
			if(millisecondsPerDivision < 60000)
				millisecondsPerDivision = 60000;
		}
		
		Date minDate = new Date(minTimestamp);
		long firstDivisionTimestamp = minTimestamp;
		if(millisecondsPerDivision < 1000) {
			// <1s per div, so use 1/2/5/10/20/50/100/200/250/500/1000ms per div, relative to the nearest second
			millisecondsPerDivision = (millisecondsPerDivision <= 1)   ? 1 :
			                          (millisecondsPerDivision <= 2)   ? 2 :
			                          (millisecondsPerDivision <= 5)   ? 5 :
			                          (millisecondsPerDivision <= 10)  ? 10 :
			                          (millisecondsPerDivision <= 20)  ? 20 :
			                          (millisecondsPerDivision <= 50)  ? 50 :
			                          (millisecondsPerDivision <= 100) ? 100 :
			                          (millisecondsPerDivision <= 200) ? 200 :
			                          (millisecondsPerDivision <= 250) ? 250 :
			                          (millisecondsPerDivision <= 500) ? 500 :
			                                                             1000;
			firstDivisionTimestamp = new Date(minDate.getYear(), minDate.getMonth(), minDate.getDate(), minDate.getHours(), minDate.getMinutes(), minDate.getSeconds()).getTime() - 1000;
		} else if(millisecondsPerDivision < 60000) {
			// <1m per div, so use 1/2/5/10/15/20/30/60s per div, relative to the nearest minute
			millisecondsPerDivision = (millisecondsPerDivision <= 1000)  ? 1000 :
			                          (millisecondsPerDivision <= 2000)  ? 2000 :
			                          (millisecondsPerDivision <= 5000)  ? 5000 :
			                          (millisecondsPerDivision <= 10000) ? 10000 :
			                          (millisecondsPerDivision <= 15000) ? 15000 :
			                          (millisecondsPerDivision <= 20000) ? 20000 :
			                          (millisecondsPerDivision <= 30000) ? 30000 :
			                                                               60000;
			firstDivisionTimestamp = new Date(minDate.getYear(), minDate.getMonth(), minDate.getDate(), minDate.getHours(), minDate.getMinutes(), 0).getTime() - 60000;
		} else if(millisecondsPerDivision < 3600000) {
			// <1h per div, so use 1/2/5/10/15/20/30/60m per div, relative to the nearest hour
			millisecondsPerDivision = (millisecondsPerDivision <= 60000)   ? 60000 :
			                          (millisecondsPerDivision <= 120000)  ? 120000 :
			                          (millisecondsPerDivision <= 300000)  ? 300000 :
			                          (millisecondsPerDivision <= 600000)  ? 600000 :
			                          (millisecondsPerDivision <= 900000)  ? 900000 :
			                          (millisecondsPerDivision <= 1200000) ? 1200000 :
			                          (millisecondsPerDivision <= 1800000) ? 1800000 :
			                                                                 3600000;
			firstDivisionTimestamp = new Date(minDate.getYear(), minDate.getMonth(), minDate.getDate(), minDate.getHours(), 0, 0).getTime() - 3600000;
		} else if(millisecondsPerDivision < 86400000) {
			// <1d per div, so use 1/2/3/4/6/8/12/24 hours per div, relative to the nearest day
			millisecondsPerDivision = (millisecondsPerDivision <= 3600000)  ? 3600000 :
			                          (millisecondsPerDivision <= 7200000)  ? 7200000 :
			                          (millisecondsPerDivision <= 10800000) ? 10800000 :
			                          (millisecondsPerDivision <= 14400000) ? 14400000 :
			                          (millisecondsPerDivision <= 21600000) ? 21600000 :
			                          (millisecondsPerDivision <= 28800000) ? 28800000 :
			                          (millisecondsPerDivision <= 43200000) ? 43200000 :
			                                                                  86400000;
			firstDivisionTimestamp = new Date(minDate.getYear(), minDate.getMonth(), minDate.getDate(), 0, 0, 0).getTime() - 86400000;
		} else {
			// >=1d per div, so use an integer number of days, relative to the nearest day
			if(millisecondsPerDivision != 86400000)
				millisecondsPerDivision += 86400000 - (millisecondsPerDivision % 86400000);
			firstDivisionTimestamp = new Date(minDate.getYear(), minDate.getMonth(), 1, 0, 0, 0).getTime() - 86400000;
		}
		while(firstDivisionTimestamp < minTimestamp)
			firstDivisionTimestamp += millisecondsPerDivision;
		
		// populate the Map
		for(int divisionN = 0; divisionN < divisionCount; divisionN++) {
			long timestampN = firstDivisionTimestamp + (divisionN * millisecondsPerDivision);
			float pixelX = (float) (timestampN - minTimestamp) / (float) millisecondsOnScreen * width;
			String label = millisecondsPerDivision < 1000  ? SettingsView.formatTimestampToMilliseconds(timestampN) :
			               millisecondsPerDivision < 60000 ? SettingsView.formatTimestampToSeconds(timestampN) :
			                                                 SettingsView.formatTimestampToMinutes(timestampN);
			if(pixelX <= width)
				divisions.put(pixelX, label);
			else
				break;
		}
		
		return divisions;
		
	}
	
	/**
	 * Formats a double as a string, limiting the total number of digits to a specific length, but never truncating the integer part.
	 * 
	 * For example, with a digitCount of 4:
	 * 1.234567 -> "1.234"
	 * 12.34567 -> "12.34"
	 * 123456.7 -> "123456"
	 * -1.23456 -> "-1.234"
	 * 
	 * @param number        The double to format.
	 * @param digitCount    How many digits to clip to.
	 * @return              The double formatted as a String.
	 */
	public static String formattedNumber(double number, int digitCount) {
		
		String text = String.format("%.9f", number);
		int pointLocation = text.indexOf('.');
		int stringLength = text.charAt(0) == '-' ? digitCount + 2 : digitCount + 1;
		if(text.charAt(stringLength - 1) == '.')
			stringLength--;
		return text.substring(0, pointLocation < stringLength ? stringLength : pointLocation);
		
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
	public static FloatBuffer getShapeFromAsciiStl(InputStream fileStream) {
		
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
			
			return buffer;
			
		} catch (Exception e) {
			
			e.printStackTrace();
			return null;
			
		}
		
	}
	
	/**
	 * Inserts a space every n characters, to format hex or binary text in a more user-friendly way.
	 * 
	 * @param text      Input String with no spaces.
	 * @param stride    How many characters before each space.
	 * @return          The padded String. (Example: "0123ABCD" with a stride of 2 becomes "01 23 AB CD")
	 */
	public static String padStringWithSpaces(String text, int stride) {
		
		String string = "";
		int count = 0;
		for(int i = 0; i < text.length(); i++) {
			string += text.charAt(i);
			count++;
			if(count % stride == 0)
				string += " ";
		}
		return string;
		
	}
	
}
