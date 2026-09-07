package com.bashworks.ugs.camera.capture;

import com.github.sarxos.webcam.WebcamDevice;
import com.github.sarxos.webcam.WebcamDiscoverySupport;
import com.github.sarxos.webcam.WebcamDriver;
import com.github.sarxos.webcam.ds.buildin.natives.Device;
import com.github.sarxos.webcam.ds.buildin.natives.DeviceList;
import com.github.sarxos.webcam.ds.buildin.natives.OpenIMAJGrabber;
import org.bridj.Pointer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Uses the webcam-capture native UVC bridge with CamerAim's deterministic
 * selectable-FPS device implementation instead of the library's hard-coded 50 FPS device.
 */
public final class CamerAimWebcamDriver implements WebcamDriver, WebcamDiscoverySupport {
    private OpenIMAJGrabber discoveryGrabber;

    static {
        if (!"true".equals(System.getProperty("webcam.debug"))) {
            System.setProperty("bridj.quiet", "true");
        }
    }

    @Override
    public synchronized List<WebcamDevice> getDevices() {
        if (discoveryGrabber == null) {
            discoveryGrabber = new OpenIMAJGrabber();
        }
        Pointer<DeviceList> pointer = discoveryGrabber.getVideoDevices();
        if (pointer == null || pointer.get() == null) {
            return Collections.emptyList();
        }

        List<WebcamDevice> devices = new ArrayList<>();
        for (Device device : pointer.get().asArrayList()) {
            devices.add(new CamerAimWebcamDevice(device));
        }
        return devices;
    }

    @Override
    public long getScanInterval() {
        return DEFAULT_SCAN_INTERVAL;
    }

    @Override
    public boolean isScanPossible() {
        return false;
    }

    @Override
    public boolean isThreadSafe() {
        return false;
    }
}
