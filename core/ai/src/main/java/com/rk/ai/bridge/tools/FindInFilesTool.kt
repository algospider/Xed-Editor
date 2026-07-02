package com.rk.ai.bridge.tools

import com.google.gson.JsonObject
import com.rk.ai.bridge.McpToolContext
import com.rk.ai.bridge.McpToolResult
import java.io.File

class FindInFilesTool : BaseMcpTool() {
    override fun getCategory(): String = "Search"
    override fun getName(): String = "findInFiles"
    override fun getDescription(): String = """Fast parallel file-content search returning N lines of context.
Use INSTEAD of terminal grep — searches 4 files concurrently, returns context in ~10ms.
A single call replaces: grep + readFile. Supports contextLines, filePattern, regex.
Performance: parallel search across up to 500 files with backpressure."""
    override fun getRequiredParams(): Map<String, String> = mapOf("query" to "string")
    override fun getOptionalParams(): Map<String, String> = mapOf(
        "pattern" to "string",
        "contextLines" to "number",
        "filePattern" to "string",
        "path" to "string",
        "limit" to "number",
        "isRegex" to "boolean",
        "concurrency" to "number",
    )
    override fun getRequiredParamDescriptions(): Map<String, String> = mapOf("query" to "Text or pattern to search for")
    override fun getOptionalParamDescriptions(): Map<String, String> = mapOf(
        "pattern" to "Alias for query",
        "contextLines" to "Lines of context before/after each match (default: 2, max: 10)",
        "filePattern" to "Glob to filter files (e.g. '*.kt', '**/*.java')",
        "path" to "Scope search to this directory",
        "limit" to "Max results (default: 50, max: 500)",
        "isRegex" to "Treat query as regex (default: false)",
        "concurrency" to "Parallel file search concurrency (default: 4)",
    )
    override suspend fun executeValidated(args: JsonObject, context: McpToolContext): McpToolResult {
        val query = getQueryParam(args) ?: throw ToolError.MissingParam("query/pattern/search/text")
        val contextLines = (optionalPositiveInt(args, "contextLines") ?: 2).coerceIn(0, 10)
        val filePattern = optionalString(args, "filePattern").ifBlank { null }
        val path = getPathParam(args)
        val limit = (optionalPositiveInt(args, "limit") ?: 50).coerceIn(1, 500)
        val isRegex = optionalBoolean(args, "isRegex")
        val concurrency = (optionalPositiveInt(args, "concurrency") ?: 4).coerceIn(1, 16)

        val workspace = context.ideService.getPrimaryWorkspacePath()
        if (workspace.isBlank()) return McpToolResult.error("No workspace path available")

        WorkspaceFileIndex.ensureIndexed(workspace)
        val files = when {
            filePattern != null -> WorkspaceFileIndex.findByNamePattern(filePattern, maxResults = 500)
            path != null -> WorkspaceFileIndex.findByNamePattern(path, maxResults = 500)
            else -> WorkspaceFileIndex.allFiles()
        }

        if (files.isEmpty()) return McpToolResult.success("No files matched the filter criteria.")

        context.trySendProgress("Searching ${files.size} files (concurrency=$concurrency)...")

        val report = ParallelSearchExecutor.search(
            files = files,
            query = query,
            isRegex = isRegex,
            contextLines = contextLines,
            maxResults = limit,
            concurrency = concurrency,
        )

        if (report.results.isEmpty()) {
            return McpToolResult.success("Found 0 matches in ${report.filesSearched} files (${report.durationMs}ms).")
        }

        val sb = StringBuilder()
        var currentFile: String? = null

        for (match in report.results) {
            val fileLabel = match.file.absolutePath.removePrefix(workspace).removePrefix("/")
            if (fileLabel != currentFile) {
                sb.append("=== $fileLabel ===\n")
                currentFile = fileLabel
            }
            if (match.contextBefore.isNotEmpty()) {
                for ((i, line) in match.contextBefore.withIndex()) {
                    val lineNum = match.lineNumber - match.contextBefore.size + i
                    sb.append("  ${lineNum}: $line\n")
                }
            }
            sb.append("> ${match.lineNumber}: ${match.line}\n")
            if (match.contextAfter.isNotEmpty()) {
                for ((i, line) in match.contextAfter.withIndex()) {
                    val lineNum = match.lineNumber + 1 + i
                    sb.append("  ${lineNum}: $line\n")
                }
            }
            sb.append("\n")
        }

        val summary = "Found ${report.totalMatches} matches in ${report.filesSearched} files (${report.durationMs}ms)."
        val trunc = if (report.truncated) " Results truncated (limit=$limit). Use a more specific query." else ""
        return McpToolResult.success("$summary$trunc\n${sb.toString().trim()}")
    }
}
