## 2026-11-22 Compact root-motion chooser and branch highlighting

- Root Motion chooser uses a bounded prototype width and short leaf names, with complete track paths in the selected-field and dropdown-row hover tooltips. Long leaf names are truncated. Selecting a joint highlights that joint and all descendant joints/bones in yellow, with a larger cross at the selected root and dimmed unrelated skeleton. Node selections highlight descendant node markers/links and their skeletons. Selection is applied on the preview render thread and does not alter saved model materials or animation definitions.
- While the dialog is open the overlay is temporarily visible; Apply, Disable, Cancel or window close restores the prior skeleton visibility. Preview replacement retains the temporary focus. This is skeleton/target highlighting, not recoloring skinned mesh vertices.
- JDK25 targeted compilation, AnimationEditorSmoke (including long-path labels/full hints and existing save/reload tests), and native AWT/OpenGL AnimationPreviewSmoke passed. RTX5080 320x240 framebuffer verified yellow pixels, descendant selection and hidden-overlay restoration (ROOT_MOTION_BRANCH_HIGHLIGHT_PASS). Core candidate passed both Swing and native tests and was installed in build/cluster/modules with matching SHA-256; restart SDK. Backup and logs: build/root-motion-ui-check. Full interactive Paladin dialog/hover was not manually tested; no user asset writes or full SDK distribution build.
- Paired bases remain SDK 7b4f4b1b / engine 44031b02e plus working trees. No engine code changes. Unrelated changes remain unstaged.

## 待办：编辑器使用手册——动画行为状态树（2026-11-22）

- [ ] 编写编辑器使用手册，将状态图、状态内 1D/2D 混合及 Java 参数驱动列为动画行为状态树章节。
- [ ] 说明 States / 1D and 2D blend 的 Name 与 State Graph 节点同步改名，并更新连线引用和 Entry；不修改源 Clip 名称，单纯改名不会自动配置混合。
- [ ] 使用单节点 MoveState 示例：设为 Entry，声明 Float 参数 Speed，配置 Idle=0、Walk=1、Run=3 的 1D 采样点。改变 Speed 在同一状态内混合相邻 Clip，无须 Idle/Walk/Run 状态间连线；解释端点、插值和超出范围时的行为。
- [ ] 对比 1D 单参数与 2D 双参数（左右/前后）的混合，区别于 MoveState → Attack / Jump 等状态转场；参数变化只有满足已配置的转场条件才会切换状态。
- [ ] 提供 Save J3O、加载模型、查找 Composer 所在节点、AnimationGraphPlayer.attach、setFloat/setBoolean/trigger 和 restart 的完整 Java 示例；解释参数名称/类型、Entry、默认值及游戏更新线程要求。
- 用户要求记得提醒：以后继续动画编辑器、动画状态图或编辑器使用手册工作时，主动提醒补充此章节。这是会话交接提醒，不是定时通知；当前无可用自动提醒工具。本次仅登记手册计划，尚未编写完整手册。

## 2026-11-22 Paladin 连续动画关联与转换时复用材质

- 勾选 Show skeleton / targets 后，首次关联或 Apply preview 会在挂接预览模型后提前刷新子树，触发 `Illegal mat param update`，右下实时参数无法启用。骨骼叠加层现在只读世界变换，由预览根按父到子更新场景；应用失败时显示异常。打开已有 J3O 的旧预览取景改进继续使用。
- Animation Links 可在动画图有未保存修改时继续添加剪辑或库。刷新 Composer 数量，保留草稿，并立刻单独预览新剪辑（优先有运动的剪辑）。需要预览现有图时点 Apply preview。移除链接仍要求先保存图，并在按钮操作时明确提示。剪辑选择框中单剪辑资源显示文件名（如 Idle），有多个剪辑时显示文件名和剪辑名，内部 ID 继续用于绑定与存档。
- Convert to J3O 与外部拖入的每个转换对话框都可选项目内已有 J3O 作为材质来源，默认空白、继续导入源材质。按网格及材质名匹配，无法唯一匹配时报错；复制现有材质并沿用纹理键，跳过源模型 DDS 和 .j3m 的生成。拖入批处理逐文件询问，复用对象须已存在于项目。目标材质参数后续变化时重新转换可取得新副本。
- SDK 576ecbe6 / engine db8ab1149 加当前工作树。RTX 5080 AWT/OpenGL 加断言使用实际 Paladin.j3o + Idle.j3o 验证骨骼叠加层开启时立即重建预览，切换 Test 后 Idle 骨骼旋转改变。Swing 保存/重载、草稿保留、按钮启用及剪辑标签通过。使用本地 SDK 当前 ufbx 二进制验证 Idle.fbx、Walk.fbx 复用 Paladin 的 DDS，重载和依次关联两个动作通过，未修改 TestGame 文件。实验用新 ufbx 与现有旧产物混用仍可能遇到严格绑定姿势校验，此次本地 SDK 候选模块不变更 JNI 二进制。
- 候选模块 `build/skin-preview-check/core-animation-material-candidate.jar` 已验证。尝试更新本地启动模块时 Windows 报 JAR 正由其他进程使用，等待退出 SDK 后更新。

## 2026-11-22 Paladin Idle FBX / J3O 预览取景

- 实测 TestGame 的 `Models/Paladin/Idle.fbx` 与现有 `Idle.j3o` 都包含 `mixamo.com` 剪辑（约 1.95 秒，358 条轨道）。旧 ufbx 导入的静态包围盒高度约 172.5，蒙皮后约 1.7 米，动画编辑器和资产浏览器按静态范围取景会让角色看不清。
- 两个预览均以首帧蒙皮后的模型范围取景，在预览副本的蒙皮分支上跳过过时的包围盒剔除。新 ufbx 导入将网格顶点与骨架统一转换到米。已有 J3O 可直接预览，之前生成并保存的碰撞体尺寸需要单独重建或重新导出。用户的 FBX、J3O 和纹理未写入。
- SDK 576ecbe6 / engine db8ab1149 加当前工作树。JDK 25 定向编译及 Windows x64 ufbx JNI 构建通过。RTX 5080 AWT/OpenGL 开启断言验证两个实际文件的动画编辑器取景与资产浏览器播放，像素回读确认显示与动作变化。原始 FBX 还经过纹理处理和 J3O 保存重载，生成两个 DDS，新 J3O 也通过取景和播放检查。未覆盖完整 SceneComposer，临时 DLL 与测试类均在 `build` 下，未替换启动模块或重打 SDK，其他平台 JNI 待重新构建。

## 2026-11-22 默认 HDR 与场景保存

- 用户确认内嵌 3DViewer HDR 效果正常。没有 WorldSettings 的场景默认 HDR (scRGB)；已有场景恢复原有选择。保存和关闭时保存都会写入游戏输出，即使没有手动操作下拉框。显式选择跟随应用也保存控制器，避免下次打开被当成新场景。
- 引擎新 WorldSettings 默认 Hdr；旧文件缺字段仍跟随应用。SimpleApplication 加载并挂载 J3O 后自动配置，不支持 HDR 时回退 SDR。配对基线 engine db8ab1149 / SDK 576ecbe6 加当前工作树。
- 本次只检查源码和改动文件的 diff 格式，遵照用户要求未编译引擎或 SDK，未运行新默认值的运行时验证。引擎和 SDK 其他工作树文件已有空白检查问题，未改动。
## 2026-11-22 StateName 与混合播放时长

- 状态名称列改为 StateName 文本输入，禁止空白和重复名称。改名只更新状态名、入口和转换引用，不替换 Clips；支持撤销。Blend parameter 和 Clips 的选择方式保持不变。Core 模块 jar 构建成功，本地启动模块已更新，AnimationEditorSmoke 通过。
- 引擎修复最长片段拖慢整个混合的问题：按当前权重计算周期，积累归一化相位，根运动使用同一相位。动画图单测、ROOT_MOTION_J3O_PASS、AWT/OpenGL AnimationPreviewSmoke 通过。新引擎仅编译及测试，需运行 build-local-engine-sdk.bat 更新正式导出和 SDK 引擎库；配对基线 engine db8ab1149 / SDK 576ecbe6 加工作树。

## 2026-11-22 纹理窗口导航

- 压缩纹理窗口和普通纹理编辑器默认 Fit，提供 Fit 按钮；窗口尺寸变化时保持完整显示，手动缩放退出 Fit。滚轮缩放，中键/右键拖动滚动视图；压缩窗口及普通编辑器未启用工具时也可左键拖动。超出视口的图像通过平移查看，视图边界仍受滚动范围约束。
- 普通编辑器裁剪只响应左键，取色为左键前景色、Shift+左键背景色；右键专用于导航。缩放/平移不修改图像内容。
- JDK 25 两个模块 jar 构建成功，已更新 build/cluster/modules 的 texturetools 和 textureeditor JAR。texturetools 完整 netbeans 目标的 release 复制被运行中的 JNI DLL 文件锁阻止，随后只用 jar 目标更新 Java 模块，未强制关闭 SDK 或替换 DLL。
- TextureNavigationSmoke 使用实际模块 JAR 在 Swing EDT 验证默认 Fit、滚轮缩放、右键平移、恢复 Fit 及裁剪模式右键不触发裁剪，TEXTURE_NAVIGATION_PASS。未在完整 SDK 中目视验证，重启后加载新模块。配套基线 SDK 576ecbe6 / engine db8ab1149 加当前工作树。

## 2026-11-22 动画图相向端口确认与本地模块更新

- 用户确认：允许 Walk 左侧拖到 Idle 右侧，禁止 Walk 右侧绕回 Idle 左侧。横向分开的节点只接受相向端口；返回线不再要求先有正向过渡。已有返回过渡也在节点之间绘制，节点上下交错时保留中间空隙的绕线。过渡 From/To、条件及资源保存格式未更改。
- JDK 25 Swing 回归覆盖外侧连接拒绝、返回优先创建、删除正向边后的返回路由、撤销/重做及保存重载，通过 ANIMATION_EDITOR_SWING_SAVE_RUNTIME_PASS。仅用当前 SDK runtime JAR 依赖定向编译动画编辑器成功，更新 Core JAR 中该编辑器类及中英文提示，并将生成的 JAR 放在测试 classpath 首位重跑通过。
- 已更新 run-sdk.bat 使用的 build/cluster/modules/com-jme3-gde-core.jar，备份位于 build/warrior-graph-check/core-before-facing-ports.jar；安装文件与测试 JAR SHA-256 一致。运行中的 SDK 需要重启。未构建完整 SDK，也未重建其他 HDR 开发中的代码；未手动操作完整编辑器。兼容基线仍为 SDK 576ecbe6 / engine db8ab1149 加当前工作树。

## 2026-11-22 动画图反向连线与 Minotaur Idle 剪辑

- 相对两侧的蓝点支持双向开始拖动，过渡方向跟随拖动起点，禁止同侧连线。已有正向边时，返回过渡在相向端口之间走下方弧线；节点移位使端口无法相向时仍可按普通路线绕行。扩大鼠标吸附范围。单剪辑状态从 Name 下拉框选择剪辑名时同步 Clips，撤销同时恢复剪辑和引用。混合状态仍单独配置样本。
- TestGame 的 Minotaur J3O 中 Warrior_Idle 原先绑定 Warrior_Walk。已备份原文件并只修复这一条绑定。原文件重载后的 WARRIOR_BOOL_AND_POSE_PASS 覆盖 ToWalk 的 false → true → false 三段、过渡完成和实际骨骼旋转。Swing AnimationEditorSmoke 通过。已尝试重建本地 Core 模块，当前引擎导出缺少另一项开发中的 WindowsHdrAwtPanel，旧的运行模块尚未更新。

## 2026-11-22 Paladin FBX 硬件蒙皮预览

- 根因是首次预览内存中的 ufbx 网格时，Mesh.prepareForAnim(false) 假设 J3O 加载时创建的 GPU 骨骼缓冲已存在。引擎现在从有效的 CPU 骨骼索引和权重按需构建硬件缓冲。FrameGraph 的 SceneQueue 只是异常传播位置。
- RTX 5080 的 AWT/OpenGL 动画预览中，用 TestGame 的 Paladin 原始 FBX 及经 SDK DDS 流程保存重载的临时 J3O，验证四个 SkinningControl 都能启用硬件蒙皮。未打开完整 TestScene2，未改动用户资源或重建最终 SDK。配套引擎基线 db8ab1149、SDK 基线 576ecbe6。

## 2026-11-22 3DViewer 原生 HDR 预览

- Windows 上用默认 AWT 3DViewer，选择 Post processing → Game display output → HDR (scRGB) 会即时请求内嵌原生 HDR，同时保留这个场景的游戏输出配置。选择 SDR 或跟随应用设置恢复 SDR 预览。内嵌 HDR 直接呈现共享的 FP16 纹理，Windows HDR 或 GPU 互操作不可用时走现有 AWT 路径。可选的 LWJGL Canvas 路径继续用 SDR。
- 配套 SDK 576ecbe6 / 引擎 db8ab1149 的当前工作树。独立探针在 RTX 5080 和 125% Windows 缩放下验证 HDR 子窗口可见，系统命中仍落在原 AWT Canvas。遵照用户要求，本轮未构建 SDK 或引擎；集成后的真实鼠标操作、拖靠和 TestScene2 目视效果待确认。

## 2026-11-22 场景游戏输出与 Film LUT 开关

- SceneComposer → Post processing → Game display output 提供跟随应用启动设置、SDR 和 HDR (scRGB) 三项。保存到场景根的 WorldSettings，旧 J3O 默认跟随应用。修改游戏输出不会重建或覆盖场景的 FPP 序列化数据。配套 SimpleApplication 在挂载场景后自动应用，移除后恢复启动设置。内嵌预览行为见后续「3DViewer 原生 HDR 预览」条目。
- 修复 Film / combined LUT 开关自身被 Film 参数依赖禁用的问题。选中后可编辑 Film 参数，关闭后可以再次启用。
- SDK 576ecbe6 / 引擎 db8ab1149 加当前工作树配套，定向编译通过；POST_AA_SETTINGS_PASS、POST_DISPLAY_SETTINGS_PASS 验证 Swing EDT 下选择状态、三个输出选项和 Film LUT 的 J3O 往返及原有 FPP 数据保留。RTX 5080 独立游戏窗口通过 WORLD_DISPLAY_OUTPUT_J3O_OK，Film LUT 的原生 OpenGL 回归通过 HISTOGRAM_FILM_POST_GPU_PASS。
- 本地引擎导出成功，SDK 打包通过本地引擎配套检查后按用户要求停止。最终 SDK 打包由用户执行。

## 2026-11-22 新增灯光的视口选中

- 修复点光和聚光灯用 W 拖动后回弹。平移回调发生在节点应用新位置之前，因此用目标局部坐标经父节点转换后写入灯光的世界位置。已重建本地启动用的 Scene Composer 模块。开启 `-ea` 的 AWT/OpenGL 测试在旧模块复现点光回弹，新模块的 GIZMO_LIGHT_DRAG_PASS 覆盖两种灯光的拖动、释放、撤销和重做。未打包完整 SDK。
- Scene Explorer 新选中的灯光会在渲染线程取得或创建可编辑对象，初始化位置和方向后显示当前变换手柄。拖放与右键添加使用同一条选中链路。后续选中会使旧的异步回调失效。环境光没有空间手柄。
- SDK 576ecbe6 和引擎 db8ab1149 当前工作树的 JDK 25 定向编译和 RTX 5080 AWT/OpenGL 4.3 测试通过。GIZMO_ADDED_LIGHT_SELECTION_PASS 走新增灯光的 Explorer 选择入口，验证视口没有焦点时方向光、点光和聚光灯显示手柄，点光和聚光灯使用落点。测试未模拟系统级拖放，未打包 SDK。
- 修复 SDK 启动默认开启断言时的 `Illegal light list update`。灯光手柄创建时只初始化逻辑状态，几何状态由场景根按父到子更新。使用同一个 AWT/OpenGL 测试并开启 `-ea`，旧代码复现方向光断言，修复后 GIZMO_DIRTY_PARENT_LIGHT_PASS、GIZMO_ADDED_LIGHT_SELECTION_PASS 和 TRANSFORM_GIZMO_PASS 均通过。未重新打包 SDK。
- 重建了 `run-sdk.bat` 使用的 `build/cluster/modules/com-jme3-gde-scenecomposer.jar`。旧 JAR 的第 661 行仍包含直接更新子节点的调用，可触发灯光列表和材质参数两条断言。已检查新 JAR 字节码，并用该 JAR 开启 `-ea` 重跑 AWT/OpenGL 手柄测试。Q 选择模式恢复三轴箭尖，仍只用于选择，W/E/R 继续负责变换。旧 SDK 进程需要重启才能加载新模块。

## 2026-11-22 动画连线与视口手柄

- 动画状态图从右侧出口拖到左侧入口创建过渡。挪动节点会重算连线，纵向错开的节点优先走中间空隙；双向过渡各自保留源和目标端口。图的运行时数据和保存格式沿用现有实现。
- 平移和缩放轴杆使用实体网格，旋转环和端部手柄加粗。命中测试优先使用鼠标下的网格，再在周围 4 个视口像素内查找当前工具可操作的手柄。
- Directional 和 Spot 的箭尖用沿局部 +Y 定向的锥体，跟随灯光方向并避开不同引擎包中 Cylinder 半径顺序差异。箭身总长 108 逻辑像素。
- 基于 SDK 576ecbe6 和引擎 db8ab1149 的当前工作树，JDK 25 定向编译通过。AnimationEditorSmoke 验证双向过渡的端口、禁止同侧端口及上下错位的绕线。RTX 5080 OpenGL 4.3 AWT 测试验证 W/E/R 的近边缘命中、灯光箭尖方向及尺寸。未重打包 SDK。

## 2026-11-22 动画 2D 混合和骨骼遮罩覆盖

- States / 1D and 2D blend：给同一状态选择两个 Float 作为 X/Y，双击剪辑或阈值编辑每个样本的 X,Y 坐标。第一版采用 Cartesian 权重，坐标唯一，支持 2–64 个样本。清空 Y 恢复 1D，并按当前样本顺序重建递增阈值。
- Masked Override：选 Boolean 开关、循环骨骼剪辑、淡入淡出秒数和骨骼树的一个或多个分支。在移动图保留全身跑步，从胸椎选择子树覆盖持枪姿势；不选根、骨盆或腿即可保留下肢。Apply preview 后开启骨骼显示可看到绿色覆盖分支，并在 Live parameters 操作两个 Float 和 Boolean。保存写入 J3O，支持撤销。第一版是一个覆盖层，无叠加和独立层状态机；根运动仍在待办。
- Windows JDK 25 定向 SDK 编译、AnimationEditorSmoke 和 RTX 5080 AWT/OpenGL AnimationPreviewSmoke 回归通过。MASKED_2D_BLEND_PASS 验证真实 J3O 保存重载、双参数权重、淡入淡出、腿部姿势独立、重置和旧 1D 定义兼容。配对基线 SDK 576ecbe6、engine db8ab1149 加本次工作树。未手动操作新增对话框，未重打包整个 SDK。

## 2026-11-22 Automatic LOD and DDS repair

