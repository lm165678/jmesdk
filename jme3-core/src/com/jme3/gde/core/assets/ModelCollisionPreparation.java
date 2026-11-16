package com.jme3.gde.core.assets;

import com.jme3.anim.SkinningControl;
import com.jme3.animation.SkeletonControl;
import com.jme3.bounding.BoundingBox;
import com.jme3.bullet.collision.shapes.CapsuleCollisionShape;
import com.jme3.bullet.control.CharacterControl;
import com.jme3.bullet.control.PhysicsControl;
import com.jme3.bullet.control.RigidBodyControl;
import com.jme3.bullet.util.CollisionShapeFactory;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer;
import java.util.logging.Logger;

/**
 * Conversion-only physics defaults. Existing controls/owned subtrees are kept.
 * Static subtrees use mesh shapes (the factory uses heightfields for terrains).
 * Skeleton/armature roots receive a Y-up capsule CharacterControl, fitted to
 * bind-pose bounds; animation is not a per-frame triangle collider. Capsule
 * dimensions are estimates and can be rebuilt in Properties. No PhysicsSpace is
 * created or started during conversion; applications register controls themselves.
 */
final class ModelCollisionPreparation {
    private static final Logger LOG = Logger.getLogger(ModelCollisionPreparation.class.getName());

    static void addMissing(Spatial spatial) {
        addMissing(spatial, ModelConversionOptions.Collision.MESH);
    }

    static void addMissing(Spatial spatial, ModelConversionOptions.Collision type) {
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
                addMissing(child, type);
            }
            return;
        }
        boolean[] triangles = {false};
        spatial.depthFirstTraversal(child -> {
            if (child instanceof Geometry geometry && geometry.getMesh() != null
                    && (geometry.getMesh().getMode() == com.jme3.scene.Mesh.Mode.Triangles
                    || geometry.getMesh().getMode() == com.jme3.scene.Mesh.Mode.TriangleStrip
                    || geometry.getMesh().getMode() == com.jme3.scene.Mesh.Mode.TriangleFan)
                    && geometry.getMesh().getTriangleCount() > 0) {
                triangles[0] = true;
            }
        });
        if (triangles[0]) {
            spatial.addControl(new RigidBodyControl(type == ModelConversionOptions.Collision.MESH
                    ? CollisionShapeFactory.createMeshShape(spatial)
                    : ModelCollisionShapes.create(spatial, type == ModelConversionOptions.Collision.SPHERE), 0));
            LOG.info("Generated static/terrain collision: " + spatial.getName());
        }
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
