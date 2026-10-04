package com.lanie.workspace

import java.io.IOException

object PingTest {

    /**
     * Executes a basic loopback ping command to test process execution capabilities
     * within the standalone Android application sandbox.
     */
    fun runPingTest(): Boolean {
        return try {
            val process = ProcessBuilder(listOf("ping", "-c", "1", "127.0.0.1"))
                .redirectErrorStream(true)
                .start()

            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: IOException) {
            false
        } catch (e: InterruptedException) {
            false
        }
    }
}
