import java.awt.Container;
import java.awt.Dimension;
import java.awt.event.ActionListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import javax.swing.text.NavigationFilter;
import javax.swing.text.PlainDocument;
import javax.swing.text.Position;

/**
 * An improved textfield:
 * 
 *     - The textfield can contain data in the form of text, hex bytes, binary bytes, an integer, or a float.
 *     - The textfield can be disabled with a message that replaces the normal on-screen data.
 *           These disabled messages are only shown on screen (they are never sent to the event handlers.)
 *           When the textfield is enabled, the normal on-screen data will be restored. This will not trigger the event handlers either.
 *     - By default, the preferredSize and minimumSize will be computed dynamically, and be slightly larger than necessary.
 *           setFixedWidth() can be used to instead have a preferredSize and minimumSize that does not change.
 *     - An optional prefix can be specified, to show a label before the data.
 *     - An optional suffix can be specified, to show a unit after the data.
 *     - The data entered by the user will be tested and rejected if not appropriate.
 *           Testing is done live (on every key press, etc.) and at the end (when focus is lost, or enter is pressed.)
 *           Hex and binary modes also ensure spaces exist between bytes, and ensure the cursor moves as expected.
 *     - The cursor will be managed so the user can not reach the prefix or suffix.
 *     - If the user clicks-and-releases (within 200ms) on the textfield, the data region will be automatically selected.
 *     - If the user clicks-and-drags on the textfield, part or all of the data region can be selected.
 *     - Event handlers are supported for onChange() onEnter() and onIncompleteChange().
 *           onChange() is guaranteed to only be called when the data changes.
 *           onChange() will be invokeLater()'d after it is defined to ensure user code is kept in sync with the data in the textfield.
 *           Calling set() will immediately trigger the onChange handler. This will cancel the invokeLater()'d call to onChange() to ensure the handler only receives the correct value.
 */
@SuppressWarnings("serial")
public class WidgetTextfield<T> implements Widget {
	
	public enum Mode {
		TEXT    { @Override public String toString() { return "Text";    } },
		HEX     { @Override public String toString() { return "Hex";     } },
		BINARY  { @Override public String toString() { return "Binary";  } },
		INTEGER { @Override public String toString() { return "Integer"; } },
		FLOAT   { @Override public String toString() { return "Float";   } };
		};
	private Mode mode;
	
	private JTextField textfield;
	
	private String prefix = "";
	private String suffix = "";
	private int prefixLength = prefix.length();
	private int suffixLength = suffix.length();
	private volatile String userText; // contents of the textfield *without* the prefix/suffix
	
	// for Integer/Float modes
	private boolean checkForSentinel;
	private final T sentinelNumber;
	private final String sentinelText;
	private final T minimum;
	private final T maximum;
	
	private String importExportLabel = "";
	private int fixedWidthColumns = -1;
	
	private boolean changeHandlerCalled = false;
	private BiPredicate<T,T> changeHandler = null; // (newValue, oldValue)
	private Consumer<String> incompleteChangeHandler = null;
	private boolean maskIncompleteChangeHandler = false;
	private ActionListener enterHandler = null;
	
	private boolean mouseButtonDown = false;
	private long    mouseButtonDownTimestamp = 0;
	private boolean maybeSelectAll = false;
	private boolean deleteKeyDown = false;
	
	public static WidgetTextfield<String>  ofText(String initialText)                                                          { return new WidgetTextfield<String> (Mode.TEXT,    initialText,  "",  "",  "",            null);           }
	public static WidgetTextfield<String>  ofHex(String initialText)                                                           { return new WidgetTextfield<String> (Mode.HEX,     initialText,  "",  "",  "",            null);           }
	public static WidgetTextfield<String>  ofBinary(String initialText)                                                        { return new WidgetTextfield<String> (Mode.BINARY,  initialText,  "",  "",  "",            null);           }
	public static WidgetTextfield<Integer> ofInt(int min, int max, int initialValue)                                           { return new WidgetTextfield<Integer>(Mode.INTEGER, initialValue, min, max, 0,             null);           }
	public static WidgetTextfield<Integer> ofInt(int min, int max, int initialValue, int sentinelValue, String sentinelString) { return new WidgetTextfield<Integer>(Mode.INTEGER, initialValue, min, max, sentinelValue, sentinelString); }
	public static WidgetTextfield<Float>   ofFloat(float min, float max, float initialValue)                                   { return new WidgetTextfield<Float>  (Mode.FLOAT,   initialValue, min, max, 0f,            null);           }
	
