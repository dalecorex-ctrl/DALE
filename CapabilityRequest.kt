package com.lanie.workspace

data class CapabilityRequest(
    val actionId: String,
    val targetPackage: String,
    val parameters: Map<String, String>,
    val timestamp: Long = System.currentTimeMillis()
)
