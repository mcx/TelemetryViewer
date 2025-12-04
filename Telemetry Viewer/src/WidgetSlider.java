import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.PrintWriter;
import java.util.Hashtable;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;

public class WidgetSlider<T> implements Widget {
	
	/**
	 * An improved slider:
	 * 
	 *     - The slider can represent an integer, logarithmic integer, or floating point number.
	 *     - An optional prefix can be specified, to show a label before the slider.
	 *     - An optional checkbox can be used to enable or disable the slider.
	 *           This is useful for settings that can be automatic or manual, such as camera focus.
	 *     - Event handlers are supported with onChange() and onDrag().
	 */
	
	private enum Mode {INTEGER, LOG_INTEGER, FLOAT};
	private final Mode mode;
	
	private String importExportLabel;
	private JLabel prefixLabel;
	private JCheckBox checkbox = null;
	private JSlider slider;
	private boolean forcedDisabled = false;

	private boolean changeHandlerCalled = false;
	private Consumer<Boolean> mousePressedHandler;
	private Consumer<Boolean> mouseReleasedHandler;
	private Consumer<T>            newValueHandler;  // if just a slider
	private BiConsumer<Boolean, T> newValuesHandler; // if a checkbox and slider
	
	// these will always be linear integers
	// conversion to floats or log integers will be done when exposing them to the outside world
	private final int min;
	private final int max;
	private volatile int value;
	private volatile boolean isSliderEnabled = true;
	
	public static WidgetSlider<Integer> ofInt(String label, int min, int max, int value) {
		
		return new WidgetSlider<Integer>(Mode.INTEGER, label, min, max, value);
		
	}
	
	public static WidgetSlider<Integer> ofLogInt(String label, int min, int max, int selectedValue) {
		
		int minimum = (int) (Math.log(min)           / Math.log(2));
		int maximum = (int) (Math.log(max)           / Math.log(2));
		int value   = (int) (Math.log(selectedValue) / Math.log(2));
		return new WidgetSlider<Integer>(Mode.LOG_INTEGER, label, minimum, maximum, value);
		
	}
	
	public static WidgetSlider<Float> ofFloat(String label, float min, float max, float selectedValue) {
		
		int minimum = Math.round(min * 10f);
		int maximum = Math.round(max * 10f);
		int value   = Math.round(selectedValue * 10f);
		return new WidgetSlider<Float>(Mode.FLOAT, label, minimum, maximum, value);
		
	}
	
	private WidgetSlider(Mode mode, String label, int min, int max, int selectedValue) {
		
		this.mode = mode;
		importExportLabel = label == null ? "" : label.toLowerCase();
		if(label != null && !label.equals(""))
			prefixLabel = new JLabel(label + ":");
		
		this.min = min;
		this.max = max;
		this.value = selectedValue;
		
		slider = new JSlider(min, max, selectedValue) {
			@Override public Dimension getPreferredSize() {
				Dimension d = super.getPreferredSize();
				d.width /= 2;
				return d;
			}
		};
		
		slider.addMouseListener(new MouseAdapter() {
			@Override public void mousePressed(MouseEvent e) {
				if(mousePressedHandler != null)
					mousePressedHandler.accept(true);
			}
			@Override public void mouseReleased(MouseEvent e) {
				if(mouseReleasedHandler != null)
					mouseReleasedHandler.accept(true);
			}
		});
		
		slider.addChangeListener(event -> {
			int newValue = slider.getValue();
			if(newValue == value)
				return; // no change
			value = newValue;
			callHandler();
		});
		
	}
	
	public WidgetSlider<T> withTickLabels(int maxTickCount) {
		
		int incrementAmount = 1;
		double range = (max - min + 1);
		while(range / (incrementAmount + 1) > maxTickCount - 1)
			incrementAmount++;
		
		Hashtable<Integer, JLabel> labels = new Hashtable<Integer, JLabel>();
		for(int i = min; i <= max; i += incrementAmount) {
			String text = switch(mode) {
				case INTEGER     -> Integer.toString(i);
				case LOG_INTEGER -> Integer.toString((int) Math.pow(2, i));
				case FLOAT       -> incrementAmount == 10 ? "%d".formatted((int) (i / 10.0)) :
				                                            "%.1f".formatted(i / 10.0);
			};
			labels.put(i, new JLabel("<html><font size=-2>" + text + "</font></html>"));
		}
		slider.setMajorTickSpacing(incrementAmount);
		slider.setPaintTicks(true);
		slider.setPaintLabels(true);
		slider.setLabelTable(labels);
		
		return this;
		
	}
	
	public WidgetSlider<T> setExportLabel(String label) {
		
		importExportLabel = (label == null) ? "" : label;
		return this;
		
	}
	
	public WidgetSlider<T> withEnableCheckbox(boolean useCheckbox, boolean isSelected, String tooltipText) {
		
		if(useCheckbox) {
			checkbox = new JCheckBox("", isSelected);
			checkbox.setBorder(null); // minimize padding around it
			checkbox.setToolTipText(tooltipText);
			checkbox.addActionListener(event -> setChecked(checkbox.isSelected()));
			isSliderEnabled = isSelected;
			slider.setEnabled(isSliderEnabled);
		} else {
			checkbox = null;
			isSliderEnabled = true;
			slider.setEnabled(isSliderEnabled);
		}
		
		return this;
		
	}
	
	public void setChecked(boolean isChecked) {
		
		if(checkbox != null) {
			checkbox.setSelected(isChecked);
			isSliderEnabled = checkbox.isSelected();
			slider.setEnabled(isSliderEnabled);
			callHandler();
		}
		
	}
	
