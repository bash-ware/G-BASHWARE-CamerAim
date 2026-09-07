package com.bashworks.ugs.camera.capture;

import com.github.sarxos.webcam.Webcam;

import java.awt.Dimension;
import java.util.Arrays;
import java.util.List;

public final class CameraDevice {
    private final Webcam webcam;
    private final Dimension[] reportedViewSizes;

    CameraDevice(Webcam webcam) {
        this.webcam = webcam;
        this.reportedViewSizes = copy(webcam.getViewSizes());
        webcam.setCustomViewSizes(CameraResolutionCatalog.customResolutions());
    }

    Webcam webcam() {
        return webcam;
    }

    public List<Dimension> viewSizes() {
        return CameraResolutionCatalog.merged(reportedViewSizes);
    }

    public boolean isReportedViewSize(Dimension size) {
        return CameraResolutionCatalog.contains(reportedViewSizes, size);
    }

    public Dimension preferredViewSize() {
        return CameraResolutionCatalog.largestReported(reportedViewSizes);
    }


    @Override
    public String toString() {
        return webcam.getName();
    }

    private static Dimension[] copy(Dimension[] sizes) {
        return Arrays.stream(sizes)
                .map(size -> new Dimension(size.width, size.height))
                .toArray(Dimension[]::new);
    }
}
