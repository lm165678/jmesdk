package com.jme3.gde.texturetools;

import com.jme3.material.skin.SkinLutGenerator;
import com.jme3.material.skin.SkinNormalFilter;
import java.awt.color.ColorSpace;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/** Raw image I/O and vector-space resampling; compressed sources use the CPU texture tools. */
final class SkinNormalAssets {
    static final class Product {
        final SkinNormalFilter.Result normals;
        final SkinLutGenerator.Result lut;
        final SkinGenerationSettings settings;
        final byte[] original;
        final BufferedImage detailImage;
        final String extension;
        final float sourceDensity;
        final float effectiveDensity;
        final SkinNormalFilter.Boundary boundary;
        final SkinNormalFilter.Quality quality;

        Product(SkinNormalFilter.Result normals, SkinLutGenerator.Result lut, SkinGenerationSettings settings,
                byte[] original, BufferedImage detailImage, String extension, float sourceDensity, float effectiveDensity,
                SkinNormalFilter.Boundary boundary, SkinNormalFilter.Quality quality) {
            this.normals = normals;
            this.lut = lut;
            this.settings = settings;
            this.original = original;
            this.detailImage = detailImage;
            this.extension = extension;
            this.sourceDensity = sourceDensity;
            this.effectiveDensity = effectiveDensity;
            this.boundary = boundary;
            this.quality = quality;
        }
    }

    private SkinNormalAssets() {
    }

