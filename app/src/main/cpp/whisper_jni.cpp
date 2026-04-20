#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <unistd.h>
#include <algorithm>
#include "whisper.h"

#define TAG "wim_whisper_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

struct whisper_context * ctx = nullptr;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_wim_app_LocalWhisperClient_nativeInit(JNIEnv *env, jobject thiz, jstring modelPath) {
    if (ctx != nullptr) {
        whisper_free(ctx);
        ctx = nullptr;
    }

    const char *path = env->GetStringUTFChars(modelPath, 0);
    LOGI("Loading model from: %s", path);

    struct whisper_context_params cparams = whisper_context_default_params();
    ctx = whisper_init_from_file_with_params(path, cparams);

    env->ReleaseStringUTFChars(modelPath, path);

    if (ctx == nullptr) {
        LOGE("Failed to load model");
        return JNI_FALSE;
    }

    LOGI("Model loaded successfully");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_wim_app_LocalWhisperClient_nativeTranscribe(JNIEnv *env, jobject thiz, jfloatArray audioData, jint length, jstring languageCode) {
    if (ctx == nullptr) {
        LOGE("Context is null, call init first");
        return env->NewStringUTF("");
    }

    jfloat *pcm = env->GetFloatArrayElements(audioData, 0);

    const char *lang = env->GetStringUTFChars(languageCode, 0);

    struct whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_progress   = false;
    wparams.print_special    = false;
    wparams.print_realtime   = false;
    wparams.print_timestamps = false;
    wparams.translate        = false;
    wparams.no_context       = true;
    wparams.single_segment   = true;
    wparams.max_tokens       = 0;
    wparams.language         = lang;
    wparams.n_threads        = std::max(1, (int)sysconf(_SC_NPROCESSORS_ONLN) - 1);

    LOGI("Starting transcription with %d samples...", length);
    
    if (whisper_full(ctx, wparams, pcm, length) != 0) {
        LOGE("Failed to process audio");
        env->ReleaseFloatArrayElements(audioData, pcm, 0);
        env->ReleaseStringUTFChars(languageCode, lang);
        return env->NewStringUTF("");
    }

    const int n_segments = whisper_full_n_segments(ctx);
    std::string result = "";
    for (int i = 0; i < n_segments; ++i) {
        const char * text = whisper_full_get_segment_text(ctx, i);
        if (text != nullptr) {
            result += text;
        }
    }

    env->ReleaseFloatArrayElements(audioData, pcm, 0);
    env->ReleaseStringUTFChars(languageCode, lang);
    
    LOGI("Transcription complete: %s", result.c_str());
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_wim_app_LocalWhisperClient_nativeRelease(JNIEnv *env, jobject thiz) {
    if (ctx != nullptr) {
        LOGI("Releasing model context");
        whisper_free(ctx);
        ctx = nullptr;
    }
}