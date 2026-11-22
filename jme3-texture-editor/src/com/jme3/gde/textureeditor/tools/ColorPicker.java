package com.jme3.gde.textureeditor.tools;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.SwingUtilities;
import com.jme3.gde.textureeditor.EditorTool;
import com.jme3.gde.textureeditor.EditorToolTarget;

public class ColorPicker extends MouseAdapter implements EditorTool {

    public static ColorPicker create() {
        return new ColorPicker();
    }
    private EditorToolTarget target;

    protected ColorPicker() {
    }

    public void install(EditorToolTarget t) {
        target = t;
        target.getImageCanvas().addMouseListener(this);
    }

    public void uninstall(EditorToolTarget t) {
        target.getImageCanvas().removeMouseListener(this);
    }

    public void drawTrack(Graphics2D g, int width, int height, float scaleX, float scaleY) {
    }

    @Override
    public void mousePressed(MouseEvent e) {
        if (!SwingUtilities.isLeftMouseButton(e)) {
            return;
        }
        Point p = e.getPoint();
        p.x /= target.getScaleX();
        p.y /= target.getScaleY();
        Color picked = new Color(target.getCurrentImage().getRGB(p.x, p.y));
        if (e.isShiftDown()) {
            target.setBackground(picked);
        } else {
            target.setForeground(picked);
        }
    }
}
