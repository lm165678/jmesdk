import com.jme3.asset.AssetManager;
import com.jme3.gde.core.scene.PreviewPanelsContext;
import com.jme3.gde.materialdefinition.fileStructure.MatDefBlock;
import com.jme3.light.DirectionalLight;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.Caps;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.RenderPath;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.pipeline.FrameGraphPipeline;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.plugins.sdf.SdfVolume;
import com.jme3.scene.plugins.sdf.SurfaceBakeData;
import com.jme3.scene.shape.Box;
import com.jme3.system.AppSettings;
import com.jme3.system.JmeSystem;
import com.jme3.system.SystemListener;
import com.jme3.system.awt.AwtPanel;
import com.jme3.system.awt.PaintMode;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.util.BufferUtils;
import com.jme3.util.blockparser.BlockLanguageParser;
import com.jme3.util.blockparser.Statement;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Uses the SDK's built engine/native libraries and its actual AWT context.
 * Checks GL capabilities, FrameGraph rendering/path restoration, independent
 * viewport rendering, baked model deserialization and nested material syntax.
 * Run from PowerShell: .\tools\verify-local-engine.ps1
 * No interactive controls; exits nonzero on error. Requires desktop OpenGL 3.3.
 */
public final class SdkPreviewSmoke implements SystemListener {
    private final PreviewPanelsContext context;

    private SdkPreviewSmoke(PreviewPanelsContext context) {
        this.context = context;
    }
    private final CountDownLatch completed = new CountDownLatch(1);
    private volatile Throwable failure;
    private RenderManager manager;
    private ViewPort view;
    private ViewPort secondary;
    private Node root;
    private FrameBuffer target;
    private FrameBuffer secondaryTarget;
    private byte[] initial;
    private int frame;
    private final RenderPath[] paths = {RenderPath.Forward, RenderPath.Deferred,
        RenderPath.TiledDeferredCPU, RenderPath.TiledDeferredGPU, RenderPath.Auto, RenderPath.Forward};

    public static void main(String[] args) throws Exception {
        com.jme3.gde.core.scene.SdkNativeLibraries.configure();
        AppSettings settings = new AppSettings(true);
        settings.setResolution(320, 240);
        settings.setAudioRenderer(null);
        settings.setGammaCorrection(false);
        settings.setGpuSceneEnabled(false);
        settings.setCustomRenderer(PreviewPanelsContext.class);
        AppSettings copied = new AppSettings(false);
        copied.copyFrom(settings);
        // Follow LegacyApplication's factory route, not direct context construction.
        PreviewPanelsContext context = (PreviewPanelsContext) JmeSystem.newContext(
                copied, com.jme3.system.JmeContext.Type.OffscreenSurface);
        SdkPreviewSmoke test = new SdkPreviewSmoke(context);
        if (!PreviewPanelsContext.previewRenderer().equals(test.context.getSettings().getRenderer())) {
            throw new AssertionError("AWT renderer did not retain the requested OpenGL profile");
        }
        AwtPanel panel = test.context.createPanel(PaintMode.OnRequest);
        panel.setSize(320, 240);
        test.context.setInputSource(panel);
        test.context.setSystemListener(test);
        test.context.create(false);
        if (!test.completed.await(90, TimeUnit.SECONDS)) {
            System.err.println("SDK_PREVIEW_TIMEOUT");
            System.exit(2);
        }
        test.context.destroy(true);
        if (test.failure != null) {
            test.failure.printStackTrace();
            System.exit(1);
        }
        System.out.println("SDK_PREVIEW_SMOKE_PASS");
        System.exit(0);
    }

