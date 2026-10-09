package com.limacharlie.orderflow;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;

import javax.swing.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;

/**
 * Prototype tests for {@link BridgeRecoveryControls}. No native DPI; font scaling is applied
 * recursively to actual components. No background threads, no sleep.
 */
class BridgeRecoveryControlsTest {

    private static void invoke(Runnable r) {
        try {
            SwingUtilities.invokeAndWait(r);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void checkboxTogglingIsReversible() {
        invoke(
                () -> {
                    BridgeRecoveryControls p = new BridgeRecoveryControls(true);
                    assertTrue(p.bridgeCheckbox().isSelected());
                    assertFalse(p.pending());
                    p.bridgeCheckbox().setSelected(false);
                    assertFalse(p.bridgeEnabled());
                    assertTrue(p.pending());
                    p.bridgeCheckbox().setSelected(true);
                    assertTrue(p.bridgeEnabled());
                    assertFalse(p.pending());
                });
    }

    @Test
    void buttonDoClickStagesArchiveOnly() {
        invoke(
                () -> {
                    BridgeRecoveryControls p = new BridgeRecoveryControls(true);
                    p.archiveOnlyButton().doClick();
                    assertFalse(p.bridgeCheckbox().isSelected());
                    assertFalse(p.bridgeEnabled());
                    assertTrue(p.pending());
                });
    }

    @Test
    void baselineFalseEnableThenButtonResetsPending() {
        invoke(
                () -> {
                    BridgeRecoveryControls p = new BridgeRecoveryControls(false);
                    assertFalse(p.bridgeEnabled());
                    assertFalse(p.pending());
                    p.bridgeCheckbox().setSelected(true);
                    assertTrue(p.bridgeEnabled());
                    assertTrue(p.pending());
                    p.archiveOnlyButton().doClick();
                    assertFalse(p.bridgeEnabled());
                    assertFalse(p.pending());
                });
    }

    @Test
    void freshControlsFromSavedFalseClearsPending() {
        invoke(
                () -> {
                    BridgeRecoveryControls p = new BridgeRecoveryControls(false);
                    assertFalse(p.pending());
                    assertEquals(
                            "No pending bridge preference change, current runtime state is"
                                    + " separate.",
                            p.pendingText().getText());
                });
    }

    @Test
    void independentPanelsDoNotShareState() {
        invoke(
                () -> {
                    BridgeRecoveryControls a = new BridgeRecoveryControls(true);
                    BridgeRecoveryControls b = new BridgeRecoveryControls(false);
                    a.bridgeCheckbox().setSelected(false);
                    assertFalse(a.bridgeEnabled());
                    assertTrue(a.pending());
                    assertFalse(b.bridgeEnabled());
                    assertFalse(b.pending());
                });
    }

    @Test
    void keyboardMnemonicAndAccessibleContext() {
        invoke(
                () -> {
                    BridgeRecoveryControls p = new BridgeRecoveryControls(true);
                    assertEquals(KeyEvent.VK_O, p.archiveOnlyButton().getMnemonic());
                    assertNotNull(p.archiveOnlyButton().getAccessibleContext());
                    assertEquals(
                            "Archive only on next Apply",
                            p.archiveOnlyButton().getAccessibleContext().getAccessibleName());
                    assertNotNull(p.bridgeCheckbox().getAccessibleContext());
                    assertNotNull(p.pendingText().getAccessibleContext());
                });
    }

    @Test
    void pendingTextDistinguishesSessionAndInvalid() {
        invoke(
                () -> {
                    BridgeRecoveryControls p = new BridgeRecoveryControls(true);
                    p.bridgeCheckbox().setSelected(false);
                    String t = p.pendingText().getText();
                    assertTrue(t.contains("DISABLED"));
                    assertTrue(t.contains("current session unchanged"));
                    assertTrue(t.contains("INVALID delivery cannot be repaired"));
                    assertTrue(t.contains("Apply restarts exporter"));
                });
    }

    @Test
    void geometryAndRenderAtMultipleWidthsAndFontScales() {
        int[] widths = {280, 520};
        int[] heights = {420, 600};
        double[] scales = {1.0, 1.25, 1.5};
        for (double scale : scales) {
            for (int w : widths) {
                for (int h : heights) {
                    invoke(
                            () -> {
                                BridgeRecoveryControls p = new BridgeRecoveryControls(true);
                                p.bridgeCheckbox().setSelected(false);
                                BridgeConfigurationLayoutTest.fonts(p, scale);

                                p.setBounds(0, 0, w, h);
                                p.doLayout();
                                for (Component c : p.getComponents()) {
                                    c.doLayout();
                                }

                                BufferedImage img =
                                        new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                                Graphics2D g2 = img.createGraphics();
                                g2.setColor(p.getBackground());
                                g2.fillRect(0, 0, w, h);
                                g2.setRenderingHint(
                                        RenderingHints.KEY_TEXT_ANTIALIASING,
                                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                                p.paint(g2);
                                g2.dispose();
                                String preview =
                                        System.getProperty("orderflow.recovery.preview.dir");
                                if (preview != null) {
                                    try {
                                        var path = java.nio.file.Path.of(preview);
                                        java.nio.file.Files.createDirectories(path);
                                        javax.imageio.ImageIO.write(
                                                img,
                                                "png",
                                                path.resolve(
                                                                "recovery-"
                                                                        + w
                                                                        + "-"
                                                                        + h
                                                                        + "-"
                                                                        + scale
                                                                        + ".png")
                                                        .toFile());
                                    } catch (java.io.IOException e) {
                                        throw new AssertionError(e);
                                    }
                                }

                                Rectangle cb = p.bridgeCheckbox().getBounds();
                                assertTrue(cb.x >= 0 && cb.y >= 0, "checkbox origin >= 0");
                                assertTrue(cb.x + cb.width <= w, "checkbox right <= host width");
                                assertTrue(cb.y + cb.height <= h, "checkbox bottom <= host height");

                                Rectangle btn = p.archiveOnlyButton().getBounds();
                                assertTrue(btn.x >= 0 && btn.y >= 0, "button origin >= 0");
                                assertTrue(btn.x + btn.width <= w, "button right <= host width");
                                assertTrue(btn.y + btn.height <= h, "button bottom <= host height");

                                JTextArea ta = p.pendingText();
                                Document doc = ta.getDocument();
                                int len = doc.getLength();
                                assertTrue(len > 0, "text area has content");
                                try {
                                    Shape shape = ta.modelToView2D(len - 1);
                                    assertNotNull(shape, "terminal glyph shape non-null");
                                    Rectangle2D sb = shape.getBounds2D();
                                    Rectangle visible = ta.getVisibleRect();
                                    assertTrue(
                                            visible.contains(sb),
                                            "terminal glyph fully visible: "
                                                    + sb
                                                    + " / "
                                                    + visible);
                                } catch (BadLocationException e) {
                                    throw new AssertionError(
                                            "BadLocationException in modelToView2D", e);
                                }
                            });
                }
            }
        }
    }

    @Test
    void actionStripStaysVisibleWhileSyntheticContentScrolls() {
        invoke(
                () -> {
                    for (int width : new int[] {280, 520}) {
                        var controls = new BridgeRecoveryControls(true);
                        controls.archiveOnlyButton().doClick();
                        var host = new JPanel(new BorderLayout());
                        host.add(controls, BorderLayout.NORTH);
                        var content = new JTextArea("Synthetic configuration\n".repeat(100));
                        var scroll = new JScrollPane(content);
                        host.add(scroll, BorderLayout.CENTER);
                        host.setSize(width, 500);
                        for (int i = 0; i < 3; i++) BridgeConfigurationLayoutTest.layout(host);
                        var before = controls.getBounds();
                        scroll.getVerticalScrollBar()
                                .setValue(scroll.getVerticalScrollBar().getMaximum());
                        assertEquals(before, controls.getBounds());
                        assertTrue(new Rectangle(0, 0, width, 500).contains(before));
                        assertTrue(scroll.getViewport().getViewPosition().y > 0);
                        try {
                            assertTrue(
                                    controls.pendingText()
                                            .getVisibleRect()
                                            .contains(
                                                    controls.pendingText()
                                                            .modelToView2D(
                                                                    controls.pendingText()
                                                                                    .getDocument()
                                                                                    .getLength()
                                                                            - 1)));
                        } catch (BadLocationException e) {
                            throw new AssertionError(e);
                        }
                        controls.archiveOnlyButton().doClick();
                        assertFalse(controls.bridgeEnabled());
                    }
                });
    }

    @Test
    void selectionAndKeyboardActionKeepCompleteExplanationsAccessible() {
        invoke(
                () -> {
                    var controls = new BridgeRecoveryControls(true);
                    var button = controls.archiveOnlyButton();
                    button.getActionMap()
                            .get("pressed")
                            .actionPerformed(new java.awt.event.ActionEvent(button, 0, "pressed"));
                    button.getActionMap()
                            .get("released")
                            .actionPerformed(new java.awt.event.ActionEvent(button, 0, "released"));
                    assertFalse(controls.bridgeEnabled());
                    assertTrue(controls.pending());
                    var text = controls.pendingText();
                    text.selectAll();
                    assertEquals(text.getText(), text.getSelectedText());
                    assertEquals(
                            text.getDocument().getLength(),
                            text.getAccessibleContext().getAccessibleText().getCharCount());
                });
    }

    @Test
    void oneHostResizesAndTransitionsWithWholeButtonLabelsVisible() {
        invoke(
                () -> {
                    var controls = new BridgeRecoveryControls(true);
                    BridgeConfigurationLayoutTest.fonts(controls, 1.5);
                    for (int width : new int[] {280, 520, 280}) {
                        for (boolean selected : new boolean[] {false, true, false}) {
                            controls.bridgeCheckbox().setSelected(selected);
                            controls.setSize(width, 500);
                            for (int pass = 0; pass < 3; pass++)
                                BridgeConfigurationLayoutTest.layout(controls);
                            var text = controls.pendingText();
                            try {
                                assertTrue(
                                        text.getVisibleRect()
                                                .contains(
                                                        text.modelToView2D(
                                                                text.getDocument().getLength()
                                                                        - 1)));
                            } catch (BadLocationException e) {
                                throw new AssertionError(e);
                            }
                            var button = controls.archiveOnlyButton();
                            var view =
                                    (javax.swing.text.View)
                                            button.getClientProperty(
                                                    javax.swing.plaf.basic.BasicHTML.propertyKey);
                            assertTrue(
                                    view.getPreferredSpan(javax.swing.text.View.X_AXIS)
                                            <= button.getWidth()
                                                    - button.getInsets().left
                                                    - button.getInsets().right);
                            assertTrue(
                                    view.getPreferredSpan(javax.swing.text.View.Y_AXIS)
                                            <= button.getHeight()
                                                    - button.getInsets().top
                                                    - button.getInsets().bottom);
                        }
                    }
                });
    }
}
