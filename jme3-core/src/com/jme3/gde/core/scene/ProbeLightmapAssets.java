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
 * // 默认平滑 UV2 接缝；最后一个参数传 false 可关闭以对照本次烘焙结果。
 * ProbeLightmapAssets maps = new ProbeLightmapAssets(app, scene, volume, true);
 * // 第二步：将 maps 保存为任务状态。每一渲染帧执行一次下面的检查。
 * if (maps.step()) {
 *     maps.close();
 *     // 停止执行此任务；保存场景可保留新的 Lightmap 引用。
 * }
 * // 取消任务或关闭场景时也在渲染线程调用 maps.close()。
 * }</pre>
 * Receivers outside supported coverage continue using live probes. The scene is saved through the editor.
 * Manual Geometry UV2 regeneration stores LightmapUvGenerator.RESOLUTION_KEY;
 * subsequent bakes use that 64..4096 target size. Absent metadata starts at 256.
 * Missing UV2 grows to fit chart margins and minimum island area, capped by
 * 2048 and the GPU's two-band texture limit. Existing UV2 is preserved.
 * The selected automatic resolution is saved only with a successful Lightmap.
 * UV island margins reserve space; the baker's color dilation policy is unchanged.
 * Regenerate and rebake on the scene's render thread, then save through the editor.
 * <pre>{@code
 * // 第一步：Properties -> Lightmap UV，重新生成所选 Geometry 的 UV2 并设置分辨率。
 * // 第二步：Bake & Apply 重新烘焙探针与静态 Lightmap；本任务使用记录的目标尺寸。
 * // 第三步：Ctrl+S 保存引用。恢复旧尺寸时撤销 UV 编辑，或删除分辨率覆盖后重新烘焙。
 * }</pre>
 */
public final class ProbeLightmapAssets implements AutoCloseable {
    private static final int RESOLUTION = 256;
    private final SceneApplication app;
    private final SceneRequest scene;
    private final GiProbeVolume volume;
    private final GiProbeData data;
    private final boolean singleVisibility;
    private final boolean octahedral;
    private final boolean blendSeams;
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

    private record Entry(Geometry geometry, Mesh source, Mesh mesh, Transform transform, String key, int resolution) {
    }

    /** Creates a render-thread bake task with UV2 seam smoothing enabled. */
    public ProbeLightmapAssets(SceneApplication app, SceneRequest scene, GiProbeVolume volume) {
        this(app, scene, volume, true);
    }

    /** Creates a render-thread bake task with the submitted seam smoothing setting. */
    public ProbeLightmapAssets(SceneApplication app, SceneRequest scene, GiProbeVolume volume, boolean blendSeams) {
        this.app = app;
        this.blendSeams = blendSeams;
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
        if (LightmapUvGenerator.isDeformable(geometry)
                || geometry.getMobility() != Mobility.Static || geometry.getMaterial() == null
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
            Integer requested = geometry.getUserData(LightmapUvGenerator.RESOLUTION_KEY);
            int lightmapResolution = requested == null ? RESOLUTION : requested;
            Mesh mesh = source;
            if (source.getBuffer(Type.TexCoord2) == null) {
                int limit = app.getRenderer().getLimits()
                        .getOrDefault(com.jme3.renderer.Limits.TextureSize, 0);
                var defaults = LightmapUvGenerator.Settings.DEFAULT;
                var layout = LightmapUvGenerator.generateForBake(source,
                        new LightmapUvGenerator.Settings(lightmapResolution, defaults.padding(), defaults.border()),
                        Math.min(2048, limit / 2));
                mesh = layout.mesh();
                lightmapResolution = layout.resolution();
            }
            Geometry proxy = new Geometry(geometry.getName(), mesh);
            Transform transform = geometry.getWorldTransform().clone();
            proxy.setLocalTransform(transform);
            proxy.setIgnoreTransform(geometry.isIgnoreTransform());
            // Keep source UV0 material inputs while rasterizing the generated UV2 mesh.
            proxy.setMaterial(geometry.getMaterial());
            for (var override : geometry.getWorldMatParamOverrides()) {
                proxy.addMatParamOverride(override);
            }
            proxy.updateGeometricState();
            // Explicit UV regeneration records its texel scale on the receiver.
            // Legacy receivers retain the original 256-pixel bake resolution.
            if (lightmapResolution < 64 || lightmapResolution > 4096) {
                throw new IOException("Invalid receiver Lightmap UV resolution: " + lightmapResolution);
            }
            Texture2D texture = ProbeLightmapBaker.bakeField(app.getRenderManager(), scene.getManager(),
                    proxy, field, lightmapResolution, lightmapResolution, blendSeams);
            String key = revision + UUID.randomUUID() + ".j3o";
            outputs.add(new Entry(geometry, source, mesh, transform, key, lightmapResolution));
            write = new CompletableFuture<>();
            CompletableFuture<String> pending = write;
            RequestProcessor.getDefault().post(() -> {
                try {
                    Path file = assetRoot.resolve(key);
                    // Publish recreated folders through NetBeans, not only NIO.
                    FileUtil.createFolder(scene.getManager().getAssetFolder(),
                            key.substring(0, key.lastIndexOf('/')));
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
                    geometry.setUserData(LightmapUvGenerator.RESOLUTION_KEY, item.resolution());
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
                        + (completed == 0 ? "" : " Lightmap seam smoothing: " + (blendSeams ? "on." : "off."))
                        + (firstFailure == null ? "" : " First Lightmap failure: " + firstFailure + ".");
    }
}
