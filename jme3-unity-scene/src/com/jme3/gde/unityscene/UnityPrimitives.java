package com.jme3.gde.unityscene;

import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Box;
import com.jme3.scene.shape.Cylinder;
import com.jme3.scene.shape.Quad;
import com.jme3.scene.shape.Sphere;

/** Unity built-in mesh fileIDs from Library/unity default resources. */
final class UnityPrimitives {
    private UnityPrimitives() {
    }

    static Spatial create(long fileId) {
        return switch ((int) fileId) {
            case 10202 -> geometry("Unity Cube", new Box(0.5f, 0.5f, 0.5f));
            case 10206 -> cylinder("Unity Cylinder", 2f);
            case 10207 -> geometry("Unity Sphere", new Sphere(32, 32, 0.5f));
            case 10208 -> capsule();
            case 10209 -> plane();
            case 10210 -> quad();
            default -> null;
        };
    }

    private static Geometry geometry(String name, com.jme3.scene.Mesh mesh) {
        return new Geometry(name, mesh);
    }

    private static Spatial plane() {
        Geometry plane = geometry("Unity Plane", new Quad(10f, 10f));
        plane.setLocalRotation(new Quaternion().fromAngleAxis(
                -FastMath.HALF_PI, Vector3f.UNIT_X));
        plane.setLocalTranslation(-5f, 0f, 5f);
        return plane;
    }

    private static Spatial quad() {
        Geometry quad = geometry("Unity Quad", new Quad(1f, 1f));
        quad.setLocalTranslation(-0.5f, -0.5f, 0f);
        return quad;
    }

    private static Geometry cylinder(String name, float height) {
        // JME Cylinder is Z-aligned; Unity primitives use the Y axis.
        Geometry cylinder = geometry(name, new Cylinder(32, 32, 0.5f, height, true));
        cylinder.setLocalRotation(new Quaternion().fromAngleAxis(
                -FastMath.HALF_PI, Vector3f.UNIT_X));
        return cylinder;
    }

    private static Spatial capsule() {
        Node result = new Node("Unity Capsule");
        result.attachChild(cylinder("Capsule Body", 1f));
        for (int sign : new int[]{-1, 1}) {
            Geometry cap = geometry("Capsule Cap " + sign, new Sphere(32, 16, 0.5f));
            cap.setLocalTranslation(0, sign * 0.5f, 0);
            result.attachChild(cap);
        }
        return result;
    }
}
