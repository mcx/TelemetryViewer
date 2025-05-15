import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Insets;
import java.text.DecimalFormat;

import javax.swing.Box;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;

import com.jogamp.opengl.GL2ES3;

import net.miginfocom.swing.MigLayout;


/**
 * All GUI-related colors, element spacing, and fonts are managed by this class.
 * Colors are specified in float[4]{r,g,b,a} format.
 * Element spacing is specified in true pixels (pre-multiplied by the display scaling factor.)
 */
public class Theme {

	// general swing and other settings
	public static float  osDpiScalingFactor = 1;
	public static int    columnWidth;
	public static Color  jpanelColor                      = new JPanel().getBackground();
	public static int    padding                          = Integer.parseInt(System.getProperty("java.version").split("\\.")[0]) >= 9 ? 5 : (int) (5 * Settings.GUI.getChartScalingFactor());
	public static String removeSymbol                     = "\uD83D\uDDD9";
	public static Color  defaultDatasetColor              = Color.RED;
	public static long   defaultChartDurationMilliseconds = 10_000;
	public static Cursor defaultCursor                    = new Cursor(Cursor.DEFAULT_CURSOR);
	public static Cursor clickableCursor                  = new Cursor(Cursor.HAND_CURSOR);
	public static Cursor upDownCursor                     = new Cursor(Cursor.N_RESIZE_CURSOR);
	public static Cursor leftRigthCursor                  = new Cursor(Cursor.E_RESIZE_CURSOR);
	public static Border narrowButtonBorder;
	static {
		JToggleButton temp = new JToggleButton("_");
		Insets insets = temp.getBorder().getBorderInsets(temp);
		narrowButtonBorder = new EmptyBorder(insets.top, Integer.max(insets.top, insets.bottom), insets.bottom, Integer.max(insets.top, insets.bottom));
	}
	
	// general openGL
	public static float  lineWidth = 1.0f;
	public static float  pointWidth = 3.0f;
	public static long   animationMilliseconds = 250;
	public static double animationMillisecondsDouble = 250.0;
	
	// charts region
	public static float[] tileColor               = new float[] {0.8f, 0.8f, 0.8f, 1.0f};
	public static float[] tileShadowColor         = new float[] {0.7f, 0.7f, 0.7f, 1.0f};
	public static float[] tileSelectedColor       = new float[] {0.5f, 0.5f, 0.5f, 1.0f};
	public static float   tilePadding             = 5.0f;
	public static float   tileShadowOffset        = tilePadding / 2;
	public static float[] neutralColor            = new float[] {jpanelColor.getRed() / 255.0f, jpanelColor.getGreen() / 255.0f, jpanelColor.getBlue() / 255.0f, 1.0f};
	public static float[] transparentNeutralColor = new float[] {neutralColor[0], neutralColor[1], neutralColor[2], 0.7f};
	
	// plot region
	public static float[] plotOutlineColor        = new float[] {0.0f, 0.0f, 0.0f, 1.0f};
	public static float[] plotBackgroundColor     = neutralColor;
	public static float[] divisionLinesColor      = new float[] {0.7f, 0.7f, 0.7f, 1.0f};
	public static float[] divisionLinesFadedColor = new float[] {0.7f, 0.7f, 0.7f, 0.0f};
	
	// tooltips and markers in the plot region
	public static float[] tooltipBackgroundColor  = new float[] {1, 1, 1, 1};
	public static float[] tooltipBorderColor      = new float[] {0.0f, 0.0f, 0.0f, 1.0f};
	public static float[] markerBorderColor       = new float[] {0.6f, 0.6f, 0.6f, 1.0f};
	public static float[] tooltipVerticalBarColor = new float[] {0.0f, 0.0f, 0.0f, 1.0f};
	public static float   tooltipTextPadding      = 5.0f;
	
	// tick marks surrounding the plot region
	public static float[] tickLinesColor   = new float[] {0.0f, 0.0f, 0.0f, 1.0f};
	public static float   tickLength       = 6.0f;
	public static float   tickTextPadding  = 3.0f;
	
	// legend
	public static float[] legendBackgroundColor = tileShadowColor;
	public static float   legendTextPadding     = 5.0f;
	public static float   legendNamesPadding    = 25.0f;
	
	// fonts
	public static Font font[] = new Font[] { new Font("Geneva", Font.PLAIN, 12),
	                                         new Font("Geneva", Font.BOLD,  14),
	                                         new Font("Geneva", Font.BOLD,  18) };
	
