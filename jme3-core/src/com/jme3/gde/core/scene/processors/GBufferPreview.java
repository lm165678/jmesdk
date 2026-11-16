package com.jme3.gde.core.scene.processors;

import com.jme3.asset.AssetManager;
import com.jme3.font.BitmapFont;
import com.jme3.font.BitmapText;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.post.SceneProcessor;
import com.jme3.profile.AppProfiler;
import com.jme3.renderer.*;
import com.jme3.renderer.framegraph.RenderTarget;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Node;
import com.jme3.texture.*;
import com.jme3.ui.Picture;

/**
 * Opt-in GPU-only GBuffer thumbnails. Deferred batches are composited by depth
 * into owned small targets during the engine callback; transient graph textures
 * never escape it. Raw storage is deliberately not labelled as universal normal
 * or albedo data. Forward shows black placeholders; switching paths never changes
 * the enabled state or overrides the requested rendering path. All calls run on
 * the render thread. drawOverlay must run after all viewport post processing,
 * against the presentation target, before AWT reads back the frame.
 * 第一步：Visualize 勾选 GBuffer；第二步：查看五个存储附件和深度；取消勾选释放预览资源。
 */
public final class GBufferPreview implements SceneProcessor {
    private final AssetManager assets;
    private final FrameBuffer[] buffers = new FrameBuffer[6];
    private final Texture2D[] images = new Texture2D[6];
    private final Node hud = new Node("GBuffer diagnostics");
    private final Picture copy = new Picture("GBuffer GPU copy");
    private final Camera copyCamera = new Camera(192, 108);
    private Camera hudCamera;
    private Material copyMaterial;
    private RenderManager manager;
    private ViewPort view;
    private int batches;
    private BitmapText title;

    public GBufferPreview(AssetManager assets) {
        this.assets = assets;
    }

