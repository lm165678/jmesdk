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
                System.out.println("UE4_POST_PANEL_PASS");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        System.exit(0);
    }
}
