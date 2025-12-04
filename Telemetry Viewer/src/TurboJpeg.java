import java.io.File;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.file.Paths;

public class TurboJpeg {
	
	/**
	 * Dev notes:
	 * 
	 * Download: https://github.com/libjpeg-turbo/libjpeg-turbo/releases/download/3.1.2/libjpeg-turbo-3.1.2-vc-x64.exe
	 *           (or check for newer versions: https://github.com/libjpeg-turbo/libjpeg-turbo/releases)
	 * 
	 * Run the EXE and have it "install" (it just extracts) to your Desktop or wherever.
	 * DLL location:    libjpeg-turbo64/bin/turbojpeg.dll
	 * Header location: libjpeg-turbo64/include/turbojpeg.h
	 * 
	 * Use jextract to list the various functions and macros available:
	 * $ cd Desktop
	 * $ jextract libjpeg-turbo64/include/turbojpeg.h --dump-includes dump.txt
	 * 
	 * Examine "dump.txt" and decide what functions and macros you want to call from Java
	 * Currently using these functions and macros:
	 * --include-function tjInitCompress      // creates handle
	 * --include-function tjCompress2         // raw -> jpeg
	 * --include-function tjInitDecompress    // creates handle
	 * --include-function tjDecompressHeader2 // get image width, height, etc.
	 * --include-function tjDecompress2       // jpeg -> raw
	 * --include-function tjDestroy           // closes handle
	 * --include-function tjFree              // frees image buffer allocated by turbojpeg
	 * --include-constant TJPF_BGR            // "pixelFormat"
	 * --include-constant TJPF_RGB            // "pixelFormat"
	 * --include-constant TJSAMP_422          // "jpegSubsamp"
	 * --include-constant TJFLAG_FASTDCT      // "flags"
	 * 
	 * Use jextract to create the Java glue class and call it "TurboJpegDLL.java":
	 * $ jextract turbojpeg.h --include-function tjInitCompress --include-function tjCompress2 --include-function tjInitDecompress --include-function tjDecompressHeader2 --include-function tjDecompress2 --include-function tjDestroy --include-function tjFree --include-constant TJPF_BGR --include-constant TJPF_RGB --include-constant TJSAMP_422 --include-constant TJFLAG_FASTDCT --header-class-name TurboJpegDLL
	 * 
	 * Move TurboJpegDLL.java to /src/
	 * Move turbojpeg.dll     to /lib/turbojpeg-x64.dll
	 * Refresh the project in Eclipse
	 * Write this class to make it easier to use TurboJpeg...
	 */

	static final boolean enabled;
	static {
		boolean success = false;
		try {
			String jarDirectory = new File(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath()).getParentFile().getPath();
			String osName = System.getProperty("os.name").toLowerCase();
			String osArch = System.getProperty("os.arch").toLowerCase();
			String libraryFilename = osName.startsWith("windows") && osArch.equals("amd64") ? "turbojpeg-x64.dll"   :
			                         osName.startsWith("windows") && osArch.equals("arm64") ? "turbojpeg-arm64.dll" : // not yet implemented
			                         osName.startsWith("linux")   && osArch.equals("amd64") ? "turbojpeg-x64.so"    : // not yet implemented
			                         osName.startsWith("linux")   && osArch.equals("arm64") ? "turbojpeg-arm64.so"  : // not yet implemented
			                                                                                  "";
			System.load(Paths.get(jarDirectory, "lib", libraryFilename).toString());
			success = true;
		} catch(Exception | Error e) {
			success = false;
		} finally {
			enabled = success;
		}
	}
	
	public record Image(boolean valid, byte[] bytes, int byteCount, int width, int height) {
		public static Image error() {
			return new Image(false, null, 0, 0, 0);
		}
	}
	