- Convert to J3O exposes Desktop/Mobile start LOD, defaulting to 0. Each geometry with usable generated LODs gets an engine LodControl. Model Detail and the legacy LOD action also attach a control on bake and include it in undo with the mesh. Compatible engine base db8ab1149 and SDK base 576ecbe6, with the paired working-tree changes.
- Ordinary J3O save and opening in the SDK fill in missing PNG/JPEG DDS sidecars with the bundled Java BC3 encoder. Existing DDS files survive unchanged. Output is relative to the source, e.g. `Textures/Wall/brick.png` -> `Textures/Wall/dds/brick.png.dds`. Multiple models or materials share it. SDK preview uses the source if generation fails. A legacy direct DDS key can be repaired at the old output path if the registry and source survive.
- SDK moves update logical texture keys and reuse existing generated bytes. Legacy registered outputs may keep their original path for direct J3O references. J3O copies in the same asset root share textures. Generated DDS directories cannot be copied, renamed or deleted separately. Single DDS deletion keeps the mapping for repair. Moves done outside SDK file operations do not update the registry.
- Targeted JDK 25 compilation and MODEL_LOD_AND_DDS_PASS cover per-geometry LOD with shared meshes, external J3M save, DDS reuse and deletion followed by J3O reopen, same names in different folders, source moves and reload after copying the J3O. GeneratedTexturesSmoke also passed through NetBeans FileObject operations, including single-file deletion and generated path protection. The full SDK package and visible editor workflow were not run.

## 2026-11-22 Scene, assets and animation editor

- States / 1D blend uses dropdowns for state names and Float/Boolean parameters. Clips or Thresholds opens the ordered sample editor with one clip dropdown and one numeric threshold per Float sample. Boolean and empty parameters use one clip at threshold zero. Renaming keeps the entry and transition references and is a single undo step. The JDK 25 AnimationEditorSmoke passed, including dropdown selection, threshold validation and undo. Full SDK packaging was not run for this edit.

- Scene Composer keeps transform handles at the viewport scale on Q/W/E/R, even with a stationary pointer. W uses the projected local/world axis. Drag vertically to change depth when an axis points almost directly at the camera. A blank click clears selection on release. Model removal clears the wireframe, handle and active drag. Other selected models stay selected.
- Selection, snapping & cursor has a default-on Selection highlight. The tools viewport uses scene depth and draws a 1.5 logical pixel orange outline from the selection mask. The model surface is unchanged. Light and audio viewport markers use Feather SVGs at 36 pixels. Directional and spot arrows are 86 pixels long with a solid shaft. Spot angles are displayed in degrees and stored in radians. The paired engine adds a separate Light Intensity property, default 1.
- Physics & effects can play, pause/resume, or Reset physics. The preview snapshots physics-controlled poses and Bullet values. Scene saves and Save on Close reset the preview before writing. Conversion to J3O has an off-by-default Separate collision bodies option for static triangle geometries, using the selected Mesh/Box/Sphere shape. Existing controls, skinned character collision and terrain handling are preserved.
- Asset Browser raster previews decode through the image editor without a project asset manager. Double-click opens the image window. Properties shows the file or folder path, name and disk size with KB/MB/GB. The dialog shows a preview memory estimate when available. A failure to read or write the thumbnail cache leaves the decoded image usable.
- Opening the Animation Editor, Apply preview and Reset initialize live Boolean controls to false. The authored defaults in Parameters and J3O remain available for runtime. Transitions use the source right port and destination left port at every layout position, including return links.
- Compatibility bases SDK 576ecbe6 and engine db8ab1149, plus the current working-tree changes. JDK 25 targeted compilation, native Minie/J3O round trip, Sponza JPEG thumbnail/folder size, RTX 5080 OpenGL 4.3/AWT readback and the Swing animation graph/save smoke all passed. The full Gradle SDK build is blocked by daemon loopback access in this session.

## 2026-11-22 动画根运动

- 动画编辑器 Root Motion 选择基础动画中的根骨骼或节点，启用自动移动或仅提取。Apply preview 后勾选 Preview root movement 可对比实际移动和原地姿势。Save J3O 保存；关闭根运动再保存恢复普通播放。预览只操作副本，Reset 会重装预览姿势。
- 勾选自动移动后，加载 J3O 并加入正常更新的场景即可，无需手动 attach。首次逻辑更新会启动已配置的动画图并应用 XZ 位移和 Y 轴转向。仅提取模式保留角色控制器的移动决定权，Java 通过 AnimationGraphPlayer.setRootMotionConsumer 接收局部增量。垂直动作保留在姿势中，物理墙体碰撞仍待项目级验证。
- ROOT_MOTION_J3O_PASS 已验证合成的移动和转向剪辑、1D/2D、过渡、循环、暂停/重置/时间跳转、带旋转缩放的骨架父节点、外部关联和内嵌 J3O 重载。Windows 定向编译、Swing 动画编辑器及 AWT/OpenGL 预览回归通过。配对基线 SDK 576ecbe6 和 engine db8ab1149 加工作树。未手动操作新增对话框，未运行完整 SDK 打包。

## 2026-11-22 Animation editor linked clips and embedded export

- Open in Animation Editor accepts a rigged J3O with zero clips. It uses the selected node as composer owner when a rigged subtree has no composer with clips; existing double-click defaults are retained. Animation Links adds a single external clip or a whole library, removes/reloads links and can preview a source clip on a different compatible J3O. Link changes validate and save as an asset operation; save other graph edits first. Saved graphs must remain valid when removing links. Deleting the last saved state can clear the graph so an obsolete reference can be removed. Ordinary saves keep external clip references.
- Export Embedded J3O creates a new file and preserves the source model. Uses AnimationLinkResolver.embed on an isolated copy, with no requirement that the composer and skinning control occupy the same node. No dependency on external animation J3Os remains in the export. Materials may still reference external DDS or other assets. The legacy Merge Animations action was not changed.
- JDK 25 targeted compilation against the companion engine, plus AnimationLinksSmoke verified disk J3O references, cycle recovery, multi-level deduplication, updated source clips, separate instance poses and independent ufbx animation files associated with a same-rig model. Windows test uses tools/tests/AnimationLinksSmoke.java and the upstream Maya fixtures from the local ufbx checkout. Source bases SDK 576ecbe6 + working-tree changes and engine db8ab1149 + this feature. Full SDK rebuild and deployment pending.
- On Windows, both ufbx fixture clips changed the exported actor's skin matrices after reloading from an asset directory containing only the actor J3O. AnimationEditorSmoke (headless Swing/save) and AnimationPreviewSmoke (RTX 5080 OpenGL 4.3/AWT) passed, including mounting an inactive model before installing a playback graph. The new link dialogs were not exercised interactively.

## 2026-11-22 Asset Browser previews

- The Preview pane has an independent model viewport. Drag to orbit, scroll to zoom, or use Fit. Models with animation clips have a clip selector and Play/Pause. The preview keeps animation and deformation controls but removes other controls from its own model. Closing the pane or changing assets stops playback and releases the viewport.
- Textures support zoom, pan and Fit with dimensions and zoom shown beside the file size. DDS/ASTC thumbnails use the compressed texture decoder. HDR goes through the texture editor's display conversion. Existing texture error cards use the old cache revision and are regenerated. The audio pane shows the waveform, duration and Play/Stop for WAV/OGG. Other audio formats need a registered project loader.
- The pane and status bar show file size and a preview memory estimate in KB/MB/GB. The estimate counts decoded texture or PCM data, or distinct model vertex/texture buffers. It does not measure GPU residency or complete object overhead. The bottom right also shows the current and maximum JVM heap.
- JDK 25 targeted compile and `tools/tests/AssetInspectorSmoke.java` passed against SDK 576ecbe6 and the exported engine db8ab11. The smoke decoded the three TestGame room DDS files and Path.hdr into thumbnails, checked Swing zoom and WAV controls, and used RTX 5080/OpenGL 4.3 offscreen to check orbit, zoom, animation, pause and viewport cleanup. Full SDK build, visible editor interaction and audio output were not verified.

## 2026-11-21 Optional SDR output dithering

- AppSettings.SrgbOutputDithering defaults Off. LWJGL3 shader sRGB blit and SDK 3DViewer can opt in to a fixed 4x4 ordered pattern after sRGB encoding, within one 8-bit output code. Alpha and the linear image are unaffected. Separate scene preview option is off by default and applies live when shader output is enabled.
- Targeted Java 11 engine and Java 25 SDK compilation passed. RTX5080 native SRGB_PRESENTATION_GPU_PASS covered unchanged RGB/alpha in the presentation path, a quarter-code near-black input becoming a subpixel pattern when enabled, and a return to uniform black when disabled. This verifies output quantization only. Visible TestScene2 HDR comparison and SDK deployment remain pending.

## 2026-11-21 ufbx JNI FBX → J3O

- SDK 的 Autodesk 模块通过 AssetManagerConfigurator 注册 ufbx 作为默认 FBX 加载器。现有 Convert to J3O、模型预览和 SDK 项目中通过 AssetManager 加载 FBX 共用该加载器。ufbx 0.23.1 原生代码解析 ASCII/二进制 FBX，Java 直接创建 JME 模型，无 glTF/GLB 中间文件。模型、骨骼和动画以普通 JME 对象保存，游戏运行时不依赖 ufbx。
- 使用 `tools/build-ufbx-jni.ps1 -UfbxSource <本地 ufbx 源码目录>`，可传 `-CMake <cmake.exe>`。本地 Windows x64 构建把 DLL 和许可证放进 Autodesk 模块的 release 目录。CI 固定 ufbx v0.23.1 源码提交，预编译 Windows/Linux/macOS 的 x64、ARM64 库，SDK 构建从同次运行的产物汇集至 lib/ufbx/<平台>-<架构> 并由 NetBeans 模块打包。正式 CI 检查六个平台的库和许可证；用户运行时无需下载或编译。`-J-Djme.sdk.fbx.backend=legacy` 加重启恢复旧导入器，`-J-Djme.sdk.fbx.library=<绝对路径>` 用于本地测试。缺库时报告安装提示。
- 保留节点层级、几何枢轴、三角化、法线、UV0、材质槽、基础 PBR 贴图、线性蒙皮及节点/骨骼 TRS 动画。按动画堆栈生成 AnimClip，以 60 Hz 采样并保留末帧，蒙皮保留归一化的四个最大权重。Blend Shape、复杂材质网络、附加 UV、非线性/多重蒙皮及独立动画文件重定向尚未实现。尚未用生产资产对照枢轴、复杂蒙皮或渲染结果。
- FBX 内嵌图片提取至模型名.textures，DDS 生于相邻 dds 目录。项目内外置 PNG/JPEG 在原图同级 dds 目录生成。优先用 Texture Tools 的 BC3 与完整 mip，自动模式在没有平台编码器时复用 SDK 自带 Java Squish 写 BC3。J3O 材质保存直接 DDS 资产路径。已有 glTF/GLB 逻辑 PNG 键与 DDS 映射保留。编解码失败在打开 J3O 目标前终止。
- 验证：Windows x64 CMake/MSVC JNI 构建通过，JDK 25 定向编译通过，Autodesk NetBeans 模块在独立 cluster 构建并打包 DLL、许可证和服务注册。UfbxConversionSmoke 验证静态 12 三角面模型、ASCII 刚性动画、二进制蒙皮动画重载后骨骼矩阵变化、外置与内嵌纹理生成 DDS 后仅复制 J3O 和 DDS 即可由独立 DesktopAssetManager 加载，以及 7×5 图片通过 Java Squish 生成三层 mip 的 BC3 DDS。其余目标的 CI 构建、完整 SDK 构建及可视验证待执行。兼容基线 SDK 576ecbe6 + 本次工作树，Engine db8ab1149，无引擎 Java/C 源代码改动。
- 本机 Gradle 验证受守护进程回环连接限制，未验证完整 CI 汇集流程。持续规则已写入 SDK/引擎 AGENTS.md：新增 JNI 按 Bullet 方式提前构建并随 SDK 打包，发布时检查目标齐全。
- 回归检查发现现有真实 GLB 样本在纹理准备前已丢失内嵌图片的 TextureKey。已用改动前的 SDK/引擎 JAR 复现同样失败。模拟内嵌图片及逻辑键/DDS 映射用例通过。此已知问题与本次 ufbx 路径独立，真实 GLB 的自动提取仍需修复。

## 2026-11-21 Unity 导入插件分阶段计划（部分待实施）

目标：从 Unity 资产包或项目资源导入可重载的 J3O。FBX 产出模型 J3O，Prefab 产出独立、可复用的预制体 J3O，场景 J3O 保存预制体资源引用及实例覆盖，支持范围内的 C# 行为在导入时生成 Java Control。现有导入器已能恢复标准资源包及导入部分文本场景和预制体，当前输出是快照，详见后续历史条目。本计划尚无实现、配对版本或验证结果。

### 1. 先验证 FBX → J3O

- [ ] 用带层级与枢轴的静态 FBX、带纹理与多个材质槽的 FBX、带蒙皮和动画的 FBX 建立基准。记录源模型的尺寸、轴向、节点、材质槽、骨骼与动画，便于对照。
- [x] SDK 模型转换采用 ufbx JNI 直接导入 JME 模型，保存 J3O 和外置 DDS。见上方实现与样本验证。独立分离式 glTF 输出和 Blender 生产资产对照仍待验证。
- [ ] 核对单位和轴向、父子变换、枢轴、材质槽、UV、法线、贴图、骨骼绑定与动画。明确坐标转换由哪一层负责，避免 FBX 转换与 Unity 场景组装重复翻轴。基础模型通路完成后再关联 Unity .meta/.mat，按材质类型映射 PBRLighting、Lighting 或 Unshaded，记录还未支持的着色器与贴图通道。
- [ ] 验收：三类样本均可重复转换，重载的 J3O 仍能正常预览和播放动画。失败必须报告出错文件与阶段，且保留已有可用输出。以样本结果决定桥接是否作为默认路径。

#### 模型转 J3O 共用的外置 DDS 流程

- [ ] 新的 FBX 桥接输出分离式 glTF，图片放在外部文件中。供其他工具使用的标准 glTF 继续引用 PNG/JPEG 等标准支持的格式，DDS 由最终 J3O 的材质通过项目资产路径引用。
- [ ] SDK 的模型转 J3O 入口和 Unity 导入路径共用纹理处理。读取普通外置图片，以及已有 .gltf/.glb 的 data URI 或 bufferView 内嵌图片。在保存目标 J3O 前，复用 SDK Texture Tools 的 DDS 编码能力生成或复用外部 .dds，把材质里的纹理替换成可重载的 TextureKey。使用次级 UV、贴图变换、不同 sampler 的情况保留原来的采样语义，纹理文件共享不应合并各材质的采样设置。
- [ ] 按用途处理色彩空间与 mipmap，保留透明度。底色和自发光按 sRGB，法线和金属度/粗糙度/遮蔽等数据贴图按线性。纹理去重包含像素内容和影响编码的用途与设置，避免同名资源冲突，也避免把同一图片的 sRGB 和线性用法合并。确认所选 DDS 压缩格式可由目标 JME 渲染后端读取。
- [ ] 编码与 J3O 保存使用暂存输出。编码、重绑或写入失败时报告模型、纹理与失败阶段，并保留已有可用输出，避免静默生成缺贴图的 J3O。
- [ ] 验收：分离式 glTF、内嵌图片的 .gltf、内嵌图片的 .glb 三类输入都产出外置 DDS 和 J3O。用只包含结果 J3O 与 DDS 的资产目录及新的 AssetManager 重载，核对材质槽、颜色、透明度和法线。再核对材质通过 DDS 资产键取图，J3O 不包含内嵌纹理数据。以内嵌贴图和外置贴图混用、共享图片但采样参数不同的模型验证引用与去重。

### 2. 验证 Prefab → 独立 J3O → 场景复用

- [ ] 第一份样本选文本 Prefab，例如引用阶段 1 门板模型的 Door.prefab。按 GUID 定位资源，在资源内部按 fileID 定位节点和组件，生成一个 Door.j3o。资源索引保留 Unity ID 到 JME 对象 ID 的映射，供实例覆盖与重导使用。
- [ ] 场景 J3O 中建立两个独立实例，分别记录自己引用的 Door.j3o 资源键、Transform 和覆盖值。评估以 AssetLinkNode 为底层，增加实例数据与覆盖恢复。先加载预制体并建立实例内 ID 表，再应用覆盖并修复引用。外部资源故障应报告来源和目标。
- [ ] 验收：保存并重新加载场景后，两扇门仍指向同一份资源，场景没有把完整预制体分别内嵌两次。移动或替换其中一扇门的材质不会影响另一扇。更新预制体并重新加载后，两个实例获得新的资源内容，各自的覆盖仍生效。Prefab 实例是场景对象，GPU Instancing 是可独立启用的渲染优化。
- [ ] 扩展到嵌套 Prefab、变体、stripped 对象、跨实例引用和重导时的稳定 ID。二进制 Prefab 单独验证，使用已有 Unity 5.1.1f1 样本评估解析或重序列化路径。FBX 桥接和 YAML→JSON 都不能替代二进制资源解析。

### 3. 验证 MonoBehaviour → Java Control → J3O 运行

- [ ] 保持 Control 接口和 AbstractControl 的 API 与序列化契约。新增继承自 AbstractControl 的 UnityBehaviourControl，作为生成脚本的父类。配套场景调度器负责激活与生命周期；保留旧 J3O 的加载回归。
- [ ] 定义 Awake、OnEnable、Start、Update、LateUpdate、FixedUpdate、OnDisable、OnDestroy 对应的 Java 回调。先恢复字段和引用，再进入运行生命周期；分别处理组件 enabled、对象自身 activeSelf 和父级决定的有效激活状态。统一执行晚更新，固定步回调接入物理步，区分临时移出场景与销毁。加载、克隆及默认 SDK 编辑预览不执行脚本。
- [ ] 先用一份 DoorControl 验证数据和调度契约。两个 Door.j3o 实例分别引用各自的门板，配置不同打开速度，单独触发和销毁。保存重载保留配置与引用，每次实例化重新开始运行生命周期。
- [ ] 实现导入时代码生成。通过 m_Script GUID 和 .cs.meta 找到 C# 源码，用语法树与类型分析生成 Java。每种脚本类型只生成一个继承 UnityBehaviourControl 的 .java；J3O 为每个组件实例保存自己的字段与对象引用。生成文件放进明确的源目录，记录生成来源，重导不覆盖手写扩展。生成类随游戏编译和发布。
- [ ] 首批支持字段、基础控制流、Transform、Time、组件查询和简单交互；为每条 Unity API 映射验证坐标、数值及事件语义。没有源码或遇到不支持的语法/API 时保留组件字段与引用，列出无法转换的位置，并允许 SDK 在没有生成类时查看资源。待验证后再扩大到输入、碰撞、动画和协程。
- [ ] 验收：导入样本 .cs 自动产生可编译的 Java 源码。编译后打开包含两份 Door 引用的场景 J3O，分别触发、停用与销毁，核对字段覆盖、引用归属和回调顺序，并验证旧 J3O 和默认编辑预览。

## 2026-11-21 In-process texture codecs

- The Texture Tools module loads an installed JNI library for BC1–BC5, BC7 and ASTC. Java handles image input, mipmaps and DDS/ASTC/KTX1 containers. Encode, preview and mip replacement use no encoder subprocess in this mode. The native timeout is checked when control returns, not enforced by killing a child process.
- The Windows x64 build links Compressonator 4.6.0 and astcenc 5.7.1 and has no runtime source download or extraction. The build script caches and verifies source archives, compiles the library and stages the platform ZIP, including upstream notices. Gradle can collect matching platform ZIPs from an artifact directory; generated binaries remain outside source control. Use `-J-Djme.sdk.texture.backend=jni` to fail if the library is absent. The default uses CLI only on a host without a JNI build, or when selected explicitly.
- Windows x64 compilation passed with VS 2022/MSVC 14.44 and CMake/Ninja. Gradle's offline `prepareTextureTools` imported the platform ZIP. The JDK 25 smoke passed with only the JNI DLL available, and again using the isolated Ant-built module and cluster. It covered all six BC formats and ASTC, mip previews/replacement, sRGB metadata, odd dimensions, channel order, timeout output retention and strict missing-library behavior. Linux/macOS/ARM builds, the full SDK installer and visible editor UI were not tested. Source bases SDK 576ecbe6 and engine db8ab1149; this change adds no engine dependency.

## 2026-11-21 Select newly added scene objects

