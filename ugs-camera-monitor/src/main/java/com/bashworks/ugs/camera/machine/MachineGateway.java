package com.bashworks.ugs.camera.machine;

import com.bashworks.ugs.camera.model.CameraOffset;
import com.bashworks.ugs.camera.model.LengthUnit;
import com.bashworks.ugs.camera.model.TargetPosition;

import java.util.function.Consumer;

public interface MachineGateway {
    MachineState state();
    TargetPosition machinePosition();
    void moveToolToCrosshair(CameraOffset movement, double feedRate) throws Exception;
    void setWorkXyZero() throws Exception;
    void moveToAimHeight(String homingCommand, double dropDistance, LengthUnit unit, double feedRate, Consumer<String> statusListener) throws Exception;
}


