import java.awt.Container;
import java.io.PrintWriter;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.ButtonGroup;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;

public class WidgetToggleButtonEnum<T> implements Widget {
	
	private String importExportLabel;
	private JLabel prefixLabel;
	private JToggleButton[] buttons;
	
	private volatile T value;
	private T[] options;
	
	public WidgetToggleButtonEnum(String label, String importExportText, T[] values, T selectedValue, Consumer<T> handler) {
		
		if(label != null && !label.equals(""))
			prefixLabel = new JLabel(label + ": ");
		importExportLabel = importExportText;
		options = values;
		
		buttons = new JToggleButton[values.length];
		ButtonGroup group = new ButtonGroup();
		for(int i = 0; i < values.length; i++) {
			int index = i;
			buttons[i] = new JToggleButton(values[i].toString(), values[i] == selectedValue);
			buttons[i].setBorder(Theme.narrowButtonBorder);
			buttons[i].addActionListener(event -> {
				value = values[index];
				if(handler != null) {
					handler.accept(value);
					// AFTER calling the event handler, force the parent Container to redraw
					// because the event handler may have added/removed/hid stuff
					Container parent = buttons[0].getParent();
					if(parent != null) {
						buttons[0].getParent().revalidate();
						buttons[0].getParent().repaint();
					}
				}
			});
			group.add(buttons[i]);
		}
		
		value = selectedValue;

		if(handler != null)
			handler.accept(value);
		
	}
	
	public boolean is(T someValue) {
		return value == someValue;
	}
	
	public T get() {
		
		return value;
		
	}
	
	public void set(T newValue) {
		
		for(JToggleButton button : buttons)
			if(button.getText().equals(newValue.toString())) {
				button.setSelected(true);
				List.of(button.getActionListeners()).forEach(listener -> listener.actionPerformed(null));
			}
		
	}

	@Override public void appendTo(JPanel panel, String constraints) {
		
		if(prefixLabel == null) {
			for(int i = 0; i < buttons.length; i++) {
				String constraint = (i == 0) ? "split " + buttons.length + ", grow" : "grow";
				panel.add(buttons[i], constraint);
			}
		} else {
			panel.add(prefixLabel, "split " + (buttons.length + 1));
			for(int i = 0; i < buttons.length; i++) {
				panel.add(buttons[i], "grow");
			}
		}

	}

	@Override public void setVisible(boolean isVisible) {
		
		if(prefixLabel != null)
			prefixLabel.setVisible(isVisible);
		for(int i = 0; i < buttons.length; i++)
			buttons[i].setVisible(isVisible);
		
	}

	@Override public void importFrom(ConnectionsController.QueueOfLines lines) throws AssertionError {

		String text = lines.parseString(importExportLabel + " = %s");
		T newValue = null;
		for(T option : options)
			if(option.toString().equals(text))
				newValue = option;
		
		if(newValue == null) {
			String message = "Invalid setting for " + importExportLabel + ".\n";
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
