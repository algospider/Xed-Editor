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

class VibeCodingSearchTools(private val ideService: IdeService) {

    private val searchCode = Tool(
        name = "searchCode",
        description = "Search for text or regex patterns in the project. Returns file:line matches. Use isRegex=true for regex searches.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("query") { put("type", "string"); put("description", "Text or regex to search for") }
                    putJsonObject("limit") { put("type", "integer"); put("description", "Maximum results (default: 50, max: 1000)") }
                    putJsonObject("isRegex") { put("type", "boolean"); put("description", "Treat query as regex (default: false)") }
                    putJsonObject("path") { put("type", "string"); put("description", "Scope to a specific directory (optional)") }
                },
                required = listOf("query"),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val query = obj["query"]?.asJsonPrimitive?.asString ?: return@Tool listOf(UIMessagePart.Text("Missing query"))
            val limit = obj["limit"]?.asJsonPrimitive?.asInt ?: 50
            val isRegex = obj["isRegex"]?.asJsonPrimitive?.asBoolean ?: false
            val path = obj["path"]?.asJsonPrimitive?.asString
            val results = ideService.searchCode(query, limit, path, isRegex)
            if (results.size() > 0) {
                val text = results.joinToString("\n") { "${it.asJsonObject["path"]?.asString ?: "?"}:${it.asJsonObject["line"]?.asInt ?: 0}" }
                listOf(UIMessagePart.Text(text))
            } else {
                listOf(UIMessagePart.Text("No results found for: $query"))
            }
        },
    )

    private val searchSymbols = Tool(
        name = "searchSymbols",
        description = "Search code declarations — classes, functions, variables. Faster and more precise than searchCode for finding definitions by name.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("query") { put("type", "string"); put("description", "Symbol name to search for") }
                    putJsonObject("limit") { put("type", "integer"); put("description", "Maximum results (default: 50)") }
                    putJsonObject("path") { put("type", "string"); put("description", "Directory to scope search to (optional)") }
                },
                required = listOf("query"),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val query = obj["query"]?.asJsonPrimitive?.asString ?: return@Tool listOf(UIMessagePart.Text("Missing query"))
            val limit = obj["limit"]?.asJsonPrimitive?.asInt ?: 50
            val path = obj["path"]?.asJsonPrimitive?.asString
            val results = ideService.searchSymbols(query, limit, path)
            if (results.size() > 0) {
                val text = results.joinToString("\n") { "${it.asJsonObject["path"]?.asString ?: "?"}:${it.asJsonObject["line"]?.asInt ?: 0}" }
                listOf(UIMessagePart.Text(text))
            } else {
                listOf(UIMessagePart.Text("No symbols found for: $query"))
            }
        },
    )

    private val searchAndRead = Tool(
        name = "searchAndRead",
        description = "Search and read matching files in one call. Powerful combination: searches for text, then returns results with file contents inline. Use to minimize round-trips when exploring code.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("query") { put("type", "string"); put("description", "Text or regex pattern to search for") }
                    putJsonObject("limit") { put("type", "integer"); put("description", "Max search results (default: 10). Matching files are read fully.") }
                    putJsonObject("isRegex") { put("type", "boolean"); put("description", "Treat query as regex (default: false)") }
                    putJsonObject("path") { put("type", "string"); put("description", "Scope to a specific directory (optional)") }
                },
                required = listOf("query"),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val query = obj["query"]?.asJsonPrimitive?.asString ?: return@Tool listOf(UIMessagePart.Text("Missing query"))
            val limit = (obj["limit"]?.asJsonPrimitive?.asInt ?: 10).coerceIn(1, 50)
            val isRegex = obj["isRegex"]?.asJsonPrimitive?.asBoolean ?: false
            val path = obj["path"]?.asJsonPrimitive?.asString

            val results = ideService.searchCode(query, limit, path, isRegex)
            if (results.size() == 0) return@Tool listOf(UIMessagePart.Text("No results found for: $query"))

            val seenFiles = linkedSetOf<String>()
            val matchLines = results.mapNotNull { el ->
                val p = el.asJsonObject["path"]?.asString ?: return@mapNotNull null
                val l = el.asJsonObject["line"]?.asInt ?: 0
                seenFiles.add(p)
                "$p:$l"
            }

            val fileContents = seenFiles.mapNotNull { filePath ->
                val resolved = ideService.resolvePath(filePath)
                val absPath = resolved?.absolutePath ?: filePath
                val content = ideService.getFileContent(absPath, null, null)
                if (content != null) "--- $filePath ---\n$content" else null
            }

            val text = buildString {
                appendLine("Search results for: $query")
                appendLine("Matches: ${matchLines.joinToString(", ")}")
                appendLine()
                append(fileContents.joinToString("\n\n"))
            }
            listOf(UIMessagePart.Text(text))
        },
    )

    val all: List<Tool> = listOf(searchCode, searchSymbols, searchAndRead)
}
