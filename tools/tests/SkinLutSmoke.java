package com.jme3.gde.texturetools;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.asset.plugins.FileLocator;
import com.jme3.material.Material;
import com.jme3.material.skin.SkinLutGenerator;
import com.jme3.texture.Texture;
import com.jme3.texture.image.ColorSpace;
import java.awt.Component;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import org.openide.util.NbBundle;

/** Isolated CPU/EDT checks; does not launch or install SDK modules. */
public final class SkinLutSmoke {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(output);
        Path layer = Path.of(args[1]);
        String registration = Files.readString(layer);
        check(registration.contains("SkinLutAction") && registration.contains("Textures"), "Missing generated menu registration");
        check(NbBundle.getMessage(SkinLutAction.class, "CTL_SkinLut").equals("Pre-integrated Skin Tools"), "Menu resource");
        Path root = Files.createTempDirectory(output, "assets-");
        Path marker = root.resolve("GI/Lightmaps/existing.j3o");
        Files.createDirectories(marker.getParent());
        byte[] untouched = {12, 17, 34, 68};
        Files.write(marker, untouched);
        SkinLutGenerator.Profile profile = SkinLutGenerator.referenceProfile();
        SkinLutGenerator.Result lut = SkinLutGenerator.generate(profile, 33, 17, .5f, 64);
        String key = "Textures/Custom Skin/Profile";
        List<Path> paths = SkinLutExport.write(root, key, lut, profile, 64, false);
        DesktopAssetManager assets = new DesktopAssetManager(true);
        assets.registerLocator(root.toString(), FileLocator.class);
        Material material = assets.loadMaterial(key + ".j3m");
        Texture texture = material.getTextureParam("SkinLut").getTextureValue();
        check(texture.getImage().getWidth() == 33 && texture.getImage().getHeight() == 17, "Image dimensions");
        check(texture.getImage().getColorSpace() == ColorSpace.Linear, "Linear LUT");
        check(texture.getMinFilter() == Texture.MinFilter.BilinearNoMipMaps, "No LUT mipmaps");
        var raster = com.jme3.texture.image.ImageRaster.create(texture.getImage(), 0, 0, false);
        var expectedRaster = com.jme3.texture.image.ImageRaster.create(lut.createTexture().getImage(), 0, 0, false);
        for (int y = 0; y < lut.getHeight(); y++) {
            for (int x = 0; x < lut.getWidth(); x++) {
                var actual = raster.getPixel(x, y);
                var expected = expectedRaster.getPixel(x, y);
                check(actual.r == expected.r && actual.g == expected.g && actual.b == expected.b && actual.a == 1,
                        "DDS half-float LUT values/orientation");
            }
        }
        check(paths.get(0).toString().endsWith(".dds"), "LUT must export DDS");
        check(CompressedTexture.read(paths.get(0)).preview(0, null) != null, "Float DDS CPU preview");
        byte[][] before = new byte[3][];
        for (int i = 0; i < 3; i++) {
            before[i] = Files.readAllBytes(paths.get(i));
        }
        boolean rejected = false;
        try {
            SkinLutExport.write(root, key, lut, profile, 64, false);
        } catch (java.nio.file.FileAlreadyExistsException expected) {
            rejected = true;
        }
        check(rejected, "Overwrite requires explicit replacement");
        for (int i = 0; i < 3; i++) {
            check(Arrays.equals(before[i], Files.readAllBytes(paths.get(i))), "Rejected overwrite changed files");
        }
        SkinLutGenerator.Result other = SkinLutGenerator.generate(profile, 17, 9, 1f, 64);
        SkinLutExport.write(root, key, other, profile, 64, true);
        Properties settings = new Properties();
        try (var input = Files.newInputStream(paths.get(2))) {
            settings.load(input);
        }
        check(settings.getProperty("width").equals("17") && settings.getProperty("height").equals("9"), "Export metadata");
        SkinLutExport.Settings restored = SkinLutExport.read(paths.get(2));
        check(restored.width == 17 && restored.height == 9 && restored.profile.size() == profile.size(), "Profile reload");
        check(restored.profile.variance(0) == profile.variance(0), "Profile precision");
        Path foreign = root.resolve("Textures/Foreign.dds");
        Files.write(foreign, untouched);
        boolean protectedForeign = false;
        try {
            SkinLutExport.write(root, "Textures/Foreign", other, profile, 64, true);
        } catch (java.io.IOException expected) {
            protectedForeign = true;
        }
        check(protectedForeign && Arrays.equals(untouched, Files.readAllBytes(foreign)), "Foreign asset overwritten");
        List<Path> rollback = SkinLutExport.write(root, "Rollback/Profile", lut, profile, 64, false);
        byte[] previousImage = Files.readAllBytes(rollback.get(0));
        byte[] previousSettings = Files.readAllBytes(rollback.get(2));
        Files.delete(rollback.get(1));
        Files.createDirectory(rollback.get(1));
        Files.write(rollback.get(1).resolve("keep.bin"), untouched);
        boolean failed = false;
        try {
            SkinLutExport.write(root, "Rollback/Profile", other, profile, 64, true);
        } catch (java.io.IOException expected) {
            failed = true;
        }
        check(failed && Arrays.equals(previousImage, Files.readAllBytes(rollback.get(0)))
                && Arrays.equals(previousSettings, Files.readAllBytes(rollback.get(2))), "Failed publication lost prior assets");
        check(Arrays.equals(untouched, Files.readAllBytes(rollback.get(1).resolve("keep.bin"))), "Blocked material path changed");
        boolean escaped = false;
        try {
            SkinLutExport.targets(root, "../../outside");
        } catch (IllegalArgumentException expected) {
            escaped = true;
        }
        check(escaped, "Asset root traversal");
        check(Arrays.equals(untouched, Files.readAllBytes(marker)), "Unrelated Lightmap changed");
        Path normalSource = fixture(output);
        verifyNormals(root, normalSource);
        verifyPanel(output, normalSource, root);
        String supplied = System.getProperty("skin.test.normal");
        if (supplied != null) {
            Path actualRoot = Files.createTempDirectory(output, "actual-assets-");
            Files.createDirectories(actualRoot);
            var settingsForHead = new SkinGenerationSettings(SkinLutGenerator.referenceProfile(), 256, 128, 1f, 128, "Reference");
            var actual = SkinNormalAssets.generate(Path.of(supplied), settingsForHead, 256, 4f,
                    com.jme3.material.skin.SkinNormalFilter.Boundary.CLAMP,
                    com.jme3.material.skin.SkinNormalFilter.Quality.EXACT);
            SkinNormalAssets.write(actualRoot, "Skin/Head", actual, true, false, true);
            SkinNormalAssets.write(actualRoot, "Skin/HeadTwo", actual, false, false, true);
            Files.writeString(output.resolve("actual-assets-root.txt"), actualRoot.toString());
            System.out.println("REAL_SKIN_NORMAL_EXPORT_PASS " + actualRoot);
        }
        System.out.println("SDK_SKIN_LUT_PASS");
        System.exit(0);
    }

    private static Path fixture(Path output) throws Exception {
        BufferedImage image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 128; y++) {
            for (int x = 0; x < 128; x++) {
                double nx = .6 * Math.sin(x * .9);
                double ny = .3 * Math.cos(y * .7);
                double nz = Math.sqrt(1.0 - nx * nx - ny * ny);
                int rgb = ((int) Math.round((nx * .5 + .5) * 255) << 16)
                        | ((int) Math.round((ny * .5 + .5) * 255) << 8)
                        | (int) Math.round((nz * .5 + .5) * 255);
                image.setRGB(x, y, rgb);
            }
        }
        Path source = output.resolve("source-normal.png");
        ImageIO.write(image, "png", source.toFile());
        return source;
    }

    private static void verifyNormals(Path root, Path source) throws Exception {
        SkinGenerationSettings settings = new SkinGenerationSettings(SkinLutGenerator.referenceProfile(), 33, 17, 1f, 64, "Reference");
        byte[] original = Files.readAllBytes(source);
        SkinNormalAssets.Product product = SkinNormalAssets.generate(source, settings, 64, 4f,
                com.jme3.material.skin.SkinNormalFilter.Boundary.WRAP,
                com.jme3.material.skin.SkinNormalFilter.Quality.EXACT);
        check(product.normals.getWidth() == 64 && product.effectiveDensity == 2f, "Density after vector-space resize");
        SkinNormalAssets.write(root, "Normals/Two", product, false, false, false);
        SkinNormalAssets.write(root, "Normals/Four", product, true, true, false);
        DesktopAssetManager assets = new DesktopAssetManager(true);
        assets.registerLocator(root.toString(), FileLocator.class);
        Material two = assets.loadMaterial("Normals/Two.j3m");
        Material four = assets.loadMaterial("Normals/Four.j3m");
        check(two.getTextureParam("SkinDiffuseNormalMap") != null, "Two-normal binding");
        check(four.getTextureParam("SkinRedNormalMap") != null && four.getTextureParam("SkinGreenNormalMap") != null
                && four.getTextureParam("SkinBlueNormalMap") != null, "Four-normal bindings");
        check(((Number) four.getParamValue("NormalType")).floatValue() == -1f, "DirectX convention");
        check(four.getTextureParam("SkinRedNormalMap").getTextureValue().getImage().getColorSpace() == ColorSpace.Linear, "Normal data color space");
        check(Arrays.equals(original, Files.readAllBytes(source)), "Original normal was modified");
        check(Files.exists(root.resolve("Normals/Four-detail.dds")), "Detailed DDS missing");
        var detail = assets.loadTexture(new com.jme3.asset.TextureKey("Normals/Four-detail.dds", false)).getImage();
        check(detail.getWidth() > 0, "Detailed DDS does not load");
        var sourceImage = ImageIO.read(source.toFile());
        var decoded = CompressedTexture.read(root.resolve("Normals/Four-detail.dds")).preview(0, null);
        for (int y = 0; y < sourceImage.getHeight(); y++) {
            for (int x = 0; x < sourceImage.getWidth(); x++) {
                check((decoded.getRGB(x, y) & 0xffffff) == (sourceImage.getRGB(x, y) & 0xffffff),
                        "Raw detail DDS changed encoded normal data");
            }
        }
        String edited = Files.readString(root.resolve("Normals/Four.j3m"))
                .replace("        BaseColor :", "        Roughness : 0.27\n        BaseColor :")
                .replace("    MaterialParameters {", "    MaterialParameters {\n        SkinWorldToMillimeters : 50.35")
                .replace("Material Preintegrated Skin", "Material My Skin")
                .replace("    }\n}\n", "    }\n    AdditionalRenderState {\n        Wireframe On\n    }\n}\n");
        Files.writeString(root.resolve("Normals/Four.j3m"), edited);
        SkinNormalAssets.write(root, "Normals/Four", product, false, false, true);
        String twoText = Files.readString(root.resolve("Normals/Four.j3m"));
        check(twoText.contains("SkinDiffuseNormalMap") && !twoText.contains("SkinRedNormalMap"), "Old normal mode retained");
        SkinLutExport.write(root, "Normals/Four", product.lut, settings.profile, settings.samples, true);
        String lutUpdated = Files.readString(root.resolve("Normals/Four.j3m"));
        check(lutUpdated.contains("SkinDiffuseNormalMap") && lutUpdated.contains("NormalMap :"), "LUT export removed normal bindings");
        check(lutUpdated.contains("Roughness : 0.27") && lutUpdated.contains("SkinWorldToMillimeters : 50.35")
                && lutUpdated.contains("Material My Skin") && lutUpdated.contains("Wireframe On"), "Export lost artist tuning");
        SkinNormalAssets.write(root, "Normals/Four", product, true, false, true);
        byte[] retainedLut = Files.readAllBytes(root.resolve("Normals/Four.dds"));
        SkinNormalAssets.writeToMaterial(root, "Normals/Selected", "Normals/Four.j3m", product, true, false, false);
        String selected = Files.readString(root.resolve("Normals/Four.j3m"));
        check(selected.contains("Normals/Selected-diffuse-r.dds") && !selected.contains("SkinDiffuseNormalMap"), "Selected normal slots not updated");
        check(Arrays.equals(retainedLut, Files.readAllBytes(root.resolve("Normals/Four.dds")))
                && selected.contains("Roughness : 0.27") && selected.contains("Wireframe On"), "Selected export changed LUT or tuning");
        check(!Files.exists(root.resolve("Normals/Selected.j3m")), "Selected export created a second material");
        SkinLutExport.write(root, "Normals/Collision", product.lut, settings.profile, settings.samples, false);
        Path collision = root.resolve("Normals/Collision-detail.dds");
        byte[] foreign = {3, 5, 7};
        Files.write(collision, foreign);
        boolean rejected = false;
        try {
            SkinNormalAssets.write(root, "Normals/Collision", product, true, false, true);
        } catch (java.io.IOException expected) {
            rejected = true;
        }
        check(rejected && Arrays.equals(foreign, Files.readAllBytes(collision)), "Normal export replaced an unrelated file");
    }

    private static void verifyPanel(Path output, Path normalSource, Path root) throws Exception {
        SkinLutExport.write(root, "Textures/Skin/SkinLut",
                SkinLutGenerator.generate(SkinLutGenerator.referenceProfile(), 33, 17, 1f, 32),
                SkinLutGenerator.referenceProfile(), 32, false);
        AtomicReference<SkinToolsPanel> reference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            SkinToolsPanel panel = new SkinToolsPanel();
            panel.setSize(1100, 780);
            layout(panel);
            reference.set(panel);
        });
        SkinToolsPanel panel = reference.get();
        long deadline = System.nanoTime() + 30_000_000_000L;
        boolean[] complete = {false};
        while (!complete[0] && System.nanoTime() < deadline) {
            SwingUtilities.invokeAndWait(() -> {
                JLabel preview = (JLabel) find(panel, "skin.preview");
                JButton generate = (JButton) find(panel, "skin.generate");
                complete[0] = preview.getIcon() != null && generate.isEnabled();
            });
            Thread.sleep(25);
        }
        check(complete[0], "Asynchronous generation did not finish");
        SwingUtilities.invokeAndWait(() -> {
            layout(panel);
            check(((JTabbedPane) find(panel, "skin.tabs")).getTabCount() == 3, "Combined two-tab tool");
            BufferedImage image = new BufferedImage(1100, 780, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D graphics = image.createGraphics();
            panel.paint(graphics);
            graphics.dispose();
            try {
                ImageIO.write(image, "png", output.resolve("skin-lut-panel.png").toFile());
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
            ((JTabbedPane) find(panel, "skin.tabs")).setSelectedIndex(1);
            layout(panel);
            ((JTextField) find(panel, "skin.normal.source")).setText(normalSource.toString());
            ((JTextField) find(panel, "skin.normal.root")).setText(root.toString());
            check(((JTextField) find(panel, "skin.root")).getText().equals(root.toString()), "Project destination not shared");

            ((JButton) find(panel, "skin.normal.generate")).doClick();
        });
        complete[0] = false;
        deadline = System.nanoTime() + 30_000_000_000L;
        while (!complete[0] && System.nanoTime() < deadline) {
            SwingUtilities.invokeAndWait(() -> complete[0] = ((JButton) find(panel, "skin.normal.export")).isEnabled());
            Thread.sleep(25);
        }
        check(complete[0], "Normal tab generation did not finish");
        Thread.sleep(200);
        SwingUtilities.invokeAndWait(() -> {
            layout(panel);
            BufferedImage image = new BufferedImage(1100, 780, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D graphics = image.createGraphics();
            panel.paint(graphics);
            graphics.dispose();
            try {
                ImageIO.write(image, "png", output.resolve("skin-normal-panel.png").toFile());
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
            ((JButton) find(panel, "skin.normal.export")).doClick();
        });
        Path exported = root.resolve("Textures/Skin/SkinNormals-diffuse.dds");
        deadline = System.nanoTime() + 30_000_000_000L;
        while (!Files.exists(exported) && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        check(Files.isRegularFile(exported), "Normal tab export did not complete");
        complete[0] = false;
        while (!complete[0] && System.nanoTime() < deadline) {
            SwingUtilities.invokeAndWait(() -> complete[0] = ((JButton) find(panel, "skin.normal.generate")).isEnabled());
            Thread.sleep(25);
        }
        check(complete[0], "Normal export transaction did not finish");
        SwingUtilities.invokeAndWait(() -> {
            ((JComboBox<?>) find(panel, "skin.preset")).setSelectedIndex(2);
            check(!((JButton) find(panel, "skin.normal.export")).isEnabled(), "Profile change retained stale normal output");
            JTable table = table(panel);
            table.setValueAt(-1.0, 0, 0);
            ((JButton) find(panel, "skin.generate")).doClick();
            check(!((JButton) find(panel, "skin.export")).isEnabled(), "Invalid fit remained exportable");
            panel.close();
        });
    }

    private static Component find(Container container, String name) {
        for (Component component : container.getComponents()) {
            if (name.equals(component.getName())) {
                return component;
            }
            if (component instanceof Container) {
                Component found = find((Container) component, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static JTable table(Container container) {
        for (Component component : container.getComponents()) {
            if (component instanceof JTable) {
                return (JTable) component;
            }
            if (component instanceof Container) {
                JTable found = table((Container) component);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void layout(Container container) {
        container.doLayout();
        for (Component component : container.getComponents()) {
            if (component instanceof Container) {
                layout((Container) component);
            }
        }
    }

    private static void check(boolean pass, String message) {
        if (!pass) {
            throw new AssertionError(message);
        }
    }
}
