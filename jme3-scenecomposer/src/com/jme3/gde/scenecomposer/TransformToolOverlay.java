package com.jme3.gde.scenecomposer;

import com.jme3.gde.core.scene.SceneApplication;
import com.jme3.input.KeyInput;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.renderer.Camera;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.shape.Quad;
import com.jme3.font.BitmapText;

/**
 * Render-thread-only viewport HUD, drawn by jME so it also covers the heavyweight
 * AWT canvas correctly. Compact 33px buttons scale with the framebuffer DPI.
 * Clicks are consumed before scene picking; Q/W/E/R and inspector buttons use the
 * same controller state. Detaching the camera removes this HUD.
 */
final class TransformToolOverlay {
    private static final int[] KEYS = {KeyInput.KEY_Q, KeyInput.KEY_W, KeyInput.KEY_E, KeyInput.KEY_R};
    private final Node root = new Node("Editor transform HUD");
    private final Material[] backgrounds = new Material[KEYS.length];
    private final SceneApplication app;
    private final BitmapText rotationLabel;
    private float scale = 1;
    private float bottom;
    private int lastSelected = Integer.MIN_VALUE;

    TransformToolOverlay(SceneApplication app) {
        this.app = app;
        root.setQueueBucket(RenderQueue.Bucket.Gui);
        root.setCullHint(com.jme3.scene.Spatial.CullHint.Never);
        for (int i = 0; i < KEYS.length; i++) {
            Geometry background = new Geometry("Transform button", new Quad(42, 44));
            backgrounds[i] = material(new ColorRGBA(.1f, .12f, .15f, .78f));
            backgrounds[i].setTexture("ColorMap", roundedBackground());
            background.setMaterial(backgrounds[i]);
            background.setLocalTranslation(i * 46, 0, 0);
            root.attachChild(background);
            float[] points;
            if (i == 0) {
                points = new float[]{13,39,13,19,13,19,19,25,19,25,24,16,
                    24,16,28,18,28,18,23,27,23,27,31,27,31,27,13,39};
            } else if (i == 1) {
                points = new float[]{10,28,32,28,21,17,21,39,10,28,15,33,10,28,15,23,
                    32,28,27,33,32,28,27,23,21,39,16,34,21,39,26,34,21,17,16,22,21,17,26,22};
            } else if (i == 2) {
                points = new float[56];
                for (int n = 0; n < 12; n++) {
                    double a = Math.toRadians(35 + n * 25);
                    double b = Math.toRadians(35 + (n + 1) * 25);
                    points[n * 4] = 21 + (float) Math.cos(a) * 10;
                    points[n * 4 + 1] = 28 + (float) Math.sin(a) * 10;
                    points[n * 4 + 2] = 21 + (float) Math.cos(b) * 10;
                    points[n * 4 + 3] = 28 + (float) Math.sin(b) * 10;
                }
                float[] arrow = {29,34,30,26,29,34,22,33};
                System.arraycopy(arrow, 0, points, 48, 8);
            } else {
                points = new float[]{11,18,31,38,21,38,31,38,31,38,31,28,
                    11,18,19,18,11,18,11,26};
            }
            // Supersampled native vector strokes stay crisp at fractional desktop DPI.
            java.awt.image.BufferedImage pixels = new java.awt.image.BufferedImage(168, 176,
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D graphics = pixels.createGraphics();
            graphics.scale(4, 4);
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(java.awt.Color.WHITE);
            graphics.setStroke(new java.awt.BasicStroke(1.4f, java.awt.BasicStroke.CAP_ROUND,
                    java.awt.BasicStroke.JOIN_ROUND));
            for (int n = 0; n < points.length; n += 4) {
                graphics.draw(new java.awt.geom.Line2D.Float(points[n], 44 - points[n + 1],
                        points[n + 2], 44 - points[n + 3]));
            }
            graphics.dispose();
            Geometry icon = new Geometry("Transform icon", new Quad(42, 44));
            Material ink = material(new ColorRGBA(.88f, .93f, 1, .95f));
            ink.setTexture("ColorMap", texture(pixels));
            icon.setMaterial(ink);
            icon.setLocalTranslation(i * 46, 0, 1);
            root.attachChild(icon);
            BitmapText label = new BitmapText(app.getAssetManager().loadFont("Interface/Fonts/Default.fnt"));
            label.setText(new String[]{"Q", "W", "E", "R"}[i]);
            label.setSize(13);
            label.setLocalTranslation(i * 46 + 17, 13, 2);
            root.attachChild(label);
        }
        rotationLabel = new BitmapText(app.getAssetManager().loadFont("Interface/Fonts/Default.fnt"));
        rotationLabel.setSize(18);
        rotationLabel.setColor(new ColorRGBA(1, .85f, .4f, 1));
        rotationLabel.setLocalTranslation(0, -10, 2);
        root.attachChild(rotationLabel);
        app.getGuiNode().attachChild(root);
    }

    private com.jme3.texture.Texture2D roundedBackground() {
        java.awt.image.BufferedImage pixels = new java.awt.image.BufferedImage(168, 176,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D graphics = pixels.createGraphics();
        graphics.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setColor(java.awt.Color.WHITE);
        graphics.fillRoundRect(0, 0, 168, 176, 24, 24);
        graphics.dispose();
        return texture(pixels);
    }

    private com.jme3.texture.Texture2D texture(java.awt.image.BufferedImage pixels) {
        var texture = new com.jme3.texture.Texture2D(new com.jme3.texture.plugins.AWTLoader().load(pixels, true));
        texture.setMagFilter(com.jme3.texture.Texture.MagFilter.Bilinear);
        texture.setMinFilter(com.jme3.texture.Texture.MinFilter.BilinearNoMipMaps);
        return texture;
    }

    private Material material(ColorRGBA color) {
        Material result = new Material(app.getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
        result.setColor("Color", color);
        result.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        result.getAdditionalRenderState().setDepthTest(false);
        result.getAdditionalRenderState().setDepthWrite(false);
        return result;
    }

    void update(Camera camera, int selected, float rotationDegrees) {
        rotationLabel.setText(Float.isNaN(rotationDegrees) ? ""
                : String.format(java.util.Locale.ROOT, "%+.1f\u00b0", rotationDegrees));
        root.setCullHint(app.isEditorHelpersVisible()
                ? com.jme3.scene.Spatial.CullHint.Never : com.jme3.scene.Spatial.CullHint.Always);
        int logicalWidth = app.getMainPanel().getWidth();
        scale = .75f * (logicalWidth > 0 ? (float) camera.getWidth() / logicalWidth : 1);
        bottom = camera.getHeight() - 56 * scale;
        root.setLocalScale(scale);
        root.setLocalTranslation(12 * scale, bottom, 0);
        if (lastSelected == selected) {
            return;
        }
        lastSelected = selected;
        for (int i = 0; i < KEYS.length; i++) {
            backgrounds[i].setColor("Color", selected == KEYS[i]
                    ? new ColorRGBA(.12f, .40f, .72f, .86f) : new ColorRGBA(.1f, .12f, .15f, .72f));
        }
    }

    int hit(float x, float y) {
        if (!app.isEditorHelpersVisible()) {
            return -1;
        }
        float localX = x / scale - 12;
        float localY = (y - bottom) / scale;
        int index = (int) (localX / 46);
        if (localX < 0 || localY < 0 || localY >= 44 || index >= KEYS.length || localX % 46 >= 42) {
            return -1;
        }
        return KEYS[index];
    }

    void close() {
        root.removeFromParent();
    }
}
