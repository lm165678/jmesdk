package com.jme3.gde.scenecomposer;

import com.jme3.environment.gi.ProbeLightmapControl;
import com.jme3.gde.core.undoredo.AbstractUndoableSceneEdit;
import com.jme3.scene.Spatial;
import java.util.ArrayList;
import java.util.List;

/** Scene-thread edit for the SDK-generated Lightmap bindings stored in a J3O. */
final class SceneLightmapEdit extends AbstractUndoableSceneEdit {
    private record Binding(Spatial receiver, ProbeLightmapControl control) {
    }

    private final List<Binding> bindings = new ArrayList<>();
    private final Runnable markModified;

    SceneLightmapEdit(Spatial root, Runnable markModified) {
        this.markModified = markModified;
        root.depthFirstTraversal(spatial -> {
            ProbeLightmapControl control = spatial.getControl(ProbeLightmapControl.class);
            if (control != null) {
                bindings.add(new Binding(spatial, control));
            }
        });
    }

    int size() {
        return bindings.size();
    }

    @Override
    public void sceneUndo() {
        for (Binding binding : bindings) {
            if (binding.receiver().getControl(ProbeLightmapControl.class) == null) {
                binding.receiver().addControl(binding.control());
            }
        }
        markModified.run();
    }

    @Override
    public void sceneRedo() {
        for (Binding binding : bindings) {
            binding.receiver().removeControl(binding.control());
        }
        markModified.run();
    }
}
