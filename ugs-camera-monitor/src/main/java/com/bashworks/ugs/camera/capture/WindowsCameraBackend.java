package com.bashworks.ugs.camera.capture;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;

final class WindowsCameraBackend {
    private static Path helperScript;

    static synchronized Path helperScript() throws IOException {
        if (helperScript != null && Files.isRegularFile(helperScript)) return helperScript;
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            throw new IOException("This camera backend requires Windows 10 or Windows 11.");
        }
        Path directory = Files.createTempDirectory("cameraim-mediacapture-");
        Path script = directory.resolve("windows-camera.ps1");
        try (InputStream source = WindowsCameraBackend.class.getResourceAsStream("windows-camera.ps1")) {
            if (source == null) throw new IOException("The Windows camera helper is missing from the plugin.");
            Files.copy(source, script);
        }
        directory.toFile().deleteOnExit();
        script.toFile().deleteOnExit();
        helperScript = script;
        return script;
    }

    CameraHelperProcess launch(String operation, Consumer<String> diagnostics) throws IOException {
        Path powershell = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"),
                "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        // No shell interpolation: fixed helper path and operation are separate process arguments.
        ProcessBuilder builder = new ProcessBuilder(powershell.toString(), "-NoLogo", "-NoProfile",
                "-NonInteractive", "-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass",
                "-File", helperScript().toString(), "-Operation", operation);
        return new CameraHelperProcess(builder.start(), Duration.ofSeconds(25), diagnostics);
    }

    List<CameraDevice> devices(CameraHelperProcess helper) throws IOException {
        List<CameraDevice> devices = new ArrayList<>();
        for (String line; !(line = helper.readLine()).equals("END"); ) {
            String[] fields = line.split("\t", -1);
            if (fields.length != 3 || !fields[0].equals("DEVICE")) throw new IOException("Invalid camera discovery response");
            devices.add(new CameraDevice(CameraMode.decode(fields[1]), CameraMode.decode(fields[2])));
        }
        return List.copyOf(devices);
    }

    List<CameraMode> modes(CameraHelperProcess helper, CameraDevice device) throws IOException {
        helper.configure(CameraMode.encode(device.id()) + "\n");
        List<CameraMode> modes = new ArrayList<>();
        for (String line; !(line = helper.readLine()).equals("END"); ) modes.add(CameraMode.parse(line));
        if (modes.isEmpty()) throw new IOException("Windows reports no color video formats for this camera.");
        return List.copyOf(modes);
    }

    void configureCapture(CameraHelperProcess helper, CameraDevice device, CameraMode mode, double previewRate)
            throws IOException {
        helper.configure(String.join("\n", CameraMode.encode(device.id()), CameraMode.encode(mode.sourceId()),
                Integer.toString(mode.width()), Integer.toString(mode.height()), Long.toString(mode.numerator()),
                Long.toString(mode.denominator()), CameraMode.encode(mode.subtype()), Double.toString(previewRate)) + "\n");
    }
}
