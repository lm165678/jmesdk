package com.jme3.gde.core.util;

import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import javax.swing.*;
import org.openide.util.NbPreferences;

/**
 * Compact two-column inspector, used on the Swing EDT. A draggable divider
 * allocates label/value widths independently of text length. Labels retain their
 * full tooltip and Swing clips their text with ellipses. Width always follows the
 * viewport; only vertical scrolling is needed. Divider proportions persist per key.
 * <pre>
 * // 第一步：依次加入名称与控件，每两个组件组成一行。
 * JPanel rows = new JPanel();
 * rows.add(new JLabel("Field of View"));
 * rows.add(new JSpinner());
 * // 第二步：安装两列布局，拖动中间分隔线调整比例。
 * InspectorColumns.install(rows, "camera");
 * // 第三步：缩窄面板时文字截断，悬停显示全文；不改变参数或场景数据。
 * </pre>
 */
public final class InspectorColumns extends JPanel implements Scrollable {
    public InspectorColumns(String key) {
        install(this, key);
    }

    /** Installs on an existing panel while preserving controls and their listeners. */
    public static void install(JPanel panel, String key) {
        for (Component component : panel.getComponents()) {
            if (component instanceof JLabel label) {
                label.setToolTipText(label.getText());
                label.addPropertyChangeListener("text", event -> label.setToolTipText(label.getText()));
            }
            component.setMinimumSize(new Dimension(0, 24));
        }
        DividerLayout layout = new DividerLayout(panel, key);
        panel.setLayout(layout);
        panel.add(layout.divider);
    }

    /**
     * Root container for inspectors embedded in a vertical-only JScrollPane.
     * Standalone dialogs retain the requested preferred width, while a viewport
     * always controls the actual width. Long labels, values and diagnostics only
     * affect height; they cannot request horizontal growth. The preferred
     * scrollable width is zero so embedding does not widen a Properties dock.
     * <pre>
     * // 第一步：用固定对话框宽度创建根面板，字段可放入 InspectorColumns。
     * JPanel root = new InspectorColumns.WidthTrackedPanel(new BorderLayout(), 360);
     * // 第二步：嵌入属性页时使用垂直滚动，禁止水平滚动。
     * JScrollPane scroll = new JScrollPane(root,
     *         JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
     *         JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
     * // 第三步：缩窄或出现垂直滚动条后，根面板自动使用新的 viewport 宽度。
     * // 独立 JDialog 不使用 JScrollPane，pack() 仍采用创建时的 360 像素宽度。
     * </pre>
     */
    public static final class WidthTrackedPanel extends JPanel implements Scrollable {
        private final int preferredWidth;

        public WidthTrackedPanel(LayoutManager layout, int preferredWidth) {
            super(layout);
            this.preferredWidth = preferredWidth;
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            size.width = preferredWidth;
            return size;
        }

        @Override
        public Dimension getMinimumSize() {
            return new Dimension(0, 0);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return new Dimension(0, getPreferredSize().height);
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return 30;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(30, visible.height - 30);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private static final class DividerLayout implements LayoutManager {
        private final JPanel panel;
        private final String key;
        private double ratio;
        private final JPanel divider = new JPanel() {
            @Override
            protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                Color color = UIManager.getColor("Separator.foreground");
                graphics.setColor(color != null ? color : Color.GRAY);
                graphics.drawLine(getWidth() / 2, 2, getWidth() / 2, Math.max(2, getHeight() - 2));
            }
        };

        DividerLayout(JPanel panel, String key) {
            this.panel = panel;
            this.key = "inspector.columns." + key;
            ratio = NbPreferences.forModule(InspectorColumns.class).getDouble(this.key, .43);
            divider.setOpaque(false);
            divider.setCursor(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR));
            divider.setToolTipText(EditorMessages.text("InspectorResizeColumns"));
            divider.addMouseMotionListener(new MouseMotionAdapter() {
                @Override
                public void mouseDragged(MouseEvent event) {
                    Point point = SwingUtilities.convertPoint(divider, event.getPoint(), panel);
                    Insets insets = panel.getInsets();
                    int width = panel.getWidth() - insets.left - insets.right;
                    ratio = Math.max(.15, Math.min(.75, (point.x - insets.left) / (double) Math.max(1, width)));
                    panel.revalidate();
                    panel.repaint();
                }
            });
            divider.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseReleased(MouseEvent event) {
                    NbPreferences.forModule(InspectorColumns.class).putDouble(DividerLayout.this.key, ratio);
                }
            });
        }

        private java.util.List<Component> cells() {
            java.util.List<Component> cells = new ArrayList<>();
            for (Component c : panel.getComponents()) {
                if (c != divider) {
                    cells.add(c);
                }
            }
            return cells;
        }

        @Override
        public void layoutContainer(Container parent) {
            Insets insets = parent.getInsets();
            int width = Math.max(0, parent.getWidth() - insets.left - insets.right);
            int left = Math.max(0, Math.min((int) (width * ratio), width - Math.min(90, width / 2) - 6));
            int y = insets.top;
            java.util.List<Component> cells = cells();
            for (int i = 0; i < cells.size(); i += 2) {
                Component label = cells.get(i);
                Component value = i + 1 < cells.size() ? cells.get(i + 1) : null;
                int height = Math.max(26, Math.max(label.getPreferredSize().height,
                        value == null ? 0 : value.getPreferredSize().height));
                label.setBounds(insets.left, y, left, height);
                if (value != null) {
                    value.setBounds(insets.left + left + 6, y, Math.max(0, width - left - 6), height);
                }
                y += height + 4;
            }
            divider.setBounds(insets.left + left, insets.top, 6, Math.max(0, y - insets.top));
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            Insets insets = parent.getInsets();
            java.util.List<Component> cells = cells();
            int height = insets.top + insets.bottom;
            for (int i = 0; i < cells.size(); i += 2) {
                height += Math.max(26, Math.max(cells.get(i).getPreferredSize().height,
                        i + 1 < cells.size() ? cells.get(i + 1).getPreferredSize().height : 0)) + 4;
            }
            return new Dimension(300, height);
        }
        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return new Dimension(0, preferredLayoutSize(parent).height);
        }
        @Override
        public void addLayoutComponent(String name, Component component) {
        }
        @Override
        public void removeLayoutComponent(Component component) {
        }
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }
    @Override
    public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
        return 30;
    }
    @Override
    public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
        return Math.max(30, visible.height - 30);
    }
    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }
    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }
}
