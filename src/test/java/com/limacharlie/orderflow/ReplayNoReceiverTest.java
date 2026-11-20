package com.limacharlie.orderflow;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.zeromq.*;
import velox.api.layer1.data.InstrumentInfo;

/**
 * D-05-1: Bookmap file replay with NO admitted receiver must end in an explicit terminal bridge
 * failure (never a silent "successful" run), while the journal/archive stays complete and the bridge
 * counters stay truthful. Offline and synthetic; no Bookmap, Windows or network beyond loopback.
 */
class ReplayNoReceiverTest {
    @TempDir Path directory;

    static final int HISTORY = 200, REPLAY = 200;
    /** START + HISTORY + REALTIME_START + REPLAY + STOP. */
    static final int TOTAL = 1 + HISTORY + 1 + REPLAY + 1;

    /** Test-only deadline injection point (see LiveBridge historical wait). */
    static void configureWait(BookmapOrderflowExporter exporter, long nanos) {
        exporter.bridgeHistoricalWaitNanos = nanos;
    }

    record Run(String summary, long producerMillis, Throwable producerFailure, String stdout) {}

    /** Producer runs on its own thread like Bookmap's callback thread; the receiver is optional. */
    Run replay(long waitNanos, java.util.function.Consumer<int[]> receiver) throws Exception {
        var captured = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try (var mode = ReplayJournalTest.sourceMode(false)) {
            int market = ExporterTest.freePort(), health = ExporterTest.freePort();
            while (health == market) health = ExporterTest.freePort();
            var settings = new BookmapOrderflowExporter.Settings();
            settings.outputDirectory = directory.toString();
            settings.bridgeEnabled = true;
            settings.bridgeBind = "127.0.0.1";
            settings.bridgePort = market;
            settings.bridgeHealthPort = health;
            settings.bridgeQueueCapacity = 4;
            var exporter = new BookmapOrderflowExporter();
            configureWait(exporter, waitNanos);
            exporter.initialize(
                    "SYNTH.CME@TEST",
                    new InstrumentInfo("SYNTH", "CME", "TEST", .25, 1, "Synthetic", true),
                    ExporterTest.api(settings),
                    null);
            var failure = new AtomicReference<Throwable>();
            var done = new CountDownLatch(1);
            long started = System.nanoTime();
            var producer =
                    new Thread(
                            () -> {
                                try {
                                    exporter.onTimestamp(1_000_000_000L);
                                    for (int i = 0; i < HISTORY; i++)
                                        exporter.send("history-" + i, true, 20000, 1);
                                    exporter.onRealtimeStart();
                                    for (int i = 0; i < REPLAY; i++)
                                        exporter.send("replay-" + i, true, 20001, 1);
                                } catch (Throwable t) {
                                    failure.set(t);
                                } finally {
                                    try {
                                        exporter.stop();
                                    } catch (Throwable t) {
                                        failure.compareAndSet(null, t);
                                    }
                                    done.countDown();
                                }
                            });
            producer.setDaemon(true);
            producer.start();
            var port = new int[] {market};
            if (receiver != null) receiver.accept(port);
            assertTrue(done.await(60, TimeUnit.SECONDS), "producer must finish: no unbounded wait");
            long millis = (System.nanoTime() - started) / 1_000_000L;
            String json;
            try (var files = Files.list(directory)) {
                json =
                        Files.readString(
                                files.filter(p -> p.toString().endsWith(".summary.json"))
                                        .findFirst()
                                        .orElseThrow());
            }
            return new Run(json, millis, failure.get(), captured.toString(StandardCharsets.UTF_8));
        } finally {
            System.setOut(originalOut);
        }
    }

    static long num(String json, String key) {
        var m = Pattern.compile("\"" + key + "\":(-?\\d+)").matcher(json);
        assertTrue(m.find(), key + " missing: " + json);
        return Long.parseLong(m.group(1));
    }

    static String str(String json, String key) {
        var m = Pattern.compile("\"" + key + "\":(null|\"((?:[^\"\\\\]|\\\\.)*)\")").matcher(json);
        assertTrue(m.find(), key + " missing: " + json);
        return m.group(2);
    }

