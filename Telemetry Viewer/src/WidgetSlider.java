import java.awt.Dimension;
import java.util.List;
import java.util.Queue;
import java.util.function.Consumer;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingConstants;

public class WidgetSlider implements Widget {
	
	String importExportLabel;
	JLabel prefixLabel;
	JSlider slider;
	JLabel valueLabel;
	int valueLabelWidth;
	
	@SuppressWarnings("serial")
	public WidgetSlider(String label, String importExportText, String suffix, int min, int max, int selectedValue, Consumer<Integer> handler) {
		
		importExportLabel = importExportText;
		
		if(label != null && !label.equals(""))
			prefixLabel = new JLabel(label + ": ");
		
		slider = new JSlider(min, max, selectedValue) {
			@Override public Dimension getPreferredSize() {
				Dimension d = super.getPreferredSize();
				d.width /= 2;
				return d;
			}
		};
		slider.setMajorTickSpacing(10);
		slider.setPaintTicks(true);
		slider.addChangeListener(event -> {
			int newNumber = slider.getValue();
			valueLabel.setText(Integer.toString(newNumber) + (suffix == null ? "" : suffix));
			handler.accept(newNumber);
		});
		
		valueLabel = new JLabel(Integer.toString(selectedValue) + (suffix == null ? "" : suffix), SwingConstants.RIGHT);
		valueLabelWidth = Integer.max(new JLabel(Integer.toString(min) + (suffix == null ? "" : suffix)).getPreferredSize().width,
		                              new JLabel(Integer.toString(max) + (suffix == null ? "" : suffix)).getPreferredSize().width);

		handler.accept(slider.getValue());
		
	}
	
	public void setNumber(int newNumber) {
		
		slider.setValue(newNumber);
		
	}

	@Override public void appendToGui(JPanel gui) {
		
		if(prefixLabel == null) {
			gui.add(slider, "split 2, growx");
			gui.add(valueLabel, "width " + valueLabelWidth);
		} else {
			gui.add(prefixLabel, "split 3");
			gui.add(slider, "growx");
			gui.add(valueLabel, "width " + valueLabelWidth);
		}

	}

	@Override public void setVisible(boolean isVisible) {
		
		if(prefixLabel != null)
			prefixLabel.setVisible(isVisible);
		slider.setVisible(isVisible);
		valueLabel.setVisible(isVisible);
		
	}

	@Override public void importFrom(Queue<String> lines) {

		int number = ChartUtils.parseInteger(lines.remove(), importExportLabel + " = %d");
		setNumber(number);
		if(slider.getValue() != number)
			throw new AssertionError("Invalid setting for " + importExportLabel + ".\n");

	}

	@Override public void exportTo(List<String> lines) {
		
		lines.add(importExportLabel + " = " + slider.getValue());

	}

}
