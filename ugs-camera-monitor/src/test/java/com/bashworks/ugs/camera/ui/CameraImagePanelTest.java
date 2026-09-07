package com.bashworks.ugs.camera.ui;

import org.junit.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.Assert.assertEquals;

public class CameraImagePanelTest {
    @Test
    public void leavesAThreeByThreeCrosshairCenterVisible() {
        CameraImagePanel panel = new CameraImagePanel();
        panel.setSize(101, 101);

        BufferedImage cameraFrame = new BufferedImage(101, 101, BufferedImage.TYPE_INT_RGB);
        Graphics2D frameGraphics = cameraFrame.createGraphics();
        frameGraphics.setColor(Color.WHITE);
        frameGraphics.fillRect(0, 0, 101, 101);
        frameGraphics.dispose();
        panel.setFrame(cameraFrame);

        BufferedImage rendered = new BufferedImage(101, 101, BufferedImage.TYPE_INT_RGB);
        Graphics2D renderedGraphics = rendered.createGraphics();
        panel.paint(renderedGraphics);
        renderedGraphics.dispose();

        for (int y = 49; y <= 51; y++) {
            for (int x = 49; x <= 51; x++) {
                assertEquals(Color.WHITE.getRGB(), rendered.getRGB(x, y));
            }
        }
        assertEquals(Color.RED.getRGB(), rendered.getRGB(50, 48));
        assertEquals(Color.RED.getRGB(), rendered.getRGB(52, 50));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnsupportedZoom() {
        new CameraImagePanel().setZoom(4);
    }
}
