package com.lanie.workspace

import kotlinx.coroutines.runBlocking

fun main() {
    runBlocking {
        // Set your working directory path (using current directory '.')
        val workspacePath = "."

        println("Initializing Mobile OS Local Capability Registry...")
        val registry = CapabilityRegistry()

        // Register our shell command capability
        registry.register(ShellCapability(workspacePath))

        // Prepare a request to list files using the POSIX 'ls' command
        val request = CapabilityRequest(
            actionId = "execute_shell_command",
            targetPackage = "com.lanie.workspace",
            parameters = mapOf("command" to "ls")
        )

        println("Executing action: ${request.actionId}...")

        // Invoke the capability through the registry router
        val result = registry.invokeCapability(request)

        // Handle and display the result
        if (result.success) {
            println("\n--- Execution Succeeded ---")
            println(result.outputData?.get("output"))
        } else {
            println("\n--- Execution Failed ---")
            println("Error: ${result.errorMessage}")
            result.outputData?.get("output")?.let { println("Partial Output:\n$it") }
        }
    }
}
