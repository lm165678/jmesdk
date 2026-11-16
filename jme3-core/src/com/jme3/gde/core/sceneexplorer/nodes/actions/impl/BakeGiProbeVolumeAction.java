package com.jme3.gde.core.sceneexplorer.nodes.actions.impl;

import com.jme3.app.Application;
import com.jme3.app.state.BaseAppState;
import com.jme3.environment.gi.*;
import com.jme3.gde.core.scene.SceneApplication;
import com.jme3.gde.core.scene.SceneRequest;
import com.jme3.gde.core.sceneexplorer.nodes.*;
import com.jme3.gde.core.sceneexplorer.nodes.actions.ToolAction;
import com.jme3.scene.Spatial;
import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;
import org.openide.loaders.DataObject;
import org.openide.util.lookup.ServiceProvider;

/**
 * Nonmodal frame-budgeted bake panel. Captures a scene snapshot; scene edits made
 * after Start require another bake. Cancellation preserves the previous volume.
 * Capture-owned shadows default to enabled and respect Light.castShadow and
 * geometry ShadowMode. Saved assets can be bound using the
 * engine's GiProbeSet/Streamer. SDK preview applies the completed result during
 * drawing only, restoring original material bindings before any save. The UI
 * reports completion only after the external file and live reference commit;
 * the user must then save the J3O. During export the preview uses embedded data.
 *
 * <pre>
 * 第一步：Add Light / GI Light Probe Volume，然后拖动节点或使用移动工具定位。
 * 第二步：在属性面板设置 Coverage；烘焙面板的 X/Y/Z counts 设置密度。
 * 数量修改立即同步到 Properties 的 Probe counts；改变数量会使旧数据失效。
 * Coverage 和缩放变化也同步更新世界间距；需点击 Bake 重新生成光照数据。
 * 取消烘焙不会回退已应用的新网格，需重新 Bake；相同数量取消保留旧数据。
 * 第三步：Tools / Bake GI volume，设置质量、帧预算并点击 Start。
 * 第四步：Surface probe layout 默认关闭；开启时按表面自由布点，使用全精度数据，
 * 不创建规则加密块。Counts 此时表示分桶尺寸和总槽位预算；橙色点表示实际表面探针。
 * 保存 J3O 及 GI 外部文件后自动恢复；关闭开关并重新烘焙恢复规则布局。
 * 规则模式可选择 Adaptive local refinement（默认关闭），按几何分布增加局部加密块。
 * 自适应始终执行分类与安全重定位；结束后显示表面采样覆盖数，未覆盖区域保留粗网格。
 * 默认最多 4 块；每块占用现有流送槽位，整个活动集合仍最多 16 个 Volume。
 * 第五步：可随时 Cancel；全部完成才发布新数据，保存场景后运行时自动流送。
 * 关闭自适应并重新烘焙可恢复普通网格；仅移除自动生成的加密子节点。
 * </pre>
 */
@ServiceProvider(service = ToolAction.class)
public final class BakeGiProbeVolumeAction implements ToolAction {
    @Override
    public Class<?> getNodeClass() {
        return JmeGiProbeVolume.class;
    }

    @Override
    public Action getAction(AbstractSceneExplorerNode node, DataObject dataObject) {
        return new AbstractAction("Bake GI volume...") {
            @Override
            public void actionPerformed(ActionEvent event) {
                GiProbeVolume volume = node.getLookup().lookup(GiProbeVolume.class);
                if (volume != null && !node.isReadOnly()) {
                    show(volume, dataObject);
                }
            }
        };
    }

