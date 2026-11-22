package com.jme3.gde.core.assets;

import com.jme3.gde.texturetools.GeneratedTextures;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.control.LodControl;
import com.jme3.scene.shape.Sphere;
import com.jme3.system.Platform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.filesystems.LocalFileSystem;
import org.openide.loaders.MultiDataObject;
import org.openide.loaders.UniFileLoader;

/** Verifies J3O save with an external material and automatic LOD after reload. */
public final class ModelLodAndDdsSmoke {
    public static void main(String[] args) throws Exception {
        try {
            verify(Path.of(args[0]));
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
        System.exit(0);
    }

    private static void verify(Path scratch) throws Exception {
        Path directory = Files.createTempDirectory(scratch, "lod-dds-");
        Files.createDirectories(directory.resolve("Textures"));
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        var imagePath = directory.resolve("Textures/normal.png");
        ImageIO.write(image, "png", imagePath.toFile());
        Files.writeString(directory.resolve("External.j3m"),
                "Material External : Common/MatDefs/Misc/Unshaded.j3md {\n"
                + "  MaterialParameters {\n    ColorMap : Textures/normal.png\n  }\n}\n");
        FileUtil.refreshFor(directory.toFile());
        FileObject root = FileUtil.toFileObject(directory.toFile());
        var manager = new ProjectAssetManager(root);
        Geometry first = new Geometry("first", new Sphere(32, 32, 1f));
        first.setMaterial(manager.loadMaterial("External.j3m"));
        Geometry second = new Geometry("second", first.getMesh());
        second.setMaterial(first.getMaterial());
        Node source = new Node("source");
        source.attachChild(first);
        source.attachChild(second);
        var options = new ModelConversionOptions(false, ModelConversionOptions.Collision.MESH, true, 4,
                false, false, false, false, true, AnimationEditorSupport.Preference.AUTO,
                ModelConversionOptions.Bake.defaults(), false, 0, 1);
        Node converted = (Node) ModelConversionPreparation.prepare(source, options, manager);
        require(first.getMesh().getNumLodLevels() == 0 && first.getControl(LodControl.class) == null, "Source changed");
        Geometry a = (Geometry) converted.getChild(0);
        Geometry b = (Geometry) converted.getChild(1);
        require(a.getMesh() == b.getMesh() && a.getControl(LodControl.class) != b.getControl(LodControl.class),
                "One shared mesh and a control per geometry");
        var filesystem = new LocalFileSystem();
        filesystem.setRootDirectory(directory.toFile());
        FileObject file = filesystem.getRoot().createData("model.j3o");
        var data = (BinaryModelDataObject) new Loader().findDataObject(file, java.util.Collections.emptySet());
        data.savable = converted;
        data.getLookupContents().add(manager);
        data.saveAsset();
        var store = new GeneratedTextures(directory);
        Path dds = store.resolve("Textures/normal.png");
        require(dds != null, "Normal J3O save didn't generate the external material's DDS");
        require(dds.equals(directory.resolve("Textures/dds/normal.png.dds")), "Wrong DDS name or directory");
        Files.createDirectories(directory.resolve("Other"));
        ImageIO.write(image, "png", directory.resolve("Other/normal.png").toFile());
        Path otherDds = store.ensureMissingPortableBc3("Other/normal.png", PortableModelDds::write);
        require(otherDds.equals(directory.resolve("Other/dds/normal.png.dds")),
                "Same-named source in a different directory did not get its own DDS");
        var timestamp = Files.getLastModifiedTime(dds);
        image.setRGB(0, 0, 0xff123456);
        ImageIO.write(image, "png", imagePath.toFile());
        data.saveAsset();
        require(Files.getLastModifiedTime(dds).equals(timestamp), "Saving rewrote an existing DDS");
        Files.delete(dds);
        data.saveAsset();
        require(Files.isRegularFile(dds), "Saving didn't restore a missing managed DDS");
        store.delete(dds);
        require(!Files.exists(dds), "Deleting a generated DDS didn't remove the file");
        data.closeAsset();
        require(data.loadAsset() != null && Files.isRegularFile(dds),
                "Reopening the J3O didn't restore a deleted DDS");
        ImageIO.write(image, "png", directory.resolve("Textures/manual.png").toFile());
        Path manual = Files.copy(dds, dds.resolveSibling("manual.png.dds"));
        var manualTimestamp = Files.getLastModifiedTime(manual);
        int[] encodes = {0};
        require(store.ensureMissingPortableBc3("Textures/manual.png", (pixels, output, levels) -> encodes[0]++)
                .equals(manual) && encodes[0] == 0 && Files.getLastModifiedTime(manual).equals(manualTimestamp),
                "Manual DDS was not preserved");
        Files.createDirectories(directory.resolve("Moved"));
        store.move(imagePath, directory.resolve("Moved/renamed.png"));
        Path movedDds = directory.resolve("Moved/dds/renamed.png.dds");
        require(!Files.exists(dds) && movedDds.equals(store.resolve("Textures/normal.png")),
                "Moving the source did not relocate its DDS or keep its logical alias");
        try {
            store.checkCopy(movedDds.getParent());
            throw new AssertionError("Generated DDS directory was copyable");
        } catch (IOException expected) {
            // Metadata and files should remain accessible.
        }
        try {
            store.move(movedDds.getParent(), movedDds.getParent().resolveSibling("renamed-dds"));
            throw new AssertionError("Generated DDS directory was renameable");
        } catch (IOException expected) {
            require(Files.isRegularFile(movedDds), "Blocked move modified generated DDS");
        }
        try {
            store.delete(movedDds.getParent());
            throw new AssertionError("Generated DDS directory was deletable");
        } catch (IOException expected) {
            require(Files.isRegularFile(movedDds), "Blocked directory deletion removed a DDS");
        }
        store.delete(otherDds);
        store.restoreMissingOutput("Other/dds/normal.png.dds", PortableModelDds::write);
        require(Files.isRegularFile(otherDds), "Legacy direct DDS key could not be restored");

        Files.copy(directory.resolve("model.j3o"), directory.resolve("Other/copied.j3o"));
        Node reloaded = (Node) manager.loadModel("Other/copied.j3o");
        verifyLod((Geometry) reloaded.getChild(0));
        System.out.println("MODEL_LOD_AND_DDS_PASS");
    }

    private static void verifyLod(Geometry geometry) throws Exception {
        LodControl control = geometry.getControl(LodControl.class);
        require(control != null && control.getDesktopStartLevel() == 0 && control.getMobileStartLevel() == 1,
                "LOD control settings lost in J3O");
        var startLevel = LodControl.class.getDeclaredMethod("startLevel", Platform.class);
        startLevel.setAccessible(true);
        require(startLevel.invoke(control, Platform.Android_ARM8).equals(1)
                && startLevel.invoke(control, Platform.iOS_ARM).equals(1)
                && startLevel.invoke(control, Platform.Windows64).equals(0), "Wrong platform start level");
        var camera = new Camera(800, 600);
        camera.setFrustumPerspective(45f, 4f / 3f, .1f, 1000f);
        var view = new ViewPort("LOD test", camera);
        geometry.updateGeometricState();
        camera.setLocation(new Vector3f(0f, 0f, 5f));
        control.render(null, view);
        require(geometry.getLodLevel() == 0, "Near camera did not select level 0");
        control.setDesktopStartLevel(1);
        control.render(null, view);
        require(geometry.getLodLevel() == 1, "Desktop start level not used with a stationary camera");
        camera.setLocation(new Vector3f(0f, 0f, 200f));
        control.render(null, view);
        require(geometry.getLodLevel() == geometry.getMesh().getNumLodLevels() - 1, "Far camera did not select coarse LOD");
        control.setDesktopStartLevel(99);
        camera.setLocation(new Vector3f(0f, 0f, 5f));
        control.render(null, view);
        require(geometry.getLodLevel() == geometry.getMesh().getNumLodLevels() - 1, "Unavailable start level was not clamped");
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError(message);
        }
    }

    private static final class Loader extends UniFileLoader {
        Loader() {
            super(BinaryModelDataObject.class.getName());
        }

        @Override
        protected FileObject findPrimaryFile(FileObject file) {
            return file;
        }

        @Override
        protected MultiDataObject createMultiObject(FileObject file) throws IOException {
            return new BinaryModelDataObject(file, this);
        }
    }
}