    @Override
    public void initialize() {
        try {
            if (!context.getRenderer().getCaps().contains(Caps.OpenGL33)) {
                throw new AssertionError("OpenGL 3.3 unavailable");
            }
            AssetManager assets = JmeSystem.newAssetManager(
                    SdkPreviewSmoke.class.getResource("/com/jme3/asset/Desktop.cfg"));
            com.jme3.bullet.PhysicsSpace physics = new com.jme3.bullet.PhysicsSpace(
                    com.jme3.bullet.PhysicsSpace.BroadphaseType.DBVT);
            physics.destroy();
            System.out.println("SDK_PHYSICS_NATIVE_PASS");
            verifyMaterialSyntax();
            for (String model : new String[]{"Tank", "Boat", "Buggy", "Tree"}) {
                Spatial loaded = assets.loadModel("Models/" + model + "/Bake" + model + ".j3o");
                SdfVolume sdf = loaded.getUserData(SdfVolume.USER_DATA_KEY);
                SurfaceBakeData surface = loaded.getUserData(SurfaceBakeData.USER_DATA_KEY);
                if (sdf == null || surface == null || !surface.isCompatibleWith(sdf)) {
                    throw new AssertionError("Missing/incompatible saved bake data: " + model);
                }
                System.out.println("SDK_BAKED_ASSET_PASS " + model);
            }
            manager = new RenderManager(context.getRenderer());
            manager.setTimer(context.getTimer());
            manager.configureGpuScene(assets, context.getSettings());
            if (!(manager.getPipeline() instanceof FrameGraphPipeline)) {
                throw new AssertionError("FrameGraph is not the default viewport pipeline");
            }
            Camera camera = new Camera(320, 240);
            camera.setFrustumPerspective(45, 320f / 240, .1f, 100);
            camera.setLocation(new Vector3f(0, 0, 5));
            camera.lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);
            view = manager.createMainView("SDK preview smoke", camera);
            view.setClearFlags(true, true, true);
            target = makeTarget();
            view.setOutputFrameBuffer(target);
            root = new Node("SDK preview root");
            Geometry box = new Geometry("Box", new Box(1, 1, 1));
            Material material = new Material(assets, "Common/MatDefs/Light/Lighting.j3md");
            material.setBoolean("UseMaterialColors", true);
            material.setColor("Diffuse", ColorRGBA.Red);
            material.setColor("Specular", ColorRGBA.Black);
            box.setMaterial(material);
            root.attachChild(box);
            DirectionalLight light = new DirectionalLight();
            light.setDirection(new Vector3f(-1, -1, -1).normalizeLocal());
            root.addLight(light);
            root.updateGeometricState();
            view.attachScene(root);
            secondary = manager.createPreView("SDK thumbnail smoke", camera.clone());
            secondary.setClearFlags(true, true, true);
            secondaryTarget = makeTarget();
            secondary.setOutputFrameBuffer(secondaryTarget);
            secondary.attachScene(root);
        } catch (Throwable error) {
            fail(error);
        }
    }

    private FrameBuffer makeTarget() {
        FrameBuffer buffer = new FrameBuffer(320, 240, 1);
        buffer.setDepthBuffer(Image.Format.Depth);
        buffer.setColorBuffer(Image.Format.RGBA8);
        return buffer;
    }

    private void verifyMaterialSyntax() throws Exception {
        String source = "MaterialDef SDK {\nMaterialParameters {\n}\n"
                + "CustomModels {\nModel 7 {\nSource Custom.glsllib\n}\n}\n"
                + "Technique {\nShadingId 7\nCustomBlock {\nNested value\n}\n}\n}";
        Statement parsed = BlockLanguageParser.parse(new ByteArrayInputStream(
                source.getBytes(StandardCharsets.UTF_8))).get(0);
        MatDefBlock edited = new MatDefBlock(parsed);
        Statement extension = edited.getContents().get(1);
        if (extension.getContents().size() != 1
                || !extension.getContents().get(0).getContents().get(0).getLine().contains("Custom.glsllib")
                || edited.getTechniques().get(0).getContents().get(1).getContents().size() != 1) {
            throw new AssertionError("Visual material parsing dropped custom nested statements");
        }
        System.out.println("SDK_MATERIAL_EXTENSION_PASS");
    }

    @Override
    public void update() {
        if (failure != null || completed.getCount() == 0) {
            return;
        }
        try {
            int stage = frame / 4;
            view.setRenderPath(paths[stage]);
            root.updateLogicalState(1f / 60);
            root.updateGeometricState();
            manager.render(1f / 60, true);
            if (frame % 4 == 3) {
                byte[] pixels = capture(target);
                int lit = 0;
                for (int i = 0; i < pixels.length; i += 4) {
                    if ((pixels[i] & 255) > 20) {
                        lit++;
                    }
                }
                if (lit < 100 || secondary.getRenderPath() != RenderPath.Inherit
                        || manager.getRenderPipeline(secondary) != null) {
                    throw new AssertionError("Blank preview or thumbnail path contamination: " + paths[stage]);
                }
                if (stage == 0) {
                    initial = pixels;
                }
                if (stage == paths.length - 1 && !Arrays.equals(initial, pixels)) {
                    throw new AssertionError("Forward restoration changed the preview image");
                }
                com.jme3.renderer.deferred.TiledDeferredProcessor pipeline = manager.getRenderPipeline(view);
                String active = pipeline == null ? "Forward" : pipeline.getActiveMode().name();
                if (paths[stage] == RenderPath.TiledDeferredGPU) {
                    String expected = context.getRenderer().getCaps().contains(Caps.OpenGL43)
                            ? "TiledDeferredGPU" : "TiledDeferredCPU";
                    if (!expected.equals(active)) {
                        throw new AssertionError("Unexpected active path: " + active + ", expected " + expected);
                    }
                }
                System.out.println("SDK_RENDER_PATH_PASS " + paths[stage] + " active=" + active + " lit=" + lit);
            }
            frame++;
            if (frame == paths.length * 4) {
                verifyDiagnostics();
                completed.countDown();
            }
        } catch (Throwable error) {
            fail(error);
        }
    }

    private void verifyDiagnostics() {
        Geometry box = (Geometry) root.getChild("Box");
        Material original = box.getMaterial();
        AssetManager assets = JmeSystem.newAssetManager(
                SdkPreviewSmoke.class.getResource("/com/jme3/asset/Desktop.cfg"));
        SdfVolume sdf = com.jme3.scene.plugins.sdf.SdfGenerator.generate(box,
                new com.jme3.scene.plugins.sdf.SdfSettings(16, 16, 16, .05f,
                        com.jme3.scene.plugins.sdf.SdfSettings.SignMode.EVEN_ODD), null);
        sdf.attachTo(box);
        box.addControl(new com.jme3.bullet.control.RigidBodyControl(
                new com.jme3.bullet.collision.shapes.BoxCollisionShape(new Vector3f(1, 1, 1)), 0));
        SurfaceBakeData surfaces = com.jme3.scene.plugins.sdf.SurfaceAttributeBaker.bake(
                box, "sdk-smoke", manager, view, 16, value -> true);
        if (surfaces.getAttributes().getResolution() != 16 || !surfaces.isCompatibleWith(sdf)) {
            throw new AssertionError("SDK GPU surface capture failed");
        }
        System.out.println("SDK_SURFACE_GPU_BAKE_PASS");
        var diagnostic = new com.jme3.gde.core.scene.processors.PreviewDebugProcessor(assets, root, System.out::println);
        view.addProcessor(diagnostic);
        diagnostic.setRenderPath(RenderPath.Deferred);
        for (var mode : com.jme3.gde.core.scene.processors.PreviewDebugProcessor.Mode.values()) {
            diagnostic.setMode(mode);
            for (int i = 0; i < 3; i++) {
                root.updateGeometricState();
                manager.render(1f / 60, true);
            }
            byte[] pixels = capture(target);
            int visible = 0;
            for (int i = 0; i < pixels.length; i += 4) {
                if ((pixels[i] & 255) + (pixels[i + 1] & 255) + (pixels[i + 2] & 255) > 30) {
                    visible++;
                }
            }
            if (visible == 0 || box.getMaterial() != original || root.getLocalCullHint() != Spatial.CullHint.Inherit) {
                throw new AssertionError("Diagnostic mode blank or failed restoration: " + mode);
            }
            if (!Arrays.equals(initial, capture(secondaryTarget))) {
                throw new AssertionError("Diagnostic contaminated secondary viewport: " + mode);
            }
            System.out.println("SDK_DIAGNOSTIC_PASS " + mode + " visible=" + visible);
        }
        diagnostic.setMode(com.jme3.gde.core.scene.processors.PreviewDebugProcessor.Mode.Lit);
        if (view.getRenderPath() != RenderPath.Deferred) {
            throw new AssertionError("Lit must restore the requested render path");
        }
        diagnostic.setRenderPath(RenderPath.Forward);
        manager.render(1f / 60, true);
        if (!Arrays.equals(initial, capture(target))) {
            throw new AssertionError("Diagnostic restoration changed saved scene appearance");
        }
        view.removeProcessor(diagnostic);
    }

    private byte[] capture(FrameBuffer buffer) {
        ByteBuffer pixels = BufferUtils.createByteBuffer(320 * 240 * 4);
        context.getRenderer().readFrameBufferWithFormat(buffer, pixels, Image.Format.RGBA8);
        byte[] result = new byte[pixels.capacity()];
        pixels.clear();
        pixels.get(result);
        BufferUtils.destroyDirectBuffer(pixels);
        return result;
    }

    private void fail(Throwable error) {
        failure = error;
        completed.countDown();
    }

    @Override
    public void handleError(String message, Throwable error) {
        fail(error == null ? new AssertionError(message) : error);
    }

    @Override
    public void destroy() {
        if (manager != null) {
            manager.cleanupRenderPaths();
        }
        if (target != null) {
            target.dispose();
        }
        if (secondaryTarget != null) {
            secondaryTarget.dispose();
        }
    }

    @Override
    public void requestClose(boolean escape) {
        fail(new AssertionError("Preview closed before verification finished"));
    }

    @Override
    public void gainFocus() {
    }

    @Override
    public void loseFocus() {
    }
}
