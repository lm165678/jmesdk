package com.jme3.gde.texturetools;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import javax.imageio.ImageIO;

/**
 * Persistent SDK ownership and logical-path mapping for generated PC textures.
 * Source images stay intact; generated BC3 DDS files live in a sibling dds
 * directory, retaining the original extension. Existing unowned output files
 * are never overwritten. All mutations are serialized, and must run off EDT.
 * Missing/changed output is regenerated on conversion; lookup falls back to the
 * original image if freshness validation fails. Only PNG/JPEG 2D LDR inputs are
 * generated here; imported compressed textures remain user-owned.
 * <pre>
 * // 第一步：使用项目资源根目录创建登记表。
 * GeneratedTextures store = new GeneratedTextures(Path.of("assets"));
 * // 第二步：后台转换时生成或复用 DDS，原始纹理 key 保持不变。
 * Path dds = store.ensure("Models/Robot/color.png", TextureToolchain.installed());
 * // 第三步：加载前 resolve 查询物理路径；null 表示使用原图。
 * Path selected = store.resolve("Models/Robot/color.png");
 * // 第四步：在 SDK 移动 DDS 或 dds 文件夹时调用 move，同步修改登记表。
 * // 第五步：删除源图会清理 DDS；只删除 DDS 时保留映射，重新打开 J3O 可恢复。
 * // 第六步：外部删除 DDS 后重新转换可恢复；不要手工编辑登记表。
 * </pre>
 */
public final class GeneratedTextures {
    public static final String MANIFEST = ".jme-textures.properties";
    private static final String PROFILE = "compressonator-4.6.0:BC3:mips-full:cpu:v1";
    private static final String PORTABLE_PROFILE = "jsquish:BC3:mips-full:cpu:v1";
    private static final Object LOCK = new Object();
    private final Path root;

