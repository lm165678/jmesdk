package com.jme3.gde.core.scene;

import com.jme3.asset.AssetManager;
import com.jme3.gde.core.assets.AssetOpenProgress;
import com.jme3.math.ColorRGBA;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.system.AppSettings;
import com.jme3.system.JmeContext;
import com.jme3.system.JmeSystem;
import com.jme3.system.NativeLibraryLoader;
import com.jme3.system.SystemListener;
import com.jme3.system.awt.AwtPanel;
import com.jme3.system.awt.AwtPanelsContext;
import com.jme3.system.awt.PaintMode;
import java.awt.EventQueue;
import java.awt.GridLayout;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.JFrame;
import org.openide.filesystems.FileUtil;
import org.openide.loaders.DataObject;

/** Standalone real AWT/OpenGL regression; never starts or installs the SDK. */
public final class SceneOpenPresentationSmoke implements SystemListener {
    private final CountDownLatch done = new CountDownLatch(1);
    private final AwtPanelsContext context = new AwtPanelsContext();
    private final AwtPanel[] panels = new AwtPanel[3];
    private final CompletableFuture<?>[] presented = new CompletableFuture<?>[3];
    private final ViewPort[] views = new ViewPort[3];
    private final AssetOpenProgress[] progress = new AssetOpenProgress[3];
    private final DataObject[] data = new DataObject[3];
    private final SceneOpenPresentation[] openings = new SceneOpenPresentation[3];
    private RenderManager manager;
    private JFrame window;
    private volatile Throwable failure;
    private int frames;
    private boolean armed;

    public static void main(String[] args) throws Exception {
        System.setProperty("org.openide.util.Lookup", TestLookup.class.getName());
        NativeLibraryLoader.setCustomExtractionFolder(Files.createTempDirectory("scene-present-natives-").toString());
        SceneOpenPresentationSmoke test = new SceneOpenPresentationSmoke();
        try {
            test.staleImageAndCancellation();
            test.runNative();
            System.out.println("SCENE_OPEN_FIRST_PRESENTATION_PASS");
        } finally {
            if (test.context.isCreated()) {
                test.context.destroy(true);
            }
            EventQueue.invokeAndWait(() -> {
                for (java.awt.Window window : java.awt.Window.getWindows()) {
                    window.dispose();
                }
            });
        }
        System.exit(0);
    }

