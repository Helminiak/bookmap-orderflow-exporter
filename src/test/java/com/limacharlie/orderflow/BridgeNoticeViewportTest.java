package com.limacharlie.orderflow;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;
import javax.swing.*;

/** Assigned host bounds + painted Swing viewport/glyph positions, not preferred-size assertions. */
class BridgeNoticeViewportTest {
    private static BridgeOperatorNotice.Notice[] states() {
        return new BridgeOperatorNotice.Notice[] {
            BridgeOperatorNotice.disabled(),
            BridgeOperatorNotice.describe(
                    new LiveBridge.OperatorSnapshot(
                            "WAITING_RECEIVER", false, "", false, 0, 80, 100, 100)),
            BridgeOperatorNotice.describe(
                    new LiveBridge.OperatorSnapshot("CONNECTED", false, "", true, 50, 2, 100, 100)),
            BridgeOperatorNotice.describe(
                    new LiveBridge.OperatorSnapshot(
                            "DISCONNECTED", false, "", true, 50, 50, 100, 100)),
            BridgeOperatorNotice.failure(
                    "SYNTHETIC long diagnostic ".repeat(100) + " END-OF-DIAGNOSTIC"),
            BridgeOperatorNotice.describe(
                    new LiveBridge.OperatorSnapshot(
                            "INVALID",
                            true,
                            "SYNTHETIC overflow detail ".repeat(100),
                            true,
                            50,
                            0,
                            100,
                            100)),
            BridgeOperatorNotice.describe(
                    new LiveBridge.OperatorSnapshot("CONNECTED", false, "", true, 0, 0, 100, 100))
        };
    }

    private static JPanel host(BridgeOperatorNotice notice) {
        JPanel root = new JPanel(new BridgeOperatorNotice.HostLayout());
        root.add(notice, BorderLayout.NORTH);
        root.add(
                BookmapOrderflowExporter.buildExporterTabs(
                        new BookmapOrderflowExporter.Settings(), null, null),
                BorderLayout.CENTER);
        return root;
    }

    private static BufferedImage render(JPanel root, int width, int height) {
        root.setSize(width, height);
        BridgeConfigurationLayoutTest.layout(root);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        root.printAll(g);
        g.dispose();
        return image;
    }

    private static void visibleEnd(JTextArea text) {
        try {
            var glyph = text.modelToView2D(text.getDocument().getLength() - 1);
            assertNotNull(glyph);
            assertTrue(
                    text.getVisibleRect().contains(glyph.getBounds()),
                    "last glyph not visible: glyph="
                            + glyph
                            + " viewport="
                            + text.getVisibleRect()
                            + " font="
                            + text.getFont()
                            + " preferred="
                            + text.getPreferredSize()
                            + " size="
                            + text.getSize()
                            + " insets="
                            + text.getInsets());
        } catch (javax.swing.text.BadLocationException e) {
            throw new AssertionError(e);
        }
    }

