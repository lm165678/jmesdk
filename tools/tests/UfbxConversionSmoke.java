package com.jme3.gde.core.assets;

import com.jme3.anim.AnimComposer;
import com.jme3.anim.SkinningControl;
import com.jme3.asset.DesktopAssetManager;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.gde.autodesk.UfbxAssetManagerConfigurator;
import com.jme3.gde.texturetools.GeneratedTextures;
import com.jme3.gde.texturetools.TextureToolchain;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.VertexBuffer;
import com.jme3.texture.Texture;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import javax.imageio.ImageIO;
import org.openide.filesystems.FileUtil;

/** Round-trips upstream ufbx fixtures through SDK model conversion without a display. */
public final class UfbxConversionSmoke {
    public static void main(String[] args) {
        try {
            run(Path.of(args[0]), Path.of(args[1]));
            System.out.println("UFBX_CONVERSION_SMOKE_PASS");
            System.exit(0);
        } catch (Throwable error) {
            error.printStackTrace();
            System.exit(1);
        }
    }

    private static void run(Path fixtures, Path work) throws Exception {
        Path assets = Files.createTempDirectory(work, "ufbx-smoke-");
        ProjectAssetManager manager = new ProjectAssetManager(FileUtil.toFileObject(assets.toFile()));
        new UfbxAssetManagerConfigurator().prepareManager(manager);
        Node cube = convert(fixtures, assets, manager, "blender_272_cube_7400_binary.fbx");
        ArrayList<Geometry> cubeParts = new ArrayList<>();
        cube.depthFirstTraversal(spatial -> {
            if (spatial instanceof Geometry geometry) {
                cubeParts.add(geometry);
            }
        });
        require(cubeParts.stream().mapToInt(Geometry::getTriangleCount).sum() == 12,
                "Static cube lost triangles");
        Node rigid = convert(fixtures, assets, manager, "max2009_cube_anim_6100_ascii.fbx");
        var rigidComposer = rigid.getControl(AnimComposer.class);
        require(rigidComposer != null && rigidComposer.getAnimClips().stream().anyMatch(clip -> clip.getLength() > 0),
                "ASCII FBX lost rigid-node animation");
        Node animated = convert(fixtures, assets, manager, "blender_279_sausage_7400_binary.fbx");
        AnimComposer composer = animated.getControl(AnimComposer.class);
        require(composer != null && !composer.getAnimClipsNames().isEmpty(), "FBX animation clips missing");
        ArrayList<SkinningControl> skins = new ArrayList<>();
        animated.depthFirstTraversal(spatial -> {
            SkinningControl skin = spatial.getControl(SkinningControl.class);
            if (skin != null) {
                skins.add(skin);
            }
        });
        require(!skins.isEmpty(), "FBX skin missing");
        var clip = composer.getAnimClipsNames().iterator().next();
        double length = composer.getAnimClip(clip).getLength();
        require(length > 0, "FBX animation duration missing");
        composer.setCurrentAction(clip);
        animated.updateLogicalState(0f);
        var armature = skins.get(0).getArmature();
        var before = java.util.Arrays.stream(armature.computeSkinningMatrices())
                .map(matrix -> matrix.clone()).toArray(com.jme3.math.Matrix4f[]::new);
        boolean moved = false;
        for (int i = 0; i < 8 && !moved; ++i) {
            animated.updateLogicalState((float) length / 9);
            moved = !java.util.Arrays.equals(before, armature.computeSkinningMatrices());
        }
        require(moved, "Reloaded FBX animation did not move the skin matrices");
        animated.depthFirstTraversal(spatial -> {
            if (spatial instanceof Geometry geometry) {
                require(geometry.getMesh().getBuffer(VertexBuffer.Type.BoneWeight) != null,
                        "Animated sample lost joint weights");
            }
        });

        Node textured = convert(fixtures, assets, manager, "blender_293_embedded_textures_7400_binary.fbx");
        verifyDds(textured);
        BufferedImage external = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        external.setRGB(0, 0, 0x00ff0000);
        ImageIO.write(external, "png", assets.resolve("tiny_clouds.png").toFile());
        Node linked = convert(fixtures, assets, manager, "max2009_cube_texture_6100_ascii.fbx");
        verifyDds(linked);
        verifyPortableDds(assets, manager);
    }

