package com.bashworks.ugs.camera.ui;

import com.bashworks.ugs.camera.machine.MachineGateway;
import com.bashworks.ugs.camera.machine.MachineState;
import com.bashworks.ugs.camera.model.CameraOffset;
import com.bashworks.ugs.camera.model.LengthUnit;
import com.bashworks.ugs.camera.model.TargetPosition;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.function.Consumer;

public final class CameraMonitorStandalone {

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }

            JFrame frame = new JFrame("G-BASHWARE CamerAim — Standalone Preview");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setMinimumSize(new Dimension(860, 680));

            SimulatedMachine gateway = new SimulatedMachine();
            CameraMonitorPanel panel = new CameraMonitorPanel(gateway);

            frame.getContentPane().add(panel);
            frame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    panel.close();
                }
            });

            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);

            // Optional screenshot argument: --screenshot <output_path>
            for (int i = 0; i < args.length; i++) {
                if ("--screenshot".equals(args[i]) && i + 1 < args.length) {
                    final String outPath = args[i + 1];
                    new Thread(() -> {
                        try {
                            Thread.sleep(1500);
                            SwingUtilities.invokeAndWait(() -> {
                                BufferedImage img = new BufferedImage(panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_RGB);
                                Graphics2D g2 = img.createGraphics();
                                panel.paint(g2);
                                g2.dispose();
                                try {
                                    ImageIO.write(img, "PNG", new File(outPath));
                                    System.out.println("Screenshot saved to: " + outPath);
                                } catch (Exception ex) {
                                    ex.printStackTrace();
                                }
                            });
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }).start();
                }
            }
        });
    }

    private static final class SimulatedMachine implements MachineGateway {
        private double x = 12.500;
        private double y = 34.200;
        private double z = 0.000;
        private final LengthUnit unit = LengthUnit.MM;

        @Override
        public MachineState state() {
            return new MachineState(true, true, false, false, true);
        }

        @Override
        public TargetPosition machinePosition() {
            return new TargetPosition(x, y, unit);
        }

        @Override
        public void moveToolToCrosshair(CameraOffset movement, double feedRate) {
            System.out.printf("[Simulated Machine] Moving tool relative X: %+.3f, Y: %+.3f at feed %.1f %s/min%n",
                    movement.x(), movement.y(), feedRate, movement.unit());
            this.x += movement.x();
            this.y += movement.y();
        }

        @Override
        public void setWorkXyZero() {
            System.out.println("[Simulated Machine] Work XY zero set (X=0, Y=0)");
        }

        @Override
        public void moveToAimHeight(String homingCommand, double dropDistance, LengthUnit unit, double feedRate, Consumer<String> statusListener) throws Exception {
            System.out.printf("[Simulated Machine] Rehoming Z via '%s'…%n", homingCommand);
            if (statusListener != null) {
                statusListener.accept("Homing Z with " + homingCommand + "…");
            }
            Thread.sleep(1200);

            System.out.printf("[Simulated Machine] Lowering Z by %.3f %s at feed %.1f…%n", dropDistance, unit, feedRate);
            if (statusListener != null) {
                statusListener.accept(String.format("Lowering Z by %.3f %s…", dropDistance, unit));
            }
            Thread.sleep(1000);

            this.z = -dropDistance;
            System.out.printf("[Simulated Machine] Aim height reached. Machine Z: %.3f %s%n", this.z, unit);
            if (statusListener != null) {
                statusListener.accept("Aim height reached");
            }
        }
    }
}
