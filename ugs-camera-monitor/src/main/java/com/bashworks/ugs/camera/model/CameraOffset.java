package com.bashworks.ugs.camera.model;

import java.util.Locale;
import java.util.Objects;

/**
 * Relative XY movement which places the tool over a target currently under the crosshair.
 * Values are deliberately not inverted by the plugin.
 */
public final class CameraOffset {
    private final double x;
    private final double y;
    private final LengthUnit unit;

    public CameraOffset(double x, double y, LengthUnit unit) {
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            throw new IllegalArgumentException("X and Y offset must be finite numbers");
        }
        this.x = x;
        this.y = y;
        this.unit = Objects.requireNonNull(unit, "unit");
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public LengthUnit unit() {
        return unit;
    }

    public CameraOffset in(LengthUnit target) {
        return new CameraOffset(unit.convert(x, target), unit.convert(y, target), target);
    }

    public TargetPosition targetFrom(double cameraX, double cameraY, LengthUnit positionUnit) {
        CameraOffset converted = in(positionUnit);
        return new TargetPosition(cameraX + converted.x, cameraY + converted.y, positionUnit);
    }

    public String movementLabel() {
        return String.format(Locale.US, "X %+.3f %s, Y %+.3f %s", x, unit, y, unit);
    }
}

