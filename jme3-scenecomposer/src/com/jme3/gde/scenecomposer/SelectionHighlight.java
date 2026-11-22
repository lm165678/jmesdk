package com.jme3.gde.scenecomposer;

import com.jme3.asset.AssetManager;
import com.jme3.material.MatParam;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector2f;
import com.jme3.post.SceneProcessor;
import com.jme3.profile.AppProfiler;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.SceneChangeListener;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Quad;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Marks the visible selection in a mask and draws its outer edge in screen pixels. */
final class SelectionHighlight implements SceneProcessor {
    private static final String MATERIAL = "com/jme3/gde/scenecomposer/SelectionHighlight.j3md";
    private static final List<String> DEFORMATION_PARAMS = List.of(
            "NumberOfBones", "BoneMatrices", "MorphWeights", "NumberOfMorphTargets", "NumberOfTargetsBuffers");
    private final AssetManager manager;
    private final ViewPort toolsView;
    private final Node overlay = new Node("Selection highlight");
    private final Geometry outline = new Geometry("Selection outline", new Quad(1f, 1f));
    private final Vector2f pixelStep = new Vector2f();
    private RenderManager renderManager;
    private FrameBuffer maskBuffer;
    private Texture2D mask;
    private final Map<Geometry, Geometry> proxies = new IdentityHashMap<>();
    private List<Spatial> selection = List.of();
    private boolean rebuild;
    private final SceneChangeListener changes = (spatial, change) -> {
        if (change == SceneChangeListener.Change.ATTACHED || change == SceneChangeListener.Change.DETACHING) {
            rebuild = true;
        }
    };

    SelectionHighlight(AssetManager manager, ViewPort toolsView) {
        this.manager = manager;
        this.toolsView = toolsView;
        overlay.setShadowMode(RenderQueue.ShadowMode.Off);
        var material = new Material(manager, "com/jme3/gde/scenecomposer/SelectionOutline.j3md");
        material.setColor("OutlineColor", new ColorRGBA(1f, .48f, .055f, .88f));
        outline.setMaterial(material);
        outline.updateGeometricState();
        toolsView.addProcessor(this);
    }

    void update(List<Spatial> next) {
        if (!next.equals(selection)) {
            for (Spatial previous : selection) {
                previous.removeSceneChangeListener(changes);
            }
            selection = List.copyOf(next);
            for (Spatial spatial : selection) {
                spatial.addSceneChangeListener(changes);
            }
            rebuild = true;
        }
        if (rebuild) {
            overlay.detachAllChildren();
            proxies.clear();
            for (Spatial spatial : selection) {
                spatial.depthFirstTraversal(child -> {
                    if (child instanceof Geometry geometry && geometry.getMesh() != null) {
                        proxies.computeIfAbsent(geometry, this::createProxy);
                    }
                });
            }
            rebuild = false;
        }
    }

    private Geometry createProxy(Geometry source) {
        var proxy = new Geometry(source.getName(), source.getMesh());
        var material = new Material(manager, MATERIAL);
        proxy.setMaterial(material);
        overlay.attachChild(proxy);
        return proxy;
    }

    private void updateProxy(Geometry source, Geometry proxy) {
        Material original = source.getMaterial();
        Mesh mesh = source.getMesh();
        RenderQueue.Bucket bucket = source.getQueueBucket();
        boolean visible = original != null && mesh != null && !source.isGrouped()
                && source.getCullHint() != Spatial.CullHint.Always
                && (bucket == RenderQueue.Bucket.Opaque || bucket == RenderQueue.Bucket.Transparent
                    || bucket == RenderQueue.Bucket.Translucent);
        Spatial.CullHint visibility = visible ? Spatial.CullHint.Inherit : Spatial.CullHint.Always;
        if (proxy.getLocalCullHint() != visibility) {
            proxy.setCullHint(visibility);
        }
        if (!visible) {
            return;
        }
        if (proxy.getMesh() != mesh) {
            proxy.setMesh(mesh);
        }
        if (proxy.getLodLevel() != source.getLodLevel()) {
            proxy.setLodLevel(source.getLodLevel());
        }
        proxy.setIgnoreTransform(source.isIgnoreTransform());
        if (!proxy.getLocalTransform().equals(source.getWorldTransform())) {
            proxy.setLocalTransform(source.getWorldTransform());
        }
        Material tint = proxy.getMaterial();
        tint.getAdditionalRenderState().setFaceCullMode(original.getAdditionalRenderState().getFaceCullMode());
        Float cutoff = original.getParamValue("AlphaDiscardThreshold");
        if (cutoff == null && (bucket != RenderQueue.Bucket.Opaque || original.isTransparent())) {
            cutoff = .06f;
        }
        Object map = original.getParamValue("BaseColorMap");
        if (!(map instanceof Texture)) {
            map = original.getParamValue("ColorMap");
        }
        if (!(map instanceof Texture)) {
            map = original.getParamValue("DiffuseMap");
        }
        setParam(tint, "ColorMap", cutoff != null && map instanceof Texture ? map : null);
        setParam(tint, "AlphaDiscardThreshold", cutoff);
        for (String name : DEFORMATION_PARAMS) {
            setParam(tint, name, original.getParamValue(name));
        }
        var overrides = source.getWorldMatParamOverrides();
        for (var current : proxy.getLocalMatParamOverrides().getArray()) {
            if (!overrides.contains(current)) {
                proxy.removeMatParamOverride(current);
            }
        }
        for (var override : overrides.getArray()) {
            if (DEFORMATION_PARAMS.contains(override.getName()) && !proxy.getLocalMatParamOverrides().contains(override)) {
                proxy.addMatParamOverride(override);
            }
        }
    }

