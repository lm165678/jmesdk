package com.jme3.gde.core.scene;

import com.jme3.bullet.PhysicsSpace;
import com.jme3.bullet.collision.PhysicsCollisionObject;
import com.jme3.bullet.collision.shapes.CollisionShape;
import com.jme3.bullet.control.CharacterControl;
import com.jme3.bullet.control.GhostControl;
import com.jme3.bullet.control.PhysicsControl;
import com.jme3.bullet.control.RigidBodyControl;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.jme3.scene.SceneChangeListener;
import com.jme3.scene.Spatial;
import com.jme3.util.clone.Cloner;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Synchronizes editor scene changes before controls can apply physics poses. */
final class EditorCollisionSync {
    private final Node root;
    private boolean updatingControls;
    private final Set<Spatial> changed = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Spatial> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<PhysicsCollisionObject, CollisionShape> editableShapes = new WeakHashMap<>();
    private final Vector3f scale = new Vector3f();
    private final Vector3f vector = new Vector3f();
    private final Vector3f axis = new Vector3f();
    private final Quaternion rotation = new Quaternion();

    EditorCollisionSync(Node root) {
        this.root = root;
        root.addSceneChangeListener((spatial, change) -> {
            if (updatingControls && change == SceneChangeListener.Change.TRANSFORM
                    && hasSimulatedPose(spatial)) {
                return;
            }
            if (change == SceneChangeListener.Change.TRANSFORM
                    || change == SceneChangeListener.Change.ATTACHED
                    || change == SceneChangeListener.Change.DETACHING
                    || change == SceneChangeListener.Change.RENDERING) {
                changed.add(spatial);
            }
        });
    }

    private static boolean hasSimulatedPose(Spatial spatial) {
        for (int i = 0; i < spatial.getNumControls(); i++) {
            if (spatial.getControl(i) instanceof PhysicsControl control
                    && control.isEnabled() && control.getPhysicsSpace() != null) {
                if (control instanceof RigidBodyControl body) {
                    return body.isDynamic();
                }
                if (control instanceof CharacterControl) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Physics writes interpolated poses during logical updates, not editor edits. */
    void updateControls(Runnable update) {
        updatingControls = true;
        try {
            update.run();
        } finally {
            updatingControls = false;
        }
    }

    void flush(PhysicsSpace previewSpace) {
        flush(previewSpace, null);
    }

    void flush(PhysicsSpace previewSpace, PhysicsPreview preview) {
        if (changed.isEmpty()) {
            return;
        }
        Spatial[] work = changed.toArray(Spatial[]::new);
        changed.clear();
        try {
            for (Spatial spatial : work) {
                boolean present = spatial == root || spatial.hasAncestor(root);
                syncSubtree(spatial, present, previewSpace, preview);
            }
        } finally {
            visited.clear();
        }
    }

    private void syncSubtree(Spatial spatial, boolean present, PhysicsSpace previewSpace, PhysicsPreview preview) {
        if (!visited.add(spatial)) {
            return;
        }
        for (int i = 0; i < spatial.getNumControls(); i++) {
            if (spatial.getControl(i) instanceof PhysicsControl control) {
                if (!present) {
                    if (previewSpace != null && control.getPhysicsSpace() == previewSpace) {
                        control.setPhysicsSpace(null);
                    }
                } else {
                    synchronize(spatial, control);
                    if (previewSpace != null && control.getPhysicsSpace() == null) {
                        if (preview != null) {
                            preview.capture(spatial, control);
                        }
                        control.setPhysicsSpace(previewSpace);
                    }
                }
            }
        }
        if (spatial instanceof Node node) {
            for (Spatial child : node.getChildren()) {
                syncSubtree(child, present, previewSpace, preview);
            }
        }
    }

    private void synchronize(Spatial spatial, PhysicsControl control) {
        if (control instanceof RigidBodyControl body) {
            boolean local = body.isApplyPhysicsLocal();
            updateScale(body, local ? spatial.getLocalScale() : spatial.getWorldScale());
            Vector3f position = local ? spatial.getLocalTranslation() : spatial.getWorldTranslation();
            Quaternion orientation = local ? spatial.getLocalRotation() : spatial.getWorldRotation();
            if (body.getPhysicsLocation(vector).distanceSquared(position) > 1e-10f) {
                body.setPhysicsLocation(position);
            }
            body.getPhysicsRotation(rotation);
            if (rotation.dot(orientation) < 0f) {
                rotation.negateLocal();
            }
            if (!rotation.isSimilar(orientation, 1e-5f)) {
                body.setPhysicsRotation(orientation);
            }
        } else if (control instanceof GhostControl ghost) {
            boolean local = ghost.isApplyPhysicsLocal();
            updateScale(ghost, local ? spatial.getLocalScale() : spatial.getWorldScale());
            ghost.setPhysicsLocation(local ? spatial.getLocalTranslation() : spatial.getWorldTranslation());
            ghost.setPhysicsRotation(local ? spatial.getLocalRotation() : spatial.getWorldRotation());
        } else if (control instanceof CharacterControl character) {
            boolean local = character.isApplyPhysicsLocal();
            var object = character.getCharacter();
            updateScale(object, local ? spatial.getLocalScale() : spatial.getWorldScale());
            Vector3f position = local ? spatial.getLocalTranslation() : spatial.getWorldTranslation();
            if (object.getPhysicsLocation(vector).distanceSquared(position) > 1e-10f) {
                character.setPhysicsLocation(position);
            }
            Quaternion orientation = local ? spatial.getLocalRotation() : spatial.getWorldRotation();
            orientation.mult(Vector3f.UNIT_Y, axis);
            if (object.getUpDirection(vector).distanceSquared(axis) > 1e-10f) {
                object.setUp(axis);
            }
            orientation.mult(Vector3f.UNIT_Z, axis);
            if (character.getViewDirection(vector).distanceSquared(axis) > 1e-10f) {
                character.setViewDirection(axis);
            }
        }
    }

    private void updateScale(PhysicsCollisionObject object, Vector3f desired) {
        CollisionShape shape = object.getCollisionShape();
        if (shape == null || !Vector3f.isValidVector(desired)) {
            return;
        }
        scale.set(Math.max(.0001f, Math.abs(desired.x)), Math.max(.0001f, Math.abs(desired.y)),
                Math.max(.0001f, Math.abs(desired.z)));
        if (!shape.canScale(scale)) {
            // Capsules and other uniform-only shapes use the largest axis.
            float uniform = Math.max(scale.x, Math.max(scale.y, scale.z));
            scale.set(uniform, uniform, uniform);
        }
        if (!shape.canScale(scale) || shape.getScale(vector).equals(scale)) {
            return;
        }
        if (editableShapes.get(object) != shape) {
            CollisionShape edited = Cloner.deepClone(shape);
            edited.setScale(scale);
            object.setCollisionShape(edited);
            editableShapes.put(object, edited);
        } else {
            shape.setScale(scale);
        }
    }
}
