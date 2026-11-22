/*
 * Copyright (c) 2009-2024 jMonkeyEngine All rights reserved. <p/>
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer. <p/> * Redistributions
 * in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other
 * materials provided with the distribution. <p/> * Neither the name of
 * 'jMonkeyEngine' nor the names of its contributors may be used to endorse or
 * promote products derived from this software without specific prior written
 * permission. <p/> THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
 * CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT
 * NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.jme3.gde.scenecomposer;

import com.jme3.asset.AssetManager;
import com.jme3.bounding.BoundingBox;
import com.jme3.collision.CollisionResult;
import com.jme3.collision.CollisionResults;
import com.jme3.gde.core.scene.SceneApplication;
import com.jme3.gde.core.sceneexplorer.nodes.JmeNode;
import com.jme3.gde.core.sceneexplorer.nodes.JmeSpatial;
import com.jme3.gde.core.undoredo.AbstractUndoableSceneEdit;
import com.jme3.gde.core.undoredo.SceneUndoRedoManager;
import com.jme3.gde.scenecomposer.gizmo.GizmoFactory;
import com.jme3.input.event.KeyInputEvent;
import com.jme3.material.Material;
import com.jme3.material.RenderState.BlendMode;
import com.jme3.material.RenderState.FaceCullMode;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Ray;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Box;
import com.jme3.scene.shape.Cylinder;
import com.jme3.scene.shape.Quad;
import com.jme3.scene.shape.Torus;
import org.openide.loaders.DataObject;
import org.openide.util.Lookup;

/**
 *
 * @author Brent Owens
 */
public abstract class SceneEditTool {

    protected static Vector3f ARROW_X = new Vector3f(1, 0, 0);
    protected static Vector3f ARROW_Y = new Vector3f(0, 1, 0);
    protected static Vector3f ARROW_Z = new Vector3f(0, 0, 1);
    protected static Vector3f QUAD_XY = new Vector3f(1, 1, 0);
    protected static Vector3f QUAD_XZ = new Vector3f(1, 0, 1);
    protected static Vector3f QUAD_YZ = new Vector3f(0, 1, 1);
    protected SceneComposerToolController toolController;
    protected AssetManager manager;
    protected Camera camera;
    private boolean overrideCameraControl = false; // if true, you cannot pan/zoom unless you hold SHIFT
    // the key to load the tool hint text from the resource bundle
    protected String toolHintTextKey = "SceneComposerTopComponent.toolHint.default"; // not used yet
    protected Node toolNode;
    protected Node onTopToolNode;
    protected Node axisMarker;
    private static final float AXIS_MARKER_RADIUS = 2f;
    private static final float AXIS_MARKER_SCREEN_RADIUS = 80f;
    private static final float HANDLE_PICK_MARGIN = 4f;
    protected Material redMat, blueMat, greenMat, yellowMat, cyanMat, magentaMat, orangeMat;
    protected Geometry quadXY, quadXZ, quadYZ;
    protected Geometry arrowX, arrowY, arrowZ;
    protected Geometry coneX, coneY, coneZ;
    protected Geometry boxX, boxY, boxZ;
    protected Geometry circleXY, circleXZ, circleYZ;

    protected SceneComposerToolController.TransformationType transformType;

    protected enum AxisMarkerPickType {
        axisOnly, planeOnly, axisAndPlane
    };

    protected AxisMarkerPickType axisPickType;

    /**
     * The tool was selected, start showing the marker.
     *
     * @param manager asset manager
     * @param toolNode parent node that the marker will attach to
     * @param onTopToolNode the node displayed on top of the scene
     * @param selectedSpatial the selected spatial
     * @param toolController the toolController {@link SceneComposerToolController
     * }
     */
    public void activate(AssetManager manager, Node toolNode, Node onTopToolNode, Spatial selectedSpatial, SceneComposerToolController toolController) {
        this.manager = manager;
        this.toolController = toolController;
        this.setTransformType(toolController.getTransformationType());
        //this.selectedSpatial = selectedSpatial;
        addMarker(toolNode, onTopToolNode);
    }

    protected void addMarker(Node toolNode, Node onTopToolNode) {
        this.toolNode = toolNode;
        this.onTopToolNode = onTopToolNode;

        if (axisMarker == null) {
            axisMarker = createAxisMarker();
        }
        axisMarker.removeFromParent();
        this.onTopToolNode.attachChild(axisMarker);
        setDefaultAxisMarkerColors();

        doUpdateToolsTransformation();

    }

