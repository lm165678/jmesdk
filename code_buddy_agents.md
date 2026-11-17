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

## 2026-11-17 CodeBuddy Nifty 编辑器修复

- 使用用户提供的 `D:\MyDowns\com-jme3-gde-gui.nbm` 作为参考，并将其中的 `Nifty-Editor0.5.7.jar` 替换到 `jme3-gui/release/modules/ext`，恢复包括 effects 在内的较完整 legacy 模型。
- `GUIWriter` 新增源 XML 合并：保存时保留 `<text>`、`<interact>`、效果及未建模属性；过滤原 XML 中不存在的 `x/y=-1px`；没有控件时不写入编辑器自动补充的默认 controls/styles。
- Visual 加载时仅对 legacy 模型过滤 `text/interact`，原始 XML 仍用于保存；`TextCreator` 恢复文本预览。选中组件后 Lookup 只发布一个 `GElementNode` 和 Palette，避免 `Multiple Objects - Properties`。
- Palette 新增控件优先加入当前选中的容器；拖放后强制重建 Nifty 绑定。属性失败时恢复旧值并刷新预览；undo/redo 异常被捕获并触发视图/XML 恢复。
- 验证结果：JDK 25 定向编译 4 个修改类通过；`NIFTY_XML_ROUNDTRIP_PASS`；`NIFTY_EDITOR_COMMAND_PASS`。后者使用真实 `J2DNiftyView`，覆盖文本预览、Panel width 属性、向选中 Panel 添加按钮、undo/redo、非法 width 回滚与预览恢复。
- 已部署到 `jme3-gui/build/classes`、`build/cluster/modules/com-jme3-gde-gui.jar` 和对应 ext JAR。需要重启 SDK；尚未执行完整 SDK 构建或人工 NetBeans 多视图交互验证。
- 隔离测试中的 `mygame.Main` ClassNotFound 属于测试 classpath 未包含用户项目；redo 后重复 ID 警告来自 legacy Nifty，后续可继续治理。
- 未提交、未推送。

## 2026-11-17 Nifty 导航、属性与 ID 增强

- Visual `componentShowing/componentActivated` 会打开并显示 Navigator；Visual 选择同时设置多视图 TopComponent 和全局 Properties 的 activated nodes，不再依赖点击 Navigator。
- 属性表为组件补齐常用尺寸、对齐、布局和可见性属性；`childLayout/align/valign` 与布尔属性使用选项编辑器，字体和资源路径使用资源选择器。
- 尺寸输入通过 Nifty `SizeValue` 校验，纯数字自动补 `px`，支持 `px/%/%w/%h/*/sum/max/default`；颜色限定为 Nifty 十六进制格式，非法输入在面板内提示，不向 EDT 抛异常。
- `GElementNode` 监听模型并即时刷新 ID 对应的 name/displayName。新增源码级 `EditAttributeCommand` 覆盖 legacy 实现，使用实时 GUI 树检查 ID 唯一性，修复重命名后 undo 被旧 ID 注册残留拒绝的问题。
- `GUIWriter` 增加层级位置回退匹配，ID 重命名后仍保留原元素的 text/interact/effect。
- 综合测试再次通过：选项编辑器、`320 -> 320px`、尺寸 undo/redo、ID 节点刷新与 undo/redo、重命名后 XML 保真，以及此前全部 Nifty 冒烟项。
- 已重新部署模块 JAR；需重启 SDK。人工 NetBeans 交互仍未执行。未提交、未推送。
