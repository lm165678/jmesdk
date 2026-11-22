/*
 * Copyright (c) 2009-2019 jMonkeyEngine All rights reserved. <p/>
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * <p>Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer. <p/> <p>Redistributions
 * in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other
 * materials provided with the distribution. <p/> <p>Neither the name of
 * 'jMonkeyEngine' nor the names of its contributors may be used to endorse or
 * promote products derived from this software without specific prior written
 * permission. <p/> <p>THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
 * CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT
 * NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.</p>
 */
package com.jme3.gde.core.scene.controller;

import com.jme3.app.state.AbstractAppState;
import com.jme3.asset.AssetManager;
import com.jme3.bounding.BoundingBox;
import com.jme3.bounding.BoundingVolume;
import com.jme3.bullet.collision.PhysicsCollisionObject;
import com.jme3.bullet.control.CharacterControl;
import com.jme3.bullet.control.GhostControl;
import com.jme3.bullet.control.PhysicsControl;
import com.jme3.bullet.control.RigidBodyControl;
import com.jme3.bullet.control.VehicleControl;
import com.jme3.bullet.util.DebugShapeFactory;
import com.jme3.effect.ParticleEmitter;
import com.jme3.gde.core.scene.SceneApplication;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.debug.Arrow;
import com.jme3.scene.debug.Grid;
import com.jme3.scene.debug.WireBox;
import java.util.concurrent.Callable;

/**
 * This class can be used or extended by other plugins to display standard tools
 * in the tools scene e.g. a cursor etc.
 *
 * @author normenhansen
 */
public class SceneToolController extends AbstractAppState {

    protected Node toolsNode;
    protected boolean showSelection = false;
    protected boolean showGrid = false;
    protected Node cursor;
    protected Geometry grid;
    protected Spatial selected;
    protected Spatial selectionShape;
    private Spatial collisionSelection;
    private com.jme3.bullet.collision.shapes.CollisionShape displayedCollisionShape;
    private final Vector3f displayedCollisionScale = new Vector3f();
    private final Vector3f currentCollisionScale = new Vector3f();
    private Geometry boundsSelection;
    private final Vector3f boundsExtent = new Vector3f(-1, -1, -1);
    protected AssetManager manager;
    protected Material blueMat;
    protected AbstractCameraController camController;

    private SceneToolControllerListener toolListener;
    
    /**
     * Expandable interface to send callbacks to (primarily) gui. 
     */
    public interface SceneToolControllerListener {
        
        /**
         * Called when cursor's location changes.
         *
         * @param location location for cursor
         */
        public void onSetCursorLocation(Vector3f location);
    }
    

    @SuppressWarnings("LeakingThisInConstructor")
    public SceneToolController(AssetManager manager) {
        this.toolsNode = new Node("ToolsNode");
        initTools();
        SceneApplication.getApplication().getStateManager().attach(this);
    }

    @SuppressWarnings("LeakingThisInConstructor")
    public SceneToolController(Node toolsNode, AssetManager manager) {
        this.toolsNode = toolsNode;
        this.manager = manager;
        initTools();
        SceneApplication.getApplication().getStateManager().attach(this);
    }

    public void setCamController(AbstractCameraController camController) {
        this.camController = camController;
    }

