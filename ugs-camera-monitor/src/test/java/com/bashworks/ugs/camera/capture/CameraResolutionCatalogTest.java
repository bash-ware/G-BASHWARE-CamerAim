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
        assertTrue(result.contains(new Dimension(2048, 1536)));
        assertTrue(result.contains(new Dimension(1280, 1024)));
        assertTrue(result.contains(new Dimension(352, 288)));
        assertTrue(result.contains(new Dimension(160, 120)));
        assertEquals(1, result.stream().filter(new Dimension(640, 480)::equals).count());
    }

    @Test
    public void choosesLargestDriverReportedModeAsPreferredSize() {
        Dimension preferred = CameraResolutionCatalog.largestReported(new Dimension[]{
                new Dimension(640, 480),
                new Dimension(1280, 720),
                new Dimension(800, 600)
        });

        assertEquals(new Dimension(1280, 720), preferred);
    }
}
