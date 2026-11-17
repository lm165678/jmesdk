/*
 * Copyright (c) 2009-2026 jMonkeyEngine
 * All rights reserved.
 */
package com.jme3.gde.core.editor.nodes;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import javax.swing.JComponent;
import javax.swing.JLayer;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.plaf.LayerUI;

/**
 * Scales a diagram and uses its glass pane to inverse-transform and redispatch
 * mouse events to the actual Swing child at the transformed position. This
 * avoids AWT choosing a child using unscaled bounds before LayerUI sees the
 * event. Ordinary wheel events scroll; Shift+wheel zooms around the cursor.
 *
 * 第一步：创建 DiagramZoomLayer 并包装现有 Diagram。
 * 第二步：把包装层设置为 JScrollPane 的 viewport view，不要改变 Diagram 模型坐标。
 * 第三步：用户按住 Shift 滚动鼠标即可缩放；普通滚轮仍用于滚动。
 * <pre>
 * DiagramZoomLayer zoomLayer = new DiagramZoomLayer(diagram);
 * scrollPane.setViewportView(zoomLayer);
 * </pre>
 * 缩放只影响编辑器显示，不修改材质节点坐标或 J3MD。
 */
public final class DiagramZoomLayer extends JPanel {

    private static final double MIN_ZOOM = 0.25;
    private static final double MAX_ZOOM = 3.0;
    private double zoom = 1.0;
    private final JPanel eventGlass = new JPanel();
    private final JLayer<Diagram> layer;
    private Component dragTarget;
    private Component hoverTarget;

