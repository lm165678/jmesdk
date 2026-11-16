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
import com.jme3.math.Quaternion;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import org.openide.loaders.DataObject;
import org.openide.util.Lookup;

/**
 *
 * @author kbender
 */
public class RotateTool extends SceneEditTool {

    private Vector3f pickedMarker;
    private Quaternion startRotate;
    private Quaternion startWorldRotate;
    private Quaternion lastRotate;
    private boolean wasDragging = false;
    private PickManager pickManager;
    private float previousAngle;
    private float accumulatedAngle;
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
        return wasDragging ? appliedAngle * com.jme3.math.FastMath.RAD_TO_DEG : Float.NaN;
    }

    @Override
    public void hideMarker() {
        wasDragging = false;
        pickedMarker = null;
        if (pickManager != null) {
            pickManager.reset();
        }
        super.hideMarker();
    }

    /**
     * Keep the displayed rings in the same frame as PickManager's drag plane.
     * Called on the render thread; release/cancel restores normal tracking on
     * the next update. This prevents direction-only light gizmos from changing
     * the displayed axes while a handle is held.
     */
    @Override
    public void doUpdateToolsTransformation() {
        if (!wasDragging) {
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
        pickManager = Lookup.getDefault().lookup(PickManager.class);
        displayCircles();
    }

    @Override
    public void actionPrimary(Vector2f screenCoord, boolean pressed, JmeNode rootNode, DataObject dataObject) {
        if (!pressed) {
            setDefaultAxisMarkerColors();
            pickedMarker = null; // mouse released, reset selection
            if (wasDragging) {
                if (!startRotate.equals(lastRotate)) {
                    actionPerformed(new RotateUndo(toolController.getSelectedSpatial(), startRotate, lastRotate));
                }
                wasDragging = false;
            }
            pickManager.reset();
        } else {
            if (toolController.getSelectedSpatial() == null) {
                return;
            }

            if (pickedMarker == null) {
                pickedMarker = pickAxisMarker(camera, screenCoord, axisPickType);
                if (pickedMarker == null) {
                    return;
                }

                if (pickedMarker.equals(QUAD_XY)) {
                    pickManager.initiatePick(toolController.getSelectedSpatial(), PickManager.PLANE_XY, getTransformType(), camera, screenCoord);
                } else if (pickedMarker.equals(QUAD_XZ)) {
                    pickManager.initiatePick(toolController.getSelectedSpatial(), PickManager.PLANE_XZ, getTransformType(), camera, screenCoord);
                } else if (pickedMarker.equals(QUAD_YZ)) {
                    pickManager.initiatePick(toolController.getSelectedSpatial(), PickManager.PLANE_YZ, getTransformType(), camera, screenCoord);
                }
                startRotate = toolController.getSelectedSpatial().getLocalRotation().clone();
                lastRotate = startRotate.clone();
                startWorldRotate = toolController.getSelectedSpatial().getWorldRotation().clone();
                previousAngle = 0;
                accumulatedAngle = 0;
                appliedAngle = 0;
                wasDragging = true;
            }
        }
    }

    @Override
    public void actionSecondary(Vector2f screenCoord, boolean pressed, JmeNode rootNode, DataObject dataObject) {
        if (pressed) {
            cancel();
        }
    }

    @Override
    public void mouseMoved(Vector2f screenCoord, JmeNode rootNode, DataObject currentDataObject) {
        if (!wasDragging) {
            highlightAxisMarker(camera, screenCoord, axisPickType);
        }
    }

    @Override
    public void draggedPrimary(Vector2f screenCoord, boolean pressed, JmeNode rootNode, DataObject currentDataObject) {
        if (!pressed) {
            setDefaultAxisMarkerColors();
            pickedMarker = null; // mouse released, reset selection

            if (wasDragging) {
                if (!startRotate.equals(lastRotate)) {
                    actionPerformed(new RotateUndo(toolController.getSelectedSpatial(), startRotate, lastRotate));
                }
                wasDragging = false;
            }
            pickManager.reset();
        } else if (wasDragging) {
            if (!pickManager.updatePick(camera, screenCoord)) {
                return;
            }

            if (pickedMarker.equals(QUAD_XY) || pickedMarker.equals(QUAD_XZ) || pickedMarker.equals(QUAD_YZ)) {
                float angle = pickManager.getSignedAngle();
                float delta = angle - previousAngle;
                if (delta > com.jme3.math.FastMath.PI) {
                    delta -= com.jme3.math.FastMath.TWO_PI;
                } else if (delta < -com.jme3.math.FastMath.PI) {
                    delta += com.jme3.math.FastMath.TWO_PI;
                }
                accumulatedAngle += delta;
                previousAngle = angle;
                appliedAngle = toolController.isRotationSnapEnabled()
                        ? Math.round(accumulatedAngle * com.jme3.math.FastMath.RAD_TO_DEG)
                                * com.jme3.math.FastMath.DEG_TO_RAD
                        : accumulatedAngle;
                Quaternion rotation = startRotate.mult(
                        pickManager.getRotationForAngle(startWorldRotate.inverse(), appliedAngle));
                toolController.updateSelectedRotation(rotation, pickedMarker);
                lastRotate = rotation;
            }
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
        if (wasDragging) {
            wasDragging = false;
            toolController.getSelectedSpatial().setLocalRotation(startRotate);
            setDefaultAxisMarkerColors();
            pickedMarker = null; // mouse released, reset selection
            pickManager.reset();
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