    @Override
    public void initialize(RenderManager manager, ViewPort view) {
        this.manager = manager;
        this.view = view;
        copyMaterial = new Material(assets, "com/jme3/gde/core/scene/processors/GBufferPreview.j3md");
        copy.setMaterial(copyMaterial);
        copy.setWidth(192);
        copy.setHeight(108);
        copy.updateGeometricState();
        BitmapFont font = assets.loadFont("Interface/Fonts/Default.fnt");
        title = new BitmapText(font);
        title.setSize(12);
        hud.attachChild(title);
        for (int i = 0; i < 6; i++) {
            images[i] = new Texture2D(192, 108, Image.Format.RGBA8);
            images[i].setMinFilter(Texture.MinFilter.BilinearNoMipMaps);
            images[i].setMagFilter(Texture.MagFilter.Bilinear);
            buffers[i] = new FrameBuffer(192, 108, 1);
            buffers[i].addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(images[i]));
            buffers[i].setDepthTarget(FrameBuffer.FrameBufferTarget.newTarget(Image.Format.Depth));
            Picture tile = new Picture("gbuffer" + i);
            tile.setTexture(assets, images[i], false);
            tile.getMaterial().getAdditionalRenderState().setDepthTest(false);
            tile.getMaterial().getAdditionalRenderState().setDepthWrite(false);
            hud.attachChild(tile);
            BitmapText label = new BitmapText(font);
            label.setText(i == 5 ? "Depth" : "gbuffer" + i + " (raw RGB)");
            label.setSize(12);
            hud.attachChild(label);
        }
        hud.setQueueBucket(RenderQueue.Bucket.Gui);
        hud.setCullHint(com.jme3.scene.Spatial.CullHint.Never);
        reshape(view, view.getRenderTargetWidth(), view.getRenderTargetHeight());
        view.setGBufferObserver(this::capture);
    }

    @Override
    public void preFrame(float tpf) {
        batches = 0;
        FrameBuffer previous = manager.getRenderer().getCurrentFrameBuffer();
        ColorRGBA background = view.getBackgroundColor();
        try {
            manager.getRenderer().setBackgroundColor(ColorRGBA.Black);
            for (FrameBuffer buffer : buffers) {
                manager.getRenderer().setFrameBuffer(buffer);
                manager.getRenderer().clearBuffers(true, true, false);
            }
        } finally {
            manager.getRenderer().setFrameBuffer(previous);
            manager.getRenderer().setBackgroundColor(background);
        }
    }

    private void capture(int batch, RenderTarget target) {
        FrameBuffer previous = manager.getRenderer().getCurrentFrameBuffer();
        Camera camera = manager.getCurrentCamera();
        String technique = manager.getForcedTechnique();
        try {
            manager.setForcedTechnique(null);
            manager.setCamera(copyCamera, true);
            copyMaterial.setTexture("Header", target.getColorTexture(0));
            copyMaterial.setTexture("Depth", target.getDepthTexture());
            for (int i = 0; i < 6; i++) {
                copyMaterial.setTexture("Source", i == 5 ? target.getDepthTexture() : target.getColorTexture(i));
                copyMaterial.setBoolean("DepthView", i == 5);
                manager.getRenderer().setFrameBuffer(buffers[i]);
                manager.renderGeometry(copy);
            }
            batches++;
        } finally {
            copyMaterial.clearParam("Source");
            copyMaterial.clearParam("Header");
            copyMaterial.clearParam("Depth");
            manager.setForcedTechnique(technique);
            manager.getRenderer().setFrameBuffer(previous);
            if (camera != null) {
                manager.setCamera(camera, false, view.getRenderTargetWidth(), view.getRenderTargetHeight());
            }
        }
    }

    @Override
    public void postFrame(FrameBuffer output) {
        // Drawing here can target an FPP intermediate or be overwritten by it.
    }

    /**
     * Draws after scene/FPP rendering on the render thread. Forward has six black
     * tiles; each deferred frame replaces them through the capture callback.
     * 第一步：将此处理器添加到场景视口；第二步：所有视口渲染后调用此方法。
     * 第三步：关闭调试时移除处理器释放纹理；无需恢复或改变渲染路径。
     * @param output final presentation framebuffer (null uses the main override)
     */
    public void drawOverlay(FrameBuffer output) {
        if (!isInitialized()) {
            return;
        }
        int width = view.getRenderTargetWidth();
        int height = view.getRenderTargetHeight();
        if (hudCamera.getWidth() != width || hudCamera.getHeight() != height) {
            reshape(view, width, height);
        }
        FrameBuffer previous = manager.getRenderer().getCurrentFrameBuffer();
        Camera camera = manager.getCurrentCamera();
        try {
            RenderPath requested = view.getRenderPath() == RenderPath.Inherit
                    ? manager.getRenderPath() : view.getRenderPath();
            var pipeline = manager.getRenderPipeline(view);
            String reason = requested == RenderPath.Forward ? "Forward has no GBuffer"
                    : pipeline == null ? "No deferred pipeline" : pipeline.getFallbackReason();
            if (reason.isEmpty()) {
                reason = "No supported deferred surfaces";
            }
            title.setText(batches == 0 ? "GBuffer: " + requested + " - " + reason
                    : "GBuffer: " + requested + "; " + batches + " batches; raw RGB");
            hud.updateGeometricState();
            manager.getRenderer().setFrameBuffer(output);
            manager.setCamera(hudCamera, true);
            for (var child : hud.getChildren()) {
                manager.renderScene(child, view);
            }
            manager.renderViewPortQueues(view, true);
        } finally {
            manager.getRenderer().setFrameBuffer(previous);
            if (camera != null) {
                manager.setCamera(camera, false, view.getRenderTargetWidth(), view.getRenderTargetHeight());
            }
        }
    }

    @Override
    public void reshape(ViewPort view, int width, int height) {
        hudCamera = new Camera(Math.max(1, width), Math.max(1, height));
        int w = Math.max(24, Math.min(160, (width - 32) / 3));
        int h = Math.max(14, w * 9 / 16);
        title.setLocalTranslation(8, h * 2 + 60, 0);
        for (int i = 0; i < 6; i++) {
            int x = 8 + (i % 3) * (w + 8);
            int y = 12 + (1 - i / 3) * (h + 20);
            Picture tile = (Picture) hud.getChild(1 + i * 2);
            tile.setWidth(w);
            tile.setHeight(h);
            tile.setLocalTranslation(x, y, 0);
            hud.getChild(2 + i * 2).setLocalTranslation(x, y + h + 14, 0);
        }
    }

    @Override
    public void cleanup() {
        if (view != null) {
            view.setGBufferObserver(null);
        }
        for (int i = 0; i < buffers.length; i++) {
            if (buffers[i] != null) {
                manager.getRenderer().deleteFrameBuffer(buffers[i]);
                images[i].getImage().dispose();
            }
        }
        hud.detachAllChildren();
        view = null;
    }

    @Override public boolean isInitialized() { return view != null; }
    @Override public void postQueue(RenderQueue queue) { }
    @Override public void setProfiler(AppProfiler profiler) { }
}
