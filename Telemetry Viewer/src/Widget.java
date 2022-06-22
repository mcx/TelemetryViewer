import java.util.List;
import java.util.Queue;

import javax.swing.JPanel;

public interface Widget {

	/**
	 * Populates a GUI (JPanel) with this widget.
	 * 
	 * @param gui    Place to populate with widgets. Populate with: gui.add(component, "miglayout constraints here");
	 */
	public void appendToGui(JPanel gui);
	
	/**
	 * Enables or disables visibility of the widgets.
	 * 
	 * @param isVisible    If the widgets should be shown on screen.
	 */
	public void setVisible(boolean isVisible);

	/**
	 * Updates the widget and chart based on a settings file.
	 * 
	 * @param lines    A queue of remaining lines from the settings file.
	 */
	public void importFrom(Queue<String> lines);
	
	/**
	 * Saves the current state to one or more lines of text.
	 * 
	 * @param    Append lines of text to this List.
	 */
	public void exportTo(List<String> lines);
	
}
