package com.bashworks.ugs.camera.machine;

import com.bashworks.ugs.camera.model.CameraOffset;
import com.bashworks.ugs.camera.model.LengthUnit;
import com.bashworks.ugs.camera.model.TargetPosition;
import com.willwinder.universalgcodesender.listeners.ControllerState;
import com.willwinder.universalgcodesender.listeners.UGSEventListener;
import com.willwinder.universalgcodesender.model.Axis;
import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.model.PartialPosition;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UnitUtils;
import com.willwinder.universalgcodesender.model.events.AlarmEvent;
import com.willwinder.universalgcodesender.model.events.CommandEvent;
import com.willwinder.universalgcodesender.model.events.CommandEventType;
import com.willwinder.universalgcodesender.model.events.ControllerStateEvent;
import com.willwinder.universalgcodesender.types.GcodeCommand;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

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

    @Override
    public void moveToAimHeight(String homingCommand, double dropDistance, LengthUnit unit, double feedRate, Consumer<String> statusListener) throws Exception {
        MachineState current = state();
        if (!current.canPosition()) {
            throw new IllegalStateException(current.blockedReason());
        }
        if (homingCommand == null || homingCommand.trim().isEmpty()) {
            throw new IllegalArgumentException("Homing command must not be blank.");
        }
        if (!Double.isFinite(dropDistance) || dropDistance < 0) {
            throw new IllegalArgumentException("Aim drop distance must be non-negative.");
        }
        if (!Double.isFinite(feedRate) || feedRate <= 0) {
            throw new IllegalArgumentException("Jog feed must be greater than zero.");
        }

        String cmd = homingCommand.trim();
        if (statusListener != null) {
            statusListener.accept("Homing Z with " + cmd + "…");
        }

        executeHoming(cmd);

        if (dropDistance > 0) {
            if (statusListener != null) {
                statusListener.accept(String.format(Locale.US, "Lowering Z by %.3f %s…", dropDistance, unit));
            }
            backend.adjustManualLocation(
                    PartialPosition.from(Axis.Z, -dropDistance, toUgsUnit(unit)),
                    feedRate);
            waitForIdle(30000);
        }

        if (statusListener != null) {
            statusListener.accept("Aim height reached");
        }
    }

    private void executeHoming(String cmd) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        GcodeCommand[] commandHolder = new GcodeCommand[1];

        UGSEventListener listener = event -> {
            if (event instanceof AlarmEvent) {
                failure.set(new IllegalStateException("Controller reported ALARM during homing."));
                latch.countDown();
            } else if (event instanceof ControllerStateEvent) {
                ControllerStateEvent cse = (ControllerStateEvent) event;
                if (cse.getState() == ControllerState.ALARM) {
                    failure.set(new IllegalStateException("Controller entered ALARM state."));
                    latch.countDown();
                } else if (cmd.equalsIgnoreCase("$H") && cse.getState() == ControllerState.IDLE) {
                    latch.countDown();
                }
            } else if (event instanceof CommandEvent) {
                CommandEvent ce = (CommandEvent) event;
                GcodeCommand command = commandHolder[0];
                if (command != null && ce.getCommand() == command && ce.getCommandEventType() == CommandEventType.COMMAND_COMPLETE) {
                    if (command.isError()) {
                        String resp = command.getResponse() != null ? command.getResponse() : "Unknown controller error";
                        failure.set(new IllegalStateException("Homing command '" + cmd + "' rejected: " + resp));
                    }
                    latch.countDown();
                }
            }
        };

        backend.addUGSEventListener(listener);
        try {
            if (cmd.equalsIgnoreCase("$H")) {
                backend.performHomingCycle();
            } else {
                GcodeCommand command = backend.getController().createCommand(cmd);
                commandHolder[0] = command;
                backend.sendGcodeCommand(command);
            }

            boolean completed = latch.await(90, TimeUnit.SECONDS);
            if (!completed) {
                throw new TimeoutException("Homing timed out after 90 seconds.");
            }
            if (failure.get() != null) {
                Throwable t = failure.get();
                if (t instanceof Exception) {
                    throw (Exception) t;
                }
                throw new RuntimeException(t);
            }
            waitForIdle(15000);
        } finally {
            backend.removeUGSEventListener(listener);
        }
    }

    private void waitForIdle(long timeoutMs) throws Exception {
        long start = System.currentTimeMillis();
        Thread.sleep(150);
        while (System.currentTimeMillis() - start < timeoutMs) {
            ControllerState state = backend.getControllerState();
            if (state == ControllerState.ALARM) {
                throw new IllegalStateException("Controller reported ALARM.");
            }
            if (state == ControllerState.DISCONNECTED) {
                throw new IllegalStateException("Controller disconnected.");
            }
            if (state == ControllerState.IDLE && backend.isIdle()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new TimeoutException("Timed out waiting for machine to become idle.");
    }

    private static UnitUtils.Units toUgsUnit(LengthUnit unit) {
        return unit == LengthUnit.INCH ? UnitUtils.Units.INCH : UnitUtils.Units.MM;
    }

    private static LengthUnit fromUgsUnit(UnitUtils.Units unit) {
        return unit == UnitUtils.Units.INCH ? LengthUnit.INCH : LengthUnit.MM;
    }
}

