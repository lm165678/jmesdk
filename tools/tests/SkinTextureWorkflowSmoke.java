package com.jme3.gde.texturetools;

import java.awt.Component;
import java.awt.datatransfer.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.openide.util.lookup.*;
import org.openide.modules.InstalledFileLocator;
import com.jme3.material.skin.*;

/** Isolated source selection, real SDK payloads, format decoding and legacy migration. */
public final class SkinTextureWorkflowSmoke {
    static Path root;
    static Path selected;
    static boolean cancel;
    static int projectCalls;
    static int textureCalls;
    static final class Bridge implements SkinAssetSelection {
        public Path chooseProject(Component owner) {
            projectCalls++;
            return cancel ? null : root;
        }
        public Path chooseTexture(Component owner) {
            textureCalls++;
            return cancel ? null : selected;
        }
        public Path textureFromTransfer(Transferable data) throws Exception {
            return new com.jme3.gde.core.assets.SkinProjectAssetSelection().textureFromTransfer(data);
        }
    }
    public static final class TestLookup extends ProxyLookup {
        public TestLookup() {
            super(Lookups.fixed(new Bridge(), new InstalledFileLocator() {
                public java.io.File locate(String name, String module, boolean localized) {
                    Path file = Path.of(System.getProperty("skin.test.cluster"), name);
                    return Files.exists(file) ? file.toFile() : null;
                }
            }), Lookups.metaInfServices(SkinTextureWorkflowSmoke.class.getClassLoader()));
        }
    }
    static void check(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
    static Object field(Object owner, String name) throws Exception {
        var field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
    static void invoke(Object owner, String name) throws Exception {
        var method = owner.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(owner);
    }
    static Transferable files(List<java.io.File> files) {
        return new Transferable() {
            public DataFlavor[] getTransferDataFlavors() {
                return new DataFlavor[]{DataFlavor.javaFileListFlavor};
            }
            public boolean isDataFlavorSupported(DataFlavor flavor) {
                return flavor.equals(DataFlavor.javaFileListFlavor);
            }
            public Object getTransferData(DataFlavor flavor) {
                return files;
            }
        };
    }
    static boolean drop(JTextField field, Transferable data) {
        return field.getTransferHandler().importData(new TransferHandler.TransferSupport(field, data));
    }
    public static void main(String[] args) throws Exception {
        System.setProperty("org.openide.util.Lookup", TestLookup.class.getName());
        root = Files.createTempDirectory(Path.of(args[0]), "workflow-");
        selected = root.resolve("normal.png");
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                image.setRGB(x, y, 0x8080ff);
            }
        }
        for (String extension : new String[]{"png", "jpg", "bmp"}) {
            Path file = root.resolve("normal." + extension);
            check(ImageIO.write(image, extension, file.toFile()), "Cannot make fixture");
            check(SkinTextureReader.read(file, Files.readAllBytes(file)).getWidth() == 8, "Format " + extension);
        }
        SwingUtilities.invokeAndWait(() -> {
            try {
                SkinLutPanel lut = new SkinLutPanel();
                SkinNormalPanel normals = new SkinNormalPanel(lut::snapshot, lut::profileDescription);
                invoke(lut, "chooseRoot");
                invoke(normals, "chooseRoot");
                invoke(normals, "chooseSource");
                JTextField source = (JTextField) field(normals, "source");
                check(projectCalls == 2 && textureCalls == 1, "Browses did not use project selection service");
                check(((JTextField) field(lut, "root")).getText().equals(root.toString()), "LUT project root");
                check(((JTextField) field(normals, "root")).getText().equals(root.toString()), "Normal project root");
                check(source.getText().equals(selected.toString()), "Project texture selection");
                cancel = true;
                invoke(normals, "chooseSource");
                invoke(lut, "chooseRoot");
                check(source.getText().equals(selected.toString()), "Cancelled picker changed input");
                cancel = false;
                check(drop(source, files(List.of(selected.toFile()))), "External texture drop");
                var file = org.openide.filesystems.FileUtil.toFileObject(selected.toFile());
                var holder = new com.jme3.gde.core.dnd.AssetNameHolder() {
                    public String getAssetName() {
                        return "normal.png";
                    }
                    public org.openide.filesystems.FileObject getAssetFile() {
                        return file;
                    }
                };
                var internal = new com.jme3.gde.core.dnd.AssetTransferable<>(holder,
                        com.jme3.gde.core.dnd.TextureDataFlavor.instance);
                check(drop(source, internal), "Actual SDK Asset Browser texture payload");
                check(drop(source, new StringSelection(selected.toUri().toString())), "URI drop");
                String before = source.getText();
                check(!drop(source, files(List.of(selected.toFile(), selected.toFile()))), "Multiple file rejection");
                Path wrong = root.resolve("wrong.txt");
                Files.writeString(wrong, "not a texture");
                check(!drop(source, files(List.of(wrong.toFile()))) && source.getText().equals(before), "Invalid drop changed source");
                source.setEnabled(false);
                check(!drop(source, internal), "Busy field accepted drop");
                lut.close();
                normals.close();
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
        });
        var profile = SkinLutGenerator.referenceProfile();
        var lut = SkinLutGenerator.generate(profile, 17, 9, 1, 32);
        String key = "Legacy/Lut";
        var paths = SkinLutExport.write(root, key, lut, profile, 32, false);
        Path old = root.resolve(key + ".j3o");
        new com.jme3.export.binary.BinaryExporter().save(lut.createTexture().getImage(), old.toFile());
        Files.delete(paths.get(0));
        Files.writeString(paths.get(1), Files.readString(paths.get(1)).replace(".dds", ".j3o"));
        Files.writeString(paths.get(2), Files.readString(paths.get(2)).replace(".dds", ".j3o"));
        SkinLutExport.write(root, key, lut, profile, 32, true);
        check(!Files.exists(old) && Files.exists(paths.get(0)), "Legacy Image J3O migration");
        check(!Files.readString(paths.get(1)).contains(".j3o"), "Legacy material reference retained");
        Path dds = paths.get(0);
        check(SkinTextureReader.read(dds, Files.readAllBytes(dds)).getWidth() == 17, "Float DDS texture input");
        Path actual = Path.of(args[1]);
        var settings = new SkinGenerationSettings(profile, 17, 9, 1, 32, "test");
        var product = SkinNormalAssets.generate(actual, settings, 32, 4,
                SkinNormalFilter.Boundary.CLAMP, SkinNormalFilter.Quality.FAST);
        check(product.normals.getWidth() == 32 && product.extension.equals(".dds"), "Actual DDS normal generation");
        SkinNormalAssets.write(root, "NormalDds/Test", product, false, false, false);
        check(java.util.Arrays.equals(product.original, Files.readAllBytes(root.resolve("NormalDds/Test-detail.dds"))),
                "DDS detail source not preserved");
        System.out.println("SKIN_TEXTURE_PROJECT_DROP_DDS_MIGRATION_PASS");
        System.exit(0);
    }
}
