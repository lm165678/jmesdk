package com.jme3.gde.assetbrowser;

import com.jme3.asset.*;
import com.jme3.export.*;
import com.jme3.gde.core.assets.ProjectAssetManager;
import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.openide.filesystems.FileObject;

/**
 * Per-preview dependency closures, including keys embedded in J3O and shader
 * imports. Discovery only loads the requested asset using a private manager.
 * Graph access is synchronized; file inspection belongs to a worker.
 */
final class AssetDependencies {
    record Scan(Set<String> paths, String warning) {
    }
    /** Captures dependencies requested by real rendering, including cached requests. */
    static final class Tracking implements AutoCloseable {
        private final ProjectAssetManager manager;
        private final Map<String, String> stamps = new ConcurrentHashMap<>();
        private final AssetEventListener listener = new AssetEventListener() {
            public void assetRequested(AssetKey key) {
                remember(key);
            }
            public void assetLoaded(AssetKey key) {
                remember(key);
            }
            public void assetDependencyNotFound(AssetKey parent, AssetKey dependent) {
                remember(dependent);
            }
        };

        Tracking(ProjectAssetManager manager) {
            this.manager = manager;
            manager.addAssetEventListener(listener);
        }

        private void remember(AssetKey key) {
            if (key != null && key.getName() != null) {
                stamps.computeIfAbsent(key.getName(), name -> stamp(manager.getAssetFolder().getFileObject(name)));
            }
        }

        Set<String> paths() {
            return Set.copyOf(stamps.keySet());
        }

        boolean unchanged() {
            return stamps.entrySet().stream().allMatch(entry ->
                    entry.getValue().equals(stamp(manager.getAssetFolder().getFileObject(entry.getKey()))));
        }

        @Override
        public void close() {
            manager.removeAssetEventListener(listener);
        }
    }

    private final Map<String, Set<String>> edges = new HashMap<>();
    private final Map<String, Long> versions = new HashMap<>();
    private long sequence;

    synchronized void record(String asset, Collection<String> paths) {
        Set<String> copy = new TreeSet<>(paths);
        copy.remove(asset);
        copy.removeIf(String::isBlank);
        edges.put(asset, Set.copyOf(copy));
    }

    synchronized long version(String asset) {
        return versions.getOrDefault(asset, 0L);
    }

    synchronized Set<String> paths(String asset) {
        return edges.getOrDefault(asset, Set.of());
    }

    /** Includes transitive dependants and directory descendants, without a global revision. */
    synchronized Set<String> invalidate(Map<String, Boolean> changes) {
        Set<String> affected = new HashSet<>(changes.keySet());
        boolean added;
        do {
            added = false;
            for (var entry : edges.entrySet()) {
                boolean matches = affected.contains(entry.getKey())
                        || entry.getValue().stream().anyMatch(affected::contains);
                if (!matches) {
                    for (var change : changes.entrySet()) {
                        if (change.getValue() && (entry.getKey().startsWith(change.getKey() + "/")
                                || entry.getValue().stream().anyMatch(path -> path.startsWith(change.getKey() + "/")))) {
                            matches = true;
                            break;
                        }
                    }
                }
                if (matches) {
                    added |= affected.add(entry.getKey());
                }
            }
        } while (added);
        long version = ++sequence;
        for (String asset : affected) {
            versions.put(asset, version);
        }
        return affected;
    }

    synchronized void invalidateAll() {
        long version = ++sequence;
        for (String asset : edges.keySet()) {
            versions.put(asset, version);
        }
    }

    static String stamp(FileObject file) {
        return file == null || !file.isValid() ? "missing"
                : file.getSize() + ":" + file.lastModified().getTime();
    }

