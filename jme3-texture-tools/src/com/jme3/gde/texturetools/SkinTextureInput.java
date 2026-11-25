package com.jme3.gde.texturetools;

import java.awt.datatransfer.*;
import java.io.File;
import java.nio.file.*;
import javax.swing.*;
import org.openide.util.Lookup;

/** Shared texture assignment. Decoding remains on the existing generation worker.
 * 第一步：在 EDT 为输入框、Browse 按钮和预览区安装 install。
 * 第二步：从 SDK 或资源管理器拖入一张纹理；失败保留原输入。
 * 第三步：Generate 在后台解码 DDS/PNG/JPEG 等受支持图片。Browse 仍保留。
 */
public final class SkinTextureInput {
    private SkinTextureInput() {
    }
    public static boolean isTexture(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        return java.util.Set.of("dds", "png", "jpg", "jpeg", "bmp", "gif", "tif", "tiff", "tga", "hdr", "ktx", "astc").contains(ext);
    }
    static Path resolve(Transferable value) throws Exception {
        if (value.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            var files = (java.util.List<?>) value.getTransferData(DataFlavor.javaFileListFlavor);
            if (files.size() == 1 && files.get(0) instanceof File) {
                return ((File) files.get(0)).toPath();
            }
            return null;
        }
        SkinAssetSelection bridge = Lookup.getDefault().lookup(SkinAssetSelection.class);
        Path internal = bridge == null ? null : bridge.textureFromTransfer(value);
        if (internal != null) {
            return internal;
        }
        if (value.isDataFlavorSupported(DataFlavor.stringFlavor)) {
            String text = ((String) value.getTransferData(DataFlavor.stringFlavor)).trim();
            if (text.startsWith("file:")) {
                return Path.of(java.net.URI.create(text));
            }
            Path path = Path.of(text);
            return path.isAbsolute() ? path : null;
        }
        return null;
    }
    static void install(JTextField target, JComponent... surfaces) {
        TransferHandler handler = new TransferHandler() {
            @Override public boolean canImport(TransferSupport support) {
                return target.isEnabled();
            }
            @Override public boolean importData(TransferSupport support) {
                if (!target.isEnabled()) {
                    return false;
                }
                try {
                    Path path = resolve(support.getTransferable());
                    if (path == null || !Files.isRegularFile(path) || !isTexture(path.getFileName().toString())) {
                        target.setToolTipText("Drop one supported texture file (DDS, PNG, JPEG, TGA, BMP...)");
                        return false;
                    }
                    target.setText(path.toAbsolutePath().normalize().toString());
                    target.setToolTipText(target.getText());
                    return true;
                } catch (Exception failure) {
                    target.setToolTipText("Cannot assign texture: " + failure.getMessage());
                    return false;
                }
            }
        };
        target.setTransferHandler(handler);
        for (JComponent component : surfaces) {
            component.setTransferHandler(handler);
        }
    }
    static Path chooseProject(java.awt.Component parent) {
        SkinAssetSelection bridge = Lookup.getDefault().lookup(SkinAssetSelection.class);
        if (bridge == null) {
            JOptionPane.showMessageDialog(parent, "The SDK project-selection service is unavailable.");
            return null;
        }
        return bridge.chooseProject(parent);
    }
    static Path chooseTexture(java.awt.Component parent) {
        SkinAssetSelection bridge = Lookup.getDefault().lookup(SkinAssetSelection.class);
        if (bridge == null) {
            JOptionPane.showMessageDialog(parent, "The SDK project-selection service is unavailable.");
            return null;
        }
        return bridge.chooseTexture(parent);
    }
}
