package com.jme3.gde.texturetools;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.MIMEResolver;
import org.openide.util.lookup.ServiceProvider;

/** Recognizes DDS, ASTC and ASTC-compressed KTX1 files. */
@ServiceProvider(service = MIMEResolver.class, position = 150)
public final class CompressedTextureResolver extends MIMEResolver {
    public static final String MIME = "image/x-jme-compressed-texture";

    public CompressedTextureResolver() {
        super(MIME);
    }

    @Override
    public String findMIMEType(FileObject file) {
        String extension = file.getExt().toLowerCase(Locale.ROOT);
        if (extension.equals("dds") || extension.equals("astc")) {
            return MIME;
        }
        if (!extension.equals("ktx")) {
            return null;
        }
        try (var stream = file.getInputStream()) {
            byte[] header = stream.readNBytes(64);
            if (header.length != 64 || header[0] != (byte) 0xab || header[1] != 'K'
                    || header[2] != 'T' || header[3] != 'X' || header[5] != '1' || header[6] != '1') {
                return null;
            }
            ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            if (buffer.getInt(12) == 0x01020304) {
                buffer.order(ByteOrder.BIG_ENDIAN);
            }
            int internal = buffer.getInt(28);
            if ((internal >= 0x93b0 && internal <= 0x93bd) || (internal >= 0x93d0 && internal <= 0x93dd)) {
                return MIME;
            }
        } catch (IOException exception) {
            // Let the platform handle files we cannot open.
        }
        return null;
    }
}
