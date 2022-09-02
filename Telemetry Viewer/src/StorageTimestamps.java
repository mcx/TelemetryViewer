import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import com.jogamp.common.nio.Buffers;

public class StorageTimestamps {
	
	// timestamps are buffered into "slots" which each hold 1M "records"
	// Each record is 3 longs which specify:
	// [i]   The first sample number in a series.
	// [i+1] The sample count of that series.
	// [i+2] The timestamp for that entire series.
	
	// to speed up queries, the min/max timestamps and sample numbers are tracked for smaller "blocks" of 1K records.
	private final int BLOCK_SIZE = StorageFloats.BLOCK_SIZE;
	private final int SLOT_SIZE  = StorageFloats.SLOT_SIZE;
	private final int MAX_SAMPLE_NUMBER = Integer.MAX_VALUE;
	private final int BYTES_PER_TIMESTAMP = 8;
	private final int BYTES_PER_RECORD = 8*3; // 8 bytes per long, 3 longs per record

	private volatile int sampleCount = 0;
	private volatile int recordCount = 0;
	private volatile Slot[] slot                       = new Slot[MAX_SAMPLE_NUMBER / SLOT_SIZE  + 1]; // +1 to round up
	private volatile long[] minimumTimestampInBlock    = new long[MAX_SAMPLE_NUMBER / BLOCK_SIZE + 1]; // +1 to round up
	private volatile long[] maximumTimestampInBlock    = new long[MAX_SAMPLE_NUMBER / BLOCK_SIZE + 1]; // +1 to round up
	private volatile int[]  minimumSampleNumberInBlock = new  int[MAX_SAMPLE_NUMBER / BLOCK_SIZE + 1]; // +1 to round up
	private volatile int[]  maximumSampleNumberInBlock = new  int[MAX_SAMPLE_NUMBER / BLOCK_SIZE + 1]; // +1 to round up
	
	// older slots are swapped to disk
	private final Path filePath;
	private final FileChannel file;
	
	private ConnectionTelemetry connection;

	/**
	 * Prepares storage space for timestamps.
	 * 
	 * @param connection    The corresponding connection.
	 */
	public StorageTimestamps(ConnectionTelemetry connection) {
		
		this.connection = connection;
		
		filePath = Paths.get("cache/" + this.toString() + ".bin");
		
		FileChannel temp = null;
		try {
			temp = FileChannel.open(filePath, StandardOpenOption.CREATE,
			                                  StandardOpenOption.TRUNCATE_EXISTING,
			                                  StandardOpenOption.READ,
			                                  StandardOpenOption.WRITE);
		} catch (IOException e) {
			NotificationsController.showCriticalFault("Unable to create the cache file for \"" + filePath.toString() + "\"");
			e.printStackTrace();
		}
		file = temp;
		
	}
	
	/**
	 * @return    A place to cache timestamps.
	 */
	public Cache createCache() {
		
		return new Cache();
		
	}
	
	/**
	 * Sets the timestamp for one or more new samples.
	 * This method is NOT reentrant! Only one thread may call this at a time.
	 * 
	 * @param timestamp    The new timestamp. This MUST be >= the timestamp of the previous sample.
	 * @param count        How many new samples use this timestamp.
	 */
	public void appendTimestamps(long timestamp, int count) {
		
		// if the current record has the same timestamp, just increment its sampleCount and maximumSampleNumberInBlock
		int slotN   = (recordCount - 1) / SLOT_SIZE;
		int recordN = (recordCount - 1) % SLOT_SIZE;
		int blockN  = (recordCount - 1) / BLOCK_SIZE;
		if(recordCount > 0 && slot[slotN].record[recordN*3 + 2] == timestamp) {
			slot[slotN].record[recordN*3 + 1] += count;
			maximumSampleNumberInBlock[blockN] += count;
			sampleCount += count;
			return;
		}
		
		// the current record has an older timestamp, so fill in a new record (creating a new slot if necessary) and update the min/max's
		slotN   = recordCount / SLOT_SIZE;
		recordN = recordCount % SLOT_SIZE;
		blockN  = recordCount / BLOCK_SIZE;
		
		if(recordN == 0) {
			slot[slotN] = new Slot();
			if(slotN > 1)
				slot[slotN - 2].flushToDisk(slotN - 2);
		}
		slot[slotN].record[recordN*3 + 0] = sampleCount;
		slot[slotN].record[recordN*3 + 1] = count;
		slot[slotN].record[recordN*3 + 2] = timestamp;
		
		if(recordCount % BLOCK_SIZE == 0) {
			minimumTimestampInBlock[blockN] = timestamp;
			maximumTimestampInBlock[blockN] = timestamp;
			minimumSampleNumberInBlock[blockN] = sampleCount;
			maximumSampleNumberInBlock[blockN] = sampleCount + count - 1;
		} else {
			if(timestamp > maximumTimestampInBlock[blockN])
				maximumTimestampInBlock[blockN] = timestamp;
			maximumSampleNumberInBlock[blockN] += count;
		}
		recordCount++;
		sampleCount += count;
		
	}
	