    public static final class TestLookup extends org.openide.util.lookup.ProxyLookup {
        public TestLookup() {
            super(org.openide.util.lookup.Lookups.fixed(SceneOpenPresentationSmoke.class.getClassLoader()),
                    org.openide.util.lookup.Lookups.metaInfServices(SceneOpenPresentationSmoke.class.getClassLoader()));
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private void staleImageAndCancellation() throws Exception {
        EventQueue.invokeAndWait(() -> {
            try {
                AwtPanel panel = new AwtPanel(PaintMode.Repaint);
                var image = AwtPanel.class.getDeclaredField("img");
                image.setAccessible(true);
                BufferedImage black = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
                image.set(panel, black);
                CompletableFuture<Void> next = panel.awaitFramePresentation();
                var graphics = black.createGraphics();
                panel.paint(graphics);
                graphics.dispose();
                require(!next.isDone(), "Painting an old image acknowledged a new frame");
                next.cancel(false);
                require(next.isCancelled(), "Abandoned presentation was not cancelled");
                CompletableFuture<Void> cleanup = panel.awaitFramePresentation();
                panel.cleanup();
                require(cleanup.isCancelled(), "Cleanup leaked presentation request");
                DataObject data = DataObject.find(FileUtil.createMemoryFileSystem().getRoot().createData("Abandoned.j3o"));
                AssetOpenProgress old = AssetOpenProgress.begin(data);
                SceneOpenPresentation opening = new SceneOpenPresentation(null, old);
                opening.attached();
                opening.close();
                AssetOpenProgress replacement = AssetOpenProgress.begin(data);
                opening.rendered(panel);
                require(AssetOpenProgress.current(data) == replacement, "Old request closed replacement progress");
                replacement.close();
                AssetOpenProgress failed = AssetOpenProgress.begin(data);
                SceneOpenPresentation failure = new SceneOpenPresentation(null, failed);
                failure.attached();
                failure.rendered(panel);
                failure.close();
                require(AssetOpenProgress.current(data) == null, "Failed render retained progress");
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
    }

    private void runNative() throws Exception {
        AppSettings settings = new AppSettings(true);
        settings.setResolution(192, 128);
        settings.setFrameRate(60);
        settings.setGammaCorrection(true);
        context.setSettings(settings);
        context.setSystemListener(this);
        EventQueue.invokeAndWait(() -> {
            try {
                window = new JFrame("Standalone first-frame presentation check");
                window.setLayout(new GridLayout(1, 3));
                PaintMode[] modes = {PaintMode.Accelerated, PaintMode.Repaint, PaintMode.OnRequest};
                var root = FileUtil.createMemoryFileSystem().getRoot();
                for (int i = 0; i < panels.length; i++) {
                    panels[i] = context.createPanel(modes[i], true);
                    window.add(panels[i]);
                    data[i] = DataObject.find(root.createData("FirstFrame" + i + ".j3o"));
                    progress[i] = AssetOpenProgress.begin(data[i]);
                    openings[i] = new SceneOpenPresentation(null, progress[i]);
                }
                window.setSize(600, 220);
                window.setLocation(80, 80);
                window.setVisible(true);
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        context.create(false);
        require(done.await(40, TimeUnit.SECONDS), "Native presentation timed out");
        if (failure != null) {
            throw new AssertionError("Native presentation failed", failure);
        }
    }

    @Override
    public void initialize() {
        try {
            manager = new RenderManager(context.getRenderer());
            manager.setTimer(context.getTimer());
            AssetManager assets = JmeSystem.newAssetManager(getClass().getResource("/com/jme3/asset/Desktop.cfg"));
            for (int i = 0; i < panels.length; i++) {
                views[i] = manager.createMainView("First frame " + i, new Camera(192, 128));
                views[i].setClearFlags(true, true, true);
                views[i].setBackgroundColor(ColorRGBA.Black);
                panels[i].attachTo(false, views[i]);
                panels[i].setShaderSrgbOutput(assets);
            }
        } catch (Throwable error) {
            handleError("Initialization", error);
        }
    }

    @Override
    public void update() {
        if (done.getCount() == 0 || manager == null) {
            return;
        }
        try {
            frames++;
            manager.render(1f / 60, context.isRenderable());
            if (!armed && frames >= 4) {
                for (int i = 0; i < panels.length; i++) {
                    openings[i].rendered(panels[i]);
                    require(AssetOpenProgress.current(data[i]) == progress[i], "Attachment alone finished progress");
                    openings[i].attached();
                    openings[i].rendered(panels[i]);
                    presented[i] = panels[i].awaitFramePresentation();
                    require(!presented[i].isDone(), "Submission was mistaken for presentation");
                }
                armed = true;
            } else if (armed) {
                boolean all = true;
                for (int i = 0; i < panels.length; i++) {
                    all &= presented[i].isDone() && AssetOpenProgress.current(data[i]) == null;
                }
                if (all) {
                    // An entirely black, valid frame must complete normally.
                    System.out.println("AWT_PRESENTED accelerated/repaint/on-request; valid black frame accepted");
                    done.countDown();
                }
            }
        } catch (Throwable error) {
            handleError("Frame", error);
        }
    }

    @Override
    public void reshape(int width, int height) {
    }

    @Override
    public void requestClose(boolean esc) {
        handleError("Unexpected close", new IllegalStateException("Preview closed"));
    }

    @Override
    public void gainFocus() {
    }

    @Override
    public void loseFocus() {
    }

    @Override
    public void handleError(String message, Throwable error) {
        failure = error;
        done.countDown();
    }

    @Override
    public void destroy() {
        for (AwtPanel panel : panels) {
            if (panel != null) {
                panel.cleanup();
            }
        }
    }
}
