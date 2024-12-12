import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.io.PrintWriter;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.Border;
import javax.swing.border.EmptyBorder;

import net.miginfocom.swing.MigLayout;

/**
 * Manages everything about one CSV column or Binary packet field.
 * The Field could be a sync word, a dataset, or a checksum.
 * Sync words and checksums are optional, and only supported in Binary mode.
 * The optional sync word must be at the beginning, and the optional checksum must be at the end.
 * 
 *     1. The Field can be configured via the GUI by using the Widgets defined below (location, type, name, ...)
 *     2. The Field can be configured programmatically by using the methods defined below (setLocation(), setType(), setName(), ...)
 *     3. After the Field has been configured, it can be inserted into the data structure by calling the insert() method below.
 */
public class Field implements Comparable<Field> {
	
	final ConnectionTelemetry connection;
	StorageFloats floats;
	
	float conversionFactor;
	boolean isBitfield = false;
	final List<Bitfield> bitfields = new ArrayList<Bitfield>();
	
	// GUI widgets
	WidgetTextfield<Integer> location;
	WidgetCombobox<Type> type;
	WidgetTextfield<String> name;
	WidgetColorPicker color;
	WidgetTextfield<String> unit;
	WidgetTextfield<Float> scalingFactorA;
	JLabel equalsLabel = new JLabel("=");
	WidgetTextfield<Float> scalingFactorB;
	JButton addButton = new JButton("Add");
	JButton doneButton = new JButton("Done");
	Consumer<String> insertHandler;
	
	public Field(ConnectionTelemetry connection) {
		
		this.connection = connection;
		
		boolean isCsvMode = connection.protocol.is(ConnectionTelemetry.Protocol.CSV);
		
		location = WidgetTextfield.ofInt(0, Integer.MAX_VALUE, connection.getFirstAvailableLocation(), -1, "[Full]")
		                          .setPrefix(isCsvMode ? "Column" : "Byte Offset")
		                          .setExportLabel("dataset location")
		                          .setFixedWidth(12)
		                          .onEnter(event -> addButton.doClick())
		                          .onChange((newOffset, oldOffset) -> {
		                              // disable widgets as needed
		                              boolean full = (newOffset == -1) || (connection instanceof ConnectionTelemetryDemo);
		                              location.setEnabled(!full);
		                              type.setEnabled(!full);
		                              name.setEnabled(!full);
		                              color.setEnabled(!full);
		                              unit.setEnabled(!full);
		                              scalingFactorA.setEnabled(!full);
		                              scalingFactorB.setEnabled(!full);
		                              addButton.setEnabled(!full);
		                              if(full) {
		                                  // full, so highlight the done button
		                                  doneButton.requestFocus();
		                                  return true;
		                              } else if(connection.isFieldAllowed(this, newOffset, Type.UINT8) != null) {
		                                  // new location can't even fit 1 byte, so reject the change
		                                  NotificationsController.printDebugMessageAndBeep(connection.getClass().getName() + " rejected field location \"" + newOffset + "\" because it is already occupied.");
		                                  return false;
		                              } else {
		                                  // new location has >= 1 byte available, so update the list of appropriate data types
		                                  type.setDisabledValues(Stream.of(Type.values())
		                                                               .filter(field -> connection.isFieldAllowed(this, newOffset, field) != null)
		                                                               .collect(Collectors.toMap(field -> field,
		                                                                                         field -> connection.isFieldAllowed(this, newOffset, field))));
		                                  return true;
		                              }
		                          });
		
		type = new WidgetCombobox<Type>(null, Arrays.asList(Type.values()), Type.values()[1])
		           .setExportLabel("binary processor")
		           .onChange((newDatatype, oldDatatype) -> {
		               if(connection.isFieldAllowed(this, location.get(), newDatatype) != null)
		                   return false; // not allowed
		               boolean isSyncWord = newDatatype.isSyncWord();
		               boolean isChecksum = newDatatype.isChecksum();
		               boolean isBitfield = newDatatype.toString().contains("Bitfield");
		               // configure the other widgets as needed
		               name.setVisible(!isChecksum);
		               name.setPrefix(isSyncWord ? "Value" : "Name");
		               name.setExportLabel(isSyncWord ? "value" : "name");
		               color.setVisible(!isSyncWord && !isChecksum);
		               unit.setVisible(!isSyncWord && !isChecksum && !isBitfield);
		               scalingFactorA.setVisible(!isSyncWord && !isChecksum && !isBitfield);
		               equalsLabel.setVisible(!isSyncWord && !isChecksum && !isBitfield);
		               scalingFactorB.setVisible(!isSyncWord && !isChecksum && !isBitfield);
		               // if changing to a sync word, set name to "0xAA" if name is not already a hex number
		               if(isSyncWord) {
		                   try {
		                       String text = name.get();
		                       if(!text.toLowerCase().startsWith("0x") || text.length() != 4)
		                           throw new NumberFormatException();
		                       Integer.parseInt(text.substring(2), 16);
		                   } catch(NumberFormatException e) {
		                       name.set("0xAA");
		                   }
		               }
		               // if changing away from a sync word, clear the name
		               if(isSyncWord() && !isSyncWord)
		                   SwingUtilities.invokeLater(() -> { // invokeLater so the datatype can change before the name event handler gets called
		                       if(name.get().toLowerCase().startsWith("0x"))
		                           name.set("");
		               });
		               // if changing to a bitfield, reset the scaling factors to 1 and clear the unit
		               if(isBitfield) {
		            	   scalingFactorA.set(1f);
		            	   scalingFactorB.set(1f);
		            	   unit.set("");
		               }
		               return true;
		           });
		
		name = WidgetTextfield.ofText("")
		                      .setPrefix("Name")
		                      .setExportLabel("name")
		                      .setFixedWidth(22)
		                      .onEnter(event -> addButton.doClick())
		                      .onChange((newText, oldText) -> {
		                          // the field name must be unique within this ConnectionTelemetry, but that will be enforced when clicking Add (to be less annoying when defining the data structure)
		                          if(isSyncWord()) {
		                              try {
		                                  if(!newText.toLowerCase().startsWith("0x") || newText.length() > 4 || newText.length() < 3)
		                                      throw new NumberFormatException();
		                                  int number = Integer.parseInt(newText.substring(2), 16);
		                                  name.set("0x%02X".formatted(number)); // ensure hex number is capitalized and two digits
		                              } catch(NumberFormatException e) {
		                                  name.set("0xAA");
		                              }
		                          }
		                          return true;
		                      });
		
		color = new WidgetColorPicker("the Dataset", Theme.defaultDatasetColor)
		            .setExportLabel("color");
		
		unit = WidgetTextfield.ofText("")
		                      .setPrefix("Unit")
		                      .setExportLabel("unit")
		                      .setFixedWidth(12)
		                      .onChange((newText, oldText) -> {
		                          scalingFactorB.setSuffix(newText);
		                          return true;
		                      })
		                      .onIncompleteChange(text -> scalingFactorB.setSuffix(text))
		                      .onEnter(event -> addButton.doClick());
		
		scalingFactorA = WidgetTextfield.ofFloat(-Float.MAX_VALUE, Float.MAX_VALUE, 1)
		                                .setPrefix("Scaling")
		                                .setExportLabel("conversion factor a")
		                                .setFixedWidth(12)
		                                .onChange((newNumber, oldNumber) -> {
		                                    if(newNumber.equals(0f))
		                                        scalingFactorA.set(1f);
		                                    else
		                                        conversionFactor = scalingFactorB.get() / newNumber;
		                                    return true;
		                                })
		                                .onEnter(event -> addButton.doClick());
		
		scalingFactorB = WidgetTextfield.ofFloat(-Float.MAX_VALUE, Float.MAX_VALUE, 1)
		                                .setExportLabel("conversion factor b")
		                                .setFixedWidth(8)
		                                .onChange((newNumber, oldNumber) -> {
		                                    if(newNumber.equals(0f))
		                                        scalingFactorB.set(1f);
		                                    else
		                                        conversionFactor = newNumber / scalingFactorA.get();
		                                    return true;
		                                })
		                                .onEnter(event -> addButton.doClick());
		
		addButton.addActionListener(event -> {
			// the field name must be unique within this connection
			List<String> usedNames = connection.getDatasetsList().stream().map(field -> field.name.get()).toList();
			if(usedNames.contains(name.get())) {
				if(insertHandler != null)
					insertHandler.accept("The dataset name must be unique.");
				return;
			}
			
			if(!type.get().toString().contains("Bitfield")) {
				// not a bitfield, so just insert it
				insert();
			} else {
				// bitfield, so disable the Field Widgets and populate the scrollableRegion with a BitfieldPanel
				if(name.get().trim().isEmpty() && insertHandler != null) {
					insertHandler.accept("A dataset name is required.");
					return;
				}
				
				location.setEnabled(false);
				type.setEnabled(false);
				name.setEnabled(false);
				color.setEnabled(false);
				addButton.setEnabled(false);
				doneButton.setEnabled(false);
				
				Border oldBorder = connection.scrollableRegion.getBorder();
				connection.scrollableRegion.setBorder(null);
				
				connection.scrollableRegion.setViewportView(new BitfieldPanel(type.get().getByteCount() * 8,
				                                                              () -> {
				                                                            	  insert();
				                                                            	  connection.scrollableRegion.setBorder(oldBorder);
				                                                              }));
				
				return;
			}
		});
		
		doneButton.addActionListener(event -> {
			if(connection.getDatasetCount() == 0) {
				JOptionPane.showMessageDialog(null, "Define at least one dataset, or disconnect.", "Error", JOptionPane.ERROR_MESSAGE);
			} else {
				connection.setFieldsDefined(true);
				if(ChartsController.getCharts().isEmpty())
					NotificationsController.showHintUntil("Add a chart by clicking on a tile, or click-and-dragging across multiple tiles.", () -> !ChartsController.getCharts().isEmpty(), true);
				Main.hideConfigurationGui();
			}
		});
		
	}
	