- Successful scene creation or insertion selects the new object in Scene Explorer and publishes the selection to Scene Composer. This covers right-click creation, the Spawn palette, model drops/imports and Scene Explorer drops. Undo/redo keep their existing refresh path. Selection uses object identity after the parent refreshes on the EDT and ignores parents outside the active scene.
- Targeted JDK 25 compilation and an isolated Swing/NetBeans smoke check passed. The check covered a new object with a duplicate name under a nested parent, a new light, and a delayed selection from a replaced scene. Full SDK build/deployment and visible native drag-and-drop verification remain pending.

## 2026-11-21 Game native cache repair helper

- repair-native-cache.bat preserves an existing ordinary C:\JmeNativeCache directory as a dated backup, then calls setup-native-cache.bat to create the private replacement. Run elevated as the game user; the game itself can run normally with its existing VM option. Check mode is read-only.
- Check mode confirmed the current directory still inherits permissions, and invalid arguments were rejected. The elevated repair was not run. The game JVM failed cache validation; the SDK launch method alone does not establish the cause.

## 2026-11-21 Start and editor collision transforms

- Spawn / Spatial and Scene Explorer / Add Spatial expose Start. Priority ID persists in J3O. The tool-only gizmo uses a position cube, cyan forward +Z and green up +Y arrows at screen-relative size. Move/rotate the underlying node using the standard tools. SDK editing does not jump the camera; standard SimpleApplication uses the runtime Start service from the paired engine.
- Both creation menus share primitive collision creation. Box/quad and wire box use finite bounds, sphere variants use a spherical shape, line uses a thin oriented box, cylinder uses native static triangles, and Terrain uses the existing Minie mesh factory. All default to mass zero; the existing Physics panel can replace the shape. This remains the SDK's Minie asset format.
- Scene change notifications coalesce transform work before physics stepping and before save. Rigid bodies, ghosts and character controls follow scene edits, including ancestor edits and undo/redo. Native scaling restrictions use the largest axis when a shape requires uniform scale. Scaling clones shared shapes before editing. Active preview adds/removes new colliders on scene attach/detach. Collision selection meshes use native shape scale once.
- Targeted Java 25 SDK and Java 11 engine compilation passed. Isolated native Minie smoke passed primitive/terrain creation, transformed rigid bodies, capsule pose/scale, shared-shape isolation, preview registration/removal and re-attachment. Start J3O and camera selection also passed. No complete SDK build/deployment or visible viewport interaction test.

## 2026-11-21 SceneComposer Spawn palette

- Spawn exposes the existing Spatial and Light creation commands as draggable Swing buttons, including optional terrain, skybox and NavMesh commands when their modules are enabled. Existing configuration dialogs, scene dirty state, and undo/redo are reused. Drops target the editable scene root; scene switches cancel pending drops.
- The viewer queues one world-space ray on drop with HiDPI conversion and a camera-depth fallback on misses. Drag motion performs no collision checks. Hidden geometry is skipped. Spatial placement converts to parent-local space; positional lights and reflection captures use the world point. Skyboxes, NavMesh and global lights retain their placement rules. Targeted compilation and a headless check passed for nested drag buttons, transfer data, nearest visible hit, hidden hit exclusion, and the miss fallback. Native drag/drop validation remains pending.

## 2026-11-21 Histogram and film naming

- Exposure and tone mapping use descriptive UI, API, shader, and example names. The original serialized enum values and editor keys are migrated on load. New saves use the renamed settings. Targeted engine/SDK compilation, legacy J3O enum migration, and the Swing post-processing panel smoke test passed. Export the engine and rebuild the SDK together. No full build or deployment.

## 2026-11-21 GI bake settings and portable compiled SDK launcher

- `setup-native-cache.bat` is a Windows helper for native extraction failures caused by replaceable ancestors. Run it elevated as the game user to create a fresh `C:\JmeNativeCache` with a private ACL. It prints the VM option to add to the game project. Existing directories are left alone.
- The GI Probe Volume bake inspector saves all 15 bake-only controls as per-volume spatial user data in the scene J3O. Reopening restores saved values. Old scenes keep the same defaults. Editing the recipe marks the scene modified and keeps existing bake data. Multiple bounces with adaptive refinement are rejected before disabling the controls.
- run-sdk.bat resolves JDK 25 using the build script's existing override/config/discovery precedence and starts the local compiled suite. JDK resolution for this mode does not require an engine checkout. Existing local --check passed without building or launching. GI source/diff checks only; rebuilt SDK save/reopen verification remains pending.
- TestScene2 dark patches reported with Windows display HDR enabled, including with FSR2 and shader sRGB output disabled. Both output routes still present through the SDR Java2D AWT image. User confirmed SDK starts with -J-Dsun.java2d.d3d=false and the black patches persist. Added an opt-in main AWT preview comparison using -J-Djme.sdk.preview.repaint=true. It uses EDT repaint instead of BufferStrategy and the same 8-bit readback. Omit the option and restart to restore Accelerated mode. Source/diff checks only. Rebuilt SDK HDR-on comparison and a confirmed fix remain pending.

## 2026-11-20 compatible shader presentation / FSR publication revisions

- Engine implementation: 85039d25e10b7f5237cf39f084c0e62366483d1f on dev_fix_lightprobe_issue; SDK implementation: 6d2c12e1dcc5154e33f68fac118832cf4ab9052c on master. Documentation-only commits follow. Export/rebuild this pair together; validation limits below remain applicable. No full SDK deployment for publication.
- Generated SDK archives, extracted/exported libraries/templates and local build revision are excluded and retained locally. Render scale remains bounded to [.25,1]; the user has raised a separate .1-scale question, not modified in this publication.

## 2026-11-20 default presentation / FSR integration (local source)

- Supersedes the opt-in note below: shader sRGB output defaults On in the engine's LWJGL3 window/application AWT policy and SDK main AWT preview. Explicit stored preview Off remains respected; it is a preview override, not game window configuration. Raw offscreen/explicit targets remain linear. Requires the paired new engine; existing deployed binaries are unchanged.
- Post processing > Upscaling offers Off / FSR1 / FSR2, Render resolution scale (.25..1, default2/3), and Sharpness (default.2). Upscaling starts Off. Scene/depth/velocity scale internally; final output, tools and GUI stay native. FSR2 disables the active AA controls and bypasses engine TAA/FXAA without losing their saved choice/parameters; Off restores native scene dimensions and prior AA. FSR1 retains prior AA.
- FSR1 runs after AA/Tonemap; FSR2 before exposure/Bloom/Tonemap. GUI is composed before the final shader sRGB encode. The actual UpscalingPass and parameters serialize through WorldSettings FPP, including its explicitly selected float format. Standard SimpleApplication SceneServices restores them when the loaded scene is attached to rootNode; loadModel alone does not configure rendering. Custom/manual FPP precedence remains as documented by the engine. The window output default is AppSettings-based, independent of J3O's preview switch.
- This is the FP32 OpenGL4.3 FSR2 implementation using AMD kernels and GL-port references, not a TAA substitute. Optional render-size reactive/composition textures are available through the engine API; auto transparent-color difference capture is not integrated. Invalid motion gets a safe reactive adapter. See engine handoff for exact source provenance, modes/limits, native entry points and resource ownership. Other RHI/Vulkan/GTAO/frame-generation plans remain pending.
- Validation: changed SDK classes compiled independently; the post-processing panel smoke test passed for output defaults, old-scene Off default, FSR2 AA card bypass/restoration and scale controls. PostAntiAliasingSmoke passed POST_AA_SETTINGS_PASS for WorldSettings/J3O round-trip through FSR1/FSR2/Off, scale, sharpness and original AA parameters. Engine native 20-stage four-path test plus moving/disocclusion/alpha/GUI/finite-output checks and eight static-edge cases pass on RTX5060Ti. Native default window path at1x/4x MSAA, core48/effects1 targeted tests and existing TAA/postprocessing regressions pass.
- No full SDK build/export/deployment, actual visible editor runtime check, AMD/Intel/second-machine validation or performance claim. Source bases engine967c43bcc / SDKe18bf330 plus paired local edits; rebuild/export together. No commit/push or external asset edits. Existing generated archives/libraries/project version changes are preserved. New local evidence is in the engine build/fsr-sdk-{compile,panel,settings}.log and build/upscaling-*.log files.

## 2026-11-20 opt-in explicit shader sRGB preview output (local source)

- Post processing adds Shader sRGB output (preview), default Off. On uses an owned float linear composition buffer and a final shader sRGB encode into ordinary RGBA8 before AWT readback; Off restores the previous hardware-sRGB target. Texture decoding, exposure/Tonemap, TAA and GI parameters are unchanged. All main-panel overlays/transparent rendering precede encoding. This is SDR, not HDR display support or an FSR implementation.
- Selection follows scene editorOptions persistence/replay, and close restores Off. Only the main editor AWT panel is affected; thumbnails/material previews and standalone game window configuration are not changed. New engine SrgbPresentation/AwtPanel/FPP APIs are required. Source pair: engine967c43bcc + local edits, SDKe18bf330 + local edits. Export/rebuild together before use; no deployed binaries changed.
- Independent compilation of SceneApplication, EditorPostProcessing and EditorPostProcessingPanel passed. Extended the post-processing panel smoke test passed on Swing EDT. Engine offscreen native SrgbPresentation smoke passed numeric transfer, alpha, resize and AWT+FPP on/off/on checks. Full SDK/AWT visible interaction, two-machine comparison and performance are unverified; no full SDK build/deployment or push. See engine handoff and local build/srgb-* logs for details. Existing generated archives/libraries/project version modifications are preserved.

## 2026-11-20 global FPP anti-aliasing selector (source only)

- Replaced the FXAA checkbox with Off / FXAA / TAA selection. Only the selected method's parameters are shown; switching preserves values. TAA controls: current-frame weight, base-pass velocity, transparent velocity and alpha threshold. Uses the existing SDK message bundle.
- EditorPostProcessing creates a single-sample HDR chain with TAA first, before AO/fog/DOF/exposure/Bloom/tone mapping; FXAA remains after tone mapping. Mode selection enables exactly one or neither and resets TAA history. Legacy reference still uses fixed ToneMap+FXAA; modern selector is disabled and retained while comparing legacy.
- WorldSettings stores the actual filter chain plus editor options through the existing scene-save flow. Old FXAA flags migrate to Off/FXAA; explicit new AntiAliasing takes precedence regardless of map iteration order. Scene switching creates independent filters. Needs paired engine TemporalAAFilter and temporal renderer/shaders (engine baseline 5bdf1e4e8 plus current local TAA changes; SDK baseline 7e32d6a5 plus these changes).
- Validation: source/diff review only. First isolated javac attempt failed because its classpath lacked NetBeans LookupProvider. A retry with dependencies was rejected by automatic approval review as conflicting with the user's no-SDK-build instruction; no further compilation attempted. HistogramFilmPanelSmoke now covers card switching/legacy restoration; PostAntiAliasingSmoke covers filter exclusivity and WorldSettings parameter round-trip, but these new checks have NOT run. Native AWT/GL preview, scene switching, compilation and runtime save/reload remain unverified. No SDK build/deployment, commit or push.

## Pending: code comment style review

- User requested a future review of comments introduced in previous AI-assisted edits. Match the surrounding author style; explain purpose, non-obvious reasons and constraints concisely. Remove template-like narration and redundant commentary without losing API/lifecycle requirements. This is a backlog item, not a completed repository-wide rewrite. Apply this style to new edits now.

## 2026-11-19 compatible implementation revisions for GI/performance publication

- Engine implementation: 45b41e76afab9172c0932cf45518f7d8ac16587b on dev_fix_lightprobe_issue; SDK implementation: 89566b32 on master. Documentation-only commits follow this pair. Export this engine before rebuilding SDK; no full SDK deployment was performed.
- Focused engine publication tests passed 69/69. Expanded baseline failures and performance limits remain in the engine handoff. These implementation revisions supersede earlier working-tree pairing notes for this publication.

## 2026-11-19 GI opt-in and engine performance publication checkpoint
- Publish GiPreview/JmeGiProbeVolume integration for Curved receiver correction, default Off. Requires the paired engine changes; see the existing validation and unavailable TestScene3 limitations below.
- Paired engine also includes exact-declaration FrameGraph compilation reuse, material-watch/sort-ID CPU optimizations and zero-duration animation fixes. Graph declarations remain dynamic each frame. CPU microbenchmarks improve, but three controlled Sponza pairs fluctuate substantially; no stable whole-frame FPS gain claimed. See engine handoff for test failures reproduced on baseline and performance figures.
- No full SDK build/deployment in this publication. Generated ZIPs, exported engine/test-data libraries, extracted templates and local nbm.revision updates are retained locally and excluded from commits. Lemur/dual-GUI/Agent and neutral physics backend plans remain pending, not implemented. Compatible implementation revisions will follow in a documentation commit.
## 2026-11-19 compatible publication revisions
- Engine implementation: fbc68bd0d on dev_fix_lightprobe_issue; SDK implementation: dc5d1ec2 on master. Use this pair for WorldSettings, histogram and film post-processing and preceding sky composition changes. Documentation-only commits follow. Validation limits below remain applicable; no full SDK build for publication.

## 2026-11-19 publication checkpoint
- Publish current WorldSettings, editor lifecycle and histogram and film post-processing source changes together with the existing sky-composition commits. Validation and limitations are recorded below; no full SDK build or deployment for publication.
- SDK also includes binary Unity input diagnostics and conversion load-failure handling. Generated archives, extracted libraries/templates, crash logs and local build revision changes are excluded.
- User IDE startup used Android Studio Java 11 despite Gradle using JDK25; runtime selection needs JDK25. IDEA module tree recovered after Gradle synchronization; no IDE configuration was changed.

## 2026-11-19 GI sky bake mode selector
- BakeGiProbeVolumeAction exposes Separate sky visibility, default unchecked. Off requests total diffuse including sky; on preserves separated bounce/sky-visibility data. Choice is copied at bake start; changing the control alone never relabels existing data. Rebake and save scene/GI files to switch. Source usage and tooltip explain outside/disabled-GI fallback and independent sky reflections.
- Paired engine change defaults GiSceneBaker.Settings.separateSky=false and corrects total-mode bounce composition/sky-pixel intensity. Targeted SDK action javac passed via engine build/sdk-bounces-check.args. Engine native tests cover both formats, all four paths and Lighting/PBR; TestScene3 fixed-view outputs identical. Full NetBeans UI, SDK build/deployment/restart were not run.
- Sky intensity/exposure are not modified by this UI change. Original project scenes untouched; comparison candidates/logs remain under the engine build directory. Distance-field sky occlusion remains pending; this changes composition, not that feature. Preserve concurrent post-processing and WorldSettings work.

## 2026-11-19 histogram and film SDR exposure / Film / CombineLUTs (working tree)
- Added AutoExposureFilter.Mode.SceneHistogram with brightness limits (.03..2), configurable log histogram (-8..4), soft adjacent bins, 18% grey and linear/exponential transition (1.5 stops). In this mode Speed Up means brighter scene / lower multiplier; Legacy retains old direction and multiplier EV bounds. Portable 32x32 metering differs from UE compute histogram; no EV100 camera, pre-exposure or metering mask.
- ColorGradingFilter now uses the viewport FrameGraph. Neutral/Filmic retain their prior equations; Curve.FilmLut adds AP1/D60 film response with .88/.55/.26/0/.04 controls, glow/red shaping, blue correction, bright saturated gamut expansion, Bradford white balance, grading gamma/offset and tonal gains. CombineLUTs generates a 32^3 log-input atlas only when parameters change; ApplyColorLUT samples it into linear SDR, alpha preserved and framebuffer encodes sRGB once. LUT GPU state is never serialized and is freed on cleanup.
- Optional external N*N by N LUT (16/32/64) via setColorLut: raw Linear texture data whose RGB values represent sRGB display colors; blends during LUT generation. SDK exposes procedural controls, not an external-LUT file picker. Tint is approximate; full per-range saturation/contrast/gamma/offset vectors and HDR/PQ output are absent. This is histogram and film SDR, NOT pixel-identical UE or full ACES output-transform parity.
- SDK Exposure adds scene histogram toggle, brightness/log ranges/percentiles/transition; ToneMap adds Film / combined LUT toggle/curve/white balance/gamut controls; Combine LUTs section adds gamma/offset and tonal gains. All numeric settings round-trip through existing WorldSettings editor options. Old scenes default to old modes; explicitly enable HistogramExposure and FilmLutTonemap to opt in. Inapplicable controls are disabled. No full SDK build/deployment/restart or push.
- Validation: targeted JDK25 filter/example and SDK class compilation passed. Native RTX5080 GL4.3 legacy CINEMATIC_POST_PASS; the histogram and film example GPU smoke test passed for 1x and 4x MSAA, with exposure direction, grey/black/HDR, alpha, LUT insertion/removal, FPP detach/re-attach and serialization. Final 1x adds saturated RGB checks. No side-by-side same-scene UE image comparison or measured performance claim. Initial command processes intermittently crashed 0xC0000005 and were retried.
- Runnable example: jme3test.post.TestHistogramFilmPostProcessing, --verify for offscreen numeric checks; -DhistogramFilm.samples=4 for MSAA. Non-verify demo varies HDR background luminance. SDK tools/tests/the post-processing panel smoke test passed on EDT (independent headless Swing test, -Xint retry); checks legacy/new control enablement and resource lookup, not full NetBeans integration.
- Source pair: engine base a0d07893a + this working tree; SDK base d8a6ff1f + working tree. Preserve concurrent GI/SkyLight/Unity edits and earlier WorldSettings work; not part of this feature validation. TAA and Lemur/dual-GUI/Agent backlogs remain separate pending work.

## 2026-11-18 scene-owned FPP and editor lifecycle (working tree, not deployed)
- Paired bases: engine a0d07893a, SDK d8a6ff1f. New WorldSettings control snapshots serialized FPP configuration and editor options; no GPU histories are serialized. SimpleApplication SceneServicesState uses cached discovery, highest priority (ties traversal order), one main viewport chain, manual FPP precedence, and releases on disable/detach/cleanup. Loading alone has no viewport side effects: attach the loaded J3O to rootNode. Filter classes/assets must ship with game.
- SDK explicit global post-processing edits snapshot configuration into active root WorldSettings and mark scene dirty; normal J3O save retains it. Scene switch rebuilds preview defaults/settings and independent histories. Exposure bounds replay independent of option insertion order. Old scenes retain default preview unless edited. SDK currently reconstructs its known editor chain from options; arbitrary externally authored filter chains are not editable through this panel.
- Preview environment changes now use scene-owner checks on render queue, clear on scene close, and reset sky/camera buttons. Thumbnail probe cache is separate. Removed asynchronous preview sky/probe completion races.
- Visualize adds independent ReflectionProbe/Directional/Point/Spot gizmo switches (session scope, default visible). Hidden markers detach from tool tree/picking without disabling actual lights. Marker iteration only when visibility mask changes; new markers apply current mask.
- DPI mitigation initializes AWT toolkit/display transform before native renderer startup and logs transform; no fixed scale override. Intermittent Windows startup behavior still needs actual repeated SDK launch verification.
- Validation: JDK25 targeted compilation of 4 engine classes, example and 5 changed SDK classes passed (no full SDK build). TestWorldSettings --smoke passes WORLD_SETTINGS_ROUND_TRIP_LIFECYCLE_PASS: binary round trip, independent filters/clones, unchanged-config reuse, disable, manual precedence, detach. Native --verify attempted but blocked before rendering by native extraction directory safety rejection; one normal JVM attempt also exited 0xC0000005. No native visual success claimed. No SDK replacement/restart, no commit/push. Preserve unrelated generated archives/project.properties.
- Performance investigation paused at user request to resume features. Two JFR recordings under SDK build/ show AWT transform/readback and FrameGraph compile hotspots; second contained active GI baking confirmed intentional by user, not evidence of leak. SDK log confirms RTX5080. FPP off changed little; cap120 failure not established. Need idle no-bake controlled capture if resuming.

