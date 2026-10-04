package com.lanie.workspace

enum class RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }

data class CapabilityRisk(
    val level: RiskLevel,
    val description: String,
    val requiresUserConfirmation: Boolean
)
