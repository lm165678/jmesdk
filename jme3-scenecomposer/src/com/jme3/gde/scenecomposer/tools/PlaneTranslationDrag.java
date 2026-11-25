package com.jme3.gde.scenecomposer.tools;

import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;

/** A projected plane basis with bounded sensitivity even when the plane is seen edge-on. */
final class PlaneTranslationDrag {
    private static final float END_ON_PROJECTION = .15f;
    private static final float MIN_PROJECTED_SCALE = .35f;

    private final Vector2f start;
    private final Vector2f screenDirection;
    private final Vector3f visibleAxis;
    private final Vector3f depthAxis;
    private final float visibleUnitsPerPixel;
    private final float depthUnitsPerPixel;
    private final float coupledSlope;

    PlaneTranslationDrag(Camera camera, Vector3f pivot, Vector3f normal, Vector3f inPlane, Vector2f pointer) {
        start = pointer.clone();
        Vector3f view = camera.isParallelProjection() ? camera.getDirection() : pivot.subtract(camera.getLocation());
        Vector3f visible = normal.cross(view.normalize());
        visibleAxis = visible.lengthSquared() > 1e-6f ? visible.normalizeLocal() : inPlane.normalize();
        depthAxis = normal.cross(visibleAxis).normalizeLocal();

        float pixelSize = AxisTranslationDrag.worldUnitsPerPixel(camera, pivot);
        float step = AxisTranslationDrag.projectionStep(camera, pivot, pixelSize);
        if (!Float.isFinite(step) || step <= 0f) {
            screenDirection = new Vector2f(1f, 0f);
            visibleUnitsPerPixel = 0f;
            depthUnitsPerPixel = 0f;
            coupledSlope = 0f;
            return;
        }

        Vector2f projectedVisible = AxisTranslationDrag.project(camera, pivot, visibleAxis, step);
        Vector2f projectedDepth = AxisTranslationDrag.project(camera, pivot, depthAxis, step);
        float length = projectedVisible.length();
        if (!Float.isFinite(length) || length <= 0f || !Float.isFinite(projectedDepth.lengthSquared())) {
            screenDirection = new Vector2f(1f, 0f);
            visibleUnitsPerPixel = 0f;
            depthUnitsPerPixel = 0f;
            coupledSlope = 0f;
            return;
        }

        screenDirection = projectedVisible.divideLocal(length);
        float perpendicular = screenDirection.x * projectedDepth.y - screenDirection.y * projectedDepth.x;
        float samplePixels = step / pixelSize;
        visibleUnitsPerPixel = step / Math.max(length, MIN_PROJECTED_SCALE * samplePixels);
        if (Math.abs(perpendicular) < END_ON_PROJECTION * samplePixels) {
            // The collapsed direction is controlled across the edge of the projected plane.
            depthUnitsPerPixel = Math.copySign(pixelSize, depthAxis.dot(view));
            coupledSlope = 0f;
        } else {
            depthUnitsPerPixel = Math.copySign(step / Math.max(Math.abs(perpendicular),
                    MIN_PROJECTED_SCALE * samplePixels), perpendicular);
            coupledSlope = projectedDepth.dot(screenDirection) / step;
        }
    }

    Vector3f translation(Vector2f pointer) {
        float dx = pointer.x - start.x;
        float dy = pointer.y - start.y;
        float depth = (screenDirection.x * dy - screenDirection.y * dx) * depthUnitsPerPixel;
        float along = (screenDirection.x * dx + screenDirection.y * dy - coupledSlope * depth)
                * visibleUnitsPerPixel;
        return visibleAxis.mult(along).addLocal(depthAxis.mult(depth));
    }
}
