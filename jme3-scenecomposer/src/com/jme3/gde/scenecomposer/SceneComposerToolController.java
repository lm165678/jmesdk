/*
 * Copyright (c) 2009-2022 jMonkeyEngine
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * * Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 *
 * * Neither the name of 'jMonkeyEngine' nor the names of its contributors
 *   may be used to endorse or promote products derived from this software
 *   without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.jme3.gde.scenecomposer;

import com.jme3.asset.AssetManager;
import com.jme3.collision.CollisionResult;
import com.jme3.collision.CollisionResults;
import com.jme3.gde.core.scene.SceneApplication;
import com.jme3.gde.core.scene.controller.SceneToolController;
import com.jme3.gde.core.sceneexplorer.nodes.AbstractSceneExplorerNode;
import com.jme3.gde.core.sceneexplorer.nodes.JmeNode;
import com.jme3.gde.scenecomposer.gizmo.GizmoFactory;
import com.jme3.gde.scenecomposer.tools.shortcuts.ShortcutManager;
import com.jme3.input.event.KeyInputEvent;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Ray;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import org.openide.util.Lookup;

/**
 *
 * @author Brent Owens
 */
public class SceneComposerToolController extends SceneToolController {

    private JmeNode rootNode;
    private SceneEditTool editTool;
    private final EditorShortcutPolicy shortcuts = new EditorShortcutPolicy();
    private boolean selectingWithPrimary;
    private SceneEditorController editorController;
    private ViewPort overlayView;
    private Node onTopToolsNode;
    private Node nonSpatialMarkersNode;
    private HashMap<AbstractSceneExplorerNode, Spatial> nonSpatialMarkers;

    private volatile boolean snapToGrid = false;
    private volatile boolean snapToScene = false;
    private volatile boolean selectTerrain = false;
    private volatile boolean selectGeometries = true;
    private TransformationType transformationType = TransformationType.local;
    
    private final float fifteenDegs = FastMath.HALF_PI / 6f;

    public enum TransformationType {
        local, global, camera
    }

    public SceneComposerToolController(final Node toolsNode, AssetManager manager, JmeNode rootNode) {
        super(toolsNode, manager);
        this.rootNode = rootNode;
        nonSpatialMarkersNode = new Node("lightMarkersNode");
        nonSpatialMarkers = new HashMap<AbstractSceneExplorerNode, Spatial>();
        SceneApplication.getApplication().enqueue(new Callable<Object>() {

            public Object call() throws Exception {
                nonSpatialMarkersNode.addControl(new com.jme3.scene.control.AbstractControl() {
                    private int appliedMask = -1;
                    @Override
                    protected void controlUpdate(float tpf) {
                        int mask = SceneApplication.getApplication().getHiddenLightGizmos();
                        if (mask != appliedMask) {
                            refreshMarkerVisibility();
                            appliedMask = mask;
                        }
                    }
                    @Override
                    protected void controlRender(com.jme3.renderer.RenderManager rm,
                            com.jme3.renderer.ViewPort vp) {
                    }
                });
                toolsNode.attachChild(nonSpatialMarkersNode);
                return null;
            }
        });
        setShowGrid(showGrid);
    }

    public SceneComposerToolController(AssetManager manager) {
        super(manager);
    }

    public void setEditorController(SceneEditorController editorController) {
        this.editorController = editorController;
    }

    public void createOnTopToolNode() {
        // a node in a viewport that will always render on top
        overlayView = SceneApplication.getApplication().getOverlayView();
        SceneApplication.getApplication().enqueue(new Callable<Void>() {

            public Void call() throws Exception {
                onTopToolsNode = new Node("OverlayNode");
                onTopToolsNode.updateGeometricState();
                overlayView.attachScene(onTopToolsNode);
                return null;
            }
        });
    }

    @Override
    public void cleanup() {
        super.cleanup();
        editorController = null;
        SceneApplication.getApplication().enqueue(new Callable<Void>() {

            public Void call() throws Exception {
                overlayView.detachScene(onTopToolsNode);
                onTopToolsNode.detachAllChildren();
                return null;
            }
        });
    }

