param([ValidateSet('devices','modes','capture')][string]$Operation)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$capture = $null
$reader = $null
try {
    Add-Type -AssemblyName System.Runtime.WindowsRuntime
    [Windows.Devices.Enumeration.DeviceInformation,Windows.Devices.Enumeration,ContentType=WindowsRuntime] > $null
    [Windows.Media.Capture.MediaCapture,Windows.Media.Capture,ContentType=WindowsRuntime] > $null
    [Windows.Media.Capture.MediaCaptureInitializationSettings,Windows.Media.Capture,ContentType=WindowsRuntime] > $null
    [Windows.Media.Capture.Frames.MediaFrameReader,Windows.Media.Capture.Frames,ContentType=WindowsRuntime] > $null
    [Windows.Graphics.Imaging.BitmapEncoder,Windows.Graphics.Imaging,ContentType=WindowsRuntime] > $null
    [Windows.Storage.Streams.InMemoryRandomAccessStream,Windows.Storage.Streams,ContentType=WindowsRuntime] > $null
    [Windows.Storage.Streams.DataReader,Windows.Storage.Streams,ContentType=WindowsRuntime] > $null
    $asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
        $_.Name -eq 'AsTask' -and $_.IsGenericMethod -and $_.GetParameters().Count -eq 1 -and
        $_.GetParameters()[0].ParameterType.Name.StartsWith('IAsyncOperation')
    } | Select-Object -First 1
    $asAction = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
        $_.Name -eq 'AsTask' -and !$_.IsGenericMethod -and $_.GetParameters().Count -eq 1 -and
        $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncAction'
    } | Select-Object -First 1
    function AwaitResult($operation, [Type]$type) {
        $task = $asTask.MakeGenericMethod($type).Invoke($null,@($operation))
        if (!$task.Wait(15000)) { throw 'Windows camera operation timed out after 15 seconds' }
        return $task.Result
    }
    function AwaitAction($operation) {
        $task = $asAction.Invoke($null,@($operation))
        if (!$task.Wait(15000)) { throw 'Windows camera operation timed out after 15 seconds' }
    }
    function Encode([string]$value) { return [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($value)) }
    function Decode([string]$value) { return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($value)) }

    if ($Operation -eq 'devices') {
        $devices = AwaitResult ([Windows.Devices.Enumeration.DeviceInformation]::FindAllAsync(
            [Windows.Devices.Enumeration.DeviceClass]::VideoCapture)) ([Windows.Devices.Enumeration.DeviceInformationCollection])
        foreach ($device in $devices) {
            [Console]::Out.WriteLine('DEVICE' + [char]9 + (Encode $device.Id) + [char]9 + (Encode $device.Name))
        }
        [Console]::Out.WriteLine('END')
        exit 0
    }

    # Configuration travels over stdin, never through command interpretation.
    $deviceId = Decode ([Console]::In.ReadLine())
    if ($Operation -eq 'capture') {
        $sourceId = Decode ([Console]::In.ReadLine())
        $width = [int][Console]::In.ReadLine()
        $height = [int][Console]::In.ReadLine()
        $numerator = [uint32][Console]::In.ReadLine()
        $denominator = [uint32][Console]::In.ReadLine()
        $subtype = Decode ([Console]::In.ReadLine())
        $previewFps = [double]::Parse([Console]::In.ReadLine(),[Globalization.CultureInfo]::InvariantCulture)
        if ($previewFps -le 0 -or $previewFps -gt 120) { throw 'Invalid preview rate' }
    }
    # Exit if UGS closes/crashes: EOF on the private input pipe belongs to this helper only.
    Add-Type -TypeDefinition @'
