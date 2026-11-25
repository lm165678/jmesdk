/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.jme3.gde.scenecomposer.tools;

import com.jme3.asset.AssetManager;
import com.jme3.bullet.control.CharacterControl;
import com.jme3.bullet.control.RigidBodyControl;
import com.jme3.gde.core.sceneexplorer.nodes.JmeNode;
import com.jme3.gde.core.undoredo.AbstractUndoableSceneEdit;
import com.jme3.gde.scenecomposer.SceneComposerToolController;
import com.jme3.gde.scenecomposer.SceneEditTool;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import org.openide.loaders.DataObject;

/**
 * Drag an arrow to move along one axis, or a square to move in its plane.
 * When the arrow points into the screen, dragging up moves away from the view.
 * Edge-on planes use a bounded screen basis for the entire gesture.
 * <pre>
 * 第一步：按 W，选择平移轴线或平面方块。
 * 第二步：轴线朝向相机时可上下拖动。平面侧对相机时，沿平面投影线或横过投影线拖动。
 * 第三步：松开左键提交一次平移，右键取消。
 * </pre>
 *
 * @author Brent Owens
 */
public class MoveTool extends SceneEditTool {

    private Vector3f pickedMarker;
    private AxisTranslationDrag axisDrag;
    private PlaneTranslationDrag planeDrag;
    private boolean wasDragging = false;
    private Vector3f startPosition;
    private Vector3f lastPosition;
    private GroupMoveGesture groupMove;
    private boolean duplicateGesture;
    private Spatial duplicateSource;
    private Spatial duplicate;
    private Node duplicateParent;

    /**
     * Shift is sampled when a translation handle is pressed. The first actual
     * motion clones the selected scene spatial once; release records one undo
     * transaction for creation plus translation. Right-click cancels it. Roots,
     * read-only linked nodes and editor-only markers cannot be duplicated.
     * <pre>
     * 第一步：选中场景物体，按 W 切换平移工具。
     * 第二步：按住 Shift 后拖动坐标轴，生成并移动一个副本。
     * 第三步：松开左键完成；右键取消，Ctrl+Z 撤销整个复制移动。
     * </pre>
     */
    @Override
    public void hideMarker() {
        cancel();
        super.hideMarker();
    }

    private boolean beginDuplicate() {
        Spatial source = toolController.getSelectedSpatial();
        JmeNode root = toolController.getRootNode();
        Spatial scene = root.getLookup().lookup(Spatial.class);
        if (source == null || source == scene || source.getParent() == null
                || !(scene instanceof Node) || !source.hasAncestor((Node) scene) || root.isReadOnly()) {
            return false;
        }
        // Linked scene children must be edited in their owning asset.
        for (Spatial parent = source; parent != scene; parent = parent.getParent()) {
            if (parent instanceof com.jme3.scene.AssetLinkNode) {
                return false;
            }
        }
        duplicateSource = source;
        duplicateParent = source.getParent();
        duplicate = source.clone(true);
        String base = (source.getName() == null ? "Object" : source.getName()) + " Copy";
        String name = base;
        int suffix = 2;
        while (duplicateParent.getChild(name) != null) {
            name = base + " " + suffix++;
        }
        duplicate.setName(name);
        duplicateParent.attachChild(duplicate);
        // Ancestor overrides/lights may still be dirty during AppState.update.
        // Geometric updates must start above all such ancestors, not at this subtree.
        Spatial updateRoot = duplicateParent;
        while (updateRoot.getParent() != null) {
            updateRoot = updateRoot.getParent();
        }
        updateRoot.updateGeometricState();
        toolController.setSelected(duplicate);
        return true;
    }

    private void refreshDuplicateSelection(Node parent, Spatial selected) {
        JmeNode root = toolController.getRootNode();
        java.awt.EventQueue.invokeLater(() -> {
            var parentNode = root.getChild(parent);
            if (parentNode != null) {
                parentNode.refresh(true);
            }
            var selectedNode = root.getChild(selected);
            if (selectedNode != null) {
                com.jme3.gde.core.sceneviewer.SceneViewerTopComponent.findInstance()
                        .setActivatedNodes(new org.openide.nodes.Node[]{selectedNode});
                com.jme3.gde.core.sceneexplorer.SceneExplorerTopComponent.findInstance()
                        .setSelectedNode(new com.jme3.gde.core.sceneexplorer.nodes.AbstractSceneExplorerNode[]{selectedNode});
            }
        });
    }

    private void finishMove() {
        if (!wasDragging) {
            return;
        }
        wasDragging = false;
        if (groupMove != null) {
            var completed = groupMove;
            groupMove = null;
            var edit = completed.finish();
            if (edit != null) {
                actionPerformed(edit);
            }
            duplicateGesture = false;
            return;
        }
        if (duplicate != null) {
            final Spatial copy = duplicate;
            final Spatial source = duplicateSource;
            final Node parent = duplicateParent;
            final Vector3f position = copy.getLocalTranslation().clone();
            actionPerformed(new AbstractUndoableSceneEdit() {
                @Override
                public void sceneUndo() {
                    copy.removeFromParent();
                    toolController.setSelected(source);
                    toolController.selectedSpatialTransformed();
                    toolController.setNeedsSave(true);
                    refreshDuplicateSelection(parent, source);
                }
                @Override
                public void sceneRedo() {
                    copy.setLocalTranslation(position);
                    parent.attachChild(copy);
                    toolController.setSelected(copy);
                    toolController.selectedSpatialTransformed();
                    toolController.setNeedsSave(true);
                    refreshDuplicateSelection(parent, copy);
                }
            });
            refreshDuplicateSelection(parent, copy);
        } else if (!startPosition.equals(lastPosition)) {
            actionPerformed(new MoveUndo(toolController.getSelectedSpatial(), startPosition, lastPosition));
        }
        duplicate = null;
        duplicateSource = null;
        duplicateParent = null;
        duplicateGesture = false;
    }

