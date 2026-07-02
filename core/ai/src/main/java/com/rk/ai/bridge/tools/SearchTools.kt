package com.rk.ai.bridge.tools

import com.google.gson.JsonObject
import com.rk.ai.bridge.McpToolContext
import com.rk.ai.bridge.McpToolResult

class SearchCodeTool : BaseMcpTool() {
    override fun getCategory(): String = "Search"
    override fun getName(): String = "searchCode"
    override fun getDescription(): String = "Search for text or regex patterns in the project. Returns file:line matches with snippets."
    override fun getOptionalParams(): Map<String, String> = mapOf(
        "query" to "string", "pattern" to "string", "search" to "string", "text" to "string",
        "limit" to "number", "path" to "string", "isRegex" to "boolean"
    )
    override fun getOptionalParamDescriptions(): Map<String, String> = mapOf(
        "query" to "Text or regex to search for",
        "pattern" to "Alternative to query",
        "search" to "Alternative to query",
        "text" to "Alternative to query",
        "limit" to "Maximum results (default: 50)",
        "path" to "Scope search to a specific directory",
        "isRegex" to "Use regex if true (default: false)"
    )
    override suspend fun executeValidated(args: JsonObject, context: McpToolContext): McpToolResult {
        val query = getQueryParam(args) ?: throw ToolError.InvalidParam("query", "one of query/pattern/search/text is required")
        val limit = (optionalPositiveInt(args, "limit") ?: 50).coerceIn(1, 500)
        val path = getPathParam(args)
        val isRegex = optionalBoolean(args, "isRegex")
        val results = context.ideService.searchCode(query, limit, path = path, isRegex = isRegex)
        if (results.isEmpty()) return McpToolResult.success("No results found.")
        val sb = StringBuilder()
        results.forEach { el ->
            val obj = el.asJsonObject
            sb.append(obj.get("path")?.asString.orEmpty()).append(":").append(obj.get("line")?.asInt ?: 0).append(": ").append(obj.get("snippet")?.asString.orEmpty().trim()).append("\n")
        }
        return McpToolResult.success(sb.toString().trim())
    }
}

class SearchSymbolsTool : BaseMcpTool() {
    override fun getCategory(): String = "Search"
    override fun getName(): String = "searchSymbols"
    override fun getDescription(): String = "Search code declarations (classes, functions, variables). Faster and more precise than searchCode for finding definitions by name."
    override fun getOptionalParams(): Map<String, String> = mapOf("query" to "string", "pattern" to "string", "symbol" to "string", "limit" to "number", "path" to "string")
    override fun getOptionalParamDescriptions(): Map<String, String> = mapOf(
        "query" to "Symbol name to search for",
        "pattern" to "Alternative to query",
        "symbol" to "Alternative to query",
        "limit" to "Maximum results (default: 50)",
        "path" to "Scope search to a specific directory"
    )
    override suspend fun executeValidated(args: JsonObject, context: McpToolContext): McpToolResult {
        val query = getQueryParam(args) ?: throw ToolError.MissingParam("query/pattern/symbol")
        val limit = (optionalPositiveInt(args, "limit") ?: 50).coerceIn(1, 500)
        val path = getPathParam(args)
        val results = context.ideService.searchSymbols(query, limit, path = path)
        if (results.isEmpty()) return McpToolResult.success("No symbols found.")
        val sb = StringBuilder()
        results.forEach { el ->
            val obj = el.asJsonObject
            sb.append(obj.get("path")?.asString.orEmpty()).append(":").append(obj.get("line")?.asInt ?: 0).append(": ").append(obj.get("snippet")?.asString.orEmpty().trim()).append("\n")
        }
        return McpToolResult.success(sb.toString().trim())
    }
}

class FindFilesTool : BaseMcpTool() {
    override fun getCategory(): String = "Search"
    override fun getName(): String = "findFiles"
    override fun getDescription(): String = "Find files by glob pattern (e.g. '*.kt' or '**/*.java'). Returns matching file paths."
    override fun getOptionalParams(): Map<String, String> = mapOf("query" to "string", "pattern" to "string", "limit" to "number", "path" to "string")
    override fun getOptionalParamDescriptions(): Map<String, String> = mapOf(
        "query" to "File name or glob pattern (e.g. *.kt, **/*.java)",
        "pattern" to "Alternative to query",
        "limit" to "Maximum results (default: 100)",
        "path" to "Directory to search in (default: workspace root)"
    )
    override suspend fun executeValidated(args: JsonObject, context: McpToolContext): McpToolResult {
        val query = getQueryParam(args) ?: throw ToolError.MissingParam("query/pattern")
        val limit = (optionalPositiveInt(args, "limit") ?: 100).coerceIn(1, 1000)
        val path = getPathParam(args)
        val results = context.ideService.findFiles(query, limit, path)
        if (results.isEmpty()) return McpToolResult.success("No files found.")
        val sb = StringBuilder()
        results.forEach { el ->
            sb.append(el.asJsonObject.get("path")?.asString).append("\n")
        }
        return McpToolResult.success(sb.toString().trim())
    }
}