	// number formatting
	final private static int maxDecimalPlaces = 3;
	final private static String formatString = "%." + maxDecimalPlaces + "f";

	/**
	 * @return    Example: 123, "Volts" -> "123V"
	 */
	public static String getInteger(int number, String unit) {
		
		return Integer.toString(number) + abbreviatedExponent(0, unit);
		
	}
	
	/**
	 * @return    Examples: 1.23456, "Watts", false -> "1.235W"
	 *                      5.00007, "Volts", true  -> "5.0V"
	 */
	public static String getFloat(float number, String unit, boolean trimTrailingZeros) {
		
		String text = String.format(formatString, number);
		if(trimTrailingZeros) {
			int trim = 0;
			for(int i = text.length() - 1; i >= 0; i--)
				if(text.charAt(i) == '0' && Character.isDigit(text.charAt(i-1)))
					trim++;
				else
					break;
			text = text.substring(0, text.length() - trim);
		}
		return text + abbreviatedExponent(0, unit);
		
	}
	
	/**
	 * @return    Examples: 1.23456, 1, "Watts" -> "1.2W"
	 *                      5.00007, 3, "Volts" -> "5.000V"
	 */
	public static String getFloat(float number, int decimalPlaces, String unit) {
		
		String format = "%." + decimalPlaces + "f";
		String text = String.format(format, number);
		return text + abbreviatedExponent(0, unit);
		
	}
	
	/**
	 * @return    Examples: 1.23456, "Watts", false -> "1.235W"
	 *                      5.00007, "Volts", false -> "5V"
	 */
	public static String getFloatOrInteger(float number, String unit, boolean trimTrailingZeros) {
		
		// check if an integer is close enough
		int numberAsInt = (int) Math.round(number);
		double error = Math.abs(number - numberAsInt);
		double maxAllowedError = Math.pow(10, -1 * maxDecimalPlaces - 1); // example: 3 decimal places -> max allowed error is 0.0001
		if(error <= maxAllowedError)
			return getInteger(numberAsInt, unit);
		else
			return getFloat(number, unit, trimTrailingZeros);
		
	}
	
	/**
	 * @return    Examples: 0.0, "Watts" -> "1W"
	 *                      1.0, "Watts" -> "10W"
	 *                      2.0, "Watts" -> "100W"
	 *                      2.1, "Watts" -> "125.893W"
	 */
	public static String getLog10float(float number, String unit) {
		
		int exponent = (int) Math.floor(number);
		double significand = Math.pow(10, number - exponent);
		return getScientificNotation(significand, exponent, unit);
		
	}
	
	/**
	 * @return    Example: 100, 1000 -> "100 (10.0%)"
	 */
	public static String getAmountAndPercentage(int amount, int total) {
		
		float percentage = (float) amount / (float) (total == 0 ? 1 : total) * 100f;
		String percentageText = getFloat(percentage, "%", true);
		return Integer.toString(amount) + " (" + percentageText + ")";
		
	}
	
	/**
	 * @return    Examples: 1.0, -3, "Watts" -> "1mW"
	 *                      1.0, -6, "Volts" -> "1uV"
	 *                      1.0, -7, "Amps"  -> "100nA"
	 */
	public static String getScientificNotation(double significand, int exponent, String unit) {
		
		// adjust so the exponent is a multiple of 3
		if(exponent % 3 != 0) {
			if(exponent > 0) { // example: 1e4 -> 10e3
				significand *= Math.pow(10, exponent % 3);
				exponent    -= (exponent % 3);
			} else { // example: 1e-4 -> 100e-6
				significand *= Math.pow(10, 3 + (exponent % 3));
				exponent    -= 3 + (exponent % 3);
			}
		}
		
		// clamp exponent to +/- 30 (the SI prefixes)
		if(exponent > 30) {
			significand *= Math.pow(10, exponent - 30);
			exponent = 30;
		} else if(exponent < -30) {
			significand /= Math.pow(10, -1*exponent - 30);
			exponent = -30;
		}
		
		String number = (significand == Math.floor(significand)) ? Integer.toString((int) significand)            : // example: 1nW
		                (significand < 1)                        ? new DecimalFormat("0.0E0").format(significand) : // example: 1.0E-6qW (useful when numbers are too extreme to have SI prefixes)
		                                                           String.format(formatString, significand);        // example: 1.234mW (typical use case)
		
		return number + abbreviatedExponent(exponent, unit);
		
	};
	
