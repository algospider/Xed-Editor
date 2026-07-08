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

    fun addFact(fact: String) {
        val trimmed = fact.trim()
        if (trimmed.isNotBlank() && trimmed !in extractedFacts) {
            extractedFacts.add(trimmed)
            if (extractedFacts.size > 50) extractedFacts.removeAt(0)
        }
    }

    fun getRelevantFacts(query: String): List<String> {
        if (query.isBlank()) return extractedFacts.takeLast(10)
        val queryTokens = tokenize(query)
        if (queryTokens.isEmpty()) return extractedFacts.takeLast(10)

        return extractedFacts
            .map { fact -> fact to scoreRelevance(queryTokens, fact) }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(15)
            .map { it.first }
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