## 2026-11-18 paired source revisions

- Engine: `caa5af33978f9818516485d86134aa49a6db5461` on `dev_fix_lightprobe_issue`.
- SDK: `42ef57e85ca789daaf92625f7dd07de07889f2f0` on `master`.
- Use this source pair for the changes below; documentation-only commits may follow. Full SDK integration/build verification is still pending, as documented below.

## 2026-11-18 fork publication checkpoint

- Publish current SDK source changes: Unity importer, material graph interaction and comment deletion fix, texture import/editor changes, asset-browser folder rename, GI inspector layout and exposure integration. Historical uncommitted labels below describe earlier checkpoints.
- Preserve the validation distinctions below: targeted compilation/smoke checks for Unity, graph zoom, comment deletion and exposure are recorded; the complete SDK and the other pending UI/native scenarios have not been validated for this publication. No full SDK build was run.
- Generated engine JARs, extracted templates/test assets, crash/build logs and regenerated deployment archives remain local and are excluded. Exact paired implementation revisions follow in a documentation-only commit.
- Sponza marked-view single-bounce rebake was accepted visually by the user; baking quality comparisons are left to the user. Do not claim all dark regions or TestScene3 visuals validated.

## 2026-11-18 材质备注删除保护（工作区修改，未提交）

- ShaderNodeDiagram.selectComment 不再自动把框内节点加入 selectedItems，普通点击备注会清除其他选集。Delete 使用显式选集，因此单选备注只删除备注；Ctrl/Shift 手动多选节点仍保留原多选语义。
- ShaderCommentPanel 将框内未显式选中的节点起始位置单独记录，拖动时按备注实际位移移动，释放时保存节点坐标及排序通知；缩放备注框不移动节点。包含关系不再等同删除关系。
- JDK 25 独立编译通过。tools/tests/MaterialCommentSmoke.java 的 EDT 鼠标/键盘回归输出 MATERIAL_COMMENT_DELETE_DRAG_PASS，退出码 0：单选、拖动、坐标保存、Delete 后节点仍在。测试只绕过删除确认弹窗，没有运行原生预览或完整 SDK。未提交或推送。

## 2026-11-18 Unity importer 全模块复查与材质编辑器检查（工作区修改，未提交）

- Unity 插件此前没有经过完整模块编译：除上一轮重复资源键外，UnityMaterialReader 将 loadAsset(String) 的 Object 返回为 Material；改用 loadMaterial。原 smoke 对 Spatial 调用 Node.getChild 的两处编译错误也已修复。
- 通读全部 9 个生产 Java 文件、测试及模块配置。修正 .meta 数字键被强制转为 String 的问题、禁用 Renderer 仍生成网格、循环父子关系、没有 Unity YAML 文档却返回空场景、并发共享 YAML Load；内置 Cylinder/Capsule 轴向和 Plane/Quad 中心修正。
- JDK 25 全插件源码和测试含注解处理独立编译通过。扩展 UnitySceneImporterSmoke：真实材质/Prefab 覆盖、J3O 保存重载、两种数字网格 ID 表、已有 J3M、原点/轴向、禁用 Renderer、循环层级、非法场景；运行输出 UNITY_SCENE_IMPORTER_SMOKE_PASS。独立 NetBeans 运行需 java.net add-opens 和临时 netbeans.user；测试断言结束后 NetBeans 后台线程不退出，手工结束测试进程。命令进程仍偶发 0xC0000005；未修改全局 JVM 参数。
- 材质编辑器全部 Java 源码与共享节点组件独立 javac 编译通过（不运行注解处理）；未执行完整 SDK 构建或 UI/原生预览验收。新备注的撤销未接入，且仅改备注时元数据保存依赖 J3MD 文件变更事件，仍需后续解决独立保存流程。复杂 Unity 嵌套 Prefab/stripped 父引用、完整骨骼重映射、真实项目渲染不在本次通过的用例范围。

## 2026-11-18 Unity importer 注册编译修复（工作区修改，未提交）

- SDK daemon 日志确认 `UnitySceneDataObject` 的实际错误为 `Key LBL_UNITY_LOADER is a duplicate of one from Bundle.properties`。删除重复的 `@Messages` 注解及 import，保留 Bundle.properties 的资源键和现有显示名称引用。
- JDK 25 单文件编译启用完整注解处理（`-proc:full`）通过，输出到 build/unity-registration-check；生成的 NetBeans layer 保留 Unity scene/prefab MIME、DataObject 和动作注册。未构建或启动完整 SDK，未提交或推送。
- 首次普通 javac 进程以 Windows 0xC0000005 退出；仅此次验证增加 `-J-Xint` 重试成功，仍有一个无关注解处理器 source 版本警告。未修改全局 JVM/构建参数；进程偶发崩溃原因仍未确定。

## 2026-11-18 Shader Node 缩放层编译修复（工作区修改，未提交）

- 从 Gradle daemon 编译日志确认 3 个错误均在 `DiagramZoomLayer`：继承 final `JLayer`、玻璃面板不是要求的 `JPanel`、`LayerUI.paint` 覆盖签名错误。旧动画 API 的 deprecation 警告不是此次失败原因。
- 改为 `JPanel` 包装 `JLayer<Diagram>`，使用透明 `JPanel` 接收缩放后的鼠标事件；修正绘制覆盖签名并按缩放比例布局内部 Diagram，保留现有 getView 调用方式。
- 使用本机 JDK 25 对 core/editor/nodes 全部源码进行独立 javac 编译通过。新增 `tools/tests/DiagramZoomSmoke.java`，无窗口 Swing EDT 测试通过 `DIAGRAM_ZOOM_COMPOSITION_PAINT_INPUT_PASS`：2 倍绘制、逆变换鼠标坐标、恢复 1 倍事件处理。输出仅在 build/diagram-zoom-check。
- 未执行完整 SDK 构建、部署或启动；实际 NetBeans 编辑器交互仍待用户重建后验证。现有 SDK 缓存类为 Java 25 class version 69，不能使用 JDK 21 编译器读取。未更改全局 JDK 或引擎源码，也未提交或推送。

## 2026-11-17 材质定义 Shader Node 编辑器完善（工作区修改，未提交）

- 标准 `position`/`color` 输出总线在加载每个 Technique 时显式补齐并去重，不再依赖残留连接。修正底部输出总线的画布宽高、默认元数据回退和标题字体，保留黄/蓝总线及预览交互。
- 端口跟踪全部连接，删除单条扇出连接不会把仍有连接的端口变灰；断线清理选集、临时拖拽状态和旧曲线区域。非法连接仅在鼠标释放时弹出一次英文或简体中文警告。
- 紧密节点使用稳定 S 曲线，反向连接使用底部动态通道；修正曲线命中区域及删除节点时重复移除连接的问题。
- 支持 Shift+滚轮以光标为锚点缩放（25%–300%），普通滚轮仍滚动。缩放层通过玻璃面板反变换并重新分派事件，保证节点、端口和框选命中；拖拽连接时独立更新悬停端口。
- 空白拖拽框选、Ctrl/Shift 多选和整组移动可用，移动后保存全部节点坐标及空间顺序。框选后按 C 创建备注框，双击编辑，拖动备注框移动完全包含的整组节点，右下角仅调整框大小。备注以独立 `.properties` 元数据按 Technique 保存，不改变 J3MD；文本输入时 C 不触发备注。
- 修正 Shader 页工具栏对齐和源码分割比例，`.java`/`.form` 已同步。切换 Technique 清理输出总线预览，关闭编辑器清理背景预览。
- 未获构建授权，未编译或启动 SDK。两轮只读静态复查发现的问题已修复，IDE 诊断无错误。仍需实际 SDK 验证缩放后连线、框选/C、备注和输出条保存重开及多语言弹窗。

## 2026-11-17 GI inspector horizontal growth fix
- Root cause: ModelDetailTopComponent wrapped the GI root in a default JScrollPane. The ordinary root JPanel requested 360px, so the nested InspectorColumns width tracking never reached the viewport. Narrow docks/vertical scrollbars plus asynchronous grid/status relayout could produce horizontal scrolling and left-clipped labels.
- Added InspectorColumns.WidthTrackedPanel: standalone dialogs still pack at 360px, while direct scroll views request zero preferred viewport width and always track actual viewport width. Long content affects height only. BakeGiProbeVolumeAction uses it; the GI Properties host now explicitly disables horizontal scrolling, uses vertical-as-needed scrolling and has zero minimum size/border.
- SdkEditorSmoke adds SDK_GI_INSPECTOR_WIDTH_PASS with repeated 240/180/260px resizes, long changing grid/diagnostic text and vertical overflow, asserting exact root/field viewport widths and no horizontal scrollbar.
- Validation: read-only host-chain review and IDE diagnostics clean. No build/compile/smoke/SDK run per user preference; actual NetBeans dock timing and visuals require rebuild/restart verification. Not committed or pushed.

## 2026-11-17 GLB/glTF embedded texture extraction and DDS
- Source-model conversion now passes the actual FileObject into ModelTexturePreparation. Root-level glTF imageN.png/jpg/jpeg keys are normalized from loaded pixels into `modelName.textures/imageN-fullSHA256.png`; this handles bufferView/data-URI images and prevents an unrelated root image0.png from capturing the J3O reference. Other external paths are unchanged; an ambiguous external root imageN URI is also safely normalized from its own pixels.
- Stable TextureKeys retain mip/anisotropy/type hints and use flipY=false because extracted PNG rows already match decoded pixels. Equal content reuses the PNG/DDS; changed content gets a new hash and preserves old bytes. Writes use temporary files/no-overwrite moves and asset-root checks. J3O stores the stable logical key, and existing GeneratedTextures creates BC3/full-mip DDS beside the extracted source.
- Supported extraction is non-compressed 8-bit luminance, luminance-alpha, RGB and RGBA. Compressed, missing-pixel, 16-bit, WebP/KTX2 and other unsupported cases retain their original key with warnings; no DDS success is claimed. Extraction/compression I/O failures abort before opening J3O output. Ordinary J3O saves and engine GltfLoader are unchanged.
- ModelTextureMappingSmoke now covers root-name collisions, orientation/key flags, reuse/non-overwrite, per-model isolation, gray-alpha channels and a real generated minimal GLB through GltfLoader/Stb -> extraction -> DDS -> J3O reload. These tests are written, NOT run.
- Validation: two read-only review passes; the root imageN collision found by review was fixed. IDE diagnostics clean. No build/compile/smoke/SDK run per the user's standing preference, so native encoding, actual GLB/J3O round trips, visible orientation and real-world assets remain unverified. Not committed or pushed.

## 2026-11-17 texture editor PNG/JPG/TGA/HDR viewing
- JmeTextureDataObject now registers hdr/HDR and always supplies OpenTexture; the malformed Loaders/image/image/x-jmetexture action path was corrected and unrelated text/plain registration removed.
- IOModule now closes ImageIO streams, rejects null/corrupt images with IOException, reads TGA through a closeable MemoryCacheImageInputStream, and decodes HDR with StbImageLoader without texture-coordinate flipping. core.baselibs/core.libraries dependencies were added for Stb.
- HdrImageConverter supports half-float RGB/RGBA/luminance images with 0 EV exposure, Reinhard tone mapping and linear-to-sRGB display conversion. HDR is explicitly read-only and cannot overwrite its source through IOModule.
- File decoding runs on RequestProcessor with EDT UI updates. Loading/failure states disable editing; both TopComponent constructors share full component/Lookup initialization. PNG/JPG/TGA retain editing, while HDR disables filters/transforms/crop/save and shows the tone-mapping mode.
- Added TextureEditorSmoke for PNG/JPG/TGA round trips, corrupt files, a real Radiance HDR decoded through Stb, half-float tone mapping and HDR write-back rejection. Static review findings (DataObject import, empty constructor initialization and loading-time null editing) were fixed.
- Validation: IDE diagnostics clean. No build/compile/smoke/SDK run per the user's standing no-build preference, so native HDR decoding, actual file opening, Swing state and visual output remain unverified. Not committed or pushed.

## 2026-11-17 independent Unity scene/prefab importer
- Added suite module `jme3-unity-scene` (`com.jme3.gde.unityscene`). No existing module business code was changed; only the suite module list was extended. The module depends on core/engine public APIs and NetBeans SnakeYAML Engine, never the reverse.
- AssetManagerConfigurator registers `.unity`/`.prefab`; the DataObject reuses core Open Model and Convert Model actions. Users copy Unity assets with `.meta` sidecars into the JME asset root, preview the YAML scene, then save a J3O snapshot with Convert Model.
- Implemented YAML documents, GUID indexing, old/new material property layouts, imported mesh fileID names, GameObject/Transform hierarchy, built-in meshes, external model reuse with baked ancestor transforms, Standard/URP Lit/Legacy/Unlit materials, alpha cutout/blending, basic lights and prefab instances with grouped transform/name/activity/material overrides. Coordinate conversion is `(x,y,-z)` and `(-x,-y,z,w)`.
- Explicit limits: no C# behaviour conversion, terrain/UI/physics/baked lighting/arbitrary component overrides, packed metallic-smoothness map conversion or Unity-identical rendering. Missing references produce `[Unity import]` warnings and a fallback/skip, not silent success. Cameras are metadata only.
- Added headless `UnitySceneImporterSmoke` for real YAML-to-scene conversion and J3O round trip, including PBR, prefab quaternion/material overrides and alpha blending. Two read-only review passes were performed and their concrete findings fixed.
- Validation: IDE diagnostics clean. No build/compile/smoke/SDK run per the user's standing no-build preference; preview, real-world Unity compatibility and rendering remain unverified. The generic same-version build-impl was included after diff confirmed only its project name differed; NetBeans may generate genfiles.properties later. A final git diff --check attempt hit the known 0xC0000005 shell crash and is not recorded as passed. Not committed or pushed.

## 2026-11-17 manual preview exposure
- Main scene EditorPostProcessing now owns ExposureFilter after DOF and before scene Bloom/ToneMap; default 0 EV preserves brightness. Post processing > Exposure > Compensation (EV), range -16..16, step .25. Reset to 0 restores brightness. Existing render-thread enqueue, Global HDR/FPP, diagnostic-view, unsupported-HDR and scene-owned-FPP bypass rules apply. Does not change J3O/GI capture or automatically add the chain to animation previews.
- Engine ExposureFilter and shader/resources required, based on engine 25d2b714d / SDK a116cf8d plus these local changes. Targeted javac of panel/processor/helpers passed (build/exposure-sdk-compile.log); engine serialization and real ANGLE GPU HDR/alpha/bypass checks passed. Additional desktop-MSAA run blocked by host JVM/toolchain failures, not claimed passed. No running SDK UI validation, SDK build/deployment or push. Full color pipeline and automatic exposure remain future work.

## 2026-11-17 asset folder rename with generated DDS guard
- Asset Browser tree/grid folder context menu now offers Rename folder. Tree right-click targets the right-clicked folder; grid right-click targets a single selected folder. The asset root is excluded.
- Rename validates a single name, rejects existing names, runs on the background RequestProcessor via FileObject.rename, and updates the current browse path when it is inside the renamed folder before rescanning.
- GeneratedTextures.isGeneratedOutputDirectory(Path) detects a folder that directly holds registered DDS outputs from the ownership manifest, without relying on the "dds" name. Rename is disabled with an explanatory tooltip for such folders, and fail-closed if the registry cannot be read. The NetBeans masterfs move/rename interceptor still keeps the registry consistent for allowed moves.
- jme3-assetbrowser now has a compile dependency on com.jme3.gde.texturetools; Bundle strings and a GeneratedTexturesSmoke assertion were added.
- Validation: IDE diagnostics clean; no build/compile/smoke run/SDK restart per the user's standing no-build preference, so visible behavior and reference integrity after rename remain unverified. Rebuild/restart SDK and test ordinary folder rename, the dds disable message, and J3O/texture resolution. Not committed or pushed.

## 2026-11-17 paired implementation revisions
- Engine implementation: `6f31fb125635c32e85184576f514dbf353aff27b` on `dev_fix_lightprobe_issue`.
- SDK implementation: `029771dd08dc37e05ecc88ee012d2e7a9fdf8404` on `master` (also includes the preceding offline texture-tool archive commit).
- These paired revisions include the rendering/performance, project asset lookup and Nifty changes described below, plus all three agents' context Markdown files across the repositories. Documentation-only commits follow these implementation revisions. User reported normal SDK behavior before handoff; this commit/push task did not build SDK.

## 2026-11-17 user verification and fork handoff
- User reports the SDK/model preview and performance are now normal after local rebuilding/testing. This is user verification; no new SDK build was run during the commit/push task.
- Include all pending source fixes and other agents' code_buddy_agents*.md context files. Keep workstation proxy settings and generated engine exports/templates/packaging outputs local. The updated versioned Nifty editor dependency is part of its source integration fix.
- Earlier entries describing uncommitted changes or unverified Sponza behavior are historical checkpoints; this entry records the subsequent user result. Remaining unrelated roadmap and RenderDoc replay/shutdown caveats are not claimed resolved.

## 2026-11-17 GI preview repeated GPUScene rebuild fix in engine
- Sponza slowdown was sampled in the running SDK: no renderdoc.dll loaded, SDK dedicated GPU-memory counter around 14 GiB, render thread repeatedly allocating/uploading GPUScene buffers. GiPreview frame-scoped bind/restore advances material revisions even when next-frame draw values are identical.
- Engine GpuScenePipeline now compares effective parameter snapshots on revision changes before structural invalidation. Keep GiPreview bind/restore: original materials must remain clean for save/undo. Do not replace this with persistent edits to saved materials.
- Engine unit/eligibility regression passed 11 tests. Native offscreen Tank test on RTX 5060 Ti passed 240 frames: builds stay 1 through repeated bindings, actual material edit raises to 2 and then stabilizes. No full Sponza FPS/VRAM soak test or SDK deployment yet. Export the rebuilt engine and rebuild/restart SDK before verifying. See engine DEV_HANDOFF for commands/logs. No commit/push.

## 2026-11-17 project Sponza shadowed by SDK testdata resource
- Root cause reproduced: SDK classpath jme3-testdata.jar contains Models/Sponza/Sponza.j3o. Default ClasspathLocator preceded project files. It loads a 9,539,478-byte single-geometry Unshaded model with no texture bindings/bakes instead of TestGame assets' 57,372,472-byte model (103 PBR geometries, 307 texture bindings, SdfVolume and SurfaceBakeData). Direct glTF works because no same-key source exists in the dependency.
- ProjectAssetManager now registers the primary folder before configurators, moves default ClasspathLocator behind project file locators, and does the same for standalone files. Engine resources remain classpath fallbacks. No game asset was modified or rebaked.
- Targeted compilation passed. Isolated lookup regression passed for standalone and project constructors (project-service updateClassLoader disabled only in test), verifies disk file size plus engine material fallback. Added tools/tests/ProjectAssetPrioritySmoke.java; run with SDK/NetBeans JARs plus testdata.jar and the asset directory argument. No full SDK build/deployment or native preview verification; rebuild SDK and reopen model. Physics deserialization not validated by this lookup test.
- Compatible source baseline: engine 2bba6193b, SDK a0ff9c98 plus existing local changes. Not committed/pushed.

## 2026-11-17 versioned texture encoder archives for offline builds
- All seven official archives were already present in build/texture-tool-downloads. Their published SHA-256 digests matched; copied the 106881664-byte set into versioned third-party/texture-tools.
- prepareTextureTools no longer downloads. It verifies and unpacks those repository archives, with URLs retained only as provenance. Extracted module resources remain ignored; buildSdk and both texture verification scripts use the same task. readme now documents clean/offline behavior.
- Validation passed: gradlew prepareTextureTools --offline --rerun-tasks with the actual jmonkeyengine sdk-engine-repository completed in 4s and checked all seven executable layouts. An initial direct invocation without localEngineRepository failed during configuration because the default sibling JMESources export was absent; unrelated to texture archives. No full SDK build or model conversion run. No push.

