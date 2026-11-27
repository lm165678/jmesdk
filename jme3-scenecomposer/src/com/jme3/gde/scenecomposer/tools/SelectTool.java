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

import com.jme3.asset.AssetManager;
import com.jme3.gde.core.sceneexplorer.SceneExplorerTopComponent;
import com.jme3.gde.core.sceneexplorer.nodes.AbstractSceneExplorerNode;
import com.jme3.gde.core.sceneexplorer.nodes.JmeNode;
import com.jme3.gde.core.sceneexplorer.nodes.JmeSpatial;
import com.jme3.gde.core.sceneviewer.SceneViewerTopComponent;
import com.jme3.gde.scenecomposer.SceneComposerToolController;
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

    @Override
    public void activate(AssetManager manager, Node toolNode, Node onTopToolNode, Spatial selected,
            SceneComposerToolController controller) {
        super.activate(manager, toolNode, onTopToolNode, selected, controller);
        displayCones();
    }

    /** Picks on press and reports an empty hit so the caller can deselect on click release. */
    public boolean selectAt(Vector2f point, com.jme3.renderer.Camera camera, JmeNode root,
            SceneComposerToolController controller) {
        toolController = controller;
        setCamera(camera);
        return pickSelection(point, root);
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
            if (!wasDraggingR && !wasDownR) {
                pickSelection(screenCoord, rootNode);
            }
            wasDownR = true;
        } else {
            // mouse up, stop everything
            wasDownR = false;
            wasDraggingR = false;
        }
    }

    private boolean pickSelection(Vector2f point, JmeNode root) {
        var collision = pickSceneSelection(camera, point, root);
        if (collision == null) {
            toolController.recordOrbitPick(null, null);
            return true;
        }
        Spatial hit = collision.getGeometry();
        if (!toolController.isSelectTerrain() && isTerrain(hit)) {
            return false;
        }
        Spatial target = toolController.isSelectGeometries() ? hit
                : findModelNodeParent(hit, root.getLookup().lookup(Spatial.class));
        if (target != null) {
            Spatial selected = findAssetLinkNode(target);
            toolController.recordOrbitPick(selected, collision.getContactPoint());
            boolean additive = toolController.isAdditiveSelection();
            java.awt.EventQueue.invokeLater(() -> publishSelection(root, selected, additive));
        }
        return false;
    }

    private void publishSelection(JmeNode root, Spatial selected, boolean additive) {
        JmeSpatial node = root.getChild(selected);
        if (node == null) {
            return;
        }
        var selection = new java.util.ArrayList<AbstractSceneExplorerNode>();
        if (additive) {
            for (Spatial spatial : toolController.getSelectedSpatials()) {
                var existing = root.getChild(spatial);
                if (existing != null) {
                    selection.add(existing);
                }
            }
        }
        if (!selection.remove(node)) {
            selection.add(0, node);
        }
        var nodes = selection.toArray(AbstractSceneExplorerNode[]::new);
        if (nodes.length == 0) {
            toolController.setSelectedSpatials(java.util.List.of());
            toolController.updateSelection(null);
        }
        SceneViewerTopComponent.findInstance().setActivatedNodes(nodes);
        SceneExplorerTopComponent.findInstance().setSelectedNode(nodes);
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
