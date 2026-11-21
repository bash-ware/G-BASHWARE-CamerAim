package com.bashworks.ugs.camera.machine;

import com.bashworks.ugs.camera.model.JobPreview;
import com.bashworks.ugs.camera.model.JobPreview.*;
import com.willwinder.universalgcodesender.gcode.DefaultCommandCreator;
import com.willwinder.universalgcodesender.gcode.GcodeParser;
import com.willwinder.universalgcodesender.gcode.util.Plane;
import com.willwinder.universalgcodesender.model.Position;
import com.willwinder.universalgcodesender.model.UnitUtils;
import com.willwinder.universalgcodesender.types.PointSegment;
import com.willwinder.universalgcodesender.utils.GcodeStreamReader;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Uses UGS's parser, but refuses coordinate transforms it cannot safely reconstruct. */
public final class JobPreviewLoader {
    private static final Pattern WORD = Pattern.compile("([A-Z])([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))");
    private static final Set<Double> SUPPORTED_G = Set.of(0d, 1d, 2d, 3d, 4d, 17d, 20d, 21d, 40d,
            43d, 43.1, 49d, 54d, 55d, 56d, 57d, 58d, 59d, 61d, 61.1, 64d, 80d,
            90d, 91d, 90.1, 91.1, 93d, 94d, 95d, 98d, 99d);
    private static final int MAX_GEOMETRY = 750000;