## 2026-11-17 RenderDoc icon and FrameGraph capture groups
- RenderDoc Options no longer reuses opengl-48.gif. Added the official RenderDoc 48px icon derived from qrenderdoc/Resources/icon.ico plus MIT license/source notice. The camera capture toolbar icon is unchanged.
- Missing capture groups were not caused by SDK in-process injection bypassing FrameGraph. Engine FrameGraph.execute already wraps executed passes with renderer push/popDebugGroup, but GLRenderer suppresses them unless GraphicsDebug=true and Caps.GLDebug exists. SDK SceneApplication previously never enabled GraphicsDebug.
- SceneApplication now enables GraphicsDebug before preview context creation only when RenderDocSupport is loaded, enabled and valid; ordinary previews keep it off. RenderDocSmoke now checks the Options icon resource and enables GraphicsDebug for its native capture so replay can be inspected for ViewPort/Pass groups.
- Official icon download/conversion, image inspection, git diff --check and IDE diagnostics completed. No SDK build/compilation/restart, native capture or RenderDoc replay inspection was run. Rebuild core/SDK, restart, capture again and inspect actual Event Browser groups. Engine sources unchanged; no commit/push.

## 2026-11-17 model conversion missing texture tool resources
- Converting TestGame Models/Chair/SheenChair.gltf failed at TextureToolchain.installed because neither jme3-texture-tools/release nor installed build/cluster texture-tools resources existed. This is missing SDK tooling, not a damaged game model.
- Added Gradle prepareTextureTools to download the seven official Compressonator 4.6.0/astcenc 5.7.1 platform archives, verify their published SHA-256 digests, unpack the existing release/texture-tools layout and verify all expected executables. buildSdk now depends on it; cleanSdk removes the generated tools. Generated tools and download caches remain unversioned.
- Texture verification scripts prepare resources before direct Ant builds; readme documents the workflow. Missing-resource IOException now names prepareTextureTools and SDK rebuild. Conversion still aborts rather than silently skipping compression.
- Static source/diff inspection only. git diff --check and IDE diagnostics passed. No archives were downloaded or unpacked; no Gradle configuration/build, Java compilation, native encoding or live conversion was run. Package layout and end-to-end repair remain unverified. Close SDK, run build-and-run-sdk.bat, then retry conversion. TestGame assets were not changed. No commit/push.

## 2026-11-17 Sponza texture encoder native crash handling
- User conversion of Models/glTF/Sponza.gltf failed inside Compressonator with exit-1073741819 (0xC0000005), empty output. Read-only input audit encoded all69 referenced images successfully to ignored build/sponza-encoder-audit using BC3/full mip chains, matching GeneratedTextures. Installed and release encoder SHA256 both9E3B1EF89A57C85B194CF45A86D2C22013FB7A4BA0CF0AA487224E9FAA44AEBC. No deterministic bad texture reproduced; SDK-process environment/crash root cause remains unconfirmed. Original assets/J3O untouched.
- TextureToolchain now retries native access violations once for encoding as well as decoding, using typed exit codes and fresh per-attempt temporary files. No retries for other failures/timeouts/interruption; repeated failures keep original as suppressed exception. Error includes hex exit, executable, input/output, full argument list and explicit empty-output marker. This is bounded fault tolerance and diagnosis, not a proven native root-cause fix or silent compression fallback.
- Targeted compilation passed. Real BC1/BC3/BC5/BC7/ASTC encoding, four mip previews/replacement, truncated-input rejection passed. Broader TextureToolsSmoke stopped later at unrelated MIME routing because FileUtil returned null in minimal standalone runtime; do not claim full suite pass. New TextureFailureSmoke passed real invalid-input diagnostics and preservation plus timeout preservation. Transient-crash retry branch not deterministically forced. No module deployment/full SDK rebuild; rebuild and restart required.

## 2026-11-17 RenderDoc button retained in overflow toolbar
- User configured E:/RenderDoc/qrenderdoc.exe and Options correctly reported connected, but capture button was absent. SceneViewer's final OverflowToolbar.install removes all earlier children and repopulates explicit groups; RenderDoc was missing from those groups. installButton now returns its JButton and SceneViewer includes it in the first group alongside light/filter/sky toggles. Configuration/injection logic unchanged. Rebuild core and restart SDK to load source changes.
- Targeted compilation of RenderDoc classes and actual SceneViewer passed. tools/tests/RenderDocToolbarSmoke.java verifies hidden unconfigured state, capture ownership after actual OverflowToolbar regrouping, visible layout when available is simulated, and hidden-state ownership. Passed RENDERDOC_TOOLBAR_REGROUP_PASS (isolated broad-classpath run also logged NetBeans startup reflective-access warnings). No new GPU capture or full running-SDK UI acceptance for this adjustment. No live deployment.
- Separate reported Java editor right-click/Shift+F10 failure had no matching log exception; project-tree context menu worked. User subsequently confirmed both editor context menu and line numbers recovered together. Cause remains unconfirmed, possibly transient editor initialization; no production popup fix made. Temporary diagnostic test source removed; abandoned isolated launcher artifacts under ignored build/ only. Do not claim a proven menu root cause or repair.

## 2026-11-17 toolbar icon color correction
- User reported global toolbar icons looked disabled. EditorUiStyle.CompactIcon explicitly recolored every enabled icon to label-foreground grayscale; this was visual styling, not evidence of missing modules. Removed recoloring and raster cache; paint original icons each time through aspect-preserving16px Graphics2D scaling, retaining dynamic theme/state changes. Swing/FlatLaf still owns disabled presentation. Existing dropdown exclusions and compact spacing retained.
- Targeted javac passed. Source-only change: no running SDK restart or module replacement; rebuild and restart to see the correction. Actual toolbar/UI appearance after reload remains to verify. No engine API changes.

## 2026-11-17 direct compiled SDK launcher
- Added run-sdk.bat: invokes bundled nbexec64.exe directly, with the same branding, clusters, build/testuserdir profile, assertions and JVM access flags as the existing build/run workflow. No Gradle, Ant, engine export or compilation. Missing JDK/core/launcher/clusters fail with instructions. SDK_JAVA_HOME optionally overrides this machine's JDK25 path. Keep copied run.args.extra flags aligned with nbproject/project.properties when changing runtime JVM requirements.
- Usage: first build normally; afterwards double-click run-sdk.bat to reuse compiled artifacts and saved settings (including RenderDoc/module enablement). After source changes rebuild normally. run-sdk.bat --check validates required paths without launching; passed on this machine. No new live SDK was opened during this launcher-only change. Existing implementation revisions unchanged.

## 2026-11-17 RenderDoc duplicate dependency startup repair
- User startup log build/testuserdir/var/log/messages.log identified the exact failure: duplicate org.netbeans.libs.jna/2 dependency caused NetBeans InvalidException while parsing core, cascading into missing-core errors. Removed the duplicate project.xml declaration. Ant jme3-core netbeans rebuild passed in21s; packaged core JAR updated. Added tools/tests/ModuleDependencySmoke.java: verifies unique source dependencies and uses actual NetBeans Dependency.create on the built manifest; passed with exactly one JNA dependency. This catches what isolated javac missed.
- User selected Disable Modules in the warning dialog. Restored enabled=true for20 com-jme3 module overrides under build/testuserdir/config/Modules, only where installed cluster defaults enable the module. No remaining disabled overrides in that folder. Preserved all other profile content. Backups: build/module-enable-backup-20261117-024254. No user Java/SDK process was observed at restoration time. Restart SDK to load the repaired core and restored modules.
- Gradle full build attempt blocked by wrapper lock permissions; used bundled Ant successfully instead. This repairs module loading, not the separate RenderDoc shutdown issue below. Original engine/SDK compatibility remains unchanged.

## 2026-11-17 built-in RenderDoc capture integration
- Added core-integrated/default-enabled RenderDoc settings (Tools > Options > RenderDoc). Auto-inject defaults true; empty executable defaults inert. Enter saves both fields and asks Yes/No restart via NetBeans lifecycle; No retains settings for next launch. Browse, Apply and Cancel supported. Windows/WGL only; configure qrenderdoc.exe with adjacent renderdoc.dll. User installation verified E:/RenderDoc, version1.42. No machine path hardcoded into production preferences.
- Before SceneApplication graphics initialization, load native RenderDoc through platform JNA module (explicit dependency), negotiate API1.4.0 and retain its library. No late injection/unload. Toolbar Feather camera icon (MIT license included, Java2D adaptation) appears only for enabled, valid, successfully loaded current configuration. Capture explicitly brackets renderPreviewFrame on owning GL thread/current HGLRC, discards failed frames, prevents duplicate requests, saves to SDK userdir/renderdoc-captures, launches qrenderdoc.exe with the exact saved file asynchronously. Errors retain captures and report replay-launch.log. JVM keeps crash reporting ownership.
- Targeted compilation of all new classes plus Installer/SceneApplication/SceneViewerTopComponent passed against existing SDK cluster and engine classes. tools/verify-renderdoc.ps1 checks default/options/Cancel/Enter restart prompt (mock No), invalid paths/disabled availability, native API and actual PreviewPanelsContext AWT/OpenGL4.3 RTX5080 capture. Generated real211-217KB .rdc files and observed qrenderdoc.exe launch. Does NOT verify the complete running SDK UI or RenderDoc replay contents.
- UNRESOLVED: test JVM shutdown produces EXCEPTION_INVALID_HANDLE in RenderDoc1.42 native cleanup (hs_err_pid2268/34512/46148.log). Even OS exit0 can accompany this crash; verifier now treats fatal-error output as failure. Disabling RenderDoc crash handler did not fix it. Unsandboxed verification command could not start (host0xC0000005). Do not claim full lifecycle/production acceptance. Need external normal-desktop run to establish whether sandbox-only, and address shutdown if reproduced. No full SDK build, deployment, restart of user's SDK, or push.
- Compatible source bases: SDK067a2f80 / engine788a35774 plus existing local changes. Integration uses existing engine APIs and does not alter engine sources, GI, Lemur/dual-GUI or coding/scene Agent backlog. Unrelated changes preserved. Native tests use isolated in-memory preferences/userdir and bundled physics natives. Build captures/logs/native installations are not versioned.
- Explicit JDK25 annotation processing also passed and generated the RenderDoc OptionsDialog registration. Final verification.log records successful native capture/replay process followed by the shutdown failure, correctly reported as failed by the script. Git staging command itself exited0xC0000005; index remains empty, changes uncommitted. No push attempted.

## 2026-11-17 per-light shadow strength
- Added Shadows > Shadow strength (0..1) to directional/point/spot light properties, default1/full occlusion;0 removes this light's direct occlusion and .5 partially restores occluded direct light. Existing embedded property path persists scene changes. Does not modify GI or other lights; engine GI capture forces full occlusion independently. Restore1 for baseline diagnosis.
- Requires sibling engine LightShadowSettings.get/setStrength plus ShadowManager record/shader update. Targeted JmeLight compilation passed; engine GPU four-path/two-material and 3 JUnit tests passed. No SDK build/package/deployment/UI test. Existing unrelated SDK and pending GI edits preserved. This is not a fix for the unresolved Sponza GI darkness.
- Commit attempt could not execute: elevated shell exited 0xC0000005 before running the prepared isolated staging script. HEAD/index verified unchanged; source and handoff edits remain uncommitted. No push attempted.

## 2026-11-17 animation editor SDK language correction
- Compact editor chrome: removed playback-toolbar shortcut text and the separate top guidance row. Guidance now sits right-aligned beside playback status in the existing bottom row; labels truncate within their columns and expose full text on hover. Top toolbar now has two rows. Isolated editor verification recorded in build/animation-footer-layout.log; no SDK build/deploy.
- Live parameter name labels now share a measured column width, aligning checkboxes/spinners/buttons while retaining compact row heights and scrolling. Skeleton links retain parent-child centerlines and add tapered wire outlines. Ordinary Spatial targets now have purple dashed links to parents within the preview model, explicitly distinguished from skinning bones in localized guide text. Overlay mesh bounds refresh after updates; overlay remains preview-only.
- `tools/verify-animation-editor.ps1 -SkipConversion -Preview` passed (build/animation-alignment-links.log): unequal-name control alignment, parent-child bone counts, node-only parent links, actual overlay pixels and prior editor/save/native checks. No SDK build/deployment.
- Previous NbBundle default-locale lookup followed Chinese Windows even when the SDK window system had only English resources. AnimationEditorMessages now checks installed window-system locale/branding resources, falling back to English when absent. Editor ResourceBundle lookup disables JVM-default-language fallback; explicit English cannot select OS Chinese. No global locale change or saved graph change.
- Regression now covers Chinese OS + English-only SDK, installed Chinese/branded parent-language pack detection, explicit English resource fallback, and graph persistence. `tools/verify-animation-editor.ps1 -SkipConversion` passed all editor/Swing/save regressions (build/animation-sdk-language-editor.log).
- Full verifier currently fails compiling unrelated ModelConversionPreparation against non-public SurfaceBakeData.prepare; left unchanged. Added opt-in -SkipConversion to isolate editor checks. No SDK build/package/deploy or push, and no live running-SDK verification. Rebuild/restart remains user-controlled.

## 2026-11-17 GI follow-up verification
- Disk cluster engine jar now contains bounce override and rollback shader (no giPlaneMoment); running JVM not inspected. External Sponza still has old 8x8 bake. User confirms static dark patches; upper-gallery investigation and exact-camera request recorded in engine handoff. No new production fix or deployment in this follow-up. Original-path save retry again failed in elevated process startup (0xC0000005); original remains unchanged.

## 2026-11-17 optional GI diffuse bounce operation
- BakeGiProbeVolumeAction adds Diffuse bounce passes, default 1, range 1..8; reopening resets to 1. Progress reports completed probe passes. Multi-pass plus Adaptive local refinement is rejected before starting; use regular volume, or restore 1 for adaptive. Runtime probe count and GI intensity are not changed. Engine stages all passes and cancellation keeps old data; save the J3O after completion.
- Requires sibling engine GiSceneBaker.Settings.bounceCount/GiBakeBounce implementation (local changes based on engine 788a35774, SDK 067a2f80). Engine rolls back the receiver-plane shader regression and adds TestGiMultipleBounces. Targeted SDK javac passed; no full SDK build/package/deploy or push. Unrelated editor/thumbnail edits are preserved.
- Original Sponza path confirmed; candidate is ../JMESources/build/sponza-fixed-placement3.j3o (3 bounces, 16x16 distance moments, original 975-valid/5-offset layout, eight-probe sampling). Geometry/material/light/transform persistence audit and separate reload/render passed. Original external path not overwritten: external backup copy EPERM; elevated Windows exec repeatedly crashed 0xC0000005. Commit attempt also failed before execution; local uncommitted changes. Engine DEV_HANDOFF records hashes, diagnostics and remaining direct-shadow contrast. GI-off dark-side pixels are nearly black, so matching silhouettes do not rule out abnormal GI transitions. GI-only fixed-gain controls are recorded in engine handoff; do not claim the user corridor report is fully resolved.

# Development handoff — 2026-11-16

## 2026-11-16 asset thumbnail cache validity
- Existing memory/disk caching retained. AssetContentPanel revisions now exclude directory timestamps and use deterministic path ordering; standalone texture/audio/shader tiles no longer invalidate on unrelated project edits. Composite assets retain conservative project-wide dependency invalidation. Hidden importer metadata remains skipped; visible .bin/.j3odata sidecars now contribute to revision without appearing as tiles.
- Legacy PreviewHelper compares preview lastModifiedTime (not Windows creationTime), avoiding repeated regeneration after a cache file is overwritten. File watchers/debounced scan and explicit Refresh remain active. Asset content changes normally use modification time and size; same-size edits preserving timestamps are not detected and need explicit Refresh.
- JDK 25 module ant netbeans build passed, output build/cluster/modules/com-jme3-gde-assetbrowser.jar. No live UI/native render verification or timing measurements in this task. Restart SDK to load module. SDK base 067a2f80 / engine base 12f5239be unchanged; local edits, no push. Follow up with directory round trips, edited textures/models and referenced .bin changes in a running SDK.

## Animation editor beginner workflow and static-clip diagnosis
- Localization: animation editor labels, menus, guidance, full tutorial, enum/Boolean renderers, routing actions and graph validation diagnostics use AnimationEditorMessages English fallback and zh_CN resources via NbBundle (SDK locale). Saved enum names, model/clip names and user data stay unchanged; reopening after SDK language change uses the chosen language. Isolated verifier copies resource bundles alongside classes.
- Transition references: From/To are noneditable dropdowns from current states; Parameter is a noneditable dropdown from current declarations, filtered by condition type. Missing/unknown/type-invalid parameter references identify the transition row and endpoints and select it; unknown source/target states are also contextual. New End Boolean parameter alone is valid; defining parameters never auto-binds transitions.
- Live parameter definitions now immediately show a pending list with Apply preview after edits; this intentionally does not silently restart the running graph. Applying recreates working controls. Parameter rows have bounded preferred heights, top alignment and vertical scrolling as needed. Single-clip nodes are labelled Clip even when an unused blend parameter is filled.
- Validation: build/animation-localization-final.log passed locale/persistence, End parameter validation, noneditable reference dropdowns, pending parameter list, save rollback, conversion and native preview/overlay tests. Layout follow-up is recorded in build/animation-localization-layout.log. No SDK build/package/deploy or push; only isolated compilation/tests. English/Chinese resources must ship with the module.
- Added optional preview-only skeleton/target overlay (off by default): cyan skeleton, orange joints directly referenced by any clip in selected Composer, magenta Spatial transform targets. Uses modern SkinningControl armatures; does not visualize morph vertices or imply inherited joint influence. Updates on preview thread, hidden overlay stops rebuilding, replaced with each preview model; never saved to J3O. Guide explains each graph is Composer-scoped; cross-Composer orchestration and layered/masked Run+Attack remain unimplemented.
- Overlay validation: headless editor/save/conversion regressions passed; final native run build/animation-overlay-verified.log passed mounted AWT, boolean pose, 108-joint selection, orange rendered-pixel assertion, hide and full-model replacement checks. Screenshot build/animation-smoke/skinned-preview.png inspected. Fixed test RGBA/BGRA screenshot mismatch; earlier failures remain in intermediate logs. Tests compile only isolated sources, no SDK build/package/deploy. Host still intermittently exits 0xC0000005.
- Boolean parameter follow-up: defaults display/edit true/false while retaining numeric legacy serialization compatibility. Live checkbox labels reflect their value. Unreferenced live parameters show an explicit no-effect hint; schema changes require Apply preview. Live controls stay disabled until the matching asynchronous preview installation succeeds, preventing input from being sent to the previous player during loading.
- Validation: tools/verify-animation-editor.ps1 -Preview passed editor/save/conversion/native regressions; additional -PreviewOnly run verifies BOOLEAN TRUE/FALSE transitions change and restore actual pose. Logs: build/animation-boolean-tests.log and build/animation-boolean-native.log. Targeted test compilation only; no SDK build, packaging or deployment.
- Added Guide / 新手教程, source-clip preview independent of the state graph, per-clip duration/track/target/static-transform diagnostics, and Composer maximum track counts. Track references define control scope, not owner hierarchy; multiple composers can conflict on shared targets.
- Empty graphs now give inline guidance on Apply preview / Save, stop the graph preview, and preserve the original saved asset. Adding the first state selects its entry in the same undo transaction. New unsaved graphs and state creation prefer nonconstant clips; saved graphs are not silently changed. Preview clone composer speed resets to 1.
- Actual external buster_drone.j3o: 55 composers; RootNode/RootNode is 25 seconds with one constant transform track, while RootNode/Start_Liftoff has 34 changing tracks. Full-model native preview proved Start_Liftoff moves nodes over its timeline. No source model changes. Two states referencing the constant clip cannot produce motion; unconditional reciprocal transitions can also repeatedly restart clips.
- Headless empty-draft/first-entry/undo and prior graph/save regressions passed. Native mounted AWT, skinning, nested composer and real asset motion passed (build/animation-guide-native.log). Some earlier runs were interrupted by host 0xC0000005 or Java launcher errors. Native test now updates from application root and samples the complete clip duration; directly updating an attached child Node does not traverse its descendants.
- tools/verify-animation-editor.ps1 -PreviewOnly supports isolating native validation. User explicitly requested no SDK build after these edits: do not build/package/deploy the SDK. Earlier engine export attempt failed (engine build/animation-guide-engine-export.log); no SDK build was started for these editor edits. No push performed for this work.

