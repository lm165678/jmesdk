package com.jme3.gde.scenecomposer.gizmo;

import com.jme3.asset.*;
import com.jme3.environment.gi.*;
import com.jme3.gde.core.scene.PreviewPanelsContext;
import com.jme3.gde.core.scene.SceneApplication;
import com.jme3.gde.core.scene.SceneApplication.ProbeDisplayMode;
import com.jme3.material.Material;
import com.jme3.math.*;
import com.jme3.renderer.*;
import com.jme3.scene.*;
import com.jme3.system.*;
import com.jme3.system.awt.PaintMode;
import com.jme3.texture.*;
import com.jme3.util.BufferUtils;
import java.io.*;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import javax.swing.*;

/**
 * Probe display EDT and native AWT/OpenGL regression. Requires the paired engine
 * and SDK classes; run tools/verify-probe-display.ps1. No user assets are edited.
 */
public final class ProbeDisplaySmoke implements SystemListener {
    private static final Map<String, byte[]> FILES = new ConcurrentHashMap<>();
    private static final Set<String> READS = ConcurrentHashMap.newKeySet();
    private final CountDownLatch done = new CountDownLatch(1);
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private PreviewPanelsContext context;
    private Throwable failure;
    private AssetManager assets;
    private GiProbeStreamer stream;
    private GiProbeVolume volume;
    private GiProbeData fine;
    private RenderManager manager;
    private ViewPort view;
    private Node root;
    private FrameBuffer target;
    private Material material;
    private int phase;
    private int frames;

    public static final class MemoryLocator implements AssetLocator {
        public void setRootPath(String root) { }
        public AssetInfo locate(AssetManager manager, AssetKey key) {
            byte[] bytes = FILES.get(key.getName());
            if (bytes == null) {
                return null;
            }
            return new AssetInfo(manager, key) {
                public InputStream openStream() {
                    READS.add(key.getName());
                    return new ByteArrayInputStream(bytes);
                }
            };
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("org.openide.util.Lookup", TestLookup.class.getName());
        com.jme3.system.NativeLibraryLoader.setCustomExtractionFolder(
                java.nio.file.Files.createTempDirectory("probe-display-natives-").toString());
        ProbeDisplaySmoke test = new ProbeDisplaySmoke();
        SwingUtilities.invokeAndWait(() -> {
            try {
                SceneApplication app = SceneApplication.getApplication();
                require(app.getProbeDisplayMode() == ProbeDisplayMode.AUTO, "Default is not Auto");
                Class<?> type = Class.forName("com.jme3.gde.core.sceneviewer.ProbeDisplayMenu");
                Constructor<?> constructor = type.getDeclaredConstructor(SceneApplication.class);
                constructor.setAccessible(true);
                JMenu menu = (JMenu) constructor.newInstance(app);
                require(menu.getItemCount() == 3, "Missing display mode");
                for (int i : new int[]{1, 2, 0}) {
                    menu.getItem(i).doClick();
                    require(app.getProbeDisplayMode() == ProbeDisplayMode.values()[i], "Wrong selected mode");
                    int selected = 0;
                    for (int j = 0; j < 3; j++) {
                        selected += ((JRadioButtonMenuItem) menu.getItem(j)).isSelected() ? 1 : 0;
                    }
                    require(selected == 1, "Menu is not mutually exclusive");
                }
                require(app.isGiPreviewEnabled() && app.isGiVolumesVisible(), "Layer choice changed lighting/visibility");
                Class<?> panelType = Class.forName("com.jme3.gde.scenecomposer.EditorGiStreamingPanel");
                Constructor<?> panelConstructor = panelType.getDeclaredConstructor();
                panelConstructor.setAccessible(true);
                Object settingsPanel = panelConstructor.newInstance();
                Method defaults = panelType.getDeclaredMethod("showSettings", com.jme3.post.WorldSettings.GiStreaming.class);
                defaults.setAccessible(true);
                defaults.invoke(settingsPanel, com.jme3.post.WorldSettings.GiStreaming.DEFAULTS);
                for (String name : new String[]{"singleVisibility", "octahedral"}) {
                    Field field = panelType.getDeclaredField(name);
                    field.setAccessible(true);
                    require(((JCheckBox) field.get(settingsPanel)).isSelected(), "Restore defaults did not enable " + name);
                }
                Method saved = panelType.getDeclaredMethod("showSettings", com.jme3.post.WorldSettings.GiStreaming.class,
                        boolean.class, boolean.class, boolean.class, boolean.class);
                saved.setAccessible(true);
                saved.invoke(settingsPanel, com.jme3.post.WorldSettings.GiStreaming.DEFAULTS, false, true, false, false);
                for (String name : new String[]{"singleVisibility", "octahedral"}) {
                    Field field = panelType.getDeclaredField(name);
                    field.setAccessible(true);
                    require(!((JCheckBox) field.get(settingsPanel)).isSelected(), "Saved false changed: " + name);
                }
                System.out.println("PROBE_DISPLAY_EDT_PASS");
            } catch (ReflectiveOperationException error) {
                throw new AssertionError(error);
            }
        });
        com.jme3.gde.core.scene.SdkNativeLibraries.configure();
        AppSettings settings = new AppSettings(true);
        settings.setResolution(320, 240);
        settings.setAudioRenderer(null);
        settings.setGammaCorrection(false);
        settings.setGpuSceneEnabled(false);
        settings.setCustomRenderer(PreviewPanelsContext.class);
        test.context = (PreviewPanelsContext) JmeSystem.newContext(settings, JmeContext.Type.OffscreenSurface);
        var panel = test.context.createPanel(PaintMode.OnRequest);
        panel.setSize(320, 240);
        test.context.setInputSource(panel);
        test.context.setSystemListener(test);
        test.context.create(false);
        require(test.done.await(60, TimeUnit.SECONDS), "Native preview timed out");
        test.context.destroy(true);
        test.io.shutdownNow();
        if (test.failure != null) {
            throw new AssertionError(test.failure);
        }
        System.out.println("PROBE_DISPLAY_NATIVE_AWT_PASS");
        System.exit(0);
    }