    protected final void initTools() {

        blueMat = createBlueMat();
        //Material redMat = new Material(manager, "Common/MatDefs/Misc/Unshaded.j3md");
        //redMat.getAdditionalRenderState().setWireframe(true);
        //redMat.setColor("Color", ColorRGBA.Red);
        Material greenMat = new Material(manager, "Common/MatDefs/Misc/Unshaded.j3md");
        greenMat.getAdditionalRenderState().setWireframe(true);
        greenMat.setColor("Color", ColorRGBA.Green);
        //Material blueMat = new Material(manager, "Common/MatDefs/Misc/Unshaded.j3md");
        //blueMat.getAdditionalRenderState().setWireframe(true);
        //blueMat.setColor("Color", ColorRGBA.Blue);
        Material grayMat = new Material(manager, "Common/MatDefs/Misc/Unshaded.j3md");
        grayMat.getAdditionalRenderState().setWireframe(true);
        grayMat.setColor("Color", ColorRGBA.Gray);

        //cursor
        if (cursor == null) {
            cursor = new Node();
        }
        cursor.detachAllChildren();
        //Geometry cursorArrowX = new Geometry("cursorArrowX", new Arrow(Vector3f.UNIT_X));
        Geometry cursorArrowY = new Geometry("cursorArrowY", new Arrow(new Vector3f(0, -1, 0)));
        cursorArrowY.setLocalTranslation(0, 1, 0);
        //Geometry cursorArrowZ = new Geometry("cursorArrowZ", new Arrow(Vector3f.UNIT_Z));
        //cursorArrowX.setMaterial(redMat);
        cursorArrowY.setMaterial(greenMat);
        //cursorArrowZ.setMaterial(blueMat);
        //cursor.attachChild(cursorArrowX);
        cursor.attachChild(cursorArrowY);
        //cursor.attachChild(cursorArrowZ);

        //grid
        grid = new Geometry("grid", new Grid(21, 21, 1.0f));
        grid.setMaterial(grayMat);
        grid.center().move(Vector3f.ZERO);
        SceneApplication.getApplication().enqueue(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                toolsNode.attachChild(cursor);
                return null;
            }
        });
    }

    public void updateSelection(final Spatial spat) {
        SceneApplication.getApplication().enqueue(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                doUpdateSelection(spat);
                return null;
            }
        });
    }

    public void doUpdateSelection(Spatial spat) {
        if (showSelection && spat != null) {
            if (selected != spat) {
                if (selectionShape != null) {
                    detachSelectionShape();
                }
                attachSelectionShape(spat);
            } else {
                if (selectionShape == null) {
                    attachSelectionShape(spat);
                }
            }
        } else {
            if (selectionShape != null) {
                detachSelectionShape();
            }
        }
        selected = spat;
    }

    public void rebuildSelectionBox() {
        if (SceneApplication.getApplication().isAwt()) {
            SceneApplication.getApplication().enqueue(new Callable<Object>() {
                @Override
                public Object call() throws Exception {
                    doUpdateSelection(selected);
                    return null;
                }
            });
        } else {
            if (selected != null) {
                attachSelectionShape(selected);
            }
        }
    }

    public void setCursorLocation(final Vector3f location) {
        SceneApplication.getApplication().enqueue(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                doSetCursorLocation(location);
                return null;
            }
        });
    }

    public void doSetCursorLocation(Vector3f location) {
        cursor.setLocalTranslation(location);
        if (camController != null) {
            camController.doSetCamFocus(location);
        }
        if (toolListener != null) {
            toolListener.onSetCursorLocation(location);
    }
    }

    public void snapCursorToSelection() {
        SceneApplication.getApplication().enqueue(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                doSnapCursorToSelection();
                return null;
            }
        });
    }

    public void doSnapCursorToSelection() {
        if (selected != null) {
            cursor.setLocalTranslation(selected.getWorldTranslation());
        }
    }

    protected void attachSelectionShape(Spatial spat) {
        if (selectionShape != null) {
            selectionShape.removeFromParent();
            if (selectionShape == collisionSelection) {
                collisionSelection = null;
                displayedCollisionShape = null;
            }
            selectionShape = null;
        }
        if (spat instanceof ParticleEmitter) {
            attachBoxSelection(spat);

        } else if (spat instanceof Geometry) {
            attachGeometrySelection((Geometry) spat);
        } else if (spat.getControl(PhysicsControl.class) != null) {
            attachPhysicsSelection(spat);
        } else {
            attachBoxSelection(spat);
        }
    }

