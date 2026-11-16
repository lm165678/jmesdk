package com.jme3.gde.core.assets;

import com.jme3.bounding.BoundingBox;
import com.jme3.bounding.BoundingVolume;
import com.jme3.bullet.collision.shapes.*;
import com.jme3.math.Transform;
import com.jme3.math.Vector3f;
import com.jme3.scene.*;

/**
 * Single bounds-based primitive in the owner's local space. Child transforms
 * are included; the owner's own world transform is applied by RigidBodyControl.
 * The compound child offset preserves models whose mesh is away from its pivot.
 * Sphere uses a one-sphere MultiSphere to support nonuniform model scaling.
 * Its radius encloses the local bounding box; scaled spheres become ellipsoids.
 * Call on the render thread. No scene transforms or shared mesh bounds are changed.
 * <pre>{@code
 * // 第一步：在渲染线程生成单个包围盒形体；true 改为包围球形体。
 * CollisionShape shape = ModelCollisionShapes.create(model, false);
 * // 第二步：质量 0 表示静态；运行时按应用生命周期注册 PhysicsSpace。
 * model.addControl(new com.jme3.bullet.control.RigidBodyControl(shape, 0));
 * // 第三步：重建前保留旧形体以支持撤销；不要叠加第二个刚体 Control。
 * }</pre>
 */
public final class ModelCollisionShapes {
    private ModelCollisionShapes() {
    }

    public static BoundingBox bounds(Spatial owner) {
        BoundingBox[] result = {null};
        collect(owner, new Transform(), result);
        if (result[0] == null) {
            throw new IllegalArgumentException("No mesh bounds are available for collision.");
        }
        return result[0];
    }

    private static void collect(Spatial spatial, Transform relative, BoundingBox[] result) {
        if (spatial instanceof Geometry geometry && geometry.getMesh() != null) {
            BoundingVolume bound = geometry.getMesh().getBound();
            if (bound != null) {
                BoundingVolume transformed = bound.transform(relative, null);
                if (result[0] == null) {
                    result[0] = new BoundingBox(transformed.getCenter(), 0, 0, 0);
                }
                result[0].mergeLocal(transformed);
            }
        }
        if (spatial instanceof Node node) {
            for (Spatial child : node.getChildren()) {
                Transform next = child.getLocalTransform().clone().combineWithParent(relative);
                collect(child, next, result);
            }
        }
    }

    public static CollisionShape create(Spatial owner, boolean sphere) {
        BoundingBox box = bounds(owner);
        Vector3f half = new Vector3f(Math.max(.001f, box.getXExtent()),
                Math.max(.001f, box.getYExtent()), Math.max(.001f, box.getZExtent()));
        CollisionShape primitive = sphere ? new MultiSphere(half.length()) : new BoxCollisionShape(half);
        CompoundCollisionShape compound = new CompoundCollisionShape(1);
        compound.addChildShape(primitive, box.getCenter());
        return compound;
    }

    public static int simpleType(CollisionShape shape) {
        if (shape instanceof CompoundCollisionShape compound && compound.countChildren() == 1) {
            shape = compound.listChildren()[0].getShape();
        }
        if (shape instanceof BoxCollisionShape) {
            return 3;
        }
        if (shape instanceof SphereCollisionShape || shape instanceof MultiSphere multi && multi.countSpheres() == 1) {
            return 4;
        }
        return -1;
    }
}