	@Override public int compareTo(Field other) {
		// hopefully no connection has a packet size >10,000 bytes (>80,000 bits)
		int thisBitOffset  = (ConnectionsController.telemetryConnections.indexOf(this.connection)  * 80000) + ( this.location.get() * 8);
		int otherBitOffset = (ConnectionsController.telemetryConnections.indexOf(other.connection) * 80000) + (other.location.get() * 8);
		return thisBitOffset - otherBitOffset;
	}
	
	public Field onInsert(Consumer<String> handler) {
		insertHandler = handler;
		return this;
	}
	
	public String insert() {
		
		if(isDataset())
			floats = new StorageFloats(connection);
		String errorMessage = connection.insertField(this);
		if(errorMessage != null) {
			floats.dispose();
			floats = null;
			if(insertHandler != null)
				insertHandler.accept(errorMessage);
			return errorMessage;
		} else {
			if(insertHandler != null)
				insertHandler.accept(null);
			return null;
		}
		
	}
	
	public Field setLocation(int newLocation) {
		location.set(newLocation);
		return this;
	}
	
	public Field setType(Type newType) {
		type.set(newType);
		return this;
	}
	
	public Field setName(String newName) {
		name.set(newName);
		return this;
	}
	
	public Field setColor(Color newColor) {
		color.set(newColor);
		return this;
	}
	
	public Field setUnit(String newUnit) {
		unit.set(newUnit);
		return this;
	}
	
	public Field setScalingFactors(float newA, float newB) {
		scalingFactorA.set(newA);
		scalingFactorB.set(newB);
		return this;
	}
	
	public boolean isSyncWord() { return type.get().isSyncWord(); }
	public boolean isDataset()  { return type.get().isDataset();  }
	public boolean isChecksum() { return type.get().isChecksum(); }
	
	public void exportTo(PrintWriter file) {
		
		file.println("");
		file.print('\t'); location.exportTo(file);
		file.print('\t'); type.exportTo(file);
		if(type.get().isSyncWord()) {
			file.print('\t'); name.exportTo(file);
		} else if(type.get().isDataset()) {
			file.print('\t'); name.exportTo(file);
			file.print('\t'); color.exportTo(file);
			file.print('\t'); unit.exportTo(file);
			file.print('\t'); scalingFactorA.exportTo(file);
			file.print('\t'); scalingFactorB.exportTo(file);
			if(type.get().toString().endsWith("Bitfield"))
				bitfields.forEach(bitfield -> {
					file.print("\t\t[" + bitfield.MSBit + ":" + bitfield.LSBit + "] = " + String.format("0x%02X%02X%02X ", bitfield.states[0].color.getRed(), bitfield.states[0].color.getGreen(), bitfield.states[0].color.getBlue()) + bitfield.states[0].name);
					for(int i = 1; i < bitfield.states.length; i++)
						file.print("," + String.format("0x%02X%02X%02X ", bitfield.states[i].color.getRed(), bitfield.states[i].color.getGreen(), bitfield.states[i].color.getBlue()) + bitfield.states[i].name);
					file.println();
				});
		}
		
	}
	
