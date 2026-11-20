package com.jme3.gde.core.scene;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.post.Filter;
import com.jme3.post.FilterPostProcessor;
import com.jme3.post.WorldSettings;
import com.jme3.post.filters.FXAAFilter;
import com.jme3.post.filters.TemporalAAFilter;
import com.jme3.scene.Geometry;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

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
        add.invoke(editor, "Upscaling", new com.jme3.post.filters.UpscalingPass(), false);
        Geometry scene = new Geometry("AA settings");
        SceneRequest request = new SceneRequest(editor, scene, null);
        Field owner = EditorPostProcessing.class.getDeclaredField("owner");
        owner.setAccessible(true);
        owner.set(editor, request);
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
            com.jme3.post.filters.UpscalingPass upscale = copy.getFilter(com.jme3.post.filters.UpscalingPass.class);
            if (upscale.isEnabled() != (mode != 0)
                    || Math.abs(upscale.getRenderScale() - .5f) > .00001f
                    || Math.abs(upscale.getSharpness() - .4f) > .00001f
                    || (mode == 2 && !upscale.replacesAntiAliasing())
                    || !copy.getFilter(TemporalAAFilter.class).isEnabled()) {
                throw new AssertionError("Upscaling configuration or AA preference failed J3O round-trip");
            }
        }
        System.out.println("POST_AA_SETTINGS_PASS");
    }
}
