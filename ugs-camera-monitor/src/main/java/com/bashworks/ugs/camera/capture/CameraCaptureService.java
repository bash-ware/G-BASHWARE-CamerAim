package com.bashworks.ugs.camera.capture;

import com.github.sarxos.webcam.Webcam;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public final class CameraCaptureService implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(CameraCaptureService.class.getName());
    private static final int MODE_OPEN_ATTEMPTS = 3;
    private static final int[] STANDARD_FRAME_RATES = {30, 25, 20, 15, 10, 5};
    private static final long CAMERA_RELEASE_DELAY_MS = 400L;
    private static final long PREVIOUS_SESSION_TIMEOUT_MS = 8000L;

    private final List<Consumer<String>> statusListeners = new CopyOnWriteArrayList<>();
    private final AtomicLong generation = new AtomicLong();
    private volatile boolean running;
    private volatile Thread captureThread;

    public CameraCaptureService() {
        Webcam.setDriver(CamerAimWebcamDriver.class);
    }

    public void findDevices(Consumer<List<CameraDevice>> onSuccess, Consumer<Throwable> onFailure) {
        Thread scanner = new Thread(() -> {
            try {
                List<CameraDevice> devices = Webcam.getWebcams().stream()
                        .map(CameraDevice::new)
                        .collect(Collectors.toList());
                LOGGER.log(Level.INFO, "USB cameras found: {0}", devices.size());
                onSuccess.accept(devices);
            } catch (Throwable error) {
                LOGGER.log(Level.WARNING, "Failed to discover USB cameras", error);
                onFailure.accept(error);
            }
        }, "cameraim-camera-scan");
        scanner.setDaemon(true);
        scanner.start();
    }

    public void start(CameraDevice device, Dimension size, Consumer<BufferedImage> onFrame) {
        start(device, size, device.preferredFrameRate(), onFrame);
    }

    public synchronized void start(
            CameraDevice device,
            Dimension size,
            double frameRate,
            Consumer<BufferedImage> onFrame) {
        Thread previous = captureThread;
        running = false;
        generation.incrementAndGet();

        long activeGeneration = generation.incrementAndGet();
        running = true;
        Thread next = new Thread(
                () -> startAfterPrevious(previous, activeGeneration, device, size, frameRate, onFrame),
                "cameraim-camera-capture-" + activeGeneration);
        next.setDaemon(true);
        captureThread = next;
        next.start();
    }

    private void startAfterPrevious(
            Thread previous,
            long activeGeneration,
            CameraDevice device,
            Dimension size,
            double frameRate,
            Consumer<BufferedImage> onFrame) {
        try {
            if (previous != null && previous != Thread.currentThread()) {
                previous.join(PREVIOUS_SESSION_TIMEOUT_MS);
                if (previous.isAlive()) {
                    throw new IllegalStateException("The previous camera session did not release the device in time");
                }
                Thread.sleep(CAMERA_RELEASE_DELAY_MS);
            }
            if (generation.get() != activeGeneration) return;
            captureLoop(activeGeneration, device, size, frameRate, onFrame);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            finishSession(activeGeneration, false);
        } catch (Throwable error) {
            LOGGER.log(Level.WARNING, "Failed to start USB camera", error);
            notifyStatus("Camera error: " + message(error));
            finishSession(activeGeneration, false);
        }
    }

    private void captureLoop(
            long activeGeneration,
            CameraDevice device,
            Dimension size,
            double frameRate,
            Consumer<BufferedImage> onFrame) {
        boolean failed = false;
        try {
            notifyStatus("Starting camera at " + label(size) + " @ " + fpsLabel(frameRate) + "…");
            OpenedMode mode = openCamera(device, size, frameRate);
            String nativeRate = mode.nativeFrameRate() == frameRate
                    ? ""
                    : " (camera opened at " + fpsLabel(mode.nativeFrameRate()) + ")";
            notifyStatus("Camera active: " + label(mode.size()) + " @ max " + fpsLabel(frameRate) + nativeRate);
            while (running && generation.get() == activeGeneration && !Thread.currentThread().isInterrupted()) {
                long captureStarted = System.nanoTime();
                BufferedImage image = device.image();
                if (image != null) onFrame.accept(image);
                long captureNanos = System.nanoTime() - captureStarted;
                sleepNanos(remainingDelayNanos(frameRate, captureNanos));
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Throwable error) {
            failed = true;
            LOGGER.log(Level.WARNING, "USB camera failure", error);
            notifyStatus("Camera error: " + message(error));
        } finally {
            closeCamera(device);
            finishSession(activeGeneration, !failed);
        }
    }

    private OpenedMode openCamera(CameraDevice device, Dimension requested, double previewFrameRate) throws Throwable {
        Throwable lastError = null;
        double[] nativeFrameRates = nativeFrameRateAttempts(previewFrameRate);
        for (double nativeFrameRate : nativeFrameRates) {
            for (int attempt = 1; attempt <= MODE_OPEN_ATTEMPTS; attempt++) {
                try {
                    Dimension actual = openAt(device, requested, nativeFrameRate);
                    if (sameSize(requested, actual)) return new OpenedMode(actual, nativeFrameRate);
                    lastError = new IllegalStateException(
                            "Camera returned " + label(actual) + " instead of " + label(requested));
                } catch (Throwable error) {
                    lastError = error;
                }

                closeCamera(device);
                if (attempt < MODE_OPEN_ATTEMPTS) Thread.sleep(CAMERA_RELEASE_DELAY_MS);
            }
            notifyStatus("Mode " + label(requested) + " did not open at " + fpsLabel(nativeFrameRate)
                    + "; trying another camera rate…");
        }

        throw new IllegalStateException(
                "Selected mode " + label(requested)
                        + " could not be opened at a standard camera frame rate. Close other camera applications and reconnect the USB camera.",
                lastError);
    }

    private Dimension openAt(CameraDevice device, Dimension size, double nativeFrameRate) {
        closeCamera(device);
        device.open(size, nativeFrameRate);
        Dimension actual = device.activeSize();
        return actual != null ? actual : size;
    }

    static double[] nativeFrameRateAttempts(double selected) {
        if (selected <= 0.0) throw new IllegalArgumentException("Frame rate must be positive");
        double[] attempts = new double[STANDARD_FRAME_RATES.length + 1];
        int count = 0;
        attempts[count++] = selected;
        for (int candidate : STANDARD_FRAME_RATES) {
            if (Double.compare(selected, candidate) != 0) attempts[count++] = candidate;
        }
        return java.util.Arrays.copyOf(attempts, count);
    }

    static long remainingDelayNanos(double frameRate, long captureNanos) {
        if (frameRate <= 0.0) throw new IllegalArgumentException("Frame rate must be positive");
        long intervalNanos = Math.max(1L, Math.round(1_000_000_000.0 / frameRate));
        return Math.max(0L, intervalNanos - Math.max(0L, captureNanos));
    }

    private static void sleepNanos(long delayNanos) throws InterruptedException {
        if (delayNanos <= 0L) return;
        long millis = delayNanos / 1_000_000L;
        int nanos = (int) (delayNanos % 1_000_000L);
        Thread.sleep(millis, nanos);
    }

    static boolean sameSize(Dimension expected, Dimension actual) {
        return expected == null || expected.equals(actual);
    }

    private static String label(Dimension size) {
        return size == null ? "default" : size.width + " × " + size.height;
    }

    private static String fpsLabel(double frameRate) {
        return Math.rint(frameRate) == frameRate
                ? Integer.toString((int) frameRate) + " fps"
                : Double.toString(frameRate) + " fps";
    }

    private static String message(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static void closeCamera(CameraDevice device) {
        if (!device.isOpen()) return;
        boolean interrupted = Thread.interrupted();
        try {
            device.closeCamera();
        } catch (Throwable error) {
            LOGGER.log(Level.WARNING, "Failed to close USB camera cleanly", error);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private record OpenedMode(Dimension size, double nativeFrameRate) {}

    private void finishSession(long activeGeneration, boolean reportStopped) {
        boolean active;
        synchronized (this) {
            if (captureThread == Thread.currentThread()) captureThread = null;
            active = generation.get() == activeGeneration;
            if (active) running = false;
        }
        if (active && reportStopped) notifyStatus("Camera stopped");
    }

    public synchronized void stop() {
        running = false;
        generation.incrementAndGet();
    }

    public boolean isRunning() {
        return running;
    }

    public void addStatusListener(Consumer<String> listener) {
        statusListeners.add(listener);
    }

    private void notifyStatus(String status) {
        statusListeners.forEach(listener -> listener.accept(status));
    }

    @Override
    public void close() {
        stop();
    }
}
