package com.jme3.gde.scenecomposer;

import com.jme3.app.SimpleApplication;
import com.jme3.environment.gi.ProbeLightmapControl;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.export.binary.BinaryImporter;
import com.jme3.gde.core.assets.LightmapUvGenerator;
import com.jme3.gde.core.sceneexplorer.nodes.NodeUtility;
import com.jme3.gde.core.undoredo.AbstractUndoableSceneEdit;
import com.jme3.gde.core.undoredo.SceneUndoRedoManager;
import com.jme3.material.Material;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.shape.Box;
import com.jme3.system.AppSettings;
import com.jme3.system.JmeContext;
import com.jme3.system.NativeLibraryLoader;
import com.jme3.texture.Image;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import java.awt.EventQueue;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.swing.JTabbedPane;
import javax.swing.undo.UndoableEdit;

/** Isolated xatlas, Swing, undo, J3O and OpenGL regression. Never starts SDK. */
public final class LightmapUvRegenerationSmoke extends SimpleApplication {
    public static final CaptureUndo UNDO = new CaptureUndo();
    private static Mesh source;
    private static Mesh regenerated;
    private final CountDownLatch done = new CountDownLatch(1);
    private volatile Throwable failure;
    private Geometry displayed;
    private byte[] baseline;
    private int frames;

    public static final class CaptureUndo extends SceneUndoRedoManager {
        AbstractUndoableSceneEdit edit;

        @Override
        public void addEdit(Object source, UndoableEdit edit) {
            this.edit = (AbstractUndoableSceneEdit) edit;
        }
    }

    public static final class TestLookup extends org.openide.util.lookup.ProxyLookup {
        public TestLookup() {
            super(org.openide.util.lookup.Lookups.fixed(UNDO, LightmapUvRegenerationSmoke.class.getClassLoader()),
                    org.openide.util.lookup.Lookups.metaInfServices(LightmapUvRegenerationSmoke.class.getClassLoader()));
        }
    }

    private static final class Editor extends SceneEditorController {
        boolean dirty;

        Editor() {
            super(NodeUtility.createNode(new Node("Test root"), false), null);
        }

