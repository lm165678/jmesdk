package com.jme3.gde.autodesk;

import com.jme3.asset.AssetManager;
import com.jme3.gde.core.assets.AssetManagerConfigurator;
import org.openide.util.lookup.ServiceProvider;

/** Selects the SDK FBX loader for every project asset manager. */
@ServiceProvider(service = AssetManagerConfigurator.class)
public final class UfbxAssetManagerConfigurator implements AssetManagerConfigurator {
    @Override
    public void prepareManager(AssetManager manager) {
        if (!"legacy".equalsIgnoreCase(System.getProperty("jme.sdk.fbx.backend"))) {
            manager.registerLoader(UfbxLoader.class, "fbx", "FBX");
        }
    }
}
