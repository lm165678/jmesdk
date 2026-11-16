import com.jme3.gde.texturetools.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;

public class TextureToolsSmoke {
    public static void main(String[] args) {
        try {
            verify(args);
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static void verify(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Path work = Path.of(args[1]);
        Files.createDirectories(work);
        TextureToolchain tools = new TextureToolchain(root);
        BufferedImage image = new BufferedImage(32, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 32; x++) {
                image.setRGB(x, y, 0xff000000 | (x * 8 << 16) | (y * 16 << 8) | 100);
            }
        }
        Path source = work.resolve("source with spaces.png");
        ImageIO.write(image, "png", source.toFile());
        for (String format : new String[]{"BC1", "BC3", "BC5", "BC7", "ASTC"}) {
            Path output = work.resolve(format + (format.equals("ASTC") ? ".ktx" : ".dds"));
            if (format.equals("ASTC")) {
                CompressedTexture.encodeAstcKtx(source, output, true, "6x6", 4, tools);
            } else {
                tools.dds(source, output, format, 4);
            }
            CompressedTexture texture = CompressedTexture.read(output);
            if (texture.levels() != 4) {
                throw new AssertionError("Mip count: " + format + " " + texture.levels());
            }
            for (int mip = 0; mip < 4; mip++) {
                BufferedImage preview = texture.preview(mip, tools);
                if (preview.getWidth() != (32 >> mip) || preview.getHeight() != (16 >> mip)) {
                    throw new AssertionError("Mip size " + format);
                }
                ImageIO.write(preview, "png", work.resolve(format + "-mip" + mip + ".png").toFile());
            }
            Path replacement = work.resolve("replacement.png");
            BufferedImage red = new BufferedImage(16, 8, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 16; x++) {
                    red.setRGB(x, y, 0xffff0000);
                }
            }
            ImageIO.write(red, "png", replacement.toFile());
            byte[] before = Files.readAllBytes(output);
            Path edited = work.resolve("edited-" + output.getFileName());
            texture.replaceMip(1, replacement, edited, tools);
            if (!java.util.Arrays.equals(before, Files.readAllBytes(output))) {
                throw new AssertionError("Source changed");
            }
            CompressedTexture changed = CompressedTexture.read(edited);
            BufferedImage changedPreview = changed.preview(1, tools);
            if (changedPreview.getWidth() != 16 || ((changedPreview.getRGB(4, 4) >>> 16) & 255) < 220) {
                throw new AssertionError("Replacement failed");
            }
            byte[] after = Files.readAllBytes(edited);
            int lastMipBytes = format.equals("BC1") ? 8 : 16;
            if (!java.util.Arrays.equals(java.util.Arrays.copyOfRange(before, before.length - lastMipBytes, before.length),
                    java.util.Arrays.copyOfRange(after, after.length - lastMipBytes, after.length))) {
                throw new AssertionError("Unedited tail mip changed");
            }
            try {
                texture.replaceMip(1, source, work.resolve("invalid-size.dds"), tools);
                throw new AssertionError("Accepted wrong-sized replacement");
            } catch (java.io.IOException expected) {
                // A base-level image must not silently resize into a custom mip.
            }
            System.out.println("PASS " + format + " encode / 4 mip previews / replace");
        }
        Path invalid = work.resolve("truncated.dds");
        Files.write(invalid, new byte[]{68, 68, 83, 32});
        try {
            CompressedTexture.read(invalid);
            throw new AssertionError("Accepted truncated DDS");
        } catch (java.io.IOException expected) {
            System.out.println("PASS truncated input rejected");
        }
        CompressedTextureResolver resolver = new CompressedTextureResolver();
        for (String name : new String[]{"BC3.dds", "ASTC.ktx"}) {
            var file = org.openide.filesystems.FileUtil.toFileObject(work.resolve(name).toAbsolutePath().toFile());
            if (!CompressedTextureResolver.MIME.equals(resolver.findMIMEType(file))) {
                throw new AssertionError("Compressed texture MIME routing failed: " + name);
            }
        }
        var pngFile = org.openide.filesystems.FileUtil.toFileObject(source.toAbsolutePath().toFile());
        if (resolver.findMIMEType(pngFile) != null) {
            throw new AssertionError("PNG routing was intercepted");
        }
        byte[] ordinary = Files.readAllBytes(work.resolve("ASTC.ktx"));
        java.nio.ByteBuffer.wrap(ordinary).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(28, 0x8058);
        Path otherKtx = work.resolve("ordinary.ktx");
        Files.write(otherKtx, ordinary);
        if (resolver.findMIMEType(org.openide.filesystems.FileUtil.toFileObject(otherKtx.toAbsolutePath().toFile())) != null) {
            throw new AssertionError("Non-ASTC KTX routing was intercepted");
        }
        System.out.println("PASS MIME routing and ordinary texture fallback");
        Path preserved = work.resolve("preserved.dds");
        byte[] sentinel = new byte[]{1, 2, 3, 4};
        Files.write(preserved, sentinel);
        try {
            new TextureToolchain(root, java.time.Duration.ofNanos(1)).dds(source, preserved, "BC3", 1);
            throw new AssertionError("Expected timeout");
        } catch (java.io.IOException expected) {
            if (!java.util.Arrays.equals(sentinel, Files.readAllBytes(preserved))) {
                throw new AssertionError("Failed encoding replaced destination");
            }
            System.out.println("PASS timeout preserves destination");
        }
    }
}
