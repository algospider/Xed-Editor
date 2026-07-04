package com.rk.ai.agent.tools

import com.rk.ai.models.UIMessagePart
import java.util.concurrent.ConcurrentHashMap

data class CachedToolResult(
    val result: List<UIMessagePart>,
    val timestamp: Long,
    val hitCount: Int = 0,
)

class ToolCache(
    private val maxEntries: Int = 100,
    private val ttlMs: Long = 120_000L,
) {
    private data class Entry(
        val result: List<UIMessagePart>,
        val timestamp: Long,
        var hitCount: Int = 0,
    )

    private val cache = ConcurrentHashMap<String, Entry>()
    private val accessOrder = mutableListOf<String>()

    private val READ_TOOLS = setOf(
        "getProjectStructure", "getProjectSummary", "getProjectConfig",
        "listFiles", "getOpenFiles", "searchCode",
        "searchSymbols", "searchAndRead", "getProjectInstructions", "searchProjectInstructions",
        "indexCodebase", "semanticSearch", "getGuidelines",
        "getEnvironment", "getIdeInfo",
    )

    fun get(toolName: String, argsHash: String): List<UIMessagePart>? {
        if (!isCacheable(toolName)) return null
        val key = makeKey(toolName, argsHash)
        val entry = cache[key] ?: return null
        if (System.currentTimeMillis() - entry.timestamp > ttlMs) {
            cache.remove(key)
            synchronized(accessOrder) { accessOrder.remove(key) }
            return null
        }
        entry.hitCount++
        synchronized(accessOrder) {
            accessOrder.remove(key)
            accessOrder.add(key)
        }
        return entry.result
    }

    fun put(toolName: String, argsHash: String, result: List<UIMessagePart>) {
        if (!isCacheable(toolName)) return
        val key = makeKey(toolName, argsHash)
        if (cache.size >= maxEntries) {
            synchronized(accessOrder) {
                if (accessOrder.isNotEmpty()) {
                    val oldest = accessOrder.removeFirst()
                    cache.remove(oldest)
                }
            }
        }
        cache[key] = Entry(result, System.currentTimeMillis())
        synchronized(accessOrder) {
            accessOrder.remove(key)
            accessOrder.add(key)
        }
    }

    fun invalidate(toolName: String) {
        val prefix = "$toolName:"
        val toRemove = cache.keys.filter { it.startsWith(prefix) }
        toRemove.forEach { 
            cache.remove(it)
            synchronized(accessOrder) { accessOrder.remove(it) }
        }
    }

    fun invalidateAll() { 
        cache.clear()
        synchronized(accessOrder) { accessOrder.clear() }
    }

    fun invalidateProjectCache() {
        for (tool in setOf("getProjectStructure", "getProjectSummary", "getProjectConfig", "listFiles", "indexCodebase")) {
            invalidate(tool)
        }
    }

    val stats: String get() = buildString {
        appendLine("Tool Cache: ${cache.size}/$maxEntries entries, TTL: ${ttlMs/1000}s")
        if (cache.isEmpty()) {
            appendLine("(empty)")
            return@buildString
        }
        val totalHits = cache.values.sumOf { it.hitCount }
        appendLine("Total hits: $totalHits")
        val sorted = cache.entries.sortedByDescending { it.value.hitCount }
        val hitRatio = if (totalHits > 0) "100%" else "0%"
        appendLine("Most-used entries:")
        sorted.take(5).forEach { (key, entry) ->
            val ageSec = (System.currentTimeMillis() - entry.timestamp) / 1000
            appendLine("  $key: ${entry.hitCount} hits, ${ageSec}s old")
        }
    }

    private fun makeKey(toolName: String, argsHash: String): String = "$toolName:$argsHash"

    private fun isCacheable(name: String): Boolean = name in READ_TOOLS
}
