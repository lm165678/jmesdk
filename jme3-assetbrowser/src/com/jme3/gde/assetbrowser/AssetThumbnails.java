package com.jme3.gde.assetbrowser;

import com.jme3.asset.AssetInfo;
import com.jme3.audio.AudioKey;
import com.jme3.audio.AudioBuffer;
import com.jme3.bounding.BoundingBox;
import com.jme3.bounding.BoundingSphere;
import com.jme3.export.binary.BinaryImporter;
import com.jme3.gde.core.assets.ProjectAssetManager;
import com.jme3.gde.core.scene.SceneApplication;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.niftygui.NiftyJmeDisplay;
import com.jme3.post.FilterPostProcessor;
import com.jme3.renderer.Camera;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Box;
import com.jme3.scene.shape.Sphere;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.util.BufferUtils;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.openide.filesystems.FileObject;
import org.openide.util.RequestProcessor;

/**
 * Bounded, lazy thumbnails. Disk IO runs on one worker; GPU jobs run one at a
 * time on the scene thread. Private views/targets are disposed after readback;
 * scene contents, camera and material state are never reused or edited.
 * Each asset records its own dependency closure and persisted file fingerprint.
 * Texture/MTL/shader edits invalidate only that asset and its known dependants.
 * Cache metadata is checked on a worker; unrelated scene saves retain thumbnails.
 * Errors produce readable tiles, not retries
 * every repaint. Closing the panel cancels queued work and stale UI callbacks.
 */
