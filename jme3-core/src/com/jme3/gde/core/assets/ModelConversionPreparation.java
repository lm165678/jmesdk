package com.jme3.gde.core.assets;

import com.jme3.math.Vector3f;
import com.jme3.material.MatParam;
import com.jme3.material.MatParamTexture;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer.Type;
import com.jme3.scene.control.LodControl;
import jme3tools.optimize.LodGenerator;
import com.jme3.util.BufferUtils;
import com.jme3.util.mikktspace.MikktspaceTangentGenerator;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Prepares a private export copy when a source model is converted to j3o.
 * Conversion requests five LOD levels including original LOD0, retaining
 * approximately 100%, 50%, 25%, 12.5% and 6.25% of triangles. Existing LODs are
 * replaced on the export copy; shared meshes are processed once. The simplifier
 * can return fewer levels for tiny or non-reducible meshes; no duplicate levels
 * are fabricated. All descendant triangle meshes participate, regardless of
 * source format. The export attaches a LodControl to geometries with reduced
 * levels, along with the configured desktop and mobile starting levels.
 * Exported material Texture2D samplers use bilinear magnification and trilinear
 * minification when mip levels exist or were requested, otherwise bilinear without
 * mipmaps. This deliberately overrides source nearest samplers (including glTF).
 * Only sampler copies on cloned materials change; pixels, wrap, UVs and color
 * spaces are preserved. Cube/3D textures and non-material data buffers are untouched.
 * Existing normals/tangents are preserved. Missing normals use area-weighted
 * triangle normals per shared vertex; split vertices retain their hard edges.
 * This does not reconstruct OBJ smoothing groups or weld UV seams.
 * Missing tangents use MikkTSpace when UV0 exists. Non-triangle meshes and meshes
 * without UV0 skip tangent generation with a log message; no UVs are invented.
 * When requested, conversions bake one local-space unsigned 64^3 SDF with 5%
 * padding and supported static models get independent 128-pixel surface cards
 * (256 MiB payload cap).
 * SDF on skinned assets captures the current/bind pose, not all animated poses.
 * The conversion dialog defaults both bakes off on animated/skinned/morph assets.
 * Surface capture is skipped with a warning for unsupported dynamic, transparent,
 * overridden or unsupported-material geometry, including programmatic conversion.
 * This preflight skip preserves source data; unexpected bake failures still abort.
 * Static
 * meshes get static physics; terrain uses heightfields; skeleton roots get an
 * estimated capsule character. Existing physics controls are preserved.
 * GPU surface capture and physics creation execute on the SDK render thread.
 * Any failure aborts before creating/truncating the output J3O.
 * Run in the existing conversion/save worker before opening the output stream.
 * The input must remain stable during cloning, as required by binary export.
 * Neither the live scene nor source assets are mutated.
 * The export copy stores the requested editor preference as String user data;
 * AUTO selects the animation editor only when imported AnimComposer clips exist.
 * This preference does not classify or modify skinning data.
 * Normal j3o saves bypass
 * this preparation, so editing/saving a light probe does not regenerate meshes.
 * <pre>
 * 第一步：选中 OBJ 等源模型，执行 Convert to j3o。
 * 第二步：导出副本自动补齐缺失法线，有 UV0 时补齐缺失切线；已有数据保留。
 * 默认生成含原始层的五级 LOD，目标保留比例为 100%、50%、25%、12.5%、6.25%。
 * PC 和移动端起始 LOD 默认均为 0。用 assetManager.loadModel() 加载并挂到场景即可自动切换。
 * 普通材质二维纹理默认使用双线性放大；有 mipmap 时三线性缩小，否则双线性缩小。
 * 第三步：自动烘焙 SDF 与表面属性，并按模型类型添加缺失的物理控件。
 * 第四步：打开 j3o；在 Properties 检查角色胶囊尺寸和轴心，需要时 Rebuild。
 * 第五步：运行时把模型添加到 PhysicsSpace；普通 Ctrl+S 不会重复烘焙。
 * 转换对话框可取消各项生成，保留输入中的现有数据；LOD 层数含原始层。
 * 取消导入材质使用中性 Lighting 材质，表面属性烘焙也使用该替代材质。
 * 选择已有 J3O 时，转换副本使用对应材质及其现有纹理路径。
 * </pre>
 */
