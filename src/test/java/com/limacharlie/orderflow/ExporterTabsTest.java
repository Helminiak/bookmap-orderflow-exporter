package com.limacharlie.orderflow;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import velox.api.layer1.simplified.Api;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;
import javax.swing.*;

class ExporterTabsTest {
    static <T extends Component> T find(Container parent, Class<T> type, String text) {
        for (Component child : parent.getComponents()) {
            if (type.isInstance(child)
                    && (text == null
                            || child instanceof AbstractButton b && text.equals(b.getText())))
                return type.cast(child);
            if (child instanceof Container c) {
                T found = find(c, type, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test
    void configurationIsFirstAndBothTabsRemainScrollable() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    for (double scale : new double[] {1, 1.25, 1.5}) {
                        var tabs =
                                BookmapOrderflowExporter.buildExporterTabs(
                                        new BookmapOrderflowExporter.Settings(), null, null);
                        assertEquals(2, tabs.getTabCount());
                        assertEquals("Configuration", tabs.getTitleAt(0));
                        assertEquals("Live status", tabs.getTitleAt(1));
                        assertEquals(0, tabs.getSelectedIndex());
                        BridgeConfigurationLayoutTest.fonts(tabs, scale);
                        tabs.setSize(560, 360);
                        for (int index = 0; index < 2; index++) {
                            tabs.setSelectedIndex(index);
                            if (index == 1) {
                                var statusScroll =
                                        find(
                                                (Container) tabs.getComponentAt(index),
                                                JScrollPane.class,
                                                null);
                                ((JLabel) statusScroll.getViewport().getView())
                                        .setText(
                                                "<html>"
                                                        + "Synthetic status line<br>".repeat(80)
                                                        + "</html>");
                            }
                            BridgeConfigurationLayoutTest.layout(tabs);
                            var body = (Container) tabs.getComponentAt(index);
                            var scroll = find(body, JScrollPane.class, null);
                            assertNotNull(scroll);
                            assertTrue(scroll.getViewport().getHeight() > 0);
                            if (index == 0) {
                                var apply =
                                        find(
                                                body,
                                                JButton.class,
                                                "Apply settings / restart exporter");
                                assertNotNull(apply);
                                assertSame(body, apply.getParent());
                                assertTrue(apply.getY() + apply.getHeight() <= body.getHeight());
                                assertTrue(scroll.getVerticalScrollBar().isVisible());
                                var fields = (Container) scroll.getViewport().getView();
                                for (Component row : fields.getComponents())
                                    if (row instanceof BridgeConfigurationLayout.NetworkRow network)
                                        for (Component control : network.getComponents()) {
                                            assertTrue(
                                                    control.getY() + control.getHeight()
                                                            <= network.getHeight());
                                            assertTrue(
                                                    control.getX() + control.getWidth()
                                                            <= network.getWidth());
                                            assertEquals(
                                                    control.getPreferredSize(), control.getSize());
                                        }
                            }
                            if (index == 1) assertTrue(scroll.getVerticalScrollBar().isVisible());
                            String out = System.getenv("ORDERFLOW_TAB_PREVIEWS");
                            if (out != null) {
                                try {
                                    var image =
                                            new BufferedImage(
                                                    tabs.getWidth(),
                                                    tabs.getHeight(),
                                                    BufferedImage.TYPE_INT_RGB);
                                    var graphics = image.createGraphics();
                                    tabs.paint(graphics);
                                    graphics.dispose();
                                    Files.createDirectories(Path.of(out));
                                    ImageIO.write(
                                            image,
                                            "png",
                                            Path.of(
                                                            out,
                                                            "tab-" + index + "-scale-" + scale
                                                                    + ".png")
                                                    .toFile());
                                    if (index == 0) {
                                        scroll.getViewport()
                                                .setViewPosition(
                                                        new Point(
                                                                0,
                                                                scroll.getViewport()
                                                                                .getView()
                                                                                .getHeight()
                                                                        - scroll.getViewport()
                                                                                .getHeight()));
                                        graphics = image.createGraphics();
                                        tabs.paint(graphics);
                                        graphics.dispose();
                                        ImageIO.write(
                                                image,
                                                "png",
                                                Path.of(
                                                                out,
                                                                "config-bottom-scale-"
                                                                        + scale
                                                                        + ".png")
                                                        .toFile());
                                    }
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        }
                    }
                });
    }

    @Test
    void applyStillSavesEditedSettingsAndReloadsOnce() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    var settings = new BookmapOrderflowExporter.Settings();
                    var saves = new AtomicInteger();
                    var reloads = new AtomicInteger();
                    Api api =
                            (Api)
                                    Proxy.newProxyInstance(
                                            Api.class.getClassLoader(),
                                            new Class<?>[] {Api.class},
                                            (proxy, method, args) -> {
                                                if (method.getName().equals("setSettings")) {
                                                    assertSame(settings, args[0]);
                                                    saves.incrementAndGet();
                                                }
                                                if (method.getName().equals("reload"))
                                                    reloads.incrementAndGet();
                                                return null;
                                            });
                    var tabs = BookmapOrderflowExporter.buildExporterTabs(settings, api, null);
                    var config = (Container) tabs.getComponentAt(0);
                    var bridge = find(config, JCheckBox.class, "Live Linux bridge (optional)");
                    bridge.setSelected(true);
                    find(config, JButton.class, "Apply settings / restart exporter").doClick();
                    assertTrue(settings.bridgeEnabled);
                    assertEquals(1, saves.get());
                    assertEquals(1, reloads.get());
                });
    }
}
