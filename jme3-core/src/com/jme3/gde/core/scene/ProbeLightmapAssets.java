package com.jme3.gde.core.scene;

import com.jme3.environment.gi.GiProbeData;
import com.jme3.environment.gi.GiProbeSet;
import com.jme3.environment.gi.ProbeLightmapAsset;
import com.jme3.environment.gi.GiProbeVolume;
import com.jme3.environment.gi.ProbeLightmapBaker;
import com.jme3.environment.gi.ProbeLightmapControl;
import com.jme3.environment.gi.ProbeLightmapRegion;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.gde.core.assets.LightmapUvGenerator;
import com.jme3.math.Transform;
import com.jme3.post.WorldSettings;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Mobility;
import com.jme3.scene.Node;
import com.jme3.scene.VertexBuffer.Type;
import com.jme3.texture.Texture2D;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.openide.filesystems.FileUtil;
import org.openide.util.RequestProcessor;

/**
 * Exports independent, region-streamed lightmaps alongside a completed probe bake.
 * <pre>{@code
 * // 第一步：在 SceneComposer 烘焙探针，完成后在渲染线程创建导出任务。
 * ProbeLightmapAssets maps = new ProbeLightmapAssets(app, scene, volume);
 * // 第二步：每个渲染帧调用 maps.step()，任务完成后保存场景引用。
 * }</pre>
 * Receivers outside supported coverage continue using live probes. The scene is saved through the editor.
 */
public final class ProbeLightmapAssets implements AutoCloseable {
    private static final int RESOLUTION = 256;
    private final SceneApplication app;
    private final SceneRequest scene;
    private final GiProbeVolume volume;
    private final GiProbeData data;
    private final boolean singleVisibility;
    private final boolean octahedral;
    private final long budget;
    private final List<ProbeLightmapRegion.Source> sources = new ArrayList<>();
    private List<ProbeLightmapRegion.Source> activeSources = List.of();
    private GiProbeSet field;
    private CompletableFuture<ProbeLightmapRegion> reading;
    private Mesh requestedMesh;
    private Transform requestedTransform;
    private final Path assetRoot;
    private final String revision = "GI/Lightmaps/" + UUID.randomUUID() + "/";
    private final ArrayDeque<Geometry> remaining = new ArrayDeque<>();
    private final List<Entry> outputs = new ArrayList<>();
    private CompletableFuture<String> write;
    private int skipped;
    private String firstFailure;

    private record Entry(Geometry geometry, Mesh source, Mesh mesh, Transform transform, String key) {
    }

    public ProbeLightmapAssets(SceneApplication app, SceneRequest scene, GiProbeVolume volume) {
        this.app = app;
        this.scene = scene;
        this.volume = volume;
        data = volume.getData();
        WorldSettings world = scene.getRootNode().getControl(WorldSettings.class);
        singleVisibility = world == null ? WorldSettings.DEFAULT_SINGLE_PROBE_VISIBILITY : world.isSingleProbeVisibility();
        octahedral = world == null ? WorldSettings.DEFAULT_OCTAHEDRAL_PROBE_IRRADIANCE : world.isOctahedralProbeIrradiance();
        budget = (long) (world == null ? WorldSettings.GiStreaming.DEFAULTS : world.getGiStreaming()).getAtlasMiB() << 20;
        var folder = scene.getManager().getAssetFolder();
        var local = folder == null ? null : FileUtil.toFile(folder);
        assetRoot = local == null ? null : local.toPath();
        if (assetRoot == null || data == null) {
            return;
        }
        var region = ProbeLightmapRegion.Source.capture(volume);
        scene.getRootNode().depthFirstTraversal(spatial -> {
            if (spatial instanceof GiProbeVolume other) {
                var source = ProbeLightmapRegion.Source.capture(other);
                if (source != null) {
                    sources.add(source);
                }
            } else if (spatial instanceof Geometry geometry && supported(geometry)
                    && region.intersects(geometry.getWorldBound())) {
                remaining.add(geometry);
            }
        });
    }

    private static boolean supported(Geometry geometry) {
        if (geometry.getMobility() != Mobility.Static || geometry.getMaterial() == null
                || geometry.getMesh() == null || geometry.getMesh().isAnimated()
                || geometry.getMesh().getMorphTargets().length != 0 || geometry.getMesh().getBuffer(Type.Normal) == null) {
            return false;
        }
        String name = geometry.getMaterial().getMaterialDef().getAssetName();
        return "Common/MatDefs/Light/Lighting.j3md".equals(name)
                || "Common/MatDefs/Light/PBRLighting.j3md".equals(name);
    }

