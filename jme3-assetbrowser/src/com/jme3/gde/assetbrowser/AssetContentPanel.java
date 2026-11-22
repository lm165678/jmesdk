package com.jme3.gde.assetbrowser;

import com.jme3.gde.core.assets.ProjectAssetManager;
import com.jme3.gde.core.assets.SpatialAssetDataObject;
import com.jme3.gde.core.assets.FilterDataObject;
import com.jme3.gde.core.scene.SceneApplication;
import com.jme3.gde.texturetools.GeneratedTextures;
import com.jme3.gde.scenecomposer.SceneComposerTopComponent;
import com.jme3.gde.textureeditor.ImageEditorTopComponent;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.event.*;
import javax.swing.tree.*;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ProjectUtils;
import org.netbeans.api.project.ui.OpenProjects;
import org.openide.DialogDisplayer;
import org.openide.NotifyDescriptor;
import org.openide.filesystems.FileUtil;
import java.io.IOException;
import org.openide.cookies.EditCookie;
import org.openide.cookies.OpenCookie;
import org.openide.filesystems.*;
import org.openide.loaders.DataObject;
import org.openide.util.NbBundle;
import org.openide.util.RequestProcessor;
import org.openide.windows.TopComponent;

/**
 * Content browser rooted at the project's configured Assets directory. Small
 * resizable controls, virtualized cells, bounded thumbnail work and debounced
 * filesystem refresh keep browsing independent of the active scene editor.
 * Normal browsing lists immediate child folders first, then files; searches
 * include descendants. Double-click/Enter opens folders and synchronizes the tree,
 * or opens a file in its editor. Type filters keep folder navigation available.
 * GUI thumbnails render the first Nifty screen without game controllers;
 * standalone shader files show source because they need a material context.
 * File actions use NetBeans Explorer/DataObject operations, including delete
 * confirmation and project template context. Dragging a model/material transfers
 * its source identity so the scene can reject assets from a different project.
 * <pre>
 * 第一步：选择 Assets 子目录，右键 New File 使用项目的 JME3 资产模板。
 * 新建文件夹：右键目录或空白处选择 New Folder；目录树右键复制/删除作用于该目录。
 * 重命名资源：右键文件或目录选择 Rename（保留扩展名）；生成纹理的 dds 目录受登记表保护而禁用，
 * 需重命名源纹理并重新转换，避免 J3O 中的逻辑纹理 key 与物理输出失配。
 * 第二步：选择资产，Ctrl+C / Ctrl+V 复制粘贴，Delete 使用标准删除确认。
 * 第三步：双击资产打开编辑器，拖动模型或材质到当前项目的 3D Viewer。
 * 第四步：源模型右键 Convert to J3O 配置导出，取消不会写入文件。
 * </pre>
 */
public final class AssetContentPanel extends JPanel implements org.openide.explorer.ExplorerManager.Provider {
    private record ProjectItem(ProjectAssetManager manager, String title) {
        @Override public String toString() { return title; }
    }
    private record Entry(FileObject file, String path, AssetKind kind) {
        @Override public String toString() { return file.getNameExt(); }
    }
    private record Folder(String path) {
        @Override public String toString() {
            int slash = path.lastIndexOf('/');
            return path.isEmpty() ? "Assets" : path.substring(slash + 1);
        }
    }
    private final JComboBox<ProjectItem> projects = new JComboBox<>();
    private final JCheckBox hideOriginals = new JCheckBox(text("HideOriginals"), true);
    private final JTextField search = new JTextField(18);
    private final JComboBox<String> types = new JComboBox<>();
    private final JTree folders = new JTree(new DefaultMutableTreeNode(new Folder("")));
    private final org.openide.explorer.ExplorerManager explorer = new org.openide.explorer.ExplorerManager();
    private FileObject folderMenuTarget;
    private Action copyAsset;
    private Action pasteAsset;
    private Action deleteAsset;

    @Override
    public org.openide.explorer.ExplorerManager getExplorerManager() {
        return explorer;
    }

    private final DefaultListModel<Entry> entries = new DefaultListModel<>();
    private final JList<Entry> grid = new JList<>(entries);
    private final JLabel status = new JLabel(" ");
    private final JLabel heap = new JLabel(" ");
    private final AssetInspector inspector = new AssetInspector(this::updateSelectionStatus);
    private final javax.swing.Timer heapTimer = new javax.swing.Timer(1500, event -> updateHeap());
    private String catalogStatus = " ";
    private final RequestProcessor io = new RequestProcessor("Asset catalog", 1, true);
    private final javax.swing.Timer refreshTimer = new javax.swing.Timer(350, event -> scan());
    private final javax.swing.Timer searchTimer = new javax.swing.Timer(180, event -> filter());
    private AssetThumbnails thumbnails;
    private final BufferedImage pendingImage = AssetThumbnails.card("...", "", new Color(89, 133, 180));
    private List<Entry> all = List.of();
    private ProjectAssetManager manager;
    private File watched;
    private String directory = "";
    private long revision;
    private long objRevision;
    private long refreshVersion;
    private long generation;
    private boolean active;
    private boolean changingProjects;
    private boolean rebuildingGrid;
    private int tileSize = 96;
    private final PropertyChangeListener projectListener = event -> SwingUtilities.invokeLater(this::reloadProjects);
    private final FileChangeListener filesListener = new FileChangeAdapter() {
        private void changed() { SwingUtilities.invokeLater(() -> { if (active) { refreshTimer.restart(); } }); }
        @Override public void fileChanged(FileEvent event) { changed(); }
        @Override public void fileDataCreated(FileEvent event) { changed(); }
        @Override public void fileFolderCreated(FileEvent event) { changed(); }
        @Override public void fileDeleted(FileEvent event) { changed(); }
        @Override public void fileRenamed(FileRenameEvent event) { changed(); }
    };

