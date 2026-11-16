package com.jme3.gde.core.renderdoc;

import java.awt.Component;
import javax.swing.*;
import com.jme3.gde.core.util.OverflowToolbar;

/** Verifies capture survives the same toolbar regrouping used by 3D Viewer. */
public final class RenderDocToolbarSmoke {
    public static void main(String[] args) throws Exception {
        System.setProperty("java.util.prefs.PreferencesFactory",
                RenderDocSmoke.TestPreferencesFactory.class.getName());
        SwingUtilities.invokeAndWait(() -> {
            JToolBar bar = new JToolBar();
            JButton capture = RenderDocSupport.installButton(bar);
            if (capture.isVisible()) {
                throw new AssertionError("Unconfigured capture should be hidden");
            }
            OverflowToolbar.install(bar, new Component[]{new JButton("Light"), capture},
                    new Component[]{new JButton("Other")});
            if (!SwingUtilities.isDescendingFrom(capture, bar)) {
                throw new AssertionError("Overflow regrouping dropped capture button");
            }
            capture.setVisible(true); // Simulate an available capture provider for layout.
            bar.setSize(800, 32);
            bar.doLayout();
            capture.getParent().doLayout();
            if (!capture.getParent().isVisible() || capture.getWidth() <= 0) {
                throw new AssertionError("Available capture has no visible layout");
            }
            capture.setVisible(false);
            bar.doLayout();
            if (capture.isVisible() || !SwingUtilities.isDescendingFrom(capture, bar)) {
                throw new AssertionError("Hidden capture must remain owned by toolbar");
            }
            System.out.println("RENDERDOC_TOOLBAR_REGROUP_PASS");
        });
    }
}
