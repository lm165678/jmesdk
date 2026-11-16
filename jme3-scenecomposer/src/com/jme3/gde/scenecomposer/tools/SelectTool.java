/*
 *  Copyright (c) 2009-2024 jMonkeyEngine
 *  All rights reserved.
 * 
 *  Redistribution and use in source and binary forms, with or without
 *  modification, are permitted provided that the following conditions are
 *  met:
 * 
 *  * Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 
 *  * Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 
 *  * Neither the name of 'jMonkeyEngine' nor the names of its contributors
 *    may be used to endorse or promote products derived from this software
 *    without specific prior written permission.
 * 
 *  THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 *  "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 *  TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 *  PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 *  CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 *  EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 *  PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 *  PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 *  LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 *  NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 *  SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.jme3.gde.scenecomposer.tools;

import com.jme3.gde.core.sceneexplorer.SceneExplorerTopComponent;
import com.jme3.gde.core.sceneexplorer.nodes.AbstractSceneExplorerNode;
import com.jme3.gde.core.sceneexplorer.nodes.JmeNode;
import com.jme3.gde.core.sceneexplorer.nodes.JmeSpatial;
import com.jme3.gde.core.sceneviewer.SceneViewerTopComponent;
import com.jme3.gde.scenecomposer.SceneEditTool;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.scene.AssetLinkNode;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.terrain.Terrain;
import org.openide.loaders.DataObject;

/**
 * Object picking used by the scene editor's left mouse button. The controller
 * routes handle hits to the active transform tool and other clicks to selectAt.
 * E/R/T switch handles; platform Ctrl shortcuts never activate modal transforms.
 * The secondary methods remain internal compatibility helpers for selection.
 */
public class SelectTool extends SceneEditTool {

    /** Selects under a left click without creating or replacing gizmo markers. */
    public void selectAt(Vector2f point, com.jme3.renderer.Camera camera, JmeNode root,
            DataObject data, com.jme3.gde.scenecomposer.SceneComposerToolController controller) {
        toolController = controller;
        setCamera(camera);
        actionSecondary(point, true, root, data);
        actionSecondary(point, false, root, data);
    }

    private boolean wasDraggingR, wasDraggingL = false;
    private boolean wasDownR = false;

    @Override
    public void actionPrimary(Vector2f screenCoord, boolean pressed, final JmeNode rootNode, DataObject dataObject) {
        if (!pressed) {
            if (!wasDraggingL) {
                Vector3f result = pickWorldLocation(getCamera(), screenCoord, rootNode);
                if (result != null) {
                    if (toolController.isSnapToGrid()) {
                        result.set(Math.round(result.x), result.y, Math.round(result.z));
                    }
                    toolController.doSetCursorLocation(result);
                }
            }
            wasDraggingL = false;
        }
    }

