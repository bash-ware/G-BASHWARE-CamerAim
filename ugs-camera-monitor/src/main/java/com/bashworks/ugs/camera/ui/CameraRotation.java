package com.bashworks.ugs.camera.ui;

public enum CameraRotation {
    DEG_0(0, "0°"),
    DEG_90(90, "90° CW"),
    DEG_180(180, "180°"),
    DEG_270(270, "270° CW");

    private final int degrees;
    private final String label;

    CameraRotation(int degrees, String label) {
        this.degrees = degrees;
        this.label = label;
    }

    public double radians() {
        return Math.toRadians(degrees);
    }

    public boolean swapsAxes() {
        return degrees == 90 || degrees == 270;
    }

    @Override
    public String toString() {
        return label;
    }
}
