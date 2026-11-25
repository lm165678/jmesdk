package com.jme3.gde.core.sceneviewer;

import com.jme3.gde.core.sceneexplorer.nodes.AbstractSceneExplorerNode;
import com.jme3.gde.core.sceneexplorer.nodes.JmeGeometry;
import com.jme3.material.Material;
import com.jme3.scene.Geometry;
import java.util.Map;

/** Drop/undo/redo render-thread commit. Asset Browser focus can suspend the
 * selected explorer node's periodic sync, so synchronize affected cached
 * properties explicitly after installing materials. Property notifications also
 * move the live-material listener from the old asset key to the new one.
 */
final class MaterialDropSupport {
    static void apply(Map<Geometry, Material> materials, AbstractSceneExplorerNode[] observers) {
        materials.forEach(Geometry::setMaterial);
        if (observers == null) {
            return;
        }
        for (AbstractSceneExplorerNode node : observers) {
            if (node instanceof JmeGeometry geometry && materials.containsKey(geometry.getGeometry())) {
                geometry.syncSceneData(0);
            }
        }
    }
}