	private WidgetTextfield(Mode type, T value, T min, T max, T sentinelValue, String sentinelString) {
		
		// sanity checks
		boolean enableSentinel = (sentinelString != null && !sentinelString.isEmpty());
		if(mode == Mode.INTEGER) {
			if((int) min > (int) max) {
				T temp = min;
				min = max;
				max = temp;
			}
			if(((int) value < (int) min && enableSentinel && !value.equals(sentinelValue)) || ((int) value < (int) min && !enableSentinel))
				value = min;
			else if(((int) value > (int) max && enableSentinel && !value.equals(sentinelValue)) || ((int) value > (int) max && !enableSentinel))
				value = max;
		} else if(mode == Mode.FLOAT) {
			if((float) min > (float) max) {
				T temp = min;
				min = max;
				max = temp;
			}
			if(((float) value < (float) min && enableSentinel && !value.equals(sentinelValue)) || ((float) value < (float) min && !enableSentinel))
				value = min;
			else if(((float) value > (float) max && enableSentinel && !value.equals(sentinelValue)) || ((float) value > (float) max && !enableSentinel))
				value = max;
		}
		
		// initialize
		textfield = new JTextField() {
			@Override public void setText(String t) {
				super.setText(t);
				validateNewText();
				if(fixedWidthColumns < 0) { // resize the textfield to fit the new text
					revalidate();
					repaint();
					Container c = getParent();
					if(c != null) {
						c.revalidate();
						c.repaint();
					}
				}
			}
			@Override public Dimension getMinimumSize()   { return getPreferredSize(); }
			@Override public Dimension getPreferredSize() {
				if(Theme.columnWidth == 0)
					Theme.columnWidth = getColumnWidth();
				Dimension size = super.getPreferredSize();
				size.width = (fixedWidthColumns >= 0) ? Theme.columnWidth * fixedWidthColumns : size.width + (Theme.columnWidth * 2);
				return size;
			}
		};
		
		mode = type;
		userText = value.toString();
		minimum = min;
		maximum = max;
		checkForSentinel = enableSentinel;
		sentinelNumber = sentinelValue;
		sentinelText = sentinelString;
		
		if(enableSentinel && value.equals(sentinelNumber))
			textfield.setText(prefix + sentinelString);
		else
			textfield.setText(prefix + value + suffix);
		
		// intercept text changes (via keyboard, copy-and-paste, drag-and-drop, etc.) to ensure only valid data can be entered
		((PlainDocument) textfield.getDocument()).setDocumentFilter( switch(mode) {
			case TEXT, HEX, BINARY -> filterForTextHexBin();
			case INTEGER           -> filterForInt();
			case FLOAT             -> filterForFloat();
		});
		
		// track when the delete key is down, so the DocumentFilter can move the cursor correctly when "deleting" a space between bytes in HEX or BINARY mode
		textfield.addKeyListener(new KeyAdapter() {
			@Override public void keyPressed(KeyEvent e)  { if(e.getKeyCode() == KeyEvent.VK_DELETE) deleteKeyDown = true;  }
			@Override public void keyReleased(KeyEvent e) { if(e.getKeyCode() == KeyEvent.VK_DELETE) deleteKeyDown = false; }
		});
		
		// when the user clicks/tabs onto the textfield, select the userText unless the user is click-and-dragging
		textfield.addFocusListener(new FocusAdapter() {
			@Override public void focusGained(FocusEvent e) {
				if(!mouseButtonDown)
					textfield.selectAll();
				else
					maybeSelectAll = true;
			}
		});
		textfield.addMouseListener(new MouseAdapter() {
			@Override public void mousePressed(MouseEvent e)  {
				mouseButtonDown = true;
				mouseButtonDownTimestamp = System.currentTimeMillis();
			}
			@Override public void mouseReleased(MouseEvent e) {
				mouseButtonDown = false;
				if(maybeSelectAll) {
					long pressedTime = System.currentTimeMillis() - mouseButtonDownTimestamp;
					if(pressedTime < 200 && textfield.getSelectedText() == null)
						textfield.selectAll();
					maybeSelectAll = false;
				}
			}
		});
		
		// intercept caret/selection events to ensure only the userText region can be selected or navigated
		textfield.setNavigationFilter(new NavigationFilter() {
			@Override public void setDot(NavigationFilter.FilterBypass fb, int dot, Position.Bias bias) {
				String text = textfield.getText();
				int minAllowedPosition = prefixLength;
				int maxAllowedPosition = text.endsWith(suffix) ? text.length() - suffix.length() :
				                                                 text.length();
				dot = (dot < minAllowedPosition) ? minAllowedPosition :
				      (dot > maxAllowedPosition) ? maxAllowedPosition :
				                                   dot;
				if(checkForSentinel && userText.equals(sentinelNumber.toString()) && text.endsWith(sentinelText)) {
					fb.setDot(minAllowedPosition, bias);
					fb.moveDot(maxAllowedPosition, bias);
				} else {
					fb.setDot(dot, bias);
				}
			}
			@Override public void moveDot(NavigationFilter.FilterBypass fb, int dot, Position.Bias bias) {
				String text = textfield.getText();
				if(checkForSentinel && userText.equals(sentinelNumber.toString()) && text.endsWith(sentinelText))
					return;
				int minAllowedPosition = prefixLength;
				int maxAllowedPosition = text.endsWith(suffix) ? text.length() - suffixLength :
				                                                 text.length();
				dot = (dot < minAllowedPosition) ? minAllowedPosition :
				      (dot > maxAllowedPosition) ? maxAllowedPosition :
				                                   dot;
				if(checkForSentinel && userText.equals(sentinelNumber.toString()) && textfield.getText().endsWith(sentinelText)) {
					fb.setDot(minAllowedPosition, bias);
					fb.moveDot(maxAllowedPosition, bias);
				} else {
					fb.moveDot(dot, bias);
				}
			}
		});
		
		// when the user clicks/tabs away from the textfield, validate the userText
		textfield.addFocusListener(new FocusAdapter() {
			@Override public void focusLost(FocusEvent e) {
				if(textfield.isEnabled()) // don't validate if disabled with a message
					validateNewText();
			}
		});
		
		// when the user presses Enter, validate the userText and select it
		textfield.addActionListener(event -> {
			validateNewText();
			textfield.selectAll();
			if(enterHandler != null)
				enterHandler.actionPerformed(event);
		});
		
	}
	
