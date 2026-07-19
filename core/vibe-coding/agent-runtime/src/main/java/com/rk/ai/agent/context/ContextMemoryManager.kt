package com.rk.ai.agent.context

/**
 * Approximate characters per token for context budgeting.
 * LLMs vary, but 4 chars/token is a reasonable conservative estimate.
 */
private const val CHARS_PER_TOKEN = 4

/** Default token budget for the context bundle injected into each prompt. */
private const val DEFAULT_MAX_CONTEXT_TOKENS = 3000

data class ContextBundle(
    val goal: String = "",
    val preferences: List<String> = emptyList(),
    val projectSummary: String = "",
    val projectStructure: String = "",
    val workingState: WorkingState = WorkingState(),
    val historical: List<String> = emptyList(),
    val relevantFiles: List<String> = emptyList(),
    val relevantSymbols: List<String> = emptyList(),
    val recentEdits: List<EditRecord> = emptyList(),
    val sessionLog: List<String> = emptyList(),
    val mentionedPaths: List<String> = emptyList(),
) {
    fun isEmpty(): Boolean = goal.isBlank() && projectSummary.isBlank()
    fun toPromptBlock(): String = buildString {
        if (goal.isNotBlank()) appendLine("Current goal: $goal")
        if (projectSummary.isNotBlank()) appendLine("Project: $projectSummary")
        if (projectStructure.isNotBlank()) {
            appendLine("Project structure:")
            appendLine(projectStructure.take(500))
        }
        if (relevantFiles.isNotEmpty()) appendLine("Active files: ${relevantFiles.joinToString(", ")}")
        if (relevantSymbols.isNotEmpty()) appendLine("Relevant symbols: ${relevantSymbols.joinToString(", ")}")
        if (mentionedPaths.isNotEmpty()) appendLine("Files mentioned by user: ${mentionedPaths.joinToString(", ")}")
        if (recentEdits.isNotEmpty()) {
            appendLine("Recent edits:")
            recentEdits.takeLast(5).forEach { appendLine("  - ${it.file} (${it.action})") }
        }
        if (historical.isNotEmpty()) {
            appendLine("Known facts:")
            historical.takeLast(8).forEach { appendLine("  - $it") }
        }
        if (sessionLog.isNotEmpty()) {
            appendLine("Session log:")
            sessionLog.takeLast(5).forEach { appendLine("  $it") }
        }
    }
}