	public void importFrom(ConnectionsController.QueueOfLines lines) throws AssertionError {
		
		lines.parseExact("");
		location.importFrom(lines);
		type.importFrom(lines);
		if(type.get().isSyncWord()) {
			name.importFrom(lines);
		} else if(type.get().isDataset()) {
			name.importFrom(lines);
			color.importFrom(lines);
			unit.importFrom(lines);
			scalingFactorA.importFrom(lines);
			scalingFactorB.importFrom(lines);
			if(type.get().toString().endsWith("Bitfield")) {
				while(!lines.peek().equals("")){
					try {
						String line = lines.remove();
						String bitNumbers = line.split(" ")[0];
						String[] stateNamesAndColors = line.substring(bitNumbers.length() + 3).split(","); // skip past "[n:n] = "
						bitNumbers = bitNumbers.substring(1, bitNumbers.length() - 1); // remove [ and ]
						int MSBit = Integer.parseInt(bitNumbers.split(":")[0]);
						int LSBit = Integer.parseInt(bitNumbers.split(":")[1]);
						Field.Bitfield bitfield = addBitfield(MSBit, LSBit);
						for(int stateN = 0; stateN < stateNamesAndColors.length; stateN++) {
							Color c = new Color(Integer.parseInt(stateNamesAndColors[stateN].split(" ")[0].substring(2), 16));
							String n = stateNamesAndColors[stateN].substring(9);
							bitfield.states[stateN].color = c;
							bitfield.states[stateN].glColor = new float[] {c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, 1};
							bitfield.states[stateN].name = n;
						}
					} catch(Exception e) {
						throw new AssertionError("Line does not specify a bitfield range.");
					}
				}
			}
		}
		
		insert();
		
	}
	
	/**
	 * Configures this Dataset to store Bitfields, and appends a new Bitfield object to it.
	 * 
	 * @param MSBit    Most-significant-bit occupied by the Bitfield.
	 * @param LSBit    Least-significant-bit occupied by the Bitfield.
	 * @return         The new Bitfield object.
	 */
	public Bitfield addBitfield(int MSBit, int LSBit) {
		
		isBitfield = true;
		Bitfield bitfield = new Bitfield(MSBit, LSBit);
		bitfields.add(bitfield);
		Collections.sort(bitfields); // sort the list so it can be easily drawn in order by the GUI
		return bitfield;
		
	}
	
	/**
	 * @return    List of Bitfields.
	 */
	public List<Bitfield> getBitfields() {
		
		return bitfields;
		
	}
	
	/**
	 * @return    A user-friendly String describing this Field.
	 *            This will be shown to the user by WidgetDatasets, and will also be used when exporting/importing.
	 *            Therefore this String *must* uniquely identify this field among *every* Field of *every* ConnectionTelemetry!
	 */
	@Override public String toString() {
		
		boolean oneConnection = ConnectionsController.telemetryConnections.size() == 1;
		return oneConnection ? name.get() : "[" + connection.toString() + "] " + name.get();
		
		
	}
	
	/**
	 * @return    A place to cache samples.
	 */
	public StorageFloats.Cache createCache() {
		
		return floats.createCache();
		
	}
	
	/**
	 * Gets one sample, as a float.
	 * 
	 * @param sampleNumber    Sample number to obtain.
	 * @param cache           Place to cache samples.
	 * @return                The sample, as a float.
	 */
	public float getSample(int sampleNumber, StorageFloats.Cache cache) {
		
		return floats.getSample(sampleNumber, cache);
		
	}
	
	/**
	 * Gets one sample, as a String.
	 * 
	 * @param sampleNumber    Sample number to obtain.
	 * @param cache           Place to cache samples.
	 * @return                The sample, formatted as a String.
	 */
	public String getSampleAsString(int sampleNumber, StorageFloats.Cache cache) {
		
		float value = getSample(sampleNumber, cache);
		
		if(isBitfield)
			return "0b" + String.format("%8s", Integer.toBinaryString((int) value)).replace(' ', '0');
		else
			return ChartUtils.formattedNumber(value, 5) + " " + unit.get();
		
	}
	
	/**
	 * Gets a sequence of samples, as a FloatBuffer.
	 * 
	 * @param firstSampleNumber    First sample number to obtain, inclusive.
	 * @param lastSampleNumber     Last sample number to obtain, inclusive.
	 * @param cache                Place to cache samples.
	 * @return                     The samples, as a FloatBuffer, positioned at the first sample number.
	 */
	public FloatBuffer getSamplesBuffer(int firstSampleNumber, int lastSampleNumber, StorageFloats.Cache cache) {
		
		return floats.getSamplesBuffer(firstSampleNumber, lastSampleNumber, cache);
		
	}
	
	/**
	 * Gets a sequence of samples, as a float[].
	 * 
	 * @param firstSampleNumber    First sample number to obtain, inclusive.
	 * @param lastSampleNumber     Last sample number to obtain, inclusive.
	 * @param cache                Place to cache samples.
	 * @return                     The samples, as a float[].
	 */
	public float[] getSamplesArray(int firstSampleNumber, int lastSampleNumber, StorageFloats.Cache cache) {
		
		float[] array = new float[lastSampleNumber - firstSampleNumber + 1];
		floats.getSamplesBuffer(firstSampleNumber, lastSampleNumber, cache).get(array);
		return array;
		
	}
	
	/**
	 * Converts and appends a new sample to the dataset.
	 * 
	 * @param sampleNumber    Which sample number to populate.
	 * @param value           New sample to be converted and then written into the dataset.
	 */
	public void setSample(int sampleNumber, float value) {
		
		setConvertedSample(sampleNumber, value * conversionFactor);
		
	}
	
	/**
	 * Appends a new sample to the dataset.
	 * 
	 * @param sampleNumber    Which sample number to populate.
	 * @param value           New sample to be written into the dataset. It will be written as-is, NOT converted.
	 */
	public void setConvertedSample(int sampleNumber, float value) {
		
		floats.setValue(sampleNumber, value);
		
	}
	
