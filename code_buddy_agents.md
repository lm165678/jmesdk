# CodeBuddy 工程上下文记录（jmesdk）

本文件由 CodeBuddy 维护，用于记录 CodeBuddy 在该工程中的代码修改上下文。
它不替代 `AGENTS.md`、`DEV_HANDOFF.md` 或引擎侧的 `RENDERING_QUALITY_ROADMAP.md`；那些文档由 GPT6 记录，仍作为历史交接参考。
最终判断以当前源码、测试和实际运行结果为准。
本文件用于随 fork 仓库一起提交，不保存凭据、私钥或令牌。

## 初始化记录（2026-11-17）

- 已读取 `AGENTS.md`、`DEV_HANDOFF.md`，以及相邻的 `jmonkeyengine/AGENTS.md`、`jmonkeyengine/DEV_HANDOFF.md`、`jmonkeyengine/RENDERING_QUALITY_ROADMAP.md`。
- 本轮没有代码修改、构建、测试、提交或推送；仅创建本文件，使其纳入 fork 仓库版本管理。
- 交接文档记录的配套源码快照为 engine `4ee85daa4`、SDK `e38d71c5`。这不是新验证过的二进制版本，不附加额外运行结论。
- 两个工程原本都没有 `code_buddy_agents.md`。

## 恢复开发时的提醒

- Lemur 与双 GUI 可视化编辑器、SDK Coding/Scene Agent 仍是待办；计划不等于已实现。
- Nifty 拖放与序列化、RenderDoc 关闭阶段崩溃、压缩纹理编码器原生崩溃等问题在交接文档中标为未完全验证或未定位根因。
- 资源操作中的依赖感知重命名、删除引用检查、派生压缩纹理重建尚未完成。
- 大世界表面卡片预算与流送同引擎侧路线图相关；需要引擎联动时同时读取引擎文档。

## CodeBuddy 修改代码时的规则

- 保留无关工作区改动，不覆盖用户或 GPT6 的既有交接内容。
- Swing 组件创建与编辑属于 EDT；场景变更和克隆属于所属渲染线程；预览安装使用自己的渲染线程，不阻塞 EDT 等待渲染或长耗时 I/O。
- 图编辑必须保留 undo/redo 与 dirty 状态；保存失败必须保留原资源；非原子回退要在源码注释和本文件中说明。
- 用户可见行为或默认值变化要写入源码注释，并给出分步骤中文示例；J3O 保持通用扩展名，动画检测与骨骼蒙皮分开处理。
- 根据交接文档，用户多次要求不做构建或编译以节省成本；除非本轮明确授权，先做源码/文档修改并如实记录未验证项。
- 有意义的编辑器交互与持久化需要测试；预览变更还要做原生 OpenGL/AWT 验证，编译成功不能推断可见渲染正确。
- 只记录实际执行过的构建与测试；提交或推送成功必须经过验证；不在交接文件中保存任何凭据。
- 每次有意义的代码修改结束前更新本文件和 `DEV_HANDOFF.md`，区分已测试行为、限制和下一步。

## 2026-11-17 GI 属性面板间歇性横向增宽修复
- 截图中的左侧标签裁切和底部横向滚动条来自 GI 面板嵌入 Properties 的路径：`ModelDetailTopComponent` 使用默认 JScrollPane 包装固定 preferred width=360 的普通 JPanel，内部 InspectorColumns 的 tracksViewportWidth 无法跨层生效；垂直滚动条出现及异步 grid/status 更新会触发布局跳动。
- `InspectorColumns.WidthTrackedPanel` 作为直接 scroll view：独立对话框仍按 360 像素 pack，嵌入时 preferred viewport width=0、始终跟踪实际 viewport 宽度、仅高度按需滚动，长文本不形成横向宽度请求。
- `BakeGiProbeVolumeAction.createPanel` 改用该根容器；`ModelDetailTopComponent` 的 GI 滚动页明确 `HORIZONTAL_SCROLLBAR_NEVER`、垂直按需滚动、无边框且 minimum size 为 0。
- `SdkEditorSmoke` 新增 `SDK_GI_INSPECTOR_WIDTH_PASS` 用例：反复切换 240/180/260 像素宽度，注入长网格与诊断文本，验证垂直滚动出现时根面板和字段仍精确匹配 viewport，水平滚动条不出现。
- 验证：只读调用链审查和 IDE diagnostics 无错误；按用户既有偏好未构建、未编译、未运行 smoke/SDK，因此实际 NetBeans Properties dock 的间歇性布局与视觉效果仍需重建重启后验证。未提交、未推送。

