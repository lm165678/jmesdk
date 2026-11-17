package com.jme3.gde.unityscene;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

/** Standalone archive restoration and failure-cleanup checks; no engine or GUI required. */
public final class UnityPackageImporterSmoke {
    public static void main(String[] args) throws Exception {
        Path sandbox = Files.createTempDirectory("unity-import-smoke-");
        Path archive = sandbox.resolve("test.unitypackage");
        write(archive, "Assets/Scenes/Demo.unity");
        Path first = UnityPackageImporter.unpack(archive);
        Path second = UnityPackageImporter.unpack(archive);
        if (first.equals(second) || !Files.readString(first.resolve("Assets/Scenes/Demo.unity")).equals("scene")
                || !Files.readString(first.resolve("Assets/Scenes/Demo.unity.meta")).contains("guid:")) {
            throw new AssertionError("Asset restoration or repeat import failed");
        }
        long before;
        try (var files = Files.list(sandbox)) {
            before = files.count();
        }
        write(archive, "Assets/../../escape.unity");
        try {
            UnityPackageImporter.unpack(archive);
            throw new AssertionError("Traversal accepted");
        } catch (IOException expected) {
            // Invalid package must leave no partial output directory.
        }
        try (var files = Files.list(sandbox)) {
            if (files.count() != before) {
                throw new AssertionError("Failed import leaked output");
            }
        }
        Files.write(archive, new byte[]{1, 2, 3});
        try {
            UnityPackageImporter.unpack(archive);
            throw new AssertionError("Invalid gzip accepted");
        } catch (IOException expected) {
            // Expected.
        }
        System.out.println("UNITY_PACKAGE_IMPORT_PASS " + sandbox);
    }

    private static void write(Path archive, String pathname) throws IOException {
        try (var output = new GZIPOutputStream(Files.newOutputStream(archive))) {
            String guid = "0123456789abcdef0123456789abcdef/";
            entry(output, guid + "pathname", pathname);
            entry(output, guid + "asset", "scene");
            entry(output, guid + "asset.meta", "guid: 0123456789abcdef0123456789abcdef\n");
            output.write(new byte[1024]);
        }
    }

    private static void entry(OutputStream output, String name, String content) throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        byte[] header = new byte[512];
        put(header, 0, name);
        put(header, 124, String.format("%011o", bytes.length));
        header[156] = '0';
        for (int i = 148; i < 156; i++) {
            header[i] = 32;
        }
        int sum = 0;
        for (byte value : header) {
            sum += value & 255;
        }
        put(header, 148, String.format("%06o\0 ", sum));
        output.write(header);
        output.write(bytes);
        output.write(new byte[(512 - bytes.length % 512) % 512]);
    }

    private static void put(byte[] header, int offset, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, header, offset, bytes.length);
    }
}
