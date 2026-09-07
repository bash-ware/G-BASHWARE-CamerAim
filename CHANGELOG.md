# Changelog

## 2.1.1 - 2026-09-08

- Request camera modes at 30 fps to match common high-resolution MJPG UVC cameras.
- Serialize camera shutdown and restart so the previous native session fully releases the device.
- Verify the actual frame dimensions and retry the selected mode up to three times.
- Stop with a clear error when the camera substitutes another mode instead of silently falling back to 640 × 480.
- Reset the Start camera button when opening or capture fails.
- Add 160 × 120, 352 × 288, 1280 × 1024, 2048 × 1536, 2560 × 1440, and other common camera-mode candidates.
## 2.1.0 - 2026-09-07

- Brand the plugin as G-BASHWARE CamerAim.
- Translate all plugin UI, runtime messages, metadata, and build output to English.
- Add 1×, 2×, and 3× centered digital zoom.
- Add 0°, 90°, 180°, and 270° camera display rotation.
- Add camera modes through 2592 × 1944 with driver fallback.
- Leave one source pixel visible at the exact crosshair center.
- Add calibrated X/Y camera-to-tool movement and XY work-zero controls.
- Add guarded movement based on UGS connection, job, Idle, and jog capability state.
- Register the G-BASHWARE CamerAim update center.
