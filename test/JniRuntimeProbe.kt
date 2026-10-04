package com.lanie.workspace

/**
 * Runtime probe for the JNI boundary.
 *
 * Loads the real built library and calls both native methods. Exits non-zero on
 * any failure so the result is machine-checkable.
 *
 * This is a placeholder-labelled probe: nativeProcessAudioBuffer returns a
 * synthetic summary string, it does NOT run whisper inference. It verifies the
 * JNI boundary (symbol resolution, marshalling, return path), not ML behaviour.
 */
fun main() {
    val bridge = NativeRuntimeBridge()

    val status = bridge.nativeGetEngineStatus()
    println("nativeGetEngineStatus() -> \"$status\"")
    if (!status.contains("mobile_runtime_native")) {
        System.err.println("FAIL: unexpected engine status")
        kotlin.system.exitProcess(1)
    }

    val audio = FloatArray(1600)
    val processed = bridge.nativeProcessAudioBuffer(audio)
    println("nativeProcessAudioBuffer(FloatArray(1600)) -> \"$processed\"")
    if (!processed.contains("1600")) {
        System.err.println("FAIL: array length was not marshalled correctly")
        kotlin.system.exitProcess(1)
    }

    println("JNI BOUNDARY OK")
}
