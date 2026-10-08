package com.limacharlie.orderflow;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;

import javax.imageio.ImageIO;
import javax.swing.*;

class BridgeConfigurationLayoutTest {
    static void fonts(Component component, double scale) {
        var originals = new java.util.IdentityHashMap<Component, Font>();
        java.util.function.Consumer<Component> collect =
                new java.util.function.Consumer<>() {
                    public void accept(Component item) {
                        if (item.getFont() != null) originals.put(item, item.getFont());
                        if (item instanceof Container c)
                            for (Component child : c.getComponents()) accept(child);
                    }
                };
        collect.accept(component);
        originals.forEach(
                (item, font) -> item.setFont(font.deriveFont((float) (font.getSize2D() * scale))));
    }

    static void layout(Container parent) {
        parent.doLayout();
        for (Component child : parent.getComponents()) if (child instanceof Container c) layout(c);
    }

    @Test
    void wrappedNetworkFitsWithoutMovingOtherRowsHorizontally() throws Exception {
        UIManager.setLookAndFeel(
                System.getProperty("os.name").startsWith("Windows")
                        ? UIManager.getSystemLookAndFeelClassName()
                        : UIManager.getCrossPlatformLookAndFeelClassName());
        SwingUtilities.invokeAndWait(
                () -> {
                    for (double scale : new double[] {1, 1.25, 1.5}) {
                        var fields = new BridgeConfigurationLayout.Fields();
                        fields.add(new JCheckBox("Live Linux bridge (optional)"));
                        fields.add(
                                new JCheckBox(
                                        "Responsive LIVE journal (overflow invalidates archive)"));
                        var network = new BridgeConfigurationLayout.NetworkRow();
                        network.add(new JLabel("Bind:"));
                        network.add(new JTextField("0.0.0.0", 15));
                        network.add(new JLabel("Market port:"));
                        network.add(new JSpinner(new SpinnerNumberModel(5555, 1, 65535, 1)));
                        network.add(new JLabel("Health port:"));
                        network.add(new JSpinner(new SpinnerNumberModel(5556, 1, 65535, 1)));
                        fields.add(network);
                        var capacity = new JPanel(new FlowLayout(FlowLayout.LEFT));
                        capacity.add(new JLabel("Unacknowledged bridge event capacity:"));
                        capacity.add(
                                new JSpinner(new SpinnerNumberModel(100000, 1, 5000000, 1000)));
                        fields.add(capacity);
                        fonts(fields, scale);
                        for (int width : new int[] {400, 560, 800}) {
                            fields.setSize(width, 1);
                            fields.setSize(width, fields.getPreferredSize().height);
                            layout(fields);
                            for (Component child : network.getComponents()) {
                                assertTrue(
                                        network.contains(child.getX(), child.getY()),
                                        "top outside network");
                                assertTrue(
                                        child.getX() + child.getWidth() <= network.getWidth(),
                                        "right clipped scale="
                                                + scale
                                                + " width="
                                                + width
                                                + " child="
                                                + child.getBounds()
                                                + " row="
                                                + network.getSize());
                                assertTrue(
                                        child.getY() + child.getHeight() <= network.getHeight(),
                                        "bottom clipped scale=" + scale);
                                assertEquals(
                                        child.getPreferredSize(),
                                        child.getSize(),
                                        "control size changed");
                            }
                            assertEquals(4, capacity.getY() - network.getY() - network.getHeight());
                            assertEquals(
                                    fields.getComponent(0).getHeight(),
                                    fields.getComponent(1).getHeight());
                            assertEquals(fields.getComponent(0).getHeight(), capacity.getHeight());
                            assertEquals(0, network.getX());
                            assertEquals(width, network.getWidth());
                            if (width == 560
                                    && System.getenv("ORDERFLOW_LAYOUT_PREVIEWS") != null) {
                                try {
                                    var image =
                                            new BufferedImage(
                                                    width,
                                                    fields.getHeight(),
                                                    BufferedImage.TYPE_INT_RGB);
                                    var graphics = image.createGraphics();
                                    fields.paint(graphics);
                                    graphics.dispose();
                                    var out = Path.of(System.getenv("ORDERFLOW_LAYOUT_PREVIEWS"));
                                    Files.createDirectories(out);
                                    ImageIO.write(
                                            image,
                                            "png",
                                            out.resolve("bridge-font-scale-" + scale + ".png")
                                                    .toFile());
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        }
                        var viewport = new JScrollPane(fields);
                        viewport.setSize(400, 80);
                        layout(viewport);
                        assertEquals(viewport.getViewport().getWidth(), fields.getWidth());
                        assertTrue(fields.getHeight() > viewport.getViewport().getHeight());
                        assertTrue(viewport.getVerticalScrollBar().isVisible());
                        for (Component child : network.getComponents())
                            assertTrue(
                                    child.getY() + child.getHeight() <= network.getHeight(),
                                    "network clipped in short viewport");
                    }
                });
    }
}
