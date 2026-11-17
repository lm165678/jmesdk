package com.jme3.gde.core.editor.nodes;

import java.awt.Color;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import javax.swing.JLayer;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/** Headless regression for the composed JLayer, scaled paint and inverse mouse coordinates. */
public final class DiagramZoomSmoke {
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Diagram diagram = new TestDiagram();
            diagram.setLayout(null);
            JPanel target = new JPanel();
            target.setBackground(Color.RED);
            target.setBounds(40, 30, 40, 30);
            Point[] received = new Point[1];
            target.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent event) {
                    received[0] = event.getPoint();
                }
            });
            diagram.add(target);
            DiagramZoomLayer wrapper = new DiagramZoomLayer(diagram);
            wrapper.setSize(800, 600);
            wrapper.setZoom(2, new Point());
            wrapper.doLayout();
            JLayer<?> layer = (JLayer<?>) wrapper.getComponent(0);
            layer.doLayout();
            if (diagram.getWidth() != 400 || !layer.getGlassPane().isVisible()) {
                throw new AssertionError("Scaled diagram bounds and event glass");
            }
            layer.getGlassPane().dispatchEvent(new MouseEvent(layer.getGlassPane(), MouseEvent.MOUSE_PRESSED,
                    1, 0, 100, 80, 1, false, MouseEvent.BUTTON1));
            if (!new Point(10, 10).equals(received[0])) {
                throw new AssertionError("Inverse mouse mapping: " + received[0]);
            }
            BufferedImage image = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            wrapper.paint(graphics);
            graphics.dispose();
            if (image.getRGB(100, 80) != Color.RED.getRGB()) {
                throw new AssertionError("Scaled child painting");
            }
            wrapper.setZoom(1, new Point());
            if (layer.getGlassPane().isVisible()) {
                throw new AssertionError("Normal zoom restores native event delivery");
            }
            System.out.println("DIAGRAM_ZOOM_COMPOSITION_PAINT_INPUT_PASS");
        });
    }

    private static final class TestDiagram extends Diagram {
        @Override
        protected boolean mouseLMBPrePressedEvent(MouseEvent event) {
            return false;
        }

        @Override
        public String makeKeyForConnection(Connection connection, Object object) {
            return "test";
        }

        @Override
        protected void showEdit(NodePanel node) {
        }

        @Override
        protected Selectable trySelect(String key) {
            return null;
        }

        @Override
        protected int calcMaxHeight() {
            return 600;
        }

        @Override
        protected int calcMaxWidth() {
            return 800;
        }

        @Override
        public void autoLayout() {
        }

        @Override
        public void toggleUpdateThread(boolean on) {
        }
    }
}