	/**
	 * Compresses an image.
	 * 
	 * @param rawBytes    Raw image, as RGB24 or BGR24.
	 * @param width       Width, in pixels.
	 * @param height      Height, in pixels.
	 * @param isRGB       True if RGB24, false if BGR24.
	 * @return            The JPEG, as a record. Check the "valid" field before use.
	 */
	public static Image compress(byte[] rawBytes, int width, int height, boolean isRGB) {
		
		if(!enabled)
			return Image.error();
		
		try(Arena arena = Arena.ofConfined()) {
		
			MemorySegment handle = TurboJpegDLL.tjInitCompress();
			if(handle.equals(MemorySegment.NULL))
				return Image.error();
			
			MemorySegment jpegBufferPtrPtr = arena.allocate(TurboJpegDLL.C_POINTER); // pointer to a pointer
			MemorySegment jpegByteCountPtr = arena.allocate(TurboJpegDLL.C_LONG);    // pointer to a long
			
			int code = TurboJpegDLL.tjCompress2(handle,
			                                    arena.allocateFrom(ValueLayout.JAVA_BYTE, rawBytes), // pointer to a copy of rawBytes[]
			                                    width,
			                                    0, // pitch
			                                    height,
			                                    isRGB ? TurboJpegDLL.TJPF_RGB() : TurboJpegDLL.TJPF_BGR(), // pixelFormat
			                                    jpegBufferPtrPtr, // will be populated with a pointer to the JPEG buffer alloc'd by TurboJpeg
			                                    jpegByteCountPtr, // pointer to unsigned long, will be populated with JPEG byte count
			                                    TurboJpegDLL.TJSAMP_422(), // subsamp
			                                    80, // quality
			                                    TurboJpegDLL.TJFLAG_FASTDCT()); // flags
			
			MemorySegment jpegBufferPtr = jpegBufferPtrPtr.get(TurboJpegDLL.C_POINTER, 0); // alloc'd by TurboJpeg
			
			if(code != 0) {
				if(!jpegBufferPtr.equals(MemorySegment.NULL))
					TurboJpegDLL.tjFree(jpegBufferPtr);
				TurboJpegDLL.tjDestroy(handle);
				return Image.error();
			}
			
			int jpegByteCount = jpegByteCountPtr.get(TurboJpegDLL.C_LONG, 0);
			MemorySegment jpegBuffer = jpegBufferPtrPtr.get(TurboJpegDLL.C_POINTER, 0).reinterpret(jpegByteCount);
			byte[] jpegBytes = jpegBuffer.toArray(ValueLayout.JAVA_BYTE);
			
			TurboJpegDLL.tjFree(jpegBufferPtr);
			TurboJpegDLL.tjDestroy(handle);
			
			return new Image(true, jpegBytes, jpegByteCount, width, height);
			
		}
		
	}
	
	/**
	 * Decompresses an image.
	 * 
	 * @param jpegBytes    JPEG image.
	 * @return             The raw image, as a record. Check the "valid" field before use. Pixel format will be BGR24.
	 */
	public static Image decompress(byte[] jpegBytes) {
		
		if(!enabled)
			return Image.error();
		
		try(Arena arena = Arena.ofConfined()) {
		
			MemorySegment handle = TurboJpegDLL.tjInitDecompress();
			if(handle.equals(MemorySegment.NULL))
				return Image.error();
			
			MemorySegment jpegBuffer   = arena.allocateFrom(ValueLayout.JAVA_BYTE, jpegBytes); // copy of jpegBytes[]
			MemorySegment widthPtr     = arena.allocate(TurboJpegDLL.C_INT);                   // pointer to an int
			MemorySegment heightPtr    = arena.allocate(TurboJpegDLL.C_INT);                   // pointer to an int
			MemorySegment subsamplePtr = arena.allocate(TurboJpegDLL.C_INT);                   // pointer to an int
			
			int code = TurboJpegDLL.tjDecompressHeader2(handle,
			                                            jpegBuffer,
			                                            (int) jpegBuffer.byteSize(),
			                                            widthPtr,
			                                            heightPtr,
			                                            subsamplePtr);
			
			if(code != 0) {
				TurboJpegDLL.tjDestroy(handle);
				return Image.error();
			}
			
			int width  = widthPtr.get(TurboJpegDLL.C_INT, 0);
			int height = heightPtr.get(TurboJpegDLL.C_INT, 0);
			MemorySegment buffer = arena.allocate(width * height * 3);
		
			code = TurboJpegDLL.tjDecompress2(handle,
			                                  jpegBuffer,
			                                  (int) jpegBuffer.byteSize(),
			                                  buffer,
			                                  width,
			                                  0, // pitch
			                                  height,
			                                  TurboJpegDLL.TJPF_BGR(),
			                                  0);

			if(code != 0) {
				TurboJpegDLL.tjDestroy(handle);
				return Image.error();
			}
			
			TurboJpegDLL.tjDestroy(handle);
			
			byte[] rawBytes = buffer.toArray(ValueLayout.JAVA_BYTE);
			return new Image(true, rawBytes, rawBytes.length, width, height);
			
		}
		
	}
	
}
