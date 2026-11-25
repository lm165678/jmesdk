package com.jme3.gde.texturetools;


import com.jme3.material.skin.SkinLutGenerator;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Exports skin assets beneath an explicitly selected asset root. No texture
 * lossy compression, native codec, process launch or model rewrite is involved.
 * Prepare all files before publication; replacements keep rollback copies.
 * Atomic rename is used per file when available, with ordinary rename fallback.
 * A failed multi-file publication restores originals; failed restoration retains
 * its backup and reports the path. This is not a filesystem-wide transaction.
 */
final class SkinLutExport {
    private SkinLutExport() {
    }

    static List<Path> targets(Path root, String key) {
        if (key == null || key.isBlank() || key.contains("\"") || key.contains(":")) {
            throw new IllegalArgumentException("Enter an asset-relative output key without an extension");
        }
        String normalized = key.replace('\\', '/');
        for (int i = 0; i < normalized.length(); i++) {
            if (Character.isISOControl(normalized.charAt(i))) {
                throw new IllegalArgumentException("Invalid character in asset key");
            }
        }
        Path base = root.toAbsolutePath().normalize();
        List<Path> paths = new ArrayList<>();
        for (String extension : new String[]{".dds", ".j3m", ".skin.properties"}) {
            Path file = base.resolve(normalized + extension).normalize();
            if (!file.startsWith(base) || file.equals(base)) {
                throw new IllegalArgumentException("Output must stay inside the asset root");
            }
            paths.add(file);
        }
        return paths;
    }

    static List<Path> write(Path root, String key, SkinLutGenerator.Result lut,
            SkinLutGenerator.Profile profile, int samples, boolean replace) throws IOException {
        return write(root, key, lut, profile, samples, "Custom coefficients", replace);
    }

    static synchronized List<Path> write(Path root, String key, SkinLutGenerator.Result lut,
            SkinLutGenerator.Profile profile, int samples, String description, boolean replace) throws IOException {
        Map<Path, byte[]> content = encode(root, key, lut, profile, samples, description);
        Path materialPath = targets(root, key).get(1);
        String material = new String(content.get(materialPath), StandardCharsets.UTF_8);
        material = mergeMaterial(root, key, material, Set.of("SkinLut", "SkinLutSize", "SkinMaxCurvature"));
        content.put(materialPath, material.getBytes(StandardCharsets.UTF_8));
        writeAssets(root, key, content, replace);
        return new ArrayList<>(content.keySet());
    }

    // Merge only managed slots in our own exported material; keep albedo,
    // roughness, scattering tuning, material name and render state intact.
    static String mergeMaterial(Path root, String key, String generated, Set<String> managed) throws IOException {
        List<Path> paths = targets(root, key);
        if (!Files.isRegularFile(paths.get(1))) {
            return generated;
        }
        if (!Files.isRegularFile(paths.get(2))
                || !"jme.preintegrated.skin".equals(properties(paths.get(2)).getProperty("generator"))) {
            throw new IOException("Existing material is not owned by this skin tool: " + paths.get(1));
        }
        return mergeParameters(Files.readString(paths.get(1)), generated, managed);
    }

    static String mergeParameters(String previous, String generated, Set<String> managed) throws IOException {
        var block = java.util.regex.Pattern.compile("(?s)MaterialParameters\\s*\\{([^{}]*)\\}");
        var oldBlock = block.matcher(previous);
        var newBlock = block.matcher(generated);
        if (!previous.contains(SkinLutGenerator.MATERIAL) || !oldBlock.find() || !newBlock.find()) {
            throw new IOException("Unsupported skin material structure; previous file preserved");
        }
        var property = java.util.regex.Pattern.compile("^\\h*([A-Za-z0-9_]+)\\h*:");
        StringBuilder body = new StringBuilder("\n");
        for (String line : oldBlock.group(1).split("\\R")) {
            var parameter = property.matcher(line);
            if (!line.isBlank() && !(parameter.find() && managed.contains(parameter.group(1)))) {
                body.append(line).append('\n');
            }
        }
        for (String line : newBlock.group(1).split("\\R")) {
            var parameter = property.matcher(line);
            if (parameter.find() && managed.contains(parameter.group(1))) {
                body.append(line).append('\n');
            }
        }
        body.append("    ");
        return previous.substring(0, oldBlock.start(1)) + body + previous.substring(oldBlock.end(1));
    }

