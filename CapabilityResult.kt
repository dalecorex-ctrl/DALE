package com.lanie.workspace

data class CapabilityResult(
    val success: Boolean,
    val outputData: Map<String, String>?,
    val errorMessage: String?
)
