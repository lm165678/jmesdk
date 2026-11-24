/**
 * Host-side compression, mip inspection and pure-Java skin authoring tools for the SDK.
 *
 * <p>Tools / Textures / Pre-integrated Skin Tools contains LUT generation and
 * normal-prefilter tabs. Both share an editable diffusion profile: the published
 * reference fit plus explicitly artistic light/medium/dark starting points.
 * Normal filtering operates on signed vectors, with exact Gaussian and fast
 * approximations, explicit source texels/mm, clamp/wrap boundaries, and an
 * original detail-map copy. Two-normal exports use one smooth normal basis;
 * four-normal exports bind separate R/G/B diffuse maps. Previews offer normal
 * data and lighting, wheel zoom, dragging and Fit. Pigmentation uses material
 * BaseColor/BaseColorMap; normal filtering does not generate curvature or unwrap UVs.</p>
 *
 * <pre>
 * 第一步：在 LUT 页选择参考/美术预设，或编辑高斯曲线与 RGB 距离。
 * 第二步：在法线页选择 PNG/JPEG，设置源 texels/mm 与输出尺寸，点击 Generate。
 * 第三步：选择双法线近似或四法线，导出配套 .j3m、线性 LUT、法线图和参数文件。
 * 第四步：在材质编辑器设置底色。Load profile 可恢复导出的扩散参数。
 * 第五步：用 tools/verify-skin-lut.ps1 -JdkHome &lt;JDK25&gt; 运行独立 CPU/EDT 检查。
 * </pre>
 * <p>This path uses no native texture codec and launches no child process at
 * runtime. Outputs stay below the chosen asset root; publication backs up owned
 * assets, refuses unrelated files and publishes material references last.
 * It does not modify source textures, scene models, DDS mappings or Lightmaps.
 * Consume the matching engine API through the user's normal engine export/SDK
 * build; this tool's verification script does not build or launch the SDK.</p>
 *
 * <p>The editor first looks for an installed JNI codec in
 * {@code release/texture-tools/jni}. This path encodes and decodes on the current
 * worker thread, without opening a child process or extracting DLLs at runtime.
 * Set {@code jme.sdk.texture.backend=jni} to require the installed library or
 * {@code jme.sdk.texture.backend=cli} to use the command-line tools explicitly.
 * Build and stage the Windows x64 library with {@code tools/build-texture-jni.ps1}
 * before building the Texture Tools module.</p>
 *
 * <p>The module bundles Compressonator 4.6.0 for Windows/Linux x86-64 and
 * astcenc 5.7.1 for Windows/Linux x86-64/ARM64 and universal macOS. Original
 * archive layouts and runtime dependencies are retained under
 * {@code release/texture-tools}. Linux Compressonator symlinks are materialized
 * as identical file copies for Windows checkouts. The NetBeans executable list
 * restores Unix execution permissions when installing the module.
 * No encoder is executed on the Android device or included in game assets.</p>
 *
 * <p>Entry point: Tools / Textures / Compressed Texture Editor. Supports
 * single-face 2D BC DDS, square-block ASTC and ASTC KTX1. Other containers and
 * texture types report an error. Raw ASTC and legacy DDS lack explicit color
 * profile metadata; they are displayed as linear/unspecified. Encoding defaults
 * to BC3 and one mip; ASTC uses 6x6 medium-quality blocks and sRGB. Mip counts
 * include the base level. Preview decoding is on the CPU, independent of GPU
 * compression support. Choose RGBA/RGB/R/G/B/A; previews open fitted to the
 * viewport. The wheel zooms, dragging pans, and Fit restores automatic sizing.</p>
 *
 * <pre>
 * 第一步：构建模块，双击 DDS、ASTC 或 ASTC KTX；也可使用 Tools 菜单打开。
 * 第二步：Encode image 选择原图，指定 BC 格式或 ASTC KTX1 及 mip 总层数。
 * 第三步：输出使用 .dds 或 .ktx；每次选择新文件，不覆盖原始资源。
 * 第四步：打开输出，切换 mip、通道及缩放；RGBA 使用棋盘背景预览透明度。
 * 第五步：Replace mip / Save as 用同尺寸源图替换当前层，其他层压缩字节保持不变。
 * 第六步：重新打开原文件恢复；需要重建 mip 时使用 Encode image 从原图重新生成。
 * 第七步：Windows 自动验证命令：powershell -File tools/verify-texture-tools.ps1。
 * </pre>
 *
 * <p>The SDK core now invokes {@link com.jme3.gde.texturetools.GeneratedTextures}
 * during source-model conversion. PNG/JPEG inputs generate full-mip BC3 DDS in
 * their sibling dds directory. The registry at the primary asset root stores
 * source/output SHA-256 and encoder profile. SDK ProjectAssetManager resolves
 * these variants before selecting loaders while retaining logical material keys.
 * Opening a J3O in the SDK repairs missing DDS files when the source is available.
 * Stale variants fall back to source and are rebuilt on reconversion.
 * SDK file operations protect generated directories. Deleting a single generated
 * DDS retains its source mapping. Deleting the source also clears its generated DDS.
 * Imported DDS files remain ordinary resources.</p>
 * <pre>
 * 第一步：转换源模型，自动生成 DDS；重复转换校验哈希，不重复生成有效输出。
 * 第二步：在 SDK 内移动原始贴图，更新映射；按源图路径管理的 DDS 随源图移动。
 * 第三步：删除单张 DDS 后重新打开引用它的 J3O，有原图时会补齐。
 * 第四步：删除源 PNG/JPG 会清理对应 DDS；生成目录禁止单独删除、复制和改名。
 * 第五步：验证命令：powershell -File tools/verify-generated-textures.ps1。
 * </pre>
 * <p>Standalone mip editing still uses Save As and does not rewrite the generated
 * recipe. ASTC renderer support and platform game packaging are separate work;
 * the SDK mapping is not automatically enabled in a standalone game. Specialized
 * normal-map/alpha-coverage mip filtering is not yet provided by the BC3 default.</p>
 */
package com.jme3.gde.texturetools;
