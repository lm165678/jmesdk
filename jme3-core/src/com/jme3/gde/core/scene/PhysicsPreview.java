package com.jme3.gde.core.scene;

import com.jme3.bullet.PhysicsSpace;
import com.jme3.bullet.control.BetterCharacterControl;
import com.jme3.bullet.control.CharacterControl;
import com.jme3.bullet.control.GhostControl;
import com.jme3.bullet.control.PhysicsControl;
import com.jme3.bullet.objects.PhysicsRigidBody;
import com.jme3.math.Transform;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import java.util.IdentityHashMap;
import java.util.Map;

/** Keeps the authored poses and Bullet values for one editor preview. */
final class PhysicsPreview {
    private final Node root;
    private final Map<Spatial, Transform> poses = new IdentityHashMap<>();
    private final Map<PhysicsControl, Runnable> controls = new IdentityHashMap<>();

    PhysicsPreview(Node root) {
        this.root = root;
        root.depthFirstTraversal(spatial -> {
            for (int i = 0; i < spatial.getNumControls(); i++) {
                if (spatial.getControl(i) instanceof PhysicsControl control) {
                    capture(spatial, control);
                }
            }
        });
    }

    void capture(Spatial spatial, PhysicsControl control) {
        if (!controls.containsKey(control)) {
            poses.computeIfAbsent(spatial, key -> key.getLocalTransform().clone());
            controls.put(control, captureBody(control));
        }
    }

    void restore(PhysicsSpace space) {
        space.removeAll(root);
        for (PhysicsControl control : controls.keySet()) {
            if (control.getPhysicsSpace() == space) {
                control.setPhysicsSpace(null);
            }
        }
        poses.forEach(Spatial::setLocalTransform);
        controls.values().forEach(Runnable::run);
    }

    private static Runnable captureBody(PhysicsControl control) {
        if (control instanceof PhysicsRigidBody body) {
            return captureRigidBody(body);
        }
        if (control instanceof BetterCharacterControl character) {
            return captureRigidBody(character.getRigidBody());
        }
        if (control instanceof CharacterControl controlCharacter) {
            var character = controlCharacter.getCharacter();
            var location = character.getPhysicsLocation(null);
            var linearVelocity = character.getLinearVelocity(null);
            var angularVelocity = character.getAngularVelocity(null);
            var walkDirection = character.getWalkDirection(null);
            return () -> {
                character.warp(location);
                character.setWalkDirection(walkDirection);
                character.setLinearVelocity(linearVelocity);
                character.setAngularVelocity(angularVelocity);
            };
        }
        if (control instanceof GhostControl ghost) {
            var location = ghost.getPhysicsLocation(null);
            var rotation = ghost.getPhysicsRotation(null);
            return () -> {
                ghost.setPhysicsLocation(location);
                ghost.setPhysicsRotation(rotation);
            };
        }
        return () -> { };
    }

    private static Runnable captureRigidBody(PhysicsRigidBody body) {
        var location = body.getPhysicsLocation(null);
        var rotation = body.getPhysicsRotation(null);
        boolean dynamic = body.isDynamic();
        var linearVelocity = dynamic ? body.getLinearVelocity(null) : null;
        var angularVelocity = dynamic ? body.getAngularVelocity(null) : null;
        var gravity = body.getGravity(null);
        boolean gravityProtected = body.isGravityProtected();
        var force = body.totalAppliedForce(null);
        var torque = body.totalAppliedTorque(null);
        return () -> {
            body.setPhysicsLocation(location);
            body.setPhysicsRotation(rotation);
            if (dynamic && body.isDynamic()) {
                body.setProtectGravity(true);
                try {
                    body.setGravity(gravity);
                } finally {
                    body.setProtectGravity(gravityProtected);
                }
                body.setLinearVelocity(linearVelocity);
                body.setAngularVelocity(angularVelocity);
                body.clearForces();
                body.applyCentralForce(force);
                body.applyTorque(torque);
            }
        };
    }
}
