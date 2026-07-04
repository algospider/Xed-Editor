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

class VibeCodingLspTools(private val ideService: IdeService) {

    private val getDiagnostics = Tool(
        name = "getDiagnostics",
        description = "Returns LSP diagnostics (errors, warnings, hints) for a file. " +
            "CALL THIS AFTER EVERY EDIT to verify code quality. " +
            "Reports [error], [warning], [hint], [info] severity with file:line:column. " +
            "Example: {\"filePath\": \"src/main.kt\"}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("filePath") { put("type", "string"); put("description", "Absolute path to the file to check") }
                },
                required = listOf("filePath"),
            )
        },
        execute = { args ->
            val filePath = args.asJsonObject["filePath"]?.asJsonPrimitive?.asString
                ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing 'filePath'."))
            val diagnostics = ideService.getDiagnostics(filePath)
            if (diagnostics.size() > 0) {
                val errors = mutableListOf<String>()
                val warnings = mutableListOf<String>()
                val others = mutableListOf<String>()
                diagnostics.forEach { diag ->
                    val d = diag.asJsonObject
                    val line = d["line"]?.asInt ?: 0
                    val col = d["column"]?.asInt ?: 0
                    val message = d["message"]?.asString ?: ""
                    val severity = d["severity"]?.asString ?: "info"
                    val entry = "[$severity] $filePath:$line:$col - $message"
                    when (severity.lowercase()) {
                        "error" -> errors.add(entry)
                        "warning" -> warnings.add(entry)
                        else -> others.add(entry)
                    }
                }
                val text = buildString {
                    if (errors.isNotEmpty()) { appendLine("=== ERRORS (${errors.size}) ==="); errors.forEach { appendLine(it) } }
                    if (warnings.isNotEmpty()) { appendLine("=== WARNINGS (${warnings.size}) ==="); warnings.forEach { appendLine(it) } }
                    if (others.isNotEmpty()) { appendLine("=== INFO/HINTS (${others.size}) ==="); others.forEach { appendLine(it) } }
                }
                listOf(UIMessagePart.Text(text.trimEnd()))
            } else {
                listOf(UIMessagePart.Text("OK No diagnostics for $filePath (clean)"))
            }
        },
    )

    private val findDefinitions = Tool(
        name = "findDefinitions",
        description = "Jump to the DEFINITION of a symbol (function, class, variable) at a given file:line:column. " +
            "Uses LSP go-to-definition. Returns the target file path. " +
            "Use when you need to understand what a symbol resolves to. " +
            "Example: {\"filePath\": \"src/main.kt\", \"line\": 42, \"column\": 10}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("filePath") { put("type", "string"); put("description", "Absolute path to the file containing the symbol") }
                    putJsonObject("line") { put("type", "integer"); put("description", "Line number of the symbol (1-indexed)") }
                    putJsonObject("column") { put("type", "integer"); put("description", "Column number of the symbol (1-indexed)") }
                },
                required = listOf("filePath", "line", "column"),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val filePath = obj["filePath"]?.asJsonPrimitive?.asString ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing filePath"))
            val line = obj["line"]?.asJsonPrimitive?.asInt ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing line"))
            val column = obj["column"]?.asJsonPrimitive?.asInt ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing column"))
            val definitions = ideService.findDefinitions(filePath, line, column)
            if (definitions.size() > 0) {
                listOf(UIMessagePart.Text(definitions.joinToString("\n") { it.asJsonObject["path"]?.asString ?: it.toString() }))
            } else {
                listOf(UIMessagePart.Text("No definition found at $filePath:$line:$column.\nSUGGESTION: Try searchSymbols with the symbol name instead."))
            }
        },
    )

    private val findReferences = Tool(
        name = "findReferences",
        description = "Find ALL usages/references of a symbol at a given file:line:column. " +
            "CRITICAL for refactoring — reveals every place a symbol is used. " +
            "Example: {\"filePath\": \"src/main.kt\", \"line\": 10, \"column\": 5}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("filePath") { put("type", "string"); put("description", "Absolute path to the file containing the symbol") }
                    putJsonObject("line") { put("type", "integer"); put("description", "Line number (1-indexed)") }
                    putJsonObject("column") { put("type", "integer"); put("description", "Column number (1-indexed)") }
                },
                required = listOf("filePath", "line", "column"),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val filePath = obj["filePath"]?.asJsonPrimitive?.asString ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing filePath"))
            val line = obj["line"]?.asJsonPrimitive?.asInt ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing line"))
            val column = obj["column"]?.asJsonPrimitive?.asInt ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing column"))
            val references = ideService.findReferences(filePath, line, column)
            if (references.size() > 0) {
                listOf(UIMessagePart.Text(references.joinToString("\n") { it.asJsonObject["path"]?.asString ?: it.toString() }))
            } else {
                listOf(UIMessagePart.Text("No references found at $filePath:$line:$column"))
            }
        },
    )

    private val formatDocument = Tool(
        name = "formatDocument",
        description = "Format a file using the LSP formatter. " +
            "Use after editing to ensure consistent code style matching project conventions. " +
            "Example: {\"filePath\": \"src/main.kt\"}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("filePath") { put("type", "string"); put("description", "Absolute path to the file to format") }
                },
                required = listOf("filePath"),
            )
        },
        execute = { args ->
            val filePath = args.asJsonObject["filePath"]?.asJsonPrimitive?.asString
                ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing 'filePath'."))
            try {
                ideService.formatDocument(filePath)
                listOf(UIMessagePart.Text("OK Formatted $filePath"))
            } catch (e: Exception) {
                listOf(UIMessagePart.Text("ERROR: Could not format $filePath: ${e.message}\nSUGGESTION: Check the file has a registered LSP formatter."))
            }
        },
    )

    val all: List<Tool> = listOf(getDiagnostics, findDefinitions, findReferences, formatDocument)
}
