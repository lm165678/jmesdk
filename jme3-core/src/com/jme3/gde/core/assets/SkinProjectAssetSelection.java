package com.jme3.gde.core.assets;

import com.jme3.gde.texturetools.SkinAssetSelection;
import com.jme3.gde.core.dnd.AssetNameHolder;
import com.jme3.gde.core.dnd.TextureDataFlavor;
import com.jme3.gde.core.dnd.MaterialDataFlavor;
import com.jme3.gde.core.dnd.SpatialDataFlavor;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.datatransfer.Transferable;
import java.nio.file.Path;
import java.util.ArrayList;
import javax.swing.*;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.*;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ProjectUtils;
import org.netbeans.api.project.ui.OpenProjects;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.loaders.DataObject;
import org.openide.nodes.Node;
import org.openide.nodes.NodeTransfer;
import org.openide.util.lookup.ServiceProvider;

/** Open-project selectors and internal asset drops, with no OS file picker. */
@ServiceProvider(service = SkinAssetSelection.class)
public final class SkinProjectAssetSelection implements SkinAssetSelection {
    private static final class Choice {
        private final String name;
        private final FileObject folder;

        private Choice(String name, FileObject folder) {
            this.name = name;
            this.folder = folder;
        }

        @Override
        public String toString() {
            return name + " — " + folder.getPath();
        }
    }

    private Choice choose(Component parent) {
        var choices = new ArrayList<Choice>();
        for (Project project : OpenProjects.getDefault().getOpenProjects()) {
            ProjectAssetManager manager = project.getLookup().lookup(ProjectAssetManager.class);
            FileObject folder = manager == null ? null : manager.getAssetFolder();
            if (folder != null && FileUtil.toFile(folder) != null) {
                choices.add(new Choice(ProjectUtils.getInformation(project).getDisplayName(), folder));
            }
        }
        if (choices.isEmpty()) {
            JOptionPane.showMessageDialog(parent, "Open a game project with an Assets folder first.");
            return null;
        }
        JList<Choice> list = new JList<>(choices.toArray(Choice[]::new));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setSelectedIndex(0);
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new java.awt.Dimension(600, 220));
        return JOptionPane.showConfirmDialog(parent, scroll, "Select an open SDK project",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION
                ? list.getSelectedValue() : null;
    }

    @Override public Path chooseProject(Component parent) {
        Choice project = choose(parent);
        return project == null ? null : FileUtil.toFile(project.folder).toPath();
    }

    private static final class AssetNode extends DefaultMutableTreeNode {
        final FileObject file;
        final String kind;
        boolean loaded;
        AssetNode(FileObject file, String kind) {
            super(file.getNameExt());
            this.file = file;
            this.kind = kind;
            if (file.isFolder()) {
                add(new DefaultMutableTreeNode("Loading..."));
            }
        }
        void load() {
            if (loaded || !file.isFolder()) {
                return;
            }
            loaded = true;
            removeAllChildren();
            FileObject[] children = file.getChildren();
            java.util.Arrays.sort(children, java.util.Comparator.comparing(FileObject::isData)
                    .thenComparing(FileObject::getNameExt, String.CASE_INSENSITIVE_ORDER));
            for (FileObject child : children) {
                if (child.isFolder() || ("material".equals(kind) ? child.hasExt("j3m") : "model".equals(kind) ? child.hasExt("j3o")
                        : com.jme3.gde.texturetools.SkinTextureInput.isTexture(child.getNameExt()))) {
                    add(new AssetNode(child, kind));
                }
            }
        }
    }

    @Override public Path chooseTexture(Component parent) {
        return chooseAsset(parent, "texture");
    }

    @Override public Path chooseMaterial(Component parent) {
        return chooseAsset(parent, "material");
    }

    @Override public Path chooseModel(Component parent) {
        return chooseAsset(parent, "model");
    }

    @Override public Path projectForAsset(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        Path result = null;
        for (Project project : OpenProjects.getDefault().getOpenProjects()) {
            ProjectAssetManager manager = project.getLookup().lookup(ProjectAssetManager.class);
            FileObject folder = manager == null ? null : manager.getAssetFolder();
            java.io.File local = folder == null ? null : FileUtil.toFile(folder);
            if (local != null) {
                Path root = local.toPath().toAbsolutePath().normalize();
                if (absolute.startsWith(root) && (result == null || root.getNameCount() > result.getNameCount())) {
                    result = root;
                }
            }
        }
        return result;
    }

