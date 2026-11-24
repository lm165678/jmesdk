package com.jme3.gde.core.scene;

import com.jme3.bullet.PhysicsSpace;
import com.jme3.bullet.collision.shapes.BoxCollisionShape;
import com.jme3.bullet.collision.shapes.MeshCollisionShape;
import com.jme3.bullet.control.RigidBodyControl;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.export.binary.BinaryImporter;
import com.jme3.gde.core.assets.ModelCollisionShapes;
import com.jme3.gde.core.assets.ModelConversionOptions;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.UserData;
import com.jme3.scene.shape.Box;
import com.jme3.scene.shape.Line;
import com.jme3.system.NativeLibraryLoader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/** Verifies Minie preview restoration and per-geometry collision through a J3O round trip. */
public final class EditorPhysicsSmoke {
    public static void main(String[] args) throws Exception {
        NativeLibraryLoader.loadNativeLibrary("bulletjme", true);
        verifyPreview(false);
        verifyPreview(true);
        verifyConversion();
        System.out.println("EDITOR_PHYSICS_PASS");
    }

    private static void verifyPreview(boolean removeBeforePlay) throws Exception {
        var root = new Node("Scene");
        var sync = new EditorCollisionSync(root);
        var box = new Geometry("Dynamic", new Box(.5f, .5f, .5f));
        root.attachChild(box);
        box.setLocalTranslation(1f, 5f, 0f);
        root.updateGeometricState();
        var body = new RigidBodyControl(new BoxCollisionShape(.5f), 5f);
        box.addControl(body);
        var velocity = new Vector3f(.5f, 0f, 0f);
        body.setLinearVelocity(velocity);
        sync.flush(null);
        var copy = box.clone(true);
        root.attachChild(copy);
        copy.setLocalTranslation(4f, 5f, 0f);
        root.updateGeometricState();
        sync.flush(null);
        if (removeBeforePlay) {
            copy.removeFromParent();
            sync.flush(null);
        }
        var preview = new PhysicsPreview(root);
        var space = new PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT);
        try {
            space.addAll(root);
            for (int tick = 0; tick < 35; tick++) {
                sync.flush(space, preview);
                space.distributeEvents();
                sync.updateControls(() -> root.updateLogicalState(1f / 60));
                root.updateGeometricState();
                // BulletAppState steps during render, after spatial controls update.
                space.update(1f / 60);
                root.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
                root.setCullHint(com.jme3.scene.Spatial.CullHint.Inherit);
            }
            check(box.getLocalTranslation().y < 4.5f, "Bullet preview did not move the dynamic object");
            if (!removeBeforePlay) {
                check(copy.getLocalTranslation().y < 4.5f, "Copy did not fall");
            }
            body.setAngularVelocity(new Vector3f(4f, 5f, 6f));
            preview.restore(space);
            var saved = reload(root);
            var restored = saved.getChild("Dynamic");
            var restoredBody = restored.getControl(RigidBodyControl.class);
            check(restored.getLocalTranslation().equals(new Vector3f(1f, 5f, 0f)), "J3O kept the preview pose");
            check(restoredBody.getPhysicsLocation(null).equals(restored.getLocalTranslation()), "Physics transform was not restored");
            check(restoredBody.getLinearVelocity(null).equals(velocity), "J3O kept the preview velocity");
            check(restoredBody.getAngularVelocity(null).equals(Vector3f.ZERO), "J3O kept preview angular velocity");
            check(body.getPhysicsSpace() == null && restoredBody.getMass() == 5f, "Reset removed authored physics");
            check(!body.isGravityProtected(), "Reset changed gravity protection");
            copy.removeFromParent();
            sync.flush(null);
            space.addAll(root);
            for (int tick = 0; tick < 35; tick++) {
                sync.flush(space);
                sync.updateControls(() -> root.updateLogicalState(1f / 60));
                root.updateGeometricState();
                space.update(1f / 60);
                root.setCullHint(com.jme3.scene.Spatial.CullHint.Always);
                root.setCullHint(com.jme3.scene.Spatial.CullHint.Inherit);
            }
            check(box.getLocalTranslation().y < 4.5f, "Replay did not fall");
        } finally {
            space.destroy();
        }
    }

    private static void verifyConversion() throws Exception {
        var prepare = Class.forName("com.jme3.gde.core.assets.ModelCollisionPreparation").getDeclaredMethod(
                "addMissing", com.jme3.scene.Spatial.class, ModelConversionOptions.Collision.class, boolean.class);
        prepare.setAccessible(true);
        check(!ModelConversionOptions.defaults().separateCollisionBodies(), "Default conversion was changed");
        for (var shape : ModelConversionOptions.Collision.values()) {
            var root = fixture();
            prepare.invoke(null, root, shape, true);
            root = reload(root);
            check(root.getControl(RigidBodyControl.class) == null, "Split output retained a root compound");
            var first = root.getChild("First").getControl(RigidBodyControl.class);
            var second = root.getChild("Second").getControl(RigidBodyControl.class);
            check(first != null && second != null && first != second, "Each geometry must own a body");
            check(root.getChild("Ignored").getControl(RigidBodyControl.class) == null, "Ignored mesh was included");
            check(root.getChild("Line").getControl(RigidBodyControl.class) == null, "Line has no triangle surface");
            switch (shape) {
                case MESH -> check(first.getCollisionShape() instanceof MeshCollisionShape, "Triangle mesh shape");
                case BOX -> check(ModelCollisionShapes.simpleType(first.getCollisionShape()) == 3, "Box shape");
                case SPHERE -> check(ModelCollisionShapes.simpleType(first.getCollisionShape()) == 4, "Sphere shape");
            }
        }
        var combined = fixture();
        prepare.invoke(null, combined, ModelConversionOptions.Collision.MESH, false);
        check(combined.getControl(RigidBodyControl.class) != null, "Legacy root body missing");
        check(combined.getChild("First").getControl(RigidBodyControl.class) == null, "Legacy body unexpectedly split");
    }

    private static Node fixture() {
        var root = new Node("Imported");
        var branch = new Node("Nested");
        branch.setLocalTranslation(0f, 3f, 0f);
        root.attachChild(branch);
        branch.attachChild(new Geometry("First", new Box(1f, 2f, .5f)));
        root.attachChild(new Geometry("Second", new Box(.5f, 1f, 2f)));
        var ignored = new Geometry("Ignored", new Box(1f, 1f, 1f));
        ignored.setUserData(UserData.JME_PHYSICSIGNORE, true);
        branch.attachChild(ignored);
        root.attachChild(new Geometry("Line", new Line(Vector3f.ZERO, Vector3f.UNIT_X)));
        root.updateGeometricState();
        return root;
    }

    private static Node reload(Node scene) throws Exception {
        var bytes = new ByteArrayOutputStream();
        new BinaryExporter().save(scene, bytes);
        return (Node) new BinaryImporter().load(new ByteArrayInputStream(bytes.toByteArray()));
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }
}
