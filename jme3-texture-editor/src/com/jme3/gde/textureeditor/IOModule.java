package com.jme3.gde.textureeditor;

import com.jme3.texture.Image;
import com.jme3.texture.plugins.StbImageLoader;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import javax.imageio.ImageIO;
import javax.imageio.stream.FileImageOutputStream;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.openide.filesystems.FileObject;
import tgaimageplugin.TGAImageReader;
import tgaimageplugin.TGAImageReaderSpi;
import tgaimageplugin.TGAImageWriter;
import tgaimageplugin.TGAImageWriterSpi;

/** Image-file I/O for the texture editor; HDR is decoded for read-only preview only. */
public class IOModule {

    public static IOModule create() {
        return new IOModule();
    }

    private IOModule() {
    }

    public void store(BufferedImage editedImage, String type, File file) throws IOException {
        if ("hdr".equalsIgnoreCase(type)) {
            throw new IOException("HDR preview is read-only; export to PNG/JPG/TGA instead");
        }
        if ("tga".equalsIgnoreCase(type)) {
            TGAImageWriterSpi spi = new TGAImageWriterSpi();
            TGAImageWriter writer = new TGAImageWriter(spi);
            try (FileImageOutputStream output = new FileImageOutputStream(file)) {
                writer.setOutput(output);
                writer.write(editedImage);
            } finally {
                writer.dispose();
            }
        } else if (!ImageIO.write(editedImage, type, file)) {
            throw new IOException("No ImageIO writer for " + type);
        }
    }

    /**
     * Loads PNG/JPEG/BMP through ImageIO, TGA through the bundled reader, and HDR
     * through StbImageLoader. Streams are closed and unsupported/corrupt content
     * produces IOException instead of a later null-image Swing failure.
     */
    public BufferedImage load(FileObject file) throws IOException, URISyntaxException {
        if (file == null || !file.isData()) {
            throw new IOException("No image file selected");
        }
        String extension = file.getExt();
        if ("hdr".equalsIgnoreCase(extension)) {
            try (InputStream input = file.getInputStream()) {
                Image decoded = new StbImageLoader().load(input, false);
                return HdrImageConverter.toBufferedImage(decoded, 0f);
            } catch (RuntimeException failure) {
                throw new IOException("Cannot decode HDR " + file.getNameExt(), failure);
            }
        }
        if ("tga".equalsIgnoreCase(extension)) {
            TGAImageReaderSpi spi = new TGAImageReaderSpi();
            TGAImageReader reader = new TGAImageReader(spi);
            try (InputStream stream = file.getInputStream();
                    ImageInputStream input = new MemoryCacheImageInputStream(stream)) {
                reader.setInput(input);
                BufferedImage result = reader.read(0);
                if (result == null) {
                    throw new IOException("Cannot decode TGA " + file.getNameExt());
                }
                return result;
            } catch (RuntimeException failure) {
                throw new IOException("Cannot decode TGA " + file.getNameExt(), failure);
            } finally {
                reader.dispose();
            }
        }
        try (InputStream input = file.getInputStream()) {
            BufferedImage result = ImageIO.read(input);
            if (result == null) {
                throw new IOException("Unsupported or corrupt image: " + file.getNameExt());
            }
            return result;
        } catch (RuntimeException failure) {
            throw new IOException("Cannot decode image " + file.getNameExt(), failure);
        }
    }
}
