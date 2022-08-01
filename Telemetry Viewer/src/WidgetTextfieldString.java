import java.awt.Container;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Queue;
import java.util.function.Consumer;

import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import javax.swing.text.NavigationFilter;
import javax.swing.text.PlainDocument;
import javax.swing.text.Position;

@SuppressWarnings("serial")
public class WidgetTextfieldString extends JTextField implements Widget {
	
	private String prefix;
	private String suffix;
	private int prefixLength;
	private int suffixLength;
	private String importExportLabel;
	
	private String userText; // contents of the textfield *without* the prefix/suffix
	private ConnectionTelemetry.TransmitDataType dataType; // what the userText represents: TEXT or HEX or BINARY data
	private final Consumer<String> eventHandler; // provided with userText, regardless of the dataType
	
	private boolean mouseButtonDown = false;
	private boolean maybeSelectAll = false;
	private long    mouseButtonDownTimestamp = 0;
	
	private boolean deleteKeyDown = false;
	
	/**
	 * Creates a special JTextField that is used to enter text.
	 * A prefix and suffix can be shown to provide more context for the user.
	 * For convenience, the text region will be automatically selected when the textfield gains focus, and when the user presses Enter.
	 * 
	 * The textfield can also be disabled and instead used to *show* a message.
	 * When disabled, showing a message will not cause the underlying text to change,
	 * and will not trigger the event handler. When enabled, the old underlying text will be shown again.
	 * 
	 * @param prepend             Optional text to show before the user's text. Can be null or "".
	 * @param importExportText    Text to use when importing or exporting.
	 * @param append              Optional text to show after the user's text. Can be null or "".
	 * @param type                Type of data the user can enter: TEXT or HEX or BINARY.
	 * @param text                Default text.
	 * @param handler             Event handler to be notified when the text changes. Can be null.
	 */
	public WidgetTextfieldString(String prepend, String importExportText, String append, ConnectionTelemetry.TransmitDataType type, String text, Consumer<String> handler) {
		
		super();
		
		// if provided, add a colon and space after the prefix, and a space before the suffix
		prepend = (prepend == null || prepend == "") ? "" : prepend + ": ";
		append = (append == null || append == "") ? "" : " " + append;
		
		// initialize
		prefix = prepend;
		suffix = append;
		prefixLength = prefix.length();
		suffixLength = suffix.length();
		importExportLabel = importExportText;
		userText = text;
		dataType = type;
		eventHandler = handler;
		
		// notify the event handler of the GUI's current state
		setText(prefix + text + suffix);
		validateNewText();
		
		// a DocumentFilter is used to intercept text changes (via keyboard, copy-and-paste, drag-and-drop, etc.)
		// this ensures that only allowed characters can be entered
		// the textfield is only allowed to contain text in the following forms:
		//
		//     prefix/<anything>/suffix          (if dataType == TEXT)
		//     prefix/<0-9,A-F,spaces>/suffix    (if dataType == HEX)
		//     prefix/<0,1,spaces>/suffix        (if dataType == BINARY)
		
		((PlainDocument) getDocument()).setDocumentFilter(new DocumentFilter() {
			
			@Override public void insertString(DocumentFilter.FilterBypass fb, int offset, String string, AttributeSet attr) throws BadLocationException {
				
				// in text mode, always accept the insertion
				if(dataType == ConnectionTelemetry.TransmitDataType.TEXT) {
					fb.insertString(offset, string, attr);
					return;
				}
				
				// if the new text contains >1 character, strip out any spaces
				if(string.length() > 1)
					string = string.replaceAll("[ ]", "");
				
				// in hex mode, convert to upper case
				if(dataType == ConnectionTelemetry.TransmitDataType.HEX)
					string = string.toUpperCase();
				
				// reject insertion of prohibited characters
				if(!string.matches((dataType == ConnectionTelemetry.TransmitDataType.HEX) ? "[0-9A-F ]*" : "[0-1 ]*")) {
					Toolkit.getDefaultToolkit().beep();
					return;
				}
				
				int digitsPerByte = (dataType == ConnectionTelemetry.TransmitDataType.HEX) ? 2 : 8;
				
				// if inserting a space, only accept if cursor is at the end of a byte
				// if a space already exists, don't insert a new one, but instead move the cursor to the right
				if(string.equals(" ")) {
					int cursorLocation = getCaretPosition();
					boolean cursorAtEndOfByte = (cursorLocation - prefixLength - digitsPerByte) % (digitsPerByte + 1) == 0;
					boolean spaceAlreadyExists = getText().length() - suffixLength > cursorLocation && getText().charAt(cursorLocation) == ' ';
					if(cursorAtEndOfByte && !spaceAlreadyExists) {
						fb.insertString(offset, string, attr);
						return;
					} else if(cursorAtEndOfByte && spaceAlreadyExists) {
						setCaretPosition(getCaretPosition() + 1);
						return;
					} else {
						Toolkit.getDefaultToolkit().beep();
						return;
					}
				}
				
				// inserting one or more digits, so perform the insertion, and ensure spaces are between each byte
				// then move the cursor to *after* the last inserted digit
				String newText = new StringBuilder(getText()).insert(offset, string).toString();
				String digits = newText.substring(prefixLength, newText.length() - suffixLength).replaceAll("[ ]", "");
				StringBuilder digitsSpacedOut = new StringBuilder();
				for(int i = 0; i < digits.length(); i++) {
					boolean lastDigitInByte = ((i + 1) % (digitsPerByte)) == 0;
					digitsSpacedOut.append(digits.charAt(i) + ((lastDigitInByte && i != digits.length() - 1) ? " " : ""));
				}
				fb.replace(0, getText().length(), prefix + digitsSpacedOut.toString() + suffix, attr);
				
				int digitsBeforeCursor = (offset - prefixLength) - ((offset - prefixLength) / (digitsPerByte + 1)) + string.length();
				int newCursorLocation = prefixLength + digitsBeforeCursor + ((digitsBeforeCursor - 1) / digitsPerByte);
				setCaretPosition(newCursorLocation);
				return;
				
			}
			
			@Override public void remove(DocumentFilter.FilterBypass fb, int offset, int length) throws BadLocationException {

				// reject if modifying the prefix or suffix
				if(offset < prefixLength || offset + length > getText().length() - suffixLength) {
					Toolkit.getDefaultToolkit().beep();
					return;
				}
				
				// in text mode, always accept the removal
				if(dataType == ConnectionTelemetry.TransmitDataType.TEXT) {
					fb.remove(offset, length);
					return;
				}
				
				// in hex or binary mode, always accept the removal
				// ensure spaces are between each byte, then move the cursor as needed
				String newText = new StringBuilder(getText()).delete(offset, offset + length).toString();
				String digits = newText.substring(prefixLength, newText.length() - suffixLength).replaceAll("[ ]", "");
				StringBuilder digitsSpacedOut = new StringBuilder();
				int digitsPerByte = (dataType == ConnectionTelemetry.TransmitDataType.HEX) ? 2 : 8;
				for(int i = 0; i < digits.length(); i++) {
					boolean lastDigitInByte = ((i + 1) % (digitsPerByte)) == 0;
					digitsSpacedOut.append(digits.charAt(i) + ((lastDigitInByte && i != digits.length() - 1) ? " " : ""));
				}
				fb.replace(0, getText().length(), prefix + digitsSpacedOut.toString() + suffix, null);
				
				if(offset > getText().length()) // a trailing space was removed
					setCaretPosition(getText().length());
				else if(deleteKeyDown && length == 1 && getText().charAt(offset) == ' ' && offset != getText().length() - 1) // user attempted to delete a space between bytes, so lets just move the cursor to the right
					setCaretPosition(offset + 1);
				else
					setCaretPosition(offset);
				return;
				
			}
			
			@Override public void replace(DocumentFilter.FilterBypass fb, int offset, int length, String string, AttributeSet attr) throws BadLocationException {
				
				// setText() will try to replace() the entire text
				// accept that if the prefix and suffix are preserved
				if(offset == 0 && length == getText().length() && string.startsWith(prefix) && string.endsWith(suffix) && (!prefix.isEmpty() || !suffix.isEmpty())) {
					fb.replace(offset, length, string, attr);
					return;
				}
				
				// reject if modifying the prefix or suffix
				if(offset < prefixLength || offset + length > getText().length() - suffixLength) {
					Toolkit.getDefaultToolkit().beep();
					return;
				}
				
				// otherwise we are only replacing part of the userText region
				// in that case, convert this replace() into a possible remove() and a possible insert()
				if(length > 0)
					remove(fb, offset, length);
				if(string.length() > 0)
					insertString(fb, offset, string, attr);
				
			}
			
		});
		
		// track when the delete key is down, so the DocumentFilter can move the cursor correctly when "deleting" a space between bytes
		addKeyListener(new KeyAdapter() {
			@Override public void keyPressed(KeyEvent e) {
				if(e.getKeyCode() == KeyEvent.VK_DELETE)
					deleteKeyDown = true;
			}
			@Override public void keyReleased(KeyEvent e) {
				if(e.getKeyCode() == KeyEvent.VK_DELETE)
					deleteKeyDown = false;
			}
		});
		
		// when the user clicks/tabs onto the textfield, select the userText unless the user is click-and-dragging
		addFocusListener(new FocusAdapter() {
			@Override public void focusGained(FocusEvent e) {
				if(!mouseButtonDown)
					selectAll();
				else
					maybeSelectAll = true;
			}
		});
		
		addMouseListener(new MouseAdapter() {
			@Override public void mousePressed(MouseEvent e)  {
				mouseButtonDown = true;
				mouseButtonDownTimestamp = System.currentTimeMillis();
			}
			@Override public void mouseReleased(MouseEvent e) {
				mouseButtonDown = false;
				if(maybeSelectAll) {
					long pressedTime = System.currentTimeMillis() - mouseButtonDownTimestamp;
					if(pressedTime < 200 && getSelectedText() == null)
						selectAll();
					maybeSelectAll = false;
				}
			}
		});
		
		// intercept caret/selection events to ensure only the userText region can be selected or navigated
		setNavigationFilter(new NavigationFilter() {
			
			@Override public void setDot(NavigationFilter.FilterBypass fb, int dot, Position.Bias bias) {
				String text = getText();
				int minAllowedPosition = prefixLength;
				int maxAllowedPosition = text.length() - suffixLength;
				dot = (dot < minAllowedPosition) ? minAllowedPosition :
				      (dot > maxAllowedPosition) ? maxAllowedPosition :
				                                   dot;
				fb.setDot(dot, bias);
			}
			
			@Override public void moveDot(NavigationFilter.FilterBypass fb, int dot, Position.Bias bias) {
				String text = getText();
				int minAllowedPosition = prefixLength;
				int maxAllowedPosition = text.length() - suffixLength;
				dot = (dot < minAllowedPosition) ? minAllowedPosition :
				      (dot > maxAllowedPosition) ? maxAllowedPosition :
				                                   dot;
				fb.moveDot(dot, bias);
			}
			
		});
		
		// when the user clicks/tabs away from the textfield, validate the userText
		addFocusListener(new FocusAdapter() {
			@Override public void focusLost(FocusEvent e) {
				validateNewText();
			}
		});
		
		// when the user presses Enter, validate the userText and select it
		addActionListener(event -> {
			validateNewText();
			selectAll();
		});
		
	}
	
