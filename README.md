<p align="center">
  <img src="assets/cameraim-logo.svg" width="720" alt="G-BASHWARE CamerAim">
</p>

# G-BASHWARE CamerAim

G-BASHWARE CamerAim is a camera positioning plugin for [Universal Gcode Sender Platform](https://github.com/winder/Universal-G-Code-Sender). It places a precise crosshair over a live USB camera image and moves the CNC tool from the camera target to the calibrated tool position.

The current release targets **UGS Platform 2.1.6** and **Java 17 or newer**. The camera connects directly to the computer running UGS.

## Features

- USB/UVC camera discovery and live preview
- Camera modes up to 2592 × 1944 for 5 MP cameras
- Automatic fallback when a camera driver rejects a selected mode
- Centered 1×, 2×, and 3× digital zoom
- 0°, 90°, 180°, and 270° clockwise display rotation
- Crosshair with one uncovered source pixel at its exact center
- Persistent X/Y camera-to-tool offset, units, and jog feed
- Exact relative XY movement from the crosshair target to the tool
- Live machine coordinates and calculated tool target
- A separate command to set the current work X/Y zero
- Movement lockout unless UGS is connected, Idle, and able to jog
- Built-in G-BASHWARE update-center registration

## Install

1. Download the latest `G-BASHWARE-CamerAim-for-UGS-2.1.6.nbm` from [Releases](https://github.com/bash-ware/G-BASHWARE-CamerAim/releases/latest).
2. In UGS Platform, open **Tools → Plugins → Downloaded**.
3. Choose **Add Plugins**, select the `.nbm` file, and complete the installer.
4. Restart UGS if requested.
5. Open **Window → Plugins → G-BASHWARE CamerAim** if the panel is not already visible.

The first installed release registers the G-BASHWARE CamerAim update center. Later tagged releases are then available through the UGS plugin manager.

See [Installation and calibration](docs/INSTALL.md) for the offset convention and first-machine test procedure.

## Camera-to-tool offset

Place a reference mark under the crosshair. Enter the relative move the tool must make to reach that mark. The plugin sends the entered signs exactly as shown:

- `X +12.000 mm` moves the tool 12 mm in positive X.
- `Y -3.500 mm` moves the tool 3.5 mm in negative Y.

Raise Z to a verified safe height and check the full XY path before accepting the movement dialog. CamerAim never starts the spindle and never changes Z.

## Build

The repository keeps UGS source and local Maven artifacts out of Git. On Windows, prepare `.upstream/ugs` and `.m2/repository`, then run:

```powershell
.\build.ps1
```

GitHub Actions performs the complete clean build against the UGS 2.1.6 source and distribution. Every push and pull request runs the tests. A `v*` tag creates the NBM, an installer ZIP, checksums, a GitHub Release, and the NetBeans update-center catalog.

## Roadmap

Planned work includes:

- live job duration, elapsed time, remaining time, feed, and progress
- a local monitoring server and ntfy push notifications
- G-code toolpath overlay on the camera image
- automatic full-table image capture by moving through a calibrated grid
- lens-distortion calibration, cropping, and image stitching similar to LightBurn camera calibration

The detailed working plan is in [PLAN-UGS-plugin.md](PLAN-UGS-plugin.md).

## License

Copyright © 2026 G-BASHWARE. Licensed under the [GNU General Public License v3.0](COPYING).
