package com.jme3.gde.core.scene.processors;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.math.Matrix4f;
import com.jme3.math.Vector3f;
import com.jme3.math.Vector4f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.plugins.sdf.GlobalDistanceField;
import com.jme3.scene.plugins.sdf.SdfSettings;
import com.jme3.scene.plugins.sdf.SdfVolume;
import com.jme3.scene.shape.Quad;
import com.jme3.texture.Texture3D;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Render-thread-only SDF diagnostic resources, based on TestBakedWorldPerformance
 * and TestSdfSurface. Never bakes or mutates scene assets. Global preview uses
 * three camera-centered 64^3 clipmaps (3 MiB GPU plus CPU composition caches),
 * a default finest cell of 0.5 world units, and 2x spacing per level.
 * This matches TestBakedWorldPerformance: coarser sampling trades detail for
 * lower composition/upload cost. At default spacing, levels span 31.5, 63 and
 * 126 world units per axis. Cell changes spacing and coverage, not resolution.
 * Global preview uses clipmaps only for conservative coarse stepping; candidate
 * surfaces are refined against the same source volumes as Selected. The raw
 * global grid is unchanged. Coarse stepping never reduces a safe source step;
 * both preview modes use a 512-step budget. Cascade selection clips coverage and colors hits;
 * it does not change the represented source surface. Per-instance refinement
 * adds texture sampling/draw cost compared with the old single-quad preview.
 * Selected preview samples original baked volumes, without clipmap resampling.
 * A baked ancestor is authoritative: its descendants are not drawn twice.
 * Missing SDF data is omitted, never silently baked. Unsigned fields display an
 * approximate surface band, not an exact mesh or a GI lighting solution.
 * Call update after world transforms are current. Close after removing consumers.
 * <pre>
 * 第一步：先对模型烘焙 SDF，再选择 SDF Selected 检查选中模型的原始体数据。
 * 第二步：选择 SDF Global，将场景中已有 SDF 合成为相机中心的三级距离场。
 * 第三步：默认 Colored 显示级联颜色；Auto 取消着色，Cascade 0/1/2 单独查看。
 * 第四步：Cell 修改粗查找格距与覆盖范围；最终表面使用原始 SDF 细化，不把误差范围当作全局表面。
 * 第五步：Refresh 或退出视图释放缓存；已存在的烘焙数据无需重新生成。
 * 第六步：回到 Lit 恢复模型显示；所有预览状态都不会保存进 j3o。
 * </pre>
 */
final class SdfPreview implements AutoCloseable {
    private static final String MATERIALS = "com/jme3/gde/core/scene/processors/sdf/";
    private final AssetManager assets;
    private final Node debug;
    private final Map<Spatial, SdfVolume> volumes = new IdentityHashMap<>();
    private final Map<Spatial, Material> materials = new IdentityHashMap<>();
    private final Map<SdfVolume, Texture3D> textures = new IdentityHashMap<>();
    private GlobalDistanceField global;
    private float globalCell = Float.NaN;

    SdfPreview(AssetManager assets, Node debug) {
        this.assets = assets;
        this.debug = debug;
        // Fullscreen quads are positioned in clip space, not at their CPU bounds.
        debug.setCullHint(Spatial.CullHint.Never);
        assets.addClassLoader(SdfPreview.class.getClassLoader());
    }