//    protected void attachParticleEmitterSelection(ParticleEmitter pe) {
//        Mesh mesh = pe.getMesh();
//        if (mesh == null) {
//            return;
//        }
//        Material mat = new Material(SceneApplication.getApplication().getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
//        mat.getAdditionalRenderState().setWireframe(true);
//        mat.setColor("Color", ColorRGBA.Blue);
//        pe.getWorldBound().
//        Geometry selectionGeometry = new Geometry("selection_geometry_sceneviewer", mesh);
//        selectionGeometry.setMaterial(mat);
//        selectionGeometry.setLocalTransform(pe.getWorldTransform());
//        toolsNode.attachChild(selectionGeometry);
//        selectionShape = selectionGeometry;
//    }
    protected void attachGeometrySelection(Geometry geom) {
        Mesh mesh = geom.getMesh();
        if (mesh == null) {
            return;
        }
        final Geometry selectionGeometry = new Geometry("selection_geometry_sceneviewer", mesh);
        selectionGeometry.setMaterial(blueMat);
        selectionGeometry.setLocalTransform(geom.getWorldTransform());
        selectionShape = selectionGeometry;
        SceneApplication.getApplication().enqueue(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                toolsNode.attachChild(selectionGeometry);
                return null;
            }
        });
    }

    /**
     * Creates a world-axis-aligned selection box. Its dimensions are refreshed in
     * render(), after scene geometric updates; never apply the model transform twice.
     * <pre>
     * 第一步：旋转子模型；第二步：选中父节点查看所有子模型合并后的世界包围盒。
     * 第三步：继续编辑或撤销，辅助框在渲染线程随最新边界刷新，无需重新选择。
     * </pre>
     */
    protected void attachBoxSelection(Spatial geom) {
        BoundingVolume bound = geom.getWorldBound();
        if (bound instanceof BoundingBox bbox) {
            final Geometry box = WireBox.makeGeometry(bbox);
            box.setName("selection_geometry_sceneviewer");
            box.setMaterial(blueMat);
            boundsSelection = box;
            bbox.getExtent(boundsExtent);
            selectionShape = box;
            SceneApplication.getApplication().enqueue(() -> {
                if (selectionShape == box) {
                    toolsNode.attachChild(box);
                }
                return null;
            });
        }
    }

    @Override
    public void render(com.jme3.renderer.RenderManager manager) {
        super.render(manager);
        if (selected != null && selectionShape != null && selectionShape == collisionSelection) {
            PhysicsCollisionObject object = collisionObject(selected);
            if (object != null) {
                var shape = object.getCollisionShape();
                if (shape != displayedCollisionShape || !shape.getScale(currentCollisionScale).equals(displayedCollisionScale)) {
                    attachPhysicsSelection(selected);
                } else {
                    updateCollisionSelectionPose(selected);
                }
            }
        }
        if (selected == null || selectionShape == null || selectionShape != boundsSelection) {
            return;
        }
        if (selected.getWorldBound() instanceof BoundingBox box) {
            if (boundsExtent.x != box.getXExtent() || boundsExtent.y != box.getYExtent()
                    || boundsExtent.z != box.getZExtent()) {
                box.getExtent(boundsExtent);
                ((WireBox) boundsSelection.getMesh()).updatePositions(
                        boundsExtent.x, boundsExtent.y, boundsExtent.z);
                boundsSelection.updateModelBound();
            }
            boundsSelection.setLocalTranslation(box.getCenter());
            boundsSelection.setLocalRotation(com.jme3.math.Quaternion.IDENTITY);
            boundsSelection.setLocalScale(1);
            // SceneApplication finalizes the entire Tools Node after all callbacks.
            // Finalize the parent too when a tool leaves its subtree dirty.
        }
    }

    private Material createBlueMat() {
        Material mat = new Material(SceneApplication.getApplication().getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
        mat.getAdditionalRenderState().setWireframe(true);
        mat.setColor("Color", ColorRGBA.Blue);
        return mat;
    }

    private static PhysicsCollisionObject collisionObject(Spatial spatial) {
        PhysicsCollisionObject object = spatial.getControl(RigidBodyControl.class);
        if (object == null) {
            object = spatial.getControl(GhostControl.class);
        }
        if (object == null) {
            CharacterControl character = spatial.getControl(CharacterControl.class);
            if (character != null) {
                object = character.getCharacter();
            }
        }
        return object;
    }

    private void updateCollisionSelectionPose(Spatial owner) {
        // DebugShapeFactory has already applied the native collision-shape scale.
        collisionSelection.setLocalTranslation(owner.getWorldTranslation());
        collisionSelection.setLocalRotation(owner.getWorldRotation());
    }

    protected void attachPhysicsSelection(Spatial owner) {
        PhysicsCollisionObject object = collisionObject(owner);
        if (object == null) {
            return;
        }
        var shape = object.getCollisionShape();
        Spatial replacement = DebugShapeFactory.getDebugShape(shape);
        if (replacement != null) {
            if (collisionSelection != null) {
                collisionSelection.removeFromParent();
            }
            replacement.setMaterial(blueMat);
            collisionSelection = replacement;
            selectionShape = replacement;
            displayedCollisionShape = shape;
            shape.getScale(displayedCollisionScale);
            updateCollisionSelectionPose(owner);
            toolsNode.attachChild(replacement);
        }
    }

    protected void detachSelectionShape() {
        if (selectionShape != null) {
            final Spatial shape = selectionShape;
            selectionShape = null;
            if (shape == collisionSelection) {
                collisionSelection = null;
                displayedCollisionShape = null;
            }
            SceneApplication.getApplication().enqueue(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    shape.removeFromParent();
                    return null;
                }
            });
        }
    }

    @Override
    public void cleanup() {
        super.cleanup();
        detachSelectionShape();
        final Spatial cursor = this.cursor;
        final Spatial grid = this.grid;
        SceneApplication.getApplication().enqueue(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                cursor.removeFromParent();
                grid.removeFromParent();
                return null;
            }
        });
        SceneApplication.getApplication().getStateManager().detach(this);
    }

    //TODO: multithreading!
    public Vector3f getCursorLocation() {
        return cursor.getLocalTranslation();
    }

    public void setShowSelection(final boolean showSelection) {
        SceneApplication.getApplication().enqueue(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                doSetShowSelection(showSelection);
                return null;
            }
        });
    }

    public void doSetShowSelection(boolean showSelection) {
        this.showSelection = showSelection;
        if (showSelection && selected != null && selectionShape == null) {
            attachSelectionShape(selected);
        } else if (!showSelection && selectionShape != null) {
            detachSelectionShape();
        }
    }

    public void setShowGrid(final boolean showGrid) {
        SceneApplication.getApplication().enqueue(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                doSetShowGrid(showGrid);
                return null;
            }
        });
    }

    public void doSetShowGrid(boolean showGrid) {
        this.showGrid = showGrid;
        if (showGrid) {
            toolsNode.attachChild(grid);
        } else {
            toolsNode.detachChild(grid);
        }
    }

    /**
     * @return the toolsNode
     */
    public Node getToolsNode() {
        return toolsNode;
    }

    @Override
    public void update(float f) {
        updateAdaptiveGrid();
        if (selected == null || selectionShape == null) {
            return;
        }

        if (selectionShape == boundsSelection || selectionShape == collisionSelection) {
            return;
        }
        selectionShape.setLocalTranslation(selected.getWorldTranslation());
        selectionShape.setLocalRotation(selected.getWorldRotation());
        selectionShape.setLocalScale(selected.getWorldScale());

    }

    /**
     * Keeps the editor grid readable while zooming. Only the helper mesh changes;
     * model transforms and world-unit snapping remain unchanged. Render thread only.
     */
    private void updateAdaptiveGrid() {
        if (grid == null || !showGrid) {
            return;
        }
        com.jme3.renderer.Camera camera = SceneApplication.getApplication().getCamera();
        float halfHeight;
        if (camera.isParallelProjection()) {
            halfHeight = Math.abs(camera.getFrustumTop() - camera.getFrustumBottom()) * .5f;
        } else {
            float distance = Math.max(camera.getLocation().length(), camera.getFrustumNear() * 2);
            halfHeight = distance * Math.abs(camera.getFrustumTop() / camera.getFrustumNear());
        }
        float spacing = Math.max(.001f, halfHeight / 8f);
        if (Float.isFinite(spacing)) {
            grid.setLocalScale(spacing);
            grid.setLocalTranslation(-10 * spacing, 0, -10 * spacing);
        }
    }

    public Spatial getSelectedSpatial() {
        return selected;
    }

    public Spatial getSelectionShape() {
        return selectionShape;
    }
    
    /**
     * Set the listener to receive callbacks from this class.
     * 
     * @param listener listener
     */
    public void setToolListener(final SceneToolControllerListener listener) {
        this.toolListener = listener;
    }
}
