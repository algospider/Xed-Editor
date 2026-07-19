@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.rk.ai.agent.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rk.ai.agent.deriveRecoveryHint
import com.rk.ai.service.IdeService
import kotlin.uuid.ExperimentalUuidApi

/**
 * Shared utility functions for file-related tools.
 * Extracted from [VibeCodingFileTools] for modularity and reusability.
 */
object FileToolUtils {

    fun extractPath(obj: JsonObject): String? {
        return obj["path"]?.asJsonPrimitive?.asString
            ?: obj["filePath"]?.asJsonPrimitive?.asString
            ?: obj["file"]?.asJsonPrimitive?.asString
            ?: obj["sourcePath"]?.asJsonPrimitive?.asString
            ?: obj["destPath"]?.asJsonPrimitive?.asString
            ?: obj["outputPath"]?.asJsonPrimitive?.asString
            ?: obj["target"]?.asJsonPrimitive?.asString
    }

    fun pathNotFoundError(rawPath: String, workspacePath: String?): String {
        val ws = workspacePath?.takeIf { it.isNotBlank() } ?: "none"
        return "ERROR: Path could not be resolved: '$rawPath'\n" +
            "Workspace: $ws\n" +
            "SUGGESTION: Use an absolute path or a path relative to workspace root. " +
            "Call getProjectStructure or listFiles to verify the path exists."
    }

    fun buildWorkspaceMsg(ideService: IdeService): String {
        val ws = ideService.getPrimaryWorkspacePath()
        return ws.takeIf { it.isNotBlank() }?.let { "Workspace: $it" } ?: "No workspace configured"
    }

    fun parseFilePaths(element: com.google.gson.JsonElement?): List<String> {
        if (element == null) return emptyList()
        if (element is JsonArray) {
            return element.mapNotNull { it.asJsonPrimitive?.asString?.trim() }.filter { it.isNotBlank() }
        }
        val raw = element.asJsonPrimitive?.asString?.trim() ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        if (raw.startsWith("[")) {
            return runCatching {
                val arr = JsonParser.parseString(raw).asJsonArray
                arr.mapNotNull { it.asJsonPrimitive?.asString?.trim() }.filter { it.isNotBlank() }
            }.getOrDefault(
                raw.removeSurrounding("[", "]").split(",").map { it.trim().removeSurrounding("\"") }.filter { it.isNotBlank() }
            )
        }
        return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }

    fun countMatches(text: String, search: String): Int {
        var count = 0
        var idx = 0
        while (true) {
            idx = text.indexOf(search, idx)
            if (idx == -1) break
            count++
            idx += search.length
        }
        return count
    }

    fun buildRecoveryMsg(error: String, toolName: String): String? {
        val hint = deriveRecoveryHint(toolName, error)
        return if (hint != null) "[RECOVERY] $error. ${hint.message}" else null
    }

    /**
     * Creates a minimal unified-diff-like preview string for human review.
     * Shows context lines around changes in a compact format.
     */
    fun createUnifiedDiff(fileName: String, oldContent: String, newContent: String): String {
        val oldLines = oldContent.lines()
        val newLines = newContent.lines()
        val diff = StringBuilder()
        diff.appendLine("--- a/$fileName")
        diff.appendLine("+++ b/$fileName")

        val CONTEXT_LINES = 2
        var idx = 0
        while (idx < oldLines.size || idx < newLines.size) {
            if (idx < oldLines.size && idx < newLines.size && oldLines[idx] == newLines[idx]) {
                idx++
                continue
            }
            // Found a change — collect the hunk
            val startOld = (idx - CONTEXT_LINES).coerceAtLeast(0)
            val startNew = (idx - CONTEXT_LINES).coerceAtLeast(0)
            var endOld = (idx + CONTEXT_LINES).coerceAtMost(oldLines.size)
            var endNew = (idx + CONTEXT_LINES).coerceAtMost(newLines.size)

            // Extend to include all contiguous changed lines
            while (endOld < oldLines.size || endNew < newLines.size) {
                if (endOld < oldLines.size && endNew < newLines.size && oldLines[endOld] == newLines[endNew]) break
                if (endOld < oldLines.size) endOld++
                if (endNew < newLines.size) endNew++
            }

            diff.appendLine("@@ -${startOld + 1},${endOld - startOld} +${startNew + 1},${endNew - startNew} @@")
            for (i in startOld until endOld) {
                if (i < oldLines.size) diff.appendLine("-${oldLines[i]}") else diff.appendLine("-")
            }
            for (i in startNew until endNew) {
                if (i < newLines.size) diff.appendLine("+${newLines[i]}") else diff.appendLine("+")
            }
            idx = maxOf(endOld, endNew)
        }
        return diff.toString().ifEmpty { "(no changes)" }
    }

    fun humanReadableSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var size = bytes.toDouble()
        for (unit in units) { size /= 1024.0; if (size < 1024.0) return "%.1f %s".format(size, unit) }
        return "%.1f PB".format(size)
    }
}
