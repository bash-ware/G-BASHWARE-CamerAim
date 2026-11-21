package com.bashworks.ugs.camera.model;

/** Coordinates are always mm, independent of the display, job, and controller units. */
public final class OriginRecovery {
    private OriginRecovery() { }
    public static final double TOLERANCE_MM = 0.005;

    public record Pose(double x, double y, double z) {
        public Pose {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
                throw new IllegalArgumentException("A valid machine/work XYZ report is required.");
        }
        public boolean near(Pose other) {
            return Math.abs(x - other.x) <= TOLERANCE_MM && Math.abs(y - other.y) <= TOLERANCE_MM
                    && Math.abs(z - other.z) <= TOLERANCE_MM;
        }
        public Pose minus(Pose other) { return new Pose(x - other.x, y - other.y, z - other.z); }
    }

    public record Anchor(Pose machine, Pose work, String workSystem, long epoch, Object controller) { }
    public record Restored(JobPreview job, Pose origin, String workSystem, long epoch, Object controller) { }

    public static Pose workAtCamera(Anchor anchor, JobPreview.Point selected, CameraOffset offset) {
        CameraOffset mm = offset.in(LengthUnit.MM);
        // The physical feature is at M + camera-to-tool movement. At the current
        // tool position the new work coordinate must therefore be Q - movement.
        return new Pose(selected.x() - mm.x(), selected.y() - mm.y(), anchor.work().z());
    }

    public static Pose recoveredOrigin(Anchor anchor, JobPreview.Point selected, CameraOffset offset) {
        return anchor.machine().minus(workAtCamera(anchor, selected, offset));
    }
}
