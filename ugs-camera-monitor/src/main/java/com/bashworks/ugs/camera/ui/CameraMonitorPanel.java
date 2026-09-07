package com.bashworks.ugs.camera.ui;

import com.bashworks.ugs.camera.capture.CameraCaptureService;
import com.bashworks.ugs.camera.capture.CameraDevice;
import com.bashworks.ugs.camera.machine.MachineGateway;
import com.bashworks.ugs.camera.machine.MachineState;
import com.bashworks.ugs.camera.model.CameraOffset;
import com.bashworks.ugs.camera.model.LengthUnit;
import com.bashworks.ugs.camera.model.TargetPosition;
import org.openide.util.NbPreferences;

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import java.util.Locale;
import java.util.prefs.Preferences;

public final class CameraMonitorPanel extends JPanel implements AutoCloseable {
    private static final String PREF_X = "offset.x";
    private static final String PREF_Y = "offset.y";
    private static final String PREF_UNIT = "offset.unit";
    private static final String PREF_FEED = "offset.feed";
    private static final String PREF_ZOOM = "camera.zoom";
    private static final String PREF_ROTATION = "camera.rotation";

    private final MachineGateway machine;
    private final CameraCaptureService camera = new CameraCaptureService();
    private final Preferences preferences = NbPreferences.forModule(CameraMonitorPanel.class);
    private final CameraImagePanel imagePanel = new CameraImagePanel();
    private final JComboBox<CameraDevice> cameras = new JComboBox<>();
    private final JComboBox<Dimension> sizes = new JComboBox<>();
    private final JButton startCamera = new JButton("Start camera");
    private final JCheckBox freeze = new JCheckBox("Freeze frame");
    private final JComboBox<Integer> zoom = new JComboBox<>(new Integer[]{1, 2, 3});
    private final JComboBox<CameraRotation> rotation = new JComboBox<>(CameraRotation.values());
    private final JLabel cameraStatus = new JLabel("Scanning for USB cameras…");
    private final JSpinner offsetX;
    private final JSpinner offsetY;
    private final JComboBox<LengthUnit> unit = new JComboBox<>(LengthUnit.values());
    private final JSpinner feedRate;
    private final JLabel movementPreview = new JLabel();
    private final JLabel positionLabel = new JLabel("Position: —");
    private final JLabel machineStatus = new JLabel("UGS: —");
    private final JButton moveButton = new JButton("Move tool to crosshair");
    private final JButton zeroButton = new JButton("Set XY zero here");
    private LengthUnit lastUnit;

    public CameraMonitorPanel(MachineGateway machine) {
        this.machine = machine;
        offsetX = new JSpinner(new SpinnerNumberModel(preferences.getDouble(PREF_X, 0.0), -100000.0, 100000.0, 0.01));
        offsetY = new JSpinner(new SpinnerNumberModel(preferences.getDouble(PREF_Y, 0.0), -100000.0, 100000.0, 0.01));
        feedRate = new JSpinner(new SpinnerNumberModel(preferences.getDouble(PREF_FEED, 500.0), 0.01, 100000.0, 10.0));
        unit.setSelectedItem(readUnit());
        lastUnit = (LengthUnit) unit.getSelectedItem();
        zoom.setSelectedItem(readZoom());
        rotation.setSelectedItem(readRotation());
        imagePanel.setZoom((Integer) zoom.getSelectedItem());
        imagePanel.setRotation((CameraRotation) rotation.getSelectedItem());

        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        add(createCameraControls(), BorderLayout.NORTH);
        imagePanel.setPreferredSize(new Dimension(640, 420));
        add(imagePanel, BorderLayout.CENTER);
        add(createPositionControls(), BorderLayout.SOUTH);

        wireEvents();
        installResolutionRenderer();
        updateMovementPreview();
        refreshMachineState();
        scanCameras();
    }