	/**
	 * @return    A DocumentFilter that only allows text in the following forms:
	 * 
	 *            prefix/<anything>/suffix          (if mode == TEXT)
	 *            prefix/<0-9,A-F,spaces>/suffix    (if mode == HEX)
	 *            prefix/<0,1,spaces>/suffix        (if mode == BINARY)
	 */
	private DocumentFilter filterForTextHexBin() {
		
		return new DocumentFilter() {
			
			@Override public void insertString(DocumentFilter.FilterBypass fb, int offset, String string, AttributeSet attr) throws BadLocationException {
				
				String newText = new StringBuilder(textfield.getText()).insert(offset, string).toString();
				
				// in text mode, always accept the insertion
				if(mode == Mode.TEXT) {
					fb.insertString(offset, string, attr);
					notifyIncompleteHandler();
					return;
				}
				
				// if the new text contains >1 character, strip out any spaces
				if(string.length() > 1)
					string = string.replaceAll("[ ]", "");
				
				// in hex mode, convert to upper case
				if(mode == Mode.HEX)
					string = string.toUpperCase();
				
				// reject insertion of prohibited characters
				if(!string.matches((mode == Mode.HEX) ? "[0-9A-F ]*" : "[0-1 ]*")) {
					Notifications.printDebugMessageAndBeep(this.getClass().getName() + " rejected text: \"" + newText + "\"");
					notifyIncompleteHandler();
					return;
				}
				
				int digitsPerByte = (mode == Mode.HEX) ? 2 : 8;
				
				// if inserting a space, only accept if cursor is at the end of a byte
				// if a space already exists, don't insert a new one, but instead move the cursor to the right
				if(string.equals(" ")) {
					int cursorLocation = textfield.getCaretPosition();
					boolean cursorAtEndOfByte = (cursorLocation - prefixLength - digitsPerByte) % (digitsPerByte + 1) == 0;
					boolean spaceAlreadyExists = textfield.getText().length() - suffixLength > cursorLocation && textfield.getText().charAt(cursorLocation) == ' ';
					if(cursorAtEndOfByte && !spaceAlreadyExists) {
						fb.insertString(offset, string, attr);
						notifyIncompleteHandler();
						return;
					} else if(cursorAtEndOfByte && spaceAlreadyExists) {
						textfield.setCaretPosition(textfield.getCaretPosition() + 1);
						notifyIncompleteHandler();
						return;
					} else {
						Notifications.printDebugMessageAndBeep(this.getClass().getName() + " rejected text: \"" + newText + "\"");
						notifyIncompleteHandler();
						return;
					}
				}
				
				// inserting one or more digits, so perform the insertion, and ensure spaces are between each byte
				// then move the cursor to *after* the last inserted digit
				String digits = newText.substring(prefixLength, newText.length() - suffixLength).replaceAll("[ ]", "");
				StringBuilder digitsSpacedOut = new StringBuilder();
				for(int i = 0; i < digits.length(); i++) {
					boolean lastDigitInByte = ((i + 1) % (digitsPerByte)) == 0;
					digitsSpacedOut.append(digits.charAt(i) + ((lastDigitInByte && i != digits.length() - 1) ? " " : ""));
				}
				fb.replace(0, textfield.getText().length(), prefix + digitsSpacedOut.toString() + suffix, attr);
				
				int digitsBeforeCursor = (offset - prefixLength) - ((offset - prefixLength) / (digitsPerByte + 1)) + string.length();
				int newCursorLocation = prefixLength + digitsBeforeCursor + ((digitsBeforeCursor - 1) / digitsPerByte);
				textfield.setCaretPosition(newCursorLocation);
				notifyIncompleteHandler();
				return;
				
			}
			
			@Override public void remove(DocumentFilter.FilterBypass fb, int offset, int length) throws BadLocationException {

				String newText = new StringBuilder(textfield.getText()).delete(offset, offset + length).toString();
				
				// reject if modifying the prefix or suffix
				if(offset < prefixLength || offset + length > textfield.getText().length() - suffixLength) {
					Notifications.printDebugMessageAndBeep(this.getClass().getName() + " rejected text: \"" + newText + "\"");
					notifyIncompleteHandler();
					return;
				}
				
				// in text mode, always accept the removal
				if(mode == Mode.TEXT) {
					fb.remove(offset, length);
					notifyIncompleteHandler();
					return;
				}
				
				// in hex or binary mode, always accept the removal
				// ensure spaces are between each byte, then move the cursor as needed
				String digits = newText.substring(prefixLength, newText.length() - suffixLength).replaceAll("[ ]", "");
				StringBuilder digitsSpacedOut = new StringBuilder();
				int digitsPerByte = (mode == Mode.HEX) ? 2 : 8;
				for(int i = 0; i < digits.length(); i++) {
					boolean lastDigitInByte = ((i + 1) % (digitsPerByte)) == 0;
					digitsSpacedOut.append(digits.charAt(i) + ((lastDigitInByte && i != digits.length() - 1) ? " " : ""));
				}
				fb.replace(0, textfield.getText().length(), prefix + digitsSpacedOut.toString() + suffix, null);
				
				if(offset > textfield.getText().length()) // a trailing space was removed
					textfield.setCaretPosition(textfield.getText().length());
				else if(deleteKeyDown && length == 1 && textfield.getText().charAt(offset) == ' ' && offset != textfield.getText().length() - 1) // user attempted to delete a space between bytes, so lets just move the cursor to the right
					textfield.setCaretPosition(offset + 1);
				else
					textfield.setCaretPosition(offset);
				notifyIncompleteHandler();
				return;
				
			}
			
			@Override public void replace(DocumentFilter.FilterBypass fb, int offset, int length, String string, AttributeSet attr) throws BadLocationException {
				
				String newText = new StringBuilder(textfield.getText()).replace(offset, offset + length, string).toString();
				
				// setText() will try to replace() the entire text
				// accept that if the prefix and suffix are preserved
				if(offset == 0 && length == textfield.getText().length() && string.startsWith(prefix) && string.endsWith(suffix) && (!prefix.isEmpty() || !suffix.isEmpty())) {
					fb.replace(offset, length, string, attr);
					notifyIncompleteHandler();
					return;
				}
				
				// reject if modifying the prefix or suffix
				if(offset < prefixLength || offset + length > textfield.getText().length() - suffixLength) {
					Notifications.printDebugMessageAndBeep(this.getClass().getName() + " rejected text: \"" + newText + "\"");
					notifyIncompleteHandler();
					return;
				}
				
				// otherwise we are only replacing part of the userText region
				// in that case, convert this replace() into a possible remove() and a possible insert()
				// important: disable notifyIncompleteHandler() during this remove() because it would report an incomplete state change
				if(length > 0) {
					maskIncompleteChangeHandler = true;
					remove(fb, offset, length);
					maskIncompleteChangeHandler = false;
				}
				if(string.length() > 0)
					insertString(fb, offset, string, attr);
				
			}
			
		};
		
	}
	
