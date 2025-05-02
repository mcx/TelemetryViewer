import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

public class Charts {
	
	final private static List<Chart> list = Collections.synchronizedList(new ArrayList<Chart>());
	
	enum Type {
		TIME_DOMAIN      ("Time Domain",      (name, x1, y1, x2, y2) -> new OpenGLTimeDomainChart(name, x1, y1, x2, y2)),
		FREQUENCY_DOMAIN ("Frequency Domain", (name, x1, y1, x2, y2) -> new OpenGLFrequencyDomainChart(name, x1, y1, x2, y2)),
		HISTOGRAM        ("Histogram",        (name, x1, y1, x2, y2) -> new OpenGLHistogramChart(name, x1, y1, x2, y2)),
		STATISTICS       ("Statistics",       (name, x1, y1, x2, y2) -> new OpenGLStatisticsChart(name, x1, y1, x2, y2)),
		DIAL             ("Dial",             (name, x1, y1, x2, y2) -> new OpenGLDialChart(name, x1, y1, x2, y2)),
		QUATERNION       ("Quaternion",       (name, x1, y1, x2, y2) -> new OpenGLQuaternionChart(name, x1, y1, x2, y2)),
		CAMERA           ("Camera",           (name, x1, y1, x2, y2) -> new OpenGLCameraChart(name, x1, y1, x2, y2)),
		TIMELINE         ("Timeline",         (name, x1, y1, x2, y2) -> new OpenGLTimelineChart(name, x1, y1, x2, y2));
		
		public Chart createAt(int x1, int y1, int x2, int y2) {
			Chart chart = ctor.apply(name, x1, y1, x2, y2);
			list.add(chart);
			return chart;
		}
		
		@Override public String toString() {
			return name;
		}
		
		private final String name;
		private final ChartSupplier ctor;
		private Type(String name, ChartSupplier ctor) {
			this.name = name;
			this.ctor = ctor;
		}
		private interface ChartSupplier {
			public Chart apply(String name, int x1, int y1, int x2, int y2);
		}
	}
	
	public static Stream<Chart> stream()                                { return list.stream();   }
	public static boolean       exist()                                 { return !list.isEmpty(); }
	public static int           count()                                 { return list.size();     }
	public static void          forEach(Consumer<Chart> consumer)       { list.forEach(consumer); }
	public static void          remove(Chart chart)                     { list.remove(chart); chart.dispose(); }
	public static void          removeAll()                             { stream().toList().forEach(chart -> remove(chart)); }
	public static void          removeIf(Function<Chart, Boolean> test) { stream().filter(chart -> test.apply(chart)).toList().forEach(chart -> remove(chart)); }
	public static void          drawChartLast(Chart chart)              { Collections.swap(list, list.indexOf(chart), list.size() - 1); }
	
}
