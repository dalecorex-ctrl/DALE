package com.lanie.workspace

class AgentGatewayRouter(private val workingDirectoryPath: String = ".") {

    private val registry = CapabilityRegistry()

    /**
     * Registers core system capabilities on initialization.
     */
    init {
        registry.register(ShellCapability(workingDirectoryPath))
    }

    /**
     * Routes an incoming capability request to the appropriate handler module.
     */
    suspend fun routeRequest(request: CapabilityRequest): CapabilityResult {
        val capability = registry.getCapability(request.actionId)
            ?: return CapabilityResult(false, null, "Unknown capability: ${request.actionId}")

        return try {
            capability.execute(request)
        } catch (e: Exception) {
            CapabilityResult(false, null, "Execution failed: ${e.localizedMessage}")
        }
    }
}