    /** Archive must hold every allocated seq, contiguous, START .. STOP, whatever the bridge did. */
    void assertJournalComplete(String json) throws Exception {
        assertTrue(json.contains("\"writer_ok\":true"), json);
        assertTrue(json.contains("\"archive_finalized\":true"), json);
        assertEquals(TOTAL, num(json, "records_persisted"));
        assertEquals(TOTAL, num(json, "last_allocated_seq"));
        assertEquals(0, num(json, "journal_dropped"));
        assertEquals(0, num(json, "journal_overflows"));
        Path archive;
        try (var files = Files.list(directory)) {
            archive = files.filter(p -> p.toString().endsWith(".gz")).findFirst().orElseThrow();
        }
        try (var gzip = new GZIPInputStream(Files.newInputStream(archive))) {
            var lines = new String(gzip.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            assertEquals(TOTAL, lines.size());
            for (int i = 0; i < lines.size(); i++)
                assertTrue(lines.get(i).contains("\"seq\":" + (i + 1) + ","), lines.get(i));
            assertTrue(lines.get(0).contains("\"control\":\"START\""));
            assertTrue(lines.get(TOTAL - 1).contains("\"control\":\"STOP\""));
        }
    }

    @Test
    void noReceiverAdmissionEndsInExplicitTerminalDeliveryFailureWithCompleteJournal()
            throws Exception {
        Run run =
                assertTimeoutPreemptively(
                        Duration.ofSeconds(90), () -> replay(400_000_000L, null));
        String json = run.summary();
        // The producer is never thrown at: a throw would bypass journal persistence for the event
        // whose sequence number is already allocated and could not be shown to reach Bookmap.
        assertNull(run.producerFailure());
        assertJournalComplete(json);

        // Explicit, machine-readable terminal failure identifying the missing admission.
        assertTrue(json.contains("\"state\":\"INVALID\""), json);
        assertTrue(json.contains("\"invalid\":true"), json);
        assertEquals("RECEIVER_ADMISSION_TIMEOUT", str(json, "failure_code"));
        assertTrue(str(json, "reason").contains("receiver admission failed"), json);
        assertTrue(str(json, "reason").contains("fresh START"), json);
        assertTrue(str(json, "bridge_error").contains("receiver admission failed"), json);
        assertTrue(run.stdout().contains("BRIDGE DELIVERY FAILED"), run.stdout());

        // Truthful delivery accounting: nothing was admitted, sent, or acknowledged.
        assertEquals("none", str(json, "receiver"));
        assertEquals(0, num(json, "events_published"));
        assertEquals(0, num(json, "acknowledged_seq"));
        assertEquals(0, num(json, "queue_depth"));
        assertEquals(0, num(json, "overflows"));
        assertTrue(json.contains("\"delivery_complete\":false"), json);
        assertEquals(1, num(json, "unconfirmed_from_seq"));
        assertEquals(TOTAL, num(json, "unconfirmed_through_seq"));
        assertEquals(TOTAL, num(json, "last_offered_seq"));
        // Every allocated event is acknowledged, discarded or dropped: none is unaccounted for.
        assertEquals(
                TOTAL,
                num(json, "acknowledged_seq")
                        + num(json, "discarded_unacknowledged")
                        + num(json, "dropped_after_invalid"),
                json);
    }

    /** Receiver helper: HELLO 0, then ACK each in-order event until {@code ackThrough} (or STOP). */
    static long receive(int market, long connectDelayMs, long ackThrough, long paceMs) {
        try (var context = new ZContext()) {
            Thread.sleep(connectDelayMs);
            var receiver = context.createSocket(SocketType.DEALER);
            receiver.setLinger(1000); // let the final ACK flush before the context closes
            receiver.setReceiveTimeOut(5000);
            receiver.connect("tcp://127.0.0.1:" + market);
            receiver.send("HELLO fixture 0");
            String welcome = receiver.recvStr();
            assertNotNull(welcome);
            assertTrue(welcome.contains("WELCOME"), welcome);
            var seq = Pattern.compile("\"seq\":(\\d+),");
            long accepted = 0;
            while (accepted < ackThrough) {
                String event = receiver.recvStr();
                assertNotNull(event, "receiver stalled at " + accepted);
                var match = seq.matcher(event);
                assertTrue(match.find(), event);
                assertEquals(++accepted, Long.parseLong(match.group(1)), event);
                if (paceMs > 0) Thread.sleep(paceMs);
                receiver.send("ACK fixture " + accepted);
                if (event.contains("\"control\":\"STOP\"")) break;
            }
            return accepted; // socket closes here: a disconnect that never ACKs the rest
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Every allocated event is acknowledged, discarded or dropped; nothing is unaccounted for. */
    static void assertAccounted(String json) {
        assertEquals(
                TOTAL,
                num(json, "acknowledged_seq")
                        + num(json, "discarded_unacknowledged")
                        + num(json, "dropped_after_invalid"),
                json);
        assertEquals(0, num(json, "queue_depth"));
    }

    @Test
    void delayedHelloWithinDeadlineDeliversEverythingAndReportsSuccess() throws Exception {
        Run run =
                assertTimeoutPreemptively(
                        Duration.ofSeconds(60),
                        () ->
                                replay(
                                        5_000_000_000L,
                                        port -> assertEquals(TOTAL, receive(port[0], 300, TOTAL, 1))));
        String json = run.summary();
        assertNull(run.producerFailure());
        assertJournalComplete(json);
        assertTrue(json.contains("\"delivery_complete\":true"), json);
        assertTrue(json.contains("\"invalid\":false"), json);
        assertEquals("null", String.valueOf(str(json, "failure_code")));
        assertEquals("null", String.valueOf(str(json, "bridge_error")));
        assertEquals(0, num(json, "unconfirmed_from_seq"));
        assertEquals(0, num(json, "unconfirmed_through_seq"));
        assertEquals(0, num(json, "dropped_after_invalid"));
        assertEquals(0, num(json, "discarded_unacknowledged"));
        assertEquals(TOTAL, num(json, "acknowledged_seq"));
        assertEquals(TOTAL, num(json, "last_offered_seq"));
        // Documented counting semantics: events_published counts each EVENT frame sent, controls
        // (START/REALTIME_START/STOP) included, so a no-reconnect delivery equals the journal count.
        assertEquals(TOTAL, num(json, "events_published"));
        assertEquals("fixture", str(json, "receiver"));
        assertFalse(run.stdout().contains("BRIDGE DELIVERY FAILED"), run.stdout());
    }

    @Test
    void malformedOrMissingHelloIsNeverAdmitted() throws Exception {
        Run run =
                assertTimeoutPreemptively(
                        Duration.ofSeconds(60),
                        () ->
                                replay(
                                        400_000_000L,
                                        port -> {
                                            try (var context = new ZContext()) {
                                                var peer = context.createSocket(SocketType.DEALER);
                                                peer.setLinger(0);
                                                peer.connect("tcp://127.0.0.1:" + port[0]);
                                                peer.send("HELLO fixture notanumber");
                                                peer.send("HELLO");
                                                peer.send("ACK fixture x");
                                                peer.send("BOGUS fixture 0");
                                                peer.sendMore("HELLO fixture 0");
                                                peer.send("extra-frame");
                                                Thread.sleep(700);
                                            } catch (InterruptedException e) {
                                                throw new IllegalStateException(e);
                                            }
                                        }));
        String json = run.summary();
        assertNull(run.producerFailure());
        assertJournalComplete(json);
        assertEquals("RECEIVER_ADMISSION_TIMEOUT", str(json, "failure_code"));
        assertEquals("none", str(json, "receiver"));
        assertEquals(0, num(json, "events_published"));
        assertTrue(json.contains("\"delivery_complete\":false"), json);
        assertAccounted(json);
    }

    @Test
    void invalidFirstAckKeepsItsOwnRootCauseAndNeverReportsAdmissionTimeout() throws Exception {
        Run run =
                assertTimeoutPreemptively(
                        Duration.ofSeconds(60),
                        () ->
                                replay(
                                        5_000_000_000L,
                                        port -> {
                                            try (var context = new ZContext()) {
                                                var peer = context.createSocket(SocketType.DEALER);
                                                peer.setLinger(0);
                                                peer.connect("tcp://127.0.0.1:" + port[0]);
                                                peer.send("HELLO fixture 5"); // ack beyond anything sent
                                                Thread.sleep(300);
                                            } catch (InterruptedException e) {
                                                throw new IllegalStateException(e);
                                            }
                                        }));
        String json = run.summary();
        assertNull(run.producerFailure());
        assertJournalComplete(json);
        assertEquals("BRIDGE_INVALID", str(json, "failure_code"));
        assertEquals("invalid receiver acknowledgement", str(json, "reason"));
        assertEquals("invalid receiver acknowledgement", str(json, "bridge_error"));
        assertTrue(json.contains("\"delivery_complete\":false"), json);
        assertTrue(run.producerMillis() < 4_000, "an already-INVALID bridge must not make the producer wait");
        assertAccounted(json);
    }

    @Test
    void disconnectAfterAdmissionKeepsExistingAckBackpressurePolicy() throws Exception {
        for (long ackThrough : new long[] {0, 10}) {
            Path run = Files.createDirectory(directory.resolve("ack-" + ackThrough));
            var scoped = new ReplayNoReceiverTest();
            scoped.directory = run;
            Run result =
                    assertTimeoutPreemptively(
                            Duration.ofSeconds(60),
                            () ->
                                    scoped.replay(
                                            1_500_000_000L, // admission must not race a slow host
                                            port -> receive(port[0], 0, ackThrough, 0)));
            String json = result.summary();
            assertNull(result.producerFailure());
            scoped.assertJournalComplete(json);
            // Admitted receiver: NOT an admission failure, the pre-existing ACK-stall policy.
            assertEquals("RECEIVER_ACK_TIMEOUT", str(json, "failure_code"));
            assertTrue(str(json, "reason").contains("historical receiver backpressure timeout"));
            assertFalse(str(json, "reason").contains("admission failed"), json);
            assertEquals("fixture", str(json, "receiver"));
            assertEquals(ackThrough, num(json, "acknowledged_seq"));
            assertTrue(json.contains("\"delivery_complete\":false"), json);
            assertEquals(ackThrough + 1, num(json, "unconfirmed_from_seq"));
            assertEquals(TOTAL, num(json, "unconfirmed_through_seq"));
            assertTrue(num(json, "events_published") >= ackThrough, json);
            assertAccounted(json);
        }
    }
}
