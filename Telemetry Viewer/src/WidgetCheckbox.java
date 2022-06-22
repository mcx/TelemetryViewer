import java.util.List;
import java.util.Queue;
import java.util.function.Consumer;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;

@SuppressWarnings("serial")
public class WidgetCheckbox extends JCheckBox implements Widget {
	
	String label;
	String importExportLabel;
	Consumer<Boolean> handler;
	
	/**
	 * A widget that lets the user check or uncheck a checkbox.
	 * 
	 * @param labelText           Label to show at the right of the checkbox.
	 * @param isChecked           If the checkbox should default to checked.
	 * @param eventHandler        Will be notified when the checkbox changes.
	 */
	public WidgetCheckbox(String labelText, boolean isChecked, Consumer<Boolean> eventHandler) {
		
		this(labelText, labelText.toLowerCase(), isChecked, eventHandler);
		
	}
	
	/**
	 * A widget that lets the user check or uncheck a checkbox.
	 * 
	 * @param labelText           Label to show at the right of the checkbox.
	 * @param importExportText    Text to use when importing or exporting.
	 * @param isChecked           If the checkbox should default to checked.
	 * @param eventHandler        Will be notified when the checkbox changes.
	 */
	public WidgetCheckbox(String labelText, String importExportText, boolean isChecked, Consumer<Boolean> eventHandler) {
		
		super(labelText);
		
		label = labelText;
		importExportLabel = importExportText;
		handler = eventHandler;
		
		setSelected(isChecked);
		addActionListener(event -> handler.accept(isSelected()));
		
		handler.accept(isSelected());
		
	}
	
	@Override public void appendToGui(JPanel gui) {
		
		gui.add(new JLabel(""), "");
		gui.add(this, "span 3, growx");
		
	}
	
	/**
	 * Updates the widget and chart based on settings from a settings file.
	 * 
	 * @param lines    A queue of remaining lines from the settings file.
	 */
	@Override public void importFrom(Queue<String> lines) {

		// parse the text
		boolean checked = ChartUtils.parseBoolean(lines.remove(), importExportLabel + " = %b");
		
		// update the widget
		setSelected(checked);
		
		// update the chart
		handler.accept(isSelected());
		
	}
	
	/**
	 * Saves the current state to one or more lines of text.
	 * 
	 * @param    Append lines of text to this List.
	 */
	@Override public void exportTo(List<String> lines) {
		
		lines.add(importExportLabel + " = " + isSelected());
		
	}

}
