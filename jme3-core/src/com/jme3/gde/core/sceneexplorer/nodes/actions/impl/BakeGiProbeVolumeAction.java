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
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.awt.event.ActionEvent;
import java.util.WeakHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.netbeans.api.progress.ProgressHandle;
import javax.swing.*;
import org.openide.loaders.DataObject;
import org.openide.util.lookup.ServiceProvider;

/**
 * Preview upscaling and TAA are transiently bypassed for the complete probe and
 * Lightmap capture. One preview frame settles native targets before sampling.
 * Cleanup restores current preferences on success, cancellation and failure.
 * 第一步：Start 后实际采样前挂起超分和 TAA，不改变保存到 J3O 的偏好。
 * 第二步：独立线性捕获不复制曝光、Bloom、色调映射或调色。
 * 第三步：探针和 Lightmap 结束后恢复；取消、失败和场景关闭也恢复。
 * Nonmodal frame-budgeted bake panel. Captures a scene snapshot; scene edits made
 * after Start require another bake. Cancellation preserves the previous volume.
 * Capture-owned shadows default to enabled and respect Light.castShadow and
 * geometry ShadowMode. Saved assets can be bound using the
 * engine's GiProbeSet/Streamer. SDK preview applies the completed result during
 * drawing only, restoring original material bindings before any save. The UI
 * reports completion only after the external file and live reference commit;
 * the user must then save the J3O. Ordinary brick export keeps the old preview
 * until the coarse layer and fine directory are ready.
 *
 * <pre>
 * 第一步：Add Light / GI Light Probe Volume，然后拖动节点或使用移动工具定位。
 * 第二步：在属性面板设置 Coverage；烘焙面板的 X/Y/Z counts 设置密度。
 * 数量修改立即同步到 Properties 的 Probe counts；改变数量会使旧数据失效。
 * Coverage 和缩放变化也同步更新世界间距；需点击 Bake 重新生成光照数据。
 * 取消烘焙不会回退已应用的新网格，需重新 Bake；相同数量取消保留旧数据。
 * 第三步：Tools / Bake GI volume，设置质量、帧预算并点击 Start。
 * Probe shadow map size 默认 512，控制探针捕获的临时阴影图和预算。
 * 启用 Bake direct lighting and static shadows 后，Lightmap shadow map size
 * 默认 2048，单独控制写入 UV2 直射层前的静态方向光遮挡精度。完成后保存场景。
 * 调大 Lightmap shadow radius 后可提高 Samples per axis 细化过渡；半径 0 重烘焙恢复原效果。
 * 第四步：Surface probe layout 默认关闭；开启时按表面自由布点，使用全精度数据，
 * 不创建规则加密块。Counts 此时表示分桶尺寸和总槽位预算；橙色点表示实际表面探针。
 * 保存 J3O 及 GI 外部文件后自动恢复；关闭开关并重新烘焙恢复规则布局。
 * 规则模式可选择 Adaptive local refinement（默认关闭），按几何分布增加局部加密块。
 * 自适应始终执行分类与安全重定位；结束后显示表面采样覆盖数，未覆盖区域保留粗网格。
 * 默认最多 4 块；每块占用现有流送槽位，整个活动集合仍最多 16 个 Volume。
 * 第五步：切换选中对象时，底部仍显示烘焙进度。点回原 Volume 可查看进度或 Cancel。
 * 依次点击多个 Volume 的 Bake 可排队，每次只捕获一个 Volume；取消某项不停止其他项。
 * 普通规则网格自动生成一层粗网格和空间细块；等待 GI 文件写入后再保存场景。
 * SceneComposer / World Settings / GI streaming 可调整细层驻留限制，无需重烘焙。
 * 关闭自适应并重新烘焙可恢复普通网格；仅移除自动生成的加密子节点。
 * 第六步：Diffuse bounce passes 默认 1，可选 2..8 进行多次漫反射烘焙。
 * 每轮只读取上一轮完整结果，旧数据不参与反馈；运行时仍使用原来的八探针设置。
 * 烘焙时间和临时内存增加；取消保留旧结果。
 * 保存场景后恢复各 Volume 的烘焙选项；选择 1 并重新烘焙可恢复单次反射。
 * 多次反射暂不支持 Adaptive local refinement；体积应覆盖需要接收反射的表面。
 * 第七步：Separate sky visibility 默认关闭，烘焙包含天空的完整漫反射。
 * 体积内替代天空漫反射，体积外或关闭 GI 后回退天光；天空反射独立保留。
 * 勾选后恢复反弹光与天空可见度分离模式；两种模式切换均需重新烘焙并保存。
 * 现有数据不因面板开关而改变，取消烘焙保留原模式。
 * 第八步：选中 GI Light Probe Volume，在烘焙面板勾选 Smooth Lightmap seams，默认开启。
 * 点击 Bake & Apply 后，新烘焙的静态 Lightmap 会混合平滑共享边两侧的 UV2 接缝。
 * 可取消勾选并重烘焙做对照；完成提示会显示开关状态，保存场景可保留配方和贴图引用。
 * 第九步：勾选 Bake directional indirect Lightmaps 并烘焙，可为带法线贴图的静态 PBR 表面生成独立方向数据。
 * 该烘焙选项默认关闭，不改变现有 Lightmap；生成后在 Baked Lightmaps 中切换播放，可即时比较。
 * </pre>
 */
