# jMonkeyEngine Software Development Kit (SDK) [![Build Status](https://github.com/jMonkeyEngine/sdk/actions/workflows/gradle.yml/badge.svg)](../../actions)

Welcome to the repository of the jMonkeyEngine Software Development Kit (SDK).
This SDK simplifies developing 3D Applications with the jMonkeyEngine (jME). It contains everything ever needed to develop a full application.  
  
Our SDK (or jmonkeyplatform) is based on the Netbeans IDE but includes additional features like:
-  A Scene Editor (SceneExplorer: Preview your scene, adjust all positions, add `Controls` and `AppStates` (WYSIWYG) and then just save the scene)

-  A Material Editor

-  A Filter Editor (combine multiple filters and just load them in-game)

-  Bundled JDK for each platform (so you don't need to install java first)

It is important to know that the SDK is not coupled with the engine itself, so if you have issues which are unrelated to the IDE, report them [here](https://github.com/jMonkeyEngine/jmonkeyengine).
This however also means, that the SDK can have a different pace than the engine but you can nonetheless work on a different engine version than the one which is bundled. For your own projects, you should consider using Gradle build system which also gives you flexibility on choosing any jMonkeyEngine version. More on this later.

## Getting Started / Downloading the SDK
Just have a look at our [releases](https://github.com/jMonkeyEngine/sdk/releases) section.
There you can download the version you desire. The SDK will generally follow the convention that it's version number is lined up with the matching engine version
plus some suffixes for different SDK releases on the same engine version.

There are multiple files from which you can choose:  
You can take the platform agnostic `jmonkeyplatform.zip` which contains the full SDK able to be run on "any" (64-bit) platform (Windows, Mac OS, Linux) however it lacks the JDK.  
Thus the prefered download is `jmonkeyplatform-windows-x64.exe` which essentially __is__ `jmonkeyplatform.zip` and the correct version of the JDK.

After the SDK is up and running. A good starting point is to look at the jME examples. Under *File | New project | JME3 Tests* you can create a new project, populated with the jME build in examples. You can freely mess around with these and try out stuff. Your changes can always be reverted by simply creating a new *JME3 Tests* project. Once you are all comfortable and ready to embark on your own exciting journey, *File | New project | Basic game (with Gradle)* is the recommended starting point.

## Building the SDK
Building the SDK is an easy process basically, but it depends on what kind of distribution you want to build. Currently the SDK requires __JDK 25__ to build.
You also have to know that the build process changes from time to time, so have a look at the `.github/workflows/gradle.yml` file, or related, you will see how we build our releases then.  

Technically the gradle task `buildSdk` is the main task which builds the sdk (Invoking `./gradlew buildSdk` or `gradlew.bat buildSdk` on Windows).

`buildSdk` also runs `prepareTextureTools`, which verifies the repository's original Compressonator 4.6.0 and astcenc 5.7.1 archives in `third-party/texture-tools` against pinned SHA-256 hashes, then unpacks them into `jme3-texture-tools/release/texture-tools`. The original archives are versioned so clean and offline SDK builds do not depend on upstream download availability; only the extracted binaries are ignored by Git. To restore only these resources without compiling the SDK, run `gradlew.bat prepareTextureTools` on Windows or `./gradlew prepareTextureTools` on other hosts. Model-to-J3O conversion requires these host-side tools; their absence is an SDK installation/build issue, not a game-project asset error.

__If you just want to run the SDK on your machine:__   
    call `./gradlew run` (`gradlew.bat run`), which internally calls `buildSdk` and then uses `ant` to run the SDK.
    
__If you want to build the platform agnostic zip:__   
    call `./gradlew buildSdk` and then you have the file in `dist/jmonkeyplatform.zip`  

__If you want to build the platform installers (like we do):__   
    call `./download-jdks.sh`, `./gradlew buildSdk` and then `ant build-installers`
    You have to install ant for this to work (for Linux consult the internet, for Mac OS look into homebrew).
    Note that this might take a long time, because a huge amount of data is compressed and such.
    
__If you want to build the platform installers (On Windows -> without having ant):__   
    See above. You can bypass ant by declaring a gradle task, just like it has been done for run. There, the gradle included ant will be used).
    Alternatively you can launch netbeans in `netbeans/`, open the SDK as Netbeans Project Collection and select __package as__:
    ![Package as...](http://i.imgur.com/5V2uBHf.png).
    Note that you still have to download and unpack the JDKs somehow, which is a cumbersome process, which is why it is discouraged to
    build the installers on Windows (Linux and Mac OS are supported).
    
__If you want to debug the SDK inside an IDE:__   
    See above. You want to open the SDK as Netbeans Project from within Netbeans (you can use the nb in `netbeans/`) and you can then treat it as regular project with the difference that there are several subproject.

A note about the `netbeans/` folder: To save bandwidth `buildSdk` downloads netbeans once to said folder and uses it over and over again. Even when the download URL is changed (i.e. when you update the Netbeans version), you have to delete the netbeans folder and remove it from any cache so it can be reloaded.

## Developing/Contributing
First of all, I suggest you to take a look at [docs/](https://github.com/jMonkeyEngine/sdk/tree/master/docs). Those docs are a loose collection of things I came across during development, but they prevent you from re-doing the same experiences. 
Other than that, `Netbeans Platform` is your google keyword for any NB related issues.
Basically the only tricky thing is how we handle custom entries in the SceneExplorer. This is called the Netbeans Nodes API and is somewhat unintuitive.
Just take a look at the `Motion Event Pull Request`, which should've been added around `March 2016`, there you can see what was needed to add MotionEvents to the SDK.

Other than that, we are more than happy to help, even if your addition is incomplete. Make sure you use the Netbeans formatting, obvious variable naming and commented and especially documented code, though.
If you think you've encountered a bug in the SDK, please open an issue to let the developers know or post [on the hub](https://hub.jmonkeyengine.org) using the `Troubleshooting | jmonkeyplatform` category.


## 本地引擎与 SDK 联合开发（本 fork）

此 SDK 使用相邻 `../jmonkeyengine` 或 `../JMESources` 的自定义引擎，而不是官方 beta2 引擎。
SDK 的 Gradle Wrapper 是 9.2.1；引擎自己的 Wrapper 是 9.8.0；两者都使用本机 JDK 25。
不要将两个工程的 Wrapper 版本互相覆盖。

### 第一次接入，或修改了引擎源码

先关闭正在运行的 **jMonkeyEngine SDK 产品窗口**，保留开发用 NetBeans/IDEA 即可。
在 SDK 根目录运行：

```bat
build-local-engine-sdk.bat
```

需要构建完成后立即启动 SDK：

```bat
build-local-engine-sdk.bat run
```

流程是：使用引擎的 Wrapper 增量编译 → 导出本地 Maven 仓库 → SDK 解析新依赖、
同步库、生成 NetBeans 模块描述 → 编译 SDK。普通的引擎 `build`/IDEA Build 不会自动同步 SDK。
两个 BAT 共用 `tools/build-local-sdk.ps1`，不再硬编码旧电脑路径。
先运行 `build-local-engine-sdk.bat check` 检查环境，不会编译或下载。
JDK 25 从 JAVA_HOME、C:/Java、用户 .jdks/.gradle/jdks、Program Files 和 PATH 查找；
不会把系统或用户 JAVA_HOME/PATH 改成 JDK 25，不影响 UE5 Android 环境。
引擎目录先找相邻 jmonkeyengine，再找 JMESources；两者都存在时可显式指定。
特殊目录可复制 `sdk-local.example.json` 为 `sdk-local.json` 后修改 javaHome/engineDir。
该本机配置已被 Git 忽略，换电脑无需改共享脚本。SDK_JAVA_HOME/SDK_ENGINE_DIR 环境变量优先于 JSON。
本地 127.0.0.1:7897 端口可连接时自动使用代理，否则直连；可在 JSON 指定 proxyHost/proxyPort，
或用空字符串 proxyHost 禁用自动代理。已有项目自身的代理设置仍独立生效。
这些检测与配置只作用于脚本子进程。运行前关闭 SDK 产品窗口，避免已加载库被占用。

引擎库版本固定为 `3.10.0-local-SNAPSHOT`，本地仓库位于：
`<检测到的引擎目录>/build/sdk-engine-repo`。该仓库不上传到 Maven Central，也不修改全局 Maven 本地库。
`gradle/local-engine-export.gradle` 仅用于 SDK 联合开发；通过 `-I` 应用到引擎，
不要求修改引擎自身的构建脚本。二进制和源码 JAR 都导出；本地迭代不生成 Javadoc HTML。
导出成功后才生成 `sdk-engine.properties`。SDK 对该仓库的引擎模块使用独占解析，
不会在缺失本地库时静默回退到官方同名模块。

首次接入统一构建 SDK 所需的 17 个引擎代码模块，而非只替换 core。
后续未改变的编译/打包任务由 Gradle 判定为 UP-TO-DATE；发布和库同步仍会检查当前产物。
额外的测试数据 JAR 合并官方测试资源和本 fork 的烘焙资源。
LWJGL 2 不用于这个 SDK，因此不需要复制 `jme3-lwjgl`；Android native 等独立预编译依赖
继续由引擎/SDK 的依赖声明解析，不会假装是本仓库重新编译的本地代码。

### 只修改 SDK Java 代码

在 NetBeans 31 中打开本目录的 **NetBeans Module Suite**（根目录的 `nbproject` 工程）。
配置 JDK 25 后，使用 Run / Debug；这两个 Ant 目标都会先构建再启动，支持断点调试。
不要把它当成只有一个 Java 主类的普通项目。

也可以双击 `build-and-run-sdk.bat`：它运行 Gradle 的 `run`，先准备当前已导出的依赖，
再通过相同的 NetBeans/Ant suite 构建和启动。它不会重新编译引擎源码。
如果修改了引擎，请先使用上面的联合构建脚本，然后再在 NetBeans 中 Run / Debug。

### 库更新的位置（均由构建管理，不要手工复制）

| 用途 | SDK 内相对路径 |
| --- | --- |
| SDK 自身使用的引擎 JAR | `jme3-core-baselibs/release/modules/ext/` |
| LWJGL 等第三方运行库 | `jme3-core-libraries/release/modules/ext/` |
| SDK 提供给项目的引擎及源码库 | `jme3-project-baselibs/release/libs/` |
| SDK 提供给项目的第三方库 | `jme3-project-libraries/release/libs/` |
| 示例模型/烘焙资源 | `jme3-project-testdata/release/modules/ext/jme3-testdata.jar` |
| 最终运行时模块库 | `build/cluster/modules/ext/` |

库目录由 Gradle 同步，模块 `project.xml` 和 library XML 由任务重建。
不能只复制 `jme3-core.jar`，也不能将第三方 native JAR 留在与引擎不匹配的版本。
已有游戏项目自己的 Gradle/Maven 依赖是独立的，不会因为 SDK 更新就自动切换；
若它也要使用本地引擎，需将上述文件 Maven 仓库加入它自己的 repositories，并使用
`org.jmonkeyengine:所需模块:3.10.0-local-SNAPSHOT`。
SDK 向导新建的 Gradle 项目仍由向导选择版本；本次没有改写其模板或已有游戏项目。

### 预览兼容和验证

- SceneViewer 顶部 `Render` 下拉框仅切换当前主预览视口，默认为 Forward；
  可选择 Deferred / TiledDeferredCPU / TiledDeferredGPU / Auto，选 Forward 恢复。
- FrameGraph 是自定义引擎的默认整帧管线；路径选择不修改模型文件，也不改变缩略图/GUI 默认路径。
- SDK AWT/Canvas 预览在 Windows/Linux 默认请求 OpenGL 4.3，让 GPU tiled 实际使用 compute/SSBO；
  macOS 默认 3.3。旧显卡可在 SDK 启动参数加入 `-J-Djme.sdk.previewOpenGL=3.3` 并重启，
  此时 GPU tiled 自动回退 CPU；缺少 4.3 的机器必须显式选择这个兼容模式。
- Minie 物理原生库在构建时打包到 `jme3-core-baselibs/release/native-libs/`，运行时通过
  NetBeans 模块定位、使用引擎预提取加载接口，避免本机临时目录权限检查阻止预览启动。
  显式设置 `com.jme3.NativeLibraryExtractionFolder` 时尊重用户配置。
- 属性面板以只读摘要显示 SDF 网格尺寸和表面卡片缓存，防止字符串编辑器损坏 Savable 数据。
- 已烘焙的 SDF/SurfaceBakeData 随 j3o 正常加载；它们不是自动启用 GI 的开关。
  本次没有添加 SDK 烘焙按钮、SDF 调试可视化或 FrameGraph 图形编辑器。
- 材质可视化编辑器保留未识别扩展块的嵌套内容；自定义 shader 声明仍应放在 `.j3md`。

构建后在 PowerShell 执行：

```powershell
.\gradlew.bat verifyLocalEngine
.\tools\verify-local-engine.ps1
.\tools\verify-local-engine.ps1 -OpenGL33
```

第一条检查解析版本与自定义类，并生成 `build/local-engine-libraries.txt`。
第二条使用 SDK 已构建的库和 AWT 上下文，检查多路径预览、Forward 恢复、
独立缩略图视口、四个已有烘焙模型和材质嵌套扩展块。
验证成功输出 `SDK_PREVIEW_SMOKE_PASS`。渲染验证需要本机 OpenGL 3.3，不适用于无图形驱动的 CI。


### NetBeans 模块隔离下的自定义上下文

`PreviewPanelsContext` 位于 SDK 的 `com.jme3.gde.core` 模块，依赖引擎模块；
引擎模块不能反向按类名加载 SDK 类。当前引擎的 `AppSettings.setCustomRenderer(Class)`
保留运行时类对象，`copyFrom`/`mergeFrom` 一并保留注册，桌面上下文工厂直接使用该类。
保存到配置文件的仍然只是类名；从文件载入后，SDK 在启动前重新注册上下文。
不需要给 NetBeans 模块添加循环依赖，也不需要把 SDK 的类复制进引擎 JAR。

对应的引擎回归测试为 `:jme3-desktop:test --tests '*CustomContextClassLoaderTest'`，
使用引擎类加载器无法看到的独立插件类，覆盖注册、设置复制、持久化和内置类回退。
SDK 的 `verify-local-engine.ps1` 也通过实际 `JmeSystem.newContext` 工厂启动 AWT 上下文。

### Modern scene/model editor

The SDK uses the bundled **FlatLaf** (Apache-2.0, no paid UI library). Dark is the default;
use the preview toolbar **Theme** to switch Dark/Light. Toolbar controls use consistent
logical-pixel padding instead of forcing mixed fixed pixel sizes.

- 左键选择模型；左键拖动操作柄。E 平移 / R 旋转 / T 缩放。
- 按住右键 + 鼠标原地转向，配合 WASD 漫游；Shift 四倍速度。
- 松开右键、失去焦点或关闭场景会清理运动状态。中键环绕，Shift+中键平移，滚轮缩放。
- 移除 G/R/S 等旧式模态键盘变换。Ctrl+C/X/V、Ctrl+S、Ctrl+Z/Y 交给平台编辑命令，
  不再触发场景变换。Esc 取消正在拖动的变换。Help / 操作帮助按钮可查看速查表。
- 剪切仅在粘贴成功时移动原节点；查询剪贴板不会删除节点。
  复制每次粘贴创建独立对象；剪切不能跨场景或粘贴进自身子树。
- 整棵子树删除、复制粘贴、剪切移动、材质/模型拖放和烘焙支持撤销/重做。
  根节点不能删除/剪切。空操作柄点击不再创建错误的重做变换。

**Render / View** are preview-only controls. Render offers Forward, Deferred,
TiledDeferredCPU, TiledDeferredGPU and Auto. GPU tiled falls back to CPU without GL4.3.
Lit uses original materials and the selected path. Unlit displays base color/texture;
Normals displays vertex normals (not normal-map detail); Wireframe displays edges.
SDF shows bounded near-surface samples of saved `SdfVolume` data, not an exact ray-marched surface.
Collision displays existing physics collision shapes without enabling simulation.
Diagnostic modes temporarily use Forward; returning to Lit restores the selected path.
No mode writes into the scene. Use Refresh after changing bake data or physics shapes.
Missing SDF/physics data produces a status message instead of silently generating data.

**Model Detail / 模型详情** opens when a selected model/node contains triangle geometry:

1. Select the model root (or one geometry). Operations include descendant triangle meshes.
2. LOD: enter decreasing retained triangle fractions, default `0.5,0.25,0.125`.
3. Normals: regenerate area-weighted vertex normals and/or MikkTSpace tangents.
   Shared vertices are smoothed; split vertices retain hard edges. Tangents require UV0.
   This generates vertex data, not a high-poly-to-low-poly normal-map texture.
4. SDF: default 64×64×64, padding 0.05, UNSIGNED for open surfaces.
   EVEN_ODD requires closed, non-intersecting shells. Baking uses static CPU mesh positions;
   GPU skinning/displacement is not evaluated. No mesh repair is performed.
5. Surface: default 128 pixels per card and 256 MiB serialized payload limit.
   Captures material attributes/depth, not lightmaps/GI. Six-direction cards can miss hidden
   concave surfaces. Update the revision string when external materials/textures change.
6. Click Bake. CPU tasks run on isolated meshes in a worker; surface capture needs the GL
   render thread and can briefly pause preview. Cancel or selecting another model discards
   pending results. CPU results are rejected if geometry changed during baking.
7. Success marks the j3o modified; Ctrl+S saves, Ctrl+Z undoes. Earlier data remains intact
   on failure/cancellation. Re-bake surface attributes after changing normals/materials.

**Windows HiDPI:** `AwtPanel` now creates its framebuffer in device pixels and draws it
without an intermediate logical-pixel resize. Mouse events and drag/drop picking use the same
physical coordinates. 100%, 125%, 150% and monitor changes are supported; keep Windows scaling
at your preferred setting. This repairs scaling-induced blur; ordinary polygon aliasing is
separate from anti-aliasing settings. No global DPI override is added.

Build and verify from PowerShell:

```powershell
cd M:\Sources\JMonkeyEngine3\jmesdk
.\build-local-engine-sdk.bat
$env:JAVA_HOME = 'C:\Users\JohnKkk\.gradle\jdks\eclipse_adoptium-25-amd64-windows.2'
.\tools\verify-editor.ps1
.\tools\verify-local-engine.ps1
.\tools\verify-local-engine.ps1 -OpenGL33
# Start the built SDK (or Run the suite in NetBeans):
.\build-and-run-sdk.bat
```

Engine DPI/context regressions:
`gradlew.bat :jme3-desktop:test --tests '*AwtPanelHiDpiTest' --tests '*CustomContextClassLoaderTest'`.
The preview smoke also checks all diagnostic modes, surface GPU baking, material/path restoration
and independence of a second viewport. CPU smoke checks shortcut isolation, fly navigation,
mesh-copy safety, LOD, normals/tangents and signed SDF baking.
