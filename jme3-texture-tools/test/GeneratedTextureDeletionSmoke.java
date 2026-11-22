import com.jme3.gde.texturetools.GeneratedTextures;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Windows folder deletion, output-directory preflight, and staged-root cleanup. */
public final class GeneratedTextureDeletionSmoke {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory(Path.of(args[0]), "delete-fixture-");
        Path model = Files.createDirectories(root.resolve("Model"));
        Path outputs = Files.createDirectories(model.resolve("dds"));
        Path source = Files.writeString(model.resolve("color.png"), "source");
        Path output = Files.writeString(outputs.resolve("color.png.dds"), "dds");
        Properties registry = new Properties();
        registry.setProperty("version", "1");
        registry.setProperty("a.source", "Model/color.png");
        registry.setProperty("a.output", "Model/dds/color.png.dds");
        try (var stream = Files.newOutputStream(root.resolve(GeneratedTextures.MANIFEST))) {
            registry.store(stream, "fixture");
        }
        GeneratedTextures store = new GeneratedTextures(root);
        try {
            store.delete(outputs);
            throw new AssertionError("Generated output folder was not protected");
        } catch (IOException expected) {
            if (!Files.exists(source) || !Files.exists(output)) {
                throw new AssertionError("Protected deletion removed children", expected);
            }
        }
        // FileInputStream denies delete sharing on Windows until the reader closes.
        FileInputStream reader = new FileInputStream(output.toFile());
        Thread release = new Thread(() -> {
            try {
                Thread.sleep(200);
                reader.close();
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
        });
        release.start();
        try {
            store.delete(model);
        } finally {
            reader.close();
            release.join();
        }
        if (Files.exists(model) || store.source("Model/color.png") != null) {
            throw new AssertionError("Source folder or registry entry remained");
        }
        Path stage = Files.createDirectories(root.resolve("staging/Models/Paladin"));
        Files.writeString(stage.resolve("model.j3o"), "model");
        Path stageRoot = root.resolve("staging");
        new GeneratedTextures(stageRoot).delete(stageRoot);
        if (Files.exists(stageRoot)) {
            throw new AssertionError("Staging directory remained");
        }
        System.out.println("GENERATED_TEXTURE_DELETION_PASS");
    }
}