	/**
	 * Obtains the samples buffer so that multiple Parser threads may write directly into it (in parallel.)
	 * 
	 * @param sampleNumber    The sample number whose buffer is wanted.
	 * @return                Corresponding buffer.
	 */
	public synchronized float[] getSlot(int sampleNumber) {
		
		return floats.getSlot(sampleNumber);
		
	}
	
	/**
	 * Specifies the minimum and maximum values found in a block.
	 * This method must be called AFTER any Parser threads have populated a block, but BEFORE the sample count has been incremented.
	 * 
	 * @param firstSampleNumber    First sample number of the block.
	 * @param minValue             Minimum value in the block.
	 * @param maxValue             Maximum value in the block.
	 */
	public synchronized void setRangeOfBlock(int firstSampleNumber, float minValue, float maxValue) {
		
		floats.setRangeOfBlock(firstSampleNumber, minValue, maxValue);
		
	}
	
	/**
	 * Gets the minimum and maximum of a sequence of samples.
	 * 
	 * @param firstSampleNumber    First sample number to consider, inclusive.
	 * @param lastSampleNumber     Last sample number to consider, inclusive.
	 * @param cache                Place to cache samples.
	 * @return                     A MinMax object, which has "min" and "max" fields.
	 */
	public StorageFloats.MinMax getRange(int firstSampleNumber, int lastSampleNumber, StorageFloats.Cache cache) {
	
		return floats.getRange(firstSampleNumber, lastSampleNumber, cache);
		
	}
	
	/**
	 * Describes one bitfield, which has 2^n states.
	 * Each Dataset can contain zero or more Bitfields.
	 */
	public class Bitfield implements Comparable<Bitfield> {
		
		final int MSBit;
		final int LSBit;
		final int bitmask; // (raw dataset value >> LSBit) & bitmask = bitfield state
		final State[] states;
		final Field dataset;
		
		public Bitfield(int MSBit, int LSBit) {
			
			this.MSBit = MSBit;
			this.LSBit = LSBit;
			
			int statesCount = (int) Math.pow(2, MSBit - LSBit + 1);
			bitmask = statesCount - 1;
			states = new State[statesCount];
			for(int i = 0; i < statesCount; i++)
				states[i] = new State(i, (MSBit != LSBit) ? "Bits [" + MSBit + ":" + LSBit + "] = " + i :
				                                            "Bit " + MSBit + " = " + i);
			
			dataset = Field.this;
			
		}
		
		/**
		 * @param sampleNumber    Sample number.
		 * @param cache           Place to cache samples.
		 * @return                State of this bitfield at the specified sample number.
		 */
		int getStateAt(int sampleNumber, StorageFloats.Cache cache) {
			int value = (int) Field.this.getSample(sampleNumber, cache);
			int state = (value >> LSBit) & bitmask;
			return state;
		}
		
		public record LevelRange(int startingSampleNumber, long startingTimestamp, int endingSampleNumber, long endingTimestamp) {}

		/**
		 * Checks the Bitfields in this Field to see which edges and levels were active.
		 * It is assumed the Maps may contain cached data. In that case, it is assumed the States have not changed. Regenerate the Maps if the enabled States have changed!
		 * 
		 * @param minSampleNumber    First sample number to check, inclusive.
		 * @param maxSampleNumber    Last sample number to check, inclusive.
		 * @param sampleCountMode    If true, the Tooltips will show their sample number. If false, the Tooltips will show their sample number and time.
		 * @param edgeTooltips       A Map where the keys are sample numbers, and the values are the corresponding Tooltips to draw on screen.
		 * @param levels             A Map where the keys are Bitfield States, and the values are the corresponding details for any level events to draw on screen.
		 * @param di                 Interface to obtain the samples from.
		 */
		void getEdgesAndLevelsBetween(int minSampleNumber, int maxSampleNumber, boolean sampleCountMode, Map<Integer, PositionedChart.Tooltip> edgeTooltips, Map<State, List<LevelRange>> levels, DatasetsInterface di) {
			
			// sanity checks
			if(minSampleNumber < 0)
				return;
			if(minSampleNumber >= maxSampleNumber)
				return;
			
			// get the samples
			FloatBuffer buffer = di.getSamplesBuffer(dataset, minSampleNumber, maxSampleNumber);
			LongBuffer tbuffer = di.getTimestampsBuffer(minSampleNumber, maxSampleNumber);
			int stateN = ((int) buffer.get() >> LSBit) & bitmask;
			int startingSampleNumber = minSampleNumber;
			long startingTimestamp = tbuffer.get();
			
			// if the levels Map contains the starting state and it ended at minSampleNumber, we should update that level
			boolean updateExistingLevel = levels.containsKey(states[stateN]) &&
			                              !levels.get(states[stateN]).isEmpty() &&
			                              levels.get(states[stateN]).getLast().endingSampleNumber == minSampleNumber;
			
			// test the samples and update the Maps
			int sampleNumber = 0;
			long timestamp = 0;
			for(sampleNumber = minSampleNumber + 1; sampleNumber <= maxSampleNumber; sampleNumber++) {
				int state = ((int) buffer.get() >> LSBit) & bitmask;
				timestamp = tbuffer.get();
				if(state != stateN) {
					if(levels.containsKey(states[stateN]))
						if(updateExistingLevel) {
							int i = levels.get(states[stateN]).size() - 1;
							LevelRange oldLevel = levels.get(states[stateN]).get(i);
							LevelRange updatedLevel = new LevelRange(oldLevel.startingSampleNumber, oldLevel.startingTimestamp, sampleNumber, timestamp);
							levels.get(states[stateN]).set(i, updatedLevel);
							updateExistingLevel = false;
						} else {
							levels.get(states[stateN]).add(new LevelRange(startingSampleNumber, startingTimestamp, sampleNumber, timestamp));
						}
					
					stateN = state;
					startingSampleNumber = sampleNumber;
					startingTimestamp = timestamp;
					if(di.edgeStates.contains(states[stateN])) {
						if(edgeTooltips.containsKey(sampleNumber)) {
							edgeTooltips.get(sampleNumber).addRow(states[stateN].glColor, states[stateN].name);
						} else {
							edgeTooltips.put(sampleNumber, new PositionedChart.Tooltip(sampleNumber, timestamp)
							                                   .addRow(sampleCountMode ? "Sample " + sampleNumber : "Sample " + sampleNumber + "\n" + SettingsView.formatTimestampToMilliseconds(timestamp))
							                                   .addRow(states[stateN].glColor, states[stateN].name));
						}
					}
				}
			}
			
			if(levels.containsKey(states[stateN])) {
				sampleNumber--; // undo the last sampleNumber++ in the above for() loop
				if(updateExistingLevel) {
					int i = levels.get(states[stateN]).size() - 1;
					LevelRange oldLevel = levels.get(states[stateN]).get(i);
					LevelRange updatedLevel = new LevelRange(oldLevel.startingSampleNumber, oldLevel.startingTimestamp, sampleNumber, timestamp);
					levels.get(states[stateN]).set(i, updatedLevel);
					updateExistingLevel = false;
				} else {
					levels.get(states[stateN]).add(new LevelRange(startingSampleNumber, startingTimestamp, sampleNumber, timestamp));
				}
			}
			
		}

