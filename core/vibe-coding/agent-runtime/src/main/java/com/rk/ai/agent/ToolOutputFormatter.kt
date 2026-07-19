@file:OptIn(ExperimentalUuidApi::class)
package com.rk.ai.agent

import com.rk.ai.models.UIMessage
import com.rk.ai.models.UIMessagePart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.uuid.ExperimentalUuidApi

/**
 * Utility methods for formatting, truncating, and analyzing tool output.
 */
object ToolOutputFormatter {

    const val MAX_TOOL_OUTPUT_CHARS = 80_000
    private const val TOOL_OUTPUT_TRUNCATION_SUFFIX =
        "\n\n[Output truncated at $MAX_TOOL_OUTPUT_CHARS characters]"

    // ── File path extraction ────────────────────────────────────────

    fun extractFilePath(input: String): String? {
        val patterns = listOf(
            Regex("""filePath["\s:=]+([^"\,}\s]+)"""),
            Regex("""path["\s:=]+([^"\,}\s]+)"""),
            Regex("""file["\s:=]+([^"\,}\s]+)"""),
        )
        for (pattern in patterns) {
            val match = pattern.find(input)
            if (match != null) {
                val path = match.groupValues[1].trim()
                if (path.startsWith("/") || path.contains(".")) return path
            }
        }
        return null
    }

    // ── Diff preview ────────────────────────────────────────────────

    fun computeDiffPreview(toolName: String, args: String, json: Json): String? {
        try {
            val obj = json.parseToJsonElement(args.ifBlank { "{}" }).jsonObject
            when (toolName) {
                "writeFile" -> {
                    val filePath = obj["filePath"]?.jsonPrimitive?.content ?: return null
                    val newContent = obj["content"]?.jsonPrimitive?.content ?: return null
                    val fileName = filePath.substringAfterLast("/")
                    return "--- a/$fileName\n+++ b/$fileName\n@@ Entire File Content @@\n" +
                           newContent.take(2000) + if(newContent.length > 2000) "\n... [truncated]" else ""
                }
                "editFile" -> {
                    val filePath = obj["filePath"]?.jsonPrimitive?.content ?: return null
                    val oldString = obj["oldString"]?.jsonPrimitive?.content ?: return null
                    val newString = obj["newString"]?.jsonPrimitive?.content ?: return null
                    val fileName = filePath.substringAfterLast("/")
                    return "--- a/$fileName\n+++ b/$fileName\n@@ Edit chunk @@\n" +
                           oldString.lines().joinToString("\n") { "-$it" } + "\n" +
                           newString.lines().joinToString("\n") { "+$it" }
                }
                "multiEditFile" -> {
                    val filePath = obj["filePath"]?.jsonPrimitive?.content ?: return null
                    val fileName = filePath.substringAfterLast("/")
                    val edits = obj["edits"]?.jsonArray ?: return null
                    var diff = "--- a/$fileName\n+++ b/$fileName\n"
                    for (i in 0 until edits.size) {
                        val edit = edits[i].jsonObject
                        val oldString = edit["oldString"]?.jsonPrimitive?.content ?: ""
                        val newString = edit["newString"]?.jsonPrimitive?.content ?: ""
                        diff += "@@ Edit chunk ${i+1} @@\n" +
                                oldString.lines().joinToString("\n") { "-$it" } + "\n" +
                                newString.lines().joinToString("\n") { "+$it" } + "\n"
                    }
                    return diff
                }
            }
        } catch (_: Exception) { }
        return null
    }

    // ── Finish reason check ─────────────────────────────────────────

    fun checkFinishReason(messages: List<UIMessage>, finishReason: String?): Boolean {
        if (messages.isEmpty()) return false
        if (finishReason == "length") return true
        val lastMsg = messages.lastOrNull() ?: return false
        val lastPart = lastMsg.parts.lastOrNull()
        return lastPart is UIMessagePart.Text && lastPart.text.contains("[length]")
    }

    // ── Smart truncation ────────────────────────────────────────────

    fun smartTruncateToolOutput(text: String, maxChars: Int = MAX_TOOL_OUTPUT_CHARS): String {
        val headBudget = (maxChars * 0.6).toInt()
        val tailBudget = (maxChars * 0.2).toInt()
        val diagBudget = maxChars - headBudget - tailBudget

        val lines = text.lines()
        if (lines.size <= 10) return text.take(maxChars) + TOOL_OUTPUT_TRUNCATION_SUFFIX

        val headLines = mutableListOf<String>()
        var headChars = 0
        for (line in lines) {
            if (headChars + line.length > headBudget && headLines.isNotEmpty()) break
            headLines.add(line)
            headChars += line.length + 1
        }

        val tailLines = mutableListOf<String>()
        var tailChars = 0
        for (line in lines.reversed()) {
            if (tailChars + line.length > tailBudget && tailLines.isNotEmpty()) break
            tailLines.add(line)
            tailChars += line.length + 1
        }
        tailLines.reverse()

        val middleStart = headLines.size
        val middleEnd = lines.size - tailLines.size
        val diagLines = mutableListOf<String>()
        var diagChars = 0
        if (middleStart < middleEnd) {
            for (i in middleStart until middleEnd) {
                val line = lines[i]
                if (line.contains("error", ignoreCase = true) ||
                    line.contains("exception", ignoreCase = true) ||
                    line.contains("FAILED", ignoreCase = true) ||
                    line.contains("warning:", ignoreCase = true) ||
                    line.matches(Regex("^\\s*(at |Caused by|\\d+\\))"))) {
                    if (diagChars + line.length <= diagBudget) {
                        diagLines.add(line)
                        diagChars += line.length + 1
                    }
                }
            }
        }

        val omitted = middleEnd - middleStart - diagLines.size
        return buildString {
            append(headLines.joinToString("\n"))
            appendLine()
            appendLine("\n[... $omitted lines omitted ...]")
            if (diagLines.isNotEmpty()) {
                appendLine("\n[Key lines from omitted section:]")
                append(diagLines.joinToString("\n"))
                appendLine()
            }
            append(tailLines.joinToString("\n"))
            append(TOOL_OUTPUT_TRUNCATION_SUFFIX)
        }
    }
}