    @Override
    public void actionSecondary(final Vector2f screenCoord, boolean pressed, final JmeNode rootNode, DataObject dataObject) {
        if (pressed) {
            Spatial selected;// = toolController.getSelectedSpatial();
            // mouse down

            if (!wasDraggingR && !wasDownR) { // wasn't dragging and was not down already
                // pick on the spot
                Spatial s = pickWorldSpatial(camera, screenCoord, rootNode);
                Vector3f orbitPoint = s == null ? null : pickWorldLocation(camera, screenCoord, rootNode);
                if (!toolController.isSelectTerrain() && isTerrain(s)) {
                    // only select non-terrain
                    selected = null;
                    return;
                } else {

                    // climb up and find the Model Node (parent) and select that, don't select the geom
                    if (!toolController.isSelectGeometries()) {
                        Spatial linkNodeParent = findModelNodeParent(s, rootNode.getLookup().lookup(Spatial.class));
                        if (linkNodeParent != null) {
                            s = linkNodeParent;
                        } else {
                            return;
                        }
                    }
                    // if the selected spatial is a sub spatial of a linked node
                    // select the linked node
                    s = findAssetLinkNode(s);

                    toolController.recordOrbitPick(s, orbitPoint);
                    final boolean additive = toolController.isAdditiveSelection();
                    final Spatial selec = s;
                    selected = selec;
                    java.awt.EventQueue.invokeLater(new Runnable() {

                        @Override
                        public void run() {
                            if (selec != null) {
                                doSelect();
                            }
                        }

                        private void doSelect() {
                            // in case of linked assets the selected node in the viewer is not necessarily in the explorer.
                            JmeSpatial n = rootNode.getChild(selec);
                            if (n != null) {
                                java.util.List<AbstractSceneExplorerNode> selection = new java.util.ArrayList<>();
                                if (additive) {
                                    for (Spatial spatial : toolController.getSelectedSpatials()) {
                                        var existing = rootNode.getChild(spatial);
                                        if (existing != null) {
                                            selection.add(existing);
                                        }
                                    }
                                }
                                if (!selection.remove(n)) {
                                    selection.add(0, n);
                                }
                                var nodes = selection.toArray(AbstractSceneExplorerNode[]::new);
                                if (nodes.length == 0) {
                                    toolController.setSelectedSpatials(java.util.List.of());
                                    toolController.updateSelection(null);
                                }
                                SceneViewerTopComponent.findInstance().setActivatedNodes(nodes);
                                SceneExplorerTopComponent.findInstance().setSelectedNode(nodes);
                            }
                        }
                    });
                }
                // toolController will be updated by the SceneComposerTopComponent
                //toolController.updateSelection(selected);
            }
            wasDownR = true;
        } else {
            // mouse up, stop everything
            wasDownR = false;
            wasDraggingR = false;
        }
    }

    /**
     * Climb up the spatial until we find the first node parent. TODO: use
     * userData to determine the actual model's parent.
     */
    private Spatial findModelNodeParent(Spatial child, Spatial sceneRoot) {
        if (child == null) {
            return null;
        }
        // A standalone Geometry asset has no selectable model parent. Never
        // climb beyond the asset into the SDK's internal Root Node.
        if (child == sceneRoot || child instanceof Node) {
            return child;
        }
        Spatial parent = child.getParent();
        if (parent != null && sceneRoot instanceof Node root
                && (parent == root || parent.hasAncestor(root))) {
            return parent;
        }
        return child;
    }

    /**
     * Search for a AssetLinkNode in the hierarchy of the Spatial spat. If found
     * return the last AssetLinkNode encounter (closest to scene root). Else
     * return the given Spatial spat.
     *
     * @param spat the Spatial to search from.
     * @return a AssetLinkNode if encounter else return spat
     */
    private Spatial findAssetLinkNode(final Spatial spat) {
        Spatial s = spat;
        Spatial linked = null;
        while (s != null) {
            if (s instanceof AssetLinkNode) {
                linked = s;
            }
            s = s.getParent();
        }
        if (linked != null) {
            return linked;
        }
        return spat;
    }

    @Override
    public void mouseMoved(Vector2f screenCoord, JmeNode rootNode, DataObject currentDataObject) {
    }

    @Override
    public void draggedPrimary(Vector2f screenCoord, boolean pressed, JmeNode rootNode, DataObject currentDataObject) {
        wasDraggingL = pressed;
    }

    @Override
    public void draggedSecondary(Vector2f screenCoord, boolean pressed, JmeNode rootNode, DataObject currentDataObject) {
        wasDraggingR = pressed;
    }

    /**
     * Check if the selected item is a Terrain It will climb up the parent tree
     * to see if a parent is terrain too. Recursive call.
     */
    protected boolean isTerrain(Spatial s) {
        if (s == null) {
            return false;
        }
        if (s instanceof Terrain) {
            return true;
        }

        if (s.getParent() != null) {
            return isTerrain(s.getParent());
        }
        return false;
    }
}