@ServiceProvider(service = ToolAction.class)
public final class BakeGiProbeVolumeAction implements ToolAction {
    private static final String BAKE_OPTION_PREFIX = "jme.sdk.giBake.";
    // Inspector panels are transient. Only the EDT accesses this volume lookup.
    private static final Map<GiProbeVolume, BakeSession> BAKES = new WeakHashMap<>();
    private static final ArrayDeque<PendingBake> QUEUE = new ArrayDeque<>();
    private static BakeSession activeBake;

    /** Returns whether a bake owns the GI queue. Call from the Swing EDT before editing baked data. */
    public static boolean isBakeInProgress() {
        return activeBake != null;
    }

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

    /** Opens bake settings for an editable volume. Call on the EDT. */
    public void show(GiProbeVolume volume, DataObject file) {
        JDialog dialog = new JDialog((java.awt.Frame) null, "Bake GI volume", false);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.add(createPanel(volume, file));
        dialog.pack();
        dialog.setLocationRelativeTo(null);
        dialog.setVisible(true);
    }

    /**
     * Creates an EDT view of the volume bake. Rebuilding the inspector does not
     * affect the scene-owned job. As a direct JScrollPane view the root
     * tracks viewport width, including when the vertical scrollbar appears, so
     * asynchronous grid/status text cannot widen the Properties dock or clip labels.
     */
    public JPanel createPanel(GiProbeVolume volume, DataObject file) {
        JPanel dialog = new com.jme3.gde.core.util.InspectorColumns.WidthTrackedPanel(
                new BorderLayout(4, 4), 360);
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
        JCheckBox separateSky = new JCheckBox("Separate sky visibility", false);
        separateSky.setToolTipText("Default off: bake total sky + bounced diffuse; replace sky diffuse inside the volume. On: store bounced diffuse and sky visibility separately. Switching requires rebaking; sky reflections stay enabled.");
        fields.add(separateSky);
        fields.add(new JLabel("Off: bake total diffuse"));
        JSpinner bounces = spinner(fields, "Diffuse bounce passes", 1, 1, 8);
        bounces.setToolTipText("Default 1. More passes propagate diffuse indirect light; increases bake time, not runtime probe count. Cancel preserves old data.");
        JSpinner visibility = spinner(fields, "Visibility resolution", 16, 2, 64);
        JSpinner clearance = new JSpinner(new SpinnerNumberModel(0.05, 0.001, 1000.0, 0.01));
        fields.add(new JLabel("Probe clearance"));
        fields.add(clearance);
        clearance.setToolTipText("Minimum distance from geometry in world units. Also respects near plane and 2% of local spacing. Requires rebaking.");
        JSpinner far = spinner(fields, "Capture distance", 200, 1, 1000000);
        JSpinner budget = spinner(fields, "Frame budget (ms)", 2, 1, 100);
        JSpinner shadowSize = spinner(fields, "Probe shadow map size", 512, 64, 4096);
        shadowSize.setToolTipText("Temporary shadow maps for probe capture. Default 512. Larger maps share the 64 MiB capture budget.");
        JCheckBox shadows = new JCheckBox("Capture shadows", true);
        fields.add(shadows);
        fields.add(new JLabel("Respects light Cast Shadow"));
        JCheckBox smoothLightmapSeams = new JCheckBox("Smooth Lightmap seams", true);
        smoothLightmapSeams.setToolTipText("Default on: blend across smooth UV2 chart seams in newly baked static Lightmaps. Rebake to apply.");
        fields.add(smoothLightmapSeams);
        JCheckBox bakeDirectShadows = new JCheckBox("Bake direct lighting and static shadows", false);
        bakeDirectShadows.setToolTipText("Up to four static directional lights. Dynamic casters still cast and receive.");
        fields.add(bakeDirectShadows);
        JSpinner lightmapShadowSize = spinner(fields, "Lightmap shadow map size", 2048, 64, 2048);
        lightmapShadowSize.setToolTipText("Shared static directional depth for baked direct Lightmaps. Default 2048. Independent of probe capture and UV2 texture size.");
        lightmapShadowSize.setEnabled(false);
        JSpinner shadowRadius = spinner(fields, "Lightmap shadow radius (texels)", 0, 0, 8);
        shadowRadius.setToolTipText("Filter radius in shadow map texels. Zero keeps the original edge. Rebake to apply.");
        shadowRadius.setEnabled(false);
        JSpinner shadowSamples = spinner(fields, "Soft shadow samples per axis", 3, 2, 8);
        shadowSamples.setToolTipText("2 to 8 samples per axis at the same radius. Higher values increase bake time.");
        shadowSamples.setEnabled(false);
        JCheckBox bakeDirectionalIndirect = new JCheckBox("Bake directional indirect Lightmaps", true);
        bakeDirectionalIndirect.setToolTipText("Bake optional direction data for normal-mapped static PBR receivers. "
                + "Enabled by default; Directional indirect Lightmaps controls playback after the bake.");
        fields.add(bakeDirectionalIndirect);
        fields.add(new JLabel());
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
        adaptive.setToolTipText("Surface-coverage refinement; classification and safe relocation are required. Uncovered areas retain coarse GI. Visualize GI volumes: light blue=valid coarse, green=valid fine, yellow=relocated, red=invalid, gray=unbaked or not resident, orange=surface probes.");
        visibility.setToolTipText("Directional distance atlas resolution. 16 by default; 32 for difficult geometry. Requires rebaking.");
        Map<String, JComponent> bakeOptions = new LinkedHashMap<>();
        bakeOptions.put("captureSize", capture);
        bakeOptions.put("radianceSamples", samples);
        bakeOptions.put("separateSky", separateSky);
        bakeOptions.put("bounceCount", bounces);
        bakeOptions.put("visibilityResolution", visibility);
        bakeOptions.put("probeClearance", clearance);
        bakeOptions.put("captureDistance", far);
        bakeOptions.put("frameBudgetMs", budget);
        bakeOptions.put("shadowMapSize", shadowSize);
        bakeOptions.put("captureShadows", shadows);
        bakeOptions.put("smoothLightmapSeams", smoothLightmapSeams);
        bakeOptions.put("bakeDirectShadows", bakeDirectShadows);
        bakeOptions.put("bakeDirectionalIndirect", bakeDirectionalIndirect);
        bakeOptions.put("lightmapShadowMapSize", lightmapShadowSize);
        bakeOptions.put("lightmapShadowRadius", shadowRadius);
        bakeOptions.put("lightmapShadowSamplesPerAxis", shadowSamples);
        bakeOptions.put("compactStorage", compact);
        bakeOptions.put("classifyProbes", placement);
        bakeOptions.put("adaptiveRefinement", adaptive);
        bakeOptions.put("maxRefinements", refinements);
        bakeOptions.put("refinementDepth", depth);
        bakeOptions.forEach((name, widget) -> {
            Runnable persist = () -> {
                if (syncingCounts[0]) {
                    return;
                }
                Object value = widget instanceof JSpinner spinner
                        ? spinner.getValue() : ((JCheckBox) widget).isSelected();
                SceneApplication.getApplication().enqueue(() -> {
                    volume.setUserData(BAKE_OPTION_PREFIX + name, value);
                    SwingUtilities.invokeLater(() -> file.setModified(true));
                    return null;
                });
            };
            if (widget instanceof JSpinner spinner) {
                spinner.addChangeListener(event -> persist.run());
            } else {
                ((JCheckBox) widget).addItemListener(event -> persist.run());
            }
        });
        JLabel status = new JLabel("Captures a snapshot. Save the scene after completion.");
        status.setMinimumSize(new java.awt.Dimension(0, 20));
        status.setToolTipText(status.getText());
        JButton start = new JButton(com.jme3.gde.core.util.EditorMessages.text("GiBakeApply"));
        JButton cancel = new JButton("Cancel");
        cancel.setEnabled(false);
        start.setEnabled(false);
        boolean[] settingsReady = {false};
        Runnable refreshBake = () -> {
            BakeSession bake = BAKES.get(volume);
            boolean busy = bake != null && bake.isRunning();
            if (bake != null) {
                String message = bake.message();
                status.setText(message);
                status.setToolTipText(message);
            }
            cancel.setEnabled(bake != null && bake.canCancel());
            start.setEnabled(settingsReady[0] && !busy);
            if (!settingsReady[0]) {
                return;
            }
            boolean editable = !busy;
            bakeOptions.values().forEach(widget -> widget.setEnabled(editable));
            shadowSize.setEnabled(editable && shadows.isSelected());
            boolean editDirect = editable && bakeDirectShadows.isSelected();
            lightmapShadowSize.setEnabled(editDirect);
            shadowRadius.setEnabled(editDirect);
            shadowSamples.setEnabled(editDirect && (Integer) shadowRadius.getValue() > 0);
            countX.setEnabled(editable);
            countY.setEnabled(editable);
            countZ.setEnabled(editable);
            surfaceLayout.setEnabled(editable);
            boolean refining = !surfaceLayout.isSelected() && adaptive.isSelected();
            adaptive.setEnabled(editable && !surfaceLayout.isSelected());
            refinements.setEnabled(editable && refining);
            depth.setEnabled(editable && refining);
            placement.setEnabled(editable && !refining);
        };
        shadows.addItemListener(event -> refreshBake.run());
        bakeDirectShadows.addItemListener(event -> refreshBake.run());
        shadowRadius.addChangeListener(event -> refreshBake.run());
        refreshBake.run();
        SceneApplication.getApplication().enqueue(() -> {
            boolean surfaceMode = volume.isSurfaceLayout();
            float strength = volume.getIntensity();
            float fadeDistance = volume.getFadeDistance();
            var counts = volume.getProbeCounts();
            var extent = volume.getCoverage().mult(volume.getWorldScale());
            Map<String, Object> savedOptions = new LinkedHashMap<>();
            for (String name : bakeOptions.keySet()) {
                savedOptions.put(name, volume.getUserData(BAKE_OPTION_PREFIX + name));
            }
            SwingUtilities.invokeLater(() -> {
                savedOptions.forEach((name, value) -> {
                    JComponent widget = bakeOptions.get(name);
                    if (widget instanceof JSpinner spinner && value instanceof Number) {
                        spinner.setValue(value);
                    } else if (widget instanceof JCheckBox checkbox && value instanceof Boolean selected) {
                        checkbox.setSelected(selected);
                    }
                });
                surfaceLayout.setSelected(surfaceMode);
                intensity.setValue((double) strength);
                fade.setValue((double) fadeDistance);
                countX.setValue((int) counts.x);
                countY.setValue((int) counts.y);
                countZ.setValue((int) counts.z);
                worldCoverage[0] = extent;
                refreshGrid.run();
                syncingCounts[0] = false;
                settingsReady[0] = true;
                refreshBake.run();
            });
            return null;
        });
        JPanel buttons = new JPanel();
        buttons.add(start);
        buttons.add(cancel);
        dialog.add(fields, BorderLayout.NORTH);
        dialog.add(status, BorderLayout.CENTER);
        javax.swing.JLabel diagnostics = new javax.swing.JLabel(" ");
        diagnostics.setMinimumSize(new java.awt.Dimension(0, 20));
        diagnostics.setToolTipText("Valid probes and supported preview materials; move/resize requires rebaking.");
        JPanel footer = new JPanel(new BorderLayout());
        footer.add(buttons, BorderLayout.NORTH);
        footer.add(diagnostics, BorderLayout.SOUTH);
        dialog.add(footer, BorderLayout.SOUTH);
        javax.swing.Timer diagnosticTimer = new javax.swing.Timer(750, event -> {
            refreshBake.run();
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
                        syncingCounts[0] = true;
                        surfaceLayout.setSelected(surfaceMode);
                        intensity.setValue((double) strength);
                        fade.setValue((double) fadeDistance);
                        countX.setValue((int) counts.x);
                        countY.setValue((int) counts.y);
                        countZ.setValue((int) counts.z);
                        syncingCounts[0] = false;
                    }
                    worldCoverage[0] = extent;
                    refreshGrid.run();
                    refreshBake.run();
                });
                return null;
            });
        });
        dialog.addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0) {
                if (dialog.isShowing()) {
                    refreshBake.run();
                    diagnosticTimer.start();
                } else {
                    diagnosticTimer.stop();
                }
            }
        });
        cancel.addActionListener(event -> {
            BakeSession bake = BAKES.get(volume);
            if (bake != null && bake.cancel()) {
                if (QUEUE.removeIf(pending -> pending.session() == bake)) {
                    bake.finish("Queued bake cancelled.");
                }
                refreshBake.run();
            }
        });
        start.addActionListener(event -> {
            BakeSession active = BAKES.get(volume);
            if (active != null && active.isRunning()) {
                refreshBake.run();
                return;
            }
            if ((Integer) bounces.getValue() > 1 && adaptive.isSelected() && !surfaceLayout.isSelected()) {
                status.setText("Multiple bounce passes require Adaptive local refinement to be off.");
                return;
            }
            GiAdaptiveProbeLayout.Settings layout = new GiAdaptiveProbeLayout.Settings();
            layout.maxRefinements = !surfaceLayout.isSelected() && adaptive.isSelected() ? (Integer) refinements.getValue() : 0;
            layout.maxDepth = (Integer) depth.getValue();
            GiSceneBaker.Settings quality = new GiSceneBaker.Settings();
            quality.probeClearance = ((Number) clearance.getValue()).floatValue();
            quality.captureSize = (Integer) capture.getValue();
            quality.radianceSamples = (Integer) samples.getValue();
            quality.bounceCount = (Integer) bounces.getValue();
            quality.separateSky = separateSky.isSelected();
            quality.visibilityResolution = (Integer) visibility.getValue();
            quality.far = ((Integer) far.getValue()).floatValue();
            quality.compactStorage = compact.isSelected();
            quality.classifyProbes = placement.isSelected();
            quality.captureShadows = shadows.isSelected();
            quality.shadowMapSize = (Integer) shadowSize.getValue();
            boolean blendSeams = smoothLightmapSeams.isSelected();
            boolean bakeDirect = bakeDirectShadows.isSelected();
            boolean directionalIndirect = bakeDirectionalIndirect.isSelected();
            int directShadowSize = (Integer) lightmapShadowSize.getValue();
            int radius = (Integer) shadowRadius.getValue();
            int samplesPerAxis = (Integer) shadowSamples.getValue();
            long nanos = ((Integer) budget.getValue()).longValue() * 1_000_000L;
            var bake = new BakeSession(volume.getName());
            BAKES.put(volume, bake);
            SceneApplication app = SceneApplication.getApplication();
            SceneRequest request = app.getCurrentSceneRequest();
            QUEUE.addLast(new PendingBake(bake,
                    () -> startBake(app, request, volume, file, quality, layout, blendSeams, bakeDirect,
                            directShadowSize, radius, samplesPerAxis, directionalIndirect, nanos, bake)));
            startNextBake();
            refreshBake.run();
        });
        return dialog;
    }

    private static void startNextBake() {
        if (activeBake == null && !QUEUE.isEmpty()) {
            PendingBake next = QUEUE.removeFirst();
            activeBake = next.session();
            activeBake.started();
            next.start().run();
        }
    }

    private record PendingBake(BakeSession session, Runnable start) {
    }

    private static void startBake(SceneApplication app, SceneRequest request, GiProbeVolume volume, DataObject file,
            GiSceneBaker.Settings quality, GiAdaptiveProbeLayout.Settings layout,
            boolean blendSeams, boolean bakeDirect, int directShadowSize, int radius, int samplesPerAxis,
            boolean directionalIndirect, long nanos, BakeSession bake) {
        app.enqueue(() -> {
            if (request == null || request != app.getCurrentSceneRequest() || request.getDataObject() != file
                    || !isInScene(request, volume) || bake.isCancelled()) {
                bake.finish("Scene unavailable or bake cancelled.");
                return null;
            }
            // Earlier panel writes are already queued; Properties and this
            // panel share the live volume. Never reapply a stale UI snapshot.
            app.getStateManager().attach(new BaseAppState() {
                private GiAdaptiveBakeJob job;
                private com.jme3.gde.core.scene.ProbeLightmapAssets lightmaps;
                private boolean saving;
                private com.jme3.gde.core.scene.GiBakeReconstructionGuard reconstruction;
                private boolean previewPending;

                @Override
                protected void initialize(Application application) {
                    try {
                        if (bake.isCancelled() || app.getCurrentSceneRequest() != request || !isInScene(request, volume)) {
                            finish("Scene unavailable or bake cancelled.");
                            return;
                        }
                        reconstruction = new com.jme3.gde.core.scene.GiBakeReconstructionGuard(app.getViewPort());
                        previewPending = reconstruction.refresh();
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
                                view -> { }, bake::isCancelled, bake::recordPasses);
                    } catch (RuntimeException error) {
                        finishFailure(error);
                    }
                }

                @Override
                public void update(float tpf) {
                    if (job == null) {
                        return;
                    }
                    if (app.getCurrentSceneRequest() != request
                            || !isInScene(request, volume)) {
                        bake.cancel();
                    }
                    try {
                        boolean changed = reconstruction.refresh();
                        if (previewPending || changed) {
                            previewPending = false;
                            return;
                        }
                        if (lightmaps == null && job.step(nanos)) {
                            lightmaps = new com.jme3.gde.core.scene.ProbeLightmapAssets(
                                    app, request, volume, blendSeams, bakeDirect, directShadowSize, radius,
                                    samplesPerAxis, directionalIndirect);
                        }
                        if (bake.isCancelled()) {
                            finish("Cancelled. Save or rebake the probe data as needed.");
                            return;
                        }
                        if (lightmaps != null && lightmaps.step()) {
                            // Old data may still be shared by runtime bindings; do not dispose it here.
                            SwingUtilities.invokeLater(() -> file.setModified(true));
                            app.setGiPreviewEnabled(true);
                            saving = true;
                            bake.saving();
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
                                boolean bricks = layout.maxRefinements == 0 && !baked.isSurfaceLayout();
                                com.jme3.gde.core.scene.GiStreamingAssets.save(app, request, baked, file, bricks, message -> {
                                    // Completion callbacks run on the EDT, so aggregation is serialized.
                                    if (!message.startsWith("Bake complete. GI data saved:")) {
                                        problems.add(message);
                                    }
                                    if (remaining.decrementAndGet() == 0) {
                                        finish(problems.isEmpty()
                                                ? "Bake complete. Saved " + published.size()
                                                        + " GI assets." + coverageReport + lightmaps.summary() + " Save the scene to keep their references."
                                                : String.join(" | ", problems));
                                    }
                                });
                            }
                        }
                    } catch (java.util.concurrent.CancellationException error) {
                        finish("Cancelled. Grid edits remain applied; rebake after changing the grid.");
                    } catch (RuntimeException error) {
                        finishFailure(error);
                    }
                }

                private void finishFailure(RuntimeException error) {
                    Logger.getLogger(BakeGiProbeVolumeAction.class.getName()).log(Level.WARNING, "GI bake failed", error);
                    Throwable cause = error;
                    while (cause.getCause() != null) {
                        cause = cause.getCause();
                    }
                    finish("Bake failed: " + (cause.getMessage() == null ? cause.toString() : cause.getMessage()));
                }

                private void finish(String text) {
                    bake.finish(text);
                    if (!saving) {
                        app.getStateManager().detach(this);
                    }
                }

                @Override
                protected void cleanup(Application application) {
                    try {
                        if (lightmaps != null) {
                            lightmaps.close();
                        }
                        if (job != null) {
                            job.close();
                        }
                    } finally {
                        if (reconstruction != null) {
                            reconstruction.close();
                            reconstruction = null;
                        }
                        if (!saving) {
                            bake.finish("Bake stopped.");
                        }
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
    }

    private static boolean isInScene(SceneRequest request, GiProbeVolume volume) {
        return volume == request.getRootNode()
                || (request.getRootNode() instanceof com.jme3.scene.Node root && volume.hasAncestor(root));
    }

    static final class BakeSession {
        private final ProgressHandle handle;
        private volatile int completedPasses;
        private volatile boolean cancelled;
        private volatile boolean saving;
        private volatile boolean queued = true;
        private volatile String result;
        private long lastProgress;

        BakeSession(String name) {
            handle = ProgressHandle.createHandle("Bake GI volume" + (name == null ? "" : ": " + name));
            handle.setInitialDelay(0);
        }

        synchronized void started() {
            queued = false;
            handle.start();
            handle.progress(message());
        }

        synchronized boolean cancel() {
            if (!canCancel()) {
                return false;
            }
            cancelled = true;
            if (!queued) {
                handle.progress("Cancelling GI bake...");
            }
            return true;
        }

        boolean isCancelled() {
            return cancelled;
        }

        boolean isRunning() {
            return result == null;
        }

        boolean canCancel() {
            return isRunning() && !saving && !cancelled;
        }

        synchronized void recordPasses(int count) {
            completedPasses = count;
            long now = System.nanoTime();
            if (canCancel() && now - lastProgress >= 200_000_000L) {
                lastProgress = now;
                handle.progress(message());
            }
        }

        synchronized void saving() {
            saving = true;
            handle.progress(message());
        }

        synchronized void finish(String text) {
            if (result == null) {
                result = text;
                if (!queued) {
                    handle.finish();
                }
                SwingUtilities.invokeLater(() -> {
                    if (activeBake == this) {
                        activeBake = null;
                        startNextBake();
                    }
                });
            }
        }

        String message() {
            String finished = result;
            if (finished != null) {
                return finished;
            }
            if (saving) {
                return "Bake captured. Saving GI data...";
            }
            if (cancelled) {
                return "Cancelling GI bake...";
            }
            if (queued) {
                return "Waiting for the current GI bake...";
            }
            return completedPasses == 0 ? "Preparing GI bake..." : "Completed probe passes: " + completedPasses;
        }
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
