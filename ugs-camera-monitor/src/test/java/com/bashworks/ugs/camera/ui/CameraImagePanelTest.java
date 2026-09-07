package com.bashworks.ugs.camera.ui;

import org.junit.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.Assert.assertEquals;

public class CameraImagePanelTest {
    @Test
    public void leavesTheExactCrosshairCenterPixelVisible() {
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

        assertEquals(Color.WHITE.getRGB(), rendered.getRGB(50, 50));
        assertEquals(Color.RED.getRGB(), rendered.getRGB(50, 49));
        assertEquals(Color.RED.getRGB(), rendered.getRGB(51, 50));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnsupportedZoom() {
        new CameraImagePanel().setZoom(4);
    }
}
