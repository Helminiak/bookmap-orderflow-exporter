package com.limacharlie.orderflow;

import java.awt.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.swing.*;
import javax.swing.event.*;

/**
 * EDT-owned controls; local address discovery stages fields only; explicit exporter Apply remains
 * the commit point.
 */
final class LanBindControls extends JPanel {
    private final JTextField bind;
    private final Supplier<List<LocalLanIpv4.Address>> source;
    private final JComboBox<String> mode =
            new JComboBox<>(new String[] {"Manual bind", "Auto local LAN IPv4"});
    private final JComboBox<LocalLanIpv4.Address> adapters = new JComboBox<>();
    private final JButton refresh = new JButton("Detect / refresh local IP");
    private final JTextArea status;
    private final JTextArea measurer = new JTextArea();
    private List<LocalLanIpv4.Address> snapshot = List.of();
    private boolean ready, updating, autoResolved;
    private int generation;
    private CompletableFuture<Void> completed = CompletableFuture.completedFuture(null);
    private Consumer<String> errors =
            text ->
                    JOptionPane.showMessageDialog(
                            this, text, "Bridge bind address", JOptionPane.WARNING_MESSAGE);

    LanBindControls(
            JTextField bind,
            String savedMode,
            Supplier<List<LocalLanIpv4.Address>> source,
            boolean enabled) {
        super(new BorderLayout(4, 4));
        this.bind = bind;
        this.source = source;
        setOpaque(false);
        mode.setSelectedIndex("auto".equals(savedMode) ? 1 : 0); // Missing/legacy mode is manual.
        mode.getAccessibleContext().setAccessibleName("Bridge bind address selection mode");
        adapters.getAccessibleContext().setAccessibleName("Local adapter and IPv4 address");
        refresh.setMnemonic(java.awt.event.KeyEvent.VK_D);
        refresh.setToolTipText(
                "Read this Bookmap computer's local interfaces; never contacts the receiver or"
                        + " applies settings.");
        adapters.setRenderer(
                (list, value, index, selected, focus) -> {
                    var label = new DefaultListCellRenderer();
                    return label.getListCellRendererComponent(
                            list,
                            value == null
                                    ? "Choose local adapter / IP"
                                    : "Adapter: " + LocalLanIpv4.label(value),
                            index,
                            selected,
                            focus);
                });
        var row = new BridgeConfigurationLayout.NetworkRow(this::availableWidth);
        row.add(mode);
        row.add(refresh);
        var top = new JPanel(new BorderLayout(4, 4));
        top.setOpaque(false);
        top.add(row, BorderLayout.NORTH);
        top.add(adapters, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);
        status =
                new JTextArea() {
                    @Override
                    public Dimension getPreferredSize() {
                        int width = availableWidth();
                        if (width <= 0) width = 520;
                        measurer.setDocument(getDocument());
                        measurer.setFont(getFont());
                        measurer.setMargin(getMargin());
                        measurer.setLineWrap(true);
                        measurer.setWrapStyleWord(true);
                        measurer.setSize(width, Integer.MAX_VALUE);
                        int height = measurer.getPreferredSize().height;
                        try {
                            var end =
                                    measurer.modelToView2D(
                                            Math.max(0, getDocument().getLength() - 1));
                            if (end != null)
                                height =
                                        Math.max(
                                                height,
                                                (int) Math.ceil(end.getMaxY())
                                                        + measurer.getInsets().bottom);
                        } catch (javax.swing.text.BadLocationException e) {
                            throw new IllegalStateException(e);
                        }
                        return new Dimension(0, height);
                    }

                    private int availableWidth() {
                        return getParent() == null || getParent().getWidth() <= 0
                                ? getWidth()
                                : getParent().getWidth();
                    }

                    @Override
                    public Dimension getMinimumSize() {
                        return new Dimension(0, getPreferredSize().height);
                    }
                };
        status.setEditable(false);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);
        status.setOpaque(false);
        status.setFont(UIManager.getFont("Label.font"));
        status.setForeground(UIManager.getColor("Label.foreground"));
        status.putClientProperty("orderflow.navigation", true);
        status.getAccessibleContext().setAccessibleName("Local bind discovery status");
        add(status, BorderLayout.CENTER);
        mode.addActionListener(
                e -> {
                    if (!updating) {
                        if (automatic()) discover();
                        else manualStatus();
                    }
                });
        refresh.addActionListener(e -> discover());
        adapters.addActionListener(
                e -> {
                    if (!updating
                            && automatic()
                            && adapters.getSelectedItem() instanceof LocalLanIpv4.Address address) {
                        autoResolved = true;
                        setDraft(address.ip());
                        message(
                                "Selected "
                                        + LocalLanIpv4.label(address)
                                        + "; next Apply only. Linux receiver connects to this"
                                        + " Bookmap address.");
                    }
                });
        bind.getDocument()
                .addDocumentListener(
                        new DocumentListener() {
                            private void edited() {
                                if (!updating) {
                                    updating = true;
                                    mode.setSelectedIndex(0);
                                    updating = false;
                                    manualStatus();
                                }
                            }

                            public void insertUpdate(DocumentEvent e) {
                                edited();
                            }

                            public void removeUpdate(DocumentEvent e) {
                                edited();
                            }

                            public void changedUpdate(DocumentEvent e) {
                                edited();
                            }
                        });
        mode.setEnabled(enabled);
        refresh.setEnabled(enabled);
        manualStatus();
        if (enabled && (automatic() || !"0.0.0.0".equals(bind.getText().trim()))) discover();
    }

    private int availableWidth() {
        return getParent() == null || getParent().getWidth() <= 0
                ? getWidth()
                : getParent().getWidth();
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(0, getPreferredSize().height);
    }

    private boolean automatic() {
        return mode.getSelectedIndex() == 1;
    }

    private void setDraft(String ip) {
        updating = true;
        try {
            bind.setText(ip);
        } finally {
            updating = false;
        }
    }

    private void message(String text) {
        status.setText(text);
        status.setCaretPosition(0);
        revalidate();
        repaint();
    }

    private void manualStatus() {
        adapters.setEnabled(false);
        message(
                "Manual bind retained, including explicit 0.0.0.0. Choose Auto to detect this"
                        + " Bookmap PC's LAN IPv4; changes take effect only on Apply.");
    }

    private void discover() {
        int request = ++generation;
        autoResolved = false;
        var completion = new CompletableFuture<Void>();
        completed = completion;
        ready = false;
        message(
                "Reading local interface metadata; bind and current session are unchanged until"
                        + " selection and Apply.");
        new SwingWorker<List<LocalLanIpv4.Address>, Void>() {
            @Override
            protected List<LocalLanIpv4.Address> doInBackground() {
                return List.copyOf(source.get());
            }

            @Override
            protected void done() {
                try {
                    if (request != generation) return;
                    snapshot = get();
                    ready = true;
                    List<LocalLanIpv4.Address> choices = LocalLanIpv4.candidates(snapshot);
                    updating = true;
                    try {
                        adapters.setModel(
                                new DefaultComboBoxModel<>(
                                        choices.toArray(LocalLanIpv4.Address[]::new)));
                        adapters.setSelectedIndex(-1);
                        adapters.setEnabled(automatic() && mode.isEnabled());
                    } finally {
                        updating = false;
                    }
                    if (!automatic()) {
                        manualStatus();
                        return;
                    }
                    String detected = LocalLanIpv4.suggested(snapshot);
                    if (detected != null) {
                        autoResolved = true;
                        setDraft(detected);
                        message(
                                "Detected "
                                        + LocalLanIpv4.label(choices.get(0))
                                        + "; next Apply only. Linux receiver connects to this"
                                        + " Bookmap address.");
                    } else {
                        message(
                                choices.isEmpty()
                                        ? "No suitable local LAN IPv4 found. Bind retained; choose"
                                                + " Manual (0.0.0.0 is supported) or refresh. No"
                                                + " restart occurred."
                                        : "Multiple local LAN IPv4 addresses found. Bind retained;"
                                                + " choose an adapter/IP or Manual. No address"
                                                + " selected automatically.");
                    }
                } catch (Exception e) {
                    if (request == generation) {
                        snapshot = List.of();
                        ready = false;
                        message(
                                "Local discovery failed. Bind retained; refresh or use Manual"
                                        + " 0.0.0.0. No restart occurred.");
                    }
                } finally {
                    completion.complete(null);
                }
            }
        }.execute();
    }

    boolean validateForApply() {
        String value = bind.getText().trim();
        String error = null;
        if (!automatic() && "0.0.0.0".equals(value)) return true;
        if (!ready)
            error =
                    "Run Detect / refresh local IP once to validate this address, then Apply. Or"
                        + " use Manual 0.0.0.0 to bind all interfaces.";
        else if (automatic()
                && (!autoResolved
                        || !LocalLanIpv4.candidates(snapshot).stream()
                                .anyMatch(a -> a.ip().equals(value))))
            error =
                    "Choose a detected local LAN adapter/IP, or switch to Manual. The receiver's"
                            + " address is not a publisher bind address.";
        else if (!LocalLanIpv4.validBind(value, snapshot))
            error =
                    "Bind must be 0.0.0.0 or an active IPv4 address assigned to this Bookmap PC."
                            + " Refresh after adapter changes.";
        if (error != null) {
            errors.accept(error);
            return false;
        }
        return true;
    }

    String modeKey() {
        return automatic() ? "auto" : "manual";
    }

    JComboBox<String> modeSelector() {
        return mode;
    }

    JComboBox<LocalLanIpv4.Address> adapterSelector() {
        return adapters;
    }

    JButton refreshButton() {
        return refresh;
    }

    JTextArea statusText() {
        return status;
    }

    CompletableFuture<Void> discoveryFinished() {
        return completed;
    }

    void errorReporter(Consumer<String> reporter) {
        errors = reporter;
    }
}
