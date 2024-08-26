import java.awt.Color;
import java.awt.Dimension;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import net.miginfocom.swing.MigLayout;

/**
 * The SettingsView manages GUI-related settings.
 * Settings can be changed when the user interacts with the GUI or opens a Settings file.
 * This class is the GUI that is optionally shown at the left side of the screen.
 * The transmit GUIs are also drawn here because it's convenient to have them on the left side of the screen.
 */
@SuppressWarnings("serial")
public class SettingsView extends JPanel {
	
	public static SettingsView instance = new SettingsView();
	
	public WidgetCheckbox                 hintsCheckbox;
	public WidgetColorPicker              hintsColorButton;
	public WidgetCheckbox                 warningsCheckbox;
	public WidgetColorPicker              warningsColorButton;
	public WidgetCheckbox                 failuresCheckbox;
	public WidgetColorPicker              failuresColorButton;
	public WidgetCheckbox                 verboseCheckbox;
	public WidgetColorPicker              verboseColorButton;
	public WidgetTextfield<Integer>       tileColumnsTextfield;
	public WidgetTextfield<Integer>       tileRowsTextfield;
	public WidgetComboboxEnum<TimeFormat> timeFormatCombobox;
	public WidgetCheckbox                 timeFormat24hoursCheckbox;
	public WidgetCheckbox                 tooltipsVisibility;
	public WidgetCheckbox                 benchmarkingCheckbox;
	public WidgetSlider                   antialiasingSlider;
	
	public void importFrom(ConnectionsController.QueueOfLines lines) throws AssertionError {
		
		lines.parseExact("GUI Settings:");
		lines.parseExact("");
		
		tileColumnsTextfield.importFrom(lines);
		tileRowsTextfield.importFrom(lines);
		timeFormatCombobox.importFrom(lines);
		timeFormat24hoursCheckbox.importFrom(lines);
		
		hintsCheckbox.importFrom(lines);
		hintsColorButton.importFrom(lines);
		warningsCheckbox.importFrom(lines);
		warningsColorButton.importFrom(lines);
		failuresCheckbox.importFrom(lines);
		failuresColorButton.importFrom(lines);
		verboseCheckbox.importFrom(lines);
		verboseColorButton.importFrom(lines);
		
		tooltipsVisibility.importFrom(lines);
		benchmarkingCheckbox.importFrom(lines);
		antialiasingSlider.importFrom(lines);
		lines.parseExact("");
		
	}
	
	public void exportTo(PrintWriter file) {
		
		file.println("GUI Settings:");
		file.println("");
		
		tileColumnsTextfield.exportTo(file);
		tileRowsTextfield.exportTo(file);
		timeFormatCombobox.exportTo(file);
		timeFormat24hoursCheckbox.exportTo(file);
		
		hintsCheckbox.exportTo(file);
		hintsColorButton.exportTo(file);
		warningsCheckbox.exportTo(file);
		warningsColorButton.exportTo(file);
		failuresCheckbox.exportTo(file);
		failuresColorButton.exportTo(file);
		verboseCheckbox.exportTo(file);
		verboseColorButton.exportTo(file);
		
		tooltipsVisibility.exportTo(file);
		benchmarkingCheckbox.exportTo(file);
		antialiasingSlider.exportTo(file);
		file.println("");
		
	}
	
	public enum TimeFormat {
		TIME_AND_YYYY_MM_DD { @Override public String toString() { return "Time and YYYY-MM-DD"; } },
		TIME_AND_MM_DD_YYYY { @Override public String toString() { return "Time and MM-DD-YYYY"; } },
		TIME_AND_DD_MM_YYYY { @Override public String toString() { return "Time and DD-MM-YYYY"; } },
		ONLY_TIME           { @Override public String toString() { return "Only Time";           } };
	};
	private static SimpleDateFormat timestampFormatterMilliseconds = new SimpleDateFormat("hh:mm:ss.SSS a");
	private static SimpleDateFormat timestampFormatterSeconds      = new SimpleDateFormat("hh:mm:ss a");
	private static SimpleDateFormat timestampFormatterMinutes      = new SimpleDateFormat("hh:mm a");
	
