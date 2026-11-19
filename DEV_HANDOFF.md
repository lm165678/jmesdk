## 2026-11-19 GI opt-in and engine performance publication checkpoint
- Publish GiPreview/JmeGiProbeVolume integration for Curved receiver correction, default Off. Requires the paired engine changes; see the existing validation and unavailable TestScene3 limitations below.
- Paired engine also includes exact-declaration FrameGraph compilation reuse, material-watch/sort-ID CPU optimizations and zero-duration animation fixes. Graph declarations remain dynamic each frame. CPU microbenchmarks improve, but three controlled Sponza pairs fluctuate substantially; no stable whole-frame FPS gain claimed. See engine handoff for test failures reproduced on baseline and performance figures.
- No full SDK build/deployment in this publication. Generated ZIPs, exported engine/test-data libraries, extracted templates and local nbm.revision updates are retained locally and excluded from commits. Lemur/dual-GUI/Agent and neutral physics backend plans remain pending, not implemented. Compatible implementation revisions will follow in a documentation commit.
## 2026-11-19 compatible publication revisions
- Engine implementation: fbc68bd0d on dev_fix_lightprobe_issue; SDK implementation: dc5d1ec2 on master. Use this pair for WorldSettings, UE4-style post-processing and preceding sky composition changes. Documentation-only commits follow. Validation limits below remain applicable; no full SDK build for publication.

## 2026-11-19 publication checkpoint
- Publish current WorldSettings, editor lifecycle and UE4-style post-processing source changes together with the existing sky-composition commits. Validation and limitations are recorded below; no full SDK build or deployment for publication.
- SDK also includes binary Unity input diagnostics and conversion load-failure handling. Generated archives, extracted libraries/templates, crash logs and local build revision changes are excluded.
- User IDE startup used Android Studio Java 11 despite Gradle using JDK25; runtime selection needs JDK25. IDEA module tree recovered after Gradle synchronization; no IDE configuration was changed.

## 2026-11-19 GI sky bake mode selector
- BakeGiProbeVolumeAction exposes Separate sky visibility, default unchecked. Off requests total diffuse including sky; on preserves separated bounce/sky-visibility data. Choice is copied at bake start; changing the control alone never relabels existing data. Rebake and save scene/GI files to switch. Source usage and tooltip explain outside/disabled-GI fallback and independent sky reflections.
- Paired engine change defaults GiSceneBaker.Settings.separateSky=false and corrects total-mode bounce composition/sky-pixel intensity. Targeted SDK action javac passed via engine build/sdk-bounces-check.args. Engine native tests cover both formats, all four paths and Lighting/PBR; TestScene3 fixed-view outputs identical. Full NetBeans UI, SDK build/deployment/restart were not run.
- Sky intensity/exposure are not modified by this UI change. Original project scenes untouched; comparison candidates/logs remain under the engine build directory. Distance-field sky occlusion remains pending; this changes composition, not that feature. Preserve concurrent post-processing and WorldSettings work.

## 2026-11-19 UE4-style SDR exposure / Film / CombineLUTs (working tree)
- Compared local UE4.27.2 StarEngine histogram/adaptation and Film/CombineLUTs source. Existing JME behavior was not equivalent. Added AutoExposureFilter.Mode.UE4Histogram with brightness limits (.03..2), configurable log histogram (-8..4), soft adjacent bins, 18% grey and linear/exponential transition (1.5 stops). In this mode Speed Up means brighter scene / lower multiplier; Legacy retains old direction and multiplier EV bounds. Portable 32x32 metering differs from UE compute histogram; no EV100 camera, pre-exposure or metering mask.
- ColorGradingFilter now uses the viewport FrameGraph. Neutral/Filmic retain their prior equations; Curve.UE4 adds AP1/D60 film response with .88/.55/.26/0/.04 controls, glow/red shaping, blue correction, bright saturated gamut expansion, Bradford white balance, grading gamma/offset and tonal gains. CombineLUTs generates a 32^3 log-input atlas only when parameters change; ApplyColorLUT samples it into linear SDR, alpha preserved and framebuffer encodes sRGB once. LUT GPU state is never serialized and is freed on cleanup.
- Optional external N*N by N LUT (16/32/64) via setColorLut: raw Linear texture data whose RGB values represent sRGB display colors; blends during LUT generation. SDK exposes procedural controls, not an external-LUT file picker. Tint is approximate; full per-range saturation/contrast/gamma/offset vectors and HDR/PQ output are absent. This is UE4-style SDR, NOT pixel-identical UE or full ACES output-transform parity.
- SDK Exposure adds UE4 histogram toggle, brightness/log ranges/percentiles/transition; ToneMap adds UE4 Film toggle/curve/white balance/gamut controls; Combine LUTs section adds gamma/offset and tonal gains. All numeric settings round-trip through existing WorldSettings editor options. Old scenes default to old modes; explicitly enable UE4Exposure and UE4Tonemap to opt in. Inapplicable controls are disabled. No full SDK build/deployment/restart or push.
- Validation: targeted JDK25 filter/example and SDK class compilation passed. Native RTX5080 GL4.3 legacy CINEMATIC_POST_PASS; new TestUE4PostProcessing --verify UE4_POST_GPU_PASS for 1x and 4x MSAA, with exposure direction, grey/black/HDR, alpha, LUT insertion/removal, FPP detach/re-attach and serialization. Final 1x adds saturated RGB checks. Logs build/ue4-post-check/{legacy,modern-final,modern-msaa}.log. No side-by-side same-scene UE image comparison or measured performance claim. Initial command processes intermittently crashed 0xC0000005 and were retried.
- Runnable example: jme3test.post.TestUE4PostProcessing, --verify for offscreen numeric checks; -Duepost.samples=4 for MSAA. Non-verify demo varies HDR background luminance. SDK tools/tests/UePostPanelSmoke passed UE4_POST_PANEL_PASS on EDT (independent headless Swing test, -Xint retry); checks legacy/new control enablement and resource lookup, not full NetBeans integration.
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
