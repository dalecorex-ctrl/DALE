package com.lanie.workspace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

data class ShellResult(
    val exitCode: Int,
    val output: String,
    val error: String
)

class TerminalRunner(private val workingDirectory: File) {

    /**
     * Executes a raw shell command asynchronously within the sandbox environment.
     */
    suspend fun executeCommand(
        command: String,
        timeoutSeconds: Long = 30,
        onOutputLine: (String) -> Unit
    ): ShellResult = withContext(Dispatchers.IO) {
        val processBuilder = ProcessBuilder("sh", "-c", command).apply {
            directory(workingDirectory)
            redirectErrorStream(false)
        }

        val process = processBuilder.start()
        val outputBuffer = StringBuilder()
        val errorBuffer = StringBuilder()

        val stdoutThread = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    outputBuffer.append(line).append("\n")
                    onOutputLine(line)
                }
            }
        }
        stdoutThread.start()

        val stderrThread = Thread {
            process.errorStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    errorBuffer.append(line).append("\n")
                    onOutputLine("[STDERR] $line")
                }
            }
        }
        stderrThread.start()

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return@withContext ShellResult(
                exitCode = -1,
                output = outputBuffer.toString(),
                error = errorBuffer.toString() + "\nCommand timed out after ${timeoutSeconds}s"
            )
        }

        stdoutThread.join()
        stderrThread.join()

        ShellResult(
            exitCode = process.exitValue(),
            output = outputBuffer.toString(),
            error = errorBuffer.toString()
        )
    }
}
