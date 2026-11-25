package com.jme3.gde.core.scene.processors;

import com.jme3.asset.AssetManager;
import com.jme3.bullet.collision.PhysicsCollisionObject;
import com.jme3.bullet.util.DebugShapeFactory;
import com.jme3.material.Material;
import com.jme3.material.MatParam;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Quaternion;
import com.jme3.post.SceneProcessor;
import com.jme3.profile.AppProfiler;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.RenderPath;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.*;

import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Texture;

import java.util.*;
import java.util.function.Consumer;

/**
 * Preview-only diagnostic views. Lit restores original materials and the requested
 * render path. Diagnostics use Forward, never mutate saved assets, and leave
 * thumbnails/GUI viewports alone. Call setters on the render thread at a frame
 * boundary; the UI must enqueue them. SDFSelected ray marches original baked
 * volumes; SDFGlobal uses the engine camera-centered three-level clipmap. Collision displays existing physics shapes,
 * never adds controls or steps simulation. Refresh after changing baked data.
 * <pre>
 * 第一步：Render 选择引擎路径；View 选择 Lit/Unlit/Normals/SDF Selected/SDF Global/Collision。
 * 第二步：SDF 需要节点已烘焙的 SdfVolume；Collision 需要物理碰撞体。
 * 第三步：Refresh 重建调试几何；回到 Lit 恢复材质与所选渲染路径。
 * </pre>
 */
public final class PreviewDebugProcessor implements SceneProcessor {
    public enum Mode { Lit, Unlit, Normals, Wireframe, SDFSelected, SDFGlobal, Collision, LightmapMip }
    private final AssetManager assets;
    private final Node root;
    private final Consumer<String> status;
    private final Node debug = new Node("SDK preview diagnostics");
    private final Map<Material, Material> replacements = new IdentityHashMap<>();
    private final Map<Geometry, Material> originals = new IdentityHashMap<>();
    private final Map<Geometry, PhysicsCollisionObject> bodies = new IdentityHashMap<>();
    private ViewPort view;
    private Mode mode = Mode.Lit;
    private RenderPath requested = RenderPath.Forward;
    private boolean dirty = true;
    private List<Spatial> lastChildren = List.of();
    private Spatial.CullHint originalCull;
    private RenderManager manager;
    private SdfPreview sdfPreview;
    private int cascade = -1;
    private boolean cascadeColors = true;
    private float sdfCell = 0.5f;
    private String sdfStatus;
    private boolean sdfFailed;
    private LightmapMipDebug lightmapMipDebug;

    /**
     * Render-thread preview options. Defaults: automatic three-level selection,
     * colored cascades, finest spacing 0.5 world units. -1 selects automatic;
     * 0..2 isolate a level. Cell changes recreate the preview cache only.
     * <pre>
     * 第一步：切到 SDFGlobal；第二步：setSdfOptions(-1, true, 0.5f) 显示三级颜色。
     * 第三步：setSdfOptions(1, true, 0.5f) 单独显示第二级；回到 Lit 释放缓存。
     * </pre>
     */
    public void setSdfOptions(int level, boolean colors, float cell) {
        if (level < -1 || level > 2 || !Float.isFinite(cell) || cell <= 0) {
            throw new IllegalArgumentException("Invalid SDF preview options");
        }
        if (sdfCell != cell) {
            refresh();
        }
        cascade = level;
        cascadeColors = colors;
        sdfCell = cell;
    }

    private boolean isSdf() {
        return mode == Mode.SDFSelected || mode == Mode.SDFGlobal;
    }

    public PreviewDebugProcessor(AssetManager assets, Node root, Consumer<String> status) {
        this.assets = assets;
        this.root = root;
        this.status = status;
    }

    /** Sets a preview mode; call between frames on the render thread. */
    public void setMode(Mode value) {
        restore();
        if (lightmapMipDebug != null) {
            lightmapMipDebug.clear();
            lightmapMipDebug = null;
        }
        mode = Objects.requireNonNull(value);
        applyPath();
        refresh();
    }

    /** Rebuilds diagnostics on the next frame after an asset edit/bake. */
    public void refresh() {
        dirty = true;
    }

