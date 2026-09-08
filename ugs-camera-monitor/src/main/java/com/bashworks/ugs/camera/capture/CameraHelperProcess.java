package com.bashworks.ugs.camera.capture;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Owns one external capture process. No native camera library is loaded into UGS. */
final class CameraHelperProcess implements AutoCloseable {
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "cameraim-helper-watchdog");
        thread.setDaemon(true);
        return thread;
    });
    private final Process process;
    private final DataInputStream input;
    private final ScheduledFuture<?> watchdog;
    private final Thread stderrReader;
    private final StringBuilder errors = new StringBuilder();
    private volatile long lastProgress = System.nanoTime();
    private volatile long timeoutNanos;
    private volatile boolean timedOut;

    CameraHelperProcess(Process process, Duration timeout, Consumer<String> diagnostics) {
        this.process = process;
        this.input = new DataInputStream(new BufferedInputStream(process.getInputStream()));
        timeoutNanos = timeout.toNanos();
        stderrReader = new Thread(() -> {
            try (Reader reader = new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8)) {
                char[] chunk = new char[1024];
                int count;
                while ((count = reader.read(chunk)) != -1) {
                    String message = new String(chunk, 0, count);
                    synchronized (errors) {
                        errors.append(message);
                        if (errors.length() > 16000) errors.delete(0, errors.length() - 16000);
                    }
                    diagnostics.accept(message.strip());
                }
            } catch (IOException ignored) { }
        }, "cameraim-helper-stderr");
        stderrReader.setDaemon(true);
        stderrReader.start();
        watchdog = WATCHDOG.scheduleAtFixedRate(() -> {
            if (process.isAlive() && System.nanoTime() - lastProgress > timeoutNanos) {
                timedOut = true;
                process.destroyForcibly();
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
    }

    void configure(String config) throws IOException {
        process.getOutputStream().write(config.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().flush();
        // Keep stdin open. EOF tells the helper that its UGS parent has gone away.
    }

    String readLine() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1 && value != '\n') {
            if (line.size() >= 65536) throw new IOException("Oversized camera helper response");
            if (value != '\r') line.write(value);
        }
        if (value == -1 && line.size() == 0) throw failure();
        lastProgress = System.nanoTime();
        return line.toString(StandardCharsets.UTF_8);
    }

    BufferedImage readFrame(Dimension expected) throws IOException {
        try {
            BufferedImage frame = decodeFrame(input, expected);
            lastProgress = System.nanoTime();
            timeoutNanos = TimeUnit.SECONDS.toNanos(15);
            return frame;
        } catch (EOFException error) {
            throw failure();
        }
    }

    static BufferedImage decodeFrame(DataInputStream input, Dimension expected) throws IOException {
        int length = Integer.reverseBytes(input.readInt());
        if (length < 4 || length > 24 * 1024 * 1024) {
            throw new IOException("Invalid camera frame length: " + length);
        }
        byte[] jpeg = new byte[length];
        input.readFully(jpeg);
        try (MemoryCacheImageInputStream stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(jpeg))) {
            Iterator<ImageReader> decoders = ImageIO.getImageReaders(stream);
            if (!decoders.hasNext()) throw new IOException("Camera helper returned an invalid image");
            ImageReader decoder = decoders.next();
            try {
                decoder.setInput(stream);
                int width = decoder.getWidth(0);
                int height = decoder.getHeight(0);
                if (width != expected.width || height != expected.height) {
                    throw new IOException("Camera returned " + width + " × " + height + " instead of "
                            + expected.width + " × " + expected.height);
                }
                return decoder.read(0);
            } finally { decoder.dispose(); }
        }
    }

    IOException failure() {
        try { stderrReader.join(200); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        String detail;
        synchronized (errors) { detail = errors.toString().strip(); }
        if (timedOut) return new IOException("Windows camera helper timed out; the helper was stopped.");
        if (detail.isBlank()) {
            detail = process.isAlive() ? "Camera helper closed its output."
                    : "Camera helper exited with code " + process.exitValue() + ".";
        }
        return new IOException(detail);
    }

    void cancel() { watchdog.cancel(false); process.destroyForcibly(); }

    @Override public void close() {
        watchdog.cancel(false);
        try { process.getOutputStream().close(); } catch (IOException ignored) { }
        if (process.isAlive()) process.destroyForcibly();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException error) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
        try { input.close(); } catch (IOException ignored) { }
    }
}
