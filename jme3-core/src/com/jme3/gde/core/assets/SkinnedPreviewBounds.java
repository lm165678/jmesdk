package com.jme3.gde.core.assets;

import com.jme3.anim.SkinningControl;
import com.jme3.bounding.BoundingBox;
import com.jme3.bounding.BoundingVolume;
import com.jme3.math.Matrix4f;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;

/**
 * Frames the current pose of a private preview model on its render thread.
 * Hardware skinning leaves CPU mesh bounds in bind space. Older FBX assets
 * may even have bind vertices and the animated pose in different units.
 *
 * <pre>
 * 第一步：克隆预览模型并采样要显示的第一帧。
 * 第二步：调用 fit(model)，用返回的世界范围调整相机和取景。
 * 第三步：切换资源时销毁预览副本，原资产与运行时剔除设置保持不变。
 * </pre>
 */
public final class SkinnedPreviewBounds {
    private SkinnedPreviewBounds() {
    }

    /**
     * Computes the current world bounds and bypasses stale frustum bounds on
     * skinned preview branches. Does not change mesh buffers or bind bounds.
     *
     * @param model private preview model with its initial animation pose applied
     * @return the current visual bounds, or null for a model without geometry
     */
    public static BoundingVolume fit(Spatial model) {
        model.updateGeometricState();
        BoundingVolume[] result = {null};
        model.depthFirstTraversal(spatial -> {
            if (spatial instanceof Geometry geometry && geometry.getCullHint() != Spatial.CullHint.Always) {
                BoundingVolume bounds = poseBounds(geometry, model);
                if (bounds != null) {
                    result[0] = result[0] == null ? bounds.clone() : result[0].mergeLocal(bounds);
                }
            }
        });
        return result[0] == null ? model.getWorldBound() : result[0];
    }

    private static BoundingVolume poseBounds(Geometry geometry, Spatial model) {
        var mesh = geometry.getMesh();
        if (mesh == null) {
            return geometry.getWorldBound();
        }
        VertexBuffer positions = mesh.getBuffer(VertexBuffer.Type.BindPosePosition);
        VertexBuffer indices = mesh.getBuffer(VertexBuffer.Type.BoneIndex);
        VertexBuffer weights = mesh.getBuffer(VertexBuffer.Type.BoneWeight);
        if (positions == null || indices == null || weights == null
                || !(positions.getData() instanceof FloatBuffer vertices)
                || !(weights.getData() instanceof FloatBuffer weightData)) {
            return geometry.getWorldBound();
        }
        var indexData = indices.getData();
        int components = indices.getNumComponents();
        if (!(indexData instanceof ByteBuffer || indexData instanceof ShortBuffer)
                || components < 1 || components != weights.getNumComponents()
                || vertices.limit() / 3 > Math.min(indexData.limit(), weightData.limit()) / components) {
            return geometry.getWorldBound();
        }
        SkinningControl skin = null;
        for (Spatial node = geometry; node != null && skin == null; node = node.getParent()) {
            skin = node.getControl(SkinningControl.class);
        }
        if (skin == null) {
            return geometry.getWorldBound();
        }
        var armature = skin.getArmature();
        armature.update();
        Matrix4f[] palette = armature.computeSkinningMatrices();
        Vector3f low = new Vector3f(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY);
        Vector3f high = new Vector3f(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY);
        Vector3f bind = new Vector3f();
        Vector3f transformed = new Vector3f();
        Vector3f blended = new Vector3f();
        for (int vertex = 0; vertex < vertices.limit() / 3; ++vertex) {
            bind.set(vertices.get(vertex * 3), vertices.get(vertex * 3 + 1), vertices.get(vertex * 3 + 2));
            blended.zero();
            boolean influenced = false;
            for (int component = 0; component < components; ++component) {
                int offset = vertex * components + component;
                float weight = weightData.get(offset);
                if (weight > 0) {
                    int joint = indexData instanceof ByteBuffer bytes ? bytes.get(offset) & 255
                            : ((ShortBuffer) indexData).get(offset) & 65535;
                    if (joint >= palette.length) {
                        return geometry.getWorldBound();
                    }
                    palette[joint].mult(bind, transformed);
                    blended.addLocal(transformed.multLocal(weight));
                    influenced = true;
                }
            }
            if (!influenced) {
                blended.set(bind);
            }
            if (!geometry.isIgnoreTransform()) {
                geometry.localToWorld(blended, blended);
            }
            low.minLocal(blended);
            high.maxLocal(blended);
        }
        if (!Vector3f.isValidVector(low) || !Vector3f.isValidVector(high)) {
            return geometry.getWorldBound();
        }
        for (Spatial node = geometry; node != model; node = node.getParent()) {
            node.setCullHint(Spatial.CullHint.Never);
        }
        model.setCullHint(Spatial.CullHint.Never);
        BoundingBox result = new BoundingBox();
        result.setMinMax(low, high);
        return result;
    }
}
