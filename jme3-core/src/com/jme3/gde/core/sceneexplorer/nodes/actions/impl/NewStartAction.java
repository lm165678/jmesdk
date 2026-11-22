package com.jme3.gde.core.sceneexplorer.nodes.actions.impl;

import com.jme3.gde.core.sceneexplorer.nodes.actions.AbstractNewSpatialAction;
import com.jme3.gde.core.sceneexplorer.nodes.actions.NewSpatialAction;
import com.jme3.scene.Node;
import com.jme3.scene.Start;
import org.openide.util.lookup.ServiceProvider;

/** Adds Start through the shared scene creation and undo workflow. */
@ServiceProvider(service = NewSpatialAction.class)
public final class NewStartAction extends AbstractNewSpatialAction {
    public NewStartAction() {
        name = "Start";
    }

    @Override
    protected boolean prepareCreateSpatial() {
        return true;
    }

    @Override
    protected Start doCreateSpatial(Node parent) {
        return new Start();
    }
}
