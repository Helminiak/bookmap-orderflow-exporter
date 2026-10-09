package com.limacharlie.orderflow;

import java.awt.*;
import java.awt.event.*;

import javax.swing.*;

/**
 * Settings-panel recovery controls for issue17 (draft-only bridge selection).
 *
 * <p>EDT-only. No network, file, timer, thread, Apply/commit, or runtime-validity mutation.
 *
 * <p>No native DPI awareness; font scale is applied recursively to actual components in tests.
 */
final class BridgeRecoveryControls extends JPanel {

    private final BridgeRecoveryDraft draft;
    private final JCheckBox checkbox;
    private final JButton button;
    private final JTextArea textArea;
    private final JTextArea measurer = new JTextArea();

    BridgeRecoveryControls(boolean savedBridgePreference) {
        this.draft = new BridgeRecoveryDraft(savedBridgePreference);
        setLayout(new GridBagLayout());
        setOpaque(false);
        Font f = UIManager.getFont("Label.font");
        if (f != null) setFont(f);
        setForeground(UIManager.getColor("Label.foreground"));
        setBackground(UIManager.getColor("Panel.background"));

        checkbox = new JCheckBox("<html>Live Linux bridge<br>on next Apply</html>");
        checkbox.setSelected(draft.bridgeEnabled());
        checkbox.setToolTipText("Toggle bridge enablement for the next Apply cycle.");
        checkbox.getAccessibleContext().setAccessibleName("Live Linux bridge on next Apply");
        checkbox.addItemListener(
                e -> {
                    draft.setBridgeEnabled(checkbox.isSelected());
                    recomputePendingText();
                });

        button = new JButton("<html>Archive only on<br>next Apply</html>");
        button.setMnemonic(KeyEvent.VK_O);
        button.setToolTipText("Stage archive-only (bridge disabled) for the next Apply cycle.");
        button.getAccessibleContext().setAccessibleName("Archive only on next Apply");
        button.addActionListener(
                e -> {
                    checkbox.setSelected(false);
                    draft.selectArchiveOnly();
                    recomputePendingText();
                });

        measurer.setLineWrap(true);
        measurer.setWrapStyleWord(true);
        textArea =
                new JTextArea() {
                    @Override
                    public Dimension getPreferredSize() {
                        int width = BridgeRecoveryControls.this.getWidth();
                        if (width <= 0) width = 520;
                        measurer.setDocument(getDocument());
                        measurer.setFont(getFont());
                        measurer.setMargin(getMargin());
                        measurer.setSize(width, Integer.MAX_VALUE);
                        int height = measurer.getPreferredSize().height;
                        try {
                            if (getDocument().getLength() > 0) {
                                var last = measurer.modelToView2D(getDocument().getLength() - 1);
                                if (last != null)
                                    height =
                                            Math.max(
                                                    height,
                                                    (int) Math.ceil(last.getMaxY())
                                                            + measurer.getInsets().bottom);
                            }
                        } catch (javax.swing.text.BadLocationException e) {
                            throw new IllegalStateException(
                                    "Unable to measure recovery draft text", e);
                        }
                        return new Dimension(0, height);
                    }

                    @Override
                    public Dimension getMinimumSize() {
                        return new Dimension(0, getPreferredSize().height);
                    }
                };
        textArea.setEditable(false);
        textArea.putClientProperty("orderflow.navigation", true);
        textArea.setLineWrap(true);
        textArea.setWrapStyleWord(true);
        textArea.setOpaque(false);
        if (f != null) textArea.setFont(f);
        textArea.setForeground(UIManager.getColor("Label.foreground"));
        textArea.getAccessibleContext().setAccessibleName("Pending bridge preference status");

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(2, 0, 2, 0);
        gbc.gridy = 0;
        add(button, gbc);
        gbc.gridy = 1;
        add(checkbox, gbc);
        gbc.gridy = 2;
        gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        add(textArea, gbc);

        recomputePendingText();
    }

    private void recomputePendingText() {
        String msg;
        if (draft.pending()) {
            String state = draft.bridgeEnabled() ? "ENABLED" : "DISABLED";
            msg =
                    "Pending bridge "
                            + state
                            + " on next Apply; current session unchanged; "
                            + "Apply restarts exporter and begins a new archive; "
                            + "INVALID delivery cannot be repaired by this selection.";
        } else {
            msg = "No pending bridge preference change, current runtime state is separate.";
        }
        textArea.setText(msg);
        textArea.setCaretPosition(0);
        revalidate();
        repaint();
    }

    JCheckBox bridgeCheckbox() {
        return checkbox;
    }

    JButton archiveOnlyButton() {
        return button;
    }

    JTextArea pendingText() {
        return textArea;
    }

    boolean bridgeEnabled() {
        return draft.bridgeEnabled();
    }

    boolean pending() {
        return draft.pending();
    }
}
