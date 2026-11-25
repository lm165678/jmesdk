/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.jme3.gde.scenecomposer.tools;

import com.jme3.asset.AssetManager;
import com.jme3.gde.core.sceneexplorer.nodes.JmeNode;
import com.jme3.gde.core.undoredo.AbstractUndoableSceneEdit;
import com.jme3.gde.scenecomposer.SceneComposerToolController;
import com.jme3.gde.scenecomposer.SceneEditTool;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Torus;
import org.openide.loaders.DataObject;

/**
 *
 * Drag the visible ring with E active. At grazing angles the drag follows a
 * fixed screen-space tangent for that ring. Shift still snaps to whole degrees
 * and the secondary button cancels the gesture.
 * <pre>
 * // 第一步：按 E 选择旋转工具，选择本地或世界坐标。
 * // 第二步：拖动蓝色 Z 环，侧对相机时沿屏幕上被压扁的环拖动。
 * // 第三步：按住 Shift 吸附整度，松开鼠标提交，右键取消。
 * </pre>
 *
 * @author kbender
 */
public class RotateTool extends SceneEditTool {

    private Vector3f pickedMarker;
    private Quaternion startRotate;
    private Quaternion lastRotate;
    private Vector3f localAxis;
    private AxisRotationDrag drag;
    private float appliedAngle;

    /**
     * Render-thread HUD value: degrees since this drag began, or NaN when idle.
     * Shift snaps the drag delta to whole degrees; release restores continuous rotation.
     * Resets on every handle press; release, cancellation and tool removal hide it.
     * <pre>
     * // 第一步：按 E 选择旋转工具；第二步：拖动旋转环查看本次累计角度，按住 Shift 吸附整度。
     * // 第三步：松开鼠标隐藏提示；再次拖动从零开始。
     * </pre>
     * @return signed accumulated degrees, or NaN outside a drag
     */
    public float getDragDegrees() {
        return drag == null ? Float.NaN : appliedAngle * FastMath.RAD_TO_DEG;
    }

    @Override
    public void hideMarker() {
        drag = null;
        pickedMarker = null;
        super.hideMarker();
    }

    /**
     * Keep the displayed rings in the coordinate frame captured at mouse-down.
     * Called on the render thread; release/cancel restores normal tracking on
     * the next update. This prevents direction-only light gizmos from changing
     * the displayed axes while a handle is held.
     */
    @Override
    public void doUpdateToolsTransformation() {
        if (drag == null) {
            super.doUpdateToolsTransformation();
        } else {
            updateAxisMarkerScale();
        }
    }

    public RotateTool() {
        axisPickType = SceneEditTool.AxisMarkerPickType.planeOnly;
        setOverrideCameraControl(true);
    }

    @Override
    public void activate(AssetManager manager, Node toolNode, Node onTopToolNode, Spatial selectedSpatial, SceneComposerToolController toolController) {
        super.activate(manager, toolNode, onTopToolNode, selectedSpatial, toolController);
        displayCircles();
    }

    @Override
    public void actionPrimary(Vector2f screenCoord, boolean pressed, JmeNode rootNode, DataObject dataObject) {
        if (!pressed) {
            finishDrag();
            return;
        }
        Spatial selected = toolController.getSelectedSpatial();
        if (selected == null || drag != null) {
            return;
        }
        pickedMarker = pickAxisMarker(camera, screenCoord, axisPickType);
        if (pickedMarker == null) {
            return;
        }

        String ringName = pickedMarker.equals(QUAD_XY) ? "circleXY"
                : pickedMarker.equals(QUAD_XZ) ? "circleXZ" : "circleYZ";
        Geometry ring = (Geometry) axisMarker.getChild(ringName);
        Vector3f worldAxis = ring.getWorldRotation().mult(Vector3f.UNIT_Z);
        float radius = ((Torus) ring.getMesh()).getOuterRadius() * ring.getWorldScale().x;
        localAxis = selected.getWorldRotation().inverse().mult(worldAxis).normalizeLocal();
        startRotate = selected.getLocalRotation().clone();
        lastRotate = startRotate.clone();
        appliedAngle = 0f;
        drag = new AxisRotationDrag(camera, ring.getWorldTranslation(), worldAxis, radius, screenCoord);
    }

    private void finishDrag() {
        if (drag != null && !startRotate.equals(lastRotate)) {
            actionPerformed(new RotateUndo(toolController.getSelectedSpatial(), startRotate, lastRotate));
        }
        drag = null;
        pickedMarker = null;
        setDefaultAxisMarkerColors();
    }

    @Override
    public void actionSecondary(Vector2f screenCoord, boolean pressed, JmeNode rootNode, DataObject dataObject) {
        if (pressed) {
            cancel();
        }
    }

    @Override
    public void mouseMoved(Vector2f screenCoord, JmeNode rootNode, DataObject currentDataObject) {
        if (drag == null) {
            highlightAxisMarker(camera, screenCoord, axisPickType);
        }
    }

    @Override
    public void draggedPrimary(Vector2f screenCoord, boolean pressed, JmeNode rootNode, DataObject currentDataObject) {
        if (!pressed) {
            finishDrag();
        } else if (drag != null) {
            float angle = drag.update(screenCoord);
            appliedAngle = toolController.isRotationSnapEnabled()
                    ? Math.round(angle * FastMath.RAD_TO_DEG) * FastMath.DEG_TO_RAD : angle;
            Quaternion delta = new Quaternion().fromAngleAxis(appliedAngle, localAxis);
            Quaternion rotation = startRotate.mult(delta);
            toolController.updateSelectedRotation(rotation, pickedMarker);
            lastRotate = rotation;
            updateToolsTransformation();
        }
    }

    @Override
    public void draggedSecondary(Vector2f screenCoord, boolean pressed, JmeNode rootNode, DataObject currentDataObject) {
        if (pressed) {
            cancel();
        }
    }

    private void cancel() {
        if (drag != null) {
            toolController.getSelectedSpatial().setLocalRotation(startRotate);
            drag = null;
            setDefaultAxisMarkerColors();
            pickedMarker = null;
        }
    }

    private class RotateUndo extends AbstractUndoableSceneEdit {

        private Spatial spatial;
        private Quaternion before, after;

        RotateUndo(Spatial spatial, Quaternion before, Quaternion after) {
            this.spatial = spatial;
            this.before = before.clone();
            this.after = after.clone();
        }

        @Override
        public void sceneUndo() {
            spatial.setLocalRotation(before);
            toolController.selectedSpatialTransformed();
            toolController.setNeedsSave(true);
        }

        @Override
        public void sceneRedo() {
            spatial.setLocalRotation(after);
            toolController.selectedSpatialTransformed();
            toolController.setNeedsSave(true);
        }
    }
}
