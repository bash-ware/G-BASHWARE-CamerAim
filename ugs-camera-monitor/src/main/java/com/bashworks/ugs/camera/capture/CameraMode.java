package com.bashworks.ugs.camera.capture;

import java.awt.Dimension;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public record CameraMode(int width, int height, long numerator, long denominator,
                         String subtype, String sourceId) {
    public CameraMode {
        if (width <= 0 || height <= 0 || width > 16384 || height > 16384
                || (long) width * height > 40_000_000 || numerator <= 0 || denominator <= 0
                || subtype == null || sourceId == null) {
            throw new IllegalArgumentException("Invalid camera mode reported by Windows");
        }
    }

    public Dimension size() { return new Dimension(width, height); }
    public double fps() { return (double) numerator / denominator; }
    public String label() {
        return width + " × " + height + " " + subtype + " @ "
                + String.format(Locale.ROOT, "%.2f", fps()) + " fps";
    }

    // MediaCapture exposes decoded NV12 modes even when the USB transport is MJPEG.
    // Prefer these over asking MediaFrameReader to convert a compressed MJPG stream.
    int conversionPriority() {
        return switch (subtype.toUpperCase(Locale.ROOT)) {
            case "NV12" -> 0;
            case "YUY2", "BGRA8", "ARGB32", "RGB32" -> 1;
            case "MJPG", "MJPEG" -> 3;
            default -> 2;
        };
    }

    static CameraMode parse(String line) {
        String[] fields = line.split("\t", -1);
        if (fields.length != 7 || !fields[0].equals("MODE")) {
            throw new IllegalArgumentException("Invalid camera mode response");
        }
        return new CameraMode(Integer.parseInt(fields[1]), Integer.parseInt(fields[2]),
                Long.parseLong(fields[3]), Long.parseLong(fields[4]), decode(fields[5]), decode(fields[6]));
    }

    static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }
    static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
