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
            exporter.onTimestamp(1_000_000_000L);
            exporter.onRealtimeStart();
            for (int i = 0; i < 100; i++) exporter.send("order-" + i, i % 2 == 0, 20000 + i, 2);
            for (int i = 0; i < 20; i++) exporter.replace("order-" + i, 20001 + i, 3);
            for (int i = 0; i < 30; i++)
                exporter.onTrade(
                        20000,
                        2,
                        new TradeInfo(false, true, true, true, "aggr-" + i, "order-" + i));
            for (int i = 0; i < 40; i++) exporter.cancel("order-" + i);
            exporter.stop();
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

    static int freePort() throws Exception {
        try (var socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
