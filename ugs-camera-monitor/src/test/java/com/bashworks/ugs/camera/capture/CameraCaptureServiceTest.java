package com.bashworks.ugs.camera.capture;

import org.junit.Test;

import java.awt.Dimension;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CameraCaptureServiceTest {
    @Test
    public void acceptsOnlyTheExactRequestedResolution() {
        assertTrue(CameraCaptureService.sameSize(
                new Dimension(2592, 1944),
                new Dimension(2592, 1944)));
        assertFalse(CameraCaptureService.sameSize(
                new Dimension(2592, 1944),
                new Dimension(640, 480)));
    }
}