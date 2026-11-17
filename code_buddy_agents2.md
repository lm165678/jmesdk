# CodeBuddy 独立工程上下文记录（jmesdk）

本文件由当前 CodeBuddy Agent 独立维护，用于减少多个 Agent 同时写入 `code_buddy_agents.md` 造成的冲突或覆盖。
它不替代 `AGENTS.md`、`DEV_HANDOFF.md`、`code_buddy_agents.md` 或引擎侧的 `RENDERING_QUALITY_ROADMAP.md`。
最终判断以当前源码、测试和实际运行结果为准。
本文件可随 fork 仓库提交，不保存凭据、私钥或令牌。

## 2026-11-17 纹理编码器归档纳入仓库与离线构建

- 本地 `build/texture-tool-downloads` 中七个官方压缩包齐全；已逐一核对官方 SHA-256，并复制到 `third-party/texture-tools`，合计 106881664 字节。
- `prepareTextureTools` 不再访问网络，只校验并解包仓库内原始压缩包；`url` 仅保留来源信息。解包产物仍由 `.gitignore` 排除，`buildSdk` 和纹理验证脚本继续使用该任务。
- `readme.md` 已改为说明干净/离线构建不依赖上游下载可用性。
- 使用实际引擎仓库参数执行 `gradlew prepareTextureTools --offline --rerun-tasks` 成功（4 秒），确认归档校验、解包和七个预期可执行文件布局。第一次未指定仓库时因默认 `JMESources` 未导出而在项目配置阶段失败，与纹理任务无关；没有运行完整 SDK 构建或模型转换。
- 已创建提交 `36f660ecc9a12e0f7c5537ef6b6fe9f58bd3ff16`（`Bundle texture encoder archives for offline SDK builds`），只包含纹理工具构建、归档和对应交接内容，不带入 RenderDoc 或其他 Agent 的工作区修改；未推送。

## 2026-11-17 RenderDoc 图标与 FrameGraph 分组

- 用户反馈 Options > RenderDoc 错用 OpenGL 图标，且 SDK 按钮截帧只有 RenderDoc 自动生成的 Compute/Colour Pass，没有引擎 FrameGraph 分组。
- `RenderDocOptions` 改用官方 `renderdoc-48.png`，来源为 RenderDoc 仓库 `qrenderdoc/Resources/icon.ico`；已查看生成图标并添加 `LICENSE-RenderDoc.txt`。工具栏相机截帧按钮保持不变。
- 引擎侧确认 `FrameGraph.execute` 已在每个实际 Pass 周围调用 `pushDebugGroup/popDebugGroup`，但 `GLRenderer` 仅在 `GraphicsDebug=true` 且支持 `Caps.GLDebug` 时调用 KHR_debug。SDK `SceneApplication` 原先没有开启该设置，因此不是 SDK 注入方式绕过了 FrameGraph。
- `SceneApplication` 现在仅在 RenderDoc 已成功加载且配置仍有效时，于预览上下文创建前设置 `GraphicsDebug=true`；普通未注入预览不开启。`RenderDocSupport` 中文步骤文档说明该行为及禁用恢复方式。
- `RenderDocSmoke` 增加 Options 注解/图标资源检查，并让原生截帧验证开启 `GraphicsDebug`，供后续人工检查 `.rdc` 中的 ViewPort/Pass 分组。
- 已执行图标来源下载/转换、图像检查和静态 diff/lint 检查；未构建、编译或运行 SDK/RenderDoc 原生测试。设置页实际图标及截帧分组仍需重建、重启和重新截帧验证。未修改引擎源码，未提交或推送。

## 2026-11-17 模型转 J3O 缺少纹理工具资源

- 用户在 `D:/MyWorks/Jme3/Projects/TestGame` 转换 `Models/Chair/SheenChair.gltf` 时，`TextureToolchain.installed()` 报告 `Texture Tools module resources are missing`。检查确认 SDK 中不存在 `jme3-texture-tools/release`，构建簇中也没有已安装的 `texture-tools`；这不是模型或游戏项目资源损坏。
- 根因是纹理工具代码要求模块随附 Compressonator/astcenc，但干净工作区没有这些二进制，原 `buildSdk` 也没有准备步骤。
- `build.gradle` 新增 `prepareTextureTools`：从 GPUOpen-Tools 和 ARM-software 的官方 GitHub Release 下载固定版本的七个平台包，按官方发布的 SHA-256 校验后展开到既有目录布局，并检查全部预期可执行文件。`buildSdk` 现在依赖该任务；`cleanSdk` 可清理生成资源。
- 生成目录已加入 `.gitignore`，下载缓存位于忽略的 `build/texture-tool-downloads`。两个纹理验证脚本会在直接 Ant 编译前运行资源准备任务。`readme.md` 记录了自动准备和单独恢复命令。
- `TextureToolchain.installed()` 的异常现在提示运行 `prepareTextureTools` 并重建 SDK；不静默跳过纹理压缩，仍让失败转换保留原 J3O。
- 仅做源码、官方 Release 元数据和 `git diff --check` 静态检查；后者通过，IDE diagnostics 无错误。未实际下载、解包、构建、编译、运行原生编码器或重试用户模型转换，因此任务语法、压缩包布局及最终转换尚需实际运行验证。
- 下一步：关闭 SDK 后运行 `build-and-run-sdk.bat`，让它准备工具、重建并启动，再重试转换。未修改 `TestGame` 或原始模型；未提交或推送。

## 初始化记录（2026-11-17）

- 已读取 `jmesdk/AGENTS.md`、`jmesdk/DEV_HANDOFF.md` 和原有的 `jmesdk/code_buddy_agents.md`。
- 本轮仅创建此独立交接文件，没有代码修改、构建、测试、提交或推送。
- 交接文档记录的配套源码快照为 engine `4ee85daa4`、SDK `e38d71c5`；这只是源码快照，不代表新验证过的二进制版本。
- 后续本 Agent 在 `jmesdk` 的有意义修改记录到本文件，避免与其他 Agent 共用同一交接文件。

## 当前约束

- 保留用户、GPT 和其他 Agent 的无关工作区改动，不覆盖既有交接内容。
- Swing 组件创建与编辑属于 EDT；场景变更和克隆属于所属渲染线程；预览安装使用自己的渲染线程，不阻塞 EDT 等待渲染或长耗时 I/O。
- 图编辑必须保留 undo/redo 与 dirty 状态；保存失败必须保留原资源；非原子回退需要明确说明。
- 用户可见行为或默认值变化要更新源码注释，并提供分步骤中文使用示例；J3O 保持通用扩展名。
- 根据交接文档，用户多次要求不做构建或编译以节省成本；除非本轮明确授权，不默认执行构建或编译。
- 只记录实际执行过的构建和测试；编译成功不能作为界面或原生渲染正确的证据；提交或推送成功必须经过核实。
- 每次有意义的代码修改结束前更新本文件，并按仓库约定更新 `DEV_HANDOFF.md`，区分已验证行为、限制和下一步。

## 恢复开发时的提醒

- Lemur 与双 GUI 可视化编辑器、SDK Coding/Scene Agent 仍是待办；计划不等于已实现，待办本身不授权实施。
- Nifty 拖放与序列化、RenderDoc 关闭阶段崩溃、压缩纹理编码器原生崩溃等问题仍未完全验证或未定位根因。
- 资源操作中的依赖感知重命名、删除引用检查、派生压缩纹理重建尚未完成。
- 大世界表面卡片预算与流送同引擎侧路线图相关；需要引擎联动时同时读取引擎文档。
