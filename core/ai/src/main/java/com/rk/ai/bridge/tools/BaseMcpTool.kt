package com.rk.ai.bridge.tools

import com.google.gson.JsonObject
import com.rk.ai.bridge.McpTool
import com.rk.ai.bridge.McpToolContext
import com.rk.ai.bridge.McpToolResult
import java.io.File

abstract class BaseMcpTool : McpTool {

    @Volatile private var cachedRequiredKeys: Set<String>? = null
    @Volatile private var cachedOptionalKeys: Set<String>? = null

    companion object {
        const val DEFAULT_MAX_LENGTH = 10_485_760
    }

    override suspend fun execute(args: JsonObject, context: McpToolContext): McpToolResult {
        val startNanos = System.nanoTime()
        var success = false
        try {
            validateRequired(args)
            val result = executeValidated(args, context)
            success = result.success
            return result.copy(durationMs = nanosToMs(System.nanoTime() - startNanos))
        } catch (e: ToolError) {
            return McpToolResult.error(e.message, duration = nanosToMs(System.nanoTime() - startNanos))
        } catch (e: Exception) {
            return McpToolResult.error("${e::class.java.simpleName}: ${e.message ?: "internal error"}",
                duration = nanosToMs(System.nanoTime() - startNanos))
        } finally {
            ToolPerformanceTracker.record(getName(), nanosToMs(System.nanoTime() - startNanos), success)
        }
    }

    internal abstract suspend fun executeValidated(args: JsonObject, context: McpToolContext): McpToolResult

    override fun getTimeoutMs(): Long = 60_000L

    protected open fun getBlankRequiredParams(): Set<String> = emptySet()

    protected fun requireString(
        args: JsonObject,
        name: String,
        maxLength: Int = DEFAULT_MAX_LENGTH,
        allowBlank: Boolean = false,
    ): String {
        val value = args.get(name)?.asString.orEmpty().take(maxLength)
        if (!allowBlank && value.isBlank()) throw ToolError.MissingParam(name)
        return value
    }

    protected fun requireInt(args: JsonObject, name: String): Int {
        return args.get(name)?.takeIf { it.isJsonPrimitive }?.asInt
            ?: throw ToolError.MissingParam(name)
    }

    protected fun requireBoolean(args: JsonObject, name: String): Boolean {
        return args.get(name)?.takeIf { it.isJsonPrimitive }?.asBoolean
            ?: throw ToolError.MissingParam(name)
    }

    protected fun optionalString(args: JsonObject, name: String, default: String = "", maxLength: Int = DEFAULT_MAX_LENGTH): String {
        return args.get(name)?.takeIf { it.isJsonPrimitive }?.asString?.take(maxLength) ?: default
    }

    protected fun optionalInt(args: JsonObject, name: String, default: Int? = null): Int? {
        val value = args.get(name)?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asInt
        return value ?: default
    }

    protected fun optionalPositiveInt(args: JsonObject, name: String, default: Int? = null): Int? {
        val value = args.get(name)?.takeIf { it.isJsonPrimitive && !it.isJsonNull }?.asInt
        return if (value != null && value > 0) value else default
    }

    protected fun optionalBoolean(args: JsonObject, name: String, default: Boolean = false): Boolean {
        return args.get(name)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: default
    }

    protected fun optionalLong(args: JsonObject, name: String, default: Long = 0L): Long {
        return args.get(name)?.takeIf { it.isJsonPrimitive }?.asLong ?: default
    }

    protected fun getPathParam(args: JsonObject): String? {
        return args.get("path")?.asString
            ?: args.get("filePath")?.asString
            ?: args.get("file")?.asString
            ?: args.get("name")?.asString
    }

    protected fun getContentParam(args: JsonObject): String? {
        return args.get("content")?.asString
            ?: args.get("text")?.asString
            ?: args.get("newContent")?.asString
    }

    protected fun getQueryParam(args: JsonObject): String? {
        return args.get("query")?.asString
            ?: args.get("pattern")?.asString
            ?: args.get("search")?.asString
            ?: args.get("text")?.asString
    }

    protected fun resolvePathOrThrow(context: McpToolContext, path: String): File {
        val ideService = context.ideService
        val resolved = ideService.resolvePath(path)
        if (resolved != null) return resolved

        val workspace = ideService.getPrimaryWorkspacePath()
        if (workspace.isBlank()) {
            throw ToolError.PathOutsideWorkspace("'$path' not found or outside workspace.")
        }

        WorkspaceFileIndex.ensureIndexed(workspace)

        val nameOnly = path.substringAfterLast("/").substringAfterLast("\\")
        val matches = WorkspaceFileIndex.findByNamePattern(nameOnly, maxResults = 5)
        when {
            matches.isEmpty() -> {
                val prefix = WorkspaceFileIndex.findByNamePattern(path, maxResults = 5)
                if (prefix.isEmpty()) {
                    throw ToolError.PathOutsideWorkspace("'$path' not found. No matching files in workspace.")
                }
                throw ToolError.PathOutsideWorkspace("'$path' not found. Did you mean one of these?\n${prefix.joinToString("\n") { it.absolutePath }}")
            }
            matches.size == 1 -> return matches.single()
            else -> {
                val exactLower = path.lowercase()
                val exact = matches.filter { it.name.lowercase() == exactLower || it.absolutePath.lowercase() == exactLower }
                if (exact.size == 1) return exact.single()
                throw ToolError.PathOutsideWorkspace("'$path' ambiguous. Did you mean one of these?\n${matches.joinToString("\n") { it.absolutePath }}")
            }
        }
    }

    protected fun buildJsonResult(data: JsonObject): McpToolResult = McpToolResult.success(data.toString())
    protected fun buildJsonResult(block: JsonObject.() -> Unit): McpToolResult = buildJsonResult(JsonObject().apply(block))

    private fun requiredKeys(): Set<String> {
        val cached = cachedRequiredKeys
        if (cached != null) return cached
        val keys = getRequiredParams().keys.toSet()
        cachedRequiredKeys = keys
        return keys
    }

    private fun validateRequired(args: JsonObject) {
        requiredKeys().forEach { name ->
            val value = args.get(name)
            if (value == null || value.isJsonNull) throw ToolError.MissingParam(name)
            if (name !in getBlankRequiredParams() && value.isJsonPrimitive && value.asString.isBlank()) {
                throw ToolError.MissingParam(name)
            }
        }
    }

    private fun nanosToMs(nanos: Long): Long = nanos / 1_000_000
}