## 2026-11-17 GLB/glTF 内嵌纹理提取与 DDS
- `AssetDataObject` 在源模型转换时把实际 `FileObject` 传给 `ModelTexturePreparation`；普通 J3O 保存仍绕过提取和压缩，未修改引擎 GltfLoader。
- 对 GLB/glTF 根级 `imageN.png/jpg/jpeg` key，按已加载的 8 位 Luminance/LuminanceAlpha/RGB/RGBA 像素解码为 PNG，写入源模型旁 `模型名.textures/imageN-完整SHA256.png`。根目录即使已有无关 `image0.png` 也不会再抢占内嵌纹理引用；罕见的根目录外部同名 URI 同样按像素归一化，其他外部路径不变。
- 新 key 保留 generateMips/anisotropy/typeHint，并固定 `flipY=false`，因为 PNG 已保存当前解码后的行顺序，避免二次翻转。J3O 因而保存可重新加载的模型专属路径，不再保存无物理文件的 `imageN` key。
- 同内容重复转换复用 PNG 和有效 DDS；内容变化生成新哈希文件并逐字节保留旧图。新文件采用临时文件加无覆盖移动，路径校验阻止提取目录逃出资产根。
- 提取后的 PNG 继续走现有 `GeneratedTextures.ensure` BC3/完整 mip 链与逻辑 key 映射。压缩、无像素、16 位及 WebP/KTX2 等不支持格式保留原 key 并警告，不宣称已生成 DDS。
- 扩展 `ModelTextureMappingSmoke`：覆盖根目录同名冲突、flipY/像素、稳定 key、重复转换不重写、内容变化不覆盖、不同 GLB 隔离、灰度 Alpha 通道，以及真实构造的最小 GLB 容器经 GltfLoader/Stb 解码后提取、DDS 和 J3O 往返。
- 验证：完成两轮只读源码审查并修复根目录 `imageN` 冲突；IDE diagnostics 无错误。按用户既有偏好未构建、未编译、未运行 smoke/SDK，因此真实编码器执行、GLB/J3O 测试、显示方向和实际资产兼容性均未运行验证。未提交、未推送。

## 2026-11-17 纹理编辑器 PNG/JPG/TGA/HDR 查看修复
- `JmeTextureDataObject` 增加 `hdr/HDR`，并无条件注册 `OpenTexture`，解除只有识别到 ProjectAssetManager 才能打开的限制；`OpenTexture` 修正 `Loaders/image/image/x-jmetexture/Actions` 为 `Loaders/image/x-jmetexture/Actions`，移除无关 text/plain 注册。
- `IOModule` 修复 PNG/JPG 输入流关闭和 ImageIO null 检查；TGA 改用 MemoryCacheImageInputStream 并关闭 reader/stream，支持非本地 FileObject；损坏文件明确抛 IOException。HDR 使用 StbImageLoader(flipY=false)，模块增加 core.baselibs/core.libraries 依赖。
- 新增 `HdrImageConverter`：支持 RGB/RGBA/Luminance half-float，执行 0 EV、Reinhard 和 linear→sRGB，保留高亮渐变而非直接 clamp；HDR 只读，IOModule 拒绝写回。
- `ImageEditorTopComponent` 后台解码、EDT 更新界面；加载中/失败后禁用编辑，统一两个构造函数的组件与 Lookup 初始化。`ImageEditorComponent` 对 HDR 禁用旋转/裁切/滤镜/保存，普通 PNG/JPG/TGA 保持编辑，修复派生窗口初始化。
- 新增 `TextureEditorSmoke`：PNG/JPG/TGA 往返、损坏文件、真实 Radiance HDR 经 Stb 解码、half-float tone mapping 和 HDR 写回拒绝。只读审查发现的缺少 DataObject import、空构造初始化和加载期 null 编辑问题已修复。
- 验证：IDE diagnostics 无错误；按用户既有偏好未构建、未运行 smoke 或 SDK，因此真实文件打开、HDR 原生解码、Swing 状态和显示效果均未运行验证。未提交、未推送。