	public int getClosestSampleNumberAtOrBefore(long timestamp, int maxSampleNumber, Cache cache) {
		
		if(sampleCount == 0)
			return -1;
		
		int lastBlock = (recordCount - 1) / BLOCK_SIZE;
		
		// check if all timestamps are younger
		if(maximumTimestampInBlock[lastBlock] < timestamp)
			return maxSampleNumber;
		
		// find the closest block
		int block = -1;
		for(int blockN = lastBlock; blockN >= 0; blockN--)
			if(minimumTimestampInBlock[blockN] <= timestamp) {
				block = blockN;
				break;
			}
		
		// all samples are older (none "at or before")
		if(block == -1)
			return -1;
		
		// get records for that block and find the closest sample
		LongBuffer buffer = getRecordsFromBlock(block);
		int offset = buffer.position();
		for(int i = BLOCK_SIZE - 1; i >= 0; i--) {
			long recordFirstSampleNumber = buffer.get(offset + i*3 + 0);
			long recordSampleCount = buffer.get(offset + i*3 + 1);
			if(recordSampleCount == 0) // this record not yet used
				continue;
			long recordTimestamp = buffer.get(offset + i*3 + 2);
			if(recordTimestamp <= timestamp)
				return (int) (recordFirstSampleNumber + recordSampleCount - 1);
		}
		
		// should never get here
		return -1;
		
	}
	
	public int getClosestSampleNumberAfter(long timestamp, Cache cache) {
		
		if(sampleCount == 0)
			return -1;
		
		int maxSampleNumber = sampleCount - 1;
		int lastBlock = (recordCount - 1) / BLOCK_SIZE;
		
		// check if all timestamps are older
		if(minimumTimestampInBlock[0] > timestamp)
			return 0;
		
		// find the closest block
		int block = -1;
		for(int blockN = 0; blockN <= lastBlock; blockN++)
			if(maximumTimestampInBlock[blockN] > timestamp) {
				block = blockN;
				break;
			}
		
		// all timestamps are younger or equal (none "after")
		if(block == -1)
			return maxSampleNumber;
		
		// get records for that block and find the closest sample
		LongBuffer buffer = getRecordsFromBlock(block);
		for(int i = 0; i < BLOCK_SIZE; i++) {
			long recordFirstSampleNumber = buffer.get();
			/* long recordSampleCount = */ buffer.get();
			long recordTimestamp = buffer.get();
			if(recordTimestamp > timestamp)
				return (int) recordFirstSampleNumber;
		}
		
		// should never get here
		return maxSampleNumber;
		
	}
	