## 2026-11-16 compressed texture preview performance
- Editor caches decoded mips in a per-window 128 MiB LRU, cleared on successful reopen; oversized levels are displayed but not cached. First visits still invoke native decoding. Visible pixels now use a packed raster and one drawImage instead of per-pixel Color allocation/fillRect calls.
- Read-only native decoding retries Windows access violation exit -1073741819 once. Other failures propagate; encoding/saving is not retried. This mitigates intermittent failures, NOT a root fix to the host/native crash.
- Validation: tools/verify-texture-tools.ps1 passed under local Adoptium JDK 25: module build, BC1/3/5/7 and ASTC four-mip decode and replacement, truncation, timeout preservation, MIME routing. Initial default JDK 11 build failed version compatibility; rerun used explicit JAVA_HOME. Live UI performance/cache interaction and the user's exact DDS remain unverified; no measured speedup claim.
- SDK implementation base 067a2f80; engine compatibility unchanged (12f5239be baseline). Module built into build/cluster; restart SDK to load it. No full SDK build or push. Preserve unrelated working-tree changes.

## 2026-11-17 Nifty preview binding and serialization
- Confirmed duplicate root currentLayer XML and null nElement in both drop/picking logs. Added shared GUIWriter source replacement filtering editor-only currentLayer for saves and legacy preview reloads, correcting schemaLocation and removing stdout dumping.
- Input cleanup removes old currentLayer debris and adds missing default style/control imports used by palette defaults. Owning GUI is activated before frame actions; runtime bindings refresh after model changes/load, not every frame.
- Source changes only, no build/compile/UI tests per user preference. Actual rendering and picking are NOT confirmed fixed. Next verify panel/button display, picking, undo/redo, reopen and two-file switching. No new engine API; recorded base engine 12f5239be / SDK 067a2f80. No commit/push. Command-process crashes required retries.

## 2026-11-16 eight-probe engine integration
- GI Properties adds `27-probe quality mode`, default off. GiPreview forwards the persistent volume option to the new engine streamer overload; saved scenes restore via engine automatic services. No rebake needed. Requires matching engine commit adding setWideProbeSampling (see sibling DEV_HANDOFF).
- Changed SDK sources passed targeted javac against updated engine classes. Full SDK build and live UI interaction were NOT run; no runtime JAR export or push. Engine GPU regression and timing evidence is recorded in sibling handoff.

这是开发状态摘要，不是完整聊天记录。以源码、测试和实际运行结果为准。

## 配套仓库与恢复
- Engine fork: https://github.com/lm165678/jmonkeyengine ，分支 `dev_feature_lightprobe`。
- SDK fork: https://github.com/lm165678/jmesdk ，分支 `master`。
- 配套实现基线：engine `1fc6e64e3a5874e004247759f76151c05606c66e`；SDK `ebf74c16cce9d4b11542c845107c6462d7632ef1`。
- 2026-11-16 已通过 GitHub API 确认两个实现提交都在对应 fork 分支；之前 SDK 推送失败的记录已过时。本文档的提交在基线之后，不改变实现。
- 新电脑无需交接 ZIP：克隆两仓库并保持相邻目录，再读各自 AGENTS.md 和 DEV_HANDOFF.md。

```powershell
git clone -b dev_feature_lightprobe https://github.com/lm165678/jmonkeyengine.git JMESources
git clone -b master https://github.com/lm165678/jmesdk.git jmesdk
```

已有工作区先检查 `git status`，处理本地修改再 `git pull --ff-only`。不要强制覆盖未提交工作。构建依赖需要重新下载，GitHub 登录在各电脑单独设置。
外部游戏项目 TestJmeNewFeature 和 buster_drone 模型不在这两个仓库，需另行迁移。生成的 release 运行库、模板解压资源、日志和构建 ZIP 不作为上下文提交。

## 下一步与切换流程
- 动画编辑器最后一轮功能修复和 SDK 构建已完成；按用户新反馈继续迭代，不重做已实现功能。
- 新电脑先适配脚本 JDK 路径、导出本地引擎，再构建 SDK 和运行相应测试。
- 原电脑 Git/终端随机崩溃尚未定位；不要宣称已修好，也不要把凭据读取失败直接归因于缺少账户权限。
- 每次结束开发更新本文：变更、测试、待办、配套实现版本，再 commit/push。另一台电脑 pull 后让助手读取。文档不会自动同步。
- 用户偏好直接执行已授权工作并测试；推送目标是自己的 fork，不是 upstream。不在文档或聊天中存放凭据。

当前文件属于 **SDK** 仓库。下文保留跨仓库关联信息，联动修改时同步检查另一仓库交接文档。

## 用户目标
在 SDK 中实现类似 Unity 动画状态机的可视化编辑器；统一 .j3o，不拆分 .j3oskinned。Java 游戏逻辑提供参数，由动画图控制状态。V1 包含参数、状态机、定时过渡、1D 混合、实时预览。分层遮罩、事件和复杂打断尚未实现。

## 已实现的引擎功能
- jme3-core/src/main/java/com/jme3/anim/graph/AnimationGraph.java：版本化 String 用户数据 jme.animationGraph.v1；FLOAT/BOOLEAN/TRIGGER 参数，Clip/1D 状态，转换条件、淡入淡出秒数、退出时间。
- AnimationGraphPlayer.java：AnimComposer 默认层运行，Transform/Morph 动画采样，1D 混合、触发器消费，close 恢复原状态；过渡不可打断。
- jme3-examples/src/main/java/jme3test/anim/TestAnimationGraph.java 为示例。
- jme3-desktop AwtPanel：修复未初始化 RenderManager/FrameBuffer 就收到帧回调造成 rm=null 的崩溃。
- 本次引擎提交还有 GI 探针、SkyLight、托管阴影、SceneServices、渲染整合等原先未提交代码和测试；这些更早开发的完整聊天不可见，具体以源码/Javadoc/测试为准。

## SDK 实现位置
jme3-core/src/com/jme3/gde/core/assets/：
- AnimationEditorTopComponent.java：图、表格、撤销、独立原生预览。
- AnimationEditorSupport.java：打开路由、完整模型克隆、保存。
- AnimationGraphComments.java：备注元数据 jme.sdk.animationGraph.comments.v1。
- ModelConversionAnalysis/Options/Preparation.java：转换预检和选项。

## 编辑器行为与操作
1. 文件和场景节点右键 Open in Animation Editor；也可右键在场景编辑器打开。旧文件缺少偏好时默认场景；AUTO 按 AnimComposer 片段识别，SCENE/ANIMATION 显式覆盖。骨骼蒙皮和存在动画片段分开识别；旧 AnimControl 模型需迁移。
2. 转换含动画/蒙皮/morph 的模型，默认关闭 Bake SDF 和表面属性。不支持的表面捕获跳过，不让整个转换失败。
3. 状态左右蓝色圆点均可拖线，方向是起点到目标。双向转换分别建立；相向端点走线，曲线分开。点击箭头定位 Transitions 行，悬停高亮，右键 Delete transition 删除。
4. 框选，Shift 增选，Delete 删除状态并清理关联连线。删除入口状态后选首个剩余状态。空图允许草稿，但必须有入口才能预览/保存。删除状态不删除源动画片段。
5. Ctrl+Z 撤销；Ctrl+Y 或 Ctrl+Shift+Z 重做，支持创建/删除、连线、移动、备注文字修改。
6. 框选按 C 建备注，Ctrl+Enter 确认，双击编辑；拖标题移动完全包含的状态，右下角调框大小；删除备注保留状态。备注随 J3O 保存。
7. Shift+滚轮围绕鼠标缩放 25%–300%；中键拖动平移。仅影响视图，不改保存坐标或撤销记录。
8. Apply preview 显示在右侧上方。主场景线程克隆完整模型，用子节点索引定位选中 Composer 的副本，避免同名节点和兄弟几何体丢失；预览线程安装模型，其余 Composer 禁用。拖动旋转，滚轮缩放。
9. 预览使用 SDK pbrenv.j3o 环境探针，使金属材质可见。预览副本移除非动画、蒙皮、morph 控制器；灯光不写回原模型。
10. Save J3O 先完整序列化后尝试原子替换。Windows 文件允许写但拒绝 rename 时，备份后通过 NetBeans 锁定流写入；失败恢复原字节，恢复失败保留备份并报告路径。流回退不保证断电原子性。

## 验证和构建
SDK tools/verify-animation-editor.ps1 验证无界面 Swing 交互、状态图运行、保存重载、GLTF 转换预检。加 -Preview 验证 OpenGL/AWT 原生预览。可选 -AssetRoot 路径 -Model 相对路径，只读测试指定模型。
已通过：双端拖线、悬停/删除、撤销重做、框选/备注、保存恢复、缩放平移和变换后节点拖动。Windows NOSHARE_DELETE reader 验证禁止 rename 时保存成功，模拟部分写入后异常验证原文件恢复与 dirty 状态保留。
原生测试通过 108 关节模型、嵌套 Composer 与兄弟几何体完整预览。只读加载用户 buster_drone.j3o，选择 Turbine_L，获得完整模型截图；未修改用户原文件。
最近 SDK buildSdk 成功，耗时 1m51s。这不代表完整引擎所有其他功能都经过全面验证。

旧电脑命令参考，先适配 JAVA_HOME：
```powershell
$env:JAVA_HOME='C:/path/to/jdk-25'
# 在 SDK 目录，先按 build-local-engine-sdk.bat 导出引擎
./gradlew.bat -PuseLocalEngine=true -PlocalEngineRepository=../JMESources/build/sdk-engine-repo buildSdk
./tools/verify-animation-editor.ps1 -Preview
```
测试脚本也硬编码旧 JDK 目录，新电脑要适配。旧电脑 python 默认 Python 2，操作脚本需 py -3。先阅读仓库 AGENTS.md；公共 API 要更新 Javadoc，包含中文步骤用例，Java/GLSL 四空格，不压缩控制流。不要回滚其他用户修改。

## 当前 Git/Windows 故障
- Git 2.34.1.windows.1 随机崩溃：cmd/git.exe --version 10 次失败 1 次，mingw64/bin/git.exe 10 次失败 3 次。退出码 -1073741819 = 0xC0000005。
- Git Bash 报 dofork child died unexpectedly / 0xC0000005 / Resource temporarily unavailable；用户还报告 Git 终端无法关闭，该问题尚未处理。
- 可靠性报告为 StackHash_0000、PCH_84，没有可确定故障 DLL。不能据此认定注册表损坏、安全软件或内存故障。PowerShell 查询事件日志也曾同码崩溃。
- 临时 MinGit 2.47.1 尝试过，未做充分重复对比。凭据管理器读取失败可能是 Git 崩溃后果，不可直接认定没有账户权限。
- 未清理注册表、关闭安全软件或重装系统。下一步是稳定新版对比或捕获崩溃转储。新电脑可能不存在此问题。


## 2026-11-16 Nifty palette drop follow-up
- User reports components remain invisible after dragging. Latest inspected log has no Nifty drop exception; live reproduction is not verified. Bytecode inspection of bundled PaletteDropTarget found no dragEnter/dropActionChanged acceptance, global GUI targeting, and duplicate dropComplete on one branch.
- Added SDK GuiDropTarget: explicit palette COPY/internal MOVE negotiation, target editor activation before commands, current-layer fallback, single completion, command-based undo, and visible/logged failures. Visual lookup now explicitly publishes its palette. This addresses concrete drag protocol defects, not proof of all rendering issues resolved.
- User explicitly prohibits builds/compilation to save cost: only source and existing bytecode/log inspection performed. No UI/native rendering tests, build, compile, commit or push in this task. Next user verification: Empty GUI -> Visual -> drag Button and Panel -> check preview and XML -> undo/redo -> repeat after switching between two GUI files. If blank persists, capture the new Cannot drop Nifty component stack and resulting XML.
- SDK base 067a2f80; engine base 12f5239be; this GUI-only working-tree change introduces no new engine API requirements. Earlier JAXB implementation dependency and XML/document/template work remain local/unverified by this task. Preserve unrelated changes.

## 2026-11-16 palette transport regression correction
- User confirms all palette components rejected. Current NetBeans DnDSupport bytecode registers CategoryList drag source with ACTION_MOVE only. Previous GuiDropTarget incorrectly required ACTION_COPY for palette payloads; this introduced unconditional rejection.
- Both drag and drop now negotiate COPY when offered, otherwise MOVE. Only POINTFLAVOR selects MoveCommand; a palette payload still executes AddElementCommand.
- No build, compilation or live UI tests per user instruction. Source/platform-bytecode inspection only. SDK base 067a2f80 / engine base 12f5239be, no engine API changes or commit/push. Next verify palette addition, XML, undo/redo and internal moves. First edit attempt crashed before writing; retry applied using PowerShell.

## 2026-11-17 待办：Lemur 与双 GUI 编辑器
- [ ] 评估 Lemur 与 Nifty 的自定义样式、控件扩展、复杂游戏 UI 功能差异；用相同 HUD、背包/长列表和动态文本场景比较 CPU/GPU 时间、draw calls 和内存，不能预先宣称 Lemur 更快。
- [ ] 将 Lemur 作为 SDK 默认提供的新项目库，应用显式初始化；保留旧项目的 Nifty 依赖及编辑器，不自动迁移。
- [ ] 为 Lemur 新增独立 GUI 可视化编辑器。先确定界面资源格式、运行时加载器及撤销模型，再实现控件树、拖拽布局、属性/主题/资源编辑、自定义控件、保存重载及运行时预览。运行游戏无需依赖 SDK。
- [ ] 两套 GUI 编辑器并存，按资源类型分别打开；未来逐步扩展复杂 3D 游戏界面能力。
- 用户要求以后提醒：下次继续 SDK GUI 规划或完成当前 Nifty 稳定性修复时，主动提起此待办。本条是后续会话的交接提醒，不是定时通知；当前会话没有可用的 automation_update 工具，未创建自动提醒。
- 状态：仅记录计划，尚未实施 Lemur 集成或编辑器，也没有性能对比结论。文档变更不改变 engine 12f5239be / SDK 067a2f80 实现基线；已有工作区修改保留。未构建、编译、提交或推送。

## 2026-11-17 TODO: SDK coding and scene Agent
- [ ] Build an Agent supporting code generation, project file creation/editing, and editor-driven game scene creation/editing.
- [ ] Evaluate existing NetBeans Agent integrations (Jeddict, BeanBot/ACP); keep model providers replaceable. Compatibility is not verified.
- [ ] Stage 1: project search, code/file editing and diff review. Stage 2: scene queries, nodes/models/lights, transforms, materials, save and preview through SDK APIs.
- [ ] Require stable object IDs, unsaved-state access, EDT/render-thread ownership, grouped undo/redo, dirty tracking, transactions and failure recovery. Distinguish J3O data from Java runtime logic.
- [ ] Stage 3: animation, physics and Nifty/Lemur integration. Stage 4: preview feedback and automated validation.
- Remind the user when resuming SDK Agent planning. Plans only; no implementation or scheduled notification. Preserve the user's default of no builds/compilation. Documentation only; implementation baseline unchanged, no commit/push.


## 2026-11-17 asset operations and Sponza surface budget (partial)
- User requests folder creation/copy/delete, file/folder rename with reference updates and derived compressed-texture rebuild/cleanup. Added explicit New Folder and corrected tree-popup copy/delete target selection (asset root excluded); grid selection clears the captured target.
- Sponza.gltf failure: 526516224-byte payload at 128 pixels/card exceeds 256 MiB. Conversion now preflights actual SurfaceBakeData card count, halves resolution 128..8 to fit while retaining all cards, logs reduction; still fails if minimum cannot fit. This payload limit does NOT bound peak memory. Expected given payload at 64: 131629056 bytes. Engine baker budget remains unchanged.
- NOT complete: dependency-aware rename, delete reference checking and compressed-texture invalidation/rebuild. Existing ExternalChangeScanner.fileRenamed merely detaches listeners. Do not enable a naive rename and claim references are updated. Need transactional reference rewrite supporting textual and binary assets, unsaved scenes, relative GLTF paths and derived-resource ownership before deletion/rebuild.
- No build/compile/runtime conversion or filesystem interaction test per user request. Source inspection only, original external Sponza asset untouched. Existing baseline engine 12f5239be / SDK 067a2f80; no new engine API required. No commit/push. Preserve other edits.

## 2026-11-17 configurable low-resolution bake parameters
- Conversion options now carry Bake settings: SDF XYZ defaults 32/32/32, surface 32 pixels/card, separate 64 MiB payload budgets. User may configure axes, resolution and budgets; SDF limited to engine 64M samples/axes 2..1024 and selected payload budget. Surface 8..2048 and budget hard cap 256 MiB, optional auto reduction (default on), actual card-count preflight before capture. Input spinner edits committed and errors return to dialog. Legacy constructors receive new low-cost defaults.
- These are payload protections, NOT a guaranteed total CPU/GPU peak cap. No build/compile/runtime tests per user preference. Dependency-aware rename work remains active and incomplete; found existing GeneratedTextures registry plus filesystem move/delete interception, which should be reused rather than duplicating ownership logic.
- Current engine SurfaceAttributeAtlas supports whole-card residency, upload budgets and renderer dirty-rectangle upload; SurfaceAttributeData still stores all float arrays in J3O/CPU memory. Disk-backed chunk streaming and automatic incremental rebake must not be claimed.


## 2026-11-17 documentation entry points
- Both AGENTS.md files now specify startup reading order including the engine rendering roadmap and local handoff backlogs. Relevant pending work should be mentioned on resuming its area.
- Documentation only; implementation baselines unchanged. No build, commit or push.

## 2026-11-17 hidden animation preview rendering
- User reports scene-editor FPS drops after opening animation editors. Source inspection found independent editor.SceneApplication previews kept simulating/rendering while hidden: AwtPanelsContext only sleeps 100 ms, then still calls application.update(). Animation UI timer also kept running.
- Embedded editor.SceneApplication now tracks AwtPanel SHOWING_CHANGED on EDT using a volatile flag. Its render-thread update sets paused while hidden, preserving queued task processing; resets the timer on resume to avoid accumulated hidden animation time. Visible split panes keep rendering. Animation TopComponent stops/restarts its Swing refresh timer on hidden/showing and respects visibility after asynchronous startup.
- Scope: independent embedded previews using core.editor.SceneApplication. Does not pause the shared core.scene.SceneApplication or promise zero CPU/VRAM consumption; retained contexts and queued work remain. Simultaneously visible previews still share GPU resources.
- Source/diff whitespace inspection only; no builds, compilation or native/UI performance tests per user instruction. Verify animation -> scene tab switching, multiple hidden animation editors, split views, resume playback and save/close while hidden on the next user-run SDK. Actual FPS gain is unmeasured.
- Inspected HEADs: SDK 067a2f80, engine 788a35774; SDK working-tree change uses existing engine API, no engine source change in this task. Preserve unrelated edits. No commit or push.