    @Override
    public void update(float tpf) {
        super.update(tpf);
        if (editTool != null) {
            editTool.doUpdateToolsTransformation();
        }
        if (onTopToolsNode != null) {
            onTopToolsNode.updateLogicalState(tpf);
        }
    }

    @Override
    public void render(RenderManager rm) {
        super.render(rm);
        // SceneApplication finalizes all overlay roots immediately before drawing.
        // A controller callback cannot guarantee that it runs last in the frame.
    }

    public boolean isEditToolEnabled() {
        return editTool != null;
    }

    /**
     * If the current tool overrides camera zoom/pan controls
     *
     * @return
     */
    public boolean isOverrideCameraControl() {
        if (editTool != null) {
            return editTool.isOverrideCameraControl();
        } else {
            return false;
        }
    }

    /**
     * Scene composer edit tool activated. Pass in null to remove tools.
     *
     * @param sceneEditTool pass in null to hide any existing tool markers
     */
    public void showEditTool(final SceneEditTool sceneEditTool) {
        SceneApplication.getApplication().enqueue(new Callable<Object>() {

            public Object call() throws Exception {
                doEnableEditTool(sceneEditTool);
                return null;
            }
        });
    }

    private void doEnableEditTool(SceneEditTool sceneEditTool) {
        if (selected instanceof com.jme3.environment.gi.GiProbeVolume
                && sceneEditTool instanceof com.jme3.gde.scenecomposer.tools.RotateTool) {
            return;
        }
        if (editTool != null) {
            editTool.hideMarker();
        }
        editTool = sceneEditTool;
        editTool.activate(manager, toolsNode, onTopToolsNode, selected, this);
        java.awt.EventQueue.invokeLater(() -> SceneComposerTopComponent.findInstance().showSelectedTool(sceneEditTool));
    }

    public void selectedSpatialTransformed() {
        if (editTool != null) {
            editTool.updateToolsTransformation();
        }
    }

    @Override
    public void doUpdateSelection(Spatial spatial) {
        super.doUpdateSelection(spatial);
        if (spatial instanceof com.jme3.environment.gi.GiProbeVolume
                && editTool instanceof com.jme3.gde.scenecomposer.tools.RotateTool) {
            doEnableEditTool(new com.jme3.gde.scenecomposer.tools.MoveTool());
        }
    }

    public void setSelected(Spatial selected) {
        this.selected = selected;
        if (selected instanceof com.jme3.environment.gi.GiProbeVolume
                && editTool instanceof com.jme3.gde.scenecomposer.tools.RotateTool) {
            doEnableEditTool(new com.jme3.gde.scenecomposer.tools.MoveTool());
        }
    }

    public void setNeedsSave(boolean needsSave) {
        editorController.setNeedsSave(needsSave);
    }

    /**
     * Primary button activated, send command to the tool for appropriate
     * action.
     *
     * @param mouseLoc
     * @param pressed
     * @param camera
     */
    public void doEditToolActivatedPrimary(Vector2f mouseLoc, boolean pressed, Camera camera) {
        ShortcutManager scm = Lookup.getDefault().lookup(ShortcutManager.class);

        if (scm.isActive()) {
            scm.getActiveShortcut().setCamera(camera);
            scm.getActiveShortcut().actionPrimary(mouseLoc, pressed, rootNode, editorController.getCurrentDataObject());
        } else if (editTool != null) {
            editTool.setCamera(camera);
            if (pressed) {
                selectingWithPrimary = !SceneApplication.getApplication().isEditorHelpersVisible()
                        || editTool instanceof com.jme3.gde.scenecomposer.tools.SelectTool
                        || editTool.pickAxisMarker(camera, mouseLoc, editTool.axisPickType) == null;
            }
            if (selectingWithPrimary) {
                com.jme3.gde.scenecomposer.tools.SelectTool selection = new com.jme3.gde.scenecomposer.tools.SelectTool();
                if (pressed) {
                    selection.selectAt(mouseLoc, camera, rootNode, editorController.getCurrentDataObject(), this);
                }
            } else {
                editTool.actionPrimary(mouseLoc, pressed, rootNode, editorController.getCurrentDataObject());
            }
            if (!pressed) {
                selectingWithPrimary = false;
            }
        }
    }

