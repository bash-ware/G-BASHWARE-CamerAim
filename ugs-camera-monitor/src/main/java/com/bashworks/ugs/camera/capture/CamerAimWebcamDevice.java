package com.bashworks.ugs.camera.capture;

import com.github.sarxos.webcam.WebcamDevice;
import com.github.sarxos.webcam.WebcamException;
import com.github.sarxos.webcam.ds.buildin.natives.Device;
import com.github.sarxos.webcam.ds.buildin.natives.DeviceList;
import com.github.sarxos.webcam.ds.buildin.natives.OpenIMAJGrabber;
import org.bridj.Pointer;

import java.awt.Dimension;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.ComponentColorModel;
import java.awt.image.ComponentSampleModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.nio.ByteBuffer;

/**
 * UVC capture device with a selectable native frame rate. The upstream
 * webcam-capture default device requests 50 FPS, which can make Windows
 * negotiate a valid high-resolution request down to VGA.
 */
final class CamerAimWebcamDevice implements WebcamDevice, WebcamDevice.FPSSource {
    private static final Dimension[] BASE_RESOLUTIONS = {
            new Dimension(160, 120),
            new Dimension(176, 144),
            new Dimension(320, 240),
            new Dimension(640, 480)
    };
    private static final int[] BAND_OFFSETS = {0, 1, 2};
    private static final int[] BITS = {8, 8, 8};
    private static final int[] DATA_OFFSET = {0};
    private static final ColorSpace COLOR_SPACE = ColorSpace.getInstance(ColorSpace.CS_sRGB);

    private final Device nativeDevice;
    private final String name;
    private Dimension size = new Dimension(640, 480);
    private OpenIMAJGrabber grabber;
    private ComponentSampleModel sampleModel;
    private ColorModel colorModel;
    private boolean open;
    private boolean disposed;
    private long previousFrameNanos;
    private double fps;
    private double requestedFps = 30.0;

    CamerAimWebcamDevice(Device nativeDevice) {
        this.nativeDevice = nativeDevice;
        this.name = nativeDevice.getNameStr() + " " + nativeDevice.getIdentifierStr();
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Dimension[] getResolutions() {
        return copy(BASE_RESOLUTIONS);
    }

    @Override
    public synchronized Dimension getResolution() {
        return new Dimension(size);
    }

    @Override
    public synchronized void setResolution(Dimension requested) {
        if (requested == null) throw new IllegalArgumentException("Resolution cannot be null");
        if (open) throw new IllegalStateException("Cannot change resolution while the camera is open");
        size = new Dimension(requested);
    }

    synchronized void setRequestedFps(double requestedFps) {
        if (requestedFps <= 0.0) throw new IllegalArgumentException("Frame rate must be positive");
        if (open) throw new IllegalStateException("Cannot change frame rate while the camera is open");
        this.requestedFps = requestedFps;
    }

    @Override
    public synchronized BufferedImage getImage() {
        if (!open || grabber == null) return null;
        grabber.nextFrame();
        Pointer<Byte> pointer = grabber.getImage();
        if (pointer == null) return null;

        int length = Math.multiplyExact(Math.multiplyExact(size.width, size.height), 3);
        ByteBuffer buffer = pointer.getByteBuffer(length);
        byte[] bytes = new byte[length];
        buffer.get(bytes);

        DataBufferByte data = new DataBufferByte(new byte[][]{bytes}, bytes.length, DATA_OFFSET);
        WritableRaster raster = Raster.createWritableRaster(sampleModel, data, null);
        updateFps();
        return new BufferedImage(colorModel, raster, false, null);
    }

    @Override
    public synchronized void open() {
        if (disposed) throw new WebcamException("Camera device has been disposed");
        if (open) return;

        OpenIMAJGrabber candidate = new OpenIMAJGrabber();
        DeviceList devices = candidate.getVideoDevices().get();
        if (devices != null) {
            for (Device device : devices.asArrayList()) {
                device.getNameStr();
                device.getIdentifierStr();
            }
        }

        boolean started;
        try {
            started = candidate.startSession(
                    size.width,
                    size.height,
                    requestedFps,
                    Pointer.pointerTo(nativeDevice));
        } catch (RuntimeException | Error error) {
            stopSession(candidate);
            throw error;
        }
        if (!started) {
            stopSession(candidate);
            throw new WebcamException("Cannot start the native camera grabber at " + label(size)
                    + " @ " + fpsLabel(requestedFps));
        }

        candidate.setTimeout(5000);
        int actualWidth = candidate.getWidth();
        int actualHeight = candidate.getHeight();
        if (actualWidth > 0 && actualHeight > 0) {
            size = new Dimension(actualWidth, actualHeight);
        }

        sampleModel = new ComponentSampleModel(
                DataBuffer.TYPE_BYTE,
                size.width,
                size.height,
                3,
                size.width * 3,
                BAND_OFFSETS);
        colorModel = new ComponentColorModel(
                COLOR_SPACE,
                BITS,
                false,
                false,
                Transparency.OPAQUE,
                DataBuffer.TYPE_BYTE);
        grabber = candidate;
        previousFrameNanos = 0L;
        fps = 0.0;
        open = true;
    }

    @Override
    public synchronized void close() {
        if (!open) return;
        open = false;
        OpenIMAJGrabber active = grabber;
        grabber = null;
        if (active != null) active.stopSession();
    }

    @Override
    public synchronized void dispose() {
        close();
        disposed = true;
    }

    @Override
    public synchronized boolean isOpen() {
        return open;
    }

    @Override
    public synchronized double getFPS() {
        return fps;
    }

    private void updateFps() {
        long now = System.nanoTime();
        if (previousFrameNanos != 0L) {
            double current = 1_000_000_000.0 / Math.max(1L, now - previousFrameNanos);
            fps = fps == 0.0 ? current : (fps * 4.0 + current) / 5.0;
        }
        previousFrameNanos = now;
    }

    private static String label(Dimension dimension) {
        return dimension.width + " × " + dimension.height;
    }

    private static void stopSession(OpenIMAJGrabber candidate) {
        try {
            candidate.stopSession();
        } catch (Throwable ignored) {
            // A failed native start may not have created a stoppable session.
        }
    }

    private static String fpsLabel(double frameRate) {
        return Math.rint(frameRate) == frameRate
                ? Integer.toString((int) frameRate) + " fps"
                : Double.toString(frameRate) + " fps";
    }

    private static Dimension[] copy(Dimension[] dimensions) {
        Dimension[] result = new Dimension[dimensions.length];
        for (int i = 0; i < dimensions.length; i++) result[i] = new Dimension(dimensions[i]);
        return result;
    }
}
