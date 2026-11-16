package com.jme3.gde.core.util;

import org.openide.util.NbBundle;

/**
 * SDK-locale editor labels. English-only SDK installations use the same English
 * fallback; translated resource packs follow the SDK locale after restart.
 */
public final class EditorMessages {
    private EditorMessages() {
    }
    public static String text(String key) {
        // Use the same bundle lookup as existing SDK controls. Do not load the
        // obsolete EditorLabels bundles retained by older incremental JAR builds.
        return NbBundle.getMessage(EditorMessages.class, "Editor." + key);
    }
}
