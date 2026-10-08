package com.limacharlie.orderflow;

import velox.api.layer1.annotations.Layer1ApiVersion;
import velox.api.layer1.annotations.Layer1ApiVersionValue;
import velox.api.layer1.annotations.Layer1SimpleAttachable;
import velox.api.layer1.annotations.Layer1StrategyName;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.Scrollable;
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
@Layer1StrategyName("Orderflow Raw Exporter v0.5")
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

    private final Map<String, OrderState> orders = new HashMap<>();
    private final AtomicReference<Throwable> writerError = new AtomicReference<>();
    private final AtomicBoolean statusUpdateScheduled = new AtomicBoolean();

    private Api api;
    private Settings settings;

    private String alias;
    private double pips;
    private double multiplier;
    private volatile long marketNs = 0L;
    private long sequence = 0L;
    private long firstMarketNs = Long.MIN_VALUE;
    private long lastMarketNs = Long.MIN_VALUE;
    private volatile boolean realtimePhase = false;
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
    private volatile long recordsPersisted = 0L;
    private volatile long approxUnflushedBytes = 0L;
    private volatile long diskBytesWritten = 0L;
    private volatile long diskWriteOps = 0L;
    private volatile long flushCount = 0L;
    private volatile String lastFlushReason = "pending";
    private volatile long lastFlushEpochMs = 0L;

    private volatile JLabel statusLabel;

    private record OrderState(boolean bid, int priceLevel, int size) {}

    @Override
    public void initialize(String alias, InstrumentInfo info, Api api, InitialState initialState) {
        this.api = api;
        this.alias = alias;
        this.pips = info.pips;
        this.multiplier = info.multiplier;
        this.settings = api.getSettings(Settings.class);

        resetRunState();

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

    private void resetRunState() {
        orders.clear();
        bridge = null;
        bridgeFailure = "";
        journalOverflows = 0;
        journalDropped = 0;
        journalHighWater = 0;
        callbackMetrics = new CallbackMetrics();
        writerError.set(null);
        marketNs = 0L;
        sequence = 0L;
        firstMarketNs = Long.MIN_VALUE;
        lastMarketNs = Long.MIN_VALUE;
        realtimePhase = false;
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
    }

    @Override
    public void onTimestamp(long nanoseconds) {
        long callbackStart = System.nanoTime();
        try {
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

        } finally {
            callbackMetrics.record(System.nanoTime() - callbackStart);
        }
    }

    @Override
    public void send(String orderId, boolean isBid, int price, int size) {
        long callbackStart = System.nanoTime();
        try {
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

        } finally {
            callbackMetrics.record(System.nanoTime() - callbackStart);
        }
    }

    @Override
    public void replace(String orderId, int price, int size) {
        long callbackStart = System.nanoTime();
        try {
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

        } finally {
            callbackMetrics.record(System.nanoTime() - callbackStart);
        }
    }

    @Override
    public void cancel(String orderId) {
        long callbackStart = System.nanoTime();
        try {
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

        } finally {
            callbackMetrics.record(System.nanoTime() - callbackStart);
        }
    }

    @Override
    public void onTrade(double price, int size, TradeInfo tradeInfo) {
        long callbackStart = System.nanoTime();
        try {
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
                                    rawNumberField(b, "price", Double.toString(price * eventPips))
                                            .append(',');
                                    numberField(b, "size", size).append(',');
                                    nullableStringField(b, "aggressor_order_id", aggressorId)
                                            .append(',');
                                    nullableStringField(b, "passive_order_id", passiveId)
                                            .append(',');
                                    booleanField(b, "execution_start", executionStart).append(',');
                                    booleanField(b, "execution_end", executionEnd).append(',');
                                    booleanField(b, "otc", otc);
                                    b.append('}');
                                    return b.toString();
                                }));
            }
            scheduleStatusRefresh(false);

        } finally {
            callbackMetrics.record(System.nanoTime() - callbackStart);
        }
    }

    @Override
    public void onRealtimeStart() {
        long callbackStart = System.nanoTime();
        try {
            realtimePhase = true;
            emitControl("REALTIME_START", "Bookmap historical catch-up completed");
            uiMessage = "Realtime";
            scheduleStatusRefresh(true);

        } finally {
            callbackMetrics.record(System.nanoTime() - callbackStart);
        }
    }

    @Override
    public void stop() {
        if (stopped) {
            return;
        }
        stopped = true;
        uiMessage = "Stopping";
        scheduleStatusRefresh(true);
        try {
            if (writerError.get() == null || realtimePhase) emitControl("STOP", "module stopped");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (writerThread.isAlive() && !queue.offer(POISON)) {
                if (System.nanoTime() > deadline)
                    throw new IllegalStateException("Writer stop queue timeout");
                Thread.sleep(1);
            }
            writerThread.join(30_000L);
            if (writerThread.isAlive()) {
                throw new IllegalStateException("Writer did not terminate within 30 seconds");
            }
            writeSummary();
            if (bridge != null) bridge.close();
            Throwable error = writerError.get();
            if (error != null) {
                throw new IllegalStateException("Writer failed; export is not valid", error);
            }
            uiMessage = "Stopped cleanly";
            scheduleStatusRefresh(true);
            System.out.println("[OrderflowExporter] complete: " + summaryFile);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            uiMessage = "Stop interrupted";
            scheduleStatusRefresh(true);
            throw new IllegalStateException("Interrupted while stopping exporter", e);
        } catch (IOException e) {
            uiMessage = "Summary write failed: " + e;
            scheduleStatusRefresh(true);
            throw new IllegalStateException("Unable to write exporter summary", e);
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
        StrategyPanel configPanel = new StrategyPanel("Exporter configuration");
        configPanel.setLayout(new BorderLayout());
        configPanel.add(buildConfigurationPanel(settings, api), BorderLayout.CENTER);

        StrategyPanel statusPanel = new StrategyPanel("Live exporter status");
        statusPanel.setLayout(new BorderLayout(4, 4));
        JLabel status = new JLabel();
        status.setVerticalAlignment(SwingConstants.TOP);
        statusPanel.add(status, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton refreshButton = new JButton("Refresh");
        JButton openFolderButton = new JButton("Open export folder");
        buttons.add(refreshButton);
        buttons.add(openFolderButton);
        statusPanel.add(buttons, BorderLayout.SOUTH);

        if (instance != null) {
            instance.statusLabel = status;
            instance.refreshStatusLabel();
            refreshButton.addActionListener(e -> instance.refreshStatusLabel());
            openFolderButton.addActionListener(e -> instance.openOutputFolder());
        } else {
            status.setText(
                    "<html>Enable the exporter for an instrument to see live status.</html>");
        }

        boolean enabled = api != null;
        setEnabledRecursively(configPanel, enabled);
        setEnabledRecursively(statusPanel, enabled);
        return new StrategyPanel[] {configPanel, statusPanel};
    }

    /**
     * Swing-only content also used by narrow-panel regression/preview without Bookmap runtime GUI
     * internals.
     */
    static JPanel buildConfigurationPanel(Settings settings, Api api) {
        JPanel configPanel = new JPanel();
        configPanel.setLayout(new BorderLayout(4, 4));

        SettingsFields fields = new SettingsFields();

        JCheckBox exportMboBox = new JCheckBox("Export MBO events", settings.exportMbo);
        JCheckBox exportTradesBox = new JCheckBox("Export trade events", settings.exportTrades);
        fields.add(exportMboBox);
        fields.add(exportTradesBox);

        JTextField outputField =
                new JTextField(settings.outputDirectory == null ? "" : settings.outputDirectory);
        JButton browseButton = new JButton("Browse...");
        JPanel outputInput = new JPanel(new BorderLayout(4, 0));
        outputInput.add(outputField, BorderLayout.CENTER);
        outputInput.add(browseButton, BorderLayout.EAST);
        fields.add(settingRow("Output directory (blank = default)", outputInput));
        JTextField tagField = new JTextField(settings.runTag == null ? "" : settings.runTag);
        fields.add(settingRow("Run tag", tagField));

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
        fields.add(settingRow("Journal queue (events)", queueSpinner));

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
        fields.add(settingRow("Checkpoint interval (ms)", flushSpinner));

        JLabel bufferingNote =
                new JLabel(
                        "<html>Disk buffer: 4 MiB.<br>Writes when full or at checkpoint.</html>");
        fields.add(bufferingNote);

        JCheckBox bridgeBox = new JCheckBox("Live Linux bridge (optional)", settings.bridgeEnabled);
        JCheckBox liveJournalBox =
                new JCheckBox("Responsive live journal", settings.responsiveLiveJournal);
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
        liveJournalBox.setToolTipText(
                "Keep Bookmap responsive: live journal overflow marks the archive invalid instead"
                        + " of waiting.");
        fields.add(settingRow("Bind address", bindField));
        fields.add(settingRow("Market port (TCP)", portSpinner));
        fields.add(settingRow("Health port (TCP)", healthSpinner));
        fields.add(settingRow("Bridge queue (events)", bridgeQueueSpinner));

        JLabel applyNote =
                new JLabel(
                        "<html>Apply restarts the exporter<br>and starts a new output"
                                + " file.</html>");
        fields.add(applyNote);

        JButton applyButton = new JButton("Apply / restart exporter");
        JScrollPane configScroll =
                new JScrollPane(
                        fields,
                        JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                        JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        configScroll.getVerticalScrollBar().setUnitIncrement(20);
        configScroll.setMinimumSize(new java.awt.Dimension(0, 0));
        configPanel.add(configScroll, BorderLayout.CENTER);
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

        return configPanel;
    }

    /** One labeled control per row, with the editor taking the available panel width. */
    private static JPanel settingRow(String text, javax.swing.JComponent control) {
        JPanel row = new JPanel(new BorderLayout(0, 3));
        JLabel label = new JLabel(text);
        label.setLabelFor(control);
        row.add(label, BorderLayout.NORTH);
        row.add(control, BorderLayout.CENTER);
        return row;
    }

    private static final class SettingsFields extends JPanel implements Scrollable {
        SettingsFields() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 6, 6, 6));
        }

        @Override
        public java.awt.Component add(java.awt.Component child) {
            if (child instanceof javax.swing.JComponent component) {
                component.setAlignmentX(LEFT_ALIGNMENT);
                java.awt.Dimension preferred = component.getPreferredSize();
                component.setMaximumSize(
                        new java.awt.Dimension(Integer.MAX_VALUE, preferred.height));
                component.setMinimumSize(new java.awt.Dimension(0, preferred.height));
            }
            super.add(child);
            super.add(Box.createVerticalStrut(8));
            return child;
        }

        @Override
        public java.awt.Dimension getPreferredScrollableViewportSize() {
            return new java.awt.Dimension(280, 400);
        }

        @Override
        public int getScrollableUnitIncrement(
                java.awt.Rectangle r, int orientation, int direction) {
            return 20;
        }

        @Override
        public int getScrollableBlockIncrement(
                java.awt.Rectangle r, int orientation, int direction) {
            return Math.max(20, r.height - 20);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private static void setEnabledRecursively(java.awt.Component component, boolean enabled) {
        component.setEnabled(enabled);
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

    private void startWriter() {
        writerThread =
                new Thread(
                        () -> {
                            long checkpointIntervalNs =
                                    TimeUnit.MILLISECONDS.toNanos(effectiveFlushIntervalMs);
                            long nextCheckpointNs = System.nanoTime() + checkpointIntervalNs;

                            try (CountingOutputStream countedFile =
                                            new CountingOutputStream(
                                                    Files.newOutputStream(eventFile));
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
                            } catch (Throwable t) {
                                writerError.compareAndSet(null, t);
                                uiMessage = "Writer failed";
                                scheduleStatusRefresh(true);
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

    /** Strict historical extraction; LIVE queue overflow fails the archive instead of waiting. */
    private void enqueue(CanonicalEvent event) {
        if (bridge != null) bridge.offer(event);
        boolean responsive = realtimePhase && settings.responsiveLiveJournal;
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
                while (!queue.offer(event)) {
                    if (writerError.get() != null)
                        throw new IllegalStateException("Writer failed", writerError.get());
                    if (Thread.interrupted()) throw new InterruptedException();
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
        booleanField(b, "writer_ok", valid).append(',');
        nullableStringField(b, "writer_error", error == null ? null : error.toString()).append(',');
        numberField(b, "total_records", sequence).append(',');
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
