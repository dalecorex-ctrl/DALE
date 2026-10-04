package com.lanie.workspace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

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

class LlamaCppBridge(private val serverUrl: String = "http://127.0.0.1:8080") {

    /**
     * Sends a synchronous completion request to the local llama.cpp server.
     */
    suspend fun complete(request: InferenceRequest): InferenceResponse = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val url = URL("$serverUrl/completion")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 5000
            readTimeout = 60000
        }

        // Construct raw JSON payload manually to keep dependencies lean
        val payload = """
            {
              "prompt": "${escapeJson(request.prompt)}",
              "n_predict": ${request.maxTokens},
              "temperature": ${request.temperature},
              "stream": false
            }
        """.trimIndent()

        connection.outputStream.use { os ->
            os.write(payload.toByteArray(Charsets.UTF_8))
        }

        val responseCode = connection.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
            throw RuntimeException("Inference server error: HTTP $responseCode")
        }

        val responseString = connection.inputStream.bufferedReader().use { it.readText() }
        val duration = System.currentTimeMillis() - startTime

        // Simple parsing of response text (assuming standard llama.cpp JSON field "content")
        val content = extractJsonField(responseString, "content") ?: ""

        InferenceResponse(
            text = content,
            generationTimeMs = duration,
            tokensGenerated = request.maxTokens // Approximation if count omitted
        )
    }

    /**
     * Streams tokens in real-time using Kotlin Flows for UI or agent feedback loops.
     */
    fun streamCompletion(request: InferenceRequest): Flow<String> = flow {
        val url = URL("$serverUrl/completion")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            doOutput = true
        }

        val payload = """
            {
              "prompt": "${escapeJson(request.prompt)}",
              "n_predict": ${request.maxTokens},
              "temperature": ${request.temperature},
              "stream": true
            }
        """.trimIndent()

        connection.outputStream.use { os ->
            os.write(payload.toByteArray(Charsets.UTF_8))
        }

        if (connection.responseCode == HttpURLConnection.HTTP_OK) {
            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            var line: String? = reader.readLine()
            while (line != null) {
                if (line.startsWith("data: ")) {
                    val jsonData = line.substring(6)
                    val token = extractJsonField(jsonData, "content")
                    if (!token.isNullOrEmpty()) {
                        emit(token)
                    }
                }
                line = reader.readLine()
            }
        }
    }

    private fun escapeJson(input: String): String {
        return input.replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    private fun extractJsonField(json: String, field: String): String? {
        // Lightweight helper to pull text values out without heavy parser dependencies
        val pattern = "\"$field\"\\s*:\\s*\"([^\"]*)\"".toRegex()
        val match = pattern.find(json)
        return match?.groupValues?.get(1)
    }
}
