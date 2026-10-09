package com.limacharlie.orderflow;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import velox.api.layer1.simplified.Api;

import java.awt.*;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;

import javax.swing.*;

class LanBindControlsTest {
    @Test
    void manuallyTypedAddressBeforeAutoNeedsExplicitRefreshAndNeverGetsOverwritten()
            throws Exception {
        var field = edt(() -> new JTextField("0.0.0.0"));
        var controls = edt(() -> new LanBindControls(field, null, LanBindControlsTest::one, true));
        var errors = new AtomicReference<String>();
        edt(
                () -> {
                    controls.errorReporter(errors::set);
                    field.setText("192.168.10.20");
                    assertEquals("manual", controls.modeKey());
                    assertFalse(controls.validateForApply());
                    assertTrue(errors.get().contains("Detect / refresh"));
                    controls.refreshButton().doClick();
                    return null;
                });
        await(controls);
        edt(
                () -> {
                    assertEquals("192.168.10.20", field.getText());
                    assertEquals("manual", controls.modeKey());
                    assertTrue(controls.validateForApply());
                    return null;
                });
    }

    private static <T> T edt(Supplier<T> action) throws Exception {
        var result = new AtomicReference<T>();
        SwingUtilities.invokeAndWait(() -> result.set(action.get()));
        return result.get();
    }

    private static void await(LanBindControls controls) throws Exception {
        edt(controls::discoveryFinished).get(5, TimeUnit.SECONDS);
    }

    private static List<LocalLanIpv4.Address> one() {
        return List.of(LocalLanIpv4Test.address("Ethernet", "192.168.10.20"));
    }

    @Test
    void legacyWildcardAndManualLocalRefreshNeverOverwritten() throws Exception {
        for (String saved : List.of("0.0.0.0", "192.168.10.20")) {
            var field = edt(() -> new JTextField(saved));
            var controls =
                    edt(
                            () ->
                                    new LanBindControls(
                                            field,
                                            null,
                                            () -> {
                                                assertFalse(SwingUtilities.isEventDispatchThread());
                                                return one();
                                            },
                                            true));
            edt(
                    () -> {
                        controls.refreshButton().doClick();
                        return null;
                    });
            await(controls);
            edt(
                    () -> {
                        assertEquals(saved, field.getText());
                        assertEquals("manual", controls.modeKey());
                        assertTrue(controls.validateForApply());
                        return null;
                    });
        }
    }

    @Test
    void autoOnePrefillsOnlyDraftAmbiguityRequiresExplicitChoice() throws Exception {
        var source = new AtomicReference<>(one());
        var field = edt(() -> new JTextField("0.0.0.0"));
        var controls = edt(() -> new LanBindControls(field, "manual", source::get, true));
        edt(
                () -> {
                    controls.modeSelector().setSelectedIndex(1);
                    return null;
                });
        await(controls);
        edt(
                () -> {
                    assertEquals("192.168.10.20", field.getText());
                    assertTrue(controls.validateForApply());
                    return null;
                });
        source.set(List.of(one().get(0), LocalLanIpv4Test.address("Wi-Fi", "192.168.10.21")));
        var errors = new AtomicReference<String>();
        edt(
                () -> {
                    controls.errorReporter(errors::set);
                    controls.refreshButton().doClick();
                    return null;
                });
        await(controls);
        edt(
                () -> {
                    assertEquals("192.168.10.20", field.getText());
                    assertEquals(-1, controls.adapterSelector().getSelectedIndex());
                    assertFalse(controls.validateForApply());
                    assertNotNull(errors.get());
                    controls.adapterSelector().setSelectedIndex(1);
                    assertEquals("auto", controls.modeKey(), "programmatic draft selection must retain Auto mode");
                    assertEquals("192.168.10.21", field.getText());
                    assertTrue(controls.validateForApply());
                    field.setText("192.168.10.20");
                    assertEquals("manual", controls.modeKey(), "typing overrides Auto explicitly");
                    assertTrue(controls.validateForApply());
                    return null;
                });
    }

