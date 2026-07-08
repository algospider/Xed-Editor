@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)
package com.rk.ai.agent

import com.rk.ai.models.UIMessage
import com.rk.ai.models.UIMessagePart
import com.rk.ai.core.MessageRole

object CompactionHandler {
    private const val PRUNE_MINIMUM = 20_000
    private const val PRUNE_PROTECT = 40_000
    private const val TOOL_OUTPUT_MAX_CHARS = 4_000
    private val PRUNE_PROTECTED_TOOLS = setOf("skill", "use_skill", "memory_tool")
    private const val MIN_PRESERVE_RECENT_TOKENS = 4_000
    private const val MAX_PRESERVE_RECENT_TOKENS = 16_000
    private const val DEFAULT_TAIL_TURNS = 2
    private const val DOOM_LOOP_THRESHOLD = 3
    private const val PATTERN_WINDOW = 6
    private const val PATTERN_REPEAT_THRESHOLD = 2

    private var runningSummary: String? = null
    private var compactionGeneration = 0

    data class CompactionResult(
        val compactedMessages: List<UIMessage>,
        val summary: String?,
        val prunedCount: Int,
    )

    fun needsCompaction(
        messages: List<UIMessage>,
        contextWindow: Int,
        maxOutputTokens: Int,
    ): Boolean {
        return TokenEstimator.isOverflow(messages, contextWindow, maxOutputTokens)
    }

    fun createCompactionPrompt(
        conversation: List<UIMessage>,
        previousSummary: String? = null,
    ): String {
        val effectivePrev = previousSummary ?: runningSummary
        val anchor = if (effectivePrev != null) {
            """
Update the anchored summary below using the conversation history above.
Preserve still-true details, remove stale details, and merge in the new facts.
This is compaction generation #$compactionGeneration — each compaction MUST preserve all information from prior summaries.
<previous-summary>
$effectivePrev
</previous-summary>
""".trimIndent()
        } else {
            "Create a new anchored summary from the conversation history above."
        }

        return """
$anchor

Output exactly the Markdown structure shown inside <template> and keep the section order unchanged. Do not include the <template> tags in your response.
<template>
## Goal
- [single-sentence task summary]

## Constraints & Preferences
- [user constraints, preferences, specs, or "(none)"]

## Progress
### Done
- [completed work or "(none)"]

### In Progress
- [current work or "(none)"]

### Blocked
- [blockers or "(none)"]

## Key Decisions
- [decision and why, or "(none)"]

## Next Steps
- [ordered next actions or "(none)"]

## Critical Context
- [important technical facts, errors, open questions, or "(none)"]

## Relevant Files
- [file or directory path: why it matters, or "(none)"]
</template>

Rules:
- Keep every section, even when empty.
- Use terse bullets, not prose paragraphs.
- Preserve exact file paths, commands, error strings, and identifiers when known.
- Preserve build errors, compiler messages, and test failures verbatim.
- Do not mention the summary process or that context was compacted.

Conversation history to summarize:
${conversation.joinToString("\n") { m ->
    when (m.role) {
        MessageRole.USER -> "USER: ${m.toText()}"
        MessageRole.ASSISTANT -> "ASSISTANT: ${m.toText()}"
        MessageRole.SYSTEM -> "SYSTEM: ${m.toText()}"
        else -> "${m.role}: ${m.toText()}"
    }
}}
""".trimIndent()
    }

