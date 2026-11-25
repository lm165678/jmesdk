package com.jme3.gde.texturetools;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Single-face 2D DDS (BC1/2/3/4/5/7 or RGBA16F), raw ASTC and ASTC KTX1 mip inspection.
 * Unsupported arrays, cubes, volumes and formats fail explicitly. CPU decoding
 * works independently of renderer capabilities. Mip replacement preserves every
 * other compressed level byte-for-byte and requires a matching source size.
 * All methods performing IO must run off the EDT. Source files are not mutated.
 * <pre>
 * // 第一步：读取压缩纹理；mip 索引从 0 开始。
 * CompressedTexture texture = CompressedTexture.read(Path.of("image.dds"));
 * // 第二步：后台解码指定 mip，再在 EDT 上显示 BufferedImage。
 * BufferedImage preview = texture.preview(0, TextureToolchain.installed());
 * // 第三步：替换 mip 必须使用相同尺寸的源图，并写入新的输出文件。
 * texture.replaceMip(1, Path.of("mip1.png"), Path.of("edited.dds"), TextureToolchain.installed());
 * // 第四步：重新打开原文件即可恢复；自动生成资产还须由上层保存覆盖输入。
 * </pre>
 */
public final class CompressedTexture {
    private static final byte[] KTX = {(byte) 0xab, 0x4b, 0x54, 0x58, 0x20, 0x31, 0x31,
        (byte) 0xbb, 0x0d, 0x0a, 0x1a, 0x0a};
    private final byte[] data;
    private final List<Integer> offsets = new ArrayList<>();
    private final List<Integer> sizes = new ArrayList<>();
    private final int width;
    private final int height;
    private final String format;
    private final int block;
    private final boolean srgb;
    private final boolean astc;
    private final int header;

    private CompressedTexture(byte[] bytes) throws IOException {
        data = bytes;
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        if (data.length >= 128 && buffer.getInt(0) == 0x20534444) {
            astc = false;
            width = buffer.getInt(16);
            height = buffer.getInt(12);
            int count = Math.max(1, buffer.getInt(28));
            if (buffer.getInt(4) != 124 || buffer.getInt(76) != 32 || buffer.getInt(112) != 0
                    || buffer.getInt(24) > 1) {
                throw new IOException("Only single-face 2D DDS is supported");
            }
            int fourcc = buffer.getInt(84);
            int stride;
            boolean color = false;
            if (fourcc == 0x30315844 && data.length >= 148) {
                header = 148;
                int dxgi = buffer.getInt(128);
                if (buffer.getInt(132) != 3 || buffer.getInt(140) != 1 || (buffer.getInt(136) & 4) != 0) {
                    throw new IOException("DDS arrays, volumes and cubemaps are unsupported");
                }
                format = switch (dxgi) {
                    case 71, 72 -> "BC1";
                    case 74, 75 -> "BC2";
                    case 77, 78 -> "BC3";
                    case 80 -> "BC4";
                    case 83 -> "BC5";
                    case 98, 99 -> "BC7";
                    default -> throw new IOException("Unsupported DDS DXGI format: " + dxgi);
                };
                color = dxgi == 72 || dxgi == 75 || dxgi == 78 || dxgi == 99;
            } else {
                header = 128;
                format = switch (fourcc) {
                    case 113 -> "RGBA16F";
                    case 0 -> "RGBA8";
                    case 0x31545844 -> "BC1";
                    case 0x33545844 -> "BC2";
                    case 0x35545844 -> "BC3";
                    case 0x31495441, 0x55344342 -> "BC4";
                    case 0x32495441, 0x55354342 -> "BC5";
                    default -> throw new IOException("Unsupported DDS FourCC");
                };
            }
            srgb = color;
            if (format.equals("RGBA8") && (buffer.getInt(88) != 32 || buffer.getInt(92) != 0xff
                    || buffer.getInt(96) != 0xff00 || buffer.getInt(100) != 0xff0000 || buffer.getInt(104) != 0xff000000)) {
                throw new IOException("Unsupported raw DDS channel layout");
            }
            block = format.startsWith("RGBA") ? 1 : 4;
            stride = format.equals("RGBA8") ? 4 : format.equals("RGBA16F") || format.equals("BC1") || format.equals("BC4") ? 8 : 16;
            validateDimensions(count);
            int offset = header;
            for (int level = 0; level < count; level++) {
                int size = payloadSize(mipWidth(level), mipHeight(level), block, stride);
                addLevel(offset, size);
                offset += size;
            }
        } else if (data.length >= 16 && buffer.getInt(0) == 0x5ca1ab13) {
            astc = true;
            header = 16;
            block = data[4] & 255;
            if (block != (data[5] & 255) || data[6] != 1 || uint24(data, 13) != 1) {
                throw new IOException("Only square-block 2D ASTC is supported");
            }
            width = uint24(data, 7);
            height = uint24(data, 10);
            srgb = false;
            format = "ASTC " + block + "x" + block + " (profile unspecified)";
            validateDimensions(1);
            addLevel(16, payloadSize(width, height, block, 16));
        } else if (data.length >= 64 && Arrays.equals(Arrays.copyOf(data, 12), KTX)) {
            astc = true;
            header = 64;
            if (buffer.getInt(12) != 0x04030201 || buffer.getInt(16) != 0 || buffer.getInt(20) != 1
                    || buffer.getInt(24) != 0 || buffer.getInt(44) != 0 || buffer.getInt(48) != 0
                    || buffer.getInt(52) != 1) {
                throw new IOException("Only little-endian, single-face compressed 2D KTX1 is supported");
            }
            int internal = buffer.getInt(28);
            srgb = internal >= 0x93d0 && internal <= 0x93dd;
            int index = internal - (srgb ? 0x93d0 : 0x93b0);
            block = switch (index) {
                case 0 -> 4;
                case 2 -> 5;
                case 4 -> 6;
                case 7 -> 8;
                case 11 -> 10;
                case 13 -> 12;
                default -> throw new IOException("Only square-block ASTC KTX1 is supported");
            };
            format = "ASTC " + block + "x" + block;
            width = buffer.getInt(36);
            height = buffer.getInt(40);
            int count = Math.max(1, buffer.getInt(56));
            validateDimensions(count);
            long position = 64L + Integer.toUnsignedLong(buffer.getInt(60));
            for (int level = 0; level < count; level++) {
                if (position + 4 > data.length) {
                    throw new IOException("Truncated KTX mip header");
                }
                int size = buffer.getInt((int) position);
                if (size != payloadSize(mipWidth(level), mipHeight(level), block, 16)) {
                    throw new IOException("Incorrect KTX ASTC mip size");
                }
                addLevel((int) position + 4, size);
                position += 4L + size + ((4 - size % 4) % 4);
            }
        } else {
            throw new IOException("Expected BC DDS, ASTC KTX1, or raw ASTC");
        }
    }

