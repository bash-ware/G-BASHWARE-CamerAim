# Install and calibrate G-BASHWARE CamerAim

## Requirements

- Universal Gcode Sender Platform 2.1.6
- Windows 10 or 11 for the currently tested package
- A USB/UVC camera connected directly to the UGS computer
- Java 17 or newer when using a UGS installation without its bundled runtime

## Install the NBM

1. Close any active CNC job and leave the controller disconnected or Idle.
2. Download `G-BASHWARE-CamerAim-2.3.0_for-UGS-2.1.6.nbm` from the latest GitHub Release.
3. In UGS, open **Tools → Plugins → Downloaded**.
4. Select **Add Plugins**, choose the downloaded NBM, then follow the installer.
5. Restart UGS if requested.
6. Open **Window → Plugins → G-BASHWARE CamerAim**.

Installing this branded release over the earlier Camera Monitor prototype updates the same internal module. It does not create a second copy.

## Verify the camera

1. Select the camera. Integrated notebook cameras and USB/UVC cameras are supported.
2. Wait while CamerAim asks Windows for the camera's available formats, then select one of the displayed resolutions.
3. Select a **Preview FPS** limit. The choices are capped by the selected resolution's Windows-reported native rate.
4. Click **Start camera**. CamerAim verifies the exact resolution and never silently substitutes another size.
5. Set display rotation to match the machine axes.
6. Use 2× or 3× zoom for fine alignment. Zoom and rotation affect only the view; the crosshair remains at the optical center with a 3 × 3 pixel opening.

### Camera and resolution notes

Version 2.3.0 reads formats directly from Windows MediaCapture. The resolution list follows the selected camera and computer instead of a hard-coded model profile. CamerAim chooses a valid native format automatically and applies **Preview FPS** only as the displayed-frame limit. This permits a camera that requires 30 FPS for a 5 MP native mode to be displayed at 5 or 10 FPS without asking the driver for an invalid combination.

Camera work runs in a separate helper process. A native camera or driver failure ends that helper and reports an error in the panel without taking down UGS. Use **Copy diagnostics** and send the copied text when troubleshooting another computer. **Rescan cameras** refreshes both devices and formats.

On Windows, keep **Settings → Privacy & security → Camera → Let desktop apps access your camera** enabled. Also close Windows Camera before starting CamerAim because most camera drivers allow only one active application.

## Determine the X/Y offset

1. Raise Z to a safe height.
2. Put a clearly visible mark on scrap material.
3. Center the crosshair on the mark.
4. Using normal UGS jog controls, move the tool tip exactly over the same mark.
5. Record the X and Y movement required from the camera position to the tool position.
6. Enter those signed values as **X offset** and **Y offset**, choose the correct unit, and click **Save offset**.

The offset means “how the tool must move when the target is under the crosshair.” CamerAim does not invert either sign.

## First movement test

Perform the first test with the spindle off, Z raised, low jog feed, and scrap material. Put the mark under the crosshair, confirm that the displayed **Tool target** is inside the machine travel, then click **Move tool to crosshair**. Check the displayed relative move and target before clicking. Once the tool reaches the target and UGS reports Idle, CamerAim automatically sets work X and Y to zero. Z is unchanged. An interrupted or failed move does not set XY zero.

The positioning commands are disabled while a job is running or paused, while the machine is not Idle, when UGS is disconnected, or when the controller does not support UGS jog commands.

## Recover a lost job X0 Y0

This recovers XY translation only. The workpiece must retain its orientation and shape, and the machine coordinates and camera-to-tool calibration must still be valid. It does not recover Z zero.

1. Load the original job in UGS and select its work coordinate system (for example G54). Stop the spindle and leave the controller Idle.
2. Use the live camera and UGS jogging to center the crosshair on a recognizable milled feature. Prefer an arc center or an unambiguous corner.
3. Click **Recover job XY origin…**. The preview shows UGS's processed tool-center paths from above, with +X right and +Y up. It does not show the finished edges of the cutter's swept area; account for the tool radius when matching an edge.
4. Zoom with the wheel or **+ / −**, right-drag to pan, and use **Fit job** to reset the view. Click the matching point. Snapping selects exact endpoints/corners and arc centers, or the nearest cutting path. Turn snapping off only when you intend to choose a free point.
5. Check the selected job coordinates and calculated machine origin, then click **Re-reference XY**. This sets the current tool's work coordinates to the selected feature minus the calibrated camera-to-tool movement. It does not move any axis, change Z zero, or use the ordinary **Move tool to crosshair** zeroing action.
6. Wait for confirmation. To travel to the restored origin, enter **Safe machine Z (mm)**: an absolute machine-coordinate height, not a distance above work Z0. Confirm that machine Z is referenced, +Z is upward, and the entire XY path is clear.
7. Click **Raise Z, then move to X0 Y0**. CamerAim raises Z if below the chosen level, waits for the reported safe height and Idle, then moves XY. If already above the level, it never lowers Z. It leaves Z raised at the origin. **Stop move** cancels the jog.

Selecting a point never starts motion. Moving the camera/machine disarms a pending selection. Changing the job or work frame, reconnecting, or homing disarms stale selections or recovery results. If a command fails or coordinates are not confirmed, verify UGS's displayed work coordinates before continuing; setting an origin is not automatically undone.

The initial release supports ordinary G0/G1 and XY-plane G2/G3 paths, metric/inch units, absolute/incremental movement, and a single G54–G59 work system. It requires explicit G20/G21 before XY movement. It refuses macros, probing/canned cycles, cutter compensation, rotated/scaled/local coordinate frames, and machine-coordinate XY moves rather than presenting misleading geometry. Z-only G53/G28/G30 retracts are ignored in the XY preview. Camera alignment, clamping clearance, and a real-machine recovery test remain operator checks.

## Updates

CamerAim registers this update catalog during installation:

`https://raw.githubusercontent.com/bash-ware/G-BASHWARE-CamerAim/update-center/updates.xml`

UGS can use the catalog to discover later signed releases. If the catalog does not appear under **Tools → Plugins → Settings**, add that URL once as an update center named **G-BASHWARE CamerAim Updates**.
