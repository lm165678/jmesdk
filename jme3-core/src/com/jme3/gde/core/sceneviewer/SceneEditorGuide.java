package com.jme3.gde.core.sceneviewer;

import com.jme3.gde.core.util.EditorMessages;
import java.awt.*;
import java.awt.event.*;
import java.text.MessageFormat;
import javax.swing.*;

/**
 * Four-step, Swing-EDT scene guide. An owned transparent window is used because
 * a Swing glass pane cannot reliably cover the heavyweight OpenGL/AWT canvas.
 * The guide consumes input without changing the scene. Unsupported translucent
 * window systems fall back to the same instructions in a compact opaque card.
 * Closing the scene/owner removes all listeners and the overlay.
 */
final class SceneEditorGuide {
    private static final String[] STEPS = {"Orbit", "Fly", "Vertical", "Tools"};
    private final Window owner;
    private final Component viewport;
    private final JWindow window;
    private final JPanel card = new JPanel(new BorderLayout(0, 10));
    private final JLabel heading = new JLabel();
    private final JLabel explanation = new JLabel();
    private final JLabel count = new JLabel();
    private final JButton back = new JButton(text("GuideBack"));
    private final JButton next = new JButton(text("GuideNext"));
    private final Runnable onClose;
    private final boolean translucent;
    private Rectangle highlight = new Rectangle();
    private int step;
    private boolean closed;
    private final ComponentAdapter positionListener = new ComponentAdapter() {
        @Override
        public void componentMoved(ComponentEvent event) {
            position();
        }
        @Override
        public void componentResized(ComponentEvent event) {
            position();
        }
        @Override
        public void componentHidden(ComponentEvent event) {
            close();
        }
    };
    private final WindowAdapter ownerListener = new WindowAdapter() {
        @Override
        public void windowClosed(WindowEvent event) {
            close();
        }
        @Override
        public void windowIconified(WindowEvent event) {
            close();
        }
    };

    SceneEditorGuide(Window owner, Component viewport, Runnable onClose) {
        this.owner = owner;
        this.viewport = viewport;
        this.onClose = onClose;
        translucent = owner.getGraphicsConfiguration().getDevice().isWindowTranslucencySupported(
                GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT);
        window = new JWindow(owner);
        window.setFocusableWindowState(true);
        if (translucent) {
            window.setBackground(new Color(0, 0, 0, 0));
        }
        JPanel shade = new JPanel(null) {
            @Override
            protected void paintComponent(Graphics graphics) {
                if (!translucent) {
                    super.paintComponent(graphics);
                    return;
                }
                Graphics2D g = (Graphics2D) graphics.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setComposite(AlphaComposite.Src);
                g.setColor(new Color(0, 0, 0, 170));
                g.fillRect(0, 0, getWidth(), getHeight());
                // Alpha 1 keeps the spotlight visually clear but consumes clicks.
                g.setColor(new Color(0, 0, 0, 1));
                g.fillRoundRect(highlight.x, highlight.y, highlight.width, highlight.height, 10, 10);
                g.setComposite(AlphaComposite.SrcOver);
                g.setColor(new Color(100, 180, 255));
                g.setStroke(new BasicStroke(2));
                g.drawRoundRect(highlight.x, highlight.y, highlight.width, highlight.height, 10, 10);
                int tipX = highlight.x + highlight.width / 2;
                int tipY = step == 3 ? highlight.y + highlight.height + 3
                        : highlight.y + highlight.height / 2;
                int startX = card.getX() + card.getWidth() / 2;
                int startY = card.getY();
                g.drawLine(startX, startY, tipX, tipY);
                double angle = Math.atan2(startY - tipY, startX - tipX);
                Polygon arrow = new Polygon();
                arrow.addPoint(tipX, tipY);
                arrow.addPoint(tipX + (int) (12 * Math.cos(angle + .45)),
                        tipY + (int) (12 * Math.sin(angle + .45)));
                arrow.addPoint(tipX + (int) (12 * Math.cos(angle - .45)),
                        tipY + (int) (12 * Math.sin(angle - .45)));
                g.fillPolygon(arrow);
                g.dispose();
            }
        };
        shade.setOpaque(!translucent);
        shade.addMouseListener(new MouseAdapter() { });
        shade.addMouseMotionListener(new MouseMotionAdapter() { });
        window.setContentPane(shade);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(90, 140, 190), 1, true),
                BorderFactory.createEmptyBorder(12, 14, 12, 14)));
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 15f));
        card.add(heading, BorderLayout.NORTH);
        card.add(explanation, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(8, 0));
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        JButton skip = new JButton(text("GuideSkip"));
        skip.addActionListener(event -> close());
        back.addActionListener(event -> {
            step--;
            showStep();
        });
        next.addActionListener(event -> {
            if (++step == STEPS.length) {
                close();
            } else {
                showStep();
            }
        });
        for (JButton button : new JButton[]{skip, back, next}) {
            button.setMargin(new Insets(3, 7, 3, 7));
            actions.add(button);
        }
        footer.add(count, BorderLayout.WEST);
        footer.add(actions, BorderLayout.EAST);
        card.add(footer, BorderLayout.SOUTH);
        shade.add(card);
        shade.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "skip");
        shade.getActionMap().put("skip", new AbstractAction() {
            public void actionPerformed(ActionEvent event) {
                close();
            }
        });
        window.getRootPane().setDefaultButton(next);
        owner.addComponentListener(positionListener);
        viewport.addComponentListener(positionListener);
        owner.addWindowListener(ownerListener);
        showStep();
    }

    private static String text(String key) {
        return EditorMessages.text(key);
    }

    void show() {
        window.setVisible(true);
        next.requestFocusInWindow();
    }

    private void showStep() {
        heading.setText(text("Guide" + STEPS[step] + "Title"));
        explanation.setText("<html><body style='width:290px'>"
                + text("Guide" + STEPS[step] + "Text") + "</body></html>");
        count.setText(MessageFormat.format(text("GuideStep"), step + 1, STEPS.length));
        back.setEnabled(step > 0);
        next.setText(text(step == STEPS.length - 1 ? "GuideDone" : "GuideNext"));
        position();
    }

    private void position() {
        if (closed || !viewport.isShowing()) {
            return;
        }
        if (!translucent) {
            window.setSize(370, 235);
            window.setLocationRelativeTo(owner);
            card.setBounds(0, 0, window.getWidth(), window.getHeight());
            return;
        }
        window.setBounds(owner.getBounds());
        Point origin = viewport.getLocationOnScreen();
        origin.translate(-window.getX(), -window.getY());
        highlight = step == 3 ? new Rectangle(origin.x + 7, origin.y + 7, 142, 38)
                : new Rectangle(origin.x + 3, origin.y + 3,
                        Math.max(1, viewport.getWidth() - 6), Math.max(1, viewport.getHeight() - 6));
        int width = Math.min(370, Math.max(1, window.getWidth() - 24));
        int height = Math.min(235, Math.max(1, window.getHeight() - 24));
        int x = Math.max(12, Math.min(origin.x + 16, window.getWidth() - width - 12));
        int y = Math.max(12, Math.min(origin.y + viewport.getHeight() - height - 16,
                window.getHeight() - height - 12));
        card.setBounds(x, y, width, height);
        window.repaint();
    }

    void close() {
        if (closed) {
            return;
        }
        closed = true;
        owner.removeComponentListener(positionListener);
        viewport.removeComponentListener(positionListener);
        owner.removeWindowListener(ownerListener);
        window.dispose();
        onClose.run();
    }
}
