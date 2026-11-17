package com.jme3.gde.textureeditor;

import com.jme3.math.FastMath;
import com.jme3.texture.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Converts linear half-float JME images to displayable, tone-mapped AWT images. */
final class HdrImageConverter {
    private HdrImageConverter() {
    }

    /**
     * Applies exposure, Reinhard tone mapping and the sRGB transfer function.
     * <pre>
     * // 第一步：用 StbImageLoader 以 flipY=false 读取 Radiance HDR。
     * Image hdr = new StbImageLoader().load(inputStream, false);
     * // 第二步：以 0 EV 生成预览；正曝光提高亮度，负曝光降低亮度。
     * BufferedImage preview = HdrImageConverter.toBufferedImage(hdr, 0f);
     * // 第三步：preview 仅用于查看，不能覆盖原 HDR；线性 half-float 数据仍在 hdr 中。
     * // 第四步：高亮使用 value/(1+value)，不是简单 clamp，因此大于 1 的颜色仍保留渐变。
     * </pre>
     */
    static BufferedImage toBufferedImage(Image source, float exposureEv) throws IOException {
        int channels = switch (source.getFormat()) {
            case RGB16F, RGBA16F -> source.getFormat() == Image.Format.RGB16F ? 3 : 4;
            case Luminance16F -> 1;
            case Luminance16FAlpha16F -> 2;
            default -> throw new IOException("Unsupported HDR preview format: " + source.getFormat());
        };
        int width = source.getWidth();
        int height = source.getHeight();
        ByteBuffer data = source.getData(0);
        if (width <= 0 || height <= 0 || data == null) {
            throw new IOException("HDR image has no pixel data");
        }
        long required = (long) width * height * channels * 2;
        if (data.remaining() < required) {
            throw new IOException("Truncated HDR pixel data: expected " + required
                    + " bytes, got " + data.remaining());
        }
        ByteBuffer input = data.duplicate().order(ByteOrder.nativeOrder());
        BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        float exposure = (float) Math.pow(2.0, exposureEv);
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float first = half(input);
                float red;
                float green;
                float blue;
                float alpha = 1f;
                if (channels >= 3) {
                    red = first;
                    green = half(input);
                    blue = half(input);
                    if (channels == 4) {
                        alpha = half(input);
                    }
                } else {
                    red = green = blue = first;
                    if (channels == 2) {
                        alpha = half(input);
                    }
                }
                int r = display(red, exposure);
                int g = display(green, exposure);
                int b = display(blue, exposure);
                int a = Math.round(clamp(alpha) * 255f);
                row[x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
            result.setRGB(0, y, width, 1, row, 0, width);
        }
        return result;
    }

    private static float half(ByteBuffer input) {
        return FastMath.convertHalfToFloat(input.getShort());
    }

    private static int display(float linear, float exposure) {
        if (Float.isNaN(linear) || linear <= 0f) {
            return 0;
        }
        float mapped;
        if (linear == Float.POSITIVE_INFINITY) {
            mapped = 1f;
        } else {
            float exposed = linear * exposure;
            mapped = exposed / (1f + exposed);
        }
        mapped = clamp(mapped);
        float srgb = mapped <= 0.0031308f
                ? mapped * 12.92f
                : (float) (1.055 * Math.pow(mapped, 1.0 / 2.4) - 0.055);
        return Math.round(clamp(srgb) * 255f);
    }

    private static float clamp(float value) {
        if (Float.isNaN(value) || value <= 0f) {
            return 0f;
        }
        return Math.min(value, 1f);
    }
}
