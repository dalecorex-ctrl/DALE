package com.lanie.workspace

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for ScriptValidator and PingTest, plus security regression guards.
 *
 * These tests assert that the safety checks still function. They must never be
 * weakened to make another test pass.
 */
class ScriptValidatorTest {

    private val validator = ScriptValidator()

    @Test
    fun `rejects rm -rf root`() {
        val r = validator.validateScript("rm -rf /")
        assertFalse(r.isSafe)
        assertTrue(r.reason.contains("ScriptValidator"))
    }

    @Test
    fun `rejects mkfs`() {
        assertFalse(validator.validateScript("mkfs /dev/sda1").isSafe)
    }

    @Test
    fun `rejects fork bomb`() {
        assertFalse(validator.validateScript(":(){ :|:& };:").isSafe)
    }

    @Test
    fun `rejects python os system rm`() {
        assertFalse(validator.validateScript("os.system('rm -rf /')").isSafe)
    }

    @Test
    fun `rejects python shutil rmtree root`() {
        assertFalse(validator.validateScript("shutil.rmtree(\"/\")").isSafe)
    }

    @Test
    fun `allows benign script`() {
        val r = validator.validateScript("print('hello from lanie')")
        assertTrue(r.isSafe)
        assertTrue(r.reason.contains("passed"))
    }

    @Test
    fun `validator does not execute the script it inspects`() {
        // If validateScript had side effects, this marker file would exist.
        val marker = java.io.File("/tmp/opencode/validator_side_effect_marker")
        marker.delete()
        validator.validateScript("touch /tmp/opencode/validator_side_effect_marker")
        assertFalse(
            "ScriptValidator must be a pure static check, never an executor",
            marker.exists()
        )
    }
}

class PingTestTest {

    /**
     * Runs `ping -c 1 127.0.0.1` through a *separate* ProcessBuilder so the
     * expectation does not come from PingTest itself. If PingTest always returned
     * false, always returned true, or mishandled an exception, this would fail.
     */
    private fun independentPing(): Boolean = try {
        ProcessBuilder("ping", "-c", "1", "-W", "2", "127.0.0.1")
            .redirectErrorStream(true)
            .start()
            .waitFor() == 0
    } catch (e: Exception) {
        false
    }

    @Test
    fun `ping agrees with an independently run ping`() {
        val expected = independentPing()
        val actual = PingTest.runPingTest()
        assertEquals(
            "PingTest must report the same loopback reachability an independent ping sees",
            expected,
            actual
        )
    }

    @Test
    fun `ping does not hang`() {
        val started = System.currentTimeMillis()
        PingTest.runPingTest()
        val elapsed = System.currentTimeMillis() - started
        assertTrue("runPingTest must return promptly, took ${elapsed}ms", elapsed < 30_000)
    }
}

/**
 * Security regression guard: the shell capability must keep requiring user
 * confirmation and must keep reporting a HIGH risk level.
 */
class SecurityRegressionTest {

    @Test
    fun `shell capability risk stays HIGH and requires confirmation`() {
        val c = ShellCapability(".")
        assertEquals(RiskLevel.HIGH, c.risk.level)
        assertTrue(c.risk.requiresUserConfirmation)
        assertTrue(c.risk.description.isNotEmpty())
    }

    @Test
    fun `script validator still blocks every documented forbidden pattern`() {
        val validator = ScriptValidator()
        val samples = listOf(
            "rm -rf /",
            "mkfs",
            ":(){ :|:& };:",
            "os.system('rm",
            "shutil.rmtree(\"/\")"
        )
        for (s in samples) {
            assertFalse("pattern must stay blocked: $s", validator.validateScript(s).isSafe)
        }
    }

    @Test
    fun `unknown capability cannot be routed into a real one`() = runBlocking {
        val router = AgentGatewayRouter(".")
        // An attacker-supplied action id must not resolve to shell execution.
        val result = router.routeRequest(
            CapabilityRequest(
                actionId = "execute_shell_command_but_not_really",
                targetPackage = "com.lanie.workspace",
                parameters = mapOf("command" to "echo pwned")
            )
        )
        assertFalse(result.success)
        assertEquals(null, result.outputData)
    }

    // --- Enforcement, not merely declaration -------------------------------

    private fun shellRequest(command: String, confirmed: Boolean) = CapabilityRequest(
        actionId = "execute_shell_command",
        targetPackage = "com.lanie.workspace",
        parameters = buildMap {
            put("command", command)
            if (confirmed) {
                put(ShellCapability.USER_CONFIRMATION_KEY, ShellCapability.CONFIRMATION_VALUE)
            }
        }
    )

    @Test
    fun `high risk capability refuses to execute without user confirmation`() = runBlocking {
        val marker = java.io.File("/tmp/opencode/security_unconfirmed_marker")
        marker.delete()
        val router = AgentGatewayRouter(".")
        val result = router.routeRequest(
            shellRequest("touch ${marker.absolutePath}", confirmed = false)
        )
        assertFalse("unconfirmed HIGH risk request must fail", result.success)
        assertEquals(null, result.outputData)
        assertTrue(
            "the refusal must say confirmation is required",
            result.errorMessage!!.contains("requires user confirmation")
        )
        assertFalse(
            "no command may run on an unconfirmed request",
            marker.exists()
        )
    }

    @Test
    fun `confirmation value must be exactly true`() = runBlocking {
        val marker = java.io.File("/tmp/opencode/security_confirm_value_marker")
        marker.delete()
        val router = AgentGatewayRouter(".")
        for (value in listOf("yes", "1", "TRUE", "true ")) {
            val result = router.routeRequest(
                CapabilityRequest(
                    actionId = "execute_shell_command",
                    targetPackage = "com.lanie.workspace",
                    parameters = mapOf(
                        "command" to "touch ${marker.absolutePath}",
                        ShellCapability.USER_CONFIRMATION_KEY to value
                    )
                )
            )
            assertFalse("confirmation '$value' must be rejected", result.success)
        }
        assertFalse("only the exact confirmation value may execute", marker.exists())
    }

    @Test
    fun `destructive command is blocked on the live execution path`() = runBlocking {
        // Sub-audit proof this closes: previously ScriptValidator was never called,
        // so a blocked pattern still executed. The marker would appear if it did.
        val marker = java.io.File("/tmp/opencode/security_validator_live_marker")
        marker.delete()
        val router = AgentGatewayRouter(".")
        val result = router.routeRequest(
            shellRequest("echo probe-mkfs; touch ${marker.absolutePath}", confirmed = true)
        )
        assertFalse("blocked pattern must be rejected", result.success)
        assertTrue(
            "rejection must come from ScriptValidator",
            result.errorMessage!!.contains("ScriptValidator")
        )
        assertFalse("the blocked command must never have run", marker.exists())
    }

    @Test
    fun `script validator is reachable from the execution path`() {
        // Guards against the validator silently becoming dead code again.
        val shellText = java.io.File("ShellCapability.kt").readText()
        assertTrue(
            "ShellCapability must consult ScriptValidator",
            shellText.contains("validator.validateScript(")
        )
        assertTrue(
            "ShellCapability must enforce requiresUserConfirmation",
            shellText.contains("risk.requiresUserConfirmation")
        )
        assertTrue(
            "ShellCapability must bound execution with TerminalRunner",
            shellText.contains("runner.executeCommand(")
        )
    }
}
