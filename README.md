# LANIE Android Runtime

An Android/Kotlin **capability-gated agent runtime** for the LANIE project.

This repository contains the **Android runtime layer only**. It is deliberately
kept separate from the Phase 5 Python LANIE repository.

---

## Architectural placement

```
LANIE (product)
├── Phase 5 Python LANIE repository   ← NOT this repo (separate, verified source of truth)
│     /sdcard/Download/LANIE-Mobile-Agent-OS-v0.2.0-alpha/LANIE-OS
│
└── Android Runtime                   ← THIS repo
      package com.lanie.workspace
      Kotlin capability layer + JNI native bridge (whisper.cpp)
```

**Why the separation:** the two implementations overlap partially (both expose an
agent loop, shell/tool execution, and memory), but they are built on different
runtimes — CPython vs ART — with different capability/security models. Merging
them before the Android runtime is proven would destroy the clean boundary
between them. Nothing from this repository has been merged into Phase 5.

---

## Layers

| Layer | Contents | Status |
|-------|----------|--------|
| Kotlin capability layer | `Capability*`, `ShellCapability`, `CapabilityRegistry`, `AgentGatewayRouter`, `TerminalRunner`, `ScriptValidator`, `PingTest` | builds + runs |
| Kotlin Android layer | `MemoryManager` (SQLite) | needs Android SDK |
| Kotlin LLM bridge | `LlamaCppBridge` (llama.cpp HTTP/SSE client) | builds |
| JNI bridge | `NativeRuntimeBridge` ↔ `native_bridge.cpp` | builds + runs |
| Native | `whisper.cpp` + `ggml` via CMake | builds |

### Request flow

```
main / caller
  → AgentGatewayRouter.routeRequest   (suspend)
      → CapabilityRegistry.getCapability(actionId)
          → Capability.execute(request)   (suspend)
              → ShellCapability → ProcessBuilder("sh", "-c", …)
      ← CapabilityResult(success, outputData, errorMessage)
```

`TerminalRunner` is the streaming/time-out variant of shell execution;
`ScriptValidator` performs static policy checks **before** execution.

---

## Security model

- Every capability declares a `CapabilityRisk` (level + `requiresUserConfirmation`).
- `ShellCapability` is `RiskLevel.HIGH` and `requiresUserConfirmation = true`.
- `ScriptValidator` blocks destructive patterns (`rm -rf /`, `mkfs`, fork bombs,
  `os.system('rm`, `shutil.rmtree("/")`). It is a **pure static check** — it never
  executes what it inspects (asserted by test).
- Unknown `actionId` values fail closed; they never resolve to a real capability.

Tests in `test/ScriptValidatorTest.kt` (`SecurityRegressionTest`) guard these
invariants so they cannot be silently weakened.

---

## Building

### Kotlin layer (host)

```bash
kotlinc Capability.kt CapabilityRequest.kt CapabilityResult.kt CapabilityRisk.kt \
        CapabilityRegistry.kt ShellCapability.kt AgentGatewayRouter.kt \
        TerminalRunner.kt ScriptValidator.kt NativeRuntimeBridge.kt \
        PingTest.kt LlamaCppBridge.kt main.kt \
        -classpath kotlinx-coroutines-core-jvm.jar -d out

java -cp "out:kotlinx-coroutines-core-jvm.jar:kotlin-stdlib.jar" com.lanie.workspace.MainKt
```

`MemoryManager.kt` is excluded here: it needs `android.jar` from the Android SDK.

### Native layer (host)

```bash
cmake -B build -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON
cmake --build build -j$(nproc)
```

Requires a **JDK** (not a JRE) so `jni.h` can be found; set `JAVA_HOME`.
On Android/NDK the headers come from the toolchain sysroot automatically.

### Tests

See `test/` — 41 JUnit 4 tests covering package/API consistency, router ↔
registry ↔ capability integration, invalid/unknown capability handling, JNI
declaration ↔ implementation correspondence, and security regressions.

---

## Vendor bootstrap

`whisper.cpp-master/` and `agent-native-main/` are third-party trees, excluded
from this repository by `.gitignore` and restored locally as needed. CMake guards
the optional `agent-native` integration with `if(EXISTS …)` so its absence never
breaks the build.

---

## Not yet done

- No Gradle project, `AndroidManifest.xml`, or Android SDK/NDK on the audit host:
  **this runtime has never been built into an APK.**
- `native_bridge.cpp` methods are **placeholders** — they verify JNI symbol
  resolution and marshalling, but do not yet call into whisper inference.
- `MemoryManager.kt` has never been compiled.
