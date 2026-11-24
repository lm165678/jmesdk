package com.jme3.gde.core.assets;

import com.jme3.bounding.BoundingBox;
import com.jme3.bounding.BoundingVolume;
import com.jme3.bullet.collision.shapes.*;
import com.jme3.math.Transform;
import com.jme3.math.Quaternion;
import com.jme3.scene.shape.Cylinder;
import com.jme3.scene.shape.Line;
import com.jme3.scene.shape.Sphere;
import com.jme3.scene.debug.WireSphere;
import com.jme3.math.Vector3f;
import com.jme3.scene.*;

/**
 * Single bounds-based primitive in the owner's local space. Child transforms
 * are included; the owner's own world transform is applied by RigidBodyControl.
 * The compound child offset preserves models whose mesh is away from its pivot.
 * Sphere uses a one-sphere MultiSphere to support nonuniform model scaling.
 * Its radius encloses mesh vertices around the local bounding-box center;
 * scaled spheres become ellipsoids. Meshes without positions use their bounds.
 * Existing saved shapes retain their size until Rebuild collision is invoked.
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
        CollisionShape primitive = sphere
                ? new MultiSphere(Math.max(.001f, (float) Math.sqrt(radiusSquared(owner,
                        new Transform(), box.getCenter()))))
                : new BoxCollisionShape(half);
        CompoundCollisionShape compound = new CompoundCollisionShape(1);
        compound.addChildShape(primitive, box.getCenter());
        return compound;
    }

    private static float radiusSquared(Spatial spatial, Transform relative, Vector3f center) {
        float radiusSquared = 0f;
        if (spatial instanceof Geometry geometry && geometry.getMesh() != null) {
            Mesh mesh = geometry.getMesh();
            VertexBuffer positions = mesh.getBuffer(VertexBuffer.Type.Position);
            Vector3f vertex = new Vector3f();
            if (positions != null && positions.getNumComponents() >= 3
                    && positions.getNumElements() > 0) {
                for (int i = 0; i < positions.getNumElements(); i++) {
                    vertex.set(((Number) positions.getElementComponent(i, 0)).floatValue(),
                            ((Number) positions.getElementComponent(i, 1)).floatValue(),
                            ((Number) positions.getElementComponent(i, 2)).floatValue());
                    relative.transformVector(vertex, vertex);
                    radiusSquared = Math.max(radiusSquared, vertex.distanceSquared(center));
                }
            } else if (mesh.getBound() != null) {
                BoundingVolume transformed = mesh.getBound().transform(relative, null);
                BoundingBox box = new BoundingBox(transformed.getCenter(), 0, 0, 0);
                box.mergeLocal(transformed);
                vertex.set(Math.abs(box.getCenter().x - center.x) + box.getXExtent(),
                        Math.abs(box.getCenter().y - center.y) + box.getYExtent(),
                        Math.abs(box.getCenter().z - center.z) + box.getZExtent());
                radiusSquared = vertex.lengthSquared();
            }
        }
        if (spatial instanceof Node node) {
            for (Spatial child : node.getChildren()) {
                Transform next = child.getLocalTransform().clone().combineWithParent(relative);
                radiusSquared = Math.max(radiusSquared, radiusSquared(child, next, center));
            }
        }
        return radiusSquared;
    }

    /**
     * Builds the local collision shape for a newly created editor primitive.
     * Thin wire lines and quads have small finite thickness. Wrapped cylinders
     * use their actual triangles, including taper.
     */
    public static CollisionShape createPrimitive(Geometry geometry) {
        Mesh mesh = geometry.getMesh();
        if (mesh instanceof Sphere sphere) {
            return new MultiSphere(Math.max(.001f, sphere.getRadius()));
        }
        if (mesh instanceof WireSphere) {
            BoundingBox bounds = bounds(geometry);
            return new MultiSphere(Math.max(.001f, Math.max(bounds.getXExtent(),
                    Math.max(bounds.getYExtent(), bounds.getZExtent()))));
        }
        if (mesh instanceof Cylinder) {
            return new MeshCollisionShape(mesh);
        }
        if (mesh instanceof Line line) {
            Vector3f direction = line.getEnd().subtract(line.getStart());
            float length = direction.length();
            direction = length > 0f ? direction.divideLocal(length) : Vector3f.UNIT_Z;
            Vector3f up = Math.abs(direction.y) > .99f ? Vector3f.UNIT_X : Vector3f.UNIT_Y;
            Quaternion rotation = new Quaternion().lookAt(direction, up);
            Vector3f center = line.getStart().add(line.getEnd()).multLocal(.5f);
            BoxCollisionShape segment = new BoxCollisionShape(new Vector3f(.005f, .005f,
                    Math.max(.005f, length * .5f)));
            CompoundCollisionShape compound = new CompoundCollisionShape(1);
            compound.addChildShape(segment, center, rotation.toRotationMatrix());
            return compound;
        }
        return create(geometry, false);
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
