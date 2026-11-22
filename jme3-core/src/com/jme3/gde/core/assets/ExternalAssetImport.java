package com.jme3.gde.core.assets;

import com.jme3.asset.TextureKey;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.material.MatParamTexture;
import com.jme3.scene.Geometry;
import com.jme3.scene.Spatial;
import java.awt.datatransfer.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.openide.DialogDisplayer;
import org.openide.NotifyDescriptor;
import org.openide.filesystems.*;
import org.openide.util.RequestProcessor;
import org.openide.util.datatransfer.PasteType;

/**
 * Shared external drop/paste importer. A single worker serializes dialogs and I/O.
 * Sources are staged outside Assets; conversion failure/cancel leaves originals intact.
 * Cancel discards the staged batch before any project outputs are published.
 * Existing output files are never overwritten. Models precede unrelated images so
 * model dependencies do not also receive standalone texture dialogs.
 * <pre>
 * 第一步：从资源管理器复制或拖入文件/文件夹到 Project Assets 或 Asset Browser。
 * 单独拖入分离式 glTF 时，读取同目录下由 glTF 声明的 BIN 和纹理，保留其相对目录。
 * 第二步：逐个确认模型或纹理选项；Copy original resources 默认关闭。
 * 第三步：确认后提交 J3O/DDS；取消丢弃整批临时转换，不改动项目。
 * 第四步：需要源文件时勾选保留；重新导入遇到同名输出会报错，不覆盖已有编辑。
 * </pre>
 */
public final class ExternalAssetImport {
    private static final RequestProcessor WORK = new RequestProcessor("External asset import", 1, true);
    private static final Set<Path> WRITING = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Map<String, Watch> WATCHES = new HashMap<>();

    private static boolean trackingProjects;

    private static void trackProjects() {
        if (trackingProjects) {
            return;
        }
        trackingProjects = true;
        org.netbeans.api.project.ui.OpenProjects.getDefault().addPropertyChangeListener(event -> {
            if (org.netbeans.api.project.ui.OpenProjects.PROPERTY_OPEN_PROJECTS.equals(event.getPropertyName())) {
                Set<org.netbeans.api.project.Project> open = new HashSet<>(Arrays.asList(
                        org.netbeans.api.project.ui.OpenProjects.getDefault().getOpenProjects()));
                synchronized (ExternalAssetImport.class) {
                    WATCHES.values().removeIf(watch -> {
                        if (open.contains(watch.manager.getProject())) {
                            return false;
                        }
                        watch.task.cancel();
                        FileUtil.removeRecursiveListener(watch, FileUtil.toFile(watch.manager.getAssetFolder()));
                        return true;
                    });
                }
            }
        });
    }

    private ExternalAssetImport() {
    }