    @Test
    void lateDiscoveryCannotOverwriteManualEditAndFailedDiscoveryIsVisible() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var field = edt(() -> new JTextField("0.0.0.0"));
        var controls =
                edt(
                        () ->
                                new LanBindControls(
                                        field,
                                        "manual",
                                        () -> {
                                            started.countDown();
                                            try {
                                                assertTrue(release.await(5, TimeUnit.SECONDS));
                                            } catch (InterruptedException e) {
                                                throw new AssertionError(e);
                                            }
                                            return one();
                                        },
                                        true));
        edt(
                () -> {
                    controls.modeSelector().setSelectedIndex(1);
                    return null;
                });
        assertTrue(started.await(5, TimeUnit.SECONDS));
        edt(
                () -> {
                    field.setText("0.0.0.0");
                    return null;
                });
        release.countDown();
        await(controls);
        edt(
                () -> {
                    assertEquals("manual", controls.modeKey());
                    assertEquals("0.0.0.0", field.getText());
                    return null;
                });
        var broken =
                edt(
                        () ->
                                new LanBindControls(
                                        new JTextField("0.0.0.0"),
                                        "auto",
                                        () -> {
                                            throw new IllegalStateException(
                                                    "synthetic metadata failure");
                                        },
                                        true));
        await(broken);
        edt(
                () -> {
                    assertTrue(broken.statusText().getText().contains("failed"));
                    broken.modeSelector().setSelectedIndex(0);
                    assertTrue(broken.validateForApply());
                    return null;
                });
    }

    @Test
    void actualApplyPersistsDetectedModeOnceButRejectsNonlocalBeforeAnySettingsMutation()
            throws Exception {
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
                                    if (method.getName().equals("setSettings"))
                                        saves.incrementAndGet();
                                    if (method.getName().equals("reload"))
                                        reloads.incrementAndGet();
                                    return null;
                                });
        var tabs =
                edt(
                        () ->
                                BookmapOrderflowExporter.buildExporterTabs(
                                        settings, api, null, LanBindControlsTest::one));
        var controls = edt(() -> ExporterTabsTest.find(tabs, LanBindControls.class, null));
        edt(
                () -> {
                    controls.modeSelector().setSelectedIndex(1);
                    return null;
                });
        await(controls);
        edt(
                () -> {
                    assertEquals("0.0.0.0", settings.bridgeBind);
                    assertEquals("manual", settings.bridgeBindMode);
                    assertEquals(0, saves.get());
                    assertEquals(0, reloads.get());
                    ExporterTabsTest.find(tabs, JButton.class, "Apply settings / restart exporter")
                            .doClick();
                    assertEquals("192.168.10.20", settings.bridgeBind);
                    assertEquals("auto", settings.bridgeBindMode);
                    assertEquals(1, saves.get());
                    assertEquals(1, reloads.get());

                    // Find the original Bind field by its saved/detected value, not a test-only
                    // replacement.
                    var bind = findBind(tabs, "192.168.10.20");
                    assertNotNull(bind);
                    bind.setText("192.168.10.99");
                    ExporterTabsTest.find(
                                    tabs,
                                    JCheckBox.class,
                                    "Export MBO add / replace / cancel records")
                            .setSelected(false);
                    var errors = new AtomicReference<String>();
                    controls.errorReporter(errors::set);
                    ExporterTabsTest.find(tabs, JButton.class, "Apply settings / restart exporter")
                            .doClick();
                    assertNotNull(errors.get());
                    assertEquals(1, saves.get());
                    assertEquals(1, reloads.get());
                    assertTrue(settings.exportMbo);
                    assertEquals("192.168.10.20", settings.bridgeBind);
                    return null;
                });
    }

    private static JTextField findBind(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof JTextField f && text.equals(f.getText())) return f;
            if (child instanceof Container c) {
                var found = findBind(c, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test
    void realTabsLocalControlsWrapAndRemainAccessibleAcrossWidthsAndFontScales() throws Exception {
        for (double scale : new double[] {1, 1.25, 1.5}) {
            Api api =
                    (Api)
                            Proxy.newProxyInstance(
                                    Api.class.getClassLoader(),
                                    new Class<?>[] {Api.class},
                                    (p, m, a) -> null);
            var tabs =
                    edt(
                            () ->
                                    BookmapOrderflowExporter.buildExporterTabs(
                                            new BookmapOrderflowExporter.Settings(),
                                            api,
                                            null,
                                            LanBindControlsTest::one));
            var controls = edt(() -> ExporterTabsTest.find(tabs, LanBindControls.class, null));
            edt(
                    () -> {
                        controls.modeSelector().setSelectedIndex(1);
                        return null;
                    });
            await(controls);
            edt(
                    () -> {
                        BridgeConfigurationLayoutTest.fonts(tabs, scale);
                        for (int width : new int[] {400, 560, 800, 400}) {
                            for (int pass = 0; pass < 4; pass++) {
                                tabs.setSize(width, tabs.getPreferredSize().height);
                                BridgeConfigurationLayoutTest.layout(tabs);
                            }
                            var text = controls.statusText();
                            try {
                                var glyph = text.modelToView2D(text.getDocument().getLength() - 1);
                                assertTrue(
                                        text.getVisibleRect().contains(glyph.getBounds()),
                                        "width="
                                                + width
                                                + " scale="
                                                + scale
                                                + " text="
                                                + text.getBounds()
                                                + " visible="
                                                + text.getVisibleRect()
                                                + " glyph="
                                                + glyph
                                                + " controls="
                                                + controls.getBounds()
                                                + " textPref="
                                                + text.getPreferredSize()
                                                + " controlsPref="
                                                + controls.getPreferredSize()
                                                + " controlsMin="
                                                + controls.getMinimumSize());
                            } catch (javax.swing.text.BadLocationException e) {
                                throw new AssertionError(e);
                            }
                            for (Component c :
                                    new Component[] {
                                        controls.modeSelector(),
                                        controls.refreshButton(),
                                        controls.adapterSelector()
                                    })
                                assertTrue(
                                        c.getX() + c.getWidth() <= c.getParent().getWidth()
                                                && c.getY() + c.getHeight()
                                                        <= c.getParent().getHeight());
                            assertEquals(3, tabs.getTabCount());
                            var image =
                                    new java.awt.image.BufferedImage(
                                            width,
                                            tabs.getHeight(),
                                            java.awt.image.BufferedImage.TYPE_INT_RGB);
                            var g = image.createGraphics();
                            tabs.paint(g);
                            g.dispose();
                            String output = System.getenv("ORDERFLOW_LAN_PREVIEWS");
                            if (output != null && width == 400) {
                                try {
                                    var dir = java.nio.file.Path.of(output);
                                    java.nio.file.Files.createDirectories(dir);
                                    javax.imageio.ImageIO.write(
                                            image,
                                            "png",
                                            dir.resolve("lan-auto-font-" + scale + ".png")
                                                    .toFile());
                                } catch (java.io.IOException e) {
                                    throw new AssertionError(e);
                                }
                            }
                        }
                        return null;
                    });
        }
    }
}
