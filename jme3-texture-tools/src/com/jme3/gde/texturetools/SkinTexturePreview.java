package com.jme3.gde.texturetools;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import javax.swing.JPanel;

/** Fit by default; wheel zoom anchors to the cursor and dragging pans the image. */
final class SkinTexturePreview extends JPanel {
    private BufferedImage image;
    private boolean fitted = true;
    private double scale = 1.0;
    private double offsetX;
    private double offsetY;

    SkinTexturePreview() {
        setBackground(new Color(35, 38, 42));
        MouseAdapter navigation = new MouseAdapter() {
            private Point last;

            @Override
            public void mousePressed(MouseEvent event) {
                last = event.getPoint();
                if (fitted) {
                    scale = fittedScale();
                    offsetX = (getWidth() - imageWidth() * scale) * .5;
                    offsetY = (getHeight() - imageHeight() * scale) * .5;
                    fitted = false;
                }
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if (last != null) {
                    offsetX += event.getX() - last.x;
                    offsetY += event.getY() - last.y;
                    last = event.getPoint();
                    repaint();
                }
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                last = null;
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent event) {
                if (image == null) {
                    return;
                }
                double oldScale = fitted ? fittedScale() : scale;
                double oldX = fitted ? (getWidth() - image.getWidth() * oldScale) * .5 : offsetX;
                double oldY = fitted ? (getHeight() - image.getHeight() * oldScale) * .5 : offsetY;
                scale = Math.max(.02, Math.min(32.0, oldScale * Math.pow(1.15, -event.getPreciseWheelRotation())));
                offsetX = event.getX() - (event.getX() - oldX) * scale / oldScale;
                offsetY = event.getY() - (event.getY() - oldY) * scale / oldScale;
                fitted = false;
                repaint();
            }
        };
        addMouseListener(navigation);
        addMouseMotionListener(navigation);
        addMouseWheelListener(navigation);
    }

    void setImage(BufferedImage value) {
        boolean changedSize = image == null || value == null || image.getWidth() != value.getWidth() || image.getHeight() != value.getHeight();
        image = value;
        if (changedSize) {
            fitted = true;
        }
        repaint();
    }

    void fit() {
        fitted = true;
        repaint();
    }

    private int imageWidth() {
        return image == null ? 1 : image.getWidth();
    }

    private int imageHeight() {
        return image == null ? 1 : image.getHeight();
    }

    private double fittedScale() {
        return Math.max(.001, Math.min((double) Math.max(1, getWidth() - 20) / imageWidth(),
                (double) Math.max(1, getHeight() - 20) / imageHeight()));
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (image == null) {
            graphics.setColor(Color.LIGHT_GRAY);
            graphics.drawString("Choose a normal map and click Generate", 20, 30);
            return;
        }
        double zoom = fitted ? fittedScale() : scale;
        double x = fitted ? (getWidth() - image.getWidth() * zoom) * .5 : offsetX;
        double y = fitted ? (getHeight() - image.getHeight() * zoom) * .5 : offsetY;
        Graphics2D canvas = (Graphics2D) graphics.create();
        canvas.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        canvas.translate(x, y);
        canvas.scale(zoom, zoom);
        canvas.drawImage(image, 0, 0, null);
        canvas.dispose();
    }
}
