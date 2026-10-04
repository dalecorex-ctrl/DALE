package com.lanie.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

data class ShellResult(
    val exitCode: Int,
    val output: String,
    val error: String
)

/** Default upper bound on a single command's wall-clock runtime. */
const val DEFAULT_TIMEOUT_SECONDS: Long = 30

/** Milliseconds each reader thread is given to drain before we treat it as stuck. */
private const val READER_JOIN_GRACE_MS = 2000L

/** Milliseconds allowed for a process to die gracefully after a polite destroy. */
private const val TERMINATION_GRACE_MS = 500L

/** Interval at which the process tree is re-snapshotted while waiting. */
private const val DESCENDANT_POLL_MS = 50L

/**
 * Executes raw shell commands with a hard timeout, bounded output draining, and
 * deterministic process termination.
 *
 * Guarantees:
 *  - returns within `timeoutSeconds` plus a bounded drain grace;
 *  - child stdin is closed immediately, so a command that reads stdin cannot
 *    block past the timeout;
 *  - on timeout the whole process *tree* is reaped, not just the direct child;
 *  - reader threads are joined with a bound, so a descendant that inherited the
 *    output pipe can never hang the caller indefinitely;
 *  - output buffers are read under a lock, so no reader thread can be observed
 *    mid-write.
 */