    /**
     * Secondary button activated, send command to the tool for appropriate
     * action.
     *
     * @param mouseLoc
     * @param pressed
     * @param camera
     */
    public void doEditToolActivatedSecondary(Vector2f mouseLoc, boolean pressed, Camera camera) {
        ShortcutManager scm = Lookup.getDefault().lookup(ShortcutManager.class);

        if (scm.isActive()) {
            scm.getActiveShortcut().setCamera(camera);
            scm.getActiveShortcut().actionSecondary(mouseLoc, pressed, rootNode, editorController.getCurrentDataObject());
        } else if (editTool != null) {
            editTool.setCamera(camera);
            editTool.actionSecondary(mouseLoc, pressed, rootNode, editorController.getCurrentDataObject());
        }
    }

    public void doEditToolMoved(Vector2f mouseLoc, Camera camera) {
        ShortcutManager scm = Lookup.getDefault().lookup(ShortcutManager.class);

        if (scm.isActive()) {
            scm.getActiveShortcut().setCamera(camera);
            scm.getActiveShortcut().mouseMoved(mouseLoc, rootNode, editorController.getCurrentDataObject());
        } else if (editTool != null && editorController != null) {
            editTool.setCamera(camera);
            editTool.mouseMoved(mouseLoc, rootNode, editorController.getCurrentDataObject());
        }
    }

    /** Whether the current primary gesture is selection rather than a handle drag. */
    boolean allowsPrimaryOrbit() {
        ShortcutManager shortcuts = Lookup.getDefault().lookup(ShortcutManager.class);
        return !shortcuts.isActive() && (selectingWithPrimary || editTool == null);
    }

    public void doEditToolDraggedPrimary(Vector2f mouseLoc, boolean pressed, Camera camera) {
        if (selectingWithPrimary) {
            return;
        }
        ShortcutManager scm = Lookup.getDefault().lookup(ShortcutManager.class);

        if (scm.isActive()) {
            scm.getActiveShortcut().setCamera(camera);
            scm.getActiveShortcut().draggedPrimary(mouseLoc, pressed, rootNode, editorController.getCurrentDataObject());
        } else if (editTool != null) {
            editTool.setCamera(camera);
            editTool.draggedPrimary(mouseLoc, pressed, rootNode, editorController.getCurrentDataObject());
        }
    }

    public void doEditToolDraggedSecondary(Vector2f mouseLoc, boolean pressed, Camera camera) {
        ShortcutManager scm = Lookup.getDefault().lookup(ShortcutManager.class);

        if (scm.isActive()) {
            scm.getActiveShortcut().setCamera(null);
            scm.getActiveShortcut().draggedSecondary(mouseLoc, pressed, rootNode, editorController.getCurrentDataObject());
        } else if (editTool != null) {
            editTool.setCamera(camera);
            editTool.draggedSecondary(mouseLoc, pressed, rootNode, editorController.getCurrentDataObject());
        }
    }

    /**
     * Only unmodified Q/W/E/R switch handles. Clipboard/save/undo shortcuts stay
     * owned by NetBeans; legacy modal transform shortcuts are intentionally disabled.
     */
    public void doKeyPressed(KeyInputEvent kie) {
        doKeyPressed(kie, true);
    }

    private volatile java.util.List<Spatial> selectedSpatials = java.util.List.of();
    private boolean leftControl;
    private boolean rightControl;

    /** EDT selection snapshot; transform gestures consume it on the render thread. */
    public void setSelectedSpatials(java.util.List<Spatial> values) {
        selectedSpatials = java.util.List.copyOf(values);
    }

    public java.util.List<Spatial> getSelectedSpatials() {
        return selectedSpatials;
    }

    public boolean isAdditiveSelection() {
        return leftControl || rightControl;
    }

    private boolean leftShift;
    private boolean rightShift;