class ContextMemoryManager(
    val conversation: ConversationMemory = ConversationMemory(),
    val project: ProjectMemory = ProjectMemory(),
    val working: WorkingMemory = WorkingMemory(),
) {
    /**
     * Builds a context bundle with token-aware budgeting.
     *
     * Sections are truncated in priority order when the total exceeds [maxContextTokens]:
     * 1. sessionLog (lowest priority) → 2. historical facts → 3. relevant files →
     * 4. projectStructure → 5. projectSummary (highest priority)
     */
    fun getBundle(
        query: String = "",
        maxContextTokens: Int = DEFAULT_MAX_CONTEXT_TOKENS,
    ): ContextBundle {
        val mentionedPaths = extractMentionedPaths(query)

        // Collect all raw data first
        val goal = conversation.getCurrentGoal()
        val preferences = conversation.getPreferences()
        val projectSummary = project.getCachedSummary()
        val projectStructure = project.getCachedStructure()
        val historicalAll = conversation.getRelevantFacts(query)
        val relevantFiles = if (query.isNotBlank()) project.findFiles(query) else emptyList()
        val relevantSymbols = project.findSymbol(query)
        val recentEdits = working.getState().recentEdits
        val sessionLogAll = working.getRecentLogs(15)

        // ── Token-aware budgeting ──────────────────────────────────────
        // Estimate tokens for each section (char count / 4)
        val goalTokens = estimateTokens(goal)
        val projectSummaryTokens = estimateTokens(projectSummary)
        val projectStructureTokens = estimateTokens(projectStructure)
        val relevantFilesTokens = estimateTokens(relevantFiles)
        val historicalTokens = estimateTokens(historicalAll)
        val sessionLogTokens = estimateTokens(sessionLogAll)

        val totalFixed = goalTokens + projectSummaryTokens
        val totalBeforeBudget = totalFixed + projectStructureTokens + relevantFilesTokens +
            historicalTokens + sessionLogTokens
        val remainingBudget = (maxContextTokens - totalFixed).coerceAtLeast(0)

        // Priority order: sessionLog < historical < relevantFiles < projectStructure
        var budget = remainingBudget

        // 1. Session log — lowest priority
        val sessionLog = if (sessionLogTokens <= budget) {
            budget -= sessionLogTokens
            sessionLogAll
        } else {
            truncateToBudget(sessionLogAll, budget).also { budget = 0 }
        }

        // 2. Historical facts
        val historical = if (historicalTokens <= budget) {
            budget -= historicalTokens
            historicalAll
        } else {
            truncateToBudget(historicalAll, budget).also { budget = 0 }
        }

        // 3. Relevant files (list of path strings)
        val trimmedRelevantFiles = if (relevantFilesTokens <= budget) {
            budget -= relevantFilesTokens
            relevantFiles
        } else {
            truncateToBudget(relevantFiles, budget).also { budget = 0 }
        }

        // 4. Project structure
        val trimmedProjectStructure = if (projectStructure.isBlank()) {
            projectStructure
        } else if (projectStructureTokens <= budget) {
            projectStructure
        } else {
            // Keep the first part of the structure (shows top-level layout)
            val maxChars = budget * CHARS_PER_TOKEN
            projectStructure.take(maxChars) + "\n... (truncated)"
        }

        return ContextBundle(
            goal = goal,
            preferences = preferences,
            projectSummary = projectSummary,
            projectStructure = trimmedProjectStructure,
            workingState = working.getState(),
            historical = historical,
            relevantFiles = trimmedRelevantFiles,
            relevantSymbols = relevantSymbols,
            recentEdits = recentEdits,
            sessionLog = sessionLog,
            mentionedPaths = mentionedPaths,
        )
    }

    // ── Token estimation helpers ──────────────────────────────────────

    /** Rough token estimate: chars / 4. */
    private fun estimateTokens(text: String): Int =
        (text.length / CHARS_PER_TOKEN) + 1

    private fun estimateTokens(items: List<String>): Int =
        items.sumOf { estimateTokens(it) } + items.size // +size for separators

    /**
     * Returns a suffix of [items] that fits within [budgetTokens].
     * Keeps the most recent entries (appended last) since they are most relevant.
     */
    private fun truncateToBudget(items: List<String>, budgetTokens: Int): List<String> {
        if (items.isEmpty()) return items
        val maxChars = budgetTokens * CHARS_PER_TOKEN
        var total = 0
        val result = mutableListOf<String>()
        // Iterate from the end (most recent) to keep the latest entries
        for (item in items.reversed()) {
            val cost = item.length + 1 // +1 for separator
            if (total + cost > maxChars) break
            total += cost
            result.add(item)
        }
        return result.reversed()
    }

    // ── Path extraction ───────────────────────────────────────────────

    fun extractMentionedPaths(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val paths = mutableListOf<String>()
        // Match file paths with extensions
        val pathPattern = Regex("""(?:^|\s|`|"|')([a-zA-Z0-9_./-]+\.[a-zA-Z0-9]{1,10})(?:\s|`|"|'|$|[,;:)]|\z)""")
        for (match in pathPattern.findAll(text)) {
            val path = match.groupValues[1]
            if (path.contains("/") || path.contains(".")) {
                val ext = path.substringAfterLast(".")
                if (ext !in setOf("com", "org", "net", "io", "dev", "app", "ai")) {
                    paths.add(path)
                }
            }
        }
        // Match backtick-quoted identifiers that look like filenames
        val backtickPattern = Regex("""`([^`]+\.[a-zA-Z]{1,10})`""")
        for (match in backtickPattern.findAll(text)) {
            val candidate = match.groupValues[1]
            if (candidate !in paths) paths.add(candidate)
        }
        return paths.distinct().take(10)
    }

    // ── Delegation methods ────────────────────────────────────────────

    fun storeProjectInfo(summary: String, structure: String) {
        project.setSummary(summary)
        project.setStructure(structure)
    }

    fun storeFileIndex(path: String, symbols: List<String>, lineCount: Int) {
        project.indexFile(path, symbols, lineCount)
    }

    fun storeSymbol(name: String, filePath: String) {
        project.indexSymbol(name, filePath)
    }

    fun recordEdit(file: String, action: String) {
        working.recordEdit(file, action)
    }

    fun log(message: String) {
        working.log(message)
    }

    fun addFact(fact: String) {
        conversation.addFact(fact)
    }

    fun addPreference(pref: String) {
        conversation.addPreference(pref)
    }

    fun clearAll() {
        conversation.clear()
        project.clear()
        working.clear()
    }
}
