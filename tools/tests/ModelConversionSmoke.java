package com.jme3.gde.core.assets;

import com.jme3.anim.*;
import com.jme3.asset.DesktopAssetManager;
import com.jme3.material.Material;
import com.jme3.math.Vector3f;
import com.jme3.scene.*;
import com.jme3.scene.shape.Box;
import java.awt.Dialog;
import javax.swing.*;
import org.openide.DialogDescriptor;
import org.openide.DialogDisplayer;
import org.openide.NotifyDescriptor;

/** Actual conversion dialog defaults and unsupported-surface regression. */
public final class ModelConversionSmoke {
    private static ModelConversionAnalysis expected;
    private static boolean uncheckAnimated;

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static final class Dialogs extends DialogDisplayer {
        public Dialog createDialog(DialogDescriptor descriptor) {
            throw new UnsupportedOperationException();
        }
        public Object notify(NotifyDescriptor descriptor) {
            JPanel panel = (JPanel) descriptor.getMessage();
            boolean animationFound = false;
            boolean sdfFound = false;
            boolean surfaceFound = false;
            for (java.awt.Component component : panel.getComponents()) {
                if (!(component instanceof JLabel label) || !(label.getLabelFor() instanceof JCheckBox checkbox)) {
                    continue;
                }
                if (label.getText().startsWith("Animated model")) {
                    animationFound = true;
                    require(checkbox.isSelected() == expected.animated(), "Animation checkbox default");
                    if (uncheckAnimated) {
                        checkbox.doClick();
                    }
                } else if (label.getText().equals("Bake SDF")) {
                    sdfFound = true;
                    require(checkbox.isSelected() == !expected.dynamic(), "SDF default must follow actual animation/skinning");
                } else if (label.getText().equals("Bake surface attributes")) {
                    surfaceFound = true;
                    require(checkbox.isSelected() == (!expected.dynamic() && expected.surfaceSupported()), "Surface default");
                    require(checkbox.isEnabled() == expected.surfaceSupported(), "Unsupported capture disabled");
                }
            }
            require(animationFound && sdfFound && surfaceFound, "Dialog exposes all required flags");
            return NotifyDescriptor.OK_OPTION;
        }
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.util.prefs.PreferencesFactory", MemoryPreferencesFactory.class.getName());
        DesktopAssetManager assets = new DesktopAssetManager(true);
        verifyGltfImport(assets);
        Node root = new Node("Imported model");
        Geometry mesh = new Geometry("Mesh", new Box(1, 1, 1));
        mesh.setMaterial(new Material(assets, "Common/MatDefs/Misc/Unshaded.j3md"));
        root.attachChild(mesh);
        verifyDialog(root);
        AnimComposer composer = new AnimComposer();
        root.addControl(composer);
        AnimClip clip = new AnimClip("Rotate");
        clip.setTracks(new AnimTrack[]{new TransformTrack(mesh, new float[]{0, 1},
                new Vector3f[]{new Vector3f(), new Vector3f(0, 1, 0)}, null, null)});
        composer.addAnimClip(clip);
        ModelConversionAnalysis animated = ModelConversionAnalysis.inspect(root);
        require(animated.animated() && !animated.skinned(), "Node animation is not skinning");
        verifyDialog(root);
        uncheckAnimated = true;
        verifyDialog(root);
        require(composer.hasAnimClip("Rotate"), "Unchecking must preserve clips");
        uncheckAnimated = false;
        // Force the previously failing surface option through the programmatic path.
        ModelConversionOptions forcedSurface = new ModelConversionOptions(false, ModelConversionOptions.Collision.MESH,
                false, 1, false, false, false, true, true);
        Spatial result = ModelConversionPreparation.prepare(root, forcedSurface, null);
        require(result.getControl(AnimComposer.class).hasAnimClip("Rotate"), "Conversion retains animations");
        require(result.getUserData(com.jme3.scene.plugins.sdf.SurfaceBakeData.USER_DATA_KEY) == null,
                "Unsupported surface is skipped without fake bake data");
        root.removeControl(composer);
        root.addControl(new SkinningControl(new Armature(new Joint[]{new Joint("Root")})));
        ModelConversionAnalysis skeleton = ModelConversionAnalysis.inspect(root);
        require(skeleton.skinned() && !skeleton.animated(), "Skeleton without clips detected separately");
        verifyDialog(root);
        root.removeControl(root.getControl(SkinningControl.class));
        mesh.getMaterial().setTransparent(true);
        verifyDialog(root);
        require(!ModelConversionAnalysis.inspect(root).surfaceSupported(), "Transparent capture rejected in preflight");
        ModelConversionPreparation.prepare(root, forcedSurface, null);
        System.out.println("MODEL_CONVERSION_PREFLIGHT_DIALOG_SKIP_PASS");
        System.exit(0);
    }

