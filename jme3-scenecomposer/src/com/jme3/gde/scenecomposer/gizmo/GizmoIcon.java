package com.jme3.gde.scenecomposer.gizmo;

import com.jme3.asset.AssetManager;
import com.jme3.gde.scenecomposer.gizmo.light.ScreenSizeControl;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Quad;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.plugins.AWTLoader;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import org.openide.util.ImageUtilities;

/** Centered tool icons, rasterized once for the SDK viewport. */
public final class GizmoIcon {
    private static final Map<String, Texture> textures = new HashMap<>();
    private static final String ICON_FOLDER = "com/jme3/gde/scenecomposer/gizmo/icons/";

    private GizmoIcon() {
    }

    /** Creates a 36-pixel icon. Attach it to a camera-facing editor node on the render thread. */
    public static Geometry create(AssetManager assets, String name, String file, String fallback, boolean depthTest) {
        var icon = new Geometry(name, new Quad(1f, 1f));
        icon.move(-.5f, -.5f, 0f);
        icon.addControl(new ScreenSizeControl(36f, 1f));

        var material = new Material(assets, "Common/MatDefs/Misc/Unshaded.j3md");
        Texture texture = textures.computeIfAbsent(file, GizmoIcon::loadTexture);
        material.setTexture("ColorMap", texture == null ? assets.loadTexture(fallback) : texture);
        material.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        material.getAdditionalRenderState().setDepthTest(depthTest);
        material.getAdditionalRenderState().setDepthWrite(false);
        icon.setMaterial(material);
        icon.setQueueBucket(RenderQueue.Bucket.Transparent);
        return icon;
    }

    private static Texture loadTexture(String file) {
        Image icon = ImageUtilities.loadImage(ICON_FOLDER + file);
        if (icon == null) {
            return null;
        }
        var image = new BufferedImage(96, 96, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            graphics.drawImage(icon, 0, 0, image.getWidth(), image.getHeight(), null);
        } finally {
            graphics.dispose();
        }
        return new Texture2D(new AWTLoader().load(image, true));
    }
}
