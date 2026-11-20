package com.jme3.gde.scenecomposer;
import javax.swing.*;
import java.lang.reflect.*;
public final class UePostPanelSmoke {
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                EditorPostProcessingPanel panel = new EditorPostProcessingPanel();
                Field field = panel.getClass().getDeclaredField("inputs");
                field.setAccessible(true);
                java.util.Map<String,JComponent> inputs = (java.util.Map<String,JComponent>) field.get(panel);
                if (inputs.get("MinBrightness").isEnabled() || inputs.get("FilmSlope").isEnabled()) {
                    throw new AssertionError("Old scenes should keep old modes");
                }
                ((JCheckBox) inputs.get("UE4Exposure")).setSelected(true);
                ((JCheckBox) inputs.get("UE4Tonemap")).setSelected(true);
                Method update = panel.getClass().getDeclaredMethod("updateModeControls");
                update.setAccessible(true);
                update.invoke(panel);
                if (!inputs.get("MinBrightness").isEnabled() || !inputs.get("FilmSlope").isEnabled()
                        || inputs.get("ExposureMin").isEnabled() || inputs.get("FilmicCurve").isEnabled()) {
                    throw new AssertionError("Mode control enablement mismatch");
                }
                for (String key : new String[]{"UE4Exposure","UE4Tonemap","FilmSlope","GradingGammaRed"}) {
                    if (inputs.get(key).getToolTipText().contains("Post"+key)) {
                        throw new AssertionError("Missing tooltip: "+key);
                    }
                }
                Field refreshing = panel.getClass().getDeclaredField("refreshing");
                refreshing.setAccessible(true);
                refreshing.setBoolean(panel, true);
                Field cardsField = panel.getClass().getDeclaredField("aaCards");
                cardsField.setAccessible(true);
                JPanel cards = (JPanel) cardsField.get(panel);
                JComboBox<?> mode = (JComboBox<?>) inputs.get("AntiAliasing");
                if (mode.getSelectedIndex() != 1) {
                    throw new AssertionError("Default AA changed");
                }
                for (int selected : new int[]{2, 1, 0, 2}) {
                    mode.setSelectedIndex(selected);
                    update.invoke(panel);
                    for (int index = 0; index < 3; index++) {
                        if (cards.getComponent(index).isVisible() != (index == selected)) {
                            throw new AssertionError("Wrong AA parameter panel");
                        }
                    }
                }
                ((JCheckBox) inputs.get("LegacyPipeline")).setSelected(true);
                update.invoke(panel);
                if (mode.isEnabled() || !cards.getComponent(0).isVisible()) {
                    throw new AssertionError("Legacy mode shows inactive AA controls");
                }
                ((JCheckBox) inputs.get("LegacyPipeline")).setSelected(false);
                update.invoke(panel);
                if (!mode.isEnabled() || mode.getSelectedIndex() != 2 || !cards.getComponent(2).isVisible()) {
                    throw new AssertionError("AA mode was lost after legacy comparison");
                }
                System.out.println("UE4_POST_PANEL_PASS");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        System.exit(0);
    }
}
