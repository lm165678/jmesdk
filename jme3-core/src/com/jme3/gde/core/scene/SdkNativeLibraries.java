package com.jme3.gde.core.scene;

import com.jme3.system.JmeSystem;
import com.jme3.system.NativeLibraryLoader;
import java.io.File;
import org.openide.modules.InstalledFileLocator;

/**
 * Loads SDK-bundled Minie natives using the engine's pre-extracted-library mode.
 * Called before engine initialization, never during rendering. LWJGL uses its own
 * native loader. An explicit engine extraction-folder property takes precedence.
 * If this platform is not bundled, the ordinary engine loader remains active.
 * <pre>{@code
 * // 第一步：Gradle prepareSdkPhysicsNatives 将依赖原生库放入模块 release/native-libs。
 * // 第二步：SDK 启动时先配置打包目录，再创建 SceneApplication 的图形上下文。
 * SdkNativeLibraries.configure();
 * // 独立验证可用 -Djme.sdk.nativeDirectory=.../release/native-libs 指定同一目录。
 * // 删除该参数恢复模块自动定位；用户显式配置的引擎原生库目录优先。
 * }</pre>
 */
public final class SdkNativeLibraries {
    private static boolean configured;

    private SdkNativeLibraries() {
    }

    public static synchronized void configure() {
        if (configured || System.getProperty(NativeLibraryLoader.CUSTOM_EXTRACTION_FOLDER_PROPERTY) != null) {
            return;
        }
        String relative;
        switch (JmeSystem.getPlatform()) {
            case Windows64:
                relative = "windows/x86_64/bulletjme-x86_64.dll";
                break;
            case Windows_ARM64:
                relative = "windows/arm64/bulletjme-arm64.dll";
                break;
            case Linux64:
                relative = "linux/x86_64/libbulletjme-x86_64.so";
                break;
            case Linux_ARM64:
                relative = "linux/arm64/libbulletjme-arm64.so";
                break;
            case MacOSX64:
                relative = "osx/x86_64/libbulletjme-x86_64.dylib";
                break;
            case MacOSX_ARM64:
                relative = "osx/arm64/libbulletjme-arm64.dylib";
                break;
            default:
                return;
        }
        String override = System.getProperty("jme.sdk.nativeDirectory");
        File library = override == null
                ? InstalledFileLocator.getDefault().locate("native-libs/" + relative,
                        "com.jme3.gde.core.baselibs", false)
                : new File(override, relative);
        if (library != null && library.isFile()) {
            NativeLibraryLoader.setCustomExtractionFolder(library.getParentFile().getAbsolutePath());
            NativeLibraryLoader.setExtractNativeLibraries(false);
            configured = true;
        }
    }
}