    private static void allTextReachable(BridgeOperatorNotice notice) {
        JTextArea text = notice.completeText();
        JScrollPane scroll = notice.detailsScroll();
        String expected = notice.displayed().message();
        assertEquals(expected, text.getText());
        assertEquals(
                expected.length(), text.getAccessibleContext().getAccessibleText().getCharCount());
        text.selectAll();
        assertEquals(expected, text.getSelectedText(), "full notice remains selectable/copyable");
        assertEquals(expected.length(), text.getDocument().getLength());
        scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum());
        BridgeConfigurationLayoutTest.layout(scroll);
        visibleEnd(text);
        // Scrolling details must not scroll or conceal the critical status.
        assertEquals(0, notice.criticalText().getVisibleRect().y);
        visibleEnd(notice.criticalText());
    }

    @Test
    void actualViewportReachesAllStatesAtWidthsScalesAndDuringTransitions() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    for (double scale : new double[] {1, 1.25, 1.5}) {
                        AtomicReference<BridgeOperatorNotice.Notice> source =
                                new AtomicReference<>(states()[0]);
                        BridgeOperatorNotice notice = new BridgeOperatorNotice(source::get);
                        JPanel root = host(notice);
                        BridgeConfigurationLayoutTest.fonts(root, scale);
                        for (int width : new int[] {280, 400, 760, 280, 760}) {
                            for (var state : states()) {
                                source.set(state);
                                notice.refreshNow();
                                render(root, width, 500);
                                assertTrue(
                                        notice.detailsScroll().getViewport().getExtentSize().height
                                                > 0);
                                assertFalse(
                                        notice.detailsScroll()
                                                .getHorizontalScrollBar()
                                                .isVisible());
                                JTabbedPane tabs =
                                        ExporterTabsTest.find(root, JTabbedPane.class, null);
                                assertEquals(3, tabs.getTabCount());
                                assertTrue(tabs.getY() >= notice.getY() + notice.getHeight());
                                assertTrue(
                                        tabs.getHeight() > 0,
                                        "notice cannot consume all tab space");
                                assertTrue(tabs.getBoundsAt(0).height > 0);
                                assertEquals(state.state(), notice.displayed().state());
                                if (state.state().contains("INVALID"))
                                    assertTrue(
                                            notice.detailsScroll()
                                                    .getVerticalScrollBar()
                                                    .isVisible());
                                allTextReachable(notice);
                            }
                        }
                        notice.removeNotify();
                    }
                });
    }

    @Test
    void longTextScrollsInConstrainedHostAndExpandsWhenHostGrows() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    var notice = new BridgeOperatorNotice(() -> states()[4]);
                    JPanel root = host(notice);
                    render(root, 400, 380);
                    JScrollPane scroll = notice.detailsScroll();
                    assertTrue(
                            scroll.getVerticalScrollBar().isVisible(),
                            "old plain textarea had no recovery affordance");
                    int small = scroll.getViewport().getExtentSize().height;
                    allTextReachable(notice);
                    render(root, 400, 1100);
                    assertTrue(
                            scroll.getViewport().getExtentSize().height > small,
                            "message expands with actual available height");
                    allTextReachable(notice);
                    render(root, 760, 3500);
                    assertFalse(
                            scroll.getVerticalScrollBar().isVisible(),
                            "full text fits without scrolling when space permits");
                    visibleEnd(notice.completeText());
                    notice.removeNotify();
                });
    }

    @Test
    void actualBackgroundContrastIsAccessibleAcrossWarningStatesAndThemes() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    for (Color background :
                            new Color[] {
                                new Color(45, 45, 45), Color.WHITE, new Color(125, 125, 125)
                            }) {
                        var notice = new BridgeOperatorNotice(() -> states()[4]);
                        JPanel root = host(notice);
                        notice.setBackground(background);
                        render(root, 400, 500);
                        assertEquals(background, notice.completeText().getBackground());
                        assertEquals(
                                background,
                                notice.criticalText().getParent().getBackground(),
                                "contrast must use the actually painted opaque heading background");
                        assertTrue(
                                BridgeOperatorNotice.contrast(
                                                notice.completeText().getForeground(), background)
                                        >= 4.5);
                        assertTrue(
                                BridgeOperatorNotice.contrast(
                                                notice.criticalText().getForeground(), background)
                                        >= 4.5);
                        assertTrue(
                                notice.criticalText().getText().contains("ERROR / INVALID"),
                                "severity conveyed by text, not color alone");
                        notice.removeNotify();
                    }
                });
    }

    @Test
    void fractionalFontTransitionsMeasureThePaintedTerminalSummaryRow() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    var notice = new BridgeOperatorNotice(() -> states()[1]);
                    JPanel root = host(notice);
                    render(root, 760, 500);
                    for (float points : new float[] {11f, 13.75f, 16.5f, 21f, 11f}) {
                        Font font = new Font(Font.SANS_SERIF, Font.PLAIN, 11).deriveFont(points);
                        notice.criticalText().setFont(font);
                        notice.completeText().setFont(font);
                        render(root, 280, 500);
                        visibleEnd(notice.criticalText());
                        allTextReachable(notice);
                        render(root, 760, 500);
                        visibleEnd(notice.criticalText());
                    }
                    notice.removeNotify();
                });
    }

    @Test
    void outerBookmapViewportKeepsTabsAndEveryConfigurationRowReachable() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    for (double scale : new double[] {1, 1.25, 1.5}) {
                        var notice = new BridgeOperatorNotice(() -> states()[4]);
                        JPanel root = host(notice);
                        BridgeConfigurationLayoutTest.fonts(root, scale);
                        JPanel page =
                                new JPanel(new GridBagLayout()) {
                                    @Override
                                    public Dimension getPreferredSize() {
                                        Dimension d = super.getPreferredSize();
                                        if (getParent() instanceof JViewport v && v.getWidth() > 0)
                                            d.width = v.getWidth();
                                        return d;
                                    }
                                };
                        var c = new GridBagConstraints();
                        c.weightx = 1;
                        c.fill = GridBagConstraints.HORIZONTAL;
                        page.add(root, c);
                        JScrollPane outer =
                                new JScrollPane(
                                        page,
                                        JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                                        JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
                        outer.setSize(560, 460);
                        for (int pass = 0; pass < 3; pass++)
                            BridgeConfigurationLayoutTest.layout(outer);
                        BufferedImage image =
                                new BufferedImage(560, 460, BufferedImage.TYPE_INT_RGB);
                        Graphics2D g = image.createGraphics();
                        outer.printAll(g);
                        g.dispose();
                        var tabs = ExporterTabsTest.find(root, JTabbedPane.class, null);
                        Rectangle tabHeader =
                                SwingUtilities.convertRectangle(tabs, tabs.getBoundsAt(0), page);
                        assertTrue(
                                outer.getViewport().getViewRect().contains(tabHeader),
                                "tabs remain visible below a long notice");
                        allTextReachable(notice);
                        var apply =
                                ExporterTabsTest.find(
                                        root, JButton.class, "Apply settings / restart exporter");
                        Rectangle button =
                                SwingUtilities.convertRectangle(
                                        apply.getParent(), apply.getBounds(), page);
                        outer.getViewport()
                                .setViewPosition(
                                        new Point(
                                                0,
                                                Math.max(
                                                        0,
                                                        button.y
                                                                + button.height
                                                                - outer.getViewport()
                                                                        .getHeight())));
                        assertTrue(outer.getViewport().getViewRect().contains(button));
                        var fields =
                                ExporterTabsTest.find(
                                        root, BridgeConfigurationLayout.Fields.class, null);
                        for (Component row : fields.getComponents()) {
                            assertTrue(
                                    row.getY() + row.getHeight() <= fields.getHeight(),
                                    "no inaccessible/clipped field row");
                        }
                        notice.removeNotify();
                    }
                });
    }

    private static BufferedImage renderOuter(JScrollPane outer) {
        outer.setSize(520, 700);
        for (int i = 0; i < 3; i++) BridgeConfigurationLayoutTest.layout(outer);
        BufferedImage image = new BufferedImage(520, 700, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        outer.printAll(g);
        g.dispose();
        return image;
    }

    /** Realized local Swing frame only; not a native Bookmap acceptance test. */
    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        Files.createDirectories(out);
        SwingUtilities.invokeAndWait(
                () -> {
                    for (double scale : new double[] {1, 1.25, 1.5}) {
                        var notice = new BridgeOperatorNotice(() -> states()[4]);
                        JPanel root = host(notice);
                        BridgeConfigurationLayoutTest.fonts(root, scale);
                        notice.setBackground(new Color(45, 45, 45));
                        JPanel page =
                                new JPanel(new GridBagLayout()) {
                                    @Override
                                    public Dimension getPreferredSize() {
                                        Dimension d = super.getPreferredSize();
                                        if (getParent() instanceof JViewport v && v.getWidth() > 0)
                                            d.width = v.getWidth();
                                        return d;
                                    }
                                };
                        var c = new GridBagConstraints();
                        c.weightx = 1;
                        c.fill = GridBagConstraints.HORIZONTAL;
                        page.add(root, c);
                        JScrollPane outer =
                                new JScrollPane(
                                        page,
                                        JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                                        JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
                        JFrame frame =
                                GraphicsEnvironment.isHeadless()
                                        ? null
                                        : new JFrame("Synthetic Swing host — NOT Bookmap");
                        if (frame != null) {
                            frame.setContentPane(outer);
                            frame.setSize(540, 760);
                            frame.setVisible(true);
                            frame.validate();
                        }
                        try {
                            ImageIO.write(
                                    renderOuter(outer),
                                    "png",
                                    out.resolve("notice-dark-font" + scale + ".png").toFile());
                            allTextReachable(notice);
                            ImageIO.write(
                                    renderOuter(outer),
                                    "png",
                                    out.resolve("notice-dark-font" + scale + "-scrolled.png")
                                            .toFile());
                            var apply =
                                    ExporterTabsTest.find(
                                            root,
                                            JButton.class,
                                            "Apply settings / restart exporter");
                            var button =
                                    SwingUtilities.convertRectangle(
                                            apply.getParent(), apply.getBounds(), page);
                            outer.getViewport()
                                    .setViewPosition(
                                            new Point(
                                                    0,
                                                    Math.max(
                                                            0,
                                                            button.y
                                                                    + button.height
                                                                    - outer.getViewport()
                                                                            .getHeight())));
                            ImageIO.write(
                                    renderOuter(outer),
                                    "png",
                                    out.resolve("configuration-font" + scale + "-apply.png")
                                            .toFile());
                        } catch (java.io.IOException e) {
                            throw new RuntimeException(e);
                        } finally {
                            if (frame != null) frame.dispose();
                            notice.removeNotify();
                        }
                    }
                });
    }
}
