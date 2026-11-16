import com.jme3.gde.texturetools.TextureToolchain;
import java.nio.file.*;
import java.time.Duration;
import java.util.Arrays;

/** Real subprocess failure diagnostics and transactional destination checks. */
public final class TextureFailureSmoke {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Path work = Path.of(args[1]).toAbsolutePath();
        Files.createDirectories(work);
        Path invalid = work.resolve("invalid-input.png");
        Files.writeString(invalid, "not an image");
        Path destination = work.resolve("preserved.dds");
        byte[] sentinel = {1, 2, 3, 4};
        Files.write(destination, sentinel);
        try {
            new TextureToolchain(root).dds(invalid, destination, "BC3", 1);
            throw new AssertionError("Invalid input accepted");
        } catch (java.io.IOException ex) {
            if (!ex.getMessage().contains(invalid.toString()) || !ex.getMessage().contains("Arguments:")) {
                throw new AssertionError("Missing source/arguments diagnostic", ex);
            }
        }
        if (!Arrays.equals(sentinel, Files.readAllBytes(destination))) {
            throw new AssertionError("Failure replaced existing destination");
        }
        try {
            new TextureToolchain(root, Duration.ofNanos(1)).dds(
                    work.resolve("source with spaces.png"), destination, "BC3", 1);
            throw new AssertionError("Expected timeout");
        } catch (java.io.IOException expected) {
            if (!expected.getMessage().contains("timed out")) {
                throw expected;
            }
        }
        if (!Arrays.equals(sentinel, Files.readAllBytes(destination))) {
            throw new AssertionError("Timeout replaced existing destination");
        }
        System.out.println("TEXTURE_FAILURE_DIAGNOSTICS_AND_PRESERVATION_PASS");
    }
}
