package com.jme3.gde.core.scene.processors;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mobility;
import com.jme3.scene.VertexBuffer;
import java.util.IdentityHashMap;
import java.util.Map;

/** Render-thread SDK-only mip diagnostics using the actual resident texture.
 * No work is performed by this helper outside the selected debug view.
 * 第一步：Visualize -> Lightmap mip view（或 View -> LightmapMip）。
 * 第二步：绿色 mip0、青色 mip1、蓝色 mip2、紫色 mip3、橙色 mip4、暗红 mip5+；
 * 颜色间混合表示三线性采样。黄色动态物体，红色无驻留贴图，洋红色仅基级，
 * 灰色已有 mip 但当前未启用，橙红色缺 UV2。第三步：移动相机检查层级变化，
 * 关闭 mip 开关对比；回到 Lit 完全恢复原材质和渲染路径，不改保存数据。
 * Requires desktop GLSL400 for hardware textureQueryLod. Unsupported contexts
 * receive a status message and retain their original materials.
 */
final class LightmapMipDebug {
    static final String LEGEND = "Lightmap mip: 0 green, 1 cyan, 2 blue, 3 purple, 4 orange, 5+ dark red; "
            + "yellow=dynamic, red=no resident map, magenta=base-only, gray=mips disabled, orange-red=no UV2. "
            + "Colors query actual texture/sampler LOD; Lit restores normal rendering.";
    private final AssetManager assets;
    private final Map<Geometry, Material> materials = new IdentityHashMap<>();

    LightmapMipDebug(AssetManager assets) {
        this.assets = assets;
    }

    Material material(Geometry geometry) {
        Material material = materials.computeIfAbsent(geometry, ignored ->
                new Material(assets, "com/jme3/gde/core/scene/processors/LightmapMipDebug.j3md"));
        Material original = geometry.getMaterial();
        for (String name : new String[]{"UseInstancing", "NumberOfBones", "BoneMatrices", "MorphWeights",
                "NumberOfMorphTargets", "NumberOfTargetsBuffers"}) {
            var parameter = original.getParam(name);
            if (parameter == null) {
                material.clearParam(name);
            } else {
                material.setParam(name, parameter.getVarType(), parameter.getValue());
            }
        }
        var texture = geometry.getResidentProbeLightmap();
        boolean uv2 = geometry.getMesh().getBuffer(VertexBuffer.Type.TexCoord2) != null;
        material.setBoolean("HasUV2", uv2);
        int state = 0;
        int[] sizes = texture == null ? null : texture.getImage().getMipMapSizes();
        boolean useMip = texture != null && texture.getMinFilter().usesMipMapLevels();
        if (geometry.getMobility() != Mobility.Static) {
            state = 1;
        } else if (texture == null) {
            state = 2;
        } else if (!uv2) {
            state = 5;
        } else if (sizes == null || sizes.length < 2) {
            state = 3;
        } else if (!useMip) {
            state = 4;
        }
        material.setInt("State", state);
        material.setFloat("MaxMip", sizes == null ? 0 : sizes.length - 1);
        material.setBoolean("MipSampling", useMip);
        float inset = 0;
        for (var override : geometry.getWorldMatParamOverrides()) {
            if (override.isEnabled() && "GiLightmapAtlasInset".equals(override.getName())
                    && override.getValue() instanceof Number value) {
                inset = value.floatValue();
            }
        }
        material.setFloat("AtlasInset", inset);
        if (texture == null) {
            material.clearParam("Lightmap");
        } else {
            material.setTexture("Lightmap", texture);
        }
        return material;
    }

    void clear() {
        materials.clear();
    }
}