    public GeneratedTextures(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /** Finds the closest enclosing ownership registry, or null for ordinary files. */
    public static GeneratedTextures containing(Path path) {
        Path current = path.toAbsolutePath().normalize();
        if (!Files.isDirectory(current)) {
            current = current.getParent();
        }
        while (current != null) {
            if (Files.isRegularFile(current.resolve(MANIFEST))) {
                return new GeneratedTextures(current);
            }
            current = current.getParent();
        }
        return null;
    }

    private Properties read() throws IOException {
        Properties properties = new Properties();
        if (Files.exists(root.resolve(MANIFEST))) {
            try (InputStream input = Files.newInputStream(root.resolve(MANIFEST))) {
                properties.load(input);
            }
            if (!"1".equals(properties.getProperty("version"))) {
                throw new IOException("Unknown texture ownership manifest version");
            }
        }
        return properties;
    }

    private List<String> ids(Properties properties) {
        return properties.stringPropertyNames().stream().filter(key -> key.endsWith(".source"))
                .map(key -> key.substring(0, key.length() - 7)).sorted().toList();
    }

    private Path checked(String relative) throws IOException {
        if (relative == null || relative.isBlank()) {
            throw new IOException("Missing texture registry path");
        }
        Path result;
        try {
            result = root.resolve(relative).normalize();
        } catch (java.nio.file.InvalidPathException failure) {
            throw new IOException("Invalid texture path: " + relative, failure);
        }
        if (!result.startsWith(root) || result.equals(root)) {
            throw new IOException("Texture path escapes asset root: " + relative);
        }
        // Skip links or junctions that lead outside the registered root.
        Path existing = result;
        while (!Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (!existing.toRealPath().startsWith(root.toRealPath())) {
            throw new IOException("Texture path resolves outside asset root: " + relative);
        }
        return result;
    }

    private String relative(Path file) throws IOException {
        Path normalized = file.toAbsolutePath().normalize();
        if (!normalized.startsWith(root) || normalized.equals(root)) {
            throw new IOException("Managed texture moves must remain inside the asset root");
        }
        String result = root.relativize(normalized).toString().replace('\\', '/');
        checked(result);
        return result;
    }

    private void save(Properties properties) throws IOException {
        properties.setProperty("version", "1");
        Path temporary = Files.createTempFile(root, ".texture-registry-", ".tmp");
        try {
            try (var output = Files.newOutputStream(temporary)) {
                properties.store(output, "SDK generated texture ownership. Do not edit manually.");
            }
            Files.move(temporary, root.resolve(MANIFEST), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Revision of the contents, even when the file size and timestamp stay the same. */
    public static String contentRevision(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, count);
                }
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private static String hash(Path file) throws IOException {
        return contentRevision(file);
    }

    private String find(Properties properties, String key) throws IOException {
        for (String id : ids(properties)) {
            if (key.equals(properties.getProperty(id + ".source"))) {
                return id;
            }
        }
        if (Files.isRegularFile(checked(key))) {
            return null;
        }
        for (String id : ids(properties)) {
            if (key.equals(properties.getProperty(id + ".key"))
                    || properties.getProperty(id + ".aliases", "").lines().anyMatch(key::equals)) {
                return id;
            }
        }
        return null;
    }

    /** Rebuilds a missing or stale DDS. Returns null for sources other than PNG or JPEG. */
    public Path ensure(String key, TextureToolchain tools) throws IOException, InterruptedException {
        return ensure(key, PROFILE, false, false, (source, image, temporary, levels) ->
                tools.dds(source, temporary, "BC3", levels));
    }

    /** Generates a managed sidecar for a material that keeps the original texture key. */
    public Path ensureLogicalDds(String key, TextureToolchain tools) throws IOException, InterruptedException {
        return ensure(key, PROFILE, false, true, (source, image, temporary, levels) ->
                tools.dds(source, temporary, "BC3", levels));
    }
    /** Encoder used when the SDK has no native or CLI texture encoder for the host. */
    @FunctionalInterface
    public interface PortableBc3Encoder {
        /**
         * Writes a complete BC3 DDS file, including all requested mip levels.
         *
         * @param image the decoded source image
         * @param temporary destination inside the asset root
         * @param levels mip count including the base image
         * @throws IOException on encoding or write failure
         * @throws InterruptedException if the encoding was interrupted
         */
        void write(java.awt.image.BufferedImage image, Path temporary, int levels)
                throws IOException, InterruptedException;
    }

    /**
     * Generates the same external DDS dependency with a portable BC3 encoder.
     * Current outputs from either encoder can be reused safely.
     * <pre>
     * // 第一步：准备项目的 PNG/JPEG 资源键。
     * // 第二步：调用 ensurePortableBc3(key, encoder) 生成完整 BC3 DDS。
     * // 第三步：通过返回的路径加载 DDS，或用 ensure(key, tools) 恢复工具链默认路径。
     * </pre>
     *
     * @param key logical PNG or JPEG asset key
     * @param encoder portable BC3 encoder
     * @return current generated DDS path, or null for other source formats
     * @throws IOException if the source or destination cannot be processed
     * @throws InterruptedException if encoding is interrupted
     */
    public Path ensurePortableBc3(String key, PortableBc3Encoder encoder)
            throws IOException, InterruptedException {
        return ensure(key, PORTABLE_PROFILE, false, false, (source, image, temporary, levels) ->
                encoder.write(image, temporary, levels));
    }

    /**
     * For ordinary J3O saves, generates a BC3 sidecar only when neither the
     * registered output nor a conventionally named DDS exists. Existing DDS
     * files stay untouched, including manually created files.
     */
    public Path ensureMissingPortableBc3(String key, PortableBc3Encoder encoder)
            throws IOException, InterruptedException {
        return ensure(key, PORTABLE_PROFILE, true, true, (source, image, temporary, levels) ->
                encoder.write(image, temporary, levels));
    }

    /** Restores a missing DDS named directly in a legacy J3O, if its source is registered. */
    public void restoreMissingOutput(String key, PortableBc3Encoder encoder)
            throws IOException, InterruptedException {
        String source = null;
        synchronized (LOCK) {
            if (Files.exists(checked(key))) {
                return;
            }
            Properties properties = read();
            for (String id : ids(properties)) {
                if (key.equals(properties.getProperty(id + ".output"))) {
                    String candidate = properties.getProperty(id + ".source");
                    if (Files.isRegularFile(checked(candidate))) {
                        source = candidate;
                    }
                    break;
                }
            }
        }
        if (source != null) {
            ensurePortableBc3(source, encoder);
        }
    }

    @FunctionalInterface
    private interface Encoder {
        void write(Path source, java.awt.image.BufferedImage image, Path temporary, int levels)
                throws IOException, InterruptedException;
    }

    private Path ensure(String key, String profile, boolean onlyMissing, boolean followSource, Encoder encoder)
            throws IOException, InterruptedException {
        Properties properties;
        String id;
        String sourceName;
        String sourceHash;
        Path source;
        Path output;
        boolean existing;
        synchronized (LOCK) {
            properties = read();
            id = find(properties, key);
            existing = id != null;
            sourceName = id == null ? key : properties.getProperty(id + ".source");
            String lower = sourceName.toLowerCase(java.util.Locale.ROOT);
            if (!lower.endsWith(".png") && !lower.endsWith(".jpg") && !lower.endsWith(".jpeg")) {
                return null;
            }
            source = checked(sourceName);
            if (onlyMissing) {
                Path present = existingOutput(properties, id, source);
                if (present != null || !Files.isRegularFile(source)) {
                    return present;
                }
            }
            sourceHash = hash(source);
            if (id != null) {
                Path valid = resolve(properties, id);
                if (valid != null) {
                    return valid;
                }
            }
            if (id == null) {
                id = UUID.randomUUID().toString();
                output = source.getParent().resolve("dds").resolve(source.getFileName() + ".dds");
                if (Files.exists(output)) {
                    output = output.resolveSibling(source.getFileName() + "." + id + ".dds");
                }
            } else {
                output = checked(properties.getProperty(id + ".output"));
            }
            relative(output);
        }
        // Release the registry lock while encoding so filesystem operations and
        // action enablement can proceed.
        Path temporary = Files.createTempFile(root, ".texture-generation-", ".dds");
        try {
            var image = ImageIO.read(source.toFile());
            if (image == null) {
                throw new IOException("Cannot decode texture source: " + source);
            }
            int levels = 32 - Integer.numberOfLeadingZeros(Math.max(image.getWidth(), image.getHeight()));
            encoder.write(source, image, temporary, levels);
            synchronized (LOCK) {
                if (!properties.equals(read()) || !sourceHash.equals(hash(source))) {
                    throw new IOException("Texture sources or ownership changed during encoding; retry conversion");
                }
                if (!existing && Files.exists(output)) {
                    throw new IOException("An unowned file appeared at the generated texture destination");
                }
                Files.createDirectories(output.getParent());
                relative(output);
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
                properties.setProperty(id + ".key", properties.getProperty(id + ".key", key));
                properties.setProperty(id + ".source", sourceName);
                properties.setProperty(id + ".output", relative(output));
                properties.setProperty(id + ".sourceHash", sourceHash);
                properties.setProperty(id + ".outputHash", hash(output));
                properties.setProperty(id + ".profile", profile);
                if (followSource && !existing) {
                    properties.setProperty(id + ".followSource", "true");
                }
                save(properties);
                return output;
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Path existingOutput(Properties properties, String id, Path source) throws IOException {
        if (id != null) {
            Path registered = checked(properties.getProperty(id + ".output"));
            if (Files.isRegularFile(registered)) {
                return registered;
            }
        }
        String name = source.getFileName().toString();
        for (Path directory : List.of(source.getParent().resolve("dds"), source.getParent())) {
            Path file = checked(root.relativize(directory.resolve(name + ".dds")).toString());
            if (Files.isRegularFile(file)) {
                return file;
            }
        }
        return null;
    }

    /** Returns a current DDS on disk, or null to let the caller load the source. */
    public Path resolve(String key) throws IOException {
        synchronized (LOCK) {
            Properties properties = read();
            String id = find(properties, key);
            return id == null ? null : resolve(properties, id);
        }
    }

    /** Finds the source behind an alias, even after its folder is moved. */
    public Path source(String key) throws IOException {
        synchronized (LOCK) {
            Properties properties = read();
            String id = find(properties, key);
            return id == null ? null : checked(properties.getProperty(id + ".source"));
        }
    }

    private static boolean isCurrentProfile(String profile) {
        return PROFILE.equals(profile) || PORTABLE_PROFILE.equals(profile);
    }

    private Path resolve(Properties properties, String id) throws IOException {
        Path source = checked(properties.getProperty(id + ".source"));
        Path output = checked(properties.getProperty(id + ".output"));
        if (Files.isRegularFile(source) && Files.isRegularFile(output)
                && isCurrentProfile(properties.getProperty(id + ".profile"))
                && hash(source).equals(properties.getProperty(id + ".sourceHash"))
                && hash(output).equals(properties.getProperty(id + ".outputHash"))) {
            return output;
        }
        return null;
    }

    /** Checks registry entries for outputs under this path. Directory names do not imply ownership. */
    public boolean containsGenerated(Path path) throws IOException {
        synchronized (LOCK) {
            Path target = path.toAbsolutePath().normalize();
            Properties properties = read();
            for (String id : ids(properties)) {
                if (checked(properties.getProperty(id + ".output")).startsWith(target)) {
                    return true;
                }
            }
            return target.equals(root.resolve(MANIFEST));
        }
    }

    /**
     * Checks whether a directory directly contains registered DDS outputs, so a
     * caller can refuse to rename generated texture folders without depending on
     * the literal "dds" directory name. Ordinary folders and folders that merely
     * have generated outputs below them return false. An absent manifest or an
     * unreadable registry also yields false; callers that must protect the whole
     * managed tree should additionally check {@link #affects(Path)}.
     * <pre>
     * // 第一步：先确认路径处在生成纹理登记表管辖范围内。
     * GeneratedTextures store = GeneratedTextures.containing(folderPath);
     * if (store == null) {
     *     // 普通目录：可以重命名。
     * }
     * // 第二步：判断该目录本身是否直接存放已登记的 DDS 输出。
     * boolean generated = store.isGeneratedOutputDirectory(folderPath);
     * // 第三步：generated 为 true 时禁止重命名该目录；应重命名源纹理后重新转换。
     * // 第四步：本方法只读登记表；真正的文件改名仍需在后台线程执行。
     * </pre>
     *
     * @param path directory to test; a file path is treated as its parent
     * @return true when a registered output lives directly in the directory
     * @throws IOException if the registry cannot be read
     */
    public boolean isGeneratedOutputDirectory(Path path) throws IOException {
        synchronized (LOCK) {
            Path target = path.toAbsolutePath().normalize();
            if (!Files.isDirectory(target)) {
                target = target.getParent();
            }
            if (target == null) {
                return false;
            }
            Properties properties = read();
            for (String id : ids(properties)) {
                String relative = properties.getProperty(id + ".output");
                if (relative == null || relative.isBlank()) {
                    continue;
                }
                // Compare the parent paths without touching the disk for each output file.
                Path parent = root.resolve(relative).normalize().getParent();
                if (target.equals(parent)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Whether a mutation touches registered sources, outputs or the registry. */
    public boolean affects(Path path) throws IOException {
        synchronized (LOCK) {
            Path target = path.toAbsolutePath().normalize();
            if (root.resolve(MANIFEST).startsWith(target)) {
                return true;
            }
            Properties properties = read();
            for (String id : ids(properties)) {
                if (checked(properties.getProperty(id + ".source")).startsWith(target)
                        || checked(properties.getProperty(id + ".output")).startsWith(target)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Returns true if the whole registry root is moving and relative paths stay valid. */
    public boolean isRoot(Path path) {
        return root.equals(path.toAbsolutePath().normalize());
    }

    /** Prevents copying generated files, including copies of a parent folder. */
    public void checkCopy(Path path) throws IOException {
        if (containsGenerated(path)) {
            throw new IOException("Generated DDS resources cannot be copied. Copy source images and convert again.");
        }
    }

    /**
     * Removes sources with their DDS files. Deleting one DDS retains its mapping.
     * Call this once for a managed folder before NetBeans recursively deletes its
     * children, so output-folder protection is checked before any child is removed.
     * Directory enumeration is closed before deletion; transient filesystem locks
     * receive bounded retries. A persistent failure is reported and may leave a
     * partially removed tree; registry records are retained for retry.
     * <pre>
     * // 第一步：用户确认后，在后台线程取得目录所属登记表。
     * GeneratedTextures store = GeneratedTextures.containing(selectedFolder);
     * // 第二步：整个目录只调用一次，不先逐个删除子文件。
     * if (store != null) {
     *     store.delete(selectedFolder);
     * }
     * // 第三步：刷新资源树；失败显示具体路径，保留登记以便重试。
     * </pre>
     */
    public void delete(Path path) throws IOException {
        synchronized (LOCK) {
            Path target = isRoot(path) ? root : checked(relative(path));
            if (target.equals(root.resolve(MANIFEST))) {
                throw new IOException("The generated-texture registry cannot be deleted separately");
            }
            Properties properties = read();
            List<String> removed = new ArrayList<>();
            List<Path> outputs = new ArrayList<>();
            for (String id : ids(properties)) {
                Path source = checked(properties.getProperty(id + ".source"));
                Path output = checked(properties.getProperty(id + ".output"));
                if (output.equals(target)) {
                    deleteWithRetry(output);
                    return;
                }
                if (output.startsWith(target) && !source.startsWith(target)) {
                    throw new IOException("The generated DDS directory cannot be deleted separately: " + target);
                }
                if (source.startsWith(target)) {
                    removed.add(id);
                    if (!output.startsWith(target)) {
                        outputs.add(output);
                    }
                }
            }
            // Check each path against the registered root before deleting recursively.
            if (Files.isDirectory(target)) {
                List<Path> deletionOrder;
                try (var files = Files.walk(target)) {
                    deletionOrder = files.sorted(Comparator.reverseOrder()).toList();
                }
                for (Path file : deletionOrder) {
                    deleteWithRetry(file);
                }
            } else {
                deleteWithRetry(target);
            }
            for (Path output : outputs) {
                deleteWithRetry(output);
            }
            for (String id : removed) {
                properties.keySet().removeIf(key -> key.toString().startsWith(id + "."));
            }
            if (Files.isDirectory(root)) {
                save(properties);
            }
        }
    }

    private static void deleteWithRetry(Path path) throws IOException {
        for (int attempt = 0; ; attempt++) {
            try {
                Files.deleteIfExists(path);
                return;
            } catch (java.nio.file.FileSystemException failure) {
                if (attempt >= 5) {
                    throw failure;
                }
                try {
                    Thread.sleep(50L * (attempt + 1));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new java.io.InterruptedIOException("Interrupted deleting " + path);
                }
            }
        }
    }

    /** Moves sources and relocates their logical-key sidecars, keeping old texture keys readable. */
    public void move(Path from, Path to) throws IOException {
        synchronized (LOCK) {
            Path source = checked(relative(from));
            Path destination = checked(relative(to));
            if (source.equals(root.resolve(MANIFEST)) || destination.startsWith(source)) {
                throw new IOException("Invalid managed texture move");
            }
            if (isGeneratedOutputDirectory(source)) {
                throw new IOException("Generated DDS files and their directory cannot be moved or renamed");
            }
            Properties properties = read();
            var pending = new ArrayList<Relocation>();
            for (String id : ids(properties)) {
                Path previousSource = checked(properties.getProperty(id + ".source"));
                Path previousOutput = checked(properties.getProperty(id + ".output"));
                Path movedSource = relocate(previousSource, source, destination);
                Path movedOutput = relocate(previousOutput, source, destination);
                Path output = movedOutput;
                if (!movedSource.equals(previousSource)
                        && Boolean.parseBoolean(properties.getProperty(id + ".followSource"))) {
                    String name = movedSource.getFileName().toString();
                    output = movedSource.getParent().resolve("dds").resolve(name + ".dds");
                    if (!movedOutput.equals(output) && Files.exists(relocate(output, destination, source))) {
                        output = output.resolveSibling(name + "." + id + ".dds");
                    }
                    if (!movedOutput.equals(output) && Files.isRegularFile(previousOutput)) {
                        pending.add(new Relocation(movedOutput, output));
                    }
                }
                if (!movedSource.equals(previousSource)) {
                    String previousKey = relative(previousSource);
                    String aliasesKey = id + ".aliases";
                    String aliases = properties.getProperty(aliasesKey, "");
                    if (!previousKey.equals(properties.getProperty(id + ".key"))
                            && aliases.lines().noneMatch(previousKey::equals)) {
                        properties.setProperty(aliasesKey, aliases + previousKey + "\n");
                    }
                    properties.setProperty(id + ".source", relative(movedSource));
                }
                properties.setProperty(id + ".output", relative(output));
            }
            Files.move(source, destination);
            var completed = new ArrayList<Relocation>();
            try {
                for (Relocation change : pending) {
                    Files.createDirectories(change.to().getParent());
                    Files.move(change.from(), change.to());
                    completed.add(change);
                }
                save(properties);
            } catch (IOException failure) {
                for (Relocation change : completed.reversed()) {
                    try {
                        Files.move(change.to(), change.from());
                    } catch (IOException rollback) {
                        failure.addSuppressed(rollback);
                    }
                }
                try {
                    Files.move(destination, source);
                } catch (IOException rollback) {
                    failure.addSuppressed(rollback);
                }
                throw failure;
            }
        }
    }

    private static Path relocate(Path path, Path source, Path destination) {
        return path.startsWith(source) ? destination.resolve(source.relativize(path)) : path;
    }

    private record Relocation(Path from, Path to) {
    }
}