		/**
		 * For sorting a Collection of Bitfields so the fields occupying less-significant bits come first.
		 */
		@Override public int compareTo(Bitfield other) {
			// hopefully no connection has a packet size >10,000 bytes (>80,000 bits)
			int thisBitOffset  = (ConnectionsController.telemetryConnections.indexOf(this.dataset.connection)  * 80000) + ( this.dataset.location.get() * 8) +  this.LSBit;
			int otherBitOffset = (ConnectionsController.telemetryConnections.indexOf(other.dataset.connection) * 80000) + (other.dataset.location.get() * 8) + other.LSBit;
			return thisBitOffset - otherBitOffset;
		}
		
		/**
		 * Describes one possible state (value) of the Bitfield.
		 */
		public class State implements Comparable<State> {
			
			String label;                   // Example: "Bit 7 = 1" (shown in the BitfieldPanel.Visualization)
			int value;                      // Example: "1"
			String name;                    // Example: "Some Fault Occurred" (shown on markers on the charts)
			Color color;                    // shown in the PacketBinary.BitfieldPanel
			float[] glColor;                // shown on markers on the charts
			ConnectionTelemetry connection; // owner of this State
			Field dataset;                  // owner of this State
			Bitfield bitfield;              // owner of this State
			
			List<Integer> edgesCache = new ArrayList<Integer>(); // cache of the sample numbers for each transition to this state
			int lastSampleNumberInCache = -1;
			
			public State(int value, String label) {
				this.label = label;
				this.value = value;
				this.name = "";
				this.color = Field.this.color.get();
				this.glColor = Field.this.color.getGl();
				connection = Field.this.connection;
				dataset = Field.this;
				bitfield = Bitfield.this;
			}
			
			@Override public String toString() {
				return "connection " + ConnectionsController.allConnections.indexOf(dataset.connection) + " location " + Field.this.location.get() + " [" + Bitfield.this.MSBit + ":" + Bitfield.this.LSBit + "] = " + value;
			}
			
			/**
			 * For sorting a collections of States so earlier datasets come first, and smaller values come first.
			 */
			@Override public int compareTo(State other) {
				// hopefully no connection has a packet size >10,000 bytes (>80,000 bits)
				int thisBitOffset  = (ConnectionsController.telemetryConnections.indexOf(this.dataset.connection)  * 80000) + ( this.dataset.location.get() * 8) +  this.bitfield.LSBit;
				int otherBitOffset = (ConnectionsController.telemetryConnections.indexOf(other.dataset.connection) * 80000) + (other.dataset.location.get() * 8) + other.bitfield.LSBit;
				
				// with 8-bit bitfields, every bitfield value will be <=255
				int thisValue  = (thisBitOffset  * 8) +  this.value;
				int otherValue = (otherBitOffset * 8) + other.value;
				return thisValue - otherValue;
			}
			
		}

	}
	
	enum Type {
		UINT8_SYNC_WORD { @Override public String toString()                        { return "uint8 Sync Word";                  }
		                  @Override public String getJavaTypeName()                 { return "Byte";                             }
		                  @Override public boolean isLittleEndian()                 { return true;                               }
		                  @Override public int getByteCount()                       { return 1;                                  }
		                  @Override public boolean isSyncWord()                     { return true;                               }
		                  @Override public boolean testSyncWord(byte[] buffer, int offset, byte syncWord) { return buffer[offset] == syncWord;} },

		UINT8          { @Override public String toString()                        { return "uint8";                            }
		                 @Override public String getJavaTypeName()                 { return "Byte";                             }
		                 @Override public boolean isLittleEndian()                 { return true;                               }
		                 @Override public int getByteCount()                       { return 1;                                  }
		                 @Override public boolean isDataset()                      { return true;                               }
		                 @Override public float parse(byte[] buffer, int offset) { return (float)
		                                                                             (0xFF & buffer[offset]);                   } },

		UINT16_LE      { @Override public String toString()                        { return "uint16 LSB First";                 }
		                 @Override public String getJavaTypeName()                 { return "Short";                            }
		                 @Override public boolean isLittleEndian()                 { return true;                               }
		                 @Override public int getByteCount()                       { return 2;                                  }
		                 @Override public boolean isDataset()                      { return true;                               }
		                 @Override public float parse(byte[] buffer, int offset) { return (float)
		                                                                             (((0xFF & buffer[0+offset]) << 0) |
		                                                                              ((0xFF & buffer[1+offset]) << 8));        } },

		UINT16_BE      { @Override public String toString()                        { return "uint16 MSB First";                 }
		                 @Override public String getJavaTypeName()                 { return "Short";                            }
		                 @Override public boolean isLittleEndian()                 { return false;                              }
		                 @Override public int getByteCount()                       { return 2;                                  }
		                 @Override public boolean isDataset()                      { return true;                               }
		                 @Override public float parse(byte[] buffer, int offset) { return (float)
		                                                                             (((0xFF & buffer[1+offset]) << 0) |
		                                                                              ((0xFF & buffer[0+offset]) << 8));        } },

