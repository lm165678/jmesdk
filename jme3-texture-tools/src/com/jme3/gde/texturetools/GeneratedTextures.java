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
 * // 第五步：删除源图会清理所属 DDS；不能单独删除或复制登记的 DDS。
 * // 第六步：外部删除 DDS 后重新转换可恢复；不要手工编辑登记表。
 * </pre>
 */
public final class GeneratedTextures {
    public static final String MANIFEST = ".jme-textures.properties";
    private static final String PROFILE = "compressonator-4.6.0:BC3:mips-full:cpu:v1";
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
        Path result = root.resolve(relative).normalize();
        if (!result.startsWith(root) || result.equals(root)) {
            throw new IOException("Texture path escapes asset root: " + relative);
        }
        // Do not follow symlinks/junctions outside the registered asset root.
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

    /** Content revision used for cache invalidation, even when size/time are unchanged. */
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

    private String find(Properties properties, String key) {
        for (String id : ids(properties)) {
            if (key.equals(properties.getProperty(id + ".key")) || key.equals(properties.getProperty(id + ".source"))) {
                return id;
            }
        }
        return null;
    }

    /** Generates missing/stale DDS from source. Returns null for non-PNG/JPEG inputs. */
    public Path ensure(String key, TextureToolchain tools) throws IOException, InterruptedException {
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
        // Encoding never holds the registry lock: node enablement and filesystem
        // operations must remain responsive while the subprocess is running.
        Path temporary = Files.createTempFile(root, ".texture-generation-", ".dds");
        try {
            var image = ImageIO.read(source.toFile());
            if (image == null) {
                throw new IOException("Cannot decode texture source: " + source);
            }
            int levels = 32 - Integer.numberOfLeadingZeros(Math.max(image.getWidth(), image.getHeight()));
            tools.dds(source, temporary, "BC3", levels);
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
                properties.setProperty(id + ".profile", PROFILE);
                save(properties);
                return output;
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Returns a fresh physical DDS, or null so the caller can load the source. */
    public Path resolve(String key) throws IOException {
        synchronized (LOCK) {
            Properties properties = read();
            String id = find(properties, key);
            return id == null ? null : resolve(properties, id);
        }
    }

    /** Resolves the source of a logical alias, including after source-folder moves. */
    public Path source(String key) throws IOException {
        synchronized (LOCK) {
            Properties properties = read();
            String id = find(properties, key);
            return id == null ? null : checked(properties.getProperty(id + ".source"));
        }
    }

    private Path resolve(Properties properties, String id) throws IOException {
        Path source = checked(properties.getProperty(id + ".source"));
        Path output = checked(properties.getProperty(id + ".output"));
        if (Files.isRegularFile(source) && Files.isRegularFile(output)
                && PROFILE.equals(properties.getProperty(id + ".profile"))
                && hash(source).equals(properties.getProperty(id + ".sourceHash"))
                && hash(output).equals(properties.getProperty(id + ".outputHash"))) {
            return output;
        }
        return null;
    }

    /** Whether a file/subtree contains registered outputs, independent of directory names. */
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

    /** True when moving the whole registry root; all relative paths remain valid. */
    public boolean isRoot(Path path) {
        return root.equals(path.toAbsolutePath().normalize());
    }

    /** Rejects copying generated resources, including a folder containing them. */
    public void checkCopy(Path path) throws IOException {
        if (containsGenerated(path)) {
            throw new IOException("Generated DDS resources cannot be copied. Copy source images and convert again.");
        }
    }

    /** Deletes sources with their generated outputs; refuses orphan-output deletion. */
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
                if (output.startsWith(target) && !source.startsWith(target)) {
                    throw new IOException("Delete the source texture instead of its generated DDS: " + source);
                }
                if (source.startsWith(target)) {
                    removed.add(id);
                    if (!output.startsWith(target)) {
                        outputs.add(output);
                    }
                }
            }
            // Paths are normalized and verified inside root before any recursive deletion.
            if (Files.isDirectory(target)) {
                try (var files = Files.walk(target)) {
                    for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                        Files.delete(file);
                    }
                }
            } else {
                Files.delete(target);
            }
            for (Path output : outputs) {
                Files.deleteIfExists(output);
            }
            for (String id : removed) {
                properties.keySet().removeIf(key -> key.toString().startsWith(id + "."));
            }
            if (Files.isDirectory(root)) {
                save(properties);
            }
        }
    }

    /** Moves a managed source/output subtree and updates physical paths, retaining logical aliases. */
    public void move(Path from, Path to) throws IOException {
        synchronized (LOCK) {
            Path source = checked(relative(from));
            Path destination = checked(relative(to));
            if (source.equals(root.resolve(MANIFEST)) || destination.startsWith(source)) {
                throw new IOException("Invalid managed texture move");
            }
            Properties properties = read();
            for (String id : ids(properties)) {
                for (String field : List.of("source", "output")) {
                    Path value = checked(properties.getProperty(id + "." + field));
                    if (value.startsWith(source)) {
                        properties.setProperty(id + "." + field, relative(destination.resolve(source.relativize(value))));
                    }
                }
            }
            Files.move(source, destination);
            try {
                save(properties);
            } catch (IOException failure) {
                try {
                    Files.move(destination, source);
                } catch (IOException rollback) {
                    failure.addSuppressed(rollback);
                }
                throw failure;
            }
        }
    }
}
