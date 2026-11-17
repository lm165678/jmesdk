import com.jme3.asset.AssetKey;
import com.jme3.gde.core.assets.ProjectAssetManager;
import java.io.File;
/**
 * Regression for project assets shadowed by SDK dependency models.
 * Run with SDK/NetBeans runtime JARs and jme3-testdata.jar on the classpath.
 * Argument: project asset directory containing a distinct Models/Sponza/Sponza.j3o.
 * This is a read-only lookup test; it requires no OpenGL or Bullet natives.
 * 第一步：将项目 assets 目录作为命令行参数传入。
 * 第二步：验证项目模型优先于 testdata 中的同名模型。
 * 第三步：验证项目没有的引擎材质仍可从 classpath 加载。
 */
public class ProjectAssetPrioritySmoke {
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);
        ProjectAssetManager standalone = new ProjectAssetManager(org.netbeans.modules.masterfs.filebasedfs.FileBasedFileSystem.getFileObject(root));
        
        verify(standalone, root);
        ProjectAssetManager project = new ProjectAssetManager(standalone.getProject(), ".") {
            @Override
            public void updateClassLoader() {
                // NetBeans project services are not started in this isolated lookup test.
            }
        };
        verify(project, root);
        System.out.println("PASS project and standalone roots override classpath; engine fallback preserved");
    }
    private static void verify(ProjectAssetManager assets, File root) throws Exception {
        try (var stream = assets.locateAsset(new AssetKey<>("Models/Sponza/Sponza.j3o")).openStream()) {
            long size = stream.transferTo(java.io.OutputStream.nullOutputStream());
            if (size != new File(root, "Models/Sponza/Sponza.j3o").length()) {
                throw new AssertionError("Dependency model shadowed project file: " + size);
            }
        }
        if (assets.locateAsset(new AssetKey<>("Common/MatDefs/Misc/Unshaded.j3md")) == null) {
            throw new AssertionError("Engine classpath fallback lost");
        }
    }
}