		UINT32_LE      { @Override public String toString()                        { return "uint32 LSB First";                 }
		                 @Override public String getJavaTypeName()                 { return "Int";                              }
		                 @Override public boolean isLittleEndian()                 { return true;                               }
		                 @Override public int getByteCount()                       { return 4;                                  }
		                 @Override public boolean isDataset()                      { return true;                               }
		                 @Override public float parse(byte[] buffer, int offset) { return (float)
		                                                                             (((long)(0xFF & buffer[0+offset]) << 0)  |
		                                                                              ((long)(0xFF & buffer[1+offset]) << 8)  |
		                                                                              ((long)(0xFF & buffer[2+offset]) << 16) |
		                                                                              ((long)(0xFF & buffer[3+offset]) << 24)); } },
		
		UINT32_BE      { @Override public String toString()                        { return "uint32 MSB First";                 }
		                 @Override public String getJavaTypeName()                 { return "Int";                              }
		                 @Override public boolean isLittleEndian()                 { return false;                              }
		                 @Override public int getByteCount()                       { return 4;                                  }
		                 @Override public boolean isDataset()                      { return true;                               }
		                 @Override public float parse(byte[] buffer, int offset) { return (float)
		                                                                             (((long)(0xFF & buffer[3+offset]) << 0)  |
		                                                                              ((long)(0xFF & buffer[2+offset]) << 8)  |
		                                                                              ((long)(0xFF & buffer[1+offset]) << 16) |
		                                                                              ((long)(0xFF & buffer[0+offset]) << 24)); } },
		
		INT8           { @Override public String toString()                        { return "int8";                             }
		                 @Override public String getJavaTypeName()                 { return "Byte";                             }
		                 @Override public boolean isLittleEndian()                 { return true;                               }
		                 @Override public int getByteCount()                       { return 1;                                  }
		                 @Override public boolean isDataset()                      { return true;                               }
		                 @Override public float parse(byte[] buffer, int offset) { return (float)(byte)
		                                                                             (0xFF & buffer[offset]);                   } },
		
		INT16_LE       { @Override public String toString()                        { return "int16 LSB First";                  }
		                 @Override public String getJavaTypeName()                 { return "Short";                            }
		                 @Override public boolean isLittleEndian()                 { return true;                               }
		                 @Override public int getByteCount()                       { return 2;                                  }
		                 @Override public boolean isDataset()                      { return true;                               }
		                 @Override public float parse(byte[] buffer, int offset) { return (float)(short)
		                                                                             (((0xFF & buffer[0+offset]) << 0) |
		                                                                              ((0xFF & buffer[1+offset]) << 8));        } },
		
		INT16_BE       { @Override public String toString()                        { return "int16 MSB First";                  }
		                 @Override public String getJavaTypeName()                 { return "Short";                            }
		                 @Override public boolean isLittleEndian()                 { return false;                              }
		                 @Override public int getByteCount()                       { return 2;                                  }
		                 @Override public boolean isDataset()                      { return true;                               }
		                 @Override public float parse(byte[] buffer, int offset) { return (float)(short)
		                                                                             (((0xFF & buffer[1+offset]) << 0) |
		                                                                              ((0xFF & buffer[0+offset]) << 8));        } },
		
		INT32_LE       { @Override public String toString()                        { return "int32 LSB First";                  } 
		                 @Override public String getJavaTypeName()                 { return "Int";                              }
		                 @Override public boolean isLittleEndian()                 { return true;                               }
		                 @Override public int getByteCount()                       { return 4;                                  }
		                 @Override public boolean isDataset()                      { return true;                               }
		                 @Override public float parse(byte[] buffer, int offset) { return (float)
		                                                                             (((0xFF & buffer[0+offset]) << 0)  |
		                                                                              ((0xFF & buffer[1+offset]) << 8)  |
		                                                                              ((0xFF & buffer[2+offset]) << 16) |
		                                                                              ((0xFF & buffer[3+offset]) << 24));       } },
		
		INT32_BE       { @Override public String toString()                        { return "int32 MSB First";                  }
		                 @Override public String getJavaTypeName()                 { return "Int";                              }
		                 @Override public boolean isLittleEndian()                 { return false;                              }
		                 @Override public int getByteCount()                       { return 4;                                  }
		                 @Override public boolean isDataset()                      { return true;                               }
		                 @Override public float parse(byte[] buffer, int offset) { return (float)
		                                                                             (((0xFF & buffer[3+offset]) << 0)  |
		                                                                              ((0xFF & buffer[2+offset]) << 8)  |
		                                                                              ((0xFF & buffer[1+offset]) << 16) |
		                                                                              ((0xFF & buffer[0+offset]) << 24));       } },
		
		FLOAT32_LE     { @Override public String toString()                        { return "float32 LSB First";                }
		                 @Override public String getJavaTypeName()                 { return "Float";                            }
		                 @Override public boolean isLittleEndian()                 { return true;                               }
		                 @Override public int getByteCount()                       { return 4;                                  }
		                 @Override public boolean isDataset()                      { return true;                               }
		                 @Override public float parse(byte[] buffer, int offset) { return Float.intBitsToFloat(
		                                                                             ((0xFF & buffer[0+offset]) <<  0) |
		                                                                             ((0xFF & buffer[1+offset]) <<  8) |
		                                                                             ((0xFF & buffer[2+offset]) << 16) |
		                                                                             ((0xFF & buffer[3+offset]) << 24));        } },
		
		FLOAT32_BE     { @Override public String toString()                        { return "float32 MSB First";                }
		                 @Override public String getJavaTypeName()                 { return "Float";                            }
		                 @Override public boolean isLittleEndian()                 { return false;                              }
		                 @Override public int getByteCount()                       { return 4;                                  }
		                 @Override public boolean isDataset()                      { return true;                               }
		                 @Override public float parse(byte[] buffer, int offset) { return Float.intBitsToFloat(
		                                                                             ((0xFF & buffer[3+offset]) <<  0) |
		                                                                             ((0xFF & buffer[2+offset]) <<  8) |
		                                                                             ((0xFF & buffer[1+offset]) << 16) |
		                                                                             ((0xFF & buffer[0+offset]) << 24));        } },
		
		UINT8_BITFIELD { @Override public String toString()                        { return "uint8 Bitfield";                   }
		                 @Override public String getJavaTypeName()                 { return "Byte";                             }
		                 @Override public boolean isLittleEndian()                 { return true;                               }
		                 @Override public int getByteCount()                       { return 1;                                  }
		                 @Override public boolean isDataset()                      { return true;                               }
		                 @Override public float parse(byte[] buffer, int offset) { return (float)
		                                                                             (0xFF & buffer[offset]);                   } },
		
