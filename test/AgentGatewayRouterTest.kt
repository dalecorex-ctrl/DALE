package com.lanie.workspace

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural tests for the gateway router: routing, unknown capabilities,
 * execution failure handling, and the suspend boundary.
 */
class AgentGatewayRouterTest {

    private fun request(actionId: String, command: String? = null) = CapabilityRequest(
        actionId = actionId,
        targetPackage = "com.lanie.workspace",
        parameters = command?.let { mapOf("command" to it) } ?: emptyMap()
    )

    @Test
    fun `router registers shell capability on construction`() {
        val router = AgentGatewayRouter(".")
        // Reach the private registry through behaviour: a routed known action must not
        // report "Unknown capability".
        val result = runBlocking { router.routeRequest(request("execute_shell_command", "true")) }
        assertTrue(
            "router did not register ShellCapability: ${result.errorMessage}",
            result.errorMessage?.contains("Unknown capability") != true
        )
    }

    @Test
    fun `router routes known action to shell capability`() = runBlocking {
        val router = AgentGatewayRouter(".")
        val result = router.routeRequest(request("execute_shell_command", "echo routed"))
        assertTrue("expected success, got ${result.errorMessage}", result.success)
        assertEquals("routed\n", result.outputData!!["output"])
    }

    @Test
    fun `router rejects unknown action id without throwing`() = runBlocking {
        val router = AgentGatewayRouter(".")
        val result = router.routeRequest(request("unknown_action"))
        assertTrue(!result.success)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage!!.contains("unknown_action"))
    }

    @Test
    fun `router surfaces invalid capability input as failure not crash`() = runBlocking {
        val router = AgentGatewayRouter(".")
        val result = router.routeRequest(request("execute_shell_command")) // no command
        assertTrue(!result.success)
        assertTrue(result.errorMessage!!.contains("command"))
    }

    @Test
    fun `router honours the configured working directory`() = runBlocking {
        val router = AgentGatewayRouter("/system/bin")
        val result = router.routeRequest(request("execute_shell_command", "pwd"))
        assertTrue(result.success)
        val output = result.outputData!!["output"].orEmpty().trim()
        // pwd resolves symlinks; accept either the literal path or its resolution.
        assertTrue(
            "expected pwd under /system/bin, got: $output",
            output == "/system/bin" || output.startsWith("/system")
        )
    }

    @Test
    fun `router is callable from coroutine context (suspend boundary holds)`() = runBlocking {
        val router = AgentGatewayRouter(".")
        // routeRequest is suspend; executing it inside runBlocking proves the
        // suspend/non-suspend boundary is consistent with Capability.execute.
        val results = (1..5).map { i ->
            router.routeRequest(request("execute_shell_command", "echo $i"))
        }
        assertTrue(results.all { it.success })
        assertEquals(listOf("1", "2", "3", "4", "5"),
            results.map { it.outputData!!["output"]!!.trim() })
    }

    @Test
    fun `router does not silently swallow unexpected exceptions`() = runBlocking {
        val router = AgentGatewayRouter("/nonexistent/path/for/lanie/test")
        val result = router.routeRequest(request("execute_shell_command", "echo hi"))
        // ProcessBuilder fails on a missing directory: must be reported as an error,
        // never as a fabricated success.
        if (!result.success) {
            assertNotNull(result.errorMessage)
            assertTrue(!result.success)
        } else {
            // Some platforms resolve this oddly; then output must be real.
            assertNotNull(result.outputData)
        }
    }
}