    static synchronized void writeAssets(Path root, String key, Map<Path, byte[]> content, boolean replace) throws IOException {
        writeAssets(root, key, content, replace, null);
    }

    static synchronized void writeAssets(Path root, String key, Map<Path, byte[]> content, boolean replace,
            Path selectedMaterial) throws IOException {
        List<Path> paths = targets(root, key);
        Path assetRoot = root.toAbsolutePath().normalize();
        Set<String> owned = new LinkedHashSet<>();
        Path legacy = paths.get(0).resolveSibling(paths.get(0).getFileName().toString().replaceFirst("\\.dds$", ".j3o"));
        if (Files.exists(legacy) && !replace) {
            throw new FileAlreadyExistsException("Legacy Image J3O requires confirmed migration: " + legacy);
        }
        if (replace && (paths.stream().anyMatch(Files::exists) || Files.exists(legacy))) {
            Properties previous = properties(paths.get(2));
            if (!"jme.preintegrated.skin".equals(previous.getProperty("generator"))) {
                throw new IOException("Existing assets were not exported by this tool; choose another output key");
            }
            int count;
            try {
                count = Integer.parseInt(previous.getProperty("assets.count", "0"));
            } catch (NumberFormatException ex) {
                throw new IOException("Invalid exported asset manifest", ex);
            }
            if (count < 0 || count > 64) {
                throw new IOException("Invalid exported asset manifest size");
            }
            for (int i = 0; i < count; i++) {
                owned.add(previous.getProperty("assets." + i, ""));
            }
            // Only migrate a legacy Image created by this tool, never a model.
            if (Files.exists(legacy)) {
                try (var input = Files.newInputStream(legacy)) {
                    if (!(new com.jme3.export.binary.BinaryImporter().load(input) instanceof com.jme3.texture.Image)) {
                        throw new IOException("Legacy path is not a generated LUT Image: " + legacy);
                    }
                }
                content.put(legacy, null);
            }
            // Older LUT-only exports owned the original three files.
            for (Path path : List.of(paths.get(1), paths.get(2), legacy)) {
                owned.add(assetRoot.relativize(path).toString().replace('\\', '/'));
            }
            for (Path target : content.keySet()) {
                String name = assetRoot.relativize(target).toString().replace('\\', '/');
                if (Files.exists(target) && !owned.contains(name) && !target.equals(selectedMaterial)) {
                    throw new IOException("Refusing to replace an unrelated asset: " + target);
                }
            }
        }
        for (Path path : content.keySet()) {
            if (!path.startsWith(assetRoot)) {
                throw new IOException("Asset output escaped the selected root");
            }
            owned.add(assetRoot.relativize(path).toString().replace('\\', '/'));
        }
        StringBuilder manifest = new StringBuilder(new String(content.get(paths.get(2)), StandardCharsets.UTF_8));
        manifest.append("assets.count=").append(owned.size()).append('\n');
        int index = 0;
        for (String name : owned) {
            manifest.append("assets.").append(index++).append('=').append(name).append('\n');
        }
        content.put(paths.get(2), manifest.toString().getBytes(StandardCharsets.UTF_8));
        publish(content, replace);
        content.entrySet().removeIf(entry -> entry.getValue() == null);
    }

    static Map<Path, byte[]> encode(Path root, String key, SkinLutGenerator.Result lut,
            SkinLutGenerator.Profile profile, int samples) throws IOException {
        return encode(root, key, lut, profile, samples, "Custom coefficients");
    }

