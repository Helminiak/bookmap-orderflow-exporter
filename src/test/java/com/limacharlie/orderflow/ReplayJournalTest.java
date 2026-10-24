package com.limacharlie.orderflow;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import velox.api.layer1.common.RunModeHelper;
import velox.api.layer1.data.InstrumentInfo;

class ReplayJournalTest {
  @TempDir Path directory;

  // Bookmap normally sets this once per process. Restore it after each synthetic run so these
  // tests neither depend on test order nor change other fixtures' unknown-source behavior.
  static AutoCloseable sourceMode(Boolean mode) throws Exception {
    var field = RunModeHelper.class.getDeclaredField("isLive");
    field.setAccessible(true);
    Object previous = field.get(null);
    field.set(null, mode);
    return () -> field.set(null, previous);
  }

  BookmapOrderflowExporter initialize(CountDownLatch release) throws Exception {
    var exporter =
        new BookmapOrderflowExporter(
            path -> {
              try {
                if (!release.await(10, TimeUnit.SECONDS))
                  throw new IOException("fixture gate timeout");
              } catch (InterruptedException e) {
                throw new IOException(e);
              }
              return Files.newOutputStream(path);
            });
    var settings = new BookmapOrderflowExporter.Settings();
    settings.outputDirectory = directory.toString();
    settings.queueCapacity = 10_000; // production minimum; no enlarged queue
    exporter.initialize(
        "SYNTH.CME@TEST",
        new InstrumentInfo("SYNTH", "CME", "TEST", .25, 1, "Synthetic", true),
        ExporterTest.api(settings),
        null);
    exporter.onTimestamp(1_000_000_000L);
    // Leave one slot for REALTIME_START. The history backlog is deliberately undrained.
    for (int i = 0; i < 9_998; i++) exporter.send("history-" + i, true, 20000, 1);
    exporter.onRealtimeStart();
    return exporter;
  }

  String summary() throws Exception {
    try (var paths = Files.list(directory)) {
      return Files.readString(
          paths.filter(p -> p.toString().endsWith("summary.json")).findFirst().orElseThrow());
    }
  }

  @Test
  void bookmapReplayBackpressuresBurstAfterRealtimeStartAndFinalizesLosslessly() throws Exception {
    try (var mode = sourceMode(false)) {
      var release = new CountDownLatch(1);
      var exporter = initialize(release);
      var entered = new CountDownLatch(1);
      var done = new CountDownLatch(1);
      var failure = new AtomicReference<Throwable>();
      var feeder =
          new Thread(
              () -> {
                entered.countDown();
                try {
                  for (int i = 0; i < 20_000; i++) exporter.send("replay-" + i, true, 20001, 1);
                } catch (Throwable t) {
                  failure.set(t);
                } finally {
                  done.countDown();
                }
              });
      feeder.setDaemon(true);
      feeder.start();
      boolean waited;
      Throwable stopFailure = null;
      try {
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        waited = !done.await(200, TimeUnit.MILLISECONDS);
      } finally {
        release.countDown();
        feeder.join(10_000);
        try {
          exporter.stop();
        } catch (Throwable t) {
          stopFailure = t;
        }
      }
      assertTrue(waited, "Bookmap replay must wait for journal capacity after REALTIME_START");
      assertFalse(feeder.isAlive());
      assertNull(failure.get());
      assertNull(stopFailure);
      String summary = summary();
      assertTrue(summary.contains("\"writer_ok\":true"));
      assertTrue(summary.contains("\"bookmap_source_mode\":\"BOOKMAP_REPLAY\""));
      assertTrue(summary.contains("\"archive_finalized\":true"));
      assertTrue(summary.contains("\"journal_dropped\":0"));
      assertTrue(summary.contains("\"journal_overflows\":0"));
      assertTrue(summary.contains("\"records_persisted\":30001"));
      Path archive;
      try (var paths = Files.list(directory)) {
        archive = paths.filter(p -> p.toString().endsWith(".gz")).findFirst().orElseThrow();
      }
      try (var gzip = new GZIPInputStream(Files.newInputStream(archive))) {
        var lines =
            new String(gzip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                .lines()
                .toList();
        assertEquals(30_001, lines.size());
        for (int i = 0; i < lines.size(); i++)
          assertTrue(lines.get(i).contains("\"seq\":" + (i + 1) + ","));
        assertTrue(lines.get(9_999).contains("\"control\":\"REALTIME_START\""));
        assertTrue(lines.get(10_000).contains("\"phase\":\"REALTIME\""));
        assertTrue(lines.get(30_000).contains("\"control\":\"STOP\""));
      }
    }
  }

  @Test
  void replayWriterStallAfterRealtimeStartStillFailsClosed() throws Exception {
    try (var mode = sourceMode(false)) {
      var release = new CountDownLatch(1);
      var exporter = initialize(release);
      exporter.writerStallTimeoutSeconds = 1;
      Throwable failure;
      try {
        failure =
            assertThrows(
                IllegalStateException.class, () -> exporter.send("blocked-replay", true, 20001, 1));
      } finally {
        release.countDown();
        assertThrows(IllegalStateException.class, exporter::stop);
      }
      assertTrue(failure.toString().contains("stalled"));
      assertTrue(summary().contains("\"writer_ok\":false"));
      assertTrue(summary().contains("\"archive_finalized\":false"));
    }
  }

  @Test
  void providerAndUnknownSourceNeverWaitOnFullLiveQueue() throws Exception {
    Path root = directory;
    for (Boolean modeValue : new Boolean[] {true, null}) {
      directory = Files.createDirectory(root.resolve(String.valueOf(modeValue)));
      try (var mode = sourceMode(modeValue)) {
        var release = new CountDownLatch(1);
        var exporter = initialize(release);
        try {
          assertTimeoutPreemptively(
              java.time.Duration.ofSeconds(1),
              () -> exporter.send("live-overload", true, 20001, 1));
        } finally {
          release.countDown();
          assertThrows(IllegalStateException.class, exporter::stop);
        }
        assertTrue(summary().contains("Live journal queue overflow; archive INVALID"));
        assertTrue(summary().contains("\"journal_overflows\":1"));
      }
    }
  }
}
