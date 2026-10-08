package com.limacharlie.orderflow;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.awt.Component;
import java.awt.Container;
import java.awt.Rectangle;

import javax.swing.*;

class ConfigLayoutTest {
    static void layout(Container parent) {
        parent.doLayout();
        for (Component child : parent.getComponents()) if (child instanceof Container c) layout(c);
    }

    static <T> T find(Container parent, Class<T> kind) {
        for (Component child : parent.getComponents()) {
            if (kind.isInstance(child)) return kind.cast(child);
            if (child instanceof Container c) {
                T found = find(c, kind);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test
    void narrowPanelKeepsEachBridgeControlReachable() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    var panel =
                            BookmapOrderflowExporter.buildConfigurationPanel(
                                    new BookmapOrderflowExporter.Settings(), null);
                    panel.setSize(240, 340);
                    layout(panel);
                    JScrollPane scroll = find(panel, JScrollPane.class);
                    assertNotNull(scroll);
                    var fields = (Container) scroll.getViewport().getView();
                    assertTrue(fields.getHeight() > scroll.getViewport().getHeight());
                    int width = scroll.getViewport().getWidth(), found = 0;
                    for (Component child : fields.getComponents()) {
                        if (!(child instanceof JPanel row)) continue;
                        JLabel label = find(row, JLabel.class);
                        if (label == null
                                || !java.util.Set.of(
                                                "Bind address",
                                                "Market port (TCP)",
                                                "Health port (TCP)",
                                                "Bridge queue (events)")
                                        .contains(label.getText())) continue;
                        var input = label.getLabelFor();
                        assertNotNull(input);
                        Rectangle bounds =
                                SwingUtilities.convertRectangle(row, input.getBounds(), fields);
                        assertTrue(
                                bounds.x >= 0 && bounds.x + bounds.width <= width, label.getText());
                        assertTrue(input.getWidth() >= 100, label.getText());
                        ((JComponent) fields).scrollRectToVisible(bounds);
                        assertTrue(
                                scroll.getViewport().getViewRect().contains(bounds),
                                label.getText());
                        found++;
                    }
                    assertEquals(4, found);
                    JButton apply = null;
                    for (Component child : panel.getComponents())
                        if (child instanceof JButton button) apply = button;
                    assertNotNull(apply);
                    assertTrue(apply.getBounds().y + apply.getHeight() <= panel.getHeight());
                });
    }

    @Test
    void onePanelOffersConfigurationFirstAndScrollableStatus() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    var tabs =
                            BookmapOrderflowExporter.buildExporterTabs(
                                    new BookmapOrderflowExporter.Settings(), null, null);
                    assertEquals(2, tabs.getTabCount());
                    assertEquals("Configuration", tabs.getTitleAt(0));
                    assertEquals("Status", tabs.getTitleAt(1));
                    assertEquals(0, tabs.getSelectedIndex());
                    assertNotNull(find((Container) tabs.getComponentAt(0), JScrollPane.class));
                    assertTrue(tabs.getComponentAt(1) instanceof JScrollPane);
                });
    }
}