class TerminalRunner(
    private val workingDirectory: File,
    private val defaultTimeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS
) {

    /**
     * Executes a raw shell command asynchronously within the sandbox environment.
     */
    suspend fun executeCommand(
        command: String,
        timeoutSeconds: Long = defaultTimeoutSeconds,
        onOutputLine: (String) -> Unit
    ): ShellResult = withContext(Dispatchers.IO) {
        val processBuilder = ProcessBuilder("sh", "-c", command).apply {
            directory(workingDirectory)
            redirectErrorStream(false)
        }

        val process = processBuilder.start()

        // The agent runtime never writes to a child's stdin. Leaving the pipe open
        // means `head`/`read`/interactive tools inherit a silent writer and block
        // forever, past the timeout. Close it so they observe EOF immediately.
        try {
            process.outputStream.close()
        } catch (_: Exception) {
            // Best effort: failing to close stdin must not abort the command.
        }

        val lock = Any()
        val outputBuffer = StringBuilder()
        val errorBuffer = StringBuilder()
        var callbackFailure: String? = null

        fun reader(stream: InputStream, target: StringBuilder, prefix: String, threadName: String): Thread {
            val thread = Thread {
                try {
                    stream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            synchronized(lock) {
                                target.append(prefix).append(line).append('\n')
                            }
                            try {
                                onOutputLine(prefix + line)
                            } catch (t: Throwable) {
                                // A faulty callback must not kill the reader thread
                                // (that would stall the pipe) nor be swallowed.
                                synchronized(lock) {
                                    if (callbackFailure == null) callbackFailure = t.toString()
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Expected once the stream is closed during termination.
                }
            }
            // Daemon: a stuck reader must never pin the JVM at exit.
            thread.isDaemon = true
            thread.name = threadName
            return thread
        }

        val stdoutThread = reader(process.inputStream, outputBuffer, "", "lanie-stdout-reader")
        val stderrThread = reader(process.errorStream, errorBuffer, "[STDERR] ", "lanie-stderr-reader")
        stdoutThread.start()
        stderrThread.start()

        val trackedDescendants = LinkedHashSet<ProcessHandle>()
        var timedOut = false
        var interrupted = false

        try {
            val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
            var exited = false
            while (!exited) {
                // Cooperative cancellation: checked on every poll tick so a
                // cancelled coroutine stops waiting within ~DESCENDANT_POLL_MS
                // instead of silently running out the whole timeout.
                ensureActive()
                // Snapshot while the process is still alive: once it exits its
                // children are reparented to init and become unreachable.
                rememberDescendants(process, trackedDescendants)
                val remainingNs = deadlineNs - System.nanoTime()
                if (remainingNs <= 0L) {
                    timedOut = true
                    break
                }
                val waitMs = minOf(TimeUnit.NANOSECONDS.toMillis(remainingNs), DESCENDANT_POLL_MS)
                exited = process.waitFor(if (waitMs <= 0L) 1L else waitMs, TimeUnit.MILLISECONDS)
            }
        } catch (ce: CancellationException) {
            // Reap the tree *before* propagating, otherwise a cancelled coroutine
            // leaves its command running until the (no longer observed) timeout.
            rememberDescendants(process, trackedDescendants)
            terminate(process, trackedDescendants)
            joinReaders(stdoutThread, stderrThread, READER_JOIN_GRACE_MS)
            throw ce
        } catch (ie: InterruptedException) {
            interrupted = true
            Thread.currentThread().interrupt()
        } finally {
            if (timedOut || interrupted) {
                rememberDescendants(process, trackedDescendants)
                terminate(process, trackedDescendants)
            }
        }

        // Drain readers under a bound. On the normal path the pipes are already at
        // EOF and this returns immediately; if a leftover descendant is holding the
        // pipe open, reap it and give the readers one more chance.
        if (!joinReaders(stdoutThread, stderrThread, READER_JOIN_GRACE_MS)) {
            terminate(process, trackedDescendants)
            joinReaders(stdoutThread, stderrThread, READER_JOIN_GRACE_MS)
        }

        val output: String
        val error: String
        val failure: String?
        synchronized(lock) {
            output = outputBuffer.toString()
            error = errorBuffer.toString()
            failure = callbackFailure
        }

        val notes = StringBuilder()
        if (timedOut) notes.append("\nCommand timed out after ${timeoutSeconds}s")
        if (interrupted) notes.append("\nCommand was interrupted")
        if (failure != null) notes.append("\nOutput callback failed: $failure")
        if (stdoutThread.isAlive || stderrThread.isAlive) {
            notes.append(
                "\n[warning: output may be truncated; a background process kept " +
                    "the output pipe open]"
            )
        }

        val exitCode = when {
            timedOut || interrupted -> -1
            else -> try {
                process.exitValue()
            } catch (e: Exception) {
                -1
            }
        }

        ShellResult(
            exitCode = exitCode,
            output = output,
            error = error + notes.toString()
        )
    }

    /** Adds every currently alive descendant of [process] into [sink]. */
    private fun rememberDescendants(process: Process, sink: MutableSet<ProcessHandle>) {
        val stream = process.toHandle().descendants()
        try {
            val iterator = stream.iterator()
            while (iterator.hasNext()) {
                val handle = iterator.next()
                if (handle.isAlive) sink.add(handle)
            }
        } catch (_: Exception) {
            // Tree inspection is best effort; never fail the command because of it.
        } finally {
            try {
                stream.close()
            } catch (_: Exception) {
                // Ignore.
            }
        }
    }

    /**
     * Terminates [process] and every tracked descendant.
     *
     * Descendants are signalled first: killing the parent reparents them to init,
     * after which they can no longer be reached through the parent's handle.
     */
    private fun terminate(process: Process, tracked: Collection<ProcessHandle>) {
        for (handle in tracked) {
            try {
                if (handle.isAlive) handle.destroy()
            } catch (_: Exception) {
                // A handle may already have exited.
            }
        }
        try {
            process.destroy()
        } catch (_: Exception) {
            // Already exited.
        }
        try {
            process.waitFor(TERMINATION_GRACE_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        for (handle in tracked) {
            try {
                if (handle.isAlive) handle.destroyForcibly()
            } catch (_: Exception) {
                // Already gone.
            }
        }
        try {
            process.destroyForcibly()
        } catch (_: Exception) {
            // Already gone.
        }
    }

    /** Joins both readers within [graceMs]; returns false if either stayed alive. */
    private fun joinReaders(stdout: Thread, stderr: Thread, graceMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + graceMs
        for (thread in listOf(stdout, stderr)) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) return false
            try {
                thread.join(remaining)
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            if (thread.isAlive) return false
        }
        return true
    }
}
