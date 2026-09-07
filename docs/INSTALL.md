# Install and calibrate G-BASHWARE CamerAim

## Requirements

- Universal Gcode Sender Platform 2.1.6
- Windows 10 or 11 for the currently tested package
- A USB/UVC camera connected directly to the UGS computer
- Java 17 or newer when using a UGS installation without its bundled runtime

## Install the NBM

1. Close any active CNC job and leave the controller disconnected or Idle.
2. Download `G-BASHWARE-CamerAim-for-UGS-2.1.6.nbm` from the latest GitHub Release.
3. In UGS, open **Tools → Plugins → Downloaded**.
4. Select **Add Plugins**, choose the downloaded NBM, then follow the installer.
5. Restart UGS if requested.
6. Open **Window → Plugins → G-BASHWARE CamerAim**.

Installing this branded release over the earlier Camera Monitor prototype updates the same internal module. It does not create a second copy.

## Verify the camera

1. Select the USB camera.
2. Select a resolution. Modes marked **additional mode** were not advertised by the driver and may fall back automatically.
3. Click **Start camera**.
4. Set display rotation to match the machine axes.
5. Use 2× or 3× zoom for fine alignment. Zoom and rotation affect only the view; the crosshair remains at the optical center.

## Determine the X/Y offset

1. Raise Z to a safe height.
2. Put a clearly visible mark on scrap material.
3. Center the crosshair on the mark.
4. Using normal UGS jog controls, move the tool tip exactly over the same mark.
5. Record the X and Y movement required from the camera position to the tool position.
6. Enter those signed values as **X offset** and **Y offset**, choose the correct unit, and click **Save offset**.

The offset means “how the tool must move when the target is under the crosshair.” CamerAim does not invert either sign.

## First movement test

Perform the first test with the spindle off, Z raised, low jog feed, and scrap material. Put the mark under the crosshair, confirm that the displayed **Tool target** is inside the machine travel, then click **Move tool to crosshair**. Cancel the confirmation if the displayed relative move or target is unexpected.

The positioning commands are disabled while a job is running or paused, while the machine is not Idle, when UGS is disconnected, or when the controller does not support UGS jog commands.

## Updates

CamerAim registers this update catalog during installation:

`https://raw.githubusercontent.com/bash-ware/G-BASHWARE-CamerAim/update-center/updates.xml`

UGS can use the catalog to discover later signed releases. If the catalog does not appear under **Tools → Plugins → Settings**, add that URL once as an update center named **G-BASHWARE CamerAim Updates**.
