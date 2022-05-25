import java.awt.Dimension;
import java.awt.event.ActionListener;
import java.util.List;
import java.util.function.Predicate;

import javax.swing.JComboBox;

@SuppressWarnings("serial")
public class WidgetComboboxString extends JComboBox<String> {
	
	private String value;

	public WidgetComboboxString(List<String> values, String selectedValue, Predicate<String> handler) {
		
		// initialize
		super();
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
	
}
