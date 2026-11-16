package com.jme3.gde.core.assets;

import com.jme3.anim.AnimComposer;
import com.jme3.anim.SkinningControl;
import com.jme3.animation.AnimControl;
import com.jme3.animation.SkeletonControl;
import com.jme3.material.Material;
import com.jme3.scene.Geometry;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer.Type;

/**
 * Read-only inspection of the imported model, including descendant controls and
 * meshes. Animation and skinning are separate facts: node/morph animation need
 * not use skinning, and a skinned mesh need not contain animation clips.
 * No animation/control/material data is changed. Inspect a stable model on the
 * conversion worker before displaying its options on EDT.
 * <pre>{@code
 * // 第一步：后台加载 GLTF/GLB，按真实导入结果识别动画与蒙皮，不猜测文件名。
 * Spatial model = source.loadAsset();
 * ModelConversionAnalysis analysis = ModelConversionAnalysis.inspect(model);
 * // 第二步：EDT 调用 ModelConversionOptions.ask(analysis, source.getName())。
 * // 有动画默认选动画编辑器；骨骼/动画/形变默认不烘焙 SDF 与静态表面属性。
 * // 第三步：用户确认后后台转换；取消动画模型选项不删除骨骼或动画片段。
 * }</pre>
 */
@SuppressWarnings("deprecation")
public record ModelConversionAnalysis(boolean animated, boolean skinned, boolean morphed,
        String surfaceUnsupportedReason) {

    public boolean dynamic() {
        return animated || skinned || morphed;
    }

    public boolean surfaceSupported() {
        return surfaceUnsupportedReason == null;
    }

    /** Inspect imported data; null input is an import error, not a static model. */
    public static ModelConversionAnalysis inspect(Spatial model) {
        if (model == null) {
            throw new IllegalArgumentException("Cannot inspect a model that failed to load");
        }
        boolean[] facts = new boolean[3];
        String[] reason = new String[1];
        model.depthFirstTraversal(spatial -> {
            AnimComposer composer = spatial.getControl(AnimComposer.class);
            AnimControl legacy = spatial.getControl(AnimControl.class);
            facts[0] |= composer != null && !composer.getAnimClips().isEmpty();
            facts[0] |= legacy != null && !legacy.getAnimationNames().isEmpty();
            facts[1] |= spatial.getControl(SkinningControl.class) != null
                    || spatial.getControl(SkeletonControl.class) != null;
            if (spatial instanceof Geometry geometry && geometry.getMesh() != null) {
                facts[1] |= geometry.getMesh().getBuffer(Type.BoneIndex) != null
                        || geometry.getMesh().getBuffer(Type.BindPosePosition) != null;
                facts[2] |= geometry.getMesh().getMorphTargets().length > 0;
                if (reason[0] == null) {
                    reason[0] = surfaceReason(geometry);
                }
            }
        });
        if (facts[0] || facts[1] || facts[2]) {
            reason[0] = "Animated, skinned or morphing models require dynamic surface capture, which is not supported yet.";
        }
        return new ModelConversionAnalysis(facts[0], facts[1], facts[2], reason[0]);
    }

    private static String surfaceReason(Geometry geometry) {
        Material material = geometry.getMaterial();
        String name = geometry.getName();
        if (material == null || material.getMaterialDef().getTechniqueDefs("SurfaceCapture") == null) {
            return "Material has no SurfaceCapture technique: " + name;
        }
        if (material.isTransparent()) {
            return "Transparent geometry is not supported by surface capture: " + name;
        }
        for (Spatial spatial = geometry; spatial != null; spatial = spatial.getParent()) {
            if (!spatial.getLocalMatParamOverrides().isEmpty()) {
                return "Material parameter overrides are not supported by surface capture: " + name;
            }
        }
        if (Boolean.TRUE.equals(material.getParamValue("UseInstancing"))
                || positive(material.getParamValue("NumberOfBones"))
                || positive(material.getParamValue("NumberOfMorphTargets"))) {
            return "Instancing, skinning or morphing material is not supported by surface capture: " + name;
        }
        return null;
    }

    private static boolean positive(Object value) {
        return value instanceof Number number && number.intValue() > 0;
    }
}
