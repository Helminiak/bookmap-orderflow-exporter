package com.limacharlie.orderflow;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.GridLayout;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;

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

/**
 * Raw Bookmap MBO/trade exporter for the Orderflow project.
 *
 * v0.2 adds a Bookmap-native configuration/status UI while preserving the
 * v0.1 raw event schema.
 *
 * This module intentionally performs no trading and no feature engineering.
 */
@Layer1SimpleAttachable
@Layer1StrategyName("Orderflow Raw Exporter v0.2")
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION2)
public class BookmapOrderflowExporter implements
        CustomModule,
        CustomSettingsPanelProvider,
        MarketByOrderDepthDataListener,
        TradeDataListener,
        TimeListener,
        HistoricalModeListener {

    private static final String SCHEMA = "bookmap-orderflow-v0.1";
    private static final String POISON = new String("__POISON__");
    private static final int DEFAULT_QUEUE_CAPACITY = 1_000_000;
    private static final int MIN_QUEUE_CAPACITY = 10_000;
    private static final int MAX_QUEUE_CAPACITY = 5_000_000;
    private static final int DEFAULT_FLUSH_INTERVAL_MS = 1_000;
    private static final int MIN_FLUSH_INTERVAL_MS = 100;
    private static final int MAX_FLUSH_INTERVAL_MS = 10_000;
    private static final long STATUS_REFRESH_INTERVAL_NS = 250_000_000L;
    private static final DateTimeFormatter FILE_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS").withZone(ZoneOffset.UTC);

    @StrategySettingsVersion(currentVersion = 1, compatibleVersions = {})
    public static class Settings {
        public String outputDirectory = "";
        public String runTag = "";
        public int queueCapacity = DEFAULT_QUEUE_CAPACITY;
        public int flushIntervalMs = DEFAULT_FLUSH_INTERVAL_MS;
        public boolean exportMbo = true;
        public boolean exportTrades = true;
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
    private volatile String lastMboEvent = "No MBO event received yet";
    private volatile String lastTradeEvent = "No trade received yet";
    private volatile String uiMessage = "Running";
    private volatile long lastStatusRequestNs = 0L;

    private Path outputDir;
    private Path eventFile;
    private Path summaryFile;
    private BlockingQueue<String> queue;
    private Thread writerThread;
    private int effectiveQueueCapacity = DEFAULT_QUEUE_CAPACITY;
    private int effectiveFlushIntervalMs = DEFAULT_FLUSH_INTERVAL_MS;
    private volatile long recordsPersisted = 0L;
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
                configured = Paths.get(System.getProperty("user.home"), "BookmapOrderflowExports").toString();
            }
            outputDir = Paths.get(configured);
            Files.createDirectories(outputDir);

            String stem = sanitize(alias) + "_" + FILE_TS.format(Instant.now());
            eventFile = outputDir.resolve(stem + ".ndjson.gz");
            summaryFile = outputDir.resolve(stem + ".summary.json");

            effectiveQueueCapacity = clamp(settings.queueCapacity, MIN_QUEUE_CAPACITY, MAX_QUEUE_CAPACITY);
            effectiveFlushIntervalMs = clamp(settings.flushIntervalMs, MIN_FLUSH_INTERVAL_MS, MAX_FLUSH_INTERVAL_MS);
            queue = new ArrayBlockingQueue<>(effectiveQueueCapacity);
            startWriter();

            emitControl("START", "initialized");
            uiMessage = "Export active";
            scheduleStatusRefresh(true);

            System.out.println("[OrderflowExporter] alias=" + alias
                    + " pips=" + pips
                    + " queue=" + effectiveQueueCapacity
                    + " output=" + eventFile);
        } catch (Exception e) {
            uiMessage = "Initialization failed: " + e;
            scheduleStatusRefresh(true);
            throw new IllegalStateException("Unable to initialize Orderflow exporter", e);
        }
    }

    private void resetRunState() {
        orders.clear();
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
        lastFlushEpochMs = 0L;
        lastMboEvent = "No MBO event received yet";
        lastTradeEvent = "No trade received yet";
        uiMessage = "Initializing";
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
        scheduleStatusRefresh(false);
    }

    @Override
    public void send(String orderId, boolean isBid, int price, int size) {
        OrderState prior = orders.put(orderId, new OrderState(isBid, price, size));
        openOrderCount = orders.size();
        if (prior != null) {
            duplicateAdds++;
        }
        mboAdds++;
        lastMboEvent = "ADD " + sideText(isBid) + " " + formatPriceLevel(price)
                + " x" + size + " id=" + abbreviate(orderId);
        if (settings.exportMbo) {
            emitMbo("MBO_ADD", orderId, isBid ? "BID" : "ASK", price, size, prior != null);
        }
        scheduleStatusRefresh(false);
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
        openOrderCount = orders.size();
        mboReplaces++;
        lastMboEvent = "REPLACE " + side + " " + formatPriceLevel(price)
                + " x" + size + " id=" + abbreviate(orderId)
                + (anomaly ? " [UNKNOWN ORDER]" : "");
        if (settings.exportMbo) {
            emitMbo("MBO_REPLACE", orderId, side, price, size, anomaly);
        }
        scheduleStatusRefresh(false);
    }

    @Override
    public void cancel(String orderId) {
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
        lastMboEvent = "CANCEL " + side
                + (price == null ? "" : " " + formatPriceLevel(price))
                + (size == null ? "" : " x" + size)
                + " id=" + abbreviate(orderId)
                + (anomaly ? " [UNKNOWN ORDER]" : "");
        if (settings.exportMbo) {
            emitMboNullable("MBO_CANCEL", orderId, side, price, size, anomaly);
        }
        scheduleStatusRefresh(false);
    }

    @Override
    public void onTrade(double price, int size, TradeInfo tradeInfo) {
        trades++;
        lastTradeEvent = (tradeInfo.isBidAggressor ? "BUY " : "SELL ")
                + formatPriceLevel(price) + " x" + size
                + " aggr=" + abbreviate(tradeInfo.aggressorOrderId)
                + " passive=" + abbreviate(tradeInfo.passiveOrderId);

        if (settings.exportTrades) {
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
        scheduleStatusRefresh(false);
    }

    @Override
    public void onRealtimeStart() {
        realtimePhase = true;
        emitControl("REALTIME_START", "Bookmap historical catch-up completed");
        uiMessage = "Realtime";
        scheduleStatusRefresh(true);
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

    private static StrategyPanel[] buildPanels(Settings settings, Api api, BookmapOrderflowExporter instance) {
        StrategyPanel configPanel = new StrategyPanel("Exporter configuration");
        configPanel.setLayout(new BorderLayout(4, 4));

        JPanel fields = new JPanel(new GridLayout(0, 1, 4, 4));

        JCheckBox exportMboBox = new JCheckBox("Export MBO add / replace / cancel records", settings.exportMbo);
        JCheckBox exportTradesBox = new JCheckBox("Export trade records", settings.exportTrades);
        fields.add(exportMboBox);
        fields.add(exportTradesBox);

        JPanel outputRow = new JPanel(new BorderLayout(4, 0));
        JTextField outputField = new JTextField(settings.outputDirectory == null ? "" : settings.outputDirectory, 30);
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
        JSpinner queueSpinner = new JSpinner(new SpinnerNumberModel(
                clamp(settings.queueCapacity, MIN_QUEUE_CAPACITY, MAX_QUEUE_CAPACITY),
                MIN_QUEUE_CAPACITY,
                MAX_QUEUE_CAPACITY,
                10_000));
        queueRow.add(new JLabel("Writer queue capacity: "));
        queueRow.add(queueSpinner);
        fields.add(queueRow);

        JPanel flushRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JSpinner flushSpinner = new JSpinner(new SpinnerNumberModel(
                clamp(settings.flushIntervalMs, MIN_FLUSH_INTERVAL_MS, MAX_FLUSH_INTERVAL_MS),
                MIN_FLUSH_INTERVAL_MS,
                MAX_FLUSH_INTERVAL_MS,
                100));
        flushRow.add(new JLabel("Disk flush interval (ms): "));
        flushRow.add(flushSpinner);
        fields.add(flushRow);

        JLabel applyNote = new JLabel(
                "<html>Apply restarts this exporter instance and starts a new output file.</html>");
        fields.add(applyNote);

        JButton applyButton = new JButton("Apply settings / restart exporter");
        configPanel.add(fields, BorderLayout.CENTER);
        configPanel.add(applyButton, BorderLayout.SOUTH);

        browseButton.addActionListener(e -> {
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

        applyButton.addActionListener(e -> {
            if (api == null) {
                return;
            }
            settings.exportMbo = exportMboBox.isSelected();
            settings.exportTrades = exportTradesBox.isSelected();
            settings.outputDirectory = outputField.getText().trim();
            settings.runTag = tagField.getText().trim();
            settings.queueCapacity = ((Number) queueSpinner.getValue()).intValue();
            settings.flushIntervalMs = ((Number) flushSpinner.getValue()).intValue();
            api.setSettings(settings);
            api.reload();
        });

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
            status.setText("<html>Enable the exporter for an instrument to see live status.</html>");
        }

        boolean enabled = api != null;
        setEnabledRecursively(configPanel, enabled);
        setEnabledRecursively(statusPanel, enabled);
        return new StrategyPanel[] { configPanel, statusPanel };
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
            SwingUtilities.invokeLater(() -> {
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

        Runnable update = () -> {
            Throwable error = writerError.get();
            int queueSize = queue == null ? 0 : queue.size();
            int queueCapacity = effectiveQueueCapacity <= 0 ? DEFAULT_QUEUE_CAPACITY : effectiveQueueCapacity;
            double fill = queueCapacity == 0 ? 0.0 : (100.0 * queueSize / queueCapacity);

            StringBuilder b = new StringBuilder(1600);
            b.append("<html>");
            b.append("<b>Instrument:</b> ").append(html(alias)).append("<br/>");
            b.append("<b>Mode:</b> ").append(phase()).append("<br/>");
            b.append("<b>Status:</b> ").append(error == null ? html(uiMessage) : "WRITER ERROR").append("<br/>");
            b.append("<b>Market/replay time:</b> ").append(html(formatMarketTime(marketNs))).append("<br/>");
            b.append("<b>Output:</b> ").append(html(eventFile == null ? "not initialized" : eventFile.toString())).append("<br/>");
            b.append("<b>On-disk size:</b> ").append(formatFileSize(currentFileSize())).append("<br/>");
            b.append("<b>Persisted records:</b> ").append(recordsPersisted)
                    .append(" &nbsp; Last flush: ").append(formatFlushAge()).append("<br/>");
            b.append("<b>Queue:</b> ").append(queueSize).append(" / ").append(queueCapacity)
                    .append(String.format(" (%.2f%%)", fill)).append("<br/><br/>");

            b.append("<b>Received events</b><br/>");
            b.append("MBO add: ").append(mboAdds)
                    .append(" &nbsp; replace: ").append(mboReplaces)
                    .append(" &nbsp; cancel: ").append(mboCancels)
                    .append(" &nbsp; trades: ").append(trades).append("<br/>");
            b.append("Open MBO orders tracked: ").append(openOrderCount).append("<br/>");
            b.append("Records written/enqueued: ").append(sequence).append("<br/><br/>");

            b.append("<b>Integrity counters</b><br/>");
            b.append("Duplicate adds: ").append(duplicateAdds)
                    .append(" &nbsp; unknown replaces: ").append(unknownReplaces)
                    .append(" &nbsp; unknown cancels: ").append(unknownCancels)
                    .append(" &nbsp; time reversals: ").append(timeReversals).append("<br/><br/>");

            b.append("<b>Last MBO:</b> ").append(html(lastMboEvent)).append("<br/>");
            b.append("<b>Last trade:</b> ").append(html(lastTradeEvent)).append("<br/><br/>");

            b.append("<b>Active configuration:</b> MBO=")
                    .append(settings != null && settings.exportMbo)
                    .append(", Trades=")
                    .append(settings != null && settings.exportTrades)
                    .append(", Flush=")
                    .append(effectiveFlushIntervalMs)
                    .append(" ms, Run tag=")
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
        writerThread = new Thread(() -> {
            long flushIntervalNs = TimeUnit.MILLISECONDS.toNanos(effectiveFlushIntervalMs);
            long nextFlushNs = System.nanoTime() + flushIntervalNs;
            try (BufferedWriter out = new BufferedWriter(
                    new OutputStreamWriter(
                            new GZIPOutputStream(
                                    new BufferedOutputStream(Files.newOutputStream(eventFile), 1 << 20),
                                    1 << 20,
                                    true),
                            StandardCharsets.UTF_8),
                    1 << 20)) {
                while (true) {
                    long waitNs = Math.max(1L, nextFlushNs - System.nanoTime());
                    String line = queue.poll(waitNs, TimeUnit.NANOSECONDS);
                    if (line == POISON) {
                        break;
                    }
                    if (line != null) {
                        out.write(line);
                        out.newLine();
                        recordsPersisted++;
                    }

                    long now = System.nanoTime();
                    if (now >= nextFlushNs) {
                        out.flush();
                        lastFlushEpochMs = System.currentTimeMillis();
                        scheduleStatusRefresh(true);
                        nextFlushNs = now + flushIntervalNs;
                    }
                }
                out.flush();
                lastFlushEpochMs = System.currentTimeMillis();
                scheduleStatusRefresh(true);
            } catch (Throwable t) {
                writerError.compareAndSet(null, t);
                uiMessage = "Writer failed";
                scheduleStatusRefresh(true);
            }
        }, "bookmap-orderflow-writer");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    /**
     * STRICT mode: block the Bookmap callback rather than silently drop an event.
     * That is intentional for historical extraction. Live streaming will use
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
        StringBuilder b = new StringBuilder(1400);
        b.append('{');
        field(b, "schema", SCHEMA).append(',');
        field(b, "addon_version", "0.2.0").append(',');
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
        numberField(b, "flush_interval_ms", effectiveFlushIntervalMs).append(',');
        numberField(b, "records_persisted", recordsPersisted).append(',');
        numberField(b, "duplicate_adds", duplicateAdds).append(',');
        numberField(b, "unknown_replaces", unknownReplaces).append(',');
        numberField(b, "unknown_cancels", unknownCancels).append(',');
        numberField(b, "time_reversals", timeReversals).append(',');
        nullableLongField(b, "first_market_ns", firstMarketNs == Long.MIN_VALUE ? null : firstMarketNs).append(',');
        nullableLongField(b, "last_market_ns", lastMarketNs == Long.MIN_VALUE ? null : lastMarketNs).append(',');
        numberField(b, "orders_open_at_stop", orders.size()).append(',');
        field(b, "run_tag", effectiveRunTag());
        b.append('}');
        Files.writeString(summaryFile, b.toString() + System.lineSeparator(), StandardCharsets.UTF_8);
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
