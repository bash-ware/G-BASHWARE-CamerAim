package com.bashworks.ugs.camera.machine;

import com.bashworks.ugs.camera.model.CameraOffset;
import com.bashworks.ugs.camera.model.LengthUnit;
import com.bashworks.ugs.camera.model.TargetPosition;
import com.bashworks.ugs.camera.model.JobPreview;
import com.bashworks.ugs.camera.model.OriginRecovery;
import com.bashworks.ugs.camera.model.OriginRecovery.*;
import com.willwinder.universalgcodesender.gcode.util.Code;
import com.willwinder.universalgcodesender.model.UGSEvent;
import com.willwinder.universalgcodesender.model.events.FileStateEvent;
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
import java.util.concurrent.atomic.AtomicLong;

public final class UgsMachineGateway implements MachineGateway {
    private final BackendAPI backend;
    private final AtomicLong recoveryEpoch = new AtomicLong();

    public UgsMachineGateway(BackendAPI backend) {
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    /** Called by the existing TopComponent event subscription (no extra listener lifetime). */
    public void onMachineEvent(UGSEvent event) {
        if (event instanceof FileStateEvent || event instanceof AlarmEvent
                || (event instanceof ControllerStateEvent && switch (((ControllerStateEvent) event).getState()) {
                    case DISCONNECTED, CONNECTING, ALARM, HOME -> true;
                    default -> false;
                })) recoveryEpoch.incrementAndGet();
    }

    @Override
    public JobPreview loadActiveJob() throws Exception {
        if (backend.getGcodeFile() == null || backend.getProcessedGcodeFile() == null)
            throw new IllegalStateException("Open the job in UGS before recovering its origin.");
        JobPreview job = new JobPreviewLoader().load(backend.getGcodeFile().toPath(), backend.getProcessedGcodeFile().toPath());
        requireJob(job, true);
        return job;
    }

    private boolean jobCurrent(JobPreview job) {
        try {
            return backend.getGcodeFile() != null && backend.getProcessedGcodeFile() != null
                    && backend.getGcodeFile().toPath().toRealPath().equals(job.source().path())
                    && backend.getProcessedGcodeFile().toPath().toRealPath().equals(job.processed().path())
                    && job.source().matchesMetadata() && job.processed().matchesMetadata();
        } catch (Exception e) { return false; }
    }

    private void requireJob(JobPreview job, boolean content) throws Exception {
        if (!jobCurrent(job) || (content && (!job.source().matchesContent() || !job.processed().matchesContent())))
            throw new IllegalStateException("The active job changed. Reopen the reference preview.");
        if (backend.getGcodeState() == null || !job.workSystem().equals(backend.getGcodeState().offset.toString()))
            throw new IllegalStateException("Select " + job.workSystem() + " in UGS to match the job before referencing.");
    }

    private void requireRecoveryIdle() {
        if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Origin operation cancelled.");
        MachineState current = state();
        if (!current.canPosition()) throw new IllegalStateException(current.blockedReason());
        if (backend.getGcodeState() == null || backend.getGcodeState().spindle != Code.M5)
            throw new IllegalStateException("Stop the spindle before referencing or moving to the origin.");
    }

    private static Pose pose(Position p) {
        Position mm = p.getPositionIn(UnitUtils.Units.MM);
        return new Pose(mm.x, mm.y, mm.z);
    }

    @Override
    public Anchor captureReference(JobPreview job) throws Exception {
        requireRecoveryIdle();
        requireJob(job, false);
        return new Anchor(pose(backend.getMachinePosition()), pose(backend.getWorkPosition()),
                job.workSystem(), recoveryEpoch.get(), backend.getController());
    }

    @Override
    public boolean referenceValid(JobPreview job, Anchor anchor) {
        try {
            requireRecoveryIdle();
            requireJob(job, false);
            return anchor.epoch() == recoveryEpoch.get() && anchor.controller() == backend.getController()
                    && anchor.workSystem().equals(job.workSystem())
                    && anchor.machine().near(pose(backend.getMachinePosition()))
                    && anchor.work().near(pose(backend.getWorkPosition()));
        } catch (Exception e) { return false; }
    }

    @Override
    public synchronized Restored restoreOrigin(JobPreview job, Anchor anchor, JobPreview.Point point, CameraOffset offset) throws Exception {
        requireJob(job, true);
        if (!referenceValid(job, anchor))
            throw new IllegalStateException("Camera position or machine state changed. Aim again and reselect the feature.");
        Pose work = OriginRecovery.workAtCamera(anchor, point, offset);
        Pose origin = OriginRecovery.recoveredOrigin(anchor, point, offset);
        backend.setWorkPosition(new PartialPosition(work.x(), work.y(), UnitUtils.Units.MM));
        // setWorkPosition only queues the command. Do not offer motion until the
        // controller actually reports the recovered frame, with Z unaffected.
        Restored restored = new Restored(job, origin, job.workSystem(), anchor.epoch(), anchor.controller());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Referencing cancelled.");
            requireRecoveryIdle();
            requireJob(job, false);
            if (!anchor.machine().near(pose(backend.getMachinePosition())) || anchor.epoch() != recoveryEpoch.get())
                throw new IllegalStateException("Machine changed while setting the origin. Recovery was not confirmed.");
            if (restoredOriginValid(restored)) return restored;
            Thread.sleep(50);
        }
        throw new TimeoutException("UGS did not confirm the new XY origin. No move was started.");
    }

