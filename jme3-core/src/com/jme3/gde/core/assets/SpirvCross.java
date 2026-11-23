package com.jme3.gde.core.assets;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import org.openide.modules.InstalledFileLocator;

/**
 * SDK-side SPIR-V cross-compiler backed by the bundled Khronos C API.
 * Each call owns its native context. Run conversion on an editor worker thread.
 * <pre>{@code
 * // 第一步：用 HLSL/GLSL 编译器生成顶点或片元阶段的 .spv。
 * byte[] binary = Files.readAllBytes(Path.of("surface.frag.spv"));
 * // 第二步：选择原入口点及目标 OpenGL GLSL 版本。
 * String glsl = SpirvCross.toGlsl(binary, SpirvCross.Stage.FRAGMENT, "main", 330, false);
 * // 第三步：将 GLSL 保存为 SDK 资产，再由 JME 材质绑定输入和资源。
 * Files.writeString(Path.of("Surface.frag"), glsl);
 * }</pre>
 * The result uses OpenGL GLSL semantics. Material parameters, vertex attributes and
 * renderer inputs must be bound by the importer or the authoring material.
 */
public final class SpirvCross {
    private static final int MAGIC = 0x07230203;
    private static boolean loaded;

    /** SPIR-V execution model and conventional JME shader extension. */
    public enum Stage {
        VERTEX(0, "vert"), TESS_CONTROL(1, "tesc"), TESS_EVALUATION(2, "tese"),
        GEOMETRY(3, "geom"), FRAGMENT(4, "frag"), COMPUTE(5, "comp");

        private final int executionModel;
        private final String extension;

        Stage(int executionModel, String extension) {
            this.executionModel = executionModel;
            this.extension = extension;
        }

        public String extension() {
            return extension;
        }
    }

    private SpirvCross() {
    }

    /**
     * Cross-compiles one entry point, defaulting to desktop GLSL 330 and main.
     * @throws IOException if the input is malformed, the entry point is missing,
     *         the target cannot express the shader, or the bundled native is unavailable
     */
    public static String toGlsl(byte[] binary, Stage stage) throws IOException {
        return toGlsl(binary, stage, "main", 330, false);
    }

    /** Cross-compiles using an explicit entry point and desktop GLSL or GLSL ES target. */
    public static String toGlsl(byte[] binary, Stage stage, String entryPoint, int version, boolean es)
            throws IOException {
        Objects.requireNonNull(binary, "binary");
        Objects.requireNonNull(stage, "stage");
        if (entryPoint == null || entryPoint.isBlank() || entryPoint.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("A SPIR-V entry point is required");
        }
        if (version <= 0) {
            throw new IllegalArgumentException("Use a positive target GLSL version");
        }
        if (binary.length < 5 * Integer.BYTES || binary.length % Integer.BYTES != 0) {
            throw new IOException("SPIR-V must contain a five-word header and complete 32-bit words");
        }
        ByteBuffer buffer = ByteBuffer.wrap(binary).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.getInt(0) == Integer.reverseBytes(MAGIC)) {
            buffer.order(ByteOrder.BIG_ENDIAN);
        }
        if (buffer.getInt(0) != MAGIC) {
            throw new IOException("Invalid SPIR-V magic word");
        }
        int[] words = new int[binary.length / Integer.BYTES];
        buffer.asIntBuffer().get(words);
        loadLibrary();
        return new String(compile(words, stage.executionModel, entryPoint, version, es), StandardCharsets.UTF_8);
    }

    private static synchronized void loadLibrary() throws IOException {
        if (loaded) {
            return;
        }
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        os = os.startsWith("windows") ? "windows" : os.startsWith("mac") ? "macos" : os;
        arch = "amd64".equals(arch) ? "x86_64" : "aarch64".equals(arch) ? "arm64" : arch;
        String target = os + "-" + arch;
        String override = System.getProperty("jme.sdk.spirvCross.library");
        File library = override == null ? InstalledFileLocator.getDefault().locate(
                "lib/spirv-cross/" + target + "/" + System.mapLibraryName("jme-spirv-cross"),
                "com.jme3.gde.core", false) : new File(override);
        if (library == null || !library.isFile()) {
            throw new IOException("The SDK SPIRV-Cross library is missing for " + target);
        }
        try {
            System.load(library.getCanonicalPath());
            loaded = true;
        } catch (LinkageError | SecurityException failure) {
            throw new IOException("Unable to load SPIRV-Cross from " + library, failure);
        }
    }

    private static native byte[] compile(int[] words, int executionModel, String entryPoint, int version, boolean es)
            throws IOException;
}
