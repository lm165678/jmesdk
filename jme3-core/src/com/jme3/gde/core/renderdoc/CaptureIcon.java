package com.jme3.gde.core.renderdoc;

import java.awt.*;
import java.awt.geom.*;
import javax.swing.Icon;

/** Java2D adaptation of Feather's free camera icon, theme-aware and HiDPI-safe.
 * Source: https://github.com/feathericons/feather/blob/main/icons/camera.svg
 * Copyright (c) 2013-2023 Cole Bemis. MIT license in LICENSE-Feather.txt.
 */
final class CaptureIcon implements Icon {
    @Override
    public int getIconWidth() {
        return 20;
    }

    @Override
    public int getIconHeight() {
        return 20;
    }

    @Override
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.translate(x, y);
            g.scale(20.0 / 24.0, 20.0 / 24.0);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(component.getForeground());
            g.setStroke(new BasicStroke(2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D body = new Path2D.Double();
            body.moveTo(23, 19);
            body.quadTo(23, 21, 21, 21);
            body.lineTo(3, 21);
            body.quadTo(1, 21, 1, 19);
            body.lineTo(1, 8);
            body.quadTo(1, 6, 3, 6);
            body.lineTo(7, 6);
            body.lineTo(9, 3);
            body.lineTo(15, 3);
            body.lineTo(17, 6);
            body.lineTo(21, 6);
            body.quadTo(23, 6, 23, 8);
            body.closePath();
            g.draw(body);
            g.draw(new Ellipse2D.Double(8, 9, 8, 8));
        } finally {
            g.dispose();
        }
    }
}