    public JobPreview load(Path sourcePath, Path processedPath) throws Exception {
        FileStamp source = FileStamp.capture(sourcePath), processed = FileStamp.capture(processedPath);
        Builder builder = new Builder();
        // The processed file is a UGS GcodeStream, not ordinary G-code text.
        try (var input = Files.newInputStream(processed.path());
             var reader = new GcodeStreamReader(input, new DefaultCommandCreator())) {
            while (reader.getNumRowsRemaining() > 0) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                builder.add(reader.getNextCommand().getCommandString());
            }
        }
        if (!source.matchesContent() || !processed.matchesContent())
            throw new IOException("The active job changed while loading. Reopen the preview.");
        return builder.finish(source, processed);
    }

    /** Text entry point for standalone previews and parser tests. */
    public JobPreview loadText(Path path) throws Exception {
        FileStamp stamp = FileStamp.capture(path);
        Builder builder = new Builder();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            for (String line; (line = reader.readLine()) != null;) builder.add(line);
        }
        return builder.finish(stamp, stamp);
    }

    private static final class Builder {
        private final GcodeParser parser = new GcodeParser();
        private final List<Trace> traces = new ArrayList<>();
        private final List<Feature> features = new ArrayList<>();
        private Point previous;
        private String workSystem;
        private boolean unitsKnown;
        private int line;

        void add(String raw) throws Exception {
            line++;
            String text = raw.replaceAll("\\([^)]*\\)", "").split(";", 2)[0].toUpperCase(java.util.Locale.ROOT).trim();
            if (text.isEmpty() || text.equals("%")) return;
            if (text.matches(".*[#\\[\\]{}:].*") || text.contains("(") || text.contains(")"))
                reject("Macros, expressions, and malformed comments are not supported");
            Matcher matcher = WORD.matcher(text);
            List<String> words = new ArrayList<>();
            boolean machineMove = false, xy = false;
            int end = 0;
            while (matcher.find()) {
                if (!text.substring(end, matcher.start()).isBlank()) reject("Unrecognized G-code");
                end = matcher.end();
                String letter = matcher.group(1);
                double value = Double.parseDouble(matcher.group(2));
                if (!Double.isFinite(value)) reject("Invalid numeric value");
                if (letter.equals("G")) {
                    if (value == 53 || value == 28 || value == 30) machineMove = true;
                    else if (!SUPPORTED_G.contains(value)) reject("Unsupported coordinate/motion command G" + value);
                    if (value == 20 || value == 21) unitsKnown = true;
                }
                if (letter.equals("M") && (value == 97 || value == 98 || value == 99)) reject("Subprograms are not supported");
                if ("ABCUVW".contains(letter)) reject("Rotary and auxiliary axes are not supported");
                if (letter.equals("O")) reject("Subprograms are not supported");
                if (letter.equals("X") || letter.equals("Y")) xy = true;
                words.add(matcher.group());
            }
            if (!text.substring(end).isBlank()) reject("Unrecognized G-code");
            if (machineMove) {
                if (xy || words.stream().noneMatch(w -> w.startsWith("Z")))
                    reject("Machine-coordinate XY moves cannot be used for origin recovery");
                // Z-only machine retracts are outside the job's XY frame. Retain modal words.
                words.removeIf(w -> w.startsWith("Z") || w.matches("G0*(53|28|30)(\\.0*)?"));
            }
            if (xy && !unitsKnown) reject("The job must specify G20 or G21 before XY movement");
            for (GcodeParser.GcodeMeta meta : parser.addCommand(String.join(" ", words))) {
                PointSegment segment = meta.point;
                if (segment == null) continue;
                Position p = segment.point().getPositionIn(UnitUtils.Units.MM);
                if (!Double.isFinite(p.x) || !Double.isFinite(p.y)) { previous = null; continue; }
                String wcs = meta.state.offset.toString();
                if (workSystem != null && !workSystem.equals(wcs)) reject("Jobs using multiple work coordinate systems are not supported");
                workSystem = wcs;
                Point next = new Point(p.x, p.y);
                if (!segment.isFastTraverse()) features.add(new Feature(next, "Endpoint / corner"));
                if (previous != null) {
                    if (!segment.isFastTraverse()) features.add(new Feature(previous, "Endpoint / corner"));
                    if (segment.isArc()) addArc(previous, next, segment);
                    else if (previous.distance(next) > 0) traces.add(new Trace(previous, next, segment.isFastTraverse()));
                }
                previous = next;
                if (traces.size() + features.size() > MAX_GEOMETRY) reject("Job too large for the reference preview");
            }
        }

        private void addArc(Point start, Point end, PointSegment segment) {
            if (segment.getPlaneState() != Plane.XY) reject("Only XY-plane arcs are supported");
            Position center = segment.center().getPositionIn(UnitUtils.Units.MM);
            Point c = new Point(center.x, center.y);
            double radius = start.distance(c);
            if (radius <= 0 || Math.abs(end.distance(c) - radius) > Math.max(0.01, radius * 0.001))
                reject("Invalid arc geometry");
            double a = Math.atan2(start.y() - c.y(), start.x() - c.x());
            double b = Math.atan2(end.y() - c.y(), end.x() - c.x());
            double sweep = b - a;
            if (segment.isClockwise()) { while (sweep >= -1e-12) sweep -= Math.PI * 2; }
            else { while (sweep <= 1e-12) sweep += Math.PI * 2; }
            // Chord error <= 0.001 mm so zoomed path selection remains useful.
            double step = 2 * Math.acos(Math.max(-1, Math.min(1, 1 - 0.001 / radius)));
            int count = (int) Math.ceil(Math.abs(sweep) / Math.min(0.1, step));
            if (count > 100000 || count + traces.size() > MAX_GEOMETRY) reject("Arc too large for the reference preview");
            Point last = start;
            for (int i = 1; i <= count; i++) {
                double angle = a + sweep * i / count;
                Point p = i == count ? end : new Point(c.x() + radius * Math.cos(angle), c.y() + radius * Math.sin(angle));
                traces.add(new Trace(last, p, false));
                last = p;
            }
            features.add(new Feature(c, "Arc center"));
        }

        JobPreview finish(FileStamp source, FileStamp processed) {
            if (traces.stream().noneMatch(t -> !t.rapid())) reject("No cutting XY path found");
            return new JobPreview(source, processed, workSystem, traces, features);
        }
        private void reject(String reason) { throw new IllegalArgumentException(reason + " (line " + line + ")."); }
    }
}
