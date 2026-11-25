package com.jme3.gde.materials;

import com.jme3.app.SimpleApplication;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.renderer.RenderManager;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Sphere;
import com.jme3.system.AppSettings;
import com.jme3.system.JmeContext;
import com.jme3.texture.Image;
import com.jme3.util.BufferUtils;
import java.awt.image.BufferedImage;
import java.awt.image.MultiResolutionImage;
import java.nio.ByteBuffer;

/** Isolated native render and 100/125/150/200 percent thumbnail presentation check. */
public final class MaterialPreviewDpiSmoke extends SimpleApplication {
    private int frame;

    public static void main(String[] args) {
        MaterialPreviewDpiSmoke app = new MaterialPreviewDpiSmoke();
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(AppSettings.LWJGL_OPENGL43);
        settings.setResolution(800, 800);
        settings.setAudioRenderer(null);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.start(JmeContext.Type.OffscreenSurface);
    }

    @Override
    public void simpleInitApp() {
        try {
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                javax.swing.JLabel label = new javax.swing.JLabel();
                label.setSize(200, 200);
                try {
                    var method = MaterialPreviewRenderer.class.getDeclaredMethod("previewRequest");
                    method.setAccessible(true);
                    MaterialPreviewRenderer legacy = new MaterialPreviewRenderer(label);
                    MaterialPreviewRenderer editor = new MaterialPreviewRenderer(label, true);
                    var legacyRequest = (com.jme3.gde.core.scene.PreviewRequest) method.invoke(legacy);
                    var editorRequest = (com.jme3.gde.core.scene.PreviewRequest) method.invoke(editor);
                    if (legacyRequest instanceof MaterialPreviewRenderer.MaterialImageRequest
                            || legacyRequest.getCameraRequest().getWidth() != 200
                            || !(editorRequest instanceof MaterialPreviewRenderer.MaterialImageRequest)
                            || editorRequest.getCameraRequest().getWidth() != 400) {
                        throw new AssertionError("J3M-only preview policy was not isolated");
                    }
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException(failure);
                }
            });
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
        setDisplayFps(false);
        setDisplayStatView(false);
        Geometry sphere = new Geometry("silhouette", new Sphere(64, 64, 1));
        Material material = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        material.setColor("Color", ColorRGBA.White);
        sphere.setMaterial(material);
        rootNode.attachChild(sphere);
        cam.setLocation(new com.jme3.math.Vector3f(0, 0, 4));
        cam.lookAt(com.jme3.math.Vector3f.ZERO, com.jme3.math.Vector3f.UNIT_Y);
    }

    @Override
    public void simpleRender(RenderManager manager) {
        if (++frame < 4) {
            return;
        }
        ByteBuffer pixels = BufferUtils.createByteBuffer(800 * 800 * 4);
        renderer.readFrameBufferWithFormat(viewPort.getOutputFrameBuffer(), pixels, Image.Format.RGBA8);
        BufferedImage image = new BufferedImage(800, 800, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 800; y++) {
            for (int x = 0; x < 800; x++) {
                int offset = ((799 - y) * 800 + x) * 4;
                int c = pixels.get(offset) & 255;
                image.setRGB(x, y, c * 0x010101);
            }
        }
        for (double scale : new double[]{1, 1.25, 1.5, 2}) {
            var request = new MaterialPreviewRenderer.MaterialImageRequest(this, null, 200, 200, scale, scale);
            if (request.getCameraRequest().getWidth() != (int) (400 * scale)) {
                throw new AssertionError("Wrong render resolution");
            }
            var icon = request.icon(image);
            if (icon.getIconWidth() != 200 || icon.getIconHeight() != 200) {
                throw new AssertionError("Icon changed logical layout");
            }
            var variants = (MultiResolutionImage) icon.getImage();
            BufferedImage physical = (BufferedImage) variants.getResolutionVariant(200 * scale, 200 * scale);
            if (physical.getWidth() != (int) (200 * scale)) {
                throw new AssertionError("Wrong device resolution");
            }
            int blended = 0;
            for (int y = 0; y < physical.getHeight(); y++) {
                for (int x = 0; x < physical.getWidth(); x++) {
                    int value = physical.getRGB(x, y) & 255;
                    if (value > 0 && value < 255) {
                        blended++;
                    }
                }
            }
            if (blended < 100) {
                throw new AssertionError("Silhouette has no antialiased coverage");
            }
            System.out.println("DPI " + scale + " PASS edge pixels=" + blended);
        }
        System.out.println("MATERIAL_PREVIEW_DPI_PASS");
        stop();
    }

    @Override
    public void handleError(String message, Throwable failure) {
        failure.printStackTrace();
        System.exit(1);
    }
}