    /** Offers in-place repair for names produced by the previous converter. */
    public static javax.swing.Action audioRepairAction(ProjectAssetManager manager, FileObject file) {
        if (file == null || file.isFolder() || !file.canWrite() || FileUtil.toFile(file) == null
                || !file.getExt().equalsIgnoreCase("ogg") || !media(Path.of(file.getName()))) {
            return null;
        }
        return new javax.swing.AbstractAction("Repair converted OGG...") {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                if (DialogDisplayer.getDefault().notify(new NotifyDescriptor.Confirmation(
                        "Repair Ogg FLAC in place as OGG Vorbis? Existing asset references will be preserved.",
                        file.getNameExt(), NotifyDescriptor.OK_CANCEL_OPTION)) != NotifyDescriptor.OK_OPTION) {
                    return;
                }
                WORK.post(() -> {
                    try {
                        var disk = FileUtil.toFile(file);
                        AudioImportConverter.repair(disk.toPath());
                        FileUtil.refreshFor(disk);
                        manager.clearCache();
                    } catch (Exception failure) {
                        error(failure);
                    }
                });
            }
        };
    }

    /** Existing source files use the same dialog/transaction as new imports, on a worker. */
    public static javax.swing.Action audioAction(ProjectAssetManager manager, FileObject file) {
        if (file == null || file.isFolder() || FileUtil.toFile(file) == null || !file.canWrite()
                || !media(Path.of(file.getNameExt()))) {
            return null;
        }
        return new javax.swing.AbstractAction("Convert audio to OGG...") {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) {
                WORK.post(() -> run(manager, manager.getAssetFolder(),
                        List.of(FileUtil.toFile(file).toPath().toAbsolutePath().normalize()), true));
            }
        };
    }

    /**
     * Converts an existing image using the import options and staged publication.
     * 第一步：右键 PNG/JPEG/BMP/GIF，选择 Convert texture to DDS。
     * 第二步：选择尺寸、mipmap 和是否保留原图；取消不修改文件。
     * 第三步：后台转换显示进度；同名 DDS 已存在时报错，不覆盖。
     */
    public static javax.swing.Action textureAction(ProjectAssetManager manager, FileObject file) {
        if (manager == null || file == null || file.isFolder() || !file.canWrite()
                || FileUtil.toFile(file) == null || !image(Path.of(file.getNameExt()))) {
            return null;
        }
        return new javax.swing.AbstractAction("Convert texture to DDS...") {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                WORK.post(() -> run(manager, manager.getAssetFolder(),
                        List.of(FileUtil.toFile(file).toPath().toAbsolutePath().normalize()), true));
            }
        };
    }

    public static boolean accepts(Transferable transfer) {
        return transfer != null && transfer.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
    }

    /** Snapshot native transfer data before the OS completes the drop. */
    public static boolean submit(ProjectAssetManager manager, FileObject destination, Transferable transfer) throws Exception {
        if (!accepts(transfer) || destination == null || !destination.isFolder() || !destination.canWrite()) {
            return false;
        }
        List<Path> sources = new ArrayList<>();
        for (Object value : (List<?>) transfer.getTransferData(DataFlavor.javaFileListFlavor)) {
            if (value instanceof File file) {
                sources.add(file.toPath().toAbsolutePath().normalize());
            }
        }
        if (sources.isEmpty()) {
            return false;
        }
        WORK.post(() -> run(manager, destination, sources, false));
        return true;
    }

    public static PasteType paste(ProjectAssetManager manager, FileObject destination, Transferable transfer) {
        if (!accepts(transfer) || destination == null || !destination.isFolder()) {
            return null;
        }
        return new PasteType() {
            @Override public String getName() {
                return "Import resources...";
            }
            @Override public Transferable paste() throws IOException {
                try {
                    submit(manager, destination, transfer);
                    return null;
                } catch (Exception error) {
                    throw new IOException(error);
                }
            }
        };
    }

    /** Watch newly created disk files too, without converting existing project contents. */
    public static synchronized void watch(ProjectAssetManager manager) {
        trackProjects();
        FileObject root = manager.getAssetFolder();
        if (root != null && FileUtil.toFile(root) != null && !WATCHES.containsKey(root.getPath())) {
            Watch watch = new Watch(manager);
            WATCHES.put(root.getPath(), watch);
            FileUtil.addRecursiveListener(watch, FileUtil.toFile(root));
        }
    }

    private static final class Watch extends FileChangeAdapter {
        private final ProjectAssetManager manager;
        private final Set<Path> pending = new LinkedHashSet<>();
        private final RequestProcessor.Task task;
        Watch(ProjectAssetManager manager) {
            this.manager = manager;
            task = WORK.create(() -> {
                List<Path> files;
                synchronized (pending) {
                    files = new ArrayList<>(pending);
                    pending.clear();
                }
                if (!files.isEmpty()) {
                    run(manager, manager.getAssetFolder(), files, true);
                }
            });
        }
        @Override public void fileDataCreated(FileEvent event) {
            File file = FileUtil.toFile(event.getFile());
            if (file == null) {
                return;
            }
            Path path = file.toPath().toAbsolutePath().normalize();
            String relative = FileUtil.getRelativePath(manager.getAssetFolder(), event.getFile());
            if (relative == null || Arrays.stream(relative.split("/")).anyMatch(part ->
                    part.startsWith(".") || part.equals("dds") || part.endsWith(".textures"))) {
                return;
            }
            if (!WRITING.contains(path)) {
                synchronized (pending) {
                    pending.add(path);
                    task.schedule(2000);
                }
            }
        }
        @Override public void fileChanged(FileEvent event) {
            File file = FileUtil.toFile(event.getFile());
            synchronized (pending) {
                if (file != null && pending.contains(file.toPath().toAbsolutePath().normalize())) {
                    task.schedule(2000);
                }
            }
        }
        @Override public void fileFolderCreated(FileEvent event) {
            WORK.post(() -> {
                var children = event.getFile().getChildren(true);
                while (children.hasMoreElements()) {
                    FileObject child = children.nextElement();
                    if (child.isData()) {
                        fileDataCreated(new FileEvent(child));
                    }
                }
            });
        }
    }

    private static boolean model(Path path) {
        return Set.of("fbx", "gltf", "glb", "obj", "dae", "blend", "3ds").contains(extension(path));
    }
    private static boolean media(Path path) {
        return Set.of("mp3", "mp4", "m4a", "aac", "flac").contains(extension(path));
    }
    private static boolean image(Path path) {
        return Set.of("png", "jpg", "jpeg", "bmp", "gif").contains(extension(path));
    }
    private static String extension(Path path) {
        String name = path.getFileName().toString();
        return name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }
    private static String key(Path path) {
        return path.toString().replace('\\', '/');
    }
    private static <T> T ui(Callable<T> action) throws Exception {
        java.util.concurrent.FutureTask<T> task = new java.util.concurrent.FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
    private static void error(Exception error) {
        java.util.logging.Logger.getLogger(ExternalAssetImport.class.getName())
                .log(java.util.logging.Level.WARNING, "Import failed", error);
        DialogDisplayer.getDefault().notifyLater(new NotifyDescriptor.Message(
                "Import failed: " + error.getMessage(), NotifyDescriptor.ERROR_MESSAGE));
    }

    private static void run(ProjectAssetManager manager, FileObject destination, List<Path> sources, boolean onDisk) {
        Path stage = null;
        boolean published = false;
        try {
            Path root = FileUtil.toFile(manager.getAssetFolder()).toPath().toAbsolutePath().normalize();
            Path target = FileUtil.toFile(destination).toPath().toAbsolutePath().normalize();
            if (!target.startsWith(root)) {
                throw new IOException("Destination is outside Assets");
            }
            Map<Path, Path> inputs = new LinkedHashMap<>();
            for (Path source : sources) {
                if (Files.isSymbolicLink(source)) {
                    throw new IOException("Symbolic links cannot be imported: " + source);
                }
                if (Files.isDirectory(source)) {
                    try (var files = Files.walk(source)) {
                        for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                            Path relative = onDisk ? root.relativize(file)
                                    : root.relativize(target).resolve(source.getFileName()).resolve(source.relativize(file));
                            addInput(inputs, relative, file);
                        }
                    }
                } else if (Files.isRegularFile(source)) {
                    addInput(inputs, onDisk ? root.relativize(source)
                            : root.relativize(target).resolve(source.getFileName()), source);
                }
            }
            if (onDisk && inputs.keySet().stream().noneMatch(p -> model(p) || image(p) || media(p))) {
                return;
            }
            Set<Path> originalInputs = Set.copyOf(inputs.keySet());
            Map<Path, Set<Path>> gltfDependencies = new HashMap<>();
            for (Path relative : originalInputs) {
                if ("gltf".equals(extension(relative))) {
                    gltfDependencies.put(relative, addGltfDependencies(inputs, relative, root, onDisk));
                }
            }
            stage = FileUtil.normalizeFile(Files.createTempDirectory(root.getParent(), ".jme-import-").toFile()).toPath();
            for (var entry : inputs.entrySet()) {
                Path staged = stage.resolve(entry.getKey());
                Files.createDirectories(staged.getParent());
                Files.copy(entry.getValue(), staged);
            }
            FileUtil.refreshFor(stage.toFile());
            ProjectAssetManager staging = new ProjectAssetManager(FileUtil.toFileObject(stage.toFile()));
            final Path stagingRoot = stage;
            Set<Path> used = new HashSet<>();
            Set<Path> retain = new HashSet<>();
            Set<Path> consumed = new HashSet<>();
            Set<Path> outputs = new LinkedHashSet<>();
            for (Path relative : inputs.keySet()) {
                if (!model(relative)) {
                    continue;
                }
                FileObject source = staging.getAssetFolder().getFileObject(key(relative));
                Set<Path> dependencies = new HashSet<>(gltfDependencies.getOrDefault(relative, Set.of()));
                List<String> missing = new ArrayList<>();
                com.jme3.asset.AssetEventListener listener = new com.jme3.asset.AssetEventListener() {
                    @Override public void assetLoaded(com.jme3.asset.AssetKey asset) {
                    }
                    @Override public void assetRequested(com.jme3.asset.AssetKey asset) {
                        Path path = Path.of(asset.getName());
                        Path existing = root.resolve(path).normalize();
                        if (onDisk && existing.startsWith(root) && Files.isRegularFile(existing)
                                && !Files.exists(stagingRoot.resolve(path))) {
                            try {
                                Files.createDirectories(stagingRoot.resolve(path).getParent());
                                Files.copy(existing, stagingRoot.resolve(path));
                            } catch (IOException failure) {
                                throw new java.io.UncheckedIOException(failure);
                            }
                        }
                        if (Files.isRegularFile(stagingRoot.resolve(path))) {
                            dependencies.add(path);
                        }
                    }
                    @Override public void assetDependencyNotFound(com.jme3.asset.AssetKey parent, com.jme3.asset.AssetKey dependency) {
                        missing.add(dependency.getName());
                    }
                };
                staging.addAssetEventListener(listener);
                Spatial loaded;
                try {
                    loaded = staging.loadModel(key(relative));
                } finally {
                    staging.removeAssetEventListener(listener);
                }
                if (loaded == null || !missing.isEmpty()) {
                    throw new IOException("Missing or unreadable model dependencies: " + relative + " " + missing);
                }
                dependencies.add(relative);
                var analysis = ModelConversionAnalysis.inspect(loaded);
                boolean[] keep = {false};
                ModelConversionOptions options = ui(() -> {
                    JCheckBox checkbox = new JCheckBox("Copy original resources to Assets", false);
                    ModelConversionOptions result = ModelConversionOptions.ask(analysis, relative.toString(), checkbox, manager);
                    keep[0] = checkbox.isSelected();
                    return result;
                });
                if (options == null) {
                    return;
                }
                Spatial prepared = ModelConversionPreparation.prepareForExport(loaded, options, staging, manager, source);
                if (options.importsSourceMaterials()) {
                    outputs.addAll(directTextureKeys(prepared, staging, stage));
                }
                Path output = relative.resolveSibling(source.getName() + ".j3o");
                if (inputs.containsKey(output)) {
                    throw new IOException("Batch already contains the model output: " + output);
                }
                BinaryExporter.getInstance().save(prepared, stage.resolve(output).toFile());
                outputs.add(output);
                used.addAll(dependencies);
                consumed.addAll(dependencies);
                if (keep[0]) {
                    retain.addAll(dependencies);
                }
                staging.clearCache();
            }
            for (Path relative : inputs.keySet()) {
                if (used.contains(relative) || !image(relative)) {
                    continue;
                }
                BufferedImage pixels = ImageIO.read(stage.resolve(relative).toFile());
                if (pixels == null) {
                    throw new IOException("Cannot decode " + relative);
                }
                TextureChoice choice = ui(() -> textureChoice(relative, pixels));
                if (choice == null) {
                    return;
                }
                BufferedImage resized = new BufferedImage(choice.width(), choice.height(), BufferedImage.TYPE_INT_ARGB);
                var graphics = resized.createGraphics();
                graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                        java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.drawImage(pixels, 0, 0, choice.width(), choice.height(), null);
                graphics.dispose();
                int levels = choice.mips() ? 32 - Integer.numberOfLeadingZeros(Math.max(choice.width(), choice.height())) : 1;
                Path output = relative.resolveSibling(relative.getFileName() + ".dds");
                if (inputs.containsKey(output)) {
                    throw new IOException("Batch already contains the texture output: " + output);
                }
                PortableModelDds.write(resized, stage.resolve(output), levels);
                outputs.add(output);
                consumed.add(relative);
                if (choice.retain()) {
                    retain.add(relative);
                }
            }
            for (Path relative : inputs.keySet()) {
                if (!media(relative)) {
                    continue;
                }
                int[] choice = ui(() -> {
                    JPanel rows = new JPanel(new java.awt.GridLayout(0, 2, 8, 6));
                    JComboBox<Integer> rate = new JComboBox<>(new Integer[]{44100, 48000, 22050});
                    JComboBox<Integer> bitrate = new JComboBox<>(new Integer[]{160, 96, 128, 192, 256});
                    JCheckBox keep = new JCheckBox("Copy original resources to Assets", false);
                    rows.add(new JLabel("Convert first audio track to OGG Vorbis"));
                    rows.add(new JLabel(relative.toString()));
                    rows.add(new JLabel("Sample rate (Hz)"));
                    rows.add(rate);
                    rows.add(new JLabel("Bitrate (kbps)"));
                    rows.add(bitrate);
                    rows.add(keep);
                    if (DialogDisplayer.getDefault().notify(new NotifyDescriptor.Confirmation(rows,
                            "Import audio", NotifyDescriptor.OK_CANCEL_OPTION)) != NotifyDescriptor.OK_OPTION) {
                        return null;
                    }
                    return new int[]{(Integer) rate.getSelectedItem(), (Integer) bitrate.getSelectedItem() * 1000,
                        keep.isSelected() ? 1 : 0};
                });
                if (choice == null) {
                    return;
                }
                Path output = relative.resolveSibling(relative.getFileName() + ".ogg");
                if (inputs.containsKey(output)) {
                    throw new IOException("Batch contains audio output: " + output);
                }
                AudioImportConverter.convert(stage.resolve(relative), stage.resolve(output), choice[0], choice[1]);
                outputs.add(output);
                consumed.add(relative);
                if (choice[2] != 0) {
                    retain.add(relative);
                }
            }
            // Non-convertible files are copied as-is, except consumed model sidecars.
            for (Path relative : inputs.keySet()) {
                if (!consumed.contains(relative)) {
                    outputs.add(relative);
                }
            }
            outputs.addAll(retain);
            for (var input : inputs.entrySet()) {
                if (Files.mismatch(input.getValue(), stage.resolve(input.getKey())) != -1) {
                    throw new IOException("Source changed during import; retry after copying finishes: " + input.getValue());
                }
            }
            publish(stage, root, outputs);
            published = true;
            if (onDisk) {
                for (Path relative : originalInputs) {
                    if (!consumed.contains(relative)) {
                        continue;
                    }
                    Path original = inputs.get(relative);
                    if (original != null && !retain.contains(relative) && !outputs.contains(relative)
                            && Files.mismatch(original, stage.resolve(relative)) == -1) {
                        Files.delete(original);
                    }
                }
            }
            manager.clearCache();
            manager.getAssetFolder().refresh(true);
        } catch (Exception failure) {
            error(failure);
        } finally {
            if (stage != null) {
                // Only the private directory returned by createTempDirectory is removed.
                try {
                    new com.jme3.gde.texturetools.GeneratedTextures(stage).delete(stage);
                } catch (IOException failure) {
                    java.util.logging.Logger.getLogger(ExternalAssetImport.class.getName())
                            .log(java.util.logging.Level.WARNING, "Cannot remove import staging directory: " + stage, failure);
                    DialogDisplayer.getDefault().notifyLater(new NotifyDescriptor.Message(
                            (published ? "Assets were imported successfully. " : "Import staging cleanup was incomplete. ")
                            + "Could not remove temporary directory: " + stage + "\n" + failure.getMessage(),
                            NotifyDescriptor.WARNING_MESSAGE));
                }
            }
        }
    }

    private static Set<Path> addGltfDependencies(Map<Path, Path> inputs, Path model, Path root, boolean onDisk)
            throws IOException {
        Path source = inputs.get(model);
        Path sourceFolder = source.getParent();
        Path allowedFolder = onDisk ? root : sourceFolder;
        Path destinationFolder = model.getParent() == null ? Path.of("") : model.getParent();
        Set<Path> dependencies = new LinkedHashSet<>();
        try (var reader = Files.newBufferedReader(source)) {
            var document = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
            for (String section : List.of("buffers", "images")) {
                var entries = document.getAsJsonArray(section);
                if (entries == null) {
                    continue;
                }
                for (var entry : entries) {
                    var uri = entry.getAsJsonObject().get("uri");
                    if (uri == null || uri.isJsonNull()) {
                        continue;
                    }
                    String reference = uri.getAsString();
                    if (reference.startsWith("data:")) {
                        continue;
                    }
                    // Match GltfLoader, including %2F used by Godot for texture subdirectories.
                    String decoded = java.net.URLDecoder.decode(reference, java.nio.charset.StandardCharsets.UTF_8);
                    if (decoded.isEmpty() || decoded.indexOf(':') >= 0 || decoded.indexOf('\\') >= 0
                            || reference.indexOf('?') >= 0 || reference.indexOf('#') >= 0) {
                        throw new IOException("Expected a local glTF dependency path: " + reference);
                    }
                    Path path = Path.of(decoded);
                    Path dependency = sourceFolder.resolve(path).normalize();
                    Path destination = destinationFolder.resolve(path).normalize();
                    if (path.isAbsolute() || !dependency.startsWith(allowedFolder)
                            || destination.startsWith("..")) {
                        throw new IOException("glTF dependency is outside the import folder: " + reference);
                    }
                    Path existing = inputs.get(destination);
                    if (existing != null && !existing.equals(dependency)) {
                        throw new IOException("Conflicting glTF dependency at " + destination);
                    }
                    if (!Files.isRegularFile(dependency) || !dependency.toRealPath().startsWith(allowedFolder.toRealPath())) {
                        throw new IOException("Missing or inaccessible glTF dependency: " + dependency);
                    }
                    addInput(inputs, destination, dependency);
                    dependencies.add(destination);
                }
            }
        } catch (com.google.gson.JsonParseException | IllegalArgumentException | IllegalStateException failure) {
            throw new IOException("Invalid glTF dependency list: " + source.getFileName(), failure);
        }
        return dependencies;
    }

    private static void addInput(Map<Path, Path> inputs, Path relative, Path source) throws IOException {
        if (relative.isAbsolute() || relative.normalize().startsWith("..") || Files.isSymbolicLink(source)) {
            throw new IOException("Invalid import path: " + source);
        }
        Path previous = inputs.putIfAbsent(relative, source);
        if (previous != null && !previous.equals(source)) {
            throw new IOException("Two source files have the same destination: " + relative);
        }
    }

    private record TextureChoice(int width, int height, boolean mips, boolean retain) {
    }
    private static TextureChoice textureChoice(Path file, BufferedImage image) {
        JPanel panel = new JPanel(new java.awt.GridLayout(0, 2, 8, 6));
        JSpinner width = new JSpinner(new SpinnerNumberModel(image.getWidth(), 1, 16384, 1));
        JSpinner height = new JSpinner(new SpinnerNumberModel(image.getHeight(), 1, 16384, 1));
        JCheckBox mip = new JCheckBox("Generate mipmaps", true);
        JCheckBox retain = new JCheckBox("Copy original resources to Assets", false);
        panel.add(new JLabel("Source: " + image.getWidth() + " x " + image.getHeight()));
        panel.add(new JLabel("DDS / BC3 (RGBA)"));
        panel.add(new JLabel("Width"));
        panel.add(width);
        panel.add(new JLabel("Height"));
        panel.add(height);
        panel.add(mip);
        panel.add(retain);
        while (DialogDisplayer.getDefault().notify(new NotifyDescriptor.Confirmation(panel,
                "Convert texture - " + file, NotifyDescriptor.OK_CANCEL_OPTION)) == NotifyDescriptor.OK_OPTION) {
            try {
                width.commitEdit();
                height.commitEdit();
                int w = ((Number) width.getValue()).intValue();
                int h = ((Number) height.getValue()).intValue();
                if ((long) w * h > 64L * 1024 * 1024) {
                    throw new IllegalArgumentException("Texture exceeds 64 million pixels");
                }
                return new TextureChoice(w, h, mip.isSelected(), retain.isSelected());
            } catch (Exception failure) {
                DialogDisplayer.getDefault().notify(new NotifyDescriptor.Message(failure.getMessage(), NotifyDescriptor.ERROR_MESSAGE));
            }
        }
        return null;
    }

    private static Set<Path> directTextureKeys(Spatial model, ProjectAssetManager assets, Path stage) throws IOException {
        Set<Path> resources = new LinkedHashSet<>();
        var store = new com.jme3.gde.texturetools.GeneratedTextures(stage);
        IOException[] failure = {null};
        model.depthFirstTraversal(spatial -> {
            if (spatial instanceof Geometry geometry && geometry.getMaterial() != null) {
                for (var parameter : geometry.getMaterial().getParams()) {
                    if (parameter instanceof MatParamTexture param && param.getTextureValue().getKey() instanceof TextureKey original) {
                        try {
                            Path resolved = store.resolve(original.getName());
                            Path relative = resolved == null ? Path.of(original.getName()) : stage.relativize(resolved);
                            if (Files.isRegularFile(stage.resolve(relative))) {
                                resources.add(relative);
                                TextureKey replacement = new TextureKey(key(relative), original.isFlipY());
                                replacement.setGenerateMips(original.isGenerateMips());
                                replacement.setAnisotropy(original.getAnisotropy());
                                replacement.setTextureTypeHint(original.getTextureTypeHint());
                                param.getTextureValue().setKey(replacement);
                            }
                        } catch (IOException ex) {
                            failure[0] = ex;
                        }
                    }
                }
            }
        });
        if (failure[0] != null) {
            throw failure[0];
        }
        return resources;
    }

    private static void publish(Path stage, Path root, Set<Path> outputs) throws IOException {
        List<Path> created = new ArrayList<>();
        for (Path relative : outputs) {
            Path destination = root.resolve(relative).normalize();
            if (!destination.startsWith(root) || !Files.isRegularFile(stage.resolve(relative))) {
                throw new IOException("Invalid output: " + relative);
            }
            if (Files.exists(destination) && Files.mismatch(stage.resolve(relative), destination) != -1) {
                throw new IOException("Destination already exists (nothing overwritten): " + destination);
            }
        }
        try {
            for (Path relative : outputs) {
                Path destination = root.resolve(relative).normalize();
                if (!Files.exists(destination)) {
                    WRITING.add(destination);
                    Files.createDirectories(destination.getParent());
                    Path temporary = Files.createTempFile(destination.getParent(), ".import-", ".tmp");
                    try {
                        Files.copy(stage.resolve(relative), temporary, StandardCopyOption.REPLACE_EXISTING);
                        Files.move(temporary, destination);
                        created.add(destination);
                    } finally {
                        Files.deleteIfExists(temporary);
                    }
                }
            }
            FileUtil.refreshFor(root.toFile());
        } catch (IOException failure) {
            for (Path path : created) {
                Files.deleteIfExists(path);
            }
            throw failure;
        } finally {
            // Retain suppression until the filesystem refresh has dispatched its events.
            WORK.post(() -> WRITING.removeAll(created), 5000);
        }
    }
}