    static Map<Path, byte[]> encode(Path root, String key, SkinLutGenerator.Result lut,
            SkinLutGenerator.Profile profile, int samples, String description) throws IOException {
        List<Path> paths = targets(root, key);
        byte[] image = TextureToolchain.linearHalfDds(lut.createTexture().getImage());
        String textureKey = root.toAbsolutePath().normalize().relativize(paths.get(0)).toString().replace('\\', '/');
        String material = "Material Preintegrated Skin : " + SkinLutGenerator.MATERIAL + " {\n"
                + "    MaterialParameters {\n"
                + "        SkinLut : MinBilinearNoMipMaps MagBilinear WrapEdgeClamp \"" + textureKey + "\"\n"
                + "        SkinLutSize : " + lut.getWidth() + " " + lut.getHeight() + "\n"
                + "        SkinMaxCurvature : " + lut.getMaximumCurvature() + "\n"
                + "        BaseColor : 1.0 1.0 1.0 1.0\n"
                + "    }\n}\n";
        Map<Path, byte[]> content = new LinkedHashMap<>();
        content.put(paths.get(0), image);
        content.put(paths.get(1), material.getBytes(StandardCharsets.UTF_8));
        content.put(paths.get(2), (settings(lut, profile, samples)
                + "profile.label=" + description.replace('\n', ' ').replace('\r', ' ') + "\n").getBytes(StandardCharsets.UTF_8));
        return content;
    }

    private static String settings(SkinLutGenerator.Result lut, SkinLutGenerator.Profile profile, int samples) {
        StringBuilder text = new StringBuilder("# Linear RGBA16F DDS; X=NdotL*.5+.5; Y=sqrt(curvature/maxCurvature)\n");
        text.append("generator=jme.preintegrated.skin\nversion=1\nwidth=").append(lut.getWidth()).append("\nheight=").append(lut.getHeight());
        text.append("\nmaxCurvature=").append(lut.getMaximumCurvature()).append("\nsamples=").append(samples);
        text.append("\nlobes=").append(profile.size()).append('\n');
        for (int c = 0; c < 3; c++) {
            text.append("distance.").append(c).append('=').append(profile.distance(c)).append('\n');
        }
        for (int i = 0; i < profile.size(); i++) {
            text.append("variance.").append(i).append('=').append(profile.variance(i)).append('\n');
            for (int c = 0; c < 3; c++) {
                text.append("weight.").append(i).append('.').append(c).append('=').append(profile.weight(i, c)).append('\n');
            }
        }
        return text.toString();
    }

    static final class Settings {
        final SkinLutGenerator.Profile profile;
        final int width;
        final int height;
        final int samples;
        final float curvature;

        Settings(Properties values) {
            if (!"jme.preintegrated.skin".equals(values.getProperty("generator"))
                    || !"1".equals(values.getProperty("version"))) {
                throw new IllegalArgumentException("Unsupported skin profile format");
            }
            width = Integer.parseInt(values.getProperty("width"));
            height = Integer.parseInt(values.getProperty("height"));
            samples = Integer.parseInt(values.getProperty("samples"));
            curvature = Float.parseFloat(values.getProperty("maxCurvature"));
            int count = Integer.parseInt(values.getProperty("lobes"));
            if (width < 2 || width > 2048 || height < 2 || height > 2048 || samples < 16 || samples > 512
                    || !Float.isFinite(curvature) || curvature <= 0f || count < 1 || count > 64) {
                throw new IllegalArgumentException("Invalid skin profile dimensions or range");
            }
            float[] variances = new float[count];
            float[][] weights = new float[count][3];
            float[] distances = new float[3];
            for (int c = 0; c < 3; c++) {
                distances[c] = Float.parseFloat(values.getProperty("distance." + c));
            }
            for (int i = 0; i < count; i++) {
                variances[i] = Float.parseFloat(values.getProperty("variance." + i));
                for (int c = 0; c < 3; c++) {
                    weights[i][c] = Float.parseFloat(values.getProperty("weight." + i + "." + c));
                }
            }
            profile = new SkinLutGenerator.Profile(variances, weights, distances);
        }
    }

