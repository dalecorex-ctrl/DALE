package com.lanie.workspace

class CapabilityRegistry {
    private val capabilities = mutableMapOf<String, Capability>()

    fun register(capability: Capability) {
        capabilities[capability.id] = capability
    }

    fun getCapability(id: String): Capability? = capabilities[id]

    suspend fun invokeCapability(request: CapabilityRequest): CapabilityResult {
        val capability = capabilities[request.actionId]
            ?: return CapabilityResult(false, null, "Capability not found for action ID: ${request.actionId}")
        return try {
            capability.execute(request)
        } catch (e: Exception) {
            // Keep failure semantics identical to AgentGatewayRouter.routeRequest:
            // an escaping exception must surface as a failed result, never as a
            // crash on one entry point and a result on the other.
            CapabilityResult(false, null, "Execution failed: ${e.localizedMessage}")
        }
    }
}