    int update(Spatial source, boolean globalMode, Camera camera, Renderer renderer,
            int cascade, boolean colors, float cell) {
        if (globalMode && (global == null || globalCell != cell)) {
            if (global != null) {
                global.close();
            }
            global = new GlobalDistanceField(64, 3, cell);
            globalCell = cell;
        }
        if (globalMode) {
            global.update(source, camera.getLocation(), renderer);
        }
        Map<Spatial, SdfVolume> current = new IdentityHashMap<>();
        if (source != null) {
            collect(source, current);
        }
        if (!current.equals(volumes)) {
            clearSurfaces();
            volumes.putAll(current);
            for (var entry : volumes.entrySet()) {
                SdfVolume volume = entry.getValue();
                Material material = new Material(assets, MATERIALS + "Surface.j3md");
                material.setTexture("DistanceField", textures.computeIfAbsent(volume, SdfVolume::toTexture3D));
                material.setVector3("BoundsMin", volume.getMin());
                material.setVector3("BoundsMax", volume.getMax());
                material.setVector3("Resolution", new Vector3f(volume.getSizeX(), volume.getSizeY(), volume.getSizeZ()));
                material.setVector3("CellSize", volume.getCellSize());
                material.setFloat("HitDistance", volume.getSignMode() == SdfSettings.SignMode.UNSIGNED
                        ? volume.getInterpolationMargin() : 0);
                material.setBoolean("StepHeatmap", false);
                material.setFloat("SourceMargin", volume.getInterpolationMargin());
                materials.put(entry.getKey(), material);
                screen(material);
            }
        }
        for (var entry : materials.entrySet()) {
            Matrix4f localToClip = camera.getViewProjectionMatrix().mult(entry.getKey().getWorldTransform().toTransformMatrix());
            entry.getValue().setMatrix4("LocalToClip", localToClip);
            Material material = entry.getValue();
            material.setMatrix4("ClipToLocal", localToClip.invert());
            material.setVector4("ScreenRect", screenRect(localToClip,
                    volumes.get(entry.getKey()), camera));
            material.setBoolean("GlobalPreview", globalMode);
            if (globalMode) {
                material.setMatrix4("LocalToWorld", entry.getKey().getWorldTransform().toTransformMatrix());
                Vector3f scale = entry.getKey().getWorldScale();
                material.setFloat("DistanceScale", Math.max(1e-8f,
                        Math.min(Math.abs(scale.x), Math.min(Math.abs(scale.y), Math.abs(scale.z)))));
                for (int i = 0; i < 3; i++) {
                    material.setTexture("Field" + i, global.getTexture(i));
                    material.setVector3("Min" + i, global.getMin(i));
                }
                material.setVector3("Cells", new Vector3f(global.getCellSize(0),
                        global.getCellSize(1), global.getCellSize(2)));
                material.setFloat("GlobalResolution", global.getResolution());
                material.setInt("Cascade", cascade);
                material.setBoolean("CascadeColors", colors);
            }
        }
        return volumes.size();
    }

    // Project the source bounds to avoid one full-screen ray march per instance.
    // A bound crossing the eye plane conservatively retains the whole viewport.
    private Vector4f screenRect(Matrix4f localToClip, SdfVolume volume, Camera camera) {
        Vector3f low = volume.getMin();
        Vector3f high = volume.getMax();
        float left = 1;
        float bottom = 1;
        float right = -1;
        float top = -1;
        for (int corner = 0; corner < 8; corner++) {
            Vector4f clip = localToClip.mult(new Vector4f(
                    (corner & 1) == 0 ? low.x : high.x,
                    (corner & 2) == 0 ? low.y : high.y,
                    (corner & 4) == 0 ? low.z : high.z, 1));
            if (clip.w <= 1e-6f) {
                return new Vector4f(0, 0, 1, 1);
            }
            left = Math.min(left, clip.x / clip.w);
            right = Math.max(right, clip.x / clip.w);
            bottom = Math.min(bottom, clip.y / clip.w);
            top = Math.max(top, clip.y / clip.w);
        }
        float padX = 2f / Math.max(1, camera.getWidth());
        float padY = 2f / Math.max(1, camera.getHeight());
        return new Vector4f(clampUv(left - padX), clampUv(bottom - padY),
                clampUv(right + padX), clampUv(top + padY));
    }

    private float clampUv(float clip) {
        return Math.max(0, Math.min(1, clip * 0.5f + 0.5f));
    }

    private void collect(Spatial spatial, Map<Spatial, SdfVolume> result) {
        SdfVolume volume = spatial.getUserData(SdfVolume.USER_DATA_KEY);
        if (volume != null) {
            result.put(spatial, volume);
            return;
        }
        if (spatial instanceof Node node) {
            for (Spatial child : node.getChildren()) {
                collect(child, result);
            }
        }
    }

    private void screen(Material material) {
        Geometry geometry = new Geometry("SDF ray march", new Quad(1, 1));
        geometry.setMaterial(material);
        geometry.setCullHint(Spatial.CullHint.Never);
        geometry.setQueueBucket(RenderQueue.Bucket.Opaque);
        debug.attachChild(geometry);
    }

    private void clearSurfaces() {
        debug.detachAllChildren();
        materials.clear();
        volumes.clear();
        for (Texture3D texture : textures.values()) {
            texture.getImage().dispose();
        }
        textures.clear();
    }

    @Override
    public void close() {
        clearSurfaces();
        if (global != null) {
            global.close();
            global = null;
        }
        globalCell = Float.NaN;
    }
}
