package com.lanie.workspace

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Deterministic llama.cpp integration probe.
 *
 * Every case runs against an in-process HTTP server on an ephemeral port, so the
 * suite proves the bridge's contract — connection failure, HTTP failure,
 * malformed/absent/null content, read timeout, streaming, and descriptor
 * cleanup — with no external model, GPU, or network. Reaching a *real* llama.cpp
 * server is verified separately (see README) because it cannot be assumed here.
 */
class LlamaCppBridgeTest {

    private var server: HttpServer? = null

    @After
    fun stopServer() {
        server?.stop(0)
        server = null
    }

    /** A port with nothing listening, so connect() is refused rather than routed. */
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun fdCount(): Int = java.io.File("/proc/self/fd").list()?.size ?: -1

    private fun request(prompt: String = "hello", maxTokens: Int = 16) =
        InferenceRequest(prompt, maxTokens = maxTokens)

    /**
     * Starts a server answering POST /completion. Returns the base URL.
     *
     * @param hangMillis delays the response to exercise the read timeout.
     * @param onReceive hands the raw request body to the test (for escaping checks).
     */
    private fun serve(
        status: Int = 200,
        body: String = "",
        hangMillis: Long = 0,
        onReceive: ((String) -> Unit)? = null
    ): String {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/completion") { exchange ->
            val raw = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            onReceive?.invoke(raw)
            if (hangMillis > 0) Thread.sleep(hangMillis)
            val payload = body.toByteArray(Charsets.UTF_8)
            if (payload.isEmpty()) {
                exchange.sendResponseHeaders(status, -1)
            } else {
                exchange.sendResponseHeaders(status, payload.size.toLong())
                exchange.responseBody.use { it.write(payload) }
            }
            exchange.close()
        }
        http.start()
        server = http
        return "http://127.0.0.1:${http.address.port}"
    }

    /** Runs [block], returning the throwable it raised, or null if it did not throw. */
    private fun thrownBy(block: () -> Unit): Throwable? = try {
        block()
        null
    } catch (t: Throwable) {
        t
    }

    @Test
    fun `connection failure is reported rather than hanging`() {
        val base = "http://127.0.0.1:${freePort()}"
        val started = System.currentTimeMillis()
        val thrown = thrownBy {
            runBlocking { LlamaCppBridge(base, connectTimeoutMs = 2000).complete(request()) }
        }
        assertTrue("a refused connection must throw", thrown != null)
        assertTrue(
            "expected a connect failure, got ${thrown!!.javaClass.simpleName}",
            thrown.javaClass.simpleName.contains("Connect")
        )
        assertTrue(
            "must fail fast, took ${System.currentTimeMillis() - started}ms",
            System.currentTimeMillis() - started < 5000
        )
    }

    @Test
    fun `http error status is surfaced with its code`() {
        val base = serve(status = 500, body = """{"error":"boom"}""")
        val thrown = thrownBy {
            runBlocking { LlamaCppBridge(base).complete(request()) }
        }
        assertTrue("HTTP 500 must throw", thrown != null)
        assertTrue(
            "error must name the status, got: ${thrown!!.message}",
            thrown.message!!.contains("HTTP 500")
        )
    }

    @Test
    fun `response with no content field fails safely`() {
        val base = serve(status = 200, body = """<<<not a completion payload>>>""")
        val thrown = thrownBy {
            runBlocking { LlamaCppBridge(base).complete(request()) }
        }
        assertTrue("garbage body must throw", thrown != null)
        assertTrue(
            "must be reported as malformed, got: ${thrown!!.message}",
            thrown.message!!.contains("Malformed")
        )
    }

    @Test
    fun `null content is treated as missing, not the literal word null`() {
        // Regression: a bare JSON null used to come back as the string "null",
        // which the caller would print as if the model had said it.
        val base = serve(status = 200, body = """{"content":null,"tokens_predicted":3}""")
        val thrown = thrownBy {
            runBlocking { LlamaCppBridge(base).complete(request()) }
        }
        assertTrue("JSON null content must be rejected", thrown != null)
        assertTrue(
            "must be reported as malformed, got: ${thrown!!.message}",
            thrown.message!!.contains("Malformed")
        )
    }

    @Test
    fun `truncated response fails safely instead of returning half a token`() {
        // Regression: the string never terminates, so the value must not be
        // mistaken for real output.
        val base = serve(status = 200, body = """{"tokens_predicted":3,"content": "parti""")
        val thrown = thrownBy {
            runBlocking { LlamaCppBridge(base).complete(request()) }
        }
        assertTrue("truncated body must throw", thrown != null)
        assertTrue(
            "must be reported as malformed, got: ${thrown!!.message}",
            thrown.message!!.contains("Malformed")
        )
    }

    @Test
    fun `valid response yields text and server-measured tokens`() {
        val base = serve(
            status = 200,
            body = """{"content":"hello world","tokens_predicted":7,"stop":false}"""
        )
        val response = runBlocking { LlamaCppBridge(base).complete(request()) }
        assertEquals("hello world", response.text)
        assertEquals(
            "the count must come from the server, not from the request budget",
            7,
            response.tokensGenerated
        )
        assertTrue("generation time must be recorded", response.generationTimeMs >= 0)
    }

    @Test
    fun `escaped characters in the response are unescaped`() {
        val base = serve(
            status = 200,
            body = """{"content":"say \"hi\"\nnext","tokens_predicted":2}"""
        )
        val response = runBlocking { LlamaCppBridge(base).complete(request()) }
        assertEquals("say \"hi\"\nnext", response.text)
    }

    @Test
    fun `backslashes in the outgoing prompt are escaped`() {
        // Regression: "C:\temp" previously went out unescaped, which the server
        // rejected as an invalid JSON escape (or silently corrupted).
        var sent: String? = null
        val base = serve(
            status = 200,
            body = """{"content":"ok","tokens_predicted":1}""",
            onReceive = { sent = it }
        )
        runBlocking { LlamaCppBridge(base).complete(request(prompt = """C:\temp\test""")) }
        val payload = sent ?: error("server never received a request")
        assertTrue(
            "prompt must arrive JSON-escaped, payload was: $payload",
            payload.contains("""C:\\temp\\test""")
        )
    }

    @Test
    fun `read timeout is bounded`() {
        val base = serve(status = 200, body = "unused", hangMillis = 4000)
        val started = System.currentTimeMillis()
        val thrown = thrownBy {
            runBlocking {
                LlamaCppBridge(base, readTimeoutMs = 900).complete(request())
            }
        }
        val elapsed = System.currentTimeMillis() - started
        assertTrue("a hanging server must time out", thrown != null)
        assertTrue(
            "expected a socket timeout, got ${thrown!!.javaClass.simpleName}",
            thrown.javaClass.simpleName.contains("Timeout")
        )
        assertTrue("must return near the 900ms bound, took ${elapsed}ms", elapsed in 700..5000)
    }

    @Test
    fun `stream surfaces an http failure instead of completing empty`() {
        val base = serve(status = 500, body = """{"error":"boom"}""")
        val thrown = thrownBy {
            runBlocking {
                LlamaCppBridge(base).streamCompletion(request()).collect { }
            }
        }
        assertTrue("streaming must not swallow HTTP errors", thrown != null)
        assertTrue(
            "error must name the status, got: ${thrown!!.message}",
            thrown.message!!.contains("HTTP 500")
        )
    }

    @Test
    fun `stream emits tokens in order`() {
        val sse = "data: {\"content\":\"a\",\"stop\":false}\n\n" +
            "data: {\"content\":\"b\",\"stop\":false}\n\n" +
            "data: {\"content\":\"c\",\"stop\":true}\n\n"
        val base = serve(status = 200, body = sse)
        val received = StringBuilder()
        runBlocking {
            LlamaCppBridge(base).streamCompletion(request()).collect { received.append(it) }
        }
        assertEquals("abc", received.toString())
    }

    @Test
    fun `repeated failures do not leak file descriptors`() {
        val base = serve(status = 500, body = """{"error":"boom"}""")
        val before = fdCount()
        var threw = 0
        repeat(20) {
            val thrown = thrownBy {
                runBlocking { LlamaCppBridge(base).complete(request()) }
            }
            if (thrown != null) threw++
        }
        Thread.sleep(300)
        val after = fdCount()
        assertEquals("every failing call must throw", 20, threw)
        // A per-call leak would show ~+20; bounded growth proves disconnect() runs
        // on the error path too.
        assertTrue(
            "descriptors grew per failing call: before=$before after=$after over 20 calls",
            after <= before + 8
        )
    }
}