    static Product generate(Path source, SkinGenerationSettings settings, int maximumSize,
            float density, SkinNormalFilter.Boundary boundary, SkinNormalFilter.Quality quality) throws IOException {
        String name = source.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot).toLowerCase(java.util.Locale.ROOT);
        if (!SkinTextureInput.isTexture(name)) {
            throw new IOException("Choose a supported RGB normal texture (DDS, PNG, JPEG, TGA, BMP...)");
        }
        if (Files.size(source) > 128L * 1024 * 1024) {
            throw new IOException("Normal source exceeds 128 MiB");
        }
        byte[] original = Files.readAllBytes(source);
        BufferedImage image = SkinTextureReader.read(source, original);
        if (image.getColorModel().getNumColorComponents() != 3 || image.getRaster().getNumBands() < 3
                || image.getColorModel().getColorSpace().getType() != ColorSpace.TYPE_RGB) {
            throw new IOException("The normal map must contain RGB XYZ data");
        }
        double scale = Math.min(1.0, (double) maximumSize / Math.max(image.getWidth(), image.getHeight()));
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        float[] encoded = resample(image, width, height);
        float effectiveDensity = density * (float) scale;
        SkinNormalFilter.Result normals = SkinNormalFilter.generate(encoded, width, height, settings.profile,
                effectiveDensity, boundary, quality);
        SkinLutGenerator.Result lut = settings.generateLut();
        return new Product(normals, lut, settings, original, image, extension, density, effectiveDensity, boundary, quality);
    }

    private static float[] resample(BufferedImage image, int width, int height) {
        Raster raster = image.getRaster();
        int[] sizes = raster.getSampleModel().getSampleSize();
        double[] divisors = {Math.pow(2.0, sizes[0]) - 1.0, Math.pow(2.0, sizes[1]) - 1.0,
            Math.pow(2.0, sizes[2]) - 1.0};
        float[] data = new float[width * height * 3];
        double sx = (double) image.getWidth() / width;
        double sy = (double) image.getHeight() / height;
        for (int y = 0; y < height; y++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException();
            }
            for (int x = 0; x < width; x++) {
                double[] sum = new double[3];
                for (int iy = (int) Math.floor(y * sy); iy < Math.min(image.getHeight(), Math.ceil((y + 1) * sy)); iy++) {
                    double wy = Math.min((y + 1) * sy, iy + 1.0) - Math.max(y * sy, iy);
                    for (int ix = (int) Math.floor(x * sx); ix < Math.min(image.getWidth(), Math.ceil((x + 1) * sx)); ix++) {
                        double weight = wy * (Math.min((x + 1) * sx, ix + 1.0) - Math.max(x * sx, ix));
                        double nx = raster.getSampleDouble(ix, iy, 0) / divisors[0] * 2.0 - 1.0;
                        double ny = raster.getSampleDouble(ix, iy, 1) / divisors[1] * 2.0 - 1.0;
                        double nz = raster.getSampleDouble(ix, iy, 2) / divisors[2] * 2.0 - 1.0;
                        double length = Math.sqrt(nx * nx + ny * ny + nz * nz);
                        if (length > 1e-10) {
                            sum[0] += weight * nx / length;
                            sum[1] += weight * ny / length;
                            sum[2] += weight * nz / length;
                        } else {
                            sum[2] += weight;
                        }
                    }
                }
                double length = Math.sqrt(sum[0] * sum[0] + sum[1] * sum[1] + sum[2] * sum[2]);
                for (int c = 0; c < 3; c++) {
                    data[(y * width + x) * 3 + c] = (float) ((length > 1e-10 ? sum[c] / length : (c == 2 ? 1.0 : 0.0)) * .5 + .5);
                }
            }
        }
        return data;
    }

    static BufferedImage image(SkinNormalFilter.Result normals, int channel) {
        BufferedImage image = new BufferedImage(normals.getWidth(), normals.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < normals.getHeight(); y++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException();
            }
            for (int x = 0; x < normals.getWidth(); x++) {
                int rgb = 0;
                for (int c = 0; c < 3; c++) {
                    int value = Math.round((normals.get(channel, x, y, c) * .5f + .5f) * 255f);
                    rgb = (rgb << 8) | Math.max(0, Math.min(255, value));
                }
                image.setRGB(x, y, rgb);
            }
        }
        return image;
    }

    static Path targetMaterial(Path root, String key) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        Path target = base.resolve(key).normalize();
        if (!target.startsWith(base) || !key.toLowerCase(java.util.Locale.ROOT).endsWith(".j3m")
                || !Files.isRegularFile(target)) {
            throw new IOException("Choose an existing skin J3M inside this project's assets folder");
        }
        String text = Files.readString(target);
        if (!text.contains(SkinLutGenerator.MATERIAL) || !text.matches("(?s).*\\bSkinLut\\s*:.*")) {
            throw new IOException("Target must be a PreintegratedSkin material with a SkinLut texture");
        }
        return target;
    }

    static SkinGenerationSettings targetSettings(Path target, SkinGenerationSettings fallback) throws IOException {
        Path metadata = Path.of(target.toString().replaceFirst("(?i)\\.j3m$", ".skin.properties"));
        if (!Files.isRegularFile(metadata)) {
            return fallback;
        }
        var stored = SkinLutExport.read(metadata);
        return new SkinGenerationSettings(stored.profile, stored.width, stored.height, stored.curvature,
                stored.samples, "Stored target material profile");
    }

    /** Publishes only normals and updates an explicitly selected material, retaining its LUT and tuning. */
    static List<Path> writeToMaterial(Path root, String key, String materialKey, Product product,
            boolean fourNormals, boolean directX, boolean replace) throws IOException {
        synchronized (SkinLutExport.class) {
            Path target = targetMaterial(root, materialKey);
            List<Path> base = SkinLutExport.targets(root, key);
            Map<Path, byte[]> files = new LinkedHashMap<>(SkinLutExport.encode(root, key, product.lut,
                    product.settings.profile, product.settings.samples, product.settings.description));
            String generated = new String(files.remove(base.get(1)), StandardCharsets.UTF_8);
            files.remove(base.get(0));
            String prefix = root.toAbsolutePath().normalize().relativize(base.get(0)).toString().replace('\\', '/');
            prefix = prefix.substring(0, prefix.length() - 4);
            StringBuilder bindings = new StringBuilder();
            appendNormals(files, base.get(0), prefix, product, fourNormals, directX, bindings);
            generated = generated.replace("    MaterialParameters {\n", "    MaterialParameters {\n" + bindings);
            String previous = Files.readString(target);
            String merged = SkinLutExport.mergeParameters(previous, generated, java.util.Set.of("NormalMap", "NormalType",
                    "SkinDiffuseNormalMap", "SkinRedNormalMap", "SkinGreenNormalMap", "SkinBlueNormalMap"));
            for (Path file : files.keySet()) {
                if (Files.exists(file) && !replace) {
                    throw new java.nio.file.FileAlreadyExistsException(file.toString());
                }
            }
            files.put(target, merged.getBytes(StandardCharsets.UTF_8));
            SkinLutExport.writeAssets(root, key, files, true, target);
            return new ArrayList<>(files.keySet());
        }
    }

    static List<Path> write(Path root, String key, Product product, boolean fourNormals, boolean directX,
            boolean replace) throws IOException {
        // Both tabs can publish asynchronously to the same material.
        synchronized (SkinLutExport.class) {
            return writeLocked(root, key, product, fourNormals, directX, replace);
        }
    }

    private static List<Path> writeLocked(Path root, String key, Product product, boolean fourNormals,
            boolean directX, boolean replace) throws IOException {
        Map<Path, byte[]> files = new LinkedHashMap<>(SkinLutExport.encode(root, key, product.lut,
                product.settings.profile, product.settings.samples, product.settings.description));
        List<Path> base = SkinLutExport.targets(root, key);
        String material = new String(files.get(base.get(1)), StandardCharsets.UTF_8);
        String prefix = root.toAbsolutePath().normalize().relativize(base.get(0)).toString().replace('\\', '/');
        prefix = prefix.substring(0, prefix.length() - 4);
        StringBuilder bindings = new StringBuilder();
        appendNormals(files, base.get(0), prefix, product, fourNormals, directX, bindings);
        material = material.replace("    MaterialParameters {\n", "    MaterialParameters {\n" + bindings);
        material = SkinLutExport.mergeMaterial(root, key, material, java.util.Set.of("SkinLut", "SkinLutSize", "SkinMaxCurvature",
                "NormalMap", "NormalType", "SkinDiffuseNormalMap", "SkinRedNormalMap", "SkinGreenNormalMap", "SkinBlueNormalMap"));
        files.put(base.get(1), material.getBytes(StandardCharsets.UTF_8));
        String metadata = new String(files.get(base.get(2)), StandardCharsets.UTF_8)
                + "normal.mode=" + (fourNormals ? "four" : "two") + "\nnormal.type=" + (directX ? -1 : 1)
                + "\nnormal.sourceDensity=" + product.sourceDensity + "\nnormal.effectiveDensity=" + product.effectiveDensity
                + "\nnormal.quality=" + product.quality + "\nnormal.boundary=" + product.boundary
                + "\nnormal.width=" + product.normals.getWidth() + "\nnormal.height=" + product.normals.getHeight() + "\n";
        files.put(base.get(2), metadata.getBytes(StandardCharsets.UTF_8));
        SkinLutExport.writeAssets(root, key, files, replace);
        return new ArrayList<>(files.keySet());
    }
    private static void appendNormals(Map<Path, byte[]> files, Path imagePath, String prefix, Product product,
            boolean fourNormals, boolean directX, StringBuilder bindings) {
        bindings.append("        NormalType : ").append(directX ? "-1.0" : "1.0").append('\n');
        bindings.append("        NormalMap : MinTrilinear WrapEdgeClamp \"").append(prefix).append("-detail")
                .append(".dds").append("\"\n");
        files.put(Path.of(imagePath.toString().replaceFirst("\\.dds$", "-detail.dds")),
                product.extension.equals(".dds") ? product.original : TextureToolchain.linearRgbaDds(product.detailImage));
        String[] parameters = fourNormals ? new String[]{"SkinRedNormalMap", "SkinGreenNormalMap", "SkinBlueNormalMap"}
                : new String[]{"SkinDiffuseNormalMap"};
        for (int c = 0; c < parameters.length; c++) {
            String suffix = fourNormals ? new String[]{"-diffuse-r.dds", "-diffuse-g.dds", "-diffuse-b.dds"}[c] : "-diffuse.dds";
            byte[] dds = TextureToolchain.linearRgbaDds(image(product.normals, c));
            Path target = Path.of(imagePath.toString().replaceFirst("\\.dds$", suffix));
            files.put(target, dds);
            bindings.append("        ").append(parameters[c]).append(" : MinTrilinear WrapEdgeClamp \"")
                    .append(prefix).append(suffix).append("\"\n");
        }
    }

}
