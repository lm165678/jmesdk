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
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

/**
 * 第一步：在 SceneComposer 的 World Settings / GI streaming 调整场景预算。
 * 第二步：应用；值为 0 的细探针预算只预览粗层，恢复默认会重新请求细块。
 * 第三步：保存场景 J3O；游戏挂载场景后应用同一组配置。
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
    private SceneRequest scene;

    EditorGiStreamingPanel() {
        add(new JLabel(EditorMessages.text("GiStreamingDistance")));
        add(distance);
        add(new JLabel(EditorMessages.text("GiStreamingFineProbes")));
        add(fine);
        add(new JLabel(EditorMessages.text("GiStreamingAtlas")));
        add(atlas);
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
        apply.setEnabled(editable);
        defaults.setEnabled(editable);
    }

    private void showSettings(GiStreaming settings) {
        distance.setValue((double) settings.getPreloadDistance());
        fine.setValue(settings.getMaxFineProbes());
        atlas.setValue(settings.getAtlasMiB());
    }

    private void apply(GiStreaming values) {
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
            EventQueue.invokeLater(() -> {
                if (isDisplayable() && current == app.getCurrentSceneRequest()) {
                    scene = current;
                    showSettings(values);
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
