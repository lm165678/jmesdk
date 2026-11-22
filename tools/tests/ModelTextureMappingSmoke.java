package com.jme3.gde.core.assets;

import com.jme3.asset.TextureKey;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.gde.texturetools.GeneratedTextures;
import com.jme3.material.Material;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.shape.Box;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;

/** Checks conversion preparation and both linked J3M and embedded J3O materials without a GPU. */
public final class ModelTextureMappingSmoke {
    public static void main(String[] args) {
        try {
            run(Path.of(args[0]));
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void run(Path work) throws Exception {
        Path root = Files.createTempDirectory(work, "model-mapping-").toAbsolutePath();
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xffff0000);
        ImageIO.write(image, "png", root.resolve("color.png").toFile());
        FileUtil.refreshFor(root.toFile());
        ProjectAssetManager manager = new ProjectAssetManager(FileUtil.toFileObject(root.toFile()));
        TextureKey key = new TextureKey("color.png", true);
        key.setGenerateMips(true);
        key.setAnisotropy(4);
        Texture original = manager.loadTexture(key);
        require(!original.getImage().getFormat().isCompressed(), "Source should initially load PNG");
        Material material = new Material(manager, "Common/MatDefs/Misc/Unshaded.j3md");
        material.setTexture("ColorMap", original);
        Geometry geometry = new Geometry("model", new Box(1, 1, 1));
        geometry.setMaterial(material);
        Node model = new Node("root");
        model.attachChild(geometry);
        ModelTexturePreparation.prepare(model, manager);
        GeneratedTextures store = new GeneratedTextures(root);
        Path output = store.resolve("color.png");
        require(output != null, "Conversion did not generate texture");
        Texture mapped = manager.loadTexture(key);
        require(mapped.getImage().getFormat().isCompressed(), "DDS variant was not loaded");
        require(mapped.getKey().equals(key), "Logical key or texture flags changed");
        require(mapped.getAnisotropicFilter() == 4, "Anisotropy lost");
        BinaryExporter.getInstance().save(model, root.resolve("model.j3o").toFile());
        FileUtil.refreshFor(root.toFile());
        Node restored = (Node) manager.loadModel("model.j3o");
        Texture embedded = ((Geometry) restored.getChild(0)).getMaterial().getTextureParam("ColorMap").getTextureValue();
        require(embedded.getImage().getFormat().isCompressed(), "Embedded J3O material bypassed mapping");
        require(embedded.getKey().getName().equals("color.png"), "J3O persisted a physical DDS key");
        Files.writeString(root.resolve("external.j3m"), "Material test : Common/MatDefs/Misc/Unshaded.j3md {\n"
                + "MaterialParameters {\nColorMap : color.png\n}\n}\n");
        Texture external = manager.loadMaterial("external.j3m").getTextureParam("ColorMap").getTextureValue();
        require(external.getImage().getFormat().isCompressed(), "External J3M bypassed mapping");
        manager.setUseGeneratedTextures(false);
        require(!manager.loadTexture(key).getImage().getFormat().isCompressed(), "Source preview switch failed");
        manager.setUseGeneratedTextures(true);
        require(manager.loadTexture(key).getImage().getFormat().isCompressed(), "DDS restore switch failed");
        Files.delete(output);
        require(!manager.loadTexture(key).getImage().getFormat().isCompressed(), "Missing DDS fallback failed");
        System.out.println("PASS conversion, logical key flags, external J3M, embedded J3O, switching and missing-output fallback");
        verifyEmbeddedGltf(root, manager, store);
    }

    private static void verifyEmbeddedGltf(Path root, ProjectAssetManager manager,
            GeneratedTextures store) throws Exception {
        byte[] pixels = new byte[]{
            1, 2, 3, 4,
            11, 12, 13, 14,
            21, 22, 23, 24,
            31, 32, 33, 34
        };
        BufferedImage unrelated = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        unrelated.setRGB(0, 0, 0xffabcdef);
        ImageIO.write(unrelated, "png", root.resolve("image0.png").toFile());
        byte[] unrelatedBytes = Files.readAllBytes(root.resolve("image0.png"));
        Node source = embeddedModel(manager, Image.Format.RGBA8, pixels);
        FileObject glb = file(root.resolve("Models/Embedded.glb"));
        ModelTexturePreparation.prepare(source, manager, glb);
        require(Arrays.equals(unrelatedBytes, Files.readAllBytes(root.resolve("image0.png"))),
                "Embedded extraction modified an unrelated root texture");
        Texture extracted = texture(source);
        TextureKey stableKey = (TextureKey) extracted.getKey();
        String stableName = stableKey.getName();
        require(stableName.startsWith("Models/Embedded.textures/image0-"), "Embedded key is not model-specific");
        require(stableName.endsWith(".png"), "Embedded source was not normalized to PNG");
        require(!stableKey.isFlipY(), "Extracted pixels would be flipped twice");
        require(stableKey.isGenerateMips() && stableKey.getAnisotropy() == 8, "TextureKey flags were lost");
        Path extractedFile = root.resolve(stableName);
        require(Files.isRegularFile(extractedFile), "Embedded PNG was not extracted");
        Path dds = store.resolve(stableName);
        require(dds != null, "Embedded PNG did not generate DDS");
        manager.setUseGeneratedTextures(false);
        Texture decoded = manager.loadTexture(stableKey);
        require(Arrays.equals(pixels, pixelBytes(decoded.getImage())), "Embedded pixel order changed");
        manager.setUseGeneratedTextures(true);
        BinaryExporter.getInstance().save(source, root.resolve("Models/Embedded.j3o").toFile());
        FileUtil.refreshFor(root.toFile());
        Node restored = (Node) manager.loadModel("Models/Embedded.j3o");
        Texture persisted = texture(restored);
        require(persisted.getKey().getName().equals(stableName), "J3O lost the stable embedded key");
        require(persisted.getImage().getFormat().isCompressed(), "Reloaded J3O did not use embedded DDS");
        System.out.println("PASS GLB extraction, pixel orientation, stable J3O key and DDS reload");

        var ddsTimestamp = Files.getLastModifiedTime(dds);
        Node repeated = embeddedModel(manager, Image.Format.RGBA8, pixels);
        ModelTexturePreparation.prepare(repeated, manager, glb);
        require(texture(repeated).getKey().getName().equals(stableName), "Equal content changed its stable key");
        require(Files.getLastModifiedTime(dds).equals(ddsTimestamp), "Fresh embedded DDS was rewritten");
        byte[] changedPixels = pixels.clone();
        changedPixels[0] = 99;
        byte[] originalExtractedBytes = Files.readAllBytes(extractedFile);
        Node changed = embeddedModel(manager, Image.Format.RGBA8, changedPixels);
        ModelTexturePreparation.prepare(changed, manager, glb);
        String changedName = texture(changed).getKey().getName();
        require(!changedName.equals(stableName), "Changed content reused the old hash key");
        require(Arrays.equals(originalExtractedBytes, Files.readAllBytes(extractedFile)),
                "Changed content overwrote the old extracted PNG");
        System.out.println("PASS embedded extraction reuse and non-overwriting content revisions");

        Node other = embeddedModel(manager, Image.Format.RGBA8, pixels);
        ModelTexturePreparation.prepare(other, manager, file(root.resolve("Models/Other.glb")));
        require(texture(other).getKey().getName().startsWith("Models/Other.textures/image0-"),
                "Two GLB files collided on image0.png");
        byte[] grayAlpha = new byte[]{0, 1, 63, 2, 127, 3, -1, 4};
        Node twoChannel = embeddedModel(manager, Image.Format.Luminance8Alpha8, grayAlpha);
        ModelTexturePreparation.prepare(twoChannel, manager, file(root.resolve("Models/Gray.glb")));
        TextureKey grayKey = (TextureKey) texture(twoChannel).getKey();
        manager.setUseGeneratedTextures(false);
        Texture grayDecoded = manager.loadTexture(grayKey);
        require(grayDecoded.getImage().getFormat() == Image.Format.Luminance8Alpha8,
                "Gray-alpha PNG changed channel layout");
        require(Arrays.equals(grayAlpha, pixelBytes(grayDecoded.getImage())), "Gray-alpha pixels changed");
        manager.setUseGeneratedTextures(true);
        require(store.resolve(grayKey.getName()) != null, "Gray-alpha embedded texture did not generate DDS");
        System.out.println("PASS model collision isolation and gray-alpha channel preservation");
        verifyRealGlb(root, manager, store, pixels);
    }

    private static void verifyRealGlb(Path root, ProjectAssetManager manager,
            GeneratedTextures store, byte[] pixels) throws Exception {
        Path path = root.resolve("Models/Real.glb");
        writeRealGlb(path, pixels);
        FileUtil.refreshFor(path.toFile());
        Node loaded = (Node) manager.loadModel("Models/Real.glb");
        Texture imported = texture(loaded);
        require(imported.getKey().getName().equals("image0.png"), "GLB loader did not use its stream key");
        require(!((TextureKey) imported.getKey()).isFlipY(), "Unexpected glTF default texture orientation");
        require(Arrays.equals(pixels, pixelBytes(imported.getImage())), "Real GLB/Stb decode changed pixels");
        ModelTexturePreparation.prepare(loaded, manager, file(path));
        TextureKey stableKey = (TextureKey) texture(loaded).getKey();
        require(stableKey.getName().startsWith("Models/Real.textures/image0-"),
                "Real GLB texture was not extracted");
        require(store.resolve(stableKey.getName()) != null, "Real GLB texture did not generate DDS");
        BinaryExporter.getInstance().save(loaded, root.resolve("Models/Real.j3o").toFile());
        FileUtil.refreshFor(root.toFile());
        Node restored = (Node) manager.loadModel("Models/Real.j3o");
        require(texture(restored).getKey().getName().equals(stableKey.getName()),
                "Real GLB J3O lost its extracted key");
        require(texture(restored).getImage().getFormat().isCompressed(),
                "Real GLB J3O did not reload generated DDS");
        System.out.println("PASS real GLB container, Stb decode, extraction, DDS and J3O round trip");
    }

    private static void writeRealGlb(Path path, byte[] pixels) throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < 4; i++) {
            int offset = i * 4;
            int argb = (pixels[offset] & 0xff) << 24
                    | (pixels[offset + 1] & 0xff) << 16
                    | (pixels[offset + 2] & 0xff) << 8
                    | pixels[offset + 3] & 0xff;
            image.setRGB(i % 2, i / 2, argb);
        }
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        require(ImageIO.write(image, "png", png), "No PNG writer for real GLB test");
        byte[] pngBytes = png.toByteArray();
        ByteBuffer binary = ByteBuffer.allocate(44 + pngBytes.length).order(ByteOrder.LITTLE_ENDIAN);
        float[] positions = new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0};
        for (float position : positions) {
            binary.putFloat(position);
        }
        binary.putShort((short) 0).putShort((short) 1).putShort((short) 2).putShort((short) 0);
        binary.put(pngBytes);
        String json = """
                {"asset":{"version":"2.0"},
                "buffers":[{"byteLength":%d}],
                "bufferViews":[
                {"buffer":0,"byteOffset":0,"byteLength":36,"target":34962},
                {"buffer":0,"byteOffset":36,"byteLength":6,"target":34963},
                {"buffer":0,"byteOffset":44,"byteLength":%d}],
                "accessors":[
                {"bufferView":0,"componentType":5126,"count":3,"type":"VEC3","min":[0,0,0],"max":[1,1,0]},
                {"bufferView":1,"componentType":5123,"count":3,"type":"SCALAR"}],
                "images":[{"bufferView":2,"mimeType":"image/png"}],
                "textures":[{"source":0}],
                "materials":[{"pbrMetallicRoughness":{"baseColorTexture":{"index":0}}}],
                "meshes":[{"primitives":[{"attributes":{"POSITION":0},"indices":1,"material":0}]}],
                "nodes":[{"mesh":0}],"scenes":[{"nodes":[0]}],"scene":0}
                """.formatted(binary.capacity(), pngBytes.length);
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        int jsonLength = (jsonBytes.length + 3) & ~3;
        int binaryLength = (binary.capacity() + 3) & ~3;
        ByteBuffer output = ByteBuffer.allocate(28 + jsonLength + binaryLength).order(ByteOrder.LITTLE_ENDIAN);
        output.putInt(0x46546c67).putInt(2).putInt(output.capacity());
        output.putInt(jsonLength).putInt(0x4e4f534a).put(jsonBytes);
        while (output.position() < 20 + jsonLength) {
            output.put((byte) 0x20);
        }
        output.putInt(binaryLength).putInt(0x004e4942).put(binary.array());
        while (output.hasRemaining()) {
            output.put((byte) 0);
        }
        Files.createDirectories(path.getParent());
        Files.write(path, output.array());
    }

    private static Node embeddedModel(ProjectAssetManager manager, Image.Format format, byte[] pixels) {
        ColorSpace colorSpace = format == Image.Format.RGB8 || format == Image.Format.RGBA8
                ? ColorSpace.sRGB : ColorSpace.Linear;
        Image image = new Image(format, 2, 2, BufferUtils.createByteBuffer(pixels), colorSpace);
        Texture2D texture = new Texture2D(image);
        TextureKey key = new TextureKey("image0.png", true);
        key.setGenerateMips(true);
        key.setAnisotropy(8);
        texture.setKey(key);
        Material material = new Material(manager, "Common/MatDefs/Misc/Unshaded.j3md");
        material.setTexture("ColorMap", texture);
        Geometry geometry = new Geometry("embedded", new Box(1, 1, 1));
        geometry.setMaterial(material);
        Node result = new Node("root");
        result.attachChild(geometry);
        return result;
    }

    private static Texture texture(Node model) {
        Texture[] result = new Texture[1];
        model.depthFirstTraversal(spatial -> {
            if (result[0] != null || !(spatial instanceof Geometry geometry) || geometry.getMaterial() == null) {
                return;
            }
            for (var parameter : geometry.getMaterial().getParams()) {
                if (parameter instanceof com.jme3.material.MatParamTexture textureParameter
                        && textureParameter.getTextureValue() != null) {
                    result[0] = textureParameter.getTextureValue();
                    return;
                }
            }
        });
        require(result[0] != null, "Model has no texture parameter");
        return result[0];
    }

    private static byte[] pixelBytes(Image image) {
        byte[] result = new byte[image.getWidth() * image.getHeight() * image.getFormat().getBitsPerPixel() / 8];
        ByteBuffer data = image.getData(0).duplicate();
        for (int i = 0; i < result.length; i++) {
            result[i] = data.get(i);
        }
        return result;
    }

    private static FileObject file(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        if (!Files.exists(path)) {
            Files.write(path, new byte[]{0});
        }
        FileUtil.refreshFor(path.toFile());
        return FileUtil.toFileObject(FileUtil.normalizeFile(path.toFile()));
    }
}