	private static String abbreviatedExponent(int exponent, String unit) {
		
		String abbreviatedUnit = switch(unit.toLowerCase()) {
			case "volt", "volts", "voltage"          -> "V";
			case "amp", "amps", "ampere", "amperes"  -> "A";
			case "watt", "watts", "power"            -> "W";
			case "hertz", "frequency"                -> "Hz";
			case "percentage", "percent"             -> "%";
			case "seconds", "time"                   -> "s";
			case "sample count"                      -> "";
			default                                  -> unit;    // don't know how to abbreviate it
		};
		
		String gap = (abbreviatedUnit.length() > 3) ? " "      : // regular space if not abbreviated
		             (abbreviatedUnit.length() > 0) ? "\u200A" : // Unicode "hair space" if abbreviated
		                                              "";        // no space if no unit
		
		String siPrefix = switch(exponent) {
			case  30 -> "Q";      // quetta
			case  27 -> "R";      // ronna
			case  24 -> "Y";      // yotta
			case  21 -> "Z";      // zetta
			case  18 -> "E";      // exa
			case  15 -> "P";      // peta
			case  12 -> "T";      // tera
			case   9 -> "G";      // giga
			case   6 -> "M";      // mega
			case   3 -> "k";      // kilo
			case   0 -> "";     
			case  -3 -> "m";      // milli
			case  -6 -> "\u00B5"; // micro
			case  -9 -> "n";      // nano
			case -12 -> "p";      // pico
			case -15 -> "f";      // femto
			case -18 -> "a";      // atto
			case -21 -> "z";      // zepto
			case -24 -> "y";      // yocto
			case -27 -> "r";      // ronto
			case -30 -> "q";      // quecto
			default  -> "?";      // should never get here
		};
		
		return gap + siPrefix + abbreviatedUnit;
		
	}
	
	/**
	 * This method must be called when the OpenGL context is initialized,
	 * and any time the display scaling factor changes.
	 * 
	 * @param gl                      The OpenGL context.
	 * @param displayScalingFactor    The display scaling factor.
	 */
	public static void initialize(GL2ES3 gl, float displayScalingFactor) {
			
		lineWidth  = 1.0f * displayScalingFactor;
		pointWidth = 3.0f * displayScalingFactor;
		
		tilePadding      = 5.0f * displayScalingFactor;
		tileShadowOffset = tilePadding / 2;
		
		tooltipTextPadding = 5.0f * displayScalingFactor;
		
		tickLength      = 6.0f * displayScalingFactor;
		tickTextPadding = 3.0f * displayScalingFactor;
		
		legendTextPadding  = 5.0f * displayScalingFactor;
		legendNamesPadding = 25.0f * displayScalingFactor;
		
		font[0] = new Font("Geneva", Font.PLAIN, (int) (12.0 * displayScalingFactor));
		font[1] = new Font("Geneva", Font.BOLD,  (int) (14.0 * displayScalingFactor));
		font[2] = new Font("Geneva", Font.BOLD,  (int) (18.0 * displayScalingFactor));
		OpenGL.updateFontTextures(gl);
		
	}
	
	/**
	 * Creates a panel intended for containing Widgets.
	 * It has a TitledBorder, and uses a MigLayout.
	 * The MigLayout is configured for 1 column and defaults to stretching each widget to fill that column.
	 * 
	 * @param label    Text to show in the TitledBorder.
	 * @return         The panel.
	 */
	public static PanelBuilder newWidgetsPanel(String label) {
		
		return new PanelBuilder(label);
		
	}
	
	public static class PanelBuilder {
		
		private final JPanel panel;
		
		public PanelBuilder(String label) {
			panel = new JPanel();
			panel.setLayout(new MigLayout("hidemode 3, wrap 1, insets 0 " + Theme.padding / 2 + " " + Theme.padding + " " + Theme.padding + ", gap " + Theme.padding, "[grow,fill]"));
			panel.setBorder(new TitledBorder(label));
		}
		
		public PanelBuilder with(Widget widget) {
			widget.appendTo(panel, "");
			return this;
		}
		
		public PanelBuilder with(Widget widget, String constraints) {
			widget.appendTo(panel, constraints);
			return this;
		}
		
		public PanelBuilder with(Component component) {
			panel.add(component);
			return this;
		}
		
		public PanelBuilder with(Component component, String constraints) {
			panel.add(component, constraints);
			return this;
		}
		
		public PanelBuilder withGap(int pixelCount) {
			panel.add(Box.createVerticalStrut(pixelCount));
			return this;
		}
		
		public JPanel getPanel() {
			return panel;
		}
		
	}

}