    private static void verifyPortableDds(Path assets, ProjectAssetManager manager) throws Exception {
        Path emptyTools = Files.createTempDirectory(assets.getParent(), "ufbx-no-encoders-");
        String configured = System.getProperty("jme.sdk.texture.backend");
        try {
            System.setProperty("jme.sdk.texture.backend", "auto");
            require(!new TextureToolchain(emptyTools).hasConfiguredDdsEncoder(),
                    "Toolchain incorrectly found a DDS encoder in an empty installation");
        } finally {
            if (configured == null) {
                System.clearProperty("jme.sdk.texture.backend");
            } else {
                System.setProperty("jme.sdk.texture.backend", configured);
            }
        }
        BufferedImage image = new BufferedImage(7, 5, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0x8078a623);
        ImageIO.write(image, "png", assets.resolve("portable.png").toFile());
        GeneratedTextures registry = new GeneratedTextures(assets);
        Path output = registry.ensurePortableBc3("portable.png", PortableModelDds::write);
        require(output.equals(registry.resolve("portable.png")), "Generated DDS registry lost the portable profile");
        var key = new com.jme3.asset.TextureKey(assets.relativize(output).toString().replace('\\', '/'), false);
        var dds = manager.loadTexture(key).getImage();
        require(dds.getWidth() == 7 && dds.getHeight() == 5 && dds.getFormat().isCompressed()
                && dds.getMipMapSizes().length == 3, "Portable BC3 lost dimensions or the full mip chain");
    }

    private static void verifyDds(Node textured) {
        ArrayList<Texture> textures = new ArrayList<>();
        textured.depthFirstTraversal(spatial -> {
            if (spatial instanceof Geometry geometry) {
                var parameter = geometry.getMaterial().getTextureParam("BaseColorMap");
                if (parameter != null) {
                    textures.add(parameter.getTextureValue());
                }
            }
        });
        require(!textures.isEmpty(), "FBX sample lost all base textures");
        for (Texture texture : textures) {
            String key = texture.getKey().getName();
            require(key.endsWith(".dds") && (key.startsWith("dds/") || key.contains("/dds/")),
                    "J3O did not save a direct DDS key: " + key);
            require(texture.getImage().getFormat().isCompressed(), "DDS failed to reload");
        }
    }

    private static Node convert(Path fixtures, Path assets, ProjectAssetManager manager, String name)
            throws Exception {
        Path fbx = Files.copy(fixtures.resolve(name), assets.resolve(name));
        FileUtil.refreshFor(assets.toFile());
        var options = new ModelConversionOptions(false, ModelConversionOptions.Collision.MESH,
                false, 1, true, true, false, false, true);
        Node model = (Node) ModelConversionPreparation.prepare(manager.loadModel(name), options, manager);
        ModelTexturePreparation.prepare(model, manager, FileUtil.toFileObject(fbx.toFile()));
        String j3o = name + ".j3o";
        BinaryExporter.getInstance().save(model, assets.resolve(j3o).toFile());

        Path runtime = Files.createTempDirectory(assets.getParent(), "ufbx-runtime-");
        Files.copy(assets.resolve(j3o), runtime.resolve(j3o));
        try (var paths = Files.walk(assets)) {
            for (Path dds : paths.filter(file -> file.toString().endsWith(".dds")).toList()) {
                Path target = runtime.resolve(assets.relativize(dds));
                Files.createDirectories(target.getParent());
                Files.copy(dds, target);
            }
        }
        var fresh = new DesktopAssetManager(true);
        fresh.registerLocator(runtime.toString(), com.jme3.asset.plugins.FileLocator.class);
        return (Node) fresh.loadModel(j3o);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
