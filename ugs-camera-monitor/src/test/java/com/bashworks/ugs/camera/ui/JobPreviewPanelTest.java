package com.bashworks.ugs.camera.ui;

import com.bashworks.ugs.camera.model.JobPreview;
import com.bashworks.ugs.camera.model.JobPreview.*;
import com.bashworks.ugs.camera.model.JobPreview.Point;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Point2D;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class JobPreviewPanelTest {
    static JobPreview sample() {
        return new JobPreview(null, null, "G54", List.of(
                new Trace(new Point(0, 0), new Point(100, 0), false),
                new Trace(new Point(100, 0), new Point(100, 50), false),
                new Trace(new Point(100, 50), new Point(0, 50), false),
                new Trace(new Point(0, 50), new Point(0, 0), false)),
                List.of(new Feature(new Point(100, 50), "Corner")));
    }

    @Test public void keepsPositiveYUpAndRoundTripsCoordinatesAtZoom() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JobPreviewPanel panel = new JobPreviewPanel(sample());
            panel.setSize(880, 470);
            panel.fit();
            Point2D zero = panel.toScreen(new Point(0, 0)), corner = panel.toScreen(new Point(100, 50));
            assertTrue(corner.getX() > zero.getX());
            assertTrue(corner.getY() < zero.getY());
            panel.zoom(5);
            Point2D screen = panel.toScreen(new Point(22, 17));
            assertEquals(22, panel.toJob(screen.getX(), screen.getY()).x(), 1e-9);
            assertEquals(17, panel.toJob(screen.getX(), screen.getY()).y(), 1e-9);
        });
    }

    @Test public void wheelZoomAnchorsCursorAndClickSnapsRatherThanArmingOnHover() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JobPreviewPanel panel = new JobPreviewPanel(sample());
            panel.setSize(880, 470);
            panel.fit();
            Point before = panel.toJob(200, 100);
            panel.dispatchEvent(new MouseWheelEvent(panel, MouseEvent.MOUSE_WHEEL, 0, 0, 200, 100,
                    0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, -2));
            assertEquals(before.x(), panel.toJob(200, 100).x(), 1e-9);
            assertEquals(before.y(), panel.toJob(200, 100).y(), 1e-9);
            panel.fit();
            AtomicReference<Pick> picked = new AtomicReference<>();
            panel.onSelection(picked::set);
            Point2D screen = panel.toScreen(new Point(100, 50));
            panel.dispatchEvent(new MouseEvent(panel, MouseEvent.MOUSE_MOVED, 0, 0, (int) screen.getX(), (int) screen.getY(), 0, false));
            assertNull(picked.get());
            panel.dispatchEvent(new MouseEvent(panel, MouseEvent.MOUSE_CLICKED, 0, 0, (int) screen.getX(), (int) screen.getY(), 1, false, MouseEvent.BUTTON1));
            assertEquals(new Point(100, 50), picked.get().point());
            panel.setEnabled(false);
            picked.set(null);
            panel.dispatchEvent(new MouseEvent(panel, MouseEvent.MOUSE_CLICKED, 0, 0, (int) screen.getX(), (int) screen.getY(), 1, false, MouseEvent.BUTTON1));
            assertNull(picked.get());
        });
    }
}