    public AssetContentPanel() {
        super(new BorderLayout(4, 4));
        setMinimumSize(new Dimension(0, 100));
        setPreferredSize(new Dimension(800, 260));
        setBorder(BorderFactory.createEmptyBorder(4, 5, 4, 5));
        refreshTimer.setRepeats(false);
        searchTimer.setRepeats(false);
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        projects.setPreferredSize(new Dimension(170, 26));
        projects.setMaximumSize(new Dimension(230, 26));
        search.setToolTipText(text("SearchTip"));
        types.addItem(text("AllTypes"));
        for (AssetKind kind : AssetKind.values()) {
            types.addItem(text(kind.name()));
        }
        JButton refresh = new JButton(text("Refresh"));
        refresh.addActionListener(event -> { refreshVersion++; scan(); });
        JSlider size = new JSlider(64, 160, tileSize);
        size.setPreferredSize(new Dimension(85, 24));
        size.setToolTipText(text("TileSize"));
        size.addChangeListener(event -> {
            tileSize = size.getValue();
            grid.setFixedCellWidth(tileSize + 18);
            grid.setFixedCellHeight(tileSize + 39);
        });
        JCheckBox showPreview = new JCheckBox(text("Preview"), false);
        showPreview.setToolTipText(text("PreviewTip"));
        com.jme3.gde.core.util.OverflowToolbar.install(bar,
                new Component[]{projects}, new Component[]{new JLabel(text("Search")), search},
                new Component[]{types}, new Component[]{refresh}, new Component[]{size},
                new Component[]{showPreview}, new Component[]{hideOriginals});
        hideOriginals.addActionListener(event -> filter());
        add(bar, BorderLayout.NORTH);
        grid.setLayoutOrientation(JList.HORIZONTAL_WRAP);
        grid.setVisibleRowCount(-1);
        grid.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        grid.setFixedCellWidth(tileSize + 18);
        grid.setFixedCellHeight(tileSize + 39);
        grid.setCellRenderer(new Cell());
        grid.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !rebuildingGrid) {
                folderMenuTarget = null;
                showSelected();
                syncAssetSelection();
            }
        });
        installAssetActions();
        grid.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent event) { popup(event); }
            @Override public void mouseReleased(MouseEvent event) { popup(event); }
            @Override public void mouseClicked(MouseEvent event) {
                int index = grid.locationToIndex(event.getPoint());
                if (SwingUtilities.isLeftMouseButton(event) && event.getClickCount() == 2 && index >= 0
                        && grid.getCellBounds(index, index).contains(event.getPoint())) {
                    open(entries.get(index));
                }
            }
        });
        grid.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "openAsset");
        grid.getActionMap().put("openAsset", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { open(grid.getSelectedValue()); }
        });
        ToolTipManager.sharedInstance().registerComponent(grid);
        JScrollPane gridScroll = new JScrollPane(grid);
        gridScroll.setTransferHandler(grid.getTransferHandler());
        gridScroll.getViewport().setTransferHandler(grid.getTransferHandler());
        gridScroll.getVerticalScrollBar().setUnitIncrement(28);
        folders.setRootVisible(true);
        folders.addTreeSelectionListener(event -> {
            Object value = folders.getLastSelectedPathComponent();
            if (value instanceof DefaultMutableTreeNode node && node.getUserObject() instanceof Folder folder) {
                directory = folder.path();
                syncAssetSelection();
                filter();
            }
        });
        folders.addMouseListener(new MouseAdapter() {
            private void showFolderMenu(MouseEvent event) {
                if (!event.isPopupTrigger()) {
                    return;
                }
                var path = folders.getPathForLocation(event.getX(), event.getY());
                if (path != null) {
                    folders.setSelectionPath(path);
                    grid.clearSelection();
                    syncAssetSelection();
                    folderMenuTarget = currentFolder();
                    showAssetPopup(folders, event.getX(), event.getY());
                }
            }
            @Override public void mousePressed(MouseEvent event) { showFolderMenu(event); }
            @Override public void mouseReleased(MouseEvent event) { showFolderMenu(event); }
        });
        JScrollPane treeScroll = new JScrollPane(folders);
        treeScroll.setMinimumSize(new Dimension(0, 0));
        treeScroll.setPreferredSize(new Dimension(170, 160));
        JSplitPane content = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, gridScroll, inspector);
        content.setResizeWeight(1);
        // Give the asset grid the full width while the preview is closed.
        // Reserve inspector space only when the user opens the preview.
        inspector.setVisible(false);
        content.setDividerSize(0);
        showPreview.addActionListener(event -> {
            boolean visible = showPreview.isSelected();
            inspector.setVisible(visible);
            content.setDividerSize(visible ? 5 : 0);
            if (visible) {
                content.setDividerLocation(Math.max(0, content.getWidth() - 305));
                showSelected();
            } else {
                inspector.clear();
                updateSelectionStatus();
            }
            content.revalidate();
            content.repaint();
        });
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeScroll, content);
        split.setResizeWeight(0);
        split.setDividerSize(5);
        split.setDividerLocation(170);
        add(split, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(10, 0));
        footer.add(status, BorderLayout.CENTER);
        footer.add(heap, BorderLayout.EAST);
        add(footer, BorderLayout.SOUTH);
        projects.addActionListener(event -> { if (!changingProjects) { chooseProject(); } });
        types.addActionListener(event -> filter());
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { searchTimer.restart(); }
            @Override public void removeUpdate(DocumentEvent event) { searchTimer.restart(); }
            @Override public void changedUpdate(DocumentEvent event) { searchTimer.restart(); }
        });
    }

    static String text(String key) {
        return NbBundle.getMessage(AssetContentPanel.class, "Content." + key);
    }

    public void start() {
        if (active) { return; }
        active = true;
        thumbnails = new AssetThumbnails();
        updateHeap();
        heapTimer.start();
        OpenProjects.getDefault().addPropertyChangeListener(projectListener);
        reloadProjects();
    }

    public void stop() {
        active = false;
        generation++;
        refreshTimer.stop();
        searchTimer.stop();
        heapTimer.stop();
        inspector.clear();
        OpenProjects.getDefault().removePropertyChangeListener(projectListener);
        unwatch();
        if (thumbnails != null) { thumbnails.close(); }
    }

    private void reloadProjects() {
        if (!active) { return; }
        ProjectAssetManager previous = manager;
        changingProjects = true;
        projects.removeAllItems();
        int selected = 0;
        for (Project project : OpenProjects.getDefault().getOpenProjects()) {
            ProjectAssetManager assets = project.getLookup().lookup(ProjectAssetManager.class);
            if (assets != null) {
                projects.addItem(new ProjectItem(assets, ProjectUtils.getInformation(project).getDisplayName()));
                if (assets == previous || (previous == null && project == OpenProjects.getDefault().getMainProject())) {
                    selected = projects.getItemCount() - 1;
                }
            }
        }
        if (projects.getItemCount() > 0) { projects.setSelectedIndex(selected); }
        changingProjects = false;
        chooseProject();
    }

    private void unwatch() {
        if (watched != null) {
            FileUtil.removeRecursiveListener(filesListener, watched);
            watched = null;
        }
    }

    private void chooseProject() {
        unwatch();
        inspector.clear();
        grid.clearSelection();
        ProjectItem item = (ProjectItem) projects.getSelectedItem();
        manager = item == null ? null : item.manager();
        if (manager != null) {
            com.jme3.gde.core.assets.ExternalAssetImport.watch(manager);
        }
        directory = "";
        FileObject root = manager == null ? null : manager.getAssetFolder();
        if (root != null) {
            watched = FileUtil.toFile(root);
            if (watched != null) { FileUtil.addRecursiveListener(filesListener, watched); }
        }
        syncAssetSelection();
        scan();
    }

    private void scan() {
        long ticket = ++generation;
        ProjectAssetManager assets = manager;
        FileObject root = assets == null ? null : assets.getAssetFolder();
        if (root == null) {
            all = List.of();
            entries.clear();
            folders.setModel(new DefaultTreeModel(new DefaultMutableTreeNode(new Folder(""))));
            catalogStatus = text("NoProject");
            updateSelectionStatus();
            return;
        }
        catalogStatus = text("Scanning");
        updateSelectionStatus();
        io.post(() -> {
            List<Entry> found = new ArrayList<>();
            SortedSet<String> dirs = new TreeSet<>();
            collect(root, root, found, dirs, new HashSet<>());
            found.sort(Comparator.comparing((Entry entry) -> !entry.file().isFolder())
                    .thenComparing(Entry::path, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(Entry::path));
            long stamp = 17;
            long objStamp = 17;
            for (Entry entry : found) {
                // Sidecar writes or directory scans can change folder timestamps.
                // Check the asset file itself to decide whether its thumbnail is stale.
                if (entry.file().isFolder()) {
                    continue;
                }
                stamp = stamp * 31 + entry.path().hashCode();
                stamp = stamp * 31 + entry.file().lastModified().getTime();
                stamp = stamp * 31 + entry.file().getSize();
                // An OBJ thumbnail depends on its MTL and textures.
                // Saving the converted J3O should not invalidate the OBJ thumbnail.
                if (!entry.file().getExt().equalsIgnoreCase("j3o")) {
                    objStamp = objStamp * 31 + entry.path().hashCode();
                    objStamp = objStamp * 31 + entry.file().lastModified().getTime();
                    objStamp = objStamp * 31 + entry.file().getSize();
                }
            }
            // Sidecars are model dependencies, so leave them out of the asset list.
            found.removeIf(entry -> !entry.file().isFolder()
                    && (entry.file().getExt().equalsIgnoreCase("j3odata")
                    || entry.file().getExt().equalsIgnoreCase("bin")));
            long changed = stamp;
            long objChanged = objStamp;
            SwingUtilities.invokeLater(() -> {
                if (!active || ticket != generation) { return; }
                all = List.copyOf(found);
                revision = changed + refreshVersion;
                objRevision = objChanged + refreshVersion;
                DefaultMutableTreeNode tree = new DefaultMutableTreeNode(new Folder(""));
                Map<String, DefaultMutableTreeNode> nodes = new HashMap<>();
                nodes.put("", tree);
                for (String dir : dirs) {
                    int slash = dir.lastIndexOf('/');
                    String parent = slash < 0 ? "" : dir.substring(0, slash);
                    DefaultMutableTreeNode node = new DefaultMutableTreeNode(new Folder(dir));
                    nodes.get(parent).add(node);
                    nodes.put(dir, node);
                }
                folders.setModel(new DefaultTreeModel(tree));
                DefaultMutableTreeNode selected = nodes.getOrDefault(directory, tree);
                folders.setSelectionPath(new TreePath(selected.getPath()));
                filter();
            });
        });
    }

    private void collect(FileObject root, FileObject folder, List<Entry> found, Set<String> dirs, Set<Path> visited) {
        if (!active) { return; }
        try {
            File local = FileUtil.toFile(folder);
            File base = FileUtil.toFile(root);
            if (local != null && base != null) {
                Path real = local.toPath().toRealPath();
                if (!real.startsWith(base.toPath().toRealPath()) || !visited.add(real)) { return; }
            }
            for (FileObject file : folder.getChildren()) {
                if (file.getNameExt().startsWith(".")) { continue; }
                String relative = FileUtil.getRelativePath(root, file);
                if (relative == null) { continue; }
                if (file.isFolder()) {
                    dirs.add(relative);
                    found.add(new Entry(file, relative, AssetKind.OTHER));
                    collect(root, file, found, dirs, visited);
                } else {
                    found.add(new Entry(file, relative, AssetKind.of(file)));
                }
            }
        } catch (java.io.IOException ignored) {
            // A folder can disappear between filesystem notification and scan.
        }
    }

    /**
     * 第一步：默认隐藏外部图片和模型源文件，文件夹仍可导航。
     * 第二步：取消 Hide original assets 可查看源文件；此开关不删除文件。
     */
    private static boolean isOriginalAsset(String extension) {
        return switch (extension.toLowerCase(Locale.ROOT)) {
            case "png", "jpg", "jpeg", "bmp", "tga", "gif", "tif", "tiff",
                    "mp3", "mp4", "m4a", "aac", "flac", "hdr", "exr", "fbx", "gltf", "glb", "bin", "obj", "mtl", "dae", "blend", "3ds" -> true;
            default -> false;
        };
    }
    private void filter() {
        Entry selected = grid.getSelectedValue();
        rebuildingGrid = true;
        entries.clear();
        String query = search.getText().trim().toLowerCase(Locale.ROOT);
        int type = types.getSelectedIndex() - 1;
        for (Entry entry : all) {
            int slash = entry.path().lastIndexOf('/');
            String parent = slash < 0 ? "" : entry.path().substring(0, slash);
            boolean location = query.isEmpty() ? parent.equals(directory)
                    : directory.isEmpty() || entry.path().startsWith(directory + "/");
            if (hideOriginals.isSelected() && !entry.file().isFolder()
                    && isOriginalAsset(entry.file().getExt())) {
                continue;
            }
            if (location && entry.path().toLowerCase(Locale.ROOT).contains(query)
                    && (entry.file().isFolder() || type < 0 || entry.kind().ordinal() == type)) {
                entries.addElement(entry);
            }
        }
        if (selected != null) { grid.setSelectedValue(selected, false); }
        rebuildingGrid = false;
        catalogStatus = "Assets/" + directory + "  —  " + entries.size() + " " + text("Assets");
        showSelected();
        syncAssetSelection();
    }

    /** Standalone images/audio/source only depend on themselves; composite assets
     * retain conservative project dependency invalidation, including binary sidecars.
     * Directory navigation never changes either revision. Explicit Refresh forces
     * regeneration; filesystem notifications rescan off EDT after a short debounce.
     * <pre>
     * 第一步：打开目录，首次可见的资产生成并保存缩略图。
     * 第二步：切换目录再返回，复用内存或磁盘缓存。
     * 第三步：保存资源或其依赖，文件监听触发失效并更新可见缩略图。
     * 第四步：需要强制重建时点击 Refresh；关闭窗口会取消待处理任务。
     * </pre>
     */
    private long thumbnailRevision(Entry entry) {
        return switch (entry.kind()) {
            case TEXTURE, AUDIO, SHADER -> refreshVersion;
            default -> entry.file().getExt().equalsIgnoreCase("obj") ? objRevision : revision;
        };
    }

    private AssetThumbnails.Result image(Entry entry) {
        if (entry.file().isFolder() || manager == null || thumbnails == null || !active) { return null; }
        return thumbnails.request(manager, entry.file(), entry.path(),
                thumbnailRevision(entry), result -> {
            if (active) {
                grid.repaint();
                if (entry.equals(grid.getSelectedValue())) { showSelected(); }
            }
        });
    }

    private void showSelected() {
        Entry entry = grid.getSelectedValue();
        updateSelectionStatus();
        if (!active || !inspector.isVisible()) {
            return;
        }
        if (entry == null) {
            inspector.clear();
            return;
        }
        inspector.select(manager, entry.file(), entry.path(), entry.kind(), thumbnailRevision(entry));
        if (!entry.file().isFolder() && entry.kind() != AssetKind.TEXTURE) {
            AssetThumbnails.Result result = image(entry);
            inspector.poster(result == null ? pendingImage : result.image());
        }
    }

    private void updateSelectionStatus() {
        Entry entry = grid.getSelectedValue();
        String message = catalogStatus;
        if (entry != null && !entry.file().isFolder()) {
            message += "  —  " + entry.file().getNameExt() + "  ·  " + text("PreviewFile")
                    + " " + AssetInspector.units(entry.file().getSize());
            long estimate = inspector.memoryBytes();
            message += "  ·  " + text("PreviewMemory") + " " + (estimate < 0 ? "—" : AssetInspector.units(estimate));
        }
        status.setText(message);
    }

    private void updateHeap() {
        Runtime runtime = Runtime.getRuntime();
        heap.setText(text("PreviewHeap") + " " + AssetInspector.units(runtime.totalMemory() - runtime.freeMemory())
                + " / " + AssetInspector.units(runtime.maxMemory()));
    }

    private final class Cell extends JPanel implements ListCellRenderer<Entry> {
        private final JLabel icon = new JLabel("", SwingConstants.CENTER);
        private final JLabel label = new JLabel("", SwingConstants.CENTER);
        Cell() {
            super(new BorderLayout(0, 4));
            add(icon, BorderLayout.CENTER);
            add(label, BorderLayout.SOUTH);
        }
        @Override public Component getListCellRendererComponent(JList<? extends Entry> list, Entry entry,
                int index, boolean selected, boolean focus) {
            AssetThumbnails.Result result = image(entry);
            icon.setIcon(entry.file().isFolder() ? new FolderThumb(tileSize)
                    : new Thumb(result == null ? pendingImage : result.image(), tileSize));
            label.setText(entry.file().getNameExt());
            label.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
            setToolTipText(entry.path() + " — " + text(entry.file().isFolder() ? "Folder" : entry.kind().name()));
            return this;
        }
    }

    /** Uses the IDE's folder icon without submitting a thumbnail job. */
    private record FolderThumb(int size) implements Icon {
        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }
        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Icon icon = UIManager.getIcon("FileView.directoryIcon");
            if (icon == null) {
                icon = UIManager.getIcon("Tree.closedIcon");
            }
            if (icon == null) {
                return;
            }
            Graphics2D g = (Graphics2D) graphics.create();
            double scale = Math.min(size * .65, 64) / Math.max(icon.getIconWidth(), icon.getIconHeight());
            g.translate(x + (size - icon.getIconWidth() * scale) / 2,
                    y + (size - icon.getIconHeight() * scale) / 2);
            g.scale(scale, scale);
            icon.paintIcon(component, g, 0, 0);
            g.dispose();
        }
    }

    private void openFolder(Entry entry) {
        directory = entry.path();
        search.setText("");
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) folders.getModel().getRoot();
        var descendants = root.depthFirstEnumeration();
        while (descendants.hasMoreElements()) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) descendants.nextElement();
            if (node.getUserObject() instanceof Folder folder && folder.path().equals(directory)) {
                TreePath path = new TreePath(node.getPath());
                folders.setSelectionPath(path);
                folders.scrollPathToVisible(path);
                break;
            }
        }
        filter();
        syncAssetSelection();
        grid.ensureIndexIsVisible(0);
    }

    private record Thumb(BufferedImage image, int size) implements Icon {
        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }
        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, x, y, size, size, null);
            g.dispose();
        }
    }

    private FileObject currentFolder() {
        return manager == null ? null : directory.isEmpty() ? manager.getAssetFolder()
                : manager.getAssetFolder().getFileObject(directory);
    }

    private void syncAssetSelection() {
        try {
            FileObject folder = currentFolder();
            if (folder == null) {
                explorer.setRootContext(org.openide.nodes.Node.EMPTY);
                return;
            }
            var root = org.openide.loaders.DataFolder.findFolder(folder).getNodeDelegate();
            explorer.setRootContext(root);
            java.util.List<org.openide.nodes.Node> nodes = new ArrayList<>();
            for (Entry entry : grid.getSelectedValuesList()) {
                if (entry.file().isValid() && FileUtil.isParentOf(folder, entry.file())) {
                    nodes.add(DataObject.find(entry.file()).getNodeDelegate());
                }
            }
            explorer.setSelectedNodes(nodes.toArray(org.openide.nodes.Node[]::new));
        } catch (Exception error) {
            report(error);
        }
    }

    private java.util.List<Entry> selectedFileEntries() {
        FileObject target = folderMenuTarget;
        if (target != null) {
            if (!target.isValid() || target.equals(manager.getAssetFolder())) {
                return java.util.List.of();
            }
            return java.util.List.of(new Entry(target,
                    FileUtil.getRelativePath(manager.getAssetFolder(), target), null));
        }
        return grid.getSelectedValuesList();
    }

    /** Creates a direct child of this folder. The name must be a single path component. */
    private void createFolder(FileObject parent) {
        NotifyDescriptor.InputLine input = new NotifyDescriptor.InputLine(
                "Folder name", "New Folder");
        if (!NotifyDescriptor.OK_OPTION.equals(DialogDisplayer.getDefault().notify(input))) {
            return;
        }
        String name = input.getInputText().trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf((char) 92) >= 0) {
            report(new java.io.IOException("Enter a single asset name."));
            return;
        }
        io.post(() -> {
            try {
                if (parent.getFileObject(name) != null) {
                    throw new java.io.IOException("A file or folder with this name already exists.");
                }
                parent.createFolder(name);
            } catch (Exception error) {
                SwingUtilities.invokeLater(() -> report(error));
            } finally {
                SwingUtilities.invokeLater(this::scan);
            }
        });
    }

    /** Keeps generated DDS files and their own folder fixed during rename. */
    private boolean isGeneratedRenameTarget(FileObject folder) {
        File local = FileUtil.toFile(folder);
        if (local == null) {
            return false;
        }
        GeneratedTextures store = GeneratedTextures.containing(local.toPath());
        if (store == null) {
            return false;
        }
        try {
            return folder.isFolder()
                    ? store.isGeneratedOutputDirectory(local.toPath()) : store.containsGenerated(local.toPath());
        } catch (java.io.IOException error) {
            DialogDisplayer.getDefault().notifyLater(new NotifyDescriptor.Message(
                    text("RenameFailed") + ": " + error.getMessage(), NotifyDescriptor.ERROR_MESSAGE));
            // Refuse the rename when we cannot verify ownership.
            return true;
        }
    }

    /** Finds the asset to rename. The project asset root itself cannot be renamed here. */
    private FileObject renameAssetTarget() {
        FileObject target = folderMenuTarget;
        if (target == null) {
            java.util.List<Entry> selected = grid.getSelectedValuesList();
            if (selected.size() != 1) {
                return null;
            }
            target = selected.get(0).file();
        }
        if (target == null || !target.isValid()
                || manager == null || target.equals(manager.getAssetFolder())) {
            return null;
        }
        return target;
    }

    /** Renames the DataObject and its sidecars without changing the extension. Existing references keep their old paths. */
    private void renameAsset(FileObject folder) {
        if (isGeneratedRenameTarget(folder)) {
            DialogDisplayer.getDefault().notifyLater(new NotifyDescriptor.Message(
                    text("RenameGenerated"), NotifyDescriptor.INFORMATION_MESSAGE));
            return;
        }
        NotifyDescriptor.InputLine input = new NotifyDescriptor.InputLine(
                "Name (without extension)", text("Rename"));
        input.setInputText(folder.getName());
        if (!NotifyDescriptor.OK_OPTION.equals(DialogDisplayer.getDefault().notify(input))) {
            return;
        }
        String name = input.getInputText().trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf((char) 92) >= 0) {
            report(new java.io.IOException("Enter a single asset name."));
            return;
        }
        if (name.equals(folder.getName())) {
            return;
        }
        String oldRelative = FileUtil.getRelativePath(manager.getAssetFolder(), folder);
        boolean currentIsInside = oldRelative != null
                && (directory.equals(oldRelative) || directory.startsWith(oldRelative + "/"));
        String prefix = oldRelative == null ? "" : oldRelative.substring(0, oldRelative.lastIndexOf('/') + 1);
        io.post(() -> {
            try {
                FileObject parent = folder.getParent();
                if (parent == null || !parent.isValid()) {
                    throw new java.io.IOException("The folder has no writable parent.");
                }
                String extension = folder.isFolder() ? "" : folder.getExt();
                String fullName = extension.isEmpty() ? name : name + "." + extension;
                if (parent.getFileObject(fullName) != null) {
                    throw new java.io.IOException("A file or folder with this name already exists.");
                }
                org.openide.loaders.DataObject.find(folder).rename(name);
                SwingUtilities.invokeLater(() -> {
                    folderMenuTarget = null;
                    if (currentIsInside) {
                        directory = prefix + name + directory.substring(oldRelative.length());
                    }
                    scan();
                });
            } catch (Exception error) {
                SwingUtilities.invokeLater(() -> DialogDisplayer.getDefault().notifyLater(
                        new NotifyDescriptor.Message(
                                text("RenameFailed") + ": " + error.getMessage(),
                                NotifyDescriptor.ERROR_MESSAGE)));
            }
        });
    }

    private void installAssetActions() {
        copyAsset = new AbstractAction(text("Copy")) {
            @Override
            public void actionPerformed(ActionEvent event) {
                try {
                    java.util.List<java.awt.datatransfer.Transferable> items = new ArrayList<>();
                    for (Entry entry : selectedFileEntries()) {
                        DataObject object = DataObject.find(entry.file());
                        if (!object.isCopyAllowed()) {
                            throw new java.io.IOException("This generated resource cannot be copied: " + entry.file().getNameExt());
                        }
                        items.add(object.getNodeDelegate().clipboardCopy());
                    }
                    if (!items.isEmpty()) {
                        java.awt.datatransfer.Transferable copied = items.size() == 1 ? items.get(0)
                                : new org.openide.util.datatransfer.ExTransferable.Multi(
                                        items.toArray(java.awt.datatransfer.Transferable[]::new));
                        assetClipboard().setContents(copied, null);
                    }
                } catch (Exception error) {
                    report(error);
                }
            }
        };
        pasteAsset = org.openide.explorer.ExplorerUtils.actionPaste(explorer);
        // Use the thumbnail selection since the Explorer selection can update later.
        deleteAsset = new AbstractAction(text("Delete")) {
            @Override
            public boolean isEnabled() {
                return !selectedFileEntries().isEmpty() && selectedFileEntries().stream()
                        .allMatch(entry -> entry.file().isValid() && entry.file().canWrite());
            }

            @Override
            public void actionPerformed(ActionEvent event) {
                java.util.List<Entry> selected = java.util.List.copyOf(selectedFileEntries());
                if (selected.isEmpty() || !isEnabled()) {
                    return;
                }
                String names = selected.stream().map(Entry::path)
                        .collect(java.util.stream.Collectors.joining("\n"));
                NotifyDescriptor confirmation = new NotifyDescriptor.Confirmation(
                        text("DeleteConfirm") + "\n\n" + names, text("Delete"),
                        NotifyDescriptor.YES_NO_OPTION, NotifyDescriptor.WARNING_MESSAGE);
                if (!NotifyDescriptor.YES_OPTION.equals(DialogDisplayer.getDefault().notify(confirmation))) {
                    return;
                }
                io.post(() -> {
                    try {
                        for (Entry entry : selected) {
                            if (entry.file().isValid()) {
                                var disk = FileUtil.toFile(entry.file());
                                var store = disk == null ? null
                                        : com.jme3.gde.texturetools.GeneratedTextures.containing(disk.toPath());
                                if (entry.file().isFolder() && store != null && store.affects(disk.toPath())) {
                                    // Validate the whole managed tree before DataFolder removes children.
                                    store.delete(disk.toPath());
                                    FileUtil.refreshFor(disk.getParentFile());
                                } else {
                                    DataObject.find(entry.file()).delete();
                                }
                            }
                        }
                    } catch (Exception error) {
                        DialogDisplayer.getDefault().notifyLater(new NotifyDescriptor.Message(
                                text("DeleteFailed") + ": " + error.getMessage(), NotifyDescriptor.ERROR_MESSAGE));
                    } finally {
                        SwingUtilities.invokeLater(() -> scan());
                    }
                });
            }
        };
        getActionMap().put("copy-to-clipboard", copyAsset);
        getActionMap().put("paste-from-clipboard", pasteAsset);
        getActionMap().put("delete", deleteAsset);
        bindAsset(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK, "copy", copyAsset);
        bindAsset(KeyEvent.VK_DELETE, 0, "delete", deleteAsset);
        Action paste = new AbstractAction(text("Paste")) {
            @Override public void actionPerformed(ActionEvent event) {
                try {
                    FileObject folder = currentFolder();
                    if (folder == null || !folder.canWrite()) {
                        throw new java.io.IOException(text("PasteNoFolder"));
                    }
                    var clipboard = assetClipboard();
                    var contents = clipboard.getContents(null);
                    if (contents == null) {
                        return;
                    }
                    if (com.jme3.gde.core.assets.ExternalAssetImport.submit(manager, folder, contents)) {
                        return;
                    }
                    // Ask the destination to accept the paste. NetBeans updates the global
                    // action asynchronously and may still have another node selected.
                    var destination = org.openide.loaders.DataFolder.findFolder(folder).getNodeDelegate();
                    var choices = destination.getPasteTypes(contents);
                    if (choices.length == 0) {
                        throw new java.io.IOException(text("PasteUnsupported"));
                    }
                    var operation = choices[0];
                    io.post(() -> {
                        try {
                            var replacement = operation.paste();
                            SwingUtilities.invokeLater(() -> {
                                if (replacement != null) {
                                    clipboard.setContents(replacement, null);
                                }
                                scan();
                            });
                        } catch (Exception error) {
                            SwingUtilities.invokeLater(() -> report(error));
                        }
                    });
                } catch (Exception error) {
                    report(error);
                }
            }
        };
        getActionMap().put("pasteIntoFolder", paste);
        getActionMap().put("paste-from-clipboard", paste);
        bindAsset(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK, "paste", paste);
        grid.setDragEnabled(true);
        TransferHandler imports = new TransferHandler() {
            @Override public boolean canImport(TransferSupport support) {
                return manager != null && support.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
            }
            @Override public boolean importData(TransferSupport support) {
                if (!canImport(support)) {
                    return false;
                }
                try {
                    FileObject target = currentFolder();
                    if (support.isDrop() && support.getComponent() == grid) {
                        int index = grid.locationToIndex(support.getDropLocation().getDropPoint());
                        if (index >= 0 && grid.getCellBounds(index, index).contains(support.getDropLocation().getDropPoint())
                                && entries.get(index).file().isFolder()) {
                            target = entries.get(index).file();
                        }
                    } else if (support.isDrop() && support.getComponent() == folders) {
                        var location = (JTree.DropLocation) support.getDropLocation();
                        if (location.getPath() != null) {
                            var node = (DefaultMutableTreeNode) location.getPath().getLastPathComponent();
                            var folder = (Folder) node.getUserObject();
                            target = folder.path().isEmpty() ? manager.getAssetFolder()
                                    : manager.getAssetFolder().getFileObject(folder.path());
                        }
                    }
                    return com.jme3.gde.core.assets.ExternalAssetImport.submit(manager, target, support.getTransferable());
                } catch (Exception error) {
                    report(error);
                    return false;
                }
            }

            @Override public int getSourceActions(JComponent component) { return COPY; }
            @Override protected java.awt.datatransfer.Transferable createTransferable(JComponent component) {
                Entry entry = grid.getSelectedValue();
                if (entry == null || (entry.kind() != AssetKind.MODEL && entry.kind() != AssetKind.MATERIAL && entry.kind() != AssetKind.TEXTURE)) {
                    return null;
                }
                var holder = new com.jme3.gde.core.dnd.AssetNameHolder() {
                    public String getAssetName() { return entry.path(); }
                    public FileObject getAssetFile() { return entry.file(); }
                };
                return new com.jme3.gde.core.dnd.AssetTransferable<>(holder,
                        entry.kind() == AssetKind.MODEL ? com.jme3.gde.core.dnd.SpatialDataFlavor.instance
                                : entry.kind() == AssetKind.TEXTURE ? com.jme3.gde.core.dnd.TextureDataFlavor.instance
                                : com.jme3.gde.core.dnd.MaterialDataFlavor.instance);
            }
        };
        grid.setTransferHandler(imports);
        folders.setTransferHandler(imports);
        folders.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK), "importPaste");
        folders.getActionMap().put("importPaste", paste);
    }

    private java.awt.datatransfer.Clipboard assetClipboard() {
        var clipboard = org.openide.util.Lookup.getDefault().lookup(java.awt.datatransfer.Clipboard.class);
        return clipboard != null ? clipboard : Toolkit.getDefaultToolkit().getSystemClipboard();
    }

    private void bindAsset(int key, int modifiers, String name, Action action) {
        grid.getInputMap().put(KeyStroke.getKeyStroke(key, modifiers), name);
        grid.getActionMap().put(name, action);
    }

    private void popup(MouseEvent event) {
        if (!event.isPopupTrigger() || manager == null) {
            return;
        }
        int index = grid.locationToIndex(event.getPoint());
        if (index < 0 || !grid.getCellBounds(index, index).contains(event.getPoint())) {
            grid.clearSelection();
        } else if (!grid.isSelectedIndex(index)) {
            grid.setSelectedIndex(index);
        }
        folderMenuTarget = null;
        syncAssetSelection();
        showAssetPopup(grid, event.getX(), event.getY());
    }

    private void showAssetPopup(Component invoker, int x, int y) {
        FileObject folder = currentFolder();
        if (folder == null) {
            return;
        }
        var dataFolder = org.openide.loaders.DataFolder.findFolder(folder);
        var project = org.netbeans.api.project.FileOwnerQuery.getOwner(folder);
        java.util.List<Object> context = new ArrayList<>();
        context.add(folder);
        context.add(dataFolder);
        context.add(dataFolder.getNodeDelegate());
        if (project != null) {
            context.add(project);
        }
        Action create = org.netbeans.spi.project.ui.support.CommonProjectActions.newFileAction();
        if (create instanceof org.openide.util.ContextAwareAction aware) {
            create = aware.createContextAwareInstance(org.openide.util.lookup.Lookups.fixed(context.toArray()));
        }
        JPopupMenu menu = new JPopupMenu();
        JMenuItem createItem = menu.add(create);
        org.openide.awt.Mnemonics.setLocalizedText(createItem, createItem.getText());
        JMenuItem newFolder = new JMenuItem("New Folder");
        newFolder.setEnabled(folder.canWrite());
        newFolder.addActionListener(event -> createFolder(folder));
        menu.add(newFolder);
        FileObject renameTarget = renameAssetTarget();
        if (renameTarget != null) {
            boolean generated = isGeneratedRenameTarget(renameTarget);
            JMenuItem rename = new JMenuItem(text("Rename"));
            rename.setEnabled(!generated && renameTarget.canWrite());
            if (generated) {
                rename.setToolTipText(text("RenameGeneratedTip"));
            }
            rename.addActionListener(event -> renameAsset(renameTarget));
            menu.add(rename);
        }
        menu.addSeparator();
        JMenuItem open = new JMenuItem(text("Open"));
        open.setEnabled(grid.getSelectedValue() != null);
        open.addActionListener(e -> open(grid.getSelectedValue()));
        menu.add(open);
        Entry selected = grid.getSelectedValue();
        FileObject inspected = folderMenuTarget != null ? folderMenuTarget
                : selected != null ? selected.file() : folder;
        JMenuItem properties = new JMenuItem(text("Properties"));
        properties.addActionListener(event -> showProperties(inspected));
        menu.add(properties);
        if (selected != null && selected.file().getExt().equalsIgnoreCase("j3o")) {
            try {
                if (DataObject.find(selected.file()) instanceof com.jme3.gde.core.assets.BinaryModelDataObject binary) {
                    menu.add(com.jme3.gde.core.assets.AnimationEditorSupport.fileAction(binary, false));
                    JMenuItem scene = new JMenuItem("Open in Scene Editor");
                    scene.addActionListener(new com.jme3.gde.scenecomposer.OpenSceneComposer(binary));
                    menu.add(scene);
                }
            } catch (java.io.IOException exception) {
                report(exception);
            }
        }
        menu.add(new JMenuItem(copyAsset)).setText(text("Copy"));
        if (selected != null) {
            var convertTexture = com.jme3.gde.core.assets.ExternalAssetImport.textureAction(manager, selected.file());
            if (convertTexture != null) {
                menu.add(convertTexture);
            }
            var convertAudio = com.jme3.gde.core.assets.ExternalAssetImport.audioAction(manager, selected.file());
            if (convertAudio != null) {
                menu.add(convertAudio);
            }
            var repairAudio = com.jme3.gde.core.assets.ExternalAssetImport.audioRepairAction(manager, selected.file());
            if (repairAudio != null) {
                menu.add(repairAudio);
            }
        }
        menu.add(new JMenuItem(getActionMap().get("pasteIntoFolder")));
        menu.add(new JMenuItem(deleteAsset)).setText(text("Delete"));
        java.util.List<SpatialAssetDataObject> models = new ArrayList<>();
        for (Entry entry : grid.getSelectedValuesList()) {
            try {
                if (DataObject.find(entry.file()) instanceof SpatialAssetDataObject model
                        && !entry.file().getExt().equalsIgnoreCase("j3o")) {
                    models.add(model);
                }
            } catch (java.io.IOException error) {
                report(error);
            }
        }
        if (!models.isEmpty()) {
            menu.addSeparator();
            JMenuItem convert = new JMenuItem(text("Convert"));
            convert.addActionListener(new com.jme3.gde.core.assets.actions.ConvertModel(models));
            menu.add(convert);
        }
        menu.show(invoker, x, y);
    }

    private void open(Entry entry) {
        if (entry == null || manager == null) { return; }
        var convertAudio = com.jme3.gde.core.assets.ExternalAssetImport.audioAction(manager, entry.file());
        if (convertAudio != null) {
            convertAudio.actionPerformed(new ActionEvent(this, 0, "convertAudio"));
            return;
        }
        if (entry.file().isFolder()) {
            openFolder(entry);
            return;
        }
        if (entry.kind() == AssetKind.TEXTURE && AssetPreviewTextures.usesImageEditor(entry.file())) {
            openImageEditor(entry.file());
            return;
        }
        ProjectAssetManager assets = manager;
        io.post(() -> {
            try {
                DataObject object = DataObject.find(entry.file());
                if (object instanceof com.jme3.gde.core.assets.BinaryModelDataObject binary) {
                    SwingUtilities.invokeLater(() -> binary.getNodeDelegate().getPreferredAction()
                            .actionPerformed(new ActionEvent(binary, 0, "open")));
                    return;
                }
                if (object instanceof SpatialAssetDataObject model) {
                    var spatial = model.loadAsset();
                    SwingUtilities.invokeLater(() -> {
                        if (spatial != null) {
                            SceneComposerTopComponent.findInstance().openScene(spatial, model, assets);
                        }
                    });
                    return;
                }
                SwingUtilities.invokeLater(() -> {
                    try {
                        if (entry.file().getExt().equalsIgnoreCase("j3m")) {
                            var support = object.getLookup().lookup(com.jme3.gde.materials.multiview.MaterialOpenSupport.class);
                            if (support != null) {
                                support.open();
                            } else {
                                // Use the public window API. The concrete editor implements
                                // internal widget listeners not exported to this module.
                                org.openide.windows.TopComponent editor =
                                        new com.jme3.gde.materials.multiview.MaterialEditorTopComponent(object);
                                editor.open();
                                editor.requestActive();
                            }
                        } else if (entry.kind() == AssetKind.TEXTURE) {
                            // Decode mipmaps on the CPU so preview also works without GPU format support.
                            OpenCookie textureOpen = object.getLookup().lookup(OpenCookie.class);
                            if ("image/x-jme-compressed-texture".equals(entry.file().getMIMEType())
                                    && textureOpen != null) {
                                textureOpen.open();
                            } else {
                                openImageEditor(entry.file());
                            }
                        } else if (object instanceof FilterDataObject filters) {
                            com.jme3.gde.core.filters.FilterExplorerTopComponent.findInstance().loadFile(filters);
                        } else if (entry.kind() == AssetKind.AUDIO) {
                            openSound(assets, entry);
                        } else {
                            OpenCookie open = object.getLookup().lookup(OpenCookie.class);
                            EditCookie edit = object.getLookup().lookup(EditCookie.class);
                            if (open != null) { open.open(); }
                            else if (edit != null) { edit.edit(); }
                            else {
                                Action action = object.getNodeDelegate().getPreferredAction();
                                if (action != null) { action.actionPerformed(new ActionEvent(object, 0, "open")); }
                                else { throw new IllegalStateException(text("NoEditor")); }
                            }
                        }
                    } catch (Exception error) { report(error); }
                });
            } catch (Exception error) { SwingUtilities.invokeLater(() -> report(error)); }
        });
    }

    private void openImageEditor(FileObject file) {
        var editor = new ImageEditorTopComponent(file);
        editor.open();
        editor.requestActive();
    }

    private void showProperties(FileObject file) {
        Entry selected = grid.getSelectedValue();
        long previewMemory = selected != null && selected.file().equals(file) ? inspector.memoryBytes() : -1;
        io.post(() -> {
            try {
                long size = assetSize(file);
                long memory = previewMemory;
                if (memory < 0 && !file.isFolder() && AssetPreviewTextures.usesImageEditor(file)) {
                    try {
                        var image = AssetPreviewTextures.read(null, file, file.getNameExt());
                        memory = (long) image.getWidth() * image.getHeight() * 4;
                    } catch (Exception ignored) {
                        // A damaged image still has a name, path and file size.
                    }
                }
                long estimate = memory;
                SwingUtilities.invokeLater(() -> {
                    var details = new JPanel(new java.awt.GridLayout(0, 2, 8, 5));
                    addProperty(details, text("PropertyName"), file.getNameExt());
                    addProperty(details, text("PropertyPath"), FileUtil.getFileDisplayName(file));
                    addProperty(details, text("PropertySize"), AssetInspector.units(size));
                    if (!file.isFolder()) {
                        addProperty(details, text("PreviewMemory"), estimate < 0 ? "—" : AssetInspector.units(estimate));
                    }
                    DialogDisplayer.getDefault().notify(new NotifyDescriptor(details, text("Properties"),
                            NotifyDescriptor.DEFAULT_OPTION, NotifyDescriptor.PLAIN_MESSAGE,
                            new Object[]{NotifyDescriptor.OK_OPTION}, NotifyDescriptor.OK_OPTION));
                });
            } catch (Exception error) {
                SwingUtilities.invokeLater(() -> report(error));
            }
        });
    }

    static long assetSize(FileObject file) throws IOException {
        if (file.isData()) {
            return file.getSize();
        }
        var local = FileUtil.toFile(file);
        if (local != null) {
            long[] size = {0L};
            java.nio.file.Files.walkFileTree(local.toPath(), new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(java.nio.file.Path path,
                        java.nio.file.attribute.BasicFileAttributes attributes) {
                    if (attributes.isRegularFile()) {
                        size[0] += attributes.size();
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
            return size[0];
        }
        long total = 0;
        var children = file.getChildren(true);
        while (children.hasMoreElements()) {
            FileObject child = children.nextElement();
            if (child.isData()) {
                total += child.getSize();
            }
        }
        return total;
    }

    private static void addProperty(JPanel details, String name, String value) {
        var field = new JTextField(value, 32);
        field.setEditable(false);
        field.setCaretPosition(0);
        var label = new JLabel(name);
        label.setLabelFor(field);
        details.add(label);
        details.add(field);
    }

    private void openSound(ProjectAssetManager assets, Entry entry) {
        TopComponent inspector = new TopComponent() {
            private final AssetAudioPlayer player = new AssetAudioPlayer();
            private volatile boolean disposed;
            private volatile long audioRequest;
            {
                setName(entry.file().getNameExt());
                setLayout(new BorderLayout());
                AssetThumbnails.Result result = image(entry);
                JLabel image = new JLabel(new Thumb(result == null ? pendingImage : result.image(), 256));
                add(image, BorderLayout.CENTER);
                JPanel controls = new JPanel();
                JButton play = new JButton(text("Play"));
                JButton stop = new JButton(text("Stop"));
                controls.add(play);
                controls.add(stop);
                add(controls, BorderLayout.SOUTH);
                play.addActionListener(event -> {
                    long ticket = ++audioRequest;
                    io.post(() -> {
                    if (!disposed) {
                        try {
                            var audio = AssetThumbnails.loadAudio(assets, entry.file(), entry.path());
                            SwingUtilities.invokeLater(() -> {
                                if (!disposed && ticket == audioRequest) {
                                    player.play(audio, error -> SwingUtilities.invokeLater(() -> report(error)));
                                }
                            });
                        } catch (Exception error) {
                            SwingUtilities.invokeLater(() -> report(error));
                        }
                    }
                    });
                });
                stop.addActionListener(event -> stopSound());
            }
            private void stopSound() {
                audioRequest++;
                player.stop();
            }
            @Override public void componentClosed() { disposed = true; stopSound(); }
            @Override public int getPersistenceType() { return PERSISTENCE_NEVER; }
        };
        var mode = org.openide.windows.WindowManager.getDefault().findMode("editor");
        if (mode != null) { mode.dockInto(inspector); }
        inspector.open();
        inspector.requestActive();
    }

    private void report(Exception error) {
        DialogDisplayer.getDefault().notifyLater(new NotifyDescriptor.Message(
                text("OpenFailed") + ": " + error.getMessage(), NotifyDescriptor.ERROR_MESSAGE));
    }
}
