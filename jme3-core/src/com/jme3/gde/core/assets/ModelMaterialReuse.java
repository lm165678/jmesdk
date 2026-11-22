package com.jme3.gde.core.assets;

import com.jme3.material.Material;
import com.jme3.scene.Geometry;
import com.jme3.scene.Spatial;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Copies saved material assignments to the conversion's private model. */
final class ModelMaterialReuse {
    private ModelMaterialReuse() {
    }

    static void apply(Spatial output, ProjectAssetManager assets, String key) throws IOException {
        if (assets == null || assets.getAssetFolder() == null || !key.toLowerCase(Locale.ROOT).endsWith(".j3o")
                || key.startsWith("/") || key.contains("\\") || key.contains(":")
                || List.of(key.split("/")).contains("..") || assets.getAssetFolder().getFileObject(key) == null) {
            throw new IOException("Choose an existing material source J3O inside the project: " + key);
        }
        Spatial donor = assets.loadModel(key);
        var bySurface = new HashMap<String, List<Material>>();
        var byMaterial = new HashMap<String, List<Material>>();
        var byGeometry = new HashMap<String, List<Material>>();
        donor.depthFirstTraversal(spatial -> {
            if (spatial instanceof Geometry geometry && geometry.getMaterial() != null) {
                Material material = geometry.getMaterial();
                add(bySurface, surfaceKey(geometry, material), material);
                add(byMaterial, material.getName(), material);
                add(byGeometry, geometry.getName(), material);
            }
        });
        List<Geometry> meshes = new ArrayList<>();
        output.depthFirstTraversal(spatial -> {
            if (spatial instanceof Geometry geometry) {
                meshes.add(geometry);
            }
        });
        if (bySurface.isEmpty() || meshes.isEmpty()) {
            throw new IOException("Material reuse requires geometry and material assignments in " + key + " and the source model");
        }
        Map<Material, Material> copies = new IdentityHashMap<>();
        for (Geometry geometry : meshes) {
            Material source = geometry.getMaterial();
            Material match = unique(bySurface.get(surfaceKey(geometry, source)));
            if (match == null && source != null) {
                match = unique(byMaterial.get(source.getName()));
            }
            if (match == null) {
                match = unique(byGeometry.get(geometry.getName()));
            }
            if (match == null) {
                throw new IOException("No unique material for " + geometry.getName() + " in " + key);
            }
            geometry.setMaterial(copies.computeIfAbsent(match, Material::clone));
        }
    }

    private static String surfaceKey(Geometry geometry, Material material) {
        return geometry.getName() + "\n" + (material == null ? "" : material.getName());
    }

    private static void add(Map<String, List<Material>> index, String name, Material material) {
        if (name != null && !name.isBlank()) {
            var candidates = index.computeIfAbsent(name, ignored -> new ArrayList<>());
            if (candidates.stream().noneMatch(material::contentEquals)) {
                candidates.add(material);
            }
        }
    }

    private static Material unique(List<Material> candidates) {
        return candidates != null && candidates.size() == 1 ? candidates.get(0) : null;
    }
}
