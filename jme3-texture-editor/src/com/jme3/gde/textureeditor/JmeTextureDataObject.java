/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.jme3.gde.textureeditor;

import com.jme3.gde.core.assets.AssetDataObject;
import com.jme3.gde.textureeditor.icons.Icons;
import java.io.IOException;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.MIMEResolver;
import org.openide.loaders.DataObject;
import org.openide.loaders.DataObjectExistsException;
import org.openide.loaders.MultiFileLoader;
import org.openide.nodes.Node;
import org.openide.nodes.CookieSet;

/**
 * Registers ordinary and HDR textures with the Swing image editor.
 * <pre>
 * 第一步：在项目树双击 PNG/JPG/JPEG/BMP/TGA，后台解码后可按原流程编辑和保存。
 * 第二步：双击 HDR/Radiance .hdr，使用 StbImageLoader 读取线性 half-float 像素。
 * 第三步：HDR 以 0 EV、Reinhard 和 sRGB 显示；该预览只读，不会把 tone-mapped
 *         像素覆盖回原始 HDR。需要编辑时请先在外部工具导出普通 LDR 图片。
 * 第四步：损坏或不支持的文件显示 IOException 对话框，不再以空图进入编辑器。
 * </pre>
 */
@MIMEResolver.ExtensionRegistration(
        displayName = "jME3 Texture",
        mimeType = "image/x-jmetexture",
        extension = {"png", "bmp", "jpg", "PNG", "BMP", "JPG", "jpeg", "JPEG",
            "tga", "TGA", "hdr", "HDR"}
)
@DataObject.Registration(displayName = "jME3 Texture", mimeType = "image/x-jmetexture", iconBase = Icons.TEXTURE_BLUE)
public class JmeTextureDataObject extends AssetDataObject {

    public JmeTextureDataObject(FileObject pf, MultiFileLoader loader) throws DataObjectExistsException, IOException {
        super(pf, loader);
        CookieSet cookies = getCookieSet();
        cookies.add((Node.Cookie) new OpenTexture(getPrimaryEntry()));

    }

}
