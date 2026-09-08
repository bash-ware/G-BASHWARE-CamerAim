param(
    [string]$JavaHome = 'C:\Program Files\Java\jdk-22',
    [string]$Maven = 'C:\java\maven\bin\mvn.cmd'
)

$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$moduleRoot = Join-Path $projectRoot 'ugs-camera-monitor'
$localRepository = Join-Path $projectRoot '.m2\repository'
$ugsParent = Join-Path $projectRoot '.upstream\ugs\ugs-platform\pom.xml'

if (-not (Test-Path -LiteralPath $JavaHome)) {
    throw "JDK not found: $JavaHome"
}
if (-not (Test-Path -LiteralPath $Maven)) {
    throw "Maven not found: $Maven"
}
if (-not (Test-Path -LiteralPath $ugsParent)) {
    throw 'Prepared UGS 2.1.6 source is missing from .upstream/ugs.'
}
if (-not (Test-Path -LiteralPath $localRepository)) {
    throw 'Prepared local Maven repository is missing from .m2/repository.'
}

$env:JAVA_HOME = $JavaHome
Push-Location $moduleRoot
try {
    & $Maven clean package "-Dmaven.repo.local=$localRepository"
    if ($LASTEXITCODE -ne 0) {
        throw "Maven build failed (exit $LASTEXITCODE)."
    }
} finally {
    Pop-Location
}

$nbm = Join-Path $moduleRoot 'target\nbm\ugs-platform-plugin-camera-monitor-2.1.3.nbm'
Write-Host "NBM created: $nbm"
