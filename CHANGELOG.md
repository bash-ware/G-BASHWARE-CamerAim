# Changelog

## 2.1.3 - 2026-09-08

- Bypass the webcam-capture lock wrapper that could leave the target camera unavailable after a failed start.
- Keep the selected resolution while retrying the native UVC session at the camera's standard frame rates.
- Keep the selected Preview FPS as an independent maximum display rate.
- Release a failed native session before retrying.
- Show the plugin version after the G-BASHWARE CamerAim title in the UGS tab and Window menu.
## 2.1.2 - 2026-09-08

- Show only the seven resolution modes verified on the `5MP Camera` hardware profile.
- Remove substituted and rejected modes including 160 × 120, 320 × 240, 352 × 288, 1024 × 768, 1280 × 1024, 2048 × 1536, and 2560 × 1440.
- Add selectable 5, 10, 15, 20, 25, and 30 FPS preview limits and pass the same value to the UVC session.
- Preserve the selected preview FPS between UGS sessions.
- Increase the transparent crosshair center opening from 1 × 1 to 3 × 3 pixels.
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
