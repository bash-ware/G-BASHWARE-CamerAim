package com.bashworks.ugs.camera.machine;

import com.bashworks.ugs.camera.model.CameraOffset;
import com.bashworks.ugs.camera.model.LengthUnit;
import com.willwinder.universalgcodesender.Capabilities;
import com.willwinder.universalgcodesender.IController;
import com.willwinder.universalgcodesender.listeners.ControllerState;
import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.model.PartialPosition;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UnitUtils;
import org.junit.Test;

import java.lang.reflect.Proxy;

import static org.junit.Assert.*;

public class CameraPositioningTest {
    @Test
    public void waitsThroughStaleIdleAndMotionBeforeZeroing() throws Exception {
        Fixture f = new Fixture("success");
        f.gateway.moveToolToCrosshair(new CameraOffset(2, -3, LengthUnit.MM), 500);
        assertTrue(f.reads >= 4);
        assertEquals(1, f.zeroCalls);
        assertEquals(0.0, f.zero.getAxis(com.willwinder.universalgcodesender.model.Axis.X), 0);
        assertEquals(0.0, f.zero.getAxis(com.willwinder.universalgcodesender.model.Axis.Y), 0);
        assertFalse(f.zero.hasAxis(com.willwinder.universalgcodesender.model.Axis.Z));
    }

    @Test
    public void convertsInchOffsetsAndPreservesWorkUnits() throws Exception {
        Fixture f = new Fixture("success");
        f.gateway.moveToolToCrosshair(new CameraOffset(2 / 25.4, -3 / 25.4, LengthUnit.INCH), 20);
        assertEquals(1, f.zeroCalls);
        assertEquals(UnitUtils.Units.INCH, f.zero.getUnits());
    }

    @Test
    public void alarmDoesNotZero() throws Exception {
        assertFailure("alarm");
    }

    @Test
    public void disconnectDoesNotZero() throws Exception {
        assertFailure("disconnect");
    }

    @Test
    public void cancelledMoveShortOfTargetDoesNotZero() throws Exception {
        assertFailure("cancel");
    }

    @Test
    public void rejectedJogDoesNotZero() throws Exception {
        assertFailure("reject");
    }

    @Test
    public void zeroOffsetZerosCurrentPosition() throws Exception {
        Fixture f = new Fixture("zero");
        f.gateway.moveToolToCrosshair(new CameraOffset(0, 0, LengthUnit.MM), 500);
        assertEquals(1, f.zeroCalls);
    }

    private void assertFailure(String mode) throws Exception {
        Fixture f = new Fixture(mode);
        try {
            f.gateway.moveToolToCrosshair(new CameraOffset(2, -3, LengthUnit.MM), 500);
            fail("Expected failed move: " + mode);
        } catch (IllegalStateException expected) {
            assertEquals(0, f.zeroCalls);
        }
    }

    private static final class Fixture {
        private final String mode;
        private ControllerState state = ControllerState.IDLE;
        private int reads;
        private int zeroCalls;
        private PartialPosition zero;
        private final UgsMachineGateway gateway;

        Fixture(String mode) {
            this.mode = mode;
            Capabilities capabilities = new Capabilities() {
                @Override public boolean hasJogging() { return true; }
            };
            IController controller = (IController) Proxy.newProxyInstance(
                    IController.class.getClassLoader(), new Class<?>[]{IController.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("getCapabilities")) return capabilities;
                        throw new AssertionError(method.getName());
                    });
            BackendAPI backend = (BackendAPI) Proxy.newProxyInstance(
                    BackendAPI.class.getClassLoader(), new Class<?>[]{BackendAPI.class},
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "isConnected": return state != ControllerState.DISCONNECTED;
                            case "isIdle": return state == ControllerState.IDLE;
                            case "isSendingFile":
                            case "isPaused": return false;
                            case "getControllerState": return state;
                            case "getController": return controller;
                            case "getWorkPosition": return new Position(1, 1, 7, UnitUtils.Units.INCH);
                            case "getMachinePosition":
                                reads++;
                                if (reads == 1 || mode.equals("zero")) return position(10, 20);
                                if (reads == 2) {
                                    state = mode.equals("alarm") ? ControllerState.ALARM
                                            : mode.equals("disconnect") ? ControllerState.DISCONNECTED
                                            : ControllerState.JOG;
                                    return position(10, 20); // Stale Idle must not trigger zero.
                                }
                                if (reads == 3) {
                                    state = ControllerState.IDLE;
                                    return position(11, 19);
                                }
                                return mode.equals("cancel") ? position(11, 19) : position(12, 17);
                            case "adjustManualLocation":
                                if (mode.equals("reject")) throw new IllegalStateException("Jog rejected");
                                return null;
                            case "setWorkPosition":
                                assertEquals(ControllerState.IDLE, state);
                                assertTrue(mode.equals("zero") || reads >= 4);
                                zero = (PartialPosition) args[0];
                                zeroCalls++;
                                return null;
                            default: throw new AssertionError(method.getName());
                        }
                    });
            gateway = new UgsMachineGateway(backend);
        }

        private Position position(double x, double y) {
            return new Position(x, y, 7, UnitUtils.Units.MM);
        }
    }
}
