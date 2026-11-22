package com.jme3.gde.texturetools;

import java.io.IOException;
import javax.swing.AbstractAction;
import javax.swing.Action;
import org.openide.cookies.OpenCookie;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.loaders.DataNode;
import org.openide.loaders.DataObject;
import org.openide.loaders.MultiDataObject;
import org.openide.loaders.MultiFileLoader;
import org.openide.nodes.Children;
import org.openide.nodes.Node;

/**
 * Provides double-click opening in project/file nodes as well as OpenCookie for
 * the asset browser. No renderer or project AssetManager is needed for CPU preview.
 * <pre>
 * 第一步：在 SDK 项目树或资源浏览器中双击压缩纹理。
 * 第二步：默认操作通过 OpenCookie 打开统一压缩纹理编辑器。
 * 第三步：选择 mip 预览或另存编辑结果，原图及模型引用保持不变。
 * </pre>
 */
@DataObject.Registration(mimeType = CompressedTextureResolver.MIME,
        displayName = "Compressed Texture", position = 100)
public final class CompressedTextureDataObject extends MultiDataObject {
    public CompressedTextureDataObject(FileObject file, MultiFileLoader loader) throws IOException {
        super(file, loader);
        getCookieSet().add((OpenCookie) () -> {
            var local = FileUtil.toFile(getPrimaryFile());
            if (local != null) {
                TextureToolsAction.open(local.toPath());
            } else {
                javax.swing.SwingUtilities.invokeLater(() -> javax.swing.JOptionPane.showMessageDialog(null,
                        "Extract this texture to a local file before editing."));
            }
        });
    }

    @Override
    protected int associateLookup() {
        return 1;
    }

    private boolean generated() {
        var local = FileUtil.toFile(getPrimaryFile());
        if (local == null) {
            return false;
        }
        GeneratedTextures store = GeneratedTextures.containing(local.toPath());
        try {
            return store != null && store.containsGenerated(local.toPath());
        } catch (IOException failure) {
            return true;
        }
    }

    @Override
    public boolean isCopyAllowed() {
        return !generated() && super.isCopyAllowed();
    }

    @Override
    protected Node createNodeDelegate() {
        return new DataNode(this, Children.LEAF, getLookup()) {
            @Override
            public Action getPreferredAction() {
                return new AbstractAction("Open Compressed Texture") {
                    @Override
                    public void actionPerformed(java.awt.event.ActionEvent event) {
                        getLookup().lookup(OpenCookie.class).open();
                    }
                };
            }
        };
    }
}
