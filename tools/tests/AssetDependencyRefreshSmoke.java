package com.jme3.gde.assetbrowser;

import com.jme3.gde.core.assets.ProjectAssetManager;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.material.Material;
import com.jme3.scene.*;
import com.jme3.scene.shape.Box;
import java.nio.file.*;
import java.util.*;
import org.openide.filesystems.FileUtil;
import org.openide.filesystems.FileObject;

/** Per-asset transitive refresh, missing/renamed dependencies and persisted fingerprints. */
public final class AssetDependencyRefreshSmoke {
    private static void check(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
    public static void main(String[] args) throws Exception {
        Path folder = Files.createTempDirectory("asset-dependency-refresh-");
        Files.createDirectories(folder.resolve("Shaders"));
        Files.writeString(folder.resolve("Shaders/main.frag"), "#import \"Shaders/indirect.glsllib\"\nvoid main(){}");
        Files.writeString(folder.resolve("Shaders/indirect.glsllib"), "// initial");
        Files.writeString(folder.resolve("test.j3md"), "MaterialDef Test {\n MaterialParameters { }\n Technique {\n VertexShader GLSL100: Common/MatDefs/Misc/Unshaded.vert\n FragmentShader GLSL100: Shaders/main.frag\n }\n}");
        var filesystem = new org.openide.filesystems.LocalFileSystem();
        filesystem.setRootDirectory(folder.toFile());
        var root = filesystem.getRoot();
        var manager = new ProjectAssetManager(root);
        manager.registerLocator(folder.toString(), com.jme3.asset.plugins.FileLocator.class);
        System.out.println("Dependency fixture: " + folder + "; root=" + root.getPath()
                + "; exists=" + root.getFileObject("test.j3md") + "; locator="
                + manager.locateAsset(new com.jme3.asset.AssetKey<>("test.j3md")));
        Material material = new Material(manager, "test.j3md");
        Geometry model = new Geometry("Model", new Box(1, 1, 1));
        model.setMaterial(material);
        new BinaryExporter().save(model, folder.resolve("model.j3o").toFile());
        root.refresh();
        var inspected = AssetDependencies.inspect(manager, root.getFileObject("model.j3o"), "model.j3o");
        check(inspected.paths().contains("test.j3md")
                && inspected.paths().contains("Shaders/main.frag")
                && inspected.paths().contains("Shaders/indirect.glsllib"), "Indirect shader dependencies missing: " + inspected);
        try (var tracking = new AssetDependencies.Tracking(manager)) {
            manager.loadAsset(new com.jme3.asset.AssetKey<>("test.j3md"));
            check(tracking.paths().contains("test.j3md") && tracking.unchanged(), "Cached dependency request not tracked");
            try (var output = root.getFileObject("test.j3md").getOutputStream()) {
                output.write("changed".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            check(!tracking.unchanged(), "A dependency modified during capture was accepted");
        }
        AssetDependencies index = new AssetDependencies();
        index.record("model.j3o", inspected.paths());
        index.record("unrelated.j3o", Set.of("Textures/other.dds"));
        index.record("scene.j3o", Set.of("model.j3o"));
        long other = index.version("unrelated.j3o");
        String fingerprint = AssetDependencies.fingerprint(root, "model.j3o", inspected.paths());
        Files.writeString(folder.resolve("Shaders/indirect.glsllib"), "// changed and longer");
        root.getFileObject("Shaders/indirect.glsllib").refresh();
        Set<String> affected = index.invalidate(Map.of("Shaders/indirect.glsllib", false));
        check(affected.contains("model.j3o") && affected.contains("scene.j3o")
                && index.version("unrelated.j3o") == other, "Unrelated thumbnail invalidated");
        check(!fingerprint.equals(AssetDependencies.fingerprint(root, "model.j3o", inspected.paths())), "Disk cache fingerprint stale");
        check(index.invalidate(Map.of("Shaders", true)).contains("model.j3o"), "Folder deletion not propagated");
        index.record("cycle-a", Set.of("cycle-b"));
        index.record("cycle-b", Set.of("cycle-a"));
        check(index.invalidate(Map.of("cycle-a", false)).contains("cycle-b"), "Cycle did not terminate");
        FileObject pendingModel = FileUtil.createData(root, "Models/pending.gltf");
        try (var output = pendingModel.getOutputStream()) {
            output.write(("{\"asset\":{\"version\":\"2.0\"},"
                    + "\"buffers\":[{\"uri\":\"later.bin\",\"byteLength\":12}]}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        var missing = AssetDependencies.inspect(manager, pendingModel, "Models/pending.gltf");
        check(missing.paths().contains("Models/later.bin"), "Missing relative dependency lost its parent directory");
        index.record("Models/pending.gltf", missing.paths());
        check(index.invalidate(Map.of("Models/later.bin", false)).contains("Models/pending.gltf"),
                "Restoring a missing dependency did not invalidate its model");
        System.out.println("ASSET_DEPENDENCY_REFRESH_PASS");
    }
}
