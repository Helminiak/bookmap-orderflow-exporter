package com.limacharlie.orderflow;

import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

import velox.api.layer1.annotations.Layer1ApiVersion;
import velox.api.layer1.annotations.Layer1ApiVersionValue;
import velox.api.layer1.annotations.Layer1SimpleAttachable;
import velox.api.layer1.annotations.Layer1StrategyName;
import velox.api.layer1.data.InstrumentInfo;
import velox.api.layer1.data.TradeInfo;
import velox.api.layer1.simplified.Api;
import velox.api.layer1.simplified.CustomModule;
import velox.api.layer1.simplified.HistoricalModeListener;
import velox.api.layer1.simplified.InitialState;
import velox.api.layer1.simplified.MarketByOrderDepthDataListener;
import velox.api.layer1.simplified.TimeListener;
import velox.api.layer1.simplified.TradeDataListener;

/**
 * Raw Bookmap MBO/trade exporter for the Orderflow project.
 *
 * v0.1 goal: prove that a Bookmap replay (.bmf) and a live Rithmic-backed
 * Bookmap instrument can emit the same normalized raw event schema.
 *
 * This module intentionally performs no trading and no feature engineering.
 */
@Layer1SimpleAttachable
@Layer1StrategyName("Orderflow Raw Exporter v0.1")
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION2)
public class BookmapOrderflowExporter implements
        CustomModule,
        MarketByOrderDepthDataListener,
        TradeDataListener,
        TimeListener,
        HistoricalModeListener {

    private static final String SCHEMA = "bookmap-orderflow-v0.1";
    private static final String POISON = new String("__POISON__");
    private static final int DEFAULT_QUEUE_CAPACITY = 1_000_000;
    private static final DateTimeFormatter FILE_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS").withZone(ZoneOffset.UTC);

    private final Map<String, OrderState> orders = new HashMap<>();
    private final AtomicReference<Throwable> writerError = new AtomicReference<>();

    private String alias;
    private double pips;
    private double multiplier;
    private long marketNs = 0L;
    private long sequence = 0L;
    private long firstMarketNs = Long.MIN_VALUE;
    private long lastMarketNs = Long.MIN_VALUE;
    private volatile boolean realtimePhase = false;
    private volatile boolean stopped = false;

    private long mboAdds = 0L;
    private long mboReplaces = 0L;
    private long mboCancels = 0L;
    private long trades = 0L;
    private long duplicateAdds = 0L;
    private long unknownReplaces = 0L;
    private long unknownCancels = 0L;
    private long timeReversals = 0L;

    private Path outputDir;
    private Path eventFile;
    private Path summaryFile;
    private BlockingQueue<String> queue;
    private Thread writerThread;

    private record OrderState(boolean bid, int priceLevel, int size) {}

    @Override
    public void initialize(String alias, InstrumentInfo info, Api api, InitialState initialState) {
        this.alias = alias;
        this.pips = info.pips;
        this.multiplier = info.multiplier;

        try {
            String configured = System.getenv("ORDERFLOW_EXPORT_DIR");
            if (configured == null || configured.isBlank()) {
                configured = Paths.get(System.getProperty("user.home"), "BookmapOrderflowExports").toString();
            }
            outputDir = Paths.get(configured);
            Files.createDirectories(outputDir);

            String stem = sanitize(alias) + "_" + FILE_TS.format(Instant.now());
            eventFile = outputDir.resolve(stem + ".ndjson.gz");
            summaryFile = outputDir.resolve(stem + ".summary.json");

            int queueCapacity = DEFAULT_QUEUE_CAPACITY;
            String queueEnv = System.getenv("ORDERFLOW_EXPORT_QUEUE");
            if (queueEnv != null && !queueEnv.isBlank()) {
                queueCapacity = Math.max(10_000, Integer.parseInt(queueEnv.trim()));
            }
            queue = new ArrayBlockingQueue<>(queueCapacity);
            startWriter();

            emitControl("START", "initialized");
            System.out.println("[OrderflowExporter] alias=" + alias
                    + " pips=" + pips
                    + " output=" + eventFile);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to initialize Orderflow exporter", e);
        }
    }

    @Override
    public void onTimestamp(long nanoseconds) {
        if (marketNs != 0L && nanoseconds < marketNs) {
            timeReversals++;
            emitControl("TIME_REVERSAL", "from=" + marketNs + ",to=" + nanoseconds);
        }
        marketNs = nanoseconds;
        if (firstMarketNs == Long.MIN_VALUE) {
            firstMarketNs = nanoseconds;
        }
        lastMarketNs = nanoseconds;
    }

    @Override
    public void send(String orderId, boolean isBid, int price, int size) {
        OrderState prior = orders.put(orderId, new OrderState(isBid, price, size));
        if (prior != null) {
            duplicateAdds++;
        }
        mboAdds++;
        emitMbo("MBO_ADD", orderId, isBid ? "BID" : "ASK", price, size, prior != null);
    }

    @Override
    public void replace(String orderId, int price, int size) {
        OrderState prior = orders.get(orderId);
        String side = "UNKNOWN";
        boolean anomaly = false;
        if (prior == null) {
            unknownReplaces++;
            anomaly = true;
        } else {
            side = prior.bid() ? "BID" : "ASK";
            orders.put(orderId, new OrderState(prior.bid(), price, size));
        }
        mboReplaces++;
        emitMbo("MBO_REPLACE", orderId, side, price, size, anomaly);
    }

    @Override
    public void cancel(String orderId) {
        OrderState prior = orders.remove(orderId);
        String side = "UNKNOWN";
        Integer price = null;
        Integer size = null;
        boolean anomaly = false;
        if (prior == null) {
            unknownCancels++;
            anomaly = true;
        } else {
            side = prior.bid() ? "BID" : "ASK";
            price = prior.priceLevel();
            size = prior.size();
        }
        mboCancels++;
        emitMboNullable("MBO_CANCEL", orderId, side, price, size, anomaly);
    }

    @Override
    public void onTrade(double price, int size, TradeInfo tradeInfo) {
        trades++;
        long seq = nextSequence();
        StringBuilder b = new StringBuilder(512);
        b.append('{');
        field(b, "schema", SCHEMA).append(',');
        numberField(b, "seq", seq).append(',');
        numberField(b, "market_ns", marketNs).append(',');
        field(b, "phase", phase()).append(',');
        field(b, "alias", alias).append(',');
        field(b, "event", "TRADE").append(',');
        field(b, "aggressor_side", tradeInfo.isBidAggressor ? "BUY" : "SELL").append(',');
        rawNumberField(b, "price_level", Double.toString(price)).append(',');
        rawNumberField(b, "price", Double.toString(price * pips)).append(',');
        numberField(b, "size", size).append(',');
        nullableStringField(b, "aggressor_order_id", tradeInfo.aggressorOrderId).append(',');
        nullableStringField(b, "passive_order_id", tradeInfo.passiveOrderId).append(',');
        booleanField(b, "execution_start", tradeInfo.isExecutionStart).append(',');
        booleanField(b, "execution_end", tradeInfo.isExecutionEnd).append(',');
        booleanField(b, "otc", tradeInfo.isOtc);
        b.append('}');
        enqueue(b.toString());
    }

    @Override
    public void onRealtimeStart() {
        realtimePhase = true;
        emitControl("REALTIME_START", "Bookmap historical catch-up completed");
    }

    @Override
    public void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        try {
            emitControl("STOP", "module stopped");
            queue.put(POISON);
            writerThread.join(30_000L);
            if (writerThread.isAlive()) {
                throw new IllegalStateException("Writer did not terminate within 30 seconds");
            }
            writeSummary();
            Throwable error = writerError.get();
            if (error != null) {
                throw new IllegalStateException("Writer failed; export is not valid", error);
            }
            System.out.println("[OrderflowExporter] complete: " + summaryFile);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while stopping exporter", e);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to write exporter summary", e);
        }
    }

    private void startWriter() {
        writerThread = new Thread(() -> {
            try (BufferedWriter out = new BufferedWriter(
                    new OutputStreamWriter(
                            new GZIPOutputStream(
                                    new BufferedOutputStream(Files.newOutputStream(eventFile), 1 << 20),
                                    1 << 20),
                            StandardCharsets.UTF_8),
                    1 << 20)) {
                while (true) {
                    String line = queue.take();
                    if (line == POISON) {
                        break;
                    }
                    out.write(line);
                    out.newLine();
                }
                out.flush();
            } catch (Throwable t) {
                writerError.compareAndSet(null, t);
            }
        }, "bookmap-orderflow-writer");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    /**
     * STRICT mode: block the Bookmap callback rather than silently drop an event.
     * That is intentional for v0.1 historical extraction. Live streaming will use
     * a different non-blocking journal/transport layer in a later revision.
     */
    private void enqueue(String line) {
        Throwable error = writerError.get();
        if (error != null) {
            throw new IllegalStateException("Writer has failed; refusing to continue with a partial export", error);
        }
        try {
            queue.put(line);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while writing market event", e);
        }
    }

    private void emitMbo(String event, String orderId, String side, int price, int size, boolean anomaly) {
        emitMboNullable(event, orderId, side, price, size, anomaly);
    }

    private void emitMboNullable(String event, String orderId, String side,
                                 Integer price, Integer size, boolean anomaly) {
        long seq = nextSequence();
        StringBuilder b = new StringBuilder(384);
        b.append('{');
        field(b, "schema", SCHEMA).append(',');
        numberField(b, "seq", seq).append(',');
        numberField(b, "market_ns", marketNs).append(',');
        field(b, "phase", phase()).append(',');
        field(b, "alias", alias).append(',');
        field(b, "event", event).append(',');
        field(b, "order_id", orderId).append(',');
        field(b, "side", side).append(',');
        nullableNumberField(b, "price_level", price).append(',');
        if (price == null) {
            b.append("\"price\":null,");
        } else {
            rawNumberField(b, "price", Double.toString(price * pips)).append(',');
        }
        nullableNumberField(b, "size", size).append(',');
        booleanField(b, "anomaly", anomaly);
        b.append('}');
        enqueue(b.toString());
    }

    private void emitControl(String type, String detail) {
        if (queue == null) {
            return;
        }
        long seq = nextSequence();
        StringBuilder b = new StringBuilder(320);
        b.append('{');
        field(b, "schema", SCHEMA).append(',');
        numberField(b, "seq", seq).append(',');
        numberField(b, "market_ns", marketNs).append(',');
        field(b, "phase", phase()).append(',');
        field(b, "alias", alias == null ? "" : alias).append(',');
        field(b, "event", "CONTROL").append(',');
        field(b, "control", type).append(',');
        field(b, "detail", detail);
        b.append('}');
        enqueue(b.toString());
    }

    private long nextSequence() {
        return ++sequence;
    }

    private String phase() {
        return realtimePhase ? "REALTIME" : "HISTORY";
    }

    private void writeSummary() throws IOException {
        Throwable error = writerError.get();
        boolean valid = error == null;
        StringBuilder b = new StringBuilder(1024);
        b.append('{');
        field(b, "schema", SCHEMA).append(',');
        field(b, "alias", alias).append(',');
        rawNumberField(b, "pips", Double.toString(pips)).append(',');
        rawNumberField(b, "multiplier", Double.toString(multiplier)).append(',');
        field(b, "event_file", eventFile.getFileName().toString()).append(',');
        booleanField(b, "writer_ok", valid).append(',');
        nullableStringField(b, "writer_error", error == null ? null : error.toString()).append(',');
        numberField(b, "total_records", sequence).append(',');
        numberField(b, "mbo_add", mboAdds).append(',');
        numberField(b, "mbo_replace", mboReplaces).append(',');
        numberField(b, "mbo_cancel", mboCancels).append(',');
        numberField(b, "trades", trades).append(',');
        numberField(b, "duplicate_adds", duplicateAdds).append(',');
        numberField(b, "unknown_replaces", unknownReplaces).append(',');
        numberField(b, "unknown_cancels", unknownCancels).append(',');
        numberField(b, "time_reversals", timeReversals).append(',');
        nullableLongField(b, "first_market_ns", firstMarketNs == Long.MIN_VALUE ? null : firstMarketNs).append(',');
        nullableLongField(b, "last_market_ns", lastMarketNs == Long.MIN_VALUE ? null : lastMarketNs).append(',');
        numberField(b, "orders_open_at_stop", orders.size()).append(',');
        field(b, "run_tag", System.getenv().getOrDefault("ORDERFLOW_RUN_TAG", ""));
        b.append('}');
        Files.writeString(summaryFile, b.toString() + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    private static String sanitize(String s) {
        return s == null ? "unknown" : s.replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    private static StringBuilder field(StringBuilder b, String key, String value) {
        return b.append('"').append(escape(key)).append("\":\"")
                .append(escape(value == null ? "" : value)).append('"');
    }

    private static StringBuilder nullableStringField(StringBuilder b, String key, String value) {
        b.append('"').append(escape(key)).append("\":");
        if (value == null) {
            return b.append("null");
        }
        return b.append('"').append(escape(value)).append('"');
    }

    private static StringBuilder numberField(StringBuilder b, String key, long value) {
        return b.append('"').append(escape(key)).append("\":").append(value);
    }

    private static StringBuilder rawNumberField(StringBuilder b, String key, String value) {
        return b.append('"').append(escape(key)).append("\":").append(value);
    }

    private static StringBuilder nullableNumberField(StringBuilder b, String key, Integer value) {
        b.append('"').append(escape(key)).append("\":");
        return value == null ? b.append("null") : b.append(value);
    }

    private static StringBuilder nullableLongField(StringBuilder b, String key, Long value) {
        b.append('"').append(escape(key)).append("\":");
        return value == null ? b.append("null") : b.append(value);
    }

    private static StringBuilder booleanField(StringBuilder b, String key, boolean value) {
        return b.append('"').append(escape(key)).append("\":").append(value);
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