	private LongBuffer getRecordsFromBlock(int blockN) {
		
		int slotN = (blockN * BLOCK_SIZE) / SLOT_SIZE;
		
		// save a reference to the array BEFORE checking if the array is in memory, to prevent a race condition
		long[] record = slot[slotN].record;
		
		if(!slot[slotN].flushing && slot[slotN].inRam) {
			
			// slot is in memory
			int offset = blockN % (SLOT_SIZE / BLOCK_SIZE) * BLOCK_SIZE * 3;
			return LongBuffer.wrap(record, offset, BLOCK_SIZE * 3);
			
		} else {
			
			// slot must be read from disk
			while(slot[slotN].flushing);
			long fileOffset = (long) slotN * (long) SLOT_SIZE * (long) BYTES_PER_RECORD;
			int byteCount = SLOT_SIZE * BYTES_PER_RECORD;
			ByteBuffer buffer = Buffers.newDirectByteBuffer(byteCount);
			try {
				file.read(buffer, fileOffset);
			} catch (IOException e) {
				NotificationsController.showCriticalFault("Error while reading a value from the cache file at \"" + filePath.toString() + "\"");
				e.printStackTrace();
			}
			
			int offset = blockN % (SLOT_SIZE / BLOCK_SIZE) * BLOCK_SIZE * 3;
			return buffer.rewind().asLongBuffer().position(offset);
			
		}	
		
	}
	
	/**
	 * Reads the timestamp for a certain sample number.
	 * 
	 * @param sampleNumber    Which sample number to read. This MUST be a valid sample number.
	 * @param cache           Place to cache timestamps.
	 * @return                The corresponding timestamp.
	 */
	public long getTimestamp(int sampleNumber, Cache cache) {
		
		cache.update(sampleNumber, sampleNumber);
		cache.cacheLongs.position(sampleNumber - cache.startOfCache);
		return cache.cacheLongs.get();
		
	}
	
	/**
	 * Reads a sequence of timestamps.
	 * 
	 * @param firstSampleNumber    The first sample number, inclusive. This MUST be a valid sample number.
	 * @param lastSampleNumber     The last sample number, inclusive. This MUST be a valid sample number.
	 * @param plotMinX             Timestamp at the left edge of the plot.
	 * @param cache                Place to cache timestamps.
	 */
	public FloatBuffer getTampstamps(int firstSampleNumber, int lastSampleNumber, long plotMinX, Cache cache) {
		
		cache.update(firstSampleNumber, lastSampleNumber);
		cache.cacheLongs.position(firstSampleNumber - cache.startOfCache);
		
		FloatBuffer buffer = Buffers.newDirectFloatBuffer(lastSampleNumber - firstSampleNumber + 1);
		for(int i = firstSampleNumber; i <= lastSampleNumber; i++)
			buffer.put(cache.cacheLongs.get() - plotMinX);
		
		return buffer.rewind();
		
	}
	
	/**
	 * Empties the file on disk and empties the slots in memory.
	 * 
	 * TO PREVENT RACE CONDITIONS, THIS METHOD MUST ONLY BE CALLED WHEN NO OTHER METHODS OF THIS CLASS ARE IN PROGRESS.
	 * When connected (UART/TCP/UDP/etc.) a thread could be appending new values.
	 * And regardless of connection status, the charts could be reading existing values.
	 * Therefore: this method must only be called when disconnected AND when no charts are on screen.
	 */
	public void clear() {
		
		// slots may be flushing to disk, so wait for that to finish
		for(Slot s : slot) {
			
			if(s == null)
				break; // reached the end
			
			while(s.flushing)
				; // wait
			
		}
		
		// empty the file
		try {
			file.truncate(0);
		} catch (IOException e) {
			NotificationsController.showCriticalFault("Unable to clear the cache file at \"" + filePath.toString() + "\"");
			e.printStackTrace();
		}
		
		// empty the slots
		sampleCount = 0;
		recordCount = 0;
		slot                       = new Slot[MAX_SAMPLE_NUMBER / SLOT_SIZE  + 1]; // +1 to round up
		minimumTimestampInBlock    = new long[MAX_SAMPLE_NUMBER / BLOCK_SIZE + 1]; // +1 to round up
		maximumTimestampInBlock    = new long[MAX_SAMPLE_NUMBER / BLOCK_SIZE + 1]; // +1 to round up
		minimumSampleNumberInBlock = new  int[MAX_SAMPLE_NUMBER / BLOCK_SIZE + 1]; // +1 to round up
		maximumSampleNumberInBlock = new  int[MAX_SAMPLE_NUMBER / BLOCK_SIZE + 1]; // +1 to round up
		
	}
	