    fun pruneMessages(messages: List<UIMessage>): CompactionResult {
        if (messages.isEmpty()) return CompactionResult(messages, null, 0)

        val totalTokens = TokenEstimator.estimate(messages)
        if (totalTokens <= PRUNE_PROTECT) return CompactionResult(messages, null, 0)

        compactionGeneration++

        val budget = minOf(
            MAX_PRESERVE_RECENT_TOKENS,
            maxOf(MIN_PRESERVE_RECENT_TOKENS, totalTokens / 3)
        )

        val tailTurns = mutableListOf<UIMessage>()
        var tailTokens = 0
        for (msg in messages.reversed()) {
            val est = TokenEstimator.estimate(listOf(msg))
            if (tailTokens + est > budget && tailTurns.isNotEmpty()) break
            tailTokens += est
            tailTurns.add(msg)
        }
        tailTurns.reverse()

        if (tailTurns.isEmpty()) return CompactionResult(messages, null, 0)

        val tailStart = messages.indexOf(tailTurns.first())
        val headMessages = messages.subList(0, tailStart)

        var prunedCount = 0
        val compactedHead = headMessages.map { msg ->
            val tools = msg.getTools()
            if (tools.isEmpty()) return@map msg

            val hasLargeOutput = tools.any { tool ->
                val outputText = tool.output.joinToString("\n") { p ->
                    when (p) {
                        is UIMessagePart.Text -> p.text
                        else -> ""
                    }
                }
                val outputTokens = TokenEstimator.estimate(outputText)
                outputTokens > TOOL_OUTPUT_MAX_CHARS / 4
            }
            if (!hasLargeOutput) return@map msg

            prunedCount++
            val updatedParts = msg.parts.map { part ->
                if (part is UIMessagePart.Tool && part.isExecuted) {
                    val truncatedOutput = part.output.map { p ->
                        when (p) {
                            is UIMessagePart.Text -> {
                                val text = p.text
                                if (text.length > TOOL_OUTPUT_MAX_CHARS) {
                                    val (summary, keyLines) = smartTruncate(text, TOOL_OUTPUT_MAX_CHARS)
                                    UIMessagePart.Text(summary + "\n... [truncated]" + keyLines)
                                } else p
                            }
                            else -> p
                        }
                    }
                    part.copy(output = truncatedOutput)
                } else part
            }
            msg.copy(parts = updatedParts)
        }

        return CompactionResult(
            compactedMessages = compactedHead + tailTurns,
            summary = null,
            prunedCount = prunedCount,
        )
    }

    /**
     * Smart truncation that preserves the first portion, key diagnostic lines,
     * and error messages while cutting verbose middle content.
     */
    private fun smartTruncate(text: String, maxChars: Int): Pair<String, String> {
        val lines = text.lines()
        if (lines.size <= 3) return text.take(maxChars) to ""

        val keyLines = mutableListOf<String>()
        val midStart = (lines.size * 0.1).toInt().coerceAtLeast(1)
        val midEnd = (lines.size * 0.9).toInt().coerceAtMost(lines.size - 1)

        // Preserve error lines from the middle
        for (i in midStart until midEnd) {
            val line = lines[i]
            if (line.contains("error", ignoreCase = true) ||
                line.contains("exception", ignoreCase = true) ||
                line.contains("FAILED", ignoreCase = true) ||
                line.contains("warning:", ignoreCase = true) ||
                line.matches(Regex("^\\s*(at |Caused by|... \\d+ more)"))
            ) {
                keyLines.add(line)
            }
        }

        val head = lines.take(midStart).joinToString("\n").take(maxChars)
        val tailSuffix = if (keyLines.isNotEmpty()) {
            "\n\nKey diagnostics from truncated section:\n" + keyLines.distinct().take(10).joinToString("\n")
        } else ""

        return head to tailSuffix
    }

    fun detectDoomLoop(
        messages: List<UIMessage>,
        threshold: Int = DOOM_LOOP_THRESHOLD,
    ): String? {
        if (messages.isEmpty()) return null

        val recentToolCalls = messages.flatMap { msg ->
            msg.getTools().filter { it.isExecuted }
        }.takeLast(threshold)

        if (recentToolCalls.size < threshold) return null

        val toolEntry = mutableListOf<Pair<String, String>>()
        for (toolResult in recentToolCalls) {
            val key = toolResult.toolName to toolResult.input.take(200)
            toolEntry.add(key)
        }

        val allSameName = toolEntry.all { it.first == toolEntry.first().first }
        val allSameInput = toolEntry.all { it.second == toolEntry.first().second }
        if (allSameName && allSameInput) {
            return toolEntry.first().first
        }

        return null
    }

    fun detectPatternLoop(
        recentToolNameSequences: List<List<String>>,
    ): Boolean {
        if (recentToolNameSequences.size < 4) return false
        val half = recentToolNameSequences.size / 2
        val firstHalf = recentToolNameSequences.take(half).flatten()
        val secondHalf = recentToolNameSequences.drop(half).flatten()
        return firstHalf == secondHalf && firstHalf.isNotEmpty()
    }

    fun detectExcessiveReads(
        messages: List<UIMessage>,
        readTools: Set<String> = setOf("readFile", "readFiles", "readAndEdit"),
        maxReadsPerWindow: Int = 100,
        windowSize: Int = 50,
        maxReReadsOfSameFile: Int = 5,
    ): Boolean {
        val recentMessages = messages.takeLast(windowSize)
        val readTargets = mutableMapOf<String, Int>()
        var readCount = 0
        for (msg in recentMessages) {
            for (tool in msg.getTools().filter { it.toolName in readTools && it.isExecuted }) {
                readCount++
                val path = extractFilePath(tool.input)
                if (path != null) {
                    readTargets[path] = (readTargets[path] ?: 0) + 1
                }
            }
        }
        if (readCount <= maxReadsPerWindow) return false
        // Only flag it if the same file is being re-read many times (true waste)
        val maxReReads = readTargets.values.maxOrNull() ?: return false
        if (maxReReads < maxReReadsOfSameFile) return false
        return true
    }

