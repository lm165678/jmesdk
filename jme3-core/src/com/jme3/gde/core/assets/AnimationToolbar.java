package com.jme3.gde.core.assets;

import java.awt.Component;
import java.awt.Dimension;
import java.util.ArrayList;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;

/**
 * Single-row toolbar with trailing actions available through an overflow menu.
 * 第一步：按显示顺序添加控件；第二步：缩窄窗口后点击省略号访问收起的按钮。
 * Menu items invoke the original buttons, retaining enabled state and listeners.
 * Create, lay out and use on EDT.
 */
final class AnimationToolbar extends JPanel {
    private static final int GAP = 5;
    private final JButton more = new JButton("…");
    private final ArrayList<AbstractButton> hidden = new ArrayList<>();

    AnimationToolbar() {
        super(null);
        more.setToolTipText(AnimationEditorMessages.text("Animation.More_actions"));
        more.addActionListener(event -> {
            JPopupMenu menu = overflowMenu();
            menu.show(more, 0, more.getHeight());
        });
        add(more);
    }

    JPopupMenu overflowMenu() {
        JPopupMenu menu = new JPopupMenu();
        for (AbstractButton button : hidden) {
            JMenuItem item = new JMenuItem(button.getText(), button.getIcon());
            item.setEnabled(button.isEnabled());
            item.setToolTipText(button.getToolTipText());
            item.addActionListener(event -> button.doClick());
            menu.add(item);
        }
        return menu;
    }

    @Override
    public Dimension getPreferredSize() {
        int width = GAP;
        int height = more.getPreferredSize().height;
        for (Component component : getComponents()) {
            if (component != more) {
                Dimension size = component.getPreferredSize();
                width += size.width + GAP;
                height = Math.max(height, size.height);
            }
        }
        return new Dimension(width, height + 2 * GAP);
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(more.getPreferredSize().width + 2 * GAP, getPreferredSize().height);
    }

    @Override
    public void doLayout() {
        int total = getPreferredSize().width;
        boolean overflow = total > getWidth();
        int moreWidth = more.getPreferredSize().width;
        int limit = getWidth() - GAP - (overflow ? moreWidth + GAP : 0);
        int x = GAP;
        boolean folded = false;
        hidden.clear();
        for (Component component : getComponents()) {
            if (component == more) {
                continue;
            }
            Dimension size = component.getPreferredSize();
            if (component instanceof AbstractButton button && (folded || x + size.width > limit)) {
                folded = true;
                hidden.add(button);
                component.setVisible(false);
            } else {
                component.setVisible(true);
                int width = Math.min(size.width, Math.max(0, limit - x));
                component.setBounds(x, (getHeight() - size.height) / 2, width, size.height);
                x += size.width + GAP;
            }
        }
        more.setVisible(!hidden.isEmpty());
        more.setBounds(Math.max(0, getWidth() - GAP - moreWidth), GAP, moreWidth,
                Math.max(0, getHeight() - 2 * GAP));
    }
}
