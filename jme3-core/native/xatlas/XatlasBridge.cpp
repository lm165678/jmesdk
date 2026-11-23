#include <jni.h>
#include <xatlas.h>
#include <cstring>
#include <limits>
#include <memory>
#include <vector>

static void fail(JNIEnv* env, const char* message) {
    jclass type = env->FindClass("java/io/IOException");
    if (type != nullptr) {
        env->ThrowNew(type, message);
    }
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_jme3_gde_core_assets_LightmapUvGenerator_unwrap(
        JNIEnv* env, jclass, jfloatArray xyz, jintArray indices) {
    try {
        const jsize coordinateCount = env->GetArrayLength(xyz);
        const jsize indexCount = env->GetArrayLength(indices);
        if (coordinateCount < 9 || coordinateCount % 3 != 0 || indexCount < 3 || indexCount % 3 != 0) {
            fail(env, "xatlas requires a triangle mesh");
            return nullptr;
        }
        std::vector<jfloat> positions(coordinateCount);
        std::vector<jint> faces(indexCount);
        env->GetFloatArrayRegion(xyz, 0, coordinateCount, positions.data());
        env->GetIntArrayRegion(indices, 0, indexCount, faces.data());
        if (env->ExceptionCheck()) {
            return nullptr;
        }
        xatlas::MeshDecl mesh;
        mesh.vertexCount = coordinateCount / 3;
        mesh.vertexPositionStride = 3 * sizeof(jfloat);
        mesh.vertexPositionData = positions.data();
        mesh.indexCount = indexCount;
        mesh.indexData = faces.data();
        mesh.indexFormat = xatlas::IndexFormat::UInt32;
        std::unique_ptr<xatlas::Atlas, decltype(&xatlas::Destroy)> atlas(xatlas::Create(), xatlas::Destroy);
        if (xatlas::AddMesh(atlas.get(), mesh) != xatlas::AddMeshError::Success) {
            fail(env, "xatlas rejected the mesh");
            return nullptr;
        }
        xatlas::PackOptions packing;
        packing.padding = 8;
        packing.texelsPerUnit = 0.0f;
        xatlas::Generate(atlas.get(), xatlas::ChartOptions(), packing);
        if (atlas->meshes == nullptr || atlas->meshCount != 1) {
            fail(env, "xatlas did not produce an output mesh");
            return nullptr;
        }
        const xatlas::Mesh& output = atlas->meshes[0];
        const uint64_t resultSize = 1ull + 3ull * output.vertexCount + output.indexCount;
        if (atlas->width == 0 || atlas->height == 0 || atlas->atlasCount != 1
                || output.indexCount != static_cast<uint32_t>(indexCount)
                || resultSize > std::numeric_limits<jsize>::max()) {
            fail(env, "xatlas could not generate one complete UV layout");
            return nullptr;
        }
        std::vector<jint> encoded(resultSize);
        encoded[0] = output.vertexCount;
        for (uint32_t i = 0; i < output.vertexCount; ++i) {
            const xatlas::Vertex& vertex = output.vertexArray[i];
            encoded[1 + i * 3] = vertex.xref;
            const float u = vertex.uv[0] / atlas->width;
            const float v = vertex.uv[1] / atlas->height;
            std::memcpy(&encoded[2 + i * 3], &u, sizeof(u));
            std::memcpy(&encoded[3 + i * 3], &v, sizeof(v));
        }
        for (uint32_t i = 0; i < output.indexCount; ++i) {
            encoded[1 + output.vertexCount * 3 + i] = output.indexArray[i];
        }
        jintArray result = env->NewIntArray(static_cast<jsize>(resultSize));
        if (result != nullptr) {
            env->SetIntArrayRegion(result, 0, static_cast<jsize>(resultSize), encoded.data());
        }
        return result;
    } catch (...) {
        fail(env, "xatlas could not allocate a mesh layout");
        return nullptr;
    }
}