    /** Render-thread modifier state; Shift snaps rotation to one-degree increments. */
    public boolean isRotationSnapEnabled() {
        return leftShift || rightShift;
    }

    void doKeyPressed(KeyInputEvent kie, boolean allowSwitch) {
        if (kie.getKeyCode() == com.jme3.input.KeyInput.KEY_LSHIFT) {
            leftShift = kie.isPressed();
        } else if (kie.getKeyCode() == com.jme3.input.KeyInput.KEY_RSHIFT) {
            rightShift = kie.isPressed();
        }
        if (kie.getKeyCode() == com.jme3.input.KeyInput.KEY_LCONTROL) {
            leftControl = kie.isPressed();
        } else if (kie.getKeyCode() == com.jme3.input.KeyInput.KEY_RCONTROL) {
            rightControl = kie.isPressed();
        }
        int code = shortcuts.accept(kie);
        if (!allowSwitch) {
            return;
        }
        if (code == com.jme3.input.KeyInput.KEY_G) {
            SceneApplication.getApplication().toggleEditorHelpers();
            return;
        }
        selectTransform(code);
    }

    /** Render-thread entry point shared by keyboard and viewport buttons. */
    void selectTransform(int code) {
        SceneEditTool replacement = switch (code) {
            case com.jme3.input.KeyInput.KEY_Q -> new com.jme3.gde.scenecomposer.tools.SelectTool();
            case com.jme3.input.KeyInput.KEY_W -> new com.jme3.gde.scenecomposer.tools.MoveTool();
            case com.jme3.input.KeyInput.KEY_E -> new com.jme3.gde.scenecomposer.tools.RotateTool();
            case com.jme3.input.KeyInput.KEY_R -> new com.jme3.gde.scenecomposer.tools.ScaleTool();
            default -> null;
        };
        if (replacement != null) {
            doEnableEditTool(replacement);

        }
    }

    float rotationDragDegrees() {
        if (editTool instanceof com.jme3.gde.scenecomposer.tools.RotateTool rotate) {
            return rotate.getDragDegrees();
        }
        return Float.NaN;
    }

    int activeTransform() {
        if (editTool instanceof com.jme3.gde.scenecomposer.tools.SelectTool) {
            return com.jme3.input.KeyInput.KEY_Q;
        }
        if (editTool instanceof com.jme3.gde.scenecomposer.tools.MoveTool) {
            return com.jme3.input.KeyInput.KEY_W;
        }
        if (editTool instanceof com.jme3.gde.scenecomposer.tools.RotateTool) {
            return com.jme3.input.KeyInput.KEY_E;
        }
        if (editTool instanceof com.jme3.gde.scenecomposer.tools.ScaleTool) {
            return com.jme3.input.KeyInput.KEY_R;
        }
        return -1;
    }

    void resetShortcutState() {
        leftControl = false;
        rightControl = false;
        leftShift = false;
        rightShift = false;
        shortcuts.reset();
    }

    protected void refreshNonSpatialMarkers() {
        addMarkers();
    }

    private void getNodes(org.openide.nodes.Node node, List<AbstractSceneExplorerNode> list) {
        if (node instanceof AbstractSceneExplorerNode) {
            list.add((AbstractSceneExplorerNode) node);
        }
        if (!node.isLeaf()) {
            for (org.openide.nodes.Node n : node.getChildren().getNodes(true)) {
                getNodes(n, list);
            }
        }
    }

