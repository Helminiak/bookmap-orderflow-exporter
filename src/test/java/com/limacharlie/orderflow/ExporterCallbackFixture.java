package com.limacharlie.orderflow;

import velox.api.layer1.data.InstrumentInfo;
import velox.api.layer1.data.TradeInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.locks.LockSupport;

/** Invokes the real exporter callbacks with synthetic input, without a Bookmap application. */
public final class ExporterCallbackFixture {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]),
                health = Integer.parseInt(args[1]),
                capacity = Integer.parseInt(args[2]);
        int cycles = Integer.parseInt(args[3]), rate = Integer.parseInt(args[4]);
        boolean enabled = Boolean.parseBoolean(args[5]);
        Path dir = Path.of(args[6]);
        Files.createDirectories(dir);
        var settings = new BookmapOrderflowExporter.Settings();
        settings.outputDirectory = dir.toString();
        settings.bridgeEnabled = enabled;
        settings.bridgeBind = "127.0.0.1";
        settings.bridgePort = port;
        settings.bridgeHealthPort = health;
        settings.bridgeQueueCapacity = capacity;
        var exporter = new BookmapOrderflowExporter();
        exporter.initialize(
                "SYNTH.CME@TEST",
                new InstrumentInfo("SYNTH", "CME", "TEST", .25, 1, "Synthetic", true),
                ExporterTest.api(settings),
                null);
        // Let the fixture receiver handshake. This sleep is in the fixture, never a market
        // callback.
        if (enabled) Thread.sleep(1000);
        exporter.onTimestamp(1_000_000_000L);
        exporter.onRealtimeStart();
        long next = System.nanoTime();
        for (int c = 0; c < cycles; c++)
            for (int i = 0; i < 190; i++) {
                int j = i < 100 ? i : i < 120 ? i - 100 : i < 150 ? i - 120 : i - 150;
                exporter.onTimestamp(1_000_000_000L + c * 190 + i + 1);
                if (i < 100) exporter.send("order-" + c + "-" + j, j % 2 == 0, 20000 + j, 2);
                else if (i < 120) exporter.replace("order-" + c + "-" + j, 20001 + j, 3);
                else if (i < 150)
                    exporter.onTrade(
                            20000,
                            2,
                            new TradeInfo(
                                    false,
                                    true,
                                    true,
                                    true,
                                    "aggr-" + c + "-" + j,
                                    "order-" + c + "-" + j));
                else exporter.cancel("order-" + c + "-" + j);
                if (rate > 0) {
                    next += 1_000_000_000L / rate;
                    long wait = next - System.nanoTime();
                    if (wait > 0) LockSupport.parkNanos(wait);
                }
            }
        exporter.stop();
        Thread.sleep(1000);
    }
}
