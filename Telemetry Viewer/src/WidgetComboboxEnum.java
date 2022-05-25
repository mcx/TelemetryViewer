import java.awt.Dimension;
import java.util.function.Consumer;

import javax.swing.JComboBox;

@SuppressWarnings("serial")
public class WidgetComboboxEnum<T> extends JComboBox<T> {
	
	private T value;

	@SuppressWarnings("unchecked")
	public WidgetComboboxEnum(T[] values, T selectedValue, Consumer<T> handler) {
		
		// initialize
		super(values);
		setSelectedItem(selectedValue);
		value = selectedValue;
		
		// when the user selects an option, notify the event handler
		addActionListener(event -> {
			T newValue = (T) getSelectedItem();
			if(newValue == value)
				return;
			
			value = newValue;
			if(handler != null)
				handler.accept(newValue);
		});
		
		// notify the event handler of the GUI's current state
		if(handler != null)
			handler.accept((T) getSelectedItem());
		
	}
	
	/**
	 * Don't let this combobox shrink.
	 */
	@Override public Dimension getMinimumSize() {
		
		return getPreferredSize();
		
	}
	
}
