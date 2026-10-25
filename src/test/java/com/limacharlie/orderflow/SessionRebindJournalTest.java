package com.limacharlie.orderflow;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.zeromq.*;
import velox.api.layer1.data.InstrumentInfo;

/**
 * The journal half of the export-session lifecycle. A settings reload (Apply: api.setSettings +
 * api.reload) re-enters initialize() without stop(), so the previous session's journal writer, its
 * EventBuffer, its open .partial handles and its bridge are live exactly when initialize()
 * overwrites the session fields. Nothing drains or finalizes the previous archive, the still-alive
 * writer keeps mutating the new session's counters and can consume the new session's records
 * through the shared queue field, and the new session inherits the previous archive_finalized
 * claim. Sessions must stay independent: the old journal is drained, finalized and summarised with
 * its own counts before initialize() rebinds, an old session whose pending work never got ACKed
 * stays explicitly failed/incomplete, and the new session sees only its own records.
 */
class SessionRebindJournalTest {
    @TempDir Path directory;

    private static Object field(Object instance, String name) throws Exception {
        var handle = BookmapOrderflowExporter.class.getDeclaredField(name);
        handle.setAccessible(true);
        return handle.get(instance);
    }

    private static Thread writerThreadOf(BookmapOrderflowExporter exporter) throws Exception {
        return (Thread) field(exporter, "writerThread");
    }

    private static boolean archiveFinalizedOf(BookmapOrderflowExporter exporter) throws Exception {
        return ((AtomicBoolean) field(exporter, "archiveFinalized")).get();
    }

    private static LiveBridge bridgeOf(BookmapOrderflowExporter exporter) throws Exception {
        return (LiveBridge) field(exporter, "bridge");
    }

    private static long counterOf(BookmapOrderflowExporter exporter, String name) throws Exception {
        return ((Long) field(exporter, name)).longValue();
    }

    private static String session(String json) {
        var key = "\"session\":\"";
        var from = json.indexOf(key);
        assertTrue(from >= 0, json);
        var to = json.indexOf('"', from + key.length());
        assertTrue(to > from + key.length(), json);
        return json.substring(from + key.length(), to);
    }

