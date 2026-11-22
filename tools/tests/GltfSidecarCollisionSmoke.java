package com.jme3.gde.core.assets;

import com.jme3.bullet.PhysicsSpace;
import com.jme3.bullet.control.RigidBodyControl;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Box;
import com.jme3.system.NativeLibraryLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Verifies the single-file dependency list and native static collision baking. */
public final class GltfSidecarCollisionSmoke {
    public static void main(String[] args) throws Exception {
        NativeLibraryLoader.loadNativeLibrary("bulletjme", true);
        checkCollision();
        Path source = Path.of(args[0]).toAbsolutePath();
        Path work = Files.createTempDirectory(Path.of(args[1]).toAbsolutePath(), "gltf-sidecars-");
        Path relative = Path.of("Models").resolve(source.getFileName());
        Map<Path, Path> inputs = new LinkedHashMap<>();
        inputs.put(relative, source);
        var collect = ExternalAssetImport.class.getDeclaredMethod(
                "addGltfDependencies", Map.class, Path.class, Path.class, boolean.class);
        collect.setAccessible(true);
        var dependencies = (java.util.Set<?>) collect.invoke(null, inputs, relative, work, false);
        require(dependencies.size() == 25, "Spaceship must stage one binary buffer and 24 textures");
        for (var input : inputs.entrySet()) {
            Path output = work.resolve(input.getKey());
            Files.createDirectories(output.getParent());
            Files.copy(input.getValue(), output);
        }
        var filesystem = new org.openide.filesystems.LocalFileSystem();
        filesystem.setRootDirectory(work.toFile());
        var manager = new ProjectAssetManager(filesystem.getRoot());
        // A LocalFileSystem FileObject has a relative path. Supply the disk root
        // explicitly when testing outside NetBeans' master filesystem.
        manager.unregisterLocator("", RootLockingFileLocator.class);
        manager.registerLocator(work.toString(), RootLockingFileLocator.class);
        String key = relative.toString().replace('\\', '/');
        require(manager.getAssetFolder().getFileObject(key) != null,
                "Staged glTF missing under NetBeans asset root " + manager.getAssetFolder().getPath());
        Spatial model = manager.loadModel(key);
        model.updateGeometricState();
        ModelCollisionPreparation.addMissing(model);
        require(model.getControl(RigidBodyControl.class) != null, "Spaceship collision missing");
        BinaryExporter.getInstance().save(model, work.resolve("SpaceshipCollision.j3o").toFile());
        Spatial reopened = manager.loadModel("SpaceshipCollision.j3o");
        require(reopened.getControl(RigidBodyControl.class) != null, "Spaceship collision missing after J3O reload");
        System.out.println("GLTF_SIDECARS_AND_MIRRORED_COLLISION_PASS");
    }

    private static void checkCollision() {
        var space = new PhysicsSpace(PhysicsSpace.BroadphaseType.DBVT);
        try {
            for (boolean mirrored : new boolean[]{false, true}) {
                Node owner = new Node("Owner");
                owner.setLocalTranslation(10, 0, 0);
                owner.setLocalScale(mirrored ? -2 : 2, 1, 1);
                Node child = new Node("Offset");
                child.setLocalTranslation(2, 0, 0);
                owner.attachChild(child);
                Geometry geometry = new Geometry("Asymmetric box", new Box(.3f, .5f, .7f));
                geometry.setLocalTranslation(1, 0, 0);
                child.attachChild(geometry);
                owner.updateGeometricState();
                ModelCollisionPreparation.addMissing(owner);
                var control = owner.getControl(RigidBodyControl.class);
                require(control != null && control.getCollisionShape().getScale(null).x > 0, "Invalid native scale");
                control.setPhysicsSpace(space);
                float center = geometry.getWorldTranslation().x;
                require(!space.rayTest(new Vector3f(center, 3, 0), new Vector3f(center, -3, 0)).isEmpty(),
                        "Collision did not follow rendered geometry");
                require(space.rayTest(new Vector3f(20 - center, 3, 0), new Vector3f(20 - center, -3, 0)).isEmpty(),
                        "Collision incorrectly appears on the other side");
                control.setPhysicsSpace(null);
            }
        } finally {
            space.destroy();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