        @Override
        public void setNeedsSave(boolean dirty) {
            this.dirty = dirty;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("org.openide.util.Lookup", TestLookup.class.getName());
        if (System.getProperty("com.jme3.NativeLibraryExtractionFolder") == null) {
            NativeLibraryLoader.setCustomExtractionFolder(Files.createTempDirectory("uv-regeneration-native-").toString());
        }
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        testMesh();
        EventQueue.invokeAndWait(() -> {
            try {
                testInspectorAndUndo(output);
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        LightmapUvRegenerationSmoke app = new LightmapUvRegenerationSmoke();
        app.setShowSettings(false);
        app.setDisplayFps(false);
        app.setDisplayStatView(false);
        AppSettings settings = new AppSettings(true);
        settings.setResolution(240, 180);
        settings.setRenderer(AppSettings.LWJGL_OPENGL43);
        settings.setAudioRenderer(null);
        settings.setGammaCorrection(true);
        settings.setFrameRate(60);
        app.setSettings(settings);
        app.start(JmeContext.Type.OffscreenSurface);
        require(app.done.await(40, TimeUnit.SECONDS), "Native UV0 render timed out");
        app.stop(true);
        if (app.failure != null) {
            throw new AssertionError("Native UV0 rendering", app.failure);
        }
        EventQueue.invokeAndWait(() -> {
            for (java.awt.Window window : java.awt.Window.getWindows()) {
                window.dispose();
            }
        });
        System.out.println("LIGHTMAP_UV_REGENERATION_PASS");
        System.exit(0);
    }

    private static void testMesh() throws Exception {
        source = new Box(1, 1, 1);
        float[] old = new float[source.getVertexCount() * 2];
        Arrays.fill(old, .91f);
        source.setBuffer(VertexBuffer.Type.TexCoord2, 2, old);
        VertexBuffer lod0 = source.getBuffer(VertexBuffer.Type.Index).clone();
        VertexBuffer lod1 = new VertexBuffer(VertexBuffer.Type.Index);
        lod1.setupData(VertexBuffer.Usage.Static, 3, VertexBuffer.Format.UnsignedInt,
                BufferUtils.createIntBuffer(0, 1, 2));
        source.setLodLevels(new VertexBuffer[]{lod0, lod1});
        require(LightmapUvGenerator.generate(source) == source, "Legacy generation replaced existing UV2");
        var settings = new LightmapUvGenerator.Settings(512, 8, 4);
        regenerated = LightmapUvGenerator.regenerate(source, settings);
        require(regenerated != source && regenerated.getTriangleCount() == source.getTriangleCount(), "Invalid output mesh");
        require(regenerated.getNumLodLevels() == 2, "LOD indices lost");
        int[] before = new int[3];
        int[] after = new int[3];
        for (int triangle = 0; triangle < source.getTriangleCount(); triangle++) {
            source.getTriangle(triangle, before);
            regenerated.getTriangle(triangle, after);
            for (VertexBuffer.Type type : new VertexBuffer.Type[]{VertexBuffer.Type.Position,
                    VertexBuffer.Type.Normal, VertexBuffer.Type.TexCoord}) {
                FloatBuffer a = (FloatBuffer) source.getBuffer(type).getDataReadOnly();
                FloatBuffer b = (FloatBuffer) regenerated.getBuffer(type).getDataReadOnly();
                int components = source.getBuffer(type).getNumComponents();
                for (int corner = 0; corner < 3; corner++) {
                    for (int c = 0; c < components; c++) {
                        require(a.get(before[corner] * components + c) == b.get(after[corner] * components + c),
                                "Changed " + type + " at triangle " + triangle);
                    }
                }
            }
        }
        FloatBuffer unchanged = (FloatBuffer) source.getBuffer(VertexBuffer.Type.TexCoord2).getDataReadOnly();
        while (unchanged.hasRemaining()) {
            require(unchanged.get() == .91f, "Source UV2 changed");
        }
        checkMargins(regenerated, settings);
        Mesh different = LightmapUvGenerator.regenerate(source, new LightmapUvGenerator.Settings(1024, 2, 1));
        require(!different.getBuffer(VertexBuffer.Type.TexCoord2).getDataReadOnly().equals(
                regenerated.getBuffer(VertexBuffer.Type.TexCoord2).getDataReadOnly()), "Settings did not change layout");
        try {
            LightmapUvGenerator.regenerate(source, new LightmapUvGenerator.Settings(64, 30, 0));
            throw new AssertionError("Impossible atlas was accepted");
        } catch (java.io.IOException expected) {
            require(source.getVertexCount() == 24, "Failed generation damaged source");
        }
        System.out.println("UV_REGEN_NATIVE_XATLAS: UV0/normal/position/LOD/source preservation and texel margins passed");
    }

    private static void checkMargins(Mesh mesh, LightmapUvGenerator.Settings settings) {
        FloatBuffer uv = (FloatBuffer) mesh.getBuffer(VertexBuffer.Type.TexCoord2).getDataReadOnly();
        double limit = (settings.padding() + settings.border() + .5) / settings.resolution();
        for (int i = 0; i < uv.limit(); i++) {
            require(uv.get(i) >= limit - 1e-6 && uv.get(i) <= 1 - limit + 1e-6, "Atlas border violated");
        }
        // The six disconnected Box faces are separate charts. Each pair must
        // have an axis-aligned gap reserving both islands' requested margins.
        float[][] bounds = new float[6][4];
        int[] face = new int[3];
        for (int chart = 0; chart < 6; chart++) {
            Arrays.fill(bounds[chart], Float.POSITIVE_INFINITY);
            bounds[chart][2] = bounds[chart][3] = Float.NEGATIVE_INFINITY;
            for (int triangle = chart * 2; triangle < chart * 2 + 2; triangle++) {
                mesh.getTriangle(triangle, face);
                for (int index : face) {
                    bounds[chart][0] = Math.min(bounds[chart][0], uv.get(index * 2));
                    bounds[chart][1] = Math.min(bounds[chart][1], uv.get(index * 2 + 1));
                    bounds[chart][2] = Math.max(bounds[chart][2], uv.get(index * 2));
                    bounds[chart][3] = Math.max(bounds[chart][3], uv.get(index * 2 + 1));
                }
            }
        }
        for (int a = 0; a < 6; a++) {
            for (int b = a + 1; b < 6; b++) {
                double gap = Math.max(Math.max(bounds[a][0] - bounds[b][2], bounds[b][0] - bounds[a][2]),
                        Math.max(bounds[a][1] - bounds[b][3], bounds[b][1] - bounds[a][3]));
                require(gap * settings.resolution() >= 2 * settings.padding() - 1e-4, "Chart texel margin violated");
            }
        }
    }

    private static void testInspectorAndUndo(Path output) throws Exception {
        Geometry geometry = new Geometry("Selected box", source);
        Geometry shared = new Geometry("Other box", source);
        ProbeLightmapControl old = new ProbeLightmapControl("GI/Lightmaps/old.j3o", geometry.getWorldTransform(), 100);
        geometry.addControl(old);
        geometry.setUserData(LightmapUvGenerator.RESOLUTION_KEY, 256);
        ModelDetailTopComponent panel = new ModelDetailTopComponent();
        var tabsField = ModelDetailTopComponent.class.getDeclaredField("tabs");
        tabsField.setAccessible(true);
        JTabbedPane tabs = (JTabbedPane) tabsField.get(panel);
        require(tabs.indexOfTab("Lightmap UV") >= 0 && tabs.indexOfTab("SDF") >= 0, "Missing UV tab or original tabs");
        Editor editor = new Editor();
        Method apply = ModelDetailTopComponent.class.getDeclaredMethod("applyLightmapUv", Geometry.class, Mesh.class,
                LightmapUvGenerator.Settings.class, SceneEditorController.class);
        apply.setAccessible(true);
        apply.invoke(panel, geometry, regenerated, new LightmapUvGenerator.Settings(512, 8, 4), editor);
        require(editor.dirty && UNDO.edit != null && geometry.getControl(ProbeLightmapControl.class) == null,
                "UV edit did not invalidate Lightmap or register undo");
        require(shared.getMesh() == source && geometry.getMesh() == regenerated, "Shared mesh instance changed");
        UNDO.edit.sceneUndo();
        require(geometry.getMesh() == source && geometry.getControl(ProbeLightmapControl.class) == old
                && Integer.valueOf(256).equals(geometry.getUserData(LightmapUvGenerator.RESOLUTION_KEY)), "Undo lost old data");
        UNDO.edit.sceneRedo();
        require(geometry.getMesh() == regenerated && geometry.getControl(ProbeLightmapControl.class) == null, "Redo failed");
        Geometry plain = new Geometry("No LOD", new Box(1, 1, 1));
        Mesh noLodBefore = plain.getMesh();
        Mesh noLodAfter = LightmapUvGenerator.regenerate(noLodBefore, new LightmapUvGenerator.Settings(256, 2, 1));
        require(noLodAfter.getNumLodLevels() == 0, "Regeneration created unexpected LODs");
        apply.invoke(panel, plain, noLodAfter, new LightmapUvGenerator.Settings(256, 2, 1), editor);
        UNDO.edit.sceneUndo();
        require(plain.getMesh() == noLodBefore, "No-LOD undo failed");
        UNDO.edit.sceneRedo();
        require(plain.getMesh() == noLodAfter, "No-LOD redo failed");
        var selection = ModelDetailTopComponent.class.getDeclaredField("selected");
        var currentEditor = ModelDetailTopComponent.class.getDeclaredField("editor");
        var hasGeometry = ModelDetailTopComponent.class.getDeclaredField("hasGeometry");
        var bake = ModelDetailTopComponent.class.getDeclaredField("bake");
        for (var field : new java.lang.reflect.Field[]{selection, currentEditor, hasGeometry, bake}) {
            field.setAccessible(true);
        }
        currentEditor.set(panel, editor);
        hasGeometry.set(panel, true);
        var uvAvailable = ModelDetailTopComponent.class.getDeclaredField("hasUvGeometry");
        uvAvailable.setAccessible(true);
        uvAvailable.set(panel, true);
        selection.set(panel, new Node("Not a geometry"));
        tabs.setSelectedIndex(tabs.indexOfTab("Lightmap UV"));
        require(((javax.swing.JButton) bake.get(panel)).isEnabled(), "UV regeneration disabled for a Node");
        selection.set(panel, geometry);
        Method enabled = ModelDetailTopComponent.class.getDeclaredMethod("refreshBakeEnabled");
        enabled.setAccessible(true);
        enabled.invoke(panel);
        require(((javax.swing.JButton) bake.get(panel)).isEnabled(), "UV regeneration disabled for Geometry");
        // Keep the view-refresh callbacks inert in this isolated test.
        selection.set(panel, null);
        var bytes = new ByteArrayOutputStream();
        new BinaryExporter().save(geometry, bytes);
        Geometry saved = (Geometry) new BinaryImporter().load(new ByteArrayInputStream(bytes.toByteArray()));
        require(Integer.valueOf(512).equals(saved.getUserData(LightmapUvGenerator.RESOLUTION_KEY))
                && saved.getMesh().getBuffer(VertexBuffer.Type.TexCoord2) != null
                && saved.getControl(ProbeLightmapControl.class) == null, "J3O lost UV/settings or retained stale Lightmap");
        var snapshot = LightmapUvView.capture(saved);
        LightmapUvView.UvCanvas canvas = new LightmapUvView.UvCanvas();
        canvas.setPreferredSize(new java.awt.Dimension(560, 560));
        canvas.setSize(560, 560);
        canvas.setSnapshot(snapshot);
        BufferedImage image = new BufferedImage(560, 560, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        canvas.paint(graphics);
        graphics.dispose();
        ImageIO.write(image, "png", output.resolve("uv2-preview.png").toFile());
        int cyan = 0;
        for (int y = 24; y < 536; y++) {
            for (int x = 24; x < 536; x++) {
                int rgb = image.getRGB(x, y);
                if ((rgb & 255) > ((rgb >> 16) & 255) + 20) {
                    cyan++;
                }
            }
        }
        require(cyan > 1000, "UV2 preview did not draw islands");
        System.out.println("UV_REGEN_EDT_UNDO_J3O_PASS; preview=" + output.resolve("uv2-preview.png"));
    }

    @Override
    public void simpleInitApp() {
        try {
            flyCam.setEnabled(false);
            cam.setLocation(new Vector3f(3, 2, 3));
            cam.lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);
            ByteBuffer pixels = BufferUtils.createByteBuffer(8 * 8 * 4);
            for (int i = 0; i < 64; i++) {
                pixels.put((byte) ((i % 8 % 2 == i / 8 % 2) ? 230 : 25));
                pixels.put((byte) 90).put((byte) 180).put((byte) 255);
            }
            pixels.flip();
            Material material = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            material.setTexture("ColorMap", new Texture2D(new Image(Image.Format.RGBA8, 8, 8, pixels, ColorSpace.sRGB)));
            displayed = new Geometry("Render UV0", source);
            displayed.setMaterial(material);
            rootNode.attachChild(displayed);
        } catch (Throwable error) {
            handleError("Initialize", error);
        }
    }

    @Override
    public void simpleRender(com.jme3.renderer.RenderManager manager) {
        if (done.getCount() == 0) {
            return;
        }
        try {
            if (++frames < 4) {
                return;
            }
            ByteBuffer pixels = BufferUtils.createByteBuffer(240 * 180 * 4);
            renderer.readFrameBuffer(viewPort.getOutputFrameBuffer(), pixels);
            byte[] data = new byte[pixels.remaining()];
            pixels.get(data);
            if (baseline == null) {
                baseline = data;
                displayed.setMesh(regenerated);
            } else {
                require(Arrays.equals(baseline, data), "Regeneration changed rendered UV0 appearance");
                System.out.println("UV_REGEN_OPENGL_UV0_PIXELS_IDENTICAL");
                done.countDown();
            }
        } catch (Throwable error) {
            handleError("Render", error);
        }
    }

    @Override
    public void handleError(String message, Throwable error) {
        failure = error;
        done.countDown();
    }
}
