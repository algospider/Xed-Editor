package com.rk.ai.bridge.server

import android.util.Log
import com.rk.ai.bridge.McpToolRegistry
import com.rk.ai.service.IdeService
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicReference

class McpStdioServer(
    private val ideServiceProvider: () -> IdeService,
) {
    companion object {
        private const val TAG = "McpStdioServer"
    }

    @Volatile
    var toolRegistry: McpToolRegistry = McpToolRegistry()

    private val activeServer = AtomicReference<Server?>(null)
    private var serverJob: Job? = null

    val isRunning: Boolean get() = activeServer.get() != null

    @Synchronized
    fun start(
        registry: McpToolRegistry,
        workspacePaths: List<String>,
        input: java.io.InputStream = System.`in`,
        output: java.io.OutputStream = System.out,
    ) {
        if (activeServer.get() != null) {
            if (com.rk.xededitor.BuildConfig.DEBUG) {
                Log.w(TAG, "Stdio server already running")
            }
            return
        }
        toolRegistry = registry
        val sdkServer = buildServer(registry, workspacePaths)
        activeServer.set(sdkServer)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        serverJob = scope.launch {
            try {
                val transport = StdioServerTransport(
                    inputStream = input.asSource().buffered(),
                    outputStream = output.asSink().buffered(),
                )
                sdkServer.createSession(transport)
                if (com.rk.xededitor.BuildConfig.DEBUG) {
                    Log.d(TAG, "Stdio MCP server session ended")
                }
            } catch (e: Exception) {
                if (com.rk.xededitor.BuildConfig.DEBUG) {
                    Log.e(TAG, "Stdio server error", e)
                }
                activeServer.set(null)
            }
        }
    }

    @Synchronized
    fun startWithProcess(
        registry: McpToolRegistry,
        workspacePaths: List<String>,
        command: List<String>,
    ): Process? {
        if (activeServer.get() != null) return null
        toolRegistry = registry

        return try {
            val pb = ProcessBuilder(command)
            pb.redirectErrorStream(false)
            val process = pb.start()

            val sdkServer = buildServer(registry, workspacePaths)
            activeServer.set(sdkServer)

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            serverJob = scope.launch {
                try {
                    val transport = StdioServerTransport(
                        inputStream = process.inputStream.asSource().buffered(),
                        outputStream = process.outputStream.asSink().buffered(),
                    )
                    sdkServer.createSession(transport)
                } catch (e: Exception) {
                    if (com.rk.xededitor.BuildConfig.DEBUG) {
                        Log.e(TAG, "Stdio process server error", e)
                    }
                    activeServer.set(null)
                }
            }

            if (com.rk.xededitor.BuildConfig.DEBUG) {
                Log.d(TAG, "Started MCP stdio server with process: ${command.joinToString(" ")}")
            }
            process
        } catch (e: Exception) {
            if (com.rk.xededitor.BuildConfig.DEBUG) {
                Log.e(TAG, "Failed to start stdio process", e)
            }
            null
        }
    }

    @Synchronized
    fun startWithPipes(
        registry: McpToolRegistry,
        workspacePaths: List<String>,
    ): Pair<PipedInputStream, PipedOutputStream> {
        val serverToClientIn = PipedInputStream(8192)
        val clientToServerOut = PipedOutputStream(serverToClientIn)

        val clientToServerIn = PipedInputStream(8192)
        val serverToClientOut = PipedOutputStream(clientToServerIn)

        val sdkServer = buildServer(registry, workspacePaths)
        activeServer.set(sdkServer)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        serverJob = scope.launch {
            try {
                val transport = StdioServerTransport(
                    inputStream = clientToServerIn.asSource().buffered(),
                    outputStream = serverToClientOut.asSink().buffered(),
                )
                sdkServer.createSession(transport)
            } catch (e: Exception) {
                if (com.rk.xededitor.BuildConfig.DEBUG) {
                    Log.e(TAG, "Stdio pipe server error", e)
                }
                activeServer.set(null)
            }
        }

        if (com.rk.xededitor.BuildConfig.DEBUG) {
            Log.d(TAG, "Started MCP stdio server with piped I/O")
        }
        return Pair(serverToClientIn, clientToServerOut)
    }

    private fun buildServer(registry: McpToolRegistry, workspacePaths: List<String>): Server {
        val sdkServer = Server(
            serverInfo = Implementation(
                name = "xed-ide-bridge-stdio",
                version = "2.2.0",
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = true),
                    resources = ServerCapabilities.Resources(
                        listChanged = true,
                        subscribe = true,
                    ),
                    prompts = ServerCapabilities.Prompts(listChanged = true),
                ),
            ),
        )
        registerToolsToSdkServer(
            sdkServer = sdkServer,
            registry = registry,
            ideServiceProvider = ideServiceProvider,
            progressCallback = { name, msg ->
                if (com.rk.xededitor.BuildConfig.DEBUG) {
                    Log.d(TAG, "Tool progress: $name - $msg")
                }
            },
        )
        McpResourceProvider.registerResources(sdkServer, workspacePaths, ideServiceProvider)
        McpPromptProvider.registerPrompts(sdkServer)
        return sdkServer
    }

    @Synchronized
    fun stop() {
        serverJob?.cancel()
        serverJob = null
        activeServer.getAndSet(null)?.close()
        if (com.rk.xededitor.BuildConfig.DEBUG) {
            Log.d(TAG, "Stdio MCP server stopped")
        }
    }
}
