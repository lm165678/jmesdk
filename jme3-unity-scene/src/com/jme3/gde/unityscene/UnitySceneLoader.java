package com.jme3.gde.unityscene;

import com.jme3.asset.AssetInfo;
import com.jme3.asset.AssetLoader;
import com.jme3.gde.core.assets.ProjectAssetManager;
import com.jme3.scene.Node;
import java.io.IOException;
import org.openide.filesystems.FileObject;

/**
 * Loads Unity .unity and .prefab YAML assets as JME scene graphs.
 * <pre>
 * // 第一步：把 Unity Assets 下要导入的资源复制到 JME 项目的 Assets 中，
 * //         必须同时保留 .meta 文件，否则 GUID 无法解析。
 * // 第二步：确认被引用的 FBX/glTF/OBJ/J3O 等模型能由当前项目 AssetManager 加载；
 * //         Unity .asset 中的二进制 Mesh 不在首版转换范围内。
 * // 第三步：双击 .unity/.prefab 预览，或在代码中调用：
 * Spatial scene = assetManager.loadModel("Scenes/Demo.unity");
 * // 第四步：检查日志中的 [Unity import] 警告；缺失引用会跳过或使用回退材质，
 * //         不会把未解析的对象伪装成转换成功。
 * // 第五步：右键文件选择 Convert Model，保存为 J3O；J3O 是转换快照，
 * //         后续修改 Unity 源文件不会自动同步，需要重新转换并保存。
 * // 第六步：坐标转换为 position=(x,y,-z)、quaternion=(-x,-y,z,w)；
 * //         源模型自身的导入坐标约定不同造成偏差时，应调整源模型而不是改写 J3O。
 * </pre>
 * Supports GameObject hierarchies, Transform/RectTransform placement, MeshRenderer,
 * basic SkinnedMeshRenderer reuse, common materials/textures, directional/point/spot
 * lights and basic prefab transform/name/activity/material overrides. The separate
 * Export Unity J3O with Controls action enables script bindings and linked prefab output.
 * Terrain, UI layouts, physics, baked lightmaps, arbitrary component properties and
 * packed metallic/smoothness textures are not converted. Camera values are metadata only.
 * Disabled renderers are omitted. Cyclic parent links fall back to the asset root with
 * a warning; files without Unity YAML documents fail instead of producing empty J3O assets.
 * <p>The loader is stateless and performs no Swing work. A ProjectAssetManager and a real
 * asset-root directory are required because GUID discovery must inspect .meta files.</p>
 */
public class UnitySceneLoader implements AssetLoader {
    @Override
    public Object load(AssetInfo info) throws IOException {
        if (!(info.getManager() instanceof ProjectAssetManager manager)
                || manager.getAssetFolder() == null) {
            throw new IOException("Unity import requires a ProjectAssetManager with an "
                    + "asset-root directory and Unity .meta files");
        }
        String path = info.getKey().getName();
        FileObject source = manager.getAssetFolder().getFileObject(path);
        if (source == null) {
            throw new IOException("Unity source is outside the project asset root: " + path);
        }
        return load(source, manager);
    }

    /** Direct entry used by the loader and the headless importer smoke test. */
    Node load(FileObject source, ProjectAssetManager manager) throws IOException {
        return load(source, manager, null);
    }

    Node load(FileObject source, ProjectAssetManager manager, UnityProjectExport export) throws IOException {
        FileObject preferred = source.getParent();
        while (preferred != null && !preferred.equals(manager.getAssetFolder())
                && !preferred.getNameExt().startsWith("UnityImport-")) {
            preferred = preferred.getParent();
        }
        UnityAssetIndex index = new UnityAssetIndex(manager.getAssetFolder(), preferred);
        return new UnitySceneBuilder(manager, index, export).build(source);
    }
}
