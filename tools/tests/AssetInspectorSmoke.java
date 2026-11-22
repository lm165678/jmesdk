package com.jme3.gde.assetbrowser;

import com.jme3.anim.AnimClip;
import com.jme3.anim.AnimComposer;
import com.jme3.anim.AnimTrack;
import com.jme3.anim.TransformTrack;
import com.jme3.app.SimpleApplication;
import com.jme3.gde.core.assets.ProjectAssetManager;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.shape.Box;
import com.jme3.system.AppSettings;
import com.jme3.system.JmeContext;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import org.openide.filesystems.LocalFileSystem;
import org.openide.modules.InstalledFileLocator;
import org.openide.util.lookup.Lookups;
import org.openide.util.lookup.ProxyLookup;

public final class AssetInspectorSmoke extends SimpleApplication {
    private final CompletableFuture<Void> result = new CompletableFuture<>();

    public static void main(String[] args) throws Exception {
        System.setProperty("org.openide.util.Lookup", PreviewLookup.class.getName());
        verifyRasterFiles(Path.of(args[0]));
        verifyThumbnails(Path.of(args[0]));
        verifyControls(Path.of(args[0]));
        AssetInspectorSmoke app = new AssetInspectorSmoke();
        AppSettings settings = new AppSettings(true);
        settings.setRenderer(AppSettings.LWJGL_OPENGL43);
        settings.setAudioRenderer(null);
        settings.setResolution(384, 384);
        app.setSettings(settings);
        app.start(JmeContext.Type.OffscreenSurface);
        try {
            app.result.get(45, TimeUnit.SECONDS);
        } finally {
            app.stop(true);
        }
        System.out.println("ASSET_INSPECTOR_PASS");
        System.exit(0);
    }

    private static void verifyRasterFiles(Path assets) throws Exception {
        var project = new LocalFileSystem();
        project.setRootDirectory(assets.toFile().getCanonicalFile());
        var sponza = project.getRoot().getFileObject("Models/Sponza");
        if (sponza == null) {
            return;
        }
        var image = sponza.getFileObject("10381718147657362067.jpg");
        var decoded = AssetPreviewTextures.read(null, image, image.getNameExt());
        check(decoded.getWidth() == 1024 && decoded.getHeight() == 1024, "Sponza JPEG decode");
        check(AssetContentPanel.assetSize(image) == image.getSize()
                && AssetContentPanel.assetSize(sponza) > image.getSize(), "File and folder properties");
        try (var thumbnails = new AssetThumbnails()) {
            var result = new CompletableFuture<AssetThumbnails.Result>();
            SwingUtilities.invokeAndWait(() -> thumbnails.request(null, image, image.getNameExt(), 1, result::complete));
            check(result.get(15, TimeUnit.SECONDS).error().isEmpty(), "Sponza JPEG thumbnail");
        }
        System.out.println("ASSET_RASTER_PROPERTIES_PASS");
    }

    private static void verifyControls(Path assets) throws Exception {
        Path temporary = Path.of(System.getProperty("netbeans.user")).resolve("audio-fixture");
        Files.createDirectories(temporary);
        try (var pcm = new AudioInputStream(new ByteArrayInputStream(new byte[1600]),
                new AudioFormat(8000, 16, 1, true, false), 800)) {
            AudioSystem.write(pcm, AudioFileFormat.Type.WAVE, temporary.resolve("test.wav").toFile());
        }
        LocalFileSystem project = new LocalFileSystem();
        project.setRootDirectory(assets.toFile().getCanonicalFile());
        LocalFileSystem audioFs = new LocalFileSystem();
        audioFs.setRootDirectory(temporary.toFile().getCanonicalFile());
        var manager = new ProjectAssetManager(project.getRoot());
        var audioManager = new ProjectAssetManager(audioFs.getRoot());
        var holder = new AtomicReference<AssetInspector>();
        var pending = new AtomicReference<CompletableFuture<Long>>(new CompletableFuture<>());
        String path = "Models/room/textures/dds/leaves_baseColor.png.dds";
        SwingUtilities.invokeAndWait(() -> {
            var inspector = new AssetInspector(() -> {
                long size = holder.get().memoryBytes();
                if (size >= 0) {
                    pending.get().complete(size);
                }
            });
            holder.set(inspector);
            inspector.getComponent(0).setSize(200, 180);
            inspector.select(manager, project.getRoot().getFileObject(path), path, AssetKind.TEXTURE, 0);
        });
        check(pending.get().get(40, TimeUnit.SECONDS) > 0, "Texture memory");
        SwingUtilities.invokeAndWait(() -> {
            var inspector = holder.get();
            String before = zoomLabel(inspector);
            Component view = inspector.getComponent(0);
            view.dispatchEvent(new MouseWheelEvent(view, MouseWheelEvent.MOUSE_WHEEL, 0, 0,
                    100, 90, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, -3));
            check(!before.equals(zoomLabel(inspector)), "Texture zoom did not update");
            pending.set(new CompletableFuture<>());
            inspector.select(audioManager, audioFs.getRoot().getFileObject("test.wav"), "test.wav", AssetKind.AUDIO, 0);
        });
        check(pending.get().get(40, TimeUnit.SECONDS) == 1600, "Decoded WAV memory");
        SwingUtilities.invokeAndWait(() -> {
            var inspector = holder.get();
            check(hasButton(inspector, "Play") && hasButton(inspector, "Stop"), "Audio controls missing");
            inspector.clear();
            check(!hasButton(inspector, "Play") && inspector.memoryBytes() < 0, "Audio selection not cleared");
        });
        System.out.println("ASSET_PREVIEW_CONTROLS_PASS");
    }

