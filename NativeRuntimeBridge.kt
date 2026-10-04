package com.lanie.workspace

class NativeRuntimeBridge {

    companion object {
        // Static initializer loads the compiled C++ shared library (.so)
        init {
            System.loadLibrary("mobile_runtime_native")
        }
    }

    // Returns the active status of the native engine components
    external fun nativeGetEngineStatus(): String

    // Processes audio frames natively using whisper.cpp integration
    external fun nativeProcessAudioBuffer(audioData: FloatArray): String
}
