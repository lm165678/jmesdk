package com.jme3.gde.scenecomposer;

import com.jme3.gde.core.scene.SceneApplication;
import com.jme3.gde.core.util.EditorMessages;
import com.jme3.gde.core.util.InspectorColumns;
import javax.swing.*;

/** Scene settings controls; updates are serialized with the scene root WorldSettings. */
final class EditorPostProcessingPanel extends JPanel implements com.jme3.gde.core.scene.SceneListener {
    private final java.util.Map<String, JComponent> inputs = new java.util.LinkedHashMap<>();
    private final java.util.Map<String, Double> defaults = new java.util.LinkedHashMap<>();
    private boolean refreshing;
    private final JPanel aaCards = new JPanel(new java.awt.CardLayout()) {
        @Override
        public java.awt.Dimension getPreferredSize() {
            for (java.awt.Component child : getComponents()) {
                if (child.isVisible()) {
                    return child.getPreferredSize();
                }
            }
            return new java.awt.Dimension();
        }
    };
    EditorPostProcessingPanel() {
        super(new java.awt.BorderLayout());
        JPanel stack = new JPanel();
        stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
        JPanel global = new InspectorColumns("scene.postprocessing");
        toggle(global, "Enabled", true);
        toggle(global, "LegacyPipeline", false);
        stack.add(global);
        JPanel exposure = section(stack, "Exposure");
        toggle(exposure, "AutomaticExposure", true);
        toggle(exposure, "UE4Exposure", false);
        number(exposure, "MinBrightness", .03, .00001, 65504, .01);
        number(exposure, "MaxBrightness", 2, .00001, 65504, .1);
        number(exposure, "HistogramMin", -8, -16, 23, .25);
        number(exposure, "HistogramMax", 4, -15.99, 24, .25);
        number(exposure, "LowPercent", .1, 0, .98, .01);
        number(exposure, "HighPercent", .9, .01, 1, .01);
        number(exposure, "TransitionDistance", 1.5, .1, 10, .1);
        JButton resetExposure = new JButton(EditorMessages.text("PostResetExposure"));
        resetExposure.addActionListener(event -> send("ResetExposure", 1));
        row(exposure, "ResetExposure", resetExposure);
        number(exposure, "ExposureCompensation", 0, -16, 16, 0.25);
        number(exposure, "ExposureMin", -8, -16, 16, 0.25);
        number(exposure, "ExposureMax", 8, -16, 16, 0.25);
        number(exposure, "ExposureSpeedUp", 3, 0.01, 100, 0.1);
        number(exposure, "ExposureSpeedDown", 1, 0.01, 100, 0.1);
        JPanel bloom = section(stack, "Bloom");
        toggle(bloom, "Bloom", true);
        number(bloom, "BloomIntensity", 0.08, 0, 10, 0.01);
        number(bloom, "BloomThreshold", 1, 0, 100, 0.1);
        number(bloom, "BloomSoftKnee", 0.5, 0, 1, 0.05);
        number(bloom, "BloomScatter", 0.7, 0, 1, 0.05);
        JPanel tonemap = section(stack, "ToneMap");
        toggle(tonemap, "ToneMap", true);
        toggle(tonemap, "FilmicCurve", false);
        toggle(tonemap, "UE4Tonemap", false);
        number(tonemap, "FilmSlope", .88, .05, 1, .01);
        number(tonemap, "FilmToe", .55, 0, .95, .01);
        number(tonemap, "FilmShoulder", .26, 0, .95, .01);
        number(tonemap, "FilmBlackClip", 0, 0, .2, .01);
        number(tonemap, "FilmWhiteClip", .04, 0, .2, .01);
        number(tonemap, "Temperature", 6500, 2000, 15000, 100);
        number(tonemap, "Tint", 0, -1, 1, .01);
        number(tonemap, "BlueCorrection", .6, 0, 1, .05);
        number(tonemap, "ExpandGamut", 1, 0, 1, .05);
        JPanel grading = section(stack, "CombineLUTs");
        for (String channel : new String[]{"Red", "Green", "Blue"}) {
            number(grading, "GradingGamma" + channel, 1, .1, 4, .05);
            number(grading, "GradingOffset" + channel, 0, -1, 1, .01);
            number(grading, "ShadowGain" + channel, 1, 0, 4, .05);
            number(grading, "MidtoneGain" + channel, 1, 0, 4, .05);
            number(grading, "HighlightGain" + channel, 1, 0, 4, .05);
        }
        number(tonemap, "WhitePoint", 11.2, 1, 100, 0.1);
        number(tonemap, "Saturation", 1, 0, 2, 0.05);
        number(tonemap, "Contrast", 1, 0.25, 2, 0.05);
        number(tonemap, "GainRed", 1, 0, 4, 0.05);
        number(tonemap, "GainGreen", 1, 0, 4, 0.05);
        number(tonemap, "GainBlue", 1, 0, 4, 0.05);
        JPanel aa = section(stack, "AntiAliasing");
        JComboBox<String> mode = new JComboBox<>(new String[]{
            EditorMessages.text("PostAAOff"), "FXAA", "TAA"});
        mode.setSelectedIndex(1);
        inputs.put("AntiAliasing", mode);
        defaults.put("AntiAliasing", 1d);
        row(aa, "AntiAliasing", mode);
        mode.addActionListener(event -> send("AntiAliasing", mode.getSelectedIndex()));
        JPanel aaBody = new JPanel(new java.awt.BorderLayout());
        JPanel group = (JPanel) aa.getParent();
        group.remove(aa);
        aaBody.add(aa, java.awt.BorderLayout.NORTH);
        aaBody.add(aaCards, java.awt.BorderLayout.CENTER);
        aaBody.setVisible(aa.isVisible());
        aa.putClientProperty("sectionBody", aaBody);
        group.add(aaBody, java.awt.BorderLayout.CENTER);
        aaCards.add(new JPanel(), "0");
        JPanel fxaa = new InspectorColumns("scene.postprocessing.FXAA");
        aaCards.add(fxaa, "1");
        JPanel taa = new InspectorColumns("scene.postprocessing.TAA");
        aaCards.add(taa, "2");
        number(taa, "TAACurrentWeight", .1, .01, 1, .01);
        toggle(taa, "TAABaseVelocity", true);
        toggle(taa, "TAATransparentVelocity", true);
        number(taa, "TAAAlphaThreshold", .5, 0, 1, .05);
        number(fxaa, "FXAASubPixelShift", 0.25, 0, 1, 0.01);
        number(fxaa, "FXAASpanMax", 8, 1, 32, 1);
        number(fxaa, "FXAAReduceMul", 0.125, 0.001, 1, 0.01);
        number(fxaa, "FXAAVxOffset", 0, 0, 1, 0.01);
        JPanel ssao = section(stack, "SSAO");
        toggle(ssao, "SSAO", false);
        number(ssao, "SSAORadius", 1, 0.001, 100, 0.1);
        number(ssao, "SSAOIntensity", 0.7, 0, 4, 0.1);
        number(ssao, "SSAOBias", 0.1, 0, 0.9, 0.01);
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
        updateModeControls();
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
            JComponent body = (JComponent) rows.getClientProperty("sectionBody");
            (body == null ? rows : body).setVisible(header.isSelected());
            if (body != null) {
                rows.setVisible(true);
            }
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
        inputs.put(key, input);
        defaults.put(key, initial ? 1d : 0d);
        input.addActionListener(event -> send(key, input.isSelected() ? 1 : 0));
        row(rows, key, input);
        if (java.util.Set.of("Bloom", "ToneMap", "FXAA", "SSAO", "Fog", "DepthOfField").contains(key)) {
            ((JLabel) rows.getComponent(rows.getComponentCount() - 2)).setText(EditorMessages.text("PostEffectEnabled"));
        }
    }

