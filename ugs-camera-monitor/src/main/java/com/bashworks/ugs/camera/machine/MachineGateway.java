package com.bashworks.ugs.camera.machine;

import com.bashworks.ugs.camera.model.CameraOffset;
import com.bashworks.ugs.camera.model.TargetPosition;

public interface MachineGateway {
    MachineState state();
    TargetPosition machinePosition();
    void moveToolToCrosshair(CameraOffset movement, double feedRate) throws Exception;
    void setWorkXyZero() throws Exception;
}

