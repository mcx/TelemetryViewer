import java.awt.Dimension;
import java.io.PrintWriter;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

public class WidgetButton implements Widget {
	
	private JButton button;
	private volatile byte[] bytes;
	private Consumer<WidgetButton> clickHandler = null;
	private JButton removeButton = null; // only used if removable
	private JButton fixedWidthButton = null; // only used for fixed-width mode
	
	/**
	 * A widget that lets the user click on a button.
	 * 
	 * @param label    Text to show on the button.
	 */
	public WidgetButton(String label) {
		
		button = new JButton(label) {
			@Override public Dimension getPreferredSize() {
				return (fixedWidthButton == null) ? super.getPreferredSize() : fixedWidthButton.getPreferredSize();
			}
		};
		button.addActionListener(event -> callHandler());
		
	}
	
	/**
	 * @param eventHandler    Will be notified when the button is clicked. Can be null.
	 */
	public WidgetButton onClick(Consumer<WidgetButton> eventHandler) {
		
		clickHandler = eventHandler;
		return this;
		
	}
	
	@Override public void callHandler() {
		
		if(clickHandler != null)
			clickHandler.accept(this);
		
	}

	/**
	 * Adds a remove button next to the main button, and registers the event handler for it.
	 * 
	 * @param handler    Will be notified when the remove button is clicked.
	 */
	public WidgetButton onRemove(Consumer<WidgetButton> handler) {
		
		removeButton = new JButton(Theme.removeSymbol);
		removeButton.setBorder(Theme.narrowButtonBorder);
		removeButton.addActionListener(click -> handler.accept(this));
		return this;
		
	}
	

	public void click() {
		
		button.doClick();
		
	}
	
	public void requestFocus() {
		
		button.requestFocus();
		
	}
	
	public WidgetButton setNarrowBorder() {
		
		button.setBorder(Theme.narrowButtonBorder);
		if(fixedWidthButton != null)
			fixedWidthButton.setBorder(Theme.narrowButtonBorder);
		return this;
		
	}
	
	public WidgetButton setFixedWidthBasedOn(String placeholderText) {
		
		fixedWidthButton = new JButton(placeholderText);
		fixedWidthButton.setBorder(button.getBorder());
		return this;
		
	}
	
	public void setText(String text) {
		
		button.setText(text);
		
	}
	
	public String getText() {
		
		return button.getText();
		
	}
	
	/**
	 * Configures this button to also represent some bytes of data. This data will be saved to the settings file when exporting.
	 * The button's text will be left aligned (instead of centered.)
	 * This feature is primarily used when this button represents a bookmarked packet of data in a TX GUI.
	 * 
	 * @param newBytes    Data to store.
	 */
	public WidgetButton setBytes(byte[] newBytes) {
		
		bytes = newBytes;
		button.setHorizontalAlignment(SwingConstants.LEFT);
		return this;
		
	}
	
	/**
	 * @return    The data that was previously provided with setBytes().
	 *            This feature is primarily used when this button represents a bookmarked packet of data in a TX GUI.
	 */
	public byte[] getBytes() {
		
		return bytes;
		
	}
	
	public WidgetButton setEnabled(boolean isEnabled) {
		
		button.setEnabled(isEnabled);
		if(removeButton != null)
			removeButton.setEnabled(isEnabled);
		return this;
		
	}

	@Override public WidgetButton setVisible(boolean isVisible) {

		button.setVisible(isVisible);
		if(removeButton != null)
			removeButton.setVisible(isVisible);
		return this;
		
	}
	
	@Override public void appendTo(JPanel panel, String constraints) {
		
		panel.add(button, constraints);
		if(removeButton != null)
			panel.add(removeButton);
		
	}
	
	@Override public void importFrom(Connections.QueueOfLines lines) throws AssertionError {

		String label = lines.remove();
		String[] hexBytes = lines.remove().trim().split(" ");
		byte[] bytes = new byte[hexBytes.length];
		for(int i = 0; i < hexBytes.length; i++)
			bytes[i] = (byte) (Integer.parseInt(hexBytes[i], 16) & 0xFF);
		if(label.equals("") || bytes.length == 0)
			throw new AssertionError("Invalid data for button \"" + label + "\".");
		setText(label);
		setBytes(bytes);
		
	}
	
	@Override public void exportTo(PrintWriter file) {
		
		String hexText = IntStream.range(0, bytes.length)
		                          .mapToObj(i -> bytes[i])
		                          .map(b -> String.format("%02X", b))
		                          .collect(Collectors.joining(" "));
		file.println("\t\t" + getText());
		file.println("\t\t" + hexText);
		
	}

}
