package com.limacharlie.orderflow;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import velox.api.layer1.data.InstrumentInfo;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.zip.GZIPInputStream;

/**
 * R-07 regression: the archive must be staged as a .partial file, fsynced before close, and only
 * renamed to its final name after a clean, count-matched shutdown. A failed write must leave an
 * explicit incomplete file instead of a truncated gzip wearing the final name.
 */
class ArchiveFinalizationTest {
    @TempDir Path directory;

    void initialize(BookmapOrderflowExporter exporter) throws Exception {
        var settings = new BookmapOrderflowExporter.Settings();
        settings.outputDirectory = directory.toString();
        settings.queueCapacity = 1000;
        exporter.initialize(
                "SYNTH.CME@TEST",
                new InstrumentInfo("SYNTH", "CME", "TEST", .25, 1, "Synthetic", true),
                ExporterTest.api(settings),
                null);
    }

    void driveEvents(BookmapOrderflowExporter exporter) {
        exporter.onTimestamp(1_000_000_000);
        exporter.onRealtimeStart();
        for (int i = 0; i < 50; i++) exporter.send("order-" + i, true, 20000, 1);
    }

    String summary() throws IOException {
        try (var paths = Files.list(directory)) {
            return Files.readString(
                    paths.filter(p -> p.toString().endsWith(".summary.json"))
                            .findFirst()
                            .orElseThrow());
        }
    }

    List<Path> files(String suffix) throws IOException {
        try (var paths = Files.list(directory)) {
            return paths.filter(p -> p.toString().endsWith(suffix)).sorted().toList();
        }
    }

    @Test
    void cleanStopFsyncsAndAtomicallyRenamesPartialToFinalArchive() throws Exception {
        var syncRequests = new AtomicInteger();
        var exporter =
                new BookmapOrderflowExporter(
                        new BookmapOrderflowExporter.JournalOutputFactory() {
                            @Override
                            public OutputStream open(Path path) throws IOException {
                                return Files.newOutputStream(path);
                            }

                            @Override
                            public void sync(OutputStream out) throws IOException {
                                syncRequests.incrementAndGet();
                            }
                        });
        initialize(exporter);
        driveEvents(exporter);
        exporter.stop(); // clean stop must not throw

        var finalArchives = files(".ndjson.gz");
        assertEquals(1, finalArchives.size(), "exactly one finalized archive expected");
        assertTrue(files(".partial").isEmpty(), ".partial must be renamed away on clean stop");

        int persistedLines;
        try (var in = new GZIPInputStream(Files.newInputStream(finalArchives.get(0)))) {
            byte[] payload = in.readAllBytes();
            persistedLines = (int) IntStream.range(0, payload.length).filter(i -> payload[i] == '\n').count();
        }
        assertTrue(persistedLines > 0, "finalized archive must contain the persisted events");

        String text = summary();
        assertTrue(text.contains("\"archive_finalized\":true"));
        assertTrue(text.contains("\"writer_ok\":true"));
        var persistedMatch = java.util.regex.Pattern.compile("\"records_persisted\":(\\d+)").matcher(text);
        assertTrue(persistedMatch.find(), "summary must report records_persisted");
        int recordsPersisted = Integer.parseInt(persistedMatch.group(1));
        assertEquals(recordsPersisted, persistedLines, "gzip line count must match summary");
        assertTrue(syncRequests.get() >= 1, "durable sync must be requested before close");
    }

    @Test
    void failedWriteLeavesExplicitPartialAndNeverCreatesFinalArchive() throws Exception {
        var exporter =
                new BookmapOrderflowExporter(
                        path ->
                                new FilterOutputStream(Files.newOutputStream(path)) {
                                    private boolean firstWriteDone;

                                    @Override
                                    public void write(byte[] bytes, int offset, int length)
                                            throws IOException {
                                        if (firstWriteDone) {
                                            throw new IOException("Synthetic full disk");
                                        }
                                        firstWriteDone = true;
                                        super.write(bytes, offset, length);
                                    }
                                });
        initialize(exporter);
        driveEvents(exporter);
        assertThrows(IllegalStateException.class, exporter::stop);

        assertEquals(1, files(".partial").size(), "incomplete archive must remain as .partial");
        assertTrue(files(".ndjson.gz").isEmpty(), "no final-name file may exist after failure");
        String text = summary();
        assertTrue(text.contains("\"archive_finalized\":false"));
        assertTrue(text.contains("\"writer_ok\":false"));
        assertTrue(text.contains("Synthetic full disk"));
    }
}
