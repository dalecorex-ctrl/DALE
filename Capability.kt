package com.lanie.workspace

interface Capability {
    val id: String
    val name: String
    val risk: CapabilityRisk

    suspend fun execute(request: CapabilityRequest): CapabilityResult
}