    static String fingerprint(FileObject root, String asset, Collection<String> dependencies) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        Set<String> paths = new TreeSet<>(dependencies);
        paths.add(asset);
        for (String path : paths) {
            digest.update((path + "\n" + stamp(root.getFileObject(path)) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static ProjectAssetManager isolated(ProjectAssetManager original) {
        ProjectAssetManager result = new ProjectAssetManager(original.getAssetFolder());
        for (ClassLoader loader : original.getClassLoaders()) {
            result.addClassLoader(loader);
        }
        return result;
    }

    static Scan inspect(ProjectAssetManager manager, FileObject file, String asset) {
        Set<String> paths = ConcurrentHashMap.newKeySet();
        AssetEventListener listener = new AssetEventListener() {
            public void assetRequested(AssetKey key) {
                paths.add(key.getName());
            }
            public void assetLoaded(AssetKey key) {
                paths.add(key.getName());
            }
            public void assetDependencyNotFound(AssetKey parent, AssetKey dependent) {
                paths.add(dependent.getName());
            }
        };
        manager.addAssetEventListener(listener);
        String warning = "";
        try {
            Object loaded = switch (AssetKind.of(file)) {
                case MODEL -> manager.loadModel(asset);
                case MATERIAL -> file.getExt().equalsIgnoreCase("j3md")
                        ? new com.jme3.material.Material(manager, asset) : manager.loadMaterial(asset);
                case FILTER, LIGHTMAP -> {
                    var importer = new com.jme3.export.binary.BinaryImporter();
                    importer.setAssetManager(manager);
                    try (InputStream input = file.getInputStream()) {
                        yield importer.load(input);
                    }
                }
                default -> null;
            };
            if (loaded instanceof Savable savable) {
                new References(manager.getAssetFolder(), paths).visit(savable);
            }
        } catch (Exception | LinkageError error) {
            warning = error.toString();
        } finally {
            manager.removeAssetEventListener(listener);
        }
        Set<String> inspected = new HashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>(paths);
        pending.add(asset);
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (!inspected.add(current)) {
                continue;
            }
            FileObject local = manager.getAssetFolder().getFileObject(current);
            if (local == null || !Set.of("j3m", "j3md", "vert", "frag", "geom", "comp", "glsllib",
                    "glsl", "gltf", "obj", "mtl", "xml", "gui").contains(local.getExt().toLowerCase(Locale.ROOT))) {
                continue;
            }
            try (InputStream input = local.getInputStream()) {
                String text = new String(input.readNBytes(4 * 1024 * 1024), StandardCharsets.UTF_8);
                var matcher = Pattern.compile("[\\w./%\\-]+\\.(?:j3md|j3m|glsllib|glsl|vert|frag|geom|comp|png|jpg|jpeg|dds|hdr|ktx|bin|mtl|xml)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS).matcher(text);
                while (matcher.find()) {
                    String reference = matcher.group();
                    String relative = current.contains("/") ? current.substring(0, current.lastIndexOf('/') + 1) + reference : reference;
                    boolean relativeFormat = Set.of("gltf", "obj", "mtl")
                            .contains(local.getExt().toLowerCase(Locale.ROOT));
                    if (manager.getAssetFolder().getFileObject(reference) == null
                            && (manager.getAssetFolder().getFileObject(relative) != null || relativeFormat)) {
                        reference = new AssetKey<>(relative).getName();
                    }
                    if (paths.add(reference)) {
                        pending.add(reference);
                    }
                }
            } catch (IOException error) {
                warning = error.toString();
            }
        }
        var diskRoot = org.openide.filesystems.FileUtil.toFile(manager.getAssetFolder());
        if (diskRoot != null && java.nio.file.Files.isRegularFile(diskRoot.toPath().resolve(
                com.jme3.gde.texturetools.GeneratedTextures.MANIFEST))) {
            var registry = new com.jme3.gde.texturetools.GeneratedTextures(diskRoot.toPath());
            for (String path : List.copyOf(paths)) {
                if (!path.matches("(?i).+\\.(png|jpg|jpeg|dds|astc)")) {
                    continue;
                }
                try {
                    var output = registry.resolve(path);
                    if (output != null) {
                        paths.add(diskRoot.toPath().relativize(output).toString().replace('\\', '/'));
                        paths.add(com.jme3.gde.texturetools.GeneratedTextures.MANIFEST);
                    }
                } catch (IOException error) {
                    warning = error.toString();
                }
            }
        }
        paths.remove(asset);
        return new Scan(Set.copyOf(paths), warning);
    }

    /** Uses Savable's own fields, so embedded material keys and external animation/lightmap paths participate. */
    private static final class References implements JmeExporter {
        private final Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        private final FileObject root;
        private final Set<String> paths;
        private final OutputCapsule capsule;

        References(FileObject root, Set<String> paths) {
            this.root = root;
            this.paths = paths;
            capsule = (OutputCapsule) Proxy.newProxyInstance(OutputCapsule.class.getClassLoader(),
                    new Class<?>[]{OutputCapsule.class}, (proxy, method, args) -> {
                        if (args != null && args.length > 0) {
                            visit(args[0]);
                        }
                        return null;
                    });
        }

        void visit(Object value) throws IOException {
            if (value == null || !visited.add(value)) {
                return;
            }
            if (value instanceof String path) {
                if (root.getFileObject(path) != null || path.matches("(?i).+\\.(j3o|j3m|j3md|giprobe|png|jpg|dds|hdr|glsl|glsllib)")) {
                    paths.add(path);
                }
            } else if (value instanceof AssetKey<?> key) {
                paths.add(key.getName());
            } else if (value instanceof Savable savable) {
                savable.write(this);
            } else if (value instanceof Object[] array) {
                for (Object item : array) {
                    visit(item);
                }
            } else if (value instanceof Iterable<?> list) {
                for (Object item : list) {
                    visit(item);
                }
            } else if (value instanceof Map<?, ?> map) {
                for (var entry : map.entrySet()) {
                    visit(entry.getKey());
                    visit(entry.getValue());
                }
            }
        }

        public OutputCapsule getCapsule(Savable object) {
            return capsule;
        }
        public void save(Savable object, OutputStream output) throws IOException {
            visit(object);
        }
        public void save(Savable object, File file) throws IOException {
            visit(object);
        }
        public void save(Savable object, File file, boolean directories) throws IOException {
            visit(object);
        }
    }
}
