package com.jme3.gde.core.assets;

import java.awt.Dialog;
import java.awt.Component;
import java.awt.Container;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.lang.reflect.Method;
import java.util.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.openide.*;
import org.openide.filesystems.FileUtil;
import org.openide.util.lookup.*;

/** Exercises DDS publication, cancel, retention, collision and disk-copy handling. */
public final class ExternalAssetImportSmoke {
    static final TestDialogs DIALOGS = new TestDialogs();
    public static final class TestLookup extends ProxyLookup {
        public TestLookup() {
            super(Lookups.fixed(DIALOGS), Lookups.metaInfServices(ExternalAssetImportSmoke.class.getClassLoader()));
        }
    }
    public static final class TestDialogs extends DialogDisplayer {
        boolean cancel;
        boolean retain;
        int dialogs;
        final List<String> errors = new ArrayList<>();
        @Override public Object notify(NotifyDescriptor descriptor) {
            if (descriptor.getMessageType() == NotifyDescriptor.ERROR_MESSAGE) {
                errors.add(String.valueOf(descriptor.getMessage()));
                return NotifyDescriptor.OK_OPTION;
            }
            check(SwingUtilities.isEventDispatchThread(), "Dialog outside EDT");
            dialogs++;
            configure(descriptor.getMessage());
            return cancel ? NotifyDescriptor.CANCEL_OPTION : NotifyDescriptor.OK_OPTION;
        }
        private void configure(Object object) {
            if (object instanceof JCheckBox box) {
                if (box.getText().equals("Copy original resources to Assets")) {
                    check(!box.isSelected(), "Originals must default off");
                    box.setSelected(retain);
                } else if (!box.getText().equals("Generate mipmaps")) {
                    box.setSelected(false);
                }
            }
            if (object instanceof Container container) {
                for (Component child : container.getComponents()) {
                    configure(child);
                }
            }
        }
        @Override public Dialog createDialog(DialogDescriptor descriptor) {
            throw new UnsupportedOperationException();
        }
    }
    static void check(boolean pass, String message) {
        if (!pass) {
            throw new AssertionError(message);
        }
    }
    private static void verifyInternalCopy(Path root, Path input, ProjectAssetManager manager) throws Exception {
        Files.copy(input, root.resolve("original.png"));
        FileUtil.refreshFor(root.toFile());
        var assetRoot = manager.getAssetFolder();
        var copyFolder = assetRoot.createFolder("copies");
        var destination = org.openide.loaders.DataFolder.findFolder(copyFolder).getNodeDelegate();
        var sourceNode = org.openide.loaders.DataObject.find(assetRoot.getFileObject("original.png"))
                .getNodeDelegate();
        var watchType = Class.forName("com.jme3.gde.core.assets.ExternalAssetImport$Watch");
        var constructor = watchType.getDeclaredConstructor(ProjectAssetManager.class);
        constructor.setAccessible(true);
        var watcher = constructor.newInstance(manager);
        var start = watchType.getDeclaredMethod("start");
        var stop = watchType.getDeclaredMethod("stop");
        start.setAccessible(true);
        stop.setAccessible(true);
        start.invoke(watcher);
        try {
            var copied = sourceNode.clipboardCopy();
            check(copied.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.javaFileListFlavor),
                    "NetBeans copy must exercise the overlapping file-list flavor");
            check(ExternalAssetImport.paste(manager, copyFolder, copied) == null,
                    "Project Assets clipboard copy was routed into external import");
            check(!ExternalAssetImport.submit(manager, copyFolder, copied),
                    "Asset Browser clipboard copy was routed into external import");
            var choices = destination.getPasteTypes(copied);
            check(choices.length > 0, "Native folder has no paste operation");
            choices[0].paste();
            check(Files.mismatch(input, root.resolve("copies/original.png")) == -1,
                    "Native copy changed image contents");

            // Wait for the same watcher to convert a genuine disk addition.
            var diskFile = assetRoot.createData("clipboard-control.png");
            try (var stream = diskFile.getOutputStream()) {
                stream.write(Files.readAllBytes(input));
            }
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
            while (!Files.exists(root.resolve("clipboard-control.png.dds")) && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            check(Files.exists(root.resolve("clipboard-control.png.dds")), "External disk import stopped working");
            check(Files.exists(root.resolve("copies/original.png"))
                    && !Files.exists(root.resolve("copies/original.png.dds")), "SDK copy was converted by watcher");
        } finally {
            stop.invoke(watcher);
        }
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("org.openide.util.Lookup", TestLookup.class.getName());
        try {
            Path work = Files.createTempDirectory(Path.of(args[0]), "external-import-test-").toAbsolutePath();
            Path root = Files.createDirectories(work.resolve("assets"));
            Path source = Files.createDirectories(work.resolve("external"));
            Path input = source.resolve("image.png");
            ImageIO.write(new BufferedImage(16, 8, BufferedImage.TYPE_INT_ARGB), "png", input.toFile());
            FileUtil.refreshFor(root.toFile());
            ProjectAssetManager manager = new ProjectAssetManager(FileUtil.toFileObject(root.toFile()));
            if (args.length > 1 && args[1].equals("--clipboard-only")) {
                verifyInternalCopy(root, input, manager);
                System.out.println("INTERNAL_ASSET_COPY_PASS");
                System.exit(0);
            }
            Method run = ExternalAssetImport.class.getDeclaredMethod("run", ProjectAssetManager.class,
                    org.openide.filesystems.FileObject.class, List.class, boolean.class);
            run.setAccessible(true);
            run.invoke(null, manager, manager.getAssetFolder(), List.of(input), false);
            check(Files.exists(root.resolve("image.png.dds")), "DDS missing: " + DIALOGS.errors);
            check(!Files.exists(root.resolve("image.png")) && Files.exists(input), "Original source policy");
            byte[] dds = Files.readAllBytes(root.resolve("image.png.dds"));
            var header = java.nio.ByteBuffer.wrap(dds).order(java.nio.ByteOrder.LITTLE_ENDIAN);
            check(header.getInt(0) == 0x20534444 && header.getInt(12) == 8
                    && header.getInt(16) == 16 && header.getInt(28) == 5, "DDS dimensions and mip chain");
            DIALOGS.retain = true;
            Path kept = Files.copy(input, source.resolve("kept.png"));
            run.invoke(null, manager, manager.getAssetFolder(), List.of(kept), false);
            check(Files.exists(root.resolve("kept.png")), "Retained source missing");
            DIALOGS.cancel = true;
            Path cancelled = Files.copy(input, source.resolve("cancel.png"));
            run.invoke(null, manager, manager.getAssetFolder(), List.of(cancelled), false);
            check(!Files.exists(root.resolve("cancel.png.dds")), "Cancel wrote output");
            DIALOGS.cancel = false;
            DIALOGS.retain = false;
            Path disk = Files.copy(input, root.resolve("disk.png"));
            run.invoke(null, manager, manager.getAssetFolder(), List.of(disk), true);
            check(Files.exists(root.resolve("disk.png.dds")) && !Files.exists(disk), "Disk-copy conversion");
            Path obj = source.resolve("triangle.obj");
            Files.writeString(obj, "v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n");
            Path independent = Files.copy(input, source.resolve("independent.png"));
            int before = DIALOGS.dialogs;
            run.invoke(null, manager, manager.getAssetFolder(), List.of(independent, obj), false);
            check(Files.exists(root.resolve("triangle.j3o")), "Model missing: " + DIALOGS.errors);
            try (var children = Files.list(work)) {
                check(children.noneMatch(path -> path.getFileName().toString().startsWith(".jme-import-")),
                        "Successful import left its staging directory");
            }
            check(Files.exists(root.resolve("independent.png.dds")), "Mixed texture missing");
            check(DIALOGS.dialogs == before + 2, "Mixed batch dialog count");
            com.jme3.scene.Spatial loaded = manager.loadModel("triangle.j3o");
            com.jme3.scene.AssetLinkNode link = new com.jme3.scene.AssetLinkNode();
            link.attachLinkedChild(loaded, new com.jme3.asset.ModelKey("triangle.j3o"));
            com.jme3.export.binary.BinaryExporter.getInstance().save(link, root.resolve("linked.j3o").toFile());
            var restored = (com.jme3.scene.AssetLinkNode) manager.loadModel("linked.j3o");
            check(restored.getAssetLoaderKeys().size() == 1 && restored.getQuantity() == 1, "Link persistence");
            var texture = manager.loadTexture("kept.png");
            var store = new com.jme3.gde.texturetools.GeneratedTextures(root);
            store.ensurePortableBc3("kept.png", PortableModelDds::write);
            var material = new com.jme3.material.Material(manager, "Common/MatDefs/Misc/Unshaded.j3md");
            material.setTexture("ColorMap", texture);
            var geometry = new com.jme3.scene.Geometry("textured", new com.jme3.scene.shape.Box(1, 1, 1));
            geometry.setMaterial(material);
            Method direct = ExternalAssetImport.class.getDeclaredMethod("directTextureKeys",
                    com.jme3.scene.Spatial.class, ProjectAssetManager.class, Path.class);
            direct.setAccessible(true);
            direct.invoke(null, geometry, manager, root);
            check(texture.getKey().getName().endsWith(".dds"), "Export must reference DDS directly");
            com.jme3.export.binary.BinaryExporter.getInstance().save(geometry, root.resolve("textured.j3o").toFile());
            Files.delete(root.resolve("kept.png"));
            Files.delete(root.resolve(com.jme3.gde.texturetools.GeneratedTextures.MANIFEST));
            manager.clearCache();
            var textured = (com.jme3.scene.Geometry) manager.loadModel("textured.j3o");
            check(textured.getMaterial().getTextureParam("ColorMap").getTextureValue().getImage().getFormat().isCompressed(),
                    "J3O must load without source or SDK mapping");
            Path pasted = Files.copy(input, source.resolve("pasted.png"));
            java.awt.datatransfer.Transferable transfer = new java.awt.datatransfer.Transferable() {
                @Override public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors() {
                    return new java.awt.datatransfer.DataFlavor[]{java.awt.datatransfer.DataFlavor.javaFileListFlavor};
                }
                @Override public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor flavor) {
                    return flavor.equals(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
                }
                @Override public Object getTransferData(java.awt.datatransfer.DataFlavor flavor) {
                    return List.of(pasted.toFile());
                }
            };
            var paste = ExternalAssetImport.paste(manager, manager.getAssetFolder(), transfer);
            check(paste != null, "External clipboard not accepted");
            paste.paste();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
            while (!Files.exists(root.resolve("pasted.png.dds")) && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            check(Files.exists(root.resolve("pasted.png.dds")), "Asynchronous clipboard import failed");
            var watchType = Class.forName("com.jme3.gde.core.assets.ExternalAssetImport$Watch");
            var constructor = watchType.getDeclaredConstructor(ProjectAssetManager.class);
            constructor.setAccessible(true);
            var watcher = (org.openide.filesystems.FileChangeListener) constructor.newInstance(manager);
            manager.getAssetFolder().addRecursiveListener(watcher);
            try {
                var diskFile = manager.getAssetFolder().createData("watched.png");
                try (var stream = diskFile.getOutputStream()) {
                    stream.write(Files.readAllBytes(input));
                }
                deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
                while (!Files.exists(root.resolve("watched.png.dds")) && System.nanoTime() < deadline) {
                    Thread.sleep(50);
                }
                check(Files.exists(root.resolve("watched.png.dds")), "Filesystem notification import failed");
            } finally {
                manager.getAssetFolder().removeRecursiveListener(watcher);
            }
            Files.write(root.resolve("image.png.dds"), new byte[]{1, 2, 3});
            run.invoke(null, manager, manager.getAssetFolder(), List.of(input), false);
            SwingUtilities.invokeAndWait(() -> {});
            check(Arrays.equals(Files.readAllBytes(root.resolve("image.png.dds")), new byte[]{1,2,3}), "Overwrite protection");
            System.out.println("EXTERNAL_ASSET_IMPORT_PASS dialogs=" + DIALOGS.dialogs);
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }
}
