package com.limacharlie.orderflow;

import java.awt.*;

import javax.swing.*;

/** Preserve the original equal-height settings rows except for the wrapping network row. */
final class BridgeConfigurationLayout {
    /** A long network label must fit the host width even with larger system fonts. */
    static final class NetworkLabel extends JLabel {
        private final String plainText;

        NetworkLabel(String text) {
            super("<html>" + text + "</html>");
            plainText = text;
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension natural = super.getPreferredSize();
            Container row = getParent();
            if (row == null || row.getParent() == null) return natural;
            int available = row.getParent().getWidth();
            if (available <= 0) return natural;
            FlowLayout flow = (FlowLayout) row.getLayout();
            Insets insets = row.getInsets();
            available = Math.max(1, available - insets.left - insets.right - 2 * flow.getHgap());
            int naturalWidth =
                    plainText == null
                            ? natural.width
                            : getFontMetrics(getFont()).stringWidth(plainText);
            int width = Math.min(naturalWidth, available);
            var view =
                    (javax.swing.text.View)
                            getClientProperty(javax.swing.plaf.basic.BasicHTML.propertyKey);
            view.setSize(width, 0);
            return new Dimension(
                    width, (int) Math.ceil(view.getPreferredSpan(javax.swing.text.View.Y_AXIS)));
        }
    }

    static final class NetworkRow extends JPanel {
        private final java.util.function.IntSupplier layoutWidth;

        NetworkRow() { this(null); }

        NetworkRow(java.util.function.IntSupplier layoutWidth) {
            super(new FlowLayout(FlowLayout.LEFT));
            this.layoutWidth = layoutWidth;
        }

        Dimension singleLineSize() {
            return getLayout().preferredLayoutSize(this);
        }

        @Override
        public Dimension getMinimumSize() {
            return new Dimension(0, getPreferredSize().height);
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension natural = singleLineSize();
            int width = layoutWidth == null
                    ? (getParent() == null ? getWidth() : getParent().getWidth())
                    : layoutWidth.getAsInt();
            if (getParent() != null
                    && getParent().getParent() instanceof JViewport viewport
                    && viewport.getWidth() > 0) width = viewport.getWidth();
            if (width <= 0) return natural;
            FlowLayout flow = (FlowLayout) getLayout();
            Insets insets = getInsets();
            int available = Math.max(1, width - insets.left - insets.right - 2 * flow.getHgap());
            int used = 0, lineHeight = 0, height = insets.top + insets.bottom + 2 * flow.getVgap();
            for (Component child : getComponents()) {
                if (!child.isVisible()) continue;
                Dimension size = child.getPreferredSize();
                int gap = used == 0 ? 0 : flow.getHgap();
                if (used > 0 && used + gap + size.width > available) {
                    height += lineHeight + flow.getVgap();
                    used = 0;
                    lineHeight = 0;
                    gap = 0;
                }
                used += gap + size.width;
                lineHeight = Math.max(lineHeight, size.height);
            }
            return new Dimension(natural.width, height + lineHeight);
        }
    }

    static final class Fields extends JPanel implements Scrollable {
        Fields() {
            super(new Rows());
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            Dimension natural = getPreferredSize();
            var metrics = getFontMetrics(getFont());
            // Bookmap gives plugin rows horizontal fill only. A full-form preferred height
            // forces its host into minimum-size layout; use a scrollable viewport instead.
            return new Dimension(
                    0, // Host supplies width; long labels must not force minimum-size fallback.
                    Math.min(natural.height, metrics.getHeight() * 12));
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle r, int axis, int direction) {
            return 20;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle r, int axis, int direction) {
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

    private static final class Rows extends GridBagLayout {
        private void prepare(Container parent) {
            Component[] children = parent.getComponents();
            int baseline = 0;
            for (Component child : children) {
                // Multirow discovery controls must not enlarge every existing settings row.
                if (child instanceof LanBindControls) continue;
                Dimension size =
                        child instanceof NetworkRow row
                                ? row.singleLineSize()
                                : child.getPreferredSize();
                baseline = Math.max(baseline, size.height);
            }
            rowHeights = new int[children.length];
            for (int i = 0; i < children.length; i++) {
                int gap = i == children.length - 1 ? 0 : 4;
                rowHeights[i] = (children[i] instanceof LanBindControls
                        ? Math.max(baseline, children[i].getPreferredSize().height) : baseline) + gap;
                GridBagConstraints constraints = new GridBagConstraints();
                constraints.gridx = 0;
                constraints.gridy = i;
                constraints.weightx = 1;
                // Keep the discovery explanation at its measured wrapped height.
                constraints.weighty = children[i] instanceof LanBindControls ? 0 : 1;
                constraints.fill = GridBagConstraints.BOTH;
                constraints.insets = new Insets(0, 0, gap, 0);
                setConstraints(children[i], constraints);
            }
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            prepare(parent);
            return super.preferredLayoutSize(parent);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            prepare(parent);
            return super.minimumLayoutSize(parent);
        }

        @Override
        public void layoutContainer(Container parent) {
            prepare(parent);
            super.layoutContainer(parent);
        }
    }
}
