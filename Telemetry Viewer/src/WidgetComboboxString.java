import java.awt.Dimension;
import java.awt.event.ActionListener;
import java.util.List;
import java.util.Queue;
import java.util.function.Predicate;

import javax.swing.JComboBox;
import javax.swing.JPanel;

@SuppressWarnings("serial")
public class WidgetComboboxString extends JComboBox<String> implements Widget {
	
	private String value;
	private String importExportLabel;

	public WidgetComboboxString(String importExportText, List<String> values, String selectedValue, Predicate<String> handler) {
		
		// initialize
		super();
		importExportLabel = importExportText;
		for(String value : values)
			addItem(value);
		if(values.contains(selectedValue)) {
			setSelectedItem(selectedValue);
		} else {
			addItem(selectedValue);
			setSelectedItem(selectedValue);
		}
		value = selectedValue;
		
		// don't use scroll bars, just make the drop-down big enough
		setMaximumRowCount(getItemCount());
		
		// when the user selects an option, ask the event handler if the choice is acceptable
		addActionListener(event -> {
			String newValue = (String) getSelectedItem();
			if(newValue.equals(value))
				return;
			
			if(handler == null) {
				value = newValue;
			} else {
				boolean accepted = handler.test(newValue);
				if(accepted)
					value = newValue;
				else
					setSelectedItem(value);
			}
		});
		
		// notify the event handler of the GUI's current state
		if(handler != null)
			handler.test((String) getSelectedItem());
		
	}
	
	@Override public void setSelectedItem(Object anObject) {
		boolean found = false;
		for(int i = 0; i < getItemCount(); i++)
			if(getItemAt(i).equals(anObject))
				found = true;
		if(!found)
			addItem(anObject.toString());
		super.setSelectedItem(anObject);
		for(ActionListener listener : getActionListeners())
			listener.actionPerformed(null);
	}
	
	/**
	 * Keep track of the old validated text when disabling, so it can be restored when enabling.
	 */
	private String preDisabledText;
	private String disabledText;
	@Override public void setEnabled(boolean enabled) {
		
		if(isEnabled() && !enabled)
			preDisabledText = (String) getSelectedItem();
		if(!isEnabled() && enabled) {
			setSelectedItem(preDisabledText);
			removeItem(disabledText);
		}
		super.setEnabled(enabled);
		
	}
	
	/**
	 * Disables the combobox and displays a message without validating it.
	 * The event handler is NOT notified.
	 * This allows the combobox to be used for displaying information when disabled.
	 * 
	 * @param message    Message to display.
	 */
	public void disableWithMessage(String message) {
		
		setEnabled(false);
		
		ActionListener[] listeners = getActionListeners();
		for(ActionListener listener : listeners)
			removeActionListener(listener);
		
		removeItem(disabledText); // in case this method is called multiple times while disabled
		addItem(message);
		setSelectedItem(message);
		disabledText = message;

		for(ActionListener listener : listeners)
			addActionListener(listener);
		
	}
	
	/**
	 * Don't let this combobox shrink.
	 */
	@Override public Dimension getMinimumSize() {
		
		return getPreferredSize();
		
	}

	@Override public void appendToGui(JPanel gui) {
		
		gui.add(this);
		
	}

	@Override public void importFrom(Queue<String> lines) {

		String text = ChartUtils.parseString(lines.remove(), importExportLabel + " = %s");
		int n = -1;
		for(int i = 0; i < getItemCount(); i++)
			if(getItemAt(i).equals(text))
				n = i;
		if(n >= 0) {
			setSelectedIndex(n);
		} else {
			addItem(text);
			setSelectedItem(text);
		}
		
		value = text;
		
	}

	@Override public void exportTo(List<String> lines) {

		lines.add(importExportLabel + " = " + value);
		
	}
	
}
