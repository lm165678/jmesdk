package com.jme3.gde.core.sceneexplorer.nodes.actions;

import com.jme3.gde.core.scene.SceneApplication;
import com.jme3.gde.core.scene.SceneRequest;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import java.awt.event.ActionEvent;

/** A creation action invoked with a world-space viewport drop point. */
public final class SceneSpawnEvent extends ActionEvent {
    private final SceneRequest request;
    private final Vector3f position;

    public SceneSpawnEvent(SceneRequest request, Vector3f position) {
        super(request, ActionEvent.ACTION_PERFORMED, "spawn");
        this.request = request;
        this.position = position.clone();
    }

    static boolean canCreate(ActionEvent event) {
        return !(event instanceof SceneSpawnEvent spawn)
                || SceneApplication.getApplication().getCurrentSceneRequest() == spawn.request;
    }

    static Vector3f position(ActionEvent event) {
        return event instanceof SceneSpawnEvent spawn ? spawn.position.clone() : null;
    }

    static void place(ActionEvent event, Node parent, Spatial spatial) {
        if (event instanceof SceneSpawnEvent spawn) {
            spatial.setLocalTranslation(parent.worldToLocal(spawn.position, null));
        }
    }
}
