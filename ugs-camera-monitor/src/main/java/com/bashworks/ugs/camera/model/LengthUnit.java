package com.bashworks.ugs.camera.model;

public enum LengthUnit {
    MM("mm", 1.0),
    INCH("inch", 25.4);

    private final String label;
    private final double millimeters;

    LengthUnit(String label, double millimeters) {
        this.label = label;
        this.millimeters = millimeters;
    }

    public double convert(double value, LengthUnit target) {
        return value * millimeters / target.millimeters;
    }

    @Override
    public String toString() {
        return label;
    }
}

