param(
    [string]$Java = "C:\Program Files\Java\jdk-22\bin\java.exe",
    [string]$Javac = "C:\Program Files\Java\jdk-22\bin\javac.exe"
)

$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$verifyDir = Join-Path $projectRoot ".verify\standalone-classes"

if (-not (Test-Path -LiteralPath $Java)) {
    throw "Java runtime not found at: $Java"
}

$sources = @(
    (Join-Path $projectRoot "ugs-camera-monitor\src\main\java\com\bashworks\ugs\camera\PluginInfo.java"),
    (Join-Path $projectRoot "ugs-camera-monitor\src\main\java\com\bashworks\ugs\camera\capture\CameraCaptureService.java"),
    (Join-Path $projectRoot "ugs-camera-monitor\src\main\java\com\bashworks\ugs\camera\capture\CameraDevice.java"),
    (Join-Path $projectRoot "ugs-camera-monitor\src\main\java\com\bashworks\ugs\camera\capture\CameraHelperProcess.java"),
    (Join-Path $projectRoot "ugs-camera-monitor\src\main\java\com\bashworks\ugs\camera\capture\CameraMode.java"),
    (Join-Path $projectRoot "ugs-camera-monitor\src\main\java\com\bashworks\ugs\camera\capture\WindowsCameraBackend.java"),
    (Join-Path $projectRoot "ugs-camera-monitor\src\main\java\com\bashworks\ugs\camera\machine\MachineGateway.java"),
    (Join-Path $projectRoot "ugs-camera-monitor\src\main\java\com\bashworks\ugs\camera\machine\MachineState.java"),
    (Join-Path $projectRoot "ugs-camera-monitor\src\main\java\com\bashworks\ugs\camera\model\CameraOffset.java"),
    (Join-Path $projectRoot "ugs-camera-monitor\src\main\java\com\bashworks\ugs\camera\model\LengthUnit.java"),
    (Join-Path $projectRoot "ugs-camera-monitor\src\main\java\com\bashworks\ugs\camera\model\TargetPosition.java"),
    (Join-Path $projectRoot "ugs-camera-monitor\src\main\java\com\bashworks\ugs\camera\ui\CameraImagePanel.java"),
    (Join-Path $projectRoot "ugs-camera-monitor\src\main\java\com\bashworks\ugs\camera\ui\CameraRotation.java"),
    (Join-Path $projectRoot "ugs-camera-monitor\src\main\java\com\bashworks\ugs\camera\ui\CameraMonitorPanel.java"),
    (Join-Path $projectRoot "ugs-camera-monitor\src\test\java\com\bashworks\ugs\camera\ui\CameraMonitorStandalone.java")
)

if (-not (Test-Path -LiteralPath $verifyDir) -or (Get-ChildItem $verifyDir -ErrorAction SilentlyContinue).Count -eq 0) {
    Write-Host "Compiling CamerAim standalone runner..."
    New-Item -ItemType Directory -Force -Path $verifyDir | Out-Null
    & $Javac -d $verifyDir $sources
    $resources = Join-Path $projectRoot "ugs-camera-monitor\src\main\resources"
    Copy-Item "$resources\*" $verifyDir -Recurse -Force
}

Write-Host "Launching CamerAim standalone preview..."
& $Java -cp $verifyDir com.bashworks.ugs.camera.ui.CameraMonitorStandalone
