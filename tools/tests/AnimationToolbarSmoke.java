package com.jme3.gde.core.assets;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;

/** Resize the actual toolbar and invoke its overflow actions on EDT. */
public final class AnimationToolbarSmoke {
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AnimationToolbar toolbar = new AnimationToolbar();
            toolbar.add(new JLabel("Composer and entry"));
            JButton links = new JButton("Animation Links");
            JButton root = new JButton("Root Motion");
            JButton undo = new JButton("Undo");
            undo.setEnabled(false);
            int[] calls = {0};
            root.addActionListener(event -> calls[0]++);
            toolbar.add(links);
            toolbar.add(root);
            toolbar.add(undo);
            toolbar.setSize(toolbar.getPreferredSize());
            toolbar.doLayout();
            if (!root.isVisible() || toolbar.overflowMenu().getComponentCount() != 0) {
                throw new AssertionError("Wide toolbar must display all actions");
            }
            for (int width : new int[]{240, 90, 400, 180}) {
                toolbar.setSize(width, toolbar.getPreferredSize().height);
                toolbar.doLayout();
                var menu = toolbar.overflowMenu();
                if (!root.isVisible()) {
                    boolean found = false;
                    for (var component : menu.getComponents()) {
                        JMenuItem item = (JMenuItem) component;
                        if (item.getText().equals("Root Motion")) {
                            item.doClick();
                            found = true;
                        }
                        if (item.getText().equals("Undo") && item.isEnabled()) {
                            throw new AssertionError("Disabled actions must stay disabled in overflow");
                        }
                    }
                    if (!found) {
                        throw new AssertionError("Root Motion must remain accessible when clipped");
                    }
                }
                for (var component : toolbar.getComponents()) {
                    if (component instanceof JButton button && button.getText().equals("…")
                            && button.isVisible() && button.getX() + button.getWidth() > width) {
                        throw new AssertionError("Overflow button must fit within the toolbar");
                    }
                }
            }
            if (calls[0] == 0) {
                throw new AssertionError("Overflow menu must invoke the original action");
            }
            toolbar.setSize(toolbar.getPreferredSize());
            toolbar.doLayout();
            if (!root.isVisible() || toolbar.overflowMenu().getComponentCount() != 0) {
                throw new AssertionError("Expanding must restore hidden actions");
            }
            System.out.println("ANIMATION_TOOLBAR_OVERFLOW_PASS");
        });
    }
}
