package com.jme3.gde.scenecomposer;

import com.jme3.gde.core.assets.LightmapUvGenerator;
import com.jme3.environment.gi.ProbeLightmapControl;
import com.jme3.gde.core.scene.SceneApplication;
import com.jme3.gde.core.scene.SceneRequest;
import com.jme3.gde.core.sceneviewer.SceneViewerTopComponent;
import com.jme3.gde.core.undoredo.AbstractUndoableSceneEdit;
import com.jme3.gde.core.undoredo.SceneUndoRedoManager;
import com.jme3.scene.*;
import com.jme3.scene.control.LodControl;
import com.jme3.scene.plugins.sdf.*;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;
import org.openide.util.Lookup;
import org.openide.windows.TopComponent;

/**
 * Geometry detail/bake inspector. Selection and widgets belong to Swing; capture
 * and commit belong to the render thread. CPU work uses private meshes, while
 * GPU surface capture is enqueued at a frame boundary. Cancelling or changing
 * selection discards results. Mesh/source fingerprints reject stale CPU work.
 * All commits support Undo/Redo and mark the current asset modified; Ctrl+S saves.
 * SDF defaults to 64^3, 5% padding, unsigned distances for open models. Surface
 * capture defaults to 128 pixels/card with a 256 MiB payload cap. Neither operation
 * generates GI. Normals average shared vertices; hard edges require split vertices.
 * This is vertex normal/tangent generation, not high-poly texture normal baking.
 * LOD Preview affects the selected Geometry or all descendants of a Node. Default
 * Original / Auto preserves normal behavior. Higher unavailable levels clamp per
 * mesh, while meshes without LOD retain their base geometry. Selection changes
 * and closing this panel reset preview; preview is never saved to the asset.
 * <pre>
 * 第一步：在场景或树中选中真实模型，Properties 下半部分显示模型操作。
 * 第二步：LOD 页的 Preview level 选择 LOD 0/1/... 预览，Original / Auto 恢复。
 * 第三步：配置比例后点击 Bake，自动给生成多个层级的几何体添加 LodControl。
 * 第四步：等待完成，Ctrl+Z 撤销，Ctrl+S 保存到当前 j3o。
 * 第五步：View 选择 SDF 查看已烘焙距离场表面；修改模型后重新烘焙并 Refresh。
 * 第六步：选中单个 Geometry，打开 Lightmap UV 页，设置目标分辨率、每岛边距和图边。
 * 点击 Regenerate UV2 替换已有 UV2；UV0 保持，旧静态 Lightmap 失效，需要重新烘焙。
 * View UV2 打开独立二维视图；Ctrl+滚轮缩放，滚动条移动查看，未生成时显示提示。
 * UV2、分辨率设置及旧 Lightmap 引用一起支持撤销/重做，Ctrl+S 保存 J3O。
 * 默认 512 像素、每岛 4 纹素、图边 2 纹素。边距预留不等于烘焙后的颜色扩边。
 * 原模型转换和自动补缺失 UV2 的路径不变；只对刚体三角形 Mesh 提供重新展开。
 * </pre>
 */
@TopComponent.Description(preferredID = "ModelDetailTopComponent", persistenceType = TopComponent.PERSISTENCE_NEVER)
@TopComponent.Registration(mode = "properties", openAtStartup = false)
public final class ModelDetailTopComponent extends TopComponent {
    private static String ui(String key) {
        return com.jme3.gde.core.util.EditorMessages.text(key);
    }

    private static ModelDetailTopComponent instance;
    private JPanel embedded;
    private final JPanel bottom = new JPanel(new BorderLayout(6, 6));
    private JPanel contextual;
    private java.awt.CardLayout contexts;
    private JPanel giPanel;
    private Spatial giTarget;

