package com.jme3.gde.core.assets;

import javax.swing.*;
import org.openide.DialogDisplayer;
import org.openide.NotifyDescriptor;
import com.jme3.gde.core.util.EditorMessages;

/**
 * Immutable source-model conversion options. Static-model generation/import flags default
 * to true when supported, with five LODs including the original and automatic mesh/character
 * collision. Disabled generation preserves existing source data; it does not
 * strip artist-supplied normals, LODs or physics controls. Import-materials false
 * replaces source materials with a neutral Lighting material in the export copy.
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
 * 第二步：设置含原始层的 LOD 总层数及碰撞形体，确认后后台转换。
 * 第三步：普通保存 J3O 不重复生成；重新转换时可恢复 defaults()。
 * 第四步：导入结果有动画片段时默认勾选 Animated model，并显示实际 SkinnedMesh 状态。
 * 第五步：骨骼、动画或形变模型默认不勾选 Bake SDF 和 Bake surface attributes。
 * 第六步：取消 Animated model 恢复场景默认打开，不删除动画或改变 j3o 后缀。
 * </pre>
 */
public record ModelConversionOptions(boolean collision, Collision collisionShape,
        boolean lod, int lodLevels, boolean normals, boolean tangents,
        boolean sdf, boolean surface, boolean materials, AnimationEditorSupport.Preference editor) {
    public enum Collision { MESH, BOX, SPHERE }

    /** Compatibility constructor: new conversions automatically detect animation clips. */
    public ModelConversionOptions(boolean collision, Collision collisionShape, boolean lod,
            int lodLevels, boolean normals, boolean tangents, boolean sdf, boolean surface, boolean materials) {
        this(collision, collisionShape, lod, lodLevels, normals, tangents, sdf, surface, materials,
                AnimationEditorSupport.Preference.AUTO);
    }

    public ModelConversionOptions {
        if (collisionShape == null || editor == null || lodLevels < 1 || lodLevels > 8) {
            throw new IllegalArgumentException("LOD levels must be 1..8 and collision type is required");
        }
    }

    public static ModelConversionOptions defaults() {
        return new ModelConversionOptions(true, Collision.MESH, true, 5, true, true, true, true, true);
    }

    /** Shows the SDK-localized dialog on EDT; null means cancel without conversion. */
    public static ModelConversionOptions ask() {
        return ask(null, null);
    }

    /** Show per-model detection and safe defaults on EDT; null means cancel. */
    public static ModelConversionOptions ask(ModelConversionAnalysis analysis, String modelName) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Show conversion options on EDT");
        }
        JPanel rows = new com.jme3.gde.core.util.InspectorColumns("conversion.options");
        JCheckBox collision = flag(rows, "Collision");
        JComboBox<String> shape = new JComboBox<>(new String[]{text("Mesh"), text("Box"), text("Sphere")});
        row(rows, "Shape", shape);
        JCheckBox lod = flag(rows, "Lod");
        JSpinner levels = new JSpinner(new SpinnerNumberModel(5, 1, 8, 1));
        row(rows, "Levels", levels);
        JCheckBox normals = flag(rows, "Normals");
        JCheckBox tangents = flag(rows, "Tangents");
        JCheckBox sdf = flag(rows, "Sdf");
        JCheckBox surface = flag(rows, "Surface");
        JCheckBox materials = flag(rows, "Materials");
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
        collision.addActionListener(e -> shape.setEnabled(collision.isSelected()));
        lod.addActionListener(e -> levels.setEnabled(lod.isSelected()));
        rows.setPreferredSize(new java.awt.Dimension(560, 440));
        String title = text("Title") + (modelName == null ? "" : " - " + modelName);
        NotifyDescriptor dialog = new NotifyDescriptor.Confirmation(rows, title, NotifyDescriptor.OK_CANCEL_OPTION);
        if (DialogDisplayer.getDefault().notify(dialog) != NotifyDescriptor.OK_OPTION) {
            return null;
        }
        return new ModelConversionOptions(collision.isSelected(), Collision.values()[shape.getSelectedIndex()],
                lod.isSelected(), ((Number) levels.getValue()).intValue(), normals.isSelected(),
                tangents.isSelected(), sdf.isSelected(), surface.isSelected(), materials.isSelected(),
                analysis == null && !changed[0] ? AnimationEditorSupport.Preference.AUTO
                        : animated.isSelected() ? AnimationEditorSupport.Preference.ANIMATION : AnimationEditorSupport.Preference.SCENE);
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
