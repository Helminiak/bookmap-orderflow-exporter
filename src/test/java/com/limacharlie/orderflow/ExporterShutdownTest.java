package com.limacharlie.orderflow;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.zeromq.*;

import velox.api.layer1.data.InstrumentInfo;

import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

class ExporterShutdownTest {
    @TempDir Path dir;

    BookmapOrderflowExporter exporter(int port, int healthPort) {
        var settings = new BookmapOrderflowExporter.Settings();
        settings.outputDirectory = dir.toString();
        settings.bridgeEnabled = true;
        settings.bridgePort = port;
        settings.bridgeHealthPort = healthPort;
        settings.bridgeBind = "127.0.0.1";
        settings.bridgeQueueCapacity = 1;
        var exporter = new BookmapOrderflowExporter();
        exporter.initialize(
                "SYNTH.CME@TEST",
                new InstrumentInfo("SYNTH", "CME", "TEST", .25, 1, "Synthetic", true),
                ExporterTest.api(settings),
                null);
        return exporter;
    }

    void awaitBound(BookmapOrderflowExporter exporter) throws Exception {
        var field = BookmapOrderflowExporter.class.getDeclaredField("bridge");
        field.setAccessible(true);
        var bridge = (LiveBridge) field.get(exporter);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (bridge.status().contains("STARTING") && System.nanoTime() < deadline)
            Thread.sleep(10);
        assertFalse(bridge.status().contains("STARTING"));
        assertFalse(bridge.invalid());
    }

    void assertCanRebind(int port, int health) {
        try (var context = new ZContext();
                var data = context.createSocket(SocketType.ROUTER);
                var status = context.createSocket(SocketType.REP)) {
            assertTrue(data.bind("tcp://127.0.0.1:" + port));
            assertTrue(status.bind("tcp://127.0.0.1:" + health));
        }
    }

    @Test
    void stopCannotOvertakeAnAcceptedCallbackBeforeQueueInsertion() throws Exception {
        var settings = new BookmapOrderflowExporter.Settings();
        settings.outputDirectory = dir.toString();
        var exporter = new BookmapOrderflowExporter();
        exporter.initialize(
                "SYNTH.CME@TEST",
                new InstrumentInfo("SYNTH", "CME", "TEST", .25, 1, "Synthetic", true),
                ExporterTest.api(settings),
                null);
        var queueField = BookmapOrderflowExporter.class.getDeclaredField("queue");
        queueField.setAccessible(true);
        var buffer = (EventBuffer) queueField.get(exporter);
        var underlyingField = EventBuffer.class.getDeclaredField("queue");
        underlyingField.setAccessible(true);
        var underlying =
                (java.util.concurrent.ConcurrentLinkedQueue<CanonicalEvent>)
                        underlyingField.get(buffer);
        // Preserve any pending START. Pause the real buffer precisely after sequence reservation.
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var gated =
                new java.util.concurrent.ConcurrentLinkedQueue<CanonicalEvent>() {
                    @Override
                    public boolean add(CanonicalEvent event) {
                        if (event.seq == 2) {
                            entered.countDown();
                            try {
                                if (!release.await(5, TimeUnit.SECONDS))
                                    throw new AssertionError("Producer gate timed out");
                            } catch (InterruptedException e) {
                                throw new AssertionError(e);
                            }
                        }
                        return super.add(event);
                    }
                };
        gated.addAll(underlying);
        underlyingField.set(buffer, gated);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var producer = pool.submit(() -> exporter.send("accepted", true, 20000, 2));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var stop = pool.submit(exporter::stop);
            // Allow the old implementation to finish prematurely; fixed stop waits for acceptance.
            try {
                stop.get(200, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException expected) {
            }
            release.countDown();
            producer.get(2, TimeUnit.SECONDS);
            stop.get(3, TimeUnit.SECONDS);
            exporter.send("after-stop", false, 20001, 3);
            exporter.onTimestamp(999);
            exporter.onRealtimeStart();
            exporter.stop();
            String text;
            try (var gzip =
                    new java.util.zip.GZIPInputStream(
                            Files.newInputStream(
                                    Files.list(dir)
                                            .filter(p -> p.toString().endsWith(".gz"))
                                            .findFirst()
                                            .orElseThrow()))) {
                text = new String(gzip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            var rows = text.lines().toList();
            assertEquals(3, rows.size(), "START, accepted callback and STOP must all persist");
            for (int i = 0; i < rows.size(); i++)
                assertTrue(rows.get(i).contains("\"seq\":" + (i + 1) + ","));
            assertTrue(rows.get(1).contains("\"order_id\":\"accepted\""));
            assertTrue(rows.get(2).contains("\"control\":\"STOP\""));
            String summary =
                    Files.readString(
                            Files.list(dir)
                                    .filter(p -> p.toString().endsWith(".summary.json"))
                                    .findFirst()
                                    .orElseThrow());
            assertTrue(summary.contains("\"total_records\":3"));
            assertTrue(summary.contains("\"records_persisted\":3"));
            assertTrue(summary.contains("\"orders_open_at_stop\":1"));
            assertTrue(summary.contains("\"writer_ok\":true"));
        } finally {
            release.countDown();
            pool.shutdownNow();
            exporter.stop();
        }
    }

    @Test
    void historicalStopDoesNotWaitForMissingReceiverCapacity() throws Exception {
        for (int iteration = 0; iteration < 5; iteration++) {
            int port = ExporterTest.freePort(), health = ExporterTest.freePort();
            var exporter = exporter(port, health);
            try {
                awaitBound(exporter); // START fills the one-event retention; no receiver ACKs it.
                assertTimeoutPreemptively(Duration.ofSeconds(3), exporter::stop);
                assertCanRebind(port, health);
                Path capture =
                        Files.list(dir)
                                .filter(p -> p.toString().endsWith(".gz"))
                                .findFirst()
                                .orElseThrow();
                try (var gzip = new java.util.zip.GZIPInputStream(Files.newInputStream(capture))) {
                    assertTrue(
                            new String(gzip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                                    .contains("\"control\":\"STOP\""));
                }
            } finally {
                exporter.stop();
            }
        }
    }

    @Test
    void summaryFailureStillClosesBridgeAndReleasesPorts() throws Exception {
        int port = ExporterTest.freePort(), health = ExporterTest.freePort();
        var exporter = exporter(port, health);
        try {
            awaitBound(exporter);
            var field = BookmapOrderflowExporter.class.getDeclaredField("summaryFile");
            field.setAccessible(true);
            field.set(
                    exporter, dir); // Deterministic IOException: write a summary over a directory.
            var error = assertThrows(IllegalStateException.class, exporter::stop);
            assertTrue(error.getMessage().contains("summary"));
            assertInstanceOf(java.io.IOException.class, error.getCause());
            assertCanRebind(port, health);
        } finally {
            exporter.stop();
        }
    }
}
