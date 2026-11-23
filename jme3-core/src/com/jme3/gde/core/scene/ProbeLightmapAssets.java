package com.jme3.gde.core.scene;

import com.jme3.bounding.BoundingBox;
import com.jme3.environment.gi.GiProbeVolume;
import com.jme3.environment.gi.ProbeLightmapBaker;
import com.jme3.environment.gi.ProbeLightmapControl;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.gde.core.assets.LightmapUvGenerator;
import com.jme3.math.Transform;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Mobility;
import com.jme3.scene.Node;
import com.jme3.scene.VertexBuffer.Type;
import com.jme3.texture.Texture2D;
import java.io.IOException;
import java.nio.file.Files;
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
public final class ProbeLightmapAssets {
    private static final int RESOLUTION = 256;
    private final SceneApplication app;
    private final SceneRequest scene;
    private final GiProbeVolume volume;
    private final com.jme3.environment.gi.GiProbeData data;
    private final Vector3f offset;
    private final Path assetRoot;
    private final String revision = "GI/Lightmaps/" + UUID.randomUUID() + "/";
    private final ArrayDeque<Geometry> remaining = new ArrayDeque<>();
    private final List<Entry> outputs = new ArrayList<>();
    private CompletableFuture<String> write;
    private int skipped;

    private record Entry(Geometry geometry, Mesh source, Mesh mesh, Transform transform, String key) {
    }

    public ProbeLightmapAssets(SceneApplication app, SceneRequest scene, GiProbeVolume volume) {
        this.app = app;
        this.scene = scene;
        this.volume = volume;
        data = volume.getData();
        offset = volume.getWorldTranslation().clone();
        var folder = scene.getManager().getAssetFolder();
        var local = folder == null ? null : FileUtil.toFile(folder);
        assetRoot = local == null ? null : local.toPath();
        if (assetRoot == null || data == null || data.hasSeparatedSky() || volume.isSurfaceLayout()) {
            return;
        }
        BoundingBox region = bounds(volume);
        List<BoundingBox> overlaps = new ArrayList<>();
        scene.getRootNode().depthFirstTraversal(spatial -> {
            if (spatial instanceof GiProbeVolume other && other != volume
                    && (other.getData() != null || other.getStreamAsset() != null || other.getStreamingData() != null)) {
                BoundingBox adjacent = bounds(other);
                if (adjacent.intersects(region)) {
                    overlaps.add(adjacent);
                }
            }
        });
        scene.getRootNode().depthFirstTraversal(spatial -> {
            if (spatial instanceof Geometry geometry && supported(geometry)
                    && geometry.getWorldBound() != null && region.intersects(geometry.getWorldBound())) {
                if (overlaps.stream().anyMatch(adjacent -> adjacent.intersects(geometry.getWorldBound()))) {
                    skipped++;
                } else {
                    remaining.add(geometry);
                }
            }
        });
    }

    private static BoundingBox bounds(GiProbeVolume volume) {
        var grid = volume.getBakeGrid();
        Vector3f min = grid.getOrigin().add(volume.getWorldTranslation());
        Vector3f extent = grid.getCounts().subtract(1, 1, 1).multLocal(grid.getSpacing()).multLocal(.5f);
        return new BoundingBox(min.add(extent), extent.x, extent.y, extent.z);
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
        Geometry geometry = remaining.removeFirst();
        Mesh source = geometry.getMesh();
        try {
            Mesh mesh = LightmapUvGenerator.generate(source);
            Geometry proxy = new Geometry(geometry.getName(), mesh);
            Transform transform = geometry.getWorldTransform().clone();
            proxy.setLocalTransform(transform);
            proxy.setIgnoreTransform(geometry.isIgnoreTransform());
            proxy.updateGeometricState();
            Texture2D texture = ProbeLightmapBaker.bake(app.getRenderManager(), scene.getManager(),
                    proxy, data, offset, RESOLUTION, RESOLUTION);
            String key = revision + UUID.randomUUID() + ".j3o";
            outputs.add(new Entry(geometry, source, mesh, transform, key));
            write = new CompletableFuture<>();
            CompletableFuture<String> pending = write;
            RequestProcessor.getDefault().post(() -> {
                try {
                    Path file = assetRoot.resolve(key);
                    Files.createDirectories(file.getParent());
                    new BinaryExporter().save(texture, file.toFile());
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
            java.util.logging.Logger.getLogger(getClass().getName()).warning(
                    "Lightmap skipped for " + geometry.getName() + ": " + failure.getMessage());
        }
        return false;
    }

    private void commit() {
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
                : " Lightmaps saved: " + completed + "; continued with live probes: " + skipped + ".";
    }
}
