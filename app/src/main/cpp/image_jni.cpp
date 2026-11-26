#include <jni.h>
#include <cstdint>
#include <string>
#include <vector>
#include "stable-diffusion.h"

namespace {
void throw_java(JNIEnv* env, const std::string& message) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type != nullptr) env->ThrowNew(type, message.c_str());
}

std::string last_error(const char* fallback) {
    const char* message = sd_get_last_error();
    return message != nullptr && message[0] != '\0' ? message : fallback;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_se_unapp_studio_OfflineImageEngine_nativeCreate(
    JNIEnv* env, jobject, jstring model_path, jint thread_count) {
    if (model_path == nullptr) {
        throw_java(env, "Sökvägen till bildmodellen saknas.");
        return 0;
    }
    const char* path = env->GetStringUTFChars(model_path, nullptr);
    if (path == nullptr) return 0;

    sd_ctx_opt_t* options = sd_ctx_opt_create();
    sd_ctx_t* context = nullptr;
    sd_status_t status = options
        ? sd_opt_set_string(options, "model_path", path)
        : SD_STATUS_OUT_OF_MEMORY;
    env->ReleaseStringUTFChars(model_path, path);

    if (status == SD_STATUS_OK) {
        status = sd_opt_set_int(options, "n_threads", thread_count > 0 ? thread_count : 4);
    }
    if (status == SD_STATUS_OK) status = sd_ctx_create(options, &context);
    sd_opt_free(options);

    if (status != SD_STATUS_OK || context == nullptr) {
        throw_java(env, last_error("Kunde inte skapa bildmotorn."));
        return 0;
    }
    return reinterpret_cast<jlong>(context);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_se_unapp_studio_OfflineImageEngine_nativeGenerate(
    JNIEnv* env, jobject, jlong handle, jstring prompt,
    jint width, jint height, jint steps, jlong seed) {
    auto* context = reinterpret_cast<sd_ctx_t*>(handle);
    if (context == nullptr || prompt == nullptr || width < 64 || height < 64 ||
        steps < 1 || steps > 50) {
        throw_java(env, "Ogiltiga inställningar för bildgenerering.");
        return nullptr;
    }

    const char* prompt_chars = env->GetStringUTFChars(prompt, nullptr);
    if (prompt_chars == nullptr) return nullptr;

    sd_img_gen_opt_t* options = sd_img_gen_opt_create();
    sd_result_t* result = nullptr;
    sd_status_t status = options
        ? sd_opt_set_string(options, "prompt", prompt_chars)
        : SD_STATUS_OUT_OF_MEMORY;
    env->ReleaseStringUTFChars(prompt, prompt_chars);

    if (status == SD_STATUS_OK) status = sd_opt_set_int(options, "width", width);
    if (status == SD_STATUS_OK) status = sd_opt_set_int(options, "height", height);
    if (status == SD_STATUS_OK) status = sd_opt_set_int(options, "sample.steps", steps);
    if (status == SD_STATUS_OK) status = sd_opt_set_int(options, "seed", seed);
    if (status == SD_STATUS_OK) status = sd_generate_image(context, options, &result);
    sd_opt_free(options);

    if (status != SD_STATUS_OK || result == nullptr || sd_result_get_image_count(result) < 1) {
        std::string message = last_error("Bildgenereringen misslyckades.");
        sd_result_free(result);
        throw_java(env, message);
        return nullptr;
    }

    const sd_tensor_t* image = sd_result_get_image(result, 0);
    const int64_t channels = image ? sd_tensor_dim(image, 0) : 0;
    const int64_t image_width = image ? sd_tensor_dim(image, 1) : 0;
    const int64_t image_height = image ? sd_tensor_dim(image, 2) : 0;
    const auto* bytes = image ? static_cast<const uint8_t*>(sd_tensor_data(image)) : nullptr;

    if (sd_tensor_type(image) != SD_TENSOR_U8 || channels < 3 || channels > 4 ||
        image_width != width || image_height != height || bytes == nullptr) {
        sd_result_free(result);
        throw_java(env, "Bildmotorn returnerade ett bildformat som appen inte stöder.");
        return nullptr;
    }

    const jsize pixel_count = static_cast<jsize>(image_width * image_height);
    std::vector<jint> pixels(static_cast<size_t>(pixel_count));
    for (int64_t i = 0; i < image_width * image_height; ++i) {
        const uint8_t* p = bytes + i * channels;
        const uint8_t alpha = channels == 4 ? p[3] : 255;
        pixels[static_cast<size_t>(i)] =
            (static_cast<jint>(alpha) << 24) |
            (static_cast<jint>(p[0]) << 16) |
            (static_cast<jint>(p[1]) << 8) |
            static_cast<jint>(p[2]);
    }

    jintArray output = env->NewIntArray(pixel_count);
    if (output != nullptr) env->SetIntArrayRegion(output, 0, pixel_count, pixels.data());
    sd_result_free(result);
    return output;
}

extern "C" JNIEXPORT void JNICALL
Java_se_unapp_studio_OfflineImageEngine_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    auto* context = reinterpret_cast<sd_ctx_t*>(handle);
    if (context != nullptr) sd_ctx_free(context);
}
