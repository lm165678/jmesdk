#include <jni.h>
#include "astcenc.h"
#include "compressonator.h"
#include <cstdint>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
using Bytes = std::vector<uint8_t>;

CMP_FORMAT bcFormat(int codec) {
    switch (codec) {
        case 1: return CMP_FORMAT_BC1;
        case 2: return CMP_FORMAT_BC2;
        case 3: return CMP_FORMAT_BC3;
        case 4: return CMP_FORMAT_BC4;
        case 5: return CMP_FORMAT_BC5;
        case 7: return CMP_FORMAT_BC7;
        default: throw std::invalid_argument("Unknown BC format");
    }
}

CMP_Texture texture(int width, int height, CMP_FORMAT format, Bytes& data) {
    CMP_Texture result{};
    result.dwSize = sizeof(result);
    result.dwWidth = width;
    result.dwHeight = height;
    result.dwPitch = format == CMP_FORMAT_RGBA_8888 ? width * 4 : 0;
    result.format = format;
    result.nBlockWidth = 4;
    result.nBlockHeight = 4;
    result.nBlockDepth = 1;
    result.dwDataSize = static_cast<CMP_DWORD>(data.size());
    result.pData = data.data();
    if (CMP_CalculateBufferSize(&result) != result.dwDataSize) {
        throw std::invalid_argument("Incorrect BC buffer size");
    }
    return result;
}

void convertBc(int width, int height, int codec, bool decode, Bytes& input, Bytes& output) {
    CMP_FORMAT compressed = bcFormat(codec);
    auto source = texture(width, height, decode ? compressed : CMP_FORMAT_RGBA_8888, input);
    auto target = texture(width, height, decode ? CMP_FORMAT_RGBA_8888 : compressed, output);
    CMP_CompressOptions options{};
    options.dwSize = sizeof(options);
    options.fquality = codec == 7 ? 0.05f : 1.0f;
    options.dwnumThreads = 1;
    auto result = CMP_ConvertTexture(&source, &target, &options, nullptr);
    if (result != CMP_OK) {
        throw std::runtime_error("Compressonator error " + std::to_string(result));
    }
}

void checkAstc(astcenc_error result) {
    if (result != ASTCENC_SUCCESS) {
        throw std::runtime_error(astcenc_get_error_string(result));
    }
}

void convertAstc(int width, int height, int block, bool srgb, bool decode, Bytes& input, Bytes& output) {
    astcenc_config config{};
    checkAstc(astcenc_config_init(srgb ? ASTCENC_PRF_LDR_SRGB : ASTCENC_PRF_LDR,
            block, block, 1, ASTCENC_PRE_MEDIUM, decode ? ASTCENC_FLG_DECOMPRESS_ONLY : 0, &config));
    astcenc_context* raw = nullptr;
    checkAstc(astcenc_context_alloc(&config, 1, &raw, nullptr));
    std::unique_ptr<astcenc_context, decltype(&astcenc_context_free)> context(raw, astcenc_context_free);
    void* pixels = decode ? output.data() : input.data();
    astcenc_image image{static_cast<unsigned>(width), static_cast<unsigned>(height), 1, ASTCENC_TYPE_U8, &pixels};
    astcenc_swizzle rgba{ASTCENC_SWZ_R, ASTCENC_SWZ_G, ASTCENC_SWZ_B, ASTCENC_SWZ_A};
    if (decode) {
        checkAstc(astcenc_decompress_image(raw, input.data(), input.size(), &image, &rgba, 0));
    } else {
        checkAstc(astcenc_compress_image(raw, &image, &rgba, output.data(), output.size(), 0));
    }
}
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_com_jme3_gde_texturetools_NativeTextureCodec_convert(
        JNIEnv* env, jclass, jbyteArray bytes, jint width, jint height, jint codec,
        jint block, jboolean srgb, jboolean decode) {
    try {
        if (!bytes || width < 1 || height < 1 || width > 32768 || height > 32768) {
            throw std::invalid_argument("Invalid texture dimensions");
        }
        bool astc = codec == 0;
        if ((!astc && block != 4) || (astc && block != 4 && block != 5 && block != 6
                && block != 8 && block != 10 && block != 12)) {
            throw std::invalid_argument("Invalid texture block size");
        }
        int64_t rgbaSize = static_cast<int64_t>(width) * height * 4;
        int64_t blockSize = static_cast<int64_t>((width + block - 1) / block)
                * ((height + block - 1) / block) * (codec == 1 || codec == 4 ? 8 : 16);
        int64_t inputSize = decode ? blockSize : rgbaSize;
        int64_t outputSize = decode ? rgbaSize : blockSize;
        if (rgbaSize > 512LL * 1024 * 1024 || env->GetArrayLength(bytes) != inputSize) {
            throw std::invalid_argument("Incorrect or excessive texture buffer size");
        }
        Bytes input(static_cast<size_t>(inputSize));
        Bytes output(static_cast<size_t>(outputSize));
        env->GetByteArrayRegion(bytes, 0, static_cast<jsize>(inputSize), reinterpret_cast<jbyte*>(input.data()));
        if (env->ExceptionCheck()) {
            return nullptr;
        }
        if (astc) {
            convertAstc(width, height, block, srgb, decode, input, output);
        } else {
            convertBc(width, height, codec, decode, input, output);
        }
        jbyteArray result = env->NewByteArray(static_cast<jsize>(outputSize));
        if (result) {
            env->SetByteArrayRegion(result, 0, static_cast<jsize>(outputSize),
                    reinterpret_cast<const jbyte*>(output.data()));
        }
        return result;
    } catch (const std::exception& error) {
        jclass type = env->FindClass("java/io/IOException");
        if (type) {
            env->ThrowNew(type, error.what());
        }
        return nullptr;
    }
}
