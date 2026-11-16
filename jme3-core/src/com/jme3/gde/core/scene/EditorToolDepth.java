package com.jme3.gde.core.scene;

import com.jme3.post.SceneProcessor;
import com.jme3.profile.AppProfiler;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.texture.FrameBuffer;

/**
 * Keeps scene depth for editor tools drawn after FPP. Install last on the scene
 * viewport, after any FPP; tools have a separate, unfiltered Forward viewport.
 * All calls run on the render thread. No scene asset or material is modified.
 * 第一步：场景后处理完成后复制深度；第二步：工具视口保留深度绘制；置顶轴仍使用 Overlay。
 */
final class EditorToolDepth implements SceneProcessor {
    private RenderManager manager;
    private ViewPort scene;
    private final ViewPort tools;
    private FrameBuffer source;

    EditorToolDepth(ViewPort tools) {
        this.tools = tools;
    }

    @Override
    public void initialize(RenderManager manager, ViewPort viewport) {
        this.manager = manager;
        scene = viewport;
    }

    @Override
    public void postQueue(RenderQueue queue) {
        source = scene.getOutputFrameBuffer();
    }

    @Override
    public void postFrame(FrameBuffer output) {
        FrameBuffer destination = tools.getOutputFrameBuffer();
        if (source != null && source != destination) {
            var renderer = manager.getRenderer();
            FrameBuffer previous = renderer.getCurrentFrameBuffer();
            try {
                renderer.clearClipRect();
                renderer.copyFrameBuffer(source, destination, false, true);
            } finally {
                renderer.setFrameBuffer(previous);
                scene.getCamera().onViewPortChange();
                manager.setCamera(scene.getCamera(), false,
                        scene.getRenderTargetWidth(), scene.getRenderTargetHeight());
            }
        }
    }

    @Override public boolean isInitialized() { return scene != null; }
    @Override public void reshape(ViewPort viewport, int width, int height) { }
    @Override public void preFrame(float tpf) { }
    @Override public void setProfiler(AppProfiler profiler) { }
    @Override public void cleanup() { source = null; scene = null; }
}