    private static void setParam(Material material, String name, Object value) {
        MatParam current = material.getParam(name);
        if (value == null) {
            if (current != null) {
                material.clearParam(name);
            }
        } else if (current == null || !Objects.equals(current.getValue(), value)) {
            if (value instanceof Texture texture) {
                material.setTexture(name, texture);
            } else {
                material.setParam(name, value);
            }
        }
    }

    void close() {
        update(List.of());
        toolsView.removeProcessor(this);
    }

    @Override
    public void initialize(RenderManager renderManager, ViewPort viewport) {
        this.renderManager = renderManager;
    }

    @Override
    public void postQueue(RenderQueue queue) {
        if (proxies.isEmpty()) {
            return;
        }
        FrameBuffer output = toolsView.getOutputFrameBuffer();
        int width = toolsView.getRenderTargetWidth();
        int height = toolsView.getRenderTargetHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        Image.Format depth = output == null || output.getDepthTarget() == null
                ? Image.Format.Depth : output.getDepthTarget().getFormat();
        ensureMask(width, height, depth);
        proxies.forEach(this::updateProxy);
        overlay.updateGeometricState();
        var renderer = renderManager.getRenderer();
        renderer.clearClipRect();
        renderer.copyFrameBuffer(output, maskBuffer, false, true);
        try {
            renderer.setFrameBuffer(maskBuffer);
            renderer.setBackgroundColor(ColorRGBA.BlackNoAlpha);
            renderer.clearBuffers(true, false, false);
            for (Geometry proxy : proxies.values()) {
                if (proxy.getCullHint() != Spatial.CullHint.Always) {
                    renderManager.renderGeometry(proxy);
                }
            }
        } finally {
            renderer.setFrameBuffer(output);
            renderer.setBackgroundColor(toolsView.getBackgroundColor());
            toolsView.getCamera().onViewPortChange();
            renderManager.setCamera(toolsView.getCamera(), false, width, height);
        }
        var panel = com.jme3.gde.core.scene.SceneApplication.getApplication().getMainPanel();
        float logicalWidth = panel.getWidth() > 0 ? panel.getWidth() : width;
        float logicalHeight = panel.getHeight() > 0 ? panel.getHeight() : height;
        pixelStep.set(1.5f / logicalWidth, 1.5f / logicalHeight);
        outline.getMaterial().setVector2("PixelStep", pixelStep);
        renderManager.renderGeometry(outline);
    }

    private void ensureMask(int width, int height, Image.Format depth) {
        if (maskBuffer != null && maskBuffer.getWidth() == width && maskBuffer.getHeight() == height
                && maskBuffer.getDepthTarget().getFormat() == depth) {
            return;
        }
        releaseMask();
        mask = new Texture2D(width, height, Image.Format.RGBA8);
        mask.setMagFilter(Texture.MagFilter.Nearest);
        mask.setMinFilter(Texture.MinFilter.NearestNoMipMaps);
        maskBuffer = new FrameBuffer(width, height, 1);
        maskBuffer.setDepthTarget(FrameBuffer.FrameBufferTarget.newTarget(depth));
        maskBuffer.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(mask));
        outline.getMaterial().setTexture("Mask", mask);
    }

    private void releaseMask() {
        if (maskBuffer != null) {
            renderManager.getRenderer().deleteFrameBuffer(maskBuffer);
            renderManager.getRenderer().deleteImage(mask.getImage());
            maskBuffer = null;
            mask = null;
        }
    }

    @Override public boolean isInitialized() { return renderManager != null; }
    @Override public void reshape(ViewPort viewport, int width, int height) { }
    @Override public void preFrame(float tpf) { }
    @Override public void postFrame(FrameBuffer output) { }
    @Override public void setProfiler(AppProfiler profiler) { }

    @Override
    public void cleanup() {
        releaseMask();
        renderManager = null;
    }
}
