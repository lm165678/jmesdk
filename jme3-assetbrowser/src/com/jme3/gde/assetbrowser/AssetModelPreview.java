package com.jme3.gde.assetbrowser;

import com.jme3.anim.AnimComposer;
import com.jme3.anim.MorphControl;
import com.jme3.anim.SkinningControl;
import com.jme3.animation.AnimControl;
import com.jme3.animation.SkeletonControl;
import com.jme3.bounding.BoundingBox;
import com.jme3.bounding.BoundingSphere;
import com.jme3.gde.core.assets.SkinnedPreviewBounds;
import com.jme3.gde.core.assets.ProjectAssetManager;
import com.jme3.gde.core.scene.SceneApplication;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.material.MatParamTexture;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.RenderPath;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.util.BufferUtils;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.TreeSet;

/** Owns the model and temporary render target on the SDK render thread. */
final class AssetModelPreview {
    private static final int SIZE = 384;
    private final RenderManager renderManager;
    private final Node scene = new Node("Asset browser preview");
    private final Camera camera = new Camera(SIZE, SIZE);
    private final ByteBuffer pixels = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
    private final List<AnimComposer> composers = new ArrayList<>();
    private final List<AnimControl> legacy = new ArrayList<>();
    final List<String> clips;
    final long memory;
    private final ViewPort view;
    private final FrameBuffer target;
    private String currentClip;
    private long lastFrame;

    static ProjectAssetManager isolated(ProjectAssetManager source) {
        ProjectAssetManager manager = new ProjectAssetManager(source.getAssetFolder());
        source.getClassLoaders().forEach(manager::addClassLoader);
        return manager;
    }

    AssetModelPreview(SceneApplication app, ProjectAssetManager source, String asset) {
        this(app.getRenderManager(), loadModel(source, asset));
    }

    private static Spatial loadModel(ProjectAssetManager source, String asset) {
        ProjectAssetManager manager = isolated(source);
        try {
            return manager.loadModel(asset);
        } finally {
            manager.clearCache();
        }
    }

    AssetModelPreview(RenderManager renderManager, Spatial model) {
        this.renderManager = renderManager;
        TreeSet<String> names = new TreeSet<>();
        model.depthFirstTraversal(spatial -> {
            for (int i = spatial.getNumControls() - 1; i >= 0; i--) {
                var control = spatial.getControl(i);
                if (control instanceof AnimComposer composer) {
                    composer.reset();
                    composers.add(composer);
                    names.addAll(composer.getAnimClipsNames());
                } else if (control instanceof AnimControl animation) {
                    animation.clearChannels();
                    legacy.add(animation);
                    names.addAll(animation.getAnimationNames());
                } else if (!(control instanceof SkinningControl || control instanceof MorphControl
                        || control instanceof SkeletonControl)) {
                    // Preview a private model without running application or physics controls.
                    spatial.removeControl(control);
                }
            }
        });
        clips = List.copyOf(names);
        memory = modelMemory(model);
        if (!clips.isEmpty()) {
            selectClip(clips.get(0));
            model.updateLogicalState(0);
        }
        var bounds = SkinnedPreviewBounds.fit(model);
        Vector3f center = bounds == null ? new Vector3f() : bounds.getCenter().clone();
        float radius = bounds instanceof BoundingBox box
                ? new Vector3f(box.getXExtent(), box.getYExtent(), box.getZExtent()).length()
                : bounds instanceof BoundingSphere sphere ? sphere.getRadius() : 1;
        float scale = Float.isFinite(radius) && radius > 1e-6f ? 1.35f / radius : 1f;
        Node normalized = new Node();
        normalized.setCullHint(Spatial.CullHint.Never);
        normalized.attachChild(model);
        normalized.setLocalScale(scale);
        normalized.setLocalTranslation(center.mult(-scale));
        scene.attachChild(normalized);
        scene.setCullHint(Spatial.CullHint.Never);
        scene.addLight(new AmbientLight(new ColorRGBA(.45f, .45f, .45f, 1)));
        scene.addLight(new DirectionalLight(new Vector3f(-1, -2, -1).normalizeLocal(), ColorRGBA.White));
        camera.setFrustumPerspective(40, 1, .05f, 100);
        view = renderManager.createPreView("Asset browser preview", camera);
        view.setEnabled(false);
        view.setRenderPath(RenderPath.Forward);
        view.setClearFlags(true, true, true);
        view.setBackgroundColor(new ColorRGBA(.045f, .055f, .07f, 1));
        target = new FrameBuffer(SIZE, SIZE, 1);
        target.setDepthBuffer(Image.Format.Depth);
        target.setColorBuffer(Image.Format.RGBA8);
        target.setSrgb(true);
        view.setOutputFrameBuffer(target);
        view.attachScene(scene);
    }

