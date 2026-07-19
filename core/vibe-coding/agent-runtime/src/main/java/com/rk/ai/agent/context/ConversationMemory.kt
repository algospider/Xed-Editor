package com.rk.ai.agent.context

import com.rk.ai.core.MessageRole
import com.rk.ai.models.UIMessage

class ConversationMemory {
    private var currentGoal: String = ""
    private val preferences = mutableListOf<String>()
    private val previousInstructions = mutableListOf<String>()
    private val extractedFacts = mutableListOf<String>()

    fun updateFromMessages(messages: List<UIMessage>) {
        val lastUserMsg = messages.lastOrNull { it.role == MessageRole.USER }
        if (lastUserMsg != null) {
            currentGoal = lastUserMsg.toText().take(500)
        }
    }

    fun getCurrentGoal(): String = currentGoal

    fun addPreference(pref: String) {
        val trimmed = pref.trim()
        if (trimmed.isNotBlank() && trimmed !in preferences) {
            preferences.add(trimmed)
            if (preferences.size > 20) preferences.removeAt(0)
        }
    }

    fun getPreferences(): List<String> = preferences.toList()

    fun addInstruction(instruction: String) {
        val trimmed = instruction.trim()
        if (trimmed.isNotBlank() && trimmed !in previousInstructions) {
            previousInstructions.add(trimmed)
            if (previousInstructions.size > 10) previousInstructions.removeAt(0)
        }
    }

    fun getInstructions(): List<String> = previousInstructions.toList()

    /**
     * Adds a fact with semantic deduplication.
     *
     * A fact is skipped if an existing fact shares high word overlap,
     * preventing near-duplicate entries from bloating the context.
     */
    fun addFact(fact: String) {
        val trimmed = fact.trim()
        if (trimmed.isBlank()) return

        // Exact dedup
        if (trimmed in extractedFacts) return

        // Semantic dedup: skip if a very similar fact already exists
        // (high common-word overlap suggests they convey the same info)
        if (extractedFacts.any { existing -> isNearDuplicate(trimmed, existing) }) return

        extractedFacts.add(trimmed)
        if (extractedFacts.size > 50) extractedFacts.removeAt(0)
    }

    /**
     * Returns relevant facts for a query, scored by token overlap.
     * Scored by token overlap, then filtered to the top 15 and
     * further deduplicated against the query itself.
     */
    fun getRelevantFacts(query: String): List<String> {
        if (extractedFacts.isEmpty()) return emptyList()

        if (query.isBlank()) return extractedFacts.takeLast(10)

        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty()) return extractedFacts.takeLast(10)

        // Score each fact by token overlap with query
        val scored = extractedFacts
            .map { fact -> fact to scoreRelevance(queryTokens, fact) }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }

        // Take the top 15 most relevant
        return scored.take(15).map { it.first }
    }

    /**
     * Returns the most recent facts directly (used when there's no query context).
     */
    fun getRecentFacts(count: Int = 10): List<String> =
        extractedFacts.takeLast(count)

    /**
     * Checks if two fact strings are near-duplicates based on
     * high common-word overlap.
     */
    private fun isNearDuplicate(a: String, b: String): Boolean {
        if (a.length < 10 || b.length < 10) return false
        val tokensA = tokenize(a)
        val tokensB = tokenize(b)
        if (tokensA.isEmpty() || tokensB.isEmpty()) return false

        val smaller = if (tokensA.size <= tokensB.size) tokensA else tokensB
        val larger = if (tokensA.size <= tokensB.size) tokensB else tokensA
        val overlap = smaller.count { it in larger }
        val ratio = overlap.toDouble() / smaller.size
        // If >70% of the smaller fact's words appear in the larger one, they're near-duplicates
        return ratio > 0.7 && smaller.size >= 2
    }

    private fun tokenize(text: String): Set<String> {
        return text.lowercase()
            .split(Regex("[\\s,._\\-/(){}\\[\\]<>:;\"']+"))
            .filter { it.length >= 2 }
            .toSet()
    }

    private fun scoreRelevance(queryTokens: Set<String>, fact: String): Double {
        val factTokens = tokenize(fact)
        if (factTokens.isEmpty()) return 0.0
        val matches = queryTokens.count { qt -> factTokens.any { ft -> ft.contains(qt) || qt.contains(ft) } }
        if (matches == 0) return 0.0
        val coverage = matches.toDouble() / queryTokens.size
        val recency = 1.0 + (extractedFacts.indexOf(fact).toDouble() / extractedFacts.size.coerceAtLeast(1)) * 0.5
        return coverage * recency
    }

    fun clear() {
        currentGoal = ""
        preferences.clear()
        previousInstructions.clear()
        extractedFacts.clear()
    }
}
