package com.jme3.gde.core.scene;

import com.jme3.environment.gi.GiProbeData;
import com.jme3.environment.gi.GiProbeVolume;
import java.nio.file.*;
import java.util.UUID;
import org.openide.filesystems.FileUtil;
import org.openide.loaders.DataObject;
import org.openide.util.RequestProcessor;
import org.openide.util.Exceptions;

/** Saves immutable GI revisions off-thread; publishes only after IO succeeds.
 * Old files are retained for undo/old scene revisions. Failed exports leave
 * embedded data usable. Never overwrite a revision while a loader reads it.
 */
public final class GiStreamingAssets {
    private GiStreamingAssets() {
    }

    /**
     * Starts on the render thread after baking. Export is asynchronous and marks
     * the scene modified again when the committed reference replaces embedded data.
     * Save the scene after export completes and ship GI/Streaming with the assets.
     * Old revisions remain available for undo; automatic orphan removal is unsafe.
     * <pre>{@code
     * // 第一步：烘焙完成后，在渲染线程发起后台压缩保存。
     * GiStreamingAssets.save(app, request, volume, sceneDataObject);
     * // 第二步：等待文件提交；主线程自动发布引用并将场景标记为待保存。
     * // 第三步：保存 J3O；打包时一并包含 assets/GI/Streaming。
     * }</pre>
     */
    public static void save(SceneApplication app, SceneRequest request, GiProbeVolume volume, DataObject file) {
        save(app, request, volume, file, message -> { });
    }

    /** Starts on the render thread; completion runs on the EDT after publication
     * and dirty marking, or reports why embedded data was retained. Completion
     * does not save the user's J3O. Preview can display the bake while IO runs.
     * <pre>{@code
     * // 第一步：烘焙结束后显示 Saving GI data，避免提前提示用户保存。
     * GiStreamingAssets.save(app, request, volume, file, message -> {
     *     // 第二步：EDT 回调表示导出和引用发布已结束；展示结果并恢复按钮。
     *     status.setText(message);
     * });
     * // 第三步：成功提示后保存场景，J3O 引用已提交的新版本。
     * }</pre>
     */
    public static void save(SceneApplication app, SceneRequest request, GiProbeVolume volume,
            DataObject file, java.util.function.Consumer<String> completion) {
        GiProbeData data = volume.getData();
        var folder = request.getManager().getAssetFolder();
        java.io.File local = folder == null ? null : FileUtil.toFile(folder);
        if (data == null || local == null) {
            complete(completion, data == null
                    ? "GI export skipped: bake changed or is unavailable."
                    : "Bake complete with embedded data: no local asset folder. Save the scene.");
            return;
        }
        Path root = local.toPath().toAbsolutePath().normalize();
        String key = "GI/Streaming/" + UUID.randomUUID() + ".giprobe";
        Path destination = root.resolve(key).normalize();
        if (!destination.startsWith(root)) {
            throw new IllegalArgumentException("GI export outside asset root");
        }
        RequestProcessor.getDefault().post(() -> {
            Path temporary = null;
            try {
                Files.createDirectories(destination.getParent());
                temporary = Files.createTempFile(destination.getParent(), "gi-", ".tmp");
                try (var output = Files.newOutputStream(temporary)) {
                    data.saveCompressed(output);
                }
                try {
                    Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, destination);
                }
                FileUtil.refreshFor(destination.toFile());
                app.enqueue(() -> {
                    boolean attached = volume == request.getRootNode()
                            || (request.getRootNode() instanceof com.jme3.scene.Node
                            && volume.hasAncestor((com.jme3.scene.Node) request.getRootNode()));
                    if (app.getCurrentSceneRequest() == request && attached && volume.useStreamAsset(key, data)) {
                        java.awt.EventQueue.invokeLater(() -> {
                            file.setModified(true);
                            completion.accept("Bake complete. GI data saved: " + key + ". Save the scene to keep this reference.");
                        });
                    } else {
                        complete(completion, "GI export not applied: scene or bake changed. Previous scene reference retained.");
                    }
                    return null;
                });
            } catch (java.io.IOException | RuntimeException error) {
                Exceptions.printStackTrace(error);
                complete(completion, "GI export failed: " + error.getMessage()
                        + ". The in-memory bake remains available; save the scene to retain embedded data.");
            } finally {
                if (temporary != null) {
                    try {
                        Files.deleteIfExists(temporary);
                    } catch (java.io.IOException ignored) {
                        // Never delete a published revision as temporary cleanup.
                    }
                }
            }
        });
    }
    private static void complete(java.util.function.Consumer<String> completion, String message) {
        java.awt.EventQueue.invokeLater(() -> completion.accept(message));
    }

}