	/**
	 * @return    A DocumentFilter that only allows text in the following forms:
	 * 
	 *            <any form>                                   (only if disabled, so you can disableWithNumber()/disableWithMessage())
	 *            
	 *            prefix/number/suffix                         (typical use case)
	 *            prefix/suffix                                (user backspace/delete'd the number, and is about to enter a new value)
	 *            prefix/-/suffix                              (user started to enter a negative number, only allowed if minimum < 0 or sentinelNumber < 0)
	 *            prefix/beginningOrAllOfSentinelText/suffix   (user is typing out the sentinelText, case-insensitive)
	 *            prefix/sentinelText                          (sentinel value is active)
	 *            
	 *            prefix                                       (user backspace/delete'd the sentinelNumber)
	 *            prefix/-                                     (user typed - over the sentinelNumber, only allowed if minimum < 0 or sentinelNumber < 0)
	 *            prefix/number                                (user pasted or dropped a number over the sentinelText)
	 *            prefix/beginningOrAllOfSentinelText          (user is typing out the sentinelText, case-insensitive)
	 *            
	 *            when any of those last four forms occur, the suffix is immediately appended since a number will be/is being entered
	 */
	private DocumentFilter filterForInt() {
		
		return new DocumentFilter() {
			
			@Override public void insertString(DocumentFilter.FilterBypass fb, int offset, String string, AttributeSet attr) throws BadLocationException {
				String newText = new StringBuilder(textfield.getText()).insert(offset, string).toString();
				if(isAcceptable(newText)) {
					fb.insertString(offset, string, attr);
					if(textfield.isEnabled() && !newText.endsWith(suffix) && checkForSentinel && !newText.endsWith(sentinelText)) // exited sentinel mode
						textfield.setText(textfield.getText() + suffix);
				} else {
					Notifications.printDebugMessageAndBeep(this.getClass().getName() + " rejected text: \"" + newText + "\"");
				}
			}
			
			@Override public void remove(DocumentFilter.FilterBypass fb, int offset, int length) throws BadLocationException {
				String newText = new StringBuilder(textfield.getText()).delete(offset, offset + length).toString();
				if(isAcceptable(newText)) {
					fb.remove(offset, length);
					if(textfield.isEnabled() && !newText.endsWith(suffix) && checkForSentinel && !newText.endsWith(sentinelText)) // exited sentinel mode
						textfield.setText(textfield.getText() + suffix);
				} else {
					Notifications.printDebugMessageAndBeep(this.getClass().getName() + " rejected text: \"" + newText + "\"");
				}
			}
			
			@Override public void replace(DocumentFilter.FilterBypass fb, int offset, int length, String string, AttributeSet attr) throws BadLocationException {
				String newText = new StringBuilder(textfield.getText()).replace(offset, offset + length, string).toString();
				if(isAcceptable(newText)) {
					fb.replace(offset, length, string, attr);
					if(textfield.isEnabled() && !newText.endsWith(suffix) && checkForSentinel && !newText.endsWith(sentinelText)) // exited sentinel mode
						textfield.setText(textfield.getText() + suffix);
				} else {
					Notifications.printDebugMessageAndBeep(this.getClass().getName() + " rejected text: \"" + newText + "\"");
				}
			}
			
			private boolean isAcceptable(String text) {
				
				if(!textfield.isEnabled())
					return true; // allow any text
				
				boolean hasPrefix = text.startsWith(prefix);
				boolean hasSuffix = text.endsWith(suffix);
				
				if(hasPrefix && hasSuffix && text.length() >= prefix.length() + suffix.length()) {
					
					String centerRegion = text.substring(prefix.length(), text.length() - suffix.length());
					
					try {
						Integer.parseInt(centerRegion);
						return true; // prefix/number/suffix
					} catch(NumberFormatException e) {}
					
					if(centerRegion.length() == 0)
						return true; // prefix/suffix
					
					if(centerRegion.length() == 1 && centerRegion.charAt(0) == '-' && ((int) minimum < 0 || (checkForSentinel && (int) sentinelNumber < 0)))
						return true; // prefix/-/suffix
					
					if(checkForSentinel && sentinelText.toLowerCase().startsWith(centerRegion.toLowerCase()))
						return true; // prefix/beginningOrAllOfSentinelText/suffix
					
				} else if(hasPrefix && !hasSuffix) {
					
					String remainingText = text.substring(prefix.length());
					
					if(checkForSentinel && sentinelText.toLowerCase().equals(remainingText.toLowerCase()))
						return true; // prefix/sentinelText
					
					if(remainingText.length() == 0)
						return true; // prefix
					
					if(remainingText.length() == 1 && remainingText.charAt(0) == '-' && ((int) minimum < 0 || (checkForSentinel && (int) sentinelNumber < 0)))
						return true; // prefix/-
					
					try {
						Integer.parseInt(remainingText);
						return true; // prefix/number
					} catch(NumberFormatException e) {}
					
					if(checkForSentinel && sentinelText.toLowerCase().startsWith(remainingText.toLowerCase()))
						return true; // prefix/beginningOrAllOfSentinelText
					
				}

				return false; // doesn't match any of the acceptable forms
				
			}
			
		};
		
	}
	
