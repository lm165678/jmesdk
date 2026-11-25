package com.jme3.gde.texturetools;

import java.awt.Component;
import java.awt.datatransfer.Transferable;
import java.nio.file.Path;

/** SDK project bridge. Implemented by Core to avoid a texture-tools/Core cycle.
 * Call selection methods on EDT. Null means cancelled or no matching project.
 * 第一步：从 Lookup 获取此服务。第二步：chooseProject 选择已打开工程的 Assets，
 * chooseTexture 选择工程内纹理。第三步：后台读取纹理；取消不修改现有输入。
 */
public interface SkinAssetSelection {
    Path chooseProject(Component parent);
    Path chooseTexture(Component parent);
    /** Selects an existing J3M in an open project; cancel returns null. */
    default Path chooseMaterial(Component parent) {
        return null;
    }
    /** Selects a saved J3O in an open project for offline surface baking. */
    default Path chooseModel(Component parent) {
        return null;
    }
    /** Returns the open project's asset root containing the supplied file. */
    default Path projectForAsset(Path file) {
        return null;
    }
    Path textureFromTransfer(Transferable transferable) throws Exception;
}
