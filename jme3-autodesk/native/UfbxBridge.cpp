#include <jni.h>
#include "ufbx.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>
#include <stdexcept>
#include <vector>

namespace {

struct Packet {
    std::vector<jbyte> bytes;

    void raw(const void *data, size_t length) {
        if (length > 512u * 1024u * 1024u - bytes.size()) {
            throw std::runtime_error("FBX exceeds the JNI scene transfer limit");
        }
        if (length != 0) {
            const auto *begin = static_cast<const jbyte *>(data);
            bytes.insert(bytes.end(), begin, begin + length);
        }
    }

    void integer(uint32_t value) {
        const uint8_t data[] = {
            uint8_t(value >> 24), uint8_t(value >> 16), uint8_t(value >> 8), uint8_t(value)
        };
        raw(data, sizeof(data));
    }

    void number(double value) {
        float f = static_cast<float>(value);
        if (!std::isfinite(f)) {
            throw std::runtime_error("FBX contains a non-finite value");
        }
        uint32_t bits;
        std::memcpy(&bits, &f, sizeof(bits));
        integer(bits);
    }

    void string(ufbx_string value) {
        integer(static_cast<uint32_t>(value.length));
        raw(value.data, value.length);
    }

    void vector(ufbx_vec3 value) {
        number(value.x);
        number(value.y);
        number(value.z);
    }

    void transform(const ufbx_transform &value) {
        vector(value.translation);
        number(value.rotation.x);
        number(value.rotation.y);
        number(value.rotation.z);
        number(value.rotation.w);
        vector(value.scale);
    }

