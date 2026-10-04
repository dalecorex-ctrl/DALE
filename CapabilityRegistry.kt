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
        return capability.execute(request)
    }
}
