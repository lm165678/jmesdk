import org.openide.filesystems.FileUtil;
import java.io.File;
public class TextureRoutingSmoke {
    public static void main(String[] args) {
        try {
            verify(args);
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static void verify(String[] args) {
        for (String path : args) {
            var file = FileUtil.toFileObject(FileUtil.normalizeFile(new File(path)));
            String mime = file.getMIMEType();
            if (!mime.equals("image/x-jme-compressed-texture")) {
                throw new AssertionError(path + " resolved as " + mime);
            }
            System.out.println("PASS automatic MIME: " + path);
        }
    }
}