## 2026-11-17 独立 Unity Scene/Prefab 导入模块
- 新增 `jme3-unity-scene`（`com.jme3.gde.unityscene`），只在套件 `nbproject/project.properties` 注册；没有修改 `jme3-core`、assetbrowser、texturetools 或其他模块业务代码。依赖方向为新模块到 core/引擎/NetBeans SnakeYAML。
- `UnityAssetManagerConfigurator` 注册 `.unity/.prefab` loader；`UnitySceneDataObject` 通过 MIME/DataObject/ActionID 注解复用现有 Open Model 与 Convert Model，因此可直接预览并保存 J3O。
- 实现 Unity YAML 多文档、GUID/.meta 索引、新旧 ModelImporter mesh fileID 名称表、新旧材质属性格式；支持 GameObject 层级、坐标转换、MeshRenderer、基础 SkinnedMeshRenderer 模型复用、内置几何体、Standard/URP Lit/Legacy/Unlit 材质、透明/裁切、方向光/点光/聚光、基础 Prefab 实例与变换/名称/活动状态/材质覆盖。
- 坐标约定为 position `(x,y,-z)`、quaternion `(-x,-y,z,w)`；缺失引用记录 `[Unity import]` 警告并使用回退材质或跳过。C# 脚本、Terrain、UI、物理、烘焙光照、任意组件覆盖、打包 metallic/smoothness 贴图不转换；相机仅保留元数据，不宣称 Unity 全兼容。
- 添加 `UnitySceneImporterSmoke`：真实 YAML→场景→J3O 往返，覆盖 PBR 标量、Prefab 四元数/材质覆盖和 Alpha 混合，不需要 OpenGL。两轮只读审查发现的问题已修复，包括 DataObject 注册、Prefab 多根/分量归一化、子模型祖先 Transform、URP Unlit 分派等。
- 验证：IDE diagnostics 无错误；按用户既有偏好未构建、未编译、未运行 smoke 或 SDK，所以可见预览、真实 Unity 项目兼容性和渲染效果均未验证。`build-impl.xml` 复用经 diff 确认仅项目名不同的同版生成文件；`genfiles.properties` 由 NetBeans 后续生成。git diff --check 最后一次执行遇到已知 0xC0000005 环境崩溃，不记为通过。未提交、未推送。

## 2026-11-17 CodeBuddy Nifty 编辑器修复