    /** Records the user path even while diagnostics temporarily use Forward. */
    public void setRenderPath(RenderPath path) {
        requested = path;
        applyPath();
    }

    private void applyPath() {
        if (view != null) {
            view.setRenderPath(mode == Mode.Lit ? requested : RenderPath.Forward);
        }
    }

    @Override
    public void initialize(RenderManager manager, ViewPort viewport) {
        this.manager = manager;
        view = viewport;
        view.attachScene(debug);
        applyPath();
    }

    @Override
    public boolean isInitialized() {
        return view != null;
    }

    @Override
    public void preFrame(float tpf) {
        restore();
        if (!lastChildren.equals(root.getChildren())) {
            lastChildren = new ArrayList<>(root.getChildren());
            dirty = true;
        }
        if (dirty) {
            if (sdfPreview != null) {
                sdfPreview.close();
                sdfPreview = null;
            }
            sdfStatus = null;
            sdfFailed = false;
            replacements.clear();
            debug.detachAllChildren();
            bodies.clear();
            if (mode == Mode.Collision) {
                try {
                    buildDiagnostics(root);
                    status.accept(debug.getQuantity() == 0
                            ? "No " + mode + " data. Bake SDF / add physics collision controls first."
                            : mode + ": " + debug.getQuantity() + " objects; Refresh after edits.");
                } catch (RuntimeException error) {
                    status.accept(mode + ": " + error.getMessage());
                }
            } else if (!isSdf()) {
                status.accept(mode == Mode.Lit ? "Lit: selected rendering path" : mode + " (Forward preview)");
            }
            dirty = false;
        }
        if (isSdf() && !sdfFailed) {
            try {
                if (!manager.getRenderer().getCaps().contains(com.jme3.renderer.Caps.GLSL150)
                        || !manager.getRenderer().getCaps().contains(com.jme3.renderer.Caps.FloatTexture)) {
                    throw new IllegalStateException("SDF preview requires GLSL150 and floating-point textures");
                }
                if (sdfPreview == null) {
                    sdfPreview = new SdfPreview(assets, debug);
                }
                Spatial selected = com.jme3.gde.core.scene.SceneApplication.getApplication().getSdfPreviewSelection();
                if (selected != null && selected != root && !selected.hasAncestor(root)) {
                    selected = null;
                }
                int count = sdfPreview.update(mode == Mode.SDFGlobal ? root : selected,
                        mode == Mode.SDFGlobal, view.getCamera(), manager.getRenderer(),
                        cascade, cascadeColors, sdfCell);
                reportSdf(count == 0 ? text("SdfNoData")
                        : java.text.MessageFormat.format(text(mode == Mode.SDFGlobal ? "SdfGlobalStatus" : "SdfStatus"), count));
            } catch (RuntimeException error) {
                if (sdfPreview != null) {
                    sdfPreview.close();
                    sdfPreview = null;
                }
                java.util.logging.Logger.getLogger(PreviewDebugProcessor.class.getName())
                        .log(java.util.logging.Level.WARNING, "SDF preview failed", error);
                sdfFailed = true;
                reportSdf("SDF: " + error.getMessage());
            }
        }
        for (Map.Entry<Geometry, PhysicsCollisionObject> entry : bodies.entrySet()) {
            entry.getKey().setLocalTranslation(entry.getValue().getPhysicsLocation());
            entry.getKey().setLocalRotation(entry.getValue().getPhysicsRotation(new Quaternion()));
        }
        debug.updateGeometricState();
        if (isSdf() || mode == Mode.Collision) {
            originalCull = root.getLocalCullHint();
            root.setCullHint(Spatial.CullHint.Always);
        }
    }

    private static String text(String key) {
        return com.jme3.gde.core.util.EditorMessages.text(key);
    }

    private void reportSdf(String message) {
        if (!message.equals(sdfStatus)) {
            sdfStatus = message;
            status.accept(message);
        }
    }

    private void buildDiagnostics(Spatial source) {
        for (int i = 0; i < source.getNumControls(); i++) {
            if (source.getControl(i) instanceof PhysicsCollisionObject body && body.getCollisionShape() != null) {
                Geometry geometry = new Geometry("Physics shape", DebugShapeFactory.getDebugMesh(body.getCollisionShape()));
                geometry.setMaterial(color(ColorRGBA.Green, true));
                debug.attachChild(geometry);
                bodies.put(geometry, body);
            }
        }
        if (source instanceof Node node) {
            for (Spatial child : node.getChildren()) {
                buildDiagnostics(child);
            }
        }
    }

