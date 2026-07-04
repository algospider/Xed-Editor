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
        description = "Search for text or regex patterns across the project. Returns file:line matches. " +
            "Use for finding usages, error messages, log statements, or any text pattern. " +
            "For finding definitions by name (classes, functions), prefer searchSymbols (faster and more precise). " +
            "For search + read in one call, use searchAndRead. " +
            "Example: {\"query\": \"TODO\"} or {\"query\": \"fun \\\\w+Handler\", \"isRegex\": true}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("query") { put("type", "string"); put("description", "Text or regex pattern to search for") }
                    putJsonObject("limit") { put("type", "integer"); put("description", "Max results (default: 50, max: 1000)") }
                    putJsonObject("isRegex") { put("type", "boolean"); put("description", "Set true to treat query as regex (default: false)") }
                    putJsonObject("path") { put("type", "string"); put("description", "Scope search to a specific directory (optional)") }
                },
                required = listOf("query"),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val query = obj["query"]?.asJsonPrimitive?.asString
                ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing 'query' parameter. Example: {\"query\": \"class Foo\"}"))
            val limit = obj["limit"]?.asJsonPrimitive?.asInt ?: 50
            val isRegex = obj["isRegex"]?.asJsonPrimitive?.asBoolean ?: false
            val path = obj["path"]?.asJsonPrimitive?.asString
            val results = ideService.searchCode(query, limit, path, isRegex)
            if (results.size() > 0) {
                val text = results.joinToString("\n") { "${it.asJsonObject["path"]?.asString ?: "?"}:${it.asJsonObject["line"]?.asInt ?: 0}" }
                listOf(UIMessagePart.Text(text))
            } else {
                val suggestion = if (isRegex) "\nSUGGESTION: Try without isRegex for literal text search, or simplify the regex."
                    else "\nSUGGESTION: Try a different search term, partial match, or different case."
                listOf(UIMessagePart.Text("No results for: $query$suggestion"))
            }
        },
    )

    private val searchSymbols = Tool(
        name = "searchSymbols",
        description = "Search code DECLARATIONS — classes, functions, interfaces, variables by name. " +
            "FASTER and MORE PRECISE than searchCode for finding definitions. " +
            "Uses the LSP index for instant results. " +
            "Example: {\"query\": \"UserService\"} or {\"query\": \"ViewModel\", \"path\": \"src/features/\"}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("query") { put("type", "string"); put("description", "Symbol name to find (class, function, interface, variable name)") }
                    putJsonObject("limit") { put("type", "integer"); put("description", "Max results (default: 50)") }
                    putJsonObject("path") { put("type", "string"); put("description", "Scope to a specific directory (optional)") }
                },
                required = listOf("query"),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val query = obj["query"]?.asJsonPrimitive?.asString
                ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing 'query' parameter. Example: {\"query\": \"MyClass\"}"))
            val limit = obj["limit"]?.asJsonPrimitive?.asInt ?: 50
            val path = obj["path"]?.asJsonPrimitive?.asString
            val results = ideService.searchSymbols(query, limit, path)
            if (results.size() > 0) {
                val text = results.joinToString("\n") { "${it.asJsonObject["path"]?.asString ?: "?"}:${it.asJsonObject["line"]?.asInt ?: 0}" }
                listOf(UIMessagePart.Text(text))
            } else {
                listOf(UIMessagePart.Text("No symbols found for: $query\nSUGGESTION: Try searchCode instead for text matching, or use a broader symbol name."))
            }
        },
    )

    private val searchAndRead = Tool(
        name = "searchAndRead",
        description = "COMBINED: Search for text AND read matching files in one call. " +
            "Returns search results with full file contents inline — saves 2+ round-trips vs searchCode + readFiles. " +
            "Use when exploring unknown code: search a pattern and immediately see the surrounding context. " +
            "Example: {\"query\": \"class UserService\"} or {\"query\": \"TODO|FIXME\", \"isRegex\": true}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("query") { put("type", "string"); put("description", "Text or regex pattern to search for") }
                    putJsonObject("limit") { put("type", "integer"); put("description", "Max files to read (default: 5, max: 20)") }
                    putJsonObject("isRegex") { put("type", "boolean"); put("description", "Treat query as regex (default: false)") }
                    putJsonObject("path") { put("type", "string"); put("description", "Scope to a specific directory (optional)") }
                },
                required = listOf("query"),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val query = obj["query"]?.asJsonPrimitive?.asString
                ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing 'query' parameter."))
            val limit = (obj["limit"]?.asJsonPrimitive?.asInt ?: 5).coerceIn(1, 20)
            val isRegex = obj["isRegex"]?.asJsonPrimitive?.asBoolean ?: false
            val path = obj["path"]?.asJsonPrimitive?.asString
            val results = ideService.searchCode(query, limit * 3, path, isRegex)
            if (results.size() == 0) return@Tool listOf(UIMessagePart.Text("No results for: $query"))

            val seenFiles = linkedSetOf<String>()
            val matchLines = results.mapNotNull { el ->
                val p = el.asJsonObject["path"]?.asString ?: return@mapNotNull null
                seenFiles.add(p); "$p:${el.asJsonObject["line"]?.asInt ?: 0}"
            }
            val fileContents = seenFiles.take(limit).mapNotNull { filePath ->
                val resolved = ideService.resolvePath(filePath)
                val content = ideService.getFileContent(resolved?.absolutePath ?: filePath, null, null)
                if (content != null) "--- $filePath ---\n$content" else null
            }
            listOf(UIMessagePart.Text(buildString {
                appendLine("Search: $query")
                appendLine("Matches: ${matchLines.joinToString(", ")}")
                if (fileContents.isNotEmpty()) { appendLine(); append(fileContents.joinToString("\n\n")) }
            }))
        },
    )

    val all: List<Tool> = listOf(searchCode, searchSymbols, searchAndRead)
}
