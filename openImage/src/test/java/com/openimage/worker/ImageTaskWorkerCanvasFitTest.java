package com.openimage.worker;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ImageTaskWorkerCanvasFitTest {
    @Test
    void portraitResultKeepsTopAndBottomWhenFittedToSquare() {
        BufferedImage source = new BufferedImage(100, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = source.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 100, 200);
        g.setColor(Color.RED);
        g.fillRect(20, 0, 60, 12);
        g.setColor(Color.GREEN);
        g.fillRect(20, 188, 60, 12);
        g.dispose();

        BufferedImage output = ImageTaskWorker.containResize(source, 200, 200);

        assertEquals(200, output.getWidth());
        assertEquals(200, output.getHeight());
        assertEquals(Color.RED.getRGB(), output.getRGB(100, 2));
        assertEquals(Color.GREEN.getRGB(), output.getRGB(100, 197));
    }

    @Test
    void landscapeResultKeepsLeftAndRightWhenFittedToSquare() {
        BufferedImage source = new BufferedImage(200, 100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = source.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 200, 100);
        g.setColor(Color.RED);
        g.fillRect(0, 20, 12, 60);
        g.setColor(Color.GREEN);
        g.fillRect(188, 20, 12, 60);
        g.dispose();

        BufferedImage output = ImageTaskWorker.containResize(source, 200, 200);

        assertEquals(Color.RED.getRGB(), output.getRGB(2, 100));
        assertEquals(Color.GREEN.getRGB(), output.getRGB(197, 100));
    }
}
