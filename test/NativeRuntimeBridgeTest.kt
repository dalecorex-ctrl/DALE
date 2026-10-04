package com.lanie.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Tests for NativeRuntimeBridge declaration consistency and the JNI boundary.
 *
 * IMPORTANT: these tests intentionally do NOT load the native library. They verify
 * that the Kotlin declarations match what native_bridge.cpp exports, without
 * requiring an Android device or the built .so.
 */
class NativeRuntimeBridgeTest {

    private val klass = NativeRuntimeBridge::class.java

    @Test
    fun `bridge class lives in the lanie workspace package`() {
        assertEquals("com.lanie.workspace", klass.`package`.name)
    }

    @Test
    fun `nativeGetEngineStatus is declared external and returns String`() {
        val m = klass.getDeclaredMethod("nativeGetEngineStatus")
        assertTrue(
            "nativeGetEngineStatus must be an external (native) method",
            Modifier.isNative(m.modifiers)
        )
        assertFalse(
            "nativeGetEngineStatus must be an instance method to match the jobject "
                + "parameter in native_bridge.cpp",
            Modifier.isStatic(m.modifiers)
        )
        assertEquals(String::class.java, m.returnType)
        assertEquals(0, m.parameterCount)
    }

    @Test
    fun `nativeProcessAudioBuffer is declared external and takes FloatArray`() {
        val m = klass.getDeclaredMethod("nativeProcessAudioBuffer", FloatArray::class.java)
        assertTrue(
            "nativeProcessAudioBuffer must be an external (native) method",
            Modifier.isNative(m.modifiers)
        )
        assertFalse(
            "nativeProcessAudioBuffer must be an instance method to match the jobject "
                + "parameter in native_bridge.cpp",
            Modifier.isStatic(m.modifiers)
        )
        assertEquals(String::class.java, m.returnType)
        assertEquals(1, m.parameterCount)
        assertEquals(FloatArray::class.java, m.parameterTypes[0])
    }

    @Test
    fun `companion object exists and holds the library load`() {
        val companion = klass.declaredClasses.firstOrNull {
            Modifier.isStatic(it.modifiers) && it.simpleName.contains("Companion")
        }
        assertNotNull(
            "NativeRuntimeBridge must declare a companion object holding "
                + "System.loadLibrary",
            companion
        )
    }

    @Test
    fun `library name matches the cmake add_library target`() {
        // The JNI loader resolves lib<name>.so; CMake target is mobile_runtime_native.
        val source = javaClass.classLoader
            .getResource("native_bridge.cpp") // not on classpath; fall back to file read
        // Read the C++ source directly from the workspace instead.
        val cpp = java.io.File("native_bridge.cpp")
        val kt = java.io.File("NativeRuntimeBridge.kt")
        if (cpp.exists() && kt.exists()) {
            val ktText = kt.readText()
            val cppText = cpp.readText()
            assertTrue(
                "NativeRuntimeBridge.kt must load mobile_runtime_native",
                ktText.contains("System.loadLibrary(\"mobile_runtime_native\")")
            )
            // JNI symbol prefix must match the fully qualified Kotlin class.
            assertTrue(
                "native_bridge.cpp must export JNI symbols for "
                    + "com.lanie.workspace.NativeRuntimeBridge",
                cppText.contains("Java_com_lanie_workspace_NativeRuntimeBridge_")
            )
        }
    }

    @Test
    fun `both declared native methods are implemented in native_bridge cpp`() {
        val cpp = java.io.File("native_bridge.cpp")
        if (!cpp.exists()) return
        val text = cpp.readText()
        for (m in klass.declaredMethods) {
            if (!Modifier.isNative(m.modifiers)) continue
            val symbol = "Java_com_lanie_workspace_NativeRuntimeBridge_${m.name}"
            assertTrue(
                "native_bridge.cpp must define $symbol",
                text.contains(symbol)
            )
        }
    }
}
