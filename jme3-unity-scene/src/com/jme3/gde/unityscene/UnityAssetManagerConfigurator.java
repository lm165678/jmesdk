package com.jme3.gde.unityscene;

import com.jme3.asset.AssetManager;
import com.jme3.gde.core.assets.AssetManagerConfigurator;
import org.openide.util.lookup.ServiceProvider;

/** Registers the Unity loader without modifying or being referenced by jme3-core. */
@ServiceProvider(service = AssetManagerConfigurator.class)
public class UnityAssetManagerConfigurator implements AssetManagerConfigurator {
    @Override
    public void prepareManager(AssetManager manager) {
        manager.registerLoader(UnitySceneLoader.class, "unity", "prefab");
    }
}
