import java.io.PrintWriter;
import java.util.function.Consumer;

import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

public class WidgetCheckbox implements Widget {
	
	private String importExportLabel;
	private JCheckBox checkbox;
	private Consumer<Boolean> handler;
	private volatile boolean isChecked;
	
	/**
	 * A widget that lets the user check or uncheck a checkbox.
	 * The export label defaults to a lower-case version of the label text.
	 * 
	 * @param label        Label to show next to the checkbox.
	 * @param isChecked    If the checkbox should default to checked.
	 */
	public WidgetCheckbox(String label, boolean isChecked) {
		
		this.isChecked = isChecked;
		checkbox = new JCheckBox(label, isChecked);
		checkbox.addActionListener(event -> {
			this.isChecked = checkbox.isSelected();
			if(handler != null)
				handler.accept(this.isChecked);
		});
		
		importExportLabel = label.toLowerCase();
		handler = null;
		
	}
	
	/**
	 * @param label    Label to use when importing/exporting a settings file.
	 */
	public WidgetCheckbox setExportLabel(String label) {
		
		importExportLabel = (label == null) ? "" : label;
		return this;
		
	}
	
	/**
	 * @param eventHandler    Will be notified when the checkbox changes. Can be null.
	 */
	public WidgetCheckbox onChange(Consumer<Boolean> eventHandler) {
		
		handler = eventHandler;
		
		// call the handler, but later, so the calling code can finish constructing things before the handler is triggered
		SwingUtilities.invokeLater(() -> {
			if(handler != null)
				handler.accept(isChecked);
		});
		
		return this;
		
	}
	
	public void set(boolean newValue) {
		
		if(isChecked == newValue)
			return;
		
		isChecked = newValue;
		checkbox.setSelected(isChecked);
		if(handler != null)
			handler.accept(isChecked);
		
	}
	
	public boolean get() {
		
		return isChecked;
		
	}
	
	public boolean isTrue() {
		
		return isChecked == true;
		
	}
	
	public boolean isFalse() {
		
		return isChecked == false;
		
	}
	
	public void setEnabled(boolean isEnabled) {
		
		checkbox.setEnabled(isEnabled);
		
	}

	@Override public void setVisible(boolean isVisible) {

		checkbox.setVisible(isVisible);
		
	}
	
	@Override public void appendTo(JPanel panel, String constraints) {
		
		panel.add(checkbox, constraints);
		
	}
	
	@Override public void importFrom(ConnectionsController.QueueOfLines lines) throws AssertionError {

		set(lines.parseBoolean(importExportLabel + " = %b"));
		
	}
	
	@Override public void exportTo(PrintWriter file) {
		
		file.println("\t" + importExportLabel + " = " + isChecked);
		
	}

}
