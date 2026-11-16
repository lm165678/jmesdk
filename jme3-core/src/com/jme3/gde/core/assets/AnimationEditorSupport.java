package com.jme3.gde.core.assets;

import static com.jme3.gde.core.assets.AnimationEditorMessages.text;

import com.jme3.anim.AnimComposer;
import com.jme3.scene.Spatial;
import java.awt.EventQueue;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.AbstractAction;
import javax.swing.Action;
import org.openide.DialogDisplayer;
import org.openide.NotifyDescriptor;
import org.openide.awt.Actions;
import org.openide.util.ContextAwareAction;
import org.openide.util.RequestProcessor;
import org.openide.util.lookup.Lookups;

/**
 * J3O editor routing. Missing preference preserves legacy scene-editor opening.
 * AUTO detects clips, not skinning: static skinned meshes remain scene assets.
 * Preference is portable String user data; it never changes runtime behavior.
 * <pre>
 * 第一步：转换源模型时选择 Auto / Scene / Animation，Auto 按动画片段识别。
 * 第二步：双击 j3o 使用已保存的偏好；右键 Open in Animation Editor 强制打开。
 * 第三步：动画编辑器中更改 Double-click 后保存，Scene 恢复场景默认打开。
 * 第四步：右键 Open in SceneComposer 始终可以打开场景，不改变偏好。
 * </pre>
 */
public final class AnimationEditorSupport {
    public static final String PREFERENCE = "jme.sdk.preferredEditor";
    public enum Preference { AUTO, SCENE, ANIMATION }

    private AnimationEditorSupport() {
    }

    public static List<Spatial> owners(Spatial root) {
        List<Spatial> owners = new ArrayList<>();
        root.depthFirstTraversal(spatial -> {
            AnimComposer composer = spatial.getControl(AnimComposer.class);
            if (composer != null && !composer.getAnimClips().isEmpty()) {
                owners.add(spatial);
            }
        });
        return owners;
    }

    record PreviewModel(Spatial model, Spatial owner) { }

    // Conservative diagnostic: unknown/Morph tracks are not labelled static.
    static boolean constantTransformClip(com.jme3.anim.AnimClip clip) {
        for (var track : clip.getTracks()) {
            if (!(track instanceof com.jme3.anim.TransformTrack t)) {
                return false;
            }
            if (changes(t.getTranslations()) || changes(t.getRotations()) || changes(t.getScales())) {
                return false;
            }
        }
        return true;
    }

