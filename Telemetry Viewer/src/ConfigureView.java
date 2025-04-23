import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JToggleButton;
import javax.swing.border.EmptyBorder;

import net.miginfocom.swing.MigLayout;

@SuppressWarnings("serial")
public class ConfigureView extends JPanel {
	
	static ConfigureView instance = new ConfigureView();
	
	private JPanel widgetsPanel;
	private JPanel buttonsPanel;
	private JScrollPane scrollableRegion;
	
	private Chart activeChart = null;
	private boolean activeChartIsNew = false;
	
	/**
	 * Private constructor to enforce singleton usage.
	 */
	private ConfigureView() {
		
		super();
		
		widgetsPanel = new JPanel();
		widgetsPanel.setLayout(new MigLayout("hidemode 3, wrap 1, insets " + Theme.padding + " " + Theme.padding / 2 + " " + Theme.padding + " " + Theme.padding + ", gapy " + Theme.padding*2, "[fill,grow]"));
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
		if(getSize().height < scrollSize.height + buttonsPanel.getSize().height) {
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
	public void forExistingChart(Chart chart) {
		
		activeChart = chart;
		activeChartIsNew = false;
		
		widgetsPanel.setVisible(false); // hiding during removeAll() massively speeds up removeAll()
		widgetsPanel.removeAll();
		widgetsPanel.setVisible(true);
		
		chart.appendConfigurationWidgets(widgetsPanel);
		
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
	public void forNewChart(Chart chart) {
		
		activeChart = chart;
		activeChartIsNew = true;
		
		JPanel chartTypePanel = new JPanel();
		chartTypePanel.setLayout(new GridLayout(0, 2, Theme.padding, Theme.padding));
		for(Charts.Type chartType : Charts.Type.values()) {
			String typeString = chartType.toString();
			JToggleButton button = new JToggleButton(typeString);
			button.setSelected(typeString.equals(activeChart.toString()));
			button.addActionListener(event -> {
				if(!activeChart.toString().equals(typeString)) {
					int x1 = activeChart.topLeftX;
					int y1 = activeChart.topLeftY;
					int x2 = activeChart.bottomRightX;
					int y2 = activeChart.bottomRightY;
					Charts.remove(activeChart);
					Chart newChart = chartType.createAt(x1, y1, x2, y2);
					instance.forNewChart(newChart);
				}
			});
			chartTypePanel.add(button);
		}
		
		widgetsPanel.setVisible(false); // hiding during removeAll() massively speeds up removeAll()
		widgetsPanel.removeAll();
		widgetsPanel.setVisible(true);
		
		widgetsPanel.add(chartTypePanel);
		chart.appendConfigurationWidgets(widgetsPanel);
		
		JButton cancelButton = new JButton("Cancel");
		cancelButton.addActionListener(event -> { Charts.remove(activeChart); close(); });
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
	public void forDataset(Field dataset) {
		
		activeChart = null;
		
		JButton doneButton = new JButton("Done");
		doneButton.addActionListener(event -> close());
		
		buttonsPanel.removeAll();
		buttonsPanel.add(doneButton,  "growx, cell 2 0");
		
		widgetsPanel.setVisible(false); // hiding during removeAll() massively speeds up removeAll()
		widgetsPanel.removeAll();
		widgetsPanel.setVisible(true);
		
		// important: redefine any onEnter handlers because they may have been written assuming the data structure is still being defined!
		widgetsPanel.add(Theme.newWidgetsPanel(dataset.connection.name.is("Demo Mode") ? "Dataset (Not Editable in Demo Mode)" : "Dataset")
		                      .with(dataset.name.onEnter(event -> close()), "sizegroup 1")
		                      .with(dataset.color,                          "sizegroup 1")
		                      .with(dataset.unit.onEnter(event -> close()), "sizegroup 1")
		                      .getPanel());
		
		scrollableRegion.getVerticalScrollBar().setValue(0);

		instance.setPreferredSize(null);
		instance.revalidate();
		instance.repaint();
		
	}
	
	public void redrawIfUsedFor(Chart chart) {
		
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
	public void closeIfUsedFor(Chart chart) {
		
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