    private JPanel createCameraControls() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        panel.add(new JLabel("USB camera:"));
        panel.add(cameras);
        panel.add(sizes);
        panel.add(startCamera);
        panel.add(freeze);
        panel.add(new JLabel("Zoom:"));
        panel.add(zoom);
        panel.add(new JLabel("Rotation:"));
        panel.add(rotation);
        panel.add(cameraStatus);
        return panel;
    }

    private JPanel createPositionControls() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Crosshair-to-tool movement"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;
        panel.add(new JLabel("X offset:"), c);
        c.gridx = 1;
        panel.add(offsetX, c);
        c.gridx = 2;
        panel.add(new JLabel("Y offset:"), c);
        c.gridx = 3;
        panel.add(offsetY, c);
        c.gridx = 4;
        panel.add(unit, c);
        c.gridx = 5;
        panel.add(new JLabel("Jog feed:"), c);
        c.gridx = 6;
        panel.add(feedRate, c);

        String offsetHelp = "Exact relative tool movement when the target is under the crosshair; signs are not inverted.";
        offsetX.setToolTipText(offsetHelp);
        offsetY.setToolTipText(offsetHelp);
        moveButton.setToolTipText("Sends one relative XY jog through UGS using the displayed offset, without moving Z.");

        c.gridy = 1;
        c.gridx = 0;
        c.gridwidth = 4;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(movementPreview, c);
        c.gridx = 4;
        c.gridwidth = 3;
        panel.add(positionLabel, c);

        c.gridy = 2;
        c.gridx = 0;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.NONE;
        JButton save = new JButton("Save offset");
        save.addActionListener(event -> saveOffset());
        panel.add(save, c);
        c.gridx = 2;
        c.gridwidth = 2;
        panel.add(moveButton, c);
        c.gridx = 4;
        c.gridwidth = 2;
        panel.add(zeroButton, c);
        c.gridx = 6;
        c.gridwidth = 1;
        panel.add(machineStatus, c);
        return panel;
    }

    private void wireEvents() {
        cameras.addActionListener(event -> updateSizes());
        startCamera.addActionListener(event -> toggleCamera());
        freeze.addActionListener(event -> imagePanel.setFrozen(freeze.isSelected()));
        zoom.addActionListener(event -> updateZoom());
        rotation.addActionListener(event -> updateRotation());
        offsetX.addChangeListener(event -> updateMovementPreview());
        offsetY.addChangeListener(event -> updateMovementPreview());
        unit.addActionListener(event -> convertDisplayedUnit());
        moveButton.addActionListener(event -> moveTool());
        zeroButton.addActionListener(event -> setXyZero());
        camera.addStatusListener(status -> SwingUtilities.invokeLater(() -> cameraStatus.setText(status)));
    }

    private void scanCameras() {
        camera.findDevices(
                devices -> SwingUtilities.invokeLater(() -> setDevices(devices)),
                error -> SwingUtilities.invokeLater(() -> cameraStatus.setText("Error: " + error.getMessage())));
    }

    private void setDevices(List<CameraDevice> devices) {
        cameras.removeAllItems();
        devices.forEach(cameras::addItem);
        cameraStatus.setText(devices.isEmpty()
                ? "No USB camera found"
                : devices.size() + (devices.size() == 1 ? " camera found" : " cameras found"));
        startCamera.setEnabled(!devices.isEmpty());
        updateSizes();
    }

    private void updateSizes() {
        sizes.removeAllItems();
        CameraDevice selected = (CameraDevice) cameras.getSelectedItem();
        if (selected != null) {
            for (Dimension size : selected.viewSizes()) sizes.addItem(size);
            Dimension preferred = selected.preferredViewSize();
            if (preferred != null) sizes.setSelectedItem(preferred);
        }
    }

    private void installResolutionRenderer() {
        sizes.setRenderer((list, value, index, isSelected, cellHasFocus) -> {
            JLabel label = (JLabel) new DefaultListCellRenderer()
                    .getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value != null) {
                CameraDevice cameraDevice = (CameraDevice) cameras.getSelectedItem();
                String suffix = cameraDevice != null && !cameraDevice.isReportedViewSize(value)
                        ? " (additional mode)"
                        : "";
                label.setText(value.width + " × " + value.height + suffix);
            }
            return label;
        });
    }

    private void updateZoom() {
        Integer selected = (Integer) zoom.getSelectedItem();
        if (selected == null) return;
        imagePanel.setZoom(selected);
        preferences.putInt(PREF_ZOOM, selected);
    }

    private void updateRotation() {
        CameraRotation selected = (CameraRotation) rotation.getSelectedItem();
        if (selected == null) return;
        imagePanel.setRotation(selected);
        preferences.put(PREF_ROTATION, selected.name());
    }

    private void toggleCamera() {
        if (camera.isRunning()) {
            camera.stop();
            startCamera.setText("Start camera");
            cameraStatus.setText("Camera stopped");
            return;
        }
        CameraDevice selected = (CameraDevice) cameras.getSelectedItem();
        if (selected == null) return;
        camera.start(selected, (Dimension) sizes.getSelectedItem(), imagePanel::setFrame);
        startCamera.setText("Stop camera");
    }

    private CameraOffset currentOffset() {
        return new CameraOffset(
                ((Number) offsetX.getValue()).doubleValue(),
                ((Number) offsetY.getValue()).doubleValue(),
                (LengthUnit) unit.getSelectedItem());
    }

    private void updateMovementPreview() {
        movementPreview.setText("Tool movement: " + currentOffset().movementLabel());
        refreshPositionPreview();
    }

    private void convertDisplayedUnit() {
        LengthUnit selected = (LengthUnit) unit.getSelectedItem();
        if (selected == null || selected == lastUnit) {
            updateMovementPreview();
            return;
        }
        offsetX.setValue(lastUnit.convert(((Number) offsetX.getValue()).doubleValue(), selected));
        offsetY.setValue(lastUnit.convert(((Number) offsetY.getValue()).doubleValue(), selected));
        feedRate.setValue(lastUnit.convert(((Number) feedRate.getValue()).doubleValue(), selected));
        lastUnit = selected;
        updateMovementPreview();
    }

    private void saveOffset() {
        CameraOffset offset = currentOffset();
        preferences.putDouble(PREF_X, offset.x());
        preferences.putDouble(PREF_Y, offset.y());
        preferences.put(PREF_UNIT, offset.unit().name());
        preferences.putDouble(PREF_FEED, ((Number) feedRate.getValue()).doubleValue());
        machineStatus.setText("Offset saved");
    }

    private void moveTool() {
        CameraOffset offset = currentOffset();
        MachineState state = machine.state();
        if (!state.canPosition()) {
            showError(state.blockedReason());
            refreshMachineState();
            return;
        }
        int answer = JOptionPane.showConfirmDialog(
                this,
                "The target must be under the crosshair and Z must be at a verified safe height.\n\nRelative move: " + offset.movementLabel(),
                "Confirm tool movement",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return;

        try {
            machine.moveToolToCrosshair(offset, ((Number) feedRate.getValue()).doubleValue());
            machineStatus.setText("Move sent to UGS");
        } catch (Exception error) {
            showError(error.getMessage());
        }
        refreshMachineState();
    }

    private void setXyZero() {
        MachineState state = machine.state();
        if (!state.canPosition()) {
            showError(state.blockedReason());
            refreshMachineState();
            return;
        }
        int answer = JOptionPane.showConfirmDialog(
                this,
                "Set the current work position to X=0 and Y=0?\nThe Z coordinate will not change.",
                "Confirm XY zero",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return;
        try {
            machine.setWorkXyZero();
            machineStatus.setText("XY work zero set");
        } catch (Exception error) {
            showError(error.getMessage());
        }
        refreshMachineState();
    }

    public void refreshMachineState() {
        MachineState state = machine.state();
        moveButton.setEnabled(state.canPosition());
        zeroButton.setEnabled(state.canPosition());
        machineStatus.setText(state.canPosition() ? "UGS: Idle" : "UGS: " + state.blockedReason());
        refreshPositionPreview();
    }

    private void refreshPositionPreview() {
        try {
            TargetPosition p = machine.machinePosition();
            TargetPosition target = currentOffset().targetFrom(p.x(), p.y(), p.unit());
            positionLabel.setText(String.format(
                    Locale.US,
                    "Machine XY: %.3f, %.3f %s  |  Tool target: %.3f, %.3f %s",
                    p.x(), p.y(), p.unit(), target.x(), target.y(), target.unit()));
        } catch (RuntimeException ignored) {
            positionLabel.setText("Machine XY: —  |  Tool target: —");
        }
    }

    private LengthUnit readUnit() {
        try {
            return LengthUnit.valueOf(preferences.get(PREF_UNIT, LengthUnit.MM.name()));
        } catch (IllegalArgumentException ignored) {
            return LengthUnit.MM;
        }
    }

    private int readZoom() {
        int saved = preferences.getInt(PREF_ZOOM, 1);
        return saved >= 1 && saved <= 3 ? saved : 1;
    }

    private CameraRotation readRotation() {
        try {
            return CameraRotation.valueOf(preferences.get(PREF_ROTATION, CameraRotation.DEG_0.name()));
        } catch (IllegalArgumentException ignored) {
            return CameraRotation.DEG_0;
        }
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "G-BASHWARE CamerAim", JOptionPane.ERROR_MESSAGE);
    }

    @Override
    public void close() {
        camera.close();
    }
}