		UINT8_CHECKSUM { @Override public String toString()                        { return "uint8 Checksum"; }
		                 @Override public String getJavaTypeName()                 { return "Byte"; }
		                 @Override public boolean isLittleEndian()                 { return true; }
		                 @Override public int getByteCount()                       { return 1; }
		                 @Override public boolean isChecksum()                     { return true;                               }
		                 @Override public boolean testChecksum(byte[] bytes, int offset, int packetLength, int syncWordByteCount) {
		                     // skip past the sync word
		                     offset += syncWordByteCount;
		                     packetLength -= syncWordByteCount;
		                     
		                     // calculate the sum
		                     byte sum = 0;
		                     for(int i = 0; i < packetLength - 1; i++)
		                         sum += bytes[offset + i];
		                     
		                     // extract the reported checksum
		                     byte checksum = bytes[offset + packetLength - 1];
		                     
		                     // test
		                     return (sum == checksum);
		                 } },

		UINT16_LE_CHECKSUM { @Override public String toString()                    { return "uint16 Checksum LSB First"; }
		                     @Override public String getJavaTypeName()             { return "Short"; }
		                     @Override public boolean isLittleEndian()             { return true; }
		                     @Override public int getByteCount()                   { return 2; }
		                     @Override public boolean isChecksum()                 { return true;                               }
		                     @Override public boolean testChecksum(byte[] bytes, int offset, int packetLength, int syncWordByteCount) {
		                         // skip past the sync word
		                         offset += syncWordByteCount;
		                         packetLength -= syncWordByteCount;
		                         
		                         // sanity check: a 16bit checksum requires an even number of bytes
		                         if(packetLength % 2 != 0)
		                             return false;
		                         
		                         // calculate the sum
		                         int wordCount = (packetLength - getByteCount()) / 2; // 16bit words
		                         
		                         int sum = 0;
		                         int lsb = 0;
		                         int msb = 0;
		                         for(int i = 0; i < wordCount; i++) {
		                             lsb = 0xFF & bytes[offset + i*2];
		                             msb = 0xFF & bytes[offset + i*2 + 1];
		                             sum += (msb << 8 | lsb);
		                         }
		                         sum %= 65536;
		                         
		                         // extract the reported checksum
		                         lsb = 0xFF & bytes[offset + packetLength - 2];
		                         msb = 0xFF & bytes[offset + packetLength - 1];
		                         int checksum = (msb << 8 | lsb);
		                         
		                         // test
		                         return (sum == checksum);
		                     } };

		abstract String getJavaTypeName();
		abstract boolean isLittleEndian();
		abstract int getByteCount();
		boolean isSyncWord() { return false; }                                                                    /* sync words should @Override this and return true! */
		boolean isDataset()  { return false; }                                                                    /* datasets should @Override this and return true! */
		boolean isChecksum() { return false; }                                                                    /* checksums should @Override this and return true! */
		boolean testSyncWord(byte[] buffer, int offset, byte syncWord) { return false; }                          /* sync words should @Override this and test if the sync word exists! */
		float parse(byte[] buffer, int offset) { return 0; }                                                      /* datasets should @Override this and return a number! */
		boolean testChecksum(byte[] bytes, int offset, int packetLength, int syncWordByteCount) { return false; } /* checksums should @Override this and test if the checksum is valid! */
		
		public static Type fromString(String text) {
			return Stream.of(values()).filter(value -> value.toString().equals(text)).findFirst().orElse(null);
		}
	};
	
	/**
	 * The GUI for defining a Bitfield Dataset.
	 */
	@SuppressWarnings("serial")
	private class BitfieldPanel extends JPanel {
		
		JPanel widgets = new JPanel(new MigLayout("wrap 3, gap " + Theme.padding, "[pref][pref][grow]"));
		
		public BitfieldPanel(int bitCount, Runnable doneEventHandler) {
			
			super();
			setLayout(new BorderLayout());
			
			JButton bitfieldDoneButton = new JButton("Done With Bitfield");
			bitfieldDoneButton.addActionListener(event -> doneEventHandler.run());
			JPanel bottom = new JPanel();
			bottom.setLayout(new FlowLayout(FlowLayout.RIGHT, 0, 0));
			bottom.setBorder(new EmptyBorder(Theme.padding, 0, 0, 0));
			bottom.add(bitfieldDoneButton);
			
			add(new Visualization(bitCount), BorderLayout.NORTH);
			add(widgets, BorderLayout.CENTER);
			add(bottom, BorderLayout.SOUTH);
			
		}
		
		/**
		 * The user added a new bitfield, so update and redraw the panel.
		 * 
		 * @param MSBit    The most-significant bit of the bitfield.
		 * @param LSBit    The least-significant bit of the bitfield.
		 */
		public void addField(int MSBit, int LSBit) {
			
			addBitfield(MSBit, LSBit);
			
			// repopulate the GUI
			widgets.removeAll();
			
			bitfields.forEach(bitfield -> {
				for(Field.Bitfield.State state : bitfield.states) {
					WidgetTextfield<String> nameTextfield = WidgetTextfield.ofText(state.name)
					                                                       .setPrefix("Name")
					                                                       .setExportLabel("bitfield state name")
					                                                       .onChange((newName, oldName) -> {
					                                                           state.name = newName;
					                                                           return true;
					                                                       });
					nameTextfield.onIncompleteChange(incompleteName -> {
						if(incompleteName.contains(","))
							nameTextfield.set(incompleteName.replace(',', ' ')); // no commas allowed, because they will break import/export logic
						else
							state.name = incompleteName;
					}); 
					
					WidgetColorPicker colorButton = new WidgetColorPicker(state.label, state.color)
					                                    .onEvent(newColor -> {
					                                                 state.color = newColor;
					                                                 state.glColor = new float[] {newColor.getRed() / 255f, newColor.getGreen() / 255f, newColor.getBlue() / 255f, 1};
					                                             });
					
					widgets.add(new JLabel(state.label));
					colorButton.appendTo(widgets, "");
					nameTextfield.appendTo(widgets, "grow x"); // stretch to fill column width
				}
				widgets.add(new JLabel(" "), "wrap");
			});
			widgets.remove(widgets.getComponentCount() - 1);
			
			revalidate();
			repaint();
			
		}
		
