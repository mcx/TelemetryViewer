import java.awt.Container;
import java.util.List;
import java.util.Queue;
import java.util.function.Consumer;

import javax.swing.ButtonGroup;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;

public class WidgetToggleButtonEnum<T> implements Widget {
	
	JLabel prefixLabel;
	String prefix;
	String importExportLabel;
	JToggleButton[] buttons;
	
	private T value;
	private T[] options;
	
	public WidgetToggleButtonEnum(String label, String importExportText, T[] values, T selectedValue, Consumer<T> handler) {
		
		if(label != null && !label.equals("")) {
			prefixLabel = new JLabel(label + ": ");
			prefix = label;
		}
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
	
	public void setValue(T newValue) {
		
		for(JToggleButton button : buttons)
			if(button.getText().equals(newValue.toString()))
				button.setSelected(true);
		
	}

	@Override public void appendToGui(JPanel gui) {
		
		if(prefixLabel == null) {
			for(int i = 0; i < buttons.length; i++) {
				String constraints = (i == 0) ? "split " + buttons.length + ", grow" : "grow";
				gui.add(buttons[i], constraints);
			}
		} else {
			gui.add(prefixLabel, "split " + (buttons.length + 1));
			for(int i = 0; i < buttons.length; i++) {
				gui.add(buttons[i], "grow");
			}
		}

	}

	@Override public void setVisible(boolean isVisible) {
		
		if(prefixLabel != null)
			prefixLabel.setVisible(isVisible);
		for(int i = 0; i < buttons.length; i++)
			buttons[i].setVisible(isVisible);
		
	}

	@Override public void importFrom(Queue<String> lines) {

		String text = ChartUtils.parseString(lines.remove(), importExportLabel + " = %s");
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
			setValue(newValue);
		}

	}

	@Override public void exportTo(List<String> lines) {
		
		lines.add(importExportLabel + " = " + value.toString());

	}

}
