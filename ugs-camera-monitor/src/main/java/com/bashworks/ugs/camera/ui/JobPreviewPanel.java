package com.bashworks.ugs.camera.ui;

import com.bashworks.ugs.camera.model.JobPreview;
import com.bashworks.ugs.camera.model.JobPreview.*;
import com.bashworks.ugs.camera.model.JobPreview.Point;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.util.Locale;
import java.util.function.Consumer;

/** Top-down XY view: +X right, +Y up. All geometry and picking use job mm. */
public final class JobPreviewPanel extends JPanel {
    private final JobPreview job;
    private final Path2D cuts = new Path2D.Double(), rapids = new Path2D.Double();
    private double centerX, centerY, scale = 1;
    private boolean snap = true, showRapids;
    private boolean fitted;
    private Pick selection, hover;
    private java.awt.Point drag;
    private Consumer<Pick> onSelection = p -> { };
    private Consumer<String> onHover = s -> { };

    public JobPreviewPanel(JobPreview job) {
        this.job = job;
        setBackground(new Color(23, 28, 35));
        setPreferredSize(new Dimension(880, 470));
        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        for (Trace trace : job.traces()) {
            Path2D path = trace.rapid() ? rapids : cuts;
            path.moveTo(trace.start().x(), trace.start().y());
            path.lineTo(trace.end().x(), trace.end().y());
        }
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mouseWheelMoved(MouseWheelEvent e) { zoom(Math.pow(1.2, -e.getPreciseWheelRotation()), e.getPoint()); }
            @Override public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e) || SwingUtilities.isMiddleMouseButton(e)) drag = e.getPoint();
            }
            @Override public void mouseReleased(MouseEvent e) { drag = null; }
            @Override public void mouseDragged(MouseEvent e) {
                if (drag == null) return;
                centerX -= (e.getX() - drag.x) / scale;
                centerY += (e.getY() - drag.y) / scale;
                drag = e.getPoint();
                repaint();
            }
            @Override public void mouseMoved(MouseEvent e) {
                Point cursor = toJob(e.getX(), e.getY());
                hover = job.pick(cursor, 10 / scale, snap);
                Point p = hover == null ? cursor : hover.point();
                onHover.accept(String.format(Locale.US, "X %.4f   Y %.4f mm%s", p.x(), p.y(),
                        hover == null ? "" : "  ·  " + hover.label()));
                repaint();
            }
            @Override public void mouseExited(MouseEvent e) { hover = null; repaint(); }
            @Override public void mouseClicked(MouseEvent e) {
                if (!isEnabled() || !SwingUtilities.isLeftMouseButton(e)) return;
                selection = job.pick(toJob(e.getX(), e.getY()), 10 / scale, snap);
                onSelection.accept(selection);
                repaint();
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { if (!fitted) fit(); }
        });
    }

    public void onSelection(Consumer<Pick> listener) { onSelection = listener; }
    public void onHover(Consumer<String> listener) { onHover = listener; }
    public void setSnap(boolean snap) { this.snap = snap; }
    public void setShowRapids(boolean show) { showRapids = show; repaint(); }
    public void clearSelection() { selection = null; hover = null; repaint(); }
    Point toJob(double x, double y) { return new Point(centerX + (x - getWidth() / 2.0) / scale, centerY - (y - getHeight() / 2.0) / scale); }
    Point2D toScreen(Point p) { return new Point2D.Double(getWidth() / 2.0 + (p.x() - centerX) * scale, getHeight() / 2.0 - (p.y() - centerY) * scale); }

    public void fit() {
        double minX = Double.POSITIVE_INFINITY, minY = minX, maxX = -minX, maxY = -minX;
        for (Trace t : job.traces()) {
            if (t.rapid()) continue;
            minX = Math.min(minX, Math.min(t.start().x(), t.end().x()));
            minY = Math.min(minY, Math.min(t.start().y(), t.end().y()));
            maxX = Math.max(maxX, Math.max(t.start().x(), t.end().x()));
            maxY = Math.max(maxY, Math.max(t.start().y(), t.end().y()));
        }
        centerX = (minX + maxX) / 2;
        centerY = (minY + maxY) / 2;
        scale = Math.max(0.0001, Math.min((Math.max(100, getWidth()) - 70) / Math.max(1, maxX - minX),
                (Math.max(100, getHeight()) - 70) / Math.max(1, maxY - minY)));
        fitted = getWidth() > 0 && getHeight() > 0;
        repaint();
    }

    public void zoom(double factor) { zoom(factor, new java.awt.Point(getWidth() / 2, getHeight() / 2)); }
    private void zoom(double factor, java.awt.Point anchor) {
        Point before = toJob(anchor.x, anchor.y);
        scale = Math.max(0.0001, Math.min(100000, scale * factor));
        Point after = toJob(anchor.x, anchor.y);
        centerX += before.x() - after.x();
        centerY += before.y() - after.y();
        repaint();
    }

    @Override protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (!fitted) fit();
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        drawGrid(g);
        Graphics2D world = (Graphics2D) g.create();
        world.translate(getWidth() / 2.0, getHeight() / 2.0);
        world.scale(scale, -scale);
        world.translate(-centerX, -centerY);
        if (showRapids) {
            world.setColor(new Color(99, 107, 122));
            world.setStroke(new BasicStroke((float) (1 / scale)));
            world.draw(rapids);
        }
        world.setColor(new Color(100, 205, 236));
        world.setStroke(new BasicStroke((float) (1.5 / scale)));
        world.draw(cuts);
        world.dispose();
        Point2D zero = toScreen(new Point(0, 0));
        g.setColor(new Color(160, 173, 190));
        g.drawOval((int) zero.getX() - 5, (int) zero.getY() - 5, 10, 10);
        g.drawString("Job X0 Y0", (int) zero.getX() + 9, (int) zero.getY() - 9);
        if (hover != null) drawCross(g, hover, new Color(245, 194, 90), 6);
        if (selection != null) drawCross(g, selection, new Color(255, 107, 100), 12);
        g.setColor(new Color(210, 219, 230));
        g.drawString("TOP · +X →  +Y ↑  ·  mm", 14, 22);
        g.drawString("Wheel: zoom   ·   Right-drag: pan   ·   Left-click: reference point", 14, getHeight() - 14);
        g.dispose();
    }

    private void drawGrid(Graphics2D g) {
        double base = Math.pow(10, Math.floor(Math.log10(65 / scale)));
        double step = base;
        if (step * scale < 30) step *= 5;
        else if (step * scale < 50) step *= 2;
        Point low = toJob(0, getHeight()), high = toJob(getWidth(), 0);
        g.setColor(new Color(40, 48, 59));
        for (double x = Math.ceil(low.x() / step) * step; x <= high.x(); x += step) {
            int px = (int) toScreen(new Point(x, 0)).getX();
            g.drawLine(px, 0, px, getHeight());
        }
        for (double y = Math.ceil(low.y() / step) * step; y <= high.y(); y += step) {
            int py = (int) toScreen(new Point(0, y)).getY();
            g.drawLine(0, py, getWidth(), py);
        }
        g.setColor(new Color(65, 77, 91));
        Point2D zero = toScreen(new Point(0, 0));
        g.drawLine((int) zero.getX(), 0, (int) zero.getX(), getHeight());
        g.drawLine(0, (int) zero.getY(), getWidth(), (int) zero.getY());
        g.setColor(new Color(140, 154, 172));
        g.drawString(String.format(Locale.US, "Grid %.4g mm", step), getWidth() - 145, 22);
    }

    private void drawCross(Graphics2D g, Pick pick, Color color, int radius) {
        Point2D p = toScreen(pick.point());
        int x = (int) p.getX(), y = (int) p.getY();
        g.setColor(color);
        g.setStroke(new BasicStroke(1.5f));
        g.drawOval(x - radius, y - radius, radius * 2, radius * 2);
        g.drawLine(x - radius - 4, y, x + radius + 4, y);
        g.drawLine(x, y - radius - 4, x, y + radius + 4);
    }
}
