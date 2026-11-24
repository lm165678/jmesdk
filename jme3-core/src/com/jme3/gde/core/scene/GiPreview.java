package com.jme3.gde.core.scene;

import com.jme3.environment.gi.*;
import com.jme3.material.Material;
import com.jme3.math.Vector3f;
import com.jme3.post.WorldSettings;
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
    private final Set<com.jme3.environment.gi.ProbeLightmapControl> lightmaps =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private record Source(String id, GiProbeData data, String asset,
            GiProbeStreamingData bricks, Vector3f offset) { }
    private final Map<GiProbeVolume, Source> sources = new IdentityHashMap<>();
    private GiProbeStreamer stream;
    private ExecutorService io;
    private long nextId;
    private int atlasMiB;
    private boolean spatialBricks;
    private volatile String status = "";

    GiProbeData getProbeData(GiProbeVolume volume) {
        GiProbeData embedded = volume.getData();
        if (embedded != null) {
            return embedded;
        }
        GiProbeStreamingData bricks = volume.getStreamingData();
        if (bricks != null) {
            return bricks.getCoarseData();
        }
        Source source = sources.get(volume);
        if (stream == null || source == null || !Objects.equals(source.asset(), volume.getStreamAsset())) {
            return null;
        }
        return stream.getResidentData(source.id());
    }

    List<GiProbeStreamer.ResidentBrick> getProbeBricks(GiProbeVolume volume) {
        Source source = sources.get(volume);
        if (stream == null || source == null || source.bricks() != volume.getStreamingData()
                || !source.offset().equals(volume.getWorldTranslation())) {
            return List.of();
        }
        return stream.getResidentFineBricks(source.id());
    }

    String getStatus() {
        return status;
    }

    void render(SceneApplication app, Runnable draw) {
        SceneRequest request = app.getCurrentSceneRequest();
        Spatial root = request == null ? null : request.getRootNode();
        WorldSettings previewWorld = root == null ? null : root.getControl(WorldSettings.class);
        app.getViewPort().setHalfResolutionProbeGi(previewWorld != null && previewWorld.isHalfResolutionProbeGi());
        app.getViewPort().setProbeGiFullFallback(previewWorld == null || previewWorld.isProbeGiFullFallback());
        if (root == null || !app.isGiPreviewEnabled()) {
            close();
            status = "GI preview disabled";
            draw.run();
            return;
        }
        List<Material> bound = new ArrayList<>();
        try {
            WorldSettings world = root.getControl(WorldSettings.class);
            WorldSettings.GiStreaming settings = world == null ? WorldSettings.GiStreaming.DEFAULTS : world.getGiStreaming();
            int limit = settings.getAtlasMiB();
            boolean[] hasBricks = {false};
            if (!spatialBricks) {
                root.depthFirstTraversal(spatial -> {
                    if (spatial instanceof GiProbeVolume volume && volume.getStreamingData() != null) {
                        hasBricks[0] = true;
                    }
                });
            }
            if (stream == null || atlasMiB != limit || (hasBricks[0] && !spatialBricks)) {
                boolean useBricks = spatialBricks || hasBricks[0];
                close();
                io = Executors.newFixedThreadPool(2, runnable -> {
                    Thread thread = new Thread(runnable, "SDK GI streaming IO");
                    thread.setDaemon(true);
                    return thread;
                });
                long gpu = (long) limit << 20;
                stream = new GiProbeStreamer(io, Math.max(256L << 20, gpu * 4),
                        app.getRenderer().getLimits().get(Limits.TextureSize), 200, app.getRenderer(),
                        useBricks || limit != 64 ? gpu : 0);
                atlasMiB = limit;
                spatialBricks = useBricks;
                stream.setUploadBudget(1L << 20, 2_000_000);
                stream.setUnloadMultiplier(1.3);
            }
            stream.setFineStreaming(settings.getPreloadDistance(), settings.getMaxFineProbes());
            stream.setSingleProbeVisibility(previewWorld == null ? WorldSettings.DEFAULT_SINGLE_PROBE_VISIBILITY : previewWorld.isSingleProbeVisibility());
            stream.setOctahedralProbeIrradiance(previewWorld == null ? WorldSettings.DEFAULT_OCTAHEDRAL_PROBE_IRRADIANCE : previewWorld.isOctahedralProbeIrradiance());
            Set<GiProbeVolume> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            Set<Material> materials = Collections.newSetFromMap(new IdentityHashMap<>());
            Set<com.jme3.environment.gi.ProbeLightmapControl> seenLightmaps =
                    Collections.newSetFromMap(new IdentityHashMap<>());
            root.depthFirstTraversal(spatial -> {
                var lightmap = spatial.getControl(com.jme3.environment.gi.ProbeLightmapControl.class);
                if (lightmap != null) {
                    seenLightmaps.add(lightmap);
                    lightmap.updateResidency(request.getManager(), app.getCamera().getLocation(), true);
                }
                if (spatial instanceof GiProbeVolume volume) {
                    Source old = sources.get(volume);
                    if (GiStreamingAssets.isWritingBricks(volume)) {
                        if (old != null) {
                            seen.add(volume);
                        }
                        return;
                    }
                    GiProbeData data = volume.getData();
                    String external = volume.getStreamAsset();
                    GiProbeStreamingData bricks = volume.getStreamingData();
                    if (data == null && external == null && bricks == null) {
                        return;
                    }
                    seen.add(volume);
                    Vector3f offset = volume.getWorldTranslation();
                    if (old == null || old.data() != data || old.bricks() != bricks
                            || !Objects.equals(old.asset(), external) || !old.offset().equals(offset)) {
                        if (old != null) {
                            stream.unregister(old.id());
                        }
                        String id = "volume-" + nextId++;
                        stream.registerVolume(id, volume, request.getManager());
                        sources.put(volume, new Source(id, data, external, bricks, offset.clone()));
                    }
                    stream.setAppearance(sources.get(volume).id(), volume.getIntensity(), volume.getFadeDistance(), volume.isStabilizedBlending(), volume.isSmoothBlending(), volume.isWideProbeSampling(), volume.isCurvedReceiverCorrection());
                }
                if (spatial instanceof Geometry geometry && geometry.getMaterial() != null) {
                    materials.add(geometry.getMaterial());
                }
            });
            lightmaps.removeIf(lightmap -> {
                if (!seenLightmaps.contains(lightmap)) {
                    lightmap.clearResidency();
                    return true;
                }
                return false;
            });
            lightmaps.addAll(seenLightmaps);
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
            status = "GI: " + stream.getPublishedCount() + " pages ready, "
                    + stream.getRequestedFineProbeCount() + " requested fine probes, "
                    + stream.getPendingCount() + " loads, "
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
        lightmaps.forEach(com.jme3.environment.gi.ProbeLightmapControl::clearResidency);
        lightmaps.clear();
        if (stream != null) {
            stream.close();
            stream = null;
        }
        if (io != null) {
            io.shutdownNow();
            io = null;
        }
        sources.clear();
        spatialBricks = false;
        status = "";
    }
}