    static Settings read(Path file) throws IOException {
        try {
            return new Settings(properties(file));
        } catch (RuntimeException ex) {
            throw new IOException("Invalid skin profile: " + file, ex);
        }
    }

    private static Properties properties(Path file) throws IOException {
        Properties properties = new Properties();
        try (java.io.Reader input = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(input);
        }
        return properties;
    }

    private static void publish(Map<Path, byte[]> content, boolean replace) throws IOException {
        Map<Path, Path> staged = new LinkedHashMap<>();
        Map<Path, Path> backups = new LinkedHashMap<>();
        List<Path> installed = new ArrayList<>();
        boolean restored = true;
        IOException publicationFailure = null;
        try {
            for (Map.Entry<Path, byte[]> entry : content.entrySet()) {
                Path target = entry.getKey();
                if (Files.exists(target) && !replace) {
                    throw new FileAlreadyExistsException(target.toString());
                }
                Files.createDirectories(target.getParent());
                Path temp = null;
                if (entry.getValue() != null) {
                    temp = Files.createTempFile(target.getParent(), ".skin-stage-", ".tmp");
                    Files.write(temp, entry.getValue());
                }
                staged.put(target, temp);
                if (Files.exists(target)) {
                    Path backup = Files.createTempFile(target.getParent(), ".skin-backup-", ".tmp");
                    backups.put(target, backup);
                    Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            List<Map.Entry<Path, Path>> ordered = new ArrayList<>(staged.entrySet());
            // Publish the material after its textures exist, so file watchers
            // do not encounter new material references before their payloads.
            ordered.sort(java.util.Comparator.comparing(entry -> entry.getKey().toString().endsWith(".j3m")));
            for (Map.Entry<Path, Path> entry : ordered) {
                if (entry.getValue() == null) {
                    Files.deleteIfExists(entry.getKey());
                } else {
                    move(entry.getValue(), entry.getKey(), replace);
                }
                installed.add(entry.getKey());
            }
        } catch (IOException failure) {
            publicationFailure = failure;
            for (int i = installed.size() - 1; i >= 0; i--) {
                Path target = installed.get(i);
                try {
                    if (backups.containsKey(target)) {
                        move(backups.get(target), target, true);
                    } else {
                        Files.deleteIfExists(target);
                    }
                } catch (IOException recovery) {
                    restored = false;
                    Path backup = backups.get(target);
                    String message = backup == null ? "Could not remove newly published file: " + target
                            : "Recovery backup retained for " + target + ": " + backup;
                    failure.addSuppressed(new IOException(message, recovery));
                }
            }
            throw failure;
        } finally {
            IOException cleanupFailure = null;
            for (Path temp : staged.values()) {
                try {
                    if (temp != null) {
                        Files.deleteIfExists(temp);
                    }
                } catch (IOException failure) {
                    if (cleanupFailure == null) {
                        cleanupFailure = failure;
                    } else {
                        cleanupFailure.addSuppressed(failure);
                    }
                }
            }
            if (restored) {
                for (Path backup : backups.values()) {
                    try {
                        Files.deleteIfExists(backup);
                    } catch (IOException failure) {
                        if (cleanupFailure == null) {
                            cleanupFailure = failure;
                        } else {
                            cleanupFailure.addSuppressed(failure);
                        }
                    }
                }
            }
            if (cleanupFailure != null) {
                if (publicationFailure != null) {
                    publicationFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
        }
    }

    private static void move(Path source, Path target, boolean replace) throws IOException {
        // Non-replacement exports retain the filesystem's exclusive create check.
        if (!replace) {
            Files.move(source, target);
            return;
        }
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
