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

    @Test
    fun `ping returns a boolean rather than throwing`() {
        // On a sandbox without ping or without net privileges this may be false;
        // the contract is that it must not throw.
        val result = PingTest.runPingTest()
        assertTrue(result || !result) // type/behaviour sanity: no exception escaped
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
}
