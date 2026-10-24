package com.limacharlie.orderflow;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import org.junit.jupiter.api.Test;

class ConfigurationRefreshTest {
  private static class TestFocusManager extends DefaultKeyboardFocusManager {
    void focus(Component component) {
      setGlobalFocusOwner(component);
    }
  }

  @Test
  void enabledConfigurationIgnoresHiddenStatusRefreshAndRetainsEditingAndScroll() throws Exception {
    var previousFocusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
    var focusManager = new TestFocusManager();
    KeyboardFocusManager.setCurrentKeyboardFocusManager(focusManager);
    try {
      var changes = new AtomicInteger();
      var exporter = new BookmapOrderflowExporter();
      var settings = new BookmapOrderflowExporter.Settings();
      Method refresh =
          BookmapOrderflowExporter.class.getDeclaredMethod("scheduleStatusRefresh", boolean.class);
      refresh.setAccessible(true);
      var tabsRef = new JTabbedPane[1];
      var outerRef = new JScrollPane[1];
      var fieldRef = new JTextField[1];
      var position = new Point[1];
      SwingUtilities.invokeAndWait(
          () -> {
            var tabs =
                BookmapOrderflowExporter.buildExporterTabs(
                    settings, ExporterTest.api(settings), exporter);
            tabsRef[0] = tabs;
            var outer = new JScrollPane(tabs);
            outerRef[0] = outer;
            outer.setSize(560, 300);
            tabs.setSize(530, tabs.getPreferredSize().height);
            BridgeConfigurationLayoutTest.layout(outer);
            outer.getViewport().setViewPosition(new Point(0, 250));
            position[0] = outer.getViewport().getViewPosition();
            assertTrue(position[0].y > 0);
            var field =
                ExporterTabsTest.find((Container) tabs.getComponentAt(0), JTextField.class, null);
            fieldRef[0] = field;
            field.setText("unfinished settings");
            field.select(2, 7);
            focusManager.focus(field);
            assertSame(field, focusManager.getFocusOwner());
            var status =
                ExporterTabsTest.find((Container) tabs.getComponentAt(1), JLabel.class, null);
            status.addPropertyChangeListener("text", e -> changes.incrementAndGet());
          });
      // Let Swing finish initial construction/edit invalidations before measuring refresh effects.
      SwingUtilities.invokeAndWait(
          () -> {
            BridgeConfigurationLayoutTest.layout(outerRef[0]);
            outerRef[0].getViewport().setViewPosition(new Point(0, 100));
            position[0] = outerRef[0].getViewport().getViewPosition();
            assertTrue(position[0].y > 0);
            changes.set(0);
            for (int i = 0; i < 50; i++) {
              exporter.onTimestamp(i + 1);
              try {
                refresh.invoke(exporter, true);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            }
          });
      SwingUtilities.invokeAndWait(
          () -> {
            assertEquals(
                0, changes.get(), "hidden HTML status must not invalidate configuration layout");
            assertEquals(position[0], outerRef[0].getViewport().getViewPosition());
            assertEquals(0, tabsRef[0].getSelectedIndex());
            assertSame(
                fieldRef[0], focusManager.getFocusOwner(), "refresh must retain focus owner");
            assertEquals("unfinished settings", fieldRef[0].getText());
            assertEquals(2, fieldRef[0].getSelectionStart());
            assertEquals(7, fieldRef[0].getSelectionEnd());
            tabsRef[0].setSelectedIndex(1);
            assertTrue(
                changes.get() > 0, "opening Live status must immediately show fresh diagnostics");
            var status =
                ExporterTabsTest.find((Container) tabsRef[0].getComponentAt(1), JLabel.class, null);
            assertTrue(status.getText().contains("1970"));
            assertNotNull(
                ExporterTabsTest.find(
                    (Container) tabsRef[0].getComponentAt(0),
                    JButton.class,
                    "Apply settings / restart exporter"));
          });
    } finally {
      KeyboardFocusManager.setCurrentKeyboardFocusManager(previousFocusManager);
    }
  }
}
