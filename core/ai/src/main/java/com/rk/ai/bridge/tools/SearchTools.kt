package com.rk.ai.bridge.tools

import com.google.gson.JsonObject
import com.rk.ai.bridge.McpToolContext
import com.rk.ai.bridge.McpToolResult

class SearchCodeTool : BaseMcpTool() {
    override fun getCategory(): String = "Search"
    override fun getName(): String = "searchCode"
    override fun getDescription(): String = "Searches text patterns project-wide (plain text, non-regex). For regex searches use the 'grep' tool instead. Accepts: query, pattern, search, text."
    override fun getOptionalParams(): Map<String, String> = mapOf("query" to "string", "pattern" to "string", "search" to "string", "text" to "string", "limit" to "number", "path" to "string")
    override fun getOptionalParamDescriptions(): Map<String, String> = mapOf(
        "query" to "Text to search for",
        "pattern" to "Alternative to query",
        "search" to "Alternative to query",
        "text" to "Alternative to query",
        "limit" to "Maximum results to return (default: 50)",
        "path" to "Scope search to a specific directory"
    )
    override suspend fun executeValidated(args: JsonObject, context: McpToolContext): McpToolResult {
        val query = getQueryParam(args) ?: throw ToolError.MissingParam("query/pattern/search/text")
        val limit = (optionalPositiveInt(args, "limit") ?: 50).coerceIn(1, 500)
        val path = getPathParam(args)
        val results = context.ideService.searchCode(query, limit, path = path, isRegex = false)
        if (results.isEmpty()) return McpToolResult.success("No results found.")
        val sb = StringBuilder()
        results.forEach { el ->
            val obj = el.asJsonObject
            sb.append(obj.get("path")?.asString.orEmpty()).append(":").append(obj.get("line")?.asInt ?: 0).append(": ").append(obj.get("snippet")?.asString.orEmpty().trim()).append("\n")
        }
        return McpToolResult.success(sb.toString().trim())
    }
}

class GrepTool : BaseMcpTool() {
    override fun getCategory(): String = "Search"
    override fun getName(): String = "grep"
    override fun getDescription(): String = "Regex pattern search project-wide. For plain-text search use 'searchCode'. Accepts: query, pattern, search, text."
    override fun getOptionalParams(): Map<String, String> = mapOf("query" to "string", "pattern" to "string", "search" to "string", "text" to "string", "limit" to "number", "path" to "string")
    override fun getOptionalParamDescriptions(): Map<String, String> = mapOf(
        "query" to "Regex pattern to search for",
        "pattern" to "Alternative to query",
        "search" to "Alternative to query",
        "text" to "Alternative to query",
        "limit" to "Maximum results (default: 50, max: 1000)",
        "path" to "Scoped directory"
    )
    override suspend fun executeValidated(args: JsonObject, context: McpToolContext): McpToolResult {
        val query = getQueryParam(args) ?: throw ToolError.MissingParam("query/pattern/search/text")
        val limit = (optionalPositiveInt(args, "limit") ?: 50).coerceIn(1, 1000)
        val path = getPathParam(args)
        val results = context.ideService.searchCode(query, limit, path = path, isRegex = true)
        if (results.isEmpty()) return McpToolResult.success("No matches found.")
        val sb = StringBuilder()
        results.forEach { el ->
            val obj = el.asJsonObject
            sb.append(obj.get("path")?.asString.orEmpty()).append(":").append(obj.get("line")?.asInt ?: 0).append(": ").append(obj.get("snippet")?.asString.orEmpty().trim()).append("\n")
        }
        return McpToolResult.success(sb.toString().trim())
    }
}

class GrepSearchTool : BaseMcpTool() {
    override fun getCategory(): String = "Search"
    override fun getName(): String = "grepSearch"
    override fun getDescription(): String = "Alias for 'grep' tool. Supports regex text search. Use 'grep' or 'searchCode' instead for clarity."
    override fun getOptionalParams(): Map<String, String> = mapOf("query" to "string", "pattern" to "string", "limit" to "number", "path" to "string")
    override fun getOptionalParamDescriptions(): Map<String, String> = mapOf(
        "query" to "Text or regex to search for",
        "pattern" to "Alternative to query",
        "limit" to "Maximum results (default: 50, max: 1000)",
        "path" to "Scoped directory"
    )
    override suspend fun executeValidated(args: JsonObject, context: McpToolContext): McpToolResult {
        return GrepTool().executeValidated(args, context)
    }
}

class SearchSymbolsTool : BaseMcpTool() {
    override fun getCategory(): String = "Search"
    override fun getName(): String = "searchSymbols"
    override fun getDescription(): String = "Searches code declarations (classes, functions, variables). Faster and more precise than grep for finding definitions."
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
    override fun getDescription(): String = "Finds files by glob patterns like '*.kt' or '**/*.java'. Use this to locate files by name. Accepts: query, pattern, limit, path."
    override fun getOptionalParams(): Map<String, String> = mapOf("query" to "string", "pattern" to "string", "limit" to "number", "path" to "string")
    override fun getOptionalParamDescriptions(): Map<String, String> = mapOf(
        "query" to "File name or glob pattern to search for (e.g. *.kt, **/*.java)",
        "pattern" to "Alternative to query",
        "limit" to "Maximum results to return (default: 100)",
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

class GlobTool : BaseMcpTool() {
    override fun getCategory(): String = "Search"
    override fun getName(): String = "glob"
    override fun getDescription(): String = "Alias for 'findFiles'. Finds files by glob patterns. Prefer using 'findFiles' for clarity."
    override fun getOptionalParams(): Map<String, String> = mapOf("query" to "string", "pattern" to "string", "limit" to "number", "path" to "string")
    override fun getOptionalParamDescriptions(): Map<String, String> = mapOf(
        "query" to "File name or glob pattern to search for (e.g. *.kt, **/*.java)",
        "pattern" to "Alternative to query",
        "limit" to "Maximum results to return (default: 100)",
        "path" to "Directory to search in (default: workspace root)"
    )
    override suspend fun executeValidated(args: JsonObject, context: McpToolContext): McpToolResult {
        return FindFilesTool().executeValidated(args, context)
    }
}
