package com.rk.ai.bridge.tools

import com.google.gson.JsonObject
import com.rk.ai.bridge.McpToolContext
import com.rk.ai.bridge.McpToolResult
import java.io.File

class EditFileTool : BaseMcpTool() {
    override fun getCategory(): String = "File Operations"
    override fun getName(): String = "editFile"
    override fun getDescription(): String =
        "Replace exact text in a file via find-and-replace. Preferred for targeted edits. " +
            "Provide enough surrounding context in oldString for a unique match. Use replaceAll=true to replace all occurrences."

    override fun getRequiredParams(): Map<String, String> = mapOf(
        "filePath" to "string", "oldString" to "string", "newString" to "string"
    )
    override fun getRequiredParamDescriptions(): Map<String, String> = mapOf(
        "filePath" to "Absolute path to the file to edit",
        "oldString" to "The exact text to find. Must match whitespace exactly. Include surrounding lines for uniqueness.",
        "newString" to "The replacement text. Can be empty to delete oldString."
    )
    override fun getOptionalParams(): Map<String, String> = mapOf(
        "path" to "string", "file" to "string",
        "replaceAll" to "boolean"
    )
    override fun getOptionalParamDescriptions(): Map<String, String> = mapOf(
        "path" to "Alternative to filePath",
        "file" to "Alternative to filePath",
        "replaceAll" to "Replace all occurrences (default: false)"
    )
    override fun getBlankRequiredParams(): Set<String> = setOf("newString")

    override suspend fun executeValidated(args: JsonObject, context: McpToolContext): McpToolResult {
        val filePath = requireString(args, "filePath")
        val oldString = requireString(args, "oldString")
        val newString = requireString(args, "newString", allowBlank = true)
        val replaceAll = optionalBoolean(args, "replaceAll")

        val file = resolvePathOrThrow(context, filePath)
        if (!file.exists()) throw ToolError.FileNotFound(filePath)

        val ideService = context.ideService
        val content = ideService.getFileContent(file.absolutePath)
            ?: runCatching { file.readText() }.getOrDefault("")

        if (content.isEmpty()) throw ToolError.InvalidParam("oldString", "file is empty")

        val index = content.indexOf(oldString)

        if (index == -1) {
            val oldTrimmed = oldString.trim()
            if (oldTrimmed.isNotBlank()) {
                val trimmedIndex = content.indexOf(oldTrimmed)
                if (trimmedIndex != -1 && content.indexOf(oldTrimmed, trimmedIndex + 1) == -1) {
                    val newContent = content.substring(0, trimmedIndex) + newString + content.substring(trimmedIndex + oldTrimmed.length)
                    return applyEdit(ideService, file, content, newContent, filePath)
                }
            }

            val similar = findSimilar(content, oldString)
            throw ToolError.InvalidParam("oldString",
                "text not found in ${file.name}.${if (similar.isNotEmpty()) " Did you mean:\n$similar" else ""}")
        }

        if (!replaceAll) {
            val nextIndex = content.indexOf(oldString, index + oldString.length)
            if (nextIndex != -1) {
                val occurrences = mutableListOf<Int>()
                var searchFrom = 0
                while (true) {
                    val idx = content.indexOf(oldString, searchFrom)
                    if (idx == -1) break
                    val lineNum = content.substring(0, idx).count { it == '\n' } + 1
                    occurrences.add(lineNum)
                    searchFrom = idx + 1
                }
                throw ToolError.InvalidParam("oldString",
                    "found ${occurrences.size} occurrences at lines: ${occurrences.joinToString(", ")}. " +
                        "Use replaceAll=true or include more context for a unique match.")
            }
        }

        val newContent = if (replaceAll) content.replace(oldString, newString) else content.replaceRange(index, index + oldString.length, newString)
        return applyEdit(ideService, file, content, newContent, filePath)
    }

    private suspend fun applyEdit(
        ideService: com.rk.ai.service.IdeService, file: File, oldContent: String, newContent: String, filePath: String
    ): McpToolResult {
        showPatchAndApply(ideService, file, newContent, "Review AI surgical edit")
        return McpToolResult.success("Edit opened in Xed Editor for ${file.absolutePath}. Results will be sent via notifications.")
    }

    private fun findSimilar(content: String, query: String, maxSuggestions: Int = 3): String {
        val lines = content.split("\n")
        val words = query.split(Regex("\\s+")).filter { it.length > 3 }
        if (words.isEmpty()) return ""

        val scored = lines.mapIndexed { i, line ->
            val matchCount = words.count { word -> line.contains(word, ignoreCase = true) }
            Pair(i + 1, matchCount)
        }.filter { it.second > 0 }.sortedByDescending { it.second }

        return scored.take(maxSuggestions).joinToString("\n") { (line, score) ->
            val excerpt = lines.getOrNull(line - 1)?.trim()?.take(120) ?: ""
            "  line $line: $excerpt"
        }
    }
}
