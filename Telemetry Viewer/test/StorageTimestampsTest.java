import static org.junit.Assume.assumeTrue;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.FloatBuffer;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class StorageTimestampsTest {

	/**
	 * @return    An array of sample counts that are most likely to reveal bugs.
	 *            I expect bugs to be mostly off-by-one errors, near the block or slot boundaries.
	 *            The second-oldest slot is flushed to disk when creating a new slot, so we should also test with enough data to fill 3 slots.
	 */
	static int[] riskySampleCounts() {
		return new int[] {
			1,
			StorageFloats.BLOCK_SIZE - 1,
			StorageFloats.BLOCK_SIZE,
			StorageFloats.BLOCK_SIZE + 1,
			1*StorageFloats.SLOT_SIZE - 1,
			1*StorageFloats.SLOT_SIZE,
			1*StorageFloats.SLOT_SIZE + 1,
			3*StorageFloats.SLOT_SIZE - 1,
			3*StorageFloats.SLOT_SIZE,
			3*StorageFloats.SLOT_SIZE + 1,
		};
	}
	
	/**
	 * @return    Cartesian product of the riskySampleCounts.
	 */
	static Stream<Arguments> riskySampleCountsPair() {
		
		List<Arguments> list = new ArrayList<Arguments>();
		for(int x : riskySampleCounts())
			for(int y : riskySampleCounts())
				if(y <= x)
					list.add(Arguments.of(x, y));
		
		return list.stream();
		
	}
	
	ConnectionTelemetry connection;
	DatasetsInterface datasetsInterface;
	
	@BeforeEach
	void prepare() {
		
		try { Files.createDirectory(Paths.get("cache")); } catch(FileAlreadyExistsException e) {} catch(Exception e) { e.printStackTrace(); }
		connection = new ConnectionTelemetry("Demo Mode");
		datasetsInterface = new DatasetsInterface(connection);
		
	}
	
	@DisplayName(value = "Reading/Writing Timestamps")
	@ParameterizedTest(name = "Inserting {0} timestamps, reading back in chunks of {1}")
	@MethodSource("riskySampleCountsPair")
	@Disabled("Slow test, only run occasionally.")
	void readWrite(int writeSampleCount, int readSampleCount) {
		
		// populate with timestamps = 0,1,2,3,...
		for(int i = 0; i < writeSampleCount; i++)
			connection.incrementSampleCountWithTimestamp(1, i);
		
		// verify the timestamps
		if(readSampleCount == 1) {
			for(int i = 0; i < writeSampleCount; i++)
				assertTrue(datasetsInterface.getTimestamp(i) == i);
		} else {
			for(int firstSampleNumber = 0; firstSampleNumber + readSampleCount - 1 < writeSampleCount; firstSampleNumber += readSampleCount) {
				FloatBuffer buffer = datasetsInterface.getTimestampsBuffer(firstSampleNumber, firstSampleNumber + readSampleCount - 1, 0);
				for(int sampleNumber = firstSampleNumber; sampleNumber < firstSampleNumber + readSampleCount; sampleNumber++) {
					float timestamp = buffer.get();
					float expectedTimestamp = (float) sampleNumber;
					assertTrue(timestamp == expectedTimestamp, "Got " + timestamp + ", expected " + expectedTimestamp);
				}
			}
		}
		
	}
	
	@DisplayName(value = "Closest Sample At or Before")
	@ParameterizedTest(name = "With {0} unique timestamps, each repeated {1} times")
	@MethodSource("riskySampleCountsPair")
//	@Disabled("Slow test, only run occasionally.")
	void closestAtOrBefore(int uniqueTimestampsCount, int repititionCount) {
		
		int sampleCount = uniqueTimestampsCount * repititionCount;
		
		// populate with timestamps = 0,1,2,3,...
		for(int i = 0; i < uniqueTimestampsCount; i++)
			for(int j = 0; j < repititionCount; j++)
				connection.incrementSampleCountWithTimestamp(1, i);
		
		// verify responses
		for(int timestampN = 0; timestampN < uniqueTimestampsCount; timestampN++) {
			int sampleNumber = datasetsInterface.getClosestSampleNumberAtOrBefore(timestampN, sampleCount - 1);
			long timestamp = datasetsInterface.getTimestamp(sampleNumber);
			assertTrue(timestamp <= timestampN, "Got a sample that is not \"atOrBefore\"");
			int nextSampleNumber = sampleNumber + 1;
			if(nextSampleNumber < sampleCount) {
				long nextTimestamp = datasetsInterface.getTimestamp(nextSampleNumber);
				assertTrue(nextTimestamp > timestamp, "Got a sample that is not at the threshold.");
			}
		}
		
	}
	
	@DisplayName(value = "Closest Sample After")
	@ParameterizedTest(name = "With {0} unique timestamps, each repeated {1} times")
	@MethodSource("riskySampleCountsPair")
	@Disabled("Slow test, only run occasionally.")
	void closestAfter(int uniqueTimestampsCount, int repititionCount) {
		
		int sampleCount = uniqueTimestampsCount * repititionCount;
		assumeTrue(sampleCount > 1);
		
		// populate with timestamps = 0,1,2,3,...
		for(int i = 0; i < uniqueTimestampsCount; i++)
			for(int j = 0; j < repititionCount; j++)
				connection.incrementSampleCountWithTimestamp(1, i);
		
		// verify responses
		for(int timestampN = 0; timestampN < uniqueTimestampsCount - 1; timestampN++) {
			int sampleNumber = datasetsInterface.getClosestSampleNumberAfter(timestampN);
			long timestamp = datasetsInterface.getTimestamp(sampleNumber);
			assertTrue(timestamp > timestampN, "Got a sample that is not \"after\"");
			int previousSampleNumber = sampleNumber - 1;
			if(previousSampleNumber >= 0) {
				long previousTimestamp = datasetsInterface.getTimestamp(previousSampleNumber);
				assertTrue(previousTimestamp < timestamp, "Got a sample that is not at the threshold.");
			}
		}
		
	}
	
	@AfterEach
	void deleteCacheFiles() {
		
		datasetsInterface = null;
		connection.dispose();
		
	}

}