	/**
	 * @return    A DocumentFilter that only allows text in the following forms:
	 * 
	 *            <any form>                                   (only if disabled, so you can disableWithNumber()/disableWithMessage())
	 *            
	 *            prefix/number/suffix                         (typical use case)
	 *            prefix/./suffix                              (user about to provide a number <1 and >0)
	 *            prefix/-./suffix                             (user about to provide a number <0 and >-1)
	 *            prefix/<integer>./suffix                     (user about to provide the decimal part)
	 *            prefix/<number>e/suffix                      (exponential notation, user about to provide an exponent)
	 *            prefix/<number>e-/suffix                     (exponential notation, user about to provide a negative exponent)
	 *            prefix/<integer>.e<integer>/suffix           (exponential notation, user modifying the mantissa's decimal part)
	 *            prefix/.<integer>e<integer>/suffix           (exponential notation, user modifying the mantissa's integer part)
	 *            prefix/e<integer>/suffix                     (exponential notation, user modifying the mantissa)
	 *            prefix/suffix                                (user backspace/delete'd the number, and is about to enter a new value)
	 *            prefix/-/suffix                              (user started to enter a negative number, only allowed if minimum < 0 or sentinelNumber < 0)
	 *            prefix/beginningOrAllOfSentinelText/suffix   (user is typing out the sentinelText, case-insensitive)
	 *            prefix/sentinelText                          (sentinel value is active)
	 *            
	 *            prefix                              (user backspace/delete'd the sentinelNumber)
	 *            prefix/.                            (user typed . over the sentinelNumber)
	 *            prefix/-                            (user typed - over the sentinelNumber, only allowed if minimum < 0 or sentinelNumber < 0)
	 *            prefix/number                       (user pasted or dropped a number over the sentinelText)
	 *            prefix/beginningOrAllOfSentinelText (user is typing out the sentinelText, case-insensitive)
	 *            
	 *            when any of those last five forms occur, the suffix is immediately appended since a number will be/is being entered
	 */
	private DocumentFilter filterForFloat() {
		
		return new DocumentFilter() {
			
			@Override public void insertString(DocumentFilter.FilterBypass fb, int offset, String string, AttributeSet attr) throws BadLocationException {
				String newText = new StringBuilder(textfield.getText()).insert(offset, string).toString();
				if(isAcceptable(newText)) {
					fb.insertString(offset, string, attr);
					if(textfield.isEnabled() && !newText.endsWith(suffix) && checkForSentinel && !newText.endsWith(sentinelText))
						textfield.setText(textfield.getText() + suffix);
				} else {
					Notifications.printDebugMessageAndBeep(this.getClass().getName() + " rejected text: \"" + newText + "\"");
				}
			}
			
			@Override public void remove(DocumentFilter.FilterBypass fb, int offset, int length) throws BadLocationException {
				String newText = new StringBuilder(textfield.getText()).delete(offset, offset + length).toString();
				if(isAcceptable(newText)) {
					fb.remove(offset, length);
					if(textfield.isEnabled() && !newText.endsWith(suffix) && checkForSentinel && !newText.endsWith(sentinelText))
						textfield.setText(textfield.getText() + suffix);
				} else {
					Notifications.printDebugMessageAndBeep(this.getClass().getName() + " rejected text: \"" + newText + "\"");
				}
			}
			
			@Override public void replace(DocumentFilter.FilterBypass fb, int offset, int length, String string, AttributeSet attr) throws BadLocationException {
				String newText = new StringBuilder(textfield.getText()).replace(offset, offset + length, string).toString();
				if(isAcceptable(newText)) {
					fb.replace(offset, length, string, attr);
					if(textfield.isEnabled() && !newText.endsWith(suffix) && checkForSentinel && !newText.endsWith(sentinelText))
						textfield.setText(textfield.getText() + suffix);
				} else {
					Notifications.printDebugMessageAndBeep(this.getClass().getName() + " rejected text: \"" + newText + "\"");
				}
			}
			
			private boolean isAcceptable(String text) {
				
				if(!textfield.isEnabled())
					return true; // allow any text
				
				boolean hasPrefix = text.startsWith(prefix);
				boolean hasSuffix = text.endsWith(suffix);
				
				if(hasPrefix && hasSuffix && text.length() >= prefixLength + suffixLength) {
					
					String centerRegion = text.substring(prefixLength, text.length() - suffixLength).toLowerCase();
					
					try {
						Float.parseFloat(centerRegion);
						return true; // prefix/number/suffix
					} catch(NumberFormatException e) {}
					
					if(centerRegion.equals("."))
						return true; // prefix/./suffix

					if(centerRegion.equals("-."))
						return true; // prefix/-./suffix
					
					if(centerRegion.endsWith(".")) {
						try {
							Integer.parseInt(centerRegion.substring(0, centerRegion.length() - 1));
							return true; // prefix/<integer>./suffix
						} catch(NumberFormatException e) {}
					}
					
					if(centerRegion.endsWith("e")) {
						try {
							Float.parseFloat(centerRegion.substring(0, centerRegion.length() - 1));
							return true; // prefix/<number>e/suffix
						} catch(NumberFormatException e) {}
					}
					
					if(centerRegion.endsWith("e-")) {
						try {
							Float.parseFloat(centerRegion.substring(0, centerRegion.length() - 2));
							return true; // prefix/<number>e-/suffix
						} catch(NumberFormatException e) {}
					}
					
					String[] tokens = centerRegion.split("e");
					if(tokens.length == 2) {
						try {
							Integer.parseInt(tokens[1]);
							// center regions ends with "e<integer>"
							if(tokens[0].length() == 0)
								return true; // prefix/e<integer>/suffix
							if(tokens[0].endsWith(".")) {
								try {
									Integer.parseInt(tokens[0].substring(0, tokens[0].length() - 1));
									return true; // prefix/<integer>.e<integer>/suffix
								} catch(NumberFormatException e) {}
							}
							if(tokens[0].startsWith(".")) {
								try {
									Integer.parseInt(tokens[0].substring(1));
									return true; // prefix/.<integer>e<integer>/suffix
								} catch(NumberFormatException e) {}
							}
						} catch(NumberFormatException e) {}
					}
					
					if(centerRegion.length() == 0)
						return true; // prefix/suffix
					
					if(centerRegion.length() == 1 && centerRegion.charAt(0) == '-' && ((float) minimum < 0 || (checkForSentinel && (float) sentinelNumber < 0)))
						return true; // prefix/-/suffix
					
					if(checkForSentinel && sentinelText.toLowerCase().startsWith(centerRegion.toLowerCase()))
						return true; // prefix/beginningOrAllOfSentinelText/suffix
					
				} else if (hasPrefix && !hasSuffix) {
					
					String remainingText = text.substring(prefixLength);
					
					if(checkForSentinel && sentinelText.toLowerCase().equals(remainingText.toLowerCase()))
						return true; // prefix/sentinelText
					
					if(remainingText.length() == 0)
						return true; // prefix
					
					if(remainingText.equals("."))
						return true; // prefix/.
					
					if(remainingText.length() == 1 && remainingText.charAt(0) == '-' && ((float) minimum < 0 || (checkForSentinel && (float) sentinelNumber < 0)))
						return true; // prefix/-
					
					try {
						Integer.parseInt(remainingText);
						return true; // prefix/number
					} catch(NumberFormatException e) {}
					
					if(checkForSentinel && sentinelText.toLowerCase().startsWith(remainingText.toLowerCase()))
						return true; // prefix/beginningOrAllOfSentinelText
					
				}

				return false; // doesn't match any of the acceptable forms
				
			}
			
		};
		
	}
	
