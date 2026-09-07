package com.bashworks.ugs.camera.ui;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.image.BufferedImage;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;

public final class CameraImagePanel extends JPanel {
    private volatile BufferedImage image;
    private volatile boolean frozen;
    private volatile int zoom = 1;
    private volatile CameraRotation rotation = CameraRotation.DEG_0;

    public CameraImagePanel() {
        setBackground(new Color(25, 25, 25));
    }

    public void setFrame(BufferedImage frame) {
        if (!frozen) {
            image = frame;
            repaint();
        }
    }

    public void setFrozen(boolean frozen) {
        this.frozen = frozen;
        repaint();
    }

    public void setZoom(int zoom) {
        if (zoom < 1 || zoom > 3) throw new IllegalArgumentException("Zoom must be 1, 2, or 3");
        this.zoom = zoom;
        repaint();
    }

    public void setRotation(CameraRotation rotation) {
        this.rotation = rotation == null ? CameraRotation.DEG_0 : rotation;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            BufferedImage frame = image;
            if (frame == null) {
                drawCentered(g, "Select a camera and click Start camera", Color.LIGHT_GRAY);
                return;
            }

            CameraRotation activeRotation = rotation;
            int rotatedWidth = activeRotation.swapsAxes() ? frame.getHeight() : frame.getWidth();
            int rotatedHeight = activeRotation.swapsAxes() ? frame.getWidth() : frame.getHeight();
            double baseScale = Math.min((double) getWidth() / rotatedWidth, (double) getHeight() / rotatedHeight);
            int width = (int) Math.round(rotatedWidth * baseScale);
            int height = (int) Math.round(rotatedHeight * baseScale);
            int left = (getWidth() - width) / 2;
            int top = (getHeight() - height) / 2;

            Shape previousClip = g.getClip();
            g.clip(new Rectangle2D.Double(left, top, width, height));
            AffineTransform transform = new AffineTransform();
            transform.translate(getWidth() / 2.0, getHeight() / 2.0);
            transform.scale(baseScale * zoom, baseScale * zoom);
            transform.rotate(activeRotation.radians());
            transform.translate(-frame.getWidth() / 2.0, -frame.getHeight() / 2.0);
            g.drawImage(frame, transform, null);
            g.setClip(previousClip);

            int cx = getWidth() / 2;
            int cy = getHeight() / 2;
            g.setColor(Color.RED);
            g.setStroke(new BasicStroke(1.0f));
            g.drawLine(cx, top, cx, cy - 2);
            g.drawLine(cx, cy + 2, cx, top + height);
            g.drawLine(left, cy, cx - 2, cy);
            g.drawLine(cx + 2, cy, left + width, cy);
            g.drawOval(cx - 12, cy - 12, 24, 24);

            if (frozen) {
                g.setColor(new Color(180, 0, 0, 210));
                g.fillRoundRect(8, 8, 105, 25, 8, 8);
                g.setColor(Color.WHITE);
                g.drawString("FROZEN", 18, 25);
            }
        } finally {
            g.dispose();
        }
    }

    private void drawCentered(Graphics2D g, String text, Color color) {
        FontMetrics metrics = g.getFontMetrics();
        g.setColor(color);
        g.drawString(text, Math.max(8, (getWidth() - metrics.stringWidth(text)) / 2), getHeight() / 2);
    }
}
