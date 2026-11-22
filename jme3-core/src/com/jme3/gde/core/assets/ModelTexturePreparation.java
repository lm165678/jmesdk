package com.jme3.gde.core.assets;

import com.jme3.asset.TextureKey;
import com.jme3.gde.texturetools.GeneratedTextures;
import com.jme3.gde.texturetools.TextureToolchain;
import com.jme3.material.MatParamTexture;
import com.jme3.scene.Geometry;
import com.jme3.scene.Spatial;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import java.awt.Transparency;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.ComponentColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import jme3tools.converters.ImageToAwt;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;

/**
 * Generates PC DDS dependencies on source-model conversion, before J3O output
 * is opened. ufbx imports use direct DDS keys; glTF retains logical image keys.
 * PNG/JPEG Texture2D sources under the primary asset root are deduplicated.
 * For GLB and glTF root-level keys named imageN.png/jpg/jpeg, loaded pixels are
 * decoded into a model-specific ".textures" directory. This covers bufferView
 * and data-URI images and also disambiguates the rare external root URI with the
 * same synthetic name; an unrelated root imageN.png can never capture the J3O
 * reference. Other external texture paths retain their original logical keys.
 * The PNG file name contains its full SHA-256, so equal pixels reuse one stable
 * key and changed pixels never overwrite the earlier source. The saved key uses
 * flipY=false because the extracted PNG already contains the decoded row order;
 * mip, anisotropy and texture-type hints are retained.
 * Only non-compressed 8-bit luminance, luminance-alpha, RGB and RGBA pixels are
 * extracted. Other formats keep their original data/key and log a warning; this
 * does not claim WebP, KTX2, 16-bit or compressed-image DDS support. Extraction
 * and compression I/O failures abort conversion before the J3O is opened.
 * Imported compressed images, procedural pixels without keys, non-2D textures,
 * and dependency-JAR assets are otherwise preserved with explicit skip messages.
 * Full mip chains use the encoder's default filtering; no specialized normal-map
 * renormalization or alpha-coverage adjustment is implied.
 * <pre>
 * 第一步：复制模型及原始纹理到项目资源目录，GLB 无需手工拆出内嵌 PNG/JPEG。
 * 第二步：Convert to J3O 自动解到 模型名.textures/imageN-内容哈希.png。
 * 第三步：同一转换继续生成该 PNG 旁 dds/文件名.png.dds，并把稳定 key 写入 J3O。
 * 第四步：重复转换复用同内容文件和有效 DDS；内容变化生成新哈希文件，不覆盖旧图。
 * 第五步：普通外部 PNG/JPG 仍在原位置生成 DDS，材质逻辑 key 保持不变。
 * 第六步：给已有 J3O 绑定材质后保存，只补齐材质引用的、尚无 DDS 的 PNG/JPEG。
 * </pre>
 */
