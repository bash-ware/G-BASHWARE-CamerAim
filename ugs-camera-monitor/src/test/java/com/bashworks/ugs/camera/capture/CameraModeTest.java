package com.bashworks.ugs.camera.capture;

import org.junit.Test;
import java.awt.Dimension;
import java.util.List;
import static org.junit.Assert.*;

public class CameraModeTest {
    @Test public void resolutionsComeOnlyFromWindowsAndKeepFractionalFrameRates() {
        CameraDevice device = new CameraDevice("device-1", "Integrated Camera");
        device.setModes(List.of(
                new CameraMode(640,480,30,1,"NV12","source"),
                new CameraMode(1280,720,30000,1001,"NV12","source"),
                new CameraMode(1280,720,30,1,"MJPG","source")));
        assertEquals(List.of(new Dimension(640,480), new Dimension(1280,720)),device.viewSizes());
        assertTrue(device.candidates(new Dimension(2592,1944)).isEmpty());
        assertEquals(29.97002997, device.modes().get(1).fps(), 0.00001);
        assertEquals("NV12",device.candidates(new Dimension(1280,720)).get(0).subtype());
    }

    @Test public void parsesModeWithoutLosingTheDeviceSourceIdentity() {
        CameraMode mode = CameraMode.parse("MODE\t2592\t1944\t30\t1\t"
                + CameraMode.encode("NV12") + "\t" + CameraMode.encode("source#0\\USB"));
        assertEquals("source#0\\USB",mode.sourceId());
        assertEquals(new Dimension(2592,1944),mode.size());
    }

    @Test public void previewLimitDoesNotCreateInventedNativeCameraModes() {
        CameraDevice device = new CameraDevice("id","camera");
        CameraMode mode = new CameraMode(1280,720,8,1,"YUY2","source");
        device.setModes(List.of(mode));
        assertArrayEquals(new int[]{5,8},device.frameRates(mode.size()));
        assertEquals(List.of(mode),device.candidates(mode.size()));
    }

    @Test(expected=IllegalArgumentException.class)
    public void rejectsInvalidDriverDimensions() { new CameraMode(0,480,30,1,"NV12","source"); }
}
