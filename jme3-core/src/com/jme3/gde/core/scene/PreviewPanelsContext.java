package com.jme3.gde.core.scene;

import com.jme3.system.AppSettings;
import com.jme3.system.JmeSystem;
import com.jme3.system.Platform;
import com.jme3.system.awt.AwtPanel;
import com.jme3.system.awt.AwtPanelsContext;
import com.jme3.system.awt.PaintMode;
import com.jme3.system.lwjgl.WindowsHdrAwtPanel;
import java.util.Locale;

/**
 * Desktop AWT context for custom rendering. Upstream forces OpenGL 3.2, while
 * surface capture/deferred need 3.3 and GPU tiled culling needs 4.3. Defaults to
 * 4.3 on Windows/Linux, 3.3 on macOS. Set jme.sdk.previewOpenGL=3.3 for older GPUs;
 * that mode keeps deferred rendering and uses the engine's CPU tiled fallback.
 * Context version changes require restarting the SDK; path changes are enqueued.
 * Embedded previews use an EDT-owned mouse capture adapter with ordered events
 * and edge-only recentering. Right-button release/focus loss restores the cursor.
 * <pre>{@code
 * // 第一步：SDK 的 Swing 预览使用此上下文（Windows 默认支持 GPU compute）。
 * settings.setCustomRenderer(PreviewPanelsContext.class);
 * // 第二步：旧显卡启动时添加 -J-Djme.sdk.previewOpenGL=3.3，重启后使用 CPU fallback。
 * // 第三步：在渲染线程切换路径；选择 Forward 恢复，不需要重建图形上下文。
 * application.enqueue(() -> application.getViewPort().setRenderPath(RenderPath.Forward));
 * }</pre>
 */
public final class PreviewPanelsContext extends AwtPanelsContext {
    public PreviewPanelsContext() {
        mouseInput = new PreviewMouseInput();
    }

    @Override
    protected AwtPanel newPanel(PaintMode paintMode, boolean srgb, int bitsPerPixel) {
        if (JmeSystem.getPlatform().getOs() == Platform.Os.Windows) {
            return new WindowsHdrAwtPanel(paintMode, srgb, bitsPerPixel);
        }
        return super.newPanel(paintMode, srgb, bitsPerPixel);
    }

    /** Shared renderer choice for embedded panels and the optional canvas preview. */
    public static String previewRenderer() {
        String fallback = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")
                ? "3.3" : "4.3";
        String version = System.getProperty("jme.sdk.previewOpenGL", fallback);
        if ("3.3".equals(version)) {
            return AppSettings.LWJGL_OPENGL33;
        }
        if ("4.3".equals(version)) {
            return AppSettings.LWJGL_OPENGL43;
        }
        throw new IllegalArgumentException("jme.sdk.previewOpenGL must be 3.3 or 4.3");
    }

    @Override
    public void setSettings(AppSettings settings) {
        super.setSettings(settings);
        getSettings().setRenderer(previewRenderer());
    }
}