    private void embedInProperties() {
        if (embedded != null) {
            return;
        }
        TopComponent properties = org.openide.windows.WindowManager.getDefault().findTopComponent("properties");
        if (properties == null) {
            return;
        }
        properties.setActivatedNodes(null);
        embedded = new JPanel(new BorderLayout(4, 4));
        java.awt.BorderLayout layout = (java.awt.BorderLayout) getLayout();
        for (java.awt.Component component : getComponents()) {
            Object constraint = layout.getConstraints(component);
            embedded.add(component, constraint);
        }
        contexts = new java.awt.CardLayout();
        contextual = new JPanel(contexts);
        contextual.add(embedded, "model");
        giPanel = new JPanel(new BorderLayout());
        contextual.add(giPanel, "gi");
        contextual.add(new JLabel(ui("NoModelOperations"), SwingConstants.CENTER), "none");
        contexts.show(contextual, "none");
        JPanel original = new JPanel(new BorderLayout());
        for (java.awt.Component component : properties.getComponents()) {
            original.add(component, BorderLayout.CENTER);
        }
        properties.removeAll();
        properties.setLayout(new BorderLayout());
        javax.swing.JSplitPane split = new javax.swing.JSplitPane(javax.swing.JSplitPane.VERTICAL_SPLIT,
                original, contextual);
        split.setResizeWeight(0.5);
        split.setDividerSize(5);
        original.setMinimumSize(new java.awt.Dimension(0, 0));
        contextual.setMinimumSize(new java.awt.Dimension(0, 0));
        split.setDividerLocation(0.5);
        properties.add(split, BorderLayout.CENTER);
        properties.revalidate();
        properties.repaint();
        if (isOpened()) {
            close();
        }
    }

    private void enableModelInputs(boolean enabled) {
        setInputsEnabled(tabs, enabled);
        bake.setEnabled(enabled && editor != null && cancellation == null && tabs.getSelectedComponent() != physics);
        if (enabled) {
            refreshBakeEnabled();
        }
    }

    private boolean isUvPage() {
        return tabs.getSelectedComponent() instanceof JComponent page
                && Integer.valueOf(4).equals(page.getClientProperty("bakeOperation"));
    }

    private void refreshBakeEnabled() {
        bake.setText(isUvPage() ? "Regenerate UV2" : ui("Bake"));
        bake.setEnabled(editor != null && cancellation == null && hasGeometry
                && tabs.getSelectedComponent() != physics && (!isUvPage() || selected instanceof Geometry));
        viewUv.setEnabled(selected instanceof Geometry && hasGeometry);
    }

    private void refreshUvView(boolean activate) {
        long generation = ++uvViewGeneration;
        if (!(selected instanceof Geometry geometry)) {
            return;
        }
        if (!activate && (uvView == null || !uvView.isOpened())) {
            return;
        }
        SceneApplication.getApplication().enqueue(() -> {
            LightmapUvView.Snapshot snapshot = LightmapUvView.capture(geometry);
            SwingUtilities.invokeLater(() -> {
                if (selected == geometry && generation == uvViewGeneration) {
                    if (uvView == null) {
                        uvView = new LightmapUvView();
                    }
                    uvView.showSnapshot(snapshot, activate);
                }
            });
            return null;
        });
    }