    private static void verifyDialog(Spatial model) throws Exception {
        expected = ModelConversionAnalysis.inspect(model);
        SwingUtilities.invokeAndWait(() -> {
            ModelConversionOptions options = ModelConversionOptions.ask(expected, "Fixture.gltf");
            require(options.sdf() == !expected.dynamic(), "Actual chosen SDF option");
            boolean animation = expected.animated() && !uncheckAnimated;
            require(options.editor() == (animation ? AnimationEditorSupport.Preference.ANIMATION
                    : AnimationEditorSupport.Preference.SCENE), "Editor preference matches checkbox");
        });
    }

    private static void verifyGltfImport(DesktopAssetManager assets) throws Exception {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(32).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (float value : new float[]{0, 1, 0, 0, 0, 0, 1, 0}) {
            buffer.putFloat(value);
        }
        String encoded = java.util.Base64.getEncoder().encodeToString(buffer.array());
        String json = "{\"asset\":{\"version\":\"2.0\"},\"scene\":0,\"scenes\":[{\"nodes\":[0]}],"
                + "\"nodes\":[{\"name\":\"Turbine\"}],\"buffers\":[{\"byteLength\":32,"
                + "\"uri\":\"data:application/octet-stream;base64," + encoded + "\"}],"
                + "\"bufferViews\":[{\"buffer\":0,\"byteOffset\":0,\"byteLength\":8},{\"buffer\":0,\"byteOffset\":8,\"byteLength\":24}],"
                + "\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":2,\"type\":\"SCALAR\",\"min\":[0],\"max\":[1]},"
                + "{\"bufferView\":1,\"componentType\":5126,\"count\":2,\"type\":\"VEC3\"}],"
                + "\"animations\":[{\"name\":\"Turbine_L\",\"samplers\":[{\"input\":0,\"output\":1}],"
                + "\"channels\":[{\"sampler\":0,\"target\":{\"node\":0,\"path\":\"translation\"}}]}]}";
        java.nio.file.Path folder = java.nio.file.Paths.get("build/animation-smoke").toAbsolutePath();
        java.nio.file.Files.createDirectories(folder);
        java.nio.file.Files.writeString(folder.resolve("AnimatedFixture.gltf"), json);
        assets.registerLocator(folder.toString(), com.jme3.asset.plugins.FileLocator.class);
        Spatial imported = assets.loadModel("AnimatedFixture.gltf");
        require(ModelConversionAnalysis.inspect(imported).animated(), "Actual GLTF animation detected before options");
        verifyDialog(imported);
        System.out.println("GLTF_ANIMATION_AUTO_DEFAULTS_PASS");
    }

    /** Keep dialog layout preferences out of the developer's registry/profile. */
    public static final class MemoryPreferencesFactory implements java.util.prefs.PreferencesFactory {
        private final java.util.prefs.Preferences root = new MemoryPreferences(null, "");
        public java.util.prefs.Preferences systemRoot() {
            return root;
        }
        public java.util.prefs.Preferences userRoot() {
            return root;
        }
    }

    private static final class MemoryPreferences extends java.util.prefs.AbstractPreferences {
        private final java.util.Map<String, String> values = new java.util.HashMap<>();
        MemoryPreferences(java.util.prefs.AbstractPreferences parent, String name) {
            super(parent, name);
        }
        protected void putSpi(String key, String value) {
            values.put(key, value);
        }
        protected String getSpi(String key) {
            return values.get(key);
        }
        protected void removeSpi(String key) {
            values.remove(key);
        }
        protected void removeNodeSpi() {
            values.clear();
        }
        protected String[] keysSpi() {
            return values.keySet().toArray(new String[0]);
        }
        protected String[] childrenNamesSpi() {
            return new String[0];
        }
        protected java.util.prefs.AbstractPreferences childSpi(String name) {
            return new MemoryPreferences(this, name);
        }
        protected void syncSpi() {
        }
        protected void flushSpi() {
        }
    }
}
