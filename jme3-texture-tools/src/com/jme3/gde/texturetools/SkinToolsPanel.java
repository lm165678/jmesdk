package com.jme3.gde.texturetools;

import java.awt.BorderLayout;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;

/** Both tabs use one editable diffusion profile; stale normal results are invalidated. */
final class SkinToolsPanel extends JPanel {
    private final SkinLutPanel lut = new SkinLutPanel();
    private final SkinNormalPanel normals = new SkinNormalPanel(lut::snapshot, lut::profileDescription);

    private final SkinSurfacePanel surface = new SkinSurfacePanel(lut.assetRootDocument(), normals.materialDocument());

    SkinToolsPanel() {
        super(new BorderLayout());
        normals.shareDestination(lut.assetRootDocument(), lut.outputKeyDocument());
        JTabbedPane tabs = new JTabbedPane();
        tabs.setName("skin.tabs");
        tabs.addTab("LUT generation", lut);
        tabs.addTab("Normal prefiltering", normals);
        tabs.addTab("Thickness & curvature", surface);
        add(tabs, BorderLayout.CENTER);
        lut.addPropertyChangeListener("skinProfile", event -> normals.profileChanged());
    }

    void close() {
        lut.close();
        normals.close();
        surface.close();
    }
}
