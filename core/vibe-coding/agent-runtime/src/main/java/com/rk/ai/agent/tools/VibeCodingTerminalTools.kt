@file:OptIn(ExperimentalUuidApi::class)

package com.rk.ai.agent.tools

import com.google.gson.JsonObject
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import com.rk.ai.models.InputSchema
import com.rk.ai.models.Tool
import com.rk.ai.models.UIMessagePart
import com.rk.ai.service.IdeService

class VibeCodingTerminalTools(private val ideService: IdeService) {

    private val runCommand = Tool(
        name = "runCommand",
        description = "Run a shell command in the terminal environment. " +
            "Use ONLY for: building, running tests, package installs, compilation, linters. " +
            "DO NOT use for file reading/writing, code search, git operations — native tools are faster. " +
            "Always call saveOpenFiles first to ensure editor content is saved to disk. " +
            "Example: {\"command\": \"npm run build\"} or {\"command\": \"kotlinc src/*.kt\", \"timeoutSeconds\": 60}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("command") { put("type", "string"); put("description", "Shell command to execute. Can include pipes, redirects, chaining (&&, ||).") }
                    putJsonObject("timeoutSeconds") { put("type", "integer"); put("description", "Timeout in seconds (default: 120, max: 600). Increase for long builds.") }
                    putJsonObject("workdir") { put("type", "string"); put("description", "Working directory for the command (optional, defaults to project root)") }
                },
                required = listOf("command"),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val command = obj["command"]?.asJsonPrimitive?.asString
                ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing 'command'.\nEXPECTED: {\"command\": \"npm run build\"}"))
            val timeout = (obj["timeoutSeconds"]?.asJsonPrimitive?.asLong ?: 120L).coerceIn(1, 600)
            val result = ideService.runCommand(command, timeout)
            val text = buildString {
                if (result.output.isNotBlank()) appendLine("STDOUT:\n${result.output}")
                if (result.error.isNotBlank()) appendLine("STDERR:\n${result.error}")
                append("Exit: ${result.exitCode}")
                if (result.timedOut) append(" (TIMED OUT after ${timeout}s)")
            }
            listOf(UIMessagePart.Text(text.trimEnd()))
        },
    )

    private val getTerminalOutput = Tool(
        name = "getTerminalOutput",
        description = "Get recent terminal transcript output. " +
            "Use to check what was printed in an ongoing terminal session. " +
            "Example: {\"lines\": 50}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("lines") { put("type", "integer"); put("description", "Number of recent lines to retrieve (default: all available)") }
                },
                required = emptyList<String>(),
            )
        },
        execute = { args ->
            val lines = args.asJsonObject["lines"]?.asJsonPrimitive?.asInt
            val output = ideService.getTerminalOutput(lines)
            listOf(UIMessagePart.Text(output.ifEmpty { "No terminal output available" }))
        },
    )

    val all: List<Tool> = listOf(runCommand, getTerminalOutput)
}
