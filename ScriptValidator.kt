package com.lanie.workspace

data class ValidationResult(
    val isSafe: Boolean,
    val reason: String
)

class ScriptValidator {

    private val forbiddenPatterns = listOf(
        "rm -rf /",
        "mkfs",
        ":(){ :|:& };:", // Fork bomb
        "os.system('rm",
        "shutil.rmtree(\"/\")"
    )

    /**
     * Statically analyzes script text to ensure it complies with local security policies
     * before execution in the Python or Terminal runners.
     */
    fun validateScript(scriptContent: String): ValidationResult {
        for (pattern in forbiddenPatterns) {
            if (scriptContent.contains(pattern)) {
                return ValidationResult(
                    isSafe = false,
                    reason = "Blocked by ScriptValidator: Detected dangerous pattern matching '$pattern'."
                )
            }
        }

        return ValidationResult(
            isSafe = true,
            reason = "Script passed static security inspection successfully."
        )
    }
}