    public DiagramZoomLayer(Diagram diagram) {
        super(new java.awt.BorderLayout());
        // JLayer is final: compose it rather than subclassing it.
        layer = new JLayer<>(diagram, new ZoomLayerUI());
        add(layer, java.awt.BorderLayout.CENTER);
        eventGlass.setOpaque(false);
        MouseAdapter mouseHandler = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                redispatch(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                redispatch(e);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                redispatch(e);
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                redispatch(e);
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                redispatch(e);
            }

            @Override
            public void mouseEntered(MouseEvent e) {
                redispatch(e);
            }

            @Override
            public void mouseExited(MouseEvent e) {
                redispatch(e);
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                if (e.isShiftDown()) {
                    Point layerPoint = SwingUtilities.convertPoint((Component) e.getSource(),
                            e.getPoint(), DiagramZoomLayer.this);
                    double factor = Math.pow(1.1, -e.getWheelRotation());
                    setZoom(zoom * factor, layerPoint);
                    e.consume();
                } else {
                    forwardWheelEvent(e);
                }
            }
        };
        eventGlass.addMouseListener(mouseHandler);
        eventGlass.addMouseMotionListener(mouseHandler);
        eventGlass.addMouseWheelListener(mouseHandler);
        layer.addMouseWheelListener(mouseHandler);
        layer.setGlassPane(eventGlass);
        eventGlass.setVisible(false);
    }

    public double getZoom() {
        return zoom;
    }

    public Diagram getView() {
        return layer.getView();
    }

    public void setZoom(double requestedZoom, Point anchor) {
        double nextZoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, requestedZoom));
        if (nextZoom == zoom) {
            return;
        }
        JViewport viewport = getView().getViewport();
        final Point viewportAnchor = viewport == null ? new Point(anchor)
                : SwingUtilities.convertPoint(this, anchor, viewport);
        final Point modelAnchor = new Point(
                (int) Math.round(anchor.x / zoom),
                (int) Math.round(anchor.y / zoom));
        zoom = nextZoom;
        getView().setZoom(zoom);
        eventGlass.setVisible(zoom != 1.0);
        if (!eventGlass.isVisible()) {
            dragTarget = null;
            hoverTarget = null;
        }
        revalidate();
        repaint();
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                JViewport currentViewport = getView().getViewport();
                if (currentViewport == null) {
                    return;
                }
                Point viewPosition = new Point(
                        (int) Math.round(modelAnchor.x * zoom) - viewportAnchor.x,
                        (int) Math.round(modelAnchor.y * zoom) - viewportAnchor.y);
                Dimension viewSize = currentViewport.getViewSize();
                Dimension extentSize = currentViewport.getExtentSize();
                viewPosition.x = Math.max(0, Math.min(viewPosition.x,
                        viewSize.width - extentSize.width));
                viewPosition.y = Math.max(0, Math.min(viewPosition.y,
                        viewSize.height - extentSize.height));
                currentViewport.setViewPosition(viewPosition);
            }
        });
    }

    @Override
    public Dimension getPreferredSize() {
        return scaled(getView().getPreferredSize());
    }

    @Override
    public Dimension getMinimumSize() {
        return scaled(getView().getMinimumSize());
    }

    private Dimension scaled(Dimension size) {
        return new Dimension((int) Math.ceil(size.width * zoom),
                (int) Math.ceil(size.height * zoom));
    }

    private void redispatch(MouseEvent e) {
        Point layerPoint = SwingUtilities.convertPoint(eventGlass, e.getPoint(), this);
        Point modelPoint = new Point((int) Math.floor(layerPoint.x / zoom),
                (int) Math.floor(layerPoint.y / zoom));
        Component target = dragTarget;
        if (target == null && e.getID() != MouseEvent.MOUSE_EXITED) {
            Component deepest = SwingUtilities.getDeepestComponentAt(getView(), modelPoint.x, modelPoint.y);
            target = findListenerTarget(deepest, e.getID());
        }
        if (target == null) {
            target = getView();
        }

        if (dragTarget != null && e.getID() == MouseEvent.MOUSE_DRAGGED) {
            Component deepest = SwingUtilities.getDeepestComponentAt(getView(), modelPoint.x, modelPoint.y);
            Component hovered = findListenerTarget(deepest, MouseEvent.MOUSE_MOVED);
            updateHoverTarget(hovered, e);
        } else if (dragTarget == null && (e.getID() == MouseEvent.MOUSE_MOVED
                || e.getID() == MouseEvent.MOUSE_ENTERED)) {
            updateHoverTarget(target, e);
        }
        if (e.getID() == MouseEvent.MOUSE_PRESSED) {
            dragTarget = target;
            if (target.isFocusable()) {
                target.requestFocusInWindow();
            }
        }
        dispatchToTarget(target, modelPoint, e);
        eventGlass.setCursor(target.getCursor());
        if (e.getID() == MouseEvent.MOUSE_RELEASED) {
            dragTarget = null;
        } else if (e.getID() == MouseEvent.MOUSE_EXITED && dragTarget == null) {
            updateHoverTarget(getView(), e);
            hoverTarget = null;
        }
        e.consume();
    }

    private Component findListenerTarget(Component target, int eventId) {
        boolean motionEvent = eventId == MouseEvent.MOUSE_DRAGGED
                || eventId == MouseEvent.MOUSE_MOVED;
        while (target != null) {
            if (target.getMouseListeners().length > 0
                    || motionEvent && target.getMouseMotionListeners().length > 0) {
                return target;
            }
            if (target == getView()) {
                break;
            }
            target = target.getParent();
        }
        return getView();
    }

    private void updateHoverTarget(Component target, MouseEvent e) {
        if (target == hoverTarget) {
            return;
        }
        if (hoverTarget != null) {
            dispatchToTarget(hoverTarget, toModelPoint(e), e.getID() == MouseEvent.MOUSE_EXITED,
                    MouseEvent.MOUSE_EXITED);
        }
        hoverTarget = target;
        if (hoverTarget != null) {
            dispatchToTarget(hoverTarget, toModelPoint(e), e.getID() == MouseEvent.MOUSE_EXITED,
                    MouseEvent.MOUSE_ENTERED);
        }
    }

    private Point toModelPoint(MouseEvent e) {
        Point layerPoint = SwingUtilities.convertPoint(eventGlass, e.getPoint(), this);
        return new Point((int) Math.floor(layerPoint.x / zoom),
                (int) Math.floor(layerPoint.y / zoom));
    }

    private void dispatchToTarget(Component target, Point modelPoint, MouseEvent original) {
        dispatchToTarget(target, modelPoint, false, original.getID(), original);
    }

    private void dispatchToTarget(Component target, Point modelPoint, boolean exited, int id) {
        dispatchToTarget(target, modelPoint, exited, id, null);
    }

    private void dispatchToTarget(Component target, Point modelPoint, boolean exited,
            int id, MouseEvent sourceEvent) {
        Point targetPoint = exited ? new Point(-1, -1)
                : SwingUtilities.convertPoint(getView(), modelPoint, target);
        long when = sourceEvent == null ? System.currentTimeMillis() : sourceEvent.getWhen();
        int modifiers = sourceEvent == null ? 0 : sourceEvent.getModifiersEx();
        int screenX = sourceEvent == null ? 0 : sourceEvent.getXOnScreen();
        int screenY = sourceEvent == null ? 0 : sourceEvent.getYOnScreen();
        int clickCount = sourceEvent == null ? 0 : sourceEvent.getClickCount();
        boolean popupTrigger = sourceEvent != null && sourceEvent.isPopupTrigger();
        int button = sourceEvent == null ? MouseEvent.NOBUTTON : sourceEvent.getButton();
        MouseEvent transformed = new MouseEvent(target, id, when, modifiers,
                targetPoint.x, targetPoint.y, screenX, screenY, clickCount, popupTrigger, button);
        target.dispatchEvent(transformed);
    }

    private void forwardWheelEvent(MouseWheelEvent e) {
        JScrollPane scrollPane = getView().getScrollPane();
        if (scrollPane == null) {
            return;
        }
        Point point = SwingUtilities.convertPoint((Component) e.getSource(), e.getPoint(), scrollPane);
        MouseWheelEvent forwarded = new MouseWheelEvent(scrollPane, e.getID(), e.getWhen(),
                e.getModifiersEx(), point.x, point.y, e.getXOnScreen(), e.getYOnScreen(),
                e.getClickCount(), e.isPopupTrigger(), e.getScrollType(), e.getScrollAmount(),
                e.getWheelRotation());
        scrollPane.dispatchEvent(forwarded);
        e.consume();
    }

    private final class ZoomLayerUI extends LayerUI<Diagram> {

        @Override
        public void paint(Graphics graphics, JComponent component) {
            Graphics2D scaledGraphics = (Graphics2D) graphics.create();
            double scale = getZoom();
            scaledGraphics.scale(scale, scale);
            try {
                super.paint(scaledGraphics, component);
            } finally {
                scaledGraphics.dispose();
            }
        }

        @Override
        public void doLayout(JLayer<? extends Diagram> component) {
            component.getView().setBounds(0, 0,
                    (int) Math.ceil(component.getWidth() / zoom),
                    (int) Math.ceil(component.getHeight() / zoom));
            component.getGlassPane().setBounds(0, 0, component.getWidth(), component.getHeight());
        }
    }
}