- 使用用户提供的 `D:\MyDowns\com-jme3-gde-gui.nbm` 作为参考，并将其中的 `Nifty-Editor0.5.7.jar` 替换到 `jme3-gui/release/modules/ext`，恢复包括 effects 在内的较完整 legacy 模型。
- `GUIWriter` 新增源 XML 合并：保存时保留 `<text>`、`<interact>`、效果及未建模属性；过滤原 XML 中不存在的 `x/y=-1px`；没有控件时不写入编辑器自动补充的默认 controls/styles。
- Visual 加载时仅对 legacy 模型过滤 `text/interact`，原始 XML 仍用于保存；`TextCreator` 恢复文本预览。选中组件后 Lookup 只发布一个 `GElementNode` 和 Palette，避免 `Multiple Objects - Properties`。
- Palette 新增控件优先加入当前选中的容器；拖放后强制重建 Nifty 绑定。属性失败时恢复旧值并刷新预览；undo/redo 异常被捕获并触发视图/XML 恢复。
- 验证结果：JDK 25 定向编译 4 个修改类通过；`NIFTY_XML_ROUNDTRIP_PASS`；`NIFTY_EDITOR_COMMAND_PASS`。后者使用真实 `J2DNiftyView`，覆盖文本预览、Panel width 属性、向选中 Panel 添加按钮、undo/redo、非法 width 回滚与预览恢复。
- 已部署到 `jme3-gui/build/classes`、`build/cluster/modules/com-jme3-gde-gui.jar` 和对应 ext JAR。需要重启 SDK；尚未执行完整 SDK 构建或人工 NetBeans 多视图交互验证。
- 隔离测试中的 `mygame.Main` ClassNotFound 属于测试 classpath 未包含用户项目；redo 后重复 ID 警告来自 legacy Nifty，后续可继续治理。
- 未提交、未推送。

## 2026-11-17 资产浏览器文件夹重命名与 DDS 目录保护
- `AssetContentPanel` 右键菜单新增 Rename folder：树右键作用于右键目录，网格右键作用于选中的单个文件夹；资产根目录不提供重命名。
- 改名使用 `FileObject.rename(lock, name, "")`，在后台 `RequestProcessor` 执行；校验单一名称、重名与可写父目录；当前浏览路径若位于被改名目录内则同步更新后重扫。NetBeans 主文件系统的 `GeneratedTextureFiles` 拦截器仍会同步生成纹理登记表。
- `GeneratedTextures` 新增 `isGeneratedOutputDirectory(Path)`：只读登记表，精确判断目录是否直接存放已登记的 DDS 输出，不依赖目录名；读取失败时 UI 侧 fail closed，禁止重命名。生成的 DDS 目录右键 Rename 项禁用并给出原因提示。
- `assetbrowser` 模块增加对 `com.jme3.gde.texturetools` 的编译依赖；Bundle 增加 Rename 相关文案；`GeneratedTexturesSmoke` 增加目录判断断言。
- 验证：IDE diagnostics/lint 无错误；按用户既有偏好未构建、未编译、未运行 smoke test 或 SDK。需在重建并重启 SDK 后人工验证普通文件夹改名、dds 目录禁用提示以及改名后引用与登记表一致性。未提交、未推送。

## 2026-11-17 Nifty 导航、属性与 ID 增强

- Visual `componentShowing/componentActivated` 会打开并显示 Navigator；Visual 选择同时设置多视图 TopComponent 和全局 Properties 的 activated nodes，不再依赖点击 Navigator。
- 属性表为组件补齐常用尺寸、对齐、布局和可见性属性；`childLayout/align/valign` 与布尔属性使用选项编辑器，字体和资源路径使用资源选择器。
- 尺寸输入通过 Nifty `SizeValue` 校验，纯数字自动补 `px`，支持 `px/%/%w/%h/*/sum/max/default`；颜色限定为 Nifty 十六进制格式，非法输入在面板内提示，不向 EDT 抛异常。
- `GElementNode` 监听模型并即时刷新 ID 对应的 name/displayName。新增源码级 `EditAttributeCommand` 覆盖 legacy 实现，使用实时 GUI 树检查 ID 唯一性，修复重命名后 undo 被旧 ID 注册残留拒绝的问题。
- `GUIWriter` 增加层级位置回退匹配，ID 重命名后仍保留原元素的 text/interact/effect。
- 综合测试再次通过：选项编辑器、`320 -> 320px`、尺寸 undo/redo、ID 节点刷新与 undo/redo、重命名后 XML 保真，以及此前全部 Nifty 冒烟项。
- 已重新部署模块 JAR；需重启 SDK。人工 NetBeans 交互仍未执行。未提交、未推送。