	/**
	 * @param newPrefix    Prefix to show before the userText. Disable with null or "".
	 */
	public WidgetTextfield<T> setPrefix(String newPrefix) {
		
		prefix = (newPrefix == null || newPrefix == "") ? "" : newPrefix + ": ";
		prefixLength = prefix.length();
		if(checkForSentinel && userText.equals(sentinelNumber.toString()))
			textfield.setText(prefix + sentinelText);
		else
			textfield.setText(prefix + userText + suffix);
		return this;
		
	}
	
	/**
	 * @param newSuffix    Suffix to show after the userText. Disable with null or "".
	 *                     If the textfield is enabled, the textfield will be updated and the event handler will be called.
	 */
	public WidgetTextfield<T> setSuffix(String newSuffix) {
		
		boolean updateTextfield = textfield.isEnabled() || textfield.getText().equals(prefix + userText + suffix) || (checkForSentinel && textfield.getText().equals(prefix + sentinelText));
		
		suffix = (newSuffix == null || newSuffix == "") ? "" : " " + newSuffix;
		suffixLength = suffix.length();
		
		if(updateTextfield)
			textfield.setText(prefix + userText + suffix);
		
		return this;
		
	}
	
	/**
	 * @param label    Label to use when importing/exporting a settings file. Can be null.
	 */
	public WidgetTextfield<T> setExportLabel(String label) {
		
		importExportLabel = (label == null) ? "" : label;
		return this;
		
	}
	
	/**
	 * @param handler    Will be called when the textfield changes. Can be null.
	 *                   The event handler will receive (newValue, oldValue) and should return true if newValue is acceptable.
	 *                   If get() is called by code inside the handler, it will return newValue.
	 *                   If set() is called by code inside the handler, it will win *if* the handler returns true. If the handler returns false, the oldValue will go into effect.
	 */
	@SuppressWarnings("unchecked")
	public WidgetTextfield<T> onChange(BiPredicate<T,T> handler) {
		
		changeHandler = handler;
		changeHandlerCalled = false;
		
		// call the handler, but later, so the calling code can finish constructing things before the handler is triggered
		SwingUtilities.invokeLater(() -> {
			if(changeHandler != null && changeHandlerCalled == false) {
				changeHandlerCalled = true;
				T newValue = switch(mode) {
					case TEXT, HEX, BINARY -> (T) userText;
					case INTEGER           -> (T) (Integer) Integer.parseInt(userText);
					case FLOAT             -> (T) (Float) Float.parseFloat(userText);
				};
				changeHandler.test(newValue, newValue);
			}
		});
		
		return this;
		
	}
	
	/**
	 * @param handler    Will be called when the user presses Enter. Can be null.
	 */
	public WidgetTextfield<T> onEnter(ActionListener handler) {
		
		enterHandler = handler;
		return this;
		
	}
	
	/**
	 * @param handler    Will be called when a key is released or text is drag-and-dropped in. Can be null;
	 */
	public WidgetTextfield<T> onIncompleteChange(Consumer<String> handler) {
		
		incompleteChangeHandler = handler;
		return this;
		
	}
	
	private void notifyIncompleteHandler() {
		
		if(maskIncompleteChangeHandler)
			return;
		
		if(incompleteChangeHandler == null)
			return;
		String text = textfield.getText();
		incompleteChangeHandler.accept(text.substring(prefixLength, text.length() - suffixLength));
		
	}
	
	/**
	 * Configures the textfield to have a preferred size NOT based on it's contents, but instead based on a pre-defined number of columns.
	 * 
	 * @param columnCount    How wide this textfield should be, in columns. Use a negative number to switch back to an automatic width.
	 */
	public WidgetTextfield<T> setFixedWidth(int columnCount) {
		
		fixedWidthColumns = columnCount;
		return this;
		
	}
	
	/**
	 * Requests focus for the textfield, and selects the text.
	 */
	public void requestFocus() {
		
		SwingUtilities.invokeLater(() -> {
			textfield.requestFocus();
			textfield.selectAll();
		});
		
	}
	
	private String disabledMessage = null;
	private boolean forcedDisabled = false;
	
	/**
	 * Disables the textfield and displays a message without validating it.
	 * The event handler is NOT notified.
	 * This allows the textfield to be used for displaying information when disabled.
	 * 
	 * @param message    Message to display.
	 */
	public WidgetTextfield<T> disableWithMessage(String message) {
		
		setEnabled(false);
		disabledMessage = prefix + message;
		textfield.setText(disabledMessage);
		return this;
		
	}
	
	/**
	 * Disables the textfield and displays a number without validating it.
	 * The event handler is NOT notified.
	 * This allows the textfield to be used for displaying information when disabled.
	 * 
	 * @param displayedNumber    Number to display.
	 */
	public WidgetTextfield<T> disableWithNumber(int displayedNumber) {
		
		setEnabled(false);
		disabledMessage = prefix + displayedNumber + suffix;
		textfield.setText(disabledMessage);
		return this;
		
	}

