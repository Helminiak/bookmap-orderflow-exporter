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
    void historicalStopDoesNotWaitForMissingReceiverCapacity() throws Exception {
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
