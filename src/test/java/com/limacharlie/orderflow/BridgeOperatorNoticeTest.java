package com.limacharlie.orderflow;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import javax.swing.*;

class BridgeOperatorNoticeTest {
    private static LiveBridge.OperatorSnapshot snapshot(
            String state, boolean invalid, String reason, boolean registered, long ack, int depth) {
        return new LiveBridge.OperatorSnapshot(
                state, invalid, reason, registered, ack, depth, 100, 100);
    }

    @Test
    void distinguishesHandshakeMemoryAckRecoveryAndIrreversibleFailure() {
        assertEquals("DISABLED", BridgeOperatorNotice.disabled().state());
        var waiting =
                BridgeOperatorNotice.describe(snapshot("WAITING_RECEIVER", false, "", false, 0, 0));
        assertTrue(waiting.warning());
        assertTrue(waiting.message().contains("Start-Receiver.sh --host WINDOWS_IP"));
        assertTrue(waiting.message().contains("Apply restarts"));
        assertEquals(
                "CONNECTED / AWAITING ACK",
                BridgeOperatorNotice.describe(snapshot("CONNECTED", false, "", true, 0, 0))
                        .state());
        var connected =
                BridgeOperatorNotice.describe(snapshot("CONNECTED", false, "", true, 50, 2));
        assertEquals("CONNECTED / ACKED IN RAM", connected.state());
        assertTrue(connected.message().contains("not durable"));
        assertFalse(connected.warning());
        assertTrue(
                BridgeOperatorNotice.describe(snapshot("CONNECTED", false, "", true, 50, 80))
                        .warning());
        var disconnected =
                BridgeOperatorNotice.describe(snapshot("DISCONNECTED", false, "", true, 50, 50));
        assertEquals("RECOVERABLE DISCONNECT", disconnected.state());
        assertTrue(disconnected.message().contains("Receiver-process restart is not supported"));
        assertFalse(disconnected.message().contains("Start-Receiver.sh"));
        assertTrue(disconnected.message().contains("do not launch a second receiver"));
        var invalid =
                BridgeOperatorNotice.describe(
                        snapshot("INVALID", true, "outbound buffer overflow", true, 50, 0));
        assertEquals("INVALID", invalid.state());
        assertTrue(invalid.message().contains("51–100"));
        assertTrue(invalid.message().contains("cannot restore"));
        assertTrue(invalid.message().contains("local archive"));
        assertEquals("ERROR / INVALID", BridgeOperatorNotice.failure("bind failed").state());
        assertEquals(
                "ERROR / INVALID",
                BridgeOperatorNotice.describe(
                                snapshot("INVALID", true, "publisher failure: bind", false, 0, 0))
                        .state());
    }