	/**
	 * @param timestamp    The timestamp (milliseconds since 1970-01-01.)
	 * @return             String representation with milliseconds resolution.
	 */
	public static String formatTimestampToMilliseconds(long timestamp) { return timestampFormatterMilliseconds.format(timestamp); }
	
	/**
	 * @param timestamp    The timestamp (milliseconds since 1970-01-01.)
	 * @return             String representation with seconds resolution (no milliseconds.)
	 */
	public static String formatTimestampToSeconds(long timestamp) { return timestampFormatterSeconds.format(timestamp); }
	
	/**
	 * @param timestamp    The timestamp (milliseconds since 1970-01-01.)
	 * @return             String representation with minutes resolution (no seconds or milliseconds.)
	 */
	public static String formatTimestampToMinutes(long timestamp) { return timestampFormatterMinutes.format(timestamp); }
	
	public static boolean isTimeFormatTwoLines() { return !instance.timeFormatCombobox.is(TimeFormat.ONLY_TIME); }
	
	private boolean isVisible = true;
	private JScrollPane scrollablePanel;
	private JPanel panel;
	private List<JPanel> txGuis = new ArrayList<JPanel>();
	
	/**
	 * Private constructor to enforce singleton usage.
	 */
	private SettingsView() {
		
		setLayout(new MigLayout("wrap 1, insets 0, filly")); // 1 column, no border
		panel = new JPanel();
		panel.setLayout(new MigLayout("hidemode 3, wrap 1, insets" + Theme.padding + " " + Theme.padding + " " + Theme.padding + " 0, gapy " + Theme.padding*2, "[fill,grow]"));
		scrollablePanel = new JScrollPane(panel, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scrollablePanel.setBorder(null);
		scrollablePanel.getVerticalScrollBar().setUnitIncrement(10);
		add(scrollablePanel, "grow");
		
		// widgets in the notifications panel
		hintsCheckbox = new WidgetCheckbox("Show Hints", true)
		                    .setExportLabel("show hint notifications")
		                    .onChange(newVisibility -> {
		                        if(!newVisibility)
		                            NotificationsController.getNotifications().removeIf(note -> note.level.equals("hint"));
		                    });
		
		hintsColorButton = new WidgetColorPicker("Hint Notifications", Color.GREEN)
		                       .setIndicateUsedColors(false)
		                       .onEvent(newColor -> {
		                           NotificationsController.getNotifications().forEach(note -> {
		                               if(note.level.equals("hint")) note.glColor = new float[] {newColor.getRed() / 255f, newColor.getGreen() / 255f, newColor.getBlue() / 255f, 0.2f};
		                           });
		                       });
		
		warningsCheckbox = new WidgetCheckbox("Show Warnings", true)
		                       .setExportLabel("show warning notifications")
		                       .onChange(newVisibility -> {
		                           if(!newVisibility)
		                               NotificationsController.getNotifications().removeIf(note -> note.level.equals("warning"));
		                       });
		
		warningsColorButton = new WidgetColorPicker("Warning Notifications", Color.YELLOW)
		                          .setIndicateUsedColors(false)
		                          .onEvent(newColor -> {
		                              NotificationsController.getNotifications().forEach(note -> {
		                                  if(note.level.equals("warning"))
		                                      note.glColor = new float[] {newColor.getRed() / 255f, newColor.getGreen() / 255f, newColor.getBlue() / 255f, 0.2f};
		                              });
		                          });
		
		failuresCheckbox = new WidgetCheckbox("Show Failures", true)
		                       .setExportLabel("show failure notifications")
		                       .onChange(newVisibility -> {
		                           if(!newVisibility)
		                               NotificationsController.getNotifications().removeIf(note -> note.level.equals("failure"));
		                       });
		
		failuresColorButton = new WidgetColorPicker("Failure Notifications", Color.RED)
		                          .setIndicateUsedColors(false)
		                          .onEvent(newColor -> {
		                              NotificationsController.getNotifications().forEach(note -> {
		                                  if(note.level.equals("failure"))
		                                      note.glColor = new float[] {newColor.getRed() / 255f, newColor.getGreen() / 255f, newColor.getBlue() / 255f, 0.2f};
		                              });
		                          });
		
		verboseCheckbox = new WidgetCheckbox("Show Verbose", false)
		                      .setExportLabel("show verbose notifications")
		                      .onChange(newVisibility -> {
		                          if(!newVisibility)
		                              NotificationsController.getNotifications().removeIf(note -> note.level.equals("verbose"));
		                      });
		
		verboseColorButton = new WidgetColorPicker("Verbose Notifications", Color.CYAN)
		                         .setIndicateUsedColors(false)
		                         .onEvent(newColor -> {
		                             NotificationsController.getNotifications().forEach(note -> {
		                                 if(note.level.equals("verbose"))
		                                     note.glColor = new float[] {newColor.getRed() / 255f, newColor.getGreen() / 255f, newColor.getBlue() / 255f, 0.2f};
		                             });
		                         });
		
		// widgets in the charts panel
		tileColumnsTextfield = WidgetTextfield.ofInt(1, 15, 6)
		                                      .setPrefix("Tile Columns")
		                                      .setExportLabel("tile column count")
		                                      .onChange((newNumber, oldNumber) -> {
		                                          boolean chartsObscured = ChartsController.getCharts().stream().anyMatch(chart -> chart.regionOccupied(newNumber, 0, oldNumber, tileRowsTextfield.get()));
		                                          if(chartsObscured) {
		                                              return false;
		                                          } else {
		                                              OpenGLChartsView.instance.updateTileOccupancy(null);
		                                              return true;
		                                          }
		                                      });
		
		tileRowsTextfield = WidgetTextfield.ofInt(1, 15, 6)
		                                   .setPrefix("Tile Rows")
		                                   .setExportLabel("tile row count")
		                                   .onChange((newNumber, oldNumber) -> {
		                                       boolean chartsObscured = ChartsController.getCharts().stream().anyMatch(chart -> chart.regionOccupied(0, newNumber, tileColumnsTextfield.get(), oldNumber));
		                                       if(chartsObscured) {
		                                           return false;
		                                       } else {
		                                           OpenGLChartsView.instance.updateTileOccupancy(null);
		                                           return true;
		                                       }
		                                   });
		
		timeFormatCombobox = new WidgetComboboxEnum<TimeFormat>(TimeFormat.values(), TimeFormat.ONLY_TIME)
		                         .setExportLabel("time format")
		                         .onChange(newFormat -> {
		                             boolean is24hourMode = timeFormat24hoursCheckbox.get();
		                             switch(newFormat) {
		                                 case TIME_AND_YYYY_MM_DD -> {
		                                     timestampFormatterMilliseconds = new SimpleDateFormat(is24hourMode ? "kk:mm:ss.SSS\nyyyy-MM-dd" : "hh:mm:ss.SSS a\nyyyy-MM-dd");
		                                     timestampFormatterSeconds      = new SimpleDateFormat(is24hourMode ? "kk:mm:ss\nyyyy-MM-dd"     : "hh:mm:ss a\nyyyy-MM-dd");
		                                     timestampFormatterMinutes      = new SimpleDateFormat(is24hourMode ? "kk:mm\nyyyy-MM-dd"        : "hh:mm a\nyyyy-MM-dd");
		                                 }
		                                 case TIME_AND_MM_DD_YYYY -> {
		                                     timestampFormatterMilliseconds = new SimpleDateFormat(is24hourMode ? "kk:mm:ss.SSS\nMM-dd-yyyy" : "hh:mm:ss.SSS a\nMM-dd-yyyy");
		                                     timestampFormatterSeconds      = new SimpleDateFormat(is24hourMode ? "kk:mm:ss\nMM-dd-yyyy"     : "hh:mm:ss a\nMM-dd-yyyy");
		                                     timestampFormatterMinutes      = new SimpleDateFormat(is24hourMode ? "kk:mm\nMM-dd-yyyy"        : "hh:mm a\nMM-dd-yyyy");
		                                 }
		                                 case TIME_AND_DD_MM_YYYY -> {
		                                     timestampFormatterMilliseconds = new SimpleDateFormat(is24hourMode ? "kk:mm:ss.SSS\ndd-MM-yyyy" : "hh:mm:ss.SSS a\ndd-MM-yyyy");
		                                     timestampFormatterSeconds      = new SimpleDateFormat(is24hourMode ? "kk:mm:ss\ndd-MM-yyyy"     : "hh:mm:ss a\ndd-MM-yyyy");
		                                     timestampFormatterMinutes      = new SimpleDateFormat(is24hourMode ? "kk:mm\ndd-MM-yyyy"        : "hh:mm a\ndd-MM-yyyy");
		                                 }
		                                 case ONLY_TIME -> {
		                                     timestampFormatterMilliseconds = new SimpleDateFormat(is24hourMode ? "kk:mm:ss.SSS" : "hh:mm:ss.SSS a");
		                                     timestampFormatterSeconds      = new SimpleDateFormat(is24hourMode ? "kk:mm:ss"     : "hh:mm:ss a");
		                                     timestampFormatterMinutes      = new SimpleDateFormat(is24hourMode ? "kk:mm"        : "hh:mm a");
		                                 }
		                             }
		                             return true;
		                         });

		timeFormat24hoursCheckbox = new WidgetCheckbox("Show 24-Hour Time", false)
		                                .setExportLabel("show 24-hour time")
		                                .onChange(newValue -> {
		                                    boolean is24hourMode = newValue;
		                                    TimeFormat format = timeFormatCombobox.get();
		                                    switch(format) {
		                                        case TIME_AND_YYYY_MM_DD -> {
		                                            timestampFormatterMilliseconds = new SimpleDateFormat(is24hourMode ? "kk:mm:ss.SSS\nyyyy-MM-dd" : "hh:mm:ss.SSS a\nyyyy-MM-dd");
		                                            timestampFormatterSeconds      = new SimpleDateFormat(is24hourMode ? "kk:mm:ss\nyyyy-MM-dd"     : "hh:mm:ss a\nyyyy-MM-dd");
		                                            timestampFormatterMinutes      = new SimpleDateFormat(is24hourMode ? "kk:mm\nyyyy-MM-dd"        : "hh:mm a\nyyyy-MM-dd");
		                                        }
		                                        case TIME_AND_MM_DD_YYYY -> {
		                                            timestampFormatterMilliseconds = new SimpleDateFormat(is24hourMode ? "kk:mm:ss.SSS\nMM-dd-yyyy" : "hh:mm:ss.SSS a\nMM-dd-yyyy");
		                                            timestampFormatterSeconds      = new SimpleDateFormat(is24hourMode ? "kk:mm:ss\nMM-dd-yyyy"     : "hh:mm:ss a\nMM-dd-yyyy");
		                                            timestampFormatterMinutes      = new SimpleDateFormat(is24hourMode ? "kk:mm\nMM-dd-yyyy"        : "hh:mm a\nMM-dd-yyyy");
		                                        }
		                                        case TIME_AND_DD_MM_YYYY -> {
		                                            timestampFormatterMilliseconds = new SimpleDateFormat(is24hourMode ? "kk:mm:ss.SSS\ndd-MM-yyyy" : "hh:mm:ss.SSS a\ndd-MM-yyyy");
		                                            timestampFormatterSeconds      = new SimpleDateFormat(is24hourMode ? "kk:mm:ss\ndd-MM-yyyy"     : "hh:mm:ss a\ndd-MM-yyyy");
		                                            timestampFormatterMinutes      = new SimpleDateFormat(is24hourMode ? "kk:mm\ndd-MM-yyyy"        : "hh:mm a\ndd-MM-yyyy");
		                                        }
		                                        case ONLY_TIME -> {
		                                            timestampFormatterMilliseconds = new SimpleDateFormat(is24hourMode ? "kk:mm:ss.SSS" : "hh:mm:ss.SSS a");
		                                            timestampFormatterSeconds      = new SimpleDateFormat(is24hourMode ? "kk:mm:ss"     : "hh:mm:ss a");
		                                            timestampFormatterMinutes      = new SimpleDateFormat(is24hourMode ? "kk:mm"        : "hh:mm a");
		                                        }
		                                    }
		                                });
		
		tooltipsVisibility = new WidgetCheckbox("Show Plot Tooltips", true);
		
		benchmarkingCheckbox = new WidgetCheckbox("Show Benchmarks", false)
		                           .setExportLabel("benchmarking");
		
		antialiasingSlider = new WidgetSlider("Antialiasing", 1, 16, 8) // MSAA levels
		                         .setExportLabel("antialiasing level")
		                         .setLog2Mode()
		                         .onChange(null,
		                                   newLevel -> OpenGLChartsView.regenerate(),
		                                   null);

		// populate with everything except the TX panels
		panel.add(Theme.newWidgetsPanel("Notifications")
		               .with(hintsCheckbox, "split 2, grow x")
		               .with(hintsColorButton)
		               .with(warningsCheckbox, "split 2, grow x")
		               .with(warningsColorButton)
		               .with(failuresCheckbox, "split 2, grow x")
		               .with(failuresColorButton)
		               .with(verboseCheckbox, "split 2, grow x")
		               .with(verboseColorButton)
		               .getPanel());
		
		panel.add(Theme.newWidgetsPanel("Charts")
		               .with(tileColumnsTextfield)
		               .with(tileRowsTextfield)
		               .withGap(Theme.padding)
		               .with(new JLabel("Time Format: "), "split 2")
		               .with(timeFormatCombobox, "grow x")
		               .with(timeFormat24hoursCheckbox)
		               .withGap(Theme.padding)
		               .with(tooltipsVisibility)
		               .with(benchmarkingCheckbox)
		               .with(antialiasingSlider)
		               .getPanel());
		
		// note: setVisible() must be called any time a connection is added/removed/connected/disconnected because it will update the TX panels
		setVisible(false);
		
	}
	
	/**
	 * Shows or hides this panel, and repopulates the panel to ensure everything is in sync.
	 * 
	 * @param visible    True or false.
	 */
	@Override public void setVisible(boolean visible) {
		
		txGuis.forEach(txGui -> panel.remove(txGui));
		txGuis.clear();
		
		// if visible, also repopulate the panel with transmit GUIs
		if(visible)
			ConnectionsController.telemetryConnections.forEach(connection -> {
				JPanel txGui = connection.getUpdatedTransmitGUI();
				if(txGui != null) {
					panel.add(txGui);
					txGuis.add(txGui);
				}
			});
		
		isVisible = visible;
		revalidate();
		repaint();
		
	}
	
	/**
	 * @return    True if the panel is visible.
	 */
	@Override public boolean isVisible() {
		
		return isVisible;
		
	}
	
	/**
	 * Redraws the SettingsView if it is visible. This should be done when any of the following events occur:
	 * 
	 *     When a connection is created/removed
	 *         because the TX GUI will be added/removed
	 *         
	 *     When a connection connects/disconnects
	 *         because the TX GUI becomes enabled/disabled
	 *         
	 *     When a TX packet is bookmarked/un-bookmarked
	 *         because the TX GUI shows the bookmark list
	 */
	public void redraw() {
		
		if(isVisible())
			setVisible(true);
		
	}
	
	/**
	 * Ensures this panel is sized correctly.
	 */
	@Override public Dimension getPreferredSize() {
		
		Dimension scrollSize = panel.getPreferredSize();
		if(getSize().height < scrollSize.height) {
			scrollSize.width += scrollablePanel.getVerticalScrollBar().getPreferredSize().width;
			scrollablePanel.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
		} else {
			scrollablePanel.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
		}
		
		// ignore the height of the TX panels
		txGuis.forEach(gui -> {
			scrollSize.height -= gui.getPreferredSize().height;
			scrollSize.height -= 5*Theme.padding;
		});
		
		// hide if not visible
		if(!isVisible)
			scrollSize.width = 0;
		
		// revalidate if the size changed
		Dimension oldScrollSize = scrollablePanel.getPreferredSize();
		if(!scrollSize.equals(oldScrollSize))
			revalidate();
		
		// apply change
		scrollablePanel.setPreferredSize(scrollSize);
		return super.getPreferredSize();
		
	}

}
