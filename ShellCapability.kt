package com.lanie.workspace

import java.io.File

/**
 * High-risk shell capability.
 *
 * Three gates must all pass before anything reaches `sh -c`:
 *  1. the action id resolves through the capability registry (exact match);
 *  2. the request carries explicit user confirmation, because this capability
 *     declares `requiresUserConfirmation = true` — enforced here so that *every*
 *     entry point (router, registry, main) is covered and none can bypass it;
 *  3. [ScriptValidator] rejects destructive script content.
 *
 * Execution itself is delegated to [TerminalRunner], which applies a hard
 * timeout and reaps the whole process tree.
 */
class ShellCapability(
    private val workingDirectoryPath: String,
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS
) : Capability {
    override val id: String = "execute_shell_command"
    override val name: String = "Local Shell Execution"
    override val risk: CapabilityRisk = CapabilityRisk(
        level = RiskLevel.HIGH,
        description = "Executes arbitrary shell commands in the local sandbox workspace.",
        requiresUserConfirmation = true
    )

    private val validator = ScriptValidator()
    private val runner = TerminalRunner(File(workingDirectoryPath), timeoutSeconds)

    override suspend fun execute(request: CapabilityRequest): CapabilityResult {
        val command = request.parameters["command"]
            ?: return CapabilityResult(false, null, "Missing required parameter: 'command'")

        // Gate 2 — fail closed. Declared-but-unchecked risk metadata is not a
        // security control, so the capability validates its own policy.
        if (risk.requiresUserConfirmation && !isConfirmed(request)) {
            return CapabilityResult(
                false,
                null,
                "Capability '$id' is ${risk.level} risk and requires user confirmation; " +
                    "resubmit with parameter '$USER_CONFIRMATION_KEY'='true' to proceed."
            )
        }

        // Gate 3 — static policy check, on the live execution path.
        val validation = validator.validateScript(command)
        if (!validation.isSafe) {
            return CapabilityResult(false, null, validation.reason)
        }

        return try {
            val result = runner.executeCommand(command, timeoutSeconds) { }

            val outputData = mapOf(
                "output" to result.output,
                "stderr" to result.error,
                "exitCode" to result.exitCode.toString()
            )

            val message = when {
                result.exitCode == 0 -> null
                result.error.contains("Command timed out after") ->
                    "Command timed out after ${timeoutSeconds}s"
                else -> "Command exited with error code: ${result.exitCode}"
            }

            CapabilityResult(result.exitCode == 0, outputData, message)
        } catch (e: Exception) {
            // Never fabricate success: a spawn failure is reported as failure.
            CapabilityResult(false, null, "Execution exception: ${e.localizedMessage}")
        }
    }

    private fun isConfirmed(request: CapabilityRequest): Boolean =
        request.parameters[USER_CONFIRMATION_KEY] == CONFIRMATION_VALUE

    companion object {
        /** Request parameter that carries the caller's explicit acknowledgement. */
        const val USER_CONFIRMATION_KEY = "user_confirmation"

        /** Only this exact value counts as confirmation. */
        const val CONFIRMATION_VALUE = "true"
    }
}
