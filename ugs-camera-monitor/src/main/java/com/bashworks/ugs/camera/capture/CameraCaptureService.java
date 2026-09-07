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
    private final List<Consumer<String>> statusListeners = new CopyOnWriteArrayList<>();
    private final AtomicLong generation = new AtomicLong();
    private volatile boolean running;
    private volatile Thread captureThread;

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
        }, "ugs-camera-scan");
        scanner.setDaemon(true);
        scanner.start();
    }

    public synchronized void start(CameraDevice device, Dimension size, Consumer<BufferedImage> onFrame) {
        Thread previous = captureThread;
        stop();
        long activeGeneration = generation.incrementAndGet();
        running = true;
        captureThread = new Thread(() -> {
            if (previous != null) {
                try {
                    previous.join(1500L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            captureLoop(activeGeneration, device, size, onFrame);
        }, "ugs-camera-capture");
        captureThread.setDaemon(true);
        captureThread.start();
    }

    private void captureLoop(long activeGeneration, CameraDevice device, Dimension size, Consumer<BufferedImage> onFrame) {
        Webcam webcam = device.webcam();
        try {
            notifyStatus("Starting camera…");
            Dimension activeSize = openCamera(webcam, device, size);
            notifyStatus("Camera active: " + label(activeSize));
            while (running && generation.get() == activeGeneration && !Thread.currentThread().isInterrupted()) {
                BufferedImage image = webcam.getImage();
                if (image != null) onFrame.accept(image);
                Thread.sleep(40L);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (Throwable error) {
            LOGGER.log(Level.WARNING, "USB camera failure", error);
            notifyStatus("Camera error: " + error.getMessage());
        } finally {
            if (webcam.isOpen()) webcam.close();
            if (generation.get() == activeGeneration) {
                running = false;
                captureThread = null;
                notifyStatus("Camera stopped");
            }
        }
    }

    private Dimension openCamera(Webcam webcam, CameraDevice device, Dimension requested) throws Throwable {
        try {
            return openAt(webcam, requested);
        } catch (Throwable firstError) {
            if (webcam.isOpen()) webcam.close();
            Dimension fallback = device.fallbackViewSize(requested);
            if (fallback == null) throw firstError;

            LOGGER.log(
                    Level.INFO,
                    "Resolution {0} was rejected; trying reported mode {1}",
                    new Object[]{label(requested), label(fallback)});
            notifyStatus("Mode " + label(requested) + " is unsupported; trying " + label(fallback));
            return openAt(webcam, fallback);
        }
    }

    private Dimension openAt(Webcam webcam, Dimension size) {
        if (size != null) webcam.setViewSize(size);
        if (!webcam.open(true)) {
            throw new IllegalStateException("The driver did not open the selected mode " + label(size));
        }
        Dimension actual = webcam.getViewSize();
        return actual != null ? actual : size;
    }

    private static String label(Dimension size) {
        return size == null ? "default" : size.width + " × " + size.height;
    }

    public synchronized void stop() {
        running = false;
        generation.incrementAndGet();
        Thread thread = captureThread;
        captureThread = null;
        if (thread != null) thread.interrupt();
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
