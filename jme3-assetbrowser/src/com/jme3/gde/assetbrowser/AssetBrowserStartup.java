package com.jme3.gde.assetbrowser;

/** Migrates existing SDK window layouts once; subsequent user closure is respected. */
@org.openide.windows.OnShowing
public final class AssetBrowserStartup implements Runnable {
    @Override
    public void run() {
        var prefs = org.openide.util.NbPreferences.forModule(AssetBrowserTopComponent.class);
        if (prefs.getBoolean("contentBrowserOpenedV2", false)) {
            return;
        }
        var windows = org.openide.windows.WindowManager.getDefault();
        var panel = windows.findTopComponent("AssetBrowserTopComponent");
        if (panel == null) {
            panel = new AssetBrowserTopComponent();
        }
        var bottom = windows.findMode("output");
        if (bottom != null) {
            bottom.dockInto(panel);
        }
        panel.open();
        panel.requestVisible();
        prefs.putBoolean("contentBrowserOpenedV2", true);
    }
}