    private Path chooseAsset(Component parent, String kind) {
        Choice project = choose(parent);
        if (project == null) {
            return null;
        }
        AssetNode root = new AssetNode(project.folder, kind);
        root.load();
        DefaultTreeModel model = new DefaultTreeModel(root);
        JTree tree = new JTree(model);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override public void treeWillExpand(TreeExpansionEvent event) {
                AssetNode node = (AssetNode) event.getPath().getLastPathComponent();
                node.load();
                model.nodeStructureChanged(node);
            }
            @Override public void treeWillCollapse(TreeExpansionEvent event) {
            }
        });
        JScrollPane scroll = new JScrollPane(tree);
        scroll.setPreferredSize(new java.awt.Dimension(600, 420));
        while (JOptionPane.showConfirmDialog(parent, scroll, "Select " + kind + " in " + project.name,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            Object selected = tree.getLastSelectedPathComponent();
            if (selected instanceof AssetNode) {
                AssetNode node = (AssetNode) selected;
                if (node.file.isData()) {
                    return FileUtil.toFile(node.file).toPath();
                }
            }
            JOptionPane.showMessageDialog(parent, "Select a " + kind + " file, not a folder.");
        }
        return null;
    }

    @Override public Path textureFromTransfer(Transferable transferable) throws Exception {
        FileObject file = null;
        if (transferable.isDataFlavorSupported(TextureDataFlavor.instance)
                || transferable.isDataFlavorSupported(MaterialDataFlavor.instance)
                || transferable.isDataFlavorSupported(SpatialDataFlavor.instance)) {
            var flavor = transferable.isDataFlavorSupported(MaterialDataFlavor.instance)
                    ? MaterialDataFlavor.instance : transferable.isDataFlavorSupported(SpatialDataFlavor.instance)
                    ? SpatialDataFlavor.instance : TextureDataFlavor.instance;
            Object value = transferable.getTransferData(flavor);
            if (value instanceof AssetNameHolder) {
                AssetNameHolder holder = (AssetNameHolder) value;
                file = holder.getAssetFile();
            }
        } else {
            Node node = NodeTransfer.node(transferable, NodeTransfer.DND_COPY_OR_MOVE | NodeTransfer.CLIPBOARD_COPY);
            if (node != null) {
                file = node.getLookup().lookup(FileObject.class);
                if (file == null) {
                    DataObject data = node.getLookup().lookup(DataObject.class);
                    file = data == null ? null : data.getPrimaryFile();
                }
            }
        }
        return file == null || FileUtil.toFile(file) == null ? null : FileUtil.toFile(file).toPath();
    }

    @Override
    public void surfaceMaterialExported(Path assetRoot, String materialKey) {
        Path root = assetRoot.toAbsolutePath().normalize();
        for (Project project : OpenProjects.getDefault().getOpenProjects()) {
            ProjectAssetManager manager = project.getLookup().lookup(ProjectAssetManager.class);
            FileObject folder = manager == null ? null : manager.getAssetFolder();
            java.io.File local = folder == null ? null : FileUtil.toFile(folder);
            if (local == null || !root.equals(local.toPath().toAbsolutePath().normalize())) {
                continue;
            }
            com.jme3.gde.core.scene.SceneApplication application = com.jme3.gde.core.scene.SceneApplication.getApplication();
            application.enqueue(() -> {
                com.jme3.asset.MaterialKey key = new com.jme3.asset.MaterialKey(materialKey);
                manager.deleteFromCache(key);
                com.jme3.material.Material saved = manager.loadMaterial(materialKey);
                for (String name : com.jme3.gde.texturetools.SkinMaterialUpdates.SURFACE_PARAMETERS) {
                    com.jme3.material.MatParam parameter = saved.getParam(name);
                    if (parameter instanceof com.jme3.material.MatParamTexture) {
                        com.jme3.texture.Texture texture = ((com.jme3.material.MatParamTexture) parameter).getTextureValue();
                        if (texture.getKey() != null) {
                            manager.deleteFromCache(texture.getKey());
                        }
                    }
                }
                manager.deleteFromCache(key);
                saved = manager.loadMaterial(materialKey);
                com.jme3.gde.core.scene.SceneRequest request = application.getCurrentSceneRequest();
                if (request != null && request.getManager() == manager && request.getRootNode() != null) {
                    final com.jme3.material.Material exported = saved;
                    final boolean[] changed = {false};
                    request.getRootNode().depthFirstTraversal(spatial -> {
                        if (spatial instanceof com.jme3.scene.Geometry) {
                            com.jme3.material.Material target = ((com.jme3.scene.Geometry) spatial).getMaterial();
                            if (target != null && materialKey.equals(target.getAssetName())) {
                                com.jme3.gde.texturetools.SkinMaterialUpdates.apply(target, exported);
                                changed[0] = true;
                            }
                        }
                    });
                    if (changed[0] && request.getDataObject() != null) {
                        SwingUtilities.invokeLater(() -> request.getDataObject().setModified(true));
                    }
                }
                return null;
            });
            break;
        }
    }
}
