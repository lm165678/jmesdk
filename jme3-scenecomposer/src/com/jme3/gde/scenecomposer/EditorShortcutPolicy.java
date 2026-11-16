package com.jme3.gde.scenecomposer;

import com.jme3.input.KeyInput;
import com.jme3.input.event.KeyInputEvent;
import java.util.HashSet;
import java.util.Set;

/** Render-thread shortcut gate. Modified keys belong to the host editor. */
final class EditorShortcutPolicy {
    private final Set<Integer> down = new HashSet<>();

    void reset() {
        down.clear();
    }

    int accept(KeyInputEvent event) {
        int code = event.getKeyCode();
        if (event.isReleased()) {
            down.remove(code);
            return 0;
        }
        boolean first = down.add(code);
        if (!first || event.isRepeating() || down.contains(KeyInput.KEY_LCONTROL)
                || down.contains(KeyInput.KEY_RCONTROL) || down.contains(KeyInput.KEY_LMENU)
                || down.contains(KeyInput.KEY_RMENU) || down.contains(KeyInput.KEY_LSHIFT)
                || down.contains(KeyInput.KEY_RSHIFT) || down.contains(KeyInput.KEY_LMETA)
                || down.contains(KeyInput.KEY_RMETA)) {
            return 0;
        }
        return code == KeyInput.KEY_Q || code == KeyInput.KEY_W
                || code == KeyInput.KEY_E || code == KeyInput.KEY_R
                || code == KeyInput.KEY_G ? code : 0;
    }
}
