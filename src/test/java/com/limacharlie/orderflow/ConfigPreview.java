package com.limacharlie.orderflow;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

import javax.imageio.ImageIO;
import javax.swing.*;

/** Optional local visual inspection at narrow host-panel width. */
public final class ConfigPreview {
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    try {
                        JPanel panel =
                                BookmapOrderflowExporter.buildConfigurationPanel(
                                        new BookmapOrderflowExporter.Settings(), null);
                        panel.setSize(240, 380);
                        ConfigLayoutTest.layout(panel);
                        BufferedImage image =
                                new BufferedImage(500, 410, BufferedImage.TYPE_INT_RGB);
                        Graphics2D g = image.createGraphics();
                        g.setColor(Color.WHITE);
                        g.fillRect(0, 0, 500, 410);
                        g.setColor(Color.BLACK);
                        g.drawString("Top of narrow panel", 10, 16);
                        g.drawString("Scroll to bridge settings", 260, 16);
                        g.translate(0, 25);
                        panel.printAll(g);
                        g.translate(260, 0);
                        JScrollPane scroll = ConfigLayoutTest.find(panel, JScrollPane.class);
                        scroll.getVerticalScrollBar()
                                .setValue(scroll.getVerticalScrollBar().getMaximum());
                        ConfigLayoutTest.layout(panel);
                        panel.printAll(g);
                        g.dispose();
                        ImageIO.write(image, "png", Path.of(args[0]).toFile());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
    }
}
