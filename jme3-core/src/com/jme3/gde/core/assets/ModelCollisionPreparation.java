package com.jme3.gde.core.assets;

import com.jme3.anim.SkinningControl;
import com.jme3.animation.SkeletonControl;
import com.jme3.bounding.BoundingBox;
import com.jme3.bullet.collision.shapes.CapsuleCollisionShape;
import com.jme3.bullet.collision.shapes.MeshCollisionShape;
import com.jme3.bullet.collision.shapes.infos.IndexedMesh;
import com.jme3.bullet.control.CharacterControl;
import com.jme3.bullet.control.PhysicsControl;
import com.jme3.bullet.control.RigidBodyControl;
import com.jme3.bullet.util.CollisionShapeFactory;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.UserData;
import com.jme3.scene.VertexBuffer;
import com.jme3.terrain.Terrain;
import com.jme3.util.BufferUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Conversion-only physics defaults. Existing controls/owned subtrees are kept.
 * Static subtrees use mesh shapes (the factory uses heightfields for terrains).
 * Skeleton/armature roots receive a Y-up capsule CharacterControl, fitted to
 * bind-pose bounds; animation is not a per-frame triangle collider. Capsule
 * dimensions are estimates and can be rebuilt in Properties. No PhysicsSpace is
 * created or started during conversion; applications register controls themselves.
 * 第一步：保留默认 Mesh 碰撞转换含镜像缩放的模型。
 * 第二步：静态网格将镜像写入碰撞顶点，渲染变换和源网格不变。
 */
final class ModelCollisionPreparation {
    private static final Logger LOG = Logger.getLogger(ModelCollisionPreparation.class.getName());

    static void addMissing(Spatial spatial) {
        addMissing(spatial, ModelConversionOptions.Collision.MESH);
    }

    static void addMissing(Spatial spatial, ModelConversionOptions.Collision type) {
        addMissing(spatial, type, false);
    }

    static void addMissing(Spatial spatial, ModelConversionOptions.Collision type, boolean separate) {
        if (separate) {
            addSeparate(spatial, type);
        } else {
            addCombined(spatial, type);
        }
    }

    private static void addSeparate(Spatial spatial, ModelConversionOptions.Collision type) {
        if (hasPhysics(spatial) || Boolean.TRUE.equals(spatial.getUserData(UserData.JME_PHYSICSIGNORE))) {
            return;
        }
        if (isSkinned(spatial) || spatial instanceof Terrain) {
            addCombined(spatial, type);
        } else if (spatial instanceof Geometry geometry && hasTriangles(geometry)) {
            addStatic(geometry, type);
        } else if (spatial instanceof Node node) {
            for (Spatial child : node.getChildren()) {
                addSeparate(child, type);
            }
        }
    }

    private static void addCombined(Spatial spatial, ModelConversionOptions.Collision type) {
        if (hasPhysics(spatial)) {
            return;
        }
        if (type == ModelConversionOptions.Collision.MESH && isSkinned(spatial)) {
            spatial.updateGeometricState();
            if (spatial.getWorldBound() instanceof BoundingBox box) {
                float radius = Math.max(.01f, Math.max(box.getXExtent(), box.getZExtent()));
                float cylinder = Math.max(.01f, box.getYExtent() * 2 - radius * 2);
                CharacterControl character = new CharacterControl(new CapsuleCollisionShape(radius, cylinder), radius * .2f);
                spatial.addControl(character);
                LOG.info("Generated character capsule (review dimensions/pivot): " + spatial.getName());
            }
            return;
        }
        boolean[] specialChild = {false};
        spatial.depthFirstTraversal(child -> {
            if (child != spatial && (hasPhysics(child) || isSkinned(child))) {
                specialChild[0] = true;
            }
        });
        if (specialChild[0] && spatial instanceof Node node) {
            for (Spatial child : node.getChildren()) {
                addCombined(child, type);
            }
            return;
        }
        boolean[] triangles = {false};
        spatial.depthFirstTraversal(child -> {
            if (child instanceof Geometry geometry && hasTriangles(geometry)) {
                triangles[0] = true;
            }
        });
        if (triangles[0]) {
            addStatic(spatial, type);
        }
    }

    private static boolean hasTriangles(Geometry geometry) {
        Mesh mesh = geometry.getMesh();
        return mesh != null && (mesh.getMode() == Mesh.Mode.Triangles || mesh.getMode() == Mesh.Mode.TriangleStrip
                || mesh.getMode() == Mesh.Mode.TriangleFan) && mesh.getTriangleCount() > 0;
    }

