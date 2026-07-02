package com.rk.ai.bridge.tools

import com.google.gson.JsonObject
import com.rk.ai.bridge.McpToolContext
import com.rk.ai.bridge.McpToolResult
import java.io.File

class DiffFilesTool : BaseMcpTool() {
    override fun getCategory(): String = "File Operations"
    override fun getName(): String = "diffFiles"
    override fun getDescription(): String = """Compare two files inline and show the diff.
Use INSTEAD of terminal diff — returns structured diff in ~5ms vs 50ms process spawn.
If only one filePath is given, compares against the content in 'newContent'.
Output uses unified diff format with @@ headers, ---/+++ markers, and +/- lines."""
    override fun getRequiredParams(): Map<String, String> = mapOf("filePath" to "string")
    override fun getOptionalParams(): Map<String, String> = mapOf(
        "filePath2" to "string",
        "newContent" to "string",
        "contextLines" to "number",
    )
    override fun getRequiredParamDescriptions(): Map<String, String> = mapOf("filePath" to "Path to the first file (original)")
    override fun getOptionalParamDescriptions(): Map<String, String> = mapOf(
        "filePath2" to "Path to the second file (modified). If omitted, compare filePath with newContent",
        "newContent" to "Content to compare filePath against (used when filePath2 is not provided)",
        "contextLines" to "Lines of context around changes (default: 3, max: 10)",
    )
    override suspend fun executeValidated(args: JsonObject, context: McpToolContext): McpToolResult {
        val filePath = requireString(args, "filePath")
        val file = resolvePathOrThrow(context, filePath)
        if (!file.isFile) throw ToolError.FileNotFound(filePath)

        val oldContent = try { file.readText() } catch (e: Exception) { return McpToolResult.error("Cannot read $filePath: ${e.message}") }

        val filePath2 = optionalString(args, "filePath2").ifBlank { null }
        val newContentParam = optionalString(args, "newContent").ifBlank { null }

        val newContent: String
        val newLabel: String

        if (filePath2 != null) {
            val file2 = resolvePathOrThrow(context, filePath2)
            if (!file2.isFile) throw ToolError.FileNotFound(filePath2)
            newContent = try { file2.readText() } catch (e: Exception) { return McpToolResult.error("Cannot read $filePath2: ${e.message}") }
            newLabel = filePath2
        } else if (newContentParam != null) {
            newContent = newContentParam
            newLabel = "(provided content)"
        } else {
            return McpToolResult.error("Either filePath2 or newContent must be provided.")
        }

        if (oldContent == newContent) {
            return McpToolResult.success("Files are identical.")
        }

        val contextLines = (optionalPositiveInt(args, "contextLines") ?: 3).coerceIn(0, 10)
        val diff = simpleDiff(filePath, newLabel, oldContent, newContent, contextLines)
        return McpToolResult.success(diff)
    }

    private fun simpleDiff(
        oldLabel: String,
        newLabel: String,
        oldContent: String,
        newContent: String,
        ctx: Int
    ): String {
        val oldLines = oldContent.lines()
        val newLines = newContent.lines()

        val sb = StringBuilder()
        sb.append("--- $oldLabel\n+++ $newLabel\n")

        val lcs = longestCommonSubsequence(oldLines, newLines)
        val hunks = extractChanges(lcs, oldLines, newLines, ctx)
        if (hunks.isEmpty()) return sb.append("(no changes)\n").toString()

        for (hunk in hunks) {
            sb.append(hunk)
        }
        return sb.toString().trimEnd()
    }

    private fun longestCommonSubsequence(a: List<String>, b: List<String>): Array<IntArray> {
        val m = a.size
        val n = b.size
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1] + 1
                else maxOf(dp[i - 1][j], dp[i][j - 1])
            }
        }
        return dp
    }

    private fun extractChanges(
        dp: Array<IntArray>,
        a: List<String>,
        b: List<String>,
        ctx: Int
    ): List<String> {
        val hunks = mutableListOf<String>()
        val changes = mutableListOf<Triple<Int, Int, String>>() // oldIdx, newIdx, type

        var i = a.size
        var j = b.size
        val ops = mutableListOf<Triple<Int, Int, String>>() // -1 for delete, -2 for insert, else index

        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && a[i - 1] == b[j - 1] -> { ops.add(Triple(i - 1, j - 1, "E")); i--; j-- }
                i > 0 && j > 0 && dp[i][j] == dp[i - 1][j] -> { ops.add(Triple(i - 1, -1, "D")); i-- }
                i > 0 && j > 0 -> { ops.add(Triple(-1, j - 1, "I")); j-- }
                i > 0 -> { ops.add(Triple(i - 1, -1, "D")); i-- }
                j > 0 -> { ops.add(Triple(-1, j - 1, "I")); j-- }
            }
        }
        ops.reverse()

        // Group into hunks with context
        var pos = 0
        while (pos < ops.size) {
            if (ops[pos].third == "E") { pos++; continue }

            val hunkStart = pos
            var hunkEnd = pos

            // Find end of this change block
            while (hunkEnd < ops.size) {
                hunkEnd++
                if (hunkEnd >= ops.size) break
                // Look ahead: if we see ctx * 2 or more equal lines, stop
                if (ops[hunkEnd].third == "E") {
                    var eqCount = 1
                    while (hunkEnd + eqCount < ops.size && ops[hunkEnd + eqCount].third == "E") eqCount++
                    if (eqCount > ctx * 2) break
                    hunkEnd += eqCount - 1
                }
            }
            if (hunkEnd > ops.size) hunkEnd = ops.size

            val startCtx = maxOf(0, hunkStart - ctx)
            val endCtx = minOf(ops.size, hunkEnd + ctx)

            // Build the hunk lines
            val hunkLines = mutableListOf<String>()
            for (k in startCtx until endCtx) {
                val (oi, ni, type) = ops[k]
                when (type) {
                    "E" -> hunkLines.add(" ${if (oi in a.indices) a[oi] else ""}")
                    "D" -> hunkLines.add("-${if (oi in a.indices) a[oi] else ""}")
                    "I" -> hunkLines.add("+${if (ni in b.indices) b[ni] else ""}")
                }
            }

            // Calculate @@ line numbers
            val firstChangeIdx = (hunkStart until endCtx).firstOrNull { ops[it].third != "E" } ?: hunkStart
            val (foi, fni, _) = ops[firstChangeIdx]
            val (loi, lni, _) = ops[minOf(ops.size - 1, endCtx - 1)]

            // Count lines in this hunk
            val oldCount = hunkLines.count { it.startsWith("-") || it.startsWith(" ") }
            val newCount = hunkLines.count { it.startsWith("+") || it.startsWith(" ") }
            val oldStart = (if (foi >= 0) foi + 1 else 1)
            val newStart = (if (fni >= 0) fni + 1 else 1)

            hunks.add("@@ -${oldStart},${oldCount} +${newStart},${newCount} @@\n" +
                hunkLines.joinToString("\n") + "\n")

            pos = endCtx
        }

        return hunks
    }
}
