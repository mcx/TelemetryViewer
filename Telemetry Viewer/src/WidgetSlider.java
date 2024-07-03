import java.awt.Dimension;
import java.io.PrintWriter;
import java.util.Hashtable;
import java.util.function.Consumer;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;

public class WidgetSlider implements Widget {
	
	private String importExportLabel;
	private JLabel prefixLabel;
	private JSlider slider;
	private volatile int value;
	private boolean isLog2Mode = false;
	private boolean isDividedByTen = false;
	private Consumer<Integer> handler;
	
	@SuppressWarnings("serial")
	public WidgetSlider(String label, int min, int max, int selectedValue) {
		
		importExportLabel = label.toLowerCase();
		
		if(label != null && !label.equals(""))
			prefixLabel = new JLabel(label + ":");
		
		value = selectedValue;
		slider = new JSlider(min, max, selectedValue) {
			@Override public Dimension getPreferredSize() {
				Dimension d = super.getPreferredSize();
				d.width /= 2;
				return d;
			}
		};
		
		Hashtable<Integer, JLabel> labels = new Hashtable<Integer, JLabel>();
		int tickCount = (max - min + 1);
		int incrementAmount = (int) Math.ceil(tickCount / 8.0);
		for(int i = min; i <= max; i += incrementAmount)
			labels.put(i,  new JLabel("<html><font size=-2>" + i + "</font></html>"));
		
		slider.setMajorTickSpacing(1);
		slider.setPaintTicks(true);
		slider.setPaintLabels(true);
		slider.setLabelTable(labels);
		slider.addChangeListener(event -> {
			int newValue = isLog2Mode ? (int) Math.pow(2, slider.getValue()) : slider.getValue();
			if(newValue == value)
				return; // no change
			value = newValue;
			if(handler != null)
				handler.accept(newValue);
		});
		
	}
	
	public WidgetSlider setLog2Mode() {
		
		isLog2Mode = true;
		
		// the slider always works in linear mode, so we need to map between linear/log to provide the illusion
		int log2min   = (int) (Math.log(slider.getMinimum()) / Math.log(2));
		int log2max   = (int) (Math.log(slider.getMaximum()) / Math.log(2));
		int log2value = (int) (Math.log(slider.getValue())   / Math.log(2));
		slider.setMinimum(log2min);
		slider.setMaximum(log2max);
		slider.setValue(log2value);
		
		Hashtable<Integer, JLabel> labels = new Hashtable<Integer, JLabel>();
		int tickCount = (log2max - log2min + 1);
		int incrementAmount = (int) Math.ceil(tickCount / 8.0); // max 8 tick labels, skip some labels if necessary
		for(int i = log2min; i <= log2max; i += incrementAmount)
			labels.put(i,  new JLabel("<html><font size=-2>" + (int) Math.pow(2, i) + "</font></html>"));
		slider.setLabelTable(labels);
		
		return this;
		
	}
	
	public WidgetSlider setDividedByTen() {
		
		isDividedByTen = true;
		
		Hashtable<Integer, JLabel> labels = new Hashtable<Integer, JLabel>();
		int min = slider.getMinimum();
		int max = slider.getMaximum();
		int tickCount = (max - min + 1);
		int incrementAmount = (int) Math.ceil(tickCount / 8.0); // max 8 tick labels, skip some labels if necessary
		for(int i = min; i <= max; i += incrementAmount)
			labels.put(i,  new JLabel("<html><font size=-2>" + ((i < 10)  ? "0." + i :
			                                                    (i == 10) ? "1.0"    :
			                                                    	        (int) (i / 10.0)) + "</font></html>"));
		slider.setLabelTable(labels);
		
		return this;
		
	}
	
	public WidgetSlider setExportLabel(String label) {
		
		importExportLabel = (label == null) ? "" : label;
		return this;
		
	}
	
	/**
	 * @param eventHandler    Will be notified when the slider changes. Can be null.
	 */
	public WidgetSlider onChange(Consumer<Integer> eventHandler) {
		
		handler = eventHandler;
		
		// call the handler, but later, so the calling code can finish constructing things before the handler is triggered
		SwingUtilities.invokeLater(() -> {
			if(handler != null)
				handler.accept(isLog2Mode ? (int) Math.pow(2, slider.getValue()) : slider.getValue());
		});
		
		return this;
		
	}
	
	public void set(int newNumber) {
		
		slider.setValue(isLog2Mode ? (int) (Math.log(newNumber) / Math.log(2)) : newNumber);
		
	}
	
	public int get() {
		
		return value;
		
	}
	
	public float getFloat() {
		
		return isDividedByTen ? (value / 10f) : value;
		
	}

	@Override public void appendTo(JPanel panel, String constraints) {
		
		if(prefixLabel == null) {
			panel.add(slider, "");
		} else {
			panel.add(prefixLabel, "split 2");
			panel.add(slider, "growx");
		}

	}

	@Override public void setVisible(boolean isVisible) {
		
		if(prefixLabel != null)
			prefixLabel.setVisible(isVisible);
		slider.setVisible(isVisible);
		
	}

	@Override public void importFrom(ConnectionsController.QueueOfLines lines) throws AssertionError {

		int number = lines.parseInteger(importExportLabel + " = %d");
		set(number);
		if(get() != number)
			throw new AssertionError("Invalid setting for " + importExportLabel + ".\n");

	}

	@Override public void exportTo(PrintWriter file) {
		
		file.println("\t" + importExportLabel + " = " + get());

	}

}