    /**
     * Remove the marker from it's parent (the tools node)
     */
    public void hideMarker() {
        detachMarker();
    }

    final void detachMarker() {
        if (axisMarker != null) {
            axisMarker.removeFromParent();
        }
    }

    public boolean isOverrideCameraControl() {
        return overrideCameraControl;
    }

    public void setOverrideCameraControl(boolean overrideCameraControl) {
        this.overrideCameraControl = overrideCameraControl;
    }

    /**
     * Called when the selected spatial has been modified outside of the tool.
     *
     * @TODO: why? just move the tool where the object is each frame? Proposed
     * Answer: Performance.
     */
    public void updateToolsTransformation() {

        SceneApplication.getApplication().enqueue(() -> {
            doUpdateToolsTransformation();
            return null;
        });
    }

    public void doUpdateToolsTransformation() {
        boolean hasSelection = toolController.getSelectedSpatial() != null;
        Spatial.CullHint visibility = hasSelection ? Spatial.CullHint.Inherit : Spatial.CullHint.Always;
        if (axisMarker.getLocalCullHint() != visibility) {
            axisMarker.setCullHint(visibility);
        }
        if (hasSelection) {
            axisMarker.setLocalTranslation(toolController.getSelectedSpatial().getWorldTranslation());
            switch (transformType) {
                case local ->
                    axisMarker.setLocalRotation(toolController.getSelectedSpatial().getWorldRotation());
                case global ->
                    axisMarker.setLocalRotation(Quaternion.IDENTITY);
                case camera -> {
                    if (camera != null) {
                        axisMarker.setLocalRotation(camera.getRotation());
                    }
                }
            }
            updateAxisMarkerScale();
        } else {
            axisMarker.setLocalTranslation(Vector3f.ZERO);
            axisMarker.setLocalRotation(Quaternion.IDENTITY);
        }
    }

    /**
     * Keep handles approximately 80 logical pixels from pivot to axis tip/ring,
     * independent of object bounds. Perspective uses pivot depth along the view
     * direction; orthographic projection uses its fixed frustum height. The
     * visible geometry and picking geometry share this scale. Called only on
     * the render thread, including while rotation locks the marker orientation.
     * Missing/zero-size camera or panel data falls back to the current scale
     * or physical pixels until the next valid update.
     * <pre>
     * // 第一步：选择物体，按 W/E/R 切换平移、旋转或缩放。
     * // 第二步：拉远或拉近相机；控件自动保持屏幕尺寸，无需配置。
     * // 第三步：调整视口、FOV 或正交缩放后，下一帧自动重新计算。
     * </pre>
     */
    protected final void updateAxisMarkerScale() {
        if (camera == null || axisMarker == null || camera.getHeight() <= 0) {
            return;
        }
        float viewportHeight = camera.getHeight()
                * (camera.getViewPortTop() - camera.getViewPortBottom());
        if (viewportHeight <= 0f) {
            return;
        }
        float worldHeight = Math.abs(camera.getFrustumTop() - camera.getFrustumBottom());
        if (!camera.isParallelProjection()) {
            float near = camera.getFrustumNear();
            if (near <= 0f) {
                return;
            }
            float depth = axisMarker.getLocalTranslation().subtract(camera.getLocation())
                    .dot(camera.getDirection());
            worldHeight *= Math.max(near, depth) / near;
        }
        java.awt.Component panel = SceneApplication.getApplication().getMainPanel();
        int logicalHeight = panel == null ? 0 : panel.getHeight();
        float pixelScale = logicalHeight > 0 ? (float) camera.getHeight() / logicalHeight : 1f;
        float scale = worldHeight / viewportHeight * pixelScale
                * AXIS_MARKER_SCREEN_RADIUS / AXIS_MARKER_RADIUS;
        if (Float.isFinite(scale) && scale > 0f) {
            axisMarker.setLocalScale(scale);
        }
    }

    /**
     * The primary action for the tool gets activated
     *
     * @param screenCoord the position of the mouse
     * @param pressed true if the primary button is pressed, false if released
     * @param rootNode the root of sceneExplorer nodes
     * @param dataObject see {@link DataObject}
     */
    public abstract void actionPrimary(Vector2f screenCoord, boolean pressed, JmeNode rootNode, DataObject dataObject);

