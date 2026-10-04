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
| Kotlin Android layer | `MemoryManager` (SQLite) | compiles against `android.jar` (API 28) |
| Kotlin LLM bridge | `LlamaCppBridge` (llama.cpp HTTP/SSE client) | builds; verified against a live server |
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

`ShellCapability` delegates all process handling to `TerminalRunner`, which
imposes a hard timeout, closes child stdin, reaps the whole process tree, joins
its reader threads under a bound, and honours cooperative cancellation.
`ScriptValidator` performs the static policy check **before** execution, and the
`requiresUserConfirmation` gate is enforced inside `ShellCapability` itself, so
every entry point (router, registry, `main`) passes through it.

---

## Security model

- Every capability declares a `CapabilityRisk` (level + `requiresUserConfirmation`).
- `ShellCapability` is `RiskLevel.HIGH` and `requiresUserConfirmation = true`,
  and the declaration is **enforced, not decorative**: a request missing
  `user_confirmation=true` fails closed without spawning anything. The check
  lives in the capability, so no entry point can bypass it. The value must match
  exactly (`true` is accepted; `yes`/`1`/`TRUE` are not).
- `ScriptValidator` blocks destructive patterns (`rm -rf /`, `mkfs`, fork bombs,
  `os.system('rm`, `shutil.rmtree("/")`). It runs on the **live execution
  path**, so a blocked command never reaches `sh -c` — proven by a test that
  would create a marker file if execution ever happened. It is a **pure static
  check** — it never executes what it inspects (asserted by test).
- Unknown `actionId` values fail closed; they never resolve to a real capability.
- Execution is bounded: hard timeout, whole-process-tree termination, closed
  child stdin, bounded reader joins, and cooperative cancellation, so a
  runaway command cannot outlive its caller.

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

`MemoryManager.kt` is excluded from the host build above: it needs `android.jar`
from the Android SDK. With a platform installed it compiles on its own:

```bash
kotlinc MemoryManager.kt -classpath /usr/lib/android-sdk/platforms/android-28/android.jar -d out-mm
```

### Native layer (host)

```bash
cmake -B build -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON
cmake --build build -j$(nproc)
```

Requires a **JDK** (not a JRE) so `jni.h` can be found; set `JAVA_HOME`.
On Android/NDK the headers come from the toolchain sysroot automatically.

### Tests

See `test/` — 59 JUnit 4 tests covering package/API consistency, router ↔
registry ↔ capability integration, invalid/unknown capability handling, JNI
declaration ↔ implementation correspondence, security regressions (including
*enforcement*: unconfirmed and destructive requests are proven not to execute),
bounded execution (timeout, process termination, no orphan survivors), and the
`LlamaCppBridge` contract.

`test/LlamaCppBridgeTest.kt` is a **deterministic integration probe**: it starts
an in-process HTTP server on an ephemeral port and proves connection failure,
HTTP failure, malformed / `null` / truncated responses, read timeout, streaming
order, and descriptor cleanup — with no model, GPU, or network required.

A runnable end-to-end check sits alongside them:

```bash
java -cp "…" com.lanie.workspace.SmokeTestKt   # 13 checks; exits non-zero on any failure
```

To exercise the bridge against a **real** llama.cpp server (not part of the
suite, since it cannot be assumed):

```bash
llama-server --model model.gguf --host 127.0.0.1 --port 8080
# then POST http://127.0.0.1:8080/completion — the bridge's endpoint
```

Note the bridge targets llama.cpp's legacy `/completion` endpoint, not the
OpenAI-compatible `/v1/chat/completions`; both are served by the same binary.

---

## Vendor bootstrap

`whisper.cpp-master/` and `agent-native-main/` are third-party trees, excluded
from this repository by `.gitignore`. Restore them from the archives kept beside
the sources:

```bash
# Required: the native build HARD-FAILS without whisper.cpp-master.
unzip -q whisper.cpp-master.zip

# Optional: only used if agent-native-main/src ever exists.
unzip -q agent-native-main.zip
```

CMake treats the two differently, on purpose:

| Tree | If missing |
|------|------------|
| `whisper.cpp-master/` | `message(FATAL_ERROR …)` — configure **fails**, because `whisper` is a required link dependency of `mobile_runtime_native` |
| `agent-native-main/src/` | guarded by `if(EXISTS …)`, builds without it and prints a status line |

The `agent-native` guard covers only the *sources*; note that `agent-native-main`
as shipped is a JS/TS tree with no `src/` or `include/`, so the C++ integration
is currently dormant.

---

## Not yet done

- **No APK.** There is no Gradle project and no `AndroidManifest.xml`, and the
  host has no NDK and no `d8`/dexer. A platform jar (`android.jar`, API 28) and
  `aapt2`/`zipalign`/`apksigner` are present, but that alone cannot package,
  dex, or sign an application. The Kotlin layer is therefore **build-proven and
  host-runnable**, not installable.
- `native_bridge.cpp` methods are **placeholders** — they verify JNI symbol
  resolution and marshalling, but do not yet call into whisper inference.
  `libwhisper` is built from the vendored tree and exports the expected symbols,
  but `mobile_runtime_native` does not reference them yet, so it does not link
  against it.
- `MemoryManager.kt` compiles against `android.jar`, but has never been *run* —
  executing it requires a real Android runtime. Known limitations found by
  audit, **not yet fixed** because they cannot be exercised here:
  - `onUpgrade` runs `DROP TABLE` + `onCreate`, so a schema-version bump
    **discards existing memories** (host-verified against an equivalent schema);
  - `saveMemory` uses `CONFLICT_REPLACE`, which deletes-then-inserts, so the row
    `id` is **not stable** across saves and `AUTOINCREMENT` ids are burned;
  - the `content` column has no `NOT NULL` constraint while the Kotlin field is
    non-null, so an externally inserted `NULL` row would surface as a platform
    type and can throw on read.
