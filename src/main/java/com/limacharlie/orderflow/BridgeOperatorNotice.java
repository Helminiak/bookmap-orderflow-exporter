package com.limacharlie.orderflow;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.event.HierarchyEvent;
import java.util.function.Supplier;

import javax.swing.*;

/** Read-only nonmodal notice, outside all tabs and outside the callback path. */
final class BridgeOperatorNotice extends JPanel {
    static final String REVIEW = "FOR REVIEW — NOT PRODUCTION APPROVED";
    private static final String START =
            "Start the Linux receiver with Start-Receiver.sh --host WINDOWS_IP. "
                    + "Use this Bookmap PC's LAN address; a health probe alone is not a receiver.";
    private static final String DISABLE =
            "For archive-only use, uncheck Live Linux bridge and Apply in a safe window. Apply"
                    + " restarts the exporter and creates a new session; do not interrupt active"
                    + " trading.";

    record Notice(String state, String message, boolean warning) {}

    private final Supplier<Notice> source;
    private final JTextArea message =
            new JTextArea() {
                @Override
                public Dimension getPreferredSize() {
                    java.awt.Container parent = getParent();
                    int width = parent == null ? 0 : parent.getWidth();
                    if (parent != null
                            && parent.getParent() != null
                            && parent.getParent().getWidth() > 0)
                        width = parent.getParent().getWidth();
                    if (width <= 0 || getUI() == null) return super.getPreferredSize();
                    java.awt.Insets insets = getInsets();
                    javax.swing.text.View view = getUI().getRootView(this);
                    view.setSize(Math.max(1, width - insets.left - insets.right), Float.MAX_VALUE);
                    return new Dimension(
                            width,
                            (int) Math.ceil(view.getPreferredSpan(javax.swing.text.View.Y_AXIS))
                                    + insets.top
                                    + insets.bottom);
                }
            };
    private final Timer timer;
    private Notice displayed;

    BridgeOperatorNotice(Supplier<Notice> source) {
        super(new BorderLayout(4, 4));
        this.source = source;
        JLabel review = new JLabel("<html>" + REVIEW + "</html>");
        review.putClientProperty("orderflow.navigation", true);
        add(review, BorderLayout.NORTH);
        message.setEditable(false);
        message.setLineWrap(true);
        message.setWrapStyleWord(true);
        message.setOpaque(false);
        message.setFont(review.getFont());
        message.setRows(0);
        message.setColumns(1);
        message.putClientProperty("orderflow.navigation", true);
        message.getAccessibleContext()
                .setAccessibleName("Linux bridge availability and review notice");
        add(message, BorderLayout.CENTER);
        timer = new Timer(1000, e -> refreshNow());
        timer.setCoalesce(true);
        addHierarchyListener(
                e -> {
                    if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                        if (isShowing()) {
                            refreshNow();
                            timer.start();
                        } else timer.stop();
                    }
                });
        refreshNow();
    }

    @Override
    public void removeNotify() {
        timer.stop();
        super.removeNotify();
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(0, getPreferredSize().height);
    }

    void refreshNow() {
        Notice next =
                source.get(); // Volatile fields only: no network, disk, waits or JSON parsing.
        if (!next.equals(displayed)) {
            displayed = next;
            message.setText("Linux bridge — " + next.state() + "\n" + next.message());
            message.setCaretPosition(0);
            message.setForeground(
                    next.warning()
                            ? new java.awt.Color(150, 55, 0)
                            : UIManager.getColor("Label.foreground"));
        }
    }

    Notice displayed() {
        return displayed;
    }

    boolean polling() {
        return timer.isRunning();
    }

    static Notice disabled() {
        return new Notice(
                "DISABLED",
                "Optional bridge delivery is off. Local archive validity is separate. "
                        + "See Information → Review checklist before native testing.",
                false);
    }

    static Notice failure(String reason) {
        return new Notice(
                "ERROR / INVALID",
                "Bridge could not operate: "
                        + reason
                        + ". Delivery is not proven. Check bind address and unique ports. "
                        + "Archive validity is separate. "
                        + DISABLE,
                true);
    }

    static Notice describe(LiveBridge.OperatorSnapshot s) {
        String retained = " Retained events: " + s.depth() + "/" + s.capacity() + ".";
        if (s.invalid()) {
            String range =
                    s.offered() > s.acknowledged()
                            ? " Unconfirmed sequence range: "
                                    + (s.acknowledged() + 1)
                                    + "–"
                                    + s.offered()
                                    + "."
                            : "";
            return new Notice(
                    s.reason().startsWith("publisher failure:") ? "ERROR / INVALID" : "INVALID",
                    "Transport completeness is no longer proven: "
                            + s.reason()
                            + "."
                            + range
                            + " Starting a receiver now cannot restore lost events. Validate the"
                            + " local archive for offline recovery, or start a fresh session in a"
                            + " safe window. "
                            + DISABLE,
                    true);
        }
        if (s.state().equals("STOPPED"))
            return new Notice(
                    "STOPPED",
                    "Session closed. Inspect final archive and delivery summary separately.",
                    false);
        boolean near = s.capacity() > 0 && (long) s.depth() * 100 >= (long) s.capacity() * 80;
        String advisory =
                near
                        ? " WARNING: retention is at least 80% full; further backlog can invalidate"
                                + " delivery."
                        : "";
        if (s.state().equals("DISCONNECTED"))
            return new Notice(
                    "RECOVERABLE DISCONNECT",
                    "Same running receiver may reconnect while the contiguous retention window"
                            + " remains available. Receiver-process restart is not supported."
                            + retained
                            + advisory
                            + " "
                            + START
                            + " Archive validity is separate.",
                    true);
        if (!s.registered())
            return new Notice(
                    "WAITING FOR RECEIVER",
                    "Receiver unavailable: no HELLO handshake observed. "
                            + "Events are retained only within the finite limit."
                            + retained
                            + advisory
                            + " "
                            + START
                            + " "
                            + DISABLE
                            + " Archive validity is separate.",
                    true);
        return new Notice(
                s.acknowledged() > 0 ? "CONNECTED / ACKED IN RAM" : "CONNECTED / AWAITING ACK",
                "Receiver handshake observed. ACK checkpoint: "
                        + s.acknowledged()
                        + "; validation in memory, not durable storage."
                        + retained
                        + advisory
                        + " Archive validity is separate.",
                near);
    }
}