    private static void setInputsEnabled(java.awt.Component component, boolean enabled) {
        component.setEnabled(enabled);
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                setInputsEnabled(child, enabled);
            }
        }
    }

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "SDK model bake");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Spatial selected;
    private boolean hasGeometry;
    private volatile AtomicBoolean cancellation;
    private SceneEditorController editor;
    private final JLabel targetLabel = new JLabel(ui("SelectModel"));
    private final JTextArea status = new JTextArea(4, 20);
    private final JProgressBar progress = new JProgressBar(0, 100);
    private final JButton bake = new JButton(ui("Bake"));
    private final JButton cancel = new JButton(ui("Cancel"));
    private final JTabbedPane tabs = new JTabbedPane();
    private final ModelPhysicsPanel physics = new ModelPhysicsPanel();
    private final JSpinner uvResolution = spinner(512, 64, 4096, 64);
    private final JSpinner uvPadding = spinner(4, 0, 64, 1);
    private final JSpinner uvBorder = spinner(2, 0, 64, 1);
    private final JButton viewUv = new JButton("View UV2");
    private LightmapUvView uvView;
    private long uvViewGeneration;
    private final JComboBox<String> lodPreview = new JComboBox<>();
    private boolean updatingLodPreview;
    private final JLabel lodPreviewInfo = new JLabel();
    private final JTextField lod = new JTextField("0.5,0.25,0.125");
    private final JCheckBox normals = new JCheckBox(ui("RebuildNormals"), true);
    private final JCheckBox tangents = new JCheckBox(ui("GenerateTangents"), true);
    private final JSpinner x = spinner(64, 8, 256, 8);
    private final JSpinner y = spinner(64, 8, 256, 8);
    private final JSpinner z = spinner(64, 8, 256, 8);
    private final JSpinner padding = new JSpinner(new SpinnerNumberModel(0.05, 0.001, 1.0, 0.01));
    private final JComboBox<SdfSettings.SignMode> sign = new JComboBox<>(SdfSettings.SignMode.values());
    private final JSpinner resolution = spinner(128, 8, 512, 8);
    private final JSpinner budget = spinner(256, 16, 1024, 16);
    private final JTextField revision = new JTextField("sdk-local");

    public ModelDetailTopComponent() {
        setName(ui("ModelDetail"));
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        add(targetLabel, BorderLayout.NORTH);
        targetLabel.addPropertyChangeListener("text", event -> targetLabel.setToolTipText(targetLabel.getText()));
        lodPreview.addItem(ui("LodPreviewOriginal"));
        lodPreview.setEnabled(false);
        lodPreview.setToolTipText(ui("LodPreviewTip"));
        lodPreview.addActionListener(event -> {
            if (!updatingLodPreview) {
                SceneApplication.getApplication().setPreviewLod(selected, lodPreview.getSelectedIndex() - 1);
            }
        });
        tabs.addTab("LOD", rows(ui("LodPreview"), lodPreview,
                ui("LodAvailability"), lodPreviewInfo, "Retained triangle ratios", lod,
                "Applied to all descendant triangle meshes", new JLabel("e.g. 0.5,0.25,0.125")));
        tabs.addTab(ui("Normals"), rows("Vertex normals", normals, "Tangents", tangents,
                "Hard edges preserve split vertices", new JLabel("UV0 required for tangents")));
        sign.setSelectedItem(SdfSettings.SignMode.UNSIGNED);
        tabs.addTab("SDF", rows("Grid X", x, "Grid Y", y, "Grid Z", z,
                "Padding fraction", padding, "Sign", sign,
                "EVEN_ODD: closed meshes only", new JLabel("UNSIGNED: open surfaces")));
        tabs.addTab(ui("Surface"), rows("Pixels / card", resolution, "Payload budget (MiB)", budget,
                "Material/texture revision", revision,
                "GPU capture; may pause preview", new JLabel("Albedo / normal / material / depth")));
        viewUv.setToolTipText("Inspect only the selected Geometry's lightmap UV2 in a separate view");
        viewUv.addActionListener(event -> refreshUvView(true));
        uvPadding.setToolTipText("Reserved margin on EACH island, in target-resolution texels; not color dilation");
        uvBorder.setToolTipText("Additional reserved border around the entire UV atlas, in texels");
        tabs.addTab("Lightmap UV", rows("Target resolution", uvResolution,
                "Margin / island (texels)", uvPadding, "Atlas border (texels)", uvBorder,
                "UV2 preview", viewUv, "Selected Geometry only", new JLabel("Regeneration invalidates old Lightmaps")));
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        // Reparenting a tab into a scroll pane removes it from JTabbedPane.
        // Snapshot and detach all tabs first, otherwise indices shift mid-loop.
        int tabCount = tabs.getTabCount();
        String[] titles = new String[tabCount];
        java.awt.Component[] contents = new java.awt.Component[tabCount];
        for (int i = 0; i < tabCount; i++) {
            titles[i] = tabs.getTitleAt(i);
            contents[i] = tabs.getComponentAt(i);
        }
        tabs.removeAll();
        for (int i = 0; i < tabCount; i++) {
            JScrollPane scroll = new JScrollPane(contents[i], JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                    JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            scroll.putClientProperty("bakeOperation", i);
            scroll.setBorder(null);
            scroll.setMinimumSize(new java.awt.Dimension(0, 0));
            tabs.addTab(titles[i], scroll);
        }
        tabs.setMinimumSize(new java.awt.Dimension(0, 0));
        tabs.addTab("Physics", physics);
        tabs.addChangeListener(event -> {
            boolean meshOperation = tabs.getSelectedComponent() != physics;
            bottom.setVisible(meshOperation || cancellation != null);
            refreshBakeEnabled();
        });
        add(tabs, BorderLayout.CENTER);
        setMinimumSize(new java.awt.Dimension(100, 80));
        targetLabel.setMinimumSize(new java.awt.Dimension(0, 20));
        bottom.setMinimumSize(new java.awt.Dimension(0, 0));
        JPanel buttons = new JPanel(new GridLayout(1, 2, 6, 0));
        buttons.add(bake);
        buttons.add(cancel);
        bottom.add(buttons, BorderLayout.NORTH);
        bottom.add(progress, BorderLayout.CENTER);
        status.setEditable(false);
        status.setLineWrap(true);
        status.setWrapStyleWord(true);
        bottom.add(new JScrollPane(status), BorderLayout.SOUTH);
        add(bottom, BorderLayout.SOUTH);
        bake.setEnabled(false);
        cancel.setEnabled(false);
        bake.addActionListener(event -> startBake());
        cancel.addActionListener(event -> {
            if (cancellation != null) {
                cancellation.set(true);
                status.setText("Cancelling; previous data remains unchanged.");
            }
        });
    }

    @Override
    protected String preferredID() {
        return "ModelDetailTopComponent";
    }

    @Override
    public org.openide.awt.UndoRedo getUndoRedo() {
        return Lookup.getDefault().lookup(org.openide.awt.UndoRedo.class);
    }

    private static JSpinner spinner(int initial, int min, int max, int step) {
        return new JSpinner(new SpinnerNumberModel(initial, min, max, step));
    }

    private static JPanel rows(Object... pairs) {
        JPanel panel = new com.jme3.gde.core.util.InspectorColumns("model." + pairs[0]);
        panel.setBorder(BorderFactory.createEmptyBorder(8, 4, 8, 4));
        for (int i = 0; i < pairs.length; i += 2) {
            JLabel label = new JLabel(pairs[i].toString());
            label.setToolTipText(label.getText());
            panel.add(label);
            java.awt.Component value = (java.awt.Component) pairs[i + 1];
            if (value instanceof JLabel valueLabel) {
                valueLabel.setToolTipText(valueLabel.getText());
                valueLabel.addPropertyChangeListener("text", event -> valueLabel.setToolTipText(valueLabel.getText()));
            } else if (value instanceof JComboBox<?> combo) {
                combo.addActionListener(event -> combo.setToolTipText(String.valueOf(combo.getSelectedItem())));
            }
            panel.add(value);
        }
        return panel;
    }

    /**
     * Shows descendant mesh bake settings for the selected model. Calls from
     * outside Swing are marshalled to EDT. Null clears selection; a null editor
     * allows inspection only. Baking commits through the supplied scene editor.
     */
    public static void showSelection(Spatial spatial, SceneEditorController controller) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> showSelection(spatial, controller));
            return;
        }
        if (instance == null) {
            TopComponent registered = org.openide.windows.WindowManager.getDefault().findTopComponent("ModelDetailTopComponent");
            instance = registered instanceof ModelDetailTopComponent detail ? detail : new ModelDetailTopComponent();
        }
        ModelDetailTopComponent panel = instance;
        panel.embedInProperties();
        panel.enableModelInputs(false);
        if (panel.contexts != null && panel.selected != spatial) { panel.contexts.show(panel.contextual, "none"); }
        if (panel.selected != spatial && panel.cancellation != null) {
            panel.cancellation.set(true);
        }
        if (panel.selected != spatial) {
            panel.resetLodPreview();
        }
        SceneApplication.getApplication().setSdfPreviewSelection(spatial);
        boolean uvSelectionChanged = panel.selected != spatial;
        panel.uvViewGeneration++;
        panel.selected = spatial;
        if (spatial instanceof Geometry geometry && uvSelectionChanged) {
            SceneApplication.getApplication().enqueue(() -> {
                Integer size = geometry.getUserData(LightmapUvGenerator.RESOLUTION_KEY);
                Integer margin = geometry.getUserData(LightmapUvGenerator.PADDING_KEY);
                Integer border = geometry.getUserData(LightmapUvGenerator.BORDER_KEY);
                SwingUtilities.invokeLater(() -> {
                    if (panel.selected == geometry) {
                        panel.uvResolution.setValue(size == null ? 512 : size);
                        panel.uvPadding.setValue(margin == null ? 4 : margin);
                        panel.uvBorder.setValue(border == null ? 2 : border);
                        panel.refreshUvView(false);
                    }
                });
                return null;
            });
        } else if (!(spatial instanceof Geometry) && panel.uvView != null && panel.uvView.isOpened()) {
            panel.uvView.showSnapshot(new LightmapUvView.Snapshot("No Geometry selected", new float[0],
                    new int[0], 256, "Select a Geometry"), false);
        }
        panel.editor = controller;
        panel.bake.setEnabled(false);
        panel.hasGeometry = false;
        if (spatial == null) {
            panel.targetLabel.setText(ui("SelectModel"));
            return;
        }
        SceneApplication.getApplication().enqueue(() -> {
            var request = SceneApplication.getApplication().getCurrentSceneRequest();
            boolean gi = spatial instanceof com.jme3.environment.gi.GiProbeVolume
                    && request != null && request.getJmeNode() != null && !request.getJmeNode().isReadOnly()
                    && (spatial == request.getRootNode() || (request.getRootNode() instanceof Node root && spatial.hasAncestor(root)));
            int count = ModelBakeSupport.isEditableSceneModel(spatial) ? ModelBakeSupport.geometries(spatial).size() : 0;
            SwingUtilities.invokeLater(() -> {
                if (panel.selected == spatial) {
                    panel.hasGeometry = count > 0;
                    panel.targetLabel.setText(spatial.getName() + " — " + count + " meshes");
                    panel.bake.setEnabled(count > 0 && panel.editor != null && panel.cancellation == null && panel.tabs.getSelectedComponent() != panel.physics);
                    panel.enableModelInputs(count > 0);
                    panel.physics.showSelection(count > 0 ? spatial : null, controller);
                    if (panel.contexts != null) {
                        if (gi) {
                            if (panel.giTarget != spatial) {
                                panel.giPanel.removeAll();
                                JPanel giContent = new com.jme3.gde.core.sceneexplorer.nodes.actions.impl.BakeGiProbeVolumeAction()
                                        .createPanel((com.jme3.environment.gi.GiProbeVolume) spatial, request.getDataObject());
                                JScrollPane giScroll = new JScrollPane(giContent, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                                        JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
                                giScroll.setBorder(null);
                                giScroll.setMinimumSize(new java.awt.Dimension(0, 0));
                                panel.giPanel.add(giScroll, BorderLayout.CENTER);
                                panel.giTarget = spatial;
                            }
                            panel.contexts.show(panel.contextual, "gi");
                        } else {
                            panel.giTarget = null;
                            panel.giPanel.removeAll();
                            panel.contexts.show(panel.contextual, count > 0 ? "model" : "none");
                        }
                    }
                    if (count > 0) {
                        panel.refreshLodPreviewChoices();
                    }
                }
            });
            return null;
        });
    }

    private int lodPreviewGeneration;

    private void resetLodPreview() {
        lodPreviewGeneration++;
        updatingLodPreview = true;
        lodPreview.setSelectedIndex(0);
        lodPreview.setEnabled(false);
        lodPreviewInfo.setText("");
        updatingLodPreview = false;
        SceneApplication.getApplication().setPreviewLod(null, -1);
    }

    private void refreshLodPreviewChoices() {
        int generation = ++lodPreviewGeneration;
        Spatial target = selected;
        if (target == null) {
            return;
        }
        SceneApplication.getApplication().enqueue(() -> {
            List<Geometry> geometries = ModelBakeSupport.geometries(target);
            int maxLevels = 1;
            int withLod = 0;
            for (Geometry geometry : geometries) {
                int count = geometry.getMesh().getNumLodLevels();
                maxLevels = Math.max(maxLevels, count);
                if (count > 0) {
                    withLod++;
                }
            }
            final long[] triangleCounts = new long[maxLevels];
            for (Geometry geometry : geometries) {
                com.jme3.scene.Mesh mesh = geometry.getMesh();
                for (int level = 0; level < maxLevels; level++) {
                    triangleCounts[level] += mesh.getNumLodLevels() == 0 ? mesh.getTriangleCount()
                            : mesh.getTriangleCount(Math.min(level, mesh.getNumLodLevels() - 1));
                }
            }
            final int count = maxLevels;
            final int lodMeshes = withLod;
            SwingUtilities.invokeLater(() -> {
                if (selected != target || generation != lodPreviewGeneration) {
                    return;
                }
                int previous = lodPreview.getSelectedIndex();
                updatingLodPreview = true;
                lodPreview.removeAllItems();
                lodPreview.addItem(ui("LodPreviewOriginal"));
                for (int i = 0; i < count; i++) {
                    lodPreview.addItem(java.text.MessageFormat.format(ui("LodPreviewTriangles"),
                            i, triangleCounts[i]));
                }
                lodPreview.setSelectedIndex(Math.min(Math.max(0, previous), count));
                lodPreview.setEnabled(!geometries.isEmpty());
                lodPreviewInfo.setText(java.text.MessageFormat.format(ui("LodAvailabilityCount"),
                        lodMeshes, geometries.size()));
                updatingLodPreview = false;
                SceneApplication.getApplication().setPreviewLod(selected, lodPreview.getSelectedIndex() - 1);
            });
            return null;
        });
    }

    @Override
    public void componentClosed() {
        resetLodPreview();
        super.componentClosed();
    }

    private void startBake() {
        Spatial target = selected;
        SceneEditorController currentEditor = editor;
        if (target == null || currentEditor == null || cancellation != null || !hasGeometry) {
            return;
        }
        // Find the page by identity because users can reorder the tabs.
        Object pageOperation = ((JComponent) tabs.getSelectedComponent()).getClientProperty("bakeOperation");
        if (!(pageOperation instanceof Integer)) {
            status.setText("No bake operation selected.");
            return;
        }
        final int operation = (Integer) pageOperation;
        final float[] ratios;
        try {
            String[] values = operation == 0 ? lod.getText().split(",") : new String[0];
            ratios = new float[values.length];
            for (int i = 0; i < values.length; i++) {
                ratios[i] = Float.parseFloat(values[i].trim());
            }
        } catch (NumberFormatException error) {
            status.setText("Invalid LOD ratios: e.g. 0.5,0.25,0.125");
            return;
        }
        final LightmapUvGenerator.Settings uvSettings;
        try {
            uvSettings = operation == 4 ? new LightmapUvGenerator.Settings((int) uvResolution.getValue(),
                    (int) uvPadding.getValue(), (int) uvBorder.getValue()) : null;
            if (operation == 4 && !(target instanceof Geometry)) {
                throw new IllegalArgumentException("Select one Geometry to regenerate UV2.");
            }
        } catch (IllegalArgumentException error) {
            status.setText(error.getMessage());
            return;
        }
        SdfSettings settings = new SdfSettings((int) x.getValue(), (int) y.getValue(), (int) z.getValue(),
                ((Number) padding.getValue()).floatValue(), (SdfSettings.SignMode) sign.getSelectedItem());
        int edge = (int) resolution.getValue();
        long bytes = (long) (int) budget.getValue() * 1024 * 1024;
        String revisionText = revision.getText();
        boolean rebuildNormals = normals.isSelected();
        boolean rebuildTangents = tangents.isSelected();
        AtomicBoolean token = new AtomicBoolean();
        cancellation = token;
        bake.setEnabled(false);
        cancel.setEnabled(true);
        progress.setValue(0);
        status.setText("Baking private copy; Cancel or changing selection discards the result.");
        SceneApplication app = SceneApplication.getApplication();
        app.enqueue(() -> {
            SceneRequest request = app.getCurrentSceneRequest();
            try {
                if (!ModelBakeSupport.isEditableSceneModel(target)) {
                    throw new IllegalStateException("Select an editable model in the current scene.");
                }
                if (operation == 3) {
                    SurfaceBakeData result = SurfaceAttributeBaker.bake(target, revisionText,
                            app.getRenderManager(), app.getViewPort(), edge, bytes, fraction -> updateProgress(token, fraction));
                    checkCurrent(app, request, target, token);
                    applyData(target, SurfaceBakeData.USER_DATA_KEY, result, currentEditor);
                    finish(token, "Surface attributes ready. Ctrl+S to save; Ctrl+Z to undo.");
                } else {
                    target.updateGeometricState();
                    byte[] stamp = ModelBakeSupport.fingerprint(target);
                    Spatial copy = ModelBakeSupport.copy(target);
                    List<Geometry> originalGeometries = ModelBakeSupport.geometries(target);
                    WORKER.submit(() -> {
                        try {
                            SdfVolume volume = null;
                            if (operation == 0) {
                                ModelBakeSupport.lod(copy, ratios);
                            } else if (operation == 1) {
                                ModelBakeSupport.normalsAndTangents(copy, rebuildNormals, rebuildTangents);
                            } else if (operation == 4) {
                                Geometry geometry = (Geometry) copy;
                                if (token.get()) {
                                    throw new CancellationException("UV regeneration cancelled");
                                }
                                geometry.setMesh(LightmapUvGenerator.regenerate(geometry.getMesh(), uvSettings));
                                updateProgress(token, 1f);
                            } else {
                                volume = SdfGenerator.generate(copy, settings, fraction -> updateProgress(token, fraction));
                            }
                            SdfVolume result = volume;
                            app.enqueue(() -> {
                                try {
                                    checkCurrent(app, request, target, token);
                                    target.updateGeometricState();
                                    if (!Arrays.equals(stamp, ModelBakeSupport.fingerprint(target))) {
                                        throw new IllegalStateException("Model changed during bake. Result discarded; retry.");
                                    }
                                    if (operation == 2) {
                                        applyData(target, SdfVolume.USER_DATA_KEY, result, currentEditor);
                                    } else if (operation == 4) {
                                        applyLightmapUv((Geometry) target, ((Geometry) copy).getMesh(), uvSettings, currentEditor);
                                    } else {
                                        applyMeshes(originalGeometries, ModelBakeSupport.geometries(copy), operation == 0, currentEditor);
                                    }
                                    finish(token, operation == 4
                                            ? "UV2 regenerated. Old Lightmap invalidated; rebake GI. Ctrl+Z to undo; Ctrl+S to save."
                                            : "Bake complete. Ctrl+S to save; Ctrl+Z to undo. Refresh diagnostic views after changes.");
                                } catch (Exception error) {
                                    finish(token, error.toString());
                                }
                                return null;
                            });
                        } catch (Exception error) {
                            finish(token, error.toString());
                        }
                    });
                }
            } catch (Exception error) {
                finish(token, error.toString());
            }
            return null;
        });
    }

    private boolean updateProgress(AtomicBoolean token, float fraction) {
        SwingUtilities.invokeLater(() -> progress.setValue(Math.round(fraction * 100)));
        return !token.get();
    }

    private void checkCurrent(SceneApplication app, SceneRequest request, Spatial target, AtomicBoolean token) {
        boolean attached = false;
        for (Spatial ancestor = target; ancestor != null; ancestor = ancestor.getParent()) {
            if (request != null && ancestor == request.getRootNode()) {
                attached = true;
                break;
            }
        }
        if (!attached || !ModelBakeSupport.isEditableSceneModel(target) || token.get() || selected != target || app.getCurrentSceneRequest() != request) {
            throw new CancellationException("Bake cancelled / selection changed; previous data preserved.");
        }
    }

    private void finish(AtomicBoolean token, String message) {
        SwingUtilities.invokeLater(() -> {
            if (cancellation == token) {
                cancellation = null;
                cancel.setEnabled(false);
                refreshBakeEnabled();
                status.setText(message);
                refreshLodPreviewChoices();
                refreshUvView(false);
            }
        });
    }

    private void applyLightmapUv(Geometry geometry, Mesh result, LightmapUvGenerator.Settings settings,
            SceneEditorController currentEditor) {
        Mesh previous = geometry.getMesh();
        int previousLod = geometry.getLodLevel();
        ProbeLightmapControl lightmap = geometry.getControl(ProbeLightmapControl.class);
        Object oldSize = geometry.getUserData(LightmapUvGenerator.RESOLUTION_KEY);
        Object oldPadding = geometry.getUserData(LightmapUvGenerator.PADDING_KEY);
        Object oldBorder = geometry.getUserData(LightmapUvGenerator.BORDER_KEY);
        Runnable refresh = () -> SwingUtilities.invokeLater(() -> refreshUvView(false));
        Runnable undo = () -> {
            geometry.setMesh(previous);
            if (previous.getNumLodLevels() > 0) {
                geometry.setLodLevel(previousLod);
            }
            geometry.setUserData(LightmapUvGenerator.RESOLUTION_KEY, oldSize);
            geometry.setUserData(LightmapUvGenerator.PADDING_KEY, oldPadding);
            geometry.setUserData(LightmapUvGenerator.BORDER_KEY, oldBorder);
            if (lightmap != null) {
                geometry.addControl(lightmap);
            }
            geometry.updateModelBound();
            refresh.run();
        };
        Runnable redo = () -> {
            geometry.removeControl(ProbeLightmapControl.class);
            geometry.setResidentProbeLightmap(null);
            geometry.setMesh(result);
            if (result.getNumLodLevels() > 0) {
                geometry.setLodLevel(0);
            }
            geometry.setUserData(LightmapUvGenerator.RESOLUTION_KEY, settings.resolution());
            geometry.setUserData(LightmapUvGenerator.PADDING_KEY, settings.padding());
            geometry.setUserData(LightmapUvGenerator.BORDER_KEY, settings.border());
            geometry.updateModelBound();
            refresh.run();
        };
        commit(undo, redo, currentEditor);
    }

    private void applyData(Spatial target, String key, Object result, SceneEditorController currentEditor) {
        Object previous = target.getUserData(key);
        Object oldBundle = target.getUserData(SdfBakeData.USER_DATA_KEY);
        Runnable undo = () -> {
            target.setUserData(key, previous);
            if (SdfVolume.USER_DATA_KEY.equals(key)) {
                target.setUserData(SdfBakeData.USER_DATA_KEY, oldBundle);
            }
        };
        Runnable redo = () -> {
            target.setUserData(key, result);
            if (SdfVolume.USER_DATA_KEY.equals(key)) {
                target.setUserData(SdfBakeData.USER_DATA_KEY, null);
            }
        };
        commit(undo, redo, currentEditor);
    }

    private void applyMeshes(List<Geometry> originals, List<Geometry> copies, boolean lod, SceneEditorController currentEditor) {
        List<Mesh> before = originals.stream().map(Geometry::getMesh).toList();
        List<Mesh> after = copies.stream().map(Geometry::getMesh).toList();
        Map<Geometry, LodControl> added = new IdentityHashMap<>();
        if (lod) {
            for (int i = 0; i < originals.size(); i++) {
                Geometry geometry = originals.get(i);
                if (after.get(i).getNumLodLevels() > 1 && geometry.getControl(LodControl.class) == null) {
                    added.put(geometry, new LodControl());
                }
            }
        }
        Runnable undo = () -> {
            added.forEach(Geometry::removeControl);
            replaceMeshes(originals, before);
        };
        Runnable redo = () -> {
            replaceMeshes(originals, after);
            added.forEach(Geometry::addControl);
        };
        commit(undo, redo, currentEditor);
    }

    private static void replaceMeshes(List<Geometry> geometries, List<Mesh> meshes) {
        for (int i = 0; i < geometries.size(); i++) {
            geometries.get(i).setMesh(meshes.get(i));
            if (meshes.get(i).getNumLodLevels() > 0) {
                geometries.get(i).setLodLevel(0);
            }
            geometries.get(i).updateModelBound();
        }
    }

    private void commit(Runnable undo, Runnable redo, SceneEditorController currentEditor) {
        redo.run();
        currentEditor.setNeedsSave(true);
        Lookup.getDefault().lookup(SceneUndoRedoManager.class).addEdit(this, new AbstractUndoableSceneEdit() {
            @Override
            public void sceneUndo() {
                undo.run();
                currentEditor.setNeedsSave(true);
            }
            @Override
            public void sceneRedo() {
                redo.run();
                currentEditor.setNeedsSave(true);
            }
        });
    }
}
