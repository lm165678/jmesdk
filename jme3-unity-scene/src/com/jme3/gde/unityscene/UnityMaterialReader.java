package com.jme3.gde.unityscene;

import com.jme3.asset.AssetManager;
import com.jme3.asset.TextureKey;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.texture.Texture;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.openide.filesystems.FileObject;

/** Converts common Unity Standard/URP Lit, legacy lit and Unlit materials. */
final class UnityMaterialReader {
    private static final Pattern SHADER_NAME = Pattern.compile(
            "(?m)^\\s*Shader\\s+\"([^\"]+)\"");
    private final AssetManager assets;
    private final UnityAssetIndex index;
    private final Consumer<String> warnings;

    UnityMaterialReader(AssetManager assets, UnityAssetIndex index, Consumer<String> warnings) {
        this.assets = assets;
        this.index = index;
        this.warnings = warnings;
    }

    Material read(UnityYaml.Ref reference, Material fallback) {
        if (reference == null || reference.isZero()) {
            return fallback;
        }
        FileObject file = index.find(reference);
        if (file == null) {
            warnings.accept("Material GUID is missing from the project: " + reference.guid());
            return fallback;
        }
        String path = index.path(file);
        try {
            if ("j3m".equalsIgnoreCase(file.getExt())) {
                return assets.loadMaterial(path);
            }
            List<UnityYaml.Document> documents = UnityYaml.parse(file.asText("UTF-8"));
            for (UnityYaml.Document document : documents) {
                if (document.classId() == 21) {
                    return convert(document.values(), file.getNameExt());
                }
            }
            warnings.accept("No Unity Material document in " + path);
        } catch (IOException | RuntimeException failure) {
            warnings.accept("Cannot read material " + path + ": " + failure.getMessage());
        }
        return fallback;
    }

    private Material convert(Map<String, Object> source, String fileName) {
        Map<String, Object> saved = UnityYaml.map(source.get("m_SavedProperties"));
        Map<String, Map<String, Object>> textures = new HashMap<>();
        for (Map.Entry<String, Object> entry : UnityYaml.properties(saved.get("m_TexEnvs"))) {
            textures.put(entry.getKey(), UnityYaml.map(entry.getValue()));
        }
        Map<String, Float> floats = new HashMap<>();
        for (Map.Entry<String, Object> entry : UnityYaml.properties(saved.get("m_Floats"))) {
            floats.put(entry.getKey(), UnityYaml.floatValue(entry.getValue(), 0));
        }
        Map<String, ColorRGBA> colors = new HashMap<>();
        for (Map.Entry<String, Object> entry : UnityYaml.properties(saved.get("m_Colors"))) {
            colors.put(entry.getKey(), UnityYaml.color(entry.getValue(), ColorRGBA.White));
        }
        String shaderName = shaderName(UnityYaml.ref(source.get("m_Shader")));
        long shaderId = UnityYaml.ref(source.get("m_Shader")).fileId();
        Material result;
        String shaderGuid = UnityYaml.ref(source.get("m_Shader")).guid();
        boolean unlitShader = shaderId >= 10750 && shaderId <= 10752
                || "650dd952a5a5f1a4f9b4a5a5b6e2a1e4".equals(shaderGuid)
                || (shaderName != null && (shaderName.startsWith("Unlit/")
                || shaderName.endsWith("/Unlit") || "Unlit".equals(shaderName)));
        if (unlitShader) {
            result = unlit(textures, colors, fileName);
        } else if (shaderId == 46 || floats.containsKey("_Metallic")
                || floats.containsKey("_Glossiness") || floats.containsKey("_Smoothness")
                || textures.containsKey("_BaseMap") || colors.containsKey("_BaseColor")) {
            result = pbr(textures, floats, colors, fileName);
        } else {
            result = lighting(textures, floats, colors, fileName);
        }
        result.setName(UnityYaml.stringValue(source.get("m_Name"), fileName));
        applySurfaceState(result, floats);
        return result;
    }

    private Material unlit(Map<String, Map<String, Object>> textures,
            Map<String, ColorRGBA> colors, String fileName) {
        Material result = new Material(assets, "Common/MatDefs/Misc/Unshaded.j3md");
        result.setColor("Color", colors.getOrDefault("_BaseColor",
                colors.getOrDefault("_Color", ColorRGBA.White)));
        setTexture(result, "ColorMap", textures, fileName, "_MainTex", "_BaseMap");
        return result;
    }

