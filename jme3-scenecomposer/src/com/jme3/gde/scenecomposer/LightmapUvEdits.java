package com.jme3.gde.scenecomposer;

import com.jme3.environment.gi.GiProbeVolume;
import com.jme3.environment.gi.ProbeLightmapControl;
import com.jme3.gde.core.assets.LightmapUvGenerator;
import com.jme3.scene.*;
import com.jme3.scene.VertexBuffer.Type;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Private snapshots for a single, reversible UV edit of a selected subtree. */
final class LightmapUvEdits {
    private record Item(Geometry geometry, Mesh source, Mesh snapshot, int lod,
            ProbeLightmapControl lightmap, Object resolution, Object padding, Object border) {
    }

    private final Spatial root;
    private final List<Item> items = new ArrayList<>();
    private final Map<Geometry, LightmapUvGenerator.BakeLayout> results = new LinkedHashMap<>();
    private LightmapUvGenerator.Settings settings;

    LightmapUvEdits(Spatial root) {
        this.root = root;
        for (Geometry geometry : receivers(root)) {
            items.add(new Item(geometry, geometry.getMesh(), geometry.getMesh().deepClone(),
                    geometry.getLodLevel(), geometry.getControl(ProbeLightmapControl.class),
                    geometry.getUserData(LightmapUvGenerator.RESOLUTION_KEY),
                    geometry.getUserData(LightmapUvGenerator.PADDING_KEY),
                    geometry.getUserData(LightmapUvGenerator.BORDER_KEY)));
        }
        if (items.isEmpty()) {
            throw new IllegalArgumentException("No editable triangle geometry in the selected subtree");
        }
    }

    static List<Geometry> receivers(Spatial root) {
        List<Geometry> result = new ArrayList<>();
        collect(root, result);
        return result;
    }

    private static void collect(Spatial spatial, List<Geometry> result) {
        if (LightmapUvGenerator.isDeformable(spatial)
                || spatial instanceof GiProbeVolume || spatial instanceof com.jme3.audio.AudioNode
                || spatial instanceof com.jme3.effect.ParticleEmitter
                || spatial.getQueueBucket() == com.jme3.renderer.queue.RenderQueue.Bucket.Sky) {
            return;
        }
        if (spatial instanceof Geometry geometry && geometry.getMesh() != null
                && geometry.getMesh().getMode() == Mesh.Mode.Triangles
                && geometry.getMesh().getTriangleCount() > 0) {
            result.add(geometry);
        } else if (spatial instanceof Node node) {
            for (Spatial child : node.getChildren()) {
                collect(child, result);
            }
        }
    }

    /** Worker-only generation; no original Geometry or Mesh is edited. */
    void build(LightmapUvGenerator.Settings requested, int maximum, BooleanSupplier cancelled) throws Exception {
        settings = requested;
        IdentityHashMap<Mesh, LightmapUvGenerator.BakeLayout> shared = new IdentityHashMap<>();
        for (Item item : items) {
            if (cancelled.getAsBoolean()) {
                throw new CancellationException("UV edit cancelled");
            }
            var result = shared.get(item.source());
            if (result == null) {
                if (requested == null) {
                    Mesh mesh = item.snapshot().deepClone();
                    mesh.clearBuffer(Type.TexCoord2);
                    result = new LightmapUvGenerator.BakeLayout(mesh, 0);
                } else {
                    result = LightmapUvGenerator.generateForBake(item.snapshot(), requested, maximum);
                }
                shared.put(item.source(), result);
            }
            results.put(item.geometry(), result);
        }
    }

    /** Render-thread stale-result check, ignoring animated output position/normal buffers. */
    void validate() {
        if (!receivers(root).equals(items.stream().map(Item::geometry).toList())) {
            throw new CancellationException("UV receiver subtree changed");
        }
        for (Item item : items) {
            if (item.geometry().getMesh() != item.source() || !sameInput(item.source(), item.snapshot())) {
                throw new CancellationException("Mesh changed during UV generation: " + item.geometry().getName());
            }
        }
    }

    private static boolean sameInput(Mesh source, Mesh snapshot) {
        for (var buffer : source.getBufferList()) {
            Type type = buffer.getBufferType();
            if (type == Type.HWBoneIndex || type == Type.HWBoneWeight
                    || source.isAnimated() && (type == Type.Position || type == Type.Normal || type == Type.Tangent)
                    && source.getBuffer(Type.BindPosePosition) != null) {
                continue;
            }
            var previous = snapshot.getBuffer(type);
            if (previous == null || buffer.getNumComponents() != previous.getNumComponents()
                    || buffer.getFormat() != previous.getFormat()
                    || !Objects.equals(buffer.getDataReadOnly(), previous.getDataReadOnly())) {
                return false;
            }
        }
        for (var buffer : snapshot.getBufferList()) {
            Type type = buffer.getBufferType();
            if (type != Type.HWBoneIndex && type != Type.HWBoneWeight && source.getBuffer(type) == null) {
                return false;
            }
        }
        return true;
    }

    void redo() {
        for (Item item : items) {
            Geometry geometry = item.geometry();
            geometry.removeControl(ProbeLightmapControl.class);
            geometry.setResidentProbeLightmap(null);
            geometry.setMesh(results.get(geometry).mesh());
            if (geometry.getMesh().getNumLodLevels() > 0) {
                geometry.setLodLevel(0);
            }
            geometry.setUserData(LightmapUvGenerator.RESOLUTION_KEY,
                    settings == null ? null : results.get(geometry).resolution());
            geometry.setUserData(LightmapUvGenerator.PADDING_KEY, settings == null ? null : settings.padding());
            geometry.setUserData(LightmapUvGenerator.BORDER_KEY, settings == null ? null : settings.border());
            geometry.updateModelBound();
        }
    }

    void undo() {
        for (Item item : items) {
            Geometry geometry = item.geometry();
            geometry.setResidentProbeLightmap(null);
            geometry.setMesh(item.source());
            if (item.source().getNumLodLevels() > 0) {
                geometry.setLodLevel(item.lod());
            }
            geometry.setUserData(LightmapUvGenerator.RESOLUTION_KEY, item.resolution());
            geometry.setUserData(LightmapUvGenerator.PADDING_KEY, item.padding());
            geometry.setUserData(LightmapUvGenerator.BORDER_KEY, item.border());
            if (item.lightmap() != null) {
                geometry.addControl(item.lightmap());
            }
            geometry.updateModelBound();
        }
    }

    int size() {
        return items.size();
    }
}