    private void number(JPanel rows, String key, double initial, double min, double max, double step) {
        JSpinner input = new JSpinner(new SpinnerNumberModel(initial, min, max, step));
        inputs.put(key, input);
        defaults.put(key, initial);
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

    private void updateModeControls() {
        if (!inputs.containsKey("UE4Tonemap")) {
            return;
        }
        int aaMode = ((JComboBox<?>) inputs.get("AntiAliasing")).getSelectedIndex();
        boolean legacy = ((JCheckBox) inputs.get("LegacyPipeline")).isSelected();
        inputs.get("AntiAliasing").setEnabled(!legacy);
        ((java.awt.CardLayout) aaCards.getLayout()).show(aaCards, Integer.toString(legacy ? 0 : aaMode));
        java.awt.Container parent = aaCards;
        while (parent != null && !(parent instanceof JScrollPane)) {
            parent.setMinimumSize(new java.awt.Dimension(0, parent.getPreferredSize().height));
            parent.revalidate();
            parent = parent.getParent();
        }
        aaCards.repaint();
        boolean exposure = ((JCheckBox) inputs.get("UE4Exposure")).isSelected();
        boolean film = ((JCheckBox) inputs.get("UE4Tonemap")).isSelected();
        for (var entry : inputs.entrySet()) {
            String key = entry.getKey();
            if (java.util.Set.of("MinBrightness", "MaxBrightness", "HistogramMin", "HistogramMax", "TransitionDistance").contains(key)) {
                entry.getValue().setEnabled(exposure);
            } else if (key.equals("ExposureMin") || key.equals("ExposureMax")) {
                entry.getValue().setEnabled(!exposure);
            } else if (key.equals("FilmicCurve") || key.equals("WhitePoint")) {
                entry.getValue().setEnabled(!film);
            } else if (key.startsWith("Film") || key.equals("Temperature") || key.equals("Tint") || key.equals("BlueCorrection") || key.equals("ExpandGamut")
                    || key.startsWith("Grading") || key.startsWith("ShadowGain") || key.startsWith("MidtoneGain") || key.startsWith("HighlightGain")) {
                entry.getValue().setEnabled(film);
            }
        }
    }

    private void send(String key, double value) {
        if (!refreshing) {
            SceneApplication.getApplication().configureEditorPostProcessing(key, value);
            updateModeControls();
        }
    }
    private void refreshSettings() {
        SceneApplication.getApplication().readEditorPostProcessing(values -> {
            if (!isDisplayable()) {
                return;
            }
            refreshing = true;
            try {
                inputs.forEach((key, input) -> {
                    double value = values.getOrDefault(key, defaults.get(key));
                    if (input instanceof JCheckBox box) {
                        box.setSelected(value != 0);
                    } else if (input instanceof JComboBox<?> combo) {
                        combo.setSelectedIndex((int) values.getOrDefault(key,
                                values.getOrDefault("FXAA", 1d) != 0 ? 1d : 0d).doubleValue());
                    } else if (input instanceof JSpinner spinner) {
                        spinner.setValue(value);
                    }
                });
                updateModeControls();
            } finally {
                refreshing = false;
            }
        });
    }

    @Override
    public void addNotify() {
        super.addNotify();
        SceneApplication.getApplication().addSceneListener(this);
        refreshSettings();
    }

    @Override
    public void removeNotify() {
        SceneApplication.getApplication().removeSceneListener(this);
        super.removeNotify();
    }

    @Override
    public void sceneOpened(com.jme3.gde.core.scene.SceneRequest request) {
        refreshSettings();
    }

    @Override
    public void sceneClosed(com.jme3.gde.core.scene.SceneRequest request) {
    }

    @Override
    public void previewCreated(com.jme3.gde.core.scene.PreviewRequest request) {
    }
}
