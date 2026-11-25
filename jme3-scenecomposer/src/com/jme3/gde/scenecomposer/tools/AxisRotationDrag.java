package com.jme3.gde.scenecomposer.tools;

import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;

/** A rotation gesture in the frame of the camera and the ring at mouse-down. */
final class AxisRotationDrag {
    private static final float EDGE_ON_COSINE = .25f;
    private static final float MIN_RAY_COSINE = .12f;
    private static final float MIN_RADIUS_FRACTION = .12f;

    private final Camera camera;
    private final Vector3f pivot;
    private final Vector3f axis;
    private final float radius;
    private final Vector2f screenTangent;
    private final float radiansPerPixel;
    private final Vector2f previousPointer;
    private Vector3f previousRadial;
    private boolean useScreenTangent;
    private float angle;

    AxisRotationDrag(Camera camera, Vector3f pivot, Vector3f worldAxis, float radius, Vector2f pointer) {
        this.camera = camera.clone();
        this.pivot = pivot.clone();
        axis = worldAxis.normalize();
        this.radius = radius;
        previousPointer = pointer.clone();
        Vector3f towardEye = camera.isParallelProjection() ? camera.getDirection().negate()
                : camera.getLocation().subtract(pivot).normalizeLocal();
        useScreenTangent = Math.abs(axis.dot(towardEye)) <= EDGE_ON_COSINE;
        Vector3f tangent = axis.cross(towardEye);
        if (tangent.lengthSquared() < 1e-4f) {
            Vector3f radial = intersect(pointer);
            tangent = axis.cross(radial == null ? camera.getUp() : radial);
        }
        tangent.normalizeLocal().multLocal(radius);
        Vector3f from = camera.getScreenCoordinates(pivot.subtract(tangent));
        Vector3f to = camera.getScreenCoordinates(pivot.add(tangent));
        screenTangent = new Vector2f(to.x - from.x, to.y - from.y);
        float diameter = screenTangent.length();
        if (Float.isFinite(diameter) && diameter >= 2f) {
            screenTangent.divideLocal(diameter);
            radiansPerPixel = 2f / diameter;
        } else {
            screenTangent.set(1f, 0f);
            radiansPerPixel = 0f;
        }
        if (!useScreenTangent) {
            previousRadial = validRadial(intersect(pointer));
            useScreenTangent = previousRadial == null;
        }
    }

    float update(Vector2f pointer) {
        if (!useScreenTangent) {
            Vector3f intersection = intersect(pointer);
            if (intersection == null) {
                // A drag can reach the plane horizon. Keep the angle and switch
                // once, so returning across the horizon cannot flip the gesture.
                useScreenTangent = true;
                previousPointer.set(pointer);
                return angle;
            }
            Vector3f radial = validRadial(intersection);
            if (previousRadial != null && radial != null) {
                angle += (float) Math.atan2(axis.dot(previousRadial.cross(radial)), previousRadial.dot(radial));
            }
            previousRadial = radial;
        } else {
            float dx = pointer.x - previousPointer.x;
            float dy = pointer.y - previousPointer.y;
            angle += (dx * screenTangent.x + dy * screenTangent.y) * radiansPerPixel;
        }
        previousPointer.set(pointer);
        return angle;
    }

    private Vector3f validRadial(Vector3f radial) {
        float minimum = radius * MIN_RADIUS_FRACTION;
        return radial != null && radial.lengthSquared() >= minimum * minimum ? radial.normalizeLocal() : null;
    }

    private Vector3f intersect(Vector2f pointer) {
        Vector3f origin = camera.getWorldCoordinates(pointer, 0f);
        Vector3f direction = camera.getWorldCoordinates(pointer, .5f).subtractLocal(origin).normalizeLocal();
        float divisor = axis.dot(direction);
        if (Math.abs(divisor) < MIN_RAY_COSINE) {
            return null;
        }
        float distance = axis.dot(pivot.subtract(origin)) / divisor;
        if (!Float.isFinite(distance) || distance < 0f) {
            return null;
        }
        return origin.addLocal(direction.multLocal(distance)).subtractLocal(pivot);
    }
}
