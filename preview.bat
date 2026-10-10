@echo off
cd /d "%~dp0"
echo ==============================================
echo   G-BASHWARE CamerAim - Standalone Preview
echo ==============================================
echo Starting...
"C:\Program Files\Java\jdk-22\bin\java.exe" -cp ".verify\standalone-classes" com.bashworks.ugs.camera.ui.CameraMonitorStandalone
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo CamerAim preview exited with error code %ERRORLEVEL%.
    pause
)
