package com.jme3.gde.scenecomposer;

import com.jme3.gde.core.scene.PreviewRequest;
import com.jme3.gde.core.scene.SceneApplication;
import com.jme3.gde.core.scene.SceneListener;
import com.jme3.gde.core.scene.SceneRequest;
import com.jme3.gde.core.util.InspectorColumns;
import com.jme3.post.WorldSettings;
import com.jme3.renderer.gpuscene.GpuScenePipeline;
import java.awt.EventQueue;
import java.util.Arrays;
import java.util.StringJoiner;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;

/**
 * 第一步：在 SceneComposer 的 GPU Scene LOD 填写由大到小的像素直径。
 * 第二步：点击 Apply 预览并保存 J3O。游戏挂载场景后自动读取阈值。
 * 第三步：点击 Use defaults 恢复 80、30、10、3，无需再次生成网格。
 */
final class EditorGpuLodPanel extends JPanel implements SceneListener {
    private final JTextField pixels = new JTextField(16);
    private final JButton apply = new JButton("Apply");
    private final JButton defaults = new JButton("Use defaults");
    private SceneRequest scene;

    EditorGpuLodPanel() {
        add(new JLabel("Screen diameter (px)"));
        add(pixels);
        add(defaults);
        add(apply);
        pixels.setToolTipText("Up to seven positive, decreasing values. Separate them with commas.");
        InspectorColumns.install(this, "scene.gpuLod");
        apply.addActionListener(event -> applyText());
        pixels.addActionListener(event -> applyText());
        defaults.addActionListener(event -> apply(new float[0]));
        showSettings(new float[0], false);
    }

    private void applyText() {
        try {
            String input = pixels.getText().trim();
            String[] parts = input.isEmpty() ? new String[0] : input.split(",", -1);
            float[] values = new float[parts.length];
            for (int i = 0; i < parts.length; i++) {
                values[i] = Float.parseFloat(parts[i].trim());
            }
            GpuScenePipeline.validateLodThresholds(values);
            apply(values);
        } catch (IllegalArgumentException invalid) {
            JOptionPane.showMessageDialog(this,
                    "Enter up to seven positive pixel diameters in decreasing order (e.g. 80, 30, 10, 3).",
                    "GPU Scene LOD", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void apply(float[] values) {
        SceneRequest request = scene;
        if (request == null) {
            return;
        }
        SceneApplication app = SceneApplication.getApplication();
        app.enqueue(() -> {
            if (app.getCurrentSceneRequest() != request) {
                return null;
            }
            WorldSettings world = request.getRootNode().getControl(WorldSettings.class);
            boolean changed = world == null ? values.length != 0
                    : !Arrays.equals(values, world.getGpuLodThresholds());
            if (changed) {
                if (world == null) {
                    world = new WorldSettings();
                    world.setDisplayOutput(WorldSettings.DisplayOutput.Application);
                    request.getRootNode().addControl(world);
                }
                world.setGpuLodThresholds(values);
            }
            EventQueue.invokeLater(() -> {
                if (changed) {
                    request.getDataObject().setModified(true);
                }
                if (scene == request) {
                    showSettings(values, true);
                }
            });
            return null;
        });
    }

    private void showSettings(float[] values, boolean editable) {
        StringJoiner text = new StringJoiner(", ");
        for (float value : values.length == 0 ? GpuScenePipeline.getDefaultWorldLodThresholds() : values) {
            String number = Float.toString(value);
            text.add(number.endsWith(".0") ? number.substring(0, number.length() - 2) : number);
        }
        pixels.setText(text.toString());
        pixels.setEnabled(editable);
        apply.setEnabled(editable);
        defaults.setEnabled(editable);
    }

    private void refresh() {
        SceneApplication app = SceneApplication.getApplication();
        app.enqueue(() -> {
            SceneRequest request = app.getCurrentSceneRequest();
            boolean editable = request != null && request.getRootNode() != null && request.getDataObject() != null;
            WorldSettings world = editable ? request.getRootNode().getControl(WorldSettings.class) : null;
            float[] values = world == null ? new float[0] : world.getGpuLodThresholds();
            EventQueue.invokeLater(() -> {
                if (isDisplayable() && request == app.getCurrentSceneRequest()) {
                    scene = editable ? request : null;
                    showSettings(values, editable);
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
