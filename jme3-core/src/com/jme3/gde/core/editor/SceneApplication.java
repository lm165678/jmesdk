package com.jme3.gde.core.editor;

import com.jme3.app.SimpleApplication;
import com.jme3.app.StatsAppState;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Node;
import com.jme3.system.AppSettings;
import com.jme3.system.awt.AwtPanel;
import com.jme3.system.awt.AwtPanelsContext;
import com.jme3.system.awt.PaintMode;
import java.awt.event.HierarchyEvent;
import java.util.concurrent.Callable;

/**
 *
 * @author normenhansen
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class SceneApplication extends SimpleApplication {

    private AwtPanel panel;
    private ViewPort overlayView;
    private volatile boolean previewShowing;
    private boolean previewWasShowing;

    public SceneApplication() {
        super(new StatsAppState());
        com.jme3.gde.core.scene.SdkNativeLibraries.configure();
        AppSettings newSetting = new AppSettings(true);
        newSetting.setFrameRate(30);
        newSetting.setRenderer(com.jme3.gde.core.scene.PreviewPanelsContext.previewRenderer());
        newSetting.setCustomRenderer(com.jme3.gde.core.scene.PreviewPanelsContext.class);
        setSettings(newSetting);
        setPauseOnLostFocus(false);
        setShowSettings(false);
    }

    public AwtPanel getPanel() {
        if (panel == null) {
             panel = ((AwtPanelsContext) getContext()).createPanel(PaintMode.Accelerated);
            panel.addHierarchyListener(event -> {
                if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                    previewShowing = panel.isShowing();
                }
            });
            previewShowing = panel.isShowing();
            ((AwtPanelsContext) getContext()).setInputSource(panel);
            attachPanel();
        }
        return panel;
    }

    /**
     * Hidden embedded previews retain their scene but suspend simulation and
     * rendering. Queued tasks still execute, including installation and cleanup.
     * AWT reports visibility on EDT; pause and timer changes belong to this
     * application's render thread. Visible split panes continue rendering.
     * <pre>{@code
     * // 第一步：在 EDT 将 getPanel() 加入编辑器，显示后自动恢复预览。
     * // 第二步：切换标签隐藏面板，自动暂停；不销毁场景或修改播放设置。
     * // 第三步：重新显示面板，从原位置继续，不累计隐藏期间的时间。
     * }</pre>
     */
    @Override
    public void update() {
        boolean showing = previewShowing;
        if (showing && !previewWasShowing) {
            timer.reset();
        }
        previewWasShowing = showing;
        paused = !showing;
        super.update();
    }

    private void attachPanel() {
        enqueue(new Callable() {
            @Override
            public Object call() throws Exception {
                panel.attachTo(true, viewPort, /*overlayView, */guiViewPort);
                return null;
            }
        });
    }

    @Override
    public Node getRootNode() {
        return super.getRootNode();
    }
    
    @Override
    public void simpleInitApp() {
        overlayView = getRenderManager().createMainView("Overlay", cam);
        overlayView.setClearFlags(false, true, false);
    }

}
