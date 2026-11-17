package com.jme3.gde.textureeditor;

import com.jme3.math.FastMath;
import com.jme3.texture.Image;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;

/**
 * CPU-only texture I/O and HDR preview conversion smoke test; no Swing or OpenGL.
 * <pre>
 * 第一步：写出并重新读取 PNG/JPG/TGA，验证普通编辑链路。
 * 第二步：构造 RGB16F 线性像素，验证 HDR tone mapping 不直接截断高亮。
 * 第三步：生成真实 Radiance .hdr，经过 StbImageLoader 和 tone mapping 验证显示值。
 * 第四步：验证损坏图片抛出 IOException，HDR 写回被明确拒绝。
 * 运行前提：JDK、SDK 与 NetBeans runtime classpath 及 Stb 原生库已准备好；成功输出
 * TEXTURE_EDITOR_SMOKE_PASS。本测试不覆盖真实 Swing 界面。
 * </pre>
 */
public final class TextureEditorSmoke {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("jme-texture-editor-test");
        try {
            BufferedImage source = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < source.getHeight(); y++) {
                for (int x = 0; x < source.getWidth(); x++) {
                    source.setRGB(x, y, (10 << 16) | (128 << 8) | 240);
                }
            }
            IOModule io = IOModule.create();
            for (String extension : new String[]{"png", "jpg", "tga"}) {
                Path path = directory.resolve("image." + extension);
                io.store(source, extension, path.toFile());
                FileObject root = FileUtil.toFileObject(FileUtil.normalizeFile(directory.toFile()));
                root.refresh();
                BufferedImage loaded = io.load(root.getFileObject("image." + extension));
                require(loaded.getWidth() == 3 && loaded.getHeight() == 2,
                        extension + " dimensions changed");
                if (!"jpg".equals(extension)) {
                    require((loaded.getRGB(1, 1) & 0x00ffffff) == 0x0a80f0,
                            extension + " RGB channel order changed");
                }
            }
            Files.write(directory.resolve("broken.png"), new byte[]{1, 2, 3, 4});
            FileObject root = FileUtil.toFileObject(FileUtil.normalizeFile(directory.toFile()));
            root.refresh();
            requireThrows(() -> io.load(root.getFileObject("broken.png")),
                    "Corrupt PNG did not produce IOException");
            requireThrows(() -> io.store(source, "hdr", directory.resolve("x.hdr").toFile()),
                    "HDR write-back was not rejected");
            testHdrConversion();
            testHdrFile(io, root);
            System.out.println("TEXTURE_EDITOR_SMOKE_PASS");
        } finally {
            try (var paths = Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
    }

    private static void testHdrConversion() throws IOException {
        ByteBuffer data = BufferUtils.createByteBuffer(2 * 3 * 2);
        put(data, 1f);
        put(data, 4f);
        put(data, 0.25f);
        put(data, 0f);
        put(data, 0.0031308f);
        put(data, 2f);
        data.flip();
        Image hdr = new Image(Image.Format.RGB16F, 2, 1, data, ColorSpace.Linear);
        BufferedImage preview = HdrImageConverter.toBufferedImage(hdr, 0f);
        int high = preview.getRGB(0, 0);
        require(((high >> 16) & 0xff) == 188, "Reinhard 1.0 should map to sRGB 188");
        require(((high >> 8) & 0xff) == 231, "HDR highlight was clipped instead of tone mapped");
        require((high & 0xff) == 124, "Reinhard 0.25 should map to sRGB 124");
        require((preview.getRGB(1, 0) & 0x00ff0000) == 0, "Zero HDR channel should remain black");
    }

    private static void testHdrFile(IOModule io, FileObject root) throws Exception {
        ByteArrayOutputStream hdr = new ByteArrayOutputStream();
        hdr.write("#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y 1 +X 2\n"
                .getBytes(StandardCharsets.US_ASCII));
        // Shared exponent 131 gives scale 1/32: (32,128,8) => (1,4,0.25).
        hdr.write(new byte[]{32, 128, 8, (byte) 131});
        hdr.write(new byte[]{0, 0, 0, 0});
        Files.write(FileUtil.toFile(root).toPath().resolve("preview.hdr"), hdr.toByteArray());
        root.refresh();
        BufferedImage preview = io.load(root.getFileObject("preview.hdr"));
        int high = preview.getRGB(0, 0);
        require(((high >> 16) & 0xff) == 188, "Stb HDR red conversion mismatch");
        require(((high >> 8) & 0xff) == 231, "Stb HDR green conversion mismatch");
        require((high & 0xff) == 124, "Stb HDR blue conversion mismatch");
        require((preview.getRGB(1, 0) & 0x00ffffff) == 0, "Stb HDR black pixel mismatch");
    }

    private static void put(ByteBuffer data, float value) {
        data.putShort(FastMath.convertFloatToHalf(value));
    }

    private static void requireThrows(Action action, String message) throws Exception {
        try {
            action.run();
            throw new RuntimeException(message);
        } catch (IOException expected) {
            // Expected explicit, user-reportable failure.
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new RuntimeException(message);
        }
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }
}
