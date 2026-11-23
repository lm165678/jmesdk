package com.jme3.gde.core.assets;

import javax.swing.*;
import org.openide.DialogDisplayer;
import org.openide.NotifyDescriptor;
import com.jme3.gde.core.util.EditorMessages;

/**
 * Options for source model conversion. Static models generate supported data
 * by default, including five LODs (counting the original) and mesh or character
 * collision. Turning generation off keeps the normals, LODs and physics controls
 * already supplied with the model. Import-materials false
 * replaces source materials with a neutral Lighting material in the export copy.
 * A project-relative J3O material source overrides the import checkbox. The
 * conversion copies its assigned materials and retains their asset texture keys.
 * SDF defaults to 32 cubed; surface capture defaults to 32 pixels/card. Each
 * has a configurable 64 MiB payload budget (hard ceiling 256 MiB). Surface
 * resolution may halve down to 8 when automatic reduction is enabled (default).
 * This does not cap peak CPU/GPU memory. If even 8 does not fit, conversion fails
 * before capture; disable surface baking to export without it. Reductions are logged.
 * SDF and surface generation are independent. Missing UVs skip tangent generation.
 * Preflight detects clips before showing the dialog, checks Animated model when
 * clips exist, and defaults SDF/surface baking off for animation, skinning or morphs.
 * Actual SkinnedMesh status is read-only; the checkbox controls editor routing only.
 * Unsupported static surface capture is disabled with an explanation. Explicit SDF
 * baking on dynamic data captures only the current/bind pose. The no-preflight API
 * retains AUTO unless the user explicitly changes the Animated model checkbox.
 * SCENE/ANIMATION override opening only, never skinning or mesh conversion. Older
 * J3O files without this preference still open in the scene editor. The animation
 * editor's Double-click selector can change or restore the saved preference.
 * <pre>
 * 第一步：右键源模型选择 Convert to J3O，勾选需要的生成项目。
 * 第二步：设置 LOD 和碰撞形体。勾选 Separate collision bodies 时，按静态几何体分别生成刚体。
 * 默认合并生成。已有的物理控制、地形和骨骼角色沿用原有处理。
 * 第三步：普通保存 J3O 不重复生成；重新转换时可恢复 defaults()。
 * 第四步：导入结果有动画片段时默认勾选 Animated model，并显示实际 SkinnedMesh 状态。
 * 第五步：骨骼、动画或形变模型默认不勾选 Bake SDF 和 Bake surface attributes。
 * 参数示例：new Bake(32, 32, 32, 64, 32, 64, true)。
 * 第一步：设置 SDF 三轴采样数与 MiB 预算；超限在烘焙前拒绝。
 * 第二步：设置表面卡片分辨率与预算，取消自动降低可强制保持所选精度。
 * 第三步：恢复 Bake.defaults() 使用低分辨率；总进程峰值内存高于载荷预算。
 * 第六步：取消 Animated model 恢复场景默认打开，不删除动画或改变 j3o 后缀。
 * 第七步：在材料来源选择现有 J3O，复制其材质并共用纹理，不生成当前模型的 DDS。
 * 重新勾选 Import materials 恢复从源模型导入，或清除来源使用中性材质。
 * </pre>
 */
