package com.jme3.gde.unityscene;

import com.jme3.gde.core.assets.SpatialAssetDataObject;
import java.io.IOException;
import org.openide.awt.ActionID;
import org.openide.awt.ActionReference;
import org.openide.awt.ActionReferences;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.MIMEResolver;
import org.openide.loaders.DataObject;
import org.openide.loaders.DataObjectExistsException;
import org.openide.loaders.MultiFileLoader;

/**
 * SDK node for Unity scenes/prefabs with the existing Open Model and Convert Model actions.
 * <pre>
 * 第一步：将 Unity 源资源与 .meta 一起复制到项目 Assets，保持相对路径不变。
 * 第二步：在项目树中打开 .unity 或 .prefab；本对象只负责编辑器接入，
 *         实际转换由独立模块中的 UnitySceneLoader 完成。
 * 第三步：右键 Convert Model，在现有转换对话框中设置目标路径并保存 J3O。
 * 第四步：如果提示依赖缺失，先补齐源模型、材质、纹理和对应 .meta，再重新转换；
 *         不要直接覆盖正在编辑或已手工调整过的 J3O。
 * </pre>
 */
@MIMEResolver.ExtensionRegistration(
        displayName = "#LBL_UNITY_LOADER",
        mimeType = "application/x-unity-scene",
        extension = {"unity", "prefab"}
)
@DataObject.Registration(
        mimeType = "application/x-unity-scene",
        iconBase = "com/jme3/gde/unityscene/UnityScene.png",
        displayName = "#LBL_UNITY_LOADER",
        position = 300
)
@ActionReferences({
    @ActionReference(
            path = "Loaders/application/x-unity-scene/Actions",
            id = @ActionID(
                    category = "jMonkeyPlatform",
                    id = "com.jme3.gde.core.assets.actions.ConvertModel"
            ),
            position = 100
    ),
    @ActionReference(
            path = "Loaders/application/x-unity-scene/Actions",
            id = @ActionID(
                    category = "jMonkeyPlatform",
                    id = "com.jme3.gde.core.assets.actions.OpenModel"
            ),
            position = 200
    )
})
public class UnitySceneDataObject extends SpatialAssetDataObject {
    public UnitySceneDataObject(FileObject pf, MultiFileLoader loader)
            throws DataObjectExistsException, IOException {
        super(pf, loader);
    }
}