    private static String wireStatus(String bind, int healthPort) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        String reply = null;
        while (reply == null && System.nanoTime() < deadline)
            reply = BridgeHealthQuery.query(bind, healthPort, 200);
        return reply;
    }

    private static void assertPortsReusable(int market, int healthPort) throws Exception {
        try (var context = new ZContext();
                var data = context.createSocket(SocketType.ROUTER);
                var status = context.createSocket(SocketType.REP)) {
            assertTrue(data.bind("tcp://127.0.0.1:" + market), "data port still held after teardown");
            assertTrue(
                    status.bind("tcp://127.0.0.1:" + healthPort), "health port still held after teardown");
        }
    }

    private static BookmapOrderflowExporter.Settings bridgeSettings(
            Path dir, int market, int healthPort, int bridgeCapacity) {
        var settings = new BookmapOrderflowExporter.Settings();
        settings.outputDirectory = dir.toString();
        settings.bridgeEnabled = true;
        settings.bridgeBind = "127.0.0.1";
        settings.bridgePort = market;
        settings.bridgeHealthPort = healthPort;
        // Deliberate headroom so this fixture's historical pacing never waits; never enlarged to
        // dodge a defect (the journal queue stays at its production minimum either way).
        settings.bridgeQueueCapacity = bridgeCapacity;
        return settings;
    }

    private static boolean becomesDeadWithin(Thread thread, long seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (thread.isAlive() && System.nanoTime() < deadline) Thread.sleep(5);
        return !thread.isAlive();
    }

    private static String statusUntilTerminal(LiveBridge bridge, long seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        String status = bridge.status();
        while (!status.contains("\"state\":\"INVALID\"") && System.nanoTime() < deadline) {
            Thread.sleep(5);
            status = bridge.status();
        }
        return status;
    }

    private java.util.List<Path> filesWithin(String suffix, long seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        java.util.List<Path> found = files(suffix);
        while (found.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(5);
            found = files(suffix);
        }
        return found;
    }

    private java.util.List<Path> files(String suffix) throws Exception {
        try (var paths = java.nio.file.Files.list(directory)) {
            return paths.filter(p -> p.toString().endsWith(suffix)).sorted().toList();
        }
    }

    private String readSummaryOf(String stem) throws Exception {
        for (var path : files(".summary.json"))
            if (path.getFileName().toString().startsWith(stem))
                return java.nio.file.Files.readString(path);
        return "";
    }

    private java.util.List<String> archiveLinesOf(String stem) throws Exception {
        var archives = files(".ndjson.gz");
        for (var path : archives)
            if (path.getFileName().toString().startsWith(stem))
                try (var gzip = new GZIPInputStream(java.nio.file.Files.newInputStream(path))) {
                    return new String(gzip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                            .lines()
                            .toList();
                }
        return java.util.List.of();
    }

    private Path archiveOf(String stem) throws Exception {
        for (var path : files(".ndjson.gz"))
            if (path.getFileName().toString().startsWith(stem)) return path;
        return null;
    }

    /**
     * Structural session finalization, not just a count: one session's own contiguous seq 1..N run
     * under one alias, opened by START, carrying exactly one REALTIME_START lifecycle frame and
     * closed by the terminal STOP that normal stop() emits. Equal allocated/persisted counts alone
     * would also match a journal that was truncated by the reload.
     */
    private void assertJournalLifecycle(String stem, long expectedRecords, String alias) throws Exception {
        var lines = archiveLinesOf(stem);
        assertEquals(expectedRecords, (long) lines.size(), stem + " archive record count");
        var seq = Pattern.compile("\"seq\":(\\d+)");
        var realtimeStarts = 0L;
        var position = 0L;
        for (var line : lines) {
            position++;
            assertTrue(line.contains("\"alias\":\"" + alias + "\""), line);
            var matcher = seq.matcher(line);
            assertTrue(matcher.find(), line);
            assertEquals(position, Long.parseLong(matcher.group(1)), "journal sequence discontinuity at " + position);
            if (line.contains("\"control\":\"REALTIME_START\"")) realtimeStarts++;
        }
        assertTrue(lines.get(0).contains("\"control\":\"START\""), "archive must open with START: " + lines.get(0));
        assertEquals(
                1L,
                realtimeStarts,
                "archive must carry exactly one REALTIME_START lifecycle frame");
        assertTrue(
                lines.get(lines.size() - 1).contains("\"control\":\"STOP\""),
                "archive must close with the terminal STOP: " + lines.get(lines.size() - 1));
    }

    /**
     * gzip integrity of a finalized archive, verified by the test itself rather than only accepted
     * by a decoder: gzip magic, and a trailer whose CRC32 and ISIZE match the decompressed payload.
     * The single-member boundary itself is not scanned (see the reviewer notes).
     */
    private void assertGzipIntegrity(String stem) throws Exception {
        var path = archiveOf(stem);
        assertNotNull(path, "no finalized archive for " + stem);
        var raw = java.nio.file.Files.newInputStream(path).readAllBytes();
        assertTrue(raw.length > 8, "archive shorter than a gzip trailer");
        assertEquals(0x1f, raw[0] & 0xff, "gzip magic byte 1");
        assertEquals(0x8b, raw[1] & 0xff, "gzip magic byte 2");
        long decodedLength;
        long checksum;
        try (var gzip = new GZIPInputStream(java.nio.file.Files.newInputStream(path))) {
            var payload = gzip.readAllBytes();
            decodedLength = payload.length;
            checksum = crc32Of(payload);
        }
        var trailing = raw.length - 8;
        var storedCrc =
                (raw[trailing] & 0xffL)
                        | ((raw[trailing + 1] & 0xffL) << 8)
                        | ((raw[trailing + 2] & 0xffL) << 16)
                        | ((raw[trailing + 3] & 0xffL) << 24);
        var storedSize =
                (raw[trailing + 4] & 0xffL)
                        | ((raw[trailing + 5] & 0xffL) << 8)
                        | ((raw[trailing + 6] & 0xffL) << 16)
                        | ((raw[trailing + 7] & 0xffL) << 24);
        assertEquals(storedCrc, checksum, "gzip CRC32 trailer must match the decompressed payload");
        assertEquals(storedSize, decodedLength & 0xffffffffL, "gzip ISIZE trailer must match the payload size");
    }

    private static long crc32Of(byte[] payload) throws Exception {
        var table = crcTable();
        var crc = 0xffffffffL;
        for (var value : payload) crc = table[(int) ((crc ^ value) & 0xff)] ^ (crc >>> 8);
        return (crc ^ 0xffffffffL) & 0xffffffffL;
    }

    private static long[] crcTable() throws Exception {
        var table = new long[256];
        for (int index = 0; index < 256; index++) {
            var entry = (long) index;
            for (int step = 0; step < 8; step++)
                entry = (entry & 1) == 1 ? 0xedb88320L ^ (entry >>> 1) : entry >>> 1;
            table[index] = entry;
        }
        return table;
    }

    @Test
    void reloadFinalizesThePreviousJournalAndKeepsTheNewSessionIndependent() throws Exception {
        int market = ExporterTest.freePort(), healthPort = ExporterTest.freePort();
        while (healthPort == market) healthPort = ExporterTest.freePort();
        var settings = bridgeSettings(directory, market, healthPort, 64);
        var info = new InstrumentInfo("SYNTH", "CME", "TEST", .25, 1, "Synthetic", true);

        var exporter = new BookmapOrderflowExporter();
        exporter.initialize("SYNTH.CME.PREVIOUS", info, ExporterTest.api(settings), null);
        // Historical frames plus one realtime frame: 24 records allocated (START, 20 MBO adds,
        // REALTIME_START, one realtime add), all of them still unACKed on the old bridge because
        // no receiver is attached to the pre-reload session.
        exporter.onTimestamp(1_000_000_000L);
        for (int i = 0; i < 20; i++) exporter.send("previous-" + i, true, 20000 + i, 1);
        exporter.onRealtimeStart();
        exporter.send("previous-live", true, 20021, 1);
        var previousSequence = counterOf(exporter, "sequence");
        var previousBridge = bridgeOf(exporter);
        assertNotNull(previousBridge);
        var previousWriter = writerThreadOf(exporter);
        assertTrue(previousWriter.isAlive(), "the previous session owns a live writer before reload");
        // No POISON has ever been offered, so the previous archive cannot have been finalized yet.
        assertTrue(files(".ndjson.gz").isEmpty(), "fixture precondition: nothing finalized yet");
        assertEquals(1, (long) filesWithin(".partial", 10).size(), "the previous session stages a .partial archive");
        assertTrue(readSummaryOf("SYNTH.CME.PREVIOUS").isEmpty(), "no previous summary yet");

        exporter.initialize("SYNTH.CME.CURRENT", info, ExporterTest.api(settings), null);
        var current = bridgeOf(exporter);
        assertNotNull(current);

        // The previous session was released by the reload itself, before the rebind. Normal
        // session finalization emits the terminal STOP first, so the old journal holds its own
        // allocated records plus that STOP.
        var expectedPreviousRecords = previousSequence + 1L;
        var previousSummary = readSummaryOf("SYNTH.CME.PREVIOUS");
        assertTrue(previousSummary.contains("\"writer_ok\":true"), "previous session summary missing: " + previousSummary);
        assertTrue(previousSummary.contains("\"archive_finalized\":true"), previousSummary);
        assertTrue(
                previousSummary.contains("\"last_allocated_seq\":" + expectedPreviousRecords + ","), previousSummary);
        assertTrue(
                previousSummary.contains("\"records_persisted\":" + expectedPreviousRecords + ","), previousSummary);
        assertTrue(
                previousSummary.contains("\"total_records\":" + expectedPreviousRecords + ","), previousSummary);
        assertTrue(previousSummary.contains("\"journal_dropped\":0"), previousSummary);
        assertTrue(previousSummary.contains("\"journal_overflows\":0"), previousSummary);
        assertEquals(
                expectedPreviousRecords,
                (long) archiveLinesOf("SYNTH.CME.PREVIOUS").size(),
                "previous archive must hold exactly its own " + expectedPreviousRecords + " records");
        for (var line : archiveLinesOf("SYNTH.CME.PREVIOUS"))
            assertTrue(line.contains("\"alias\":\"SYNTH.CME.PREVIOUS\""), line);
        assertJournalLifecycle("SYNTH.CME.PREVIOUS", expectedPreviousRecords, "SYNTH.CME.PREVIOUS");
        assertGzipIntegrity("SYNTH.CME.PREVIOUS");
        assertEquals(
                1,
                (long) filesWithin(".partial", 10).size(),
                "the previous .partial must be renamed by the reload; only the new session stages its own");
        assertTrue(
                becomesDeadWithin(previousWriter, 15),
                "the previous writer thread and its file handles must exit on the reload");

        // The old session never received an ACK: it stays explicitly incomplete, never delivered.
        var previousStatus = previousBridge.status();
        assertFalse(previousStatus.contains("\"delivery_complete\":true"), previousStatus);
        assertFalse(previousStatus.contains("\"discarded_unacknowledged\":0"), previousStatus);

        // The new session starts clean and owns exactly one writer.
        assertFalse(archiveFinalizedOf(exporter), "a reloaded session must not inherit the previous archive_finalized claim");
        assertTrue(writerThreadOf(exporter).isAlive(), "the current session owns its own writer");

        // Fresh receiver attaching after the reload: HELLO 0 -> WELCOME -> START..STOP on the
        // current session only.
        var currentSession = session(current.status());
        var failure = new AtomicReference<Throwable>();
        var done = new CountDownLatch(1);
        var producer = new Thread(() -> {
            try {
                exporter.onTimestamp(2_000_000_000L);
                exporter.send("current-add", true, 20000, 1);
                exporter.onRealtimeStart();
                exporter.send("current-live", true, 20001, 1);
            } catch (Throwable callbackFault) {
                failure.set(callbackFault);
            } finally {
                try {
                    exporter.stop();
                } catch (Throwable stopFault) {
                    failure.compareAndSet(null, stopFault);
                }
                done.countDown();
            }
        });
        producer.setDaemon(true);
        producer.start();

        long accepted = 0;
        boolean sawStop = false;
        try (var context = new ZContext()) {
            var receiver = context.createSocket(SocketType.DEALER);
            receiver.setLinger(0);
            receiver.setReceiveTimeOut(2000);
            receiver.connect("tcp://127.0.0.1:" + market);
            receiver.send("HELLO rebind-journal 0");
            var welcome = receiver.recvStr();
            assertNotNull(welcome, current.status());
            assertTrue(welcome.contains("WELCOME"), welcome);
            assertEquals(currentSession, session(welcome), welcome);
            var seq = Pattern.compile("\"seq\":(\\d+),");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!sawStop && System.nanoTime() < deadline) {
                var event = receiver.recvStr();
                assertNotNull(event, current.status());
                assertTrue(event.contains("\"type\":\"EVENT\""), event);
                var matcher = seq.matcher(event);
                assertTrue(matcher.find(), event);
                assertEquals(++accepted, Long.parseLong(matcher.group(1)), event);
                assertEquals(currentSession, session(event), event);
                sawStop = event.contains("\"control\":\"STOP\"");
                receiver.send("ACK rebind-journal " + accepted);
            }
            assertTrue(done.await(10, TimeUnit.SECONDS), current.status());
        }
        assertEquals(5, accepted, "START, one MBO add, REALTIME_START, one MBO add, STOP");
        assertTrue(sawStop);
        assertNull(failure.get(), "the new session must stop cleanly");
        var currentStatus = current.status();
        assertTrue(currentStatus.contains("\"state\":\"STOPPED\""), currentStatus);
        assertTrue(currentStatus.contains("\"delivery_complete\":true"), currentStatus);
        assertTrue(currentStatus.contains("\"events_published\":5"), currentStatus);
        assertTrue(currentStatus.contains("\"acknowledged_seq\":5"), currentStatus);
        assertTrue(currentStatus.contains("\"overflows\":0"), currentStatus);
        assertPortsReusable(market, healthPort);

        // The new archive carries only the new session's records.
        var currentSummary = readSummaryOf("SYNTH.CME.CURRENT");
        assertTrue(currentSummary.contains("\"writer_ok\":true"), currentSummary);
        assertTrue(currentSummary.contains("\"archive_finalized\":true"), currentSummary);
        assertTrue(currentSummary.contains("\"records_persisted\":5"), currentSummary);
        assertTrue(currentSummary.contains("\"journal_dropped\":0"), currentSummary);
        assertTrue(currentSummary.contains("\"journal_overflows\":0"), currentSummary);
        var currentLines = archiveLinesOf("SYNTH.CME.CURRENT");
        assertEquals(5, currentLines.size(), "the new archive holds exactly its own five records");
        for (var line : currentLines) {
            assertTrue(line.contains("\"alias\":\"SYNTH.CME.CURRENT\""), line);
            assertFalse(line.contains("previous-"), line);
        }
        assertJournalLifecycle("SYNTH.CME.CURRENT", 5L, "SYNTH.CME.CURRENT");
        assertGzipIntegrity("SYNTH.CME.CURRENT");
        assertEquals(2, files(".ndjson.gz").size(), "one finalized archive per session");
        assertTrue(files(".partial").isEmpty(), "no archive left unfinalized after a clean stop");
    }

    @Test
    void stalledPreviousSessionFailsClosedInsideTheBoundAndCannotConsumeNewRecords() throws Exception {
        int market = ExporterTest.freePort(), healthPort = ExporterTest.freePort();
        while (healthPort == market) healthPort = ExporterTest.freePort();
        var settings = bridgeSettings(directory, market, healthPort, 256);
        var info = new InstrumentInfo("SYNTH", "CME", "TEST", .25, 1, "Synthetic", true);

        // The gate keeps every allocated record pending in the journal queue at the reload point:
        // deterministic prior writer work, no timing sleep standing in for the drain.
        var release = new CountDownLatch(1);
        var opened = new AtomicBoolean();
        var exporter = new BookmapOrderflowExporter(
                path -> {
                    if (opened.compareAndSet(false, true)) {
                        // Interrupt-resistant hung journal I/O: neither the POISON ask nor the
                        // release's interrupt may stand in for the termination proof.
                        var stalled = true;
                        while (stalled)
                            try {
                                stalled = !release.await(1, TimeUnit.SECONDS);
                            } catch (InterruptedException interruption) {
                            }
                    }
                    return java.nio.file.Files.newOutputStream(path);
                });
        exporter.initialize("SYNTH.CME.STALLY", info, ExporterTest.api(settings), null);
        exporter.writerStallTimeoutSeconds = 1; // existing test-tunable bound; production stays 30s
        exporter.onTimestamp(1_000_000_000L);
        for (int i = 0; i < 200; i++) exporter.send("stally-" + i, true, 20000 + i % 20, 1);
        var previousSequence = counterOf(exporter, "sequence");
        assertEquals(201L, previousSequence, "START plus 200 callbacks, all pending in the journal queue");
        assertEquals(0L, counterOf(exporter, "recordsPersisted"), "fixture precondition: nothing persisted");
        var previousBridge = bridgeOf(exporter);
        assertNotNull(previousBridge);
        var previousWriter = writerThreadOf(exporter);
        assertTrue(previousWriter.isAlive(), "the stalled writer is still alive when the reload happens");

        long reloadStarted = System.nanoTime();
        var refusal = new AtomicReference<Throwable>();
        try {
            exporter.initialize("SYNTH.CME.AFTER", info, ExporterTest.api(settings), null);
        } catch (Throwable rejected) {
            refusal.set(rejected);
        }
        var teardownSeconds = (System.nanoTime() - reloadStarted) / 1_000_000_000L;
        assertTrue(
                teardownSeconds <= 5L,
                "the bounded release must stay inside its bound, never block the Apply handler for ever: "
                        + teardownSeconds
                        + "s with a 1s bound");

        // Fail closed: while the previous writer is still live the reload is refused outright.
        assertInstanceOf(
                IllegalStateException.class,
                refusal.get(),
                "a reload whose previous writer is still live must be refused, not silently accepted: "
                        + refusal.get());
        assertTrue(refusal.get().toString().contains("reload refused"), "refusal must name the cause: " + refusal.get());

        // Nothing new was created: one session, one writer, one bridge, no field replacement.
        assertEquals(previousWriter, writerThreadOf(exporter), "no new writer may replace a live one");
        assertTrue(previousWriter.isAlive(), "the previous writer stays behind the refusal until it terminates");
        var stillCurrent = bridgeOf(exporter);
        assertNotNull(stillCurrent, "the previous bridge may not be discarded on a refused reload");
        assertEquals(previousBridge, stillCurrent, "no new bridge may be created behind a live writer");
        assertEquals(
                session(previousBridge.status()),
                session(stillCurrent.status()),
                "still exactly one session owns the port pair");
        var expectedPreviousRecords = previousSequence + 1L;
        assertEquals(
                expectedPreviousRecords,
                counterOf(exporter, "sequence"),
                "the previous session keeps its own allocation, terminal STOP included");
        assertEquals(0L, counterOf(exporter, "recordsPersisted"), "nothing may be persisted behind the refusal");
        assertEquals(0L, counterOf(exporter, "journalDropped"), "no prior pending work may be silently dropped");
        assertEquals(0L, counterOf(exporter, "journalOverflows"), "no journal overflow on this fixture");
        assertTrue(files(".ndjson.gz").isEmpty(), "a stalled previous session must not produce a final archive");
        assertEquals(1L, (long) files(".summary.json").size(), "one session, one summary");

        // The previous session is recorded as explicitly failed/incomplete with its own counts.
        var previousSummary = readSummaryOf("SYNTH.CME.STALLY");
        assertTrue(previousSummary.contains("\"writer_ok\":false"), "previous stalled session summary missing: " + previousSummary);
        assertTrue(previousSummary.contains("\"archive_finalized\":false"), previousSummary);
        assertTrue(
                previousSummary.contains("\"last_allocated_seq\":" + expectedPreviousRecords + ","), previousSummary);
        assertTrue(previousSummary.contains("\"records_persisted\":0,"), previousSummary);
        assertTrue(previousSummary.contains("did not terminate"), previousSummary);
        var stalledStatus = statusUntilTerminal(previousBridge, 10L);
        assertFalse(stalledStatus.contains("\"delivery_complete\":true"), stalledStatus);
        // Fail closed INSIDE the bound, independently of the stalled writer: the old bridge is
        // released separately, so it ends INVALID (never left live/WAITING_RECEIVER behind a
        // refused reload), with its whole unACKed backlog accounted for in discarded_unacknowledged
        // rather than silently lost, and its unACKed range still visible as unconfirmed.
        assertTrue(
                stalledStatus.contains("\"invalid\":true"),
                "the refused session's bridge must fail closed, not stay live: " + stalledStatus);
        assertTrue(
                stalledStatus.contains("\"state\":\"INVALID\""),
                "the refused session's bridge must fail closed, not stay live: " + stalledStatus);
        assertTrue(
                stalledStatus.contains("\"reason\":\"shutdown with unacknowledged events\""),
                "the release must name its own cause: " + stalledStatus);
        assertTrue(
                stalledStatus.contains("\"discarded_unacknowledged\":" + expectedPreviousRecords),
                "the refused session's whole backlog must be accounted for, nothing lost: "
                        + stalledStatus);
        assertTrue(
                stalledStatus.contains("\"unconfirmed_through_seq\":" + expectedPreviousRecords),
                "the unACKed range must stay visible: " + stalledStatus);

        // Recovery: once the hung journal I/O is released the previous writer terminates properly,
        // and only then may a reload rebind.
        release.countDown();
        assertTrue(
                becomesDeadWithin(previousWriter, 20),
                "the released writer must drain and exit, it may not leak behind the next session");
        assertEquals(java.util.List.of(), archiveLinesOf("SYNTH.CME.STALLY"), "no previous archive may appear late");
        assertEquals(1L, (long) files(".partial").size(), "its incomplete journal stays as evidence");
        assertEquals(previousSummary, readSummaryOf("SYNTH.CME.STALLY"), "the recorded failed status is not rewritten");
        assertEquals(1L, (long) files(".summary.json").size(), "the released session is not summarised twice");

        exporter.initialize("SYNTH.CME.AFTER", info, ExporterTest.api(settings), null);
        var current = bridgeOf(exporter);
        assertNotNull(current);
        assertNotEquals(
                session(previousBridge.status()),
                session(current.status()),
                "the new session owns a fresh identity");
        assertEquals(
                session(current.status()),
                session(wireStatus("127.0.0.1", healthPort)),
                "the closed old listener may not keep owning the port pair");
        assertTrue(writerThreadOf(exporter).isAlive(), "the new session owns its own writer");
        // Contract from source: initialize() re-enters resetRunState() (sequence/recordsPersisted
        // cleared) and then emits its own START synchronously, so the reloaded session's allocation
        // is exactly 1, and its writer can persist at most the record the NEW session allocated. A
        // leak of the refused session's 202 pending records would show 202/203 here.
        assertEquals(
                1L,
                counterOf(exporter, "sequence"),
                "the new session allocated only its own START, never the refused session's backlog");
        var newSessionPersisted = counterOf(exporter, "recordsPersisted");
        assertTrue(
                newSessionPersisted <= 1L,
                "the new writer may persist only records the new session allocated (its START): "
                        + newSessionPersisted);
        assertFalse(archiveFinalizedOf(exporter), "the new session must not inherit the previous session's finalization claim");
        assertEquals(0L, counterOf(exporter, "journalDropped"), "no prior pending work may leak into the new session");
        assertEquals(0L, counterOf(exporter, "journalOverflows"), "no prior journal overflow may leak into the new session");

        // The new session runs to a clean STOP and its counts are its own.
        exporter.onTimestamp(2_000_000_000L);
        exporter.send("after-add", true, 20000, 1);
        exporter.onRealtimeStart();
        exporter.send("after-live", true, 20001, 1);
        exporter.stop();
        var currentSummary = readSummaryOf("SYNTH.CME.AFTER");
        assertTrue(currentSummary.contains("\"writer_ok\":true"), currentSummary);
        assertTrue(currentSummary.contains("\"archive_finalized\":true"), currentSummary);
        assertTrue(currentSummary.contains("\"records_persisted\":5"), currentSummary);
        assertTrue(currentSummary.contains("\"journal_dropped\":0"), currentSummary);
        assertTrue(currentSummary.contains("\"journal_overflows\":0"), currentSummary);
        var currentLines = archiveLinesOf("SYNTH.CME.AFTER");
        assertEquals(5, currentLines.size(), "the new archive holds exactly its own five records");
        for (var line : currentLines) {
            assertTrue(line.contains("\"alias\":\"SYNTH.CME.AFTER\""), line);
            assertFalse(line.contains("stally-"), line);
        }
        assertJournalLifecycle("SYNTH.CME.AFTER", 5L, "SYNTH.CME.AFTER");
        assertGzipIntegrity("SYNTH.CME.AFTER");
        var currentStatus = bridgeOf(exporter).status();
        // The new session had no receiver attached, so its own bridge fails closed exactly as the
        // existing contract requires; what must hold is that its counters are its own, not the
        // stalled session's: the journal block below reports 5 records, never the 201 pending ones.
        assertTrue(currentStatus.contains("\"current_seq\":5"), currentStatus);
        assertTrue(currentStatus.contains("\"records_persisted\":5"), currentStatus);
        var oldStatus = previousBridge.status();
        assertFalse(oldStatus.contains("\"delivery_complete\":true"), oldStatus);
        assertFalse(oldStatus.contains("\"discarded_unacknowledged\":0"), oldStatus);
    }

    @Test
    void firstInitializeHasNoSessionToReleaseAndReloadAfterACleanStopIsIdempotent() throws Exception {
        var exporter = new BookmapOrderflowExporter();
        var info = new InstrumentInfo("SYNTH", "CME", "TEST", .25, 1, "Synthetic", true);
        var settings = new BookmapOrderflowExporter.Settings();
        settings.outputDirectory = directory.toString();

        // First initialize: no previous session, no teardown side effects.
        exporter.initialize("SYNTH.CME.FIRST", info, ExporterTest.api(settings), null);
        assertTrue(files(".summary.json").isEmpty(), "the first initialize must not finalize anything");
        assertFalse(filesWithin(".partial", 10).isEmpty(), "the new session stages its own archive");

        exporter.onTimestamp(1_000_000_000L);
        exporter.send("first-1", true, 20000, 1);
        exporter.stop();
        assertEquals(1, files(".summary.json").size(), "one session, one summary");
        assertEquals(1, files(".ndjson.gz").size(), "one finalized archive");
        assertTrue(files(".partial").isEmpty(), "clean stop leaves no .partial");

        // Reload after a clean stop: the session was already released by stop(), so nothing is
        // finalised twice and the previous summary/archive stay exactly as stop() left them.
        var firstSummary = readSummaryOf("SYNTH.CME.FIRST");
        var firstArchive = archiveLinesOf("SYNTH.CME.FIRST");
        exporter.initialize("SYNTH.CME.SECOND", info, ExporterTest.api(settings), null);
        assertEquals(firstSummary, readSummaryOf("SYNTH.CME.FIRST"), "the released session must not be re-summarised");
        assertEquals(firstArchive, archiveLinesOf("SYNTH.CME.FIRST"), "the finalized archive must not be rewritten");
        assertFalse(archiveFinalizedOf(exporter), "the second session starts unfinalized");
        assertEquals(1, files(".summary.json").size(), "the reload does not write a second summary for the released session");

        // Two more reloads must never stack writers.
        for (int reload = 0; reload < 2; reload++) {
            var previousWriter = writerThreadOf(exporter);
            exporter.initialize("SYNTH.CME.SECOND", info, ExporterTest.api(settings), null);
            assertTrue(becomesDeadWithin(previousWriter, 30), "reload " + reload + " must retire the previous writer");
            assertTrue(writerThreadOf(exporter).isAlive(), "exactly one writer stays alive");
        }
        exporter.stop();
        assertEquals(4, files(".summary.json").size(), "every session that ran gets its own summary");
        assertEquals(4, files(".ndjson.gz").size(), "every session gets its own finalized archive");
        assertTrue(files(".partial").isEmpty(), "no archive left unfinalized");
    }
}
