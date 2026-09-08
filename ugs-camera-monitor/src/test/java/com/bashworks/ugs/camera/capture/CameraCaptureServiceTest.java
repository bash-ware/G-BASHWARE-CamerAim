package com.bashworks.ugs.camera.capture;

import org.junit.Test;

import java.awt.Dimension;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CameraCaptureServiceTest {
    @Test
    public void calculatesARealPreviewFrameRateLimit() {
        assertEquals(50_000_000L, CameraCaptureService.remainingDelayNanos(5.0, 150_000_000L));
        assertEquals(0L, CameraCaptureService.remainingDelayNanos(10.0, 150_000_000L));
    }

    @Test
    public void acceptsOnlyTheExactRequestedResolution() {
        assertTrue(CameraCaptureService.sameSize(
                new Dimension(2592, 1944),
                new Dimension(2592, 1944)));
        assertFalse(CameraCaptureService.sameSize(
                new Dimension(2592, 1944),
                new Dimension(640, 480)));
    }

    @Test
    public void triesSelectedFrameRateFirstThenEachOtherStandardRate() {
        assertTrue(java.util.Arrays.equals(
                new double[]{10, 30, 25, 20, 15, 5},
                CameraCaptureService.nativeFrameRateAttempts(10)));
        assertTrue(java.util.Arrays.equals(
                new double[]{30, 25, 20, 15, 10, 5},
                CameraCaptureService.nativeFrameRateAttempts(30)));
    }
}
