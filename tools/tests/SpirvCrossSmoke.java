import com.jme3.gde.core.assets.SpirvCross;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/** SDK native CI smoke. The fixture writes an RGBA constant from a fragment shader. */
public final class SpirvCrossSmoke {
    private static final String FIXTURE = "AwIjBwAAAQAKAAgADwAAAAAAAAARAAIAAQAAAAsABgABAAAAR0xTTC5z"
            + "dGQuNDUwAAAAAA4AAwAAAAAAAQAAAA8ABgAEAAAABAAAAG1haW4AAAAACQAAABAAAwAEAAAABwAAAAMAAwAC"
            + "AAAAwgEAAAUABAAEAAAAbWFpbgAAAAAFAAQACQAAAHBpeGVsAAAARwAEAAkAAAAeAAAAAAAAABMAAgACAAAA"
            + "IQADAAMAAAACAAAAFgADAAYAAAAgAAAAFwAEAAcAAAAGAAAABAAAACAABAAIAAAAAwAAAAcAAAA7AAQACAAA"
            + "AAkAAAADAAAAKwAEAAYAAAAKAAAAAACAPisABAAGAAAACwAAAAAAAD8rAAQABgAAAAwAAAAAAEA/KwAEAAYA"
            + "AAANAAAAAACAPywABwAHAAAADgAAAAoAAAALAAAADAAAAA0AAAA2AAUAAgAAAAQAAAAAAAAAAwAAAPgAAgAF"
            + "AAAAPgADAAkAAAAOAAAA/QABADgAAQA=";

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            throw new IllegalArgumentException("Pass the compiled JNI library path");
        }
        System.setProperty("jme.sdk.spirvCross.library", Path.of(args[0]).toAbsolutePath().toString());
        byte[] binary = Base64.getDecoder().decode(FIXTURE);
        String desktop = SpirvCross.toGlsl(binary, SpirvCross.Stage.FRAGMENT);
        require(desktop.contains("#version 330") && desktop.contains("void main()")
                && desktop.contains("vec4(0.25, 0.5, 0.75, 1.0)"), "Unexpected desktop GLSL");
        String es = SpirvCross.toGlsl(binary, SpirvCross.Stage.FRAGMENT, "main", 310, true);
        require(es.contains("#version 310 es") && es.contains("void main()"), "Unexpected GLSL ES");
        ByteBuffer swapped = ByteBuffer.allocate(binary.length).order(ByteOrder.BIG_ENDIAN);
        ByteBuffer source = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN);
        while (source.hasRemaining()) {
            swapped.putInt(source.getInt());
        }
        require(desktop.equals(SpirvCross.toGlsl(swapped.array(), SpirvCross.Stage.FRAGMENT)), "Word order mismatch");
        try {
            SpirvCross.toGlsl(binary, SpirvCross.Stage.VERTEX);
            throw new AssertionError("Accepted a missing vertex entry point");
        } catch (IOException expected) {
            require(expected.getMessage() != null && !expected.getMessage().isBlank(), "Missing native diagnostics");
        }
        if (args.length > 1) {
            Files.writeString(Path.of(args[1]), desktop);
        }
        System.out.println("SPIRV_CROSS_JNI_PASS");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
