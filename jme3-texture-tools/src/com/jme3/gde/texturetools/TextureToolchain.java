package com.jme3.gde.texturetools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.openide.modules.InstalledFileLocator;

/**
 * Host-side CPU texture encoders. This class never selects a binary based on the
 * game target. Compressonator is supported on Windows/Linux x86-64 only; ASTC
 * also supports ARM64 and macOS. x86-64 uses SSE2, without assuming AVX support.
 * Calls block and must run off the Swing EDT and engine rendering thread.
 * No subprocess survives interruption or timeout. Tools are not game assets.
 * A native access violation is retried once with fresh temporary output. Other
 * failures are not retried; existing destinations remain unchanged on failure.
 * <pre>
 * // 第一步：在后台线程定位 SDK 随附工具；独立构建也可以传入工具根目录。
 * TextureToolchain tools = TextureToolchain.installed();
 * // 第二步：从原图生成 PC DDS；4 表示包括原始层的 mip 总数。
 * tools.dds(Path.of("source.png"), Path.of("output.dds"), "BC3", 4);
 * // 第三步：从同一原图生成 ASTC，而不是从 DDS 再压缩。
 * tools.astc(Path.of("source.png"), Path.of("output.astc"), true, "6x6");
 * // 第四步：ASTC 单文件只有一层；多层 KTX 由 CompressedTexture 编排。
 * // 第五步：切换目标只改变输出，源图始终保留；失败时不会替换已有输出。
 * // 第六步：原生访问冲突仅重试一次；再次失败时按异常中的 Source 定位纹理。
 * </pre>
 */
public final class TextureToolchain {
    private final Path root;
    private final Duration timeout;

    /** Uses a ten-minute timeout per invocation and an unpacked tool root. */
    public TextureToolchain(Path root) {
        this(root, Duration.ofMinutes(10));
    }

    /** Allows build systems to specify a positive timeout. */
    public TextureToolchain(Path root, Duration timeout) {
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Timeout must be positive");
        }
        this.root = root.toAbsolutePath().normalize();
        this.timeout = timeout;
    }

    /**
     * Locates installed module resources. The Gradle {@code prepareTextureTools}
     * task downloads the official encoders before the SDK is built. Missing tools
     * cause an explicit error rather than silently skipping texture conversion.
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

    /** Encodes BC1 through BC5 or BC7 DDS using CPU encoding and explicit mip count. */
    public void dds(Path input, Path output, String format, int levels) throws IOException, InterruptedException {
        if (!List.of("BC1", "BC2", "BC3", "BC4", "BC5", "BC7").contains(format) || levels < 1 || levels > 20) {
            throw new IllegalArgumentException("Unsupported BC format or mip count");
        }
        encodeWithCrashRetry(false, input, output, List.of("-fd", format, "-EncodeWith", "CPU",
                "-miplevels", Integer.toString(levels)), List.of());
    }

    /** Encodes one ASTC level, with medium quality and an explicit color profile. */
    public void astc(Path input, Path output, boolean srgb, String block) throws IOException, InterruptedException {
        if (!List.of("4x4", "5x5", "6x6", "8x8", "10x10", "12x12").contains(block)) {
            throw new IllegalArgumentException("Unsupported ASTC block size");
        }
        encodeWithCrashRetry(true, input, output, List.of(srgb ? "-cs" : "-cl"), List.of(block, "-medium"));
    }

    /** CPU-decodes a single level to PNG. Retries a native access violation once;
     * other failures and interruption propagate immediately. */
    public void decode(Path input, Path output, boolean astc, boolean srgb) throws IOException, InterruptedException {
        encodeWithCrashRetry(astc, input, output, astc ? List.of(srgb ? "-ds" : "-dl") : List.of(), List.of());
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
