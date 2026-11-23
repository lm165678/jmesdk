package com.jme3.gde.scenecomposer;

import com.jme3.gde.core.scene.PreviewRequest;
import com.jme3.gde.core.scene.SceneApplication;
import com.jme3.gde.core.scene.SceneListener;
import com.jme3.gde.core.scene.SceneRequest;
import com.jme3.gde.core.util.EditorMessages;
import com.jme3.gde.core.util.InspectorColumns;
import com.jme3.post.WorldSettings;
import com.jme3.post.WorldSettings.GiStreaming;
import com.jme3.scene.Spatial;
import java.awt.EventQueue;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

/**
 * 第一步：在 SceneComposer 的 World Settings / GI streaming 调整场景预算。
 * 第二步：应用；值为 0 的细探针预算只预览粗层，恢复默认会重新请求细块。
 * 第三步：Half-resolution GI 默认关闭；应用后对照边缘、薄墙和曲面。
 * 完整回退默认开启；关闭仅作性能对照，重建失败处改用邻近缓存，可能漏光。
 * 第四步：保存场景 J3O；游戏挂载后恢复开关，关闭可回退全分辨率，无须重烘焙。
 */
final class EditorGiStreamingPanel extends JPanel implements SceneListener {
    private final JSpinner distance = new JSpinner(new SpinnerNumberModel(
            (double) GiStreaming.DEFAULTS.getPreloadDistance(), .01, (double) Float.MAX_VALUE, 1d));
    private final JSpinner fine = new JSpinner(new SpinnerNumberModel(
            GiStreaming.DEFAULTS.getMaxFineProbes(), 0, Integer.MAX_VALUE, 128));
    private final JSpinner atlas = new JSpinner(new SpinnerNumberModel(
            GiStreaming.DEFAULTS.getAtlasMiB(), GiStreaming.MIN_ATLAS_MIB, GiStreaming.MAX_ATLAS_MIB, 1));
    private final JButton apply = new JButton(EditorMessages.text("GiStreamingApply"));
    private final JButton defaults = new JButton(EditorMessages.text("GiStreamingDefaults"));
    private final JCheckBox halfGi = new JCheckBox(EditorMessages.text("GiHalfResolution"));
    private final JCheckBox fullFallback = new JCheckBox(EditorMessages.text("GiFullFallback"), true);
    private final JCheckBox singleVisibility = new JCheckBox(EditorMessages.text("GiSingleVisibility"));
    private final JCheckBox octahedral = new JCheckBox(EditorMessages.text("GiOctahedralIrradiance"));
    private SceneRequest scene;

    EditorGiStreamingPanel() {
        add(new JLabel(EditorMessages.text("GiStreamingDistance")));
        add(distance);
        add(new JLabel(EditorMessages.text("GiStreamingFineProbes")));
        add(fine);
        add(new JLabel(EditorMessages.text("GiStreamingAtlas")));
        add(atlas);
        halfGi.setToolTipText(EditorMessages.text("GiHalfResolutionHint"));
        add(halfGi);
        fullFallback.setToolTipText(EditorMessages.text("GiFullFallbackHint"));
        add(fullFallback);
        add(singleVisibility);
        add(octahedral);
        halfGi.addActionListener(event -> fullFallback.setEnabled(halfGi.isEnabled() && halfGi.isSelected()));
        add(defaults);
        add(apply);
        InspectorColumns.install(this, "scene.giStreaming");
        apply.addActionListener(event -> {
            GiStreaming settings = new GiStreaming(((Number) distance.getValue()).floatValue(),
                    (Integer) fine.getValue(), (Integer) atlas.getValue());
            apply(settings);
        });
        defaults.addActionListener(event -> {
            showSettings(GiStreaming.DEFAULTS);
            apply(GiStreaming.DEFAULTS);
        });
        setEditable(false);
    }

    private void setEditable(boolean editable) {
        distance.setEnabled(editable);
        fine.setEnabled(editable);
        atlas.setEnabled(editable);
        halfGi.setEnabled(editable);
        singleVisibility.setEnabled(editable);
        octahedral.setEnabled(editable);
        fullFallback.setEnabled(editable && halfGi.isSelected());
        apply.setEnabled(editable);
        defaults.setEnabled(editable);
    }

    private void showSettings(GiStreaming settings) {
        showSettings(settings, false);
    }

    private void showSettings(GiStreaming settings, boolean halfResolution) {
        showSettings(settings, halfResolution, true, false, false);
    }

    private void showSettings(GiStreaming settings, boolean halfResolution, boolean fallback, boolean single, boolean oct) {
        fullFallback.setSelected(fallback);
        fullFallback.setEnabled(halfGi.isEnabled() && halfResolution);
        halfGi.setSelected(halfResolution);
        singleVisibility.setSelected(single);
        octahedral.setSelected(oct);
        distance.setValue((double) settings.getPreloadDistance());
        fine.setValue(settings.getMaxFineProbes());
        atlas.setValue(settings.getAtlasMiB());
    }

    private void apply(GiStreaming values) {
        boolean halfResolution = halfGi.isSelected();
        boolean fallback = fullFallback.isSelected();
        boolean single = singleVisibility.isSelected();
        boolean oct = octahedral.isSelected();
        SceneRequest request = scene;
        if (request == null) {
            return;
        }
        SceneApplication app = SceneApplication.getApplication();
        app.enqueue(() -> {
            if (app.getCurrentSceneRequest() != request) {
                return null;
            }
            Spatial root = request.getRootNode();
            WorldSettings world = root.getControl(WorldSettings.class);
            if (world == null) {
                world = new WorldSettings();
                world.setDisplayOutput(WorldSettings.DisplayOutput.Application);
                root.addControl(world);
            }
            world.setGiStreaming(values);
            world.setHalfResolutionProbeGi(halfResolution);
            world.setProbeGiFullFallback(fallback);
            world.setSingleProbeVisibility(single);
            world.setOctahedralProbeIrradiance(oct);
            EventQueue.invokeLater(() -> request.getDataObject().setModified(true));
            return null;
        });
    }

    private void refresh() {
        SceneApplication app = SceneApplication.getApplication();
        app.enqueue(() -> {
            SceneRequest current = app.getCurrentSceneRequest();
            WorldSettings world = current == null || current.getRootNode() == null
                    ? null : current.getRootNode().getControl(WorldSettings.class);
            GiStreaming values = world == null ? GiStreaming.DEFAULTS : world.getGiStreaming();
            boolean halfResolution = world != null && world.isHalfResolutionProbeGi();
            boolean fallback = world == null || world.isProbeGiFullFallback();
            boolean single = world != null && world.isSingleProbeVisibility();
            boolean oct = world != null && world.isOctahedralProbeIrradiance();
            EventQueue.invokeLater(() -> {
                if (isDisplayable() && current == app.getCurrentSceneRequest()) {
                    scene = current;
                    showSettings(values, halfResolution, fallback, single, oct);
                    setEditable(current != null && current.getDataObject() != null && current.getRootNode() != null);
                }
            });
            return null;
        });
    }

    @Override
    public void addNotify() {
        super.addNotify();
        SceneApplication.getApplication().addSceneListener(this);
        refresh();
    }

    @Override
    public void removeNotify() {
        SceneApplication.getApplication().removeSceneListener(this);
        scene = null;
        super.removeNotify();
    }

    @Override
    public void sceneOpened(SceneRequest request) {
        refresh();
    }

    @Override
    public void sceneClosed(SceneRequest request) {
        refresh();
    }

    @Override
    public void previewCreated(PreviewRequest request) {
    }
}
