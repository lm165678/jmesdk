package com.jme3.gde.scenecomposer;

/** Inspector facade for the shared conversion/preview bounds policy. */
final class ModelCollisionShapes {
    private ModelCollisionShapes() {
    }
    static com.jme3.bullet.collision.shapes.CollisionShape create(com.jme3.scene.Spatial owner, boolean sphere) {
        return com.jme3.gde.core.assets.ModelCollisionShapes.create(owner, sphere);
    }
    static int simpleType(com.jme3.bullet.collision.shapes.CollisionShape shape) {
        return com.jme3.gde.core.assets.ModelCollisionShapes.simpleType(shape);
    }
    static com.jme3.bounding.BoundingBox bounds(com.jme3.scene.Spatial owner) {
        return com.jme3.gde.core.assets.ModelCollisionShapes.bounds(owner);
    }
}
