package com.jme3.gde.core.assets;

import com.jme3.anim.AnimComposer;
import com.jme3.anim.Joint;
import com.jme3.anim.SkinningControl;
import com.jme3.anim.TransformTrack;
import com.jme3.anim.graph.AnimationGraph.Overlay;
import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.control.AbstractControl;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Preview-only overlay; create and update on the preview render thread.
 * 第一步：勾选显示骨骼/目标（默认关闭）；第二步：选择 Composer。
 * 青色为骨架，橙色为该 Composer 所有片段直接引用的关节，紫色十字为普通节点目标。
 * 应用带遮罩的图后，绿色标出配置的覆盖分支，是否启用由预览 Boolean 控制。
 * 骨骼使用实线和菱形轮廓；普通节点到模型内父节点的关系使用紫色虚线，不能视为蒙皮骨骼。
 * 高亮不代表所有受父级运动间接影响的关节，也不代表当前片段全部正在运动。
 * 根运动选择器打开时，选择轨道以黄色显示该目标及子级，放大根标记；关闭后恢复原显示。
 * No overlay data is attached to the saved model. Hidden overlays do no update work.
 */
final class AnimationTargetOverlay extends AbstractControl {
    final Geometry geometry = new Geometry("Animation targets", new Mesh());
    private final Spatial model;
    private final Set<Object> targets = Collections.newSetFromMap(new IdentityHashMap<>());
    private final float markerSize;
    private final Overlay mask;
    private final Spatial composerOwner;
    private Object focus;
    int jointCount;
    int selectedJointCount;
    int nodeCount;
    int boneLinkCount;
    int nodeLinkCount;

    AnimationTargetOverlay(AssetManager assets, Spatial model, AnimComposer composer, float size) {
        this(assets, model, composer, size, null);
    }

    AnimationTargetOverlay(AssetManager assets, Spatial model, AnimComposer composer, float size, Overlay mask) {
        this.model = model;
        this.mask = mask;
        composerOwner = composer.getSpatial();
        markerSize = Math.max(0.001f, size);
        for (var clip : composer.getAnimClips()) {
            for (var track : clip.getTracks()) {
                if (track instanceof TransformTrack transform) {
                    targets.add(transform.getTarget());
                }
            }
        }
        Material material = new Material(assets, "Common/MatDefs/Misc/Unshaded.j3md");
        material.setBoolean("VertexColor", true);
        material.getAdditionalRenderState().setDepthTest(false);
        material.getAdditionalRenderState().setDepthWrite(false);
        geometry.setMaterial(material);
        geometry.setQueueBucket(RenderQueue.Bucket.Transparent);
        geometry.setShadowMode(RenderQueue.ShadowMode.Off);
        geometry.setCullHint(Spatial.CullHint.Never);
        geometry.addControl(this);
        geometry.getMesh().setMode(Mesh.Mode.Lines);
        geometry.getMesh().setDynamic();
    }

    void focus(String path, boolean joint) {
        focus = null;
        for (Object target : targets) {
            String candidate = target instanceof Joint bone && joint
                    ? com.jme3.anim.graph.AnimationGraph.jointPath(bone)
                    : target instanceof Spatial node && !joint
                            ? com.jme3.anim.graph.AnimationGraph.nodePath(composerOwner, node) : null;
            if (path != null && path.equals(candidate)) {
                focus = target;
                break;
            }
        }
    }

    void show(boolean visible) {
        setEnabled(visible);
        geometry.setCullHint(visible ? Spatial.CullHint.Never : Spatial.CullHint.Always);
        if (visible) {
            controlUpdate(0);
        }
    }

