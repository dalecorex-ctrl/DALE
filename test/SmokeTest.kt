package com.lanie.workspace

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

/**
 * Deterministic runtime smoke path.
 *
 * Exercises the real stack end to end:
 *
 *   caller -> AgentGatewayRouter -> CapabilityRegistry -> Capability -> execution
 *
 * plus the negative paths (unknown action, malformed request, blocked script,
 * execution failure, timeout).
 *
 * Run it with `kotlin`/`java`; it exits 0 only when every check passed.
 * Destructive commands are NEVER executed here: ScriptValidator is asked to
 * *judge* them, never ShellCapability.
 */
data class SmokeCheck(val name: String, val passed: Boolean, val detail: String)

private fun req(actionId: String, command: String? = null, confirmed: Boolean = true) =
    CapabilityRequest(
        actionId = actionId,
        targetPackage = "com.lanie.workspace",
        parameters = buildMap {
            if (command != null) put("command", command)
            if (confirmed) {
                put(ShellCapability.USER_CONFIRMATION_KEY, ShellCapability.CONFIRMATION_VALUE)
            }
        }
    )

fun runSmokeChecks(): List<SmokeCheck> = runBlocking {
    val checks = mutableListOf<SmokeCheck>()
    fun add(name: String, passed: Boolean, detail: String) {
        checks += SmokeCheck(name, passed, detail)
    }

    val router = AgentGatewayRouter(".")

    // --- positive: request -> router -> registry -> capability -> result ---
    val ok = router.routeRequest(req("execute_shell_command", "echo smoke-ok"))
    add(
        "router_positive",
        ok.success && ok.outputData?.get("output") == "smoke-ok\n",
        "success=${ok.success} output=${ok.outputData?.get("output")?.trim()}"
    )

    // --- unknown action must fail closed AND execute nothing ---
    val markerUnknown = File("/tmp/lanie_smoke_unknown_marker")
    markerUnknown.delete()
    val unknown = router.routeRequest(
        req("totally_unknown_action", "touch ${markerUnknown.absolutePath}")
    )
    add(
        "unknown_action_fails_closed",
        !unknown.success && unknown.outputData == null && !markerUnknown.exists(),
        "success=${unknown.success} outputData=${unknown.outputData} " +
            "executed=${markerUnknown.exists()}"
    )

    // --- malformed request: missing required 'command' parameter ---
    val malformed = router.routeRequest(req("execute_shell_command"))
    add(
        "malformed_request_rejected",
        !malformed.success && (malformed.errorMessage?.contains("command") == true),
        "success=${malformed.success} error=${malformed.errorMessage}"
    )

    // --- non-zero exit propagation ---
    val nonzero = router.routeRequest(req("execute_shell_command", "echo partial; exit 7"))
    add(
        "nonzero_exit_propagated",
        !nonzero.success &&
            (nonzero.errorMessage?.contains("7") == true) &&
            nonzero.outputData?.get("output") == "partial\n",
        "success=${nonzero.success} error=${nonzero.errorMessage} " +
            "output=${nonzero.outputData?.get("output")}"
    )

    // --- empty output ---
    val empty = router.routeRequest(req("execute_shell_command", "true"))
    add(
        "empty_output_handled",
        empty.success && empty.outputData?.get("output") == "",
        "success=${empty.success} outputLen=${empty.outputData?.get("output")?.length}"
    )

    // --- large output must not deadlock ---
    val large = router.routeRequest(req("execute_shell_command", "seq 1 100000"))
    val largeLen = large.outputData?.get("output")?.length ?: 0
    add(
        "large_output_no_deadlock",
        large.success && largeLen > 500_000,
        "success=${large.success} outputLen=$largeLen"
    )

    // --- stderr separation + exit code via TerminalRunner ---
    val runner = TerminalRunner(File("."))
    val split = runner.executeCommand("echo oops 1>&2; echo fine", 30) { }
    add(
        "terminal_stderr_captured",
        split.exitCode == 0 &&
            split.output.contains("fine") &&
            split.error.contains("oops"),
        "exit=${split.exitCode} out=${split.output.trim()} err=${split.error.trim()}"
    )

    val termNonZero = runner.executeCommand("exit 3", 30) { }
    add(
        "terminal_nonzero_exit",
        termNonZero.exitCode == 3,
        "exit=${termNonZero.exitCode}"
    )

    // --- timeout must fire, report, and terminate the process ---
    val markerTimeout = File("/tmp/lanie_smoke_timeout_marker")
    markerTimeout.delete()
    val timedOut = runner.executeCommand(
        "sleep 3; touch ${markerTimeout.absolutePath}",
        1
    ) { }
    Thread.sleep(5000) // give a surviving process time to create the marker
    add(
        "timeout_terminates_process",
        timedOut.exitCode == -1 &&
            timedOut.error.contains("timed out") &&
            !markerTimeout.exists(),
        "exit=${timedOut.exitCode} reported=${timedOut.error.contains("timed out")} " +
            "processSurvived=${markerTimeout.exists()}"
    )

    // --- security: destructive script is BLOCKED by the validator, not run ---
    val validator = ScriptValidator()
    val blocked = validator.validateScript("rm -rf /")
    add(
        "destructive_script_blocked",
        !blocked.isSafe,
        "isSafe=${blocked.isSafe} reason=${blocked.reason}"
    )

    val markerValidate = File("/tmp/lanie_smoke_validate_marker")
    markerValidate.delete()
    validator.validateScript("touch ${markerValidate.absolutePath}")
    add(
        "validator_side_effect_free",
        !markerValidate.exists(),
        "executed=${markerValidate.exists()}"
    )

    // --- security declarations remain intact ---
    val shell = ShellCapability(".")
    add(
        "shell_risk_high_and_confirmed",
        shell.risk.level == RiskLevel.HIGH && shell.risk.requiresUserConfirmation,
        "level=${shell.risk.level} requiresConfirmation=${shell.risk.requiresUserConfirmation}"
    )

    // --- the declared risk gate is actually ENFORCED ---
    val markerUnconfirmed = File("/tmp/lanie_smoke_unconfirmed_marker")
    markerUnconfirmed.delete()
    val unconfirmed = router.routeRequest(
        req("execute_shell_command", "touch ${markerUnconfirmed.absolutePath}", confirmed = false)
    )
    add(
        "unconfirmed_request_fails_closed",
        !unconfirmed.success && !markerUnconfirmed.exists(),
        "success=${unconfirmed.success} executed=${markerUnconfirmed.exists()}"
    )

    checks
}

fun main() {
    val checks = runSmokeChecks()

    println()
    println("LANIE ANDROID RUNTIME — SMOKE")
    println("=".repeat(74))
    for (c in checks) {
        val verdict = if (c.passed) "PASS" else "FAIL"
        println(String.format("  %-4s  %-32s %s", verdict, c.name, c.detail))
    }
    val failed = checks.count { !it.passed }
    println("=".repeat(74))
    println("total=${checks.size} passed=${checks.size - failed} failed=$failed")

    if (failed > 0) exitProcess(1)
    exitProcess(0)
}
