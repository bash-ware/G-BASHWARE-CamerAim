package com.bashworks.ugs.camera.machine;

import com.bashworks.ugs.camera.model.CameraOffset;
import com.bashworks.ugs.camera.model.LengthUnit;
import com.bashworks.ugs.camera.model.TargetPosition;
import com.bashworks.ugs.camera.model.JobPreview;
import com.bashworks.ugs.camera.model.OriginRecovery;

import java.util.function.Consumer;

public interface MachineGateway {
    MachineState state();
    TargetPosition machinePosition();
    /** Moves to the camera target and sets work XY zero after arrival. Blocks until complete. */
    void moveToolToCrosshair(CameraOffset movement, double feedRate) throws Exception;
    void setWorkXyZero() throws Exception;
    default JobPreview loadActiveJob() throws Exception { throw new UnsupportedOperationException("Open a job in UGS first."); }
    default OriginRecovery.Anchor captureReference(JobPreview job) throws Exception { throw new UnsupportedOperationException(); }
    default boolean referenceValid(JobPreview job, OriginRecovery.Anchor anchor) { return false; }
    default OriginRecovery.Restored restoreOrigin(JobPreview job, OriginRecovery.Anchor anchor,
            JobPreview.Point point, CameraOffset offset) throws Exception { throw new UnsupportedOperationException(); }
    default boolean restoredOriginValid(OriginRecovery.Restored restored) { return false; }
    default void moveToRestoredOrigin(OriginRecovery.Restored restored, double safeMachineZMm, double feedMm,
            Consumer<String> status) throws Exception { throw new UnsupportedOperationException(); }
    default void cancelReferenceMove() throws Exception { throw new UnsupportedOperationException(); }
    void moveToAimHeight(String homingCommand, double dropDistance, LengthUnit unit, double feedRate, Consumer<String> statusListener) throws Exception;
}


