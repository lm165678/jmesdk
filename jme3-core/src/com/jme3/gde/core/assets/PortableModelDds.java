package com.jme3.gde.core.assets;

import gr.zdimensions.jsquish.Squish;
import java.awt.AlphaComposite;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * BC3 encoder using bundled Java Squish. Worker-thread callers receive a status-bar
 * activity indicator with the current mip and dimensions; the encoder has no
 * within-mip progress callback.
 */
final class PortableModelDds {

    private PortableModelDds() {
    }

    static void write(BufferedImage image, Path path, int levels) throws IOException, InterruptedException {
        int width = image.getWidth();
        int height = image.getHeight();
        long rgbaSize = (long) width * height * 4;
        if (rgbaSize > 512L * 1024 * 1024) {
            throw new IOException("DDS texture exceeds the 512 MiB decoded pixel limit");
        }

        ByteBuffer header = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(0x20534444).putInt(124).putInt(levels > 1 ? 0xa1007 : 0x81007);
        header.putInt(height).putInt(width);
        header.putInt(Squish.getStorageRequirements(width, height, Squish.CompressionType.DXT5));
        header.putInt(0).putInt(levels);
        header.putInt(76, 32).putInt(80, 4).putInt(84, 0x35545844);
        header.putInt(108, levels > 1 ? 0x401008 : 0x1000);

        var progress = org.netbeans.api.progress.ProgressHandle.createHandle("Texture to DDS: " + path.getFileName());
        progress.setInitialDelay(0);
        progress.start();
        try (var output = Files.newOutputStream(path)) {
            output.write(header.array());
            for (int level = 0; level < levels; ++level) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("DDS conversion interrupted");
                }
                progress.progress("Compressing mip " + (level + 1) + "/" + levels
                        + " (" + image.getWidth() + " x " + image.getHeight() + ")");
                output.write(compress(image));
                if (level + 1 < levels) {
                    image = downsample(image);
                }
            }
        } finally {
            progress.finish();
        }
    }

    // Protect the bundled encoder's shared compression working state.
    private static synchronized byte[] compress(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] rgba = new byte[width * height * 4];
        int[] row = new int[width];
        int index = 0;
        for (int y = 0; y < height; ++y) {
            image.getRGB(0, y, width, 1, row, 0, width);
            for (int argb : row) {
                rgba[index++] = (byte) (argb >> 16);
                rgba[index++] = (byte) (argb >> 8);
                rgba[index++] = (byte) argb;
                rgba[index++] = (byte) (argb >>> 24);
            }
        }
        return Squish.compressImage(rgba, width, height, null, Squish.CompressionType.DXT5);
    }

    private static BufferedImage downsample(BufferedImage source) {
        int width = Math.max(1, source.getWidth() / 2);
        int height = Math.max(1, source.getHeight() / 2);
        BufferedImage mip = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        var graphics = mip.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return mip;
    }
}
