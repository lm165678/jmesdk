/*
 * Copyright (c) 2009-2026 jMonkeyEngine All rights reserved. <p/>
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice,
 * this list of conditions and the following disclaimer. <p/> * Redistributions
 * in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other
 * materials provided with the distribution. <p/> * Neither the name of
 * 'jMonkeyEngine' nor the names of its contributors may be used to endorse or
 * promote products derived from this software without specific prior written
 * permission. <p/> THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
 * CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT
 * NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.jme3.gde.core.scene;

import com.jme3.app.LegacyApplication;
import com.jme3.app.StatsView;
import com.jme3.asset.AssetManager;
import com.jme3.bullet.BulletAppState;
import com.jme3.environment.EnvironmentCamera;
import com.jme3.font.BitmapFont;
import com.jme3.font.BitmapText;
import com.jme3.gde.core.Installer;
import com.jme3.gde.core.assets.AssetData;
import com.jme3.gde.core.assets.AssetDataObject;
import com.jme3.gde.core.scene.controller.AbstractCameraController;
import com.jme3.gde.core.scene.processors.WireProcessor;
import com.jme3.gde.core.scene.state.NormalViewState;
import com.jme3.gde.core.sceneexplorer.nodes.NodeUtility;
import com.jme3.gde.core.sceneviewer.SceneViewerTopComponent;
import com.jme3.gde.core.undoredo.SceneUndoRedoManager;
import com.jme3.gde.core.util.notify.MessageType;
import com.jme3.gde.core.util.notify.NotifyUtil;
import com.jme3.input.FlyByCamera;
import com.jme3.input.MouseInput;
import com.jme3.input.controls.MouseAxisTrigger;
import com.jme3.input.controls.MouseButtonTrigger;
import com.jme3.light.LightProbe;
import com.jme3.light.PointLight;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.queue.RenderQueue.Bucket;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.Spatial.CullHint;
import com.jme3.system.AppSettings;
import com.jme3.system.JmeCanvasContext;
import com.jme3.system.awt.AwtPanel;
import com.jme3.system.awt.AwtPanelsContext;
import com.jme3.system.awt.PaintMode;
import com.jme3.util.SkyFactory;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.netbeans.spi.project.LookupProvider;
import org.openide.DialogDisplayer;
import org.openide.NotifyDescriptor;
import org.openide.NotifyDescriptor.Confirmation;
import org.openide.awt.HtmlBrowser;
import org.openide.loaders.DataObject;
import org.openide.util.Exceptions;
import org.openide.util.HelpCtx;
import org.openide.util.Lookup;
import org.openide.util.NbPreferences;
import org.openide.util.lookup.Lookups;

/**
 *
 * @author normenhansen
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class SceneApplication extends LegacyApplication implements LookupProvider {

    private static final Logger logger = Logger.getLogger(SceneApplication.class.getName());
    private static boolean failMessageShown = false;
    private final ColorRGBA backgroundColor = new ColorRGBA().setAsSrgb(0.25f, 0.25f, 0.25f, 1.0f);
    private PointLight camLight;
    private static SceneApplication application;

    public static SceneApplication getApplication() {
        if (application == null) {
            application = new SceneApplication();
        }
        return application;
    }
    protected Node rootNode = new Node("Root Node") {
        @Override
        public boolean removeFromParent() {
            DialogDisplayer.getDefault().notifyLater(new NotifyDescriptor.Message("Trying to remove main RootNode!"));
            return false;
        }
    };
    protected Node guiNode = new Node("Gui Node");
    private Node statsGuiNode = new Node("Stats Gui Node");
    protected Node toolsNode = new Node("Tools Node");
    private SceneCameraController camController;
    private AbstractCameraController activeCamController = null;

    protected float secondCounter = 0.0f;
    protected BitmapText fpsText;
    protected StatsView statsView;
    protected FlyByCamera flyCam;
    protected boolean showSettings = true;
    private volatile SceneRequest currentSceneRequest;
    private ConcurrentLinkedQueue<SceneListener> listeners = new ConcurrentLinkedQueue<>();
    private ScenePreviewProcessor previewProcessor;
    private ApplicationLogHandler logHandler = new ApplicationLogHandler();
    private WireProcessor wireProcessor;
    private String lastError = "";
    private boolean started = false;
    private boolean initFailed = false;
    private AwtPanel panel;
    private ViewPort overlayView;
    private ViewPort editorToolsView;
    private EditorToolDepth editorToolDepth;
    boolean useCanvas = false;
    private BulletAppState physicsState;
    private Thread thread;
    private NodeSyncAppState nodeSync;
    private FakeApplication fakeApp;
    private com.jme3.light.SkyLight pbrLightProbe;
    private Spatial pbrSky;
    private com.jme3.light.SkyLight thumbnailLightProbe;

    private volatile boolean gBufferVisible;
    private com.jme3.gde.core.scene.processors.GBufferPreview gBufferPreview;

    public boolean isGBufferVisible() {
        return gBufferVisible;
    }

    /** EDT-safe toggle. A diagnostic processor copies real deferred buffers on GPU;
     * disabling releases them. Forward displays black placeholders; rendering paths
     * are never changed by this toggle. No scene data is saved.
     * 第一步：Visualize 勾选 GBuffer；第二步：取消勾选恢复普通预览。
     */
    public void setGBufferVisible(boolean visible) {
        enqueue(() -> {
            if (gBufferVisible == visible) {
                return null;
            }
            gBufferVisible = visible;
            if (visible) {
                gBufferPreview = new com.jme3.gde.core.scene.processors.GBufferPreview(assetManager);
                viewPort.addProcessor(gBufferPreview);
            } else if (gBufferPreview != null) {
                viewPort.removeProcessor(gBufferPreview);
                gBufferPreview = null;
            }
            return null;
        });
    }

    private final GiPreview giPreview = new GiPreview();
    private volatile boolean giVolumesVisible = true;
    private volatile boolean giPreviewEnabled = true;

    /** Last GI preview diagnostic; an empty string means no atlas error. */
    /** Render-thread diagnostic borrow; never retain/dispose across frames.
     * Includes streamed external data. Returns null until the payload is resident.
     */
    public com.jme3.environment.gi.GiProbeData getGiProbePreviewData(
            com.jme3.environment.gi.GiProbeVolume volume) {
        return giPreview.getProbeData(volume);
    }

    public String getGiPreviewStatus() {
        return giPreview.getStatus();
    }

    /** Preview-only helper visibility, independent of GI lighting; default true. */
    public boolean isGiVolumesVisible() {
        return giVolumesVisible;
    }

    /** EDT-safe; hide/show volume bounds and probe markers without changing the scene. */
    public void setGiVolumesVisible(boolean visible) {
        giVolumesVisible = visible;
    }

    /** Whether completed, valid GI bakes are applied to preview materials (default true). */
    public boolean isGiPreviewEnabled() {
        return giPreviewEnabled;
    }

    /**
     * EDT-safe preview toggle. Parameters are restored after each rendered frame,
     * so saving a scene preserves baked volume data without editor atlas bindings.
     * <pre>
     * // 第一步：添加 GI Volume，设置 Coverage/Probe counts 并点击 Bake & Apply。
     * // 第二步：烘焙成功后自动预览；关闭辅助线不影响光照。
     * // 第三步：关闭 Apply baked GI 对比原始光照，再开启恢复预览。
     * </pre>
     */
    public void setGiPreviewEnabled(boolean enabled) {
        giPreviewEnabled = enabled;
    }

    private volatile Spatial sdfPreviewSelection;

    /**
     * Sets the selected SDF subtree without changing scene data; null clears it.
     * May be called on the EDT; consumed after world updates on the render thread.
     * <pre>
     * 第一步：选择节点后 setSdfPreviewSelection(node)；第二步：View 选择 SDF Selected。
     * 第三步：清空选择时传 null；SDF Global 始终使用完整场景，不受选择影响。
     * </pre>
     */
    public void setSdfPreviewSelection(Spatial selected) {
        sdfPreviewSelection = selected;
    }

    /** Returns the selected subtree; consumers must reject detached/stale selections. */
    public Spatial getSdfPreviewSelection() {
        return sdfPreviewSelection;
    }

    private volatile float editorFlySpeed = Math.max(0.01f, Math.min(10000f,
            NbPreferences.forModule(SceneApplication.class).getFloat("editorFlySpeed", 10f)));

    /** Returns editor flight speed in world units/second, default 10; Shift multiplies by four. */
    public float getEditorFlySpeed() {
        return editorFlySpeed;
    }

    /**
     * Changes the preview-only flight speed; safe from the EDT, persisted for later sessions.
     * Does not change the scene camera or saved assets. Range: 0.01..10000 units/second.
     * <pre>
     * // 第一步：顶部 Speed 输入移动速度，默认 10。
     * // 第二步：按住右键使用 WASD/QZ 漫游，Shift 临时加速四倍。
     * // 第三步：设回 10 恢复默认，场景保存不会包含此编辑器选项。
     * SceneApplication.getApplication().setEditorFlySpeed(10f);
     * </pre>
     */
    public void setEditorFlySpeed(float speed) {
        if (!Float.isFinite(speed) || speed < 0.01f || speed > 10000f) {
            throw new IllegalArgumentException("Flight speed must be between 0.01 and 10000");
        }
        editorFlySpeed = speed;
        NbPreferences.forModule(SceneApplication.class).putFloat("editorFlySpeed", speed);
    }

    private Spatial previewLodTarget;
    private int previewLodLevel = -1;

    /**
     * Overrides LOD only while drawing the main preview. A Node affects all its
     * descendant geometries; a Geometry affects only itself. -1/null restores
     * automatic/original behavior. Missing levels clamp to each mesh's last LOD;
     * meshes without LOD buffers retain their base mesh. The default is disabled.
     * Changes are queued to the render thread. LOD levels and enabled LodControls
     * are restored in finally before another queued save/edit can execute, so
     * preview state is never serialized or added to undo history.
     * <pre>{@code
     * // 第一步：选择模型节点或单个 Geometry，然后预览指定级别。
     * SceneApplication.getApplication().setPreviewLod(selectedModel, 1);
     * // 第二步：同一节点内缺少该级别的模型使用其最后一级，无 LOD 的仍显示原模型。
     * // 第三步：恢复原有/自动 LOD；切换选择或关闭详情面板也会恢复。
     * SceneApplication.getApplication().setPreviewLod(null, -1);
     * }</pre>
     */
    public void setPreviewLod(Spatial target, int level) {
        if (level < -1) {
            throw new IllegalArgumentException("LOD preview level must be -1 or non-negative");
        }
        enqueue(() -> {
            previewLodTarget = target;
            previewLodLevel = level;
            return null;
        });
    }

    private final EditorPostProcessing editorPostProcessing = new EditorPostProcessing(this);

    /** Queues scene-owned settings; transient diagnostic options are never saved. */
    public void configureEditorPostProcessing(String option, double value) {
        final SceneRequest owner = currentSceneRequest;
        if (owner != null && owner.getDataObject() != null
                && !option.equals("Lit") && !option.equals("ResetExposure")) {
            // Mark before the render queue runs so an immediate close asks to save.
            if (java.awt.EventQueue.isDispatchThread()) {
                owner.getDataObject().setModified(true);
            } else {
                java.awt.EventQueue.invokeLater(() -> owner.getDataObject().setModified(true));
            }
        }
        enqueue(() -> {
            if (owner != currentSceneRequest || owner == null) {
                return null;
            }
            editorPostProcessing.configure(option, value);
            if (!option.equals("Lit") && !option.equals("ResetExposure")) {
                try {
                    editorPostProcessing.saveSettings(owner);
                    java.awt.EventQueue.invokeLater(() -> {
                        if (owner.getDataObject() != null) {
                            owner.getDataObject().setModified(true);
                        }
                    });
                } catch (java.io.IOException exception) {
                    org.openide.util.Exceptions.printStackTrace(exception);
                }
            }
            return null;
        });
    }

    /** Delivers a scene-scoped UI snapshot on EDT; stale requests are discarded. */
    public void readEditorPostProcessing(java.util.function.Consumer<java.util.Map<String, Double>> receive) {
        final SceneRequest owner = currentSceneRequest;
        enqueue(() -> {
            if (owner != currentSceneRequest) {
                return null;
            }
            var values = editorPostProcessing.options();
            java.awt.EventQueue.invokeLater(() -> {
                if (owner == currentSceneRequest) {
                    receive.accept(values);
                }
            });
            return null;
        });
    }

    private void renderPreviewFrame(float tpf) {
        // Editor-only geometry must never enter managed shadow caster queues.
        // Visit descendants as imported gizmo meshes can have explicit Cast set.
        toolsNode.depthFirstTraversal(spatial -> {
            if (spatial.getLocalShadowMode() != com.jme3.renderer.queue.RenderQueue.ShadowMode.Off) {
                spatial.setShadowMode(com.jme3.renderer.queue.RenderQueue.ShadowMode.Off);
            }
        });
        if (overlayView != null) {
            renderManager.getShadowManager(overlayView).setEnabled(false);
            for (Spatial overlay : overlayView.getScenes()) {
                overlay.depthFirstTraversal(spatial -> {
                    if (spatial.getLocalShadowMode() != com.jme3.renderer.queue.RenderQueue.ShadowMode.Off) {
                        spatial.setShadowMode(com.jme3.renderer.queue.RenderQueue.ShadowMode.Off);
                    }
                });
            }
        }
        editorPostProcessing.sync();
        // Copy final scene depth after every FPP, then draw editor-only geometry
        // in its own unfiltered viewport. Preserve occlusion for non-overlay tools.
        if (editorToolDepth != null && (viewPort.getProcessors().isEmpty()
                || viewPort.getProcessors().get(viewPort.getProcessors().size() - 1) != editorToolDepth)) {
            viewPort.removeProcessor(editorToolDepth);
            viewPort.addProcessor(editorToolDepth);
        }
        giPreview.render(this, () -> renderPreviewLodFrame(tpf));
        if (gBufferPreview != null && context.isRenderable()) {
            // The GUI target is the AWT presentation target, not the scene FPP's
            // temporary HDR framebuffer. Draw only after all viewports finish.
            gBufferPreview.drawOverlay(guiViewPort.getOutputFrameBuffer());
        }
    }

    private void renderPreviewLodFrame(float tpf) {
        Spatial target = previewLodTarget;
        if (target == null || previewLodLevel < 0
                || (target != rootNode && !target.hasAncestor(rootNode))) {
            renderManager.render(tpf, context.isRenderable());
            return;
        }
        java.util.Map<com.jme3.scene.Geometry, Integer> levels = new java.util.IdentityHashMap<>();
        java.util.Map<com.jme3.scene.control.LodControl, Boolean> controls = new java.util.IdentityHashMap<>();
        try {
            target.depthFirstTraversal(spatial -> {
                for (int i = 0; i < spatial.getNumControls(); i++) {
                    if (spatial.getControl(i) instanceof com.jme3.scene.control.LodControl control) {
                        controls.put(control, control.isEnabled());
                        control.setEnabled(false);
                    }
                }
                if (spatial instanceof com.jme3.scene.Geometry geometry
                        && geometry.getMesh() != null && geometry.getMesh().getNumLodLevels() > 0) {
                    levels.put(geometry, geometry.getLodLevel());
                    geometry.setLodLevel(Math.min(previewLodLevel, geometry.getMesh().getNumLodLevels() - 1));
                }
            });
            renderManager.render(tpf, context.isRenderable());
        } finally {
            for (var entry : levels.entrySet()) {
                int count = entry.getKey().getMesh().getNumLodLevels();
                if (count > 0) {
                    entry.getKey().setLodLevel(Math.min(entry.getValue(), count - 1));
                }
            }
            for (var entry : controls.entrySet()) {
                entry.getKey().setEnabled(entry.getValue());
            }
        }
    }

    private volatile boolean editorHelpersVisible = true;
    private Spatial.CullHint savedToolCull;
    private Spatial.CullHint savedStatsCull;
    private boolean savedOverlayEnabled;

    /**
     * Whether editor helpers are visible (default true). This is preview state,
     * not scene data. G hides the tool root, handle viewport and statistics;
     * application GUI nodes and scene lighting are unaffected. Individual grid
     * and selection settings are preserved. Called from any thread; mutations
     * are queued on the render thread. Closing the scene restores visibility.
     * <pre>
     * 第一步：在场景视口松开鼠标按钮，按 G 隐藏编辑辅助，仅预览场景。
     * 第二步：再次按 G 恢复网格、选择线框和操作柄原先的显示配置。
     * </pre>
     */
    public boolean isEditorHelpersVisible() {
        return editorHelpersVisible;
    }

    public void toggleEditorHelpers() {
        enqueue(() -> {
            setEditorHelpersVisibleInThread(!editorHelpersVisible);
            return null;
        });
    }

    private void setEditorHelpersVisibleInThread(boolean visible) {
        if (editorHelpersVisible == visible) {
            return;
        }
        if (!visible) {
            savedToolCull = toolsNode.getLocalCullHint();
            savedStatsCull = statsGuiNode.getLocalCullHint();
            savedOverlayEnabled = overlayView != null && overlayView.isEnabled();
            toolsNode.setCullHint(Spatial.CullHint.Always);
            statsGuiNode.setCullHint(Spatial.CullHint.Always);
            if (overlayView != null) {
                overlayView.setEnabled(false);
            }
        } else {
            toolsNode.setCullHint(savedToolCull);
            statsGuiNode.setCullHint(savedStatsCull);
            if (overlayView != null) {
                overlayView.setEnabled(savedOverlayEnabled);
            }
        }
        editorHelpersVisible = visible;
    }

    private volatile int previewFrameLimit = 60;
    private volatile double measuredPreviewFps;
    private long fpsSampleStart;
    private int fpsSampleFrames;

    /**
     * Sets the editor preview limit without recreating the GL context. Default 60;
     * -1 disables the software cap. Renderer VSync is disabled at startup; driver
     * overrides/AWT presentation may still limit FPS. Hidden previews still throttle.
     * FPS includes editor rendering and AWT transfer, not isolated GPU throughput.
     * Safe from Swing: settings changes are queued on the render thread.
     * <pre>{@code
     * // 第一步：工具栏帧率按钮选择 30 / 60 / 120 / Unlimited。
     * SceneApplication.getApplication().setPreviewFrameLimit(-1);
     * // 第二步：保持预览可见，读取至少半秒统计窗口的实际帧率。
     * double fps = SceneApplication.getApplication().getMeasuredPreviewFps();
     * // 第三步：测试结束恢复默认限帧；此设置不写入场景文件。
     * SceneApplication.getApplication().setPreviewFrameLimit(60);
     * }</pre>
     */
    public void setPreviewFrameLimit(int limit) {
        if (limit != -1 && limit != 30 && limit != 60 && limit != 120) {
            throw new IllegalArgumentException("Preview limit must be -1, 30, 60 or 120");
        }
        enqueue(() -> {
            settings.setFrameRate(limit);
            context.setSettings(settings);
            previewFrameLimit = limit;
            fpsSampleFrames = 0;
            fpsSampleStart = System.nanoTime();
            return null;
        });
    }

    private volatile int hiddenLightGizmos;

    /** Editor-only, session-wide visibility; actual lights and saved scenes are unchanged.
     * Defaults to visible for every type. Safe to call from EDT.
     * <pre>{@code
     * // 第一步：在 Visualize 中关闭点光源组件，不关闭实际照明。
     * app.setLightGizmosVisible(com.jme3.light.Light.Type.Point, false);
     * // 第二步：恢复显示；场景切换保留本次会话的选择。
     * app.setLightGizmosVisible(com.jme3.light.Light.Type.Point, true);
     * }</pre>
     */
    public synchronized void setLightGizmosVisible(com.jme3.light.Light.Type type, boolean visible) {
        int bit = 1 << type.ordinal();
        hiddenLightGizmos = visible ? hiddenLightGizmos & ~bit : hiddenLightGizmos | bit;
    }

    public boolean isLightGizmosVisible(com.jme3.light.Light.Type type) {
        return (hiddenLightGizmos & (1 << type.ordinal())) == 0;
    }

    /** Snapshot used to avoid traversing editor markers when nothing changed. */
    public int getHiddenLightGizmos() {
        return hiddenLightGizmos;
    }

    public int getPreviewFrameLimit() {
        return previewFrameLimit;
    }

    public double getMeasuredPreviewFps() {
        return measuredPreviewFps;
    }

    public SceneApplication() {
        // Initialize Java desktop DPI handling before SDL creates its native window.
        // Keep the OS per-monitor scale; never force a fixed 100% or 125% override.
        java.awt.Toolkit.getDefaultToolkit();
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            var configuration = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice().getDefaultConfiguration();
            logger.log(Level.INFO, "AWT desktop transform before renderer startup: {0}",
                    configuration.getDefaultTransform());
        }
        com.jme3.gde.core.renderdoc.RenderDocSupport.initializeBeforeGraphics();
        Logger.getLogger("com.jme3").addHandler(logHandler);
        useCanvas = "true".equals(NbPreferences.forModule(Installer.class).get("use_lwjgl_canvas", "false"));
        Logger.getLogger("com.jme3.renderer.opengl.TextureUtil").setLevel(Level.SEVERE);
        try {
            SdkNativeLibraries.configure();
            AppSettings newSetting = new AppSettings(true);
            newSetting.setFrameRate(60);
            // The preview cap is controlled by the toolbar, not monitor refresh.
            // Driver overrides and AWT presentation can still bound observed FPS.
            newSetting.setVSync(false);
            newSetting.setGammaCorrection(true);
            // RenderDoc injection does not implicitly enable engine debug markers.
            // Enable FrameGraph groups only while the configured integration is active.
            newSetting.setGraphicsDebug(com.jme3.gde.core.renderdoc.RenderDocSupport.available());
            newSetting.setRenderer(com.jme3.gde.core.scene.PreviewPanelsContext.previewRenderer());
            if (!useCanvas) {
                newSetting.setCustomRenderer(PreviewPanelsContext.class);
            }
            setSettings(newSetting);

            setPauseOnLostFocus(false);

            if (useCanvas) {
                createCanvas();
                startCanvas(true);
            }
            fakeApp = new FakeApplication(rootNode, guiNode, assetManager, cam);
            nodeSync = new NodeSyncAppState();
            stateManager.attach(nodeSync);
            if (!useCanvas) {
                start();
            }
        } catch (Exception | Error e) {
            showStartupErrorMessage(e);
        } finally {
        }
    }

    public Component getMainPanel() {
        if (useCanvas) {
            return ((JmeCanvasContext) getContext()).getCanvas();
        } else {
            if (panel == null) {
                panel = ((AwtPanelsContext) getContext()).createPanel(PaintMode.Accelerated, true);
                ((AwtPanelsContext) getContext()).setInputSource(panel);
                attachPanel();
            }
            return panel;
        }
    }

    private void attachPanel() {
        enqueue(() -> {
            panel.attachTo(true, viewPort, editorToolsView, overlayView, guiViewPort);
            return null;
        });
    }

    public ViewPort getOverlayView() {
        return overlayView;
    }

    private void loadFPSText() {
        BitmapFont font = assetManager.loadFont("Interface/Fonts/Default.fnt");

        fpsText = new BitmapText(font);
        fpsText.setSize(font.getCharSet().getRenderedSize());
        fpsText.setLocalTranslation(0, fpsText.getLineHeight(), 0);
        fpsText.setText("Frames per second");
        statsGuiNode.attachChild(fpsText);
    }

    public void loadStatsView() {
        statsView = new StatsView("Statistics View", assetManager, renderer.getStatistics());
        // move it up so it appears above fps text
        statsView.setLocalTranslation(0, fpsText.getLineHeight(), 0);
        statsGuiNode.attachChild(statsView);
    }

    @Override
    public void initialize() {
        if (!initFailed) {
            try {
                super.initialize();
                thread = Thread.currentThread();
                fakeApp.setAudioRenderer(audioRenderer);
                fakeApp.startFakeApp();
                {
                    editorToolsView = getRenderManager().createMainView("Editor tools", cam);
                    editorToolsView.setClearFlags(false, false, false);
                    editorToolsView.setRenderPath(com.jme3.renderer.RenderPath.Forward);
                    renderManager.getShadowManager(editorToolsView).setEnabled(false);
                    editorToolDepth = new EditorToolDepth(editorToolsView);
                    overlayView = getRenderManager().createMainView("Overlay", cam);
                    overlayView.setClearFlags(false, true, false);
                    guiViewPort.setClearFlags(false, false, false);
                }
                
                // Default inherited scene geometry participates in engine-managed shadows.
                // Explicit per-spatial Off/Cast/Receive settings remain authoritative.
                rootNode.setShadowMode(com.jme3.renderer.queue.RenderQueue.ShadowMode.CastAndReceive);
                renderManager.getShadowManager(viewPort).setEnabled(true);
                viewPort.setBackgroundColor(backgroundColor);
                //create camera controller
                camController = new SceneCameraController(cam, inputManager);
                //create preview view

                previewProcessor = new ScenePreviewProcessor();
                previewProcessor.setupPreviewView();

                camLight = new PointLight();
                camLight.setCastShadow(false);
                camLight.setColor(ColorRGBA.White);
                guiNode.setQueueBucket(Bucket.Gui);
                guiNode.setCullHint(CullHint.Never);
                loadFPSText();
                loadStatsView();
                viewPort.attachScene(rootNode);
                editorToolsView.attachScene(toolsNode);
                guiViewPort.attachScene(guiNode);
                cam.setLocation(new Vector3f(0, 0, 10));
                getStateManager().attach(new EnvironmentCamera());

                wireProcessor = new WireProcessor(assetManager);

                inputManager.addMapping("MouseAxisX", new MouseAxisTrigger(MouseInput.AXIS_X, false));
                inputManager.addMapping("MouseAxisY", new MouseAxisTrigger(MouseInput.AXIS_Y, false));
                inputManager.addMapping("MouseAxisX-", new MouseAxisTrigger(MouseInput.AXIS_X, true));
                inputManager.addMapping("MouseAxisY-", new MouseAxisTrigger(MouseInput.AXIS_Y, true));
                inputManager.addMapping("MouseWheel", new MouseAxisTrigger(MouseInput.AXIS_WHEEL, false));
                inputManager.addMapping("MouseWheel-", new MouseAxisTrigger(MouseInput.AXIS_WHEEL, true));
                inputManager.addMapping("MouseButtonLeft", new MouseButtonTrigger(0));
                inputManager.addMapping("MouseButtonMiddle", new MouseButtonTrigger(2));
                inputManager.addMapping("MouseButtonRight", new MouseButtonTrigger(1));
                started = true;
            } catch (Throwable e) {
                showStartupErrorMessage(e);
                initFailed = true;
                if (fakeApp != null) {
                    fakeApp.stopFakeApp();
                }
            } finally {
            }
        }
    }

    @Override
    public void destroy() {
        giPreview.close();
        fakeApp.stopFakeApp();
        initFailed = false;
        super.destroy();
    }

    @Override
    public void update() {
        if (!started) {
            try {
                runQueuedTasks();
                getStateManager().update(0);
            } catch (Exception e) {
                logger.log(Level.INFO, "Exception calling Tasks:", e);
            }
        } else {
            try {
                super.update();
                FakeApplication fakap = fakeApp;
                if (fakap != null) {
                    fakap.runQueuedFake();
                }
                long sampleNow = System.nanoTime();
                if (fpsSampleStart == 0) {
                    fpsSampleStart = sampleNow;
                }
                fpsSampleFrames++;
                long elapsed = sampleNow - fpsSampleStart;
                if (elapsed >= 500_000_000L) {
                    measuredPreviewFps = fpsSampleFrames * 1_000_000_000.0 / elapsed;
                    fpsSampleFrames = 0;
                    fpsSampleStart = sampleNow;
                }
                float tpf = timer.getTimePerFrame();
                camLight.setPosition(cam.getLocation());
                secondCounter += tpf;
                int fps = (int) timer.getFrameRate();
                if (secondCounter >= 1.0f) {
                    fpsText.setText("Frames per second: " + fps);
                    secondCounter = 0.0f;
                }
                getStateManager().update(tpf);
                toolsNode.updateLogicalState(tpf);
                if (fakap != null) {
                    fakap.updateFake(tpf);
                    fakap.updateExternalLogicalState(rootNode, tpf);
                    fakap.updateExternalLogicalState(guiNode, tpf);
                    fakap.updateExternalGeometricState(rootNode);
                    fakap.updateExternalGeometricState(guiNode);
                } else {
                    rootNode.updateLogicalState(tpf);
                    guiNode.updateLogicalState(tpf);
                    rootNode.updateGeometricState();
                    guiNode.updateGeometricState();
                }
                toolsNode.updateGeometricState();
                if (fakap != null) {
                    fakap.renderFake();
                }
                getStateManager().render(renderManager);
                // Tool controllers may change selection bounds in render callbacks.
                // Updating their child nodes alone leaves the top-level Tools Node
                // dirty. Finalize the complete helper/HUD roots after ALL callbacks,
                // before any viewport culling. Do not tick logical controls again.
                toolsNode.updateGeometricState();
                guiNode.updateGeometricState();
                // Overlay roots are not children of rootNode/toolsNode. Finalize
                // them after every app-state callback, even on frames where a
                // tool controller is being attached, detached or reordered.
                // This updates bounds/transforms only; controls must not tick twice.
                if (overlayView != null) {
                    for (Spatial overlayRoot : overlayView.getScenes()) {
                        overlayRoot.updateGeometricState();
                    }
                }
                boolean capture = com.jme3.gde.core.renderdoc.RenderDocSupport.beginFrame();
                boolean rendered = false;
                try {
                    renderPreviewFrame(tpf);
                    rendered = true;
                } finally {
                    if (capture) {
                        com.jme3.gde.core.renderdoc.RenderDocSupport.endFrame(rendered);
                    }
                }
                getStateManager().postRender();
            } catch (NullPointerException e) {
                handleError("NullPointerException: " + e.getMessage(), e);
            } catch (Exception | Error e) {
                handleError(e.getMessage(), e);
            }
        }
    }

    //TODO: Lookup for Application
    @Override
    public Lookup createAdditionalLookup(Lookup baseContext) {
        return Lookups.fixed(getApplication());
    }

    //TODO: replace with Lookup functionality
    public void addSceneListener(SceneListener listener) {
        listeners.add(listener);
    }

    public void removeSceneListener(SceneListener listener) {
        listeners.remove(listener);
    }

    private void notifyOpen(final SceneRequest opened) {
        for (SceneListener sceneViewerListener : listeners) {
            sceneViewerListener.sceneOpened(opened);
        }
    }

    private void notifyClose(final SceneRequest closed) {
        for (SceneListener sceneViewerListener : listeners) {
            sceneViewerListener.sceneClosed(closed);
        }
    }

    public void notifyPreview(final PreviewRequest request) {
        java.awt.EventQueue.invokeLater(() -> {
            for (SceneListener sceneViewerListener : listeners) {
                sceneViewerListener.previewCreated(request);
            }
        });
    }

    public void createPreview(final PreviewRequest request) {
        previewProcessor.addRequest(request);
    }

    /**
     * method to display the node tree of a plugin (threadsafe)
     *
     * @param request
     */
    public void openScene(final SceneRequest request) {
        if (!started) {
            NotifyUtil.show("OpenGL context not started!", "Click here to go to troubleshooting web page.", MessageType.WARNING, lst, 0);
            return;
        }
        java.awt.EventQueue.invokeLater(() -> {
            if (request == null || !closeSceneOnEdt(currentSceneRequest, request)) {
                return;
            }
            currentSceneRequest = request;
            if (request.getDataNode() != null) {
                setCurrentFileNode(request.getDataNode());
            } else {
                setCurrentFileNode(null);
            }
            //TODO: handle this differently (no opened file)
            if (request.getRootNode() == null && request.getJmeNode() == null) {
                DataObject dobj = request.getDataObject();
                if (dobj != null) {
                    request.setJmeNode(NodeUtility.createNode(rootNode, dobj));
                } else {
                    request.setJmeNode(NodeUtility.createNode(rootNode, false));
                }
            }
            setHelpContext(request.getHelpCtx());
            setWindowTitle(request.getWindowTitle());
            if (request.getRequester() instanceof SceneApplication) {
                camController.enable();
            } else {
                camController.disable();
            }
            final AssetManager manager = request.getManager();
            request.setFakeApp(fakeApp);
            fakeApp.newAssetManager(manager);
            enqueue(() -> {
                if (manager != null) {
                    assetManager = manager;
                }
                Spatial model = request.getRootNode();
                //TODO: use FakeApp internal root node, don't handle model vs clean scene here
                if (model != null && model != rootNode) {
                    rootNode.attachChild(model);
                }
                if (request.getToolNode() != null) {
                    toolsNode.attachChild(request.getToolNode());
                }
                editorPostProcessing.open(request);
                request.setDisplayed(true);
                return null;
            });
            com.jme3.gde.core.sceneviewer.SceneViewerTopComponent.findInstance().resetSceneLightingButtons();
            notifyOpen(request);
        });
    }

    /**
     * method to close a scene displayed by a scene request (threadsafe)
     *
     * @param request
     */
    public void closeScene(final SceneRequest request) {
        closeScene(request, null);
    }

    private void closeScene(final SceneRequest oldRequest, final SceneRequest newRequest) {
        java.awt.EventQueue.invokeLater(() -> {
            if (oldRequest == currentSceneRequest) {
                closeSceneOnEdt(oldRequest, newRequest);
            }
        });
    }

    // Keep close/open in one EDT continuation. A modal save dialog runs a nested
    // event loop, so separately queued opening can otherwise precede old cleanup.
    private boolean closeSceneOnEdt(final SceneRequest oldRequest, final SceneRequest newRequest) {
        if (oldRequest == null) {
            return true;
        }
        if (newRequest == null || newRequest.getDataObject() != oldRequest.getDataObject()) {
            if (!checkSave(oldRequest)) {
                return false;
            }
            SceneUndoRedoManager manager = Lookup.getDefault().lookup(SceneUndoRedoManager.class);
            if (manager != null) {
                manager.discardAllEdits();
            }
        }
        currentSceneRequest = null;
        notifyClose(oldRequest);
        if (newRequest == null) {
            setCurrentFileNode(null);
            setWindowTitle("OpenGL Window");
            setHelpContext(null);
        }
        if (oldRequest.getRequester() instanceof SceneApplication) {
            camController.disable();
        }
        enableCamLight(false);
        viewPort.setBackgroundColor(backgroundColor);
        //TODO: state list is not thread safe..
        fakeApp.removeCurrentStates();
        enqueue(() -> {
            if (physicsState != null) {
                physicsState.getPhysicsSpace().removeAll(rootNode);
                getStateManager().detach(physicsState);
                physicsState = null;
            }
            //TODO: possibly dangerous (new var is created in EDT
            if (fakeApp != null) {
                fakeApp.cleanupFakeApp();
            }
            editorPostProcessing.close();
            clearScenePreviewEnvironment();
            giPreview.close();
            sdfPreviewSelection = null;
            previewLodTarget = null;
            previewLodLevel = -1;
            setEditorHelpersVisibleInThread(true);
            toolsNode.detachAllChildren();
            rootNode.detachAllChildren();
            // resetCam();
            lastError = "";
            oldRequest.setDisplayed(false);
            return null;
        });
        return true;
    }

    private boolean checkSave(SceneRequest request) {
        if ((request != null) && request.getDataObject() != null
                && request.getDataObject().isModified()) {
            final DataObject req = request.getDataObject();
            Confirmation mesg = new NotifyDescriptor.Confirmation("Scene has not been saved,\ndo you want to save it?",
                    "Not Saved",
                    NotifyDescriptor.YES_NO_CANCEL_OPTION);
            DialogDisplayer.getDefault().notify(mesg);
            if (mesg.getValue() == Confirmation.YES_OPTION) {
                try {
                    req.getLookup().lookup(AssetData.class).saveAsset();
                } catch (IOException ex) {
                    Exceptions.printStackTrace(ex);
                    return false;
                }
            } else if (mesg.getValue() == Confirmation.NO_OPTION) {
                req.setModified(false);
            } else {
                return false;
            }
        }
        if ((request != null) && (request.getDataObject() instanceof AssetDataObject)) {
            AssetDataObject obj = (AssetDataObject) request.getDataObject();
            obj.closeAsset();
        }
        return true;
    }


    private void setWindowTitle(final String string) {
        SceneViewerTopComponent.findInstance().setDisplayName(string);
    }

    private void setCurrentFileNode(final org.openide.nodes.Node node) {
        if (node == null) {
            SceneViewerTopComponent.findInstance().setActivatedNodes(new org.openide.nodes.Node[]{});
            SceneViewerTopComponent.findInstance().close();
        } else {
            SceneViewerTopComponent.findInstance().setActivatedNodes(new org.openide.nodes.Node[]{node});
            SceneViewerTopComponent.findInstance().open();
            SceneViewerTopComponent.findInstance().requestVisible();
        }
    }

    private void setHelpContext(final HelpCtx helpContext) {
        if (helpContext == null) {
            SceneViewerTopComponent.findInstance().setHelpContext(new HelpCtx("com.jme3.gde.core.sceneviewer"));
        } else {
            SceneViewerTopComponent.findInstance().setHelpContext(helpContext);
        }
    }

    public void enableCamLight(final boolean enabled) {
        enqueue(() -> {
            if (enabled) {
                rootNode.removeLight(camLight);
                rootNode.addLight(camLight);
                
            } else {
                rootNode.removeLight(camLight);
            }
            enablePBRProbe(enabled);
            return null;
        });
    }

    public void enableStats(final boolean enabled) {
        enqueue(() -> {
            if (enabled) {
                // An explicit Stats click overrides a previous G-key hide.
                statsGuiNode.setCullHint(Spatial.CullHint.Never);
                savedStatsCull = Spatial.CullHint.Never;
                statsGuiNode.depthFirstTraversal(spatial -> {
                    if (spatial instanceof com.jme3.scene.Geometry geometry) {
                        geometry.getMaterial().getAdditionalRenderState().setDepthTest(false);
                        geometry.getMaterial().getAdditionalRenderState().setDepthWrite(false);
                    }
                });
                guiNode.attachChild(statsGuiNode);
            } else {
                guiNode.detachChild(statsGuiNode);
            }
            return null;
        });
    }

    public void enableWireFrame(final boolean selected) {
        enqueue(() -> {
            if (selected) {
                viewPort.addProcessor(wireProcessor);
            } else {
                viewPort.removeProcessor(wireProcessor);
            }
            return null;
        });
    }
    
    // All preview environment state belongs to the render thread. A disabled
    // request never loads or creates lighting. Thumbnail and scene lights are
    // separate instances; queued clicks cannot affect a replacement scene.
    private com.jme3.light.SkyLight loadPreviewEnvironment() {
        Spatial environment = assetManager.loadModel("com/jme3/gde/core/sceneviewer/pbrenv.j3o");
        return com.jme3.light.SkyLight.fromEnvironment(
                (LightProbe) environment.getLocalLightList().get(0));
    }

    public void enablePBRProbe(final boolean selected) {
        final SceneRequest owner = currentSceneRequest;
        enqueue(() -> {
            if (owner != currentSceneRequest || owner == null) {
                return null;
            }
            if (selected && pbrLightProbe == null) {
                pbrLightProbe = loadPreviewEnvironment();
            }
            if (pbrLightProbe != null) {
                rootNode.removeLight(pbrLightProbe);
                if (selected) {
                    rootNode.addLight(pbrLightProbe);
                }
            }
            return null;
        });
    }

    public void enablePBRSkybox(final boolean selected) {
        final SceneRequest owner = currentSceneRequest;
        enqueue(() -> {
            if (owner != currentSceneRequest || owner == null) {
                return null;
            }
            if (selected && pbrSky == null) {
                pbrSky = SkyFactory.createSky(assetManager, "Textures/Sky/Path.hdr",
                        SkyFactory.EnvMapType.EquirectMap);
            }
            if (pbrSky != null) {
                pbrSky.removeFromParent();
                if (selected) {
                    rootNode.attachChild(pbrSky);
                }
            }
            return null;
        });
    }

    public void enablePreviewLighting(final boolean selected) {
        enqueue(() -> {
            if (selected && thumbnailLightProbe == null) {
                thumbnailLightProbe = loadPreviewEnvironment();
            }
            if (thumbnailLightProbe != null) {
                previewProcessor.previewNode.removeLight(thumbnailLightProbe);
                if (selected) {
                    previewProcessor.previewNode.addLight(thumbnailLightProbe);
                }
            }
            return null;
        });
    }

    private void clearScenePreviewEnvironment() {
        if (pbrLightProbe != null) {
            rootNode.removeLight(pbrLightProbe);
        }
        if (pbrSky != null) {
            pbrSky.removeFromParent();
        }
    }

    public void setPhysicsEnabled(final boolean enabled) {
        enqueue(() -> {
            if (enabled) {
                if (physicsState == null) {
                    physicsState = new BulletAppState();
                    getStateManager().attach(physicsState);
                    physicsState.getPhysicsSpace().addAll(rootNode);
                }
            } else {
                if (physicsState != null) {
                    physicsState.getPhysicsSpace().removeAll(rootNode);
                    getStateManager().detach(physicsState);
                    physicsState = null;
                }
            }
            return null;
        });
    }

    /**
     * @return the currentSceneRequest
     */
    public SceneRequest getCurrentSceneRequest() {
        return currentSceneRequest;
    }

    @Override
    public void handleError(String msg, Throwable t) {
        if (msg == null) {
            msg = t.getMessage();
        }
        if (!started) {
            showStartupErrorMessage(t);
        } else if (lastError != null && !lastError.equals(msg)) {
            logger.log(Level.SEVERE, msg, t);
            lastError = msg;
        }
    }

    public static void showStartupErrorMessage(Throwable exception) {
        if (failMessageShown) {
            logger.log(Level.INFO, exception.getMessage(), exception);
            return;
        }
        failMessageShown = true;
        NotifyUtil.show("Error starting OpenGL context!", "Click here to go to troubleshooting web page.", MessageType.EXCEPTION, lst, 0);
        logger.log(Level.INFO, exception.getMessage(), exception);
    }

    private static final ActionListener lst = (ActionEvent e) -> {
        try {
            HtmlBrowser.URLDisplayer.getDefault().showURL(
                    URI.create("https://wiki.jmonkeyengine.org/docs/3.4/sdk/troubleshooting.html#troubleshooting-jmonkeyengine3-sdk").toURL());
        } catch (MalformedURLException ex) {
            Exceptions.printStackTrace(ex);
        }
    };

    @Override
    public RenderManager getRenderManager() {
        return renderManager;
    }

    @Override
    public ViewPort getViewPort() {
        return viewPort;
    }

    @Override
    public ViewPort getGuiViewPort() {
        return guiViewPort;
    }

    public Node getGuiNode() {
        return guiNode;
    }

    /**
     * Gets the RootNode of this Application.
     * Warning: With great Power comes great responsibility ;)
     * You shouldn't use this unless you exactly know about it's implications.
     * Adding Spatials here won't make them Serialize into the .j3o file...
     * @return
     */
    public Node getRootNode() {
        return rootNode;
    }

    public AbstractCameraController getActiveCameraController() {
        return activeCamController;
    }

    public void setActiveCameraController(AbstractCameraController activeCamController) {
        this.activeCamController = activeCamController;
    }

    public boolean isOgl() {
        return Thread.currentThread() == thread;
    }

    public boolean isAwt() {
        return java.awt.EventQueue.isDispatchThread();
    }

    public void enableNormalView(boolean selected) {
        if(selected) {
            stateManager.attach(new NormalViewState());
        } else if(stateManager.getState(NormalViewState.class) != null) {
            stateManager.detach(stateManager.getState(NormalViewState.class));
        }
    }
}
