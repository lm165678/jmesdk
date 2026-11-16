package com.jme3.gde.core.scene;

import com.jme3.input.RawInputListener;
import com.jme3.input.awt.AwtMouseInput;
import com.jme3.input.event.InputEvent;
import com.jme3.input.event.MouseButtonEvent;
import com.jme3.input.event.MouseMotionEvent;
import com.jme3.system.awt.AwtPanel;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Embedded preview input. AWT owns cursor state; only immutable input events
 * cross to the render thread. Hidden pointers are recentered near the panel
 * edge, not on every movement. Robot echoes never become camera rotation.
 * Missing/coalesced warp acknowledgements rebase after 200ms instead of adding
 * a large inverse delta. Without Robot permission, visible-range motion still
 * works but continuous edge capture is unavailable. Window deactivation releases
 * capture and resets held navigation; reactivation rebases the first mouse event.
 * Press right mouse again to resume flight after switching applications.
 * No scene data is changed.
 */
final class PreviewMouseInput extends AwtMouseInput {
    private final ConcurrentLinkedQueue<InputEvent> events = new ConcurrentLinkedQueue<>();
    private RawInputListener sink;
    // The following state belongs exclusively to the AWT event thread.
    private Component source;
    private Window ownerWindow;
    private final java.awt.event.WindowFocusListener windowFocus = new java.awt.event.WindowAdapter() {
        @Override
        public void windowLostFocus(java.awt.event.WindowEvent event) {
            if (source != null && captured) {
                source.setCursor(previousCursor);
            }
            captured = false;
            positioned = false;
            warpPending = false;
            lateWarpPossible = false;
            events.clear();
            SceneApplication.getApplication().enqueue(() -> {
                var controller = SceneApplication.getApplication().getActiveCameraController();
                if (controller != null) {
                    controller.resetInputState();
                }
                return null;
            });
        }

        @Override
        public void windowGainedFocus(java.awt.event.WindowEvent event) {
            // Discard coordinates recorded in another application/window position.
            positioned = false;
            warpPending = false;
            lateWarpPossible = false;
        }
    };

    private void trackWindow() {
        Window next = source == null ? null : javax.swing.SwingUtilities.getWindowAncestor(source);
        if (next != ownerWindow) {
            if (ownerWindow != null) {
                ownerWindow.removeWindowFocusListener(windowFocus);
            }
            ownerWindow = next;
            if (ownerWindow != null) {
                ownerWindow.addWindowFocusListener(windowFocus);
            }
        }
    }
    private Robot pointer;
    private Cursor hiddenCursor;
    private Cursor previousCursor;
    private boolean captured;
    private boolean positioned;
    private boolean warpPending;
    private int x;
    private int y;
    private int centerX;
    private int centerY;
    private int wheel;
    private long warpTime;
    private boolean lateWarpPossible;

    @Override
    public void setInputSource(Component component) {
        EventQueue.invokeLater(() -> {
            if (source != null && captured) {
                source.setCursor(previousCursor);
            }
            super.setInputSource(component);
            source = component;
            trackWindow();
            captured = false;
            positioned = false;
            warpPending = false;
            lateWarpPossible = false;
            events.clear();
            try {
                GraphicsConfiguration configuration = component.getGraphicsConfiguration();
                pointer = configuration == null ? new Robot() : new Robot(configuration.getDevice());
            } catch (AWTException | SecurityException error) {
                pointer = null;
                java.util.logging.Logger.getLogger(PreviewMouseInput.class.getName())
                        .log(java.util.logging.Level.WARNING, "Continuous preview mouse capture unavailable", error);
            }
        });
    }

    @Override
    public void setInputListener(RawInputListener listener) {
        sink = listener;
    }

