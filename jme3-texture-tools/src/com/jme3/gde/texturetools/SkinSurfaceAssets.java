package com.jme3.gde.texturetools;

import com.jme3.material.skin.SkinSurfaceBaker;
import com.jme3.texture.Image;
import com.jme3.texture.image.ColorSpace;
import com.jme3.texture.image.ImageRaster;
import com.jme3.math.ColorRGBA;
import com.jme3.util.BufferUtils;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Offline linear surface data and transactional updates of a selected skin material. */
final class SkinSurfaceAssets {
    private SkinSurfaceAssets() {
    }

    static Image image(float[] values, boolean[] covered, int size, float range, int padding, float background) {
        float[] data = values.clone();
        boolean[] valid = covered.clone();
        for (int pass = 0; pass < padding; pass++) {
            float[] next = data.clone();
            boolean[] nextValid = valid.clone();
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int pixel = y * size + x;
                    if (valid[pixel]) {
                        continue;
                    }
                    float sum = 0;
                    int count = 0;
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int xx = x + dx;
                            int yy = y + dy;
                            if (xx >= 0 && xx < size && yy >= 0 && yy < size && valid[yy * size + xx]) {
                                sum += data[yy * size + xx];
                                count++;
                            }
                        }
                    }
                    if (count > 0) {
                        next[pixel] = sum / count;
                        nextValid[pixel] = true;
                    }
                }
            }
            data = next;
            valid = nextValid;
        }
        Image image = new Image(Image.Format.RGBA16F, size, size,
                BufferUtils.createByteBuffer(size * size * 8), ColorSpace.Linear);
        ImageRaster raster = ImageRaster.create(image);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int pixel = y * size + x;
                float value = valid[pixel] ? Math.max(0, Math.min(1, data[pixel] / range)) : background;
                raster.setPixel(x, y, new ColorRGBA(value, value, value, 1));
            }
        }
        return image;
    }

    static List<Path> write(Path root, String key, String materialKey, SkinSurfaceBaker.Result data,
            float units, float maximumThickness, float maximumCurvature, int padding, boolean replace) throws Exception {
        synchronized (SkinLutExport.class) {
            Path target = SkinNormalAssets.targetMaterial(root, materialKey);
            List<Path> base = SkinLutExport.targets(root, key);
            Path thickness = Path.of(base.get(0).toString().replaceFirst("\\.dds$", "-thickness.dds"));
            Path curvature = Path.of(base.get(0).toString().replaceFirst("\\.dds$", "-curvature.dds"));
            Map<Path, byte[]> files = new LinkedHashMap<>();
            files.put(thickness, TextureToolchain.linearHalfDds(image(data.thickness, data.covered, data.size,
                    maximumThickness, padding, 1)));
            files.put(curvature, TextureToolchain.linearHalfDds(image(data.curvature, data.covered, data.size,
                    maximumCurvature, padding, 0)));
            Path assetRoot = root.toAbsolutePath().normalize();
            String thickKey = assetRoot.relativize(thickness).toString().replace('\\', '/');
            String curveKey = assetRoot.relativize(curvature).toString().replace('\\', '/');
            String generated = "Material Skin : com.jme3.placeholder {\n    MaterialParameters {\n"
                    + "        SkinThicknessMap : MinBilinearNoMipMaps MagBilinear WrapEdgeClamp \"" + thickKey + "\"\n"
                    + "        SkinCurvatureMap : MinBilinearNoMipMaps MagBilinear WrapEdgeClamp \"" + curveKey + "\"\n"
                    + "        SkinThickness : " + maximumThickness + "\n"
                    + "        SkinWorldToMillimeters : " + units + "\n"
                    + "        SkinTransmission : true\n    }\n}\n";
            String merged = SkinLutExport.mergeParameters(Files.readString(target), generated,
                    Set.of("SkinThicknessMap", "SkinCurvatureMap", "SkinThickness", "SkinWorldToMillimeters", "SkinTransmission"));
            String metadata = Files.isRegularFile(base.get(2)) ? Files.readString(base.get(2))
                    : "generator=jme.preintegrated.skin\n";
            metadata += "\nsurface.size=" + data.size + "\nsurface.millimetersPerUnit=" + units
                    + "\nsurface.maximumThickness=" + maximumThickness + "\nsurface.padding=" + padding + "\n";
            files.put(base.get(2), metadata.getBytes(StandardCharsets.UTF_8));
            if (!replace) {
                for (Path path : files.keySet()) {
                    if (Files.exists(path)) {
                        throw new java.nio.file.FileAlreadyExistsException(path.toString());
                    }
                }
            }
            files.put(target, merged.getBytes(StandardCharsets.UTF_8));
            SkinLutExport.writeAssets(root, key, files, true, target);
            return List.copyOf(files.keySet());
        }
    }
}
