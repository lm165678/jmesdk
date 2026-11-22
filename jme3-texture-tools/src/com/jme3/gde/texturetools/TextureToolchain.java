package com.jme3.gde.texturetools;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.openide.modules.InstalledFileLocator;

/**
 * Host-side CPU codecs. Use the installed JNI library when present. Hosts
 * without a JNI build use the bundled command-line tools. Set
 * {@code jme.sdk.texture.backend=jni} to require in-process operation, or
 * {@code cli} to run the legacy tools. Calls block; run them off the EDT and
 * the render thread. Java checks cancellation between JNI calls and before
 * publishing a file. A JNI timeout takes effect once the codec returns.
 * CLI timeouts can terminate the subprocess immediately.
 * <pre>
 * // 第一步：在后台线程定位 SDK 随附工具；独立构建也可以传入工具根目录。
 * TextureToolchain tools = TextureToolchain.installed();
 * // 第二步：从原图生成 PC DDS；4 表示包括原始层的 mip 总数。
 * tools.dds(Path.of("source.png"), Path.of("output.dds"), "BC3", 4);
 * // 第三步：从同一原图生成 ASTC，而不是从 DDS 再压缩。
 * tools.astc(Path.of("source.png"), Path.of("output.astc"), true, "6x6");
 * // 第四步：ASTC 单文件只有一层；多层 KTX 由 CompressedTexture 编排。
 * // 第五步：切换目标只改变输出，源图始终保留；失败时不会替换已有输出。
 * // 第六步：禁止启动子进程的环境可在 SDK 启动参数加 -J-Djme.sdk.texture.backend=jni。
 * </pre>
 */
public final class TextureToolchain {
    private final Path root;
    private final Duration timeout;
    private final String backend;

    /** Uses a ten-minute timeout per invocation and an unpacked tool root. */
    public TextureToolchain(Path root) {
        this(root, Duration.ofMinutes(10));
    }

