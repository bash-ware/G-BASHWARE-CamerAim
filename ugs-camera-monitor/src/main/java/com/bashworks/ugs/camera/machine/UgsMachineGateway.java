package com.bashworks.ugs.camera.machine;

import com.bashworks.ugs.camera.model.CameraOffset;
import com.bashworks.ugs.camera.model.LengthUnit;
import com.bashworks.ugs.camera.model.TargetPosition;
import com.willwinder.universalgcodesender.listeners.ControllerState;
import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.model.PartialPosition;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UnitUtils;

import java.util.Objects;

public final class UgsMachineGateway implements MachineGateway {
    private final BackendAPI backend;

    public UgsMachineGateway(BackendAPI backend) {
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    @Override
    public MachineState state() {
        boolean connected = backend.isConnected();
        boolean idle = connected && backend.isIdle() && backend.getControllerState() == ControllerState.IDLE;
        boolean joggingSupported = connected
                && backend.getController() != null
                && backend.getController().getCapabilities() != null
                && backend.getController().getCapabilities().hasJogging();
        return new MachineState(connected, idle, backend.isSendingFile(), backend.isPaused(), joggingSupported);
    }

    @Override
    public TargetPosition machinePosition() {
        Position position = backend.getMachinePosition();
        return new TargetPosition(position.getX(), position.getY(), fromUgsUnit(position.getUnits()));
    }

    @Override
    public void moveToolToCrosshair(CameraOffset movement, double feedRate) throws Exception {
        MachineState current = state();
        if (!current.canPosition()) {
            throw new IllegalStateException(current.blockedReason());
        }
        if (!Double.isFinite(feedRate) || feedRate <= 0) {
            throw new IllegalArgumentException("Jog feed must be greater than zero.");
        }

        backend.adjustManualLocation(
                new PartialPosition(movement.x(), movement.y(), toUgsUnit(movement.unit())),
                feedRate);
    }

    @Override
    public void setWorkXyZero() throws Exception {
        MachineState current = state();
        if (!current.canPosition()) {
            throw new IllegalStateException(current.blockedReason());
        }
        UnitUtils.Units unit = backend.getWorkPosition().getUnits();
        backend.setWorkPosition(new PartialPosition(0.0, 0.0, unit));
    }

    private static UnitUtils.Units toUgsUnit(LengthUnit unit) {
        return unit == LengthUnit.INCH ? UnitUtils.Units.INCH : UnitUtils.Units.MM;
    }

    private static LengthUnit fromUgsUnit(UnitUtils.Units unit) {
        return unit == UnitUtils.Units.INCH ? LengthUnit.INCH : LengthUnit.MM;
    }
}