## 2026-11-17 surface budget compilation fix
- User-provided build log against engine 788a3577409fb057b452c1435ef8ba76fd101ecb reports one compiler error: ModelConversionPreparation accessed package-private SurfaceBakeData.prepare().
- Replaced that preflight call with public SurfaceCacheLayout.bake(result).getCardCount(), the same layout builder used internally by prepare(). Resolution reduction and payload protections remain unchanged; no engine API change or extra fingerprint computation for preflight.
- Verified public declarations and source diff only. No build/compile run per user instruction; successful SDK compilation is not yet confirmed. Compatible baseline remains SDK 067a2f80 / engine 788a35774 plus working-tree changes. No commit/push.

## 2026-11-17 animation editor width constraints
- Footer diagnostics now request zero preferred width while retaining font-derived height; existing zero label minimum widths and full-text tooltips remain. Long translated diagnostics no longer inflate the footer's preferred width.
- Set both horizontal split children (graph tabs and preview container) to zero minimum size, so skeleton help text/native preview minimum dimensions cannot block divider movement. Narrow labels use Swing clipping; live controls retain scrolling.
- Source/diff checks only, no build or UI verification per user instruction. Next verify divider dragging in both directions and narrow-window tooltip access. No engine change, commit or push; prior baseline and unrelated edits preserved.

## 2026-11-17 Nifty namespace serialization correction
- User reports NAMESPACE_ERR on preview, including after correcting schemaLocation to the namespace/XSD pair. Inspected legacy GUI bytecode: its xmlns field is an ordinary @XmlAttribute. Our GUIWriter direct JAXB-to-DOM path cannot legally create that reserved attribute with no XMLNS namespace.
- GUIWriter now marshals to XML bytes first, parses them namespace-aware with DOCTYPE disabled, then removes currentLayer and serializes as before. Parsing interprets the legacy xmlns field as a proper namespace declaration without modifying model annotations or user XML.
- Source and legacy bytecode inspection only; no compilation/build/live preview per user request. Running SDK cannot see this source change until rebuilt/restarted. Need confirm empty GUI opens, palette addition, save/reload and undo after user rebuild. No claim that all Nifty issues are resolved. Engine unchanged; prior compatible baseline retained, no commit/push.

## 2026-11-17 source snapshot: unresolved capture and conversion
- Sponza conversion still fails with repeated Compressonator 0xC0000005 after retry; this is not a root-cause fix. Standalone comparisons completed five encodes each with and without RenderDoc loaded (HookIntoChildren=0); the injected JVM had abnormal shutdown. SDK environment causality remains unconfirmed.
- The inspected user capture contains desktop OpenGL draw/dispatch events, a 32x32 default backbuffer and an offscreen 1229x469 viewport. Replay logs contain glslang built-in parsing errors. Blank displayed output remains unresolved; no mobile pipeline regression has been established. Investigation paused at user request.
- User requested Git snapshots of both repositories. No new builds or runtime validation for this request. Exclude generated archives, downloaded tools, extracted assets, logs and automatic build revision increments. Pre-snapshot engine base 788a3577409fb057b452c1435ef8ba76fd101ecb, SDK base 067a2f80; paired pending engine changes concern GI/shadows. Verify remote refs before claiming push success.

## 2026-11-17 paired implementation snapshot
- Engine implementation: 4ee85daa4; SDK implementation: e38d71c5. These identify the paired source snapshots, not a newly validated binary release. No additional build/runtime checks for this Git request. Earlier test results and unresolved issues remain as documented.
## 2026-11-16：Windows 构建脚本可迁移配置（本地未提交）
- 两个 BAT 改为调用 tools/build-local-sdk.ps1，不再硬编码旧用户 JDK 路径。
- 自动查找 JDK 25、相邻 jmonkeyengine/JMESources；SDK_JAVA_HOME/SDK_ENGINE_DIR 或被 Git 忽略的 sdk-local.json 可覆盖。提交 sdk-local.example.json 作为格式示例，不提交本机配置。
- 两个 BAT 的 check 模式均验证成功：本机 JDK C:/Java/jdk-25.0.5+8、引擎 ../jmonkeyengine；父进程 JAVA_HOME/PATH 保持不变。
- 临时目录验证相邻 JMESources（含空格 SDK 路径）自动识别成功，错误的显式 JDK 路径被拒绝。
- build-local-engine-sdk.bat：导出引擎后构建 SDK；加 run 启动。build-and-run-sdk.bat：使用同一检测出的引擎仓库构建/运行，不重新导出引擎。
- 代理默认检测 127.0.0.1:7897；JSON proxyHost 为空禁用自动代理。不会修改系统/用户环境或 UE5 JDK。
- 本轮仅测试环境检测与失败处理，未执行完整引擎导出/SDK 构建，尚未 commit/push。配套实现基线仍见前文。

## 2026-11-16：版本参数拆分修复
- tools/build-local-sdk.ps1 的 -PjmeVersionName 参数加引号，避免 Windows PowerShell 5.1 将 3.10.0-local-SNAPSHOT 拆开，导致 Gradle 把 .10.0-local-SNAPSHOT 当作任务。SDK 的 useLocalEngine 参数也统一加引号。
- 保留既有未提交修改；完整导出/构建结果另行验证。
- 验证通过：Windows PowerShell 5.1 cmd echo 对比复现旧参数拆分，引用后完整保留；真实 Gradle 9.8 help 成功，Build Version 为 3.10.0-local-SNAPSHOT（JDK 25）。未运行完整 SDK 构建或启动。

## 2026-11-17 portable launcher submission
- Submitting the previously local portable BAT/PowerShell launchers, example JSON, ignore rule and README instructions. This supersedes the earlier uncommitted status of those launcher changes.
- Both BAT check modes passed after pulling SDK ce898db1 and engine 2bba6193b: detected JDK 25 and sibling jmonkeyengine. Check mode does not compile, download or start SDK. No full build/runtime verification performed for this submission.
- Generated deployment/applet ZIP changes, nbproject automatic build revision and extracted/generated release assets are excluded and preserved locally. No user/system JDK or UE Android configuration changed.

## 2026-11-17 capture failure feedback
- RenderDocSupport now reports discarded captures when preview drawing fails. Engine line-width clamping and exception-safe debug groups address the identified gizmo rendering errors; export/rebuild required. Targeted capture-class compilation passed. Isolated native test exited after GL initialization without a saved capture; actual SDK capture/replay/overlay validation remains outstanding. No full SDK build or deployment; concurrent changes preserved.

## 2026-11-17 CodeBuddy Nifty editor repair
- Used the user-supplied D:/MyDowns/com-jme3-gde-gui.nbm as the functional reference and replaced the bundled Nifty-Editor0.5.7.jar with its more complete legacy model, including effects.
- GUIWriter now merges the current model with source XML so text, interact, effects and unmodeled attributes survive Visual saves. It filters implicit x/y=-1px and removes unused automatically injected default references. Visual loading filters text/interact only for the legacy model and restores text through TextCreator.
- Selection now publishes one component node instead of mixing the DataNode with multiple GElementNodes. Palette drops target the selected container and force binding reload. Invalid property edits restore the previous value and preview; undo/redo failures are caught and synchronized instead of escaping into the EDT.
- Validation: JDK 25 targeted compilation passed for GUIWriter, NiftyGuiVisualElement, GuiDropTarget and GElementNode. NIFTY_XML_ROUNDTRIP_PASS and NIFTY_EDITOR_COMMAND_PASS. The latter used a real J2DNiftyView and verified text preview, width property exposure, selected-parent button insertion, undo/redo and invalid-width recovery.
- Deployed to jme3-gui/build/classes and build/cluster module/ext JARs. Restart SDK before testing. No full SDK build or manual NetBeans interaction yet; no commit/push.

## 2026-11-17 Nifty navigator, property and ID follow-up
- Opening/activating Nifty Visual now opens the Navigator window. Visual selection sets activated nodes on both the multiview TopComponent and the global Properties window, so clicking a component no longer requires selecting it again in Navigator.
- Property sheets expose common size/layout/alignment/visibility attributes. Layout, alignment and boolean values use choice editors; resource fields use the resource chooser. Sizes are validated through Nifty SizeValue and bare numbers become px; colors and malformed values are rejected inside the property panel without escaping to the EDT.
- GElementNode observes its model and immediately refreshes Navigator labels after ID edits. A source-level EditAttributeCommand override validates IDs against the live GUI tree, fixing undo after rename. GUIWriter falls back to hierarchy-position matching so unknown content survives ID renames.
- JDK 25 compilation and both smoke tests passed again, including choice editors, unit completion, size undo/redo, ID tree refresh/undo/redo and post-rename XML preservation. Redeployed the module JAR; restart required. Manual NetBeans interaction remains unverified. No commit/push.

## 2026-11-17 local SDK daemon startup follow-up
- User engine export succeeded (155 tasks); SDK Gradle 9.2.1 then failed to start its daemon with empty process output. No SDK compilation was reached; iOS deprecation notes were not the failure.
- Retried only `gradlew.bat help --offline --stacktrace` with the same local Adoptium JDK 25 path: BUILD SUCCESSFUL in 16s, new daemon 28820, engine export revision 25d2b714d recognized. Startup failure is not reproduced; root cause remains unknown. No JDK/Gradle configuration change or complete SDK build performed.
- Next user action: rerun build-and-run-sdk.bat to reuse the exported engine and build/start SDK. Full SDK compilation and runtime remain unverified by this follow-up.

## 2026-11-17 sky intensity and SkyLight property cast repair
- Added Sky lighting > Sky light intensity (>=0) to Sky geometry carrying SkyLightControl, and the same property on SkyLight. Default1, zero removes contribution, above1 brightens; serialized by new engine API. Existing render-thread embedded property/dirty path retained. Background/local reflection probes unchanged; baked GI not recomputed.
- User screenshot/stack confirms ClassCastException at JmeLightProbe.createSheet: Capture resolution used makeProperty, which casts the control to the node LightProbe type. Changed it to makeEmbedProperty with SkyLightControl.class. Running old SDK requires rebuild/restart.
- HDR linear-color messages are separate Material.checkTextureColorSpace warnings for parameters without an explicit requirement; no evidence that these caused the property exception. No forced sRGB conversion or global warning suppression applied.
- Requires matching local engine changes after25d2b714d and SDK aftera116cf8d. Extended engine smoke example, but no build/compile/native UI/persistence validation run per existing preference. Source/diff checks only; no commit/push. Preserve unrelated concurrent edits.

## 2026-11-18 Unity package import
- Added .unitypackage MIME/data object and Import Unity Package action. Worker restores standard gzip/tar GUID asset/pathname/asset.meta entries into a fresh UnityImport-* sibling folder, preserving Assets paths and metadata. Existing assets are not overwritten. Optional J3O conversion delegates discovered .unity/.prefab files to existing ConvertModel dialogs; packages without scenes can still expose source models for individual conversion.
- Rejects traversal, duplicate entries/paths, links and unsupported tar extensions; checks checksums/truncation and limits import to 100000 entries/4 GiB. Failed restoration removes its new output. Repeated imports prefer GUID references inside the current import directory. Missing dependencies and unsupported Unity components retain existing importer limitations.
- Added standalone UnityPackageImporterSmoke source covering restoration, repeat imports, traversal cleanup and invalid gzip. Not compiled or executed, and no SDK build/deployment or actual user package conversion per existing no-build preference. Manual verification after rebuild: right-click package -> Import Unity Package -> optional conversion; inspect imported metadata, materials and J3O. Extended tar/PAX variants currently report unsupported entries rather than silently importing incomplete data.
- SDK HEAD inspected a116cf8d; no engine API changes. Concurrent unrelated work preserved; no commit/push for this task.

## 2026-11-18 asset menu and SkyLight fixes (local source changes)
- AssetContentPanel uses NetBeans Mnemonics for New File, removing the visible ampersand. Rename now accepts a single file or folder, uses DataObject.rename with unchanged extension, refuses collisions and registry-owned generated DDS outputs/containing folders. Ordinary DDS remains eligible. Existing asset references are not rewritten.
- JmeLightProbe no longer calls attachEnv with null while constructing its editor; NetBeans supplies the environment. ButtonInplaceEditor no longer reinstalls its UI during value access and recognizes its own component. These concrete defects are fixed; the reported Refresh freeze was not reproduced in the thread snapshot (EDT was inside a sky texture selection dialog), so do not claim the entire freeze resolved.
- Deleting a light now removes any sky whose SkyLightControl owns that exact light, on the render thread. Sky deletion already removes the corresponding light via engine parent synchronization. No name matching or new serialized metadata; unrelated lights remain intact.
- Validation: all four changed Java files compiled with JDK25 against current SDK runtime jars, annotation processing disabled. Local ignored .gradle/sdk-ui-fix-check/UiSmoke checks mnemonic display, button lifecycle, editor construction, and Sky removal/light cleanup: SDK_UI_SMOKE_PASS. Actual file rename, reverse deletion through SDK UI, save/reload and native Refresh still require integration verification. No full SDK build/deployment or push; unrelated generated/build changes preserved. Compatible source baseline remains recorded above.

## 2026-11-18 material instance editor compact UI and preview (local source)
- Options, Textures & Colors and Additional RenderState now share one switching tab strip. Width-tracking scroll containers use 32px units, 160px scrollbar blocks; labels can shrink with Swing ellipsis and full-name tooltips. Header fields are flexible; texture controls remain fixed-size beside a flexible name. Runtime layout customization follows initComponents so generated forms remain unchanged.
- Asset browser now exports TextureDataFlavor. Texture row and thumbnail accept copy drops, complete the DnD protocol and reject assets outside the destination primary asset root. Existing click-to-select and widget save/change paths remain. Multiple asset-root drops are currently restricted to the primary root.
- Preview toolbar uses 18px Feather box/square/globe/coffee/sun icons with 28px buttons, neutral/blue states. MIT license and exact source attribution bundled beside SVGs. Coffee denotes the existing teapot mesh.
- Left drag orbits the viewing camera around the model; hold L and drag adjusts a request-local directional light. Key dispatcher is removed with the widget. Environment defaults ON using bundled pbrenv.j3o; sun button disables only this material preview. PreviewRequest lighting is opt-in, so other requesters retain legacy defaults. Shared processor light list is restored after each material capture. No scene light or material asset is changed by preview controls. Missing environment logs a warning and falls back to direct light.
- Coalesced drag refresh retains a pending final frame. Cleanup prevents subsequent refresh scheduling. Material geometry assignment moved to the render-thread portion of showMaterial.
- Validation: nine changed Java sources independently compiled with JDK25 against SDK runtime jars (-proc:none). tools/tests/MaterialUiSmoke.java exercises 3 tabs, width tracking at 1100/560px, scroll increment, 18px SVG icons, default environment toggle, orbit/light state, request vector isolation, real bundled probe readiness and shared light restoration; MATERIAL_UI_SMOKE_PASS. Synthetic Swing screenshots inspected under engine .gradle/material-ui-check; not live SDK GPU screenshots. Compile/run argument files and logs are local ignored artifacts there.
- Remaining verification: actual asset-panel mouse drop/save/reload, native preview orbit/light/PBR visual result, and full SDK rebuild/deployment. No full SDK build or push. Engine source unchanged; prior documented implementation pairing applies plus these SDK working-tree changes. Earlier asset/Sky fixes and unrelated build artifacts preserved.

## 2026-11-18 reflection probe properties and light gizmos (local)
- Removed Legacy diffuse IBL from local reflection-probe property sheet at user request; serialized legacy flag unchanged. This UI removal does not migrate legacy probe lighting, and old diffuseEnabled=true probes can still suppress SkyLight SH (see engine SkyLight audit).
- Refresh editor now tolerates null PropertyEnv and paint no longer refreshes the explorer. Prior removal of eager null attachEnv retained. Requires SDK rebuild for existing session.
- Unbaked probe bounds now use configured radius instead of zero. Probe sphere uses visible unshaded placeholder until a reflection cubemap is ready. Range visualization uses three world-space wire circles with 2px lines instead of one billboard dashed circle. Radius remains real world distance; a camera inside a large 100-unit volume may not see its boundary in a small view.
- New ScreenSizeControl scales bulb/probe icons to 24px and direction arrows to nominal 64px using camera projection and actual parent transforms, not callback parameter-scale getters. Influence radii/cones are deliberately not screen-scaled. Foreshortening still applies to direction arrows.
- Validation: light gizmo package plus two probe property classes compiled against SDK jars with JDK25 (-proc:none); perspective/orthographic pixel-size math and null editor attachment regression LIGHT_GIZMO_MATH_PASS. No native gizmo/selection/picking or rebuilt SDK UI verification. No deployment/push; prior material-editor fixes and unrelated changes preserved.


## 2026-11-18 independent SkyLight / static GI integration
- Engine SkyLight now extends Light with Type.Sky, not LightProbe. SDK has separate JmeSkyLight node/Refresh editor; reflection properties no longer contain sky branches. Scene, material and animation preview global environments explicitly convert bundled legacy probes through SkyLight.fromEnvironment, preserving preview diffuse illumination after local probes become specular-only.
- GI Volume exposes read-only Sky bake status: No bake / Unknown (rebake) / Current / Stale (rebake). Previous baked GI remains live until successful manual rebake. Sky capture/intensity/enabled/diffuse changes invalidate freshness; status persists for embedded and external volumes. Legacy files require a bake to establish freshness.
- Engine/SDK source bases: engine 17df591af, SDK 14dadd89 plus local edits. Must export matching engine and rebuild SDK together; old SDK binaries are not compatible with the changed SkyLight superclass. No full SDK build/deployment in this task.
- Validation: ten SDK source files compiled with JDK25 against updated engine classes and existing NetBeans jars. Engine GPU tests passed all four rendering paths: skyLightSmoke; skyVolumeCompositionSmoke (288 combinations plus 6 directional checks); existing giVisibilityFilteringSmoke and giCompactVisibilitySmoke. Eleven targeted engine JUnit tests passed, including unchanged GI irradiance/moments bytes with different sky payloads and sky freshness/save-load. Actual rebuilt SDK property UI/native previews remain unverified.
- Existing Lemur/dual-GUI and coding/scene Agent backlog remains pending and outside this lighting change. No push performed; unrelated edits retained.
- Additional headless MaterialUiSmoke assertions reached MATERIAL_UI_SMOKE_PASS after SkyLight migration, but mixed-runtime AssetBrowserStartup raised HeadlessException on EDT and sandbox preference warnings. Not a clean full SDK UI pass; actual rebuilt native UI remains unverified.


## 2026-11-18 fork source snapshot
- User requested committing and pushing current engine and SDK source changes to the existing lm165678 forks. Includes lighting/GI, editor/UI, postprocessing source, tests, icons and handoff/roadmap changes. Generated archives, extracted assets/native libraries, automatic SDK version metadata and the local engine proxy remain outside this source snapshot.
- This Git operation does not add build or runtime validation. Earlier per-feature results and limitations remain applicable; not a fully validated binary release. Paired implementation hashes are recorded in a following documentation commit.


## 2026-11-18 paired implementation snapshot
- Engine implementation: 619ed3e24d8c2bc1012d928ee22b46fe97641a06 (dev_fix_lightprobe_issue).
- SDK implementation: 788da869f934480e11e38d612ad5b74117de5929 (master).
- These are paired source snapshots; the following commits only record this pairing. Prior validation results and limitations remain applicable. No additional full SDK build or binary deployment for this Git operation.

## 2026-11-19 binary Unity prefab conversion diagnosis
- User LakeSideCabin RockLarge_A_mdl.prefab inspected read-only: contains NUL bytes and Unity version 5.1.1f1, confirming binary serialization rather than YAML. Archive restoration succeeded; existing Unity scene reader cannot deserialize this binary format.
- ConvertModel now skips analysis/save when loadAsset returns null after logging its loader failure, avoiding the misleading secondary Cannot inspect a model that failed to load exception. UnityYaml reports binary serialization explicitly with text-reserialization instructions; headerless text receives a separate diagnostic.
- Binary Unity deserialization is NOT implemented. To convert this package, reserialize source scenes/prefabs/materials as text in Unity and retain .meta files. Merely selecting Force Text does not rewrite existing assets. External assets untouched.
- Source inspection only, no build/compile/runtime checks per existing preference. Rebuild/restart required. No engine changes, commit or push.

