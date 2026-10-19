package com.limacharlie.orderflow;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import velox.api.layer1.data.InstrumentInfo;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

/**
 * R-02 regression: strict historical enqueue must not block Bookmap's callback thread forever when
 * the writer stalls (e.g. hung disk I/O). A writer that makes no journal progress for the stall
 * window fails the export explicitly instead of hanging the callback — and, because send() holds
 * lifecycleLock while enqueuing, instead of also deadlocking stop().
 */
class WriterStallTest {
    @TempDir Path directory;

    void initialize(BookmapOrderflowExporter exporter) throws Exception {
        var settings = new BookmapOrderflowExporter.Settings();
        settings.outputDirectory = directory.toString();
        settings.queueCapacity = 4;
        // Strict historical mode: enqueue blocks instead of dropping.
        settings.responsiveLiveJournal = false;
        // Minimum clamp — the first checkpoint flush reaches disk at ~5s, stalling the writer there.
        settings.flushIntervalMs = 5_000;
        exporter.initialize(
                "SYNTH.CME@TEST",
                new InstrumentInfo("SYNTH", "CME", "TEST", .25, 1, "Synthetic", true),
                ExporterTest.api(settings),
                null);
    }

    @Test
    void stalledWriterFailsExportInsteadOfBlockingCallbackForever() throws Exception {
        var gate = new CountDownLatch(1); // released only in cleanup: simulates a hung disk
        var exporter =
                new BookmapOrderflowExporter(
                        path ->
                                new FilterOutputStream(Files.newOutputStream(path)) {
                                    private boolean firstWriteDone;

                                    @Override
                                    public void write(byte[] bytes, int offset, int length)
                                            throws IOException {
                                        if (firstWriteDone) {
                                            try {
                                                gate.await(); // second+ write hangs forever
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                                throw new IOException("Interrupted while hung", e);
                                            }
                                        }
                                        firstWriteDone = true;
                                        super.write(bytes, offset, length);
                                    }
                                });
        exporter.writerStallTimeoutSeconds = 2; // test bound; production default is 30s
        initialize(exporter);

        // Prime: the writer takes this into the in-memory buffer (no disk write yet).
        exporter.onTimestamp(1_000_000_000);
        exporter.send("order-0", true, 20000, 1);

        // Feeder keeps sending. Until ~5s the writer drains into memory; at the first checkpoint
        // flush it blocks in the hung-disk fake, the queue fills, and the next send() enters the
        // strict enqueue wait. RED (unbounded): that thread parks forever holding lifecycleLock.
        var failure = new AtomicReference<Throwable>();
        var feeder =
                new Thread(
                        () -> {
                            try {
                                for (int i = 1; ; i++) {
                                    exporter.send("order-" + i, true, 20000 + i, 1);
                                }
                            } catch (Throwable t) {
                                failure.set(t);
                            }
                        },
                        "stall-test-feeder");
        feeder.setDaemon(true); // never hold the JVM open if it parks forever (RED path)
        feeder.start();

        feeder.join(15_000);
        assertFalse(
                feeder.isAlive(),
                "callback thread must not block indefinitely on a stalled writer; "
                        + "it held lifecycleLock and would also deadlock stop()");
        assertNotNull(failure.get(), "feeder must have exited via an exception, not silently");
        assertInstanceOf(
                IllegalStateException.class, failure.get(), "stall must surface as IllegalStateException");
        assertTrue(
                String.valueOf(failure.get()).contains("stalled"),
                "failure message must identify the stalled writer: " + failure.get());

        // Cleanup: release the hung disk so the writer can drain and exit, then verify the export
        // is marked invalid end-to-end (summary written, stop() fails explicitly).
        gate.countDown();
        var stopFailure = assertThrows(IllegalStateException.class, exporter::stop);
        assertTrue(
                String.valueOf(stopFailure.getCause()).contains("stalled")
                        || String.valueOf(stopFailure).contains("stalled"),
                "stop failure must carry the stall cause: " + stopFailure);
    }
}
