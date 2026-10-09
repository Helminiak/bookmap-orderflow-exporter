package com.limacharlie.orderflow;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import velox.api.layer1.simplified.Api;

import java.awt.Container;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.*;

/** Production settings wiring with a proxy Bookmap API; no installed add-on actions. */
class BridgeRecoveryApplyIntegrationTest {
    @Test
    void realBookmapHostScrollReachesRecoveryAndApplyWithoutChangingInvalid() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    for (boolean enabled : new boolean[] {false, true}) {
                        for (double scale : new double[] {1, 1.25, 1.5}) {
                            var settings = new BookmapOrderflowExporter.Settings();
                            settings.bridgeEnabled = true;
                            var saves = new AtomicInteger();
                            Api api =
                                    enabled
                                            ? (Api)
                                                    Proxy.newProxyInstance(
                                                            Api.class.getClassLoader(),
                                                            new Class<?>[] {Api.class},
                                                            (proxy, method, args) -> {
                                                                if (method.getName()
                                                                                .equals(
                                                                                        "setSettings")
                                                                        || method.getName()
                                                                                .equals("reload"))
                                                                    saves.incrementAndGet();
                                                                return null;
                                                            })
                                            : null;
                            var exporter = new BookmapOrderflowExporter();
                            String failure = "SYNTHETIC INVALID diagnostic ".repeat(100);
                            try {
                                var field =
                                        BookmapOrderflowExporter.class.getDeclaredField(
                                                "bridgeFailure");
                                field.setAccessible(true);
                                field.set(exporter, failure);
                            } catch (ReflectiveOperationException e) {
                                throw new AssertionError(e);
                            }
                            var panel =
                                    BookmapOrderflowExporter.buildPanels(settings, api, exporter)[
                                            0];
                            var recovery =
                                    ExporterTabsTest.find(
                                            panel, BridgeRecoveryControls.class, null);
                            var notice =
                                    ExporterTabsTest.find(panel, BridgeOperatorNotice.class, null);
                            var apply =
                                    ExporterTabsTest.find(
                                            panel,
                                            JButton.class,
                                            "Apply settings / restart exporter");
                            assertNotNull(recovery);
                            assertEquals(enabled, recovery.archiveOnlyButton().isEnabled());
                            assertEquals(enabled, apply.isEnabled());
                            assertTrue(
                                    recovery.pendingText().isEnabled(),
                                    "read/copy remains usable when disabled");
                            BridgeConfigurationLayoutTest.fonts(panel, scale);
                            var host = new JPanel(new java.awt.GridBagLayout());
                            var constraints = new java.awt.GridBagConstraints();
                            constraints.weightx = 1;
                            constraints.fill = java.awt.GridBagConstraints.HORIZONTAL;
                            host.add(panel, constraints);
                            var scroll =
                                    new JScrollPane(
                                            host,
                                            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                                            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
                            for (int width : new int[] {400, 560, 800, 400}) {
                                scroll.setSize(width, 360);
                                for (int pass = 0; pass < 4; pass++) {
                                    host.setPreferredSize(null);
                                    host.setSize(width - 24, host.getPreferredSize().height);
                                    BridgeConfigurationLayoutTest.layout(host);
                                    host.setPreferredSize(
                                            new java.awt.Dimension(
                                                    width - 24, host.getPreferredSize().height));
                                    BridgeConfigurationLayoutTest.layout(scroll);
                                }
                                for (JButton target :
                                        new JButton[] {recovery.archiveOnlyButton(), apply}) {
                                    var bounds =
                                            SwingUtilities.convertRectangle(
                                                    target.getParent(), target.getBounds(), host);
                                    scroll.getViewport()
                                            .setViewPosition(
                                                    new java.awt.Point(
                                                            0,
                                                            Math.max(
                                                                    0,
                                                                    bounds.y
                                                                            + bounds.height
                                                                            - scroll.getViewport()
                                                                                    .getHeight())));
                                    assertTrue(
                                            scroll.getViewport().getViewRect().contains(bounds),
                                            "production action must be fully reachable: " + bounds);
                                }
                                if (enabled) recovery.archiveOnlyButton().doClick();
                                for (int pass = 0; pass < 4; pass++) {
                                    host.setPreferredSize(null);
                                    host.setSize(width - 24, host.getPreferredSize().height);
                                    BridgeConfigurationLayoutTest.layout(host);
                                    host.setPreferredSize(
                                            new java.awt.Dimension(
                                                    width - 24, host.getPreferredSize().height));
                                    BridgeConfigurationLayoutTest.layout(scroll);
                                }
                                var text = recovery.pendingText();
                                try {
                                    var last =
                                            text.modelToView2D(text.getDocument().getLength() - 1);
                                    var glyphBounds =
                                            SwingUtilities.convertRectangle(
                                                    text, last.getBounds(), host);
                                    scroll.getViewport()
                                            .setViewPosition(
                                                    new java.awt.Point(
                                                            0,
                                                            Math.max(
                                                                    0,
                                                                    glyphBounds.y
                                                                            + glyphBounds.height
                                                                            - scroll.getViewport()
                                                                                    .getHeight())));
                                    assertTrue(
                                            text.getVisibleRect().contains(last.getBounds()),
                                            "pending explanation must fit: width="
                                                    + width
                                                    + " scale="
                                                    + scale
                                                    + " glyph="
                                                    + last
                                                    + " visible="
                                                    + text.getVisibleRect()
                                                    + " recovery="
                                                    + recovery.getBounds());
                                } catch (javax.swing.text.BadLocationException e) {
                                    throw new AssertionError(e);
                                }
                                assertEquals(0, saves.get());
                                assertTrue(settings.bridgeEnabled, "staging never writes settings");
                                assertEquals(
                                        BridgeOperatorNotice.failure(failure),
                                        notice.displayed(),
                                        "failure remains unchanged");
                                var image =
                                        new java.awt.image.BufferedImage(
                                                width,
                                                360,
                                                java.awt.image.BufferedImage.TYPE_INT_RGB);
                                var graphics = image.createGraphics();
                                scroll.paint(graphics);
                                graphics.dispose();
                                String output = System.getenv("ORDERFLOW_RECOVERY_PREVIEWS");
                                if (output != null && enabled && width == 560) {
                                    try {
                                        var dir = java.nio.file.Path.of(output);
                                        java.nio.file.Files.createDirectories(dir);
                                        var bounds =
                                                SwingUtilities.convertRectangle(
                                                        recovery.getParent(),
                                                        recovery.getBounds(),
                                                        host);
                                        scroll.getViewport()
                                                .setViewPosition(
                                                        new java.awt.Point(
                                                                0, Math.max(0, bounds.y - 32)));
                                        var g = image.createGraphics();
                                        scroll.paint(g);
                                        g.dispose();
                                        javax.imageio.ImageIO.write(
                                                image,
                                                "png",
                                                dir.resolve(
                                                                "integrated-pending-font-"
                                                                        + scale
                                                                        + ".png")
                                                        .toFile());
                                        scroll.getViewport()
                                                .setViewPosition(new java.awt.Point(0, 0));
                                        g = image.createGraphics();
                                        scroll.paint(g);
                                        g.dispose();
                                        javax.imageio.ImageIO.write(
                                                image,
                                                "png",
                                                dir.resolve("integrated-top-font-" + scale + ".png")
                                                        .toFile());
                                    } catch (java.io.IOException e) {
                                        throw new AssertionError(e);
                                    }
                                }
                                if (enabled) recovery.bridgeCheckbox().setSelected(true);
                            }
                            notice.removeNotify();
                        }
                    }
                });
    }

    @Test
    void sharedDraftIsReversibleAndOnlyExplicitApplySavesAndReloads() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    var settings = new BookmapOrderflowExporter.Settings();
                    settings.bridgeEnabled = true;
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
                                                    assertFalse(settings.bridgeEnabled);
                                                    saves.incrementAndGet();
                                                }
                                                if (method.getName().equals("reload"))
                                                    reloads.incrementAndGet();
                                                return null;
                                            });
                    var tabs = BookmapOrderflowExporter.buildExporterTabs(settings, api, null);
                    var config = (Container) tabs.getComponentAt(0);
                    var original =
                            ExporterTabsTest.find(
                                    config, JCheckBox.class, "Live Linux bridge (optional)");
                    var controls =
                            ExporterTabsTest.find(config, BridgeRecoveryControls.class, null);
                    assertNotNull(
                            controls, "real exporter configuration contains recovery controls");
                    assertSame(controls.bridgeCheckbox().getModel(), original.getModel());
                    controls.archiveOnlyButton().doClick();
                    assertFalse(original.isSelected());
                    assertTrue(controls.pending());
                    original.doClick();
                    assertTrue(controls.bridgeEnabled());
                    assertFalse(controls.pending());
                    controls.archiveOnlyButton().doClick();
                    assertTrue(settings.bridgeEnabled, "draft never mutates saved settings");
                    assertTrue(settings.exportMbo);
                    assertTrue(settings.exportTrades);
                    assertEquals("0.0.0.0", settings.bridgeBind);
                    assertEquals(0, saves.get());
                    assertEquals(0, reloads.get());
                    ExporterTabsTest.find(
                                    config, JButton.class, "Apply settings / restart exporter")
                            .doClick();
                    assertFalse(settings.bridgeEnabled);
                    assertEquals(1, saves.get());
                    assertEquals(1, reloads.get());
                    var fresh =
                            ExporterTabsTest.find(
                                    BookmapOrderflowExporter.buildExporterTabs(settings, api, null),
                                    BridgeRecoveryControls.class,
                                    null);
                    assertFalse(fresh.bridgeEnabled());
                    assertFalse(
                            fresh.pending(), "fresh post-reload UI uses the newly saved baseline");
                });
    }
}
