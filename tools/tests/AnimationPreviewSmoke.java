package com.jme3.gde.core.assets;

import com.jme3.anim.AnimComposer;
import com.jme3.anim.graph.AnimationGraph;
import com.jme3.anim.graph.AnimationGraphPlayer;
import com.jme3.gde.core.editor.SceneApplication;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.shape.Box;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Exercises the actual editor preview class and OpenGL context without showing a window. */
public final class AnimationPreviewSmoke {
    public static void main(String[] args) throws Exception {
        Class<?> type = Class.forName("com.jme3.gde.core.assets.AnimationEditorTopComponent$Preview");
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        SceneApplication app = (SceneApplication) constructor.newInstance();
        javax.swing.JFrame[] window = new javax.swing.JFrame[1];
        try {
            app.start();
            app.enqueue(() -> null).get(30, TimeUnit.SECONDS);
            // Exercise the actual EDT publication/queued attachment path, not just FBO capture.
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                window[0] = new javax.swing.JFrame("Animation preview lifecycle test");
                window[0].setUndecorated(true);
                window[0].add(app.getPanel());
                window[0].setSize(360, 240);
                window[0].setLocation(-10000, -10000);
                window[0].setVisible(true);
            });
            app.enqueue(() -> null).get(30, TimeUnit.SECONDS);
            app.enqueue(() -> {
                if (!app.getPanel().isInitialized()) {
                    throw new AssertionError("Mounted AWT panel never initialized");
                }
                return null;
            }).get(30, TimeUnit.SECONDS);
            System.out.println("ANIMATION_MOUNTED_AWT_PANEL_PASS");
            app.enqueue(() -> {
                try {
                    if (!app.getRenderer().getCaps().contains(com.jme3.renderer.Caps.OpenGL33)) {
                        throw new AssertionError("Preview needs OpenGL 3.3");
                    }
                    Node original = AnimationEditorSmoke.model();
                    Geometry cube = new Geometry("Cube", new Box(0.3f, 0.3f, 0.3f));
                    Material material = new Material(app.getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
                    material.setColor("Color", ColorRGBA.Orange);
                    cube.setMaterial(material);
                    original.attachChild(cube);
                    Node copy = original.clone(true);
                    AnimationGraph graph = new AnimationGraph("Blend",
                            List.of(new AnimationGraph.Parameter("Speed", AnimationGraph.Type.FLOAT, 0)),
                            List.of(new AnimationGraph.State("Blend", "Speed", List.of("Idle", "Walk", "Run"),
                                    new float[]{0, 1, 2}, true, 0, 0)), List.of());
                    Method install = type.getDeclaredMethod("install", com.jme3.scene.Spatial.class, AnimationGraph.class);
                    install.setAccessible(true);
                    install.invoke(app, copy, graph);
                    Field field = type.getDeclaredField("player");
                    field.setAccessible(true);
                    AnimationGraphPlayer player = (AnimationGraphPlayer) field.get(app);
                    player.setFloat("Speed", 0.5f);
                    copy.updateLogicalState(0.1f);
                    if (Math.abs(copy.getLocalTranslation().x - 0.5f) > 0.001f
                            || original.getLocalTranslation().x != 0) {
                        throw new AssertionError("Preview must animate the clone only");
                    }
                    app.getRootNode().updateGeometricState();
                    com.jme3.texture.FrameBuffer buffer = new com.jme3.texture.FrameBuffer(320, 240, 1);
                    buffer.setDepthTarget(com.jme3.texture.FrameBuffer.FrameBufferTarget.newTarget(com.jme3.texture.Image.Format.Depth));
                    buffer.addColorTarget(com.jme3.texture.FrameBuffer.FrameBufferTarget.newTarget(com.jme3.texture.Image.Format.RGBA8));
                    com.jme3.renderer.Camera camera = app.getCamera().clone();
                    camera.resize(320, 240, true);
                    var view = app.getRenderManager().createPreView("Animation test", camera);
                    view.setOutputFrameBuffer(buffer);
                    view.setClearFlags(true, true, true);
                    view.attachScene(app.getRootNode());
                    app.getRenderManager().renderViewPort(view, 0.016f);
                    java.nio.ByteBuffer pixels = com.jme3.util.BufferUtils.createByteBuffer(320 * 240 * 4);
                    app.getRenderer().readFrameBuffer(buffer, pixels);
                    boolean colored = false;
                    for (int i = 0; i < pixels.capacity(); i += 4) {
                        if ((pixels.get(i) & 255) > 100 || (pixels.get(i + 2) & 255) > 100) {
                            colored = true;
                            break;
                        }
                    }
                    if (!colored) {
                        throw new AssertionError("Preview rendered no visible model pixels");
                    }
                    com.jme3.scene.Spatial skinned = app.getAssetManager().loadModel("Models/Jaime/Jaime.j3o");
                    com.jme3.anim.util.AnimMigrationUtils.migrate(skinned);
                    com.jme3.scene.Spatial skinnedOwner = AnimationEditorSupport.owners(skinned).get(0);
                    AnimComposer skinComposer = skinnedOwner.getControl(AnimComposer.class);
                    String clipName = skinComposer.getAnimClipsNames().iterator().next();
                    AnimationGraph skinGraph = new AnimationGraph("Pose", List.of(),
                            List.of(new AnimationGraph.State("Pose", "", List.of(clipName),
                                    new float[]{0}, true, 0, 0)), List.of());
                    com.jme3.scene.Spatial skinCopy = skinnedOwner.clone(true);
                    var sourceArmature = skinnedOwner.getControl(com.jme3.anim.SkinningControl.class).getArmature();
                    var copyArmature = skinCopy.getControl(com.jme3.anim.SkinningControl.class).getArmature();
                    com.jme3.math.Transform[] originals = new com.jme3.math.Transform[sourceArmature.getJointCount()];
                    for (int i = 0; i < originals.length; i++) {
                        originals[i] = sourceArmature.getJoint(i).getLocalTransform().clone();
                    }
                    install.invoke(app, skinCopy, skinGraph);
                    skinCopy.updateLogicalState(0.37f);
                    app.getRootNode().updateGeometricState();
                    boolean jointMoved = false;
                    for (int i = 0; i < originals.length; i++) {
                        if (!originals[i].equals(sourceArmature.getJoint(i).getLocalTransform())) {
                            throw new AssertionError("Preview changed the original skeleton");
                        }
                        jointMoved |= !originals[i].equals(copyArmature.getJoint(i).getLocalTransform());
                    }
                    if (!jointMoved) {
                        throw new AssertionError("Skinned preview did not animate joints");
                    }
                    camera.copyFrom(app.getCamera());
                    camera.resize(320, 240, true);
                    app.getRenderManager().renderViewPort(view, 0.016f);
                    pixels.clear();
                    app.getRenderer().readFrameBuffer(buffer, pixels);
                    java.awt.image.BufferedImage capture = new java.awt.image.BufferedImage(320, 240,
                            java.awt.image.BufferedImage.TYPE_4BYTE_ABGR);
                    com.jme3.util.Screenshots.convertScreenShot(pixels, capture);
                    javax.imageio.ImageIO.write(capture, "png", new java.io.File("build/animation-smoke/skinned-preview.png"));
                    System.out.println("ANIMATION_SKINNED_MODEL_CLONE_RENDER_PASS joints=" + originals.length);
                    Method fullInstall = type.getDeclaredMethod("install", com.jme3.scene.Spatial.class,
                            com.jme3.scene.Spatial.class, AnimationGraph.class);
                    fullInstall.setAccessible(true);
                    Node hierarchy = new Node("Complete model");
                    Node animationOwner = AnimationEditorSmoke.model();
                    hierarchy.attachChild(animationOwner);
                    hierarchy.attachChild(cube.clone(true));
                    var fullCopy = AnimationEditorSupport.cloneForPreview(hierarchy, animationOwner);
                    fullInstall.invoke(app, fullCopy.model(), fullCopy.owner(), graph);
                    if (((Node) fullCopy.model()).getQuantity() != 2 || fullCopy.owner() == animationOwner) {
                        throw new AssertionError("Preview must include sibling geometry and a cloned controller");
                    }
                    camera.copyFrom(app.getCamera());
                    camera.resize(320, 240, true);
                    app.getRenderManager().renderViewPort(view, 0.016f);
                    pixels.clear();
                    app.getRenderer().readFrameBuffer(buffer, pixels);
                    int visible = 0;
                    for (int i = 0; i < pixels.capacity(); i += 4) {
                        if ((pixels.get(i) & 255) > 100) {
                            visible++;
                        }
                    }
                    if (visible < 20) {
                        throw new AssertionError("Sibling geometry is missing from preview");
                    }
                    System.out.println("ANIMATION_NESTED_COMPOSER_FULL_MODEL_PASS pixels=" + visible);
                    if (args.length == 2) {
                        app.getAssetManager().registerLocator(args[0], com.jme3.asset.plugins.FileLocator.class);
                        var asset = app.getAssetManager().loadModel(args[1]);
                        var assetOwner = AnimationEditorSupport.owners(asset).get(0);
                        String assetClip = assetOwner.getControl(AnimComposer.class).getAnimClipsNames().iterator().next();
                        AnimationGraph assetGraph = new AnimationGraph("Preview", List.of(),
                                List.of(new AnimationGraph.State("Preview", "", List.of(assetClip),
                                        new float[]{0}, true, 0, 0)), List.of());
                        var assetCopy = AnimationEditorSupport.cloneForPreview(asset, assetOwner);
                        fullInstall.invoke(app, assetCopy.model(), assetCopy.owner(), assetGraph);
                        assetCopy.model().updateLogicalState(0.1f);
                        app.getRootNode().updateGeometricState();
                        camera.copyFrom(app.getCamera());
                        camera.resize(320, 240, true);
                        app.getRenderManager().renderViewPort(view, 0.016f);
                        pixels.clear();
                        app.getRenderer().readFrameBuffer(buffer, pixels);
                        com.jme3.util.Screenshots.convertScreenShot(pixels, capture);
                        javax.imageio.ImageIO.write(capture, "png", new java.io.File("build/animation-smoke/asset-preview.png"));
                        System.out.println("ANIMATION_EXTERNAL_ASSET_PREVIEW_CAPTURE owner=" + assetOwner.getName());
                    }
                    app.getRenderManager().removePreView(view);
                    app.getRenderer().deleteFrameBuffer(buffer);
                    player.close();
                    System.out.println("ANIMATION_PREVIEW_OPENGL_CLONE_PIXELS_PASS");
                    return null;
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            }).get(55, TimeUnit.SECONDS);
        } finally {
            if (app.getContext() != null) {
                app.stop(true);
            }
            if (window[0] != null) {
                javax.swing.SwingUtilities.invokeAndWait(window[0]::dispose);
            }
        }
        System.exit(0);
    }
}
