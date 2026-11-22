package com.jme3.gde.assetbrowser;

import com.jme3.gde.core.assets.ProjectAssetManager;
import com.jme3.gde.textureeditor.IOModule;
import com.jme3.gde.texturetools.CompressedTexture;
import com.jme3.gde.texturetools.TextureToolchain;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Locale;
import jme3tools.converters.ImageToAwt;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;

final class AssetPreviewTextures {
    private AssetPreviewTextures() {
    }

    static boolean usesImageEditor(FileObject file) {
        return switch (file.getExt().toLowerCase(Locale.ROOT)) {
            case "hdr", "tga", "png", "jpg", "jpeg", "bmp", "gif" -> true;
            default -> false;
        };
    }

    static BufferedImage read(ProjectAssetManager manager, FileObject file, String asset) throws Exception {
        String extension = file.getExt().toLowerCase(Locale.ROOT);
        if (usesImageEditor(file)) {
            return IOModule.create().load(file);
        }
        if (extension.equals("dds") || extension.equals("astc") || extension.equals("ktx")) {
            var local = FileUtil.toFile(file);
            if (local != null) {
                CompressedTexture compressed;
                try {
                    compressed = CompressedTexture.read(local.toPath());
                } catch (IOException unsupportedContainer) {
                    // Ordinary, uncompressed DDS/KTX can still be read by the engine.
                    return engineImage(manager, asset);
                }
                return compressed.preview(0, TextureToolchain.installed());
            }
        }
        return engineImage(manager, asset);
    }

    private static BufferedImage engineImage(ProjectAssetManager manager, String asset) {
        var key = new com.jme3.asset.TextureKey(asset, false);
        key.setGenerateMips(false);
        return ImageToAwt.convert(manager.loadTexture(key).getImage(), false, true, 0);
    }
}
