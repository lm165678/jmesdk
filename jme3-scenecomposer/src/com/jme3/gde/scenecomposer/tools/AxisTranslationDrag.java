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
        start = pointer.clone();
        axis = worldAxis.normalize();
        float height = camera.getHeight() * (camera.getViewPortTop() - camera.getViewPortBottom());
        float near = camera.getFrustumNear();
        float depth = pivot.subtract(camera.getLocation()).dot(camera.getDirection());
        float worldHeight = Math.abs(camera.getFrustumTop() - camera.getFrustumBottom());
        if (!camera.isParallelProjection() && near > 0) {
            worldHeight *= Math.max(near, depth) / near;
        }
        float pixelSize = worldHeight / height;
        if (!Float.isFinite(pixelSize) || pixelSize <= 0f) {
            direction = new Vector2f(0, 1);
            unitsPerPixel = 0f;
            return;
        }
        // Bound the sample so an axis toward the camera cannot cross the eye plane.
        float step = pixelSize * PROJECTION_SAMPLE_PIXELS;
        if (!camera.isParallelProjection() && depth > 0f) {
            step = Math.min(step, depth * .25f);
        }
        float samplePixels = step / pixelSize;
        Vector3f origin = camera.getScreenCoordinates(pivot);
        Vector3f tip = camera.getScreenCoordinates(pivot.add(axis.mult(step)));
        Vector2f projected = new Vector2f(tip.x - origin.x, tip.y - origin.y);
        float length = projected.length();
        if (Float.isFinite(length) && length > 0f && length >= samplePixels * END_ON_PROJECTION) {
            direction = projected.divideLocal(length);
            // Near the viewing direction, cap the gain from foreshortening.
            unitsPerPixel = step / Math.max(length, samplePixels * MIN_PROJECTED_SCALE);
        } else {
            // End-on axes have no useful screen direction. Drag up to move away.
            direction = new Vector2f(0, axis.dot(camera.getDirection()) >= 0 ? 1 : -1);
            unitsPerPixel = pixelSize;
        }
    }

    Vector3f translation(Vector2f pointer) {
        float pixels = (pointer.x - start.x) * direction.x + (pointer.y - start.y) * direction.y;
        return axis.mult(pixels * unitsPerPixel);
    }
}