    @Override
    public boolean restoredOriginValid(Restored restored) {
        try {
            requireRecoveryIdle();
            requireJob(restored.job(), false);
            return frameValid(restored);
        } catch (Exception e) { return false; }
    }

    private boolean frameValid(Restored restored) {
        return restored.epoch() == recoveryEpoch.get() && restored.controller() == backend.getController()
                && restored.origin().near(pose(backend.getMachinePosition()).minus(pose(backend.getWorkPosition())));
    }

    @Override
    public synchronized void moveToRestoredOrigin(Restored restored, double feedMm,
                                                 Consumer<String> status) throws Exception {
        if (!Double.isFinite(feedMm) || feedMm <= 0)
            throw new IllegalArgumentException("Enter a positive feed rate.");
        requireJob(restored.job(), true);
        if (!restoredOriginValid(restored)) throw new IllegalStateException("Recovered origin is no longer valid. Reference again.");
        Pose start = pose(backend.getMachinePosition());
        double dx = restored.origin().x() - start.x(), dy = restored.origin().y() - start.y();
        status.accept("Moving XY to the restored origin…");
        requireRecoveryIdle();
        backend.adjustManualLocation(new PartialPosition(dx, dy, UnitUtils.Units.MM), feedMm);
        waitForRecoveryTarget(new Pose(restored.origin().x(), restored.origin().y(), start.z()), restored,
                timeoutFor(Math.hypot(dx, dy), feedMm));
        status.accept("At restored X0 Y0; Z unchanged.");
    }

    private long timeoutFor(double distanceMm, double feedMm) {
        return Math.min(3600000L, (long) Math.ceil(60000 * distanceMm / feedMm) + 30000L);
    }

    private void waitForRecoveryTarget(Pose target, Restored restored, long timeoutMs) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        boolean moving = false;
        try {
            while (System.nanoTime() < deadline) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Origin move cancelled.");
                requireJob(restored.job(), false);
                MachineState current = state();
                ControllerState cs = backend.getControllerState();
                if (!current.connected() || current.paused() || current.sendingFile()
                        || (cs != ControllerState.IDLE && cs != ControllerState.JOG && cs != ControllerState.RUN)
                        || backend.getGcodeState().spindle != Code.M5 || !frameValid(restored))
                    throw new IllegalStateException("Machine state or recovered frame changed. Origin move stopped.");
                Pose now = pose(backend.getMachinePosition());
                if (current.canPosition()) {
                    if (now.near(target)) return;
                    if (moving) throw new IllegalStateException("Origin move stopped before reaching the target.");
                } else moving = true;
                Thread.sleep(50);
            }
            throw new TimeoutException("Origin move timed out.");
        } catch (Exception e) {
            try { backend.getController().cancelJog(); } catch (Exception ignored) { }
            throw e;
        }
    }

    @Override
    public void cancelReferenceMove() throws Exception {
        recoveryEpoch.incrementAndGet();
        backend.getController().cancelJog();
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

        TargetPosition start = machinePosition();
        TargetPosition target = movement.targetFrom(start.x(), start.y(), start.unit());
        backend.adjustManualLocation(
                new PartialPosition(movement.x(), movement.y(), toUgsUnit(movement.unit())),
                feedRate);

        // Sending a jog returns before motion starts. Require the target position
        // as well as Idle so a stale Idle report cannot zero the starting point.
        double distance = Math.hypot(movement.x(), movement.y());
        long timeoutMs = (long) Math.ceil(60000.0 * distance / feedRate) + 30000;
        waitForTarget(target, start, timeoutMs);
        setWorkXyZero();
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

    private void waitForTarget(TargetPosition target, TargetPosition start, long timeoutMs) throws Exception {
        double distanceMm = target.unit().convert(Math.hypot(target.x() - start.x(), target.y() - start.y()), LengthUnit.MM);
        double toleranceMm = Math.min(0.002, distanceMm / 2.0);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        boolean motionObserved = false;
        while (System.nanoTime() < deadline) {
            MachineState current = state();
            ControllerState controllerState = backend.getControllerState();
            if (!current.connected() || current.sendingFile() || current.paused()
                    || (controllerState != ControllerState.IDLE && controllerState != ControllerState.JOG
                    && controllerState != ControllerState.RUN)) {
                throw new IllegalStateException("Camera move interrupted; XY zero was not set.");
            }
            TargetPosition position = machinePosition();
            double xMm = position.unit().convert(position.x(), LengthUnit.MM);
            double yMm = position.unit().convert(position.y(), LengthUnit.MM);
            boolean atTarget = Math.abs(xMm - target.unit().convert(target.x(), LengthUnit.MM)) <= toleranceMm
                    && Math.abs(yMm - target.unit().convert(target.y(), LengthUnit.MM)) <= toleranceMm;
            if (current.canPosition()) {
                if (atTarget) return;
                if (motionObserved) {
                    throw new IllegalStateException("Camera move stopped before the target; XY zero was not set.");
                }
            } else {
                motionObserved = true;
            }
            Thread.sleep(50);
        }
        throw new TimeoutException("Camera target was not reached; XY zero was not set.");
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