    /**
     * The secondary action for the tool gets activated
     *
     * @param screenCoord the position of the mouse
     * @param pressed true if the secondary button is pressed, false if released
     * @param rootNode the root of sceneExplorer nodes
     * @param dataObject see {@link DataObject}
     */
    public abstract void actionSecondary(Vector2f screenCoord, boolean pressed, JmeNode rootNode, DataObject dataObject);

    /**
     * Called when the mouse is moved but not dragged (ie no buttons are
     * pressed)
     *
     * @param screenCoord the position of the mouse
     * @param rootNode the root of sceneExplorer nodes
     * @param dataObject see {@link DataObject}
     */
    public abstract void mouseMoved(Vector2f screenCoord, JmeNode rootNode, DataObject dataObject);

    /**
     * Called when the mouse is moved while the primary button is down
     *
     * @param screenCoord the position of the mouse
     * @param pressed true if the primary button is pressed, false if released
     * @param rootNode the root of sceneExplorer nodes
     * @param currentDataObject see {@link DataObject}
     */
    public abstract void draggedPrimary(Vector2f screenCoord, boolean pressed, JmeNode rootNode, DataObject currentDataObject);

    /**
     * Called when the mouse is moved while the secondary button is down
     *
     * @param screenCoord the position of the mouse
     * @param pressed true if the secondary button is pressed, false if released
     * @param currentDataObject see {@link DataObject}
     * @param rootNode the root of sceneExplorer nodes
     */
    public abstract void draggedSecondary(Vector2f screenCoord, boolean pressed, JmeNode rootNode, DataObject currentDataObject);

    public void keyPressed(KeyInputEvent kie) {
    }

    /**
     * Call when an action is performed that requires the scene to be saved and
     * an undo can be performed
     *
     * @param undoer your implementation, probably with a begin and end state
     * for undoing
     */
    protected void actionPerformed(AbstractUndoableSceneEdit undoer) {
        Lookup.getDefault().lookup(SceneUndoRedoManager.class).addEdit(this, undoer);
        toolController.setNeedsSave(true);
    }

    /**
     * Given the mouse coordinates, pick the geometry that is closest to the
     * camera.
     *
     * @param cam the Camera
     * @param mouseLoc the position of the mouse
     * @param jmeRootNode to pick from
     * @return the selected spatial, or null if nothing
     */
    public static Spatial pickWorldSpatial(Camera cam, Vector2f mouseLoc, JmeNode jmeRootNode) {
        Node rootNode = jmeRootNode.getLookup().lookup(Node.class);
        CollisionResult cr = pick(cam, mouseLoc, rootNode);
        if (cr != null) {
            return cr.getGeometry();
        } else {
            return null;
        }
    }

    /**
     * Given the mouse coordinate, pick the world location where the mouse
     * intersects a geometry.
     *
     * @param cam the Camera
     * @param mouseLoc the position of the mouse
     * @param jmeRootNode to pick from
     * @return the location of the pick, or null if nothing collided with the
     * mouse
     */
    public static Vector3f pickWorldLocation(Camera cam, Vector2f mouseLoc, JmeNode jmeRootNode) {
        Node rootNode = jmeRootNode.getLookup().lookup(Node.class);
        return pickWorldLocation(cam, mouseLoc, rootNode, null);
    }

    /**
     * Pick anything except the excluded spatial
     *
     * @param cam the Camera
     * @param mouseLoc the position of the mouse
     * @param jmeRootNode to pick from
     * @param excludeSpat to not pick
     * @return the location of the pick, or null if nothing collided with the
     * mouse
     */
    public static Vector3f pickWorldLocation(Camera cam, Vector2f mouseLoc, JmeNode jmeRootNode, JmeSpatial excludeSpat) {
        Node rootNode = jmeRootNode.getLookup().lookup(Node.class);
        Spatial exclude = excludeSpat.getLookup().lookup(Spatial.class);
        return pickWorldLocation(cam, mouseLoc, rootNode, exclude);
    }

    public static Vector3f pickWorldLocation(Camera cam, Vector2f mouseLoc, Node rootNode, Spatial exclude) {
        CollisionResult cr = doPick(cam, mouseLoc, rootNode, exclude);
        if (cr != null) {
            return cr.getContactPoint();
        } else {
            return null;
        }
    }

