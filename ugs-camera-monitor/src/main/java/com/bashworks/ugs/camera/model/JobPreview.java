package com.bashworks.ugs.camera.model;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Immutable, millimetre-based geometry from the exact processed job UGS will send. */
public record JobPreview(FileStamp source, FileStamp processed, String workSystem,
                         List<Trace> traces, List<Feature> features) {
    public JobPreview {
        traces = List.copyOf(traces);
        features = List.copyOf(features);
        if (traces.isEmpty()) throw new IllegalArgumentException("The active job has no usable XY toolpath.");
    }

    public record Point(double x, double y) {
        public Point {
            if (!Double.isFinite(x) || !Double.isFinite(y)) throw new IllegalArgumentException("Invalid XY point.");
        }
        public double distance(Point other) { return Math.hypot(x - other.x, y - other.y); }
    }

    public record Trace(Point start, Point end, boolean rapid) { }
    public record Feature(Point point, String label) { }
    public record Pick(Point point, String label) { }

    /** Features win over the nearby toolpath; otherwise project onto the nearest cutting segment. */
    public Pick pick(Point cursor, double radiusMm, boolean snap) {
        if (!snap) return new Pick(cursor, "Free point");
        Feature nearest = null;
        double distance = radiusMm;
        for (Feature f : features) {
            double d = cursor.distance(f.point());
            if (d <= distance) { nearest = f; distance = d; }
        }
        if (nearest != null) return new Pick(nearest.point(), nearest.label());
        Point best = null;
        distance = radiusMm;
        for (Trace t : traces) {
            if (t.rapid()) continue;
            double dx = t.end().x() - t.start().x(), dy = t.end().y() - t.start().y();
            double length2 = dx * dx + dy * dy;
            double u = length2 == 0 ? 0 : Math.max(0, Math.min(1,
                    ((cursor.x() - t.start().x()) * dx + (cursor.y() - t.start().y()) * dy) / length2));
            Point p = new Point(t.start().x() + u * dx, t.start().y() + u * dy);
            double d = cursor.distance(p);
            if (d <= distance) { best = p; distance = d; }
        }
        return best == null ? null : new Pick(best, "Toolpath");
    }

    public record FileStamp(Path path, long size, long modified, String digest) {
        public static FileStamp capture(Path path) throws IOException {
            Path real = path.toRealPath();
            long size = Files.size(real), modified = Files.getLastModifiedTime(real).toMillis();
            String digest = hash(real);
            FileStamp stamp = new FileStamp(real, size, modified, digest);
            if (!stamp.matchesMetadata()) throw new IOException("Job changed while loading. Reopen the preview.");
            return stamp;
        }
        public boolean matchesMetadata() {
            try { return Files.size(path) == size && Files.getLastModifiedTime(path).toMillis() == modified; }
            catch (IOException e) { return false; }
        }
        public boolean matchesContent() throws IOException { return matchesMetadata() && hash(path).equals(digest); }
        private static String hash(Path path) throws IOException {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream input = Files.newInputStream(path)) {
                    byte[] buffer = new byte[65536];
                    for (int n; (n = input.read(buffer)) >= 0;) digest.update(buffer, 0, n);
                }
                return HexFormat.of().formatHex(digest.digest());
            } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        }
    }
}
