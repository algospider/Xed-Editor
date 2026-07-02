package com.rk.ai.bridge.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.rk.ai.bridge.McpToolContext
import com.rk.ai.bridge.McpToolResult
import java.io.File

class BatchReplaceTool : BaseMcpTool() {
    override fun getCategory(): String = "File Editing"
    override fun getName(): String = "batchReplace"
    override fun getDescription(): String = """Multi-file search-and-replace in one atomic call. 
Use INSTEAD of: searchCode -> readFile -> editFile per-file loop.
Accepts either: (query + replacement + filePattern) for pattern-based replacement, OR
(edits) as a JSON array of {filePath, oldString, newString} objects for exact replacements.
Returns per-file success/error.
ANNOTATION: DESTRUCTIVE — not idempotent if retried."""
    override fun getOptionalParams(): Map<String, String> = mapOf(
        "query" to "string",
        "replacement" to "string",
        "filePattern" to "string",
        "edits" to "string",
        "isRegex" to "boolean",
        "dryRun" to "boolean"
    )
    override fun getOptionalParamDescriptions(): Map<String, String> = mapOf(
        "query" to "Text to find (required if not using 'edits')",
        "replacement" to "Text to replace each match with (required if using query)",
        "filePattern" to "Glob pattern to filter files (e.g. '*.kt'). Default: all files",
        "edits" to "JSON array of {filePath, oldString, newString} for exact replacements",
        "isRegex" to "Treat query as regex (default: false)",
        "dryRun" to "Show what would be replaced without applying (default: false)"
    )
    override suspend fun executeValidated(args: JsonObject, context: McpToolContext): McpToolResult {
        val dryRun = optionalBoolean(args, "dryRun")
        val editsJson = args.get("edits")

        if (editsJson != null && editsJson.isJsonArray) {
            return batchEditFiles(editsJson.asJsonArray, context, dryRun)
        }

        val query = getQueryParam(args) ?: throw ToolError.MissingParam("query or edits")
        val replacement = optionalString(args, "replacement").also {
            if (it.isBlank() && !dryRun) throw ToolError.MissingParam("replacement (or use dryRun=true to preview)")
        }
        val filePattern = optionalString(args, "filePattern").ifBlank { null }
        val isRegex = optionalBoolean(args, "isRegex")

        val workspace = context.ideService.getPrimaryWorkspacePath()
        if (workspace.isBlank()) return McpToolResult.error("No workspace path available")

        WorkspaceFileIndex.ensureIndexed(workspace)
        val files = if (filePattern != null) {
            WorkspaceFileIndex.findByNamePattern(filePattern, maxResults = 500)
        } else {
            WorkspaceFileIndex.allFiles()
        }

        val regex = if (isRegex) try { query.toRegex() } catch (e: Exception) { return McpToolResult.error("Invalid regex: ${e.message}") } else null
        val plainText = if (!isRegex) query else null

        val results = JsonArray()
        var totalReplacements = 0

        for (file in files) {
            if (!file.isFile || !file.canRead()) continue
            if (isBinaryFile(file)) continue

            val originalContent = try { file.readText() } catch (_: Exception) { continue }
            val newContent = if (isRegex) {
                originalContent.replace(regex!!, replacement)
            } else {
                originalContent.replace(plainText!!, replacement)
            }

            if (originalContent == newContent) continue

            val count = if (isRegex) regex!!.findAll(originalContent).count() else originalContent.split(plainText!!).size - 1
            totalReplacements += count
            val relPath = file.absolutePath.removePrefix(workspace).removePrefix("/")

            if (!dryRun) {
                try {
                    context.ideService.writeFile(file, newContent)
                    results.add(JsonObject().apply {
                        addProperty("filePath", relPath)
                        addProperty("status", "replaced")
                        addProperty("replacements", count)
                    })
                } catch (e: Exception) {
                    results.add(JsonObject().apply {
                        addProperty("filePath", relPath)
                        addProperty("status", "error")
                        addProperty("error", e.message ?: "unknown")
                    })
                }
            } else {
                results.add(JsonObject().apply {
                    addProperty("filePath", relPath)
                    addProperty("status", "would_replace")
                    addProperty("replacements", count)
                })
            }
        }

        val summary = if (dryRun) "Dry run: " else ""
        return McpToolResult.success("${summary}Found $totalReplacements replacements in ${results.size()} files.\n$results")
    }

    private suspend fun batchEditFiles(edits: JsonArray, context: McpToolContext, dryRun: Boolean): McpToolResult {
        val results = JsonArray()
        var successCount = 0

        for (i in 0 until edits.size()) {
            val edit = edits[i].takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val filePath = edit.get("filePath")?.asString ?: continue
            val oldString = edit.get("oldString")?.asString ?: continue
            val newString = edit.get("newString")?.asString ?: continue

            try {
                val file = resolvePathOrThrow(context, filePath)
                val originalContent = file.readText()
                if (!originalContent.contains(oldString)) {
                    results.add(JsonObject().apply {
                        addProperty("filePath", filePath)
                        addProperty("status", "skipped")
                        addProperty("reason", "oldString not found in file")
                    })
                    continue
                }
                val newContent = originalContent.replaceFirst(oldString, newString)
                if (originalContent == newContent) {
                    results.add(JsonObject().apply {
                        addProperty("filePath", filePath)
                        addProperty("status", "skipped")
                        addProperty("reason", "No change (replacement produced identical content)")
                    })
                    continue
                }
                if (!dryRun) {
                    context.ideService.writeFile(file, newContent)
                }
                results.add(JsonObject().apply {
                    addProperty("filePath", filePath)
                    addProperty("status", if (dryRun) "would_replace" else "replaced")
                })
                successCount++
            } catch (e: Exception) {
                results.add(JsonObject().apply {
                    addProperty("filePath", filePath)
                    addProperty("status", "error")
                    addProperty("error", e.message ?: "unknown")
                })
            }
        }

        return McpToolResult.success(
            "${if (dryRun) "Dry run: " else ""}Applied $successCount/${edits.size()} edits.\n$results"
        )
    }

    private fun isBinaryFile(file: File): Boolean {
        val extensions = setOf("png", "jpg", "jpeg", "gif", "bmp", "ico", "webp", "zip", "jar", "aar", "apk", "so", "dll", "class", "dex", "ttf", "otf", "woff", "woff2", "mp3", "mp4", "avi", "mkv", "pdf", "doc", "docx", "xls", "xlsx")
        return file.extension.lowercase() in extensions
    }
}
