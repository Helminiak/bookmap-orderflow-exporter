package com.limacharlie.orderflow;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

/** Synthetic transport/stress fixture. No Bookmap connection or real market data. */
public final class BridgeFixture {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]),
                health = Integer.parseInt(args[1]),
                capacity = Integer.parseInt(args[2]);
        int cycles = Integer.parseInt(args[3]), rate = Integer.parseInt(args[4]);
        String mode = args[5];
        Path report = Path.of(args[6]), journal = Path.of(args[7]);
        CallbackMetrics callback = new CallbackMetrics();
        LiveBridge bridge =
                new LiveBridge(
                        "127.0.0.1",
                        port,
                        health,
                        capacity,
                        "SYNTH.CME@TEST",
                        .25,
                        () -> "{\"writer_ok\":true,\"callback_latency\":" + callback.json() + "}");
        // A tiny-queue/no-consumer test deliberately does not wait for a receiver.
        long deadline = System.nanoTime() + 15_000_000_000L;
        if (!mode.equals("overflow")) {
            while (!bridge.status().contains("CONNECTED")
                    && !bridge.invalid()
                    && System.nanoTime() < deadline) Thread.sleep(5);
        }
        List<String> records = new ArrayList<>();
        long seq = 0, ns = 1_000_000_000L, started = System.nanoTime(), next = started;
        for (int c = -1; c <= cycles; c++) {
            int length = c < 0 ? 2 : c == cycles ? 1 : 190;
            for (int i = 0; i < length; i++) {
                long assigned = ++seq;
                String body;
                if (c < 0)
                    body =
                            "\"event\":\"CONTROL\",\"control\":\""
                                    + (i == 0 ? "START" : "REALTIME_START")
                                    + "\",\"detail\":\"synthetic\"";
                else if (c == cycles)
                    body = "\"event\":\"CONTROL\",\"control\":\"STOP\",\"detail\":\"synthetic\"";
                else {
                    int j = i < 100 ? i : i < 120 ? i - 100 : i < 150 ? i - 120 : i - 150;
                    String id = "order-" + c + "-" + j;
                    if (i >= 120 && i < 150)
                        body =
                                "\"event\":\"TRADE\",\"aggressor_side\":\"BUY\",\"price_level\":20000.0,\"price\":5000.0,\"size\":2,\"aggressor_order_id\":\"aggr-"
                                        + c
                                        + "-"
                                        + j
                                        + "\",\"passive_order_id\":\""
                                        + id
                                        + "\",\"execution_start\":true,\"execution_end\":true,\"otc\":false";
                    else {
                        String event = i < 100 ? "MBO_ADD" : i < 120 ? "MBO_REPLACE" : "MBO_CANCEL";
                        int level = 20000 + j + (i >= 100 && j < 20 ? 1 : 0),
                                size = i >= 100 && j < 20 ? 3 : 2;
                        body =
                                "\"event\":\""
                                        + event
                                        + "\",\"order_id\":\""
                                        + id
                                        + "\",\"side\":\""
                                        + (j % 2 == 0 ? "BID" : "ASK")
                                        + "\",\"price_level\":"
                                        + level
                                        + ",\"price\":"
                                        + level * .25
                                        + ",\"size\":"
                                        + size
                                        + ",\"anomaly\":false";
                    }
                }
                String phase = c < 0 && i == 0 ? "HISTORY" : "REALTIME";
                long eventNs = seq == 1 ? 0 : ns++;
                String json =
                        "{\"schema\":\"bookmap-orderflow-v0.1\",\"seq\":"
                                + seq
                                + ",\"market_ns\":"
                                + eventNs
                                + ",\"phase\":\""
                                + phase
                                + "\",\"alias\":\"SYNTH.CME@TEST\","
                                + body
                                + "}";
                records.add(json);
                if (mode.equals("duplicate") && assigned == 50)
                    json = json.replace("\"seq\":50,", "\"seq\":49,");
                if (mode.equals("gap") && assigned == 50) continue;
                final String immutableJson = json;
                long begin = System.nanoTime();
                bridge.offer(new CanonicalEvent(assigned, eventNs, () -> immutableJson));
                callback.record(System.nanoTime() - begin);
                if (rate > 0) {
                    next += 1_000_000_000L / rate;
                    long pause = next - System.nanoTime();
                    if (pause > 0) LockSupport.parkNanos(pause);
                }
            }
        }
        double sourceSeconds = (System.nanoTime() - started) / 1e9;
        deadline =
                System.nanoTime()
                        + (mode.equals("gap") || mode.equals("duplicate")
                                ? 3_000_000_000L
                                : 30_000_000_000L);
        while (bridge.depth() > 0 && !bridge.invalid() && System.nanoTime() < deadline)
            Thread.sleep(5);
        // Allow a health poll to observe terminal/invalid state.
        Thread.sleep(700);
        Files.writeString(journal, String.join("\n", records) + "\n");
        Files.writeString(
                report,
                "{\"source_records\":"
                        + seq
                        + ",\"source_seconds\":"
                        + sourceSeconds
                        + ",\"source_rate\":"
                        + seq / sourceSeconds
                        + ",\"callback\":"
                        + callback.json()
                        + ",\"publisher\":"
                        + bridge.status()
                        + "}\n");
        bridge.close();
    }
}
