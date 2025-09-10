import java.io.PrintWriter;
import javax.swing.JPanel;

/**
 * Widgets are wrappers around Swing Components to make them easier to use and usually provide added functionality.
 * 
 * When defining your own Widgets, keep in mind the following conventions:
 * 
 *     - Values can be programmatically written with set() and read with get().
 *     
 *     - An event handler can be defined with onChange(). That handler must only be called when the value CHANGES.
 *       User code can safely assume their handler is only called when the value has changed!
 *     
 *     - To ensure user code is kept in sync with the value in the Widget, that handler must be called after the handler is defined.
 *       But we use a fluent interface, so the handler can not be called immediately because the object may not be fully constructed yet.
 *       So the handler must be invokeLater()'d. This presents a problem if set() is called shortly after constructing the Widget.
 *       In that case, set() may immediately trigger the event handler, so the invokeLater()'d code must be canceled in that case!
 *       
 *     - Textfields and Comboboxes can be "disabled with a message" in order to show a message other than the value they normally show.
 *       When disabled-with-a-message, the onChange() handler must NOT be notified about those disabled messages.
 *       But any time set() is called, even when disabled-with-a-message, the onChange() handler MUST be notified about the newly set value.
 *       When the Widget is enabled, the value from set() will always be restored on screen, overwriting any disabled message.
 *       
 *     - The get() and is() methods are the only methods guaranteed thread-safe. All other methods must run on the Swing EDT!
 */
public interface Widget {

	/**
	 * Populates a JPanel with the Swing Components of this widget.
	 * 
	 * @param panel          Place to populate with widgets.
	 * @param constraints    MigLayout component constraints or "".
	 */
	public void appendTo(JPanel panel, String constraints);
	
	/**
	 * Enables or disables visibility of the widget.
	 * 
	 * @param isVisible    If the widget should be shown on screen.
	 */
	public Widget setVisible(boolean isVisible);
	
	/**
	 * Enables or disables interaction with this widget.
	 * 
	 * @param isEnabled    If the widget can be interacted with.
	 */
	public Widget setEnabled(boolean isEnabled);

	/**
	 * Updates the widget based on a settings file.
	 * 
	 * @param lines              A queue of remaining lines from the settings file.
	 * @throws AssertionError    If one or more lines are not formatted correctly, or contain a setting that is outside the allowed range.
	 */
	public void importFrom(Connections.QueueOfLines lines) throws AssertionError;
	
	/**
	 * Saves the current state to a settings file.
	 * 
	 * @param file    File to write into.
	 */
	public void exportTo(PrintWriter file);
	
}
