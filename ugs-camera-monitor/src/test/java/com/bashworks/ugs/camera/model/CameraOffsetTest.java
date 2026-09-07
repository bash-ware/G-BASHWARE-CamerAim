package com.bashworks.ugs.camera.model;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CameraOffsetTest {
    @Test
    public void preservesMovementSigns() {
        CameraOffset offset = new CameraOffset(12.0, -3.5, LengthUnit.MM);
        assertEquals(12.0, offset.x(), 0.000001);
        assertEquals(-3.5, offset.y(), 0.000001);
    }

    @Test
    public void convertsInchesToMillimeters() {
        CameraOffset converted = new CameraOffset(1.0, -0.5, LengthUnit.INCH).in(LengthUnit.MM);
        assertEquals(25.4, converted.x(), 0.000001);
        assertEquals(-12.7, converted.y(), 0.000001);
    }

    @Test
    public void addsOffsetToPositionUnderCrosshair() {
        TargetPosition target = new CameraOffset(12.0, -3.5, LengthUnit.MM)
                .targetFrom(100.0, 50.0, LengthUnit.MM);
        assertEquals(112.0, target.x(), 0.000001);
        assertEquals(46.5, target.y(), 0.000001);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonFiniteOffset() {
        new CameraOffset(Double.NaN, 1.0, LengthUnit.MM);
    }
}

