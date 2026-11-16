package com.jme3.gde.scenecomposer.tools;

import com.jme3.gde.scenecomposer.SceneComposerToolController;
import com.jme3.gde.core.undoredo.AbstractUndoableSceneEdit;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import java.util.ArrayList;
import java.util.List;

/** Render-thread group translation; creation and movement share one undo entry.
 * 第一步：Ctrl+左键多选；第二步：W 平移，Shift+拖动复制；第三步：右键取消或 Ctrl+Z 整组撤销。
 * Parent/child selections are reduced to roots. Each copy keeps its original
 * parent; world displacement is converted separately through each parent.
 */
final class GroupMoveGesture {
    private final SceneComposerToolController controller;
    private final List<Spatial> sources = new ArrayList<>();
    private final List<Spatial> targets = new ArrayList<>();
    private final List<Node> parents = new ArrayList<>();
    private final List<Vector3f> before = new ArrayList<>();
    private final boolean duplicate;
    private boolean started;

    GroupMoveGesture(SceneComposerToolController controller, boolean duplicate) {
        this.controller = controller;
        this.duplicate = duplicate;
        Spatial scene = controller.getRootNode().getLookup().lookup(Spatial.class);
        List<Spatial> selected = controller.getSelectedSpatials();
        for (Spatial spatial : selected) {
            if (spatial == scene || spatial.getParent() == null
                    || !(scene instanceof Node root) || !spatial.hasAncestor(root)) {
                continue;
            }
            boolean nested = false;
            for (Spatial other : selected) {
                if (other instanceof Node node && spatial != other && spatial.hasAncestor(node)) {
                    nested = true;
                }
            }
            for (Spatial ancestor = spatial; ancestor != scene; ancestor = ancestor.getParent()) {
                if (ancestor instanceof com.jme3.scene.AssetLinkNode) {
                    nested = true;
                }
            }
            if (!nested && !controller.getRootNode().isReadOnly()) {
                sources.add(spatial);
                parents.add(spatial.getParent());
                before.add(spatial.getLocalTranslation().clone());
            }
        }
    }

    void move(Vector3f delta) {
        if (!started) {
            for (int i = 0; i < sources.size(); i++) {
                Spatial source = sources.get(i);
                Spatial target = duplicate ? source.clone(true) : source;
                if (duplicate) {
                    String base = (source.getName() == null ? "Object" : source.getName()) + " Copy";
                    String name = base;
                    int suffix = 2;
                    while (parents.get(i).getChild(name) != null) {
                        name = base + " " + suffix++;
                    }
                    target.setName(name);
                    parents.get(i).attachChild(target);
                }
                targets.add(target);
            }
            started = true;
        }
        for (int i = 0; i < targets.size(); i++) {
            Node parent = parents.get(i);
            Vector3f localDelta = parent.getWorldRotation().inverse().mult(delta).divideLocal(parent.getWorldScale());
            targets.get(i).setLocalTranslation(before.get(i).add(localDelta));
        }
        update(targets);
    }

    private void update(List<Spatial> selection) {
        Spatial root = controller.getRootNode().getLookup().lookup(Spatial.class);
        while (root.getParent() != null) {
            root = root.getParent();
        }
        root.updateGeometricState();
        for (Spatial spatial : selection) {
            var rigid = spatial.getControl(com.jme3.bullet.control.RigidBodyControl.class);
            if (rigid != null) {
                rigid.setPhysicsLocation(spatial.getWorldTranslation());
            }
            var character = spatial.getControl(com.jme3.bullet.control.CharacterControl.class);
            if (character != null) {
                character.setPhysicsLocation(spatial.getWorldTranslation());
            }
        }
        controller.setSelectedSpatials(selection);
        if (!selection.isEmpty()) {
            controller.setSelected(selection.get(0));
        }
        controller.selectedSpatialTransformed();
        controller.setNeedsSave(true);
    }

    private void refresh(List<Spatial> selection) {
        var sceneNode = controller.getRootNode();
        java.awt.EventQueue.invokeLater(() -> {
            sceneNode.refresh(true);
            for (Node parent : parents) {
                var node = sceneNode.getChild(parent);
                if (node != null) {
                    node.refresh(true);
                }
            }
            List<com.jme3.gde.core.sceneexplorer.nodes.AbstractSceneExplorerNode> nodes = new ArrayList<>();
            for (Spatial spatial : selection) {
                var node = sceneNode.getChild(spatial);
                if (node != null) {
                    nodes.add(node);
                }
            }
            var selected = nodes.toArray(com.jme3.gde.core.sceneexplorer.nodes.AbstractSceneExplorerNode[]::new);
            com.jme3.gde.core.sceneviewer.SceneViewerTopComponent.findInstance().setActivatedNodes(selected);
            com.jme3.gde.core.sceneexplorer.SceneExplorerTopComponent.findInstance().setSelectedNode(selected);
        });
    }

    void cancel() {
        for (int i = 0; i < targets.size(); i++) {
            if (duplicate) {
                targets.get(i).removeFromParent();
            } else {
                targets.get(i).setLocalTranslation(before.get(i));
            }
        }
        update(sources);
        refresh(sources);
    }

    AbstractUndoableSceneEdit finish() {
        if (!started || targets.isEmpty()) {
            return null;
        }
        List<Vector3f> after = new ArrayList<>();
        for (Spatial spatial : targets) {
            after.add(spatial.getLocalTranslation().clone());
        }
        refresh(started ? targets : sources);
        return new AbstractUndoableSceneEdit() {
            @Override
            public void sceneUndo() {
                cancel();
            }
            @Override
            public void sceneRedo() {
                for (int i = 0; i < targets.size(); i++) {
                    if (duplicate) {
                        parents.get(i).attachChild(targets.get(i));
                    }
                    targets.get(i).setLocalTranslation(after.get(i));
                }
                update(targets);
                refresh(targets);
            }
        };
    }
}
