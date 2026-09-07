package com.bashworks.ugs.camera.capture;

import org.junit.Test;

import java.awt.Dimension;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CameraResolutionCatalogTest {
    @Test
    public void usesTheValidatedFiveMegapixelCameraProfile() {
        List<Dimension> result = CameraResolutionCatalog.supported(
                "5MP Camera 0",
                new Dimension[]{new Dimension(640, 480)});

        assertTrue(result.contains(new Dimension(2592, 1944)));
        assertTrue(result.contains(new Dimension(2560, 1920)));
        assertTrue(result.contains(new Dimension(1920, 1080)));
        assertTrue(result.contains(new Dimension(1280, 720)));
        assertTrue(result.contains(new Dimension(1280, 960)));
        assertFalse(result.contains(new Dimension(2560, 1440)));
        assertFalse(result.contains(new Dimension(2048, 1536)));
        assertFalse(result.contains(new Dimension(1600, 1200)));
        assertFalse(result.contains(new Dimension(1280, 1024)));
        assertFalse(result.contains(new Dimension(1024, 768)));
        assertFalse(result.contains(new Dimension(352, 288)));
        assertFalse(result.contains(new Dimension(320, 240)));
        assertFalse(result.contains(new Dimension(160, 120)));
        assertFalse(result.contains(new Dimension(176, 144)));
    }

    @Test
    public void keepsOnlyDriverReportedModesForOtherCameras() {
        List<Dimension> result = CameraResolutionCatalog.supported(
                "Other USB camera",
                new Dimension[]{
                        new Dimension(640, 480),
                        new Dimension(1280, 720),
                        new Dimension(640, 480)
                });

        assertEquals(List.of(new Dimension(640, 480), new Dimension(1280, 720)), result);
        assertFalse(result.contains(new Dimension(2592, 1944)));
    }

    @Test
    public void exposesOnlyProfileFrameRates() {
        assertArrayEquals(new int[]{5, 10, 15, 20, 25, 30},
                CameraResolutionCatalog.frameRates("5MP Camera 0"));
        assertArrayEquals(new int[]{30},
                CameraResolutionCatalog.frameRates("Other USB camera"));
    }

    @Test
    public void choosesLargestSupportedModeAsPreferredSize() {
        Dimension preferred = CameraResolutionCatalog.largest(List.of(
                new Dimension(640, 480),
                new Dimension(1280, 720),
                new Dimension(800, 600)));

        assertEquals(new Dimension(1280, 720), preferred);
    }
}
