package com.bashworks.ugs.camera.capture;

import org.junit.Test;
import javax.imageio.ImageIO;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class CameraHelperProcessTest {
    private byte[] frame(int width, int height) throws IOException {
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width,height,BufferedImage.TYPE_3BYTE_BGR), "jpeg", jpeg);
        ByteArrayOutputStream protocol = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(protocol);
        data.writeInt(Integer.reverseBytes(jpeg.size()));
        data.write(jpeg.toByteArray());
        return protocol.toByteArray();
    }

    @Test public void decodesExactFramesAndRejectsSubstitutedResolution() throws Exception {
        byte[] packet = frame(640,480);
        assertEquals(640,CameraHelperProcess.decodeFrame(new DataInputStream(new ByteArrayInputStream(packet)),
                new Dimension(640,480)).getWidth());
        try {
            CameraHelperProcess.decodeFrame(new DataInputStream(new ByteArrayInputStream(packet)),new Dimension(2592,1944));
            fail("Substituted resolution must fail");
        } catch(IOException expected) { assertTrue(expected.getMessage().contains("instead of")); }
    }

    @Test public void rejectsTruncatedAndOversizedFramePackets() throws Exception {
        for(byte[] packet : new byte[][]{{-1,-1,-1,127}, {10,0,0,0,1,2}}) {
            try {
                CameraHelperProcess.decodeFrame(new DataInputStream(new ByteArrayInputStream(packet)),new Dimension(640,480));
                fail("Malformed frame must fail");
            } catch(IOException expected) { }
        }
    }

    private Process helper(String operation) throws IOException {
        String java = Path.of(System.getProperty("java.home"),"bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String classes;
        // URI handles workspace paths containing spaces on both Windows and Linux.
        try { classes = Path.of(Fixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(); }
        catch(Exception error) { throw new IOException(error); }
        return new ProcessBuilder(java,"-cp",classes,Fixture.class.getName(),operation).start();
    }

    @Test public void helperCrashDoesNotCrashCallerAndNextSessionWorks() throws Exception {
        try(CameraHelperProcess broken = new CameraHelperProcess(helper("crash"),Duration.ofSeconds(5),line -> {})) {
            try { broken.readLine(); fail("Expected helper failure"); }
            catch(IOException expected) { assertTrue(expected.getMessage().contains("simulated native failure")); }
        }
        try(CameraHelperProcess next = new CameraHelperProcess(helper("ok"),Duration.ofSeconds(5),line -> {})) {
            assertEquals("END",next.readLine());
        }
    }

    @Test public void watchdogTerminatesAnUnresponsiveHelper() throws Exception {
        Process child = helper("hang");
        try(CameraHelperProcess helper = new CameraHelperProcess(child,Duration.ofMillis(500),line -> {})) {
            try { helper.readLine(); fail("Expected timeout"); }
            catch(IOException expected) { assertTrue(expected.getMessage().contains("timed out")); }
        }
        assertTrue(child.waitFor(2,TimeUnit.SECONDS));
        assertFalse(child.isAlive());
    }

    @Test public void cancellationKillsOnlyOwnedHelper() throws Exception {
        Process child = helper("hang");
        try(CameraHelperProcess helper = new CameraHelperProcess(child,Duration.ofSeconds(5),line -> {})) {
            helper.cancel();
        }
        assertFalse(child.isAlive());
        assertTrue(ProcessHandle.current().isAlive());
    }

    public static class Fixture {
        public static void main(String[] args) throws Exception {
            if(args[0].equals("crash")) {
                System.err.println("simulated native failure");
                System.exit(37);
            } else if(args[0].equals("hang")) { Thread.sleep(60000); }
            else { System.out.println("END"); }
        }
    }
}
