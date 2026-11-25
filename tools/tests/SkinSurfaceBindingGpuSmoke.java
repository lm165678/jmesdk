package com.jme3.gde.texturetools;

import com.jme3.app.SimpleApplication;
import com.jme3.asset.MaterialKey;
import com.jme3.asset.plugins.FileLocator;
import com.jme3.light.DirectionalLight;
import com.jme3.material.Material;
import com.jme3.material.skin.SkinLutGenerator;
import com.jme3.material.skin.SkinSurfaceBaker;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Quad;
import com.jme3.system.AppSettings;
import com.jme3.system.JmeContext;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Native readback of an already rendered material after surface-map export. */
public final class SkinSurfaceBindingGpuSmoke extends SimpleApplication {
    private final CountDownLatch done = new CountDownLatch(1);
    private Throwable failure;
    private Material live;
    private SkinSurfaceBaker.Result maps;
    private Path directory;
    private int frame;
    private int phase;
    private float dark;
    private float thin;

    public static void main(String[] args) throws Exception {
        SkinSurfaceBindingGpuSmoke app = new SkinSurfaceBindingGpuSmoke();
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(AppSettings.LWJGL_OPENGL43);
        settings.setResolution(128, 128);
        settings.setAudioRenderer(null);
        settings.setGammaCorrection(true);
        settings.setFrameRate(60);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start(JmeContext.Type.OffscreenSurface);
        if (!app.done.await(45, TimeUnit.SECONDS)) {
            throw new AssertionError("Surface binding GPU timeout");
        }
        app.stop(true);
        if (app.failure != null) {
            throw new AssertionError(app.failure);
        }
        System.out.println("SKIN_SURFACE_LIVE_BINDING_GPU_PASS dark=" + app.dark + " thin=" + app.thin);
        System.exit(0);
    }

    @Override
    public void simpleInitApp() {
        try {
            flyCam.setEnabled(false);
            setDisplayFps(false);
            setDisplayStatView(false);
            getSceneServices().setEnabled(false);
            cam.setLocation(new Vector3f(.5f, .5f, 2));
            cam.lookAt(new Vector3f(.5f, .5f, 0), Vector3f.UNIT_Y);
            directory = Files.createTempDirectory(Path.of("build/skin-lut-check"), "binding-gpu-");
            assetManager.registerLocator(directory.toString(), FileLocator.class);
            SkinLutExport.write(directory, "Skin/Target", SkinLutGenerator.generate(
                    SkinLutGenerator.referenceProfile(), 17, 9, 1, 32), SkinLutGenerator.referenceProfile(), 32, false);
            live = assetManager.loadMaterial("Skin/Target.j3m");
            live.clearParam("SkinLut");
            live.setBoolean("EnvironmentLighting", false);
            live.setBoolean("SkinTransmission", false);
            live.setFloat("Roughness", .73f);
            Geometry subject = new Geometry("Backlit surface", new Quad(1, 1));
            subject.setMaterial(live);
            maps = SkinSurfaceBaker.bake(subject, "Skin/Target.j3m", 16, 10, 20);
            rootNode.attachChild(subject);
            rootNode.addLight(new DirectionalLight(Vector3f.UNIT_Z, ColorRGBA.White));
            java.util.Arrays.fill(maps.covered, true);
            java.util.Arrays.fill(maps.transmissionCoverage, 1f);
            java.util.Arrays.fill(maps.curvature, .05f);
        } catch (Throwable error) {
            handleError("Initialize", error);
        }
    }

    private void export(float thickness, boolean replace) throws Exception {
        java.util.Arrays.fill(maps.thickness, thickness);
        SkinSurfaceAssets.write(directory, "Skin/Surface", "Skin/Target.j3m", maps, 10, 20, 1, 4, replace);
        assetManager.clearCache();
        Material saved = assetManager.loadMaterial("Skin/Target.j3m");
        SkinMaterialUpdates.apply(live, saved);
        if (Math.abs((Float) live.getParamValue("Roughness") - .73f) > .001f) {
            throw new AssertionError("Live surface update erased roughness override");
        }
    }

    @Override
    public void simpleRender(com.jme3.renderer.RenderManager manager) {
        if (done.getCount() == 0 || ++frame % 5 != 0) {
            return;
        }
        try {
            ByteBuffer pixels = BufferUtils.createByteBuffer(128 * 128 * 4);
            renderer.readFrameBuffer(viewPort.getOutputFrameBuffer(), pixels);
            float red = (pixels.get((64 * 128 + 64) * 4) & 255) / 255f;
            if (phase == 0) {
                dark = red;
                export(2, false);
            } else if (phase == 1) {
                thin = red;
                if (thin <= dark + .08f) {
                    throw new AssertionError("Thin surface did not become translucent: " + dark + " -> " + thin);
                }
                export(20, true);
            } else if (phase == 2) {
                if (red >= thin * .5f) {
                    throw new AssertionError("Replacement DDS remained stale: " + thin + " -> " + red);
                }
                java.util.Arrays.fill(maps.transmissionCoverage, 0f);
                export(2, true);
            } else {
                if (red > dark + .01f) {
                    throw new AssertionError("Unresolved thin sample leaked transmission: " + red);
                }
                done.countDown();
            }
            phase++;
        } catch (Throwable error) {
            handleError("Render", error);
        }
    }

    @Override
    public void handleError(String message, Throwable error) {
        failure = error;
        done.countDown();
    }
}
