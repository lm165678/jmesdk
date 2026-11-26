package com.jme3.gde.core.scene;

import com.jme3.environment.gi.BakedDirectionalShadow;
import com.jme3.environment.gi.GiProbeData;
import com.jme3.environment.gi.GiProbeSet;
import com.jme3.environment.gi.ProbeLightmapAsset;
import com.jme3.environment.gi.GiProbeVolume;
import com.jme3.environment.gi.ProbeLightmapBaker;
import com.jme3.environment.gi.ProbeLightmapControl;
import com.jme3.environment.gi.ProbeLightmapRegion;
import com.jme3.export.Savable;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.gde.core.assets.LightmapUvGenerator;
import com.jme3.light.DirectionalLight;
import com.jme3.light.Light;
import com.jme3.math.Transform;
import com.jme3.renderer.queue.RenderQueue.ShadowMode;
import com.jme3.post.WorldSettings;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Mobility;
import com.jme3.scene.Node;
import com.jme3.scene.VertexBuffer.Type;
import com.jme3.shader.VarType;
import com.jme3.texture.Texture2D;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
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
    private final List<BakedDirectionalShadow> bakedShadows = new ArrayList<>();
    private final List<BakedDirectionalShadow> newShadows = new ArrayList<>();
    private final boolean bakeDirect;
    private final boolean bakeDirectionalIndirect;
    private String directionalNote;
    private final int shadowRadius;
    private final int shadowSamplesPerAxis;
    private String directNote;
    private boolean committed;
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
    private CompletableFuture<WriteResult> write;
    private int skipped;
    private String firstFailure;

    private record Entry(Geometry geometry, Mesh source, Mesh mesh, Transform transform, String key, int resolution,
            String directionalKey) {
    }

    private record WriteResult(Entry entry, String error, String directionalNote) {
    }

    /** Creates a render-thread bake task with UV2 seam smoothing enabled. */
    public ProbeLightmapAssets(SceneApplication app, SceneRequest scene, GiProbeVolume volume) {
        this(app, scene, volume, true);
    }

    /** Creates a render-thread bake task with the submitted seam smoothing setting. */
    public ProbeLightmapAssets(SceneApplication app, SceneRequest scene, GiProbeVolume volume, boolean blendSeams) {
        this(app, scene, volume, blendSeams, false, 2048);
    }

    /**
     * 第一步：在 Bake & Apply 勾选直射和静态阴影，默认关闭。
     * 第二步：按渲染线程任务捕获固定方向光遮挡及静态 UV2 直射层。
     * directShadowSize 控制共享静态深度图的尺寸（64..2048）。探针捕获尺寸单独设置。
     * 第三步：完成后保存场景以提交快照和贴图引用。取消保留旧快照。
     * 第四步：取消勾选后重烘焙，恢复原来的实时直接光照和投影。
     */
    public ProbeLightmapAssets(SceneApplication app, SceneRequest scene, GiProbeVolume volume,
            boolean blendSeams, boolean bakeDirect, int directShadowSize) {
        this(app, scene, volume, blendSeams, bakeDirect, directShadowSize, 0, 3);
    }

    /** Queues the submitted shadow filter for all directional UV2 bands in this bake. */
    public ProbeLightmapAssets(SceneApplication app, SceneRequest scene, GiProbeVolume volume,
            boolean blendSeams, boolean bakeDirect, int directShadowSize, int shadowRadius, int shadowSamplesPerAxis) {
        this(app, scene, volume, blendSeams, bakeDirect, directShadowSize, shadowRadius, shadowSamplesPerAxis, false);
    }

    /**
     * 第一步：在渲染线程创建 Lightmap 任务，末尾传 true 可烘焙可选的 PBR 间接光方向数据。
     * 第二步：逐帧调用 step()，写盘完成后关闭任务。取消或方向数据写盘失败时保留原有播放回退。
     * 第三步：保存场景以保存基础和方向数据的引用。切换 Directional indirect Lightmaps 即可对照。
     */
    public ProbeLightmapAssets(SceneApplication app, SceneRequest scene, GiProbeVolume volume,
            boolean blendSeams, boolean bakeDirect, int directShadowSize, int shadowRadius,
            int shadowSamplesPerAxis, boolean bakeDirectionalIndirect) {
        this.bakeDirectionalIndirect = bakeDirectionalIndirect;
        this.shadowRadius = shadowRadius;
        this.shadowSamplesPerAxis = shadowSamplesPerAxis;
        this.app = app;
        this.bakeDirect = bakeDirect;
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
        if (bakeDirect && !remaining.isEmpty()) {
            prepareDirect(Math.max(64, Math.min(directShadowSize, 2048)));
        }
    }

    private void prepareDirect(int size) {
        Set<Light> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<DirectionalLight> lights = new ArrayList<>();
        List<BakedDirectionalShadow> existing = new ArrayList<>();
        scene.getRootNode().depthFirstTraversal(spatial -> {
            if (spatial instanceof GiProbeVolume && spatial != volume) {
                Collections.addAll(existing, ((GiProbeVolume) spatial).getBakedDirectionalShadows());
            }
            for (Light light : spatial.getWorldLightList()) {
                if (seen.add(light) && light instanceof DirectionalLight && light.isEnabled()
                        && light.getMobility() == Mobility.Static && light.isCastShadow()) {
                    lights.add((DirectionalLight) light);
                }
            }
        });
        for (DirectionalLight light : lights) {
            if (bakedShadows.size() == BakedDirectionalShadow.MAX_LIGHTS) {
                directNote = "Additional directional lights remain realtime.";
                break;
            }
            try {
                BakedDirectionalShadow shared = null;
                for (BakedDirectionalShadow candidate : existing) {
                    if (candidate.getLight() == light && candidate.canReuse(scene.getRootNode(), size)) {
                        shared = candidate;
                        break;
                    }
                }
                if (shared == null) {
                    shared = BakedDirectionalShadow.capture(app.getRenderManager(), scene.getRootNode(), light, size);
                    newShadows.add(shared);
                }
                bakedShadows.add(shared);
            } catch (RuntimeException failure) {
                directNote = "Static shadow capture skipped: " + failure.getMessage();
            }
        }
        if (lights.isEmpty()) {
            directNote = "No eligible static shadow-casting directional lights.";
        }
    }

    private ProbeLightmapAsset makeMap(Geometry geometry, Geometry proxy, Texture2D indirect, int resolution) {
        ShadowMode mode = geometry.getShadowMode();
        if (bakedShadows.isEmpty() || mode != ShadowMode.Receive && mode != ShadowMode.CastAndReceive
                || !"Common/MatDefs/Light/PBRLighting.j3md".equals(geometry.getMaterial().getMaterialDef().getAssetName())
                || geometry.getMaterial().isTransparent()
                || geometry.getMaterial().getAdditionalRenderState().getBlendMode()
                        != com.jme3.material.RenderState.BlendMode.Off) {
            return new ProbeLightmapAsset(indirect, true);
        }
        List<BakedDirectionalShadow> applied = new ArrayList<>();
        for (Light light : geometry.getWorldLightList()) {
            for (BakedDirectionalShadow shadow : bakedShadows) {
                if (shadow.getLight() == light) {
                    applied.add(shadow);
                    break;
                }
            }
        }
        int limit = app.getRenderManager().getRenderer().getLimits()
                .getOrDefault(com.jme3.renderer.Limits.TextureSize, 0);
        if (applied.isEmpty() || (long) resolution * applied.size() > limit) {
            return new ProbeLightmapAsset(indirect, true);
        }
        List<Texture2D> bands = new ArrayList<>();
        try {
            for (BakedDirectionalShadow shadow : applied) {
                bands.add(ProbeLightmapBaker.bakeDirect(app.getRenderManager(), scene.getManager(),
                        proxy, shadow, resolution, resolution, shadowRadius, shadowSamplesPerAxis));
            }
            return ProbeLightmapAsset.withDirect(indirect, true, bands, applied);
        } catch (RuntimeException failure) {
            directNote = "Direct Lightmap skipped for " + geometry.getName() + ": " + failure.getMessage();
            return new ProbeLightmapAsset(indirect, true);
        } finally {
            for (Texture2D band : bands) {
                band.getImage().dispose();
            }
        }
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

    private Texture2D bakeDirectionalMap(Geometry proxy, int resolution) {
        var material = proxy.getMaterial();
        if (!"Common/MatDefs/Light/PBRLighting.j3md".equals(material.getMaterialDef().getAssetName())
                || proxy.getMesh().getBuffer(Type.Tangent) == null || proxy.getMesh().getBuffer(Type.TexCoord) == null) {
            return null;
        }
        Object materialNormal = material.getParamValue("NormalMap");
        for (var override : proxy.getWorldMatParamOverrides()) {
            if (override.isEnabled() && override.getName().equals("NormalMap")
                    && override.getVarType() == VarType.Texture2D) {
                materialNormal = override.getValue();
            }
        }
        if (!(materialNormal instanceof Texture2D)) {
            return null;
        }
        try {
            return ProbeLightmapBaker.bakeDirectionalField(app.getRenderManager(), scene.getManager(),
                    proxy, field, resolution, resolution, blendSeams);
        } catch (RuntimeException failure) {
            directionalNote = "Directional Lightmap skipped for " + proxy.getName() + ": " + failure.getMessage();
            return null;
        }
    }

    private void saveAsset(Savable asset, String key) throws IOException {
        Path file = assetRoot.resolve(key);
        Path staging = Files.createTempFile(file.getParent(), "lightmap-", ".tmp");
        try {
            new BinaryExporter().save(asset, staging.toFile());
            try {
                Files.move(staging, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(staging, file);
            }
        } finally {
            Files.deleteIfExists(staging);
        }
        FileUtil.refreshFor(file.toFile());
    }

    /** Advances one capture per render frame, or waits for the active file write. */
    public boolean step() {
        if (write != null) {
            if (!write.isDone()) {
                return false;
            }
            WriteResult result = write.join();
            write = null;
            if (result.entry() == null) {
                skipped++;
                java.util.logging.Logger.getLogger(getClass().getName()).warning(result.error());
            } else {
                outputs.add(result.entry());
            }
            if (result.directionalNote() != null) {
                directionalNote = result.directionalNote();
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
            ProbeLightmapAsset map = makeMap(geometry, proxy, texture, lightmapResolution);
            Texture2D directional = bakeDirectionalIndirect ? bakeDirectionalMap(proxy, lightmapResolution) : null;
            String stem = revision + UUID.randomUUID();
            String key = stem + ".j3o";
            String directionalKey = directional == null ? null : stem + "-direction.j3o";
            Entry written = new Entry(geometry, source, mesh, transform, key, lightmapResolution, directionalKey);
            write = new CompletableFuture<>();
            CompletableFuture<WriteResult> pending = write;
            RequestProcessor.getDefault().post(() -> {
                try {
                    // Publish recreated folders through NetBeans, not only NIO.
                    FileUtil.createFolder(scene.getManager().getAssetFolder(),
                            key.substring(0, key.lastIndexOf('/')));
                    saveAsset(map, key);
                    if (directional != null) {
                        try {
                            saveAsset(directional, directionalKey);
                        } catch (IOException | RuntimeException failure) {
                            Entry baseOnly = new Entry(written.geometry(), written.source(), written.mesh(),
                                    written.transform(), key, written.resolution(), null);
                            pending.complete(new WriteResult(baseOnly, null,
                                    "Directional Lightmap write failed for " + key + ": " + failure.getMessage()));
                            return;
                        }
                    }
                    pending.complete(new WriteResult(written, null, null));
                } catch (IOException | RuntimeException failure) {
                    pending.complete(new WriteResult(null,
                            "Lightmap asset failed for " + key + ": " + failure.getMessage(), null));
                } finally {
                    texture.getImage().dispose();
                    if (map.getDirectTexture() != null) {
                        map.getDirectTexture().getImage().dispose();
                    }
                    if (directional != null) {
                        directional.getImage().dispose();
                    }
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
        if (!committed) {
            for (BakedDirectionalShadow shadow : newShadows) {
                shadow.getDepth().getImage().dispose();
            }
            newShadows.clear();
            bakedShadows.clear();
        }
    }

    private void closeField() {
        if (field != null) {
            field.close();
            field = null;
        }
        activeSources = List.of();
    }

    private void commit() {
        closeField();
        int saved = 0;
        var uvSources = new IdentityHashMap<Geometry, Mesh>();
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
                    uvSources.put(geometry, item.source());
                    geometry.setMesh(item.mesh());
                    geometry.setUserData(LightmapUvGenerator.RESOLUTION_KEY, item.resolution());
                }
                geometry.removeControl(ProbeLightmapControl.class);
                geometry.addControl(new ProbeLightmapControl(item.key(), item.transform(), 100,
                        item.directionalKey()));
                if (item.directionalKey() != null) {
                    directionalCompleted++;
                }
                saved++;
            }
        }
        if (saved > 0) {
            scene.getRootNode().depthFirstTraversal(spatial -> {
                if (spatial instanceof GiProbeVolume) {
                    for (BakedDirectionalShadow shadow : ((GiProbeVolume) spatial).getBakedDirectionalShadows()) {
                        shadow.acceptUvRemaps(uvSources);
                    }
                }
            });
            for (BakedDirectionalShadow shadow : bakedShadows) {
                shadow.acceptUvRemaps(uvSources);
            }
            volume.setBakedDirectionalShadows(bakedShadows.toArray(new BakedDirectionalShadow[0]));
            committed = true;
        } else {
            close();
        }
        completed = saved;
    }

    private int completed;
    private int directionalCompleted;

    public String summary() {
        return completed == 0 && skipped == 0 ? ""
                : " Lightmaps saved: " + completed + "; continued with live probes: " + skipped + "."
                        + (completed == 0 ? "" : " Lightmap seam smoothing: " + (blendSeams ? "on." : "off."))
                        + (bakeDirect ? " Static directional shadow maps: " + bakedShadows.size() + "." : "")
                        + (directNote == null ? "" : " " + directNote)
                        + (bakeDirectionalIndirect ? " Directional Lightmaps saved: " + directionalCompleted + "." : "")
                        + (directionalNote == null ? "" : " " + directionalNote)
                        + (firstFailure == null ? "" : " First Lightmap failure: " + firstFailure + ".");
    }
}
