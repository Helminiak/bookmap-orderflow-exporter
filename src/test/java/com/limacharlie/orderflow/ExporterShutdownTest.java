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

    java.util.Map<String, Object> lifecycleSnapshot(BookmapOrderflowExporter exporter)
            throws Exception {
        var snapshot = new java.util.HashMap<String, Object>();
        for (String name :
                new String[] {
                    "sequence",
                    "mboAdds",
                    "mboReplaces",
                    "mboCancels",
                    "trades",
                    "marketNs",
                    "firstMarketNs",
                    "lastMarketNs",
                    "realtimePhase",
                    "openOrderCount"
                }) {
            var field = BookmapOrderflowExporter.class.getDeclaredField(name);
            field.setAccessible(true);
            snapshot.put(name, field.get(exporter));
        }
        var ordersField = BookmapOrderflowExporter.class.getDeclaredField("orders");
        ordersField.setAccessible(true);
        snapshot.put(
                "orders", new java.util.HashMap<>((java.util.Map<?, ?>) ordersField.get(exporter)));
        var queueField = BookmapOrderflowExporter.class.getDeclaredField("queue");
        queueField.setAccessible(true);
        snapshot.put("queueDepth", ((EventBuffer) queueField.get(exporter)).size());
        return snapshot;
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
        var persistedField = BookmapOrderflowExporter.class.getDeclaredField("recordsPersisted");
        persistedField.setAccessible(true);
        long startDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (persistedField.getLong(exporter) != 1 && System.nanoTime() < startDeadline)
            Thread.sleep(1);
        assertEquals(
                1,
                persistedField.getLong(exporter),
                "START must leave the queue before installing the gate");
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
            var stopThread = new java.util.concurrent.atomic.AtomicReference<Thread>();
            var stopEntered = new java.util.concurrent.CountDownLatch(1);
            var stop =
                    pool.submit(
                            () -> {
                                stopThread.set(Thread.currentThread());
                                stopEntered.countDown();
                                exporter.stop();
                            });
            assertTrue(stopEntered.await(2, TimeUnit.SECONDS));
            var stoppedField = BookmapOrderflowExporter.class.getDeclaredField("stopped");
            stoppedField.setAccessible(true);
            long boundaryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!stoppedField.getBoolean(exporter)
                    && stopThread.get().getState() != Thread.State.BLOCKED
                    && System.nanoTime() < boundaryDeadline) Thread.sleep(1);
            assertTrue(
                    stoppedField.getBoolean(exporter)
                            || stopThread.get().getState() == Thread.State.BLOCKED);
            // Baseline crosses STOP before the paused callback. Candidate blocks on admission.
            if (stoppedField.getBoolean(exporter)) stop.get(2, TimeUnit.SECONDS);
            release.countDown();
            producer.get(2, TimeUnit.SECONDS);
            stop.get(3, TimeUnit.SECONDS);
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
            var beforeLate = lifecycleSnapshot(exporter);
            exporter.send("after-stop", false, 20001, 3);
            exporter.replace("accepted", 20003, 4);
            exporter.cancel("accepted");
            exporter.onTrade(
                    20001,
                    2,
                    new velox.api.layer1.data.TradeInfo(false, true, true, true, "a", "p"));
            exporter.onTimestamp(999);
            exporter.onRealtimeStart();
            exporter.stop();
            assertEquals(
                    beforeLate,
                    lifecycleSnapshot(exporter),
                    "Late callbacks must not mutate lifecycle state");
        } finally {
            release.countDown();
            pool.shutdownNow();
            exporter.stop();
        }
    }

    @Test
    void interruptedWriterDrainLeavesExplicitInvalidSummaryAndTerminatesWriter() throws Exception {
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
        var entered = new java.util.concurrent.CountDownLatch(1);
        ((EventBuffer) queueField.get(exporter))
                .offer(
                        new CanonicalEvent(
                                2,
                                1,
                                () -> {
                                    entered.countDown();
                                    try {
                                        new java.util.concurrent.CountDownLatch(1).await();
                                    } catch (InterruptedException e) {
                                        throw new IllegalStateException(
                                                "Synthetic interrupted encoder", e);
                                    }
                                    return "{}";
                                }));
        var sequenceField = BookmapOrderflowExporter.class.getDeclaredField("sequence");
        sequenceField.setAccessible(true);
        sequenceField.setLong(exporter, 2);
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var stopper =
                new Thread(
                        () -> {
                            try {
                                exporter.stop();
                            } catch (Throwable e) {
                                failure.set(e);
                            }
                        });
        stopper.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (stopper.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline)
            Thread.sleep(1);
        assertEquals(Thread.State.TIMED_WAITING, stopper.getState());
        stopper.interrupt();
        stopper.join(2000);
        assertFalse(stopper.isAlive());
        assertNotNull(failure.get());
        var writerField = BookmapOrderflowExporter.class.getDeclaredField("writerThread");
        writerField.setAccessible(true);
        var writer = (Thread) writerField.get(exporter);
        writer.join(2000);
        assertFalse(writer.isAlive());
        String summary =
                Files.readString(
                        Files.list(dir)
                                .filter(p -> p.toString().endsWith(".summary.json"))
                                .findFirst()
                                .orElseThrow());
        assertTrue(summary.contains("\"writer_ok\":false"));
        assertTrue(summary.contains("Shutdown interrupted; archive incomplete"));
        exporter.stop();
    }

    @Test
    void offlineReceiverCannotBlockHistoricalAcquisitionOrRealtimeTransition() throws Exception {
        var exporter = exporter(ExporterTest.freePort(), ExporterTest.freePort());
        try {
            awaitBound(exporter);
            assertTimeoutPreemptively(
                    Duration.ofMillis(500),
                    () -> {
                        exporter.send("offline", true, 20000, 2);
                        exporter.onRealtimeStart();
                    });
        } finally {
            exporter.stop();
        }
    }

    @Test
    void retainedStartAndStopReportIncompleteDeliveryWithoutArtificialOverflow() throws Exception {
        var exporter = exporter(ExporterTest.freePort(), ExporterTest.freePort());
        awaitBound(exporter);
        exporter.stop();
        String summary =
                Files.readString(
                        Files.list(dir)
                                .filter(p -> p.toString().endsWith(".summary.json"))
                                .findFirst()
                                .orElseThrow());
        assertTrue(
                summary.contains("\"overflows\":0"), "STOP must have one reserved terminal slot");
        assertTrue(summary.contains("shutdown with unacknowledged events"));
        assertTrue(summary.contains("\"delivery_complete\":false"));
        assertTrue(summary.contains("\"unconfirmed_from_seq\":1"));
        assertTrue(summary.contains("\"unconfirmed_through_seq\":2"));
        assertTrue(
                summary.contains("\"writer_ok\":true"),
                "Optional transport failure must not invalidate acquisition");
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