	/**
	 * Deletes the file from disk.
	 * This method should be called immediately before removing a Dataset.
	 * 
	 * TO PREVENT RACE CONDITIONS, THIS METHOD MUST ONLY BE CALLED WHEN NO OTHER METHODS OF THIS CLASS ARE IN PROGRESS.
	 * When connected (UART/TCP/UDP/etc.) a thread could be appending new values.
	 * And regardless of connection status, the charts could be reading existing values.
	 * Therefore: this method must only be called when disconnected AND when no charts are on screen.
	 */
	public void dispose() {
		
		// slots may be flushing to disk, so wait for that to finish
		for(Slot s : slot) {
			
			if(s == null)
				break; // reached the end
			
			while(s.flushing)
				; // wait
			
		}
		
		// remove the file from disk
		try {
			file.close();
			Files.deleteIfExists(filePath);
		} catch (IOException e) {
			NotificationsController.showCriticalFault("Unable to delete the cache file at \"" + filePath.toString() + "\"");
			e.printStackTrace();
		}
		
	}
	
	public class Cache {
		
		private int cacheSize = 3 * StorageFloats.SLOT_SIZE;
		private ByteBuffer cacheBytes = Buffers.newDirectByteBuffer(cacheSize * BYTES_PER_TIMESTAMP);
		private LongBuffer cacheLongs = cacheBytes.asLongBuffer();
		private int startOfCache = 0;
		private int cachedCount = 0;
		