    private static boolean changes(Object[] values) {
        if (values != null) {
            for (int i = 1; i < values.length; i++) {
                if (!java.util.Objects.equals(values[0], values[i])) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Beginner workflow and source metadata. Called on EDT for the loaded editor model. */
    static String guide(Spatial owner) {
        StringBuilder text = new StringBuilder(text("Animation.GuideBody"));
        text.append(text("Animation.TargetLinks")).append('\n');
        AnimComposer composer = owner.getControl(AnimComposer.class);
        text.append(text("Animation.Node")).append(owner.getName()).append('\n');
        for (var clip : composer.getAnimClips()) {
            text.append(clip.getName()).append(" — ").append(clip.getLength()).append(text("Animation.seconds"))
                    .append(clip.getTracks().length).append(text("Animation.tracks"))
                    .append(constantTransformClip(clip) ? text("Animation.static_transforms_identical_keyframes") : text("Animation.changing_or_other_track_types")).append('\n');
            for (var track : clip.getTracks()) {
                text.append("  ").append(text(track instanceof com.jme3.anim.TransformTrack
                        ? "Animation.TransformTrack" : track instanceof com.jme3.anim.MorphTrack
                        ? "Animation.MorphTrack" : "Animation.OtherTrack"));
                if (track instanceof com.jme3.anim.TransformTrack transform) {
                    Object target = transform.getTarget();
                    text.append(" → ").append(target instanceof Spatial spatial ? spatial.getName()
                            : target instanceof com.jme3.anim.Joint joint ? joint.getName() : target);
                }
                text.append('\n');
            }
        }
        return text.toString();
    }

    // Clone the complete hierarchy so sibling meshes and animation targets remain
    // available. Resolve by child indices, not names (GLTF node names may repeat).
    // Caller runs on the original scene's render thread; install on the preview thread.
    static PreviewModel cloneForPreview(Spatial root, Spatial owner) {
        List<Integer> path = new ArrayList<>();
        Spatial cursor = owner;
        while (cursor != root) {
            com.jme3.scene.Node parent = cursor.getParent();
            if (parent == null) {
                throw new IllegalArgumentException(text("Animation.Animation_owner_is_outside_the_preview_model"));
            }
            path.add(parent.getChildIndex(cursor));
            cursor = parent;
        }
        Spatial model = root.clone(true);
        cursor = model;
        for (int i = path.size() - 1; i >= 0; i--) {
            cursor = ((com.jme3.scene.Node) cursor).getChild(path.get(i));
        }
        return new PreviewModel(model, cursor);
    }

    public static boolean prefersAnimation(Spatial root) {
        String preference = root.getUserData(PREFERENCE);
        return "ANIMATION".equals(preference)
                || ("AUTO".equals(preference) && !owners(root).isEmpty());
    }

    public static Action fileAction(BinaryModelDataObject data, boolean preferred) {
        return new AbstractAction(preferred ? text("Animation.Open") : text("Animation.Open_in_Animation_Editor")) {
            @Override
            public void actionPerformed(ActionEvent event) {
                RequestProcessor.getDefault().post(() -> {
                    try {
                        Spatial root = data.loadAsset();
                        if (root == null) {
                            throw new IllegalArgumentException(text("Animation.Unable_to_load_model"));
                        }
                        boolean animation = !preferred || prefersAnimation(root);
                        EventQueue.invokeLater(() -> {
                            if (animation) {
                                open(root, root, data);
                            } else {
                                Action action = Actions.forID("SceneComposer", "com.jme3.gde.scenecomposer.OpenSceneComposer");
                                if (action instanceof ContextAwareAction contextual) {
                                    action = contextual.createContextAwareInstance(Lookups.singleton(data));
                                }
                                if (action != null) {
                                    action.actionPerformed(event);
                                }
                            }
                        });
                    } catch (Exception exception) {
                        error(exception);
                    }
                });
            }
        };
    }

    public static Action nodeAction(Spatial selected) {
        return new AbstractAction(text("Animation.Open_in_Animation_Editor")) {
            @Override
            public void actionPerformed(ActionEvent event) {
                var request = com.jme3.gde.core.scene.SceneApplication.getApplication().getCurrentSceneRequest();
                if (request == null || !(request.getDataObject() instanceof BinaryModelDataObject data)) {
                    error(new IllegalArgumentException(text("Animation.Open_an_editable_J3O_scene_first")));
                    return;
                }
                Spatial candidate = selected;
                while (owners(candidate).isEmpty() && candidate.getParent() != null) {
                    candidate = candidate.getParent();
                }
                open(candidate, request.getRootNode(), data);
            }
        };
    }

    private static void open(Spatial selected, Spatial root, BinaryModelDataObject data) {
        List<Spatial> owners = owners(selected);
        if (owners.isEmpty()) {
            error(new IllegalArgumentException(text("Animation.No_animation_clips_found_Reimport_legacy_animation_models_using_t")));
            return;
        }
        AnimationEditorTopComponent.open(data, root, owners);
    }

    static void error(Throwable exception) {
        java.util.logging.Logger.getLogger(AnimationEditorSupport.class.getName())
                .log(java.util.logging.Level.WARNING, "Animation editor", exception);
        DialogDisplayer.getDefault().notifyLater(new NotifyDescriptor.Message(
                AnimationEditorMessages.failure(exception), NotifyDescriptor.ERROR_MESSAGE));
    }

    /**
     * Save on the scene thread while the model is stable. Serialize completely before
     * touching the destination. Prefer atomic replacement; Windows readers may deny
     * rename while still allowing writes, so that case uses the NetBeans locked stream.
     * The stream fallback keeps a backup and restores it on failure; a failed restore
     * retains the backup and reports its path. It is not crash-atomic.
     */
    static void saveSnapshot(BinaryModelDataObject data, Spatial root) throws java.io.IOException {
        java.io.File file = org.openide.filesystems.FileUtil.toFile(data.getPrimaryFile());
        if (file == null) {
            throw new java.io.IOException(text("Animation.Animation_editor_requires_a_local_J3O_file"));
        }
        java.nio.file.Path destination = file.toPath();
        java.nio.file.Path temporary = java.nio.file.Files.createTempFile(destination.getParent(), ".animation-", ".tmp");
        try (org.openide.filesystems.FileLock lock = data.getPrimaryFile().lock()) {
            new com.jme3.export.binary.BinaryExporter().save(root, temporary.toFile());
            try {
                java.nio.file.Files.move(temporary, destination, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                replaceContents(data.getPrimaryFile(), lock, temporary, destination);
            } catch (java.nio.file.AccessDeniedException exception) {
                replaceContents(data.getPrimaryFile(), lock, temporary, destination);
            }
        } finally {
            java.nio.file.Files.deleteIfExists(temporary);
        }
        data.getPrimaryFile().refresh();
        data.setModified(false);
    }

    private static void replaceContents(org.openide.filesystems.FileObject file,
            org.openide.filesystems.FileLock lock, java.nio.file.Path staged,
            java.nio.file.Path destination) throws java.io.IOException {
        java.nio.file.Path backup = java.nio.file.Files.createTempFile(destination.getParent(), ".animation-backup-", ".j3o");
        boolean retainBackup = false;
        try {
            java.nio.file.Files.copy(destination, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            try {
                copyToFile(staged, file, lock);
            } catch (java.io.IOException failure) {
                try {
                    copyToFile(backup, file, lock);
                } catch (java.io.IOException restoreFailure) {
                    retainBackup = true;
                    failure.addSuppressed(restoreFailure);
                    throw new java.io.IOException(text("Animation.Save_failed_original_J3O_backup_retained_at") + backup, failure);
                }
                throw failure;
            }
        } finally {
            if (!retainBackup) {
                java.nio.file.Files.deleteIfExists(backup);
            }
        }
    }

    private static void copyToFile(java.nio.file.Path source, org.openide.filesystems.FileObject file,
            org.openide.filesystems.FileLock lock) throws java.io.IOException {
        try (java.io.OutputStream output = file.getOutputStream(lock)) {
            java.nio.file.Files.copy(source, output);
        }
    }
}
