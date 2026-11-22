package com.jme3.gde.scenecomposer.gizmo.light;

import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Spatial;
import com.jme3.scene.control.AbstractControl;

/** Keeps editor-only icons/arrows readable at a fixed vertical pixel size.
 * 第一步：只给灯光图标和方向箭头添加此 control；第二步：拉远相机检查尺寸。
 * Real radius/cone geometry must never use this control: it represents world units.
 * Runs on the render thread for the actual viewport camera, including orthographic views.
 */
public final class ScreenSizeControl extends AbstractControl {
    private final float pixels;
    private final float meshSize;
    private Vector3f offset;

    public ScreenSizeControl(float pixels, float meshSize) {
        this.pixels = pixels;
        this.meshSize = meshSize;
    }

    @Override
    public void setSpatial(Spatial spatial) {
        super.setSpatial(spatial);
        if (spatial != null) {
            offset = spatial.getLocalTranslation().clone();
            spatial.setCullHint(Spatial.CullHint.Never);
        }
    }

    static float worldPerPixel(Camera camera, Vector3f position) {
        float height = Math.max(1f, camera.getHeight()
                * (camera.getViewPortTop() - camera.getViewPortBottom()));
        float span = camera.getFrustumTop() - camera.getFrustumBottom();
        if (camera.isParallelProjection()) {
            return span / height;
        }
        float depth = position.subtract(camera.getLocation()).dot(camera.getDirection());
        return span * Math.max(camera.getFrustumNear(), depth) / camera.getFrustumNear() / height;
    }

    @Override
    protected void controlUpdate(float tpf) {
    }

    @Override
    protected void controlRender(RenderManager manager, ViewPort view) {
        Spatial parent = spatial.getParent();
        Vector3f center = parent == null ? spatial.getWorldTranslation() : parent.getWorldTranslation();
        float size = worldPerPixel(view.getCamera(), center) * pixels / meshSize;
        // Read actual transform scale: light callback nodes expose parameter values via getWorldScale().
        Vector3f parentScale = parent == null ? Vector3f.UNIT_XYZ : parent.getWorldTransform().getScale();
        Vector3f scale = new Vector3f(size / Math.max(0.000001f, Math.abs(parentScale.x)),
                size / Math.max(0.000001f, Math.abs(parentScale.y)),
                size / Math.max(0.000001f, Math.abs(parentScale.z)));
        spatial.setLocalScale(scale);
        spatial.setLocalTranslation(offset.mult(scale));
        spatial.updateGeometricState();
    }
}