    /** Reads supported 2D containers, rejecting truncated or invalid mip chains. */
    public static CompressedTexture read(Path file) throws IOException {
        if (Files.size(file) > 512L * 1024 * 1024) {
            throw new IOException("Texture exceeds the 512 MiB editor limit");
        }
        return new CompressedTexture(Files.readAllBytes(file));
    }

    /** Returns the stored mip count, including the base level. */
    public int levels() {
        return offsets.size();
    }

    /** Returns the width of a zero-based mip level. */
    public int mipWidth(int level) {
        return Math.max(1, width >> level);
    }

    /** Returns the height of a zero-based mip level. */
    public int mipHeight(int level) {
        return Math.max(1, height >> level);
    }

    /** Returns the detected codec and, for ASTC, block footprint. */
    public String format() {
        return format;
    }

    /** Returns the stored sRGB flag, if present. Raw ASTC and legacy DDS lack this metadata. */
    public boolean srgb() {
        return srgb;
    }

    private void validateDimensions(int count) throws IOException {
        if (width < 1 || height < 1 || width > 32768 || height > 32768 || block < 1
                || count < 1 || count > 32 - Integer.numberOfLeadingZeros(Math.max(width, height))) {
            throw new IOException("Invalid texture dimensions or mip count");
        }
    }

    private static int payloadSize(int width, int height, int block, int stride) throws IOException {
        long size = ((width + block - 1L) / block) * ((height + block - 1L) / block) * stride;
        if (size > Integer.MAX_VALUE) {
            throw new IOException("Texture payload is too large");
        }
        return (int) size;
    }

    private void addLevel(int offset, int size) throws IOException {
        if (offset < 0 || size < 0 || (long) offset + size > data.length) {
            throw new IOException("Truncated texture mip data");
        }
        offsets.add(offset);
        sizes.add(size);
    }