    /** Sets the tool timeout. The supplied duration must be positive. */
    public TextureToolchain(Path root, Duration timeout) {
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Timeout must be positive");
        }
        this.root = root.toAbsolutePath().normalize();
        this.timeout = timeout;
        backend = System.getProperty("jme.sdk.texture.backend", "auto");
        if (!List.of("auto", "jni", "cli").contains(backend)) {
            throw new IllegalArgumentException("Unknown texture backend: " + backend);
        }
    }

    /**
     * Locates codecs shipped with the module. JNI is loaded from the cluster
     * directory, so loading it does not extract a library or start a helper.
     */
    public static TextureToolchain installed() throws IOException {
        var location = InstalledFileLocator.getDefault().locate(
                "texture-tools", "com.jme3.gde.texturetools", false);
        if (location == null) {
            throw new IOException("Texture Tools module resources are missing; "
                    + "run Gradle prepareTextureTools and rebuild the SDK");
        }
        return new TextureToolchain(location.toPath());
    }

    /** Encodes BC1 through BC5 or BC7 on the CPU with the requested DDS mip count. */
    public void dds(Path input, Path output, String format, int levels) throws IOException, InterruptedException {
        if (!List.of("BC1", "BC2", "BC3", "BC4", "BC5", "BC7").contains(format) || levels < 1 || levels > 20) {
            throw new IllegalArgumentException("Unsupported BC format or mip count");
        }
        if (useNativeCodec()) {
            BufferedImage image = readSource(input, output);
            writeNative(output, stream -> NativeTextureCodec.writeDds(
                    image, NativeTextureCodec.bcId(format), levels, stream));
            return;
        }
        encodeWithCrashRetry(false, input, output, List.of("-fd", format, "-EncodeWith", "CPU",
                "-miplevels", Integer.toString(levels)), List.of());
    }

    /** Encodes one ASTC mip level at medium quality using the supplied color profile. */
    public void astc(Path input, Path output, boolean srgb, String block) throws IOException, InterruptedException {
        if (!List.of("4x4", "5x5", "6x6", "8x8", "10x10", "12x12").contains(block)) {
            throw new IllegalArgumentException("Unsupported ASTC block size");
        }
        if (useNativeCodec()) {
            BufferedImage image = readSource(input, output);
            int blockSize = Integer.parseInt(block.substring(0, block.indexOf('x')));
            writeNative(output, stream -> {
                byte[] payload = NativeTextureCodec.encode(image, 0, blockSize, srgb);
                byte[] header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(0x5ca1ab13)
                        .put((byte) blockSize).put((byte) blockSize).put((byte) 1).array();
                CompressedTexture.put24(header, 7, image.getWidth());
                CompressedTexture.put24(header, 10, image.getHeight());
                CompressedTexture.put24(header, 13, 1);
                stream.write(header);
                stream.write(payload);
            });
            return;
        }
        encodeWithCrashRetry(true, input, output, List.of(srgb ? "-cs" : "-cl"), List.of(block, "-medium"));
    }

    /** Decodes the base level to PNG on the CPU. */
    public void decode(Path input, Path output, boolean astc, boolean srgb) throws IOException, InterruptedException {
        if (useNativeCodec()) {
            checkSource(input, output);
            BufferedImage image = CompressedTexture.read(input).decodeNative(0, srgb);
            writeNative(output, stream -> {
                if (!ImageIO.write(image, "png", stream)) {
                    throw new IOException("PNG writer unavailable");
                }
            });
            return;
        }
        encodeWithCrashRetry(astc, input, output, astc ? List.of(srgb ? "-ds" : "-dl") : List.of(), List.of());
    }

    /**
     * Reports whether the configured DDS encoder should be used. An explicit
     * backend stays strict. Automatic mode lets the model exporter choose a
     * portable encoder when no installed codec supports the current host.
     *
     * <pre>
     * // 第一步：调用 hasConfiguredDdsEncoder() 查询本机编码能力。
     * // 第二步：有编码器时使用 dds()。仅自动模式缺少编码器时采用跨平台回退。
     * </pre>
     *
     * @return whether to use this toolchain
     */
    public boolean hasConfiguredDdsEncoder() {
        if (!"auto".equals(backend) || Files.isRegularFile(NativeTextureCodec.library(root))) {
            return true;
        }
        try {
            executable(false);
            return true;
        } catch (IOException unavailable) {
            return false;
        }
    }

    boolean useNativeCodec() throws IOException {
        if ("cli".equals(backend)) {
            return false;
        }
        Path library = NativeTextureCodec.library(root);
        if (Files.isRegularFile(library)) {
            NativeTextureCodec.load(library);
            return true;
        }
        if ("jni".equals(backend)) {
            throw new IOException("Required SDK JNI texture codec is missing: " + library);
        }
        return false;
    }

    private static void checkSource(Path source, Path target) throws IOException {
        if (!Files.isRegularFile(source) || source.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())
                || (Files.exists(target) && Files.isSameFile(source, target))) {
            throw new IOException("Source must exist and differ from destination");
        }
    }

    private static BufferedImage readSource(Path source, Path target) throws IOException, InterruptedException {
        NativeTextureCodec.checkInterrupted();
        checkSource(source, target);
        BufferedImage image = ImageIO.read(source.toFile());
        if (image == null) {
            throw new IOException("Unreadable source image: " + source);
        }
        return image;
    }

    @FunctionalInterface
    interface NativeOutput {
        void write(OutputStream stream) throws IOException, InterruptedException;
    }

    void writeNative(Path output, NativeOutput encoder) throws IOException, InterruptedException {
        NativeTextureCodec.checkInterrupted();
        long start = System.nanoTime();
        Path target = output.toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".texture-", ".tmp");
        try {
            try (OutputStream stream = Files.newOutputStream(temporary)) {
                encoder.write(stream);
            }
            NativeTextureCodec.checkInterrupted();
            if (Duration.ofNanos(System.nanoTime() - start).compareTo(timeout) >= 0) {
                throw new IOException("Texture encoder timed out after " + timeout);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void encodeWithCrashRetry(boolean astc, Path input, Path output,
            List<String> before, List<String> after) throws IOException, InterruptedException {
        IOException firstFailure = null;
        for (int attempt = 0; ; attempt++) {
            try {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Texture operation cancelled");
                }
                encode(astc, input, output, before, after);
                return;
            } catch (IOException failure) {
                if (attempt != 0 || !(failure instanceof ToolFailure nativeFailure)
                        || nativeFailure.exitCode != -1073741819) {
                    if (firstFailure != null) {
                        failure.addSuppressed(firstFailure);
                    }
                    throw failure;
                }
                firstFailure = failure;
                java.util.logging.Logger.getLogger(TextureToolchain.class.getName()).log(
                        java.util.logging.Level.WARNING,
                        "Texture tool crashed; retrying once with fresh temporary output: " + input, failure);
            }
        }
    }

    private static final class ToolFailure extends IOException {
        private final int exitCode;

        ToolFailure(int exitCode, Path executable, Path source, Path target,
                List<String> command, String details) {
            super("Texture encoder failed (exit " + exitCode + ", "
                    + String.format(Locale.ROOT, "0x%08X", exitCode) + ")"
                    + "\nTool: " + executable + "\nSource: " + source + "\nDestination: " + target
                    + "\nArguments: " + command
                    + "\nOutput: " + (details.isBlank() ? "(no diagnostic output)" : details));
            this.exitCode = exitCode;
        }
    }

    private Path executable(boolean astc) throws IOException {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        boolean arm = arch.equals("aarch64") || arch.equals("arm64");
        String host;
        if (os.startsWith("windows")) {
            host = "windows";
        } else if (os.startsWith("linux")) {
            host = "linux";
        } else if (os.startsWith("mac")) {
            host = "macos";
        } else {
            throw new IOException("Unsupported tool host: " + os + "/" + arch);
        }
        if ((!x64 && !arm) || (!astc && (!x64 || host.equals("macos")))) {
            throw new IOException("No " + (astc ? "ASTC" : "Compressonator") + " binary for " + host + "/" + arch);
        }
        String suffix = host.equals("windows") ? ".exe" : "";
        Path result;
        if (astc) {
            String platform = host.equals("macos") ? "macos-universal" : host + (x64 ? "-x86_64" : "-arm64");
            String name = host.equals("macos") ? "astcenc" : "astcenc-" + (x64 ? "sse2" : "neon") + suffix;
            result = root.resolve("astcenc/5.7.1/" + platform + "/bin/" + name);
        } else {
            String folder = host.equals("windows") ? "compressonatorcli-4.6.0-win64" : "compressonatorcli_linux_x86_64_4.6.0";
            String name = host.equals("windows") ? "compressonatorcli.exe" : "compressonatorcli-bin";
            result = root.resolve("compressonator/4.6.0/" + host + "-x86_64/" + folder + "/" + name);
        }
        if (!Files.isRegularFile(result)) {
            throw new IOException("Missing encoder: " + result);
        }
        if (!host.equals("windows") && !Files.isExecutable(result)) {
            throw new IOException("Encoder needs execute permission: " + result);
        }
        return result;
    }

    private void encode(boolean astc, Path input, Path output, List<String> before, List<String> after)
            throws IOException, InterruptedException {
        Path source = input.toAbsolutePath().normalize();
        Path target = output.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source) || source.equals(target)) {
            throw new IOException("Source must exist and differ from destination");
        }
        Path executable = executable(astc);
        Files.createDirectories(target.getParent());
        String filename = target.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        if (dot < 0) {
            throw new IOException("Output requires a format extension");
        }
        Path temporary = Files.createTempFile(target.getParent(), ".texture-", filename.substring(dot));
        Path log = Files.createTempFile("texture-tool-", ".log");
        Process process = null;
        try {
            Files.delete(temporary);
            List<String> command = new ArrayList<>();
            command.add(executable.toString());
            command.addAll(before);
            command.add(source.toString());
            command.add(temporary.toString());
            command.addAll(after);
            ProcessBuilder builder = new ProcessBuilder(command).directory(executable.getParent().toFile())
                    .redirectErrorStream(true).redirectOutput(log.toFile());
            if (!astc && System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("linux")) {
                String directory = executable.getParent().toString();
                String previous = builder.environment().get("LD_LIBRARY_PATH");
                builder.environment().put("LD_LIBRARY_PATH", directory + ":" + directory + "/qt:" + directory
                        + "/pkglibs" + (previous == null ? "" : ":" + previous));
                builder.environment().put("QT_PLUGIN_PATH", directory + "/plugins");
            }
            process = builder.start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IOException("Texture encoder timed out after " + timeout);
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(temporary) || Files.size(temporary) == 0) {
                String details;
                try (var stream = Files.newInputStream(log)) {
                    details = new String(stream.readNBytes(16384), java.nio.charset.StandardCharsets.UTF_8);
                }
                throw new ToolFailure(process.exitValue(), executable, source, target, command, details);
            }
            try {
                if (System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows")) {
                    Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    return;
                }
                Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(temporary);
            Files.deleteIfExists(log);
        }
    }
}
