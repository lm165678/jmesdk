package com.jme3.gde.core.scene;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.post.FilterPostProcessor;
import com.jme3.post.filters.*;
import com.jme3.renderer.*;

/** Runtime scope ownership, persistence, replacement processors and failure cleanup. */
public final class GiBakeReconstructionSmoke {
    private static void check(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
    public static void main(String[] args) throws Exception {
        var assets = new DesktopAssetManager();
        var view = new ViewPort("Preview", new Camera(64, 64));
        var fpp = new FilterPostProcessor(assets);
        var fsr1 = new UpscalingPass();
        var taa = new TemporalAAFilter();
        var fxaa = new FXAAFilter();
        fpp.addFilter(taa);
        fpp.addFilter(fxaa);
        fpp.addFilter(fsr1);
        view.addProcessor(fpp);
        var a = new GiBakeReconstructionGuard(view);
        var b = new GiBakeReconstructionGuard(view);
        check(a.refresh() && !a.refresh(), "Preview settling must happen once per new stage");
        check(!fsr1.isEnabled() && !taa.isEnabled() && fxaa.isEnabled(), "FSR1/TAA suppression failed");
        var saved = BinaryExporter.saveAndLoad(assets, fpp);
        check(saved.getFilter(UpscalingPass.class).isEnabled()
                && saved.getFilter(TemporalAAFilter.class).isEnabled(), "Transient disable persisted");
        check(b.refresh(), "Nested scope not owned");
        a.close();
        a.close();
        check(!fsr1.isEnabled(), "First close resumed another capture");
        var replacement = new FilterPostProcessor(assets);
        var fsr2 = new UpscalingPass();
        fsr2.setAlgorithm(UpscalingPass.Algorithm.FSR2);
        replacement.addFilter(fsr2);
        view.removeProcessor(fpp);
        view.addProcessor(replacement);
        check(b.refresh() && !fsr2.isEnabled(), "Replacement FSR2 pipeline escaped suppression");
        fsr2.setEnabled(false);
        b.close();
        check(fsr1.isEnabled() && taa.isEnabled() && !fsr2.isEnabled(), "Current user preference not restored");
        fsr2.setEnabled(true);
        try {
            try (var guard = new GiBakeReconstructionGuard(view)) {
                guard.refresh();
                throw new IllegalStateException("Simulated bake failure");
            }
        } catch (IllegalStateException expected) {
            check(fsr2.isEnabled(), "Failure cleanup did not restore FSR2");
        }
        System.out.println("GI_BAKE_RECONSTRUCTION_PASS");
    }
}