    private static void addStatic(Spatial spatial, ModelConversionOptions.Collision type) {
        var shape = type == ModelConversionOptions.Collision.MESH ? createMeshShape(spatial)
                : ModelCollisionShapes.create(spatial, type == ModelConversionOptions.Collision.SPHERE);
        if (shape != null) {
            spatial.addControl(new RigidBodyControl(shape, 0));
        }
    }

    private static com.jme3.bullet.collision.shapes.CollisionShape createMeshShape(Spatial owner) {
        List<Geometry> geometries = new ArrayList<>();
        if (!collectCollisionMeshes(owner, geometries)) {
            return CollisionShapeFactory.createMeshShape(owner);
        }
        // RigidBodyControl applies the owner's world translation and rotation.
        // Bake the signed transforms into collision vertices and keep native scale positive.
        var inverseRotation = owner.getWorldRotation().inverse();
        Vector3f origin = owner.getWorldTranslation();
        Vector3f ownerScale = owner.getWorldScale();
        Vector3f nativeScale = new Vector3f(Math.max(.0001f, Math.abs(ownerScale.x)),
                Math.max(.0001f, Math.abs(ownerScale.y)), Math.max(.0001f, Math.abs(ownerScale.z)));
        var meshes = new ArrayList<IndexedMesh>(geometries.size());
        var vertex = new Vector3f();
        int[] triangle = new int[3];
        for (Geometry geometry : geometries) {
            Mesh mesh = geometry.getMesh();
            var source = mesh.getFloatBuffer(VertexBuffer.Type.Position);
            var positions = BufferUtils.createFloatBuffer(mesh.getVertexCount() * 3);
            for (int i = 0; i < mesh.getVertexCount(); i++) {
                int offset = 3 * i;
                vertex.set(source.get(offset), source.get(offset + 1), source.get(offset + 2));
                geometry.getWorldTransform().transformVector(vertex, vertex);
                vertex.subtractLocal(origin);
                inverseRotation.mult(vertex, vertex);
                vertex.divideLocal(nativeScale);
                positions.put(vertex.x).put(vertex.y).put(vertex.z);
            }
            positions.flip();
            Vector3f scale = geometry.getWorldScale();
            boolean reverseWinding = scale.x * scale.y * scale.z < 0f;
            var indices = BufferUtils.createIntBuffer(mesh.getTriangleCount() * 3);
            for (int i = 0; i < mesh.getTriangleCount(); i++) {
                mesh.getTriangle(i, triangle);
                indices.put(triangle[0]).put(triangle[reverseWinding ? 2 : 1]).put(triangle[reverseWinding ? 1 : 2]);
            }
            indices.flip();
            Mesh collisionMesh = new Mesh();
            collisionMesh.setBuffer(VertexBuffer.Type.Position, 3, positions);
            collisionMesh.setBuffer(VertexBuffer.Type.Index, 3, indices);
            // Minie's Mesh constructor selects the same index width as its clone path.
            meshes.add(new IndexedMesh(collisionMesh));
        }
        var shape = new MeshCollisionShape(true, meshes);
        shape.setScale(nativeScale);
        return shape;
    }

    private static boolean collectCollisionMeshes(Spatial spatial, List<Geometry> geometries) {
        if (Boolean.TRUE.equals(spatial.getUserData(UserData.JME_PHYSICSIGNORE))) {
            return false;
        }
        Vector3f scale = spatial.getWorldScale();
        boolean mirrored = scale.x < 0f || scale.y < 0f || scale.z < 0f;
        if (spatial instanceof Geometry geometry && hasTriangles(geometry)) {
            geometries.add(geometry);
            return mirrored;
        }
        if (spatial instanceof Node node) {
            int previousSize = geometries.size();
            for (Spatial child : node.getChildren()) {
                mirrored |= collectCollisionMeshes(child, geometries);
            }
            return mirrored && geometries.size() > previousSize;
        }
        return false;
    }

    private static boolean hasPhysics(Spatial spatial) {
        for (int i = 0; i < spatial.getNumControls(); i++) {
            if (spatial.getControl(i) instanceof PhysicsControl) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSkinned(Spatial spatial) {
        return spatial.getControl(SkinningControl.class) != null
                || spatial.getControl(SkeletonControl.class) != null
                || (spatial instanceof Geometry geometry && geometry.getMesh() != null
                && geometry.getMesh().getBuffer(VertexBuffer.Type.BoneWeight) != null);
    }
}
