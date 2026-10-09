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
    void configurationAndApplyShareTheWholePage() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    for (double scale : new double[] {1, 1.25, 1.5}) {
                        var tabs =
                                BookmapOrderflowExporter.buildExporterTabs(
                                        new BookmapOrderflowExporter.Settings(), null, null);
                        BridgeConfigurationLayoutTest.fonts(tabs, scale);
                        assertEquals(3, tabs.getTabCount());
                        assertEquals("Configuration", tabs.getTitleAt(0));
                        assertEquals("Live status", tabs.getTitleAt(1));
                        assertEquals("Information", tabs.getTitleAt(2));
                        assertEquals(0, tabs.getSelectedIndex());
                        var config = (Container) tabs.getComponentAt(0);
                        assertNull(
                                find(config, JScrollPane.class, null),
                                "no nested configuration scroll box");
                        assertNotNull(
                                find(config, JButton.class, "Apply settings / restart exporter"));
                        var info = (Container) tabs.getComponentAt(2);
                        assertTrue(find(info, JLabel.class, null).getText().contains("v0.5a"));
                        assertNotNull(find(info, JButton.class, "Installation / help"));
                        var statusScroll =
                                find((Container) tabs.getComponentAt(1), JScrollPane.class, null);
                        assertNotNull(statusScroll, "existing status scrolling retained");
                        ((JLabel) statusScroll.getViewport().getView())
                                .setText(
                                        "<html>"
                                                + "Synthetic status line<br>".repeat(80)
                                                + "</html>");
                        tabs.setSelectedIndex(1);
                        tabs.setSize(560, tabs.getPreferredSize().height);
                        BridgeConfigurationLayoutTest.layout(tabs);
                        assertTrue(statusScroll.getViewport().getHeight() > 0);
                        assertTrue(statusScroll.getVerticalScrollBar().isVisible());
                        var status = (Container) tabs.getComponentAt(1);
                        var refresh = find(status, JButton.class, "Refresh");
                        assertTrue(
                                refresh.getY() + refresh.getHeight()
                                        <= refresh.getParent().getHeight());
                    }
                });
    }

    @Test
    void bookmapOuterScrollReachesEveryFieldAndApply() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    for (double scale : new double[] {1, 1.25, 1.5}) {
                        for (int width : new int[] {400, 560, 800}) {
                            var panel =
                                    BookmapOrderflowExporter.getCustomDisabledSettingsPanels()[0];
                            BridgeConfigurationLayoutTest.fonts(panel, scale);
                            var host = new JPanel(new GridBagLayout());
                            var constraints = new GridBagConstraints();
                            constraints.gridx = constraints.gridy = 0;
                            constraints.weightx = 1;
                            constraints.fill = GridBagConstraints.HORIZONTAL;
                            constraints.insets = new Insets(5, 3, 5, 3);
                            host.add(panel, constraints);
                            constraints.gridy = 1;
                            constraints.weighty = 1;
                            host.add(Box.createVerticalBox(), constraints);
                            var outerScroll =
                                    new JScrollPane(
                                            host,
                                            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                                            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
                            outerScroll.setSize(width, 360);
                            // Bookmap supplies the host width; retain natural page height.
                            host.setSize(width - 24, host.getPreferredSize().height);
                            BridgeConfigurationLayoutTest.layout(host);
                            host.setPreferredSize(
                                    new Dimension(width - 24, host.getPreferredSize().height));
                            BridgeConfigurationLayoutTest.layout(outerScroll);
                            var tabs = find(panel, JTabbedPane.class, null);
                            assertTrue(tabs.isEnabled());
                            var config = (Container) tabs.getComponentAt(0);
                            var apply =
                                    find(
                                            config,
                                            JButton.class,
                                            "Apply settings / restart exporter");
                            assertFalse(apply.isEnabled());
                            assertTrue(outerScroll.getVerticalScrollBar().isVisible());
                            var fields = find(config, BridgeConfigurationLayout.Fields.class, null);
                            assertTrue(fields.getHeight() > 0);
                            int lastBottom = 0;
                            for (Component row : fields.getComponents()) {
                                assertTrue(row.getY() >= lastBottom, "rows overlap");
                                lastBottom = row.getY() + row.getHeight();
                                if (row instanceof BridgeConfigurationLayout.NetworkRow network)
                                    for (Component control : network.getComponents()) {
                                        assertEquals(control.getPreferredSize(), control.getSize());
                                        assertTrue(
                                                control.getY() + control.getHeight()
                                                        <= network.getHeight());
                                        assertTrue(
                                                control.getX() + control.getWidth()
                                                        <= network.getWidth(),
                                                "width="
                                                        + width
                                                        + " scale="
                                                        + scale
                                                        + " control="
                                                        + control.getClass().getSimpleName()
                                                        + " bounds="
                                                        + control.getBounds()
                                                        + " row="
                                                        + network.getSize());
                                    }
                            }
                            assertTrue(fields.getY() + lastBottom <= apply.getY());
                            var buttonBounds =
                                    SwingUtilities.convertRectangle(
                                            apply.getParent(), apply.getBounds(), host);
                            outerScroll
                                    .getViewport()
                                    .setViewPosition(
                                            new Point(
                                                    0,
                                                    Math.max(
                                                            0,
                                                            buttonBounds.y
                                                                    + buttonBounds.height
                                                                    - outerScroll
                                                                            .getViewport()
                                                                            .getHeight())));
                            assertTrue(
                                    outerScroll.getViewport().getViewRect().contains(buttonBounds),
                                    "Apply must be fully reachable by outer scrolling");
                            assertTrue(
                                    find(
                                                    (Container) tabs.getComponentAt(2),
                                                    JButton.class,
                                                    "Installation / help")
                                            .isEnabled());
                            tabs.setSelectedIndex(2);
                            BridgeConfigurationLayoutTest.layout(outerScroll);
                            var infoText =
                                    find((Container) tabs.getComponentAt(2), JTextArea.class, null);
                            assertTrue(
                                    infoText.getHeight()
                                            >= infoText.getUI()
                                                    .getRootView(infoText)
                                                    .getPreferredSpan(javax.swing.text.View.Y_AXIS),
                                    "wrapped Information text must remain fully readable");
                            tabs.setSelectedIndex(0);
                            BridgeConfigurationLayoutTest.layout(outerScroll);
                            String out = System.getenv("ORDERFLOW_TAB_PREVIEWS");
                            if (out != null && width == 560) {
                                try {
                                    Files.createDirectories(Path.of(out));
                                    var bottom =
                                            new BufferedImage(
                                                    width, 360, BufferedImage.TYPE_INT_RGB);
                                    var bottomGraphics = bottom.createGraphics();
                                    outerScroll.paint(bottomGraphics);
                                    bottomGraphics.dispose();
                                    ImageIO.write(
                                            bottom,
                                            "png",
                                            Path.of(out, "page-bottom-scale-" + scale + ".png")
                                                    .toFile());
                                    for (int index : new int[] {0, 2}) {
                                        tabs.setSelectedIndex(index);
                                        BridgeConfigurationLayoutTest.layout(outerScroll);
                                        outerScroll.getViewport().setViewPosition(new Point(0, 0));
                                        var image =
                                                new BufferedImage(
                                                        width, 360, BufferedImage.TYPE_INT_RGB);
                                        var graphics = image.createGraphics();
                                        outerScroll.paint(graphics);
                                        graphics.dispose();
                                        ImageIO.write(
                                                image,
                                                "png",
                                                Path.of(
                                                                out,
                                                                "page-" + index + "-scale-" + scale
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
