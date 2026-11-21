package com.bashworks.ugs.camera.ui;

import com.bashworks.ugs.camera.machine.JobPreviewLoader;
import com.bashworks.ugs.camera.machine.MachineGateway;
import com.bashworks.ugs.camera.machine.MachineState;
import com.bashworks.ugs.camera.model.*;
import com.bashworks.ugs.camera.model.OriginRecovery.*;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.*;

public class OriginRecoveryDialogTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void pointSelectionArmsSingleButtonThatRestoresThenMovesXY() throws Exception {
        exercise(false);
    }

    @Test public void rejectedReferenceNeverStartsXY() throws Exception {
        exercise(true);
    }

    private void exercise(boolean reject) throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        Path file = temp.newFile("demonstration-job.nc").toPath();
        Files.writeString(file, "G21 G54 G90\nG0 X0 Y0\nG1 X80 Y0 F200\nG1 X80 Y50\nG1 X0 Y50\nG1 X0 Y0\nG0 X25 Y25\nG2 X25 Y25 I-5 J0\n");
        JobPreview job = new JobPreviewLoader().loadText(file);
        FakeMachine fake = new FakeMachine(job);
        fake.reject = reject;
        AtomicReference<OriginRecoveryDialog> handle = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                OriginRecoveryDialog dialog = new OriginRecoveryDialog(null, fake, job,
                        new CameraOffset(30, -10, LengthUnit.MM), 500, () -> { });
                handle.set(dialog);
                JButton restore = find(dialog, JButton.class, "Re-reference XY & move to X0 Y0");
                assertFalse(restore.isEnabled());
                assertNull(find(dialog, JSpinner.class, null));
                assertEquals(0, fake.restores);
                assertEquals(0, fake.moves);
                JobPreviewPanel preview = find(dialog, JobPreviewPanel.class, null);
                preview.fit();
                Point2D point = preview.toScreen(new JobPreview.Point(80, 50));
                preview.dispatchEvent(new MouseEvent(preview, MouseEvent.MOUSE_CLICKED, 0, 0,
                        (int) point.getX(), (int) point.getY(), 1, false, MouseEvent.BUTTON1));
                assertTrue(restore.isEnabled());
                assertEquals(0, fake.restores);
                assertEquals(0, fake.moves);
                save(dialog, "origin-recovery-armed.png");
                restore.doClick();
            });
            await(() -> fake.restores == 1 && !find(handle.get(), JButton.class, "Stop move").isEnabled());
            assertEquals(reject ? 0 : 1, fake.moves);
        } finally {
            SwingUtilities.invokeAndWait(() -> { if (handle.get() != null) handle.get().requestClose(); });
        }
    }

    private static void save(OriginRecoveryDialog dialog, String name) {
        try {
            Container content = dialog.getContentPane();
            BufferedImage image = new BufferedImage(content.getWidth(), content.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics();
            content.printAll(graphics);
            graphics.dispose();
            Path path = Path.of("..", ".verify", name);
            Files.createDirectories(path.getParent());
            ImageIO.write(image, "png", path.toFile());
        } catch (Exception e) { throw new AssertionError(e); }
    }

    private interface Check { boolean value(); }
    private static void await(Check check) throws Exception {
        long deadline = System.nanoTime() + 3000000000L;
        while (System.nanoTime() < deadline) {
            AtomicReference<Boolean> value = new AtomicReference<>(false);
            SwingUtilities.invokeAndWait(() -> value.set(check.value()));
            if (value.get()) return;
            Thread.sleep(20);
        }
        fail("UI action did not complete");
    }

    private static <T extends Component> T find(Container parent, Class<T> type, String text) {
        for (Component component : parent.getComponents()) {
            if (type.isInstance(component) && (text == null
                    || component instanceof AbstractButton && text.equals(((AbstractButton) component).getText())))
                return type.cast(component);
            if (component instanceof Container) {
                T child = find((Container) component, type, text);
                if (child != null) return child;
            }
        }
        return null;
    }

    private static final class FakeMachine implements MachineGateway {
        final JobPreview job;
        volatile int restores, moves;
        boolean reject;
        FakeMachine(JobPreview job) { this.job = job; }
        @Override public MachineState state() { return new MachineState(true, true, false, false, true); }
        @Override public TargetPosition machinePosition() { return new TargetPosition(100, 200, LengthUnit.MM); }
        @Override public void moveToolToCrosshair(CameraOffset offset, double feed) { fail("Unrelated zeroing must not run"); }
        @Override public void setWorkXyZero() { fail("Must restore selected job coordinates, not zero here"); }
        @Override public void moveToAimHeight(String command, double drop, LengthUnit unit, double feed, Consumer<String> status) { fail("Recovery must not rehome"); }
        @Override public Anchor captureReference(JobPreview job) { return new Anchor(new Pose(100, 200, -30), new Pose(0, 0, 20), "G54", 0, this); }
        @Override public boolean referenceValid(JobPreview job, Anchor anchor) { return true; }
        @Override public Restored restoreOrigin(JobPreview job, Anchor anchor, JobPreview.Point point, CameraOffset offset) {
            restores++;
            if (reject) throw new IllegalStateException("Controller rejected origin change");
            assertEquals(new JobPreview.Point(80, 50), point);
            return new Restored(job, OriginRecovery.recoveredOrigin(anchor, point, offset), "G54", 0, this);
        }
        @Override public boolean restoredOriginValid(Restored restored) { return true; }
        @Override public void moveToRestoredOrigin(Restored restored, double feed, Consumer<String> status) { assertEquals(1, restores); moves++; }
        @Override public void cancelReferenceMove() { }
    }
}
