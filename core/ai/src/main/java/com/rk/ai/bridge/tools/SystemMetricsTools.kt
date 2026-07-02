package com.rk.ai.bridge.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.rk.ai.bridge.McpToolContext
import com.rk.ai.bridge.McpToolResult

class ToolMetricsTool : BaseMcpTool() {
    override fun getCategory(): String = "System"
    override fun getName(): String = "toolMetrics"
    override fun getDescription(): String = """Performance metrics for all MCP tools. 
Returns per-tool: call count, avg/min/max duration, error count, cache stats.
Use this to identify slow tools and optimize workflows.
Accepts optional 'toolName' to filter a single tool, or 'reset' to clear session metrics."""
    override fun getOptionalParams(): Map<String, String> = mapOf(
        "toolName" to "string",
        "reset" to "boolean",
    )
    override fun getOptionalParamDescriptions(): Map<String, String> = mapOf(
        "toolName" to "Filter metrics to a specific tool name",
        "reset" to "Reset session metrics (default: false)",
    )
    override suspend fun executeValidated(args: JsonObject, context: McpToolContext): McpToolResult {
        if (optionalBoolean(args, "reset")) {
            ToolPerformanceTracker.reset()
            return McpToolResult.success("Session metrics reset.")
        }

        val toolName = optionalString(args, "toolName").ifBlank { null }
        val metrics = if (toolName != null) {
            val m = ToolPerformanceTracker.getToolMetrics(toolName)
            if (m == null) return McpToolResult.success("No metrics for '$toolName'.")
            mapOf(toolName to m)
        } else {
            ToolPerformanceTracker.getSnapshot()
        }

        val sb = StringBuilder()
        sb.append("Tool Performance Metrics (session):\n")
        sb.append("─".repeat(80)).append("\n")
        sb.append("%-24s %7s %10s %8s %8s %7s\n".format("Tool", "Calls", "Avg(ms)", "Min(ms)", "Max(ms)", "Errors"))
        sb.append("─".repeat(80)).append("\n")

        val sorted = metrics.entries.sortedByDescending { it.value.totalTimeMs }
        for ((name, m) in sorted) {
            sb.append("%-24s %7d %10.1f %8d %8d %7d\n".format(
                name.take(24), m.callCount, m.avgTimeMs, m.minTimeMs, m.maxTimeMs, m.errorCount
            ))
        }
        sb.append("─".repeat(80)).append("\n")
        sb.append("Index: ${WorkspaceFileIndex.fileCount()} files indexed\n")

        return McpToolResult.success(sb.toString().trimEnd())
    }
}

class CacheStatsTool : BaseMcpTool() {
    override fun getCategory(): String = "System"
    override fun getName(): String = "cacheStats"
    override fun getDescription(): String = """Shows content cache and workspace index statistics.
Use to monitor cache efficiency and memory usage."""
    override suspend fun executeValidated(args: JsonObject, context: McpToolContext): McpToolResult {
        val workspace = context.ideService.getPrimaryWorkspacePath()
        if (workspace.isNotBlank()) {
            WorkspaceFileIndex.ensureIndexed(workspace)
        }
        val result = JsonObject().apply {
            addProperty("indexedFiles", WorkspaceFileIndex.fileCount())
            addProperty("workspacePath", WorkspaceFileIndex.getWorkspacePath())
        }
        return McpToolResult.success(result.toString())
    }
}
