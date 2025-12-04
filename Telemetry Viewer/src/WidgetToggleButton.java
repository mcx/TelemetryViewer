import java.awt.Container;
import java.awt.Dimension;
import java.awt.Graphics;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.swing.ButtonGroup;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;

public class WidgetToggleButton<T> implements Widget {
	
	private String importExportLabel = "";
	private JLabel prefixLabel = null;
	private JLabel suffixLabel = null;
	private final List<JToggleButton> buttons = new ArrayList<JToggleButton>();
	
	private volatile T selectedValue;
	private final List<T> values;
	
	private boolean changeHandlerCalled = false;
	private BiPredicate<T,T> changeHandler = null; // (newValue, oldValue)
	
	/**
	 * Creates a single toggle button for allowing a bitfield edge or level to be selected.
	 * 
	 * @param isSelected     If the toggle button defaults to being selected.
	 * @param tooltipText    Text to show if the user hovers over the button. Should be "Show as tooltip" or "Show as level".
	 * @param suffixText     Optional, a label to show to the right of the button.
	 * @param item           The object this button represents.
	 * @param handler        Event handler that will be notified when the button is selected/de-selected.
	 */
	public WidgetToggleButton(boolean isSelected, String tooltipText, String suffixText, T item, Consumer<Boolean> handler) {
		
		if(suffixText != null && !suffixText.equals(""))
			suffixLabel = new JLabel(suffixText);
		
		selectedValue = item;
		values = List.of(item);
		
		var button = new JToggleButton("    ", isSelected) {
			@Override public Dimension getPreferredSize() {
				// this seems to fix a Swing bug when using multiple monitors with different DPI scaling factors
				// without this, dragging a window between monitors causes the button to resize slightly when clicked
				return new Dimension(getWidth(), getHeight());
			}
			@Override public void paintComponent(Graphics g) {
				// reminder: (0,0) is the top-left corner
				super.paintComponent(g);
				int width  = getWidth();
				int height = getHeight();
				int xAmount = (int) Math.round(width * 0.10); // ~10%
				int yAmount = (int) Math.round(height * 0.20); // ~20%
				int x0 = xAmount;
				int x1 = 2*xAmount;
				int x2 = (int)(3.5*xAmount);
				int x3 = 4*xAmount;
				int x5 = width - 4*xAmount;
				int x6 = width - 2*xAmount;
				int x7 = width - xAmount;
				int y0 = yAmount;
				int y1 = height - (int)(1.8*yAmount);
				int y2 = height - yAmount;
				if(tooltipText.equals("Show as tooltip")) {
					g.drawLine(x1, y0, x6, y0);
					g.drawLine(x6, y0, x6, y1);
					g.drawLine(x6, y1, x2, y1);
					g.drawLine(x2, y1, x1, y2);
					g.drawLine(x1, y2, x1, y0);
				} else if(tooltipText.equals("Show as level")) {
					g.drawLine(x0, y2, x1, y2);
					g.drawLine(x1, y2, x3, y0);
					g.drawLine(x3, y0, x5, y0);
					g.drawLine(x5, y0, x6, y2);
					g.drawLine(x6, y2, x7, y2);

					g.drawLine(x0, y0, x1, y0);
					g.drawLine(x1, y0, x3, y2);
					g.drawLine(x3, y2, x5, y2);
					g.drawLine(x5, y2, x6, y0);
					g.drawLine(x6, y0, x7, y0);
				}
			}
		};
		button.setBorder(Theme.narrowButtonBorder);
		button.setToolTipText(tooltipText);
		button.addActionListener(event -> {
			if(handler != null) {
				changeHandlerCalled = true;
				handler.accept(button.isSelected());
				// AFTER calling the event handler, force the parent Container to redraw
				// because the event handler may have added/removed/hid stuff
				Container parent = button.getParent();
				if(parent != null) {
					button.getParent().revalidate();
					button.getParent().repaint();
				}
			}
		});
		buttons.add(button);
		
		changeHandlerCalled = false;
		
		// call the handler, but later, so the calling code can finish constructing things before the handler is triggered
		SwingUtilities.invokeLater(() -> {
			if(handler != null && changeHandlerCalled == false) {
				changeHandlerCalled = true;
				handler.accept(button.isSelected());
			}
		});
	}
	
	/**
	 * Creates a set of mutually-exclusive toggle buttons.
	 * 
	 * @param prefix    Optional, a label to show to the left of the buttons.
	 * @param items     The objects corresponding to the buttons.
	 * @param item      The button to select by default.
	 */
	public WidgetToggleButton(String prefix, T[] items, T item) {
		
		if(prefix != null && !prefix.equals(""))
			prefixLabel = new JLabel(prefix + ": ");
		
		selectedValue = item;
		values = List.of(items);
		
		ButtonGroup group = new ButtonGroup();
		for(T value : values) {
			var button = new JToggleButton(value.toString(), value == item);
			button.setBorder(Theme.narrowButtonBorder);
			button.addActionListener(event -> {
				if(changeHandler != null) {
					T oldValue = selectedValue;
					selectedValue = value;
					changeHandlerCalled = true;
					boolean accepted = changeHandler.test(selectedValue, oldValue);
					if(!accepted) {
						selectedValue = oldValue;
						set(oldValue);
					}
					// AFTER calling the event handler, force the parent Container to redraw
					// because the event handler may have added/removed/hid stuff
					Container parent = button.getParent();
					if(parent != null) {
						button.getParent().revalidate();
						button.getParent().repaint();
					}
				} else {
					selectedValue = value;
				}
			});
			group.add(button);
			buttons.add(button);
		}
		
	}
	
