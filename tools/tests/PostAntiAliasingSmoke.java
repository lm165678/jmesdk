package com.jme3.gde.core.scene;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.post.Filter;
import com.jme3.post.FilterPostProcessor;
import com.jme3.post.WorldSettings;
import com.jme3.post.WorldSettings.DisplayOutput;
import com.jme3.post.filters.ColorGradingFilter;
import com.jme3.post.filters.FXAAFilter;
import com.jme3.post.filters.TemporalAAFilter;
import com.jme3.post.filters.UpscalingPass;
import com.jme3.scene.Geometry;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

public final class PostAntiAliasingSmoke {
    public static void main(String[] args) throws Exception {
        DesktopAssetManager assets = new DesktopAssetManager(true);
        EditorPostProcessing editor = new EditorPostProcessing(null);
        FilterPostProcessor chain = new FilterPostProcessor(assets);
        Field processor = EditorPostProcessing.class.getDeclaredField("processor");
        processor.setAccessible(true);
        processor.set(editor, chain);
        Method add = EditorPostProcessing.class.getDeclaredMethod("add", String.class, Filter.class, boolean.class);
        add.setAccessible(true);
        add.invoke(editor, "TAA", new TemporalAAFilter(), false);
        add.invoke(editor, "FXAA", new FXAAFilter(), true);
        add.invoke(editor, "Upscaling", new UpscalingPass(), false);
        add.invoke(editor, "ToneMap", new ColorGradingFilter(), true);
        Geometry scene = new Geometry("AA settings");
        SceneRequest request = new SceneRequest(editor, scene, null);
        Field owner = EditorPostProcessing.class.getDeclaredField("owner");
        owner.setAccessible(true);
        owner.set(editor, request);
        if (new WorldSettings().getDisplayOutput() != DisplayOutput.Sdr) {
            throw new AssertionError("New WorldSettings must default to SDR");
        }
        editor.saveSettings(request, true);
        Geometry defaultRoundTrip = BinaryExporter.saveAndLoad(assets, scene);
        if (defaultRoundTrip.getControl(WorldSettings.class).getDisplayOutput() != DisplayOutput.Sdr) {
            throw new AssertionError("New scenes must save SDR without changing the output selector");
        }
        editor.configure("TAACurrentWeight", .2);
        editor.configure("TAABaseVelocity", 0);
        editor.configure("TAATransparentVelocity", 0);
        for (int mode : new int[]{2, 1, 0, 2}) {
            editor.configure("AntiAliasing", mode);
            editor.saveSettings(request);
            Geometry restored = BinaryExporter.saveAndLoad(assets, scene);
            WorldSettings settings = restored.getControl(WorldSettings.class);
            FilterPostProcessor copy = settings.createPostProcessor(assets);
            TemporalAAFilter taa = copy.getFilter(TemporalAAFilter.class);
            FXAAFilter fxaa = copy.getFilter(FXAAFilter.class);
            if (taa.isEnabled() != (mode == 2) || fxaa.isEnabled() != (mode == 1)
                    || settings.getEditorOptions().get("AntiAliasing") != mode) {
                throw new AssertionError("AA mode did not round-trip");
            }
            if (Math.abs(taa.getCurrentFrameWeight() - .2f) > .00001f
                    || taa.isBasePassOutputsVelocity() || taa.isTranslucentVelocityEnabled()) {
                throw new AssertionError("TAA parameters were lost");
            }
        }
        editor.configure("FXAA", 1);
        if (chain.getFilter(TemporalAAFilter.class).isEnabled()) {
            throw new AssertionError("Legacy FXAA switch left TAA enabled");
        }
        editor.configure("TAA", 1);
        if (chain.getFilter(FXAAFilter.class).isEnabled()) {
            throw new AssertionError("TAA switch left FXAA enabled");
        }
        editor.configure("UpscalingScale", .5);
        editor.configure("UpscalingSharpness", .4);
        for (int mode : new int[]{1, 2, 0}) {
            editor.configure("Upscaling", mode);
            editor.saveSettings(request);
            Geometry restored = BinaryExporter.saveAndLoad(assets, scene);
            WorldSettings settings = restored.getControl(WorldSettings.class);
            FilterPostProcessor copy = settings.createPostProcessor(assets);
            UpscalingPass upscale = copy.getFilter(UpscalingPass.class);
            if (upscale.isEnabled() != (mode != 0)
                    || !upscale.isBasePassOutputsVelocity()
                    || Math.abs(upscale.getRenderScale() - .5f) > .00001f
                    || Math.abs(upscale.getSharpness() - .4f) > .00001f
                    || (mode == 2 && !upscale.replacesAntiAliasing())
                    || !copy.getFilter(TemporalAAFilter.class).isEnabled()) {
                throw new AssertionError("Upscaling configuration or AA preference failed J3O round-trip");
            }
        }
        for (int mode : new int[]{1, 0, 1}) {
            editor.configure("OpaqueVelocityPass", mode);
            editor.saveSettings(request);
            WorldSettings settings = BinaryExporter.saveAndLoad(assets, scene).getControl(WorldSettings.class);
            FilterPostProcessor copy = settings.createPostProcessor(assets);
            boolean basePass = mode == 0;
            if (copy.getFilter(TemporalAAFilter.class).isBasePassOutputsVelocity() != basePass
                    || copy.getFilter(UpscalingPass.class).isBasePassOutputsVelocity() != basePass
                    || settings.getEditorOptions().get("OpaqueVelocityPass") != mode
                    || settings.getEditorOptions().containsKey("TAABaseVelocity")) {
                throw new AssertionError("Shared opaque velocity pass did not round-trip");
            }
        }
        System.out.println("POST_AA_SETTINGS_PASS");

        editor.configure("FilmicCurve", 1);
        editor.configure("FilmLutTonemap", 1);
        editor.saveSettings(request);
        WorldSettings saved = scene.getControl(WorldSettings.class);
        long filterRevision = saved.getRevision();
        for (DisplayOutput mode : DisplayOutput.values()) {
            editor.configure("GameDisplayOutput", mode.ordinal());
            editor.saveSettings(request, true);
            Geometry restored = BinaryExporter.saveAndLoad(assets, scene);
            WorldSettings settings = restored.getControl(WorldSettings.class);
            ColorGradingFilter grade = settings.createPostProcessor(assets).getFilter(ColorGradingFilter.class);
            Field curve = ColorGradingFilter.class.getDeclaredField("curve");
            curve.setAccessible(true);
            if (settings.getDisplayOutput() != mode || saved.getRevision() != filterRevision
                    || curve.get(grade) != ColorGradingFilter.Curve.FilmLut) {
                throw new AssertionError("Display output or film LUT did not survive J3O round-trip");
            }
        }
        SwingUtilities.invokeAndWait(PostAntiAliasingSmoke::verifyDisplayControls);
        System.out.println("POST_DISPLAY_SETTINGS_PASS");
    }

