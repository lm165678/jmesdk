package com.jme3.gde.core.util;

import org.openide.util.NbBundle;

/**
 * Editor labels use the SDK locale. Missing translations fall back to English.
 * Restart the SDK after changing the locale or installing translations.
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