    private static String zoomLabel(Container root) {
        for (Component child : root.getComponents()) {
            if (child instanceof JLabel label && label.getText().endsWith("%")) {
                return label.getText();
            }
            if (child instanceof Container container) {
                String found = zoomLabel(container);
                if (!found.isEmpty()) {
                    return found;
                }
            }
        }
        return "";
    }

    private static boolean hasButton(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof JButton button && text.equals(button.getText())) {
                return true;
            }
            if (child instanceof Container container && hasButton(container, text)) {
                return true;
            }
        }
        return false;
    }

    private static void verifyThumbnails(Path assets) throws Exception {
        LocalFileSystem filesystem = new LocalFileSystem();
        filesystem.setRootDirectory(assets.toFile().getCanonicalFile());
        var folder = filesystem.getRoot();
        var manager = new ProjectAssetManager(folder);
        try (var thumbnails = new AssetThumbnails()) {
            for (String path : new String[]{"Models/room/textures/dds/leaves_baseColor.png.dds",
                    "Models/room/textures/dds/Ocean_Water_baseColor.png.dds",
                    "Models/room/textures/dds/Ocean_Water_normal.png.dds", "Textures/Skys/Path.hdr"}) {
                var result = new CompletableFuture<AssetThumbnails.Result>();
                SwingUtilities.invokeAndWait(() -> {
                    var cached = thumbnails.request(manager, folder.getFileObject(path), path, 0, result::complete);
                    if (cached != null) {
                        result.complete(cached);
                    }
                });
                var thumbnail = result.get(45, TimeUnit.SECONDS);
                check(thumbnail.error().isEmpty(), path + " " + thumbnail.error());
                check(thumbnail.image().getWidth() == 256 && pixels(thumbnail.image()) != 0L, path);
            }
            check(AssetInspector.units(600).equals("0.59 KB"), "File units");
            check(AssetInspector.units(1048576).equals("1.00 MB"), "MB units");
        }
        System.out.println("ASSET_THUMBNAILS_PASS");
    }

    public static final class PreviewLookup extends ProxyLookup {
        public PreviewLookup() {
            super(Lookups.fixed(new InstalledFileLocator() {
                @Override
                public File locate(String path, String module, boolean localized) {
                    var file = new File(System.getProperty("asset.preview.cluster"), path);
                    return file.exists() ? file : null;
                }
            }), Lookups.metaInfServices(AssetInspectorSmoke.class.getClassLoader()));
        }
    }

    @Override
    public void simpleInitApp() {
        try {
            Node model = new Node();
            var geometry = new Geometry("Preview mesh", new Box(.8f, .35f, .2f));
            Material material = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            material.setColor("Color", ColorRGBA.Orange);
            geometry.setMaterial(material);
            model.attachChild(geometry);
            var composer = new AnimComposer();
            var clip = new AnimClip("Move");
            clip.setTracks(new AnimTrack<?>[]{new TransformTrack(geometry, new float[]{0, .5f, 1f},
                    new Vector3f[]{new Vector3f(), new Vector3f(0, .5f, 0), new Vector3f()}, null, null)});
            composer.addAnimClip(clip);
            model.addControl(composer);
            int initialViews = renderManager.getPreViews().size();
            var preview = new AssetModelPreview(renderManager, model);
            try {
                check(preview.clips.contains("Move") && preview.memory > 0, "Clip and model metadata");
                long original = pixels(preview.render(.65f, .35f, 4.9f, "Move", false));
                long rotated = pixels(preview.render(1.8f, .3f, 4.9f, "Move", false));
                long zoomed = pixels(preview.render(.65f, .35f, 3.3f, "Move", false));
                check(original != rotated && original != zoomed, "Orbit and zoom must change pixels");
                preview.render(.65f, .35f, 4.9f, "Move", true);
                Thread.sleep(100);
                long advanced = pixels(preview.render(.65f, .35f, 4.9f, "Move", true));
                check(advanced != original && geometry.getLocalTranslation().y > 0, "Animation did not advance");
                check(advanced == pixels(preview.render(.65f, .35f, 4.9f, "Move", false)), "Pause changed the pose");
            } finally {
                preview.close();
            }
            check(renderManager.getPreViews().size() == initialViews, "Preview viewport was retained");
            result.complete(null);
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
    }

    private static long pixels(BufferedImage image) {
        long hash = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                hash = 31 * hash + image.getRGB(x, y);
            }
        }
        return hash;
    }

    private static void check(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }
}
