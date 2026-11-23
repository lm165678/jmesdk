#include <jni.h>
#include "spirv_cross_c.h"
#include <cstring>
#include <exception>
#include <limits>
#include <memory>
#include <vector>

namespace {

void fail(JNIEnv* env, const char* message) {
    jclass exception = env->FindClass("java/io/IOException");
    if (exception != nullptr) {
        env->ThrowNew(exception, message);
    }
}

bool checked(JNIEnv* env, spvc_context context, spvc_result result) {
    if (result == SPVC_SUCCESS) {
        return true;
    }
    const char* message = spvc_context_get_last_error_string(context);
    fail(env, message != nullptr && *message != '\0' ? message : "SPIRV-Cross conversion failed");
    return false;
}

} // namespace

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_jme3_gde_core_assets_SpirvCross_compile(JNIEnv* env, jclass,
        jintArray input, jint stage, jstring name, jint version, jboolean es) {
    try {
        static_assert(sizeof(jint) == sizeof(SpvId), "SPIR-V word size mismatch");
        const jsize count = env->GetArrayLength(input);
        std::vector<jint> words(count);
        env->GetIntArrayRegion(input, 0, count, words.data());
        if (env->ExceptionCheck()) {
            return nullptr;
        }

        spvc_context raw = nullptr;
        if (spvc_context_create(&raw) != SPVC_SUCCESS) {
            fail(env, "Cannot allocate SPIRV-Cross context");
            return nullptr;
        }
        const std::unique_ptr<spvc_context_s, decltype(&spvc_context_destroy)> context(raw, spvc_context_destroy);
        spvc_parsed_ir ir = nullptr;
        spvc_compiler compiler = nullptr;
        if (!checked(env, raw, spvc_context_parse_spirv(raw,
                    reinterpret_cast<const SpvId*>(words.data()), words.size(), &ir))
                || !checked(env, raw, spvc_context_create_compiler(raw, SPVC_BACKEND_GLSL, ir,
                    SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, &compiler))) {
            return nullptr;
        }
        const char* entry = env->GetStringUTFChars(name, nullptr);
        if (entry == nullptr) {
            return nullptr;
        }
        const auto selected = spvc_compiler_set_entry_point(compiler, entry, static_cast<SpvExecutionModel>(stage));
        env->ReleaseStringUTFChars(name, entry);
        if (!checked(env, raw, selected)) {
            return nullptr;
        }

        spvc_compiler_options options = nullptr;
        spvc_variable_id dummySampler = 0;
        const char* source = nullptr;
        if (!checked(env, raw, spvc_compiler_create_compiler_options(compiler, &options))
                || !checked(env, raw, spvc_compiler_options_set_uint(options, SPVC_COMPILER_OPTION_GLSL_VERSION, version))
                || !checked(env, raw, spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_GLSL_ES,
                    es == JNI_TRUE ? SPVC_TRUE : SPVC_FALSE))
                || !checked(env, raw, spvc_compiler_install_compiler_options(compiler, options))
                || !checked(env, raw, spvc_compiler_build_dummy_sampler_for_combined_images(compiler, &dummySampler))
                || !checked(env, raw, spvc_compiler_build_combined_image_samplers(compiler))
                || !checked(env, raw, spvc_compiler_compile(compiler, &source))) {
            return nullptr;
        }
        const auto length = std::strlen(source);
        if (length > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
            fail(env, "Generated GLSL is too large for Java");
            return nullptr;
        }
        auto output = env->NewByteArray(static_cast<jsize>(length));
        if (output != nullptr) {
            env->SetByteArrayRegion(output, 0, static_cast<jsize>(length), reinterpret_cast<const jbyte*>(source));
        }
        return output;
    } catch (const std::exception& error) {
        fail(env, error.what());
    } catch (...) {
        fail(env, "Unexpected SPIRV-Cross failure");
    }
    return nullptr;
}