    /** Advances one capture per render frame, or waits for the active file write. */
    public boolean step() {
        if (write != null) {
            if (!write.isDone()) {
                return false;
            }
            String error = write.join();
            write = null;
            if (error != null) {
                outputs.remove(outputs.size() - 1);
                skipped++;
                java.util.logging.Logger.getLogger(getClass().getName()).warning(error);
            }
        }
        if (remaining.isEmpty()) {
            commit();
            return true;
        }
        Geometry geometry = remaining.peekFirst();
        var bounds = geometry.getWorldBound();
        var local = sources.stream().filter(source -> source.intersects(bounds)).toList();
        if (reading != null && (geometry.getMesh() != requestedMesh
                || !geometry.getWorldTransform().equals(requestedTransform))) {
            reading.cancel(false);
            reading = null;
        }
        if (local.isEmpty() || !supported(geometry)) {
            close();
            remaining.removeFirst();
            skipped++;
            return false;
        }
        boolean fine = local.stream().anyMatch(ProbeLightmapRegion.Source::hasBricks);
        if (reading == null && (fine || !local.equals(activeSources))) {
            closeField();
            var snapshot = bounds.clone(null);
            var pending = new CompletableFuture<ProbeLightmapRegion>();
            reading = pending;
            requestedMesh = geometry.getMesh();
            requestedTransform = geometry.getWorldTransform().clone();
            RequestProcessor.getDefault().post(() -> {
                try {
                    pending.complete(ProbeLightmapRegion.load(scene.getManager(), local, snapshot, 2 * budget, octahedral));
                } catch (IOException | RuntimeException failure) {
                    pending.completeExceptionally(failure);
                }
            });
        }
        if (reading != null && !reading.isDone()) {
            return false;
        }
        remaining.removeFirst();
        Mesh source = geometry.getMesh();
        try {
            if (reading != null) {
                var result = reading;
                reading = null;
                field = result.join().open(app.getRenderManager(), budget, singleVisibility, octahedral);
                activeSources = local;
            }
            Mesh mesh = LightmapUvGenerator.generate(source);
            Geometry proxy = new Geometry(geometry.getName(), mesh);
            Transform transform = geometry.getWorldTransform().clone();
            proxy.setLocalTransform(transform);
            proxy.setIgnoreTransform(geometry.isIgnoreTransform());
            proxy.updateGeometricState();
            Texture2D texture = ProbeLightmapBaker.bakeField(app.getRenderManager(), scene.getManager(),
                    proxy, field, RESOLUTION, RESOLUTION);
            String key = revision + UUID.randomUUID() + ".j3o";
            outputs.add(new Entry(geometry, source, mesh, transform, key));
            write = new CompletableFuture<>();
            CompletableFuture<String> pending = write;
            RequestProcessor.getDefault().post(() -> {
                try {
                    Path file = assetRoot.resolve(key);
                    Files.createDirectories(file.getParent());
                    Path staging = Files.createTempFile(file.getParent(), "lightmap-", ".tmp");
                    try {
                        new BinaryExporter().save(new ProbeLightmapAsset(texture, true), staging.toFile());
                        try {
                            Files.move(staging, file, StandardCopyOption.ATOMIC_MOVE);
                        } catch (AtomicMoveNotSupportedException unsupported) {
                            Files.move(staging, file);
                        }
                    } finally {
                        Files.deleteIfExists(staging);
                    }
                    FileUtil.refreshFor(file.toFile());
                    pending.complete(null);
                } catch (IOException | RuntimeException failure) {
                    pending.complete("Lightmap asset failed for " + key + ": " + failure.getMessage());
                } finally {
                    texture.getImage().dispose();
                }
            });
        } catch (IOException | RuntimeException failure) {
            skipped++;
            if (firstFailure == null) {
                firstFailure = failure.getMessage();
            }
            java.util.logging.Logger.getLogger(getClass().getName()).warning(
                    "Lightmap skipped for " + geometry.getName() + ": " + failure.getMessage());
        }
        return false;
    }

    @Override
    public void close() {
        if (reading != null) {
            reading.cancel(false);
            reading = null;
        }
        closeField();
    }

    private void closeField() {
        if (field != null) {
            field.close();
            field = null;
        }
        activeSources = List.of();
    }

    private void commit() {
        close();
        int saved = 0;
        if (volume.getData() == data && app.getCurrentSceneRequest() == scene) {
            for (Entry item : outputs) {
                Geometry geometry = item.geometry();
                if (geometry.getMesh() != item.source()
                        || !geometry.getWorldTransform().equals(item.transform())
                        || geometry.getMobility() != Mobility.Static
                        || (scene.getRootNode() != geometry && (!(scene.getRootNode() instanceof Node root)
                        || !geometry.hasAncestor(root)))) {
                    continue;
                }
                if (item.mesh() != item.source()) {
                    geometry.setMesh(item.mesh());
                }
                geometry.removeControl(ProbeLightmapControl.class);
                geometry.addControl(new ProbeLightmapControl(item.key(), item.transform(), 100));
                saved++;
            }
        }
        completed = saved;
    }

    private int completed;

    public String summary() {
        return completed == 0 && skipped == 0 ? ""
                : " Lightmaps saved: " + completed + "; continued with live probes: " + skipped + "."
                        + (firstFailure == null ? "" : " First Lightmap failure: " + firstFailure + ".");
    }
}