	/**
	 * Enables or disables the textfield. If enabling, the text will be reset to it's normal contents.
	 */
	@Override public void setEnabled(boolean enabled) {
		
		super.setEnabled(enabled);
		if(isEnabled()) {
			setText(prefix + userText + suffix); // restore normal text
			validateNewText();
		}
		
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
	 * Sets the suffix to show after the userText. If the textfield is enabled, the textfield will be updated and the event handler will be called.
	 * 
	 * @param newSuffix    Suffix to display. Can be null or "".
	 */
	public void setSuffix(String newSuffix) {
		
		boolean updateTextfield = isEnabled() || getText().equals(prefix + userText + suffix);
		
		newSuffix = (newSuffix == null || newSuffix == "") ? "" : " " + newSuffix;
		suffix = newSuffix;
		suffixLength = suffix.length();
		
		if(updateTextfield) {
			setText(prefix + userText + suffix);
			validateNewText();
		}
		
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
	 * @return    True if the user has entered any acceptable text.
	 */
	public boolean hasText() {
		
		if(!isEnabled() && !getText().equals(prefix + userText + suffix))
			return !userText.isEmpty(); // test userText, because currently disabled-with-message
		else
			return getText().length() - prefixLength - suffixLength > 0; // test getText(), because the user may be typing now, so userText may be stale
		
	}
	
	/**
	 * @return    The underlying text (without prefix/suffix.)
	 */
	public String getUserText() {
		
		return userText;
		
	}
	
	/**
	 * Sets the userText. It will be validated and the event handler will be notified if the text has changed.
	 * 
	 * @param newText    New text to use.
	 * @return           True if the new text was accepted, false if rejected.
	 */
	public boolean setUserText(String newText) {
		
		if(userText.equals(newText))
			return true;

		setText(prefix + newText + suffix);
		validateNewText();
		return userText.equals(newText);
		
	}
	
	/**
	 * @return    The underlying text (without prefix/suffix) interpreted and converted into a byte[].
	 */
	public byte[] getUserTextAsBytes() {
		
		if(dataType == ConnectionTelemetry.TransmitDataType.TEXT) {
			return userText.getBytes();
		} else if(userText.isEmpty()) {
			return new byte[0];
		} else {
			String[] tokens = userText.split(" ");
			byte[] bytes = new byte[tokens.length];
			for(int i = 0; i < tokens.length; i++)
				bytes[i] = (byte) (Integer.parseInt(tokens[i], (dataType == ConnectionTelemetry.TransmitDataType.HEX) ? 16 : 2) & 0xFF);
			return bytes;
		}
		
	}
	
	/**
	 * Sets the userText based on the contents of a byte[].
	 * It will be validated and the event handler will be notified if the text has changed.
	 * 
	 * @param bytes    New text to use.
	 */
	public boolean setUserTextFromBytes(byte[] bytes) {
		
		String newText = (dataType == ConnectionTelemetry.TransmitDataType.TEXT) ? new String(bytes) :
		                 (dataType == ConnectionTelemetry.TransmitDataType.HEX)  ? ChartUtils.convertBytesToHexString(bytes) :
		                                                                           ChartUtils.convertBytesToBinString(bytes);
		setText(prefix + newText + suffix);
		validateNewText();
		return userText.equals(newText);
		
	}
	
	/**
	 * Changes the data type that is used for the userText. Existing text will be reformatted to the new type.
	 * 
	 * @param newType    New data type.
	 */
	public void setDataType(ConnectionTelemetry.TransmitDataType newType) {
		
		byte[] data = getUserTextAsBytes();
		dataType = newType;
		setUserTextFromBytes(data);
		
	}
	
	/**
	 * Checks the value of the textfield and accepts or rejects the change.
	 * If the change is accepted, the userText is updated, and the event handler is notified.
	 * If the change is rejected, the textfield is updated.
	 */
	private void validateNewText() {
		
		String newText = getText();
		String oldText = prefix + userText + suffix;
				
		// ignore if nothing changed
		if(newText.equals(oldText))
			return;
		
		// parse the userText region
		String text = newText.substring(prefixLength, newText.length() - suffixLength);
		
		// reject if the userText region is not as expected, and pad last byte if necessary
		if(dataType == ConnectionTelemetry.TransmitDataType.HEX) {
			text = text.toUpperCase().trim();
			boolean rejected = false;
			if(!text.matches("[0-9A-F ]*"))
				rejected = true; // contains prohibited characters
			for(int i = 2; i < text.length(); i += 3)
				if(text.charAt(i) != ' ')
					rejected = true; // missing space between bytes
			if(rejected) {
				setText(oldText);
				Toolkit.getDefaultToolkit().beep();
				return;
			}
			if(!text.isEmpty() && (text.length() - 2) % 3 != 0)
				text += "0"; // last byte incomplete, add a least-significant-nybble
		} else if(dataType == ConnectionTelemetry.TransmitDataType.BINARY) {
			text = text.trim();
			boolean rejected = false;
			if(!text.matches("[0-1 ]*"))
				rejected = true;
			for(int i = 8; i < text.length(); i += 9)
				if(text.charAt(i) != ' ')
					rejected = true; // missing space between bytes
			if(rejected){
				setText(oldText);
				Toolkit.getDefaultToolkit().beep();
				return;
			}
			if(!text.isEmpty())
				while((text.length() - 8) % 9 != 0)
					text += "0"; // last byte incomplete, add least-significant-bits
		}
		
		// the userText is acceptable
		userText = text;
		
		// update the GUI
		setText(prefix + userText + suffix);
		
		// notify the event handler
		if(eventHandler != null)
			eventHandler.accept(userText);
		
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

	@Override public void appendToGui(JPanel gui) {
		
		gui.add(this, "grow x");
		
	}

	@Override public void importFrom(Queue<String> lines) {

		String text = ChartUtils.parseString(lines.remove(), importExportLabel + " = %s");
		setUserText(text);
		
	}

	@Override public void exportTo(List<String> lines) {

		lines.add(importExportLabel + " = " + userText);
		
	}

}
