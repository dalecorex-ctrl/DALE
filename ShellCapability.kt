package com.lanie.workspace

class ShellCapability(private val workingDirectoryPath: String) : Capability {
    override val id: String = "execute_shell_command"
    override val name: String = "Local Shell Execution"
    override val risk: CapabilityRisk = CapabilityRisk(
        level = RiskLevel.HIGH,
        description = "Executes arbitrary shell commands in the local sandbox workspace.",
        requiresUserConfirmation = true
    )

    override suspend fun execute(request: CapabilityRequest): CapabilityResult {
        val command = request.parameters["command"]
            ?: return CapabilityResult(false, null, "Missing required parameter: 'command'")

        try {
            // Simple process execution wrapper
            val process = ProcessBuilder("sh", "-c", command)
                .directory(java.io.File(workingDirectoryPath))
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                return CapabilityResult(true, mapOf("output" to output), null)
            } else {
                return CapabilityResult(false, mapOf("output" to output), "Command exited with error code: $exitCode")
            }
        } catch (e: Exception) {
            return CapabilityResult(false, null, "Execution exception: ${e.localizedMessage}")
        }
    }
}
