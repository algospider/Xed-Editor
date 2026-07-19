package com.rk.exec

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object ShellUtils {
    data class Result(val exitCode: Int, val output: String, val error: String, val timedOut: Boolean)

    private const val BUFFER_SIZE = 4096
    private const val STREAM_TIMEOUT_MS = 3000L

    private val streamPool = Executors.newCachedThreadPool { r ->
        Thread(r, "shell-stream").apply { isDaemon = true }
    }

    suspend fun run(vararg command: String, timeoutSeconds: Long? = null): Result =
        withContext(Dispatchers.IO) {
            val process = ProcessBuilder(*command)
                .redirectErrorStream(false)
                .start()
            readProcess(process, timeoutSeconds)
        }

    suspend fun runUbuntu(
        workingDir: String? = null,
        vararg command: String,
        extraEnv: Map<String, String> = emptyMap(),
        timeoutSeconds: Long? = null,
    ): Result =
        withContext(Dispatchers.IO) {
            val process = ubuntuProcess(workingDir = workingDir, command = command.toList(), extraEnv = extraEnv)
            readProcess(process, timeoutSeconds)
        }

    suspend fun runUbuntuStreaming(
        workingDir: String? = null,
        vararg command: String,
        extraEnv: Map<String, String> = emptyMap(),
        timeoutSeconds: Long? = null,
        onStdout: (String) -> Unit = {},
        onStderr: (String) -> Unit = {},
    ): Result =
        withContext(Dispatchers.IO) {
            val process = ubuntuProcess(workingDir = workingDir, command = command.toList(), extraEnv = extraEnv)
            val output = StringBuilder(512)
            val error = StringBuilder(256)

            val outputFuture = streamPool.submit {
                readStreamLines(process.inputStream, output, onStdout)
            }
            val errorFuture = streamPool.submit {
                readStreamLines(process.errorStream, error, onStderr)
            }

            val timedOut = waitForProcess(process, timeoutSeconds)

            outputFuture.get(STREAM_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            errorFuture.get(STREAM_TIMEOUT_MS, TimeUnit.MILLISECONDS)

            Result(
                exitCode = if (timedOut) -1 else runCatching { process.exitValue() }.getOrDefault(-1),
                output = output.trimEnd().toString(),
                error = error.trimEnd().toString(),
                timedOut = timedOut,
            )
        }

    private fun readProcess(process: Process, timeoutSeconds: Long?): Result {
        val output = StringBuilder(512)
        val error = StringBuilder(256)

        val outputFuture = streamPool.submit {
            readStream(process.inputStream, output)
        }
        val errorFuture = streamPool.submit {
            readStream(process.errorStream, error)
        }

        val timedOut = waitForProcess(process, timeoutSeconds)

        outputFuture.get(STREAM_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        errorFuture.get(STREAM_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        return Result(
            exitCode = if (timedOut) -1 else runCatching { process.exitValue() }.getOrDefault(-1),
            output = output.trimEnd().toString(),
            error = error.trimEnd().toString(),
            timedOut = timedOut,
        )
    }

    private fun waitForProcess(process: Process, timeoutSeconds: Long?): Boolean {
        return try {
            if (timeoutSeconds != null) {
                !process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            } else {
                process.waitFor()
                false
            }
        } catch (_: InterruptedException) {
            process.destroyForcibly()
            true
        }
    }

    private fun readStream(stream: InputStream, sb: StringBuilder) {
        stream.buffered(BUFFER_SIZE).reader().use { reader ->
            val buf = CharArray(BUFFER_SIZE)
            var read: Int
            while (reader.read(buf).also { read = it } != -1) {
                sb.append(buf, 0, read)
            }
        }
    }

    private fun readStreamLines(
        stream: InputStream,
        sb: StringBuilder,
        onLine: (String) -> Unit,
    ) {
        BufferedReader(InputStreamReader(stream), BUFFER_SIZE).use { reader ->
            var line = reader.readLine()
            while (line != null) {
                sb.appendLine(line)
                onLine(line)
                line = reader.readLine()
            }
        }
    }
}