final class ModelTexturePreparation {
    private static final Logger LOG = Logger.getLogger(ModelTexturePreparation.class.getName());
    private static final Pattern GLTF_STREAM_NAME = Pattern.compile("image[0-9]+\\.[a-z0-9]+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SUPPORTED_STREAM_NAME = Pattern.compile("image[0-9]+\\.(png|jpg|jpeg)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern UFBX_STREAM_NAME = Pattern.compile("ufbx-image[0-9]+\\.png");

    static void prepare(Spatial model, ProjectAssetManager assets) throws IOException {
        prepare(model, assets, null);
    }

    static void prepare(Spatial model, ProjectAssetManager assets, FileObject sourceModel) throws IOException {
        if (assets == null || assets.getAssetFolder() == null) {
            throw new IOException("Texture conversion requires a project asset root");
        }
        var folder = FileUtil.toFile(assets.getAssetFolder());
        if (folder == null) {
            throw new IOException("Texture conversion requires local asset files");
        }
        Path root = folder.toPath().toAbsolutePath().normalize();
        GeneratedTextures store = new GeneratedTextures(root);
        Set<Texture> textures = textures(model);
        Map<String, Boolean> keys = new LinkedHashMap<>();
        boolean fbx = sourceModel != null && "fbx".equalsIgnoreCase(sourceModel.getExt());
        for (Texture texture : textures) {
            if (texture.getType() != Texture.Type.TwoDimensional
                    || !(texture.getKey() instanceof TextureKey key)
                    || key.getClass() != TextureKey.class) {
                LOG.info("Skipping non-file or non-2D texture: " + texture.getName());
                continue;
            }
            String name = key.getName();
            boolean ufbxPixels = fbx && UFBX_STREAM_NAME.matcher(name).matches();
            boolean embedded = ufbxPixels || hasGltfStreamTextureName(sourceModel, name);
            if (embedded) {
                if (!ufbxPixels && !SUPPORTED_STREAM_NAME.matcher(name).matches()) {
                    LOG.warning("Skipping unsupported embedded glTF texture format: " + name);
                    continue;
                }
                String extracted = extractEmbedded(texture, key, root, sourceModel);
                if (extracted == null) {
                    if (ufbxPixels) {
                        throw new IOException("Cannot generate DDS from FBX texture " + name);
                    }
                    continue;
                }
                name = extracted;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
                // A previously mapped DDS still has the source key: revalidate it too.
                boolean compressed = texture.getImage() != null && texture.getImage().getFormat().isCompressed();
                keys.merge(name, compressed, (first, second) -> first && second);
            }
        }
        if (keys.isEmpty()) {
            return;
        }
        assets.getAssetFolder().refresh();
        TextureToolchain tools = TextureToolchain.installed();
        boolean portableDds = fbx && !tools.hasConfiguredDdsEncoder();
        Map<String, Path> outputs = new LinkedHashMap<>();
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
                Path output;
                if (!fbx) {
                    output = store.ensureLogicalDds(key, tools);
                } else if (portableDds) {
                    output = store.ensurePortableBc3(key, PortableModelDds::write);
                } else {
                    output = store.ensure(key, tools);
                }
                if (output != null) {
                    outputs.put(key, output);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Texture conversion interrupted", exception);
            }
        }
        if (fbx) {
            for (Texture texture : textures) {
                if (texture.getKey() instanceof TextureKey key) {
                    Path output = outputs.get(key.getName());
                    if (output != null) {
                        TextureKey ddsKey = new TextureKey(root.relativize(output).toString().replace('\\', '/'),
                                key.isFlipY());
                        ddsKey.setGenerateMips(key.isGenerateMips());
                        ddsKey.setAnisotropy(key.getAnisotropy());
                        ddsKey.setTextureTypeHint(key.getTextureTypeHint());
                        texture.setKey(ddsKey);
                    }
                }
            }
        }
        assets.clearCache();
        assets.getAssetFolder().refresh();
    }

    static void prepareMissing(Spatial model, ProjectAssetManager assets) throws IOException {
        if (assets == null || assets.getAssetFolder() == null) {
            return;
        }
        FileObject assetFolder = assets.getAssetFolder();
        var folder = FileUtil.toFile(assetFolder);
        if (folder == null) {
            return;
        }
        var store = new GeneratedTextures(folder.toPath());
        Set<String> keys = new java.util.HashSet<>();
        for (Texture texture : textures(model)) {
            if (texture.getType() == Texture.Type.TwoDimensional
                    && texture.getKey() instanceof TextureKey key && key.getClass() == TextureKey.class
                    && keys.add(key.getName())) {
                try {
                    store.ensureMissingPortableBc3(key.getName(), PortableModelDds::write);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException("DDS generation interrupted", exception);
                }
            }
        }
        assetFolder.refresh();
    }

    private static Set<Texture> textures(Spatial model) {
        Set<Texture> result = Collections.newSetFromMap(new IdentityHashMap<>());
        model.depthFirstTraversal(spatial -> {
            if (spatial instanceof Geometry geometry && geometry.getMaterial() != null) {
                for (var parameter : geometry.getMaterial().getParams()) {
                    if (parameter instanceof MatParamTexture texture && texture.getTextureValue() != null) {
                        result.add(texture.getTextureValue());
                    }
                }
            }
        });
        return result;
    }

    private static boolean hasGltfStreamTextureName(FileObject sourceModel, String name) {
        if (sourceModel == null || !GLTF_STREAM_NAME.matcher(name).matches()) {
            return false;
        }
        String extension = sourceModel.getExt().toLowerCase(Locale.ROOT);
        return extension.equals("glb") || extension.equals("gltf");
    }