    private static CollisionResult doPick(Camera cam, Vector2f mouseLoc, Node node, Spatial exclude) {
        CollisionResults results = new CollisionResults();
        Ray ray = new Ray();
        Vector3f pos = cam.getWorldCoordinates(mouseLoc, 0).clone();
        Vector3f dir = cam.getWorldCoordinates(mouseLoc, 0.3f).clone();
        dir.subtractLocal(pos).normalizeLocal();
        ray.setOrigin(pos);
        ray.setDirection(dir);
        node.collideWith(ray, results);
        CollisionResult result = null;
        if (exclude == null) {
            result = results.getClosestCollision();
        } else {
            for (CollisionResult cr : results) {
                if (!isExcluded(cr.getGeometry(), exclude)) {
                    return cr;
                }
            }

        }
        return result;
    }

    /**
     * Is the selected spatial the one we want to exclude from the picking?
     * Recursively looks up the parents to find out.
     */
    private static boolean isExcluded(Spatial s, Spatial exclude) {
        if (s.equals(exclude)) {
            return true;
        }

        if (s.getParent() != null) {
            return isExcluded(s.getParent(), exclude);
        }
        return false;
    }

    /**
     * Pick a part of the axis marker. The result is a Vector3f that represents
     * what part of the axis was selected. For example if (1,0,0) is returned,
     * then the X-axis pole was selected. If (0,1,1) is returned, then the Y-Z
     * plane was selected.
     *
     * @param cam the Camera
     * @param mouseLoc the position of the mouse
     * @param pickType the type of markers to select
     * @return null if it did not intersect the marker
     */
    protected Vector3f pickAxisMarker(Camera cam, Vector2f mouseLoc, AxisMarkerPickType pickType) {
        if (pickType == null || axisMarker == null || axisMarker.getParent() == null
                || toolController.getSelectedSpatial() == null) {
            return null;
        }

        CollisionResult cr = pickMarker(cam, mouseLoc, pickType);
        if (cr == null) {
            // Keep exact hits first. A small screen-space margin also helps when
            // a ring is viewed edge-on or the pointer is just outside a shaft.
            cr = pickNearMarker(cam, mouseLoc, pickType);
        }
        if (cr == null || cr.getGeometry() == null) {
            return null;
        }

        String collisionName = cr.getGeometry().getName();

        if (pickType != null && collisionName != null) {
            if (pickType == AxisMarkerPickType.planeOnly || pickType == AxisMarkerPickType.axisAndPlane) {
                switch (collisionName) {
                    case "quadXY", "circleXY" -> {
                        return QUAD_XY;
                    }
                    case "quadXZ", "circleXZ" -> {
                        return QUAD_XZ;
                    }
                    case "quadYZ", "circleYZ" -> {
                        return QUAD_YZ;
                    }
                    default -> {
                    }
                }
            }
            if (pickType == AxisMarkerPickType.axisOnly || pickType == AxisMarkerPickType.axisAndPlane) {
                switch (collisionName) {
                    case "arrowX", "coneX", "boxX" -> {
                        return ARROW_X;
                    }
                    case "arrowY", "coneY", "boxY" -> {
                        return ARROW_Y;
                    }
                    case "arrowZ", "coneZ", "boxZ" -> {
                        return ARROW_Z;
                    }
                    default -> {
                    }
                }
            }
        }
        return null;
    }

