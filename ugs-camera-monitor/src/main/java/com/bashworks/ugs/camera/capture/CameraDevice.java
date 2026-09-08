package com.bashworks.ugs.camera.capture;

import com.github.sarxos.webcam.Webcam;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;

public final class CameraDevice {
    private final Webcam webcam;
    private final CamerAimWebcamDevice nativeDevice;
    private final List<Dimension> supportedViewSizes;
    private final int[] supportedFrameRates;

    CameraDevice(Webcam webcam) {
        this.webcam = webcam;
        if (!(webcam.getDevice() instanceof CamerAimWebcamDevice device)) {
            throw new IllegalArgumentException("Unsupported camera device implementation");
        }
        this.nativeDevice = device;
        Dimension[] reported = copy(webcam.getViewSizes());
        this.supportedViewSizes = CameraResolutionCatalog.supported(webcam.getName(), reported);
        this.supportedFrameRates = CameraResolutionCatalog.frameRates(webcam.getName());
        webcam.setCustomViewSizes(CameraResolutionCatalog.customResolutions(webcam.getName(), reported));
    }

    void open(Dimension size, double frameRate) {
        nativeDevice.setResolution(size);
        nativeDevice.setRequestedFps(frameRate);
        nativeDevice.open();
    }

    Dimension activeSize() {
        return nativeDevice.getResolution();
    }

    BufferedImage image() {
        return nativeDevice.getImage();
    }

    void closeCamera() {
        nativeDevice.close();
    }

    boolean isOpen() {
        return nativeDevice.isOpen();
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
