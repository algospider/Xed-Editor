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
        description = "COMBINED: Search for text AND read matching file sections in one call. " +
            "Returns context windows around each match (±15 lines) instead of full file content — " +
            "saves 2+ round-trips vs searchCode + readFiles, and is far more efficient for large files. " +
            "Use when exploring unknown code: search a pattern and immediately see surrounding context. " +
            "Example: {\"query\": \"class UserService\"} or {\"query\": \"TODO|FIXME\", \"isRegex\": true}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("query") { put("type", "string"); put("description", "Text or regex pattern to search for") }
                    putJsonObject("limit") { put("type", "integer"); put("description", "Max files to read (default: 5, max: 20)") }
                    putJsonObject("contextLines") { put("type", "integer"); put("description", "Lines of context around each match (default: 15, max: 50)") }
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
            val contextLines = (obj["contextLines"]?.asJsonPrimitive?.asInt ?: 15).coerceIn(1, 50)
            val isRegex = obj["isRegex"]?.asJsonPrimitive?.asBoolean ?: false
            val path = obj["path"]?.asJsonPrimitive?.asString
            val results = ideService.searchCode(query, limit * 3, path, isRegex)
            if (results.size() == 0) return@Tool listOf(UIMessagePart.Text("No results for: $query"))

            // Group matches by file with their line numbers
            val fileMatches = linkedMapOf<String, MutableList<Int>>()
            results.forEach { el ->
                val p = el.asJsonObject["path"]?.asString
                val ln = el.asJsonObject["line"]?.asInt ?: 0
                if (p != null) fileMatches.getOrPut(p) { mutableListOf() }.add(ln)
            }

            val matchLines = fileMatches.flatMap { (f, lines) ->
                lines.map { "$f:$it" }
            }

            val fileContents = fileMatches.entries.take(limit).mapNotNull { (filePath, lines) ->
                val resolved = ideService.resolvePath(filePath) ?: return@mapNotNull null
                val absPath = resolved.absolutePath
                // Read context windows around each match (±contextLines lines)
                val sorted = lines.sorted().distinct()
                val windows = mutableListOf<String>()
                for (ln in sorted) {
                    val start = (ln - contextLines).coerceAtLeast(1)
                    val end = ln + contextLines
                    val snippet = ideService.getFileContent(absPath, start, end)
                    if (snippet != null) {
                        windows.add("... L$start (match at L$ln)")
                        windows.add(snippet.trimEnd())
                        windows.add("...")
                    }
                }
                if (windows.isNotEmpty()) {
                    "--- $filePath (${sorted.size} matches, context: ±${contextLines}L) ---\n${windows.joinToString("\n")}"
                } else {
                    // Fallback: read the whole file if context reads fail
                    val full = ideService.getFileContent(absPath, null, null)
                    if (full != null) "--- $filePath ---\n$full" else null
                }
            }
            listOf(UIMessagePart.Text(buildString {
                appendLine("Search: $query")
                appendLine("Matches (${matchLines.size}): ${matchLines.joinToString(", ")}")
                if (fileContents.isNotEmpty()) { appendLine(); append(fileContents.joinToString("\n\n")) }
            }))
        },
    )

    val all: List<Tool> = listOf(searchCode, searchSymbols, searchAndRead)
}
