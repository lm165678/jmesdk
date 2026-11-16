package com.jme3.gde.core.assets;

import com.jme3.asset.TextureKey;
import com.jme3.gde.texturetools.GeneratedTextures;
import com.jme3.gde.texturetools.TextureToolchain;
import com.jme3.material.MatParamTexture;
import com.jme3.scene.Geometry;
import com.jme3.scene.Spatial;
import com.jme3.texture.Texture;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;
import org.openide.filesystems.FileUtil;

/**
 * Generates PC DDS dependencies on source-model conversion, before J3O output
 * is opened. Material texture keys stay logical, including embedded materials.
 * PNG/JPEG Texture2D sources under the primary asset root are deduplicated.
 * Imported compressed images, procedural/embedded pixels without keys, non-2D
 * textures, and dependency-JAR assets are preserved with explicit skip messages.
 * Compression failures abort conversion and leave any existing J3O intact.
 * Full mip chains use the encoder's default filtering; no specialized normal-map
 * renormalization or alpha-coverage adjustment is implied.
 * <pre>
 * 第一步：复制模型及原始纹理到项目资源目录。
 * 第二步：Convert to J3O 自动生成源纹理旁 dds/原文件名.png.dds。
 * 第三步：重复转换校验源图、输出哈希和编码参数；过期或缺失时重建。
 * 第四步：材质仍保存 PNG/JPG 逻辑 key，SDK 加载时通过登记表选择 DDS。
 * 第五步：普通保存已有 J3O 不再次压缩；恢复原图预览可关闭生成纹理解析。
 * </pre>
 */
final class ModelTexturePreparation {
    private static final Logger LOG = Logger.getLogger(ModelTexturePreparation.class.getName());

    static void prepare(Spatial model, ProjectAssetManager assets) throws IOException {
        if (assets == null || assets.getAssetFolder() == null) {
            throw new IOException("Texture conversion requires a project asset root");
        }
        var folder = FileUtil.toFile(assets.getAssetFolder());
        if (folder == null) {
            throw new IOException("Texture conversion requires local asset files");
        }
        Map<String, Boolean> keys = new LinkedHashMap<>();
        model.depthFirstTraversal(spatial -> {
            if (spatial instanceof Geometry geometry && geometry.getMaterial() != null) {
                for (var parameter : geometry.getMaterial().getParams()) {
                    if (parameter instanceof MatParamTexture textureParameter) {
                        Texture texture = textureParameter.getTextureValue();
                        if (texture != null && texture.getType() == Texture.Type.TwoDimensional
                                && texture.getKey() instanceof TextureKey key
                                && key.getClass() == TextureKey.class) {
                            String name = key.getName();
                            String lower = name.toLowerCase(java.util.Locale.ROOT);
                            if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
                                // A previously mapped DDS still has the source key: revalidate it too.
                                boolean compressed = texture.getImage() != null && texture.getImage().getFormat().isCompressed();
                                keys.merge(name, compressed, (first, second) -> first && second);
                            }
                        } else if (texture != null) {
                            LOG.info("Skipping non-file or non-2D texture: " + parameter.getName());
                        }
                    }
                }
            }
        });
        if (keys.isEmpty()) {
            return;
        }
        GeneratedTextures store = new GeneratedTextures(folder.toPath());
        TextureToolchain tools = TextureToolchain.installed();
        for (String key : keys.keySet()) {
            if (keys.get(key) && store.source(key) == null) {
                LOG.info("Skipping already compressed imported texture: " + key);
                continue;
            }
            if (assets.getAssetFolder().getFileObject(key) == null && store.source(key) == null) {
                LOG.warning("Skipping texture outside the primary asset root: " + key);
                continue;
            }
            try {
                store.ensure(key, tools);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Texture conversion interrupted", exception);
            }
        }
        assets.clearCache();
        assets.getAssetFolder().refresh();
    }
}
