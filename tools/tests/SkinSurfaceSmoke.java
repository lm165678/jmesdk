package com.jme3.gde.texturetools;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.asset.MaterialKey;
import com.jme3.asset.plugins.FileLocator;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.material.Material;
import com.jme3.material.skin.SkinLutGenerator;
import com.jme3.material.skin.SkinSurfaceBaker;
import com.jme3.scene.Geometry;
import com.jme3.scene.shape.Sphere;
import com.jme3.scene.shape.Quad;
import com.jme3.texture.image.ColorSpace;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import javax.swing.*;

/** Isolated geometry, export and EDT/worker integration checks; never starts SDK. */
public final class SkinSurfaceSmoke {
    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
    private static Object field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory(Path.of(args[0]), "surface-assets-");
        DesktopAssetManager assets = new DesktopAssetManager(true);
        assets.registerLocator(root.toString(), FileLocator.class);
        var profile = SkinLutGenerator.referenceProfile();
        SkinLutExport.write(root, "Skin/Target", SkinLutGenerator.generate(profile, 33, 17, 1, 32), profile, 32, false);
        Material material = assets.loadMaterial("Skin/Target.j3m");
        Geometry sphere = new Geometry("Closed sphere", new Sphere(24, 48, 1));
        sphere.setMaterial(material);
        var maps = SkinSurfaceBaker.bake(sphere, "Skin/Target.j3m", 32, 10, 30);
        float sum = 0;
        float curve = 0;
        int count = 0;
        for (int i = 0; i < maps.covered.length; i++) {
            if (maps.covered[i]) {
                sum += maps.thickness[i];
                curve += maps.curvature[i];
                count++;
            }
        }
        check(count > 700, "Sphere coverage");
        System.out.println("SURFACE sphere thickness=" + sum / count + " curvature=" + curve / count + " missing=" + maps.missingExits);
        check(Math.abs(sum / count - 20) < .5, "Physical thickness units");
        check(Math.abs(curve / count - .1) < .025, "Physical smooth curvature");
        Geometry open = new Geometry("Open sheet", new Quad(1, 1));
        open.setMaterial(material);
        var unresolved = SkinSurfaceBaker.bake(open, null, 16, 10, 30);
        check(unresolved.missingExits > 100, "Open surface must be conservative");
        check(unresolved.thickness[128] == 30, "Open surface became transparent");
        check(unresolved.transmissionCoverage[128] == 0, "Open surface has transmission coverage");
        byte[] lut = Files.readAllBytes(root.resolve("Skin/Target.dds"));
        SkinSurfaceAssets.write(root, "Skin/Surface", "Skin/Target.j3m", maps, 10, 30, 1, 4, false);
        assets.clearCache();
        Material exported = assets.loadMaterial("Skin/Target.j3m");
        check(Boolean.TRUE.equals(exported.getParamValue("SkinTransmission")), "Transmission not assigned");
        check(exported.getTextureParam("SkinThicknessMap").getTextureValue().getImage().getColorSpace() == ColorSpace.Linear,
                "Thickness map must be linear");
        check(exported.getTextureParam("SkinCurvatureMap") != null, "Smooth curvature not assigned");
        check(exported.getTextureParam("SkinThicknessMap").getTextureValue().getImage().hasMipmaps(), "Thickness mip chain missing");
        check(exported.getTextureParam("SkinCurvatureMap").getTextureValue().getImage().hasMipmaps(), "Curvature mip chain missing");
        check(exported.getTextureParam("SkinThicknessMap").getTextureValue().getMinFilter() == com.jme3.texture.Texture.MinFilter.Trilinear,
                "Surface material does not use mip filtering");
        var invalid = new com.jme3.texture.Image(com.jme3.texture.Image.Format.RGBA16F, 2, 2,
                com.jme3.util.BufferUtils.createByteBuffer(32), ColorSpace.Linear);
        var raster = com.jme3.texture.image.ImageRaster.create(invalid);
        raster.setPixel(0, 0, new com.jme3.math.ColorRGBA(.1f, .1f, .1f, 1));
        raster.setPixel(1, 0, new com.jme3.math.ColorRGBA(1, 1, 1, 0));
        raster.setPixel(0, 1, new com.jme3.math.ColorRGBA(1, 1, 1, 0));
        raster.setPixel(1, 1, new com.jme3.math.ColorRGBA(1, 1, 1, 0));
        var coarse = com.jme3.texture.image.ImageRaster.create(SkinSurfaceAssets.mipLevels(invalid, true).get(1)).getPixel(0, 0);
        check(Math.abs(coarse.r - .1f) < .001f && Math.abs(coarse.a - .25f) < .001f,
                "Thickness mip averaged unresolved distances or lost coverage");
        check(Arrays.equals(lut, Files.readAllBytes(root.resolve("Skin/Target.dds"))), "Surface export changed LUT");
        check(!Files.exists(root.resolve("Skin/Surface.j3m")), "Surface exporter created a second material");
        new BinaryExporter().save(sphere, root.resolve("head.j3o").toFile());
        final SkinSurfacePanel[] panel = new SkinSurfacePanel[1];
        final String[] editorText = { Files.readString(root.resolve("Skin/Target.j3m"))
                .replace("MaterialParameters {", "MaterialParameters {\n        Roughness : 0.73") };
        final int[] notifications = {0};
        final Material live = exported.clone();
        live.setFloat("Roughness", .73f);
        SkinMaterialUpdates.Listener listener = (path, text) -> {
            check(SwingUtilities.isEventDispatchThread(), "Editor notification outside EDT");
            if (path.equals(root.resolve("Skin/Target.j3m"))) {
                notifications[0]++;
                editorText[0] = SkinMaterialUpdates.merge(editorText[0], text);
                assets.clearCache();
                SkinMaterialUpdates.apply(live, assets.loadMaterial("Skin/Target.j3m"));
            }
        };
        SkinMaterialUpdates.addListener(listener);
        SwingUtilities.invokeAndWait(() -> {
            try {
                var rootDocument = new javax.swing.text.PlainDocument();
                rootDocument.insertString(0, root.toString(), null);
                var targetDocument = new javax.swing.text.PlainDocument();
                targetDocument.insertString(0, "Skin/Target.j3m", null);
                panel[0] = new SkinSurfacePanel(rootDocument, targetDocument);
                ((JTextField) field(panel[0], "model")).setText("head.j3o");
                ((JTextField) field(panel[0], "key")).setText("Skin/UiSurface");
                ((JComboBox<?>) field(panel[0], "size")).setSelectedIndex(0);
                ((JSpinner) field(panel[0], "units")).setValue(10.0);
                ((JButton) field(panel[0], "bake")).doClick();
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
        long deadline = System.nanoTime() + 30_000_000_000L;
        boolean[] ready = {false};
        while (!ready[0] && System.nanoTime() < deadline) {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    ready[0] = ((JButton) field(panel[0], "export")).isEnabled();
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            });
            Thread.sleep(30);
        }
        if (!ready[0]) {
            System.out.println("SURFACE_UI_STATUS " + ((JLabel) field(panel[0], "status")).getText());
            System.exit(2);
        }
        SwingUtilities.invokeAndWait(() -> {
            try {
                ((JButton) field(panel[0], "export")).doClick();
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
        Path assigned = root.resolve("Skin/UiSurface-thickness.dds");
        while (notifications[0] == 0 && System.nanoTime() < deadline) {
            SwingUtilities.invokeAndWait(() -> {});
            Thread.sleep(30);
        }
        check(Files.isRegularFile(assigned), "Surface UI export failed");
        check(notifications[0] == 1, "Export did not update the open editor");
        check(editorText[0].contains("UiSurface-curvature.dds") && editorText[0].contains("UiSurface-thickness.dds"),
                "Editor retained stale surface bindings");
        check(editorText[0].contains("Roughness : 0.73"), "Export discarded an unsaved roughness edit");
        check(Math.abs((Float) live.getParamValue("Roughness") - .73f) < .001f, "Live update replaced unrelated tuning");
        check(live.getTextureParam("SkinThicknessMap").getTextureValue().getKey().getName().endsWith("UiSurface-thickness.dds"),
                "Live material retained old thickness");
        check(live.getTextureParam("SkinCurvatureMap").getTextureValue().getKey().getName().endsWith("UiSurface-curvature.dds"),
                "Live material retained old curvature");
        // A later save of the formerly stale editor must retain the new bindings.
        Files.writeString(root.resolve("Skin/Target.j3m"), editorText[0]);
        assets.clearCache();
        check(assets.loadMaterial("Skin/Target.j3m").getTextureParam("SkinThicknessMap") != null,
                "Editor save erased generated bindings");
        SwingUtilities.invokeAndWait(() -> {
            try {
                SkinMaterialUpdates.publish(root.resolve("Other/Target.j3m"), editorText[0]);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        });
        check(notifications[0] == 1, "Update leaked to a different material path");
        SkinMaterialUpdates.removeListener(listener);
        if (args.length > 1) {
            Path actual = Path.of(args[1]);
            assets.registerLocator(actual.toString(), FileLocator.class);
            var head = assets.loadModel("Models/Skin/LeePerrySmith.j3o");
            var baked = SkinSurfaceBaker.bake(head, "Textures/Skin/SkinLut.j3m", 256, 50, 30);
            Files.write(Path.of(args[0], "head-thickness.dds"), TextureToolchain.linearHalfDds(
                    SkinSurfaceAssets.image(baked.thickness, baked.covered, baked.size, 30, 8, 1)));
            Files.write(Path.of(args[0], "head-curvature.dds"), TextureToolchain.linearHalfDds(
                    SkinSurfaceAssets.image(baked.curvature, baked.covered, baked.size, 1, 8, 0)));
            System.out.println("REAL_HEAD_SURFACE_BAKE_PASS missing=" + baked.missingExits + " overlaps=" + baked.overlappingTexels);
        }
        SwingUtilities.invokeAndWait(() -> panel[0].close());
        System.out.println("SKIN_SURFACE_BAKE_ASSIGN_PASS");
        System.exit(0);
    }
}