    private static String extractEmbedded(Texture texture, TextureKey originalKey, Path root,
            FileObject sourceModel) throws IOException {
        Image image = texture.getImage();
        String oldName = originalKey.getName();
        if (image == null || image.getData(0) == null) {
            LOG.warning("Skipping model texture without decoded pixels: " + oldName);
            return null;
        }
        if (image.getFormat().isCompressed()) {
            LOG.warning("Skipping already compressed model texture: " + oldName);
            return null;
        }
        if (!supportsPngExtraction(image.getFormat())) {
            LOG.warning("Skipping non-8-bit model texture; no DDS was generated: " + oldName
                    + " (" + image.getFormat() + ")");
            return null;
        }
        BufferedImage decoded = toAwt(image);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(decoded, "png", bytes)) {
            throw new IOException("No PNG writer for model texture: " + oldName);
        }
        byte[] encoded = bytes.toByteArray();
        String hash = sha256(encoded);
        Path model = localModelPath(sourceModel);
        Path parent = model.getParent();
        if (parent == null || !parent.toRealPath().startsWith(root.toRealPath())) {
            throw new IOException("Embedded texture source model is outside the asset root: " + sourceModel.getPath());
        }
        Path directory = parent.resolve(sourceModel.getName() + ".textures");
        Files.createDirectories(directory);
        if (!directory.toRealPath().startsWith(root.toRealPath())) {
            throw new IOException("Embedded texture directory escapes the asset root: " + directory);
        }
        String base = oldName.substring(0, oldName.lastIndexOf('.'));
        Path output = directory.resolve(base + "-" + hash + ".png");
        boolean created = writeNewContent(output, encoded, root);
        String stableName = root.relativize(output).toString().replace('\\', '/');
        TextureKey stableKey = new TextureKey(stableName, false);
        stableKey.setGenerateMips(originalKey.isGenerateMips());
        stableKey.setAnisotropy(originalKey.getAnisotropy());
        stableKey.setTextureTypeHint(originalKey.getTextureTypeHint());
        texture.setKey(stableKey);
        texture.setName(stableName);
        LOG.info((created ? "Extracted" : "Reused") + " model texture: " + oldName + " -> " + stableName);
        return stableName;
    }

    private static Path localModelPath(FileObject sourceModel) throws IOException {
        var file = FileUtil.toFile(sourceModel);
        if (file == null) {
            throw new IOException("Embedded texture extraction requires a local source model");
        }
        return file.toPath().toAbsolutePath().normalize();
    }

    private static boolean writeNewContent(Path output, byte[] encoded, Path root) throws IOException {
        if (Files.exists(output)) {
            verifyExistingContent(output, encoded, root);
            return false;
        }
        Path temporary = Files.createTempFile(output.getParent(), ".embedded-texture-", ".png");
        try {
            Files.write(temporary, encoded);
            try {
                Files.move(temporary, output);
                return true;
            } catch (FileAlreadyExistsException exception) {
                verifyExistingContent(output, encoded, root);
                return false;
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void verifyExistingContent(Path output, byte[] encoded, Path root) throws IOException {
        if (!output.toRealPath().startsWith(root.toRealPath())) {
            throw new IOException("Embedded texture output escapes the asset root: " + output);
        }
        if (!Files.isRegularFile(output) || !Arrays.equals(encoded, Files.readAllBytes(output))) {
            throw new IOException("Embedded texture hash collision or changed destination: " + output);
        }
    }

    private static boolean supportsPngExtraction(Image.Format format) {
        return switch (format) {
            case Luminance8, Luminance8Alpha8, RGB8, RGBA8 -> true;
            default -> false;
        };
    }

    private static BufferedImage toAwt(Image image) {
        if (image.getFormat() == Image.Format.Luminance8) {
            BufferedImage result = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_BYTE_GRAY);
            WritableRaster raster = result.getRaster();
            ByteBuffer data = image.getData(0).duplicate();
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    raster.setSample(x, y, 0, data.get(y * image.getWidth() + x) & 0xff);
                }
            }
            return result;
        }
        if (image.getFormat() == Image.Format.Luminance8Alpha8) {
            ComponentColorModel model = new ComponentColorModel(ColorSpace.getInstance(ColorSpace.CS_GRAY),
                    new int[]{8, 8}, true, false, Transparency.TRANSLUCENT, DataBuffer.TYPE_BYTE);
            WritableRaster raster = Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE,
                    image.getWidth(), image.getHeight(), image.getWidth() * 2, 2, new int[]{0, 1}, null);
            BufferedImage result = new BufferedImage(model, raster, false, null);
            ByteBuffer data = image.getData(0).duplicate();
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int offset = (y * image.getWidth() + x) * 2;
                    raster.setSample(x, y, 0, data.get(offset) & 0xff);
                    raster.setSample(x, y, 1, data.get(offset + 1) & 0xff);
                }
            }
            return result;
        }
        return ImageToAwt.convert(image, false, false, 0);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
