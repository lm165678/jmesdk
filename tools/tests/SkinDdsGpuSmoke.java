package com.jme3.gde.texturetools;

import com.jme3.app.SimpleApplication;
import com.jme3.asset.TextureKey;
import com.jme3.asset.plugins.FileLocator;
import com.jme3.material.Material;
import com.jme3.material.skin.SkinLutGenerator;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Quad;
import com.jme3.system.*;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.Arrays;
import java.util.concurrent.*;

public final class SkinDdsGpuSmoke extends SimpleApplication {
    final CountDownLatch done = new CountDownLatch(1);
    volatile Throwable failure;
    Material material;
    com.jme3.texture.Texture reference;
    com.jme3.texture.Texture loaded;
    byte[] pixels;
    int frames;

    public static void main(String[] args) throws Exception {
        NativeLibraryLoader.setCustomExtractionFolder(Files.createTempDirectory("skin-dds-native-").toString());
        var app = new SkinDdsGpuSmoke();
        app.setShowSettings(false);
        app.setDisplayFps(false);
        app.setDisplayStatView(false);
        var settings = new AppSettings(true);
        settings.setRenderer(AppSettings.LWJGL_OPENGL43);
        settings.setResolution(192, 128);
        settings.setAudioRenderer(null);
        settings.setGammaCorrection(true);
        settings.setFrameRate(60);
        app.setSettings(settings);
        app.start(JmeContext.Type.OffscreenSurface);
        if (!app.done.await(40, TimeUnit.SECONDS)) {
            throw new AssertionError("GPU timeout");
        }
        app.stop(true);
        if (app.failure != null) {
            throw new AssertionError(app.failure);
        }
        System.out.println("SKIN_RGBA16F_DDS_GPU_PIXELS_IDENTICAL");
        System.exit(0);
    }
    @Override public void simpleInitApp() {
        try {
            flyCam.setEnabled(false);
            var lut = SkinLutGenerator.generate(SkinLutGenerator.referenceProfile(), 33, 17, 1, 32);
            reference = lut.createTexture();
            Path directory = Files.createTempDirectory("skin-dds-gpu-");
            Files.write(directory.resolve("lut.dds"), TextureToolchain.linearHalfDds(reference.getImage()));
            assetManager.registerLocator(directory.toString(), FileLocator.class);
            TextureKey key = new TextureKey("lut.dds", false);
            key.setGenerateMips(false);
            loaded = assetManager.loadTexture(key);
            loaded.getImage().setColorSpace(com.jme3.texture.image.ColorSpace.Linear);
            loaded.setMinFilter(reference.getMinFilter());
            loaded.setMagFilter(reference.getMagFilter());
            loaded.setWrap(com.jme3.texture.Texture.WrapMode.EdgeClamp);
            material = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            material.setTexture("ColorMap", reference);
            Geometry quad = new Geometry("LUT", new Quad(192, 128));
            quad.setMaterial(material);
            guiNode.attachChild(quad);
        } catch (Throwable error) {
            handleError("Initialize", error);
        }
    }
    @Override public void simpleRender(com.jme3.renderer.RenderManager manager) {
        if (done.getCount() == 0 || ++frames < 4) {
            return;
        }
        try {
            ByteBuffer data = BufferUtils.createByteBuffer(192 * 128 * 4);
            renderer.readFrameBuffer(viewPort.getOutputFrameBuffer(), data);
            byte[] next = new byte[data.remaining()];
            data.get(next);
            if (pixels == null) {
                int low = 255;
                int high = 0;
                for (int i = 0; i < next.length; i += 4) {
                    low = Math.min(low, next[i] & 255);
                    high = Math.max(high, next[i] & 255);
                }
                if (high - low < 80) {
                    throw new AssertionError("Reference LUT is not visible in the captured framebuffer");
                }
                pixels = next;
                material.setTexture("ColorMap", loaded);
            } else {
                if (!Arrays.equals(pixels, next)) {
                    throw new AssertionError("DDS RGB/orientation/render mismatch");
                }
                done.countDown();
            }
        } catch (Throwable error) {
            handleError("Render", error);
        }
    }
    @Override public void handleError(String message, Throwable error) {
        failure = error;
        done.countDown();
    }
}
