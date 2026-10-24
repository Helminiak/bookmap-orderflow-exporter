package com.limacharlie.orderflow;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import velox.api.layer1.common.RunModeHelper;
import velox.api.layer1.data.InstrumentInfo;

/** Offline synthetic million-slot reproduction; never connects to a provider or order route. */
public final class ReplayBurstFixture {
  public static void main(String[] args) throws Exception {
    Path directory = Files.createDirectories(Path.of(args[0]));
    RunModeHelper.setRunModeOnce(false, false);
    var release = new CountDownLatch(1);
    var exporter =
        new BookmapOrderflowExporter(
            path -> {
              try {
                if (!release.await(30, TimeUnit.SECONDS))
                  throw new IOException("fixture gate timeout");
              } catch (InterruptedException e) {
                throw new IOException(e);
              }
              return Files.newOutputStream(path);
            });
    var settings = new BookmapOrderflowExporter.Settings();
    settings.outputDirectory = directory.toString();
    exporter.initialize(
        "SYNTH.CME@TEST",
        new InstrumentInfo("SYNTH", "CME", "TEST", .25, 1, "Synthetic", true),
        ExporterTest.api(settings),
        null);
    // START + 999,998 paired MBO events + REALTIME_START fill exactly 1,000,000 slots.
    exporter.onTimestamp(1_000_000_000L);
    long started = System.nanoTime();
    for (int i = 0; i < 999_998; i++) market(exporter, i);
    double historySeconds = (System.nanoTime() - started) / 1e9;
    exporter.onRealtimeStart();
    var entered = new CountDownLatch(1);
    var done = new CountDownLatch(1);
    var failure = new AtomicReference<Throwable>();
    var feeder =
        new Thread(
            () -> {
              entered.countDown();
              try {
                for (int i = 0; i < 916_504; i++) market(exporter, i);
              } catch (Throwable t) {
                failure.set(t);
              } finally {
                done.countDown();
              }
            });
    feeder.setDaemon(true);
    feeder.start();
    boolean waited;
    try {
      if (!entered.await(2, TimeUnit.SECONDS)) throw new AssertionError("feeder not started");
      waited = !done.await(200, TimeUnit.MILLISECONDS);
    } finally {
      release.countDown();
      feeder.join(60_000);
      exporter.stop();
    }
    if (!waited || feeder.isAlive() || failure.get() != null)
      throw new AssertionError("replay did not backpressure and drain losslessly", failure.get());
    System.out.printf(
        "PASS: queue_capacity=1000000 records=1916505 backpressure_observed=true "
            + "history_events_per_second=%.0f (synthetic, not native throughput)%n",
        999_998 / historySeconds);
  }

  private static void market(BookmapOrderflowExporter exporter, int i) {
    if (i % 2 == 0) exporter.send("synthetic-order", true, 20000, 1);
    else exporter.cancel("synthetic-order");
  }
}
