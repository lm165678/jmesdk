package com.jme3.gde.core.sceneexplorer.nodes.actions.impl;

import com.jme3.environment.gi.GiProbeVolume;
import com.jme3.gde.core.sceneexplorer.nodes.actions.AbstractNewSpatialAction;
import com.jme3.gde.core.sceneexplorer.nodes.actions.NewSpatialAction;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import org.openide.util.lookup.ServiceProvider;

/** Adds a serializable GI volume through the standard undoable scene-node workflow. */
public final class NewGiProbeVolumeAction extends AbstractNewSpatialAction {
    public NewGiProbeVolumeAction() {
        name = com.jme3.gde.core.util.EditorMessages.text("GiAdd");
    }

    @Override
    protected boolean prepareCreateSpatial() {
        return true;
    }

    @Override
    protected Spatial doCreateSpatial(Node parent) {
        return new GiProbeVolume();
    }
}
