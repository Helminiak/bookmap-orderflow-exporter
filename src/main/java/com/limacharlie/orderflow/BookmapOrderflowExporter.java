package com.limacharlie.orderflow;

import velox.api.layer1.annotations.Layer1ApiVersion;
import velox.api.layer1.annotations.Layer1ApiVersionValue;
import velox.api.layer1.annotations.Layer1SimpleAttachable;
import velox.api.layer1.annotations.Layer1StrategyName;
import velox.api.layer1.common.RunModeHelper;
import velox.api.layer1.data.InstrumentInfo;
import velox.api.layer1.data.TradeInfo;
import velox.api.layer1.settings.StrategySettingsVersion;
import velox.api.layer1.simplified.Api;
import velox.api.layer1.simplified.CustomModule;
import velox.api.layer1.simplified.CustomSettingsPanelProvider;
import velox.api.layer1.simplified.HistoricalModeListener;
import velox.api.layer1.simplified.InitialState;
import velox.api.layer1.simplified.MarketByOrderDepthDataListener;
import velox.api.layer1.simplified.TimeListener;
import velox.api.layer1.simplified.TradeDataListener;
import velox.gui.StrategyPanel;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

/**
 * Raw Bookmap MBO/trade exporter for the Orderflow project.
 *
 * <p>v0.5 fans immutable callback copies to independent journal and optional acknowledged ZeroMQ
 * workers, preserving the v0.1 raw event schema.
 *
 * <p>This module intentionally performs no trading and no feature engineering.
 */