	@Override public WidgetTextfield<T> setVisible(boolean isVisible) {

		textfield.setVisible(isVisible);
		return this;
		
	}
	
	/**
	 * @return    True if the user has entered any acceptable text.
	 */
	public boolean hasText() {
		
		if(disabledMessage != null)
			return !userText.isEmpty(); // test userText, because currently disabled-with-message
		else
			return textfield.getText().length() - prefixLength - suffixLength > 0; // test getText(), because the user may be typing now, so userText may be stale
		
	}
	
	/**
	 * @return    The underlying value (without prefix/suffix.)
	 */
	@SuppressWarnings("unchecked")
	public T get() {
		
		return switch(mode) {
		       case TEXT, HEX, BINARY -> (T) userText;
		       case INTEGER           -> (T) (Integer) Integer.parseInt(userText);
		       case FLOAT             -> (T) (Float) Float.parseFloat(userText);
		       };
		
	}
	
	public boolean is(T value) {
		
		return userText.equals(value.toString());
		
	}
	
	/**
	 * Sets the userText. It will be validated and the event handler will be notified if the text has changed.
	 * 
	 * @param newText    New text to use.
	 * @return           True if the new text was accepted, false if rejected.
	 */
	public WidgetTextfield<T> set(T newText) {
		
		if(userText.equals(newText))
			return this;
		textfield.setText(prefix + newText + suffix);
		return this;
		
	}
	
	/**
	 * @param appendCR    If true, append a carriage return.
	 * @param appendLF    If true, append a line feed.
	 * @return            The value, in string form, with optional \r and/or \n appended.
	 */
	public String getAsText(boolean appendCR, boolean appendLF) {
		
		return userText + (appendCR ? "\\r" : "") + (appendLF ? "\\n" : "");
		
	}
	
	/**
	 * @param appendCR    If true, append a carriage return.
	 * @param appendLF    If true, append a line feed.
	 * @return            The value, as a String of hex bytes, with optional CR and/or LF appended (also as a String of hex bytes.)
	 */
	public String getAsHexText(boolean appendCR, boolean appendLF) {
		
		byte[] bytes = getAsBytes(appendCR, appendLF);
		return IntStream.range(0, bytes.length)
		                .mapToObj(i -> bytes[i])
		                .map(b -> String.format("%02X", b))
		                .collect(Collectors.joining(" "));
		
	}
	
	/**
	 * @return    The underlying text (without prefix/suffix) interpreted and converted into a byte[].
	 *            This should only be called when mode is TEXT, HEX or BINARY.
	 */
	public byte[] getAsBytes(boolean appendCR, boolean appendLF) {
		
		byte[] bytes;
		
		if(mode == Mode.TEXT) {
			bytes = userText.getBytes();
		} else if(userText.isEmpty() || mode == Mode.INTEGER || mode == Mode.FLOAT) {
			bytes = new byte[0];
		} else {
			String[] tokens = userText.split(" ");
			bytes = new byte[tokens.length];
			for(int i = 0; i < tokens.length; i++)
				bytes[i] = (byte) (Integer.parseInt(tokens[i], (mode == Mode.HEX) ? 16 : 2) & 0xFF);
		}
		
		if(appendCR) {
			bytes = Arrays.copyOf(bytes, bytes.length + 1);
			bytes[bytes.length - 1] = '\r';
		}
		if(appendLF) {
			bytes = Arrays.copyOf(bytes, bytes.length + 1);
			bytes[bytes.length - 1] = '\n';
		}
		
		return bytes;
		
	}
	
	/**
	 * Sets the userText based on the contents of a byte[].
	 * It will be validated and the event handler will be notified if the text has changed.
	 * This should only be called when mode is TEXT, HEX or BINARY.
	 * 
	 * @param bytes    New text to use.
	 */
	public boolean setFromBytes(byte[] bytes) {
		
		String newText = switch(mode) { case TEXT    -> new String(bytes).replace("\r", "\\r").replace("\n", "\\n");
		                                case HEX     -> IntStream.range(0, bytes.length)
		                                                         .mapToObj(i -> bytes[i])
		                                                         .map(b -> String.format("%02X", b))
		                                                         .collect(Collectors.joining(" "));
		                                case BINARY  -> IntStream.range(0, bytes.length)
		                                                         .mapToObj(i -> bytes[i])
		                                                         .map(b -> String.format("%8s", Integer.toBinaryString(Byte.toUnsignedInt(b))).replace(' ', '0'))
		                                                         .collect(Collectors.joining(" "));
		                                case INTEGER -> "";   // not supported
		                                case FLOAT   -> "";}; // not supported
		textfield.setText(prefix + newText + suffix);
		return userText.equals(newText);
		
	}
	
	@Override public WidgetTextfield<T> setEnabled(boolean enabled) {
		
		if(forcedDisabled)
			return this;
		
		if(enabled && disabledMessage != null) {
			disabledMessage = null;
			textfield.setText(prefix + userText + suffix);
		}
		textfield.setEnabled(enabled);
		return this;
		
	}
	
	public WidgetTextfield<T> forceDisabled(boolean isDisabled) {
		
		if(isDisabled)
			setEnabled(false);
		forcedDisabled = isDisabled;
		if(!isDisabled)
			setEnabled(true);
		return this;
		
	}
	
	public WidgetTextfield<T> setToolTipText(String text) {
		
		textfield.setToolTipText(text);
		return this;
		
	}
	
	/**
	 * Changes the data type that is used for the userText. Existing text will be reformatted to the new type.
	 * 
	 * @param newMode    New data type.
	 */
	public void setDataType(Mode newMode) {
		
		byte[] data = getAsBytes(false, false);
		mode = newMode;
		setFromBytes(data);
		
	}
	
