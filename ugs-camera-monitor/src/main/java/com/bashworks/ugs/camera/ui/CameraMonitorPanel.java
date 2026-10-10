package com.bashworks.ugs.camera.ui;

import com.bashworks.ugs.camera.PluginInfo;
import com.bashworks.ugs.camera.capture.CameraCaptureService;
import com.bashworks.ugs.camera.capture.CameraDevice;
import com.bashworks.ugs.camera.machine.MachineGateway;
import com.bashworks.ugs.camera.machine.MachineState;
import com.bashworks.ugs.camera.model.CameraOffset;
import com.bashworks.ugs.camera.model.LengthUnit;
import com.bashworks.ugs.camera.model.TargetPosition;

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
    private static final String PREF_AIM_DROP = "aimHeight.drop";
    private static final String PREF_HOMING_CMD = "aimHeight.homingCommand";
    private static final double DEFAULT_AIM_DROP = 50.0;
    private static final String DEFAULT_HOMING_CMD = "$HZ";
    private static final String PREF_ZOOM = "camera.zoom";
    private static final String PREF_ROTATION = "camera.rotation";
    private static final String PREF_FPS = "camera.fps";

    private final MachineGateway machine;
    private final CameraCaptureService camera = new CameraCaptureService();
    private final Preferences preferences = initPreferences();

    private static Preferences initPreferences() {
        try {
            Class<?> nbPrefClass = Class.forName("org.openide.util.NbPreferences");
            return (Preferences) nbPrefClass.getMethod("forModule", Class.class).invoke(null, CameraMonitorPanel.class);
        } catch (Throwable ignored) {
        }
        try {
            return Preferences.userNodeForPackage(CameraMonitorPanel.class);
        } catch (Throwable ignored) {
            return new InMemoryPreferences();
        }
    }

    private static final class InMemoryPreferences extends java.util.prefs.AbstractPreferences {
        private final java.util.Map<String, String> map = new java.util.HashMap<>();

        InMemoryPreferences() {
            super(null, "");
        }

        @Override
        protected void putSpi(String key, String value) {
            map.put(key, value);
        }

        @Override
        protected String getSpi(String key) {
            return map.get(key);
        }

        @Override
        protected void removeSpi(String key) {
            map.remove(key);
        }

        @Override
        protected void removeNodeSpi() {
        }

        @Override
        protected String[] keysSpi() {
            return map.keySet().toArray(new String[0]);
        }

        @Override
        protected String[] childrenNamesSpi() {
            return new String[0];
        }

        @Override
        protected java.util.prefs.AbstractPreferences childSpi(String name) {
            return this;
        }

        @Override
        protected void syncSpi() {
        }

        @Override
        protected void flushSpi() {
        }
    }
    private final CameraImagePanel imagePanel = new CameraImagePanel();
    private final JComboBox<CameraDevice> cameras = new JComboBox<>();
    private final JComboBox<Dimension> sizes = new JComboBox<>();
    private final JComboBox<Integer> frameRates = new JComboBox<>();
    private final JButton startCamera = new JButton("Start camera");
    private final JCheckBox freeze = new JCheckBox("Freeze frame");
    private final JComboBox<Integer> zoom = new JComboBox<>(new Integer[]{1, 2, 3});
    private final JComboBox<CameraRotation> rotation = new JComboBox<>(CameraRotation.values());
    private final javax.swing.JTextArea cameraStatus = new javax.swing.JTextArea("Scanning for cameras…", 2, 40);
    private final JButton rescan = new JButton("Rescan cameras");
    private final JSpinner offsetX;
    private final JSpinner offsetY;
    private final JComboBox<LengthUnit> unit = new JComboBox<>(LengthUnit.values());
    private final JSpinner feedRate;
    private final JSpinner aimDrop;
    private final JButton aimHeightButton = new JButton("Aim height");
    private final JButton configHomingButton = new JButton("⚙ Cmd");
    private final JLabel movementPreview = new JLabel();
    private final JLabel positionLabel = new JLabel("Position: —");
    private final JLabel machineStatus = new JLabel("UGS: —");
    private final JButton moveButton = new JButton("Move tool to crosshair");
    private final JButton zeroButton = new JButton("Set XY zero here");
    private String homingCommand;
    private volatile boolean aimHeightInProgress;
    private LengthUnit lastUnit;
    private boolean updatingCameraModes;

    public CameraMonitorPanel(MachineGateway machine) {
        this.machine = machine;
        offsetX = new JSpinner(new SpinnerNumberModel(preferences.getDouble(PREF_X, 0.0), -100000.0, 100000.0, 0.01));
        offsetY = new JSpinner(new SpinnerNumberModel(preferences.getDouble(PREF_Y, 0.0), -100000.0, 100000.0, 0.01));
        feedRate = new JSpinner(new SpinnerNumberModel(preferences.getDouble(PREF_FEED, 500.0), 0.01, 100000.0, 10.0));
        homingCommand = preferences.get(PREF_HOMING_CMD, DEFAULT_HOMING_CMD);
        aimDrop = new JSpinner(new SpinnerNumberModel(preferences.getDouble(PREF_AIM_DROP, DEFAULT_AIM_DROP), 0.0, 100000.0, 1.0));
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
        updateAimHeightTooltips();
        updateMovementPreview();
        refreshMachineState();
        scanCameras();
    }

    private JPanel createCameraControls() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        panel.add(new JLabel("Camera:"));
        panel.add(cameras);
        panel.add(sizes);
        panel.add(new JLabel("Preview FPS:"));
        panel.add(frameRates);
        panel.add(startCamera);
        panel.add(freeze);
        panel.add(new JLabel("Zoom:"));
        panel.add(zoom);
        panel.add(new JLabel("Rotation:"));
        panel.add(rotation);
        JPanel heading = new JPanel(new BorderLayout(8, 0));
        heading.add(new JLabel(PluginInfo.TITLE), BorderLayout.WEST);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton copy = new JButton("Copy diagnostics");
        copy.addActionListener(event -> {
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                    new java.awt.datatransfer.StringSelection(camera.diagnostics()), null);
            cameraStatus.setText("Camera diagnostics copied to clipboard.");
        });
        rescan.addActionListener(event -> scanCameras());
        actions.add(rescan);
        actions.add(copy);
        heading.add(actions, BorderLayout.EAST);
        cameraStatus.setEditable(false);
        cameraStatus.setLineWrap(true);
        cameraStatus.setWrapStyleWord(true);
        cameraStatus.setOpaque(false);
        cameraStatus.setFont(javax.swing.UIManager.getFont("Label.font"));
        JPanel container = new JPanel(new BorderLayout(0, 5));
        container.add(heading, BorderLayout.NORTH);
        container.add(panel, BorderLayout.CENTER);
        container.add(cameraStatus, BorderLayout.SOUTH);
        frameRates.setToolTipText("Maximum display FPS. The native camera format is selected from Windows-reported modes.");
        return container;
    }

    private JPanel createPositionControls() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Crosshair-to-tool movement & aim height"));
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
        aimDrop.setToolTipText("Distance to lower Z after homing to reach optical aiming height.");

        c.gridy = 1;
        c.gridx = 0;
        panel.add(new JLabel("Aim drop:"), c);
        c.gridx = 1;
        panel.add(aimDrop, c);
        c.gridx = 2;
        c.gridwidth = 2;
        panel.add(aimHeightButton, c);
        c.gridx = 4;
        c.gridwidth = 1;
        panel.add(configHomingButton, c);

        c.gridy = 2;
        c.gridx = 0;
        c.gridwidth = 4;
        c.fill = GridBagConstraints.HORIZONTAL;
        panel.add(movementPreview, c);
        c.gridx = 4;
        c.gridwidth = 3;
        panel.add(positionLabel, c);

        c.gridy = 3;
        c.gridx = 0;
        c.gridwidth = 2;
        c.fill = GridBagConstraints.NONE;
        JButton save = new JButton("Save settings");
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
        cameras.addActionListener(event -> {
            if (!updatingCameraModes) updateCameraModes();
        });
        sizes.addActionListener(event -> {
            if (!updatingCameraModes) updateFrameRates();
        });
        startCamera.addActionListener(event -> toggleCamera());
        freeze.addActionListener(event -> imagePanel.setFrozen(freeze.isSelected()));
        zoom.addActionListener(event -> updateZoom());
        rotation.addActionListener(event -> updateRotation());
        frameRates.addActionListener(event -> saveFrameRate());
        offsetX.addChangeListener(event -> updateMovementPreview());
        offsetY.addChangeListener(event -> updateMovementPreview());
        aimDrop.addChangeListener(event -> updateAimHeightTooltips());
        unit.addActionListener(event -> convertDisplayedUnit());
        moveButton.addActionListener(event -> moveTool());
        zeroButton.addActionListener(event -> setXyZero());
        aimHeightButton.addActionListener(event -> goToAimHeight());
        configHomingButton.addActionListener(event -> configureHomingCommand());
        camera.addStatusListener(status -> SwingUtilities.invokeLater(() -> {
            cameraStatus.setText(status);
            boolean running = camera.isRunning();
            startCamera.setText(running ? "Stop camera" : "Start camera");
            cameras.setEnabled(!running);
            sizes.setEnabled(!running && sizes.getItemCount() > 0);
            frameRates.setEnabled(!running && frameRates.getItemCount() > 0);
            if (status.startsWith("Camera error:")) imagePanel.clearFrame();
            rescan.setEnabled(!running);
        }));
    }

    private void scanCameras() {
        camera.stop();
        imagePanel.clearFrame();
        startCamera.setEnabled(false);
        rescan.setEnabled(false);
        cameraStatus.setText("Scanning for cameras…");
        camera.findDevices(
                devices -> SwingUtilities.invokeLater(() -> setDevices(devices)),
                error -> SwingUtilities.invokeLater(() -> {
                    cameraStatus.setText("Camera discovery failed: " + error.getMessage());
                    rescan.setEnabled(true);
                }));
    }

    private void setDevices(List<CameraDevice> devices) {
        updatingCameraModes = true;
        try {
            cameras.removeAllItems();
            devices.forEach(cameras::addItem);
        } finally { updatingCameraModes = false; }
        rescan.setEnabled(true);
        updateCameraModes();
    }

    private void updateCameraModes() {
        CameraDevice selected = (CameraDevice) cameras.getSelectedItem();
        updatingCameraModes = true;
        try {
            sizes.removeAllItems();
            frameRates.removeAllItems();
        } finally { updatingCameraModes = false; }
        startCamera.setEnabled(false);
        sizes.setEnabled(false);
        frameRates.setEnabled(false);
        if (selected == null) {
            cameraStatus.setText("No camera found.");
            return;
        }
        cameraStatus.setText("Reading Windows-supported formats for " + selected + "…");
        camera.findModes(selected,
                modes -> SwingUtilities.invokeLater(() -> {
                    if (cameras.getSelectedItem() != selected) return;
                    updatingCameraModes = true;
                    try {
                        for (Dimension size : selected.viewSizes()) sizes.addItem(size);
                        sizes.setSelectedItem(selected.preferredViewSize());
                    } finally { updatingCameraModes = false; }
                    updateFrameRates();
                    startCamera.setEnabled(sizes.getItemCount() > 0);
                    sizes.setEnabled(true);
                    cameraStatus.setText(modes.size() + " Windows-reported formats. Ready to start.");
                }),
                error -> SwingUtilities.invokeLater(() -> {
                    if (cameras.getSelectedItem() != selected) return;
                    cameraStatus.setText("Cannot read camera formats: " + error.getMessage()
                            + " Use Copy diagnostics for details.");
                }));
    }

    private void updateFrameRates() {
        CameraDevice selected = (CameraDevice) cameras.getSelectedItem();
        if (selected == null || sizes.getSelectedItem() == null) return;
        updatingCameraModes = true;
        try {
            frameRates.removeAllItems();
            int[] rates = selected.frameRates((Dimension) sizes.getSelectedItem());
            for (int rate : rates) frameRates.addItem(rate);
            int saved = preferences.getInt(PREF_FPS, 10);
            int preferred = rates[0];
            for (int rate : rates) {
                if (rate <= saved) preferred = rate;
            }
            frameRates.setSelectedItem(preferred);
            frameRates.setEnabled(true);
        } finally { updatingCameraModes = false; }
    }

    private void installResolutionRenderer() {
        sizes.setRenderer((list, value, index, isSelected, cellHasFocus) -> {
            JLabel label = (JLabel) new DefaultListCellRenderer()
                    .getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value != null) {
                label.setText(value.width + " × " + value.height);
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

    private void saveFrameRate() {
        if (updatingCameraModes) return;
        Integer selected = (Integer) frameRates.getSelectedItem();
        if (selected != null) preferences.putInt(PREF_FPS, selected);
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
        Integer frameRate = (Integer) frameRates.getSelectedItem();
        if (frameRate == null) return;
        freeze.setSelected(false);
        imagePanel.setFrozen(false);
        imagePanel.clearFrame();
        camera.start(selected, (Dimension) sizes.getSelectedItem(), frameRate, imagePanel::setFrame);
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
        aimDrop.setValue(lastUnit.convert(((Number) aimDrop.getValue()).doubleValue(), selected));
        lastUnit = selected;
        updateMovementPreview();
        updateAimHeightTooltips();
    }

    private void saveOffset() {
        CameraOffset offset = currentOffset();
        preferences.putDouble(PREF_X, offset.x());
        preferences.putDouble(PREF_Y, offset.y());
        preferences.put(PREF_UNIT, offset.unit().name());
        preferences.putDouble(PREF_FEED, ((Number) feedRate.getValue()).doubleValue());
        preferences.putDouble(PREF_AIM_DROP, ((Number) aimDrop.getValue()).doubleValue());
        preferences.put(PREF_HOMING_CMD, homingCommand);
        machineStatus.setText("Settings saved");
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
        boolean canMove = state.canPosition() && !aimHeightInProgress;
        moveButton.setEnabled(canMove);
        zeroButton.setEnabled(canMove);
        aimHeightButton.setEnabled(canMove);
        configHomingButton.setEnabled(!aimHeightInProgress);
        if (!aimHeightInProgress) {
            machineStatus.setText(state.canPosition() ? "UGS: Idle" : "UGS: " + state.blockedReason());
        }
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

    private void updateAimHeightTooltips() {
        String dropStr = String.format(Locale.US, "%.2f %s", ((Number) aimDrop.getValue()).doubleValue(), unit.getSelectedItem());
        aimHeightButton.setToolTipText("Homes Z via '" + homingCommand + "', then lowers Z by " + dropStr + " to reach aim height.");
        configHomingButton.setToolTipText("Configure Z homing command (current: '" + homingCommand + "')");
    }

    private void configureHomingCommand() {
        String input = JOptionPane.showInputDialog(
                this,
                "Z homing command (e.g. $HZ, $H, or G53 G0 Z0):",
                homingCommand);
        if (input != null && !input.trim().isEmpty()) {
            homingCommand = input.trim();
            preferences.put(PREF_HOMING_CMD, homingCommand);
            updateAimHeightTooltips();
            machineStatus.setText("Homing cmd: " + homingCommand);
        }
    }

    private void goToAimHeight() {
        if (aimHeightInProgress) return;
        MachineState state = machine.state();
        if (!state.canPosition()) {
            showError(state.blockedReason());
            refreshMachineState();
            return;
        }

        double drop = ((Number) aimDrop.getValue()).doubleValue();
        LengthUnit currentUnit = (LengthUnit) unit.getSelectedItem();
        double feed = ((Number) feedRate.getValue()).doubleValue();

        String confirmMsg = String.format(
                Locale.US,
                "Move to Aim Height:\n1. Rehome Z to top using '%s'\n2. Lower Z by %.3f %s (feed %.1f)\n\nEnsure tool and workpiece have safe travel clearance.",
                homingCommand, drop, currentUnit, feed);

        int answer = JOptionPane.showConfirmDialog(
                this,
                confirmMsg,
                "Confirm Aim Height",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return;

        aimHeightInProgress = true;
        refreshMachineState();
        machineStatus.setText("Starting Z homing…");

        new Thread(() -> {
            try {
                machine.moveToAimHeight(
                        homingCommand,
                        drop,
                        currentUnit,
                        feed,
                        status -> SwingUtilities.invokeLater(() -> machineStatus.setText(status)));
                SwingUtilities.invokeLater(() -> {
                    machineStatus.setText("Aim height reached");
                    refreshMachineState();
                });
            } catch (Exception error) {
                SwingUtilities.invokeLater(() -> {
                    showError("Aim height failed: " + error.getMessage());
                    machineStatus.setText("Aim height failed");
                    refreshMachineState();
                });
            } finally {
                SwingUtilities.invokeLater(() -> {
                    aimHeightInProgress = false;
                    refreshMachineState();
                });
            }
        }, "CamerAim-AimHeight").start();
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "G-BASHWARE CamerAim", JOptionPane.ERROR_MESSAGE);
    }

    @Override
    public void close() {
        camera.close();
    }
}
