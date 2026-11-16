package com.jme3.gde.core.util;

import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import javax.swing.event.*;

/**
 * EDT-only single-row toolbar with grouped overflow. Original controls are moved
 * into a vertical popup, preserving selection, listeners and tooltips. Closing the
 * popup restores ownership; resizing puts complete groups back when they fit.
 * <pre>
 * // 第一步：将名称与控件作为同组传入，防止名称与下拉框分离。
 * OverflowToolbar.install(toolbar, new Component[]{label, combo}, new Component[]{button});
 * // 第二步：缩窄窗口后点击右侧竖直省略号，使用纵向弹出的控件。
 * // 第三步：关闭菜单或扩大窗口，原控件自动回到工具栏；无需重新绑定事件。
 * </pre>
 */
public final class OverflowToolbar implements LayoutManager {
    private final JToolBar bar;
    private final List<JPanel> groups = new ArrayList<>();
    private final List<JPanel> hidden = new ArrayList<>();
    private final JButton more = new JButton("\u22ef");
    private final JPopupMenu popup = new JPopupMenu();

    public static void install(JToolBar bar, Component[]... groups) {
        new OverflowToolbar(bar, groups);
    }

    private OverflowToolbar(JToolBar bar, Component[][] controls) {
        this.bar = bar;
        bar.removeAll();
        for (Component[] row : controls) {
            JPanel group = new JPanel();
            group.setOpaque(false);
            group.setLayout(new BoxLayout(group, BoxLayout.X_AXIS));
            for (Component component : row) {
                group.add(component);
            }
            for (Component child : group.getComponents()) {
                if (child instanceof AbstractButton button && button.getToolTipText() == null) {
                    String label = button.getText();
                    if (label != null && !label.isBlank()) {
                        button.setToolTipText(label);
                    }
                }
            }
            groups.add(group);
            bar.add(group);
        }
        more.setFocusable(false);
        more.setMargin(new Insets(0, 5, 0, 5));
        more.setToolTipText(EditorMessages.text("MoreTools"));
        more.addActionListener(event -> open());
        bar.add(more);
        bar.setLayout(this);
        popup.addPopupMenuListener(new PopupMenuListener() {
            public void popupMenuWillBecomeVisible(PopupMenuEvent event) {
            }
            public void popupMenuWillBecomeInvisible(PopupMenuEvent event) {
                SwingUtilities.invokeLater(() -> restore());
            }
            public void popupMenuCanceled(PopupMenuEvent event) {
                SwingUtilities.invokeLater(() -> restore());
            }
        });
        bar.addHierarchyListener(event -> {
            if (!bar.isShowing()) {
                popup.setVisible(false);
            }
        });
    }

    private void open() {
        if (hidden.isEmpty()) {
            return;
        }
        JPanel column = new JPanel();
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        for (JPanel group : hidden) {
            group.setVisible(true);
            group.setAlignmentX(Component.LEFT_ALIGNMENT);
            Dimension size = group.getPreferredSize();
            group.setMaximumSize(new Dimension(Integer.MAX_VALUE, Math.max(28, size.height)));
            column.add(group);
            column.add(Box.createVerticalStrut(5));
        }
        JScrollPane scroll = new JScrollPane(column);
        scroll.setBorder(null);
        Dimension size = column.getPreferredSize();
        Rectangle screen = bar.getGraphicsConfiguration().getBounds();
        scroll.setPreferredSize(new Dimension(Math.min(size.width + 24, screen.width - 40),
                Math.min(size.height + 8, Math.max(120, screen.height / 2))));
        popup.removeAll();
        int popupWidth;
        if (size.height + 8 <= screen.height / 2 && size.width + 12 <= screen.width - 40) {
            // Normal overflow looks like the existing compact theme/options menu.
            popup.add(column);
            popupWidth = size.width + 12;
        } else {
            popup.add(scroll);
            popupWidth = scroll.getPreferredSize().width;
        }
        popup.show(more, Math.min(0, more.getWidth() - popupWidth), more.getHeight());
    }

    private void restore() {
        if (popup.isVisible()) {
            return;
        }
        for (JPanel group : groups) {
            if (group.getParent() != bar) {
                bar.add(group);
            }
        }
        popup.removeAll();
        bar.revalidate();
        bar.repaint();
    }

    @Override
    public void layoutContainer(Container parent) {
        Insets insets = bar.getInsets();
        int width = Math.max(0, bar.getWidth() - insets.left - insets.right);
        int height = Math.max(0, bar.getHeight() - insets.top - insets.bottom);
        int buttonWidth = 28;
        if (popup.isVisible()) {
            more.setBounds(insets.left + Math.max(0, width - buttonWidth), insets.top, buttonWidth, height);
            return;
        }
        int total = 0;
        for (JPanel group : groups) {
            total += group.getPreferredSize().width + 5;
        }
        int available = total > width ? Math.max(0, width - buttonWidth - 5) : width;
        hidden.clear();
        int x = 0;
        boolean overflow = false;
        for (JPanel group : groups) {
            int w = group.getPreferredSize().width;
            overflow |= x + w > available;
            group.setVisible(!overflow);
            if (overflow) {
                hidden.add(group);
            } else {
                group.setBounds(insets.left + x, insets.top, w, height);
                x += w + 5;
            }
        }
        more.setVisible(!hidden.isEmpty());
        more.setBounds(insets.left + Math.max(0, width - buttonWidth), insets.top, buttonWidth, height);
    }

    @Override
    public Dimension preferredLayoutSize(Container parent) {
        Insets insets = bar.getInsets();
        return new Dimension(600, 28 + insets.top + insets.bottom);
    }
    @Override
    public Dimension minimumLayoutSize(Container parent) {
        return new Dimension(28, preferredLayoutSize(parent).height);
    }
    @Override
    public void addLayoutComponent(String name, Component component) {
    }
    @Override
    public void removeLayoutComponent(Component component) {
    }
}
