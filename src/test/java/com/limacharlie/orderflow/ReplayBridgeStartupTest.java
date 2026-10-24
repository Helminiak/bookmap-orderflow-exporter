package com.limacharlie.orderflow;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.zeromq.*;
import velox.api.layer1.data.InstrumentInfo;

class ReplayBridgeStartupTest {
    @TempDir Path directory;

    @Test
    void delayedReceiverPreservesStartupAndPostTransitionReplayThroughStop() throws Exception {
        try (var mode = ReplayJournalTest.sourceMode(false)) {
            int market = ExporterTest.freePort(), health = ExporterTest.freePort();
            while (health == market) health = ExporterTest.freePort();
            var settings = new BookmapOrderflowExporter.Settings();
            settings.outputDirectory = directory.toString();
            settings.bridgeEnabled = true;
            settings.bridgeBind = "127.0.0.1";
            settings.bridgePort = market;
            settings.bridgeHealthPort = health;
            settings.bridgeQueueCapacity = 4; // force ACK pacing; never increase production capacity
            var exporter = new BookmapOrderflowExporter();
            exporter.initialize("SYNTH.CME@TEST",
                    new InstrumentInfo("SYNTH", "CME", "TEST", .25, 1, "Synthetic", true),
                    ExporterTest.api(settings), null);
            var failure = new AtomicReference<Throwable>();
            var done = new CountDownLatch(1);
            var producer = new Thread(() -> {
                try {
                    exporter.onTimestamp(1_000_000_000L);
                    for (int i = 0; i < 200; i++) exporter.send("history-" + i, true, 20000, 1);
                    exporter.onRealtimeStart();
                    for (int i = 0; i < 200; i++) exporter.send("replay-" + i, true, 20001, 1);
                } catch (Throwable t) { failure.set(t); }
                finally {
                    try { exporter.stop(); } catch (Throwable t) { failure.compareAndSet(null, t); }
                    done.countDown();
                }
            });
            producer.setDaemon(true);
            producer.start();
            boolean waited = !done.await(150, TimeUnit.MILLISECONDS);
            long accepted = 0;
            boolean stop = false;
            try (var context = new ZContext()) {
                var receiver = context.createSocket(SocketType.DEALER);
                receiver.setLinger(0);
                receiver.setReceiveTimeOut(2000);
                receiver.connect("tcp://127.0.0.1:" + market);
                receiver.send("HELLO fixture 0");
                String welcome = receiver.recvStr();
                assertNotNull(welcome);
                assertTrue(welcome.contains("WELCOME"), welcome);
                assertFalse(welcome.contains("\"invalid\":true"), welcome);
                var seq = Pattern.compile("\\\"seq\\\":(\\d+),");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (!stop && System.nanoTime() < deadline) {
                    String event = receiver.recvStr();
                    assertNotNull(event, "receiver stalled at " + accepted);
                    var match = seq.matcher(event);
                    assertTrue(match.find(), event);
                    assertEquals(++accepted, Long.parseLong(match.group(1)), event);
                    stop = event.contains("\"control\":\"STOP\"");
                    // Deliberately paced acknowledgements exercise capacity in BOTH phases.
                    Thread.sleep(2);
                    receiver.send("ACK fixture " + accepted);
                }
                assertTrue(done.await(5, TimeUnit.SECONDS));
            } finally {
                if (producer.isAlive()) { producer.interrupt(); producer.join(12000); }
            }
            assertTrue(waited, "startup must wait for the delayed receiver instead of overflowing");
            assertNull(failure.get());
            assertTrue(stop);
            assertEquals(403, accepted);
            Path summary, archive;
            try (var files = Files.list(directory)) {
                summary = files.filter(p -> p.toString().endsWith(".summary.json")).findFirst().orElseThrow();
            }
            String json = Files.readString(summary);
            for (String expected : new String[] {"\"writer_ok\":true", "\"archive_finalized\":true",
                    "\"records_persisted\":403", "\"last_allocated_seq\":403", "\"journal_dropped\":0",
                    "\"journal_overflows\":0", "\"delivery_complete\":true", "\"overflows\":0",
                    "\"acknowledged_seq\":403"}) assertTrue(json.contains(expected), expected + ": " + json);
            try (var files = Files.list(directory)) {
                archive = files.filter(p -> p.toString().endsWith(".gz")).findFirst().orElseThrow();
            }
            try (var gzip = new GZIPInputStream(Files.newInputStream(archive))) {
                var lines = new String(gzip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines().toList();
                assertEquals(403, lines.size());
                for (int i = 0; i < lines.size(); i++) assertTrue(lines.get(i).contains("\"seq\":" + (i + 1) + ","));
                assertTrue(lines.get(201).contains("REALTIME_START"));
                assertTrue(lines.get(202).contains("\"phase\":\"REALTIME\""));
                assertTrue(lines.get(402).contains("\"control\":\"STOP\""));
            }
        }
    }
}
