package com.jme3.gde.scenecomposer;

import com.jme3.gde.core.scene.SceneApplication;
import com.jme3.gde.core.util.EditorMessages;
import com.jme3.gde.core.util.InspectorColumns;
import javax.swing.*;

/** Compact preview controls; listeners enqueue rendering changes and never edit assets. */
final class EditorPostProcessingPanel extends JPanel {
    EditorPostProcessingPanel() {
        super(new java.awt.BorderLayout());
        JPanel stack = new JPanel();
        stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
        JPanel global = new InspectorColumns("scene.postprocessing");
        toggle(global, "Enabled", true);
        stack.add(global);
        JPanel bloom = section(stack, "Bloom");
        toggle(bloom, "Bloom", false);
        number(bloom, "BloomIntensity", 2, 0, 10, 0.1);
        number(bloom, "BloomBlurScale", 1.5, 0, 10, 0.1);
        number(bloom, "BloomExposurePower", 5, 0.01, 20, 0.1);
        number(bloom, "BloomExposureCutOff", 0, 0, 10, 0.01);
        number(bloom, "BloomDownSampling", 1, 1, 8, 1);
        JPanel tonemap = section(stack, "ToneMap");
        toggle(tonemap, "ToneMap", true);
        number(tonemap, "WhitePoint", 11.2, 0.1, 100, 0.1);
        JPanel fxaa = section(stack, "FXAA");
        toggle(fxaa, "FXAA", true);
        number(fxaa, "FXAASubPixelShift", 0.25, 0, 1, 0.01);
        number(fxaa, "FXAASpanMax", 8, 1, 32, 1);
        number(fxaa, "FXAAReduceMul", 0.125, 0.001, 1, 0.01);
        number(fxaa, "FXAAVxOffset", 0, 0, 1, 0.01);
        JPanel ssao = section(stack, "SSAO");
        toggle(ssao, "SSAO", false);
        number(ssao, "SSAORadius", 5.1, 0.001, 100, 0.1);
        number(ssao, "SSAOIntensity", 1.5, 0, 10, 0.1);
        number(ssao, "SSAOScale", 0.2, 0.001, 10, 0.01);
        number(ssao, "SSAOBias", 0.1, 0, 1, 0.01);
        toggle(ssao, "SSAOApproximateNormals", false);
        toggle(ssao, "SSAOOnly", false);
        JPanel fog = section(stack, "Fog");
        toggle(fog, "Fog", false);
        number(fog, "FogDensity", 0.7, 0, 5, 0.01);
        number(fog, "FogDistance", 1000, 0.01, 100000, 10);
        number(fog, "FogRed", 1, 0, 1, 0.01);
        number(fog, "FogGreen", 1, 0, 1, 0.01);
        number(fog, "FogBlue", 1, 0, 1, 0.01);
        JPanel depthoffield = section(stack, "DepthOfField");
        toggle(depthoffield, "DepthOfField", false);
        number(depthoffield, "FocusDistance", 50, 0, 100000, 1);
        number(depthoffield, "FocusRange", 10, 0.001, 100000, 1);
        number(depthoffield, "DofBlurScale", 1, 0, 10, 0.1);
        number(depthoffield, "DofBlurThreshold", 0.2, 0, 1, 0.01);
        toggle(depthoffield, "DofDebug", false);
        add(stack, java.awt.BorderLayout.NORTH);
    }

    private JPanel section(JPanel stack, String key) {
        JPanel group = new JPanel(new java.awt.BorderLayout(0, 3)) {
            @Override
            public java.awt.Dimension getMaximumSize() {
                return new java.awt.Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        JPanel rows = new InspectorColumns("scene.postprocessing." + key);
        rows.setVisible(false);
        String title = EditorMessages.text("Post" + key);
        JToggleButton header = new JToggleButton("▸  " + title);
        header.setHorizontalAlignment(SwingConstants.LEFT);
        header.setMargin(new java.awt.Insets(2, 6, 2, 6));
        header.setToolTipText(EditorMessages.text("Post" + key + "Tip"));
        header.addActionListener(event -> {
            rows.setVisible(header.isSelected());
            header.setText((header.isSelected() ? "▾  " : "▸  ") + title);
            java.awt.Container parent = group;
            while (parent != null && !(parent instanceof JScrollPane)) {
                parent.setMinimumSize(new java.awt.Dimension(0, parent.getPreferredSize().height));
                parent.revalidate();
                parent = parent.getParent();
            }
            stack.repaint();
        });
        group.add(header, java.awt.BorderLayout.NORTH);
        group.add(rows, java.awt.BorderLayout.CENTER);
        stack.add(group);
        return rows;
    }

    private void toggle(JPanel rows, String key, boolean initial) {
        JCheckBox input = new JCheckBox();
        input.setSelected(initial);
        input.addActionListener(event -> send(key, input.isSelected() ? 1 : 0));
        row(rows, key, input);
        if (java.util.Set.of("Bloom", "ToneMap", "FXAA", "SSAO", "Fog", "DepthOfField").contains(key)) {
            ((JLabel) rows.getComponent(rows.getComponentCount() - 2)).setText(EditorMessages.text("PostEffectEnabled"));
        }
    }

    private void number(JPanel rows, String key, double initial, double min, double max, double step) {
        JSpinner input = new JSpinner(new SpinnerNumberModel(initial, min, max, step));
        input.addChangeListener(event -> send(key, ((Number) input.getValue()).doubleValue()));
        row(rows, key, input);
    }

    private void row(JPanel rows, String key, JComponent input) {
        String label = EditorMessages.text("Post" + key);
        String tip = EditorMessages.text("Post" + key + "Tip");
        JLabel name = new JLabel(label);
        name.setToolTipText(tip);
        input.setToolTipText(tip);
        name.setLabelFor(input);
        rows.add(name);
        rows.add(input);
    }

    private void send(String key, double value) {
        SceneApplication.getApplication().configureEditorPostProcessing(key, value);
    }
}