		/**
		 * This panel contains the "tiles" that represent each bit in the bitfield.
		 * The user clicks or click-and-drags on the bits to specify a range for each bitfield.
		 */
		private class Visualization extends JPanel {
			
			int bitCount;
			
			final int padding = Theme.padding * 2; // space inside each button, between the edges and the text label
			final int spacing = Theme.padding; // space between each button
			final Color tileColor = new Color(Theme.tileColor[0], Theme.tileColor[1], Theme.tileColor[2], Theme.tileColor[3]);
			final Color tileSelectedColor = new Color(Theme.tileSelectedColor[0], Theme.tileSelectedColor[1], Theme.tileSelectedColor[2], Theme.tileSelectedColor[3]);
			
			int maxTextWidth;
			int textHeight;
			int buttonWidth;
			int buttonHeight;
			
			int firstBit = -1;
			int lastBit = -1;
			
			public Visualization(int bitCount) {
				
				super();
				this.bitCount = bitCount;
				
				// determine the required size of this panel
				maxTextWidth = getFontMetrics(getFont()).stringWidth(bitCount - 1 + "");
				textHeight = getFontMetrics(getFont()).getAscent();
				buttonWidth = padding + maxTextWidth + padding;
				buttonHeight = padding + textHeight + padding;
				int totalWidth = (bitCount * buttonWidth) + (spacing * bitCount);
				int totalHeight = buttonHeight + spacing;
				Dimension size = new Dimension(totalWidth, totalHeight);
				setMinimumSize(size);
				setPreferredSize(size);
				setMaximumSize(size);
				
				addMouseListener(new MouseListener() {
					
					@Override public void mousePressed(MouseEvent e) {
						firstBit = -1;
						lastBit = -1;
						
						// if the user clicked on a button, mark it as the first and last bit *if* this bit is not already being used
						int x = e.getX();
						int y = e.getY();
						for(int i = 0; i < bitCount; i++) {
							int minX = i * (spacing + buttonWidth);
							int maxX = minX + buttonWidth;
							int minY = 0;
							int maxY = minY + buttonHeight;
							if(x >= minX && x <= maxX && y >= minY && y <= maxY) {
								int bit = bitCount - 1 - i;
								boolean bitAvailable = true;
								for(Field.Bitfield bitfield : bitfields)
									if(bit >= bitfield.LSBit && bit <= bitfield.MSBit)
										bitAvailable = false;
								if(bitAvailable) {
									firstBit = bit;
									lastBit = bit;
								}
							}
						}
						repaint();
					}
					
					@Override public void mouseReleased(MouseEvent e) {
						if(firstBit == -1 || lastBit == -1)
							return;
						
						// the user released the mouse, so add the field
						addField(Integer.max(firstBit, lastBit), Integer.min(firstBit, lastBit));
						firstBit = -1;
						lastBit = -1;
						repaint();
					}
					
					@Override public void mouseExited(MouseEvent e) { }
					@Override public void mouseEntered(MouseEvent e) { }
					@Override public void mouseClicked(MouseEvent e) { }
				});
				
				addMouseMotionListener(new MouseMotionListener() {
					
					@Override public void mouseMoved(MouseEvent e) { }
					
					@Override public void mouseDragged(MouseEvent e) {
						if(firstBit == -1 || lastBit == -1)
							return;
						
						// the user moved the mouse, so update the proposed bit range *if* the entire range is available
						int x = e.getX();
						int y = e.getY();
						for(int i = 0; i < bitCount; i++) {
							int minX = i * (spacing + buttonWidth);
							int maxX = minX + buttonWidth;
							int minY = 0;
							int maxY = minY + buttonHeight;
							if(x >= minX && x <= maxX && y >= minY && y <= maxY) {
								int bit = bitCount - 1 - i;
								boolean bitRangeAvailable = true;
								for(int b = Integer.min(bit, firstBit); b <= Integer.max(bit, firstBit); b++)
									for(Field.Bitfield bitfield : bitfields)
										if(b >= bitfield.LSBit && b <= bitfield.MSBit)
											bitRangeAvailable = false;
								if(bitRangeAvailable)
									lastBit = bit;
							}
						}
						repaint();
					}
				});
				
			}
			
			@Override protected void paintComponent(Graphics g) {
				
				super.paintComponent(g);
				
				// draw the background
				g.setColor(getBackground());
				g.fillRect(0, 0, getWidth(), getHeight());
				
				// draw each button
				g.setColor(tileColor);
				for(int i = 0; i < bitCount; i++) {
					int x = i * (spacing + buttonWidth);
					int y = 0;
					g.fillRect(x, y, buttonWidth, buttonHeight);
				}
				
				// draw existing fields
				g.setColor(getBackground());
				for(Field.Bitfield bitfield : bitfields) {
					int width = padding + maxTextWidth + padding + (bitfield.MSBit - bitfield.LSBit) * (spacing + buttonWidth);
					int height = padding + textHeight + padding;
					int x = (bitCount - 1 - bitfield.MSBit) * (spacing + buttonWidth);
					int y = 0;
					g.fillRect(x, y, width, height);
				}
				
				// draw the proposed new field
				if(firstBit >= 0 && lastBit >= 0) {
					g.setColor(tileSelectedColor);
					int MSBit = Integer.max(firstBit, lastBit);
					int LSBit = Integer.min(firstBit, lastBit);
					int width = padding + maxTextWidth + padding + (MSBit - LSBit) * (spacing + buttonWidth);
					int height = padding + textHeight + padding;
					int x = (bitCount - 1 - MSBit) * (spacing + buttonWidth);
					int y = 0;
					g.fillRect(x, y, width, height);
				}
				
				// draw each text label
				for(int i = 0; i < bitCount; i++) {
					g.setColor(Color.BLACK);
					for(Field.Bitfield bitfield : bitfields)
						if((bitCount - 1 - i) >= bitfield.LSBit && (bitCount - 1 - i) <= bitfield.MSBit)
							g.setColor(Color.LIGHT_GRAY);
					int x = i * (spacing + buttonWidth) + padding;
					int y = padding + textHeight;
					String text = bitCount - 1 - i + "";
					x += (maxTextWidth - getFontMetrics(getFont()).stringWidth(text)) / 2; // adjust x to center the text
					g.drawString(text, x, y);
				}
				
			}
		
		}
		
	}
	
}
