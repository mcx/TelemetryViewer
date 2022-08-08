import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.event.ActionListener;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.border.EmptyBorder;

import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class ConfigureView extends JPanel {
	
	static ConfigureView instance = new ConfigureView();
	
	private JPanel widgetsPanel;
	private JPanel buttonsPanel;
	private JScrollPane scrollableRegion;
	
	private PositionedChart activeChart = null;
	private boolean activeChartIsNew = false;
	
	/**
	 * Private constructor to enforce singleton usage.
	 */
	private ConfigureView() {
		
		super();
		
		widgetsPanel = new JPanel();
		widgetsPanel.setLayout(new MigLayout("hidemode 3, wrap 1, insets " + Theme.padding + " " + Theme.padding / 2 + " " + Theme.padding + " " + Theme.padding + ", gapy " + Theme.padding*3, "[fill,grow]"));
		scrollableRegion = new JScrollPane(widgetsPanel, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scrollableRegion.setBorder(null);
		scrollableRegion.getVerticalScrollBar().setUnitIncrement(10);
		buttonsPanel = new JPanel() {
			@Override public Dimension getPreferredSize() {
				Dimension size = super.getPreferredSize();
				int maxButtonWidth = 0;
				for(Component c : getComponents())
					maxButtonWidth = Math.max(maxButtonWidth, c.getPreferredSize().width);
				size.width = 3 * maxButtonWidth;
				return size;
			}
		};
		buttonsPanel.setLayout(new MigLayout("insets 0", "[33%!][grow][33%!]")); // 3 equal columns
		buttonsPanel.setBorder(new EmptyBorder(Theme.padding * 2, Theme.padding, Theme.padding, Theme.padding)); // extra padding above
		
		setLayout(new MigLayout("wrap 1, insets 0, gapy " + Theme.padding)); // 1 column, no border
		add(scrollableRegion, "growx");
		add(buttonsPanel, "growx");
		
		setPreferredSize(new Dimension(0, 0));
		
	}
	
	/**
	 * Calculate the preferred size of this panel, taking into account the width of the vertical scroll bar.
	 */
	@Override public Dimension getPreferredSize() {
		
		// resize the widgets region if the scrollbar is needed
		Dimension scrollSize = widgetsPanel.getPreferredSize();
		if(getSize().height < scrollSize.height + Theme.padding + buttonsPanel.getSize().height) {
			scrollSize.width += scrollableRegion.getVerticalScrollBar().getPreferredSize().width;
			scrollableRegion.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
		} else {
			scrollableRegion.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
		}
		
		// revalidate if the size changed
		Dimension oldScrollSize = scrollableRegion.getPreferredSize();
		if(!scrollSize.equals(oldScrollSize))
			revalidate();
		
		// apply change
		scrollableRegion.setPreferredSize(scrollSize);
		return super.getPreferredSize();
		
	}
	
	/**
	 * Updates this panel with configuration widgets for an existing chart.
	 * 
	 * @param chart    The chart to configure.
	 */
	public void forExistingChart(PositionedChart chart) {
		
		activeChart = chart;
		activeChartIsNew = false;
		
		widgetsPanel.setVisible(false); // hiding during removeAll() massively speeds up removeAll()
		widgetsPanel.removeAll();
		widgetsPanel.setVisible(true);
		
		chart.getConfigurationGui(widgetsPanel);
		
		JButton doneButton = new JButton("Done");
		doneButton.addActionListener(event -> close());
		buttonsPanel.removeAll();
		buttonsPanel.add(doneButton, "growx, cell 2 0");
		
		scrollableRegion.getVerticalScrollBar().setValue(0);

		instance.setPreferredSize(null);
		instance.revalidate();
		instance.repaint();
		
	}
	
	/**
	 * Updates this panel with configuration widgets for a new chart.
	 * 
	 * @param chart    The new chart.
	 */
	public void forNewChart(PositionedChart chart) {
		
		activeChart = chart;
		activeChartIsNew = true;
		
		ActionListener chartTypeHandler = event -> {
			// replace the chart if a different chart type was selected
			JToggleButton clickedButton = (JToggleButton) event.getSource();
			if(!activeChart.toString().equals(clickedButton.getText())) {
				int x1 = activeChart.topLeftX;
				int y1 = activeChart.topLeftY;
				int x2 = activeChart.bottomRightX;
				int y2 = activeChart.bottomRightY;
				ChartsController.removeChart(activeChart);
				PositionedChart newChart = ChartsController.createAndAddChart(clickedButton.getText(), x1, y1, x2, y2);
				instance.forNewChart(newChart);
			}
		};
		
		JPanel chartTypePanel = new JPanel();
		chartTypePanel.setLayout(new GridLayout(0, 2, Theme.padding, Theme.padding));
		for(String chartType : ChartsController.getChartTypes()) {
			JToggleButton button = new JToggleButton(chartType);
			button.setSelected(button.getText().equals(activeChart.toString()));
			button.addActionListener(chartTypeHandler);
			chartTypePanel.add(button);
		}
		
		widgetsPanel.setVisible(false); // hiding during removeAll() massively speeds up removeAll()
		widgetsPanel.removeAll();
		widgetsPanel.setVisible(true);
		
		widgetsPanel.add(chartTypePanel);
		chart.getConfigurationGui(widgetsPanel);
		
		JButton cancelButton = new JButton("Cancel");
		cancelButton.addActionListener(event -> { ChartsController.removeChart(activeChart); close(); });
		JButton doneButton = new JButton("Done");
		doneButton.addActionListener(event -> close());
		buttonsPanel.removeAll();
		buttonsPanel.add(cancelButton, "growx, cell 0 0");
		buttonsPanel.add(doneButton, "growx, cell 2 0");
		
		scrollableRegion.getVerticalScrollBar().setValue(0);
		
		// size the panel as needed
		instance.setPreferredSize(null);
		instance.revalidate();
		instance.repaint();
		
	}
	
	/**
	 * Updates this panel with configuration widgets for a Dataset.
	 * 
	 * @param dataset    The dataset to configure.
	 */
	public void forDataset(Dataset dataset) {
		
		activeChart = null;
		
		JTextField nameTextfield = new JTextField(dataset.name, 15);
		JButton colorButton = new JButton("\u25B2");
		JTextField unitTextfield = new JTextField(dataset.unit, 15);
		
		JButton cancelButton = new JButton("Cancel");
		cancelButton.addActionListener(event -> close());
		JButton applyButton = new JButton("Apply");
		applyButton.addActionListener(event -> {
			dataset.setNameColorUnit(nameTextfield.getText(), colorButton.getForeground(), unitTextfield.getText());
			close();
		});
		buttonsPanel.removeAll();
		buttonsPanel.add(cancelButton, "growx, cell 0 0");
		buttonsPanel.add(applyButton, "growx, cell 2 0");

		ActionListener pressEnterToApply = event -> applyButton.doClick();
		
		nameTextfield.addActionListener(pressEnterToApply);
		nameTextfield.addFocusListener(new FocusListener() {
			@Override public void focusLost(FocusEvent e)   { nameTextfield.setText(nameTextfield.getText().trim()); }
			@Override public void focusGained(FocusEvent e) { nameTextfield.selectAll(); }
		});
		
		colorButton.setForeground(dataset.color);
		colorButton.addActionListener(event -> colorButton.setForeground(ColorPickerView.getColor(nameTextfield.getText(), colorButton.getForeground(), true)));
		
		unitTextfield.addActionListener(pressEnterToApply);
		unitTextfield.addFocusListener(new FocusListener() {
			@Override public void focusLost(FocusEvent arg0)   { unitTextfield.setText(unitTextfield.getText().trim()); }
			@Override public void focusGained(FocusEvent arg0) { unitTextfield.selectAll(); }
		});
		
		JPanel datasetPanel = Theme.newWidgetsPanel("Dataset");
		datasetPanel.add(new JLabel("Name: "), "split 2, sizegroup 0");
		datasetPanel.add(nameTextfield, "grow, sizegroup 1");
		datasetPanel.add(new JLabel("Color: "), "split 2, sizegroup 0");
		datasetPanel.add(colorButton, "grow, sizegroup 1");
		datasetPanel.add(new JLabel("Unit: "), "split 2, sizegroup 0");
		datasetPanel.add(unitTextfield, "grow, sizegroup 1");
		
		widgetsPanel.removeAll();
		widgetsPanel.add(datasetPanel);
		
		scrollableRegion.getVerticalScrollBar().setValue(0);

		instance.setPreferredSize(null);
		instance.revalidate();
		instance.repaint();
		
	}
	
	public void redrawIfUsedFor(PositionedChart chart) {
		
		if(chart != activeChart || activeChart == null)
			return;
		
		if(activeChartIsNew)
			forNewChart(chart);
		else
			forExistingChart(chart);
		
	}
	
	/**
	 * Closes the configuration view if it is being used for a specific chart.
	 * 
	 * @param chart    The chart.
	 */
	public void closeIfUsedFor(PositionedChart chart) {
		
		if(activeChart == chart)
			close();
		
	}
	
	/**
	 * Closes the configuration view.
	 */
	public void close() {
		
		activeChart = null;
		instance.setPreferredSize(new Dimension(0, 0));
		instance.revalidate();
		instance.repaint();
		
	}

}
