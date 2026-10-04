package com.lanie.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Repository-level consistency tests.
 *
 * These read the workspace sources and assert structural invariants that were
 * violated in the pre-repair state. They are not a substitute for compilation,
 * but they fail loudly if the invariants regress.
 */
class PackageConsistencyTest {

    private val lanieFiles = listOf(
        "Capability.kt",
        "CapabilityRegistry.kt",
        "CapabilityRequest.kt",
        "CapabilityResult.kt",
        "CapabilityRisk.kt",
        "ShellCapability.kt",
        "MemoryManager.kt",
        "TerminalRunner.kt",
        "ScriptValidator.kt",
        "AgentGatewayRouter.kt",
        "NativeRuntimeBridge.kt",
        "PingTest.kt",
        "LlamaCppBridge.kt",
        "main.kt"
    )

    private fun sourcesOnDisk(): List<File> =
        lanieFiles.map { File(it) }.filter { it.exists() }

    @Test
    fun `every lanie source file declares the workspace package`() {
        val files = sourcesOnDisk()
        assertTrue("no LANIE sources found - wrong working directory?", files.isNotEmpty())
        for (f in files) {
            val firstMeaningful = f.readLines()
                .firstOrNull { it.isNotBlank() && !it.startsWith("//") }
            assertEquals(
                "${f.name} must declare package com.lanie.workspace",
                "package com.lanie.workspace",
                firstMeaningful
            )
        }
    }

    @Test
    fun `no lanie source file declares a conflicting package`() {
        for (f in sourcesOnDisk()) {
            val declared = f.readLines().firstOrNull { it.startsWith("package ") }
            assertEquals(
                "${f.name} declares a different package",
                "package com.lanie.workspace",
                declared
            )
        }
    }

    @Test
    fun `core source files are all present`() {
        for (name in lanieFiles) {
            assertTrue("expected source file missing: $name", File(name).exists())
        }
        assertTrue("native_bridge.cpp missing", File("native_bridge.cpp").exists())
        assertTrue("CMakeLists.txt missing", File("CMakeLists.txt").exists())
    }

    @Test
    fun `router uses the registry public API not an invented one`() {
        val text = File("AgentGatewayRouter.kt").readText()
        assertTrue("router must call getCapability", text.contains("getCapability("))
        assertTrue("router must read request.actionId", text.contains("request.actionId"))
        assertTrue(
            "router must construct the real CapabilityResult data class",
            text.contains("CapabilityResult(false, null,")
        )
        assertTrue(
            "router must construct ShellCapability with its required working dir argument",
            text.contains("ShellCapability(workingDirectoryPath)")
        )
        assertTrue("routeRequest must be suspend", text.contains("suspend fun routeRequest"))
        // Reject the pre-repair broken API calls if they ever come back.
        assertTrue("stale registry.get( call returned", !text.contains("registry.get("))
        assertTrue("stale actionName property returned", !text.contains("request.actionName"))
        assertTrue("stale CapabilityResult.Error returned", !text.contains("CapabilityResult.Error"))
    }

    @Test
    fun `native bridge companion object is syntactically valid`() {
        val text = File("NativeRuntimeBridge.kt").readText()
        assertTrue(
            "NativeRuntimeBridge must declare a companion object",
            text.contains("companion object")
        )
        assertTrue("stale companion0 label returned", !text.contains("companion0"))
    }

    @Test
    fun `cmake does not reference files that do not exist`() {
        val rawLines = File("CMakeLists.txt").readLines()
        // Strip CMake line comments so prose (e.g. `#include "ggml.h"`) is not
        // mistaken for a source reference.
        val codeLines = rawLines.map { it.substringBefore(" #").trimEnd() }
        val allText = codeLines.joinToString("\n")

        // Source tokens are only meaningful inside commands that build files:
        // add_library / add_executable / target_sources. Header NAMES in
        // find_path(...) and include dirs are not build sources.
        val buildBlock = StringBuilder()
        var inBuildCmd = false
        for (line in codeLines) {
            if (Regex("\\b(add_library|add_executable|target_sources)\\s*\\(").containsMatchIn(line)) {
                inBuildCmd = true
            }
            if (inBuildCmd) buildBlock.appendLine(line)
            if (inBuildCmd && line.contains(')')) inBuildCmd = false
        }

        // Source tokens with a C/C++ extension, quoted or bare.
        val tokenRegex =
            Regex("\"([^\"]+\\.(?:cpp|cc|c|h))\"|(?<![A-Za-z0-9_./])([A-Za-z0-9_./-]+\\.(?:cpp|cc|c|h))(?![A-Za-z0-9_-])")
        val tokens = mutableListOf<String>()
        tokenRegex.findAll(buildBlock.toString()).forEach { m ->
            val q = m.groupValues[1]
            val b = m.groupValues[2]
            tokens += if (q.isNotEmpty()) q else b
        }

        // 1. Glob patterns (declared in file(GLOB ...)) may reference absent
        //    directories only inside if(EXISTS ...) guards.
        val globRegex = Regex("\"([^\"\\n]*\\*[^\"\\n]*)\"")
        val globPaths = globRegex.findAll(allText).map { it.groupValues[1] }
            .filter { it.endsWith(".cpp") || it.endsWith(".cc") || it.endsWith(".c") }
            .toList()
        for (p in globPaths) {
            val idx = codeLines.indexOfFirst { p in it }
            assertTrue("glob source not found in file: $p", idx >= 0)
            assertTrue(
                "glob referencing an optional/absent path must be inside if(EXISTS ...): $p",
                codeLines.take(idx).any { it.contains("if(EXISTS") }
            )
        }

        // 2. Literal (non-glob) build sources are unconditional: they must exist.
        val literalPaths = tokens.filter { '*' !in it }.distinct()
        assertTrue("no literal build sources found in CMakeLists.txt", literalPaths.isNotEmpty())
        for (p in literalPaths) {
            val resolved = File(p).exists() ||
                File(p.removePrefix("./")).exists() ||
                includeDirsOf(codeLines).any { dir -> File(dir, p).exists() }
            assertTrue("CMakeLists.txt references missing build source: $p", resolved)
        }

        // 3. The optional agent-native block must stay explicitly guarded.
        assertTrue(
            "agent-native sources must be guarded with if(EXISTS ...)",
            allText.contains("if(EXISTS")
        )
    }

    /** Directories named in this file's include_directories(...) blocks. */
    private fun includeDirsOf(lines: List<String>): List<String> =
        Regex("\\$\\{CMAKE_CURRENT_SOURCE_DIR\\}/([^\\s)\"]+)")
            .findAll(lines.joinToString("\n"))
            .map { it.groupValues[1] }
            .toList()

    @Test
    fun `main test command targets a posix shell not windows dir`() {
        val text = File("main.kt").readText()
        assertTrue(
            "main.kt must issue a POSIX `ls` for the Android/Linux target",
            text.contains("\"command\" to \"ls\"")
        )
        assertTrue(
            "Windows-only `dir` command must not be used",
            !text.contains("\"command\" to \"dir\"")
        )
    }
}