    private CollisionResult pickNearMarker(Camera cam, Vector2f mouseLoc, AxisMarkerPickType pickType) {
        CollisionResult closest = null;
        Vector2f sample = new Vector2f();
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                if (x == 0 && y == 0) {
                    continue;
                }
                float margin = x != 0 && y != 0 ? HANDLE_PICK_MARGIN * .70710677f : HANDLE_PICK_MARGIN;
                sample.set(mouseLoc.x + x * margin, mouseLoc.y + y * margin);
                CollisionResult hit = pickMarker(cam, sample, pickType);
                if (hit != null && (closest == null || hit.getDistance() < closest.getDistance())) {
                    closest = hit;
                }
            }
        }
        return closest;
    }

    private CollisionResult pickMarker(Camera cam, Vector2f mouseLoc, AxisMarkerPickType pickType) {
        for (CollisionResult hit : pickResults(cam, mouseLoc, axisMarker)) {
            String name = hit.getGeometry().getName();
            boolean plane = name.startsWith("quad") || name.startsWith("circle");
            if (plane ? pickType != AxisMarkerPickType.axisOnly : pickType != AxisMarkerPickType.planeOnly) {
                return hit;
            }
        }
        return null;
    }

    private static CollisionResult pick(Camera cam, Vector2f mouseLoc, Node node) {
        return pickResults(cam, mouseLoc, node).getClosestCollision();
    }

    private static CollisionResults pickResults(Camera cam, Vector2f mouseLoc, Node node) {
        CollisionResults results = new CollisionResults();
        Ray ray = new Ray();
        Vector3f pos = cam.getWorldCoordinates(mouseLoc, 0).clone();
        Vector3f dir = cam.getWorldCoordinates(mouseLoc, 0.125f).clone();
        dir.subtractLocal(pos).normalizeLocal();
        ray.setOrigin(pos);
        ray.setDirection(dir);
        node.collideWith(ray, results);
        return results;
    }

    /**
     * Show what axis or plane the mouse is currently over and will affect.
     *
     * @param camera the Camera
     * @param screenCoord the position of the mouse
     * @param axisMarkerPickType the type of markers to select
     */
    protected void highlightAxisMarker(Camera camera, Vector2f screenCoord, AxisMarkerPickType axisMarkerPickType) {
        highlightAxisMarker(camera, screenCoord, axisMarkerPickType, false);
    }

    /**
     * Show what axis or plane the mouse is currently over and will affect.
     *
     * @param camera the Camera
     * @param screenCoord the position of the mouse
     * @param axisMarkerPickType the type of markers to select
     * @param colorAll highlight all parts of the marker when only one is
     * selected
     */
    protected void highlightAxisMarker(Camera camera, Vector2f screenCoord, AxisMarkerPickType axisMarkerPickType, boolean colorAll) {
        setDefaultAxisMarkerColors();
        Vector3f picked = pickAxisMarker(camera, screenCoord, axisMarkerPickType);
        if (picked == null) {
            return;
        }

        if (picked == ARROW_X) {
            arrowX.setMaterial(orangeMat);
            coneX.setMaterial(orangeMat);
            boxX.setMaterial(orangeMat);
        } else if (picked == ARROW_Y) {
            arrowY.setMaterial(orangeMat);
            coneY.setMaterial(orangeMat);
            boxY.setMaterial(orangeMat);
        } else if (picked == ARROW_Z) {
            arrowZ.setMaterial(orangeMat);
            coneZ.setMaterial(orangeMat);
            boxZ.setMaterial(orangeMat);
        } else {
            if (picked == QUAD_XY || colorAll) {
                quadXY.setMaterial(orangeMat);
                circleXY.setMaterial(orangeMat);
            }
            if (picked == QUAD_XZ || colorAll) {
                quadXZ.setMaterial(orangeMat);
                circleXZ.setMaterial(orangeMat);
            }
            if (picked == QUAD_YZ || colorAll) {
                quadYZ.setMaterial(orangeMat);
                circleYZ.setMaterial(orangeMat);
            }
        }
    }

    /**
     * Create the axis marker that is selectable
     *
     * @return the axis node
     */
    protected Node createAxisMarker() {
        float size = AXIS_MARKER_RADIUS;
        float arrowSize = size;
        float planeSize = size * 0.5f;

        Quaternion ROLL090 = new Quaternion().fromAngleAxis(-FastMath.PI / 2, new Vector3f(0, 0, 1));
        Quaternion YAW090 = new Quaternion().fromAngleAxis(-FastMath.PI / 2, new Vector3f(0, 1, 0));
        Quaternion PITCH090 = new Quaternion().fromAngleAxis(FastMath.PI / 2, new Vector3f(1, 0, 0));

        redMat = new Material(manager, "Common/MatDefs/Misc/Unshaded.j3md");
        redMat.getAdditionalRenderState().setWireframe(false);
        redMat.setColor("Color", ColorRGBA.Red);
        redMat.getAdditionalRenderState().setFaceCullMode(FaceCullMode.Off);
        redMat.getAdditionalRenderState().setLineWidth(2f);

        greenMat = new Material(manager, "Common/MatDefs/Misc/Unshaded.j3md");
        greenMat.getAdditionalRenderState().setWireframe(false);
        greenMat.setColor("Color", ColorRGBA.Green);
        greenMat.getAdditionalRenderState().setFaceCullMode(FaceCullMode.Off);
        greenMat.getAdditionalRenderState().setLineWidth(2f);

        blueMat = new Material(manager, "Common/MatDefs/Misc/Unshaded.j3md");
        blueMat.getAdditionalRenderState().setWireframe(false);
        blueMat.setColor("Color", ColorRGBA.Blue);
        blueMat.getAdditionalRenderState().setFaceCullMode(FaceCullMode.Off);
        blueMat.getAdditionalRenderState().setLineWidth(2f);

        yellowMat = new Material(manager, "Common/MatDefs/Misc/Unshaded.j3md");
        yellowMat.getAdditionalRenderState().setWireframe(false);
        yellowMat.setColor("Color", new ColorRGBA(1f, 1f, 0f, 0.25f));
        yellowMat.getAdditionalRenderState().setBlendMode(BlendMode.Alpha);
        yellowMat.getAdditionalRenderState().setFaceCullMode(FaceCullMode.Off);
        yellowMat.getAdditionalRenderState().setLineWidth(2f);

        cyanMat = new Material(manager, "Common/MatDefs/Misc/Unshaded.j3md");
        cyanMat.getAdditionalRenderState().setWireframe(false);
        cyanMat.setColor("Color", new ColorRGBA(0f, 1f, 1f, 0.25f));
        cyanMat.getAdditionalRenderState().setBlendMode(BlendMode.Alpha);
        cyanMat.getAdditionalRenderState().setFaceCullMode(FaceCullMode.Off);
        cyanMat.getAdditionalRenderState().setLineWidth(2f);

        magentaMat = new Material(manager, "Common/MatDefs/Misc/Unshaded.j3md");
        magentaMat.getAdditionalRenderState().setWireframe(false);
        magentaMat.setColor("Color", new ColorRGBA(1f, 0f, 1f, 0.25f));
        magentaMat.getAdditionalRenderState().setBlendMode(BlendMode.Alpha);
        magentaMat.getAdditionalRenderState().setFaceCullMode(FaceCullMode.Off);
        magentaMat.getAdditionalRenderState().setLineWidth(2f);

        orangeMat = new Material(manager, "Common/MatDefs/Misc/Unshaded.j3md");
        orangeMat.getAdditionalRenderState().setWireframe(false);
        orangeMat.setColor("Color", new ColorRGBA(251f / 255f, 130f / 255f, 0f, 0.4f));
        orangeMat.getAdditionalRenderState().setBlendMode(BlendMode.Alpha);
        orangeMat.getAdditionalRenderState().setFaceCullMode(FaceCullMode.Off);
        orangeMat.getAdditionalRenderState().setLineWidth(2f);

        Node axis = new Node();

        // Solid shafts keep the rendered width and mouse collision on the same mesh.
        Mesh shaft = new Cylinder(2, 12, arrowSize / 36f, arrowSize, true);
        arrowX = new Geometry("arrowX", shaft);
        arrowX.setLocalRotation(YAW090);
        arrowX.setLocalTranslation(arrowSize / 2, 0, 0);
        arrowY = new Geometry("arrowY", shaft);
        arrowY.setLocalRotation(PITCH090);
        arrowY.setLocalTranslation(0, arrowSize / 2, 0);
        arrowZ = new Geometry("arrowZ", shaft);
        arrowZ.setLocalTranslation(0, 0, arrowSize / 2);
        axis.attachChild(arrowX);
        axis.attachChild(arrowY);
        axis.attachChild(arrowZ);

        // create planes
        quadXY = new Geometry("quadXY", new Quad(planeSize, planeSize));
        quadXZ = new Geometry("quadXZ", new Quad(planeSize, planeSize));
        quadXZ.setLocalRotation(PITCH090);
        quadYZ = new Geometry("quadYZ", new Quad(planeSize, planeSize));
        quadYZ.setLocalRotation(YAW090);

        // create circles
        float s = arrowSize / 30f;
        Mesh circle = new Torus(64, 8, s, arrowSize);
        circleXY = new Geometry("circleXY", circle);
        circleXZ = new Geometry("circleXZ", circle);
        circleXZ.setLocalRotation(PITCH090);
        circleYZ = new Geometry("circleYZ", circle);
        circleYZ.setLocalRotation(YAW090);

        // create cones
        float h = arrowSize / 8f;
        float r = arrowSize / 13f;
        Mesh cone = GizmoFactory.createCone(16, r, h);

        coneX = new Geometry("coneX", cone);
        coneX.move(-h, 0, 0);
        coneX.rotate(ROLL090);

        coneY = new Geometry("coneY", cone);
        coneY.move(0, -h, 0);
        coneY.setLocalRotation(YAW090);

        coneZ = new Geometry("coneZ", cone);
        coneZ.move(0, 0, -h);
        coneZ.setLocalRotation(PITCH090);

        coneX.move(ARROW_X.mult(arrowSize));
        coneY.move(ARROW_Y.mult(arrowSize));
        coneZ.move(ARROW_Z.mult(arrowSize));

        //create boxes
        float ext = arrowSize / 13f;
        Mesh box = new Box(ext, ext, ext);
        boxX = new Geometry("boxX", box);
        boxX.move(-ext, 0, 0);
        boxY = new Geometry("boxY", box);
        boxY.move(0, -ext, 0);
        boxZ = new Geometry("boxZ", box);
        boxZ.move(0, 0, -ext);

        boxX.move(ARROW_X.mult(arrowSize));
        boxY.move(ARROW_Y.mult(arrowSize));
        boxZ.move(ARROW_Z.mult(arrowSize));

        axis.setModelBound(new BoundingBox());
        axis.updateModelBound();
        return axis;
    }

    protected void displayPlanes() {
        axisMarker.attachChild(quadXY);
        axisMarker.attachChild(quadXZ);
        axisMarker.attachChild(quadYZ);
    }

    protected void hidePlanes() {
        quadXY.removeFromParent();
        quadXZ.removeFromParent();
        quadYZ.removeFromParent();

    }

    protected void displayArrows() {
        axisMarker.attachChild(arrowX);
        axisMarker.attachChild(arrowY);
        axisMarker.attachChild(arrowZ);
    }

    protected void hideArrows() {
        arrowX.removeFromParent();
        arrowY.removeFromParent();
        arrowZ.removeFromParent();
    }

    protected void displayCones() {
        axisMarker.attachChild(coneX);
        axisMarker.attachChild(coneY);
        axisMarker.attachChild(coneZ);
    }

    protected void hideCones() {
        coneX.removeFromParent();
        coneY.removeFromParent();
        coneZ.removeFromParent();
    }

    protected void displayBoxes() {
        axisMarker.attachChild(boxX);
        axisMarker.attachChild(boxY);
        axisMarker.attachChild(boxZ);
    }

    protected void hideBoxes() {
        boxX.removeFromParent();
        boxY.removeFromParent();
        boxZ.removeFromParent();
    }

    protected void displayCircles() {
        axisMarker.attachChild(circleXY);
        axisMarker.attachChild(circleXZ);
        axisMarker.attachChild(circleYZ);
    }

    protected void hideCircles() {
        circleXY.removeFromParent();
        circleXZ.removeFromParent();
        circleYZ.removeFromParent();
    }

    protected void setDefaultAxisMarkerColors() {
        arrowX.setMaterial(redMat);
        arrowY.setMaterial(greenMat);
        arrowZ.setMaterial(blueMat);

        coneX.setMaterial(redMat);
        coneY.setMaterial(greenMat);
        coneZ.setMaterial(blueMat);

        boxX.setMaterial(redMat);
        boxY.setMaterial(greenMat);
        boxZ.setMaterial(blueMat);

        quadXY.setMaterial(yellowMat);
        quadXZ.setMaterial(magentaMat);
        quadYZ.setMaterial(cyanMat);

        circleXY.setMaterial(blueMat);
        circleXZ.setMaterial(greenMat);
        circleYZ.setMaterial(redMat);
    }

    public Camera getCamera() {
        return camera;
    }

    public void setCamera(Camera camera) {
        this.camera = camera;
    }

    public SceneComposerToolController.TransformationType getTransformType() {
        return transformType;
    }

    public void setTransformType(SceneComposerToolController.TransformationType transformType) {
        this.transformType = transformType;
    }
}