final class ModelConversionPreparation {
    private static final Logger LOG = Logger.getLogger(ModelConversionPreparation.class.getName());

    static Spatial prepare(Spatial source, ModelConversionOptions options, ProjectAssetManager assets) throws java.io.IOException {
        return prepare(source, options, assets, assets);
    }

    static Spatial prepareForExport(Spatial source, ModelConversionOptions options, ProjectAssetManager assets,
            ProjectAssetManager materialLibrary, org.openide.filesystems.FileObject sourceFile) throws java.io.IOException {
        return prepareForExport(source, options, assets, materialLibrary, sourceFile, null);
    }

    static Spatial prepareForExport(Spatial source, ModelConversionOptions options, ProjectAssetManager assets,
            ProjectAssetManager materialLibrary, org.openide.filesystems.FileObject sourceFile,
            java.util.function.Consumer<String> phase) throws java.io.IOException {
        Spatial result = prepare(source, options, assets, materialLibrary);
        if (options.importsSourceMaterials()) {
            if (phase != null) {
                phase.accept("Preparing model textures: " + sourceFile.getNameExt());
            }
            ModelTexturePreparation.prepare(result, assets, sourceFile, phase);
        }
        return result;
    }

    private static Spatial prepare(Spatial source, ModelConversionOptions options, ProjectAssetManager assets,
            ProjectAssetManager materialLibrary) throws java.io.IOException {
        org.netbeans.api.progress.ProgressHandle progress = org.netbeans.api.progress.ProgressHandle.createHandle("Preparing J3O: mesh, SDF, surface and collision");
        progress.start();
        try {
            return prepareCopy(source, progress, options, assets, materialLibrary);
        } finally {
            progress.finish();
        }
    }

