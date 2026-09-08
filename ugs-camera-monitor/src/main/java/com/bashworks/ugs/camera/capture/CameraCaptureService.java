package com.bashworks.ugs.camera.capture;

import com.bashworks.ugs.camera.PluginInfo;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;

public final class CameraCaptureService implements AutoCloseable {
    private final WindowsCameraBackend backend = new WindowsCameraBackend();
    private final List<Consumer<String>> statusListeners = new CopyOnWriteArrayList<>();
    private final ExecutorService captures = daemonExecutor("cameraim-capture");
    private final ExecutorService scans = daemonExecutor("cameraim-scan");
    private final StringBuilder diagnostics = new StringBuilder();
    private long generation;
    private long scanGeneration;
    private volatile boolean running;
    private CameraHelperProcess activeCapture;
    private CameraHelperProcess activeScan;

    public CameraCaptureService() {
        log(PluginInfo.TITLE + "\nBackend: Windows MediaCapture (separate process)\n"
                + "OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " " + System.getProperty("os.arch") + "\nJava: " + System.getProperty("java.version"));
    }

    private static ExecutorService daemonExecutor(String name) {
        return Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    public void findDevices(Consumer<List<CameraDevice>> success, Consumer<Throwable> failure) {
        scan("devices", helper -> backend.devices(helper), success, failure);
    }

    public void findModes(CameraDevice device, Consumer<List<CameraMode>> success, Consumer<Throwable> failure) {
        scan("modes", helper -> {
            List<CameraMode> modes = backend.modes(helper, device);
            log("Camera: " + device + "\nWindows formats:\n"
                    + modes.stream().map(CameraMode::label).reduce("", (a, b) -> a + b + "\n"));
            return modes;
        }, modes -> { device.setModes(modes); success.accept(modes); }, failure);
    }

    private synchronized <T> void scan(String operation, Query<T> query, Consumer<T> success, Consumer<Throwable> failure) {
        long token = ++scanGeneration;
        if (activeScan != null) activeScan.cancel();
        scans.submit(() -> {
            try {
                T result;
                synchronized (this) { if (scanGeneration != token) return; }
                try (CameraHelperProcess helper = backend.launch(operation, this::log)) {
                    synchronized (this) {
                        if (scanGeneration != token) return;
                        activeScan = helper;
                    }
                    result = query.run(helper);
                }
                synchronized (this) {
                    if (scanGeneration != token) return;
                    activeScan = null;
                    success.accept(result);
                }
            } catch (Exception error) {
                log("Discovery failed: " + error.getMessage());
                synchronized (this) {
                    if (scanGeneration == token) {
                        activeScan = null;
                        failure.accept(error);
                    }
                }
            }
        });
    }

    public void start(CameraDevice device, Dimension size, Consumer<BufferedImage> onFrame) {
        start(device, size, device.preferredFrameRate(), onFrame);
    }

    public synchronized void start(CameraDevice device, Dimension size, double previewRate, Consumer<BufferedImage> onFrame) {
        if (!Double.isFinite(previewRate) || previewRate <= 0 || previewRate > 120) {
            throw new IllegalArgumentException("Invalid preview frame rate");
        }
        long token = ++generation;
        if (activeCapture != null) activeCapture.cancel();
        running = true;
        publish(token, "Starting camera at " + label(size) + "…");
        captures.submit(() -> capture(token, device, size, previewRate, onFrame));
    }

    private void capture(long token, CameraDevice device, Dimension size, double previewRate, Consumer<BufferedImage> onFrame) {
        List<CameraMode> candidates = device.candidates(size);
        String lastError = "No Windows-reported mode matches this resolution. Rescan cameras.";
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        for (CameraMode mode : candidates) {
            if (System.nanoTime() > deadline) break;
            synchronized (this) { if (generation != token) return; }
            boolean deliveredFrame = false;
            try (CameraHelperProcess helper = backend.launch("capture", this::log)) {
                synchronized (this) {
                    if (generation != token) return;
                    activeCapture = helper;
                }
                log("Opening " + device + ": " + mode.label() + "; preview limit " + previewRate);
                publish(token, "Starting camera: " + mode.label() + "…");
                backend.configureCapture(helper, device, mode, previewRate);
                while (isActive(token)) {
                    BufferedImage frame = helper.readFrame(size);
                    synchronized (this) {
                        if (generation != token) return;
                        if (!deliveredFrame) {
                            deliveredFrame = true;
                            publish(token, "Camera active: " + mode.label() + " | Preview max " + previewRate + " fps");
                        }
                        onFrame.accept(frame);
                    }
                }
                return;
            } catch (Exception error) {
                synchronized (this) { if (generation != token) return; }
                lastError = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                log("Capture failed for " + mode.label() + ": " + lastError);
                // A live-session failure is reported; never silently reconnect a frozen positioning image.
                // Permission denial is not a format negotiation failure.
                if (deliveredFrame || lastError.contains("80070005")) break;
            } finally {
                synchronized (this) { if (generation == token) activeCapture = null; }
            }
        }
        synchronized (this) {
            if (generation != token) return;
            running = false;
            publish(token, "Camera error: " + concise(lastError) + " Use Copy diagnostics for details.");
        }
    }

    private synchronized boolean isActive(long token) { return running && generation == token; }

    public synchronized void stop() {
        generation++;
        running = false;
        if (activeCapture != null) activeCapture.cancel();
        activeCapture = null;
        publish(generation, "Camera stopped");
    }

    public boolean isRunning() { return running; }
    public void addStatusListener(Consumer<String> listener) { statusListeners.add(listener); }

    private synchronized void publish(long token, String status) {
        if (generation != token) return;
        log(status);
        statusListeners.forEach(listener -> listener.accept(status));
    }

    private void log(String message) {
        synchronized (diagnostics) {
            diagnostics.append(message).append('\n');
            if (diagnostics.length() > 32000) diagnostics.delete(0, diagnostics.length() - 32000);
        }
    }

    public String diagnostics() {
        synchronized (diagnostics) { return diagnostics.toString(); }
    }

    private static String concise(String message) {
        String oneLine = message.replace('\r', ' ').replace('\n', ' ').strip();
        return oneLine.length() > 300 ? oneLine.substring(0, 300) + "…" : oneLine;
    }

    private static String label(Dimension size) { return size.width + " × " + size.height; }
    @FunctionalInterface private interface Query<T> { T run(CameraHelperProcess helper) throws Exception; }

    @Override public synchronized void close() {
        stop();
        scanGeneration++;
        if (activeScan != null) activeScan.cancel();
        activeScan = null;
    }
}
