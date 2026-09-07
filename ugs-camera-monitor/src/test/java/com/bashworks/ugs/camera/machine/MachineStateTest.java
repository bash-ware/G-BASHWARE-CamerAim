package com.bashworks.ugs.camera.machine;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MachineStateTest {
    @Test
    public void allowsPositioningOnlyWhenConnectedAndIdle() {
        assertTrue(new MachineState(true, true, false, false, true).canPosition());
        assertFalse(new MachineState(false, true, false, false, true).canPosition());
        assertFalse(new MachineState(true, false, false, false, true).canPosition());
        assertFalse(new MachineState(true, true, true, false, true).canPosition());
        assertFalse(new MachineState(true, true, false, true, true).canPosition());
        assertFalse(new MachineState(true, true, false, false, false).canPosition());
    }
}
