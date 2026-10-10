package com.limacharlie.orderflow;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import velox.api.layer1.data.InstrumentInfo;
import velox.api.layer1.data.TradeInfo;
import velox.api.layer1.simplified.Api;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

class ExporterTest {
    @TempDir Path dir;

    static Api api(BookmapOrderflowExporter.Settings settings) {
        return (Api)
                Proxy.newProxyInstance(
                        Api.class.getClassLoader(),
                        new Class<?>[] {Api.class},
                        (proxy, method, args) ->
                                method.getName().equals("getSettings") ? settings : null);
    }

    @Test
    void unchangedJournalContractAndOneAcquisition() throws Exception {
        var settings = new BookmapOrderflowExporter.Settings();
        settings.outputDirectory = dir.toString();
        var exporter = new BookmapOrderflowExporter();
        exporter.initialize(
                "SYNTH.CME@TEST",
                new InstrumentInfo("SYNTH", "CME", "TEST", .25, 1, "Synthetic", true),
                api(settings),
                null);
        exporter.onTimestamp(1_000_000_000L);
        exporter.send("x", true, 20000, 2);
        exporter.replace("x", 20001, 3);
        exporter.onRealtimeStart();
        exporter.onTrade(20001, 2, new TradeInfo(false, true, true, true, "a", "x"));
        exporter.cancel("x");
        exporter.stop();
        Path event =
                Files.list(dir).filter(p -> p.toString().endsWith(".gz")).findFirst().orElseThrow();
        String text;
        try (var input = new GZIPInputStream(Files.newInputStream(event))) {
            text = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertEquals(7, text.lines().count());
        assertTrue(text.contains("\"aggressor_order_id\":\"a\""));
        assertTrue(text.contains("\"price\":5000.25"));
        assertTrue(text.contains("\"seq\":7"));
        Path summary =
                Files.list(dir)
                        .filter(p -> p.toString().endsWith(".json"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(Files.readString(summary).contains("\"writer_ok\":true"));
        assertTrue(Files.readString(summary).contains("\"orders_open_at_stop\":0"));
    }

    @Test
    void boundedBufferNeverWaitsAndRetainsOrder() throws Exception {
        EventBuffer buffer = new EventBuffer(1);
        CanonicalEvent a = new CanonicalEvent(1, 1, () -> "a"),
                b = new CanonicalEvent(2, 2, () -> "b");
        assertTrue(buffer.offer(a));
        assertFalse(buffer.offer(b));
        assertSame(a, buffer.poll(1, TimeUnit.MILLISECONDS));
        assertTrue(buffer.offer(b));
        assertSame(b, buffer.poll(1, TimeUnit.MILLISECONDS));
    }

    @Test
    void lazySerializationAndOverflowFailClosed() throws Exception {
        var called = new java.util.concurrent.atomic.AtomicInteger();
        var bridge =
                new LiveBridge("127.0.0.1", freePort(), freePort(), 1, "SYNTH", .25, () -> "{}");
        try {
            assertTrue(
                    bridge.offer(
                            new CanonicalEvent(
                                    1,
                                    0,
                                    () -> {
                                        called.incrementAndGet();
                                        return "{}";
                                    })));
            assertFalse(bridge.offer(new CanonicalEvent(2, 1, () -> "{}")));
            assertEquals(0, called.get());
            assertTrue(bridge.invalid());
            assertTrue(bridge.status().contains("\"overflows\":1"));
        } finally {
            bridge.close();
        }
    }

    @Test
    void failedBridgeDoesNotStopJournal() throws Exception {
        var settings = new BookmapOrderflowExporter.Settings();
        settings.outputDirectory = dir.toString();
        settings.bridgeEnabled = true;
        settings.bridgePort = 5555;
        settings.bridgeHealthPort = 5555; // invalid configuration
        var exporter = new BookmapOrderflowExporter();
        exporter.initialize(
                "SYNTH.CME@TEST",
                new InstrumentInfo("SYNTH", "CME", "TEST", .25, 1, "Synthetic", true),
                api(settings),
                null);
        exporter.onTimestamp(1);
        exporter.onRealtimeStart();
        exporter.send("x", true, 1, 1);
        exporter.stop();
        Path summary =
                Files.list(dir)
                        .filter(p -> p.toString().endsWith(".json"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(Files.readString(summary).contains("\"writer_ok\":true"));
    }

    @Test
    void fullCallbacksMatchJournalAndWireExactly() throws Exception {
        int port = freePort(), health = freePort();
        var settings = new BookmapOrderflowExporter.Settings();
        settings.outputDirectory = dir.toString();
        settings.bridgeEnabled = true;
        settings.bridgeBind = "127.0.0.1";
        settings.bridgePort = port;
        settings.bridgeHealthPort = health;
        var exporter = new BookmapOrderflowExporter();
        try (var context = new org.zeromq.ZContext()) {
            var dealer = context.createSocket(org.zeromq.SocketType.DEALER);
            dealer.setLinger(0);
            dealer.setReceiveTimeOut(5000);
            dealer.setIdentity(
                    "synthetic-receiver".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            dealer.connect("tcp://127.0.0.1:" + port);
            dealer.send("HELLO synthetic-receiver 0");
            exporter.initialize(
                    "SYNTH.CME@TEST",
                    new InstrumentInfo("SYNTH", "CME", "TEST", .25, 1, "Synthetic", true),
                    api(settings),
                    null);
            var stopping =
                    java.util.concurrent.CompletableFuture.runAsync(
                            () -> {
                                exporter.onTimestamp(1_000_000_000L);
                                exporter.onRealtimeStart();
                                for (int i = 0; i < 100; i++)
                                    exporter.send("order-" + i, i % 2 == 0, 20000 + i, 2);
                                for (int i = 0; i < 20; i++)
                                    exporter.replace("order-" + i, 20001 + i, 3);
                                for (int i = 0; i < 30; i++)
                                    exporter.onTrade(
                                            20000,
                                            2,
                                            new TradeInfo(
                                                    false,
                                                    true,
                                                    true,
                                                    true,
                                                    "aggr-" + i,
                                                    "order-" + i));
                                for (int i = 0; i < 40; i++) exporter.cancel("order-" + i);
                                exporter.stop();
                            });
            var wire = new java.util.ArrayList<String>();
            var seqPattern = java.util.regex.Pattern.compile("\\\"seq\\\":(\\d+)");
            while (wire.size() < 193) {
                String envelope = dealer.recvStr();
                assertNotNull(envelope, "missing wire event");
                int offset = envelope.indexOf("\"event\":{");
                if (offset < 0) continue;
                String record = envelope.substring(offset + 8, envelope.length() - 1);
                wire.add(record);
                var matcher = seqPattern.matcher(record);
                assertTrue(matcher.find());
                dealer.send("ACK synthetic-receiver " + matcher.group(1));
            }
            stopping.get(5, java.util.concurrent.TimeUnit.SECONDS);
            Path event =
                    Files.list(dir)
                            .filter(p -> p.toString().endsWith(".gz"))
                            .findFirst()
                            .orElseThrow();
            String text;
            try (var input = new GZIPInputStream(Files.newInputStream(event))) {
                text = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            assertEquals(text.lines().toList(), wire);
        }
    }

    @Test
    void bridgeOverflowLeavesCompleteJournal() throws Exception {
        var settings = new BookmapOrderflowExporter.Settings();
        settings.outputDirectory = dir.toString();
        settings.bridgeEnabled = true;
        settings.bridgeBind = "127.0.0.1";
        settings.bridgePort = freePort();
        settings.bridgeHealthPort = freePort();
        settings.bridgeQueueCapacity = 1;
        var exporter = new BookmapOrderflowExporter();
        exporter.initialize(
                "SYNTH.CME@TEST",
                new InstrumentInfo("SYNTH", "CME", "TEST", .25, 1, "Synthetic", true),
                api(settings),
                null);
        try {
            awaitBridgeReady(settings.bridgeHealthPort);
            // Consume START, then stop ACKing to provoke LIVE overflow.
            try (var context = new org.zeromq.ZContext();
                    var peer = new SyntheticBridgePeer(context, settings.bridgePort,
                            () -> BridgeHealthQuery.query("127.0.0.1", settings.bridgeHealthPort, 200))) {
                peer.send("HELLO overflow-test 0");
                String welcome = peer.receive("WELCOME"), first = peer.receive("START");
                assertTrue(welcome.contains("WELCOME"));
                assertTrue(first.contains("\"seq\":1"));
                peer.send("ACK overflow-test 1");
                exporter.onTimestamp(100);
                exporter.onRealtimeStart();
                for (int i = 0; i < 100; i++) exporter.send("x-" + i, true, 20000, 1);
            }
        } finally {
            // A failed receive must not leak the synthetic writer/publisher into later tests.
            exporter.stop();
        }
        Path summary =
                Files.list(dir)
                        .filter(p -> p.toString().endsWith(".json"))
                        .findFirst()
                        .orElseThrow();
        String text = Files.readString(summary);
        assertTrue(text.contains("\"writer_ok\":true"));
        assertTrue(text.contains("\"records_persisted\":103"));
        assertTrue(text.contains("\"invalid\":true"));
        assertTrue(text.contains("\"overflows\":1"));
    }

    @Test
    void liveJournalOverflowReturnsAndFailsArchiveClosed() throws Exception {
        var exporter = new BookmapOrderflowExporter();
        var settings = new BookmapOrderflowExporter.Settings();
        var settingsField = BookmapOrderflowExporter.class.getDeclaredField("settings");
        settingsField.setAccessible(true);
        settingsField.set(exporter, settings);
        var queueField = BookmapOrderflowExporter.class.getDeclaredField("queue");
        queueField.setAccessible(true);
        queueField.set(exporter, new EventBuffer(1));
        var realtimeField = BookmapOrderflowExporter.class.getDeclaredField("realtimePhase");
        realtimeField.setAccessible(true);
        realtimeField.setBoolean(exporter, true);
        exporter.onTimestamp(100);
        exporter.send("first", true, 1, 1);
        exporter.send("second", true, 2, 1);
        exporter.send("third", true, 3, 1);
        var errorField = BookmapOrderflowExporter.class.getDeclaredField("writerError");
        errorField.setAccessible(true);
        assertNotNull(
                ((java.util.concurrent.atomic.AtomicReference<?>) errorField.get(exporter)).get());
        var overflowField = BookmapOrderflowExporter.class.getDeclaredField("journalOverflows");
        overflowField.setAccessible(true);
        assertEquals(1, overflowField.getLong(exporter));
        var droppedField = BookmapOrderflowExporter.class.getDeclaredField("journalDropped");
        droppedField.setAccessible(true);
        assertEquals(2, droppedField.getLong(exporter));
    }

    private static final java.util.Set<Integer> testPorts =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    static int freePort() throws Exception {
        // Closing a probe can immediately return the same ephemeral port on another call.
        while (true) {
            try (var socket = new java.net.ServerSocket(0)) {
                int port = socket.getLocalPort();
                if (testPorts.add(port)) return port;
            }
        }
    }

    static void awaitBridgeReady(int healthPort) {
        String ready = null;
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (ready == null && System.nanoTime() < deadline)
            ready = BridgeHealthQuery.query("127.0.0.1", healthPort, 200);
        assertNotNull(ready, "Publisher health endpoint did not become ready");
        assertTrue(ready.contains("\"invalid\":false"), ready);
    }
}
