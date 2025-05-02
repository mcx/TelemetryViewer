import java.awt.Dimension;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.PrintWriter;
import java.util.Hashtable;
import java.util.function.Consumer;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;

public class WidgetSlider<T> implements Widget {
	
	private enum Mode {INTEGER, LOG_INTEGER, FLOAT};
	private final Mode mode;
	
	private String importExportLabel;
	private JLabel prefixLabel;
	private JSlider slider;

	private boolean changeHandlerCalled = false;
	private Consumer<Boolean> mousePressedHandler;
	private Consumer<T> newValueHandler;
	private Consumer<Boolean> mouseReleasedHandler;
	
	// these will always be linear integers
	// conversion to floats or log integers will be done when exposing them to the outside world
	private final int min;
	private final int max;
	private volatile int value;
	
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
	
	@SuppressWarnings("serial")
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
			if(newValueHandler != null) {
				changeHandlerCalled = true;
				newValueHandler.accept(get());
			}
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
	
	/**
	 * @param valueHandler    Will be called when the slider represents a new value. Can be null.
	 */
	public WidgetSlider<T> onChange(Consumer<T> valueHandler) {
		
		newValueHandler = valueHandler;
		
		// call the value handler, but later, so the calling code can finish constructing things before the handler is triggered
		SwingUtilities.invokeLater(() -> {
			if(newValueHandler != null && !changeHandlerCalled) {
				changeHandlerCalled = true;
				newValueHandler.accept(get());
			}
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
	
	public void callHandler() {
		
		if(newValueHandler != null) {
			changeHandlerCalled = true;
			newValueHandler.accept(get());
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
			panel.add(prefixLabel, "split 2");
			panel.add(slider, "growx");
		}

	}

	@Override public WidgetSlider<T> setVisible(boolean isVisible) {
		
		if(prefixLabel != null)
			prefixLabel.setVisible(isVisible);
		slider.setVisible(isVisible);
		return this;
		
	}

	public void setEnabled(boolean isEnabled) {
		
		if(prefixLabel != null)
			prefixLabel.setEnabled(isEnabled);
		slider.setEnabled(isEnabled);
		
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

	}

	@Override public void exportTo(PrintWriter file) {
		
		file.println("\t" + importExportLabel + " = " + get().toString());

	}

}
