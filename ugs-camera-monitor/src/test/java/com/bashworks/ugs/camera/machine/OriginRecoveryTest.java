package com.bashworks.ugs.camera.machine;

import com.bashworks.ugs.camera.model.*;
import com.bashworks.ugs.camera.model.OriginRecovery.*;
import com.willwinder.universalgcodesender.Capabilities;
import com.willwinder.universalgcodesender.IController;
import com.willwinder.universalgcodesender.gcode.GcodeState;
import com.willwinder.universalgcodesender.gcode.util.Code;
import com.willwinder.universalgcodesender.listeners.ControllerState;
import com.willwinder.universalgcodesender.model.*;
import com.willwinder.universalgcodesender.model.events.ControllerStateEvent;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.*;

public class OriginRecoveryTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final CameraOffset OFFSET = new CameraOffset(30, -10, LengthUnit.MM);
    private static final JobPreview.Point POINT = new JobPreview.Point(20, 15);

    @Test public void restoresCameraFeatureUsingCorrectSignWithoutMotionOrZChange() throws Exception {
        Fixture f = fixture();
        Restored restored = f.restore();
        assertEquals(-10, f.setPosition.getX(), 0);
        assertEquals(25, f.setPosition.getY(), 0);
        assertFalse(f.setPosition.hasZ());
        assertEquals(new Pose(110, 175, -50), restored.origin());
        assertEquals(new Pose(100, 200, -30), f.machine);
        assertTrue(f.gateway.restoredOriginValid(restored));
        assertTrue(f.jogs.isEmpty());
    }

    @Test public void mixedInchReportsAndCameraOffsetsProduceSameOrigin() throws Exception {
        Fixture f = fixture();
        f.reports = UnitUtils.Units.INCH;
        Anchor anchor = f.gateway.captureReference(f.job);
        Restored restored = f.gateway.restoreOrigin(f.job, anchor, POINT,
                new CameraOffset(30 / 25.4, -10 / 25.4, LengthUnit.INCH));
        assertTrue(new Pose(110, 175, -50).near(restored.origin()));
        assertEquals(UnitUtils.Units.MM, f.setPosition.getUnits());
        assertEquals(20, f.work().z(), 1e-8);
    }

    @Test public void liftsBeforeXYAndNeverChangesZReference() throws Exception {
        Fixture f = fixture();
        Restored restored = f.restore();
        f.gateway.moveToRestoredOrigin(restored, -5, 1000, s -> { });
        assertEquals(2, f.jogs.size());
        assertTrue(f.jogs.get(0).hasZ());
        assertFalse(f.jogs.get(0).hasX());
        assertEquals(25, f.jogs.get(0).getZ(), 0);
        assertFalse(f.jogs.get(1).hasZ());
        assertEquals(10, f.jogs.get(1).getX(), 1e-9);
        assertEquals(-25, f.jogs.get(1).getY(), 1e-9);
        assertTrue(new Pose(110, 175, -5).near(f.machine));
        assertTrue(new Pose(0, 0, 45).near(f.work()));
        assertEquals(1, f.setCalls);
    }

    @Test public void alreadyAboveSafeZDoesNotDescend() throws Exception {
        Fixture f = fixture();
        Restored restored = f.restore();
        f.gateway.moveToRestoredOrigin(restored, -40, 1000, s -> { });
        assertEquals(1, f.jogs.size());
        assertFalse(f.jogs.get(0).hasZ());
        assertEquals(-30, f.machine.z(), 0);
    }

    @Test public void cameraMovementWorkResetAndConnectionResetDisarmReference() throws Exception {
        Fixture f = fixture();
        Anchor a = f.gateway.captureReference(f.job);
        f.machine = new Pose(101, 200, -30);
        assertFalse(f.gateway.referenceValid(f.job, a));
        assertThrows(IllegalStateException.class, () -> f.gateway.restoreOrigin(f.job, a, POINT, OFFSET));
        f.machine = a.machine();
        f.origin = new Pose(11, 20, -50);
        assertFalse(f.gateway.referenceValid(f.job, a));
        f.origin = new Pose(10, 20, -50);
        f.gateway.onMachineEvent(new ControllerStateEvent(ControllerState.DISCONNECTED, ControllerState.IDLE));
        assertFalse(f.gateway.referenceValid(f.job, a));
        assertEquals(0, f.setCalls);
    }

    @Test public void editedJobAndWrongWcsPreventRestoreOrTravel() throws Exception {
        Fixture f = fixture();
        Restored restored = f.restore();
        f.gcode.offset = Code.G55;
        assertFalse(f.gateway.restoredOriginValid(restored));
        assertThrows(IllegalStateException.class, () -> f.gateway.moveToRestoredOrigin(restored, -5, 1000, s -> { }));
        f.gcode.offset = Code.G54;
        // Even an edit with the same file size and timestamp is caught at action time.
        var time = Files.getLastModifiedTime(f.file);
        Files.writeString(f.file, Files.readString(f.file).replace("X10", "X11"));
        Files.setLastModifiedTime(f.file, time);
        assertThrows(IllegalStateException.class, () -> f.gateway.moveToRestoredOrigin(restored, -5, 1000, s -> { }));
        assertTrue(f.jogs.isEmpty());
    }

    @Test public void cancelledOrAlarmedOrDisconnectedLiftNeverStartsXY() throws Exception {
        for (String mode : List.of("cancel", "alarm", "disconnect", "reject")) {
            Fixture f = fixture();
            Restored restored = f.restore();
            f.mode = mode;
            assertThrows(IllegalStateException.class, () -> f.gateway.moveToRestoredOrigin(restored, -5, 1000, s -> { }));
            assertEquals(1, f.jogs.size());
            assertTrue(f.jogs.get(0).hasZ());
        }
    }

    @Test public void externallyChangedOriginOrRunningSpindlePreventsTravel() throws Exception {
        Fixture f = fixture();
        Restored restored = f.restore();
        f.origin = new Pose(99, 99, -50);
        assertThrows(IllegalStateException.class, () -> f.gateway.moveToRestoredOrigin(restored, -5, 1000, s -> { }));
        f.origin = restored.origin();
        f.gcode.spindle = Code.M3;
        assertThrows(IllegalStateException.class, () -> f.gateway.moveToRestoredOrigin(restored, -5, 1000, s -> { }));
        assertTrue(f.jogs.isEmpty());
    }

    @Test public void unconfirmedCoordinateSetCannotArmTravel() throws Exception {
        Fixture f = fixture();
        f.mode = "ignore-set";
        assertThrows(TimeoutException.class, f::restore);
        assertTrue(f.jogs.isEmpty());
    }

    private Fixture fixture() throws Exception {
        Path file = temp.newFile().toPath();
        Files.writeString(file, "G21 G54 G90\nG0 X0 Y0\nG1 X10 Y0 F100\n");
        return new Fixture(file, new JobPreviewLoader().loadText(file));
    }

    private static final class Fixture {
        final Path file;
        final JobPreview job;
        final GcodeState gcode = new GcodeState();
        Pose machine = new Pose(100, 200, -30), origin = new Pose(10, 20, -50);
        Pose pendingStart, pendingTarget;
        long started;
        UnitUtils.Units reports = UnitUtils.Units.MM;
        String mode = "success";
        ControllerState state = ControllerState.IDLE;
        final List<PartialPosition> jogs = new ArrayList<>();
        PartialPosition setPosition;
        int setCalls;
        final UgsMachineGateway gateway;

        Fixture(Path file, JobPreview job) {
            this.file = file;
            this.job = job;
            Capabilities capabilities = new Capabilities() { @Override public boolean hasJogging() { return true; } };
            IController controller = (IController) Proxy.newProxyInstance(IController.class.getClassLoader(), new Class<?>[]{IController.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getCapabilities" -> capabilities;
                        case "cancelJog" -> { pendingTarget = null; state = ControllerState.IDLE; yield null; }
                        default -> throw new AssertionError(method.getName());
                    });
            BackendAPI backend = (BackendAPI) Proxy.newProxyInstance(BackendAPI.class.getClassLoader(), new Class<?>[]{BackendAPI.class},
                    (proxy, method, args) -> {
                        advance();
                        switch (method.getName()) {
                            case "getGcodeFile": case "getProcessedGcodeFile": return file.toFile();
                            case "isConnected": return state != ControllerState.DISCONNECTED;
                            case "isIdle": return state == ControllerState.IDLE;
                            case "isSendingFile": case "isPaused": return false;
                            case "getControllerState": return state;
                            case "getController": return controller;
                            case "getGcodeState": return gcode;
                            case "getMachinePosition": return position(machine);
                            case "getWorkPosition": return position(work());
                            case "setWorkPosition":
                                setPosition = ((PartialPosition) args[0]).getPositionIn(UnitUtils.Units.MM);
                                setCalls++;
                                if (!mode.equals("ignore-set")) origin = new Pose(machine.x() - setPosition.getX(), machine.y() - setPosition.getY(), origin.z());
                                return null;
                            case "adjustManualLocation":
                                PartialPosition delta = ((PartialPosition) args[0]).getPositionIn(UnitUtils.Units.MM);
                                jogs.add(delta);
                                if (mode.equals("reject")) throw new IllegalStateException("Rejected jog");
                                if (mode.equals("alarm")) { state = ControllerState.ALARM; return null; }
                                if (mode.equals("disconnect")) { state = ControllerState.DISCONNECTED; return null; }
                                if (delta.hasX()) assertTrue("XY cannot start before the Z lift", jogs.size() == 1 || machine.z() >= -5.005);
                                pendingStart = machine;
                                pendingTarget = new Pose(machine.x() + (delta.hasX() ? delta.getX() : 0),
                                        machine.y() + (delta.hasY() ? delta.getY() : 0), machine.z() + (delta.hasZ() ? delta.getZ() : 0));
                                started = System.nanoTime();
                                return null;
                            default: throw new AssertionError(method.getName());
                        }
                    });
            gateway = new UgsMachineGateway(backend);
        }

        Pose work() { return machine.minus(origin); }
        Position position(Pose p) { return new Position(p.x(), p.y(), p.z(), UnitUtils.Units.MM).getPositionIn(reports); }
        Restored restore() throws Exception { return gateway.restoreOrigin(job, gateway.captureReference(job), POINT, OFFSET); }

        void advance() {
            if (pendingTarget == null) return;
            long elapsed = (System.nanoTime() - started) / 1000000;
            if (elapsed < 15) return; // Stale Idle at the old position.
            state = ControllerState.JOG;
            if (elapsed >= 90) {
                machine = mode.equals("cancel") ? new Pose(pendingStart.x(), pendingStart.y(), (pendingStart.z() + pendingTarget.z()) / 2) : pendingTarget;
                pendingTarget = null;
                state = ControllerState.IDLE;
            }
        }
    }
}