@Layer1SimpleAttachable
@Layer1StrategyName("Orderflow Raw Exporter v0.5c")
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION2)
public class BookmapOrderflowExporter
        implements CustomModule,
                CustomSettingsPanelProvider,
                MarketByOrderDepthDataListener,
                TradeDataListener,
                TimeListener,
                HistoricalModeListener {

    private static final String SCHEMA = "bookmap-orderflow-v0.1";
    private static final CanonicalEvent POISON = new CanonicalEvent(-1, 0, () -> "");
    private static final int DEFAULT_QUEUE_CAPACITY = 1_000_000;
    private static final int MIN_QUEUE_CAPACITY = 10_000;
    private static final int MAX_QUEUE_CAPACITY = 5_000_000;
    // R-02: strict historical enqueue fails explicitly after this much zero-progress time instead
    // of blocking Bookmap's callback thread (and lifecycleLock) forever on a stalled writer.
    private static final int WRITER_STALL_TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_FLUSH_INTERVAL_MS = 60_000;
    private static final int MIN_FLUSH_INTERVAL_MS = 5_000;
    private static final int MAX_FLUSH_INTERVAL_MS = 300_000;
    private static final int IO_BUFFER_BYTES = 4 * 1024 * 1024;
    private static final long STATUS_REFRESH_INTERVAL_NS = 250_000_000L;
    private static final DateTimeFormatter FILE_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS").withZone(ZoneOffset.UTC);

    @StrategySettingsVersion(
            currentVersion = 1,
            compatibleVersions = {})
    public static class Settings {
        public String outputDirectory = "";
        public String runTag = "";
        public int queueCapacity = DEFAULT_QUEUE_CAPACITY;
        public int flushIntervalMs = DEFAULT_FLUSH_INTERVAL_MS;
        // Retained for compatibility with v0.3 saved settings. v0.4 no longer
        // uses an explicit uncompressed-payload flush threshold.
        public int flushThresholdMiB = 4;
        public boolean exportMbo = true;
        public boolean exportTrades = true;
        public boolean bridgeEnabled = false;
        public String bridgeBind = "0.0.0.0";
        public int bridgePort = 5555;
        public int bridgeHealthPort = 5556;
        public int bridgeQueueCapacity = 100_000;
        public boolean responsiveLiveJournal = true;
    }

    @FunctionalInterface
    interface JournalOutputFactory {
        OutputStream open(Path path) throws IOException;

        /**
         * Forces bytes already handed to the operating system into durable storage, when the
         * implementation can. The default no-op keeps test fakes simple; production forces its
         * FileChannel so a crash cannot leave OS-cache-only data behind a renamed archive (R-07).
         */
        default void sync(OutputStream out) throws IOException {}
    }

    private final JournalOutputFactory journalOutputFactory;

    public BookmapOrderflowExporter() {
        this(new JournalOutputFactory() {
            private FileChannel archiveChannel;

            @Override
            public OutputStream open(Path path) throws IOException {
                archiveChannel =
                        FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                return Channels.newOutputStream(archiveChannel);
            }

            @Override
            public void sync(OutputStream out) throws IOException {
                if (archiveChannel != null && archiveChannel.isOpen()) {
                    archiveChannel.force(false);
                }
            }
        });
    }

    // Worker I/O boundary allows deterministic disk-error tests without changing runtime defaults.
    BookmapOrderflowExporter(JournalOutputFactory journalOutputFactory) {
        this.journalOutputFactory = java.util.Objects.requireNonNull(journalOutputFactory);
    }

    private final Map<String, OrderState> orders = new HashMap<>();
    private final AtomicReference<Throwable> writerError = new AtomicReference<>();
    // R-07: true only after the .partial archive was fsynced and atomically renamed to its final
    // name with matching counts. A crash or failed stop leaves the .partial file in place instead
    // of a truncated file wearing the final name.
    private final AtomicBoolean archiveFinalized = new AtomicBoolean();
    private final AtomicBoolean statusUpdateScheduled = new AtomicBoolean();

    private Api api;
    private Settings settings;

    private String alias;
    private double pips;
    private double multiplier;
    private volatile long marketNs = 0L;
    // R-03: written by Bookmap callbacks, read from UI/status and shutdown threads —
    // volatile pins the JMM visibility contract (final-field publication covers events only).
    private volatile long sequence = 0L;
    private volatile long firstMarketNs = Long.MIN_VALUE;
    private volatile long lastMarketNs = Long.MIN_VALUE;
    private volatile boolean realtimePhase = false;
    // Callback phase and data source are independent: Bookmap replay also calls onRealtimeStart.
    // null means unknown; only an explicit false authorizes replay backpressure after that callback.
    private volatile Boolean providerSource;
    private final Object lifecycleLock = new Object();
    private volatile boolean stopped = false;

    private volatile long mboAdds = 0L;
    private volatile long mboReplaces = 0L;
    private volatile long mboCancels = 0L;
    private volatile long trades = 0L;
    private volatile long duplicateAdds = 0L;
    private volatile long unknownReplaces = 0L;
    private volatile long unknownCancels = 0L;
    private volatile long timeReversals = 0L;
    private volatile int openOrderCount = 0;
    private volatile String uiMessage = "Running";
    private volatile long lastStatusRequestNs = 0L;

    private Path outputDir;
    private Path eventFile;
    private Path summaryFile;
    private EventBuffer queue;
    private LiveBridge bridge;
    private volatile String bridgeFailure = "";
    private CallbackMetrics callbackMetrics = new CallbackMetrics();
    private volatile long journalOverflows, journalDropped;
    private volatile int journalHighWater;
    private volatile java.util.function.Supplier<String> lastMboDisplay = () -> "No MBO event";
    private volatile java.util.function.Supplier<String> lastTradeDisplay = () -> "No trade event";
    private Thread writerThread;
    private int effectiveQueueCapacity = DEFAULT_QUEUE_CAPACITY;
    private int effectiveFlushIntervalMs = DEFAULT_FLUSH_INTERVAL_MS;
    // Package-private so regression tests can shorten the bound; production default is 30s of zero progress.
    int writerStallTimeoutSeconds = WRITER_STALL_TIMEOUT_SECONDS;
    private volatile long recordsPersisted = 0L;
    private volatile long approxUnflushedBytes = 0L;
    private volatile long diskBytesWritten = 0L;
    private volatile long diskWriteOps = 0L;
    private volatile long flushCount = 0L;
    private volatile String lastFlushReason = "pending";
    private volatile long lastFlushEpochMs = 0L;

    private volatile JLabel statusLabel;
    private volatile JTabbedPane statusTabs;

    private record OrderState(boolean bid, int priceLevel, int size) {}

    @Override
    public void initialize(String alias, InstrumentInfo info, Api api, InitialState initialState) {
        // R-08: the previous session is released before ANY session field (alias included) is
        // replaced, so a refused reload cannot attribute the previous session's journal/summary
        // to the new alias. resetRunState() returns false when the previous writer could not be
        // proven terminated inside the bound: the rebind is then refused outright — no new
        // session, writer, bridge or queue — instead of silently rebuilding behind it.
        if (!resetRunState()) {
            uiMessage = "Reload refused: previous session writer still running";
            scheduleStatusRefresh(true);
            throw new IllegalStateException(
                    "Orderflow exporter reload refused: previous session writer did not terminate");
        }
        this.api = api;
        this.alias = alias;
        this.pips = info.pips;
        this.multiplier = info.multiplier;
        this.settings = api.getSettings(Settings.class);

        try {
            String configured = trimToEmpty(settings.outputDirectory);
            if (configured.isBlank()) {
                configured = trimToEmpty(System.getenv("ORDERFLOW_EXPORT_DIR"));
            }
            if (configured.isBlank()) {
                configured =
                        Paths.get(System.getProperty("user.home"), "BookmapOrderflowExports")
                                .toString();
            }
            outputDir = Paths.get(configured);
            Files.createDirectories(outputDir);

            String stem = sanitize(alias) + "_" + FILE_TS.format(Instant.now());
            eventFile = outputDir.resolve(stem + ".ndjson.gz");
            summaryFile = outputDir.resolve(stem + ".summary.json");

            effectiveQueueCapacity =
                    clamp(settings.queueCapacity, MIN_QUEUE_CAPACITY, MAX_QUEUE_CAPACITY);
            effectiveFlushIntervalMs =
                    clamp(settings.flushIntervalMs, MIN_FLUSH_INTERVAL_MS, MAX_FLUSH_INTERVAL_MS);
            queue = new EventBuffer(effectiveQueueCapacity);
            startWriter();
            if (settings.bridgeEnabled) {
                try {
                    bridge =
                            new LiveBridge(
                                    settings.bridgeBind,
                                    settings.bridgePort,
                                    settings.bridgeHealthPort,
                                    settings.bridgeQueueCapacity,
                                    alias,
                                    pips,
                                    this::journalHealth);
                    if (!settings.exportMbo || !settings.exportTrades)
                        bridge.invalidate("MBO/trade filters disabled");
                } catch (RuntimeException error) {
                    bridgeFailure = error.toString();
                }
            }

            emitControl("START", "initialized");
            uiMessage = "Export active";
            scheduleStatusRefresh(true);

            System.out.println(
                    "[OrderflowExporter] alias="
                            + alias
                            + " pips="
                            + pips
                            + " queue="
                            + effectiveQueueCapacity
                            + " output="
                            + eventFile);
        } catch (Exception e) {
            uiMessage = "Initialization failed: " + e;
            scheduleStatusRefresh(true);
            throw new IllegalStateException("Unable to initialize Orderflow exporter", e);
        }
    }

    private boolean resetRunState() {
        // R-08: a settings reload re-enters initialize() without stop(), so the previous session's
        // journal writer, EventBuffer, open .partial handles and bridge are still live at the
        // moment the fields below are overwritten. Releasing them here — before the overwrite, and
        // only when a session actually exists (first initialize) — keeps the archives independent:
        // the old journal is drained and finalized with the old counts, its own summary is written,
        // and its writer/queue/file handles exit before the new session can be reached.
        // Returning false means the previous writer is still live: NOTHING below is replaced.
        if (writerThread != null
                && (writerThread.isAlive() || !stopped)
                && !releasePreviousSession()) {
            // Fail closed: the previous writer is still alive inside the bound, so it can still
            // drain the shared queue field and mutate the counters. Replacing those fields would
            // let it consume the new session's records, so the reload is refused instead; the
            // previous session keeps its own failed status and stays reachable.
            return false;
        }
        orders.clear();
        // A settings reload (Apply handler: api.setSettings + api.reload) re-enters initialize()
        // on the live instance, and Bookmap does not run stop() on that path. Nulling the bridge
        // here would discard the previous session's only reference, leaving its ROUTER/REP and
        // publisher thread still bound to the same port pair: a fresh receiver's HELLO is then
        // consumed by the stale listener and the new session cannot bind (Errno 48) and is
        // invalidated behind the exporter's back. Release the previous session before the
        // discard/rebind. close() is idempotent (AtomicBoolean guard), so the stop() teardown
        // path below is unchanged; this wait is a lifecycle reload wait, like stop()'s existing
        // drain/ACK grace, and never runs inside a Bookmap callback.
        LiveBridge previous = bridge;
        bridge = null;
        if (previous != null) previous.close();
        bridgeFailure = "";
        journalOverflows = 0;
        journalDropped = 0;
        journalHighWater = 0;
        // R-08: the flag describes the CURRENT session's archive. The previous session's .partial
        // rename already ran in releasePreviousSession() above, so a reloaded session must not
        // inherit its predecessor's archive_finalized claim.
        archiveFinalized.set(false);
        callbackMetrics = new CallbackMetrics();
        writerError.set(null);
        marketNs = 0L;
        sequence = 0L;
        firstMarketNs = Long.MIN_VALUE;
        lastMarketNs = Long.MIN_VALUE;
        realtimePhase = false;
        providerSource = RunModeHelper.isLive();
        stopped = false;
        mboAdds = 0L;
        mboReplaces = 0L;
        mboCancels = 0L;
        trades = 0L;
        duplicateAdds = 0L;
        unknownReplaces = 0L;
        unknownCancels = 0L;
        timeReversals = 0L;
        openOrderCount = 0;
        recordsPersisted = 0L;
        approxUnflushedBytes = 0L;
        diskBytesWritten = 0L;
        diskWriteOps = 0L;
        flushCount = 0L;
        lastFlushReason = "pending";
        lastFlushEpochMs = 0L;
        lastMboDisplay = () -> "No MBO event received yet";
        lastTradeDisplay = () -> "No trade received yet";
        uiMessage = "Initializing";
        return true;
    }

    @Override
    public void onTimestamp(long nanoseconds) {
        long callbackStart = System.nanoTime();
        try {
            synchronized (lifecycleLock) {
                if (stopped) return;
                if (marketNs != 0L && nanoseconds < marketNs) {
                    timeReversals++;
                    emitControl("TIME_REVERSAL", "from=" + marketNs + ",to=" + nanoseconds);
                }
                marketNs = nanoseconds;
                if (firstMarketNs == Long.MIN_VALUE) {
                    firstMarketNs = nanoseconds;
                }
                lastMarketNs = nanoseconds;
                scheduleStatusRefresh(false);
            }
        } finally {
            callbackMetrics.record(System.nanoTime() - callbackStart);
        }
    }

    @Override
    public void send(String orderId, boolean isBid, int price, int size) {
        long callbackStart = System.nanoTime();
        try {
            synchronized (lifecycleLock) {
                if (stopped) return;
                OrderState prior = orders.put(orderId, new OrderState(isBid, price, size));
                openOrderCount = orders.size();
                if (prior != null) {
                    duplicateAdds++;
                }
                mboAdds++;
                lastMboDisplay =
                        () ->
                                "ADD "
                                        + sideText(isBid)
                                        + " "
                                        + formatPriceLevel(price)
                                        + " x"
                                        + size
                                        + " id="
                                        + abbreviate(orderId);
                if (settings.exportMbo) {
                    emitMbo("MBO_ADD", orderId, isBid ? "BID" : "ASK", price, size, prior != null);
                }
                scheduleStatusRefresh(false);
            }
        } finally {
            callbackMetrics.record(System.nanoTime() - callbackStart);
        }
    }

    @Override
    public void replace(String orderId, int price, int size) {
        long callbackStart = System.nanoTime();
        try {
            synchronized (lifecycleLock) {
                if (stopped) return;
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
                openOrderCount = orders.size();
                mboReplaces++;
                final String displaySide = side;
                final boolean displayAnomaly = anomaly;
                lastMboDisplay =
                        () ->
                                "REPLACE "
                                        + displaySide
                                        + " "
                                        + formatPriceLevel(price)
                                        + " x"
                                        + size
                                        + " id="
                                        + abbreviate(orderId)
                                        + (displayAnomaly ? " [UNKNOWN ORDER]" : "");
                if (settings.exportMbo) {
                    emitMbo("MBO_REPLACE", orderId, side, price, size, anomaly);
                }
                scheduleStatusRefresh(false);
            }
        } finally {
            callbackMetrics.record(System.nanoTime() - callbackStart);
        }
    }

    @Override
    public void cancel(String orderId) {
        long callbackStart = System.nanoTime();
        try {
            synchronized (lifecycleLock) {
                if (stopped) return;
                OrderState prior = orders.remove(orderId);
                openOrderCount = orders.size();
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
                final String displaySide = side;
                final Integer displayPrice = price, displaySize = size;
                final boolean displayAnomaly = anomaly;
                lastMboDisplay =
                        () ->
                                "CANCEL "
                                        + displaySide
                                        + (displayPrice == null
                                                ? ""
                                                : " " + formatPriceLevel(displayPrice))
                                        + (displaySize == null ? "" : " x" + displaySize)
                                        + " id="
                                        + abbreviate(orderId)
                                        + (displayAnomaly ? " [UNKNOWN ORDER]" : "");
                if (settings.exportMbo) {
                    emitMboNullable("MBO_CANCEL", orderId, side, price, size, anomaly);
                }
                scheduleStatusRefresh(false);
            }
        } finally {
            callbackMetrics.record(System.nanoTime() - callbackStart);
        }
    }

    @Override
    public void onTrade(double price, int size, TradeInfo tradeInfo) {
        long callbackStart = System.nanoTime();
        try {
            synchronized (lifecycleLock) {
                if (stopped) return;
                trades++;
                final boolean bidAggressor = tradeInfo.isBidAggressor,
                        executionStart = tradeInfo.isExecutionStart;
                final boolean executionEnd = tradeInfo.isExecutionEnd, otc = tradeInfo.isOtc;
                final String aggressorId = tradeInfo.aggressorOrderId,
                        passiveId = tradeInfo.passiveOrderId;
                lastTradeDisplay =
                        () ->
                                (bidAggressor ? "BUY " : "SELL ")
                                        + formatPriceLevel(price)
                                        + " x"
                                        + size
                                        + " aggr="
                                        + abbreviate(aggressorId)
                                        + " passive="
                                        + abbreviate(passiveId);

                if (settings.exportTrades) {
                    long seq = nextSequence();
                    final long eventNs = marketNs;
                    final String eventPhase = phase(), eventAlias = alias;
                    final double eventPips = pips;
                    enqueue(
                            new CanonicalEvent(
                                    seq,
                                    eventNs,
                                    () -> {
                                        StringBuilder b = new StringBuilder(512);
                                        b.append('{');
                                        field(b, "schema", SCHEMA).append(',');
                                        numberField(b, "seq", seq).append(',');
                                        numberField(b, "market_ns", eventNs).append(',');
                                        field(b, "phase", eventPhase).append(',');
                                        field(b, "alias", eventAlias).append(',');
                                        field(b, "event", "TRADE").append(',');
                                        field(b, "aggressor_side", bidAggressor ? "BUY" : "SELL")
                                                .append(',');
                                        rawNumberField(b, "price_level", Double.toString(price))
                                                .append(',');
                                        rawNumberField(
                                                        b,
                                                        "price",
                                                        Double.toString(price * eventPips))
                                                .append(',');
                                        numberField(b, "size", size).append(',');
                                        nullableStringField(b, "aggressor_order_id", aggressorId)
                                                .append(',');
                                        nullableStringField(b, "passive_order_id", passiveId)
                                                .append(',');
                                        booleanField(b, "execution_start", executionStart)
                                                .append(',');
                                        booleanField(b, "execution_end", executionEnd).append(',');
                                        booleanField(b, "otc", otc);
                                        b.append('}');
                                        return b.toString();
                                    }));
                }
                scheduleStatusRefresh(false);
            }
        } finally {
            callbackMetrics.record(System.nanoTime() - callbackStart);
        }
    }

    @Override
    public void onRealtimeStart() {
        long callbackStart = System.nanoTime();
        try {
            synchronized (lifecycleLock) {
                if (stopped) return;
                if (bridge != null) bridge.finishHistorical();
                realtimePhase = true;
                emitControl("REALTIME_START", "Bookmap historical catch-up completed");
                uiMessage = "Realtime";
                scheduleStatusRefresh(true);
            }
        } finally {
            callbackMetrics.record(System.nanoTime() - callbackStart);
        }
    }

    @Override
    public void stop() {
        boolean owner = false;
        try {
            synchronized (lifecycleLock) {
                if (stopped) return;
                stopped = true;
                owner = true;
                uiMessage = "Stopping";
                scheduleStatusRefresh(true);
                if (writerError.get() == null || realtimePhase)
                    emitControl("STOP", "module stopped");
                // Replay/history may drain bounded ACK backlog, including the reserved STOP.
                // Provider LIVE retains its responsive admission and existing close grace.
                if (bridge != null && (!realtimePhase || Boolean.FALSE.equals(providerSource)))
                    bridge.finishHistorical();
            }
            teardownActiveSession(30L);
            Throwable error = writerError.get();
            if (error != null) {
                throw new IllegalStateException("Writer failed; export is not valid", error);
            }
            uiMessage = "Stopped cleanly";
            scheduleStatusRefresh(true);
            System.out.println("[OrderflowExporter] complete: " + summaryFile);
        } catch (InterruptedException e) {
            writerError.compareAndSet(
                    null, new IllegalStateException("Shutdown interrupted; archive incomplete", e));
            writerThread.interrupt();
            Thread.currentThread().interrupt();
            uiMessage = "Stop interrupted";
            scheduleStatusRefresh(true);
            throw new IllegalStateException("Interrupted while stopping exporter", e);
        } catch (IllegalStateException e) {
            writerError.compareAndSet(null, e);
            writerThread.interrupt();
            uiMessage = "Stop failed; export incomplete";
            scheduleStatusRefresh(true);
            throw e;
        } catch (IOException e) {
            uiMessage = "Summary write failed: " + e;
            scheduleStatusRefresh(true);
            throw new IllegalStateException("Unable to write exporter summary", e);
        } finally {
            if (owner) {
                if (bridge != null) bridge.close();
                if (writerError.get() != null) {
                    try {
                        writeSummary();
                    } catch (IOException summaryFailure) {
                        writerError.get().addSuppressed(summaryFailure);
                    }
                }
            }
        }
    }

    @Override
    public StrategyPanel[] getCustomSettingsPanels() {
        return buildPanels(settings, api, this);
    }

    public static StrategyPanel[] getCustomDisabledSettingsPanels() {
        return buildPanels(new Settings(), null, null);
    }

    private static StrategyPanel[] buildPanels(
            Settings settings, Api api, BookmapOrderflowExporter instance) {
        StrategyPanel panel = new StrategyPanel("Orderflow exporter");
        panel.setLayout(new BorderLayout(4, 4));
        panel.add(buildExporterTabs(settings, api, instance), BorderLayout.CENTER);
        // StrategyPanel.setEnabled delegates into Bookmap's own recursive GUI helpers.
        // Enable our contents directly, including tabs, without depending on those internals.
        for (java.awt.Component child : panel.getComponents())
            setEnabledRecursively(child, api != null);
        return new StrategyPanel[] {panel};
    }

    // Return one Bookmap panel so configuration cannot be hidden behind a second host panel.
    // Plain Swing tab contents also allow geometry checks without the Bookmap host window.
    static JTabbedPane buildExporterTabs(
            Settings settings, Api api, BookmapOrderflowExporter instance) {
        JPanel configPanel = new JPanel(new BorderLayout(4, 4));

        BridgeConfigurationLayout.Fields fields = new BridgeConfigurationLayout.Fields();

        JCheckBox exportMboBox =
                new JCheckBox("Export MBO add / replace / cancel records", settings.exportMbo);
        JCheckBox exportTradesBox = new JCheckBox("Export trade records", settings.exportTrades);
        fields.add(exportMboBox);
        fields.add(exportTradesBox);

        JPanel outputRow = new JPanel(new BorderLayout(4, 0));
        JTextField outputField =
                new JTextField(
                        settings.outputDirectory == null ? "" : settings.outputDirectory, 30);
        JButton browseButton = new JButton("Browse...");
        outputRow.add(new JLabel("Output directory (blank = default): "), BorderLayout.WEST);
        outputRow.add(outputField, BorderLayout.CENTER);
        outputRow.add(browseButton, BorderLayout.EAST);
        fields.add(outputRow);

        JPanel tagRow = new JPanel(new BorderLayout(4, 0));
        JTextField tagField = new JTextField(settings.runTag == null ? "" : settings.runTag, 30);
        tagRow.add(new JLabel("Run tag: "), BorderLayout.WEST);
        tagRow.add(tagField, BorderLayout.CENTER);
        fields.add(tagRow);

        JPanel queueRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JSpinner queueSpinner =
                new JSpinner(
                        new SpinnerNumberModel(
                                clamp(
                                        settings.queueCapacity,
                                        MIN_QUEUE_CAPACITY,
                                        MAX_QUEUE_CAPACITY),
                                MIN_QUEUE_CAPACITY,
                                MAX_QUEUE_CAPACITY,
                                10_000));
        queueRow.add(new JLabel("Writer queue capacity: "));
        queueRow.add(queueSpinner);
        fields.add(queueRow);

        JPanel flushRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JSpinner flushSpinner =
                new JSpinner(
                        new SpinnerNumberModel(
                                clamp(
                                        settings.flushIntervalMs,
                                        MIN_FLUSH_INTERVAL_MS,
                                        MAX_FLUSH_INTERVAL_MS),
                                MIN_FLUSH_INTERVAL_MS,
                                MAX_FLUSH_INTERVAL_MS,
                                500));
        flushRow.add(new JLabel("Maximum checkpoint interval (ms): "));
        flushRow.add(flushSpinner);
        fields.add(flushRow);

        JLabel bufferingNote =
                new JLabel(
                        "<html>Disk buffer: 4 MiB compressed-output buffer; it writes automatically"
                            + " when full. The interval below is only the maximum checkpoint"
                            + " age.</html>");
        fields.add(bufferingNote);

        JCheckBox bridgeBox = new JCheckBox("Live Linux bridge (optional)", settings.bridgeEnabled);
        JCheckBox liveJournalBox =
                new JCheckBox(
                        "Responsive LIVE journal (overflow invalidates archive)",
                        settings.responsiveLiveJournal);
        liveJournalBox.setToolTipText(
                "Bookmap file replay always waits for journal capacity, including after REALTIME_START.");
        JTextField bindField = new JTextField(settings.bridgeBind, 15);
        JSpinner portSpinner =
                new JSpinner(new SpinnerNumberModel(settings.bridgePort, 1, 65535, 1));
        JSpinner healthSpinner =
                new JSpinner(new SpinnerNumberModel(settings.bridgeHealthPort, 1, 65535, 1));
        JSpinner bridgeQueueSpinner =
                new JSpinner(
                        new SpinnerNumberModel(settings.bridgeQueueCapacity, 1, 5_000_000, 1000));
        fields.add(bridgeBox);
        fields.add(liveJournalBox);
        JPanel bridgeRow = new BridgeConfigurationLayout.NetworkRow();
        bridgeRow.add(new JLabel("Bind:"));
        bridgeRow.add(bindField);
        bridgeRow.add(new JLabel("Market port:"));
        bridgeRow.add(portSpinner);
        bridgeRow.add(new JLabel("Health port:"));
        bridgeRow.add(healthSpinner);
        fields.add(bridgeRow);
        JPanel bridgeQueueRow = new BridgeConfigurationLayout.NetworkRow();
        bridgeQueueRow.add(
                new BridgeConfigurationLayout.NetworkLabel(
                        "Unacknowledged bridge event capacity:"));
        bridgeQueueRow.add(bridgeQueueSpinner);
        fields.add(bridgeQueueRow);

        JLabel applyNote =
                new JLabel(
                        "<html>Apply restarts this exporter instance and starts a new output"
                            + " file.</html>");
        fields.add(applyNote);

        JButton applyButton = new JButton("Apply settings / restart exporter");
        // Bookmap already scrolls its plugin host. Keep the complete form, including
        // Apply, in that page instead of creating a smaller nested settings viewport.
        configPanel.add(fields, BorderLayout.CENTER);
        configPanel.add(applyButton, BorderLayout.SOUTH);

        browseButton.addActionListener(
                e -> {
                    JFileChooser chooser = new JFileChooser();
                    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                    String current = outputField.getText().trim();
                    if (!current.isEmpty()) {
                        chooser.setCurrentDirectory(Paths.get(current).toFile());
                    }
                    if (chooser.showOpenDialog(configPanel) == JFileChooser.APPROVE_OPTION) {
                        outputField.setText(chooser.getSelectedFile().getAbsolutePath());
                    }
                });

        applyButton.addActionListener(
                e -> {
                    if (api == null) {
                        return;
                    }
                    settings.exportMbo = exportMboBox.isSelected();
                    settings.exportTrades = exportTradesBox.isSelected();
                    settings.outputDirectory = outputField.getText().trim();
                    settings.runTag = tagField.getText().trim();
                    settings.queueCapacity = ((Number) queueSpinner.getValue()).intValue();
                    settings.flushIntervalMs = ((Number) flushSpinner.getValue()).intValue();
                    settings.bridgeEnabled = bridgeBox.isSelected();
                    settings.responsiveLiveJournal = liveJournalBox.isSelected();
                    settings.bridgeBind = bindField.getText().trim();
                    settings.bridgePort = ((Number) portSpinner.getValue()).intValue();
                    settings.bridgeHealthPort = ((Number) healthSpinner.getValue()).intValue();
                    settings.bridgeQueueCapacity =
                            ((Number) bridgeQueueSpinner.getValue()).intValue();
                    api.setSettings(settings);
                    api.reload();
                });

        JPanel statusPanel = new JPanel(new BorderLayout(4, 4));
        JLabel status = new JLabel();
        status.setVerticalAlignment(SwingConstants.TOP);
        JScrollPane statusScroll = new JScrollPane(status);
        statusScroll.setBorder(null);
        statusScroll.setMinimumSize(new java.awt.Dimension(0, 0));
        // Growing HTML diagnostics must scroll rather than enlarge or hide the configuration.
        statusScroll.getViewport().setPreferredSize(
                fields.getPreferredScrollableViewportSize());
        statusScroll.getVerticalScrollBar().setUnitIncrement(20);
        statusPanel.add(statusScroll, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton refreshButton = new JButton("Refresh");
        JButton openFolderButton = new JButton("Open export folder");
        buttons.add(refreshButton);
        buttons.add(openFolderButton);
        statusPanel.add(buttons, BorderLayout.SOUTH);

        if (instance != null) {
            instance.statusLabel = status;
            refreshButton.addActionListener(e -> instance.refreshStatusLabel());
            openFolderButton.addActionListener(e -> instance.openOutputFolder());
        } else {
            status.setText(
                    "<html>Enable the exporter for an instrument to see live status.</html>");
        }

        JTabbedPane tabs = new JTabbedPane() {
            @Override
            public java.awt.Dimension getMinimumSize() {
                // Preserve the complete page height in Bookmap's horizontal-only host.
                // Its outer scrollbar can then reach every row and Apply at the bottom.
                return new java.awt.Dimension(0, getPreferredSize().height);
            }
        };
        tabs.addTab("Configuration", configPanel);
        tabs.addTab("Live status", statusPanel);
        tabs.addTab("Information", buildInformationPanel());
        tabs.setSelectedIndex(0);
        if (instance != null) {
            instance.statusTabs = tabs;
            tabs.addChangeListener(e -> instance.refreshStatusLabel());
        }
        return tabs;
    }

    private static JPanel buildInformationPanel() {
        JPanel information = new JPanel(new BorderLayout());
        JPanel content = new JPanel(new BorderLayout(4, 8));
        JLabel title = new JLabel("<html>Orderflow Raw Exporter<br>v0.5c — preview build</html>");
        title.putClientProperty("orderflow.navigation", true);
        content.add(title, BorderLayout.NORTH);
        javax.swing.JTextArea description = new javax.swing.JTextArea(
                "Exports Bookmap MBO and trade callbacks to compressed event archives. "
                + "The optional Live Linux bridge sends the same events to your receiver.\n\n"
                + "Configuration: choose export settings and use Apply at the bottom of the page. "
                + "Apply restarts the exporter and starts a new archive.\n\n"
                + "Live status: view archive and bridge health, refresh diagnostics, or open the export folder.\n\n"
                + "When disabling, allow a short pause while the archive finishes and bridge closes. "
                + "A pause alone does not indicate a failure.");
        description.putClientProperty("orderflow.navigation", true);
        description.setEditable(false);
        description.setLineWrap(true);
        description.setWrapStyleWord(true);
        description.setOpaque(false);
        description.setFont(title.getFont());
        description.setRows(15);
        content.add(description, BorderLayout.CENTER);
        JPanel links = new BridgeConfigurationLayout.NetworkRow();
        addInformationLink(links, "Installation / help",
                "https://github.com/Helminiak/bookmap-orderflow-exporter/blob/feature/linux-live-bridge/docs/LINUX_BRIDGE.md");
        addInformationLink(links, "Report an issue",
                "https://github.com/Helminiak/bookmap-orderflow-exporter/issues");
        content.add(links, BorderLayout.SOUTH);
        information.add(content, BorderLayout.NORTH);
        return information;
    }

    private static void addInformationLink(JPanel links, String label, String url) {
        JButton button = new JButton(label);
        button.putClientProperty("orderflow.navigation", true);
        button.addActionListener(e -> {
            try {
                Desktop.getDesktop().browse(java.net.URI.create(url));
            } catch (Exception failure) {
                javax.swing.JOptionPane.showMessageDialog(links,
                        "Open this address in your browser:\n" + url, label,
                        javax.swing.JOptionPane.INFORMATION_MESSAGE);
            }
        });
        links.add(button);
    }

    private static void setEnabledRecursively(java.awt.Component component, boolean enabled) {
        // Navigation remains usable while the addon is disabled; editable settings do not.
        boolean navigation = component instanceof javax.swing.JComponent c
                        && Boolean.TRUE.equals(c.getClientProperty("orderflow.navigation"))
                || component instanceof JTabbedPane
                || component instanceof JScrollPane
                || component instanceof javax.swing.JScrollBar;
        component.setEnabled(navigation || enabled);
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                setEnabledRecursively(child, enabled);
            }
        }
    }

    private void openOutputFolder() {
        try {
            if (outputDir == null) {
                uiMessage = "Output directory is not initialized";
            } else if (!Desktop.isDesktopSupported()) {
                uiMessage = "Desktop folder open is not supported on this system";
            } else {
                Desktop.getDesktop().open(outputDir.toFile());
                uiMessage = "Opened export folder";
            }
        } catch (Exception e) {
            uiMessage = "Unable to open export folder: " + e.getMessage();
        }
        refreshStatusLabel();
    }

    private void scheduleStatusRefresh(boolean force) {
        if (statusLabel == null) {
            return;
        }
        long now = System.nanoTime();
        if (!force && now - lastStatusRequestNs < STATUS_REFRESH_INTERVAL_NS) {
            return;
        }
        lastStatusRequestNs = now;
        if (statusUpdateScheduled.compareAndSet(false, true)) {
            SwingUtilities.invokeLater(
                    () -> {
                        try {
                            refreshStatusLabel();
                        } finally {
                            statusUpdateScheduled.set(false);
                        }
                    });
        }
    }

    private void refreshStatusLabel() {
        JLabel label = statusLabel;
        if (label == null) {
            return;
        }

        Runnable update =
                () -> {
                    // Hidden HTML changes can invalidate Bookmap's outer settings layout and
                    // disturb scrolling/focus. Refresh on selecting Live status instead.
                    JTabbedPane tabs = statusTabs;
                    if (tabs == null || tabs.getSelectedIndex() != 1) return;
                    Throwable error = writerError.get();
                    int queueSize = queue == null ? 0 : queue.size();
                    int queueCapacity =
                            effectiveQueueCapacity <= 0
                                    ? DEFAULT_QUEUE_CAPACITY
                                    : effectiveQueueCapacity;
                    double fill = queueCapacity == 0 ? 0.0 : (100.0 * queueSize / queueCapacity);

                    StringBuilder b = new StringBuilder(1600);
                    b.append("<html>");
                    b.append("<b>Instrument:</b> ").append(html(alias)).append("<br/>");
                    b.append("<b>Mode:</b> ").append(phase()).append("<br/>");
                    b.append("<b>Source:</b> ").append(sourceMode()).append("<br/>");
                    b.append("<b>Status:</b> ")
                            .append(error == null ? html(uiMessage) : "WRITER ERROR")
                            .append("<br/>");
                    b.append("<b>Market/replay time:</b> ")
                            .append(html(formatMarketTime(marketNs)))
                            .append("<br/>");
                    b.append("<b>Output file:</b> ")
                            .append(
                                    html(
                                            eventFile == null
                                                    ? "not initialized"
                                                    : eventFile.getFileName().toString()))
                            .append("<br/>");
                    b.append("<b>On-disk size:</b> ")
                            .append(formatFileSize(currentFileSize()))
                            .append("<br/>");
                    b.append("<b>Persisted records:</b> ").append(recordsPersisted).append("<br/>");
                    b.append("<b>Approx payload since checkpoint:</b> ")
                            .append(formatFileSize(approxUnflushedBytes))
                            .append("<br/>");
                    b.append("<b>Application writes to OS:</b> ")
                            .append(formatFileSize(diskBytesWritten))
                            .append(" &nbsp; write ops=")
                            .append(diskWriteOps)
                            .append("<br/>");
                    b.append("<b>Last checkpoint:</b> ")
                            .append(formatFlushAge())
                            .append(" (")
                            .append(html(lastFlushReason))
                            .append(")")
                            .append(" &nbsp; count=")
                            .append(flushCount)
                            .append("<br/>");
                    b.append("<b>Queue:</b> ")
                            .append(queueSize)
                            .append(" / ")
                            .append(queueCapacity)
                            .append(String.format(" (%.2f%%)", fill))
                            .append("<br/><br/>");

                    b.append("<b>Bridge:</b> ")
                            .append(
                                    bridge == null
                                            ? (bridgeFailure.isEmpty()
                                                    ? "DISABLED"
                                                    : "FAILED: " + html(bridgeFailure))
                                            : html(bridge.display()))
                            .append("<br/>");
                    b.append("<b>Callback latency (log2 upper quantiles):</b> ")
                            .append(html(callbackMetrics.display()))
                            .append("<br/>");
                    b.append("<b>Journal high water / overflows:</b> ")
                            .append(journalHighWater)
                            .append(" / ")
                            .append(journalOverflows)
                            .append("<br/>");
                    b.append("<b>Received events</b><br/>");
                    b.append("MBO add: ").append(mboAdds).append("<br/>");
                    b.append("MBO replace: ").append(mboReplaces).append("<br/>");
                    b.append("MBO cancel: ").append(mboCancels).append("<br/>");
                    b.append("Trade callbacks: ").append(trades).append("<br/>");
                    b.append("Open MBO orders tracked: ").append(openOrderCount).append("<br/>");
                    b.append("Callbacks enqueued: ").append(sequence).append("<br/><br/>");

                    b.append("<b>Integrity counters</b><br/>");
                    b.append("Duplicate adds: ")
                            .append(duplicateAdds)
                            .append(" &nbsp; unknown replaces: ")
                            .append(unknownReplaces)
                            .append(" &nbsp; unknown cancels: ")
                            .append(unknownCancels)
                            .append(" &nbsp; time reversals: ")
                            .append(timeReversals)
                            .append("<br/><br/>");

                    b.append("<b>Last MBO:</b> ")
                            .append(html(lastMboDisplay.get()))
                            .append("<br/>");
                    b.append("<b>Last trade:</b> ")
                            .append(html(lastTradeDisplay.get()))
                            .append("<br/><br/>");

                    b.append("<b>Active configuration:</b> MBO=")
                            .append(settings != null && settings.exportMbo)
                            .append(", Trades=")
                            .append(settings != null && settings.exportTrades)
                            .append(", Checkpoint<=")
                            .append(effectiveFlushIntervalMs)
                            .append(" ms, Disk buffer=")
                            .append(IO_BUFFER_BYTES / (1024 * 1024))
                            .append(" MiB, Run tag=")
                            .append(html(effectiveRunTag()));
                    if (error != null) {
                        b.append("<br/><b>Writer error:</b> ").append(html(error.toString()));
                    }
                    b.append("</html>");
                    label.setText(b.toString());
                };

        if (SwingUtilities.isEventDispatchThread()) {
            update.run();
        } else {
            SwingUtilities.invokeLater(update);
        }
    }

    // R-08: paired release of the previous session on the settings-reload path. Runs the SAME
    // normal session finalization stop() runs — the terminal STOP control frame first, then the
    // bounded journal drain, writer exit, R-07 finalization and that session's summary — so the
    // previous archive keeps its START..REALTIME_START..STOP lifecycle, its own counters and its
    // gzip trailer, instead of a journal that merely happens to match counts. The old bridge
    // takes the STOP through its reserved terminal slot (offerTerminal never waits); when its
    // unACKed backlog cannot absorb it the bridge fails closed separately and that old session
    // stays explicitly failed/undelivered, never reported as a valid archive.
    // finishHistorical() is deliberately NOT reused here: it waits up to 10s for receiver ACK
    // capacity, while initialize() is Bookmap's callback path. An undrained old backlog must
    // remain FAILED, must not become a network wait inside the Apply handler.
    // Returns true only when the previous writer was PROVEN terminated inside the bound; false
    // tells the caller to refuse the rebind rather than proceed behind a live writer.
    private boolean releasePreviousSession() {
        Throwable fault = null;
        try {
            synchronized (lifecycleLock) {
                if (!stopped) {
                    stopped = true;
                    if (writerError.get() == null || realtimePhase)
                        emitControl("STOP", "module stopped");
                }
            }
        } catch (IllegalStateException terminalRefused) {
            // The journal refuses the terminal frame (strict enqueue already failed): that is the
            // previous session's own fault and must not be normalized away.
            fault = terminalRefused;
        }
        if (fault == null) {
            try {
                teardownActiveSession(writerStallTimeoutSeconds);
            } catch (InterruptedException interruption) {
                fault =
                        new IllegalStateException("Previous session teardown interrupted", interruption);
            } catch (IOException | IllegalStateException teardownFault) {
                fault = teardownFault;
            }
        }
        if (fault != null) writerError.compareAndSet(null, fault);
        if (writerThread != null && writerThread.isAlive()) {
            // The bound expired with the previous writer still live (interrupt-resistant or hung
            // journal I/O). Its POISON request and this interrupt are asks, not proofs, so the
            // shared fields stay untouched: a writer that can still drain the old EventBuffer
            // must never sit behind a new session. Record the previous session's failed status
            // with its own counts, then refuse — and do not block the Apply handler any longer.
            writerThread.interrupt();
            // R-08: the writer is the half that cannot be proven terminated, so the fields stay
            // untouched; the OLD bridge can and must be released independently of it. close() is
            // bounded (1s ACK grace + 1.5s worker join), idempotent, and fails closed: with an
            // unACKed backlog it invalidates as "shutdown with unacknowledged events", accounts
            // every retained record in discarded_unacknowledged, publishes nothing further and
            // releases both ports, so a later reload cannot bind behind a stale listener. Failing
            // the bridge closed is not the same as reporting the session healthy: its journal
            // fault, incomplete .partial archive and failed summary stay as recorded below.
            if (bridge != null) bridge.close();
            try {
                writeSummary();
            } catch (IOException summaryFailure) {
                uiMessage = "Previous session summary write failed: " + summaryFailure;
            }
            return false;
        }
        return true;
    }

    /**
     * R-08: bounded shutdown of the ACTIVE session's journal writer and bridge. stop() runs it
     * after the STOP frame; the settings-reload re-entry in initialize() runs it BEFORE the
     * session fields are overwritten, which is the only place the previous session can still be
     * reached. Drain the journal (pending records ahead of POISON are persisted in sequence
     * order), wait for the writer to exit so its streams and file handles close and the R-07
     * finalization runs, fail closed on a count mismatch, release the bridge, then write that
     * session's summary. A writer that will not drain inside the bound is interrupted so it
     * cannot keep consuming records after the fields are reset; the fault propagates to the
     * caller, which records it — stop() fails the export with its existing semantics, the reload
     * path keeps the previous session's own failure status and still starts a clean new session.
     */
    private void teardownActiveSession(long boundSeconds)
            throws InterruptedException, IOException, IllegalStateException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(boundSeconds);
        try {
            while (writerThread.isAlive() && !queue.offer(POISON)) {
                if (System.nanoTime() > deadline)
                    throw new IllegalStateException("Writer stop queue timeout");
                Thread.sleep(1);
            }
            writerThread.join(boundSeconds * 1_000L);
            if (writerThread.isAlive()) {
                writerThread.interrupt();
                writerThread.join(boundSeconds * 1_000L);
                if (writerThread.isAlive())
                    throw new IllegalStateException(
                            "Writer did not terminate within " + boundSeconds + " seconds");
            }
            if (recordsPersisted != sequence) {
                writerError.compareAndSet(
                        null,
                        new IllegalStateException(
                                "Journal count mismatch: allocated="
                                        + sequence
                                        + ", persisted="
                                        + recordsPersisted));
            }
            if (bridge != null) bridge.close();
            writeSummary();
        } catch (InterruptedException interruption) {
            writerThread.interrupt();
            Thread.currentThread().interrupt();
            throw interruption;
        }
    }

    private void startWriter() {
        writerThread =
                new Thread(
                        () -> {
                            long checkpointIntervalNs =
                                    TimeUnit.MILLISECONDS.toNanos(effectiveFlushIntervalMs);
                            long nextCheckpointNs = System.nanoTime() + checkpointIntervalNs;

                            // R-07: stage as .partial; only a clean, count-matched close renames it
                            // to the final archive name. A crash leaves an explicit incomplete file
                            // instead of a truncated gzip wearing the final name.
                            Path partialFile =
                                    eventFile.resolveSibling(
                                            eventFile.getFileName().toString() + ".partial");
                            try (CountingOutputStream countedFile =
                                            new CountingOutputStream(
                                                    journalOutputFactory.open(partialFile));
                                    BufferedOutputStream bufferedFile =
                                            new BufferedOutputStream(countedFile, IO_BUFFER_BYTES);
                                    GZIPOutputStream gzip =
                                            new GZIPOutputStream(
                                                    bufferedFile, IO_BUFFER_BYTES, true);
                                    OutputStreamWriter encoded =
                                            new OutputStreamWriter(gzip, StandardCharsets.UTF_8);
                                    BufferedWriter out =
                                            new BufferedWriter(encoded, IO_BUFFER_BYTES)) {

                                while (true) {
                                    long waitNs =
                                            Math.max(1L, nextCheckpointNs - System.nanoTime());
                                    CanonicalEvent event = queue.poll(waitNs, TimeUnit.NANOSECONDS);
                                    if (event == POISON) {
                                        break;
                                    }
                                    if (event != null) {
                                        if (event.seq != recordsPersisted + 1) {
                                            throw new IOException(
                                                    "Journal sequence discontinuity: expected="
                                                            + (recordsPersisted + 1)
                                                            + ", received="
                                                            + event.seq);
                                        }
                                        String line = event.json();
                                        out.write(line);
                                        out.newLine();
                                        recordsPersisted++;

                                        // Diagnostic only. It is deliberately not a flush trigger
                                        // in v0.4.
                                        // The actual compressed-output buffer drains automatically
                                        // when full.
                                        approxUnflushedBytes += line.length() + 1L;
                                    }

                                    long now = System.nanoTime();
                                    if (now >= nextCheckpointNs) {
                                        // This is a soft application checkpoint, not fsync(). The 4
                                        // MiB
                                        // BufferedOutputStream is free to write earlier whenever it
                                        // fills.
                                        out.flush();
                                        flushCount++;
                                        lastFlushReason = "checkpoint";
                                        approxUnflushedBytes = 0L;
                                        lastFlushEpochMs = System.currentTimeMillis();
                                        scheduleStatusRefresh(true);
                                        nextCheckpointNs = now + checkpointIntervalNs;
                                    }
                                }

                                out.flush();
                                flushCount++;
                                lastFlushReason = "shutdown";
                                approxUnflushedBytes = 0L;
                                lastFlushEpochMs = System.currentTimeMillis();
                                scheduleStatusRefresh(true);
                                // R-07: force durable storage before the streams close, so a crash
                                // after rename cannot lose data that was only in OS cache.
                                journalOutputFactory.sync(countedFile);
                            } catch (Throwable t) {
                                writerError.compareAndSet(null, t);
                                uiMessage = "Writer failed";
                                scheduleStatusRefresh(true);
                            }
                            // R-07: finalize only when the write completed cleanly and every
                            // allocated sequence was persisted; otherwise keep .partial as evidence.
                            if (writerError.get() == null && recordsPersisted == sequence) {
                                try {
                                    try {
                                        Files.move(
                                                partialFile,
                                                eventFile,
                                                StandardCopyOption.ATOMIC_MOVE);
                                    } catch (AtomicMoveNotSupportedException e) {
                                        Files.move(
                                                partialFile,
                                                eventFile,
                                                StandardCopyOption.REPLACE_EXISTING);
                                    }
                                    archiveFinalized.set(true);
                                } catch (IOException moveFailure) {
                                    writerError.compareAndSet(
                                            null,
                                            new IllegalStateException(
                                                    "Archive finalization failed; partial file retained: "
                                                            + partialFile.getFileName(),
                                                    moveFailure));
                                }
                            }
                        },
                        "bookmap-orderflow-writer");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    /**
     * Counts bytes and write calls that this process hands to the operating system below the 4 MiB
     * BufferedOutputStream. This is not a measurement of physical NAND writes; Windows and the SSD
     * controller can still cache, combine, and reorder writes.
     */
    private final class CountingOutputStream extends OutputStream {
        private final OutputStream delegate;

        private CountingOutputStream(OutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
            diskBytesWritten++;
            diskWriteOps++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            diskBytesWritten += len;
            diskWriteOps++;
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    /** History and Bookmap file replay wait for capacity; responsive provider LIVE fails closed. */
    private void enqueue(CanonicalEvent event) {
        if (bridge != null) {
            // Reuse bounded startup pacing; explicit file replay also needs it after
            // REALTIME_START. Provider/unknown REALTIME callbacks remain non-waiting.
            if (stopped) bridge.offerTerminal(event);
            else if (!realtimePhase || Boolean.FALSE.equals(providerSource))
                bridge.offerHistorical(event);
            else bridge.offer(event);
        }
        boolean responsive = realtimePhase && settings.responsiveLiveJournal
                && !Boolean.FALSE.equals(providerSource);
        Throwable error = writerError.get();
        if (error != null) {
            if (!responsive)
                throw new IllegalStateException("Writer failed; partial export", error);
            journalDropped++;
            return;
        }
        if (responsive) {
            if (!queue.offer(event)) {
                journalOverflows++;
                journalDropped++;
                writerError.compareAndSet(
                        null,
                        new IllegalStateException("Live journal queue overflow; archive INVALID"));
            }
        } else {
            try {
                // R-02: bound the historical wait. A writer that makes no journal progress for the
                // stall window (hung disk, dead thread) fails the export explicitly instead of
                // blocking Bookmap's callback thread — and lifecycleLock — forever. The deadline
                // slides with every persisted record, so a slow but progressing disk never trips it.
                long lastProgress = recordsPersisted;
                long stallDeadlineNs =
                        System.nanoTime() + TimeUnit.SECONDS.toNanos(writerStallTimeoutSeconds);
                while (!queue.offer(event)) {
                    if (writerError.get() != null)
                        throw new IllegalStateException("Writer failed", writerError.get());
                    if (Thread.interrupted()) throw new InterruptedException();
                    long progress = recordsPersisted;
                    if (progress != lastProgress) {
                        lastProgress = progress;
                        stallDeadlineNs =
                                System.nanoTime() + TimeUnit.SECONDS.toNanos(writerStallTimeoutSeconds);
                    } else if (System.nanoTime() >= stallDeadlineNs) {
                        var stall =
                                new IllegalStateException(
                                        "Writer stalled: no journal progress for "
                                                + writerStallTimeoutSeconds
                                                + "s; export aborted");
                        writerError.compareAndSet(null, stall);
                        throw new IllegalStateException("Writer stalled", stall);
                    }
                    java.util.concurrent.locks.LockSupport.parkNanos(100_000L);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        journalHighWater = Math.max(journalHighWater, queue.size());
    }

    private String journalHealth() {
        return "{\"writer_ok\":"
                + (writerError.get() == null)
                + ",\"current_seq\":"
                + sequence
                + ",\"market_ns\":"
                + marketNs
                + ",\"queue_depth\":"
                + (queue == null ? 0 : queue.size())
                + ",\"queue_high_water\":"
                + journalHighWater
                + ",\"overflows\":"
                + journalOverflows
                + ",\"records_persisted\":"
                + recordsPersisted
                + ",\"callback_latency\":"
                + callbackMetrics.json()
                + "}";
    }

    private void emitMbo(
            String event, String orderId, String side, int price, int size, boolean anomaly) {
        emitMboNullable(event, orderId, side, price, size, anomaly);
    }

    private void emitMboNullable(
            String event,
            String orderId,
            String side,
            Integer price,
            Integer size,
            boolean anomaly) {
        long seq = nextSequence();
        final long eventNs = marketNs;
        final String eventPhase = phase(), eventAlias = alias;
        final double eventPips = pips;
        enqueue(
                new CanonicalEvent(
                        seq,
                        eventNs,
                        () -> {
                            StringBuilder b = new StringBuilder(384);
                            b.append('{');
                            field(b, "schema", SCHEMA).append(',');
                            numberField(b, "seq", seq).append(',');
                            numberField(b, "market_ns", eventNs).append(',');
                            field(b, "phase", eventPhase).append(',');
                            field(b, "alias", eventAlias).append(',');
                            field(b, "event", event).append(',');
                            field(b, "order_id", orderId).append(',');
                            field(b, "side", side).append(',');
                            nullableNumberField(b, "price_level", price).append(',');
                            if (price == null) {
                                b.append("\"price\":null,");
                            } else {
                                rawNumberField(b, "price", Double.toString(price * eventPips))
                                        .append(',');
                            }
                            nullableNumberField(b, "size", size).append(',');
                            booleanField(b, "anomaly", anomaly);
                            b.append('}');
                            return b.toString();
                        }));
    }

    private void emitControl(String type, String detail) {
        if (queue == null) {
            return;
        }
        long seq = nextSequence();
        final long eventNs = marketNs;
        final String eventPhase = phase(), eventAlias = alias;
        final double eventPips = pips;
        enqueue(
                new CanonicalEvent(
                        seq,
                        eventNs,
                        () -> {
                            StringBuilder b = new StringBuilder(320);
                            b.append('{');
                            field(b, "schema", SCHEMA).append(',');
                            numberField(b, "seq", seq).append(',');
                            numberField(b, "market_ns", eventNs).append(',');
                            field(b, "phase", eventPhase).append(',');
                            field(b, "alias", eventAlias).append(',');
                            field(b, "event", "CONTROL").append(',');
                            field(b, "control", type).append(',');
                            field(b, "detail", detail);
                            b.append('}');
                            return b.toString();
                        }));
    }

    private long nextSequence() {
        return ++sequence;
    }

    private String phase() {
        return realtimePhase ? "REALTIME" : "HISTORY";
    }

    private String sourceMode() {
        return providerSource == null ? "UNKNOWN" : providerSource ? "PROVIDER" : "BOOKMAP_REPLAY";
    }

    private void writeSummary() throws IOException {
        Throwable error = writerError.get();
        boolean valid = error == null;
        StringBuilder b = new StringBuilder(1400);
        b.append('{');
        field(b, "schema", SCHEMA).append(',');
        field(b, "addon_version", "0.5.0").append(',');
        field(b, "alias", alias).append(',');
        rawNumberField(b, "pips", Double.toString(pips)).append(',');
        rawNumberField(b, "multiplier", Double.toString(multiplier)).append(',');
        field(b, "event_file", eventFile.getFileName().toString()).append(',');
        field(b, "bookmap_source_mode", sourceMode()).append(',');
        booleanField(b, "archive_finalized", archiveFinalized.get()).append(',');
        booleanField(b, "writer_ok", valid).append(',');
        nullableStringField(b, "writer_error", error == null ? null : error.toString()).append(',');
        numberField(b, "total_records", recordsPersisted).append(',');
        numberField(b, "last_allocated_seq", sequence).append(',');
        numberField(b, "mbo_add_received", mboAdds).append(',');
        numberField(b, "mbo_replace_received", mboReplaces).append(',');
        numberField(b, "mbo_cancel_received", mboCancels).append(',');
        numberField(b, "trades_received", trades).append(',');
        booleanField(b, "export_mbo", settings.exportMbo).append(',');
        booleanField(b, "export_trades", settings.exportTrades).append(',');
        numberField(b, "queue_capacity", effectiveQueueCapacity).append(',');
        numberField(b, "max_checkpoint_interval_ms", effectiveFlushIntervalMs).append(',');
        field(b, "flush_policy", "buffer_full_or_checkpoint").append(',');
        numberField(b, "io_buffer_bytes", IO_BUFFER_BYTES).append(',');
        numberField(b, "application_disk_bytes", diskBytesWritten).append(',');
        numberField(b, "application_disk_write_ops", diskWriteOps).append(',');
        numberField(b, "checkpoint_flush_count", flushCount).append(',');
        field(b, "last_flush_reason", lastFlushReason).append(',');
        numberField(b, "records_persisted", recordsPersisted).append(',');
        numberField(b, "duplicate_adds", duplicateAdds).append(',');
        numberField(b, "unknown_replaces", unknownReplaces).append(',');
        numberField(b, "unknown_cancels", unknownCancels).append(',');
        numberField(b, "time_reversals", timeReversals).append(',');
        nullableLongField(
                        b,
                        "first_market_ns",
                        firstMarketNs == Long.MIN_VALUE ? null : firstMarketNs)
                .append(',');
        nullableLongField(b, "last_market_ns", lastMarketNs == Long.MIN_VALUE ? null : lastMarketNs)
                .append(',');
        numberField(b, "orders_open_at_stop", orders.size()).append(',');
        numberField(b, "journal_dropped", journalDropped).append(',');
        numberField(b, "journal_overflows", journalOverflows).append(',');
        numberField(b, "journal_queue_high_water", journalHighWater).append(',');
        b.append("\"callback_latency\":").append(callbackMetrics.json()).append(',');
        nullableStringField(b, "bridge_error", bridgeFailure.isEmpty() ? null : bridgeFailure)
                .append(',');
        b.append("\"bridge\":").append(bridge == null ? "null" : bridge.status()).append(',');
        field(b, "run_tag", effectiveRunTag());
        b.append('}');
        Files.writeString(
                summaryFile, b.toString() + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    private String effectiveRunTag() {
        if (settings != null && settings.runTag != null && !settings.runTag.isBlank()) {
            return settings.runTag;
        }
        return trimToEmpty(System.getenv("ORDERFLOW_RUN_TAG"));
    }

    private String formatPriceLevel(double priceLevel) {
        return Double.toString(priceLevel * pips);
    }

    private static String sideText(boolean bid) {
        return bid ? "BID" : "ASK";
    }

    private static String formatMarketTime(long ns) {
        if (ns <= 0L) {
            return "not received";
        }
        try {
            long seconds = Math.floorDiv(ns, 1_000_000_000L);
            long nanos = Math.floorMod(ns, 1_000_000_000L);
            return Instant.ofEpochSecond(seconds, nanos).toString() + " (" + ns + " ns)";
        } catch (RuntimeException e) {
            return Long.toString(ns) + " ns";
        }
    }

    private static String abbreviate(String value) {
        if (value == null || value.isBlank()) {
            return "n/a";
        }
        if (value.length() <= 16) {
            return value;
        }
        return value.substring(0, 6) + "…" + value.substring(value.length() - 6);
    }

    private long currentFileSize() {
        if (eventFile == null) {
            return 0L;
        }
        try {
            return Files.exists(eventFile) ? Files.size(eventFile) : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String formatFileSize(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double kib = bytes / 1024.0;
        if (kib < 1024.0) {
            return String.format("%.1f KiB", kib);
        }
        double mib = kib / 1024.0;
        if (mib < 1024.0) {
            return String.format("%.2f MiB", mib);
        }
        return String.format("%.2f GiB", mib / 1024.0);
    }

    private String formatFlushAge() {
        long last = lastFlushEpochMs;
        if (last <= 0L) {
            return "pending";
        }
        long age = Math.max(0L, System.currentTimeMillis() - last);
        return age + " ms ago";
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String trimToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    private static String sanitize(String s) {
        return s == null ? "unknown" : s.replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    private static String html(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static StringBuilder field(StringBuilder b, String key, String value) {
        return b.append('"')
                .append(escape(key))
                .append("\":\"")
                .append(escape(value == null ? "" : value))
                .append('"');
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