		/**
		 * Updates the contents of the cache, growing the cache size if necessary.
		 * 
		 * @param firstSampleNumber    Start of range, inclusive. This MUST be a valid sample number.
		 * @param lastSampleNumber     End of range, inclusive. This MUST be a valid sample number.
		 */
		public void update(int firstSampleNumber, int lastSampleNumber) {
			
			// grow the request range to it's enclosing slot(s) to improve efficiency
			firstSampleNumber =  (firstSampleNumber / StorageFloats.SLOT_SIZE)      * StorageFloats.SLOT_SIZE;     // round DOWN to the nearest slot boundary
			lastSampleNumber  = ((lastSampleNumber  / StorageFloats.SLOT_SIZE) + 1) * StorageFloats.SLOT_SIZE - 1; // round UP   to the nearest slot boundary
			if(lastSampleNumber >= sampleCount)
				lastSampleNumber = sampleCount - 1;
			
			// grow the cache to 300% if it can't hold 200% the requested range
			int newSampleCount = lastSampleNumber - firstSampleNumber + 1;
			if(cacheSize < 2 * newSampleCount) {
				cacheSize = 3 * newSampleCount;
				cacheBytes = Buffers.newDirectByteBuffer(cacheSize * BYTES_PER_TIMESTAMP);
				cacheLongs = cacheBytes.asLongBuffer();
				startOfCache = 0;
				cachedCount = 0;
			}
			
			// flush cache if necessary
			long endOfCache = Guava.saturatedAdd(startOfCache, cacheSize) - 1;
			if(firstSampleNumber < startOfCache || lastSampleNumber > endOfCache) {
				startOfCache = firstSampleNumber - (cacheSize / 3); // reserve a third of the cache before the currently requested range, so the user can rewind a little without needing to flush the cache
				if(startOfCache < 0)
					startOfCache = 0;
				cachedCount = 0;
				// try to fill the new cache with adjacent samples too
				firstSampleNumber = startOfCache;
				lastSampleNumber = Guava.saturatedAdd(startOfCache, cacheSize - 1);
				int max = connection.getSampleCount() - 1;
				if(lastSampleNumber > max)
					lastSampleNumber = max;
			}
			
			// new range starts before cached range
			if(firstSampleNumber < startOfCache) {
				
				int start = firstSampleNumber;
				int end   = startOfCache - 1;
				cacheLongs.position(start - startOfCache);
				
				// find the block range containing (start, end) inclusive
				int firstBlock = -1;
				int lastBlock  = -1;
				for(int blockN = 0; blockN <= (recordCount - 1) / BLOCK_SIZE; blockN++) {
					if(firstBlock == -1 && maximumSampleNumberInBlock[blockN] <= start)
						firstBlock = blockN;
					if(lastBlock == -1 && maximumSampleNumberInBlock[blockN] >= end)
						lastBlock = blockN;
					if(firstBlock != -1 && lastBlock != -1)
						break;
				}
				
				// fill the cache by iterating through the appropriate slots
				int firstSlot   = (firstBlock * BLOCK_SIZE) / SLOT_SIZE;
				int lastSlot    = (lastBlock  * BLOCK_SIZE) / SLOT_SIZE;
				int firstRecord = (firstBlock * BLOCK_SIZE) % SLOT_SIZE;
				for(int slotN = firstSlot; slotN <= lastSlot; slotN++) {
					
					// save a reference to the array BEFORE checking if the array is in memory, to prevent a race condition
					long[] record = slot[slotN].record;
					
					if(!slot[slotN].flushing && slot[slotN].inRam) {
						
						// slot can be read from memory
						for(int recordN = firstRecord; recordN < SLOT_SIZE; recordN++) {
							int firstSampleNumberOfRecord =                             (int) record[recordN*3 + 0];
							int lastSampleNumberOfRecord  = firstSampleNumberOfRecord + (int) record[recordN*3 + 1] - 1;
							long timestampOfRecord        =                                   record[recordN*3 + 2];
							while(start >= firstSampleNumberOfRecord && start <= lastSampleNumberOfRecord) {
								cacheLongs.put(timestampOfRecord);
								start++;
								if(start > end) {
									recordN = SLOT_SIZE;
									break;
								}
							}
						}
						
					} else {
						
						// slot must be read from disk
						while(slot[slotN].flushing);
						long fileOffset = (long) (start / SLOT_SIZE * SLOT_SIZE) * (long) BYTES_PER_RECORD;
						int recordCount = SLOT_SIZE;
						int byteCount = recordCount * BYTES_PER_RECORD;
						ByteBuffer buffer = Buffers.newDirectByteBuffer(byteCount);
						try {
							file.read(buffer, fileOffset);
						} catch (IOException e) {
							NotificationsController.showCriticalFault("Error while reading a value from the cache file at \"" + filePath.toString() + "\"");
							e.printStackTrace();
						}
						
						for(int recordN = firstRecord; recordN < SLOT_SIZE; recordN++) {
							int firstSampleNumberOfRecord =                             (int) buffer.getLong(8 * (recordN*3 + 0));
							int lastSampleNumberOfRecord  = firstSampleNumberOfRecord + (int) buffer.getLong(8 * (recordN*3 + 1)) - 1;
							long timestampOfRecord        =                                   buffer.getLong(8 * (recordN*3 + 2));
							while(start >= firstSampleNumberOfRecord && start <= lastSampleNumberOfRecord) {
								cacheLongs.put(timestampOfRecord);
								start++;
								if(start > end) {
									recordN = SLOT_SIZE;
									break;
								}
							}
						}
						
					}
					
					firstRecord = 0;
					
				}

				startOfCache = firstSampleNumber;
				cachedCount += end - firstSampleNumber + 1;
				
			}
			
			// new range ends after cached range
			if(lastSampleNumber > startOfCache + cachedCount - 1) {
				
				int start = startOfCache + cachedCount;
				int end   = lastSampleNumber;
				cacheLongs.position(start - startOfCache);
				
				// find the block range containing (start, end) inclusive
				int firstBlock = -1;
				int lastBlock  = -1;
				for(int blockN = 0; blockN <= (recordCount - 1) / BLOCK_SIZE; blockN++) {
					if(firstBlock == -1 && maximumSampleNumberInBlock[blockN] >= start)
						firstBlock = blockN;
					if(lastBlock == -1 && maximumSampleNumberInBlock[blockN] >= end)
						lastBlock = blockN;
					if(firstBlock != -1 && lastBlock != -1)
						break;
				}
				
				// fill the cache by iterating through the appropriate slots
				int firstSlot   = (firstBlock * BLOCK_SIZE) / SLOT_SIZE;
				int lastSlot    = (lastBlock  * BLOCK_SIZE) / SLOT_SIZE;
				int firstRecord = (firstBlock * BLOCK_SIZE) % SLOT_SIZE;
				for(int slotN = firstSlot; slotN <= lastSlot; slotN++) {
					
					// save a reference to the array BEFORE checking if the array is in memory, to prevent a race condition
					long[] record = slot[slotN].record;
					
					if(!slot[slotN].flushing && slot[slotN].inRam) {
						
						// slot can be read from memory
						for(int recordN = firstRecord; recordN < SLOT_SIZE; recordN++) {
							int firstSampleNumberOfRecord =                             (int) record[recordN*3 + 0];
							int lastSampleNumberOfRecord  = firstSampleNumberOfRecord + (int) record[recordN*3 + 1] - 1;
							long timestampOfRecord        =                                   record[recordN*3 + 2];
							while(start >= firstSampleNumberOfRecord && start <= lastSampleNumberOfRecord) {
								cacheLongs.put(timestampOfRecord);
								start++;
								if(start > end) {
									recordN = SLOT_SIZE;
									break;
								}
							}
						}
						
					} else {
						
						// slot must be read from disk
						while(slot[slotN].flushing);
						long fileOffset = (long) (start / SLOT_SIZE * SLOT_SIZE) * (long) BYTES_PER_RECORD;
						int recordCount = SLOT_SIZE;
						int byteCount = recordCount * BYTES_PER_RECORD;
						ByteBuffer buffer = Buffers.newDirectByteBuffer(byteCount);
						try {
							file.read(buffer, fileOffset);
						} catch (IOException e) {
							NotificationsController.showCriticalFault("Error while reading a value from the cache file at \"" + filePath.toString() + "\"");
							e.printStackTrace();
						}
						
						for(int recordN = firstRecord; recordN < SLOT_SIZE; recordN++) {
							int firstSampleNumberOfRecord =                             (int) buffer.getLong(8 * (recordN*3 + 0));
							int lastSampleNumberOfRecord  = firstSampleNumberOfRecord + (int) buffer.getLong(8 * (recordN*3 + 1)) - 1;
							long timestampOfRecord        =                                   buffer.getLong(8 * (recordN*3 + 2));
							while(start >= firstSampleNumberOfRecord && start <= lastSampleNumberOfRecord) {
								cacheLongs.put(timestampOfRecord);
								start++;
								if(start > end) {
									recordN = SLOT_SIZE;
									break;
								}
							}
						}
						
					}
					
					firstRecord = 0;
					
				}

				cachedCount += end - (startOfCache + cachedCount) + 1;
				
			}
			
		}
		
	}

