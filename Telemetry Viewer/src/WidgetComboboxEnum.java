import java.awt.Component;
import java.awt.Dimension;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

@SuppressWarnings("serial")
public class WidgetComboboxEnum<T> implements Widget {

	private String importExportLabel = "";
	private volatile T value;
	private T[] options;
	private JComboBox<T> combobox;
	private Map<T, String> disabledItems = new HashMap<T,String>(); // <item,tooltip>
	private Predicate<T> changeHandler;

	@SuppressWarnings("unchecked")
	public WidgetComboboxEnum(T[] values, T selectedValue) {
		
		combobox = new JComboBox<T>(values) {
			@Override public Dimension getMinimumSize() { return getPreferredSize(); } // don't let it shrink
		};
		combobox.setSelectedItem(selectedValue);
		value = selectedValue;
		options = values;
		
		combobox.setMaximumRowCount(combobox.getItemCount());
		
		combobox.addActionListener(event -> {
			T newValue = (T) combobox.getSelectedItem();
			if(newValue == value)
				return;
			
			if(disabledItems.containsKey(newValue)) {
				set(value);
				return;
			}
			
			if(changeHandler == null) {
				value = newValue;
			} else {
				boolean accepted = changeHandler.test(newValue);
				if(accepted)
					value = newValue;
				else
					set(value);
			}
		});
		
		combobox.setRenderer(new DefaultListCellRenderer() {
			@Override public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
				setBackground(isSelected ? list.getSelectionBackground() : list.getBackground());
				setForeground(isSelected ? list.getSelectionForeground() : list.getForeground());
				setToolTipText(null);
				setOpaque(combobox.isEnabled());
				if(disabledItems.containsKey(value)) {
					setBackground(list.getBackground());
					setForeground(UIManager.getColor("Label.disabledForeground"));
					setToolTipText("[Disabled] " + disabledItems.get(value));
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
	public WidgetComboboxEnum<T> setExportLabel(String label) {
		
		importExportLabel = (label == null) ? "" : label;
		return this;
		
	}
	
	@SuppressWarnings("unchecked")
	public WidgetComboboxEnum<T> onChange(Predicate<T> handler) {
		
		changeHandler = handler;
		
		// call the handler, but later, so the calling code can finish constructing things before the handler is triggered
		SwingUtilities.invokeLater(() -> {
			if(changeHandler != null)
				changeHandler.test((T) combobox.getSelectedItem());
		});
		
		return this;
		
	}
	
	public boolean is(T someValue) {
		return value == someValue;
	}
	
	public T get() {
		return value;
	}
	
	public WidgetComboboxEnum<T> set(T newValue) {
		combobox.setSelectedItem(newValue);
		return this;
	}
	
	public WidgetComboboxEnum<T> removeValue(T someValue) {
		combobox.removeItem(someValue);
		return this;
	}
	
	/**
	 * @param map    Keys are disabled items, values are corresponding tooltips.
	 */
	public void setDisabledItems(Map<T, String> map) {
		disabledItems = map;
		// FIXME and setSelectedItem() if the current item is now disabled!
	}
	
	@Override public void appendTo(JPanel panel, String constraints) {
		
		panel.add(combobox, constraints);
		
	}

	@Override public void setVisible(boolean isVisible) {

		combobox.setVisible(isVisible);
		
	}
	
	public WidgetComboboxEnum<T> setEnabled(boolean isEnabled) {
		
		combobox.setEnabled(isEnabled);
		return this;
		
	}

	@Override public void importFrom(ConnectionsController.QueueOfLines lines) throws AssertionError {

		String text = lines.parseString(importExportLabel + " = %s");
		T newValue = null;
		for(T option : options)
			if(option.toString().equals(text))
				newValue = option;
		
		if(newValue == null) {
			String message = "Invalid " + importExportLabel + ".\n";
			message += "Expected: " + options[0].toString();
			for(int i = 1; i < options.length; i++)
				message += " or " + options[i].toString();
			message += "\nFound: " + text;
			throw new AssertionError(message);
		} else {
			set(newValue);
		}
		
	}

	@Override public void exportTo(PrintWriter file) {
		
		file.println("\t" + importExportLabel + " = " + value.toString());
		
	}
	
}