    @Test
    void unchangedPollingDoesNotSpamAndNoticeWrapsAtFontScales() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    for (double scale : new double[] {1, 1.25, 1.5}) {
                        var source =
                                new AtomicReference<>(
                                        BridgeOperatorNotice.describe(
                                                snapshot(
                                                        "WAITING_RECEIVER",
                                                        false,
                                                        "",
                                                        false,
                                                        0,
                                                        80)));
                        var panel = new BridgeOperatorNotice(source::get);
                        BridgeConfigurationLayoutTest.fonts(panel, scale);
                        var message = panel.criticalText();
                        var document = message.getDocument();
                        var changes = new java.util.concurrent.atomic.AtomicInteger();
                        document.addDocumentListener(
                                new javax.swing.event.DocumentListener() {
                                    public void insertUpdate(javax.swing.event.DocumentEvent e) {
                                        changes.incrementAndGet();
                                    }

                                    public void removeUpdate(javax.swing.event.DocumentEvent e) {
                                        changes.incrementAndGet();
                                    }

                                    public void changedUpdate(javax.swing.event.DocumentEvent e) {
                                        changes.incrementAndGet();
                                    }
                                });
                        for (int i = 0; i < 10; i++) panel.refreshNow();
                        assertEquals(
                                0,
                                changes.get(),
                                "unchanged polling emits no repeated notification");
                        assertFalse(panel.polling(), "hidden panel must not poll");
                        for (int width : new int[] {376, 536, 776}) {
                            panel.setSize(width, panel.getPreferredSize().height);
                            panel.setSize(width, panel.getPreferredSize().height);
                            BridgeConfigurationLayoutTest.layout(panel);
                            assertTrue(
                                    message.getHeight() >= message.getPreferredSize().height,
                                    "complete wrapped message fits");
                            assertTrue(message.getWidth() <= width);
                        }
                        source.set(BridgeOperatorNotice.disabled());
                        panel.refreshNow();
                        assertTrue(changes.get() > 0);
                        panel.removeNotify();
                        assertFalse(panel.polling());
                    }
                });
    }

    @Test
    void missingReceiverAndExhaustionStayVisibleWithoutChangingArchive() throws Exception {
        int market = ExporterTest.freePort(), health = ExporterTest.freePort();
        while (health == market) health = ExporterTest.freePort();
        var bridge =
                new LiveBridge(
                        "127.0.0.1", market, health, 1, "SYNTH", .25, () -> "{\"writer_ok\":true}");
        try {
            assertEquals(
                    "WAITING FOR RECEIVER",
                    BridgeOperatorNotice.describe(bridge.operatorSnapshot()).state());
            assertTrue(bridge.offer(new CanonicalEvent(1, 1, () -> "{}")));
            assertTrue(BridgeOperatorNotice.describe(bridge.operatorSnapshot()).warning());
            assertFalse(bridge.offer(new CanonicalEvent(2, 2, () -> "{}")));
            assertEquals(
                    "INVALID", BridgeOperatorNotice.describe(bridge.operatorSnapshot()).state());
            assertTrue(bridge.status().contains("\"writer_ok\":true"));
            assertFalse(bridge.offer(new CanonicalEvent(3, 3, () -> "{}")));
            assertEquals(
                    "INVALID", BridgeOperatorNotice.describe(bridge.operatorSnapshot()).state());
        } finally {
            bridge.close();
        }
    }

    @Test
    void displayHierarchyStartsAndStopsPolling() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    var root = new JPanel();
                    var panel = new BridgeOperatorNotice(BridgeOperatorNotice::disabled);
                    root.add(panel);
                    assertFalse(panel.polling());
                    root.addNotify();
                    assertTrue(panel.isShowing());
                    assertTrue(panel.polling());
                    panel.setVisible(false);
                    assertFalse(panel.polling());
                    panel.setVisible(true);
                    assertTrue(panel.polling());
                    root.removeNotify();
                    assertFalse(panel.polling());
                });
    }

    /** Local Swing rendering only; never claims native Bookmap/DPI acceptance. */
    public static void main(String[] args) throws Exception {
        var out = java.nio.file.Path.of(args[0]);
        java.nio.file.Files.createDirectories(out);
        SwingUtilities.invokeAndWait(
                () -> {
                    for (double scale : new double[] {1, 1.25, 1.5}) {
                        var root = new JPanel(new java.awt.BorderLayout(4, 4));
                        var notice =
                                new BridgeOperatorNotice(
                                        () ->
                                                BridgeOperatorNotice.describe(
                                                        snapshot(
                                                                "WAITING_RECEIVER",
                                                                false,
                                                                "",
                                                                false,
                                                                0,
                                                                80)));
                        root.add(notice, java.awt.BorderLayout.NORTH);
                        root.add(
                                BookmapOrderflowExporter.buildExporterTabs(
                                        new BookmapOrderflowExporter.Settings(), null, null),
                                java.awt.BorderLayout.CENTER);
                        BridgeConfigurationLayoutTest.fonts(root, scale);
                        for (int pass = 0; pass < 4; pass++) {
                            root.setSize(536, root.getPreferredSize().height);
                            BridgeConfigurationLayoutTest.layout(root);
                        }
                        var fields =
                                ExporterTabsTest.find(
                                        root, BridgeConfigurationLayout.Fields.class, null);
                        assertTrue(fields.getHeight() >= fields.getPreferredSize().height);
                        var text = notice.criticalText();
                        assertTrue(text.getHeight() >= text.getPreferredSize().height);
                        var image =
                                new java.awt.image.BufferedImage(
                                        root.getWidth(),
                                        root.getHeight(),
                                        java.awt.image.BufferedImage.TYPE_INT_RGB);
                        var graphics = image.createGraphics();
                        root.paint(graphics);
                        graphics.dispose();
                        try {
                            javax.imageio.ImageIO.write(
                                    image,
                                    "png",
                                    out.resolve("receiver-warning-scale-" + scale + ".png")
                                            .toFile());
                        } catch (java.io.IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                });
    }
}