	/**
	 * Each Slot stores 1M timestamp "records." Each record is 3 longs which specify:
	 * 1. The first sample number in a series.
	 * 2. The sample count of that series.
	 * 3. The timestamp for that entire series.
	 * 
	 * The records are stored in a strictly increasing order (there will not be two records for the same timestamp.)
	 */
	private class Slot {
		
		private volatile boolean inRam = true;
		private volatile boolean flushing = false;
		private volatile long[] record = new long[3*SLOT_SIZE]; // [i] = firstSampleNumber, [i+1] = sampleCount, [i+2] = timestamp, ...
		
		public void flushToDisk(int slotN) {
			
			// in stress test mode just delete the data
			// because even high-end SSDs will become the bottleneck
			if(connection.isTypeStressTest()) {
				slot[slotN].inRam = false;
				slot[slotN].record = null;
				slot[slotN].flushing = false;
				return;
			}
			
			// move this slot to disk
			flushing = true;
			
			new Thread(() -> {
				try {
					ByteBuffer buffer = Buffers.newDirectByteBuffer(SLOT_SIZE * BYTES_PER_RECORD);
					buffer.asLongBuffer().put(record);
					long fileOffset = (long) slotN * (long) SLOT_SIZE * (long) BYTES_PER_RECORD;
					file.write(buffer, fileOffset);
					file.force(true);
					
					inRam = false;
					record = null;
					flushing = false;
				} catch(Exception e) {
					NotificationsController.showCriticalFault("Error while moving values to the cache file at \"" + filePath.toString() + "\"");
					e.printStackTrace();
				}
			}).start();
			
		}
		
	}
	
}