using System;
using System.Threading;
public static class CameraParentLifetime {
    public static void Watch() {
        var thread = new Thread(() => {
            try { while (Console.In.ReadLine() != null) {} } catch {}
            Environment.Exit(0);
        });
        thread.IsBackground = true;
        thread.Start();
    }
}
'@
    [CameraParentLifetime]::Watch()
    $capture = [Windows.Media.Capture.MediaCapture]::new()
    $settings = [Windows.Media.Capture.MediaCaptureInitializationSettings]::new()
    $settings.VideoDeviceId = $deviceId
    $settings.StreamingCaptureMode = [Windows.Media.Capture.StreamingCaptureMode]::Video
    $settings.MemoryPreference = [Windows.Media.Capture.MediaCaptureMemoryPreference]::Cpu
    AwaitAction ($capture.InitializeAsync($settings))

    if ($Operation -eq 'modes') {
        foreach ($entry in $capture.FrameSources) {
            $source = $entry.Value
            if ($source.Info.SourceKind -ne [Windows.Media.Capture.Frames.MediaFrameSourceKind]::Color) { continue }
            foreach ($format in $source.SupportedFormats) {
                [Console]::Out.WriteLine(('MODE', $format.VideoFormat.Width, $format.VideoFormat.Height,
                    $format.FrameRate.Numerator, $format.FrameRate.Denominator,
                    (Encode $format.Subtype), (Encode $source.Info.Id)) -join [char]9)
            }
        }
        [Console]::Out.WriteLine('END')
        exit 0
    }

    $source = $null
    foreach ($entry in $capture.FrameSources) {
        if ($entry.Value.Info.Id -eq $sourceId) { $source = $entry.Value; break }
    }
    if (!$source) { throw 'The selected camera source is no longer available. Rescan cameras.' }
    $format = $source.SupportedFormats | Where-Object {
        $_.VideoFormat.Width -eq $width -and $_.VideoFormat.Height -eq $height -and
        $_.FrameRate.Numerator -eq $numerator -and $_.FrameRate.Denominator -eq $denominator -and
        $_.Subtype -eq $subtype
    } | Select-Object -First 1
    if (!$format) { throw 'Windows no longer advertises this exact camera mode. Rescan cameras.' }
    AwaitAction ($source.SetFormatAsync($format))
    $reader = AwaitResult ($capture.CreateFrameReaderAsync($source, 'BGRA8')) ([Windows.Media.Capture.Frames.MediaFrameReader])
    $reader.AcquisitionMode = [Windows.Media.Capture.Frames.MediaFrameReaderAcquisitionMode]::Realtime
    $status = AwaitResult ($reader.StartAsync()) ([Windows.Media.Capture.Frames.MediaFrameReaderStartStatus])
    if ($status -ne [Windows.Media.Capture.Frames.MediaFrameReaderStartStatus]::Success) {
        throw ('Windows frame reader returned ' + $status + ' for ' + $subtype)
    }
    [Console]::Error.WriteLine('OPEN ' + $width + 'x' + $height + ' ' + $subtype + ' ' + $numerator + '/' + $denominator + ' fps')
    $writer = [IO.BinaryWriter]::new([Console]::OpenStandardOutput())
    $clock = [Diagnostics.Stopwatch]::StartNew()
    $nextFrameMs = 0.0
    $lastFrameMs = 0.0
    $previousTimestamp = $null
    while ($true) {
        $reference = $reader.TryAcquireLatestFrame()
        if (!$reference) {
            if ($clock.Elapsed.TotalMilliseconds - $lastFrameMs -gt 12000) { throw 'No camera frame received for 12 seconds' }
            Start-Sleep -Milliseconds 10
            continue
        }
        $bitmap = $null
        try {
            $timestamp = $reference.SystemRelativeTime
            if ($null -ne $previousTimestamp -and $timestamp -eq $previousTimestamp) {
                Start-Sleep -Milliseconds 5
                continue
            }
            $previousTimestamp = $timestamp
            $bitmap = $reference.VideoMediaFrame.SoftwareBitmap
            if (!$bitmap) { throw 'Windows supplied a frame without a CPU bitmap' }
            $lastFrameMs = $clock.Elapsed.TotalMilliseconds
            if ($lastFrameMs -lt $nextFrameMs) { continue }
            if ($bitmap.PixelWidth -ne $width -or $bitmap.PixelHeight -ne $height) {
                throw ('Camera returned ' + $bitmap.PixelWidth + 'x' + $bitmap.PixelHeight + ' instead of ' + $width + 'x' + $height)
            }
            $stream = [Windows.Storage.Streams.InMemoryRandomAccessStream]::new()
            try {
                $encoder = AwaitResult ([Windows.Graphics.Imaging.BitmapEncoder]::CreateAsync(
                    [Windows.Graphics.Imaging.BitmapEncoder]::JpegEncoderId,$stream)) ([Windows.Graphics.Imaging.BitmapEncoder])
                $encoder.SetSoftwareBitmap($bitmap)
                AwaitAction ($encoder.FlushAsync())
                $stream.Seek(0)
                $dataReader = [Windows.Storage.Streams.DataReader]::new($stream)
                try {
                    $length = [uint32]$stream.Size
                    $loaded = AwaitResult ($dataReader.LoadAsync($length)) ([uint32])
                    if ($loaded -ne $length) { throw 'Incomplete encoded frame' }
                    $bytes = New-Object byte[] $length
                    $dataReader.ReadBytes($bytes)
                    # Little-endian length, then one JPEG. No text is written to stdout during capture.
                    $writer.Write([int]$length)
                    $writer.Write($bytes)
                    $writer.Flush()
                } finally { $dataReader.Dispose() }
            } finally { $stream.Dispose() }
            $nextFrameMs = $lastFrameMs + (1000.0 / $previewFps)
        } finally {
            if ($bitmap) { $bitmap.Dispose() }
            $reference.Dispose()
        }
    }
} catch {
    $errorObject = $_.Exception
    while ($errorObject.InnerException) { $errorObject = $errorObject.InnerException }
    [Console]::Error.WriteLine(('ERROR 0x{0:X8}: {1}' -f $errorObject.HResult,$errorObject.Message))
    exit 1
} finally {
    if ($reader) { try { AwaitAction ($reader.StopAsync()) } catch {}; try { $reader.Dispose() } catch {} }
    if ($capture) { try { $capture.Dispose() } catch {} }
}
