package com.jme3.gde.core.sceneviewer;

import com.jme3.gde.core.scene.SceneApplication;
import com.jme3.gde.core.scene.SceneApplication.ProbeDisplayMode;
import com.jme3.gde.core.util.EditorMessages;
import java.util.EnumMap;
import java.util.Map;
import javax.swing.ButtonGroup;
import javax.swing.JMenu;
import javax.swing.JRadioButtonMenuItem;

/** EDT-owned, mutually exclusive probe diagnostics. The scene is never modified. */
final class ProbeDisplayMenu extends JMenu {
    private final SceneApplication app;
    private final Map<ProbeDisplayMode, JRadioButtonMenuItem> items = new EnumMap<>(ProbeDisplayMode.class);

    ProbeDisplayMenu(SceneApplication app) {
        super(EditorMessages.text("GiProbeDisplay"));
        this.app = app;
        setToolTipText(EditorMessages.text("GiProbeDisplayTip"));
        ButtonGroup group = new ButtonGroup();
        for (ProbeDisplayMode mode : ProbeDisplayMode.values()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(
                    EditorMessages.text("GiProbeDisplay" + mode.name()));
            item.setToolTipText(EditorMessages.text("GiProbeDisplayTip" + mode.name()));
            item.addActionListener(event -> app.setProbeDisplayMode(mode));
            group.add(item);
            add(item);
            items.put(mode, item);
        }
        refreshSelection();
    }

    void refreshSelection() {
        items.get(app.getProbeDisplayMode()).setSelected(true);
    }
}
