package com.jme3.gde.core.assets;

import com.jme3.environment.gi.ProbeLightmapAsset;
import com.jme3.export.FormatVersion;
import com.jme3.texture.Texture2D;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.MIMEResolver;
import org.openide.util.lookup.ServiceProvider;

/** Recognizes a lightmap by its J3O root type without decoding any texture or creating scene objects. */
@ServiceProvider(service = MIMEResolver.class, position = 125)
public final class ProbeLightmapResolver extends MIMEResolver {
    public static final String MIME = "application/x-jme-probe-lightmap";
    private static final String MODEL_MIME = "application/jme3model";

    public ProbeLightmapResolver() {
        super(MIME, MODEL_MIME);
    }

    @Override
    public String findMIMEType(FileObject file) {
        if (!"j3o".equalsIgnoreCase(file.getExt())) {
            return null;
        }
        return isLightmap(file) ? MIME : MODEL_MIME;
    }

    /** Also usable by older model actions before they attempt a scene load. */
    public static boolean isLightmap(FileObject file) {
        if (!"j3o".equalsIgnoreCase(file.getExt())) {
            return false;
        }
        String path = "/" + file.getPath().replace('\\', '/').toLowerCase(Locale.ROOT);
        boolean legacy = path.contains("/gi/lightmaps/");
        try (var in = new DataInputStream(new BufferedInputStream(file.getInputStream()))) {
            return isLightmap(in, legacy);
        } catch (IOException failure) {
            return false;
        }
    }

    private static boolean isLightmap(DataInputStream in, boolean legacy) throws IOException {
        if (in.readInt() != FormatVersion.SIGNATURE || in.readInt() < 1) {
            return false;
        }
        int classes = in.readInt();
        if (classes < 1 || classes > 4096) {
            return false;
        }
        int aliasBytes = classes >= 256 ? 2 : 1;
        int marked = -1;
        int texture = -1;
        for (int i = 0; i < classes; i++) {
            int alias = readAlias(in, aliasBytes);
            in.skipNBytes(in.readUnsignedByte() * 4L);
            int nameSize = in.readInt();
            if (nameSize < 1 || nameSize > 4096) {
                return false;
            }
            String name = new String(in.readNBytes(nameSize), StandardCharsets.UTF_8);
            if (ProbeLightmapAsset.class.getName().equals(name)) {
                marked = alias;
            } else if (legacy && Texture2D.class.getName().equals(name)) {
                texture = alias;
            }
            int fields = in.readInt();
            if (fields < 0 || fields > 256) {
                return false;
            }
            for (int field = 0; field < fields; field++) {
                in.skipNBytes(2);
                int bytes = in.readInt();
                if (bytes < 0 || bytes > 4096) {
                    return false;
                }
                in.skipNBytes(bytes);
            }
        }
        if (marked < 0 && texture < 0) {
            return false;
        }
        int objects = in.readInt();
        // A standalone lightmap has only a texture/image and a few associated objects.
        if (objects < 1 || objects > 64) {
            return false;
        }
        int[] ids = new int[objects];
        int[] offsets = new int[objects];
        for (int i = 0; i < objects; i++) {
            ids[i] = in.readInt();
            offsets[i] = in.readInt();
        }
        if (in.readInt() != 1) {
            return false;
        }
        int root = in.readInt();
        for (int i = 0; i < objects; i++) {
            if (ids[i] == root && offsets[i] >= 0 && offsets[i] <= 64 * 1024 * 1024) {
                in.skipNBytes(offsets[i]);
                int alias = readAlias(in, aliasBytes);
                return alias == marked || alias == texture;
            }
        }
        return false;
    }

    private static int readAlias(DataInputStream in, int bytes) throws IOException {
        return bytes == 1 ? in.readUnsignedByte() : in.readUnsignedShort();
    }
}
