package com.jme3.gde.scenecomposer;

import com.jme3.gde.core.scene.SceneApplication;
import com.jme3.gde.core.sceneexplorer.nodes.JmeSpotLight;
import com.jme3.gde.scenecomposer.gizmo.GizmoIcon;
import com.jme3.gde.scenecomposer.gizmo.light.LightGizmoFactory;
import com.jme3.light.DirectionalLight;
import com.jme3.light.Light;
import com.jme3.light.SpotLight;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderPath;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.shape.Box;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.util.BufferUtils;
import java.nio.ByteBuffer;
import java.util.List;

/** Uses live OpenGL readback to check silhouette coverage and editor icon size. */
public final class SelectionOutlineSmoke {
    public static void verify(SceneApplication app) throws Exception {
        var camera = new Camera(800, 600);
        camera.setFrustumPerspective(45f, 4f / 3f, .1f, 100f);
        var buffer = new FrameBuffer(800, 600, 1);
        buffer.setDepthTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.Depth));
        buffer.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.RGBA8));
        var scene = new ViewPort("Outline pixels", camera);
        scene.setRenderPath(RenderPath.Forward);
        scene.setClearFlags(true, true, false);
        scene.setOutputFrameBuffer(buffer);
        var tools = new ViewPort("Outline tools", camera);
        tools.setRenderPath(RenderPath.Forward);
        tools.setClearFlags(false, false, false);
        tools.setOutputFrameBuffer(buffer);
        var box = geometry(app, .8f);
        scene.attachScene(box);
        var outline = new SelectionHighlight(app.getAssetManager(), tools);
        try {
            int near = measure(app, scene, tools, outline, box, 5f, buffer);
            int far = measure(app, scene, tools, outline, box, 11f, buffer);
            check(Math.abs(near - far) <= 1, "Outline width changed with camera distance");
            var blocker = geometry(app, 2f);
            blocker.setLocalTranslation(0f, 0f, 3f);
            blocker.updateGeometricState();
            scene.attachScene(blocker);
            check(changedPixels(app, scene, tools, outline, box, buffer) == 0, "Outline showed through an occluder");
            verifyIcons(app, scene, camera);
            verifyIntensity(app, scene, buffer);
        } finally {
            outline.close();
            app.getRenderer().setFrameBuffer(null);
            app.getRenderer().deleteFrameBuffer(buffer);
        }
        System.out.println("SELECTION_OUTLINE_AND_ICONS_AWT_PASS");
    }

    public static void verifySpotProperties() throws Exception {
        var spot = new SpotLight();
        spot.setSpotOuterAngle((float) Math.toRadians(30));
        var properties = new JmeSpotLight(new Node("Light owner"), spot).getPropertySets();
        boolean hasDegrees = false;
        boolean hasIntensity = false;
        for (var propertySet : properties) {
            for (var property : propertySet.getProperties()) {
                if (property instanceof com.jme3.gde.core.properties.SceneExplorerProperty<?> sceneProperty) {
                    sceneProperty.syncValue();
                }
                if (property.getName().equals("Spot Outer Angle (°)")) {
                    hasDegrees = Math.abs((Float) property.getValue() - 30f) < .001f;
                } else if (property.getName().equals("Intensity")) {
                    hasIntensity = (Float) property.getValue() == 1f;
                }
            }
        }
        check(hasDegrees && hasIntensity, "Spotlight inspector angle/intensity properties");
        var adapter = JmeSpotLight.ConeAngles.class.getDeclaredConstructor(SpotLight.class);
        adapter.setAccessible(true);
        var angles = adapter.newInstance(spot);
        angles.setInnerDegrees(12.5f);
        angles.setOuterDegrees(40f);
        check(Math.abs(spot.getSpotInnerAngle() - Math.toRadians(12.5f)) < .0001
                && Math.abs(spot.getSpotOuterAngle() - Math.toRadians(40f)) < .0001, "Angle edit did not convert to radians");
        System.out.println("LIGHT_INSPECTOR_PASS");
    }

    private static void verifyIntensity(SceneApplication app, ViewPort scene, FrameBuffer output) {
        var root = new Node("Lighting comparison");
        var box = new Geometry("Lit box", new Box(.8f, .8f, .8f));
        var material = new Material(app.getAssetManager(), "Common/MatDefs/Light/PBRLighting.j3md");
        material.setColor("BaseColor", ColorRGBA.White);
        box.setMaterial(material);
        root.attachChild(box);
        var base = new ColorRGBA(.28f, .14f, .07f, 1f);
        var light = new DirectionalLight(new Vector3f(0f, 0f, -1f), base);
        light.setCastShadow(false);
        root.addLight(light);
        scene.clearScenes();
        scene.attachScene(root);
        cameraAt(scene.getCamera(), 5f);
        for (var path : new RenderPath[]{RenderPath.Forward, RenderPath.Deferred}) {
            scene.setRenderPath(path);
            light.setIntensity(1f);
            light.setColor(base);
            int initial = centerPixel(app, scene, output, root);
            light.setIntensity(3f);
            int scaled = centerPixel(app, scene, output, root);
            light.setIntensity(1f);
            light.setColor(new ColorRGBA(.84f, .42f, .21f, 1f));
            int baked = centerPixel(app, scene, output, root);
            check(initial != scaled && scaled == baked, path + " intensity did not match scaled RGB");
        }
        System.out.println("LIGHT_INTENSITY_GPU_PASS");
    }

    private static int centerPixel(SceneApplication app, ViewPort scene, FrameBuffer output, Node root) {
        root.updateGeometricState();
        app.getRenderManager().renderViewPort(scene, 0f);
        return read(app, output).getInt((output.getWidth() * output.getHeight() / 2 + output.getWidth() / 2) * 4);
    }

    private static Geometry geometry(SceneApplication app, float size) {
        var box = new Geometry("Box", new Box(size, size, size));
        var material = new Material(app.getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
        material.setColor("Color", new ColorRGBA(.1f, .2f, .3f, 1f));
        box.setMaterial(material);
        box.updateGeometricState();
        return box;
    }

    private static int measure(SceneApplication app, ViewPort scene, ViewPort tools,
            SelectionHighlight outline, Geometry box, float distance, FrameBuffer buffer) {
        cameraAt(scene.getCamera(), distance);
        int edgePixels = changedPixels(app, scene, tools, outline, box, buffer);
        check(edgePixels >= 2 && edgePixels <= 4, "Expected a thin border on both edges, got " + edgePixels);
        return edgePixels;
    }

    private static int changedPixels(SceneApplication app, ViewPort scene, ViewPort tools,
            SelectionHighlight outline, Geometry box, FrameBuffer buffer) {
        scene.getScenes().forEach(com.jme3.scene.Spatial::updateGeometricState);
        app.getRenderManager().renderViewPort(scene, 0f);
        var original = read(app, buffer);
        outline.update(List.of(box));
        app.getRenderManager().renderViewPort(tools, 0f);
        var highlighted = read(app, buffer);
        int row = buffer.getWidth() * buffer.getHeight() / 2;
        check(original.getInt((row + 400) * 4) == highlighted.getInt((row + 400) * 4), "Model surface was tinted");
        int count = 0;
        for (int x = 0; x < buffer.getWidth(); x++) {
            int pixel = (row + x) * 4;
            if (original.getInt(pixel) != highlighted.getInt(pixel)) {
                count++;
            }
        }
        return count;
    }

    private static ByteBuffer read(SceneApplication app, FrameBuffer buffer) {
        var pixels = BufferUtils.createByteBuffer(buffer.getWidth() * buffer.getHeight() * 4);
        app.getRenderer().readFrameBufferWithFormat(buffer, pixels, Image.Format.RGBA8);
        return pixels;
    }

    private static void verifyIcons(SceneApplication app, ViewPort viewport, Camera camera) throws Exception {
        var assets = app.getAssetManager();
        var audio = GizmoIcon.create(assets, "Audio source", "volume-2.svg", "missing-audio-marker.gif", true);
        var light = GizmoIcon.create(assets, "Light source", "sun.svg", "missing-light-marker.png", false);
        check(audio.getMaterial().getTextureParam("ColorMap").getTextureValue().getImage().getWidth() == 96,
                "Audio SVG texture failed");
        check(light.getMaterial().getTextureParam("ColorMap").getTextureValue().getImage().getWidth() == 96,
                "Light SVG texture failed");
        var method = LightGizmoFactory.class.getDeclaredMethod("createDirectionArrow",
                com.jme3.asset.AssetManager.class, Light.class);
        method.setAccessible(true);
        var arrow = (Node) method.invoke(null, assets, new DirectionalLight());
        var tip = (Geometry) arrow.getChild("tip");
        var positions = tip.getMesh().getFloatBuffer(com.jme3.scene.VertexBuffer.Type.Position);
        int point = 3 * 12;
        check(positions.get(point) == 0 && positions.get(point + 2) == 0
                && positions.get(point + 1) + tip.getLocalTranslation().y > 1f,
                "Arrow tip must point away from the light along its local +Y");
        var icons = new Node("Helpers");
        icons.attachChild(audio);
        icons.attachChild(light);
        icons.attachChild(arrow);
        icons.updateGeometricState();
        viewport.clearScenes();
        viewport.attachScene(icons);
        icons.updateGeometricState();
        for (float distance : new float[]{5f, 11f}) {
            cameraAt(camera, distance);
            icons.updateGeometricState();
            app.getRenderManager().renderViewPort(viewport, 0f);
            check(Math.abs(projectedHeight(camera, audio.getWorldScale().y) - 36f) < .05f, "Audio icon scales with distance");
            check(Math.abs(projectedHeight(camera, light.getWorldScale().y) - 36f) < .05f, "Light icon scales with distance");
            check(Math.abs(projectedHeight(camera, arrow.getWorldScale().y) - 108f) < .05f, "Arrow scales with distance");
        }
    }

    private static void cameraAt(Camera camera, float distance) {
        camera.setLocation(new Vector3f(0f, 0f, distance));
        camera.lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);
    }

    private static float projectedHeight(Camera camera, float worldHeight) {
        return camera.getScreenCoordinates(new Vector3f(0f, worldHeight, 0f)).y
                - camera.getScreenCoordinates(Vector3f.ZERO).y;
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }
}