    public MoveTool() {
        axisPickType = SceneEditTool.AxisMarkerPickType.axisAndPlane;
        setOverrideCameraControl(true);

    }

    @Override
    public void activate(AssetManager manager, Node toolNode, Node onTopToolNode, Spatial selectedSpatial, SceneComposerToolController toolController) {
        super.activate(manager, toolNode, onTopToolNode, selectedSpatial, toolController);
        displayPlanes();
        displayCones();
    }

    @Override
    public void actionPrimary(Vector2f screenCoord, boolean pressed, JmeNode rootNode, DataObject dataObject) {
        if (!pressed) {
            setDefaultAxisMarkerColors();
            pickedMarker = null; // mouse released, reset selection
            axisDrag = null;
            planeDrag = null;
            finishMove();
        } else {
            if (toolController.getSelectedSpatial() == null) {
                return;
            }

            if (pickedMarker == null) {
                pickedMarker = pickAxisMarker(camera, screenCoord, axisPickType);
                if (pickedMarker == null) {
                    return;
                }

                var markerRotation = axisMarker.getWorldRotation();
                Vector3f pivot = axisMarker.getWorldTranslation();
                if (pickedMarker.equals(QUAD_XY)) {
                    planeDrag = new PlaneTranslationDrag(camera, pivot, markerRotation.mult(Vector3f.UNIT_Z),
                            markerRotation.mult(Vector3f.UNIT_X), screenCoord);
                } else if (pickedMarker.equals(QUAD_XZ)) {
                    planeDrag = new PlaneTranslationDrag(camera, pivot, markerRotation.mult(Vector3f.UNIT_Y),
                            markerRotation.mult(Vector3f.UNIT_X), screenCoord);
                } else if (pickedMarker.equals(QUAD_YZ)) {
                    planeDrag = new PlaneTranslationDrag(camera, pivot, markerRotation.mult(Vector3f.UNIT_X),
                            markerRotation.mult(Vector3f.UNIT_Y), screenCoord);
                } else {
                    axisDrag = new AxisTranslationDrag(camera, pivot, markerRotation.mult(pickedMarker), screenCoord);
                }
                duplicateGesture = toolController.isRotationSnapEnabled();
                startPosition = toolController.getSelectedSpatial().getLocalTranslation().clone();
                lastPosition = startPosition.clone();
                if (toolController.getSelectedSpatials().size() > 1) {
                    groupMove = new GroupMoveGesture(toolController, duplicateGesture);
                }
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
            axisDrag = null;
            planeDrag = null;
            finishMove();
        } else if (wasDragging) {
            Vector3f diff = axisDrag == null ? planeDrag.translation(screenCoord) : axisDrag.translation(screenCoord);
            if (groupMove != null) {
                if (diff.lengthSquared() > 1e-10f) {
                    groupMove.move(diff);
                    updateToolsTransformation();
                }
                return;
            }
            if (duplicateGesture && duplicate == null && diff.lengthSquared() > 1e-10f) {
                if (!beginDuplicate()) {
                    cancel();
                    return;
                }
            }
            Vector3f position;
            Spatial parent = toolController.getSelectedSpatial().getParent();
            if (parent != null) {
                position = startPosition.add(parent.getWorldRotation().inverse().mult(diff).divideLocal(parent.getWorldScale()));
            } else {
                position = startPosition.add(diff);
            }
            lastPosition = position;
            toolController.updateSelectedTranslation(position, pickedMarker);
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
            if (groupMove != null) {
                groupMove.cancel();
                groupMove = null;
            } else if (duplicate != null) {
                duplicate.removeFromParent();
                toolController.setSelected(duplicateSource);
                refreshDuplicateSelection(duplicateParent, duplicateSource);
                duplicate = null;
                duplicateSource = null;
                duplicateParent = null;
            } else {
                toolController.getSelectedSpatial().setLocalTranslation(startPosition);
            }
            duplicateGesture = false;
            toolController.selectedSpatialTransformed();
            setDefaultAxisMarkerColors();
            pickedMarker = null; // mouse released, reset selection
            axisDrag = null;
            planeDrag = null;
        }
    }

    protected class MoveUndo extends AbstractUndoableSceneEdit {

        private Spatial spatial;
        private Vector3f before = new Vector3f(), after = new Vector3f();

        MoveUndo(Spatial spatial, Vector3f before, Vector3f after) {
            this.spatial = spatial;
            this.before.set(before);
            if (after != null) {
                this.after.set(after);
            }
        }

        @Override
        public void sceneUndo() {
            spatial.setLocalTranslation(before);
            RigidBodyControl control = spatial.getControl(RigidBodyControl.class);
            if (control != null) {
                control.setPhysicsLocation(spatial.getWorldTranslation());
            }
            CharacterControl character = spatial.getControl(CharacterControl.class);
            if (character != null) {
                character.setPhysicsLocation(spatial.getWorldTranslation());
            }
            toolController.selectedSpatialTransformed();
            toolController.setNeedsSave(true);
        }

        @Override
        public void sceneRedo() {
            spatial.setLocalTranslation(after);
            RigidBodyControl control = spatial.getControl(RigidBodyControl.class);
            if (control != null) {
                control.setPhysicsLocation(spatial.getWorldTranslation());
            }
            CharacterControl character = spatial.getControl(CharacterControl.class);
            if (character != null) {
                character.setPhysicsLocation(spatial.getWorldTranslation());
            }
            toolController.selectedSpatialTransformed();
            toolController.setNeedsSave(true);
        }

        public void setAfter(Vector3f after) {
            this.after.set(after);
        }
    }

}
