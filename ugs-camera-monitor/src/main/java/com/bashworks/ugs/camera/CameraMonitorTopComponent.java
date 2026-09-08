package com.bashworks.ugs.camera;

import com.bashworks.ugs.camera.machine.UgsMachineGateway;
import com.bashworks.ugs.camera.ui.CameraMonitorPanel;
import com.willwinder.ugs.nbp.lib.Mode;
import com.willwinder.ugs.nbp.lib.lookup.CentralLookup;
import com.willwinder.ugs.nbp.lib.services.LocalizingService;
import com.willwinder.universalgcodesender.listeners.UGSEventListener;
import com.willwinder.universalgcodesender.model.BackendAPI;
import com.willwinder.universalgcodesender.model.UGSEvent;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.windows.TopComponent;

import javax.swing.SwingUtilities;
import java.awt.BorderLayout;

@TopComponent.Description(
        preferredID = "CameraMonitorTopComponent",
        persistenceType = TopComponent.PERSISTENCE_ALWAYS)
@TopComponent.Registration(
        mode = Mode.EDITOR_SECONDARY,
        openAtStartup = true,
        position = 500)
@ActionID(
        category = LocalizingService.CATEGORY_WINDOW,
        id = "com.bashworks.ugs.camera.CameraMonitorTopComponent")
@ActionReference(path = LocalizingService.MENU_WINDOW_PLUGIN)
@TopComponent.OpenActionRegistration(
        displayName = "G-BASHWARE CamerAim 2.1.3",
        preferredID = "CameraMonitorTopComponent")
public final class CameraMonitorTopComponent extends TopComponent implements UGSEventListener {
    private static final String PLUGIN_TITLE = "G-BASHWARE CamerAim 2.1.3";
    private final BackendAPI backend;
    private final CameraMonitorPanel panel;

    public CameraMonitorTopComponent() {
        backend = CentralLookup.getDefault().lookup(BackendAPI.class);
        panel = new CameraMonitorPanel(new UgsMachineGateway(backend));
        setLayout(new BorderLayout());
        add(panel, BorderLayout.CENTER);
        setName(PLUGIN_TITLE);
        setToolTipText("USB camera crosshair and calibrated camera-to-tool XY positioning");
    }

    @Override
    protected void componentOpened() {
        backend.addUGSEventListener(this);
        panel.refreshMachineState();
    }

    @Override
    protected void componentClosed() {
        backend.removeUGSEventListener(this);
        panel.close();
    }

    @Override
    public void UGSEvent(UGSEvent event) {
        SwingUtilities.invokeLater(panel::refreshMachineState);
    }
}
