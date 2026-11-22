package com.jme3.gde.core.assets;

import com.jme3.asset.AssetKey;
import com.jme3.material.MatParamTexture;
import com.jme3.material.Material;
import com.jme3.material.MaterialDef;
import com.jme3.material.plugins.J3MLoader;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer;
import com.jme3.shader.bufferobject.BufferObject;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import java.io.IOException;
import java.util.IdentityHashMap;

/** Gives the private animation model GPU objects owned by its preview context. */
final class AnimationPreviewResources {
    private final IdentityHashMap<Mesh, Mesh> meshes = new IdentityHashMap<>();
    private final IdentityHashMap<Material, Material> materials = new IdentityHashMap<>();
    private final IdentityHashMap<MaterialDef, MaterialDef> definitions = new IdentityHashMap<>();
    private final IdentityHashMap<Image, Image> images = new IdentityHashMap<>();
    private final IdentityHashMap<Texture, Texture> textures = new IdentityHashMap<>();
    private final IdentityHashMap<BufferObject, BufferObject> buffers = new IdentityHashMap<>();

    void isolate(Spatial model) {
        model.depthFirstTraversal(spatial -> {
            if (spatial instanceof Geometry geometry) {
                if (geometry.getMesh() != null) {
                    geometry.setMesh(meshes.computeIfAbsent(geometry.getMesh(), AnimationPreviewResources::copyMesh));
                }
                if (geometry.getMaterial() != null) {
                    geometry.setMaterial(materials.computeIfAbsent(geometry.getMaterial(), this::copyMaterial));
                }
            }
        });
    }

    private static Mesh copyMesh(Mesh source) {
        Mesh result = source.deepClone();
        if (source.getNumLodLevels() > 0) {
            VertexBuffer[] levels = new VertexBuffer[source.getNumLodLevels()];
            for (int i = 0; i < levels.length; ++i) {
                var buffer = source.getLodLevel(i);
                levels[i] = buffer == source.getBuffer(VertexBuffer.Type.Index)
                        ? result.getBuffer(VertexBuffer.Type.Index) : buffer.clone();
            }
            result.setLodLevels(levels);
        }
        return result;
    }

    private Material copyMaterial(Material source) {
        Material result = new Material(definitions.computeIfAbsent(source.getMaterialDef(), AnimationPreviewResources::copyDefinition));
        result.setName(source.getName());
        result.setTransparent(source.isTransparent());
        result.setReceivesShadows(source.isReceivesShadows());
        result.getAdditionalRenderState().set(source.getAdditionalRenderState());
        for (var parameter : result.getMaterialDef().getMaterialParams()) {
            if (source.getParam(parameter.getName()) == null) {
                result.clearParam(parameter.getName());
            }
        }
        for (var parameter : source.getParams()) {
            Object value = parameter.getValue();
            if (value instanceof Texture texture) {
                value = textures.computeIfAbsent(texture, this::copyTexture);
            } else if (value instanceof BufferObject buffer) {
                value = buffers.computeIfAbsent(buffer, BufferObject::clone);
            }
            result.setParam(parameter.getName(), parameter.getVarType(), value);
        }
        return result;
    }

    private Texture copyTexture(Texture source) {
        Texture result = source.clone();
        if (source.getImage() != null) {
            result.setImage(images.computeIfAbsent(source.getImage(), Image::clone));
        }
        return result;
    }

    private static MaterialDef copyDefinition(MaterialDef source) {
        // MaterialDef holds the TechniqueDef shader cache. Loading through the
        // asset cache here would return the same OpenGL Shader objects.
        var manager = source.getAssetManager();
        String asset = source.getAssetName();
        if (asset != null && manager != null) {
            var info = manager.locateAsset(new AssetKey<MaterialDef>(asset));
            if (info != null) {
                try {
                    return (MaterialDef) new J3MLoader().load(info);
                } catch (IOException error) {
                    throw new IllegalStateException("Cannot load the preview material definition " + asset, error);
                }
            }
        }
        MaterialDef result = new MaterialDef(manager, source.getName());
        result.setAssetName(asset);
        result.setShadingModelId(source.getShadingId());
        if (source.getDeferredLightingShader() != null) {
            result.setDeferredLightingShader(source.getDeferredLightingShader());
        }
        for (var parameter : source.getMaterialParams()) {
            if (parameter instanceof MatParamTexture texture) {
                result.addMaterialParamTexture(texture.getVarType(), texture.getName(), texture.getColorSpace(), (Texture) texture.getValue());
            } else {
                result.addMaterialParam(parameter.getVarType(), parameter.getName(), parameter.getValue());
            }
        }
        for (var name : source.getTechniqueDefsNames()) {
            for (var technique : source.getTechniqueDefs(name)) {
                try {
                    result.addTechniqueDef(technique.clone());
                } catch (CloneNotSupportedException error) {
                    throw new IllegalStateException("Cannot copy the preview technique " + name, error);
                }
            }
        }
        return result;
    }
}
