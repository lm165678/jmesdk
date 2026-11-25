package com.jme3.gde.core.sceneviewer;

import com.jme3.asset.*;
import com.jme3.export.binary.*;
import com.jme3.gde.core.sceneexplorer.nodes.*;
import com.jme3.gde.core.properties.*;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.scene.*;
import com.jme3.scene.shape.Box;
import java.io.*;
import java.util.*;
import javax.swing.SwingUtilities;

/** Focus-independent selected-property sync for material drops and undo/redo. */
public final class MaterialDropSyncSmoke {
    static void check(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
    public static final class TestLookup extends org.openide.util.lookup.ProxyLookup {
        public TestLookup() {
            super(org.openide.util.lookup.Lookups.fixed(MaterialDropSyncSmoke.class.getClassLoader()),
                    org.openide.util.lookup.Lookups.metaInfServices(MaterialDropSyncSmoke.class.getClassLoader()));
        }
    }
    public static void main(String[] args) throws Exception {
        System.setProperty("org.openide.util.Lookup", TestLookup.class.getName());
        var assets = new DesktopAssetManager(true);
        Material old = new Material(assets, "Common/MatDefs/Misc/Unshaded.j3md");
        old.setKey(new MaterialKey("Materials/SSS.j3m"));
        old.setColor("Color", ColorRGBA.White);
        Material next = old.clone();
        next.setKey(new MaterialKey("Textures/Skin/SkinLut.j3m"));
        next.setColor("Color", ColorRGBA.Red);
        Geometry geometry = new Geometry("Selected", new Box(1, 1, 1));
        geometry.setMaterial(old);
        JmeGeometry node = new JmeGeometry(geometry, new JmeSpatialChildren(geometry));
        SceneExplorerProperty<?> property = null;
        for (var set : node.getPropertySets()) {
            for (var prop : set.getProperties()) {
                if ("Material".equals(prop.getName())) {
                    property = (SceneExplorerProperty<?>) prop;
                }
            }
        }
        check(property != null, "Material property missing");
        node.syncSceneData(0);
        check("Materials/SSS.j3m".equals(((Material) property.getValue()).getAssetName()), "Old property not initialized");
        // No NodeSyncAppState tick: Asset Browser/material editor owns focus.
        MaterialDropSupport.apply(Map.of(geometry, next), new AbstractSceneExplorerNode[]{node});
        check(geometry.getMaterial() == next, "Drop did not replace geometry material");
        check("Textures/Skin/SkinLut.j3m".equals(((Material) property.getValue()).getAssetName()), "Property stayed on old path");
        MaterialDropSupport.apply(Map.of(geometry, old), new AbstractSceneExplorerNode[]{node});
        check("Materials/SSS.j3m".equals(((Material) property.getValue()).getAssetName()), "Undo property stale");
        MaterialDropSupport.apply(Map.of(geometry, next), new AbstractSceneExplorerNode[]{node});
        check("Textures/Skin/SkinLut.j3m".equals(((Material) property.getValue()).getAssetName()), "Redo property stale");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        new BinaryExporter().save(geometry, bytes);
        BinaryImporter importer = new BinaryImporter();
        importer.setAssetManager(assets);
        Geometry restored = (Geometry) importer.load(new ByteArrayInputStream(bytes.toByteArray()));
        // Material serializes embedded parameters and its additional savable key
        // is an engine contract; verify the saved material is the new object data.
        check(ColorRGBA.Red.equals(restored.getMaterial().getParamValue("Color")), "Saved wrong material data");
        SwingUtilities.invokeAndWait(() -> {});
        System.out.println("MATERIAL_DROP_FOCUS_SYNC_UNDO_REDO_PASS");
        System.exit(0);
    }
}
