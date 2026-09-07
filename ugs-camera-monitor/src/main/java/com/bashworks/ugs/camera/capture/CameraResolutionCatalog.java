package com.bashworks.ugs.camera.capture;

import java.awt.Dimension;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class CameraResolutionCatalog {
    private static final Dimension[] FIVE_MP_CAMERA_RESOLUTIONS = {
            new Dimension(640, 480),
            new Dimension(800, 600),
            new Dimension(1280, 720),
            new Dimension(1280, 960),
            new Dimension(1920, 1080),
            new Dimension(2560, 1920),
            new Dimension(2592, 1944)
    };
    private static final int[] FIVE_MP_CAMERA_FRAME_RATES = {5, 10, 15, 20, 25, 30};
    private static final int[] DEFAULT_FRAME_RATES = {30};

    private CameraResolutionCatalog() {
    }

    static List<Dimension> supported(String deviceName, Dimension[] reported) {
        Dimension[] source = isFiveMegapixelCamera(deviceName)
                ? FIVE_MP_CAMERA_RESOLUTIONS
                : reported;
        Map<String, Dimension> unique = new LinkedHashMap<>();
        Arrays.stream(source).forEach(size -> unique.putIfAbsent(key(size), copy(size)));
        return unique.values().stream()
                .sorted(Comparator.comparingLong(CameraResolutionCatalog::pixels))
                .toList();
    }

    static Dimension[] customResolutions(String deviceName, Dimension[] reported) {
        return supported(deviceName, reported).toArray(Dimension[]::new);
    }

    static int[] frameRates(String deviceName) {
        int[] source = isFiveMegapixelCamera(deviceName)
                ? FIVE_MP_CAMERA_FRAME_RATES
                : DEFAULT_FRAME_RATES;
        return Arrays.copyOf(source, source.length);
    }

    static Dimension largest(List<Dimension> sizes) {
        return sizes.stream()
                .max(Comparator.comparingLong(CameraResolutionCatalog::pixels))
                .map(CameraResolutionCatalog::copy)
                .orElse(null);
    }

    private static boolean isFiveMegapixelCamera(String deviceName) {
        return deviceName != null && deviceName.toLowerCase(java.util.Locale.ROOT).contains("5mp camera");
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
}
