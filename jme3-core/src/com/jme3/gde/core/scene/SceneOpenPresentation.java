package com.jme3.gde.core.scene;

import com.jme3.gde.core.assets.AssetOpenProgress;
import com.jme3.system.awt.AwtPanel;
import java.util.concurrent.CompletableFuture;

/** One scene opening, armed after listener setup and released after presentation. */
final class SceneOpenPresentation implements AutoCloseable {
    final SceneRequest request;
    private final AssetOpenProgress progress;
    private boolean ready;
    private boolean closed;
    private CompletableFuture<Void> presented;

    SceneOpenPresentation(SceneRequest request, AssetOpenProgress progress) {
        this.request = request;
        this.progress = progress;
        progress.waitingForFirstFrame();
    }

    synchronized void attached() {
        ready = true;
    }

    // Render thread, only after a successful frame owned by request.
    synchronized void rendered(AwtPanel panel) {
        if (!ready || closed || presented != null) {
            return;
        }
        if (panel == null) {
            // The legacy direct Canvas has no AWT readback/presentation stage.
            close();
            return;
        }
        presented = panel.awaitFramePresentation();
        presented.whenComplete((ignored, failure) -> close());
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (presented != null && !presented.isDone()) {
            presented.cancel(false);
        }
        progress.close();
    }
}
