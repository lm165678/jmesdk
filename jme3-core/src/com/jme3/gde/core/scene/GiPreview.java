package com.jme3.gde.core.scene;

import com.jme3.environment.gi.*;
import com.jme3.material.Material;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Limits;
import com.jme3.scene.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

/** Camera-driven editor preview using the engine page streamer. Embedded J3O
 * data is borrowed, never disposed or serialized with temporary bindings.
 * CPU source data already embedded in a scene remains resident; paging bounds
 * GPU preview residency, not the memory of the opened scene. Binding lifetime
 * is restricted to drawing so save/undo sees original materials.
 */
final class GiPreview implements AutoCloseable {
    private record Source(String id, GiProbeData data, String asset, Vector3f offset) { }
    private final Map<GiProbeVolume, Source> sources = new IdentityHashMap<>();
    private GiProbeStreamer stream;
    private ExecutorService io;
    private long nextId;
    private volatile String status = "";

    GiProbeData getProbeData(GiProbeVolume volume) {
        GiProbeData embedded = volume.getData();
        if (embedded != null) {
            return embedded;
        }
        Source source = sources.get(volume);
        if (stream == null || source == null || !Objects.equals(source.asset(), volume.getStreamAsset())) {
            return null;
        }
        return stream.getResidentData(source.id());
    }

    String getStatus() {
        return status;
    }

    void render(SceneApplication app, Runnable draw) {
        SceneRequest request = app.getCurrentSceneRequest();
        Spatial root = request == null ? null : request.getRootNode();
        if (root == null || !app.isGiPreviewEnabled()) {
            close();
            status = "GI preview disabled";
            draw.run();
            return;
        }
        List<Material> bound = new ArrayList<>();
        try {
            if (stream == null) {
                io = Executors.newFixedThreadPool(2, runnable -> {
                    Thread thread = new Thread(runnable, "SDK GI streaming IO");
                    thread.setDaemon(true);
                    return thread;
                });
                stream = new GiProbeStreamer(io, 256L << 20,
                        app.getRenderer().getLimits().get(Limits.TextureSize), 200, app.getRenderer());
                stream.setUploadBudget(1L << 20, 2_000_000);
                stream.setUnloadMultiplier(1.3);
            }
            Set<GiProbeVolume> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            Set<Material> materials = Collections.newSetFromMap(new IdentityHashMap<>());
            root.depthFirstTraversal(spatial -> {
                if (spatial instanceof GiProbeVolume volume && (volume.getData() != null || volume.getStreamAsset() != null)) {
                    seen.add(volume);
                    GiProbeData data = volume.getData();
                    String external = volume.getStreamAsset();
                    Vector3f offset = volume.getWorldTranslation();
                    Source old = sources.get(volume);
                    if (old == null || old.data() != data || !Objects.equals(old.asset(), external) || !old.offset().equals(offset)) {
                        if (old != null) {
                            stream.unregister(old.id());
                        }
                        String id = "volume-" + nextId++;
                        GiProbeGrid grid = data == null ? volume.getBakeGrid() : data.getGrid();
                        Vector3f size = grid.getCounts().subtract(1, 1, 1).multLocal(grid.getSpacing());
                        if (external == null) {
                            stream.register(GiProbeStreamer.Chunk.borrowed(id, offset.x, offset.y, offset.z, size, data));
                        } else {
                            var manager = request.getManager();
                            stream.register(new GiProbeStreamer.Chunk(id, offset.x, offset.y, offset.z, size,
                                    volume.getStreamBytes(), () -> {
                                        var info = manager.locateAsset(new com.jme3.asset.AssetKey<>(external));
                                        if (info == null) {
                                            throw new java.io.FileNotFoundException(external);
                                        }
                                        try (var input = info.openStream()) {
                                            return GiProbeData.loadCompressed(input);
                                        }
                                    }));
                        }
                        sources.put(volume, new Source(id, data, external, offset.clone()));
                    }
                    stream.setAppearance(sources.get(volume).id(), volume.getIntensity(), volume.getFadeDistance(), volume.isStabilizedBlending(), volume.isSmoothBlending(), volume.isWideProbeSampling(), volume.isCurvedReceiverCorrection());
                }
                if (spatial instanceof Geometry geometry && geometry.getMaterial() != null) {
                    materials.add(geometry.getMaterial());
                }
            });
            Iterator<Map.Entry<GiProbeVolume, Source>> iterator = sources.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<GiProbeVolume, Source> entry = iterator.next();
                if (!seen.contains(entry.getKey())) {
                    stream.unregister(entry.getValue().id());
                    iterator.remove();
                }
            }
            stream.update(app.getCamera().getLocation(), 0, 0, 0);
            for (Material material : materials) {
                if (supportsGi(material)) {
                    stream.addMaterial(material);
                    bound.add(material);
                }
            }
            status = "GI: " + stream.getPublishedCount() + "/" + sources.size()
                    + " volumes ready, " + stream.getPendingCount() + " loads, "
                    + stream.getAtlasBytes() / (1024 * 1024) + " MiB page pool";
            if (!stream.getPageAdmissionFailures().isEmpty()) {
                status += "; page capacity/width exceeded: " + stream.getPageAdmissionFailures();
            }
            if (!stream.getFailures().isEmpty()) {
                status += "; load failed: " + stream.getFailures().keySet();
            }
        } catch (RuntimeException error) {
            status = "GI preview failed: " + error.getMessage();
            Logger.getLogger(GiPreview.class.getName()).log(Level.WARNING, status, error);
        }
        try {
            draw.run();
        } finally {
            if (stream != null) {
                for (Material material : bound) {
                    stream.removeMaterial(material);
                }
            }
        }
    }

    private boolean supportsGi(Material material) {
        for (String parameter : new String[]{"GiIrradiance", "GiMoments", "GiVolumeData",
                "GiVolumeCount", "GiTextureSize", "GiBias", "GiEnabled", "GiSkyVisibility"}) {
            if (material.getMaterialDef().getMaterialParam(parameter) == null) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void close() {
        if (stream != null) {
            stream.close();
            stream = null;
        }
        if (io != null) {
            io.shutdownNow();
            io = null;
        }
        sources.clear();
        status = "";
    }
}
