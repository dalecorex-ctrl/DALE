#include <jni.h>
#include <string>

extern "C" JNIEXPORT jstring JNICALL
Java_com_lanie_workspace_NativeRuntimeBridge_nativeGetEngineStatus(JNIEnv* env, jobject) {
    std::string status = "mobile_runtime_native: OK";
    return env->NewStringUTF(status.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_lanie_workspace_NativeRuntimeBridge_nativeProcessAudioBuffer(JNIEnv* env, jobject, jfloatArray audioData) {
    jsize length = env->GetArrayLength(audioData);
    std::string result = "Processed " + std::to_string(length) + " audio samples";
    return env->NewStringUTF(result.c_str());
}
