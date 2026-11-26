package com.jme3.gde.scenecomposer;

import com.jme3.gde.core.scene.PreviewRequest;
import com.jme3.gde.core.scene.SceneApplication;
import com.jme3.gde.core.scene.SceneListener;
import com.jme3.gde.core.scene.SceneRequest;
import com.jme3.post.WorldSettings;
import java.awt.BorderLayout;
import java.awt.EventQueue;
import java.awt.GridLayout;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JPanel;

/**
 * 第一步：在 SceneComposer 展开 Baked Lightmaps。
 * 第二步：Use Lightmap mipmaps 默认勾选，使用已烘焙的 mip。
 * 第三步：取消勾选恢复原图采样，无需重烘焙。未包含 mip 的旧贴图始终采样原图。
 * 第四步：Directional indirect Lightmaps 默认勾选，使用已烘焙的方向数据。
 * 取消勾选立即恢复原 RGB Lightmap。无方向数据的接收体保持原效果。
 * 第五步：保存场景 J3O，把这两个场景级开关保存供游戏加载。
 */
final class EditorLightmapPanel extends JPanel implements SceneListener {
    private final JCheckBox useMipmaps = new JCheckBox("Use Lightmap mipmaps", WorldSettings.DEFAULT_LIGHTMAP_MIP_ENABLED);
    private final JCheckBox useDirectional = new JCheckBox("Directional indirect Lightmaps", WorldSettings.DEFAULT_DIRECTIONAL_LIGHTMAP_ENABLED);
    private SceneRequest scene;

    EditorLightmapPanel(Runnable clear) {
        super(new BorderLayout(0, 4));
        useMipmaps.setEnabled(false);
        useMipmaps.setToolTipText("Use prebaked mip levels when available. Older maps continue using their original resolution.");
        useMipmaps.addActionListener(event -> apply(useMipmaps.isSelected(),
                WorldSettings::isLightmapMipEnabled, WorldSettings::setLightmapMipEnabled));
        useDirectional.setEnabled(false);
        useDirectional.setToolTipText("Use baked indirect direction data when available. Switch off for the original RGB Lightmap.");
        useDirectional.addActionListener(event -> apply(useDirectional.isSelected(),
                WorldSettings::isDirectionalLightmapEnabled, WorldSettings::setDirectionalLightmapEnabled));
        JPanel options = new JPanel(new GridLayout(0, 1));
        options.add(useMipmaps);
        options.add(useDirectional);
        add(options, BorderLayout.NORTH);
        JButton clearLightmaps = new JButton("Clear Scene Lightmaps...");
        clearLightmaps.setToolTipText("Remove baked Lightmap references from the entire open J3O.");
        clearLightmaps.addActionListener(event -> clear.run());
        add(clearLightmaps, BorderLayout.CENTER);
    }

    private void apply(boolean enabled, Predicate<WorldSettings> current, BiConsumer<WorldSettings, Boolean> update) {
        SceneRequest request = scene;
        if (request == null) {
            return;
        }
        SceneApplication app = SceneApplication.getApplication();
        app.enqueue(() -> {
            if (app.getCurrentSceneRequest() != request) {
                return null;
            }
            var root = request.getRootNode();
            WorldSettings world = root.getControl(WorldSettings.class);
            if ((world == null || current.test(world)) == enabled) {
                return null;
            }
            if (world == null) {
                world = new WorldSettings();
                world.setDisplayOutput(WorldSettings.DisplayOutput.Application);
                root.addControl(world);
            }
            update.accept(world, enabled);
            EventQueue.invokeLater(() -> request.getDataObject().setModified(true));
            return null;
        });
    }

    private void refresh() {
        SceneApplication app = SceneApplication.getApplication();
        app.enqueue(() -> {
            SceneRequest request = app.getCurrentSceneRequest();
            boolean editable = request != null && request.getRootNode() != null && request.getDataObject() != null
                    && request.getJmeNode() != null && !request.getJmeNode().isReadOnly();
            WorldSettings world = request == null || request.getRootNode() == null
                    ? null : request.getRootNode().getControl(WorldSettings.class);
            boolean mipmaps = world == null ? WorldSettings.DEFAULT_LIGHTMAP_MIP_ENABLED : world.isLightmapMipEnabled();
            boolean directional = world == null ? WorldSettings.DEFAULT_DIRECTIONAL_LIGHTMAP_ENABLED : world.isDirectionalLightmapEnabled();
            EventQueue.invokeLater(() -> {
                if (isDisplayable() && app.getCurrentSceneRequest() == request) {
                    scene = editable ? request : null;
                    useMipmaps.setSelected(mipmaps);
                    useMipmaps.setEnabled(editable);
                    useDirectional.setSelected(directional);
                    useDirectional.setEnabled(editable);
                }
            });
            return null;
        });
    }

    @Override
    public void addNotify() {
        super.addNotify();
        SceneApplication.getApplication().addSceneListener(this);
        refresh();
    }

    @Override
    public void removeNotify() {
        SceneApplication.getApplication().removeSceneListener(this);
        scene = null;
        useMipmaps.setEnabled(false);
        useDirectional.setEnabled(false);
        super.removeNotify();
    }

    @Override
    public void sceneOpened(SceneRequest request) {
        refresh();
    }

    @Override
    public void sceneClosed(SceneRequest request) {
        refresh();
    }

    @Override
    public void previewCreated(PreviewRequest request) {
    }
}