	/**
	 * @param label    Label to use when importing/exporting a settings file. Can be null.
	 */
	public WidgetToggleButton<T> setExportLabel(String label) {
		
		importExportLabel = (label == null) ? "" : label;
		return this;
		
	}
	
	/**
	 * @param handler    Will be called when the selected button changes. Can be null.
	 *                   The event handler will receive (newValue, oldValue) and should return true if newValue is acceptable.
	 *                   If get() is called by code inside the handler, it will return newValue.
	 *                   If set() is called by code inside the handler, it will win *if* the handler returns true. If the handler returns false, the oldValue will go into effect.
	 */
	public WidgetToggleButton<T> onChange(BiPredicate<T,T> handler) {
		
		changeHandler = handler;
		changeHandlerCalled = false;
		
		// call the handler, but later, so the calling code can finish constructing things before the handler is triggered
		SwingUtilities.invokeLater(() -> {
			if(!changeHandlerCalled)
				callHandler();
		});
		
		return this;
		
	}
	
	@Override public void callHandler() {
		
		if(changeHandler != null) {
			changeHandlerCalled = true;
			changeHandler.test(selectedValue, selectedValue);
		}
		
	}
	
	public boolean is(T someValue) {
		return selectedValue == someValue;
	}
	
	public T get() {
		return selectedValue;
	}
	
	public void set(T newValue) {
		
		if(values.size() == 1)
			throw new UnsupportedOperationException(); // can't change the item if there's only one item ("boolean mode")
		
		for(JToggleButton button : buttons)
			if(button.getText().equals(newValue.toString())) {
				button.setSelected(true);
				List.of(button.getActionListeners()).forEach(listener -> listener.actionPerformed(null));
			}
		
	}

	@Override public void appendTo(JPanel panel, String constraints) {
		
		if(values.size() == 1) {
			// boolean mode
			if(suffixLabel == null) {
				panel.add(buttons.getFirst(), constraints);
			} else {
				panel.add(buttons.getFirst(), "");
				panel.add(suffixLabel, "grow");
			}
			return;
		}
		
		// enum mode
		if(!constraints.equals("")) {
			buttons.forEach(button -> panel.add(button, constraints));
		} else if(prefixLabel == null) {
			for(int i = 0; i < buttons.size(); i++) {
				String constraint = (i == 0) ? "split " + buttons.size() + ", grow" : "grow";
				panel.add(buttons.get(i), constraint);
			}
		} else {
			panel.add(prefixLabel, "split " + (buttons.size() + 1));
			buttons.forEach(button -> panel.add(button, "grow"));
		}

	}

	@Override public WidgetToggleButton<T> setVisible(boolean isVisible) {
		
		if(prefixLabel != null)
			prefixLabel.setVisible(isVisible);
		buttons.forEach(button -> button.setVisible(isVisible));
		if(suffixLabel != null)
			suffixLabel.setVisible(isVisible);
		return this;
		
	}
	
	public WidgetToggleButton<T> setEnabled(boolean isEnabled) {
		
		if(prefixLabel != null)
			prefixLabel.setEnabled(isEnabled);
		buttons.forEach(button -> button.setEnabled(isEnabled));
		if(suffixLabel != null)
			suffixLabel.setEnabled(isEnabled);
		return this;
		
	}
	
	public void setSelected(boolean isSelected) {
		if(values.size() != 1)
			throw new UnsupportedOperationException(); // setSelected() only works in "boolean mode"
		if(buttons.getFirst().isSelected() == isSelected)
			return;
		
		buttons.getFirst().setSelected(isSelected);
		List.of(buttons.getFirst().getActionListeners()).forEach(listener -> listener.actionPerformed(null));
	}
	
	public void setSelectedQuietly(boolean isSelected) {
		if(values.size() != 1)
			throw new UnsupportedOperationException(); // setSelected() only works in "boolean mode"
		if(buttons.getFirst().isSelected() == isSelected)
			return;
		
		buttons.getFirst().setSelected(isSelected);
	}
	
	public boolean isSelected() {
		if(values.size() != 1)
			throw new UnsupportedOperationException(); // isSelected() only works in "boolean mode"
		
		return buttons.getFirst().isSelected();
	}
	
	public boolean isNotSelected() {
		if(values.size() != 1)
			throw new UnsupportedOperationException(); // isSelected() only works in "boolean mode"
		
		return !buttons.getFirst().isSelected();
	}
	
	@Override public void importFrom(Connections.QueueOfLines lines) throws AssertionError {
		
		if(values.size() == 1) {
			// boolean mode
			boolean isSelected = lines.parseBoolean(importExportLabel + " = %b");
			setSelected(isSelected);
			return;
		} else {
			// enum mode
			String text = lines.parseString(importExportLabel + " = %s");
			T newValue = values.stream().filter(value -> value.toString().equals(text)).findFirst().orElse(null);
			if(newValue == null) {
				String message = "Invalid setting for " + importExportLabel + ".\n";
				message += "Expected: " + values.stream().map(value -> value.toString()).collect(Collectors.joining(" or "));
				message += "\nFound: " + text;
				throw new AssertionError(message);
			} else {
				set(newValue);
			}
		}

	}

	@Override public void exportTo(PrintWriter file) {
		
		if(values.size() == 1)
			file.println("\t" + importExportLabel + " = " + isSelected()); // boolean mode
		else
			file.println("\t" + importExportLabel + " = " + selectedValue.toString()); // enum mode

	}

}