    private Material pbr(Map<String, Map<String, Object>> textures,
            Map<String, Float> floats, Map<String, ColorRGBA> colors, String fileName) {
        Material result = new Material(assets, "Common/MatDefs/Light/PBRLighting.j3md");
        ColorRGBA base = colors.getOrDefault("_BaseColor",
                colors.getOrDefault("_Color", ColorRGBA.White));
        result.setColor("BaseColor", base);
        result.setFloat("Metallic", floats.getOrDefault("_Metallic", 0f));
        float smoothness = floats.containsKey("_Smoothness")
                ? floats.get("_Smoothness") : floats.getOrDefault("_Glossiness", 0.5f);
        result.setFloat("Roughness", FastMath.clamp(1f - smoothness, 0f, 1f));
        setTexture(result, "BaseColorMap", textures, fileName,
                "_BaseMap", "_BaseColorMap", "_MainTex");
        setTexture(result, "NormalMap", textures, fileName, "_BumpMap");
        if (result.getTextureParam("NormalMap") != null) {
            result.setFloat("NormalType", 1f);
            result.setFloat("NormalScale", floats.getOrDefault("_BumpScale", 1f));
        }
        setTexture(result, "EmissiveMap", textures, fileName, "_EmissionMap");
        ColorRGBA emission = colors.getOrDefault("_EmissionColor", ColorRGBA.Black);
        if (max(emission) > 0) {
            result.setColor("Emissive", emission);
            result.setFloat("EmissivePower", 1f);
            result.setFloat("EmissiveIntensity", 1f);
        }
        setTexture(result, "LightMap", textures, fileName, "_OcclusionMap");
        if (result.getTextureParam("LightMap") != null) {
            result.setBoolean("LightMapAsAOMap", true);
            result.setFloat("AoStrength", floats.getOrDefault("_OcclusionStrength", 1f));
        }
        if (textures.containsKey("_MetallicGlossMap") || textures.containsKey("_SpecGlossMap")) {
            warnings.accept(fileName + ": metallic/specular-smoothness packed maps are not "
                    + "converted; scalar Metallic/Roughness values are used instead");
        }
        return result;
    }

    private Material lighting(Map<String, Map<String, Object>> textures,
            Map<String, Float> floats, Map<String, ColorRGBA> colors, String fileName) {
        Material result = new Material(assets, "Common/MatDefs/Light/Lighting.j3md");
        result.setBoolean("UseMaterialColors", true);
        ColorRGBA diffuse = colors.getOrDefault("_Color", ColorRGBA.White);
        result.setColor("Diffuse", diffuse);
        result.setColor("Ambient", diffuse.clone());
        result.setColor("Specular", colors.getOrDefault("_SpecColor", ColorRGBA.White));
        result.setFloat("Shininess", 1f + 127f * floats.getOrDefault("_Glossiness", 0.5f));
        setTexture(result, "DiffuseMap", textures, fileName, "_MainTex");
        setTexture(result, "NormalMap", textures, fileName, "_BumpMap");
        if (result.getTextureParam("NormalMap") != null) {
            result.setFloat("NormalType", 1f);
        }
        ColorRGBA emission = colors.getOrDefault("_EmissionColor", ColorRGBA.Black);
        if (max(emission) > 0) {
            result.setColor("GlowColor", emission);
        }
        setTexture(result, "GlowMap", textures, fileName, "_EmissionMap");
        return result;
    }

    private void applySurfaceState(Material result, Map<String, Float> floats) {
        float unityMode = floats.getOrDefault("_Mode", 0f);
        float surface = floats.getOrDefault("_Surface", 0f);
        boolean cutout = unityMode == 1f || floats.getOrDefault("_AlphaClip", 0f) != 0f;
        boolean transparent = unityMode >= 2f || surface != 0f;
        if (cutout) {
            result.setFloat("AlphaDiscardThreshold", floats.getOrDefault("_Cutoff", 0.5f));
        }
        if (transparent) {
            result.setTransparent(true);
            result.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
            result.getAdditionalRenderState().setDepthWrite(false);
        }
        if (floats.getOrDefault("_Cull", 2f) == 0f) {
            result.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
        }
    }

    private void setTexture(Material result, String parameter,
            Map<String, Map<String, Object>> textures, String fileName, String... names) {
        for (String name : names) {
            Map<String, Object> environment = textures.get(name);
            if (environment == null) {
                continue;
            }
            UnityYaml.Ref reference = UnityYaml.ref(environment.get("m_Texture"));
            if (reference.isZero()) {
                continue;
            }
            FileObject file = index.find(reference);
            if (file == null) {
                warnings.accept(fileName + ": texture GUID is missing: " + reference.guid());
                return;
            }
            try {
                TextureKey key = new TextureKey(index.path(file), true);
                key.setGenerateMips(true);
                Texture texture = assets.loadTexture(key);
                result.setTexture(parameter, texture);
                if (nonDefaultTransform(environment)) {
                    warnings.accept(fileName + ": texture scale/offset on " + name
                            + " is not represented by JME Texture and was ignored");
                }
            } catch (RuntimeException failure) {
                warnings.accept(fileName + ": cannot load texture " + index.path(file)
                        + ": " + failure.getMessage());
            }
            return;
        }
    }

    private boolean nonDefaultTransform(Map<String, Object> environment) {
        Map<String, Object> scale = UnityYaml.map(environment.get("m_Scale"));
        Map<String, Object> offset = UnityYaml.map(environment.get("m_Offset"));
        return UnityYaml.floatValue(scale.get("x"), 1) != 1f
                || UnityYaml.floatValue(scale.get("y"), 1) != 1f
                || UnityYaml.floatValue(offset.get("x"), 0) != 0f
                || UnityYaml.floatValue(offset.get("y"), 0) != 0f;
    }

    private String shaderName(UnityYaml.Ref reference) {
        FileObject shader = index.find(reference);
        if (shader == null || !"shader".equalsIgnoreCase(shader.getExt())) {
            return null;
        }
        try {
            Matcher matcher = SHADER_NAME.matcher(shader.asText("UTF-8"));
            return matcher.find() ? matcher.group(1) : null;
        } catch (IOException failure) {
            return null;
        }
    }

    private float max(ColorRGBA color) {
        return Math.max(color.r, Math.max(color.g, color.b));
    }
}