	public WidgetSlider<T> setStepSize(int stepSize) {
		
		if(stepSize != 1) {
			slider.setMinorTickSpacing(stepSize);
			slider.setSnapToTicks(true);
		} else {
			slider.setMinorTickSpacing(0);
			slider.setSnapToTicks(false);
		}
		
		return this;
		
	}
	
	/**
	 * @param valueHandler    Will be called when the slider represents a new value. Can be null.
	 */
	public WidgetSlider<T> onChange(Consumer<T> valueHandler) {
		
		newValueHandler = valueHandler;
		
		// call the value handler, but later, so the calling code can finish constructing things before the handler is triggered
		SwingUtilities.invokeLater(() -> {
			if(!changeHandlerCalled)
				callHandler();
		});
		
		return this;
		
	}
	
	/**
	 * @param valueHandler    Will be called when the slider or checkbox represents a new value. Can be null.
	 */
	public WidgetSlider<T> onChange(BiConsumer<Boolean, T> valuesHandler) {
		
		newValuesHandler = valuesHandler;
		
		// call the value handler, but later, so the calling code can finish constructing things before the handler is triggered
		SwingUtilities.invokeLater(() -> {
			if(!changeHandlerCalled)
				callHandler();
		});
		
		return this;
		
	}
	
	/**
	 * @param dragStartedHandler    Will be called when the mouse is pressed. Can be null.
	 * @param dragEndedHandler      Will be called when the mouse is released. Can be null.
	 */
	public WidgetSlider<T> onDrag(Consumer<Boolean> dragStartedHandler, Consumer<Boolean> dragEndedHandler) {
		
		mousePressedHandler = dragStartedHandler;
		mouseReleasedHandler = dragEndedHandler;
		return this;
		
	}
	
	@Override public void callHandler() {
		
		// if a step size has been defined, don't notify the handler about intermediate values between allowed steps
		if(slider.getMinorTickSpacing() != 0) {
			int stepSize = slider.getMinorTickSpacing();
			int newValue = (int) get();
			int distance = newValue - min;
			if(distance % stepSize != 0)
				return;
		}
		
		if(newValueHandler != null) {
			changeHandlerCalled = true;
			newValueHandler.accept(get());
		}
		
		if(newValuesHandler != null) {
			changeHandlerCalled = true;
			newValuesHandler.accept(isSliderEnabled, get());
		}
		
	}
	
	public void set(T newNumber) {
		
		int number = switch(mode) {
			case INTEGER     -> (int) newNumber;
			case LOG_INTEGER -> (int) (Math.log((int) newNumber) / Math.log(2));
			case FLOAT       -> Math.round((float) newNumber * 10f);
		};
		number = Math.clamp(number, min, max);
		slider.setValue(number);
		
	}
	
	@SuppressWarnings("unchecked")
	public T get() {
		
		return switch(mode) {
			case INTEGER     -> (T) (Integer) value;
			case LOG_INTEGER -> (T) (Integer) (int) Math.pow(2, value);
			case FLOAT       -> (T) (Float)   (value / 10f);
		};
		
	}

	@Override public void appendTo(JPanel panel, String constraints) {
		
		if(prefixLabel == null) {
			panel.add(slider, "");
		} else {
			String cellCount = (checkbox == null) ? "2" : "3";
			panel.add(prefixLabel, "split " + cellCount + (constraints.isEmpty() ? "" : ", " + constraints));
			if(checkbox != null)
				panel.add(checkbox, "");
			panel.add(slider, "growx");
		}

	}

	@Override public WidgetSlider<T> setVisible(boolean isVisible) {
		
		if(prefixLabel != null)
			prefixLabel.setVisible(isVisible);
		if(checkbox != null)
			checkbox.setVisible(isVisible);
		slider.setVisible(isVisible);
		return this;
		
	}

	public WidgetSlider<T> setEnabled(boolean isEnabled) {
		
		if(forcedDisabled)
			return this;
		
		if(prefixLabel != null)
			prefixLabel.setEnabled(isEnabled);
		if(checkbox != null)
			checkbox.setEnabled(isEnabled);
		slider.setEnabled(isEnabled && isSliderEnabled);
		return this;
		
	}
	
	public WidgetSlider<T> forceDisabled(boolean isDisabled) {
		
		if(isDisabled)
			setEnabled(false);
		forcedDisabled = isDisabled;
		if(!isDisabled)
			setEnabled(true);
		return this;
		
	}

	@SuppressWarnings("unchecked")
	@Override public void importFrom(Connections.QueueOfLines lines) throws AssertionError {

		String text = lines.parseString(importExportLabel + " = %s");
		set(switch(mode) {
			case INTEGER     -> (T) (Integer) Integer.parseInt(text);
			case LOG_INTEGER -> (T) (Integer) Integer.parseInt(text);
			case FLOAT       -> (T) (Float)   Float.parseFloat(text);
		});
		
		boolean accepted = get().toString().equals(text);
		if(!accepted)
			throw new AssertionError("Invalid setting for " + importExportLabel + ".\n");
		
		if(checkbox != null) {
			boolean isChecked = lines.parseBoolean(importExportLabel + " enabled = %b");
			setChecked(isChecked);
		}

	}

	@Override public void exportTo(PrintWriter file) {
		
		file.println("\t" + importExportLabel + " = " + get().toString());
		
		if(checkbox != null)
			file.println("\t" + importExportLabel + " enabled = " + isSliderEnabled);

	}

}
