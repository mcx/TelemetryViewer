import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

@SuppressWarnings("serial")
public class WidgetComboboxString implements Widget {
	
	private String importExportLabel = "";
	private volatile String value;
	private JComboBox<String> combobox;
	private Map<String, String> disabledItems = new HashMap<String,String>(); // <item,tooltip>
	private Predicate<String> changeHandler;
	private boolean changeHandlerCalled = false;

	public WidgetComboboxString(List<String> values, String selectedValue) {
		
		combobox = new JComboBox<String>(values.toArray(new String[values.size()])) {
			@Override public Dimension getMinimumSize() { return getPreferredSize(); } // don't let it shrink
		};
		if(selectedValue != null) {
			if(values.contains(selectedValue)) {
				combobox.setSelectedItem(selectedValue);
			} else {
				combobox.addItem(selectedValue);
				combobox.setSelectedItem(selectedValue);
			}
		}
		value = selectedValue;
		
		combobox.setMaximumRowCount(combobox.getItemCount() + 1); // +1 so the user-specified item also fits on screen when setEditable(true)
		
		combobox.addActionListener(event -> {
			String newValue = (String) combobox.getSelectedItem();
			
			// ignore the event if the value has not changed, or if disabled-with-message
			if(newValue.equals(value))
				return;
			if(disabledMessage != null)
				return;
			
			// reject the change if it's in the disabled items list
			if(disabledItems.containsKey(newValue)) {
				set(value);
				return;
			}
			
			// only reject the change if there's a handler and it rejected the change
			if(changeHandler == null) {
				value = newValue;
			} else {
				changeHandlerCalled = true;
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
	public WidgetComboboxString setExportLabel(String label) {
		
		importExportLabel = (label == null) ? "" : label;
		return this;
		
	}
	
	public WidgetComboboxString setEditable(boolean isEditable) {
		
		combobox.setEditable(isEditable);
		combobox.getEditor().getEditorComponent().addFocusListener(new FocusListener() {
			@Override public void focusGained(FocusEvent e) { combobox.getEditor().selectAll(); }
			@Override public void focusLost(FocusEvent e) { }
		});
		return this;
		
	}
	
	public WidgetComboboxString onChange(Predicate<String> handler) {
		
		changeHandler = handler;
		changeHandlerCalled = false;
		
		// call the handler, but later, so the calling code can finish constructing things before the handler is triggered
		SwingUtilities.invokeLater(() -> {
			if(changeHandler != null && changeHandlerCalled == false) {
				changeHandlerCalled = true;
				changeHandler.test((String) combobox.getSelectedItem());
			}
		});
		
		return this;
		
	}
	
	public WidgetComboboxString set(String value) {
		boolean found = false;
		for(int i = 0; i < combobox.getItemCount(); i++)
			if(combobox.getItemAt(i).equals(value))
				found = true;
		if(!found)
			combobox.addItem(value.toString());
		combobox.setSelectedItem(value);
		return this;
	}
	
	public String get() {
		return value;
	}
	
	public void removeItem(String value) {
		combobox.removeItem(value);
	}
	
	/**
	 * @param map    Keys are disabled items, values are corresponding tooltips.
	 */
	public void setDisabledItems(Map<String, String> map) {
		disabledItems = map;
		
		// change to the first non-disabled item if the current item is disabled
		if(disabledItems.containsKey(value))
			for(int i = 0; i < combobox.getItemCount(); i++)
				if(!disabledItems.containsKey(combobox.getItemAt(i))) {
					combobox.setSelectedIndex(i);
					return;
				}
	}
	
	public WidgetComboboxString setEnabled(boolean enabled) {
		
		if(enabled && disabledMessage != null) {
			removeItem(disabledMessage);
			set(value);
			disabledMessage = null;
		}
		combobox.setEnabled(enabled);
		return this;
		
	}
	
	private String disabledMessage = null;
	
	/**
	 * Disables the combobox and displays a message without validating it.
	 * The event handler is NOT notified.
	 * This allows the combobox to be used for displaying information when disabled.
	 * 
	 * @param message    Message to display.
	 */
	public void disableWithMessage(String message) {
		
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

	@Override public void setVisible(boolean isVisible) {

		combobox.setVisible(isVisible);
		
	}

	@Override public void appendTo(JPanel panel, String constraints) {
		
		panel.add(combobox, constraints);
		
	}

	@Override public void importFrom(ConnectionsController.QueueOfLines lines) throws AssertionError {

		String text = lines.parseString(importExportLabel + " = %s");
		set(text);
		
		value = text;
		
	}

	@Override public void exportTo(PrintWriter file) {
		
		file.println("\t" + importExportLabel + " = " + value);
		
	}
	
}
