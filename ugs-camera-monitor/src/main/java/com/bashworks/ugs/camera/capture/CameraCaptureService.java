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

    public synchronized void start(CameraDevice device, Dimension size, Consumer<BufferedImage> onFrame) {
        Thread previous = captureThread;
        running = false;
        generation.incrementAndGet();

        long activeGeneration = generation.incrementAndGet();
        running = true;
        Thread next = new Thread(
                () -> startAfterPrevious(previous, activeGeneration, device, size, onFrame),
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
            captureLoop(activeGeneration, device, size, onFrame);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            finishSession(activeGeneration, false);
        } catch (Throwable error) {
            LOGGER.log(Level.WARNING, "Failed to start USB camera", error);
            notifyStatus("Camera error: " + message(error));
            finishSession(activeGeneration, false);
        }
    }

    private void captureLoop(long activeGeneration, CameraDevice device, Dimension size, Consumer<BufferedImage> onFrame) {
        Webcam webcam = device.webcam();
        boolean failed = false;
        try {
            notifyStatus("Starting camera at " + label(size) + "…");
            Dimension activeSize = openCamera(webcam, size);
            notifyStatus("Camera active: " + label(activeSize));
            while (running && generation.get() == activeGeneration && !Thread.currentThread().isInterrupted()) {
                BufferedImage image = webcam.getImage();
                if (image != null) onFrame.accept(image);
                Thread.sleep(10L);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Throwable error) {
            failed = true;
            LOGGER.log(Level.WARNING, "USB camera failure", error);
            notifyStatus("Camera error: " + message(error));
        } finally {
            closeCamera(webcam);
            finishSession(activeGeneration, !failed);
        }
    }

    private Dimension openCamera(Webcam webcam, Dimension requested) throws Throwable {
        Throwable lastError = null;
        for (int attempt = 1; attempt <= MODE_OPEN_ATTEMPTS; attempt++) {
            try {
                Dimension actual = openAt(webcam, requested);
                if (sameSize(requested, actual)) return actual;
                lastError = new IllegalStateException(
                        "Camera returned " + label(actual) + " instead of " + label(requested));
            } catch (Throwable error) {
                lastError = error;
            }

            closeCamera(webcam);
            if (attempt < MODE_OPEN_ATTEMPTS) {
                notifyStatus("Mode " + label(requested) + " was not confirmed; retrying "
                        + (attempt + 1) + "/" + MODE_OPEN_ATTEMPTS + "…");
                Thread.sleep(CAMERA_RELEASE_DELAY_MS);
            }
        }

        throw new IllegalStateException(
                "Selected mode " + label(requested)
                        + " could not be confirmed at 30 fps; no fallback resolution was applied",
                lastError);
    }

    private Dimension openAt(Webcam webcam, Dimension size) {
        closeCamera(webcam);
        if (size != null) webcam.setViewSize(size);
        if (!webcam.open(false)) {
            throw new IllegalStateException("The driver did not open the selected mode " + label(size));
        }
        Dimension actual = webcam.getViewSize();
        return actual != null ? actual : size;
    }

    static boolean sameSize(Dimension expected, Dimension actual) {
        return expected == null || expected.equals(actual);
    }

    private static String label(Dimension size) {
        return size == null ? "default" : size.width + " × " + size.height;
    }

    private static String message(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static void closeCamera(Webcam webcam) {
        if (!webcam.isOpen()) return;
        boolean interrupted = Thread.interrupted();
        try {
            webcam.close();
        } catch (Throwable error) {
            LOGGER.log(Level.WARNING, "Failed to close USB camera cleanly", error);
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

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