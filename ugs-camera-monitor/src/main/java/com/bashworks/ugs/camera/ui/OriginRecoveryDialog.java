package com.bashworks.ugs.camera.ui;

import com.bashworks.ugs.camera.machine.MachineGateway;
import com.bashworks.ugs.camera.model.*;
import com.bashworks.ugs.camera.model.OriginRecovery.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Locale;
import java.util.concurrent.Callable;

/** Selecting a point arms one explicit restore-and-XY-travel action. Z is never commanded. */
public final class OriginRecoveryDialog extends JDialog {
    private final MachineGateway machine;
    private final JobPreview job;
    private final CameraOffset offset;
    private final double feedMm;
    private final JobPreviewPanel preview;
    private final JLabel coordinate = new JLabel("Click the feature currently under the camera crosshair.");
    private final JTextArea status = new JTextArea("Aim the live camera at a known feature, then click the matching feature in this job.", 2, 70);
    private final JButton reference = new JButton("Re-reference XY & move to X0 Y0");
    private final JButton stop = new JButton("Stop move");
    private final Timer timer;
    private Anchor anchor;
    private JobPreview.Pick selection;
    private boolean busy, closing;
    private volatile boolean moving;
    private Thread worker;

    public OriginRecoveryDialog(Window owner, MachineGateway machine, JobPreview job, CameraOffset offset,
                                double feedMm, Runnable onClosed) {
        super(owner, "Recover job XY origin", ModalityType.MODELESS);
        this.machine = machine;
        this.job = job;
        this.offset = offset;
        this.feedMm = feedMm;
        preview = new JobPreviewPanel(job);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setLayout(new BorderLayout(8, 8));
        JPanel heading = new JPanel(new GridLayout(0, 1, 0, 4));
        heading.setBorder(BorderFactory.createEmptyBorder(10, 12, 0, 12));
        heading.add(new JLabel("Active job: " + job.source().path().getFileName() + "   ·   " + job.workSystem() + "   ·   millimetres"));
        heading.add(new JLabel("Camera-to-tool movement: " + offset.movementLabel()));
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton fit = new JButton("Fit job"), in = new JButton("+"), out = new JButton("−");
        JCheckBox snap = new JCheckBox("Snap to endpoints / arc centers / path", true);
        JCheckBox rapids = new JCheckBox("Show rapid travel");
        toolbar.add(fit); toolbar.add(in); toolbar.add(out); toolbar.add(snap); toolbar.add(rapids);
        heading.add(toolbar);
        add(heading, BorderLayout.NORTH);
        add(preview, BorderLayout.CENTER);
        JPanel footer = new JPanel(new GridLayout(0, 1, 0, 6));
        footer.setBorder(BorderFactory.createEmptyBorder(0, 12, 12, 12));
        footer.add(coordinate);
        footer.add(new JLabel("Tool-center paths are shown, not finished wall edges. Use a matching corner or arc center."));
        JPanel restoreRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        restoreRow.add(reference);
        restoreRow.add(new JLabel("Restores " + job.workSystem() + " XY and moves to X0 Y0; Z unchanged."));
        footer.add(restoreRow);
        JPanel travelRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        travelRow.add(stop);
        JButton close = new JButton("Close"); travelRow.add(close);
        footer.add(travelRow);
        status.setEditable(false);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);
        status.setOpaque(false);
        status.setFont(UIManager.getFont("Label.font"));
        footer.add(status);
        add(footer, BorderLayout.SOUTH);
        fit.addActionListener(e -> preview.fit());
        in.addActionListener(e -> preview.zoom(1.5));
        out.addActionListener(e -> preview.zoom(1 / 1.5));
        snap.addActionListener(e -> { preview.setSnap(snap.isSelected()); clearPick(); });
        rapids.addActionListener(e -> preview.setShowRapids(rapids.isSelected()));
        preview.onHover(coordinate::setText);
        preview.onSelection(this::select);
        reference.addActionListener(e -> restore());
        stop.addActionListener(e -> cancel());
        close.addActionListener(e -> requestClose());
        timer = new Timer(250, e -> validateSelection());
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { requestClose(); }
            @Override public void windowClosed(WindowEvent e) { timer.stop(); onClosed.run(); }
        });
        setMinimumSize(new Dimension(970, 720));
        pack();
        setLocationRelativeTo(owner);
        timer.start();
        updateActions();
    }

    private void select(JobPreview.Pick pick) {
        if (busy) return;
        selection = pick;
        anchor = null;
        try {
            if (pick != null) {
                anchor = machine.captureReference(job);
                Pose origin = OriginRecovery.recoveredOrigin(anchor, pick.point(), offset);
                status.setText(String.format(Locale.US,
                        "%s: job X %.4f, Y %.4f mm. Recovered machine origin: X %.4f, Y %.4f mm. Ready to re-reference XY.",
                        pick.label(), pick.point().x(), pick.point().y(), origin.x(), origin.y()));
            } else status.setText("No feature near the click. Zoom in, or turn snapping off to select a free point.");
        } catch (Exception e) { status.setText(e.getMessage()); }
        updateActions();
    }

    private void clearPick() {
        if (busy) return;
        anchor = null;
        selection = null;
        preview.clearSelection();
        updateActions();
    }

    private void restore() {
        if (selection == null || anchor == null) return;
        JobPreview.Pick pick = selection;
        Anchor captured = anchor;
        run(() -> {
            Restored result = machine.restoreOrigin(job, captured, pick.point(), offset);
            moving = true;
            machine.moveToRestoredOrigin(result, feedMm,
                    message -> SwingUtilities.invokeLater(() -> status.setText(message)));
            return result;
        });
    }

    private void run(Callable<Restored> action) {
        if (busy) return;
        busy = true;
        preview.setEnabled(false);
        status.setText("Setting XY origin, then moving to X0 Y0…");
        updateActions();
        worker = new Thread(() -> {
            String error = null;
            try { action.call(); }
            catch (Exception e) { error = e.getMessage() == null ? e.toString() : e.getMessage(); }
            String failure = error;
            SwingUtilities.invokeLater(() -> {
                busy = false;
                moving = false;
                worker = null;
                anchor = null;
                selection = null;
                preview.clearSelection();
                preview.setEnabled(true);
                if (failure != null) {
                    status.setText("Operation stopped: " + failure + " Verify the displayed UGS coordinates before continuing.");
                } else status.setText("At restored X0 Y0. Z unchanged.");
                updateActions();
                if (closing) dispose();
            });
        }, "CamerAim-OriginRecovery");
        worker.setDaemon(true);
        worker.start();
    }

    private void validateSelection() {
        if (busy) return;
        if (anchor != null && !machine.referenceValid(job, anchor)) {
            anchor = null;
            status.setText("Reference disarmed: camera position, job, or machine state changed. Aim again and click the matching feature.");
        }
        updateActions();
    }

    private void updateActions() {
        reference.setEnabled(!busy && anchor != null && selection != null);
        stop.setEnabled(busy);
    }

    private void cancel() {
        if (worker != null) worker.interrupt();
        if (moving) {
            Thread cancel = new Thread(() -> {
                try { machine.cancelReferenceMove(); }
                catch (Exception e) { SwingUtilities.invokeLater(() -> status.setText("Could not cancel jog: " + e.getMessage() + ". Use the machine stop.")); }
            }, "CamerAim-CancelOriginMove");
            cancel.setDaemon(true);
            cancel.start();
        }
    }

    public void requestClose() {
        if (busy) { closing = true; cancel(); }
        else dispose();
    }
}