    private Material color(ColorRGBA color, boolean wire) {
        Material material = new Material(assets, "Common/MatDefs/Misc/Unshaded.j3md");
        material.setColor("Color", color);
        material.getAdditionalRenderState().setWireframe(wire);
        return material;
    }

    @Override
    public void postQueue(RenderQueue queue) {
        if (mode == Mode.LightmapMip) {
            if (!manager.getRenderer().getCaps().contains(com.jme3.renderer.Caps.GLSL400)) {
                reportSdf("Lightmap mip view requires desktop OpenGL 4.0+; original materials retained");
                return;
            }
            if (lightmapMipDebug == null) {
                lightmapMipDebug = new LightmapMipDebug(assets);
            }
            reportSdf(LightmapMipDebug.LEGEND);
            root.depthFirstTraversal(spatial -> {
                if (spatial instanceof Geometry geometry && geometry.getMaterial() != null) {
                    originals.put(geometry, geometry.getMaterial());
                    geometry.setMaterial(lightmapMipDebug.material(geometry));
                }
            });
            return;
        }
        if (mode == Mode.Unlit || mode == Mode.Normals || mode == Mode.Wireframe) {
            root.depthFirstTraversal(spatial -> {
                if (spatial instanceof Geometry geometry && geometry.getMaterial() != null) {
                    Material original = geometry.getMaterial();
                    originals.put(geometry, original);
                    Material replacement = replacements.get(original);
                    if (replacement == null && mode == Mode.Normals) {
                        replacement = new Material(assets, "Common/MatDefs/Misc/ShowNormals.j3md");
                    } else if (replacement == null) {
                        replacement = color(mode == Mode.Wireframe ? ColorRGBA.Cyan : ColorRGBA.White,
                                mode == Mode.Wireframe);
                        if (mode == Mode.Unlit) {
                            copyParameter(original, replacement, "BaseColor", "Diffuse", "Color", false);
                            copyParameter(original, replacement, "BaseColorMap", "DiffuseMap", "ColorMap", true);
                        }
                    }
                    replacements.put(original, replacement);
                    if (mode == Mode.Unlit) {
                        replacement.setColor("Color", ColorRGBA.White);
                        replacement.clearParam("ColorMap");
                        copyParameter(original, replacement, "BaseColor", "Diffuse", "Color", false);
                        copyParameter(original, replacement, "BaseColorMap", "DiffuseMap", "ColorMap", true);
                    }
                    geometry.setMaterial(replacement);
                }
            });
        }
    }

    private void copyParameter(Material source, Material target, String first, String second, String third, boolean texture) {
        MatParam param = source.getParam(first);
        if (param == null) {
            param = source.getParam(second);
        }
        if (param == null) {
            param = source.getParam(third);
        }
        if (param != null) {
            if (texture && param.getValue() instanceof Texture value) {
                target.setTexture("ColorMap", value);
            } else if (!texture && param.getValue() instanceof ColorRGBA value) {
                target.setColor("Color", value);
            }
        }
    }

    private void restore() {
        originals.forEach(Geometry::setMaterial);
        originals.clear();
        if (originalCull != null) {
            root.setCullHint(originalCull);
            originalCull = null;
        }
    }

    @Override
    public void postFrame(FrameBuffer output) {
        restore();
    }

    @Override
    public void cleanup() {
        restore();
        if (sdfPreview != null) {
            sdfPreview.close();
            sdfPreview = null;
        }
        if (view != null) {
            view.detachScene(debug);
            view.setRenderPath(requested);
        }
        debug.detachAllChildren();
        bodies.clear();
        replacements.clear();
        if (lightmapMipDebug != null) {
            lightmapMipDebug.clear();
            lightmapMipDebug = null;
        }
        view = null;
    }

    @Override
    public void reshape(ViewPort viewport, int width, int height) {
    }

    @Override
    public void setProfiler(AppProfiler profiler) {
    }
}
