package com.jme3.gde.texturetools;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Path;
import com.jme3.asset.*;
import com.jme3.asset.plugins.FileLocator;
import com.jme3.texture.image.ImageRaster;

/** Raw vector data, never color-managed or gamma-decoded for normal filtering. */
final class SkinTextureReader {
    static BufferedImage read(Path path, byte[] bytes) throws IOException {
        BufferedImage image;
        try (var input = new javax.imageio.stream.MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = javax.imageio.ImageIO.getImageReaders(input);
            if (readers.hasNext()) {
                var reader = readers.next();
                try {
                    reader.setInput(input);
                    check(reader.getWidth(0), reader.getHeight(0));
                    return reader.read(0);
                } finally {
                    reader.dispose();
                }
            }
        }
        String extension = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (extension.endsWith(".dds") && bytes.length >= 128) {
            var header = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            check(header.getInt(16), header.getInt(12));
        }
        if (extension.endsWith(".astc") || extension.endsWith(".ktx")) {
            try {
                CompressedTexture compressed = CompressedTexture.read(path);
                check(compressed.mipWidth(0), compressed.mipHeight(0));
                TextureToolchain tools = TextureToolchain.installed();
                return tools.useNativeCodec() ? compressed.decodeNative(0, false) : compressed.preview(0, tools);
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
                throw new IOException("Normal decoding cancelled", cancelled);
            }
        }
        try {
            DesktopAssetManager assets = new DesktopAssetManager(true);
            assets.registerLocator(path.toAbsolutePath().getParent().toString(), FileLocator.class);
            TextureKey key = new TextureKey(path.getFileName().toString(), false);
            key.setGenerateMips(false);
            var decoded = assets.loadTexture(key).getImage();
            check(decoded.getWidth(), decoded.getHeight());
            if (decoded.getFormat().isCompressed()) {
                CompressedTexture compressed = CompressedTexture.read(path);
                TextureToolchain tools = TextureToolchain.installed();
                image = tools.useNativeCodec() ? compressed.decodeNative(0, false) : compressed.preview(0, tools);
                if (compressed.format().equals("BC5")) {
                    // BC5 tangent normals store XY; reconstruct positive Z.
                    for (int y = 0; y < image.getHeight(); y++) {
                        for (int x = 0; x < image.getWidth(); x++) {
                            int pixel = image.getRGB(x, y);
                            double nx = ((pixel >> 16) & 255) / 127.5 - 1;
                            double ny = ((pixel >> 8) & 255) / 127.5 - 1;
                            int z = (int) Math.round((Math.sqrt(Math.max(0, 1 - nx * nx - ny * ny)) * .5 + .5) * 255);
                            image.setRGB(x, y, (pixel & 0xffffff00) | z);
                        }
                    }
                }
                return image;
            }
            ImageRaster raster = ImageRaster.create(decoded, 0, 0, false);
            image = new BufferedImage(decoded.getWidth(), decoded.getHeight(), BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    var value = raster.getPixel(x, y);
                    int r = Math.round(Math.max(0, Math.min(1, value.r)) * 255);
                    int g = Math.round(Math.max(0, Math.min(1, value.g)) * 255);
                    int b = Math.round(Math.max(0, Math.min(1, value.b)) * 255);
                    image.setRGB(x, y, r << 16 | g << 8 | b);
                }
            }
            return image;
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw new IOException("Normal decoding cancelled", cancelled);
        } catch (RuntimeException failure) {
            throw new IOException("Cannot decode normal texture: " + path, failure);
        }
    }
    private static void check(int width, int height) throws IOException {
        if (width < 1 || height < 1 || (long) width * height > 4096L * 4096) {
            throw new IOException("Normal source exceeds 4096 x 4096 texels");
        }
    }
}