	/**
	 * Checks the value of the textfield and accepts or rejects the change.
	 * If the change is accepted, the userText is updated, and the event handler is notified.
	 * If the change is rejected, the textfield is updated.
	 */
	@SuppressWarnings("unchecked")
	private void validateNewText() {
		
		boolean sentinelMode = checkForSentinel && userText.equals(sentinelNumber.toString());
		String newText = textfield.getText();
		String oldText = sentinelMode ? prefix + sentinelText : 
		                                prefix + userText + suffix;
				
		// ignore if nothing changed or disabled with a message
		if(newText.equals(oldText) || disabledMessage != null)
			return;
		
		// parse the userText region
		String text = newText.substring(prefixLength, newText.length() - suffixLength);
		
		// reject if the userText region is not as expected
		switch(mode) {
		case TEXT -> {
			// always accepted
		}
		case HEX -> {
			text = text.toUpperCase().trim();
			boolean rejected = false;
			if(!text.matches("[0-9A-F ]*"))
				rejected = true; // contains prohibited characters
			for(int i = 2; i < text.length(); i += 3)
				if(text.charAt(i) != ' ')
					rejected = true; // missing space between bytes
			if(rejected) {
				textfield.setText(oldText);
				Notifications.printDebugMessageAndBeep(this.getClass().getName() + " rejected text: \"" + newText + "\"");
				return;
			}
			if(!text.isEmpty() && (text.length() - 2) % 3 != 0)
				text += "0"; // last byte incomplete, add a least-significant-nybble
		}
		case BINARY -> {
			text = text.trim();
			boolean rejected = false;
			if(!text.matches("[0-1 ]*"))
				rejected = true;
			for(int i = 8; i < text.length(); i += 9)
				if(text.charAt(i) != ' ')
					rejected = true; // missing space between bytes
			if(rejected){
				textfield.setText(oldText);
				Notifications.printDebugMessageAndBeep(this.getClass().getName() + " rejected text: \"" + newText + "\"");
				return;
			}
			if(!text.isEmpty())
				while((text.length() - 8) % 9 != 0)
					text += "0"; // last byte incomplete, add least-significant-bits
		}
		case INTEGER -> {
			int newNumber = 0;
			try {
				if(checkForSentinel && text.toLowerCase().equals(sentinelText.toLowerCase())) {
					newNumber = (int) sentinelNumber;
				} else if(checkForSentinel && text.equals(sentinelNumber.toString())) {
					newNumber = (int) sentinelNumber;
				} else {
					newNumber = Integer.parseInt(text);
					if((newNumber < (int) minimum && checkForSentinel && newNumber != (int) sentinelNumber) || (newNumber < (int) minimum && !checkForSentinel))
						throw new Exception();
					else if((newNumber > (int) maximum && checkForSentinel && newNumber != (int) sentinelNumber) || (newNumber > (int) maximum && !checkForSentinel))
						throw new Exception();
				}
			} catch(Exception e) {
				textfield.setText(oldText);
				Notifications.printDebugMessageAndBeep(this.getClass().getName() + " rejected text: \"" + newText + "\"");
				return;
			}
		}
		case FLOAT -> {
			float newNumber = 0;
			try {
				if(checkForSentinel && text.toLowerCase().equals(sentinelText.toLowerCase())) {
					newNumber = (float) sentinelNumber;
				} else if(checkForSentinel && text.equals(sentinelNumber.toString())) {
					newNumber = (float) sentinelNumber;
				} else {
					newNumber = Float.parseFloat(text);
					if((newNumber < (float) minimum && checkForSentinel && newNumber != (float) sentinelNumber) || (newNumber < (float) minimum && !checkForSentinel))
						throw new Exception();
					else if((newNumber > (float) maximum && checkForSentinel && newNumber != (float) sentinelNumber) || (newNumber > (float) maximum && !checkForSentinel))
						throw new Exception();
					text = Float.toString(newNumber); // needed so "-.5" becomes "-0.5" etc. this also ensures export/import works consistently.
				}
			} catch(Exception e) {
				textfield.setText(oldText);
				Notifications.printDebugMessageAndBeep(this.getClass().getName() + " rejected text: \"" + newText + "\"");
				return;
			}
		}
		}
		
		String oldUserText = userText;
		userText = text;
		
		// check if the change is acceptable
		if(changeHandler != null) {
			changeHandlerCalled = true;
			T newValue = switch(mode) {
				case TEXT, HEX, BINARY -> (T) userText;
				case INTEGER           -> (T) (Integer) Integer.parseInt(userText);
				case FLOAT             -> (T) (Float) Float.parseFloat(userText);
			};
			T oldValue = switch(mode) {
				case TEXT, HEX, BINARY -> (T) oldUserText;
				case INTEGER           -> (T) (Integer) Integer.parseInt(oldUserText);
				case FLOAT             -> (T) (Float) Float.parseFloat(oldUserText);
			};
			if(!changeHandler.test(newValue, oldValue)) {
				userText = oldUserText;
				Notifications.printDebugMessageAndBeep(this.getClass().getName() + " onChange() handler rejected text: \"" + newText + "\"");
			}
		}
		
		// update the GUI
		if(checkForSentinel && userText.equals(sentinelNumber.toString()))
			textfield.setText(prefix + sentinelText);
		else
			textfield.setText(prefix + userText + suffix);
		
	}

	@Override public void appendTo(JPanel panel, String constraints) {
		
		panel.add(textfield, constraints);
		
	}

	@SuppressWarnings("unchecked")
	@Override public void importFrom(Connections.QueueOfLines lines) throws AssertionError {

		String text = lines.parseString(importExportLabel + " = %s");
		set(switch(mode) {
		    case TEXT, HEX, BINARY -> (T) text;
		    case INTEGER           -> (T) (Integer) Integer.parseInt(text);
		    case FLOAT             -> (T) (Float) Float.parseFloat(text);
		    });
		
		boolean accepted = userText.equals(text);
		if(!accepted)
			throw new AssertionError("Invalid " + importExportLabel);
		
	}

	@Override public void exportTo(PrintWriter file) {
		
		file.println("\t" + importExportLabel + " = " + userText);
		
	}

}
