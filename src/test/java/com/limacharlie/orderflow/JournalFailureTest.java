package com.limacharlie.orderflow;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import velox.api.layer1.data.InstrumentInfo;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;

class JournalFailureTest {
    @TempDir Path directory;

    void initialize(BookmapOrderflowExporter exporter, int capacity) {
        var settings = new BookmapOrderflowExporter.Settings();
        settings.outputDirectory = directory.toString();
        settings.queueCapacity = capacity;
        exporter.initialize(
                "SYNTH.CME@TEST",
                new InstrumentInfo("SYNTH", "CME", "TEST", .25, 1, "Synthetic", true),
                ExporterTest.api(settings),
                null);
    }

    String summary() throws IOException {
        try (var paths = Files.list(directory)) {
            return Files.readString(
                    paths.filter(p -> p.toString().endsWith(".summary.json"))
                            .findFirst()
                            .orElseThrow());
        }
    }

    @Test
    void outputOpenFailureIsExplicitAndCannotCertifyArchive() throws Exception {
        var attempted = new CountDownLatch(1);
        var fail = new CountDownLatch(1);
        var exporter =
                new BookmapOrderflowExporter(
                        path -> {
                            attempted.countDown();
                            try {
                                fail.await();
                            } catch (InterruptedException e) {
                                throw new IOException(e);
                            }
                            throw new IOException("Synthetic storage permission failure");
                        });
        initialize(exporter, 1000);
        assertTrue(attempted.await(2, TimeUnit.SECONDS));
        fail.countDown();
        assertThrows(IllegalStateException.class, exporter::stop);
        assertTrue(summary().contains("\"writer_ok\":false"));
        assertTrue(summary().contains("Synthetic storage permission failure"));
    }

    @Test
    void outputWriteAndCloseFailuresInvalidateOtherwiseCompleteCounts() throws Exception {
        for (boolean closeFailure : new boolean[] {false, true}) {
            var exporter =
                    new BookmapOrderflowExporter(
                            path ->
                                    new ByteArrayOutputStream() {
                                        @Override
                                        public void write(byte[] bytes, int offset, int length) {
                                            if (!closeFailure)
                                                throw new java.io.UncheckedIOException(
                                                        new IOException("Synthetic full disk"));
                                            super.write(bytes, offset, length);
                                        }

                                        @Override
                                        public void close() throws IOException {
                                            if (closeFailure)
                                                throw new IOException("Synthetic close failure");
                                        }
                                    });
            initialize(exporter, 1000);
            assertThrows(IllegalStateException.class, exporter::stop);
            String text = summary();
            assertTrue(text.contains("\"writer_ok\":false"));
            assertTrue(
                    text.contains(
                            closeFailure ? "Synthetic close failure" : "Synthetic full disk"));
            // Each iteration uses a fresh path even if wall-clock granularity is coarse.
            try (var paths = Files.list(directory)) {
                for (Path path : paths.toList()) Files.delete(path);
            }
        }
    }

    @Test
    void stalledStorageDoesNotBlockResponsiveLiveCallbacksAndOverflowIsExplicit() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var exporter =
                new BookmapOrderflowExporter(
                        path -> {
                            entered.countDown();
                            try {
                                release.await();
                            } catch (InterruptedException e) {
                                throw new IOException(e);
                            }
                            assertEquals(
                                    "bookmap-orderflow-writer", Thread.currentThread().getName());
                            return Files.newOutputStream(path);
                        });
        initialize(exporter, 1000);
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        try {
            assertTimeoutPreemptively(
                    Duration.ofSeconds(2),
                    () -> {
                        exporter.onTimestamp(1_000_000_000);
                        exporter.onRealtimeStart();
                        for (int i = 0; i < 20000; i++) exporter.send("order-" + i, true, 20000, 1);
                    });
        } finally {
            release.countDown();
        }
        assertThrows(IllegalStateException.class, exporter::stop);
        String text = summary();
        assertTrue(text.contains("\"writer_ok\":false"));
        assertTrue(text.contains("Live journal queue overflow; archive INVALID"));
        assertTrue(text.contains("\"journal_overflows\":1"));
        assertFalse(text.contains("\"journal_dropped\":0"));
        assertTrue(text.contains("\"last_allocated_seq\":20003"));
    }
}