    private static Spatial prepareCopy(Spatial source, org.netbeans.api.progress.ProgressHandle progress,
            ModelConversionOptions options, ProjectAssetManager assets, ProjectAssetManager materialLibrary) throws java.io.IOException {
        Spatial result = source.clone(true);
        boolean reuseMaterials = options.materialSource() != null;
        if (reuseMaterials) {
            ModelMaterialReuse.apply(result, materialLibrary, options.materialSource());
        }
        result.setUserData(AnimationEditorSupport.PREFERENCE, options.editor().name());
        Map<Mesh, Mesh> copies = new IdentityHashMap<>();
        Map<Texture, Texture> samplers = new IdentityHashMap<>();
        result.depthFirstTraversal(spatial -> {
            if (!(spatial instanceof Geometry geometry) || geometry.getMesh() == null) {
                return;
            }
            if (!reuseMaterials && !options.materials()) {
                com.jme3.material.Material neutral = new com.jme3.material.Material(assets, "Common/MatDefs/Light/Lighting.j3md");
                neutral.setBoolean("UseMaterialColors", true);
                neutral.setColor("Diffuse", com.jme3.math.ColorRGBA.White);
                neutral.setColor("Ambient", com.jme3.math.ColorRGBA.White);
                geometry.setMaterial(neutral);
            }
            if (!reuseMaterials && geometry.getMaterial() != null) {
                for (MatParam parameter : geometry.getMaterial().getParams()) {
                    if (parameter instanceof MatParamTexture textureParameter
                            && textureParameter.getTextureValue() instanceof Texture2D texture) {
                        Texture filtered = samplers.computeIfAbsent(texture, originalTexture -> {
                            Texture clone = originalTexture.clone();
                            clone.setMagFilter(Texture.MagFilter.Bilinear);
                            boolean mipmaps = originalTexture.getMinFilter().usesMipMapLevels()
                                    || (originalTexture.getImage() != null
                                    && (originalTexture.getImage().hasMipmaps()
                                    || originalTexture.getImage().isGeneratedMipmapsRequired()));
                            clone.setMinFilter(mipmaps ? Texture.MinFilter.Trilinear
                                    : Texture.MinFilter.BilinearNoMipMaps);
                            return clone;
                        });
                        geometry.getMaterial().setTexture(parameter.getName(), filtered);
                    }
                }
            }
            Mesh original = geometry.getMesh();
            if (original.getMode() != Mesh.Mode.Triangles
                    && original.getMode() != Mesh.Mode.TriangleStrip
                    && original.getMode() != Mesh.Mode.TriangleFan) {
                return;
            }
            boolean needsNormals = original.getBuffer(Type.Normal) == null;
            Mesh copy = copies.get(original);
            if (copy == null) {
                copy = original.deepClone();
                // LodGenerator expects an explicit triangle-list index buffer.
                if (options.lod()) {
                    copy.setLodLevels(null);
                }
                if (copy.getMode() != Mesh.Mode.Triangles || copy.getBuffer(Type.Index) == null) {
                    int[] indices = new int[copy.getTriangleCount() * 3];
                    int[] triangle = new int[3];
                    for (int i = 0; i < indices.length / 3; i++) {
                        copy.getTriangle(i, triangle);
                        System.arraycopy(triangle, 0, indices, i * 3, 3);
                    }
                    copy.setMode(Mesh.Mode.Triangles);
                    copy.clearBuffer(Type.Index);
                    copy.setBuffer(Type.Index, 3, indices);
                    copy.updateCounts();
                }
                if (needsNormals && options.normals()) {
                    if (copy.getBuffer(Type.BindPosePosition) != null) {
                        throw new IllegalArgumentException("Skinned mesh lacks normals; export bind-pose normals: "
                                + geometry.getName());
                    }
                    generateNormals(copy);
                    copy.clearBuffer(Type.Tangent);
                    copy.clearBuffer(Type.Binormal);
                    LOG.info("Generated missing normals: " + geometry.getName());
                }
                if (options.tangents() && copy.getBuffer(Type.Normal) != null && copy.getBuffer(Type.Tangent) == null) {
                    if (copy.getBuffer(Type.TexCoord) != null) {
                        MikktspaceTangentGenerator.generate(copy);
                        if (copy.getBuffer(Type.Tangent) == null) {
                            throw new IllegalArgumentException("Tangent generation failed: " + geometry.getName());
                        }
                        LOG.info("Generated missing tangents: " + geometry.getName());
                    } else {
                        LOG.warning("Skipped tangents (missing UV0): " + geometry.getName());
                    }
                }
                if (options.lod() && options.lodLevels() > 1 && copy.getTriangleCount() > 0) {
                    float[] reductions = new float[options.lodLevels() - 1];
                    for (int level = 0; level < reductions.length; level++) {
                        reductions[level] = 1f - (float) Math.pow(.5, level + 1);
                    }
                    new LodGenerator(copy).bakeLods(LodGenerator.TriangleReductionMethod.PROPORTIONAL, reductions);
                    LOG.info("Generated " + copy.getNumLodLevels() + "/" + options.lodLevels() + " LOD levels: " + geometry.getName());
                }
                copies.put(original, copy);
            }
            geometry.setMesh(copy);
            if (copy.getNumLodLevels() > 0) {
                geometry.setLodLevel(0);
            }
            if (options.lod() && copy.getNumLodLevels() > 1) {
                LodControl control = geometry.getControl(LodControl.class);
                if (control == null) {
                    control = new LodControl();
                    geometry.addControl(control);
                }
                control.setDesktopStartLevel(options.desktopStartLevel());
                control.setMobileStartLevel(options.mobileStartLevel());
            }
        });
        result.updateGeometricState();
        if (!copies.isEmpty()) {
            ModelConversionAnalysis analysis = ModelConversionAnalysis.inspect(result);
            boolean captureSurface = options.surface() && analysis.surfaceSupported();
            if (options.surface() && !captureSurface) {
                LOG.warning("Skipped surface attribute bake: " + analysis.surfaceUnsupportedReason());
            }
            if (options.sdf()) {
                progress.progress("Baking SDF (" + options.bake().sdfX() + " x " + options.bake().sdfY() + " x " + options.bake().sdfZ() + ")...");
                var sdf = com.jme3.scene.plugins.sdf.SdfGenerator.generate(result,
                        new com.jme3.scene.plugins.sdf.SdfSettings(options.bake().sdfX(), options.bake().sdfY(), options.bake().sdfZ(), .05f,
                                com.jme3.scene.plugins.sdf.SdfSettings.SignMode.UNSIGNED),
                        fraction -> !Thread.currentThread().isInterrupted());
                result.setUserData(com.jme3.scene.plugins.sdf.SdfVolume.USER_DATA_KEY, sdf);
            }
            com.jme3.gde.core.scene.SceneApplication app = captureSurface || options.collision()
                    ? com.jme3.gde.core.scene.SceneApplication.getApplication() : null;
            java.util.concurrent.Callable<Void> capture = () -> {
                result.updateGeometricState();
                if (captureSurface) {
                    // 第一步：按实际卡片数估算预算；第二步：必要时降低分辨率。
                    // The cap limits the saved payload. Baking still needs memory for all cards.
                    final long surfaceBudget = options.bake().surfaceMiB() * 1024L * 1024;
                    int cards = com.jme3.scene.plugins.sdf.SurfaceCacheLayout
                            .bake(result).getCardCount();
                    int requestedResolution = options.bake().surfaceResolution();
                    int resolution = requestedResolution;
                    while (options.bake().reduceSurface() && resolution > 8
                            && (long) cards * resolution * resolution * 13 * Float.BYTES > surfaceBudget) {
                        resolution = Math.max(8, resolution / 2);
                    }
                    if ((long) cards * resolution * resolution * 13 * Float.BYTES > surfaceBudget) {
                        throw new IllegalArgumentException("Surface capture requires " + ((long) cards * resolution * resolution * 13 * Float.BYTES)
                                + " bytes for " + cards + " cards; budget is " + surfaceBudget
                                + ". Reduce resolution, increase budget, enable automatic reduction, or disable surface baking.");
                    }
                    if (resolution < requestedResolution) {
                        LOG.warning("Surface capture resolution reduced from " + requestedResolution + " to " + resolution
                                + " for " + cards + " cards to fit the " + options.bake().surfaceMiB() + " MiB payload budget.");
                    }
                    progress.progress("Capturing surface attributes (" + resolution + " pixels/card)...");
                    var surfaces = com.jme3.scene.plugins.sdf.SurfaceAttributeBaker.bake(result, "sdk-conversion-v1",
                            app.getRenderManager(), app.getViewPort(), resolution, surfaceBudget,
                            fraction -> !Thread.currentThread().isInterrupted());
                    surfaces.attachTo(result);
                }
                if (options.collision()) {
                    progress.progress("Generating collision controls...");
                    ModelCollisionPreparation.addMissing(result, options.collisionShape(), options.separateCollisionBodies());
                }
                return null;
            };
            try {
                if (app == null || app.isOgl()) {
                    capture.call();
                } else {
                    app.enqueue(capture).get();
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new java.io.IOException("Model conversion interrupted; output has not been written", error);
            } catch (Exception error) {
                throw new java.io.IOException("Model bake failed; output has not been written", error);
            }
        }
        return result;
    }

    private static void generateNormals(Mesh mesh) {
        Vector3f[] normals = new Vector3f[mesh.getVertexCount()];
        for (int i = 0; i < normals.length; i++) {
            normals[i] = new Vector3f();
        }
        int[] indices = new int[3];
        Vector3f a = new Vector3f();
        Vector3f b = new Vector3f();
        Vector3f c = new Vector3f();
        for (int i = 0; i < mesh.getTriangleCount(); i++) {
            mesh.getTriangle(i, indices);
            mesh.getTriangle(i, a, b, c);
            Vector3f normal = b.subtract(a).crossLocal(c.subtract(a));
            if (Vector3f.isValidVector(normal)) {
                for (int index : indices) {
                    normals[index].addLocal(normal);
                }
            }
        }
        for (Vector3f normal : normals) {
            if (normal.lengthSquared() > 0) {
                normal.normalizeLocal();
            } else {
                normal.set(Vector3f.UNIT_Y);
            }
        }
        mesh.setBuffer(Type.Normal, 3, BufferUtils.createFloatBuffer(normals));
    }
}