    /** Opens bake settings on the EDT; the caller must supply an editable scene volume. */
    public void show(GiProbeVolume volume, DataObject file) {
        JDialog dialog = new JDialog((java.awt.Frame) null, "Bake GI volume", false);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.add(createPanel(volume, file));
        dialog.pack();
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);
    }

    /** Embeddable EDT inspector; hiding it cancels in-flight work without replacing old data. */
    public JPanel createPanel(GiProbeVolume volume, DataObject file) {
        // Diagnostic text must not become the inspector's preferred width.
        // Parent scroll panes can otherwise expand to a multi-kilobyte error.
        JPanel dialog = new JPanel(new BorderLayout(4, 4)) {
            @Override
            public java.awt.Dimension getPreferredSize() {
                return new java.awt.Dimension(360, super.getPreferredSize().height);
            }

            @Override
            public java.awt.Dimension getMinimumSize() {
                return new java.awt.Dimension(0, 0);
            }
        };
        JPanel fields = new com.jme3.gde.core.util.InspectorColumns("gi.bake");
        JCheckBox surfaceLayout = new JCheckBox("Surface probe layout");
        JSpinner countX = spinner(fields, ui("GiCountX"), 5, 2, 128);
        JSpinner countY = spinner(fields, ui("GiCountY"), 5, 2, 128);
        JSpinner countZ = spinner(fields, ui("GiCountZ"), 5, 2, 128);
        JLabel gridInfo = new JLabel(" ");
        fields.add(new JLabel(ui("GiGridInfo")));
        fields.add(gridInfo);
        com.jme3.math.Vector3f[] worldCoverage = {new com.jme3.math.Vector3f(16, 16, 16)};
        boolean[] syncingCounts = {true};
        int[] countRevision = {0};
        Runnable refreshGrid = () -> {
            int nx = (Integer) countX.getValue();
            int ny = (Integer) countY.getValue();
            int nz = (Integer) countZ.getValue();
            var extent = worldCoverage[0];
            gridInfo.setText(String.format(java.util.Locale.ROOT, surfaceLayout.isSelected()
                    ? "%d slots | bins %.2f / %.2f / %.2f" : "%d probes | %.2f / %.2f / %.2f",
                    (long) nx * ny * nz, extent.x / (nx - 1), extent.y / (ny - 1), extent.z / (nz - 1)));
            gridInfo.setToolTipText(ui("GiGridInfoTip") + " " + gridInfo.getText());
        };
        for (JSpinner count : new JSpinner[]{countX, countY, countZ}) {
            count.setToolTipText(ui("GiCountTip"));
            count.setEnabled(false);
            count.addChangeListener(event -> {
                refreshGrid.run();
                if (syncingCounts[0]) {
                    return;
                }
                countRevision[0]++;
                var requested = new com.jme3.math.Vector3f((Integer) countX.getValue(),
                        (Integer) countY.getValue(), (Integer) countZ.getValue());
                SceneApplication app = SceneApplication.getApplication();
                app.enqueue(() -> {
                    if (!requested.equals(volume.getProbeCounts())) {
                        volume.setProbeCounts(requested);
                        SceneRequest request = app.getCurrentSceneRequest();
                        SwingUtilities.invokeLater(() -> {
                            file.setModified(true);
                            if (request != null) {
                                var node = request.getJmeNode().getChild(volume);
                                if (node != null) {
                                    node.refreshProperties();
                                }
                            }
                        });
                    }
                    return null;
                });
            });
        }
        JSpinner intensity = new JSpinner(new SpinnerNumberModel(1.0, 0.0, (Comparable<?>) null, 0.1));
        JSpinner fade = new JSpinner(new SpinnerNumberModel(0.0, 0.0, (Comparable<?>) null, 0.1));
        fields.add(new JLabel("GI intensity"));
        fields.add(intensity);
        fields.add(new JLabel("Boundary fade distance"));
        fields.add(fade);
        intensity.setToolTipText("Indirect diffuse intensity. Default 1. Applies immediately; no rebake needed.");
        fade.setToolTipText("World-space outer boundary fade. Default 0 keeps room walls fully lit. No rebake needed.");
        for (JSpinner appearance : new JSpinner[]{intensity, fade}) {
            appearance.addChangeListener(event -> {
                if (syncingCounts[0]) {
                    return;
                }
                countRevision[0]++;
                float strength = ((Number) intensity.getValue()).floatValue();
                float distance = ((Number) fade.getValue()).floatValue();
                SceneApplication app = SceneApplication.getApplication();
                app.enqueue(() -> {
                    volume.setIntensity(strength);
                    volume.setFadeDistance(distance);
                    SceneRequest request = app.getCurrentSceneRequest();
                    SwingUtilities.invokeLater(() -> {
                        file.setModified(true);
                        if (request != null) {
                            var node = request.getJmeNode().getChild(volume);
                            if (node != null) {
                                node.refreshProperties();
                            }
                        }
                    });
                    return null;
                });
            });
        }
        JSpinner capture = spinner(fields, "Capture size", 64, 4, 2048);
        JSpinner samples = spinner(fields, "Radiance samples", 1024, 32, 65536);
        JSpinner visibility = spinner(fields, "Visibility resolution", 16, 2, 64);
        JSpinner clearance = new JSpinner(new SpinnerNumberModel(0.05, 0.001, 1000.0, 0.01));
        fields.add(new JLabel("Probe clearance"));
        fields.add(clearance);
        clearance.setToolTipText("Minimum distance from geometry in world units. Also respects near plane and 2% of local spacing. Requires rebaking.");
        JSpinner far = spinner(fields, "Capture distance", 200, 1, 1000000);
        JSpinner budget = spinner(fields, "Frame budget (ms)", 2, 1, 100);
        JSpinner shadowSize = spinner(fields, "Shadow map size", 512, 64, 4096);
        JCheckBox shadows = new JCheckBox("Capture shadows", true);
        fields.add(shadows);
        fields.add(new JLabel("Respects light Cast Shadow"));
        JCheckBox compact = new JCheckBox("Normalized half precision", true);
        JCheckBox placement = new JCheckBox("Classify / relocate probes", true);
        fields.add(compact);
        fields.add(placement);
        JCheckBox adaptive = new JCheckBox("Adaptive local refinement", false);
        adaptive.setToolTipText("Keep the coarse grid and bake denser overlapping bricks near geometry. Additional memory and bake time required.");
        fields.add(adaptive);
        fields.add(new JLabel("Coarse grid + local bricks"));
        JSpinner refinements = spinner(fields, "Maximum refinement bricks", 4, 1, 15);
        refinements.setToolTipText("Extra streamed volumes. The entire active scene still shares the 16-volume limit.");
        JSpinner depth = spinner(fields, "Refinement depth", 2, 1, 4);
        depth.setToolTipText("Recursive local refinement levels. All levels share the maximum brick budget.");
        surfaceLayout.setToolTipText("Sample free positions outside surfaces. Counts set bin resolution and slot budget. Requires rebaking; disables regular refinement bricks. Uses full precision for position/index storage.");
        fields.add(surfaceLayout);
        fields.add(new JLabel("Free surface positions"));
        surfaceLayout.addActionListener(event -> {
            if (syncingCounts[0]) {
                return;
            }
            boolean selected = surfaceLayout.isSelected();
            refreshGrid.run();
            countRevision[0]++;
            adaptive.setEnabled(!selected);
            refinements.setEnabled(!selected && adaptive.isSelected());
            depth.setEnabled(!selected && adaptive.isSelected());
            SceneApplication.getApplication().enqueue(() -> {
                volume.setSurfaceLayout(selected);
                SwingUtilities.invokeLater(() -> file.setModified(true));
                return null;
            });
        });
        refinements.setEnabled(false);
        depth.setEnabled(false);
        adaptive.addActionListener(event -> {
            refinements.setEnabled(!surfaceLayout.isSelected() && adaptive.isSelected());
            depth.setEnabled(!surfaceLayout.isSelected() && adaptive.isSelected());
            if (adaptive.isSelected()) {
                placement.setSelected(true);
            }
            placement.setEnabled(!adaptive.isSelected());
        });
        placement.setToolTipText("Reject interior probes; exact triangle distance enforces clearance, including edges and corners. Every relocation is rechecked.");
        adaptive.setToolTipText("Surface-coverage refinement; classification and safe relocation are required. Uncovered areas retain coarse GI. Visualize GI volumes: green=valid, blue=fine, yellow=relocated, red=invalid, cyan=not resident.");
        visibility.setToolTipText("Directional distance atlas resolution. 16 by default; 32 for difficult geometry. Requires rebaking.");
        JLabel status = new JLabel("Captures a snapshot. Save the scene after completion.");
        status.setMinimumSize(new java.awt.Dimension(0, 20));
        status.setToolTipText(status.getText());
        JButton start = new JButton(com.jme3.gde.core.util.EditorMessages.text("GiBakeApply"));
        JButton cancel = new JButton("Cancel");
        cancel.setEnabled(false);
        start.setEnabled(false);
        SceneApplication.getApplication().enqueue(() -> {
            boolean surfaceMode = volume.isSurfaceLayout();
            float strength = volume.getIntensity();
            float fadeDistance = volume.getFadeDistance();
            var counts = volume.getProbeCounts();
            var extent = volume.getCoverage().mult(volume.getWorldScale());
            SwingUtilities.invokeLater(() -> {
                surfaceLayout.setSelected(surfaceMode);
                adaptive.setEnabled(!surfaceMode);
                intensity.setValue((double) strength);
                fade.setValue((double) fadeDistance);
                countX.setValue((int) counts.x);
                countY.setValue((int) counts.y);
                countZ.setValue((int) counts.z);
                worldCoverage[0] = extent;
                refreshGrid.run();
                syncingCounts[0] = false;
                countX.setEnabled(true);
                countY.setEnabled(true);
                countZ.setEnabled(true);
                start.setEnabled(true);
            });
            return null;
        });
        JPanel buttons = new JPanel();
        buttons.add(start);
        buttons.add(cancel);
        dialog.add(fields, BorderLayout.NORTH);
        dialog.add(status, BorderLayout.CENTER);
        dialog.add(buttons, BorderLayout.SOUTH);
        AtomicBoolean cancelled = new AtomicBoolean();
        dialog.addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && !dialog.isShowing()) {
                cancelled.set(true);
            }
        });
        javax.swing.JLabel diagnostics = new javax.swing.JLabel(" ");
        diagnostics.setMinimumSize(new java.awt.Dimension(0, 20));
        diagnostics.setToolTipText("Valid probes and supported preview materials; move/resize requires rebaking.");
        JPanel footer = new JPanel(new BorderLayout());
        footer.add(buttons, BorderLayout.NORTH);
        footer.add(diagnostics, BorderLayout.SOUTH);
        dialog.add(footer, BorderLayout.SOUTH);
        javax.swing.Timer diagnosticTimer = new javax.swing.Timer(750, event -> {
            String message = SceneApplication.getApplication().getGiPreviewStatus();
            diagnostics.setText(message.isEmpty() ? "GI preview: waiting for a valid bake" : message);
            diagnostics.setToolTipText(diagnostics.getText());
            int revision = countRevision[0];
            SceneApplication.getApplication().enqueue(() -> {
                var extent = volume.getCoverage().mult(volume.getWorldScale());
                var counts = volume.getProbeCounts();
                boolean surfaceMode = volume.isSurfaceLayout();
                float strength = volume.getIntensity();
                float fadeDistance = volume.getFadeDistance();
                SwingUtilities.invokeLater(() -> {
                    // Ignore snapshots queued before a newer local edit. Setting
                    // widgets from Properties must not write back or invalidate data.
                    if (revision == countRevision[0]) {
                        surfaceLayout.setSelected(surfaceMode);
                        if (start.isEnabled()) {
                            adaptive.setEnabled(!surfaceMode);
                            refinements.setEnabled(!surfaceMode && adaptive.isSelected());
                            depth.setEnabled(!surfaceMode && adaptive.isSelected());
                        }
                        syncingCounts[0] = true;
                        intensity.setValue((double) strength);
                        fade.setValue((double) fadeDistance);
                        countX.setValue((int) counts.x);
                        countY.setValue((int) counts.y);
                        countZ.setValue((int) counts.z);
                        syncingCounts[0] = false;
                    }
                    worldCoverage[0] = extent;
                    refreshGrid.run();
                });
                return null;
            });
        });
        dialog.addHierarchyListener(event -> {
            if (dialog.isShowing()) { diagnosticTimer.start(); }
            else { diagnosticTimer.stop(); }
        });
        cancel.addActionListener(event -> cancelled.set(true));
        start.addActionListener(event -> {
            start.setEnabled(false);
            cancel.setEnabled(true);
            cancelled.set(false);
            for (JSpinner count : new JSpinner[]{countX, countY, countZ}) {
                count.setEnabled(false);
            }
            GiAdaptiveProbeLayout.Settings layout = new GiAdaptiveProbeLayout.Settings();
            layout.maxRefinements = !surfaceLayout.isSelected() && adaptive.isSelected() ? (Integer) refinements.getValue() : 0;
            layout.maxDepth = (Integer) depth.getValue();
            adaptive.setEnabled(false);
            surfaceLayout.setEnabled(false);
            refinements.setEnabled(false);
            depth.setEnabled(false);
            placement.setEnabled(false);
            GiSceneBaker.Settings quality = new GiSceneBaker.Settings();
            quality.probeClearance = ((Number) clearance.getValue()).floatValue();
            quality.captureSize = (Integer) capture.getValue();
            quality.radianceSamples = (Integer) samples.getValue();
            quality.visibilityResolution = (Integer) visibility.getValue();
            quality.far = ((Integer) far.getValue()).floatValue();
            quality.compactStorage = compact.isSelected();
            quality.classifyProbes = placement.isSelected();
            quality.captureShadows = shadows.isSelected();
            quality.shadowMapSize = (Integer) shadowSize.getValue();
            long nanos = ((Integer) budget.getValue()).longValue() * 1_000_000L;
            SceneApplication app = SceneApplication.getApplication();
            app.enqueue(() -> {
                SceneRequest request = app.getCurrentSceneRequest();
                if (request == null || cancelled.get()
                        || (volume != request.getRootNode()
                        && !(request.getRootNode() instanceof com.jme3.scene.Node root && volume.hasAncestor(root)))) {
                    SwingUtilities.invokeLater(() -> {
                        status.setText("Scene unavailable or bake cancelled.");
                        start.setEnabled(true);
                        cancel.setEnabled(false);
                        countX.setEnabled(true);
                        countY.setEnabled(true);
                        countZ.setEnabled(true);
                        surfaceLayout.setEnabled(true);
                        adaptive.setEnabled(!surfaceLayout.isSelected());
                        refinements.setEnabled(!surfaceLayout.isSelected() && adaptive.isSelected());
                        depth.setEnabled(!surfaceLayout.isSelected() && adaptive.isSelected());
                        placement.setEnabled(!adaptive.isSelected());
                    });
                    return null;
                }
                // Earlier panel writes are already queued; Properties and this
                // panel share the live volume. Never reapply a stale UI snapshot.
                app.getStateManager().attach(new BaseAppState() {
                    private GiAdaptiveBakeJob job;

                    @Override
                    protected void initialize(Application application) {
                        try {
                            Spatial scene = request.getRootNode();
                            Spatial updateRoot = scene;
                            while (updateRoot.getParent() != null) {
                                updateRoot = updateRoot.getParent();
                            }
                            updateRoot.updateGeometricState();
                            Spatial snapshot = scene.clone(true);
                            // Detaching the snapshot must retain its world placement and
                            // resolved shadow policy inherited from the SDK scene root.
                            snapshot.setLocalTransform(scene.getWorldTransform());
                            snapshot.setShadowMode(scene.getShadowMode());
                            snapshot.depthFirstTraversal(spatial -> {
                                while (spatial.getNumControls() > 0) {
                                    spatial.removeControl(spatial.getControl(0));
                                }
                            });
                            snapshot.updateGeometricState();
                            job = GiAdaptiveBakeJob.start(app.getRenderManager(), snapshot, volume, quality, layout,
                                    view -> { }, cancelled::get,
                                    count -> SwingUtilities.invokeLater(() -> status.setText("Completed probes: " + count)));
                        } catch (RuntimeException error) {
                            finish("Bake failed: " + error.getMessage());
                        }
                    }

                    @Override
                    public void update(float tpf) {
                        if (job == null) {
                            return;
                        }
                        if (app.getCurrentSceneRequest() != request
                                || (volume != request.getRootNode() && volume.getParent() == null)) {
                            cancelled.set(true);
                        }
                        try {
                            if (job.step(nanos)) {
                                // Old data may still be shared by runtime bindings; do not dispose it here.
                                SwingUtilities.invokeLater(() -> file.setModified(true));
                                app.setGiPreviewEnabled(true);
                                SwingUtilities.invokeLater(() -> {
                                    status.setText("Bake captured. Saving GI data; preview is already updated...");
                                    status.setToolTipText(status.getText());
                                    cancel.setEnabled(false);
                                });
                                // Release capture resources now, but keep Start disabled
                                // until the file and scene reference have both committed.
                                app.getStateManager().detach(this);
                                java.util.List<GiProbeVolume> published = job.getPublishedVolumes();
                                GiAdaptiveProbeLayout.Plan plan = job.getLayoutPlan();
                                String coverageReport = plan.getSurfaceTargets() == 0 ? ""
                                        : " Refined surface samples: " + plan.getCoveredTargets() + "/"
                                        + plan.getSurfaceTargets() + ". Uncovered samples retain coarse GI.";
                                SwingUtilities.invokeLater(() -> request.getJmeNode().refresh(true));
                                java.util.concurrent.atomic.AtomicInteger remaining =
                                        new java.util.concurrent.atomic.AtomicInteger(published.size());
                                java.util.List<String> problems = new java.util.ArrayList<>();
                                for (GiProbeVolume baked : published) {
                                    com.jme3.gde.core.scene.GiStreamingAssets.save(app, request, baked, file, message -> {
                                        // Completion callbacks run on the EDT, so aggregation is serialized.
                                        if (!message.startsWith("Bake complete. GI data saved:")) {
                                            problems.add(message);
                                        }
                                        if (remaining.decrementAndGet() == 0) {
                                            finish(problems.isEmpty()
                                                    ? "Bake complete. Saved " + published.size()
                                                            + " GI assets." + coverageReport + " Save the scene to keep their references."
                                                    : String.join(" | ", problems));
                                        }
                                    });
                                }
                            }
                        } catch (java.util.concurrent.CancellationException error) {
                            finish("Cancelled. Grid edits remain applied; rebake after changing the grid.");
                        } catch (RuntimeException error) {
                            finish("Bake failed: " + error.getMessage());
                        }
                    }

                    private void finish(String text) {
                        SwingUtilities.invokeLater(() -> {
                            status.setText(text);
                            status.setToolTipText(text);
                            start.setEnabled(true);
                            cancel.setEnabled(false);
                            countX.setEnabled(true);
                            countY.setEnabled(true);
                            countZ.setEnabled(true);
                            surfaceLayout.setEnabled(true);
                        adaptive.setEnabled(!surfaceLayout.isSelected());
                            refinements.setEnabled(!surfaceLayout.isSelected() && adaptive.isSelected());
                            depth.setEnabled(!surfaceLayout.isSelected() && adaptive.isSelected());
                            placement.setEnabled(!adaptive.isSelected());
                        });
                        app.getStateManager().detach(this);
                    }

                    @Override
                    protected void cleanup(Application application) {
                        if (job != null) {
                            job.close();
                        }
                    }

                    @Override
                    protected void onEnable() {
                    }

                    @Override
                    protected void onDisable() {
                    }
                });
                return null;
            });
        });
        return dialog;
    }

    private static String ui(String key) {
        return com.jme3.gde.core.util.EditorMessages.text(key);
    }

    private JSpinner spinner(JPanel panel, String label, int value, int minimum, int maximum) {
        panel.add(new JLabel(label));
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(value, minimum, maximum, 1));
        panel.add(spinner);
        return spinner;
    }
}
