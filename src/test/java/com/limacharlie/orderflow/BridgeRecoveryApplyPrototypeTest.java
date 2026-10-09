package com.limacharlie.orderflow;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import velox.api.layer1.simplified.Api;

import java.awt.Container;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.*;

/** Synthetic adapter to the real Apply listener; not installed Bookmap UI wiring. */
class BridgeRecoveryApplyPrototypeTest {
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
                    var controls = new BridgeRecoveryControls(settings.bridgeEnabled);
                    original.setModel(controls.bridgeCheckbox().getModel());
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
                    var fresh = new BridgeRecoveryControls(settings.bridgeEnabled);
                    assertFalse(fresh.bridgeEnabled());
                    assertFalse(
                            fresh.pending(), "fresh post-reload UI uses the newly saved baseline");
                });
    }
}
