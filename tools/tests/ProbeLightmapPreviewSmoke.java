import com.jme3.environment.gi.ProbeLightmapAsset;
import com.jme3.export.Savable;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.gde.core.assets.ProbeLightmapResolver;
import com.jme3.gde.core.assets.ProbeLightmapTopComponent;
import com.jme3.gde.core.assets.ProbeLightmapTopComponent.Channel;
import com.jme3.math.FastMath;
import com.jme3.scene.Node;
import com.jme3.texture.Image;
import com.jme3.texture.Texture2D;
import com.jme3.texture.image.ColorSpace;
import com.jme3.util.BufferUtils;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;

/** Runs the generated SDK loader registration and CPU channel preview without opening a window. */
public final class ProbeLightmapPreviewSmoke {
    public static void main(String[] args) throws Exception {
        var root = FileUtil.createMemoryFileSystem().getRoot();
        var tagged = new ProbeLightmapAsset(texture(true), true);
        var markedFile = write(root, "renamed.j3o", tagged);
        var legacy = write(root, "GI/Lightmaps/legacy.j3o", texture(false));
        var unrelated = write(root, "Texture.j3o", texture(false));
        var scene = new Node("scene");
        scene.setUserData("embedded", tagged);
        var nested = write(root, "GI/Lightmaps/scene.j3o", scene);
        var resolver = new ProbeLightmapResolver();
        expect(ProbeLightmapResolver.MIME.equals(resolver.findMIMEType(markedFile)), "marked outside GI directory");
        expect(ProbeLightmapResolver.MIME.equals(resolver.findMIMEType(legacy)), "legacy GI directory");
        expect(!ProbeLightmapResolver.isLightmap(unrelated) && !ProbeLightmapResolver.isLightmap(nested), "reject unrelated roots");
        expect(ProbeLightmapResolver.MIME.equals(markedFile.getMIMEType(ProbeLightmapResolver.MIME)),
                "NetBeans resolver registration");
        var object = org.openide.loaders.DataObject.find(markedFile);
        expect(object instanceof com.jme3.gde.core.assets.ProbeLightmapDataObject
                && object.getLookup().lookup(org.openide.cookies.OpenCookie.class) != null,
                "registered default Lightmap editor");
        expect("application/jme3model".equals(nested.getMIMEType()), "nested ordinary model retains its MIME");
        var loaded = ProbeLightmapTopComponent.readLightmap(markedFile);
        expect(loaded.hasSkyBand() && !ProbeLightmapTopComponent.readLightmap(legacy).hasSkyBand(), "format compatibility");
        var diffuse = ProbeLightmapTopComponent.renderImage(loaded, Channel.IRRADIANCE, 0);
        var coverage = ProbeLightmapTopComponent.renderImage(loaded, Channel.COVERAGE, 0);
        var sky = ProbeLightmapTopComponent.renderImage(loaded, Channel.SKY_VISIBILITY, 0);
        var bent = ProbeLightmapTopComponent.renderImage(loaded, Channel.BENT_DIRECTION, 0);
        expect(diffuse.getWidth() == 2 && diffuse.getHeight() == 2 && ((diffuse.getRGB(0, 1) >> 16) & 255) > 195,
                "linear HDR tone mapping and separated atlas extent");
        expect((coverage.getRGB(0, 1) & 255) == 255 && (sky.getRGB(0, 1) & 255) == 191, "coverage and sky visibility");
        expect(((bent.getRGB(0, 1) >> 16) & 255) == 64, "signed bent direction");
        System.out.println("PROBE_LIGHTMAP_EDITOR_AND_MIME_PASS");
        System.exit(0);
    }

    private static void expect(boolean value, String description) {
        if (!value) {
            throw new AssertionError(description);
        }
    }

    private static FileObject write(FileObject root, String path, Savable object) throws Exception {
        var file = FileUtil.createData(root, path);
        try (var output = file.getOutputStream()) {
            new BinaryExporter().save(object, output);
        }
        return file;
    }

    private static Texture2D texture(boolean sky) {
        var bytes = BufferUtils.createByteBuffer(sky ? 64 : 32);
        for (int i = 0; i < 4; i++) {
            pixel(bytes, 2f, .1f, .3f, 1f);
        }
        if (sky) {
            for (int i = 0; i < 4; i++) {
                pixel(bytes, -.5f, 0f, .5f, .75f);
            }
        }
        bytes.flip();
        return new Texture2D(new Image(Image.Format.RGBA16F, 2, sky ? 4 : 2, bytes, ColorSpace.Linear));
    }

    private static void pixel(java.nio.ByteBuffer bytes, float r, float g, float b, float a) {
        for (float value : new float[]{r, g, b, a}) {
            bytes.putShort(FastMath.convertFloatToHalf(value));
        }
    }
}
