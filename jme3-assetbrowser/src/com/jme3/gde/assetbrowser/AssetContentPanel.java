package com.jme3.gde.assetbrowser;

import com.jme3.audio.AudioData;
import com.jme3.audio.AudioNode;
import com.jme3.gde.core.assets.ProjectAssetManager;
import com.jme3.gde.core.assets.SpatialAssetDataObject;
import com.jme3.gde.core.assets.FilterDataObject;
import com.jme3.gde.core.scene.SceneApplication;
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
    private final JLabel preview = new JLabel("", SwingConstants.CENTER);
    private final JLabel details = new JLabel(" ");
    private final JLabel status = new JLabel(" ");
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
                new Component[]{showPreview});
        add(bar, BorderLayout.NORTH);
        grid.setLayoutOrientation(JList.HORIZONTAL_WRAP);
        grid.setVisibleRowCount(-1);
        grid.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        grid.setFixedCellWidth(tileSize + 18);
        grid.setFixedCellHeight(tileSize + 39);
        grid.setCellRenderer(new Cell());
        grid.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
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
                if (event.getClickCount() == 2 && index >= 0
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
        JPanel inspector = new JPanel(new BorderLayout(2, 2));
        inspector.setMinimumSize(new Dimension(0, 0));
        inspector.setPreferredSize(new Dimension(210, 160));
        inspector.add(preview, BorderLayout.CENTER);
        inspector.add(details, BorderLayout.SOUTH);
        JSplitPane content = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, gridScroll, inspector);
        content.setResizeWeight(1);
        // Do not reserve an empty inspector strip. The asset grid fills all
        // remaining width until the user explicitly opens the preview pane.
        inspector.setVisible(false);
        content.setDividerSize(0);
        showPreview.addActionListener(event -> {
            boolean visible = showPreview.isSelected();
            inspector.setVisible(visible);
            content.setDividerSize(visible ? 5 : 0);
            if (visible) {
                content.setDividerLocation(Math.max(0, content.getWidth() - 215));
            }
            content.revalidate();
            content.repaint();
        });
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treeScroll, content);
        split.setResizeWeight(0);
        split.setDividerSize(5);
        split.setDividerLocation(170);
        add(split, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
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
        OpenProjects.getDefault().addPropertyChangeListener(projectListener);
        reloadProjects();
    }

    public void stop() {
        active = false;
        generation++;
        refreshTimer.stop();
        searchTimer.stop();
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
        ProjectItem item = (ProjectItem) projects.getSelectedItem();
        manager = item == null ? null : item.manager();
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
            status.setText(text("NoProject"));
            return;
        }
        status.setText(text("Scanning"));
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
                // Directory timestamps change when sidecars are written or files are
                // enumerated by other tools; only asset files determine validity.
                if (entry.file().isFolder()) {
                    continue;
                }
                stamp = stamp * 31 + entry.path().hashCode();
                stamp = stamp * 31 + entry.file().lastModified().getTime();
                stamp = stamp * 31 + entry.file().getSize();
                // OBJ can depend on MTL/textures, but never on a converted J3O.
                // Saving a scene must not reload every unchanged raw OBJ thumbnail.
                if (!entry.file().getExt().equalsIgnoreCase("j3o")) {
                    objStamp = objStamp * 31 + entry.path().hashCode();
                    objStamp = objStamp * 31 + entry.file().lastModified().getTime();
                    objStamp = objStamp * 31 + entry.file().getSize();
                }
            }
            // Sidecars affect models but are not standalone browser items.
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

    private void filter() {
        Entry selected = grid.getSelectedValue();
        entries.clear();
        String query = search.getText().trim().toLowerCase(Locale.ROOT);
        int type = types.getSelectedIndex() - 1;
        for (Entry entry : all) {
            int slash = entry.path().lastIndexOf('/');
            String parent = slash < 0 ? "" : entry.path().substring(0, slash);
            boolean location = query.isEmpty() ? parent.equals(directory)
                    : directory.isEmpty() || entry.path().startsWith(directory + "/");
            if (location && entry.path().toLowerCase(Locale.ROOT).contains(query)
                    && (entry.file().isFolder() || type < 0 || entry.kind().ordinal() == type)) {
                entries.addElement(entry);
            }
        }
        if (selected != null) { grid.setSelectedValue(selected, false); }
        status.setText("Assets/" + directory + "  —  " + entries.size() + " " + text("Assets"));
        showSelected();
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
        if (entry == null) {
            preview.setIcon(null);
            details.setText(" ");
            return;
        }
        AssetThumbnails.Result result = image(entry);
        preview.setIcon(entry.file().isFolder() ? new FolderThumb(180)
                : new Thumb(result == null ? pendingImage : result.image(), 180));
        details.setText(entry.file().getNameExt());
        String tip = entry.path() + " — " + (entry.file().isFolder() ? text("Folder") : entry.file().getSize() + " bytes");
        if (result != null && !result.error().isEmpty()) { tip += " — " + result.error(); }
        details.setToolTipText(tip);
        preview.setToolTipText(tip);
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

    /** Uses the current IDE theme's directory icon; folders never enter thumbnail rendering. */
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

    /** Creates only one child directory, never an arbitrary relative path. */
    private void createFolder(FileObject parent) {
        NotifyDescriptor.InputLine input = new NotifyDescriptor.InputLine(
                "Folder name", "New Folder");
        if (!NotifyDescriptor.OK_OPTION.equals(DialogDisplayer.getDefault().notify(input))) {
            return;
        }
        String name = input.getInputText().trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf((char) 92) >= 0) {
            report(new java.io.IOException("Enter a single folder name."));
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
        // Thumbnail selection is authoritative; Explorer's async selection may lag.
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
                                DataObject.find(entry.file()).delete();
                            }
                        }
                    } catch (Exception error) {
                        SwingUtilities.invokeLater(() -> report(error));
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
                    // Ask the destination directly. Explorer action enablement is
                    // asynchronous and a selected asset is not the paste target.
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
        grid.setTransferHandler(new TransferHandler() {
            @Override public int getSourceActions(JComponent component) { return COPY; }
            @Override protected java.awt.datatransfer.Transferable createTransferable(JComponent component) {
                Entry entry = grid.getSelectedValue();
                if (entry == null || (entry.kind() != AssetKind.MODEL && entry.kind() != AssetKind.MATERIAL)) {
                    return null;
                }
                var holder = new com.jme3.gde.core.dnd.AssetNameHolder() {
                    public String getAssetName() { return entry.path(); }
                    public FileObject getAssetFile() { return entry.file(); }
                };
                return new com.jme3.gde.core.dnd.AssetTransferable<>(holder,
                        entry.kind() == AssetKind.MODEL ? com.jme3.gde.core.dnd.SpatialDataFlavor.instance
                                : com.jme3.gde.core.dnd.MaterialDataFlavor.instance);
            }
        });
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
        menu.add(create);
        JMenuItem newFolder = new JMenuItem("New Folder");
        newFolder.setEnabled(folder.canWrite());
        newFolder.addActionListener(event -> createFolder(folder));
        menu.add(newFolder);
        menu.addSeparator();
        JMenuItem open = new JMenuItem(text("Open"));
        open.setEnabled(grid.getSelectedValue() != null);
        open.addActionListener(e -> open(grid.getSelectedValue()));
        menu.add(open);
        Entry selected = grid.getSelectedValue();
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
        if (entry.file().isFolder()) {
            openFolder(entry);
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
                            // Compressed textures use CPU mip preview, independent of GPU support.
                            OpenCookie textureOpen = object.getLookup().lookup(OpenCookie.class);
                            if ("image/x-jme-compressed-texture".equals(entry.file().getMIMEType())
                                    && textureOpen != null) {
                                textureOpen.open();
                            } else {
                                new ImageEditorTopComponent(entry.file());
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

    private void openSound(ProjectAssetManager assets, Entry entry) {
        TopComponent inspector = new TopComponent() {
            private AudioNode sound;
            private boolean disposed;
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
                play.addActionListener(event -> SceneApplication.getApplication().enqueue(() -> {
                    if (!disposed) {
                        try {
                            if (sound == null) {
                                sound = new AudioNode(assets, entry.path(), AudioData.DataType.Buffer);
                                sound.setPositional(false);
                            }
                            sound.stop();
                            sound.play();
                        } catch (RuntimeException error) { SwingUtilities.invokeLater(() -> report(error)); }
                    }
                    return null;
                }));
                stop.addActionListener(event -> stopSound());
            }
            private void stopSound() {
                SceneApplication.getApplication().enqueue(() -> {
                    if (sound != null) { sound.stop(); }
                    return null;
                });
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
