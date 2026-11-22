package com.jme3.gde.assetbrowser;

import java.util.Locale;
import org.openide.filesystems.FileObject;

enum AssetKind {
    TEXTURE, MODEL, MATERIAL, SHADER, AUDIO, FILTER, GUI, OTHER;

    static AssetKind of(FileObject file) {
        String ext = file.getExt().toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "png", "jpg", "jpeg", "bmp", "gif", "tga", "dds", "astc", "hdr", "pfm", "ktx" -> TEXTURE;
            case "j3o", "obj", "gltf", "glb", "meshxml", "blend", "fbx" -> MODEL;
            case "j3m", "j3md" -> MATERIAL;
            case "vert", "frag", "geom", "comp", "glsl", "glsllib", "tesc", "tese" -> SHADER;
            case "wav", "ogg", "mp3", "mp4", "m4a", "aac", "flac" -> AUDIO;
            case "j3f" -> FILTER;
            case "xml", "gui" -> GUI;
            default -> OTHER;
        };
    }
}