    private static long modelMemory(Spatial model) {
        var buffers = Collections.newSetFromMap(new IdentityHashMap<java.nio.Buffer, Boolean>());
        long[] total = {0};
        model.depthFirstTraversal(spatial -> {
            if (!(spatial instanceof Geometry geometry)) {
                return;
            }
            if (geometry.getMesh() != null) {
                for (var vertex : geometry.getMesh().getBufferList()) {
                    var data = vertex.getData();
                    if (data != null && buffers.add(data)) {
                        total[0] += (long) data.capacity() * vertex.getFormat().getComponentSize();
                    }
                }
            }
            if (geometry.getMaterial() != null) {
                for (var param : geometry.getMaterial().getParams()) {
                    if (param instanceof MatParamTexture texture && texture.getTextureValue() != null) {
                        var image = texture.getTextureValue().getImage();
                        if (image != null && image.getData() != null) {
                            for (ByteBuffer data : image.getData()) {
                                if (data != null && buffers.add(data)) {
                                    total[0] += data.capacity();
                                }
                            }
                        }
                    }
                }
            }
        });
        return total[0];
    }

    BufferedImage render(float yaw, float pitch, float distance, String clip, boolean playing) {
        boolean clipChanged = clip != null && !clip.equals(currentClip);
        if (clipChanged) {
            selectClip(clip);
        }
        for (AnimComposer composer : composers) {
            composer.setEnabled(playing || clipChanged);
        }
        for (AnimControl animation : legacy) {
            animation.setEnabled(playing || clipChanged);
        }
        long now = System.nanoTime();
        float elapsed = playing && lastFrame != 0 ? Math.min(.15f, (now - lastFrame) * 1e-9f) : 0f;
        lastFrame = playing ? now : 0;
        float horizontal = (float) Math.cos(pitch) * distance;
        camera.setLocation(new Vector3f((float) Math.sin(yaw) * horizontal,
                (float) Math.sin(pitch) * distance, (float) Math.cos(yaw) * horizontal));
        camera.lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);
        scene.updateLogicalState(elapsed);
        scene.updateGeometricState();

        var renderer = renderManager.getRenderer();
        Camera previousCamera = renderManager.getCurrentCamera();
        FrameBuffer previousBuffer = renderer.getCurrentFrameBuffer();
        view.setEnabled(true);
        try {
            renderManager.renderViewPort(view, elapsed);
            pixels.clear();
            renderer.readFrameBufferWithFormat(target, pixels, Image.Format.RGBA8);
        } finally {
            view.setEnabled(false);
            renderer.setFrameBuffer(previousBuffer);
            if (previousCamera != null) {
                previousCamera.onViewPortChange();
                renderManager.setCamera(previousCamera, false);
            }
        }
        BufferedImage result = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int i = (y * SIZE + x) * 4;
                row[x] = 0xff000000 | (pixels.get(i) & 255) << 16
                        | (pixels.get(i + 1) & 255) << 8 | (pixels.get(i + 2) & 255);
            }
            result.setRGB(0, SIZE - 1 - y, SIZE, 1, row, 0, SIZE);
        }
        return result;
    }

    private void selectClip(String clip) {
        currentClip = clip;
        for (AnimComposer composer : composers) {
            composer.reset();
            if (composer.hasAnimClip(clip)) {
                composer.setCurrentAction(clip);
            }
        }
        for (AnimControl animation : legacy) {
            animation.clearChannels();
            if (animation.getAnimationNames().contains(clip)) {
                animation.createChannel().setAnim(clip, 0);
            }
        }
        lastFrame = 0;
    }

    void close() {
        renderManager.removePreView(view);
        view.clearScenes();
        target.dispose();
    }
}