    private void addMarkers() {
        final List<AbstractSceneExplorerNode> nodes = new ArrayList<AbstractSceneExplorerNode>();
        // gather nodes, have to be in an other thread than the sceneApplication
        getNodes(rootNode, nodes);
        
        // then update markers
        SceneApplication.getApplication().enqueue(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                
                Iterator<AbstractSceneExplorerNode> it = nonSpatialMarkers.keySet().iterator();
                while (it.hasNext()) {
                    AbstractSceneExplorerNode n = it.next();
                    if (nodes.contains(n)) {
                        // a node is already added
                        nodes.remove(n);
                    } else {
                        // a node is no more needed
                        nonSpatialMarkers.get(n).removeFromParent();
                        it.remove();
                        //nonSpatialMarkers.remove(n);
                    }
                }

                it = nodes.iterator();
                while (it.hasNext()) {
                    AbstractSceneExplorerNode n = it.next();
                    if (!nonSpatialMarkers.containsKey(n)) {
                        Spatial s = GizmoFactory.createGizmo(manager, n);
                        if (s != null) {
                            nonSpatialMarkers.put(n, s);
                            applyMarkerVisibility(n, s);
                        }
                    }
                }
                return null;
            }
        });
    }

    private void refreshMarkerVisibility() {
        for (var entry : nonSpatialMarkers.entrySet()) {
            applyMarkerVisibility(entry.getKey(), entry.getValue());
        }
    }

    private void applyMarkerVisibility(AbstractSceneExplorerNode node, Spatial marker) {
        com.jme3.light.Light light = node.getLookup().lookup(com.jme3.light.Light.class);
        boolean visible = light == null
                || SceneApplication.getApplication().isLightGizmosVisible(light.getType());
        if (visible) {
            if (marker.getParent() != nonSpatialMarkersNode) {
                nonSpatialMarkersNode.attachChild(marker);
            }
        } else {
            // Detach also excludes hidden editor geometry from mouse picking.
            marker.removeFromParent();
        }
    }

    public Spatial getMarker(AbstractSceneExplorerNode node) {
        return nonSpatialMarkers.get(node);
    }

    public boolean isSnapToGrid() {
        return snapToGrid;
    }

    public void setSnapToGrid(boolean snapToGrid) {
        this.snapToGrid = snapToGrid;
    }

    public void setSnapToScene(boolean snapToScene) {
        this.snapToScene = snapToScene;
    }

    public boolean isSnapToScene() {
        return snapToScene;
    }

    public boolean isSelectTerrain() {
        return selectTerrain;
    }

    public void setSelectTerrain(boolean selectTerrain) {
        this.selectTerrain = selectTerrain;
    }

    private Spatial orbitPickedSpatial;
    private Vector3f orbitPickedPoint;

    /**
     * Records a viewport selection pivot on the render thread. Null uses the
     * selected object's world bounds center. This is editor state, never serialized.
     * <pre>
     * 第一步：点击模型，记录命中点；第二步：左键拖动围绕该点旋转。
     * 第三步：从树选择其他物体则使用包围盒中心；右键仍自由漫游。
     * </pre>
     */
    public void recordOrbitPick(Spatial spatial, Vector3f point) {
        orbitPickedSpatial = spatial;
        orbitPickedPoint = point == null ? null : point.clone();
        if (point != null && camController != null) {
            camController.doSetCamFocus(point, false);
        }
    }

    // Render-thread pick result, independent of asynchronous Explorer selection.
    Spatial getOrbitPickedSpatial() {
        return orbitPickedSpatial;
    }

    /** Enqueues pivot synchronization from Swing selection without moving the camera. */
    public void focusSelectionPivot(Spatial spatial) {
        SceneApplication.getApplication().enqueue(() -> {
            if (spatial != null && camController != null) {
                Spatial root = spatial;
                while (root.getParent() != null) {
                    root = root.getParent();
                }
                root.updateGeometricState();
                Vector3f point;
                if (spatial == orbitPickedSpatial && orbitPickedPoint != null) {
                    point = orbitPickedPoint;
                } else {
                    orbitPickedSpatial = null;
                    orbitPickedPoint = null;
                    point = spatial.getWorldBound() == null ? spatial.getWorldTranslation()
                            : spatial.getWorldBound().getCenter();
                }
                camController.doSetCamFocus(point, false);
            }
            return null;
        });
    }

    public boolean isSelectGeometries() {
        return selectGeometries;
    }

    /**
     * Chooses leaf geometry (default true) or its model parent. This flag is
     * shared by Swing and render-thread picking; the inspector synchronizes it
     * when creating a controller for a reopened scene.
     * <pre>
     * 第一步：默认开启 Geometry，点击模型选择命中的 Geometry。
     * 第二步：关闭 Geometry 后选择模型父节点；独立 Geometry 资产仍可选择自身。
     * 第三步：重新打开场景时按钮状态同步给新控制器，无需关闭再开启。
     * </pre>
     */
    public void setSelectGeometries(boolean selectGeometries) {
        this.selectGeometries = selectGeometries;
    }

    public void setTransformationType(String type) {
        if (type != null) {
            switch (type) {
                case "Local" -> setTransformationType(TransformationType.local);
                case "Global" -> setTransformationType(TransformationType.global);
                case "Camera" -> setTransformationType(TransformationType.camera);
                default -> {
                }
            }
        }
    }

    /**
     * @param type the transformationType to set
     */
    public void setTransformationType(TransformationType type) {
        if (type != this.transformationType) {
            this.transformationType = type;
            if (editTool != null) {
                //update the transform type of the tool
                editTool.setTransformType(transformationType);
            }
        }
    }

    /**
     * @return the transformationType
     */
    public TransformationType getTransformationType() {
        return transformationType;
    }
     
    public JmeNode getRootNode() {
        return rootNode;
    }
    
    /**
     * Update the selected spatial with translation from user input
     * 
     * @param translation absolute translation
     * @param constraints axes affected
     */
    public void updateSelectedTranslation(final Vector3f translation, 
            final Vector3f constraints) {
        if (isSnapToScene()) {
            translation.set(snapToScene(translation));
        }
        if (isSnapToGrid()) {
            if (constraints.x != 0f) {
                translation.setX((int) translation.x);
            }
            if (constraints.y != 0f) {
                translation.setY((int) translation.y);
            }
            if (constraints.z != 0f) {
                translation.setZ((int) translation.z);
            }
        }
        selected.setLocalTranslation(translation);
    }
    
    /**
     * Update the selected spatial with rotation from user input
     * 
     * @param rotation absolute rotation
     * @param constraints axes affected
     */
    public void updateSelectedRotation(final Quaternion rotation, 
            final Vector3f constraints) {
        if (isSnapToGrid()) {
            final float[] angles = new float[3];
            rotation.toAngles(angles);
            
            if (constraints.y != 0f) {
                angles[1] = Math.round(angles[1] / FastMath.HALF_PI) 
                        * fifteenDegs;
            }
            if (constraints.x != 0f) {
                angles[0] = Math.round(angles[0] / FastMath.HALF_PI) 
                        * fifteenDegs;
            }
            if (constraints.z != 0f) {
                angles[2] = Math.round(angles[2] / FastMath.HALF_PI) 
                        * fifteenDegs;
            }
            rotation.fromAngles(angles);
        }
        selected.setLocalRotation(rotation);
    }
    
    /**
     * Update the selected spatial with scale from user input
     * 
     * @param scale absolute scale
     * @param constraints axes affected 
     */
    public void updateSelectedScale(final Vector3f scale, 
            final Vector3f constraints) {
        if (isSnapToGrid()) {
            if (constraints.x != 0f) {
                scale.setX((int) Math.max(scale.x, 1));
            }
            if (constraints.y != 0f) {
                scale.setY((int) Math.max(scale.y, 1));
            }
            if (constraints.z != 0f) {
                scale.setZ((int) Math.max(scale.z, 1));
            }
        }
        if (selected instanceof com.jme3.environment.gi.GiProbeVolume) {
            scale.set(Math.max(0.001f, scale.x), Math.max(0.001f, scale.y), Math.max(0.001f, scale.z));
        }
        selected.setLocalScale(scale);
    }

    private Vector3f snapToScene(final Vector3f position) {
        final Ray ray = new Ray(position, Vector3f.UNIT_Y.negate());
        final CollisionResults collisionResults = new CollisionResults();
        final Node root = getRootNode().getLookup().lookup(Node.class);
        root.collideWith(ray, collisionResults);
        for (CollisionResult r : collisionResults) {
            if (r.getGeometry() != selected) {
                position.y = r.getContactPoint().y;
                break;
            }
        }
        return position;
    }

}