    /** Decodes a selected mip using the bundled CPU tools, without a GPU context. */
    public BufferedImage preview(int level, TextureToolchain tools) throws IOException, InterruptedException {
        if (format.equals("RGBA16F")) {
            return halfPreview(level);
        }
        if (format.equals("RGBA8")) {
            BufferedImage image = new BufferedImage(mipWidth(level), mipHeight(level), BufferedImage.TYPE_INT_RGB);
            int offset = offsets.get(level);
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int rgb = (data[offset] & 255) << 16 | (data[offset + 1] & 255) << 8 | (data[offset + 2] & 255);
                    image.setRGB(x, y, rgb);
                    offset += 4;
                }
            }
            return image;
        }
        if (tools.useNativeCodec()) {
            return decodeNative(level, srgb);
        }
        Path source = Files.createTempFile("texture-mip-", astc ? ".astc" : ".dds");
        Path output = Files.createTempFile("texture-preview-", ".png");
        try {
            Files.write(source, singleLevel(level));
            tools.decode(source, output, astc, srgb);
            BufferedImage image = ImageIO.read(output.toFile());
            if (image == null) {
                throw new IOException("Decoder produced no readable image");
            }
            return image;
        } finally {
            Files.deleteIfExists(source);
            Files.deleteIfExists(output);
        }
    }

    BufferedImage decodeNative(int level, boolean srgbProfile) throws IOException, InterruptedException {
        int offset = offsets.get(level);
        byte[] blocks = Arrays.copyOfRange(data, offset, offset + sizes.get(level));
        return NativeTextureCodec.decode(blocks, mipWidth(level), mipHeight(level),
                astc ? 0 : NativeTextureCodec.bcId(format), block, srgbProfile);
    }

    private BufferedImage halfPreview(int level) {
        BufferedImage image = new BufferedImage(mipWidth(level), mipHeight(level), BufferedImage.TYPE_INT_RGB);
        ByteBuffer pixels = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        pixels.position(offsets.get(level));
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = 0;
                for (int c = 0; c < 4; c++) {
                    float linear = com.jme3.math.FastMath.convertHalfToFloat(pixels.getShort());
                    if (c < 3) {
                        double display = linear <= .0031308 ? linear * 12.92 : 1.055 * Math.pow(linear, 1.0 / 2.4) - .055;
                        rgb = rgb << 8 | Math.max(0, Math.min(255, (int) Math.round(display * 255)));
                    }
                }
                image.setRGB(x, y, rgb);
            }
        }
        return image;
    }

    private byte[] singleLevel(int level) {
        int size = sizes.get(level);
        byte[] result = new byte[(astc ? 16 : header) + size];
        if (astc) {
            ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).putInt(0x5ca1ab13);
            result[4] = (byte) block;
            result[5] = (byte) block;
            result[6] = 1;
            put24(result, 7, mipWidth(level));
            put24(result, 10, mipHeight(level));
            put24(result, 13, 1);
        } else {
            System.arraycopy(data, 0, result, 0, header);
            ByteBuffer b = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(8, (b.getInt(8) | 0x80000) & ~0x20000 & ~8);
            b.putInt(12, mipHeight(level));
            b.putInt(16, mipWidth(level));
            b.putInt(20, size);
            b.putInt(28, 1);
            b.putInt(108, 0x1000);
        }
        System.arraycopy(data, offsets.get(level), result, astc ? 16 : header, size);
        return result;
    }

    /** Re-encodes one mip from an exact-size image; other mip bytes stay intact. */
    public void replaceMip(int level, Path source, Path destination, TextureToolchain tools)
            throws IOException, InterruptedException {
        if (format.startsWith("RGBA")) {
            throw new IOException("Regenerate raw DDS through the source tool; compressed mip replacement does not apply");
        }
        BufferedImage image = ImageIO.read(source.toFile());
        if (image == null || image.getWidth() != mipWidth(level) || image.getHeight() != mipHeight(level)) {
            throw new IOException("Replacement image must match selected mip dimensions");
        }
        if (tools.useNativeCodec()) {
            byte[] replacement = NativeTextureCodec.encode(image,
                    astc ? 0 : NativeTextureCodec.bcId(format), block, srgb);
            if (replacement.length != sizes.get(level)) {
                throw new IOException("Replacement payload size mismatch");
            }
            tools.writeNative(destination, stream -> {
                int offset = offsets.get(level);
                stream.write(data, 0, offset);
                stream.write(replacement);
                stream.write(data, offset + replacement.length, data.length - offset - replacement.length);
            });
            return;
        }
        Path encoded = Files.createTempFile("texture-replacement-", astc ? ".astc" : ".dds");
        try {
            if (astc) {
                tools.astc(source, encoded, srgb, block + "x" + block);
            } else {
                tools.dds(source, encoded, format, 1);
            }
            CompressedTexture replacement = read(encoded);
            if (!sizes.get(level).equals(replacement.sizes.get(0))) {
                throw new IOException("Replacement payload size mismatch");
            }
            byte[] edited = data.clone();
            System.arraycopy(replacement.data, replacement.offsets.get(0), edited, offsets.get(level), sizes.get(level));
            Files.write(destination, edited);
        } finally {
            Files.deleteIfExists(encoded);
        }
    }

    /**
     * Generates a square-block ASTC KTX1 mip chain from an LDR image. Filtering is
     * a box over the source footprint in linear light for sRGB RGB channels;
     * alpha remains linear. Odd dimensions include the final source row/column.
     * This generic filter does not renormalize normal maps or preserve alpha coverage.
     * Zero levels requests the complete chain.
     */
    public static void encodeAstcKtx(Path source, Path destination, boolean srgb, String block,
            int levels, TextureToolchain tools) throws IOException, InterruptedException {
        BufferedImage original = ImageIO.read(source.toFile());
        if (original == null) {
            throw new IOException("Unreadable source image");
        }
        int maximum = 32 - Integer.numberOfLeadingZeros(Math.max(original.getWidth(), original.getHeight()));
        int count = levels == 0 ? maximum : levels;
        if (count < 1 || count > maximum) {
            throw new IOException("Mip count must be between 1 and " + maximum);
        }
        int index = switch (block) {
            case "4x4" -> 0;
            case "5x5" -> 2;
            case "6x6" -> 4;
            case "8x8" -> 7;
            case "10x10" -> 11;
            case "12x12" -> 13;
            default -> throw new IOException("Unsupported ASTC block size");
        };
        ByteBuffer head = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        head.put(KTX).putInt(0x04030201).putInt(0).putInt(1).putInt(0)
                .putInt((srgb ? 0x93d0 : 0x93b0) + index).putInt(0x1908)
                .putInt(original.getWidth()).putInt(original.getHeight()).putInt(0).putInt(0)
                .putInt(1).putInt(count).putInt(0);
        if (tools.useNativeCodec()) {
            int blockSize = Integer.parseInt(block.substring(0, block.indexOf('x')));
            tools.writeNative(destination, stream -> {
                stream.write(head.array());
                BufferedImage current = original;
                for (int level = 0; level < count; level++) {
                    byte[] payload = NativeTextureCodec.encode(current, 0, blockSize, srgb);
                    stream.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                            .putInt(payload.length).array());
                    stream.write(payload);
                    if (level + 1 < count) {
                        current = downsample(current, srgb);
                    }
                }
            });
            return;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(head.array());
        Path png = Files.createTempFile("texture-level-", ".png");
        Path encoded = Files.createTempFile("texture-level-", ".astc");
        try {
            BufferedImage current = original;
            for (int level = 0; level < count; level++) {
                ImageIO.write(current, "png", png.toFile());
                tools.astc(png, encoded, srgb, block);
                CompressedTexture texture = read(encoded);
                int size = texture.sizes.get(0);
                out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(size).array());
                out.write(texture.data, 16, size);
                if (level + 1 < count) {
                    current = downsample(current, srgb);
                }
            }
            Files.write(destination, out.toByteArray());
        } finally {
            Files.deleteIfExists(png);
            Files.deleteIfExists(encoded);
        }
    }

    static BufferedImage downsample(BufferedImage input, boolean srgb) {
        BufferedImage output = new BufferedImage(Math.max(1, input.getWidth() / 2),
                Math.max(1, input.getHeight() / 2), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < output.getHeight(); y++) {
            for (int x = 0; x < output.getWidth(); x++) {
                int pixel = 0;
                for (int channel = 0; channel < 4; channel++) {
                    double sum = 0;
                    int samples = 0;
                    int x0 = x * input.getWidth() / output.getWidth();
                    int x1 = (x + 1) * input.getWidth() / output.getWidth();
                    int y0 = y * input.getHeight() / output.getHeight();
                    int y1 = (y + 1) * input.getHeight() / output.getHeight();
                    for (int sy = y0; sy < y1; sy++) {
                        for (int sx = x0; sx < x1; sx++) {
                            double value = ((input.getRGB(sx, sy) >>> (channel * 8)) & 255) / 255.0;
                            if (srgb && channel < 3) {
                                value = value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
                            }
                            sum += value;
                            samples++;
                        }
                    }
                    double value = sum / samples;
                    if (srgb && channel < 3) {
                        value = value <= 0.0031308 ? value * 12.92 : 1.055 * Math.pow(value, 1.0 / 2.4) - 0.055;
                    }
                    pixel |= Math.min(255, Math.max(0, (int) Math.round(value * 255))) << (channel * 8);
                }
                output.setRGB(x, y, pixel);
            }
        }
        return output;
    }

    private static int uint24(byte[] bytes, int offset) {
        return (bytes[offset] & 255) | ((bytes[offset + 1] & 255) << 8) | ((bytes[offset + 2] & 255) << 16);
    }

    static void put24(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >> 8);
        bytes[offset + 2] = (byte) (value >> 16);
    }
}
