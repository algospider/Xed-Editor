package com.rk.ai.agent.review

data class ActionRecord(
    val toolName: String,
    val inputHash: Int,
    val timestamp: Long = System.currentTimeMillis(),
    val outputHash: Int? = null,
)

data class LoopInfo(
    val pattern: String,
    val description: String,
    val severity: LoopSeverity,
    val suggestion: String,
)

enum class LoopSeverity { WARNING, CRITICAL }

/**
 * Detects infinite loops and wasteful tool-call patterns in the agent's execution.
 *
 * Detection strategies (in order):
 * 1. Exact repeat — same tool + same input hash ≥3 times
 * 2. Near repeat — same tool with similar-but-different inputs ≥4 times
 * 3. Read–write cycle — alternating between reading and writing the same file
 * 4. Stagnation — many tool calls with no file modifications
 * 5. Pattern repeat — same sequence of 2-4 tool names repeated
 * 6. Oscillation — A→B→A→B alternation
 * 7. Excessive project reads — project-structure tools called too frequently
 */
class InfiniteLoopDetector(
    private val windowSize: Int = 12,
    private val repeatThreshold: Int = 3,
) {
    private val actionHistory = mutableListOf<ActionRecord>()

    /** Number of file-modifying tool calls seen so far this generation. */
    private var writeCount = 0

    /** Set of file-modifying tool names. */
    private val writeTools = setOf(
        "editFile", "writeFile", "createFile", "deleteFile", "renameFile",
        "multiEditFile", "applyBatchEdits", "readAndEdit",
    )

    /** Project-orientation tools that are cheap to call repeatedly. */
    private val projectOrientTools = setOf(
        "getProjectStructure", "getProjectSummary", "getProjectConfig",
        "listFiles", "ls", "indexCodebase",
    )

    fun record(action: ActionRecord) {
        actionHistory.add(action)
        if (action.toolName in writeTools) writeCount++
        while (actionHistory.size > windowSize * 3) {
            actionHistory.removeAt(0)
        }
    }

    fun recordWrite() {
        writeCount++
    }

    fun detect(): LoopInfo? {
        if (actionHistory.size < 4) return null

        // Most severe detections first
        detectExactRepeat()?.let { return it }
        detectNearRepeat()?.let { return it }
        detectReadWriteCycle()?.let { return it }
        detectStagnation()?.let { return it }
        detectPatternRepeat()?.let { return it }
        detectOscillation()?.let { return it }
        detectExcessiveProjectReads()?.let { return it }

        return null
    }

    // ── Exact repeat: same tool + same input hash ≥ threshold ─────────

    private fun detectExactRepeat(): LoopInfo? {
        val recent = actionHistory.takeLast(windowSize)
        val toolGroups = recent.groupBy { it.toolName }
        for ((toolName, calls) in toolGroups) {
            if (calls.size >= repeatThreshold) {
                val inputHashes = calls.map { it.inputHash }.distinct()
                if (inputHashes.size == 1) {
                    return LoopInfo(
                        pattern = toolName,
                        description = "Tool '$toolName' called ${calls.size}x with same input",
                        severity = LoopSeverity.CRITICAL,
                        suggestion = buildExactRepeatSuggestion(toolName),
                    )
                }
            }
        }
        return null
    }

    // ── Near repeat: same tool called repeatedly with different args ──

    private fun detectNearRepeat(): LoopInfo? {
        val recent = actionHistory.takeLast(windowSize)
        val toolGroups = recent.groupBy { it.toolName }
        for ((toolName, calls) in toolGroups) {
            if (calls.size >= 4) {
                return LoopInfo(
                    pattern = toolName,
                    description = "Tool '$toolName' called ${calls.size}x in last $windowSize actions — consider batching or switching approach",
                    severity = if (toolName in projectOrientTools) LoopSeverity.WARNING else LoopSeverity.CRITICAL,
                    suggestion = buildNearRepeatSuggestion(toolName, calls.size),
                )
            }
        }
        return null
    }

    // ── Read–write cycle: alternating read/write on likely same file ──

    private fun readTools = setOf("readFile", "readFiles", "readAndEdit", "cat", "getFileContent")

    private fun detectReadWriteCycle(): LoopInfo? {
        val recent = actionHistory.takeLast(8).map { it.toolName }
        if (recent.size < 4) return null

        // Pattern: read, write, read, write
        val readPattern = listOf(true, false, true, false)
        val isReadWrite = recent.takeLast(4).map { it in readTools || it in writeTools }
        if (isReadWrite == readPattern) {
            val nonReadWrite = recent.filter { it !in readTools && it !in writeTools }
            return LoopInfo(
                pattern = "read-write-cycle",
                description = "Alternating between read and write tools — reads may be wasted if you just edited the file",
                severity = LoopSeverity.WARNING,
                suggestion = "You keep reading a file immediately after editing it. Read the file ONCE before editing, " +
                    "then verify with getDiagnostics afterward instead of re-reading. If you need the current state, " +
                    "remember what you just wrote.",
            )
        }
        return null
    }

    // ── Stagnation: many tool calls but no file modifications ──────────

    private fun detectStagnation(): LoopInfo? {
        val recentCalls = actionHistory.takeLast(windowSize).size
        if (recentCalls >= 8 && writeCount == 0) {
            return LoopInfo(
                pattern = "stagnation",
                description = "$recentCalls tool calls with zero file modifications — no progress made",
                severity = LoopSeverity.WARNING,
                suggestion = "You've called many tools without making any changes. Either: " +
                    "(1) read the files you need and start editing, " +
                    "(2) call getProjectSummary to re-orient, or " +
                    "(3) if you're stuck, use the 'askUser' tool to get clarification.",
            )
        }
        // Ratio-based: too many reads per write
        val writeRatioThreshold = 10
        if (writeCount > 0) {
            val readCount = actionHistory.count { it.toolName in readTools }
            if (readCount / writeCount >= writeRatioThreshold && readCount >= writeRatioThreshold) {
                return LoopInfo(
                    pattern = "excessive-reads-per-write",
                    description = "$readCount reads vs $writeCount writes — reading much more than writing",
                    severity = LoopSeverity.WARNING,
                    suggestion = "You're doing $readCount reads per edit. Use readFiles to batch reads " +
                        "and readAndEdit to read+edit in one call. Once you've read a file, " +
                        "cache its content mentally instead of re-reading.",
                )
            }
        }
        return null
    }

    // ── Pattern repeat: same 2-4 tool sequence repeated ──────────────

    private fun detectPatternRepeat(): LoopInfo? {
        val recent = actionHistory.takeLast(windowSize)
        if (recent.size < 6) return null

        for (patternLen in 2..4) {
            if (recent.size >= patternLen * 2) {
                val first = recent.take(patternLen).map { it.toolName }
                val second = recent.drop(patternLen).take(patternLen).map { it.toolName }
                if (first == second) {
                    return LoopInfo(
                        pattern = first.joinToString("->"),
                        description = "Tool pattern repeated: ${first.joinToString(" → ")}",
                        severity = LoopSeverity.WARNING,
                        suggestion = buildPatternRepeatSuggestion(first),
                    )
                }
            }
        }
        return null
    }

    // ── Oscillation: A→B→A→B alternation ─────────────────────────────

    private fun detectOscillation(): LoopInfo? {
        val recent = actionHistory.takeLast(6).map { it.toolName }
        if (recent.size < 4) return null
        if (recent[0] == recent[2] && recent[1] == recent[3]) {
            val oscillationTools = listOf(recent[0], recent[1])
            return LoopInfo(
                pattern = "${recent[0]}↔${recent[1]}",
                description = "Oscillating between '${recent[0]}' and '${recent[1]}'",
                severity = LoopSeverity.WARNING,
                suggestion = buildOscillationSuggestion(oscillationTools),
            )
        }
        return null
    }

    // ── Excessive project reads ───────────────────────────────────────

    private fun detectExcessiveProjectReads(): LoopInfo? {
        val recent = actionHistory.takeLast(windowSize)
        val projectCalls = recent.filter { it.toolName in projectOrientTools }
        if (projectCalls.size >= 4) {
            return LoopInfo(
                pattern = projectCalls.joinToString(", ") { it.toolName },
                description = "Project structure read ${projectCalls.size}x in last $windowSize calls — you already have this information",
                severity = LoopSeverity.WARNING,
                suggestion = "You've called project-orientation tools ${projectCalls.size} times. " +
                    "You already know the project structure. Use the information you have and " +
                    "start working on the task instead of re-reading the project layout.",
            )
        }
        return null
    }

    // ── Recovery suggestion builders ──────────────────────────────────

    private fun buildExactRepeatSuggestion(toolName: String): String {
        return when (toolName) {
            "readFile" -> "You keep reading the same file. Cache its content and use it. If you need different data, use searchAndRead or searchCode."
            "editFile" -> "editFile keeps failing. Try: (1) read the file first to see current content, (2) use writeFile for a full rewrite, or (3) try a different approach entirely."
            "readFiles" -> "You keep reading the same files. Use readAndEdit if you need to edit, or searchAndRead if you need to find something."
            "searchCode" -> "searchCode keeps returning the same results. Try searchSymbols or findFiles instead, or read the files directly."
            "runCommand" -> "The command keeps failing. Check what it outputs, fix the issue, or use a native tool instead."
            else -> "Tool '$toolName' was called repeatedly with the same input. Stop and try a completely different approach."
        }
    }

    private fun buildNearRepeatSuggestion(toolName: String, count: Int): String {
        return when (toolName) {
            "readFile" -> "Called readFile $count times. Batch these with readFiles(['path1', 'path2', ...]) in ONE call, or use searchAndRead to find+read in one step."
            "runCommand" -> "Called runCommand $count times. Most operations have faster native tools — check the tool list. If you need output, try one well-crafted command instead of many small ones."
            "searchCode" -> "Called searchCode $count times. Try searchSymbols or searchAndRead instead, or combine queries into one broader search."
            "findFiles" -> "Called findFiles $count times. You already know the project structure from getProjectSummary. Read specific files directly."
            else -> "Called '$toolName' $count times. Batch independent operations with the 'parallel' meta-tool, or consolidate into fewer calls."
        }
    }

    private fun buildPatternRepeatSuggestion(pattern: List<String>): String {
        val unique = pattern.distinct()
        return when {
            unique.size == 1 -> "You're calling '${unique.first()}' in a loop. Use parallel tool or consolidate."
            unique.any { it in writeTools } -> "You're cycling through ${unique.joinToString(", ")}. Plan your edits before executing — one read can inform multiple edits."
            else -> "You're repeating the pattern: ${pattern.joinToString(" → ")}. Try a different strategy or use parallel for independent calls."
        }
    }

    private fun buildOscillationSuggestion(tools: List<String>): String {
        val (a, b) = tools
        return when {
            a in readTools && b in readTools -> "You're alternating between '$a' and '$b'. Both are read tools — use readFiles to call them together."
            a in writeTools && b in readTools -> "You're writing then reading in a loop. Read ONCE, write, then verify with getDiagnostics instead of re-reading."
            else -> "You're alternating between '$a' and '$b'. Decide which one you need and use it, or use parallel to call both at once."
        }
    }

    // ── Utility ───────────────────────────────────────────────────────

    fun clear() {
        actionHistory.clear()
        writeCount = 0
    }
}
