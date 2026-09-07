package com.bashworks.ugs.camera.capture;

import org.junit.Test;

import java.awt.Dimension;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CameraResolutionCatalogTest {
    @Test
    public void addsFiveMegapixelModeAndKeepsReportedModesWithoutDuplicates() {
        List<Dimension> result = CameraResolutionCatalog.merged(new Dimension[]{
                new Dimension(640, 480),
                new Dimension(1920, 1080)
        });

        assertTrue(result.contains(new Dimension(2592, 1944)));
        assertEquals(1, result.stream().filter(new Dimension(640, 480)::equals).count());
    }

    @Test
    public void choosesLargestDriverReportedModeAsFallback() {
        Dimension fallback = CameraResolutionCatalog.largestReported(new Dimension[]{
                new Dimension(640, 480),
                new Dimension(1280, 720),
                new Dimension(800, 600)
        });

        assertEquals(new Dimension(1280, 720), fallback);
    }
}
