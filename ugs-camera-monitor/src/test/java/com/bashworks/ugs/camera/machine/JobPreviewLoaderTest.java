package com.bashworks.ugs.camera.machine;

import com.bashworks.ugs.camera.model.JobPreview;
import com.bashworks.ugs.camera.model.JobPreview.Point;
import com.willwinder.universalgcodesender.utils.GcodeStreamWriter;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class JobPreviewLoaderTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private JobPreview parse(String commands) throws Exception {
        Path file = temp.newFile().toPath();
        Files.writeString(file, commands);
        return new JobPreviewLoader().loadText(file);
    }

    @Test public void parsesMetricIncrementalAndUnitChangesWithoutInventingInitialTravel() throws Exception {
        JobPreview job = parse("G21 G54 G90\nG0 X10 Y20\nG1 X20 Y20 F100\nG91\nG1 X5 Y-5\nG20 G90\nG1 X1 Y1\n");
        assertEquals("G54", job.workSystem());
        assertEquals(3, job.traces().size());
        assertEquals(new Point(10, 20), job.traces().get(0).start());
        assertEquals(new Point(25, 15), job.traces().get(1).end());
        assertEquals(25.4, job.traces().get(2).end().x(), 1e-9);
    }

    @Test public void rendersFullCircleAndSnapsToItsExactCenterAndEndpoint() throws Exception {
        JobPreview job = parse("G21 G90\nG0 X15 Y10\nG2 X15 Y10 I-5 J0 F100\n");
        assertTrue(job.traces().size() > 100);
        assertEquals(new Point(15, 10), job.traces().get(job.traces().size() - 1).end());
        assertEquals(new Point(10, 10), job.pick(new Point(10.01, 10.02), 0.1, true).point());
        assertEquals("Arc center", job.pick(new Point(10, 10), 0.1, true).label());
        assertEquals(new Point(15, 10), job.pick(new Point(15.01, 10), 0.1, true).point());
        assertNull(job.pick(new Point(100, 100), 0.1, true));
        assertEquals(new Point(100, 100), job.pick(new Point(100, 100), 0.1, false).point());
    }

    @Test public void rendersRadiusAndAbsoluteCenterArcs() throws Exception {
        JobPreview radius = parse("G21 G90\nG0 X0 Y0\nG3 X10 Y0 R5 F100\n");
        assertTrue(radius.traces().stream().anyMatch(t -> t.end().y() < -4.9));
        JobPreview absolute = parse("G21 G90 G90.1\nG0 X15 Y10\nG3 X10 Y15 I10 J10 F100\n");
        assertEquals(new Point(10, 10), absolute.pick(new Point(10, 10), 0.1, true).point());
    }

    @Test public void snapsOntoCuttingSegmentAndIgnoresRapidTravel() throws Exception {
        JobPreview job = parse("G21\nG0 X0 Y0\nG1 X10 Y0 F100\nG0 X10 Y10\n");
        assertEquals(new Point(5, 0), job.pick(new Point(5, 0.02), 0.1, true).point());
        assertNull(job.pick(new Point(10, 5), 0.1, true));
    }

    @Test public void usesProcessedStreamRatherThanOriginalGeometry() throws Exception {
        Path source = temp.newFile().toPath(), stream = temp.newFile().toPath();
        Files.writeString(source, "G21\nG0 X0 Y0\nG1 X10 Y0\n");
        try (GcodeStreamWriter writer = new GcodeStreamWriter(stream.toFile())) {
            writer.addLine("G21", "G21", "", 1);
            writer.addLine("G0 X0 Y0", "G0 X100 Y200", "", 2);
            writer.addLine("G1 X10 Y0", "G1 X110 Y200 F100", "", 3);
        }
        JobPreview job = new JobPreviewLoader().load(source, stream);
        assertEquals(new Point(100, 200), job.traces().get(0).start());
        assertEquals(new Point(110, 200), job.traces().get(0).end());
    }

    @Test public void ignoresZOnlyMachineRetractsButPreservesXYGeometry() throws Exception {
        JobPreview job = parse("G21 G90\nG53 G0 Z0\nG0 X10 Y10\nG1 X20 Y10 F100\nG91 G28 Z0\nG90\nG1 X20 Y20\n");
        assertEquals(2, job.traces().size());
        assertEquals(new Point(20, 20), job.traces().get(1).end());
    }

    @Test public void rejectsUnsupportedOrAmbiguousCoordinateFrames() throws Exception {
        for (String unsupported : new String[]{"G92 X0", "G10 L20 P1 X0", "G68 R30", "G51 X2", "G41", "G81 X2 Y2 Z-3",
                "G53 G0 X0 Y0", "G28", "M98 P100", "G1 A90", "G1 X[#1]", "G18 G2 X20 Z5 I5 K0", "G55\nG1 X20 Y10"}) {
            try {
                parse("G21 G54 G90\nG0 X0 Y0\nG1 X10 Y0 F100\n" + unsupported + "\n");
                fail("Must reject " + unsupported);
            } catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("line")); }
        }
        try { parse("G0 X0 Y0\nG1 X10 Y0\n"); fail("Explicit units required"); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("G20 or G21")); }
    }
}
