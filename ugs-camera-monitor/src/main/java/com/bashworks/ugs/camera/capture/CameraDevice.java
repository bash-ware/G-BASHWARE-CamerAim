package com.bashworks.ugs.camera.capture;

import com.github.sarxos.webcam.Webcam;

import java.awt.Dimension;
import java.util.Arrays;
import java.util.List;

public final class CameraDevice {
    private final Webcam webcam;
    private final List<Dimension> supportedViewSizes;
    private final int[] supportedFrameRates;

    CameraDevice(Webcam webcam) {
        this.webcam = webcam;
        Dimension[] reported = copy(webcam.getViewSizes());
        this.supportedViewSizes = CameraResolutionCatalog.supported(webcam.getName(), reported);
        this.supportedFrameRates = CameraResolutionCatalog.frameRates(webcam.getName());
        webcam.setCustomViewSizes(CameraResolutionCatalog.customResolutions(webcam.getName(), reported));
    }

    Webcam webcam() {
        return webcam;
    }

    void setFrameRate(double frameRate) {
        if (webcam.getDevice() instanceof CamerAimWebcamDevice device) {
            device.setRequestedFps(frameRate);
        }
    }

    public List<Dimension> viewSizes() {
        return supportedViewSizes.stream().map(Dimension::new).toList();
    }

    public int[] frameRates() {
        return Arrays.copyOf(supportedFrameRates, supportedFrameRates.length);
    }

    public Dimension preferredViewSize() {
        return CameraResolutionCatalog.largest(supportedViewSizes);
    }

    public int preferredFrameRate() {
        return Arrays.stream(supportedFrameRates).max().orElse(30);
    }

    @Override
    public String toString() {
        return webcam.getName();
    }

    private static Dimension[] copy(Dimension[] sizes) {
        return Arrays.stream(sizes)
                .map(Dimension::new)
                .toArray(Dimension[]::new);
    }
}