    @Override
    public void setCursorVisible(boolean visible) {
        // Do not call the inherited grab implementation: it warps every event.
        EventQueue.invokeLater(() -> {
            if (source == null || captured == !visible) {
                return;
            }
            captured = !visible;
            if (captured) {
                previousCursor = source.getCursor();
                if (hiddenCursor == null) {
                    hiddenCursor = Toolkit.getDefaultToolkit().createCustomCursor(
                            new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), new Point(), "preview capture");
                }
                source.setCursor(hiddenCursor);
            } else {
                source.setCursor(previousCursor);
            }
            // A queued Robot echo may still arrive after release; retain its filter.
        });
    }

    @Override
    public void update() {
        if (sink == null) {
            return;
        }
        // Bound each frame's batch so a busy event thread cannot starve rendering.
        int count = events.size();
        for (int i = 0; i < count; i++) {
            InputEvent event = events.poll();
            if (event instanceof MouseMotionEvent motion) {
                sink.onMouseMotionEvent(motion);
            } else if (event instanceof MouseButtonEvent button) {
                sink.onMouseButtonEvent(button);
            }
        }
    }

    private int pixelX(int value) {
        return (int) Math.round(value * (source instanceof AwtPanel panel ? panel.getFramebufferScaleX() : 1));
    }

    private int pixelY(int value) {
        return (int) Math.round(value * (source instanceof AwtPanel panel ? panel.getFramebufferScaleY() : 1));
    }

    private void motion(int nextX, int nextY, int dx, int dy, int wheelDelta) {
        MouseMotionEvent event = new MouseMotionEvent(pixelX(nextX), pixelY(source.getHeight() - nextY),
                pixelX(nextX) - pixelX(nextX - dx), pixelY(nextY - dy) - pixelY(nextY), wheel, wheelDelta);
        event.setTime(System.nanoTime());
        events.add(event);
    }

    @Override
    public void mouseMoved(MouseEvent event) {
        int nextX = event.getX();
        int nextY = event.getY();
        if (!positioned) {
            x = nextX;
            y = nextY;
            positioned = true;
        }
        if (lateWarpPossible && Math.abs(nextX - centerX) <= 1 && Math.abs(nextY - centerY) <= 1) {
            x = nextX;
            y = nextY;
            lateWarpPossible = false;
            return;
        }
        if (warpPending) {
            int radius = Math.max(2, Math.min(source.getWidth(), source.getHeight()) / 4);
            if (Math.abs(nextX - centerX) < radius && Math.abs(nextY - centerY) < radius) {
                // Accept both an exact Robot echo and a coalesced post-warp move.
                // A coalesced Robot acknowledgement has no reliable physical delta.
                // Rebase at the observed position instead of turning it into a jump.
                x = nextX;
                y = nextY;
                warpPending = false;
                return;
            } else if (event.getWhen() - warpTime > 200) {
                x = nextX;
                y = nextY;
                warpPending = false;
                lateWarpPossible = true;
            }
        }
        int dx = nextX - x;
        int dy = nextY - y;
        x = nextX;
        y = nextY;
        if (dx != 0 || dy != 0 || !captured) {
            motion(x, y, dx, dy, 0);
        }
        if (captured && !warpPending && pointer != null && source.isShowing()
                && source.getWidth() > 64 && source.getHeight() > 64
                && (x < 12 || y < 12 || x >= source.getWidth() - 12 || y >= source.getHeight() - 12)) {
            centerX = source.getWidth() / 2;
            centerY = source.getHeight() / 2;
            Point screen = source.getLocationOnScreen();
            warpPending = true;
            lateWarpPossible = false;
            warpTime = System.currentTimeMillis();
            pointer.mouseMove(screen.x + centerX, screen.y + centerY);
        }
    }

    @Override
    public void mouseDragged(MouseEvent event) {
        mouseMoved(event);
    }

    @Override
    public void mousePressed(MouseEvent event) {
        button(event, true);
    }

    @Override
    public void mouseReleased(MouseEvent event) {
        button(event, false);
    }

    private void button(MouseEvent event, boolean pressed) {
        trackWindow();
        x = event.getX();
        y = event.getY();
        positioned = true;
        int index = switch (event.getButton()) {
            case MouseEvent.BUTTON2 -> 2;
            case MouseEvent.BUTTON3 -> 1;
            default -> 0;
        };
        MouseButtonEvent translated = new MouseButtonEvent(index, pressed,
                pixelX(x), pixelY(source.getHeight() - y));
        translated.setTime(System.nanoTime());
        events.add(translated);
    }

    @Override
    public void mouseWheelMoved(MouseWheelEvent event) {
        int delta = event.getUnitsToScroll() * WHEEL_AMP;
        wheel += delta;
        motion(event.getX(), event.getY(), 0, 0, -delta);
    }

    @Override
    public void destroy() {
        EventQueue.invokeLater(() -> {
            if (ownerWindow != null) {
                ownerWindow.removeWindowFocusListener(windowFocus);
                ownerWindow = null;
            }
            if (source != null) {
                if (captured) {
                    source.setCursor(previousCursor);
                }
                source.removeMouseListener(this);
                source.removeMouseMotionListener(this);
                source.removeMouseWheelListener(this);
            }
            captured = false;
            warpPending = false;
            events.clear();
        });
    }
}
