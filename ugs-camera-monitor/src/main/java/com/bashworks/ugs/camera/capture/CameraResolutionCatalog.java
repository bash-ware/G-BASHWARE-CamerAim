package com.bashworks.ugs.camera.capture;

import java.awt.Dimension;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class CameraResolutionCatalog {
    private static final Dimension[] COMMON_RESOLUTIONS = {
            new Dimension(320, 240),
            new Dimension(640, 480),
            new Dimension(800, 600),
            new Dimension(1024, 768),
            new Dimension(1280, 720),
            new Dimension(1280, 960),
            new Dimension(1600, 1200),
            new Dimension(1920, 1080),
            new Dimension(2048, 1536),
            new Dimension(2560, 1440),
            new Dimension(2560, 1920),
            new Dimension(2592, 1944)
    };

    private CameraResolutionCatalog() {
    }

    static Dimension[] customResolutions() {
        return copy(COMMON_RESOLUTIONS);
    }

    static List<Dimension> merged(Dimension[] reported) {
        Map<String, Dimension> unique = new LinkedHashMap<>();
        Arrays.stream(reported).forEach(size -> unique.put(key(size), copy(size)));
        Arrays.stream(COMMON_RESOLUTIONS).forEach(size -> unique.putIfAbsent(key(size), copy(size)));
        return unique.values().stream()
                .sorted(Comparator.comparingLong(CameraResolutionCatalog::pixels))
                .toList();
    }

    static Dimension largestReported(Dimension[] reported) {
        return Arrays.stream(reported)
                .max(Comparator.comparingLong(CameraResolutionCatalog::pixels))
                .map(CameraResolutionCatalog::copy)
                .orElse(null);
    }

    static boolean contains(Dimension[] sizes, Dimension candidate) {
        return Arrays.stream(sizes).anyMatch(candidate::equals);
    }

    private static long pixels(Dimension size) {
        return (long) size.width * size.height;
    }

    private static String key(Dimension size) {
        return size.width + "x" + size.height;
    }

    private static Dimension copy(Dimension size) {
        return new Dimension(size.width, size.height);
    }

    private static Dimension[] copy(Dimension[] sizes) {
        return Arrays.stream(sizes).map(CameraResolutionCatalog::copy).toArray(Dimension[]::new);
    }
}
