package com.jme3.gde.texturetools;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** CPU codecs loaded directly from the installed SDK. */
final class NativeTextureCodec {
    private static final String LIBRARY = "jme-texture-codec";
    private static final long MAX_IMAGE_BYTES = 512L * 1024 * 1024;
    private static Path loaded;

    static Path library(Path root) {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        String host = os.startsWith("windows") ? "windows" : os.startsWith("mac") ? "macos" : os;
        if ("amd64".equals(arch)) {
            arch = "x86_64";
        } else if ("aarch64".equals(arch)) {
            arch = "arm64";
        }
        return root.resolve("jni").resolve(host + "-" + arch).resolve(System.mapLibraryName(LIBRARY));
    }

    static synchronized void load(Path library) throws IOException {
        Path canonical = library.toRealPath();
        if (loaded == null) {
            try {
                System.load(canonical.toString());
                loaded = canonical;
            } catch (LinkageError | SecurityException exception) {
                throw new IOException("Cannot load the SDK texture codec: " + library, exception);
            }
        } else if (!loaded.equals(canonical)) {
            throw new IOException("Texture codec is already loaded from " + loaded);
        }
    }

    static int bcId(String format) {
        return format.charAt(2) - '0';
    }

    static void writeDds(BufferedImage image, int codec, int levels, OutputStream output)
            throws IOException, InterruptedException {
        int width = image.getWidth();
        int height = image.getHeight();
        int maxLevels = 32 - Integer.numberOfLeadingZeros(Math.max(width, height));
        if (levels > maxLevels) {
            throw new IOException("Mip count must be between 1 and " + maxLevels);
        }
        byte[] base = encode(image, codec, 4, false);
        ByteBuffer header = ByteBuffer.allocate(codec == 7 ? 148 : 128).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(0x20534444).putInt(124).putInt(levels > 1 ? 0xa1007 : 0x81007);
        header.putInt(height).putInt(width).putInt(base.length).putInt(0).putInt(levels);
        header.putInt(76, 32).putInt(80, 4);
        int fourcc;
        switch (codec) {
            case 1:
                fourcc = 0x31545844; // DXT1
                break;
            case 2:
                fourcc = 0x33545844; // DXT3
                break;
            case 3:
                fourcc = 0x35545844; // DXT5
                break;
            case 4:
                fourcc = 0x31495441; // ATI1
                break;
            case 5:
                fourcc = 0x32495441; // ATI2
                break;
            default:
                fourcc = 0x30315844; // DX10
                break;
        }
        header.putInt(84, fourcc);
        header.putInt(108, levels > 1 ? 0x401008 : 0x1000);
        if (codec == 7) {
            header.putInt(128, 98).putInt(132, 3).putInt(140, 1);
        }
        output.write(header.array());
        output.write(base);
        for (int level = 1; level < levels; level++) {
            image = CompressedTexture.downsample(image, false);
            output.write(encode(image, codec, 4, false));
        }
    }

    static byte[] encode(BufferedImage image, int codec, int block, boolean srgb)
            throws IOException, InterruptedException {
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] rgba = new byte[pixelBytes(width, height)];
        int[] row = new int[width];
        int offset = 0;
        for (int y = 0; y < height; y++) {
            image.getRGB(0, y, width, 1, row, 0, width);
            for (int argb : row) {
                rgba[offset++] = (byte) (argb >>> 16);
                rgba[offset++] = (byte) (argb >>> 8);
                rgba[offset++] = (byte) argb;
                rgba[offset++] = (byte) (argb >>> 24);
            }
        }
        return transcode(rgba, width, height, codec, block, srgb, false);
    }

    static BufferedImage decode(byte[] blocks, int width, int height, int codec, int block, boolean srgb)
            throws IOException, InterruptedException {
        pixelBytes(width, height);
        byte[] rgba = transcode(blocks, width, height, codec, block, srgb, true);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[width];
        int offset = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = rgba[offset++] & 255;
                int g = rgba[offset++] & 255;
                int b = rgba[offset++] & 255;
                int alpha = rgba[offset++] & 255;
                row[x] = alpha << 24 | r << 16 | g << 8 | b;
            }
            image.setRGB(0, y, width, 1, row, 0, width);
        }
        return image;
    }

    private static int pixelBytes(int width, int height) throws IOException {
        long size = (long) width * height * 4;
        if (width < 1 || height < 1 || width > 32768 || height > 32768 || size > MAX_IMAGE_BYTES) {
            throw new IOException("Texture exceeds the codec's image size limit");
        }
        return (int) size;
    }

    // Serialize calls into Compressonator, which maintains shared codec state.
    private static synchronized byte[] transcode(byte[] data, int width, int height, int codec,
            int block, boolean srgb, boolean decode) throws IOException, InterruptedException {
        checkInterrupted();
        byte[] result = convert(data, width, height, codec, block, srgb, decode);
        checkInterrupted();
        return result;
    }

    static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Texture operation cancelled");
        }
    }

    private static native byte[] convert(byte[] data, int width, int height, int codec,
            int block, boolean srgb, boolean decode) throws IOException;
}