    @SuppressWarnings("unchecked")
    private static void verifyDisplayControls() {
        try {
            Class<?> type = Class.forName("com.jme3.gde.scenecomposer.EditorPostProcessingPanel");
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object panel = constructor.newInstance();
            Field field = type.getDeclaredField("inputs");
            field.setAccessible(true);
            Map<String, JComponent> inputs = (Map<String, JComponent>) field.get(panel);
            JCheckBox film = (JCheckBox) inputs.get("FilmLutTonemap");
            JComboBox<?> opaqueVelocityPass = (JComboBox<?>) inputs.get("OpaqueVelocityPass");
            JComboBox<?> output = (JComboBox<?>) inputs.get("GameDisplayOutput");
            if (output.getSelectedIndex() != DisplayOutput.Sdr.ordinal()) {
                throw new AssertionError("The output selector must start at SDR");
            }
            var apply = type.getDeclaredMethod("applySettings", Map.class);
            apply.setAccessible(true);
            apply.invoke(panel, Map.of());
            if (!film.isEnabled() || film.isSelected() || inputs.get("FilmSlope").isEnabled()
                    || output.getSelectedIndex() != DisplayOutput.Sdr.ordinal()
                    || opaqueVelocityPass.isEnabled() || opaqueVelocityPass.getSelectedIndex() != 0) {
                throw new AssertionError("The default Film, output and velocity controls have the wrong state");
            }
            apply.invoke(panel, Map.of("FilmLutTonemap", 1d, "GameDisplayOutput", 2d));
            if (!film.isEnabled() || !film.isSelected() || !inputs.get("FilmSlope").isEnabled()
                    || inputs.get("FilmicCurve").isEnabled() || output.getSelectedIndex() != 2) {
                throw new AssertionError("Restored film LUT and output controls have the wrong state");
            }
            apply.invoke(panel, Map.of("AntiAliasing", 2d, "TAABaseVelocity", 0d));
            verifyVelocityControl(opaqueVelocityPass, true, 1);
            apply.invoke(panel, Map.of("Upscaling", 2d, "TAABaseVelocity", 0d));
            verifyVelocityControl(opaqueVelocityPass, true, 0);
            apply.invoke(panel, Map.of("Upscaling", 2d, "OpaqueVelocityPass", 1d));
            verifyVelocityControl(opaqueVelocityPass, true, 1);
            apply.invoke(panel, Map.of("Upscaling", 2d, "OpaqueVelocityPass", 1d, "UpscalingSupported", 0d));
            verifyVelocityControl(opaqueVelocityPass, false, 1);
            apply.invoke(panel, Map.of("AntiAliasing", 2d, "OpaqueVelocityPass", 1d, "LegacyPipeline", 1d));
            verifyVelocityControl(opaqueVelocityPass, false, 1);
            apply.invoke(panel, Map.of("AntiAliasing", 2d, "OpaqueVelocityPass", 1d, "Enabled", 0d));
            verifyVelocityControl(opaqueVelocityPass, false, 1);
            apply.invoke(panel, Map.of("AntiAliasing", 2d, "OpaqueVelocityPass", 1d, "PostProcessingSupported", 0d));
            verifyVelocityControl(opaqueVelocityPass, false, 1);
            apply.invoke(panel, Map.of("AntiAliasing", 2d, "Upscaling", 1d, "OpaqueVelocityPass", 1d));
            verifyVelocityControl(opaqueVelocityPass, true, 1);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static void verifyVelocityControl(JComboBox<?> control, boolean enabled, int selection) {
        if (control.isEnabled() != enabled || control.getSelectedIndex() != selection) {
            throw new AssertionError("The opaque velocity selector has the wrong state");
        }
    }
}
