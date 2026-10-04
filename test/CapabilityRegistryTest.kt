package com.lanie.workspace

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural tests for the capability registry and its API surface.
 */
class CapabilityRegistryTest {

    private fun request(actionId: String, command: String? = null) = CapabilityRequest(
        actionId = actionId,
        targetPackage = "com.lanie.workspace",
        parameters = command?.let { mapOf("command" to it) } ?: emptyMap()
    )

    @Test
    fun `registered capability is retrievable by id`() {
        val registry = CapabilityRegistry()
        registry.register(ShellCapability("."))
        assertNotNull(registry.getCapability("execute_shell_command"))
    }

    @Test
    fun `unregistered capability returns null`() {
        val registry = CapabilityRegistry()
        assertNull(registry.getCapability("no_such_capability"))
    }

    @Test
    fun `registry keys capabilities by their id property not by class`() {
        val registry = CapabilityRegistry()
        val capability = ShellCapability(".")
        registry.register(capability)
        assertEquals(capability.id, "execute_shell_command")
        assertNotNull(registry.getCapability(capability.id))
    }

    @Test
    fun `registering the same capability twice does not duplicate entries`() {
        val registry = CapabilityRegistry()
        registry.register(ShellCapability("."))
        registry.register(ShellCapability("."))
        // Map keyed by id: a second registration replaces rather than duplicates.
        assertEquals("execute_shell_command", registry.getCapability("execute_shell_command")?.id)
    }

    @Test
    fun `invokeCapability rejects unknown action id with failure result`() = runBlocking {
        val registry = CapabilityRegistry()
        val result = registry.invokeCapability(request("does_not_exist"))
        assertTrue(!result.success)
        assertNull(result.outputData)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage!!.contains("does_not_exist"))
    }

    @Test
    fun `invokeCapability executes a registered capability end to end`() = runBlocking {
        val registry = CapabilityRegistry()
        registry.register(ShellCapability("."))
        val result = registry.invokeCapability(request("execute_shell_command", "echo lanie"))
        assertTrue("expected success, got: ${result.errorMessage}", result.success)
        assertNotNull(result.outputData)
        assertEquals("lanie\n", result.outputData!!["output"])
    }

    @Test
    fun `capability without command parameter fails with explicit error`() = runBlocking {
        val registry = CapabilityRegistry()
        registry.register(ShellCapability("."))
        val result = registry.invokeCapability(request("execute_shell_command"))
        assertTrue(!result.success)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage!!.contains("command"))
    }

    @Test
    fun `nonzero exit code is reported as failure with captured output`() = runBlocking {
        val registry = CapabilityRegistry()
        registry.register(ShellCapability("."))
        val result = registry.invokeCapability(
            request("execute_shell_command", "echo partial; exit 3")
        )
        assertTrue(!result.success)
        assertTrue(result.errorMessage!!.contains("3"))
        // Partial output must still be surfaced to the caller.
        assertEquals("partial\n", result.outputData!!["output"])
    }

    @Test
    fun `shell capability carries the declared HIGH risk level`() {
        val capability = ShellCapability(".")
        assertEquals(RiskLevel.HIGH, capability.risk.level)
        assertTrue(capability.risk.requiresUserConfirmation)
    }

    @Test
    fun `capability result and request expose the documented shape`() {
        val req = request("execute_shell_command", "true")
        assertEquals("execute_shell_command", req.actionId)
        assertEquals("com.lanie.workspace", req.targetPackage)
        assertTrue(req.timestamp > 0)

        val res = CapabilityResult(true, mapOf("k" to "v"), null)
        assertTrue(res.success)
        assertEquals("v", res.outputData!!["k"])
        assertNull(res.errorMessage)
    }
}