    public static final class TestLookup extends org.openide.util.lookup.ProxyLookup {
        public TestLookup() {
            super(org.openide.util.lookup.Lookups.fixed(ProbeDisplaySmoke.class.getClassLoader()),
                    org.openide.util.lookup.Lookups.metaInfServices(ProbeDisplaySmoke.class.getClassLoader()));
        }
    }

    public void initialize() {
        try {
            assets = JmeSystem.newAssetManager(getClass().getResource("/com/jme3/asset/Desktop.cfg"));
            assets.registerLocator("", MemoryLocator.class);
            volume = new GiProbeVolume();
            volume.configure(new Vector3f(7, 7, 7), 8, 8, 8);
            volume.setLocalScale(1.8f, 0.8f, 1.1f);
            volume.updateGeometricState();
            GiProbeVolume collapsed = new GiProbeVolume();
            collapsed.setLocalScale(0, 1, 1);
            collapsed.updateGeometricState();
            var unbaked = GiProbeMarkers.build(collapsed, null, List.of(), ProbeDisplayMode.AUTO);
            for (int i = 0; i < unbaked.positions().remaining(); i++) {
                require(Float.isFinite(unbaked.positions().get(i)), "Collapsed authoring grid contains NaN");
            }
            float[][] sh = new float[512][27];
            float[][] moments = new float[512][8];
            for (int p = 0; p < 512; p++) {
                sh[p][0] = 1;
                Arrays.fill(moments[p], 1);
            }
            Constructor<GiProbeData> constructor = GiProbeData.class.getDeclaredConstructor(
                    GiProbeGrid.class, int.class, float[][].class, float[][].class,
                    Vector3f[].class, boolean[].class, boolean.class, float.class);
            constructor.setAccessible(true);
            fine = constructor.newInstance(volume.getBakeGrid(), 2, sh, moments, null, null, true, 1f);
            Method publish = GiProbeVolume.class.getDeclaredMethod("publish", GiProbeData.class);
            publish.setAccessible(true);
            publish.invoke(volume, fine);
            for (ProbeDisplayMode mode : ProbeDisplayMode.values()) {
                require(GiProbeMarkers.build(volume, fine, List.of(), mode).positions().remaining() == 512 * 3,
                        "Single-layer bake changed");
            }
            GiProbeStreamingData directory = GiProbeStreamingData.build(fine, "GI/preview", key ->
                    new ByteArrayOutputStream() {
                        public void close() {
                            FILES.put(key, toByteArray());
                        }
                    });
            require(volume.useStreamingData(directory, fine), "Publication rejected");
            require(directory.getFineGrid().getProbeCount() == 512, "Fine count changed");
            require(directory.getCoarseData().getGrid().getProbeCount() == 64, "Coarse count changed");
            stream = new GiProbeStreamer(io, 128L << 20, 4096, 200, context.getRenderer(), 16L << 20);
            stream.setUploadBudget(16L << 20, 100_000_000);
            stream.setFineStreaming(100, 0);
            stream.registerVolume("fixture", volume, assets);
            require(stream.getResidentFineBricks("fixture").isEmpty() && READS.isEmpty(), "Query performed I/O");
            manager = new RenderManager(context.getRenderer());
            manager.setTimer(context.getTimer());
            Camera camera = new Camera(320, 240);
            camera.setFrustumPerspective(45, 320f / 240, .1f, 100);
            camera.setLocation(new Vector3f(20, 14, 20));
            camera.lookAt(new Vector3f(6, 3, 4), Vector3f.UNIT_Y);
            view = manager.createMainView("Probe markers", camera);
            view.setClearFlags(true, true, true);
            target = new FrameBuffer(320, 240, 1);
            target.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.RGBA8));
            target.setDepthTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.Depth));
            view.setOutputFrameBuffer(target);
            root = new Node();
            view.attachScene(root);
            material = new Material(assets, "Common/MatDefs/Misc/Unshaded.j3md");
            material.setColor("Color", ColorRGBA.White);
            material.setBoolean("VertexColor", true);
            material.setFloat("PointSize", 5);
            material.getAdditionalRenderState().setDepthTest(false);
        } catch (Throwable error) {
            fail(error);
        }
    }

    public void update() {
        if (done.getCount() == 0 || stream == null) {
            return;
        }
        try {
            require(++frames < 2000, "Streaming did not converge: " + stream.getFailures());
            stream.update(new Vector3f(1, 1, 1), 0, 0, 0);
            var bricks = stream.getResidentFineBricks("fixture");
            var coarse = volume.getStreamingData().getCoarseData();
            if (phase == 0) {
                var markers = GiProbeMarkers.build(volume, coarse, bricks, ProbeDisplayMode.COARSE);
                require(markers.positions().remaining() == 64 * 3, "Wrong coarse markers");
                require(markers.movement().remaining() == 0, "Resampling falsely marked relocation");
                require(GiProbeMarkers.build(volume, coarse, bricks, ProbeDisplayMode.FINE).positions().remaining() == 512 * 3,
                        "Unloaded fine grid missing");
                draw(markers, true, false);
                stream.setFineStreaming(100, 8192);
                phase++;
            } else if (phase == 1 && bricks.size() == 8) {
                var markers = GiProbeMarkers.build(volume, coarse, bricks, ProbeDisplayMode.AUTO);
                require(markers.positions().remaining() == 512 * 3, "Halo duplicate or missing fine marker");
                require(markers.movement().remaining() == 0, "Fine probes falsely relocated");
                draw(markers, false, true);
                stream.setFineStreaming(100, 343);
                phase++;
            } else if (phase == 2 && bricks.size() == 1) {
                var markers = GiProbeMarkers.build(volume, coarse, bricks, ProbeDisplayMode.AUTO);
                require(markers.positions().remaining() == 181 * 3, "Wrong partial-coverage markers: " + markers.positions().remaining() / 3);
                draw(markers, true, true);
                stream.setFineStreaming(100, 0);
                phase++;
            } else if (phase == 3 && bricks.isEmpty()) {
                var markers = GiProbeMarkers.build(volume, coarse, bricks, ProbeDisplayMode.AUTO);
                require(markers.positions().remaining() == 64 * 3, "Coarse did not restore");
                draw(markers, true, false);
                stream.unregister("fixture");
                require(stream.getResidentFineBricks("fixture").isEmpty(), "Unregistered payload leaked");
                done.countDown();
            }
        } catch (Throwable error) {
            fail(error);
        }
    }

    private void draw(GiProbeMarkers.Buffers buffers, boolean green, boolean blue) {
        root.detachAllChildren();
        Mesh mesh = new Mesh();
        mesh.setMode(Mesh.Mode.Points);
        mesh.setBuffer(VertexBuffer.Type.Position, 3, buffers.positions());
        mesh.setBuffer(VertexBuffer.Type.Color, 4, buffers.colors());
        mesh.updateBound();
        Geometry geometry = new Geometry("Markers", mesh);
        geometry.setMaterial(material);
        geometry.setLocalScale(volume.getWorldScale());
        root.attachChild(geometry);
        root.updateLogicalState(1f / 60);
        root.updateGeometricState();
        manager.render(1f / 60, true);
        ByteBuffer pixels = BufferUtils.createByteBuffer(320 * 240 * 4);
        context.getRenderer().readFrameBufferWithFormat(target, pixels, Image.Format.RGBA8);
        int greens = 0;
        int blues = 0;
        for (int i = 0; i < pixels.capacity(); i += 4) {
            int r = pixels.get(i) & 255;
            int g = pixels.get(i + 1) & 255;
            int b = pixels.get(i + 2) & 255;
            greens += g > 100 && r < 40 && b < 40 ? 1 : 0;
            blues += b > 200 && r > 40 && r < 100 && g > 100 && g < 180 ? 1 : 0;
        }
        BufferUtils.destroyDirectBuffer(pixels);
        require(green == (greens > 0) && blue == (blues > 0), "Wrong rendered colors: " + greens + "/" + blues);
        System.out.println("PROBE_DISPLAY_PHASE_PASS " + phase + " markers=" + mesh.getVertexCount());
    }

    private void fail(Throwable error) {
        failure = error;
        done.countDown();
    }
    public void handleError(String message, Throwable error) {
        fail(error == null ? new AssertionError(message) : error);
    }
    public void destroy() {
        if (stream != null) {
            stream.close();
        }
        io.shutdownNow();
        if (manager != null) {
            manager.cleanupRenderPaths();
        }
        if (target != null) {
            target.dispose();
        }
        if (fine != null) {
            fine.dispose();
        }
    }
    public void reshape(int width, int height) { }
    public void requestClose(boolean escape) { }
    public void gainFocus() { }
    public void loseFocus() { }
}
