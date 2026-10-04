package com.lanie.workspace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI

data class InferenceRequest(
    val prompt: String,
    val maxTokens: Int = 512,
    val temperature: Float = 0.7f,
    val stop: List<String> = emptyList()
)

data class InferenceResponse(
    val text: String,
    val generationTimeMs: Long,
    val tokensGenerated: Int
)

/**
 * Minimal llama.cpp HTTP client (no third-party JSON dependency).
 *
 * Both call paths bound their socket waits, surface HTTP failures as exceptions
 * rather than silent empty successes, and release the connection on *every* path
 * through `finally` — including the error paths.
 */
class LlamaCppBridge(
    private val serverUrl: String = "http://127.0.0.1:8080",
    private val connectTimeoutMs: Int = 5000,
    private val readTimeoutMs: Int = 60000
) {

    /**
     * Sends a synchronous completion request to the local llama.cpp server.
     */
    suspend fun complete(request: InferenceRequest): InferenceResponse = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val connection = openConnection(request, stream = false)
        try {
            writePayload(connection, request, stream = false)

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw RuntimeException(
                    "Inference server error: HTTP $responseCode${readBody(connection.errorStream)}"
                )
            }

            val responseString = connection.inputStream.bufferedReader().use { it.readText() }
            val duration = System.currentTimeMillis() - startTime

            val content = extractJsonField(responseString, "content")
                ?: throw RuntimeException(
                    "Malformed inference response: no 'content' field in body"
                )
            // Only report a count the server actually returned; never present the
            // requested token budget as though it had been measured.
            val predicted = extractJsonField(responseString, "tokens_predicted")?.toIntOrNull() ?: 0

            InferenceResponse(
                text = content,
                generationTimeMs = duration,
                tokensGenerated = predicted
            )
        } finally {
            // Always release the socket, including on HTTP errors and timeouts.
            connection.disconnect()
        }
    }

    /**
     * Streams tokens in real time using Kotlin Flows for UI or agent feedback loops.
     *
     * Throws on a non-200 response instead of completing with zero emissions, and
     * closes both the reader and the connection when the flow completes, fails, or
     * is cancelled.
     */
    fun streamCompletion(request: InferenceRequest): Flow<String> = flow {
        val connection = openConnection(request, stream = true)
        var reader: BufferedReader? = null
        try {
            writePayload(connection, request, stream = true)

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw RuntimeException(
                    "Inference server error: HTTP $responseCode${readBody(connection.errorStream)}"
                )
            }

            reader = BufferedReader(InputStreamReader(connection.inputStream))
            var line: String? = reader.readLine()
            while (line != null) {
                if (line.startsWith("data: ")) {
                    val token = extractJsonField(line.substring(6), "content")
                    if (!token.isNullOrEmpty()) {
                        emit(token)
                    }
                }
                line = reader.readLine()
            }
        } finally {
            try {
                reader?.close()
            } catch (_: Exception) {
                // Ignore: cleanup must not mask the original failure.
            }
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    private fun openConnection(request: InferenceRequest, stream: Boolean): HttpURLConnection {
        // URI -> URL avoids the Java 20+ deprecated URL(String) constructor.
        val url = URI.create("$serverUrl/completion").toURL()
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            if (stream) setRequestProperty("Accept", "text/event-stream")
            doOutput = true
            // Both waits must be bounded, otherwise a dead peer hangs the caller.
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
        }
    }

    private fun writePayload(connection: HttpURLConnection, request: InferenceRequest, stream: Boolean) {
        val stopField = if (request.stop.isEmpty()) {
            ""
        } else {
            ",\n              \"stop\": [${request.stop.joinToString(",") { "\"${escapeJson(it)}\"" }}]"
        }
        val payload = """
            {
              "prompt": "${escapeJson(request.prompt)}",
              "n_predict": ${request.maxTokens},
              "temperature": ${request.temperature},
              "stream": $stream$stopField
            }
        """.trimIndent()

        connection.outputStream.use { os ->
            os.write(payload.toByteArray(Charsets.UTF_8))
        }
    }

    /** Reads a body for diagnostics without letting it escape as a leak. */
    private fun readBody(stream: InputStream?): String = try {
        stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    } catch (_: Exception) {
        ""
    }

    /** Escapes backslashes first, otherwise already-escaped sequences are corrupted. */
    private fun escapeJson(input: String): String = input
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    /**
     * Pulls a field out of a JSON object without a parser dependency.
     *
     * Handles quoted strings containing escaped quotes (which the previous
     * `[^"]*` pattern could not) and bare numbers, so `tokens_predicted` resolves too.
     */
    private fun extractJsonField(json: String, field: String): String? {
        val pattern = ("\"" + field + "\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|[^,}\\s]+)").toRegex()
        val match = pattern.find(json) ?: return null
        val raw = match.groupValues[1]
        return when {
            // Quoted value: the alternative only matches through a real terminator,
            // so this is a well-formed JSON string.
            raw.startsWith("\"") && raw.length >= 2 && raw.endsWith("\"") ->
                unescapeJson(raw.substring(1, raw.length - 1))

            // The bare-value branch matched something starting with a quote: the
            // string was never terminated (truncated body). Returning it would
            // hand back half a token as if it were real output.
            raw.startsWith("\"") -> null

            // JSON null is an absent value, not the literal word "null".
            raw == "null" -> null

            else -> raw
        }
    }

    /** Single-pass unescaper so an escaped backslash-n is not read as a newline. */
    private fun unescapeJson(value: String): String {
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (val next = value[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2; continue }
                    'r' -> { sb.append('\r'); i += 2; continue }
                    't' -> { sb.append('\t'); i += 2; continue }
                    'b' -> { sb.append('\b'); i += 2; continue }
                    '"' , '\\' , '/' -> { sb.append(next); i += 2; continue }
                    'u' -> {
                        if (i + 6 <= value.length) {
                            val hex = value.substring(i + 2, i + 6).toIntOrNull(16)
                            if (hex != null) {
                                sb.append(hex.toChar())
                                i += 6
                                continue
                            }
                        }
                        sb.append(c); i += 1; continue
                    }
                    else -> { sb.append(c); i += 1; continue }
                }
            }
            sb.append(c)
            i += 1
        }
        return sb.toString()
    }
}
