import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

@SuppressWarnings("serial")
public class WidgetCombobox<T> implements Widget {

	private String importExportLabel = "";
	private JLabel prefixLabel;
	private volatile T value;
	private List<T> values;
	private JComboBox<T> combobox;
	private T disabledMessage = null;
	private Map<T, String> disabledValues = new HashMap<T,String>(); // <value,tooltip>
	private boolean forcedDisabled = false;
	private boolean changeHandlerCalled = false;
	private BiPredicate<T,T> changeHandler;
	private boolean maskEvents = false;

	@SuppressWarnings("unchecked")
	public WidgetCombobox(String label, List<T> values, T selectedValue) {
		
		if(label != null && !label.equals(""))
			prefixLabel = new JLabel(label + ": ");
		
		combobox = new JComboBox<T>() {
			@Override public Dimension getMinimumSize() { return getPreferredSize(); } // don't let it shrink
		};
		values.forEach(value -> combobox.addItem(value));
		
		if(selectedValue != null) {
			if(values.contains(selectedValue)) {
				combobox.setSelectedItem(selectedValue);
			} else {
				combobox.addItem(selectedValue);
				combobox.setSelectedItem(selectedValue);
			}
		}
		value = selectedValue;
		this.values = values;
		
		combobox.setMaximumRowCount(combobox.getItemCount() + 1); // +1 so the user-specified item also fits on screen when setEditable(true)
		
		combobox.addActionListener(event -> {
			T newValue = (T) combobox.getSelectedItem();
			
			// ignore the event if disabled-with-message or if resetValues() is in progress
			if(disabledMessage != null || maskEvents)
				return;
			
			if(disabledValues.containsKey(newValue)) {
				set(value);
				return;
			}
			
			T oldValue = value;
			
			if(changeHandler == null) {
				value = newValue;
			} else {
				value = newValue;
				changeHandlerCalled = true;
				boolean accepted = changeHandler.test(value, oldValue);
				if(!accepted) {
					value = oldValue;
					set(oldValue);
				}
			}
			
			// if switching away from a custom value, remove that custom value
			if(value != oldValue && !this.values.contains(oldValue) && oldValue != null)
				removeValue(oldValue);
		});
		
		combobox.setRenderer(new DefaultListCellRenderer() {
			@Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
				setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
				setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());
				setToolTipText(null);
				setOpaque(combobox.isEnabled());
				if(disabledValues.containsKey(value)) {
					setBackground(list.getBackground());
					setForeground(UIManager.getColor("Label.disabledForeground"));
					setToolTipText("[Disabled] " + disabledValues.get(value));
					// note: and the ActionListener defined above will reject selection of disabled items
				}
				setFont(list.getFont());
				setText(value == null ? "" : value.toString());
				return this;
			}
		});
		
	}
	
	/**
	 * @param label    Label to use when importing/exporting a settings file.
	 */
	public WidgetCombobox<T> setExportLabel(String label) {
		
		importExportLabel = (label == null) ? "" : label;
		return this;
		
	}
	
	/**
	 * Enables or disables editing of this combobox. This should only be enabled if the combobox contains Strings!
	 */
	public WidgetCombobox<T> setEditable(boolean isEditable) {
		
		combobox.setEditable(isEditable);
		combobox.getEditor().getEditorComponent().addFocusListener(new FocusListener() {
			@Override public void focusGained(FocusEvent e) { combobox.getEditor().selectAll(); }
			@Override public void focusLost(FocusEvent e) { }
		});
		return this;
		
	}
	
	public WidgetCombobox<T> onChange(BiPredicate<T,T> handler) {
		
		changeHandler = handler;
		changeHandlerCalled = false;
		
		// call the handler, but later, so the calling code can finish constructing things before the handler is triggered
		SwingUtilities.invokeLater(() -> {
			if(changeHandler != null && changeHandlerCalled == false) {
				changeHandlerCalled = true;
				changeHandler.test(value, value);
			}
		});
		
		return this;
		
	}
	
	public boolean is(T someValue) {
		return value.equals(someValue);
	}
	
	public T get() {
		return value;
	}
	
	public WidgetCombobox<T> set(T value) {
		boolean found = false;
		for(int i = 0; i < combobox.getItemCount(); i++)
			if(combobox.getItemAt(i).equals(value))
				found = true;
		if(!found)
			combobox.addItem(value);
		combobox.setSelectedItem(value);
		return this;
	}
	
	public WidgetCombobox<T> removeValue(T someValue) {
		combobox.removeItem(someValue);
		return this;
	}
	
	/**
	 * @param map    Keys are disabled items, values are corresponding tooltips.
	 */
	public void setDisabledValues(Map<T, String> map) {
		disabledValues = map;
		
		// if the current item is now disabled, switch to the first enabled item
		if(disabledValues.containsKey(value))
			set(values.stream().filter(value -> !disabledValues.containsKey(value)).findFirst().orElse(null));
	}
	
	public void resetValues(List<T> newValues, T selectedValue) {
		values = newValues;
		boolean selectedValueHasChanged = selectedValue != value;
		
		maskEvents = true;
		combobox.removeAllItems();
		for(T newValue : newValues)
			combobox.addItem(newValue);
		combobox.setMaximumRowCount(combobox.getItemCount());
		if(selectedValueHasChanged)
			maskEvents = false;
		set(selectedValue);
		maskEvents = false;
		
		if(disabledMessage != null)
			disableWithMessage(disabledMessage);
	}
	
	@Override public void appendTo(JPanel panel, String constraints) {
		
		if(prefixLabel == null) {
			panel.add(combobox, constraints);
		} else {
			panel.add(prefixLabel, "split 2");
			panel.add(combobox, "grow");
		}
		
	}

	@Override public WidgetCombobox<T> setVisible(boolean isVisible) {

		if(prefixLabel == null) {
			combobox.setVisible(isVisible);
		} else {
			prefixLabel.setVisible(isVisible);
			combobox.setVisible(isVisible);
		}
		return this;
		
	}
	
	@Override public WidgetCombobox<T> setEnabled(boolean isEnabled) {
		
		if(forcedDisabled)
			return this;
		
		if(isEnabled && disabledMessage != null) {
			removeValue(disabledMessage);
			set(value);
			disabledMessage = null;
		}
		
		if(prefixLabel == null) {
			combobox.setEnabled(isEnabled);
		} else {
			prefixLabel.setEnabled(isEnabled);
			combobox.setEnabled(isEnabled);
		}
		
		return this;
		
	}
	
	public WidgetCombobox<T> forceDisabled(boolean isDisabled) {
		
		if(isDisabled && disabledMessage == null)
			setEnabled(false);
		forcedDisabled = isDisabled;
		if(!isDisabled)
			setEnabled(true);
		return this;
		
	}
	
	/**
	 * Disables the combobox and displays a message without validating it.
	 * The event handler is NOT notified.
	 * This allows the combobox to be used for displaying information when disabled.
	 * 
	 * @param message    Message to display.
	 */
	public void disableWithMessage(T message) {
		
		setEnabled(false);
		
		disabledMessage = message;
		
		boolean disabledMessageExists = false;
		for(int i = 0; i < combobox.getItemCount(); i++)
			if(combobox.getItemAt(i).equals(disabledMessage))
				disabledMessageExists = true;
		if(!disabledMessageExists)
			combobox.addItem(disabledMessage);
		
		combobox.setSelectedItem(disabledMessage);
		
	}

	@SuppressWarnings("unchecked")
	@Override public void importFrom(Connections.QueueOfLines lines) throws AssertionError {

		String text = lines.parseString(importExportLabel + " = %s");
		T newValue = values.stream().filter(value -> value.toString().equals(text)).findFirst().orElse(null);
		
		if(newValue != null) {
			set(newValue);
		} else if(!combobox.isEditable()) {
			String message = "Invalid " + importExportLabel + ".\n";
			message += "Expected: " + values.stream().map(value -> value.toString()).collect(Collectors.joining(" or "));
			message += "\nFound: " + text;
			throw new AssertionError(message);
		} else {
			set((T) text);
		}
		
	}

	@Override public void exportTo(PrintWriter file) {
		
		file.println("\t" + importExportLabel + " = " + value.toString());
		
	}
	
}