public record ModelConversionOptions(boolean collision, Collision collisionShape,
        boolean lod, int lodLevels, boolean normals, boolean tangents,
        boolean sdf, boolean surface, boolean materials, AnimationEditorSupport.Preference editor, Bake bake,
        boolean separateCollisionBodies, int desktopStartLevel, int mobileStartLevel, String materialSource, boolean lightmapUv) {
    /** Low-cost defaults; budgets describe output payload, not whole-process memory. */
    public record Bake(int sdfX, int sdfY, int sdfZ, int sdfMiB,
            int surfaceResolution, int surfaceMiB, boolean reduceSurface) {
        public Bake {
            long samples = (long) sdfX * sdfY * sdfZ;
            if (sdfX < 2 || sdfY < 2 || sdfZ < 2 || sdfX > 1024 || sdfY > 1024 || sdfZ > 1024
                    || sdfMiB < 1 || sdfMiB > 256 || samples > 64L * 1024 * 1024
                    || samples * Float.BYTES > sdfMiB * 1024L * 1024
                    || surfaceResolution < 8 || surfaceResolution > 2048
                    || surfaceMiB < 1 || surfaceMiB > 256) {
                throw new IllegalArgumentException("Bake settings exceed limits: SDF axes 2..1024, "
                        + "at most 64M samples and the selected SDF MiB budget; surface 8..2048; budgets 1..256 MiB.");
            }
        }

        public static Bake defaults() {
            return new Bake(32, 32, 32, 64, 32, 64, true);
        }
    }

    /** Existing callers receive the same low-cost bake defaults as the dialog. */
    public ModelConversionOptions(boolean collision, Collision collisionShape, boolean lod,
            int lodLevels, boolean normals, boolean tangents, boolean sdf, boolean surface,
            boolean materials, AnimationEditorSupport.Preference editor) {
        this(collision, collisionShape, lod, lodLevels, normals, tangents, sdf, surface, materials,
                editor, Bake.defaults());
    }

    public enum Collision { MESH, BOX, SPHERE }

    public ModelConversionOptions(boolean collision, Collision collisionShape, boolean lod,
            int lodLevels, boolean normals, boolean tangents, boolean sdf, boolean surface,
            boolean materials, AnimationEditorSupport.Preference editor, Bake bake) {
        this(collision, collisionShape, lod, lodLevels, normals, tangents, sdf, surface, materials, editor, bake, false);
    }

    public ModelConversionOptions(boolean collision, Collision collisionShape, boolean lod,
            int lodLevels, boolean normals, boolean tangents, boolean sdf, boolean surface,
            boolean materials, AnimationEditorSupport.Preference editor, Bake bake, boolean separateCollisionBodies) {
        this(collision, collisionShape, lod, lodLevels, normals, tangents, sdf, surface, materials, editor, bake,
                separateCollisionBodies, 0, 0);
    }

    public ModelConversionOptions(boolean collision, Collision collisionShape, boolean lod,
            int lodLevels, boolean normals, boolean tangents, boolean sdf, boolean surface,
            boolean materials, AnimationEditorSupport.Preference editor, Bake bake, boolean separateCollisionBodies,
            int desktopStartLevel, int mobileStartLevel) {
        this(collision, collisionShape, lod, lodLevels, normals, tangents, sdf, surface, materials,
                editor, bake, separateCollisionBodies, desktopStartLevel, mobileStartLevel, null);
    }

    public ModelConversionOptions(boolean collision, Collision collisionShape, boolean lod,
            int lodLevels, boolean normals, boolean tangents, boolean sdf, boolean surface,
            boolean materials, AnimationEditorSupport.Preference editor, Bake bake, boolean separateCollisionBodies,
            int desktopStartLevel, int mobileStartLevel, String materialSource) {
        this(collision, collisionShape, lod, lodLevels, normals, tangents, sdf, surface, materials,
                editor, bake, separateCollisionBodies, desktopStartLevel, mobileStartLevel, materialSource, false);
    }

    /** Uses animation detection by default, as for other new conversions. */
    public ModelConversionOptions(boolean collision, Collision collisionShape, boolean lod,
            int lodLevels, boolean normals, boolean tangents, boolean sdf, boolean surface, boolean materials) {
        this(collision, collisionShape, lod, lodLevels, normals, tangents, sdf, surface, materials,
                AnimationEditorSupport.Preference.AUTO);
    }

    public ModelConversionOptions {
        if (collisionShape == null || editor == null || bake == null || lodLevels < 1 || lodLevels > 8) {
            throw new IllegalArgumentException("LOD levels must be 1..8 and collision type is required");
        }
        if (desktopStartLevel < 0 || desktopStartLevel >= lodLevels
                || mobileStartLevel < 0 || mobileStartLevel >= lodLevels) {
            throw new IllegalArgumentException(text("LodStartRange"));
        }
    }

    public static ModelConversionOptions defaults() {
        return new ModelConversionOptions(true, Collision.MESH, true, 5, true, true, true, true, true);
    }

    boolean importsSourceMaterials() {
        return materials && materialSource == null;
    }

    /** Shows the SDK-localized dialog on EDT; null means cancel without conversion. */
    public static ModelConversionOptions ask() {
        return ask(null, null);
    }

    /** Show per-model detection and safe defaults on EDT; null means cancel. */
    public static ModelConversionOptions ask(ModelConversionAnalysis analysis, String modelName) {
        return ask(analysis, modelName, null);
    }

    /** Import callers supply an initially unchecked source-retention checkbox on EDT. */
    public static ModelConversionOptions ask(ModelConversionAnalysis analysis, String modelName, JCheckBox retainSource) {
        return ask(analysis, modelName, retainSource, null);
    }

    /** Shows the optional project-local material source picker on the EDT. */
    public static ModelConversionOptions ask(ModelConversionAnalysis analysis, String modelName, JCheckBox retainSource,
            ProjectAssetManager assets) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Show conversion options on EDT");
        }
        JPanel rows = new com.jme3.gde.core.util.InspectorColumns("conversion.options");
        if (retainSource != null) {
            rows.add(new JLabel("Original resources"));
            rows.add(retainSource);
        }
        JCheckBox collision = flag(rows, "Collision");
        JComboBox<String> shape = new JComboBox<>(new String[]{text("Mesh"), text("Box"), text("Sphere")});
        row(rows, "Shape", shape);
        JCheckBox separate = flag(rows, "SeparateCollisionBodies");
        separate.setSelected(false);
        JCheckBox lod = flag(rows, "Lod");
        JSpinner levels = new JSpinner(new SpinnerNumberModel(5, 1, 8, 1));
        row(rows, "Levels", levels);
        JSpinner desktopStart = new JSpinner(new SpinnerNumberModel(0, 0, 7, 1));
        JSpinner mobileStart = new JSpinner(new SpinnerNumberModel(0, 0, 7, 1));
        row(rows, "DesktopStart", desktopStart);
        row(rows, "MobileStart", mobileStart);
        JCheckBox normals = flag(rows, "Normals");
        JCheckBox tangents = flag(rows, "Tangents");
        JCheckBox lightmapUv = flag(rows, "LightmapUv");
        lightmapUv.setSelected(analysis == null || !analysis.skinned());
        JCheckBox sdf = flag(rows, "Sdf");
        JCheckBox surface = flag(rows, "Surface");
        JSpinner sdfX = number(rows, "SDF X", 32, 2, 1024);
        JSpinner sdfY = number(rows, "SDF Y", 32, 2, 1024);
        JSpinner sdfZ = number(rows, "SDF Z", 32, 2, 1024);
        JSpinner sdfBudget = number(rows, "SDF payload budget (MiB)", 64, 1, 256);
        JSpinner surfaceResolution = number(rows, "Surface pixels per card", 32, 8, 2048);
        JSpinner surfaceBudget = number(rows, "Surface payload budget (MiB)", 64, 1, 256);
        JCheckBox reduceSurface = new JCheckBox("Reduce resolution to fit budget", true);
        rows.add(new JLabel("Surface overflow policy"));
        rows.add(reduceSurface);
        JLabel estimate = new JLabel();
        rows.add(new JLabel("SDF estimate"));
        rows.add(estimate);
        Runnable updateEstimate = () -> {
            long bytes = ((Number) sdfX.getValue()).longValue() * ((Number) sdfY.getValue()).longValue()
                    * ((Number) sdfZ.getValue()).longValue() * Float.BYTES;
            estimate.setText(String.format(java.util.Locale.ROOT, "%.2f MiB (peak memory is higher)", bytes / 1048576.0));
            for (JSpinner input : new JSpinner[]{sdfX, sdfY, sdfZ, sdfBudget}) {
                input.setEnabled(sdf.isSelected());
            }
            surfaceResolution.setEnabled(surface.isSelected());
            surfaceBudget.setEnabled(surface.isSelected());
            reduceSurface.setEnabled(surface.isSelected());
        };
        for (JSpinner input : new JSpinner[]{sdfX, sdfY, sdfZ}) {
            input.addChangeListener(event -> updateEstimate.run());
        }
        sdf.addActionListener(event -> updateEstimate.run());
        surface.addActionListener(event -> updateEstimate.run());
        JCheckBox materials = flag(rows, "Materials");
        JTextField materialSource = new JTextField();
        if (assets != null && assets.getAssetFolder() != null) {
            var folder = org.openide.filesystems.FileUtil.toFile(assets.getAssetFolder());
            if (folder != null) {
                materialSourceRow(rows, materials, materialSource, folder.toPath());
            }
        }
        JCheckBox animated = flag(rows, "AnimatedModel");
        animated.setSelected(analysis != null && analysis.animated());
        boolean[] changed = {false};
        animated.addActionListener(event -> changed[0] = true);
        if (analysis != null) {
            row(rows, "SkinnedMesh", new JLabel(analysis.skinned() ? text("Detected") : text("NotDetected")));
            sdf.setSelected(!analysis.dynamic());
            surface.setSelected(!analysis.dynamic() && analysis.surfaceSupported());
            surface.setEnabled(analysis.surfaceSupported());
            if (!analysis.surfaceSupported()) {
                surface.setToolTipText(analysis.surfaceUnsupportedReason());
                JLabel note = new JLabel("<html>" + analysis.surfaceUnsupportedReason()
                        .replace("&", "&amp;").replace("<", "&lt;") + "</html>");
                rows.add(new JLabel(text("SurfaceUnavailable")));
                rows.add(note);
            }
        }
        collision.addActionListener(e -> {
            shape.setEnabled(collision.isSelected());
            separate.setEnabled(collision.isSelected());
        });
        lod.addActionListener(e -> {
            levels.setEnabled(lod.isSelected());
            desktopStart.setEnabled(lod.isSelected());
            mobileStart.setEnabled(lod.isSelected());
        });
        updateEstimate.run();
        JScrollPane optionsScroll = new JScrollPane(rows);
        optionsScroll.setPreferredSize(new java.awt.Dimension(650, 600));
        String title = text("Title") + (modelName == null ? "" : " - " + modelName);
        NotifyDescriptor dialog = new NotifyDescriptor.Confirmation(optionsScroll, title, NotifyDescriptor.OK_CANCEL_OPTION);
        while (DialogDisplayer.getDefault().notify(dialog) == NotifyDescriptor.OK_OPTION) {
            try {
                for (JSpinner input : new JSpinner[]{levels, desktopStart, mobileStart, sdfX, sdfY, sdfZ, sdfBudget,
                        surfaceResolution, surfaceBudget}) {
                    input.commitEdit();
                }
                Bake bake = new Bake(sdf.isSelected() ? value(sdfX) : 32,
                        sdf.isSelected() ? value(sdfY) : 32, sdf.isSelected() ? value(sdfZ) : 32,
                        value(sdfBudget), value(surfaceResolution), value(surfaceBudget), reduceSurface.isSelected());
                return new ModelConversionOptions(collision.isSelected(), Collision.values()[shape.getSelectedIndex()],
                        lod.isSelected(), value(levels), normals.isSelected(), tangents.isSelected(),
                        sdf.isSelected(), surface.isSelected(), materials.isSelected(),
                        analysis == null && !changed[0] ? AnimationEditorSupport.Preference.AUTO
                                : animated.isSelected() ? AnimationEditorSupport.Preference.ANIMATION
                                : AnimationEditorSupport.Preference.SCENE, bake, separate.isSelected(),
                        lod.isSelected() ? value(desktopStart) : 0, lod.isSelected() ? value(mobileStart) : 0,
                        materialSource.getText().isEmpty() ? null : materialSource.getText(), lightmapUv.isSelected());
            } catch (IllegalArgumentException | java.text.ParseException error) {
                DialogDisplayer.getDefault().notify(new NotifyDescriptor.Message(error.getMessage(), NotifyDescriptor.ERROR_MESSAGE));
            }
        }
        return null;
    }

    private static void materialSourceRow(JPanel rows, JCheckBox materials, JTextField source, java.nio.file.Path root) {
        JPanel picker = new JPanel(new java.awt.BorderLayout(4, 0));
        source.setEditable(false);
        picker.add(source, java.awt.BorderLayout.CENTER);
        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0));
        JButton browse = new JButton(text("MaterialSourceBrowse"));
        JButton clear = new JButton(text("MaterialSourceClear"));
        clear.setEnabled(false);
        buttons.add(browse);
        buttons.add(clear);
        picker.add(buttons, java.awt.BorderLayout.EAST);
        row(rows, "MaterialSource", picker);
        Runnable clearSource = () -> {
            source.setText("");
            clear.setEnabled(false);
        };
        clear.addActionListener(event -> clearSource.run());
        materials.addActionListener(event -> {
            if (materials.isSelected()) {
                clearSource.run();
            }
        });
        browse.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser(root.toFile());
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("J3O", "j3o"));
            if (chooser.showOpenDialog(rows) == JFileChooser.APPROVE_OPTION) {
                try {
                    var project = root.toRealPath();
                    var selected = chooser.getSelectedFile().toPath().toRealPath();
                    if (!selected.startsWith(project) || !java.nio.file.Files.isRegularFile(selected)
                            || !selected.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".j3o")) {
                        throw new java.io.IOException(text("InvalidMaterialSource"));
                    }
                    source.setText(project.relativize(selected).toString().replace('\\', '/'));
                    materials.setSelected(false);
                    clear.setEnabled(true);
                } catch (java.io.IOException error) {
                    DialogDisplayer.getDefault().notify(new NotifyDescriptor.Message(error.getMessage(),
                            NotifyDescriptor.ERROR_MESSAGE));
                }
            }
        });
    }

    private static int value(JSpinner spinner) {
        return ((Number) spinner.getValue()).intValue();
    }

    private static JSpinner number(JPanel rows, String label, int initial, int minimum, int maximum) {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(initial, minimum, maximum, 1));
        JLabel name = new JLabel(label);
        name.setLabelFor(spinner);
        rows.add(name);
        rows.add(spinner);
        return spinner;
    }

    private static String text(String key) {
        return EditorMessages.text("Convert" + key);
    }

    private static JCheckBox flag(JPanel rows, String key) {
        JCheckBox checkbox = new JCheckBox();
        checkbox.setSelected(true);
        row(rows, key, checkbox);
        return checkbox;
    }

    private static void row(JPanel rows, String key, JComponent input) {
        JLabel label = new JLabel(text(key));
        label.setToolTipText(text(key + "Tip"));
        input.setToolTipText(label.getToolTipText());
        label.setLabelFor(input);
        rows.add(label);
        rows.add(input);
    }
}