    void matrix(const ufbx_matrix &value) {
        for (const auto &column : value.cols) {
            vector(column);
        }
    }
};

void textureRef(Packet &out, const ufbx_material_map &map) {
    auto *texture = map.texture;
    out.integer(texture && texture->has_file && map.texture_enabled ? texture->file_index : UINT32_MAX);
}

void skinVertex(Packet &out, const ufbx_skin_deformer &skin, const ufbx_mesh &mesh, uint32_t index) {
    const auto &vertex = skin.vertices.data[mesh.vertex_indices.data[index]];
    size_t count = std::min<size_t>(vertex.num_weights, 4);
    double total = 0;
    for (size_t i = 0; i < count; ++i) {
        total += skin.weights.data[vertex.weight_begin + i].weight;
    }
    for (size_t i = 0; i < 4; ++i) {
        const auto *weight = i < count && total > 0 ? &skin.weights.data[vertex.weight_begin + i] : nullptr;
        // Unweighted vertices follow the mesh node.
        out.integer(weight ? weight->cluster_index : skin.clusters.count);
        out.number(weight ? weight->weight / total : (total <= 0 && i == 0 ? 1.0 : 0.0));
    }
}

void meshPacket(Packet &out, const ufbx_node &node) {
    const auto &mesh = *node.mesh;
    if (mesh.skin_deformers.count > 1) {
        throw std::runtime_error("FBX meshes with multiple skin deformers are unsupported");
    }
    const auto *skin = mesh.skin_deformers.count ? mesh.skin_deformers.data[0] : nullptr;
    if (skin && skin->skinning_method != UFBX_SKINNING_METHOD_LINEAR) {
        throw std::runtime_error("FBX mesh requires a non-linear skinning method");
    }
    out.integer(node.typed_id);
    out.integer(skin ? skin->clusters.count + 1 : 0);
    if (skin) {
        for (const auto *cluster : skin->clusters) {
            out.integer(cluster->bone_node->typed_id);
            out.matrix(cluster->geometry_to_bone);
        }
        out.integer(node.typed_id);
        out.matrix(node.geometry_to_node);
    }
    out.integer(mesh.material_parts.count);
    std::vector<uint32_t> indices(mesh.max_face_triangles * 3);
    for (const auto &part : mesh.material_parts) {
        const auto *material = part.index < node.materials.count ? node.materials.data[part.index] : nullptr;
        out.integer(material ? material->typed_id : UINT32_MAX);
        out.integer(part.num_triangles * 3);
        for (auto faceIndex : part.face_indices) {
            auto face = mesh.faces.data[faceIndex];
            if (face.num_indices < 3) {
                continue;
            }
            size_t count = ufbx_triangulate_face(indices.data(), indices.size(), &mesh, face) * 3;
            if (count != (face.num_indices - 2) * 3) {
                throw std::runtime_error("FBX polygon could not be triangulated");
            }
            for (size_t i = 0; i < count; ++i) {
                auto index = indices[i];
                out.vector(ufbx_get_vertex_vec3(&mesh.vertex_position, index));
                out.vector(ufbx_get_vertex_vec3(&mesh.vertex_normal, index));
                auto uv = mesh.vertex_uv.exists ? ufbx_get_vertex_vec2(&mesh.vertex_uv, index) : ufbx_vec2{};
                out.number(uv.x);
                out.number(uv.y);
                if (skin) {
                    skinVertex(out, *skin, mesh, index);
                }
            }
        }
    }
}

void scenePacket(Packet &out, const ufbx_scene &scene) {
    out.integer(0x55464231);
    out.integer(scene.nodes.count);
    for (auto *node : scene.nodes) {
        out.string(node->name);
        out.integer(node->parent ? node->parent->typed_id : UINT32_MAX);
        out.transform(node->local_transform);
    }
    out.integer(scene.texture_files.count);
    for (const auto &file : scene.texture_files) {
        out.string(file.relative_filename.length ? file.relative_filename : file.filename);
        out.integer(file.content.size);
        out.raw(file.content.data, file.content.size);
    }
    out.integer(scene.materials.count);
    for (const auto *material : scene.materials) {
        const auto &pbr = material->pbr;
        const auto &color = pbr.base_color;
        out.string(material->name);
        out.number(color.has_value ? color.value_vec3.x : 1);
        out.number(color.has_value ? color.value_vec3.y : 1);
        out.number(color.has_value ? color.value_vec3.z : 1);
        out.number(pbr.opacity.has_value ? pbr.opacity.value_real : 1);
        out.number(pbr.metalness.has_value ? pbr.metalness.value_real : 0);
        out.number(pbr.roughness.has_value ? pbr.roughness.value_real : 1);
        textureRef(out, color);
        textureRef(out, pbr.normal_map);
        textureRef(out, pbr.emission_color);
    }
    size_t count = 0;
    for (const auto *node : scene.nodes) {
        if (node->mesh) {
            ++count;
        }
    }
    out.integer(count);
    for (const auto *node : scene.nodes) {
        if (node->mesh) {
            meshPacket(out, *node);
        }
    }
    out.integer(scene.anim_stacks.count);
    for (const auto *stack : scene.anim_stacks) {
        double duration = std::max(0.0, stack->time_end - stack->time_begin);
        double intervals = std::ceil(duration * 60);
        if (!std::isfinite(intervals) || intervals > 216000) {
            throw std::runtime_error("FBX animation exceeds the one-hour clip limit");
        }
        auto frames = static_cast<uint32_t>(intervals) + 1;
        out.string(stack->name);
        out.integer(frames);
        out.number(duration);
        for (const auto *node : scene.nodes) {
            for (uint32_t i = 0; i < frames; ++i) {
                double time = frames > 1 ? duration * i / (frames - 1) : 0.0;
                out.transform(ufbx_evaluate_transform(stack->anim, node, stack->time_begin + time));
            }
        }
    }
}

} // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_jme3_gde_autodesk_UfbxLoader_readScene(JNIEnv *env, jclass, jbyteArray source) {
    try {
        auto length = env->GetArrayLength(source);
        std::vector<jbyte> input(length);
        env->GetByteArrayRegion(source, 0, length, input.data());
        if (env->ExceptionCheck()) {
            return nullptr;
        }
        ufbx_load_opts options{};
        options.file_format = UFBX_FILE_FORMAT_FBX;
        options.target_axes = ufbx_axes_right_handed_y_up;
        options.target_unit_meters = 1.0;
        // Keep bind geometry and animated transforms in the same units so
        // static bounds and collision sizing agree with the skinned model.
        options.space_conversion = UFBX_SPACE_CONVERSION_MODIFY_GEOMETRY;
        options.geometry_transform_handling = UFBX_GEOMETRY_TRANSFORM_HANDLING_HELPER_NODES;
        options.inherit_mode_handling = UFBX_INHERIT_MODE_HANDLING_HELPER_NODES;
        options.generate_missing_normals = true;
        options.node_depth_limit = 256;
        options.temp_allocator.memory_limit = 768u * 1024u * 1024u;
        options.result_allocator.memory_limit = 768u * 1024u * 1024u;
        ufbx_error error{};
        std::unique_ptr<ufbx_scene, decltype(&ufbx_free_scene)> scene(
            ufbx_load_memory(input.data(), input.size(), &options, &error), &ufbx_free_scene);
        if (!scene) {
            char message[512];
            ufbx_format_error(message, sizeof(message), &error);
            throw std::runtime_error(message);
        }
        Packet packet;
        scenePacket(packet, *scene);
        auto size = static_cast<jsize>(packet.bytes.size());
        jbyteArray result = env->NewByteArray(size);
        if (result != nullptr) {
            env->SetByteArrayRegion(result, 0, size, packet.bytes.data());
        }
        return result;
    } catch (const std::exception &error) {
        jclass type = env->FindClass("java/io/IOException");
        if (type != nullptr) {
            env->ThrowNew(type, error.what());
        }
        return nullptr;
    }
}
