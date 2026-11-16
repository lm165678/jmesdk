/**
 * Host-side compression and standalone mip inspection for the SDK.
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
 * compression support. Choose RGBA/RGB/R/G/B/A and 25..400 percent zoom.</p>
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
 * Missing/stale variants fall back to source and are rebuilt on reconversion.
 * Registry-owned DDS cannot be copied or deleted separately through SDK file
 * operations. Moving them within the asset root updates the registry; deleting
 * a source cleans up its output. Imported DDS files remain ordinary resources.</p>
 * <pre>
 * 第一步：转换源模型，自动生成 DDS；重复转换校验哈希，不重复生成有效输出。
 * 第二步：移动 DDS 或其目录时保留资源根目录范围，映射随操作更新。
 * 第三步：删除源 PNG/JPG 会清理对应 DDS；不能单独删除/复制生成的 DDS。
 * 第四步：操作系统文件管理器不受 SDK 保护；外部删除输出后重新转换即可恢复。
 * 第五步：验证命令：powershell -File tools/verify-generated-textures.ps1。
 * </pre>
 * <p>Standalone mip editing still uses Save As and does not rewrite the generated
 * recipe. ASTC renderer support and platform game packaging are separate work;
 * the SDK mapping is not automatically enabled in a standalone game. Specialized
 * normal-map/alpha-coverage mip filtering is not yet provided by the BC3 default.</p>
 */
package com.jme3.gde.texturetools;
