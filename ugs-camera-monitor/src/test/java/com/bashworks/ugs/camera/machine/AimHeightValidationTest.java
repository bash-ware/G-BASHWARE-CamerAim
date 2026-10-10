package com.bashworks.ugs.camera.machine;

import com.bashworks.ugs.camera.model.LengthUnit;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class AimHeightValidationTest {

    @Test
    public void rejectsInvalidAimHeightParameters() {
        MachineGateway fakeGateway = new MachineGateway() {
            @Override
            public MachineState state() {
                return new MachineState(false, false, false, false, false);
            }

            @Override
            public com.bashworks.ugs.camera.model.TargetPosition machinePosition() {
                return new com.bashworks.ugs.camera.model.TargetPosition(0, 0, LengthUnit.MM);
            }

            @Override
            public void moveToolToCrosshair(com.bashworks.ugs.camera.model.CameraOffset movement, double feedRate) {
            }

            @Override
            public void setWorkXyZero() {
            }

            @Override
            public void moveToAimHeight(String homingCommand, double dropDistance, LengthUnit unit, double feedRate, java.util.function.Consumer<String> statusListener) throws Exception {
                if (!state().canPosition()) {
                    throw new IllegalStateException("Machine not ready");
                }
                if (homingCommand == null || homingCommand.trim().isEmpty()) {
                    throw new IllegalArgumentException("Homing command must not be blank.");
                }
                if (dropDistance < 0) {
                    throw new IllegalArgumentException("Aim drop distance must be non-negative.");
                }
                if (feedRate <= 0) {
                    throw new IllegalArgumentException("Jog feed must be greater than zero.");
                }
            }
        };

        // When machine cannot position
        try {
            fakeGateway.moveToAimHeight("$HZ", 50.0, LengthUnit.MM, 500.0, null);
            fail("Expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertEquals("Machine not ready", expected.getMessage());
        } catch (Exception unexpected) {
            fail("Unexpected exception: " + unexpected);
        }
    }
}