final class AssetThumbnails implements AutoCloseable {
    record Result(BufferedImage image, String error) { }
    private static final int SIZE = 256;
    private final RequestProcessor worker = new RequestProcessor("Asset thumbnails", 1, true);
    private final Map<String, Result> memory = new LinkedHashMap<>(64, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Result> entry) {
            return size() > 256;
        }
    };
    private final Set<String> pending = new HashSet<>();
    private volatile boolean closed;
    private ProjectAssetManager isolated;
    private ProjectAssetManager sourceManager;
    private final AssetDependencies dependencies = new AssetDependencies();
    private long refreshEpoch;

    Set<String> dependencies(String asset) {
        return dependencies.paths(asset);
    }

    long version(String asset) {
        return dependencies.version(asset);
    }

    void invalidate(Map<String, Boolean> changes) {
        dependencies.invalidate(changes);
    }

    void invalidateAll() {
        dependencies.invalidateAll();
        refreshEpoch++;
        memory.clear();
    }

    // Requests come from the EDT. The worker handles disk access and waits for GPU work.
    Result request(ProjectAssetManager manager, FileObject file, String asset, Consumer<Result> callback) {
        String version = "v8-dependencies";
        long requestedVersion = dependencies.version(asset);
        long requestedEpoch = refreshEpoch;
        String key = file.toURL() + ":" + AssetDependencies.stamp(file)
                + ":" + requestedVersion + ":" + requestedEpoch + ":" + version;
        Result known = memory.get(key);
        if (known != null) {
            return known;
        }
        if (closed || pending.size() >= 48 || !pending.add(key)) {
            return null;
        }
        worker.post(() -> {
            if (closed) {
                return;
            }
            Result result;
            try {
                Path cache = Path.of(System.getProperty("netbeans.user", System.getProperty("java.io.tmpdir")),
                        "var", "cache", "asset-thumbnails-" + version);
                if (isolated == null || sourceManager != manager) {
                    isolated = AssetDependencies.isolated(manager);
                    sourceManager = manager;
                }
                String identity = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(file.toURL().toString().getBytes(StandardCharsets.UTF_8)));
                Path metadata = cache.resolve(identity + ".properties");
                Properties saved = new Properties();
                if (Files.isRegularFile(metadata)) {
                    try (InputStream input = Files.newInputStream(metadata)) {
                        saved.load(input);
                    }
                }
                boolean inspected = false;
                if (AssetDependencies.stamp(file).equals(saved.getProperty("source"))) {
                    dependencies.record(asset, Arrays.asList(saved.getProperty("paths", "").split("\\n")));
                } else {
                    dependencies.record(asset, AssetDependencies.inspect(isolated, file, asset).paths());
                    inspected = true;
                }
                String fingerprint = AssetDependencies.fingerprint(manager.getAssetFolder(), asset, dependencies.paths(asset));
                Path png = cache.resolve(identity + "-" + fingerprint + ".png");
                BufferedImage image = requestedEpoch == 0 && requestedVersion == 0 ? readCached(png) : null;
                if (image == null) {
                    if (!inspected) {
                        dependencies.record(asset, AssetDependencies.inspect(isolated, file, asset).paths());
                        fingerprint = AssetDependencies.fingerprint(manager.getAssetFolder(), asset, dependencies.paths(asset));
                        png = cache.resolve(identity + "-" + fingerprint + ".png");
                    }
                    Set<String> knownPaths = dependencies.paths(asset);
                    Set<String> observed;
                    try (var tracking = new AssetDependencies.Tracking(isolated)) {
                        image = generateForRequest(manager, file, asset);
                        if (!tracking.unchanged() || !fingerprint.equals(
                                AssetDependencies.fingerprint(manager.getAssetFolder(), asset, knownPaths))) {
                            dependencies.invalidate(Map.of(asset, false));
                            throw new IOException("Asset dependencies changed during thumbnail capture; retry");
                        }
                        observed = tracking.paths();
                    }
                    Set<String> complete = new HashSet<>(knownPaths);
                    complete.addAll(observed);
                    dependencies.record(asset, complete);
                    fingerprint = AssetDependencies.fingerprint(manager.getAssetFolder(), asset, dependencies.paths(asset));
                    png = cache.resolve(identity + "-" + fingerprint + ".png");
                    writeCached(png, image);
                    saved.setProperty("source", AssetDependencies.stamp(file));
                    saved.setProperty("paths", String.join("\n", dependencies.paths(asset)));
                    Files.createDirectories(cache);
                    try (OutputStream output = Files.newOutputStream(metadata)) {
                        saved.store(output, "Thumbnail dependency closure");
                    }
                }
                result = new Result(image, "");
            } catch (Exception | LinkageError error) {
                String message = error.getCause() == null ? error.toString() : error.getCause().toString();
                result = new Result(card(file.getNameExt(), message, new Color(173, 88, 83)), message);
            }
            if (isolated != null) {
                isolated.clearCache();
            }
            Result ready = result;
            SwingUtilities.invokeLater(() -> {
                pending.remove(key);
                if (!closed && requestedVersion == dependencies.version(asset)
                        && requestedEpoch == refreshEpoch) {
                    memory.put(key, ready);
                    callback.accept(ready);
                } else if (!closed) {
                    callback.accept(null);
                }
            });
        });
        return null;
    }

    private static BufferedImage readCached(Path png) {
        try {
            return Files.isRegularFile(png) ? ImageIO.read(png.toFile()) : null;
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private static void writeCached(Path png, BufferedImage image) {
        try {
            Files.createDirectories(png.getParent());
            ImageIO.write(image, "png", png.toFile());
        } catch (IOException | RuntimeException ignored) {
            // The decoded thumbnail still serves this request when disk cache is unavailable.
        }
    }

    private BufferedImage generateForRequest(ProjectAssetManager manager, FileObject file,
            String asset) throws Exception {
        if (AssetPreviewTextures.usesImageEditor(file)) {
            return fit(AssetPreviewTextures.read(null, file, asset));
        }
        if (isolated == null || sourceManager != manager) {
            isolated = new ProjectAssetManager(manager.getAssetFolder());
            for (ClassLoader loader : manager.getClassLoaders()) {
                isolated.addClassLoader(loader);
            }
            sourceManager = manager;
        }
        try {
            return generate(isolated, file, asset);
        } finally {
            isolated.clearCache();
        }
    }

    private BufferedImage generate(ProjectAssetManager manager, FileObject file, String asset) throws Exception {
        return switch (AssetKind.of(file)) {
            case TEXTURE -> fit(AssetPreviewTextures.read(manager, file, asset));
            case LIGHTMAP -> {
                var lightmap = com.jme3.gde.core.assets.ProbeLightmapTopComponent.readLightmap(file);
                yield fit(com.jme3.gde.core.assets.ProbeLightmapTopComponent.renderImage(lightmap,
                        com.jme3.gde.core.assets.ProbeLightmapTopComponent.Channel.IRRADIANCE, 0));
            }
            case AUDIO -> java.util.Set.of("mp3", "mp4", "m4a", "aac", "flac")
                    .contains(file.getExt().toLowerCase(java.util.Locale.ROOT))
                    ? card(file.getExt().toUpperCase(java.util.Locale.ROOT), "OGG", new Color(89, 133, 180))
                    : waveform(loadAudio(manager, file, asset));
            case SHADER -> source(file);
            case MODEL, MATERIAL, FILTER, GUI -> SceneApplication.getApplication().enqueue(
                    () -> render(manager, file, asset)).get(60, TimeUnit.SECONDS);
            default -> source(file);
        };
    }

    static AudioBuffer loadAudio(ProjectAssetManager manager, FileObject file, String asset) throws IOException {
        AudioKey key = new AudioKey(asset, false);
        var loader = switch (file.getExt().toLowerCase(Locale.ROOT)) {
            case "wav" -> new com.jme3.audio.plugins.WAVLoader();
            case "ogg" -> new com.jme3.audio.plugins.OGGLoader();
            default -> (com.jme3.asset.AssetLoader) null;
        };
        if (loader == null) {
            return (AudioBuffer) manager.loadAudio(key);
        }
        return (AudioBuffer) loader.load(new AssetInfo(manager, key) {
            @Override
            public InputStream openStream() {
                try {
                    return file.getInputStream();
                } catch (IOException error) {
                    throw new UncheckedIOException(error);
                }
            }
        });
    }

    private BufferedImage waveform(AudioBuffer audio) {
        ByteBuffer pcm = audio.getData().duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int bytes = audio.getBitsPerSample() / 8;
        int frameBytes = bytes * audio.getChannels();
        int frames = pcm.remaining() / frameBytes;
        BufferedImage image = background();
        Graphics2D g = graphics(image);
        g.setColor(new Color(92, 190, 167));
        for (int x = 12; x < SIZE - 12; x++) {
            int from = (x - 12) * frames / (SIZE - 24);
            int to = Math.max(from + 1, (x - 11) * frames / (SIZE - 24));
            float peak = 0;
            for (int f = from; f < Math.min(frames, to); f += Math.max(1, (to - from) / 256)) {
                for (int channel = 0; channel < audio.getChannels(); channel++) {
                    int offset = f * frameBytes + channel * bytes;
                    float sample = bytes == 1 ? (Byte.toUnsignedInt(pcm.get(offset)) - 128) / 128f
                            : bytes == 2 ? pcm.getShort(offset) / 32768f : 0;
                    peak = Math.max(peak, Math.abs(sample));
                }
            }
            int height = Math.max(1, (int) (peak * 90));
            g.drawLine(x, SIZE / 2 - height, x, SIZE / 2 + height);
        }
        g.dispose();
        return image;
    }

    private BufferedImage source(FileObject file) throws IOException {
        try (InputStream input = file.getInputStream()) {
            String text = new String(input.readNBytes(8192), StandardCharsets.UTF_8);
            return card(file.getNameExt(), text, new Color(128, 167, 217));
        }
    }

    private BufferedImage render(ProjectAssetManager manager, FileObject file, String asset) throws Exception {
        if (closed) {
            throw new IOException("Preview cancelled");
        }
        SceneApplication app = SceneApplication.getApplication();
        var rm = app.getRenderManager();
        Camera previousCamera = rm.getCurrentCamera();
        FrameBuffer previousBuffer = app.getRenderer().getCurrentFrameBuffer();
        Camera camera = new Camera(SIZE, SIZE);
        camera.setFrustumPerspective(40, 1, 0.05f, 100);
        camera.setLocation(new Vector3f(3, 2, 4));
        camera.lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);
        ViewPort view = rm.createPreView("Asset thumbnail", camera);
        view.setRenderPath(com.jme3.renderer.RenderPath.Forward);
        view.setClearFlags(true, true, true);
        view.setBackgroundColor(new ColorRGBA(.045f, .055f, .07f, 1));
        FrameBuffer buffer = new FrameBuffer(SIZE, SIZE, 1);
        buffer.setDepthBuffer(Image.Format.Depth);
        buffer.setColorBuffer(Image.Format.RGBA8);
        buffer.setSrgb(true);
        view.setOutputFrameBuffer(buffer);
        Node scene = new Node("Thumbnail scene");
        scene.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
        scene.addLight(new AmbientLight(new ColorRGBA(.3f, .3f, .3f, 1)));
        scene.addLight(new DirectionalLight(new Vector3f(-1, -2, -1).normalizeLocal(), ColorRGBA.White));
        view.attachScene(scene);
        try {
            AssetKind kind = AssetKind.of(file);
            if (kind == AssetKind.GUI) {
                var factory = DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
                org.w3c.dom.Document document;
                try (InputStream input = file.getInputStream()) {
                    document = factory.newDocumentBuilder().parse(input);
                }
                if (!document.getDocumentElement().getTagName().equals("nifty")) {
                    return source(file);
                }
                var screens = document.getElementsByTagName("screen");
                if (screens.getLength() == 0) {
                    return source(file);
                }
                var elements = document.getElementsByTagName("*");
                for (int i = 0; i < elements.getLength(); i++) {
                    // Static layout preview must not instantiate game controllers.
                    ((org.w3c.dom.Element) elements.item(i)).removeAttribute("controller");
                }
                String screen = ((org.w3c.dom.Element) screens.item(0)).getAttribute("id");
                ByteArrayOutputStream xml = new ByteArrayOutputStream();
                TransformerFactory.newInstance().newTransformer().transform(new DOMSource(document), new StreamResult(xml));
                NiftyJmeDisplay display = NiftyJmeDisplay.newNiftyJmeDisplay(manager, null, null, view);
                view.addProcessor(display);
                display.getNifty().fromXml(asset, new ByteArrayInputStream(xml.toByteArray()), screen);
            } else {
                Spatial model;
                if (kind == AssetKind.MODEL) {
                    model = manager.loadModel(asset).clone(true);
                    model.depthFirstTraversal(spatial -> {
                        while (spatial.getNumControls() > 0) {
                            spatial.removeControl(spatial.getControl(0));
                        }
                    });
                } else {
                    Geometry sphere = new Geometry("Material", new Sphere(32, 48, 1));
                    Material material = kind == AssetKind.MATERIAL
                            ? (file.getExt().equalsIgnoreCase("j3md") ? new Material(manager, asset) : manager.loadMaterial(asset).clone())
                            : new Material(manager, "Common/MatDefs/Light/Lighting.j3md");
                    if (kind == AssetKind.FILTER) {
                        material.setBoolean("UseMaterialColors", true);
                        material.setColor("Diffuse", new ColorRGBA(.8f, .35f, .1f, 1));
                        material.setColor("Ambient", ColorRGBA.White);
                        BinaryImporter importer = new BinaryImporter();
                        importer.setAssetManager(manager);
                        try (InputStream input = file.getInputStream()) {
                            FilterPostProcessor filters = (FilterPostProcessor) importer.load(input);
                            filters.setAssetManager(manager);
                            view.addProcessor(filters);
                        }
                    }
                    sphere.setMaterial(material);
                    model = sphere;
                }
                model.updateGeometricState();
                Vector3f center = model.getWorldBound() == null ? new Vector3f() : model.getWorldBound().getCenter().clone();
                float radius = model.getWorldBound() instanceof BoundingBox box
                        ? new Vector3f(box.getXExtent(), box.getYExtent(), box.getZExtent()).length()
                        : model.getWorldBound() instanceof BoundingSphere ball ? ball.getRadius() : 1;
                Node normalized = new Node();
                normalized.attachChild(model);
                float scale = 1.4f / Math.max(.001f, radius);
                normalized.setLocalScale(scale);
                normalized.setLocalTranslation(center.mult(-scale));
                scene.attachChild(normalized);
                if (kind == AssetKind.FILTER) {
                    Geometry floor = new Geometry("Floor", new Box(2, .05f, 2));
                    Material material = new Material(manager, "Common/MatDefs/Light/Lighting.j3md");
                    floor.setMaterial(material);
                    floor.setLocalTranslation(0, -1.4f, 0);
                    scene.attachChild(floor);
                }
            }
            scene.updateLogicalState(0);
            scene.updateGeometricState();
            rm.renderViewPort(view, 0);
            ByteBuffer pixels = BufferUtils.createByteBuffer(SIZE * SIZE * 4);
            app.getRenderer().readFrameBufferWithFormat(buffer, pixels, Image.Format.RGBA8);
            BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    int i = (y * SIZE + x) * 4;
                    image.setRGB(x, SIZE - 1 - y, 0xff000000 | (pixels.get(i) & 255) << 16
                            | (pixels.get(i + 1) & 255) << 8 | (pixels.get(i + 2) & 255));
                }
            }
            return image;
        } finally {
            try {
                view.clearProcessors();
                rm.removePreView(view);
                view.clearScenes();
                buffer.dispose();
            } finally {
                app.getRenderer().setFrameBuffer(previousBuffer);
                if (previousCamera != null) {
                    previousCamera.onViewPortChange();
                    rm.setCamera(previousCamera, false);
                }
            }
        }
    }

    static BufferedImage card(String title, String body, Color accent) {
        BufferedImage image = background();
        Graphics2D g = graphics(image);
        g.setColor(accent);
        g.fillRoundRect(12, 12, SIZE - 24, 5, 5, 5);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        g.drawString(title, 14, 39);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
        int y = 60;
        for (String line : body.split("\\R")) {
            if (y > SIZE - 10) {
                break;
            }
            g.setColor(line.trim().startsWith("//") ? new Color(115, 155, 117) : new Color(175, 188, 205));
            g.drawString(line.replace("\t", "  "), 14, y);
            y += 13;
        }
        g.dispose();
        return image;
    }

    private static BufferedImage background() {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(35, 39, 46));
        g.fillRect(0, 0, SIZE, SIZE);
        g.dispose();
        return image;
    }

    private static Graphics2D graphics(BufferedImage image) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        return g;
    }

    private static BufferedImage fit(BufferedImage source) {
        BufferedImage image = background();
        Graphics2D g = graphics(image);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        float scale = Math.min((float) SIZE / source.getWidth(), (float) SIZE / source.getHeight());
        int w = Math.max(1, Math.round(source.getWidth() * scale));
        int h = Math.max(1, Math.round(source.getHeight() * scale));
        g.drawImage(source, (SIZE - w) / 2, (SIZE - h) / 2, w, h, null);
        g.dispose();
        return image;
    }

    @Override
    public void close() {
        closed = true;
        worker.shutdown();
        memory.clear();
        pending.clear();
    }
}