    private boolean focused(Object target) {
        if (focus == null) {
            return false;
        }
        if (target instanceof Joint joint) {
            for (Joint current = joint; current != null; current = current.getParent()) {
                if (current == focus) {
                    return true;
                }
            }
        } else if (target instanceof Spatial spatial) {
            for (Spatial current = spatial; current != null; current = current.getParent()) {
                if (current == focus) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    protected void controlUpdate(float tpf) {
        // World-transform accessors resolve dirty ancestors. The preview root owns
        // the geometric pass, including inherited material overrides and lights.
        ArrayList<Vector3f> positions = new ArrayList<>();
        ArrayList<ColorRGBA> colors = new ArrayList<>();
        jointCount = 0;
        selectedJointCount = 0;
        nodeCount = 0;
        boneLinkCount = 0;
        nodeLinkCount = 0;
        Set<Spatial> members = Collections.newSetFromMap(new IdentityHashMap<>());
        model.depthFirstTraversal(members::add);
        model.depthFirstTraversal(owner -> {
            SkinningControl skin = owner.getControl(SkinningControl.class);
            if (skin != null) {
                for (Joint joint : skin.getArmature().getJointList()) {
                    jointCount++;
                    boolean selected = targets.contains(joint);
                    if (selected) {
                        selectedJointCount++;
                    }
                    ColorRGBA color = mask != null && mask.includes(joint)
                            ? ColorRGBA.Green : selected ? ColorRGBA.Orange : ColorRGBA.Cyan;
                    if (focus != null) {
                        color = focused(joint) || focused(owner) ? ColorRGBA.Yellow : ColorRGBA.DarkGray;
                    }
                    Vector3f point = owner.localToWorld(joint.getModelTransform().getTranslation(), null);
                    marker(positions, colors, point, color);
                    if (focus == joint) {
                        focusMarker(positions, colors, point);
                    }
                    if (joint.getParent() != null) {
                        boneLinkCount++;
                        Vector3f parent = owner.localToWorld(joint.getParent().getModelTransform().getTranslation(), null);
                        bone(positions, colors, parent, point, color);
                    }
                }
            }
            if (targets.contains(owner) || focused(owner)) {
                nodeCount++;
                ColorRGBA nodeColor = focus == null ? ColorRGBA.Magenta
                        : focused(owner) ? ColorRGBA.Yellow : ColorRGBA.DarkGray;
                marker(positions, colors, owner.getWorldTranslation(), nodeColor);
                if (focus == owner) {
                    focusMarker(positions, colors, owner.getWorldTranslation());
                }
                if (owner.getParent() != null && members.contains(owner.getParent())) {
                    nodeLinkCount++;
                    Vector3f start = owner.getParent().getWorldTranslation();
                    Vector3f direction = owner.getWorldTranslation().subtract(start);
                    for (int i = 0; i < 8; i++) {
                        line(positions, colors, start.add(direction.mult(i / 8f)),
                                start.add(direction.mult((i + 0.5f) / 8f)), nodeColor);
                    }
                }
            }
        });
        if (positions.isEmpty()) {
            geometry.setCullHint(Spatial.CullHint.Always);
            return;
        }
        float[] vertices = new float[positions.size() * 3];
        float[] rgba = new float[colors.size() * 4];
        for (int i = 0; i < positions.size(); i++) {
            Vector3f point = positions.get(i);
            vertices[i * 3] = point.x;
            vertices[i * 3 + 1] = point.y;
            vertices[i * 3 + 2] = point.z;
            ColorRGBA color = colors.get(i);
            rgba[i * 4] = color.r;
            rgba[i * 4 + 1] = color.g;
            rgba[i * 4 + 2] = color.b;
            rgba[i * 4 + 3] = 1;
        }
        geometry.getMesh().setBuffer(VertexBuffer.Type.Position, 3, vertices);
        geometry.getMesh().setBuffer(VertexBuffer.Type.Color, 4, rgba);
        geometry.getMesh().updateBound();
        geometry.updateModelBound();
    }

    private void focusMarker(ArrayList<Vector3f> positions, ArrayList<ColorRGBA> colors, Vector3f point) {
        float size = markerSize * 4;
        line(positions, colors, point.add(size, 0, 0), point.add(-size, 0, 0), ColorRGBA.Yellow);
        line(positions, colors, point.add(0, size, 0), point.add(0, -size, 0), ColorRGBA.Yellow);
        line(positions, colors, point.add(0, 0, size), point.add(0, 0, -size), ColorRGBA.Yellow);
    }

    private void bone(ArrayList<Vector3f> positions, ArrayList<ColorRGBA> colors,
            Vector3f parent, Vector3f child, ColorRGBA color) {
        line(positions, colors, parent, child, color);
        Vector3f direction = child.subtract(parent);
        float length = direction.length();
        if (length < 0.00001f) {
            return;
        }
        direction.divideLocal(length);
        Vector3f side = direction.cross(Math.abs(direction.y) < 0.9f ? Vector3f.UNIT_Y : Vector3f.UNIT_X)
                .normalizeLocal().multLocal(Math.min(markerSize * 1.5f, length * 0.12f));
        Vector3f middle = parent.add(child.subtract(parent).multLocal(0.25f));
        for (float sign : new float[]{-1, 1}) {
            Vector3f corner = middle.add(side.mult(sign));
            line(positions, colors, parent, corner, color);
            line(positions, colors, corner, child, color);
        }
    }

    private void marker(ArrayList<Vector3f> positions, ArrayList<ColorRGBA> colors, Vector3f point, ColorRGBA color) {
        line(positions, colors, point.add(markerSize, 0, 0), point.add(-markerSize, 0, 0), color);
        line(positions, colors, point.add(0, markerSize, 0), point.add(0, -markerSize, 0), color);
        line(positions, colors, point.add(0, 0, markerSize), point.add(0, 0, -markerSize), color);
    }

    private void line(ArrayList<Vector3f> positions, ArrayList<ColorRGBA> colors, Vector3f a, Vector3f b, ColorRGBA color) {
        positions.add(a);
        positions.add(b);
        colors.add(color);
        colors.add(color);
    }

    @Override
    protected void controlRender(RenderManager manager, ViewPort view) {
    }
}
