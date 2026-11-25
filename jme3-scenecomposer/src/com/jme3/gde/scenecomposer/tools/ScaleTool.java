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
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import org.openide.loaders.DataObject;
import org.openide.util.Lookup;

/**
 * Scales along a picked handle using a frozen pointer projection.
 * <pre>
 * 第一步：按 R 切换缩放工具，按下 X、Y 或 Z 轴方块。
 * 第二步：沿显示的轴线拖动。轴线接近视线时向上拖动可增加轴向缩放。
 * 第三步：松开左键提交一次缩放，右键取消。平面方块仍使用原有缩放手势。
 * </pre>
 * @author sploreg, dokthar
 */
public class ScaleTool extends SceneEditTool {

    private Vector3f pickedMarker;
    private AxisTranslationDrag axisDrag;
    private Vector3f localScaleAxis;
    private Vector3f startScale;
    private Vector3f lastScale;
    private boolean wasDragging = false;
    private PickManager pickManager;

    public ScaleTool() {
        axisPickType = AxisMarkerPickType.axisAndPlane;
        setOverrideCameraControl(true);
    }

    @Override
    public void activate(AssetManager manager, Node toolNode, Node onTopToolNode, Spatial selectedSpatial, SceneComposerToolController toolController) {
        super.activate(manager, toolNode, onTopToolNode, selectedSpatial, toolController);
        pickManager = Lookup.getDefault().lookup(PickManager.class);
        displayPlanes();
        displayBoxes();
    }

    @Override
    public void actionPrimary(Vector2f screenCoord, boolean pressed, JmeNode rootNode, DataObject dataObject) {
        if (!pressed) {
            endScale();
        } else {
            if (toolController.getSelectedSpatial() == null) {
                return;
            }
            if (pickedMarker == null) {
                pickedMarker = pickAxisMarker(camera, screenCoord, axisPickType);
                if (pickedMarker == null) {
                    return;
                }

                if (pickedMarker.equals(QUAD_XY) || pickedMarker.equals(QUAD_XZ) || pickedMarker.equals(QUAD_YZ)) {
                    pickManager.initiatePick(toolController.getSelectedSpatial(), camera.getRotation(),
                            SceneComposerToolController.TransformationType.camera, camera, screenCoord);
                } else {
                    Vector3f worldAxis = axisMarker.getWorldRotation().mult(pickedMarker);
                    axisDrag = AxisTranslationDrag.forScale(camera, axisMarker.getWorldTranslation(), worldAxis, screenCoord);
                    localScaleAxis = toolController.getSelectedSpatial().getWorldRotation().inverse()
                            .mult(worldAxis).multLocal(.5f);
                }
                startScale = toolController.getSelectedSpatial().getLocalScale().clone();
                lastScale = startScale.clone();
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
            highlightAxisMarker(camera, screenCoord, axisPickType, true);
        }
    }

    @Override
    public void draggedPrimary(Vector2f screenCoord, boolean pressed, JmeNode rootNode, DataObject currentDataObject) {
        if (!pressed) {
            endScale();
        } else if (wasDragging) {
            Vector3f scale;
            if (axisDrag != null) {
                scale = startScale.add(localScaleAxis.mult(axisDrag.distance(screenCoord)));
            } else if (pickManager.updatePick(camera, screenCoord)) {
                Vector3f radialDirection = pickManager.getStartOffset().normalize();
                float distance = pickManager.getLocalTranslation(radialDirection).dot(radialDirection);
                scale = startScale.mult(1f + distance);
            } else {
                return;
            }
            toolController.updateSelectedScale(scale, pickedMarker);
            lastScale = scale;
            updateToolsTransformation();
        }
    }

    @Override
    public void draggedSecondary(Vector2f screenCoord, boolean pressed, JmeNode rootNode, DataObject currentDataObject) {
        if (pressed) {
            cancel();
        }
    }

    private void endScale() {
        if (wasDragging && !startScale.equals(lastScale)) {
            actionPerformed(new ScaleUndo(toolController.getSelectedSpatial(), startScale, lastScale));
        }
        resetDrag();
    }

    private void resetDrag() {
        wasDragging = false;
        setDefaultAxisMarkerColors();
        pickedMarker = null;
        axisDrag = null;
        localScaleAxis = null;
        pickManager.reset();
    }

    private void cancel() {
        if (wasDragging) {
            toolController.getSelectedSpatial().setLocalScale(startScale);
            resetDrag();
            updateToolsTransformation();
        }
    }

    private class ScaleUndo extends AbstractUndoableSceneEdit {

        private Spatial spatial;
        private Vector3f before, after;

        ScaleUndo(Spatial spatial, Vector3f before, Vector3f after) {
            this.spatial = spatial;
            this.before = before.clone();
            this.after = after.clone();
        }

        @Override
        public void sceneUndo() {
            spatial.setLocalScale(before);
            toolController.selectedSpatialTransformed();
            toolController.setNeedsSave(true);
        }

        @Override
        public void sceneRedo() {
            spatial.setLocalScale(after);
            toolController.selectedSpatialTransformed();
            toolController.setNeedsSave(true);
        }

    }
}
