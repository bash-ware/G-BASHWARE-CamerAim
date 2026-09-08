package com.bashworks.ugs.camera.capture;

import java.awt.Dimension;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

public final class CameraDevice {
    private final String id;
    private final String name;
    private volatile List<CameraMode> modes = List.of();

    CameraDevice(String id, String name) {
        this.id = id;
        this.name = name;
    }

    String id() { return id; }
    void setModes(List<CameraMode> modes) { this.modes = List.copyOf(modes); }
    public List<CameraMode> modes() { return modes; }

    public List<Dimension> viewSizes() {
        return modes.stream().map(CameraMode::size).distinct()
                .sorted(Comparator.comparingLong(size -> (long) size.width * size.height)).toList();
    }

    List<CameraMode> candidates(Dimension size) {
        return modes.stream().filter(mode -> mode.size().equals(size))
                .sorted(Comparator.comparingInt(CameraMode::conversionPriority)
                        .thenComparing(Comparator.comparingDouble(CameraMode::fps).reversed()))
                .toList();
    }

    /** Display limits only; native FPS is selected from exact Windows-reported modes. */
    public int[] frameRates(Dimension size) {
        double maximum = modes.stream().filter(mode -> size == null || mode.size().equals(size))
                .mapToDouble(CameraMode::fps).max().orElse(1);
        int upper = Math.max(1, Math.min(30, (int) Math.floor(maximum)));
        return IntStream.concat(IntStream.of(5, 10, 15, 20, 25, 30).filter(rate -> rate <= upper),
                IntStream.of(upper)).distinct().sorted().toArray();
    }

    public int[] frameRates() { return frameRates(null); }
    public int preferredFrameRate() {
        int[] rates = frameRates();
        return rates[rates.length - 1];
    }

    public Dimension preferredViewSize() {
        List<Dimension> sizes = viewSizes();
        // A moderate default also works on lower-powered CNC computers.
        return sizes.stream().filter(size -> size.width == 1280 && size.height == 720).findFirst()
                .orElse(sizes.isEmpty() ? null : sizes.get(sizes.size() - 1));
    }

    @Override public String toString() { return name; }
}