    private fun extractFilePath(input: String): String? {
        val patterns = listOf(
            """"filePath"\s*:\s*"([^"]+)"""",
            """"path"\s*:\s*"([^"]+)"""",
            """"file"\s*:\s*"([^"]+)"""",
        )
        for (pattern in patterns) {
            val match = Regex(pattern).find(input)
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    fun buildRecoveryMessage(
        loopType: String,
        toolName: String,
        details: String = "",
    ): UIMessage {
        val message = when (loopType) {
            "doom_loop" -> """
[SYSTEM: The tool '$toolName' was called with the same input. Pivot to a different approach and continue.]

[RECOMMENDATION: $details]
""".trimIndent()

            "doom_loop_escalate" -> """
[SYSTEM: STUCK — tool '$toolName' keeps failing with the same approach. You MUST try a completely different strategy.
Previous approach is not working. Consider:
1. Use a different tool entirely
2. Break the problem into smaller steps
3. Read more context before retrying
4. Ask the user for clarification if the task is ambiguous]

[RECOMMENDATION: $details]
""".trimIndent()

            "doom_loop_abort" -> """
[SYSTEM: CRITICAL — repeated failures with '$toolName'. Stop retrying this approach.
Summarize what you've tried and what failed, then ask the user for guidance.
Do NOT attempt the same operation again.]

[DETAILS: $details]
""".trimIndent()

            "pattern_loop" -> """
[SYSTEM: Same tool sequence detected. Pivot to a different strategy and continue.]

[RECOMMENDATION: $details]
""".trimIndent()

            "excessive_reads" -> """
[SYSTEM NOTE: You've been reading many files. That's fine — use `readFiles` to batch multiple paths in one call for efficiency. Continue your work.]

[RECOMMENDATION: $details]
""".trimIndent()

            else -> """
[SYSTEM: The agent appears stuck. Try a different approach.
Call getGuidelines for a refresher on available tools and strategies.]

[RECOMMENDATION: $details]
""".trimIndent()
        }
        return UIMessage(
            role = MessageRole.SYSTEM,
            parts = listOf(UIMessagePart.Text(message)),
        )
    }

    fun escalateDoomLoopStrategy(
        escalationLevel: Int,
        failingTool: String,
        recentTools: List<String>,
    ): Pair<String, String> {
        return when {
            escalationLevel <= 0 -> "doom_loop" to
                "Tool '$failingTool' produced the same result. Try a different approach: " +
                "use a different tool, read more context first, or break the problem down differently."

            escalationLevel == 1 -> "doom_loop_escalate" to buildString {
                append("You've been stuck on '$failingTool' for multiple attempts. ")
                val alternatives = suggestAlternativeTools(failingTool)
                if (alternatives.isNotEmpty()) {
                    append("Consider using: ${alternatives.joinToString(", ")}. ")
                }
                append("If the file content doesn't match, read it first with readFile to see the actual content.")
            }

            else -> "doom_loop_abort" to
                "After ${escalationLevel + DOOM_LOOP_THRESHOLD} attempts with '$failingTool', " +
                "this approach is not working. Report what you've tried to the user and ask for guidance."
        }
    }

    private fun suggestAlternativeTools(failingTool: String): List<String> {
        return when (failingTool) {
            "editFile" -> listOf("readFile (verify content first)", "writeFile (full rewrite)", "multiEditFile")
            "writeFile" -> listOf("editFile (surgical edit)", "createFile")
            "readFile" -> listOf("readFiles (batch)", "searchAndRead", "searchCode")
            "searchCode" -> listOf("searchSymbols", "findFiles", "searchAndRead")
            "runCommand" -> listOf("readFile", "searchCode", "getDiagnostics")
            "findFiles" -> listOf("listFiles", "searchCode", "getProjectStructure")
            else -> emptyList()
        }
    }

    fun updateRunningSummary(summary: String) {
        runningSummary = summary
    }

    fun getRunningSummary(): String? = runningSummary

    fun resetCompactionState() {
        runningSummary = null
        compactionGeneration = 0
    }
}
