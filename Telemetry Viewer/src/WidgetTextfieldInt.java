import java.awt.Container;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

import javax.swing.JTextField;
import javax.swing.Timer;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import javax.swing.text.NavigationFilter;
import javax.swing.text.PlainDocument;
import javax.swing.text.Position;

@SuppressWarnings("serial")
public class WidgetTextfieldInt extends JTextField {
	
	private final String prefix;
	private final String suffix;
	private final int prefixLength;
	private final int suffixLength;
	
	private final int minimum;
	private final int maximum;
	private int number;
	private final Consumer<Integer> eventHandler;
	
	private boolean checkForSentinel;
	private int sentinelNumber;
	private String sentinelText;
	
	private boolean mouseButtonDown = false;
	
	/**
	 * Creates a special JTextField that is used to enter a number.
	 * A label and unit can be shown to provide more context for the user.
	 * A special "sentinel" number can be defined, which causes custom text to be displayed when that number is used.
	 * For convenience, the number region will be automatically selected when the textfield gains focus, and when the user presses Enter.
	 * 
	 * The textfield can also be disabled and instead used to *show* a number or message.
	 * When disabled, showing a number or message will not cause the underlying number to change,
	 * and will not trigger the event handler. When enabled, the old underlying number will be shown again.
	 * 
	 * For example: a textfield might be used to show a sample rate, with a number of "1000" displayed as "Sample Rate: 1000 Hz".
	 * A sample rate of "0" could be entered to indicate that the user wants the rate automatically calculated.
	 * 
	 * @param label             Optional text to show before the number. Can be null or "".
	 * @param unit              Optional text to show after the number. Can be null or "".
	 * @param min               Minimum allowed number, inclusive.
	 * @param max               Maximum allowed number, inclusive.
	 * @param value             Default number.
	 * @param enableSentinel    True if a sentinel value should be supported.
	 * @param sentinelValue     Number that has a special meaning (ignored if enableSentinel == false)
	 * @param sentinelString    Corresponding text to display when the sentinel number is used. (ignored if enableSentinel == false)
	 * @param handler           Event handler to be notified when the number changes. Can be null.
	 */
	public WidgetTextfieldInt(String label, String unit, int min, int max, int value, boolean enableSentinel, int sentinelValue, String sentinelString, Consumer<Integer> handler) {
		
		super();
		
		// sanity checks
		if(min > max) {
			int temp = min;
			min = max;
			max = temp;
		}
		if((value < min && enableSentinel && value != sentinelValue) || (value < min && !enableSentinel))
			value = min;
		else if((value > max && enableSentinel && value != sentinelValue) || (value > max && !enableSentinel))
			value = max;
		
		// if provided, add a colon and space after the label, and a space before the unit
		label = (label == null || label == "") ? "" : label + ": ";
		unit = (unit == null || unit == "") ? "" : " " + unit;
		
		// initialize
		prefix = label;
		suffix = unit;
		prefixLength = prefix.length();
		suffixLength = suffix.length();
		minimum = min;
		maximum = max;
		number = value;
		checkForSentinel = enableSentinel;
		sentinelNumber = sentinelValue;
		sentinelText = sentinelString;
		eventHandler = handler;
		
		if(enableSentinel && number == sentinelNumber)
			setText(prefix + sentinelString);
		else
			setText(prefix + number + suffix);
		
		// notify the event handler of the GUI's current state
		if(eventHandler != null)
			eventHandler.accept(number);
		
		// a DocumentFilter is used to intercept text changes (via keyboard, copy-and-paste, drag-and-drop, etc.)
		// this ensures that only the number region can be edited, and only numbers or the sentinel can be entered
		// the textfield is only allowed to contain text in the following forms:
		//
		//     <any form>                                   (only if disabled, so you can disableWithNumber()/disableWithMessage())
		//
		//     prefix/number/suffix                         (typical use case)
		//     prefix/suffix                                (user backspace/delete'd the number, and is about to enter a new value)
		//     prefix/-/suffix                              (user started to enter a negative number, only allowed if minimum < 0 or sentinelNumber < 0)
		//     prefix/beginningOrAllOfSentinelText/suffix   (user is typing out the sentinelText, case-insensitive)
		//     prefix/sentinelText                          (sentinel value is active)
		//
		//     prefix                              (user backspace/delete'd the sentinelNumber)
		//     prefix/-                            (user typed - over the sentinelNumber, only allowed if minimum < 0 or sentinelNumber < 0)
		//     prefix/number                       (user pasted or dropped a number over the sentinelText)
		//     prefix/beginningOrAllOfSentinelText (user is typing out the sentinelText, case-insensitive)
		//
		//     when any of those last five forms occur, the suffix is immediately appended since a number will be/is being entered
		
		((PlainDocument) getDocument()).setDocumentFilter(new DocumentFilter() {
			
			@Override public void insertString(DocumentFilter.FilterBypass fb, int offset, String string, AttributeSet attr) throws BadLocationException {
				String newText = new StringBuilder(getText()).insert(offset, string).toString();
				if(isAcceptable(newText)) {
					fb.insertString(offset, string, attr);
					if(isEnabled() && !newText.endsWith(suffix) && checkForSentinel && !newText.endsWith(sentinelText))
						setText(getText() + suffix);
				} else {
					Toolkit.getDefaultToolkit().beep();
				}
			}
			
			@Override public void remove(DocumentFilter.FilterBypass fb, int offset, int length) throws BadLocationException {
				String newText = new StringBuilder(getText()).delete(offset, offset + length).toString();
				if(isAcceptable(newText)) {
					fb.remove(offset, length);
					if(isEnabled() && !newText.endsWith(suffix) && checkForSentinel && !newText.endsWith(sentinelText))
						setText(getText() + suffix);
				} else {
					Toolkit.getDefaultToolkit().beep();
				}
			}
			
			@Override public void replace(DocumentFilter.FilterBypass fb, int offset, int length, String string, AttributeSet attr) throws BadLocationException {
				String newText = new StringBuilder(getText()).replace(offset, offset + length, string).toString();
				if(isAcceptable(newText)) {
					fb.replace(offset, length, string, attr);
					if(isEnabled() && !newText.endsWith(suffix) && checkForSentinel && !newText.endsWith(sentinelText))
						setText(getText() + suffix);
				} else {
					Toolkit.getDefaultToolkit().beep();
				}
			}
			
			private boolean isAcceptable(String text) {
				
				if(!isEnabled())
					return true; // allow any text
				
				boolean hasPrefix = text.startsWith(prefix);
				boolean hasSuffix = text.endsWith(suffix);
				
				if(hasPrefix && hasSuffix && text.length() >= prefixLength + suffixLength) {
					
					String centerRegion = text.substring(prefixLength, text.length() - suffixLength);
					
					try {
						Integer.parseInt(centerRegion);
						return true; // prefix/number/suffix
					} catch(NumberFormatException e) {}
					
					if(centerRegion.length() == 0)
						return true; // prefix/suffix
					
					if(centerRegion.length() == 1 && centerRegion.charAt(0) == '-' && (minimum < 0 || (checkForSentinel && sentinelNumber < 0)))
						return true; // prefix/-/suffix
					
					if(checkForSentinel && sentinelText.toLowerCase().startsWith(centerRegion.toLowerCase()))
						return true; // prefix/beginningOrAllOfSentinelText/suffix
					
				} else if (hasPrefix && !hasSuffix) {
					
					String remainingText = text.substring(prefixLength);
					
					if(checkForSentinel && sentinelText.toLowerCase().equals(remainingText.toLowerCase()))
						return true; // prefix/sentinelText
					
					if(remainingText.length() == 0)
						return true; // prefix
					
					if(remainingText.length() == 1 && remainingText.charAt(0) == '-' && (minimum < 0 || (checkForSentinel && sentinelNumber < 0)))
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
			
		});
		
		// track if the mouse button is currently down
		addMouseListener(new MouseAdapter() {
			@Override public void mouseReleased(MouseEvent e) { mouseButtonDown = false; }
			@Override public void mousePressed(MouseEvent e)  { mouseButtonDown = true;  }
		});
		
		// when the user clicks/tabs onto the textfield, select the number unless the user is click-and-dragging
		addFocusListener(new FocusAdapter() {
			@Override public void focusGained(FocusEvent e) {
				if(!mouseButtonDown) {
					selectAll();
				} else {
					Timer t = new Timer(100, event -> {
						if(!mouseButtonDown)
							selectAll();
					});
					t.setRepeats(false);
					t.start();
				}
			}
		});
		
		// intercept caret/selection events to ensure only the number region can be selected or navigated
		// if sentinelText is present, force it to always be selected
		setNavigationFilter(new NavigationFilter() {
			
			@Override public void setDot(NavigationFilter.FilterBypass fb, int dot, Position.Bias bias) {
				String text = getText();
				int minAllowedPosition = prefixLength;
				int maxAllowedPosition = text.endsWith(suffix) ? text.length() - suffixLength :
				                                                 text.length();
				dot = (dot < minAllowedPosition) ? minAllowedPosition :
				      (dot > maxAllowedPosition) ? maxAllowedPosition :
				                                   dot;
				if(checkForSentinel && number == sentinelNumber && text.endsWith(sentinelText)) {
					fb.setDot(minAllowedPosition, bias);
					fb.moveDot(maxAllowedPosition, bias);
				} else {
					fb.setDot(dot, bias);
				}
			}
			
			@Override public void moveDot(NavigationFilter.FilterBypass fb, int dot, Position.Bias bias) {
				String text = getText();
				if(checkForSentinel && number == sentinelNumber && text.endsWith(sentinelText))
					return;
				int minAllowedPosition = prefixLength;
				int maxAllowedPosition = text.endsWith(suffix) ? text.length() - suffixLength :
				                                                 text.length();
				dot = (dot < minAllowedPosition) ? minAllowedPosition :
				      (dot > maxAllowedPosition) ? maxAllowedPosition :
				                                   dot;
				if(checkForSentinel && number == sentinelNumber && text.endsWith(sentinelText)) {
					fb.setDot(minAllowedPosition, bias);
					fb.moveDot(maxAllowedPosition, bias);
				} else {
					fb.moveDot(dot, bias);
				}
			}
			
		});
		
		// when the user clicks/tabs away from the textfield, validate the number
		addFocusListener(new FocusAdapter() {
			@Override public void focusLost(FocusEvent e) {
				validateNewText();
			}
		});
		
		// when the user presses Enter, validate the number and select the number
		addActionListener(event -> {
			validateNewText();
			selectAll();
		});
		
	}
	
	/**
	 * Keep track of the old validated text when disabling, so it can be restored when enabling.
	 */
	private String preDisabledText;
	@Override public void setEnabled(boolean enabled) {
		
		if(isEnabled() && !enabled)
			preDisabledText = getText();
		if(!isEnabled() && enabled)
			setText(preDisabledText);
		super.setEnabled(enabled);
		
	}
	
	/**
	 * Sets the contents of this textfield, and resizes the textfield to fit the new text.
	 */
	@Override public void setText(String t) {
		
		super.setText(t);
		
		revalidate();
		repaint();
		Container c = getParent();
		if(c != null) {
			c.revalidate();
			c.repaint();
		}	
		
	}
	
	/**
	 * Sets the number. It will be validated and the event handler will be notified if the number has changed.
	 * 
	 * @param newNumber    New number to use.
	 * @return             True if the new number was accepted, false if rejected.
	 */
	public boolean setNumber(int newNumber) {
		
		if(number == newNumber)
			return true;

		setText(prefix + newNumber + suffix);
		validateNewText();
		if(!isEnabled())
			preDisabledText = getText();
		return (number == newNumber);
		
	}
	
	/**
	 * Disables the textfield and displays a number without validating it.
	 * The event handler is NOT notified about this number.
	 * This allows the textfield to be used for displaying information when disabled.
	 * 
	 * @param displayedNumber    Number to display.
	 */
	public void disableWithNumber(int displayedNumber) {
		
		setEnabled(false);
		setText(prefix + displayedNumber + suffix);
		
	}
	
	/**
	 * Disables the textfield and displays a message without validating it.
	 * The event handler is NOT notified.
	 * This allows the textfield to be used for displaying information when disabled.
	 * 
	 * @param message    Message to display.
	 */
	public void disableWithMessage(String message) {
		
		setEnabled(false);
		setText(prefix + message);
		
	}
	
	/**
	 * @return    The underlying number.
	 */
	public int getNumber() {
		
		return number;
		
	}
	
	/**
	 * Checks the value of the textfield and accepts or rejects the change.
	 * If the change is accepted, the number is updated, and the event handler is notified.
	 * If the change is rejected, the textfield is updated.
	 */
	private void validateNewText() {
		
		boolean sentinelMode = checkForSentinel && number == sentinelNumber;
		String newText = getText();
		String oldText = sentinelMode ? prefix + sentinelText : 
		                                prefix + number + suffix;
				
		// ignore if nothing changed
		if(newText.equals(oldText))
			return;
		
		// parse the number region
		String numberText = newText.substring(prefixLength, newText.length() - suffixLength);
		
		// reject if the number region doesn't contain an integer/sentinel or it's outside the allowed range
		int newNumber = 0;
		try {
			if(checkForSentinel && numberText.toLowerCase().equals(sentinelText.toLowerCase())) {
				newNumber = sentinelNumber;
			} else if(checkForSentinel && numberText.equals(Integer.toString(sentinelNumber))) {
				newNumber = sentinelNumber;
			} else {
				newNumber = Integer.parseInt(numberText);
				if((newNumber < minimum && checkForSentinel && newNumber != sentinelNumber) || (newNumber < minimum && !checkForSentinel))
					throw new Exception();
				else if((newNumber > maximum && checkForSentinel && newNumber != sentinelNumber) || (newNumber > maximum && !checkForSentinel))
					throw new Exception();
			}
		} catch(Exception e) {
			setText(oldText);
			Toolkit.getDefaultToolkit().beep();
			return;
		}
		
		// the number is acceptable
		number = newNumber;
		
		// update the GUI
		if(checkForSentinel && number == sentinelNumber)
			setText(prefix + sentinelText);
		else
			setText(prefix + number + suffix);
		
		// notify the event handler
		if(eventHandler != null)
			eventHandler.accept(number);
		
	}
	
	/**
	 * When sizing this textfield, make it slightly wider than necessary.
	 */
	@Override public Dimension getPreferredSize() {
		
		Dimension size = super.getPreferredSize();
		size.width += getColumnWidth() * 2;
		return size;
		
	}
	
	/**
	 * Don't let this textfield shrink.
	 */
	@Override public Dimension getMinimumSize() {
		
		return getPreferredSize();
		
	}

}
