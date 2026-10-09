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
    private final WrappingText summary = new WrappingText();
    private final WrappingText message = new WrappingText();
    private final JPanel heading =
            new JPanel(new BorderLayout(0, 4)) {
                @Override
                public Dimension getPreferredSize() {
                    return new Dimension(0, headingHeight(assignedWidth()));
                }
            };
    private final JScrollPane details =
            new JScrollPane(
                    message,
                    JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                    JScrollPane.HORIZONTAL_SCROLLBAR_NEVER) {
                @Override
                public void doLayout() {
                    // The first allocation establishes the wrapped view width. Negotiate bars again
                    // against that width in this same layout, not a later timer/paint cycle.
                    super.doLayout();
                    getViewport().doLayout();
                    super.doLayout();
                    getViewport().doLayout();
                }
            };
    private int layoutWidth;
    private int heightBudget = Integer.MAX_VALUE;

    /**
     * Measure against the assigned width, not a stale ancestor or the document's unwrapped width.
     */
    private static final class WrappingText extends JTextArea {
        private int allocatedWidth;

        WrappingText() {
            setEditable(false);
            setLineWrap(true);
            setWrapStyleWord(true);
            putClientProperty("orderflow.navigation", true);
        }

        Dimension wrappedSize(int width) {
            java.awt.Insets insets = getInsets();
            javax.swing.text.View view = getUI().getRootView(this);
            view.setSize(Math.max(1, width - insets.left - insets.right), Float.MAX_VALUE);
            float height = view.getPreferredSpan(javax.swing.text.View.Y_AXIS);
            try {
                // WrappedPlainView's span may omit the terminal visual row at a wrap boundary.
                // Include the rendered last-character rectangle, not an arbitrary extra row.
                var end =
                        view.modelToView(
                                        Math.max(0, getDocument().getLength() - 1),
                                        new java.awt.Rectangle(
                                                0,
                                                0,
                                                Math.max(1, width - insets.left - insets.right),
                                                Integer.MAX_VALUE),
                                        javax.swing.text.Position.Bias.Forward)
                                .getBounds();
                height = Math.max(height, end.y + end.height);
            } catch (javax.swing.text.BadLocationException e) {
                throw new IllegalStateException("Notice document changed during EDT layout", e);
            }
            return new Dimension(width, (int) Math.ceil(height) + insets.top + insets.bottom);
        }

        @Override
        public Dimension getPreferredSize() {
            int width =
                    getParent() instanceof JViewport viewport
                            ? viewport.getExtentSize().width
                            : getWidth();
            if (allocatedWidth > 0) width = allocatedWidth;
            if (width <= 0 || getUI() == null) return super.getPreferredSize();
            Dimension measured = wrappedSize(width);
            if (getWidth() == width && getHeight() > 0) {
                try {
                    var end =
                            getUI().modelToView2D(
                                            this,
                                            Math.max(0, getDocument().getLength() - 1),
                                            javax.swing.text.Position.Bias.Forward);
                    if (end != null)
                        measured.height =
                                Math.max(
                                        measured.height,
                                        (int) Math.ceil(end.getMaxY()) + getInsets().bottom);
                } catch (javax.swing.text.BadLocationException e) {
                    throw new IllegalStateException("Notice document changed during EDT layout", e);
                }
            }
            return measured;
        }
    }

    /** BorderLayout still owns positioning; measure the north row before allocating its bounds. */
    static final class HostLayout extends BorderLayout {
        HostLayout() {
            super(4, 4);
        }

        private void prepare(java.awt.Container parent, boolean allocating) {
            if (!(getLayoutComponent(NORTH) instanceof BridgeOperatorNotice notice)) return;
            java.awt.Insets insets = parent.getInsets();
            int width = Math.max(1, parent.getWidth() - insets.left - insets.right);
            var viewport = (JViewport) SwingUtilities.getAncestorOfClass(JViewport.class, parent);
            int height =
                    viewport != null && viewport.getExtentSize().height > 0
                            ? viewport.getExtentSize().height
                            : allocating
                                    ? Math.max(1, parent.getHeight() - insets.top - insets.bottom)
                                    : Integer.MAX_VALUE;
            java.awt.Component tabs = getLayoutComponent(CENTER);
            int reserve = tabs == null ? 0 : Math.min(tabs.getPreferredSize().height, height / 2);
            notice.layoutWidth = width;
            java.awt.Insets border = notice.details.getInsets();
            notice.message.allocatedWidth =
                    Math.max(
                            1,
                            width
                                    - border.left
                                    - border.right
                                    - notice.details
                                            .getVerticalScrollBar()
                                            .getPreferredSize()
                                            .width);
            notice.heightBudget = Math.max(1, height - reserve - getVgap());
        }

        @Override
        public Dimension preferredLayoutSize(java.awt.Container parent) {
            prepare(parent, false);
            return super.preferredLayoutSize(parent);
        }

        @Override
        public Dimension minimumLayoutSize(java.awt.Container parent) {
            // Bookmap's horizontal-only GridBag host falls back to minimum sizes when the
            // form is narrower than its natural width. Keep its complete page scrollable.
            Dimension preferred = preferredLayoutSize(parent);
            return new Dimension(0, preferred.height);
        }

        @Override
        public void layoutContainer(java.awt.Container parent) {
            // Use visible host height, not the potentially huge outer scrolling page height.
            prepare(parent, true);
            super.layoutContainer(parent);
        }
    }

    private final Timer timer;
    private Notice displayed;

    BridgeOperatorNotice(Supplier<Notice> source) {
        super(new BorderLayout(4, 4));
        this.source = source;
        WrappingText review = new WrappingText();
        review.setText(REVIEW);
        review.setFont(UIManager.getFont("Label.font"));
        review.setOpaque(false);
        review.putClientProperty("orderflow.navigation", true);
        heading.add(review, BorderLayout.NORTH);
        summary.setFont(review.getFont());
        summary.setOpaque(false);
        summary.getAccessibleContext().setAccessibleName("Linux bridge critical status");
        heading.add(summary, BorderLayout.CENTER);
        add(heading, BorderLayout.NORTH);
        message.setFont(review.getFont());
        message.setMargin(new java.awt.Insets(4, 4, 4, 4));
        message.getAccessibleContext()
                .setAccessibleName("Linux bridge complete instructions and diagnostics");
        details.putClientProperty("orderflow.navigation", true);
        details.getAccessibleContext().setAccessibleName("Scrollable complete Linux bridge notice");
        add(details, BorderLayout.CENTER);
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

    private int assignedWidth() {
        if (layoutWidth > 0 && getParent() != null && getParent().getWidth() > 0) {
            java.awt.Insets insets = getParent().getInsets();
            return Math.max(1, getParent().getWidth() - insets.left - insets.right);
        }
        return getWidth() > 0 ? getWidth() : 520;
    }

    private int headingHeight(int width) {
        var review = (WrappingText) heading.getComponent(0);
        return review.wrappedSize(width).height + 4 + summary.wrappedSize(width).height;
    }

    @Override
    public Dimension getPreferredSize() {
        int width = assignedWidth();
        java.awt.Insets border = details.getInsets();
        // Reserve scrollbar width during measurement so its appearance cannot cause a
        // wrap/height oscillation. The viewport still fills all available width.
        int textWidth =
                Math.max(
                        1,
                        width
                                - border.left
                                - border.right
                                - details.getVerticalScrollBar().getPreferredSize().width);
        message.allocatedWidth = textWidth;
        int natural =
                headingHeight(width)
                        + 4
                        + border.top
                        + border.bottom
                        + message.wrappedSize(textWidth).height;
        return new Dimension(0, Math.max(getMinimumSize().height, Math.min(natural, heightBudget)));
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(
                0,
                headingHeight(assignedWidth())
                        + 4
                        + details.getInsets().top
                        + details.getInsets().bottom
                        + 2 * message.getFontMetrics(message.getFont()).getHeight());
    }

    private static String critical(Notice notice) {
        if (notice.state().contains("INVALID"))
            return "Delivery is not proven. Starting a receiver cannot restore lost events.";
        if (notice.state().equals("WAITING FOR RECEIVER"))
            return "Receiver unavailable. Finite retention can fill and invalidate delivery.";
        if (notice.state().equals("RECOVERABLE DISCONNECT"))
            return "Restore the same receiver's connection; do not start a second receiver.";
        if (notice.warning()) return "Retention warning: further backlog can invalidate delivery.";
        return "Archive validity is separate; network ACK is not durable storage.";
    }

    private static double luminance(java.awt.Color color) {
        double value = 0;
        double[] weights = {.2126, .7152, .0722};
        int[] rgb = {color.getRed(), color.getGreen(), color.getBlue()};
        for (int i = 0; i < 3; i++) {
            double c = rgb[i] / 255.0;
            value += weights[i] * (c <= .04045 ? c / 12.92 : Math.pow((c + .055) / 1.055, 2.4));
        }
        return value;
    }

    static double contrast(java.awt.Color a, java.awt.Color b) {
        double x = luminance(a), y = luminance(b);
        return (Math.max(x, y) + .05) / (Math.min(x, y) + .05);
    }

    private java.awt.Color foreground(Notice notice, java.awt.Color background) {
        boolean dark = luminance(background) < .179;
        java.awt.Color candidate =
                notice.warning()
                        ? (notice.state().contains("INVALID")
                                ? (dark
                                        ? new java.awt.Color(255, 180, 180)
                                        : new java.awt.Color(140, 20, 20))
                                : (dark
                                        ? new java.awt.Color(255, 215, 140)
                                        : new java.awt.Color(100, 50, 0)))
                        : UIManager.getColor("Label.foreground");
        if (candidate == null || contrast(candidate, background) < 4.5)
            candidate = dark ? java.awt.Color.WHITE : java.awt.Color.BLACK;
        return candidate;
    }

    private void updateColors() {
        if (displayed == null) return;
        java.awt.Color background = getBackground();
        heading.setBackground(background);
        message.setBackground(background);
        details.getViewport().setBackground(background);
        heading.getComponent(0)
                .setForeground(foreground(new Notice("REVIEW", "", false), background));
        summary.setForeground(foreground(displayed, background));
        message.setForeground(foreground(displayed, background));
    }

    @Override
    public void doLayout() {
        // Theme/font/host resize changes are resolved on the EDT as part of ordinary layout.
        updateColors();
        super.doLayout();
    }

    void refreshNow() {
        Notice next =
                source.get(); // Volatile fields only: no network, disk, waits or JSON parsing.
        if (!next.equals(displayed)) {
            displayed = next;
            summary.setText("Linux bridge — " + next.state() + "\n" + critical(next));
            summary.setCaretPosition(0);
            message.setText(next.message());
            message.setCaretPosition(0);
            details.getViewport().setViewPosition(new java.awt.Point());
            updateColors();
            revalidate();
            repaint();
        }
    }

    JTextArea completeText() {
        return message;
    }

    JTextArea criticalText() {
        return summary;
    }

    JScrollPane detailsScroll() {
        return details;
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
                            + " Restore the connection for that existing receiver; do not launch a"
                            + " second receiver. A receiver restart requires a fresh exporter"
                            + " session in a safe window. Archive validity is separate.",
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
