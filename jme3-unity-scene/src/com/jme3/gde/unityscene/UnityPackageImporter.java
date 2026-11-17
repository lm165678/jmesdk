package com.jme3.gde.unityscene;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Restores Unity GUID archive entries without overwriting existing project assets.
 * Call on a worker thread. Standard tar regular files/directories are supported;
 * links and extended headers are rejected explicitly. Limits: 100000 entries, 4 GiB.
 * <pre>
 * 第一步：把 .unitypackage 放到项目资产目录。
 * 第二步：右键 Import Unity Package；资源恢复到新建的独立目录，保留 .meta。
 * 第三步：选择转换，或对恢复的 .unity/.prefab 执行 Convert Model。
 * 重复导入创建新目录，不覆盖以前的资源；失败时清理本次创建的目录。
 * </pre>
 */
final class UnityPackageImporter {
    static Path unpack(Path archive) throws IOException {
        Path staging = Files.createTempDirectory("jme-unity-package-");
        Path destination = null;
        try {
            extract(archive, staging);
            destination = Files.createTempDirectory(archive.toAbsolutePath().getParent(), "UnityImport-");
            Set<String> paths = new HashSet<>();
            int restored = 0;
            try (var groups = Files.list(staging)) {
                for (Path group : (Iterable<Path>) groups::iterator) {
                    Path pathname = group.resolve("pathname");
                    if (!Files.isRegularFile(pathname) || Files.size(pathname) > 32768) {
                        throw new IOException("Missing or oversized Unity pathname: " + group.getFileName());
                    }
                    String name = Files.readString(pathname, StandardCharsets.UTF_8).strip();
                    if (!name.startsWith("Assets/") || name.contains("\\") || name.contains(":")) {
                        throw new IOException("Invalid Unity asset path: " + name);
                    }
                    for (String part : name.split("/", -1)) {
                        if (part.isEmpty() || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ")) {
                            throw new IOException("Unsafe Unity asset path: " + name);
                        }
                    }
                    Path target = destination.resolve(name).normalize();
                    if (!target.startsWith(destination) || !paths.add(name.toLowerCase(java.util.Locale.ROOT))) {
                        throw new IOException("Duplicate or unsafe asset: " + name);
                    }
                    Files.createDirectories(target.getParent());
                    Path asset = group.resolve("asset");
                    Path meta = group.resolve("asset.meta");
                    if (!Files.isRegularFile(meta)) {
                        throw new IOException("Missing .meta for " + name);
                    }
                    if (Files.isRegularFile(asset)) {
                        Files.copy(asset, target);
                    } else {
                        if (Files.size(meta) > 1048576 || !Files.readString(meta).matches("(?s).*\\bfolderAsset:\\s*yes\\b.*")) {
                            throw new IOException("Missing asset data for " + name);
                        }
                        Files.createDirectories(target);
                    }
                    Files.copy(meta, target.resolveSibling(target.getFileName() + ".meta"));
                    restored++;
                }
            }
            if (restored == 0) {
                throw new IOException("The Unity package contains no assets.");
            }
            return destination;
        } catch (IOException | RuntimeException ex) {
            if (destination != null) {
                cleanup(destination, ex);
            }
            throw ex;
        } finally {
            cleanup(staging, null);
        }
    }

    private static void extract(Path archive, Path staging) throws IOException {
        long total = 0;
        int entries = 0;
        try (InputStream input = new GZIPInputStream(Files.newInputStream(archive))) {
            while (true) {
                byte[] header = input.readNBytes(512);
                if (header.length != 512) {
                    throw new IOException("Truncated Unity tar header");
                }
                boolean zero = true;
                for (byte value : header) {
                    zero &= value == 0;
                }
                if (zero) {
                    break;
                }
                long sum = 0;
                for (int i = 0; i < 512; i++) {
                    sum += i >= 148 && i < 156 ? 32 : header[i] & 255;
                }
                if (sum != octal(header, 148, 8)) {
                    throw new IOException("Invalid tar checksum");
                }
                String name = string(header, 0, 100);
                String prefix = string(header, 345, 155);
                if (!prefix.isEmpty()) {
                    name = prefix + "/" + name;
                }
                if (name.startsWith("./")) {
                    name = name.substring(2);
                }
                long size = octal(header, 124, 12);
                total += size;
                if (++entries > 100000 || size < 0 || total > 4L * 1024 * 1024 * 1024) {
                    throw new IOException("Unity package exceeds import limits");
                }
                int type = header[156];
                if (type == '5' && (name.isEmpty() || name.equals(".")) && size == 0) {
                    continue;
                }
                if (type == '5' && name.matches("[0-9a-fA-F]{32}/?") && size == 0) {
                    continue;
                }
                if ((type != 0 && type != '0') || !name.matches("[0-9a-fA-F]{32}/(asset|asset.meta|pathname|preview.png)")) {
                    throw new IOException("Unsupported Unity tar entry: " + name + " (type " + type + ")");
                }
                Path target = staging.resolve(name);
                Files.createDirectories(target.getParent());
                try (OutputStream output = Files.newOutputStream(target, java.nio.file.StandardOpenOption.CREATE_NEW)) {
                    byte[] buffer = new byte[65536];
                    long remaining = size;
                    while (remaining > 0) {
                        int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                        if (count < 0) {
                            throw new IOException("Truncated Unity asset: " + name);
                        }
                        output.write(buffer, 0, count);
                        remaining -= count;
                    }
                }
                int padding = (int) ((512 - size % 512) % 512);
                if (input.readNBytes(padding).length != padding) {
                    throw new IOException("Truncated tar padding");
                }
            }
        }
    }

    private static String string(byte[] data, int start, int length) {
        int end = start;
        while (end < start + length && data[end] != 0) {
            end++;
        }
        return new String(data, start, end - start, StandardCharsets.UTF_8);
    }

    private static long octal(byte[] data, int start, int length) throws IOException {
        try {
            String value = string(data, start, length).trim();
            return value.isEmpty() ? 0 : Long.parseLong(value, 8);
        } catch (NumberFormatException ex) {
            throw new IOException("Unsupported tar numeric field", ex);
        }
    }

    private static void cleanup(Path root, Throwable original) throws IOException {
        try (var files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(file);
            }
        } catch (IOException ex) {
            if (original == null) {
                throw ex;
            }
            original.addSuppressed(ex);
        }
    }
}