## 2026-11-19 compact GI curved receiver source fix
- Engine working tree after1bce1b485 limits receiver-plane depth extrapolation using unperturbed mesh-normal variation; no SDK code/API change. Export matching engine shader resources and rebuild SDK to adopt; running SDK was not replaced. No scene rebake needed.
- Candidate four-path Lighting/PBR compact visibility, confidence/cell continuity and wide reference GPU checks passed. Actual TestScene2 arch stripe removed, planar-wall ROI max delta1/255. TestScene3 unavailable here and remains unverified. No full SDK build/deployment/push; see engine handoff for exact scope, measurements and limits.

## 2026-11-19 curved receiver opt-in switch (supersedes previous note)
- GI Volume > Curved receiver correction defaults Off, including old scenes. On enables curved projection limiting; Off restores the previous plane algorithm. Scene save retains the flag; no rebake required. GiPreview forwards it through the updated engine streaming API. Requires paired engine export/SDK rebuild; no running SDK replacement.
- Engine defaults/persistence/page toggle tests5/5 passed. Actual TestScene2 off/on/off captures confirm Off matches pre-fix baseline byte-for-byte; On removes the arch strip. Two SDK classes compiled against updated engine. TestScene3 remains unavailable, per user. No full SDK build/deployment, commit/push.

## 2026-11-22 Original asset visibility (partial work)
- Asset Browser has a default-on Hide original assets checkbox, filtering source image/model extensions while retaining folder navigation. No files are removed. Source change only; compilation and UI validation not run. Automatic conversion/import queue and Link/AddTo drop selection remain unimplemented. Existing unrelated working-tree changes preserved. Bases SDK 576ecbe6 / engine db8ab1149 plus working trees.

## 2026-11-22 Asset visibility and model drop checkpoint
- Default-on source visibility filter and Link/AddTo popup source compile passed using JDK 25 and current runtime dependencies (build/asset-import-check). Link wraps the loaded model in AssetLinkNode; AddTo retains the existing embedded insertion. Both use existing undo/dirty state handling. Viewer accepts NetBeans NodeTransfer for Assets tree nodes as well as Asset Browser transfers. Native drag/drop and persistence checks still pending; no runtime JAR deployed.
- Automatic external import/conversion queue, source retention checkbox and mixed model/texture handling remain outstanding. This is not completion of the three-part request.

## 2026-11-22 External drop/paste conversion and model drop choices

- Asset Browser's default-on Hide original assets checkbox hides source images/models and glTF BIN/OBJ MTL sidecars; folders remain navigable. Unchecking restores visibility without deleting files.
- External OS file-list drops and Ctrl+V share ExternalAssetImport in both Project Assets (root and child folders) and Asset Browser (grid, blank viewport and folder tree). A debounced recursive listener also handles newly created files/folders copied directly into the active project's disk Assets directory. Existing files at watcher startup are not converted. Project close detaches registered watchers.
- A serial worker stages the batch outside Assets. Models show the existing conversion options plus default-off Copy original resources to Assets. Referenced textures are consumed with the model; unrelated PNG/JPEG/BMP/GIF images get sequential BC3 DDS dialogs with width/height and optional full mip chains. Models are processed first to identify shared dependencies. J3O material texture keys reference generated DDS directly, so omitted PNG/JPEG originals and the SDK mapping are not runtime requirements. Unsupported texture conversions retain required source dependencies rather than breaking the model.
- Confirmation of all dialogs precedes publication. Cancel discards the staged batch; errors preserve original inputs. Outputs publish via sibling temporary files, reject differing existing files, and roll back new outputs on publication failure. External originals are never deleted. For files just copied into Assets, unretained consumed sources are removed only after successful publication and byte equality checks. Changed inputs abort import. New copied batches are detected after two seconds of filesystem quiet; an incomplete dependency set reports an error and remains intact.
- Viewer model drops expose Link/AddTo and reuse existing undo/dirty state. Link serializes AssetLinkNode, AddTo embeds the loaded model. Both Asset Browser transfer flavors and Project Assets NetBeans NodeTransfer are accepted; J3M uses existing hit-target material application. Popup coordinates now use the drop component; changing scenes before choosing cancels insertion.
- Paired bases SDK 576ecbe6 / engine db8ab1149 plus current working trees. JDK25 targeted compilation passed. ExternalAssetImportSmoke passed EXTERNAL_ASSET_IMPORT_PASS dialogs=9: DDS dimensions/full mips, default source exclusion/retention, cancel, mixed OBJ/image dialogs, J3O loading, linked-model persistence, DDS references after removing source and mapping, real file-list PasteType queue, filesystem-created-image event, and overwrite protection. Source diff whitespace checks passed. Repeat with tools/verify-external-asset-import.ps1 using existing built dependencies; this is an isolated javac/test command, not SDK packaging.
- Per user request, no SDK build, packaging or runtime module replacement. Actual OS mouse drops in the complete SDK, J3M viewport interaction, and FBX/glTF-specific complete imports still need user validation after their build. Tests do not establish native UI behavior or every model codec. Prior partial-work entries are superseded for implemented source paths, not these validation limits.

## 2026-11-22 G visibility, directional arrows and Start camera marker

- G now saves/disables the Editor tools viewport as well as the overlay and existing root/stat cull state, restoring each viewport's original enabled state on the second press or scene close. Fixed-pixel markers use CullHint.Never and could survive ancestor-only hiding in flattened render queues; disabling their dedicated viewport covers Start, lights and newly created helpers without changing game objects.
- Directional light shafts are 1.8x their previous shaft length (total marker roughly 178 rather than 108 screen pixels); shaft/tip thickness and spot arrows stay as before. Start forward/up arrows now use cylinder shafts and cone tips with the same screen-space thickness as lights, retaining cyan +Z, green +Y and the yellow origin cube.
- Start adds a camera-facing 36px camera icon offset above the origin. The original camera.svg and camera.png fallback use the existing editor icon palette/outline weights and the SDK BSD license; this new drawing is not copied from Feather. All markers remain tools-only and follow G.
- SDK base 576ecbe6 / engine db8ab1149 plus working trees. Targeted JDK25 source compilation passed against paired local engine classes. RTX5080 native AWT/OpenGL4.3 helper-only TransformGizmoSmoke passed EDITOR_HELPER_VISIBILITY_AND_START_PASS: viewport hiding/restoration, camera marker creation, solid Start axes and retained colors. No pixel-diff or complete-editor mouse verification. The broader existing transform smoke encountered an unrelated Q-mode missing-arrowhead assertion with the mixed local runtime; it is not reported as passing. Use -Djme.test.helpersOnly=true for this targeted regression.
- Per user request, no SDK build/package/deployment or runtime JAR replacement. Rebuild with the paired current engine/SDK sources. Independent checks used build/asset-import-check/gizmo.args and gizmo-run.args; these local dependency paths are not portable artifacts.

## 2026-11-22 Audio preview isolation and JNI media import

- Asset Browser inspector and standalone sound window now play decoded PCM through a per-preview Java Sound Clip worker instead of adding AudioNodes to the editor's OpenAL renderer. Stop, selection change, window close and replay cancel queued playback and close the device line; natural completion releases it too. This removes the browser playback path from the reported ALAudioRenderer state-0 failure. The underlying cause of invalid source status in arbitrary scene/game OpenAL playback was not reproduced or broadly fixed.
- ExternalAssetImport now queues MP3/MP4/M4A/AAC/FLAC conversion dialogs for both Project Assets and Asset Browser drops/paste and newly copied disk files. Defaults: OGG Vorbis, 44100 Hz, 160 kbps, original retention off. MP4 exports its first audio track, no video; more than two channels downmix to stereo. Output names include the source extension (song.mp3.ogg) to avoid MP3/MP4 basename collisions. Existing source files have Convert audio to OGG in Project Assets/Asset Browser; Browser double-click/inspector also offers conversion. Raw media gets a source card rather than unsupported waveform playback and follows Hide original assets.
- In-process JavaCV/JavaCPP 1.5.12 and FFmpeg 7.1.1 JNI only: no ProcessBuilder, ffmpeg process, executable selection or runtime download. Gradle corelibs declares default LGPL native artifacts for Windows x86_64, Linux x86_64/arm64 and macOS x86_64/arm64. Existing library generation exposes them to SDK modules; copyBaseLibs verifies each target JAR/native payload and distributed notices under jme3-core-libraries/release/licenses/audio-import. Unsupported architectures fail conversion with originals preserved. JavaCPP uses its normal extracted-library cache. No new engine dependency or scene asset format.
- Windows x64 targeted JDK25 compilation passed with downloaded pinned dependencies in build/audio-import-check/lib. JNI_AUDIO_IMPORT_PASS used JNI to generate real MP3 and audio+video MP4, converted them to stereo 48k OGG, decoded output samples, rejected a video-only MP4 and corrupt MP4, and checked failed output cleanup/original retention. ASSET_AUDIO_PLAYER_PASS exercised silent real Java Sound playback, repeated play/stop, line release and queued-play cancellation. Tests are tools/tests/AudioImportSmoke.java and AssetAudioPlayerSmoke.java. Compilation/runtime argument files and logs are local build/audio-import-check artifacts. No general claim about other codecs or audio devices.
- Per user request, no SDK build, packaging, engine export or runtime module replacement. Full Gradle packaging checks and Linux/macOS JNI execution remain untested. Actual complete-editor UI/audio interaction should be checked after the user's build. Compatible bases remain SDK 576ecbe6 / engine db8ab1149 plus current working trees. Engine sources were not changed for this audio work.

## 待办：完善地形编辑器（2026-11-22）

- [ ] 先盘点现有新建/导入、地形画刷、材质绘制及保存能力，结合实际场景确认最影响使用的缺口和优先级。
- [ ] 优先验收高度与材质编辑的笔刷反馈、撤销/重做和保存重载。再评估大地形编辑性能、分块边缘、碰撞同步及新版渲染路径的预览；将确认的问题拆成独立任务。
- 下次继续 SDK 地形工具、Terrain、画刷或大地形集成时，主动提醒用户这项待办。引擎侧 VT（含 LightProbe 烘焙评估）和 Virtual Shadow Maps 见引擎的 `RENDERING_QUALITY_ROADMAP.md`。本次仅登记计划，无实现或构建。

## 2026-11-22 修复转换后的 OGG 无法播放

- Windows x64 固定 JNI 包缺少 libvorbis，JavaCV 只指定编码器名称时写出 Ogg FLAC。旧产物使用 SDK OGGLoader 复现 `The file has no identification header`。新转换显式选择 Vorbis，必要时使用内置立体声编码器，提交前用 jME3 OGGLoader 检查 PCM。
- Project Assets 和 Asset Browser 的旧转换文件可右键 `Repair converted OGG...`。确认后在后台验证 Ogg FLAC，转为 44.1 kHz / 160 kbps Vorbis，再原位原子替换，保留路径与引用。验证失败保留源文件，已有 Vorbis 不会被重写。
- Windows x64 定向编译与 JNI_AUDIO_IMPORT_PASS 通过。用 SDK 所带 OGGLoader 完整解码测试输出，覆盖单声道 MP3、音视频 MP4、全部界面采样率/码率组合、旧输出修复、拒绝重复修复及无音轨和损坏文件。未构建或替换 SDK，完整编辑器菜单尚未手测。配套基线 SDK 7b4f4b1b、引擎 44031b02e 加工作树，无引擎实现改动。

## 2026-11-22 修复普通纹理编辑器加载后空白

- 图片后台解码完成时，画布可能已经以空尺寸布局过。原逻辑仅从外层面板调用 revalidate，未刷新滚动区内的画布布局。现在由画布发起 revalidate 并重绘视口，加载及失败状态也同步首选和最小尺寸。Fit、缩放和平移共用此更新路径。HDR 继续以只读方式显示并可使用导航控件。
- Windows JDK25 定向编译和真实 Swing 窗口的 TextureNavigationSmoke 通过。回归等待视口先布局，再安装模拟异步解码完成的 HDR 图像，不强制重排，检查画布大小和实际绘制像素。原模块在同一测试中复现空白，新代码通过。现有 Fit、滚轮、右键平移及压缩纹理编辑器导航检查通过。未构建或替换 SDK，未使用用户的 Path.hdr。基线 SDK 7b4f4b1b / 引擎 44031b02e 加工作树。

## 2026-11-22 Physics preview feedback and Reset gravity

- Fixed editor collision synchronization treating dynamic rigid-body/character logical-update pose writes as manual scene edits. Bullet steps after logical controls; feeding the displayed prior pose back on the next frame prevents normal falling. SceneApplication now scopes logical updates through EditorCollisionSync; registered simulated poses are excluded from transform notifications in that scope. Static/kinematic bodies and edits outside that scope retain synchronization.
- Reset temporarily protects gravity while restoring a detached dynamic body, then restores the original protection flag. This avoids Minie's detached/unprotected gravity warning without changing the authored protection policy.
- JDK 25 targeted compilation passed against current engine AppSettings source and SDK dependencies. Native Minie EditorPhysicsSmoke now covers a mass-5 cube with editor frame ordering, reset/J3O state restoration, protection flag and replay. Disabling the new notification suppression reproduces the failed falling assertion. The user's exact scene/upward motion and Rebuild collision UI interaction have not been reproduced; overlapping collision shapes remain a possible additional issue.
- SDK 576ecbe6 / engine db8ab1149 plus current working trees. No asset changes, full SDK build or deployment; local verification files are build/physics-preview-fix. Runtime modules must be rebuilt before user verification.
- AWT/OpenGL preview render-thread execution passed EDITOR_PHYSICS_PASS and PHYSICS_AWT_PASS using a temporary preview window. This validates the native preview context and helper lifecycle, not full SceneComposer button interaction or the reported scene.

## 2026-11-22 动画图剪辑简短名称

- 动画图默认状态、添加 Clip State、源剪辑预览以及状态表的剪辑字段和样本选择，按资产文件名显示单剪辑，例 `Idle.j3o` → `Idle`。本地只有一个默认片段时用当前资产名，例如 `Paladin`。多个本地剪辑仍保留各自名称，多个外部剪辑才附加其剪辑名。已有图中由剪辑 ID 生成的旧状态标题只在显示时缩短。用户改过的状态名、转场、入口和绑定 ID 不迁移，无剪辑资源不生成虚假动作。
- Windows JDK25 定向编译与 ANIMATION_EDITOR_SWING_SAVE_RUNTIME_PASS 通过，涵盖单本地默认片段、嵌入 clip 按原 ID 绑定、Idle 外部状态短名及旧状态显示。未构建 SDK 或修改用户的 Paladin/Idle 资产。配套基线 SDK 7b4f4b1b / 引擎 44031b02e 加当前工作树，无引擎实现改动。

## 2026-11-22 Conversion progress, DDS menus and folder deletion

- Texture import now displays an immediate SDK progress activity with the current mip and dimensions. Java Squish has no within-mip callback, so this is not a fabricated percentage. MP3/MP4 audio-to-OGG conversion reports decoded-time percentage when duration is known, otherwise an activity indicator, then output verification. Compressed Texture Editor shows an activity bar for worker operations. Handles/bars finish on errors and completion; existing option-dialog cancellation remains unchanged. MP4 still imports audio only.
- Added Convert texture to DDS to ordinary PNG/JPEG/BMP/GIF context menus in Asset Browser and project AssetNode, reusing existing dimensions/mips/retention/staged-publication semantics and refusing output collisions. Existing audio conversion and legacy .ogg repair actions are retained.
- Asset Browser deletes a managed folder through GeneratedTextures once, before DataFolder can delete individual children. This validates generated-output-directory protection before deleting any child. Source folders can remove their source/DDS pairs and registry entries together. Directory enumeration closes before unlink; filesystem failures retry up to five times (750 ms total delay per path), then report the exact failure. Permanent failures may still leave a partial tree and retained records for retry. Delete errors no longer say Could not open asset. This protection is based on source/output ownership, not a complete J3O/J3M reference index; deleting J3O/J3M does not itself remove mapping records.
- SDK log identified the user's FBX Import failed after successful J3O publication as DirectoryNotEmptyException while cleaning E:/MyWorks/JmonkeyProjects/TestGame/.jme-import-5492365292293660103/Models. Private staging cleanup now uses the bounded deletion routine. A remaining cleanup failure logs a distinct warning and states that published assets were imported, rather than claiming conversion failed. The exact FBX was not reimported in this change; model/texture import and cleanup regressions passed.
- User authorized deletion of E:/MyWorks/JmonkeyProjects/TestGame/assets/Models/Paladin/Attach1.textures. Backed up its existing files and the registry in build/delete-folder-check. An independent-process DataObject deletion succeeded, so the original interactive error was not reproduced there. Restored only that folder from backup, then verified current ownership-aware deletion: dds-only deletion rejected with all files intact; entire Attach1.textures deleted successfully; unrelated registry entries exactly preserved (ACTUAL_ATTACH1_DELETE_PASS). Folder is now absent by request; backup remains. No other user asset was intentionally changed.
- JDK 25 targeted compilation; JNI_AUDIO_IMPORT_PASS (MP3/MP4, rates, repair and failure cases); CONVERSION_PROGRESS_MENU_PASS (Swing busy/end state, PNG menu, OGG repair retained); GENERATED_TEXTURE_DELETION_PASS (protection before mutation, temporary Windows reader lock, source mapping cleanup, staging root); EXTERNAL_ASSET_IMPORT_PASS (DDS/J3O publication, no leftover staging, retention, cancel, collision, disk watcher) passed. Exact three candidate JARs passed menu/deletion/import regressions.
- Updated local build/cluster/modules Core, Texture Tools and Asset Browser JARs; installed hashes match tested candidates. Backups and logs are build/conversion-progress-check. Restart SDK to load; no full SDK packaging or push. Compatible repository bases at completion: SDK 7b4f4b1b / engine 44031b02e plus working-tree changes. Full interactive drag/drop and permanently locked production files remain outside this validation.

## 2026-11-22 Animation graph ports stay attached when nodes move

- Fixed edgeRoute recomputing source/destination sides from horizontal separation on each paint. Each transition row now retains its source-side flag (the target is the opposite side); port dragging stores the actual chosen side. Node movement changes only routing. Reciprocal stacked routes use distinct lanes/labels and side offsets, with left-edge clearance bounded for positive node positions.
- The editor-only jme.animationGraph.ports.v1 String persists row-aligned L/R choices alongside the graph on its composer owner. History snapshots include the hidden model column; save conflict detection and failed-save rollback include the new data. Transition From/To and runtime graph format are unchanged. Old files infer sides once from loaded node centers; original port choices cannot be reconstructed if an older file already lost that information. Save J3O retains the inferred/edited sides thereafter.
- JDK 25 targeted compile and AnimationEditorSmoke passed: bidirectional port dragging, source/target preservation through stacked and reversed layouts, separate route labels, undo/redo, and J3O reopening with a saved left port overriding current layout. Rendered stacked graph inspected in build/animation-port-check/stacked.png. Packaged Core candidate passed ANIMATION_EDITOR_SWING_SAVE_RUNTIME_PASS, then installed into build/cluster/modules/com-jme3-gde-core.jar with identical SHA-256. Backup core-before.jar and logs remain in build/animation-port-check. Restart SDK to load.
- SDK 7b4f4b1b / engine 44031b02e plus working-tree changes. No engine/runtime transition changes, user model writes, full SDK packaging or push. Graph canvas/Swing and persistence verified; no new 3D preview behavior introduced.
