import com.jme3.gde.texturetools.GeneratedTextures;
import com.jme3.gde.texturetools.TextureToolchain;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;

/** Exercises real encoders, persistent ownership, and NetBeans filesystem interception. */
public final class GeneratedTexturesSmoke {
    public static void main(String[] args) {
        try {
            verify(Path.of(args[0]), Path.of(args[1]));
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static FileObject file(Path path) {
        FileUtil.refreshFor(path.toFile());
        return FileUtil.toFileObject(FileUtil.normalizeFile(path.toFile()));
    }

    private static void verify(Path toolRoot, Path work) throws Exception {
        Files.createDirectories(work);
        Path root = Files.createTempDirectory(work, "ownership-").toAbsolutePath();
        Path model = Files.createDirectories(root.resolve("Models/Robot"));
        Path source = model.resolve("color.png");
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xffff0000);
        ImageIO.write(image, "png", source.toFile());
        TextureToolchain tools = new TextureToolchain(toolRoot);
        GeneratedTextures store = new GeneratedTextures(root);
        String key = "Models/Robot/color.png";
        Path generated = store.ensure(key, tools);
        require(generated.equals(model.resolve("dds/color.png.dds")), "Default output path");
        var timestamp = Files.getLastModifiedTime(generated);
        require(store.ensure(key, tools).equals(generated), "Reuse path");
        require(Files.getLastModifiedTime(generated).equals(timestamp), "Fresh DDS was rewritten");
        require(new GeneratedTextures(root).resolve(key).equals(generated), "Registry did not persist");
        System.out.println("PASS generation, reuse and persistent logical mapping");

        image.setRGB(1, 1, 0xff00ff00);
        ImageIO.write(image, "png", source.toFile());
        require(store.resolve(key) == null, "Changed source should invalidate DDS");
        store.ensure(key, tools);
        require(store.resolve(key) != null, "Rebuild after source edit");
        Files.delete(generated); // Simulates deletion by an external file manager.
        require(store.resolve(key) == null, "Missing DDS should fall back");
        store.ensure(key, tools);
        System.out.println("PASS stale/missing DDS rebuild");

        FileObject generatedFile = file(generated);
        try {
            generatedFile.delete();
            throw new AssertionError("NetBeans allowed deleting generated DDS");
        } catch (java.io.IOException | java.io.UncheckedIOException expected) {
            require(Files.exists(generated), "Protected DDS was deleted");
        }
        Path target = Files.createDirectories(root.resolve("Relocated"));
        try {
            generatedFile.copy(file(target), "illegal", "dds");
            throw new AssertionError("NetBeans allowed copying generated DDS");
        } catch (java.io.IOException | java.io.UncheckedIOException expected) {
            require(!Files.exists(target.resolve("illegal.dds")), "Copy left output");
        }
        try {
            Files.writeString(model.resolve("dds/user-note.txt"), "Keep this unrelated file");
            file(model.resolve("dds")).delete();
            throw new AssertionError("NetBeans allowed deleting the generated-only folder");
        } catch (java.io.IOException | java.io.UncheckedIOException expected) {
            require(Files.exists(generated), "Protected directory deletion removed DDS");
            require(Files.exists(model.resolve("dds/user-note.txt")), "Failed deletion removed unrelated children");
        }
        System.out.println("PASS actual NetBeans file/folder copy/delete protection");

        FileObject ddsFolder = file(model.resolve("dds"));
        try (var lock = ddsFolder.lock()) {
            ddsFolder.move(lock, file(target), "dds", "");
        }
        Path moved = target.resolve("dds/color.png.dds");
        require(store.resolve(key).equals(moved), "Folder move failed to update physical mapping");
        FileObject movedFile = file(moved);
        try (var lock = movedFile.lock()) {
            movedFile.rename(lock, "renamed", "dds");
        }
        moved = target.resolve("dds/renamed.dds");
        require(store.resolve(key).equals(moved), "DDS rename failed to update mapping");
        Path sourceDestination = Files.createDirectories(root.resolve("MovedSources"));
        FileObject sourceFile = file(source);
        try (var lock = sourceFile.lock()) {
            sourceFile.move(lock, file(sourceDestination), "color", "png");
        }
        require(store.source(key).equals(sourceDestination.resolve("color.png")), "Source alias lost");
        require(store.resolve(key).equals(moved), "Logical key failed after source move");
        System.out.println("PASS folder move, DDS rename and stable source alias");

        Path imported = model.resolve("imported.dds");
        Files.copy(moved, imported);
        require(!store.containsGenerated(imported), "Imported DDS incorrectly marked generated");
        file(imported).copy(file(target), "imported-copy", "dds");
        file(imported).delete();
        file(sourceDestination.resolve("color.png")).delete();
        require(!Files.exists(moved), "Source deletion did not remove its DDS");
        require(store.source(key) == null, "Source deletion did not remove mapping");
        require(Files.exists(target.resolve("imported-copy.dds")), "Unowned DDS unexpectedly removed");
        require(Files.exists(target.resolve("dds/user-note.txt")), "Source cleanup removed unrelated directory content");
        System.out.println("PASS imported DDS unaffected and source deletion cleans owned DDS");

        Path collision = model.resolve("collision.png");
        ImageIO.write(image, "png", collision.toFile());
        Files.createDirectories(model.resolve("dds"));
        Path reserved = model.resolve("dds/collision.png.dds");
        Files.writeString(reserved, "Unowned artist resource");
        Path distinct = store.ensure("Models/Robot/collision.png", tools);
        require(!distinct.equals(reserved), "Generation reused an unowned destination");
        require(Files.readString(reserved).equals("Unowned artist resource"), "Generation overwrote artist content");
        file(collision).delete();
        require(Files.exists(reserved) && !Files.exists(distinct), "Cleanup touched an unowned collision file");
        System.out.println("PASS output collision preserves existing unowned files");
    }
}
