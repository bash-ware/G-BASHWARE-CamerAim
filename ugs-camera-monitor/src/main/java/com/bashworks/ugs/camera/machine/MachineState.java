package com.bashworks.ugs.camera.machine;

public record MachineState(boolean connected, boolean idle, boolean sendingFile, boolean paused, boolean joggingSupported) {
    public boolean canPosition() {
        return connected && idle && !sendingFile && !paused && joggingSupported;
    }

    public String blockedReason() {
        if (!connected) return "UGS is not connected to the machine.";
        if (paused) return "The job is paused; camera positioning is disabled.";
        if (sendingFile) return "The job is active; camera positioning is disabled.";
        if (!idle) return "The machine is not Idle.";
        if (!joggingSupported) return "The connected controller does not support UGS jog commands.";
        return "";
    }
}
