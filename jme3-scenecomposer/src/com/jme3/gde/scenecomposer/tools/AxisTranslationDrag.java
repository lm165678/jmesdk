package com.jme3.gde.scenecomposer.tools;

import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;

/** Maps pointer movement to a single world axis for the duration of a drag. */
final class AxisTranslationDrag {
    private static final float PROJECTION_SAMPLE_PIXELS = 16f;
    private static final float END_ON_PROJECTION = .15f;
    private static final float MIN_PROJECTED_SCALE = .35f;
    private final Vector2f start;
    private final Vector2f direction;
    private final Vector3f axis;
    private final float unitsPerPixel;

    AxisTranslationDrag(Camera camera, Vector3f pivot, Vector3f worldAxis, Vector2f pointer) {
        this(camera, pivot, worldAxis, pointer, false);
    }

    static AxisTranslationDrag forScale(Camera camera, Vector3f pivot, Vector3f worldAxis, Vector2f pointer) {
        return new AxisTranslationDrag(camera, pivot, worldAxis, pointer, true);
    }

    private AxisTranslationDrag(Camera camera, Vector3f pivot, Vector3f worldAxis, Vector2f pointer,
            boolean scaleDrag) {
        start = pointer.clone();
        axis = worldAxis.normalize();
        float pixelSize = worldUnitsPerPixel(camera, pivot);
        if (!Float.isFinite(pixelSize) || pixelSize <= 0f) {
            direction = new Vector2f(0, 1);
            unitsPerPixel = 0f;
            return;
        }
        float step = projectionStep(camera, pivot, pixelSize);
        float samplePixels = step / pixelSize;
        Vector2f projected = project(camera, pivot, axis, step);
        float length = projected.length();
        if (Float.isFinite(length) && length > 0f && length >= samplePixels * END_ON_PROJECTION) {
            direction = projected.divideLocal(length);
            // Near the viewing direction, cap the gain from foreshortening.
            unitsPerPixel = step / Math.max(length, samplePixels * MIN_PROJECTED_SCALE);
        } else {
            // Vertical drag moves away in translation mode and increases the scalar in scale mode.
            direction = new Vector2f(0, scaleDrag || axis.dot(camera.getDirection()) >= 0f ? 1f : -1f);
            unitsPerPixel = pixelSize;
        }
    }

    Vector3f translation(Vector2f pointer) {
        return axis.mult(distance(pointer));
    }

    float distance(Vector2f pointer) {
        float pixels = (pointer.x - start.x) * direction.x + (pointer.y - start.y) * direction.y;
        return pixels * unitsPerPixel;
    }

    static float worldUnitsPerPixel(Camera camera, Vector3f pivot) {
        float height = camera.getHeight() * (camera.getViewPortTop() - camera.getViewPortBottom());
        float worldHeight = Math.abs(camera.getFrustumTop() - camera.getFrustumBottom());
        float near = camera.getFrustumNear();
        if (!camera.isParallelProjection() && near > 0f) {
            float depth = pivot.subtract(camera.getLocation()).dot(camera.getDirection());
            worldHeight *= Math.max(near, depth) / near;
        }
        return worldHeight / height;
    }

    static float projectionStep(Camera camera, Vector3f pivot, float pixelSize) {
        float step = pixelSize * PROJECTION_SAMPLE_PIXELS;
        if (!camera.isParallelProjection()) {
            float depth = pivot.subtract(camera.getLocation()).dot(camera.getDirection());
            if (depth > 0f) {
                // An axis toward the camera cannot be sampled through the eye plane.
                step = Math.min(step, depth * .25f);
            }
        }
        return step;
    }

    static Vector2f project(Camera camera, Vector3f pivot, Vector3f worldAxis, float step) {
        Vector3f origin = camera.getScreenCoordinates(pivot);
        Vector3f tip = camera.getScreenCoordinates(pivot.add(worldAxis.mult(step)));
        return new Vector2f(tip.x - origin.x, tip.y - origin.y);
    }
}
