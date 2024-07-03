import static org.junit.jupiter.api.Assertions.*;

import java.awt.Dimension;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WidgetTextfieldTest {
	
	JFrame window;
	JPanel panel;
	WidgetTextfield<String> textfieldOfText;
	
	JTextField swingTextfield;
	AtomicInteger counter;
	
	@BeforeEach
	void before() throws InvocationTargetException, InterruptedException {
		SwingUtilities.invokeAndWait(() -> {
			try {
				UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
				UIManager.setLookAndFeel("com.sun.java.swing.plaf.gtk.GTKLookAndFeel");
				UIManager.put("Slider.paintValue", false);
			} catch(Exception e) {}
			counter = new AtomicInteger(0);
			window = new JFrame("Widget Textfield Test");
			panel = new JPanel();
			textfieldOfText = WidgetTextfield.ofText("");
			textfieldOfText.appendTo(panel, "");
			window.add(panel);
			window.pack();
			window.setVisible(true);
			window.setLocationRelativeTo(null);
			
			try {
				var field = WidgetTextfield.class.getDeclaredField("textfield");
				field.setAccessible(true);
				swingTextfield = (JTextField) field.get(textfieldOfText);
			} catch(Exception e) {}
		});
	}
	
	@AfterEach
	void after() throws InvocationTargetException, InterruptedException {
		SwingUtilities.invokeAndWait(() -> {
			window.dispose();
		});
	}
	
	Dimension size1 = null;
	Dimension size2 = null;
	
	@Test
	void prefSizeChangesByDefault() throws InterruptedException, InvocationTargetException {
		SwingUtilities.invokeAndWait(() -> {
			textfieldOfText.set("abc123");
			size1 = swingTextfield.getPreferredSize();
		});
		Thread.sleep(100);
		SwingUtilities.invokeAndWait(() -> {
			textfieldOfText.set("abc1234");
			size2 = swingTextfield.getPreferredSize();
			if(size1.width >= size2.width)
				fail("textfield width didn't increase");
		});
	}
	
	@Test
	void prefSizeDoesntChangeIfFixedWidth() throws InterruptedException, InvocationTargetException {
		SwingUtilities.invokeAndWait(() -> {
			textfieldOfText.setFixedWidth(10);
			textfieldOfText.set("abc123");
			size1 = swingTextfield.getPreferredSize();
		});
		Thread.sleep(100);
		SwingUtilities.invokeAndWait(() -> {
			textfieldOfText.set("abc123456789");
			size2 = swingTextfield.getPreferredSize();
			if(size1.width != size2.width)
				fail("textfield width increased");
		});
	}
	
	@Test
	void setWorksCorrectlyInTextMode() throws InterruptedException, InvocationTargetException {
		SwingUtilities.invokeAndWait(() -> {
			textfieldOfText.set("abc123");
			size1 = swingTextfield.getPreferredSize();
		});
		Thread.sleep(100);
		SwingUtilities.invokeAndWait(() -> {
			textfieldOfText.set("abc1234");
			size2 = swingTextfield.getPreferredSize();
			if(size1.width >= size2.width)
				fail("textfield width didn't increase");
		});
	}
	
	@Test
	void onChangeHandlerCalledShortlyAfterRegistration() throws InvocationTargetException, InterruptedException {
		SwingUtilities.invokeAndWait(() -> {
			textfieldOfText.onChange((newText, oldText) -> { counter.incrementAndGet(); return true; });
			if(counter.get() != 0)
				fail("onChange handler was called immediately");
		});
		Thread.sleep(100);
		if(counter.get() != 1)
			fail("onChange handler was not called shortly after registration");
	}
	
	@Test
	void onChangeHandlerCalledOnlyOnce() throws InvocationTargetException, InterruptedException {
		SwingUtilities.invokeAndWait(() -> {
			textfieldOfText.onChange((newText, oldText) -> { counter.incrementAndGet(); return true; });
			textfieldOfText.set("abc");
			if(counter.get() != 1)
				fail("onChange handler was not called immediately by set()");
		});
		Thread.sleep(100);
		if(counter.get() != 1)
			fail("onChange handler was called more than once");
	}
	
	@Test
	void onChangeHandlerCalledOnlyOnActualChanges() throws InvocationTargetException, InterruptedException {
		SwingUtilities.invokeAndWait(() -> {
			textfieldOfText.onChange((newText, oldText) -> { counter.incrementAndGet(); return true; });
			textfieldOfText.set("abc");
			if(counter.get() != 1)
				fail("onChange handler was not called as expected");
			textfieldOfText.set("abc");
			textfieldOfText.set("abc");
			if(counter.get() != 1)
				fail("onChange handler was not called as expected");
			textfieldOfText.set("123");
			if(counter.get() != 2)
				fail("onChange handler was not called as expected");
			textfieldOfText.set("abc");
			if(counter.get() != 3)
				fail("onChange handler was not called as expected");
		});
	}

}
