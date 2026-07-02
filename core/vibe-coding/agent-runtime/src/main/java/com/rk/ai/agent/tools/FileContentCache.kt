package com.rk.ai.agent.tools

import java.io.File
import java.util.LinkedHashMap

class FileContentCache(
    private val maxEntries: Int = 200,
    private val ttlMs: Long = 30_000L,
) {
    private data class Entry(val content: String, val timestamp: Long)
    private val cache = LinkedHashMap<String, Entry>(maxEntries, 0.75f, true)

    @Synchronized
    fun get(path: String): String? {
        val normalized = normalize(path)
        val entry = cache[normalized] ?: return null
        if (System.currentTimeMillis() - entry.timestamp > ttlMs) {
            cache.remove(normalized)
            return null
        }
        return entry.content
    }

    @Synchronized
    fun put(path: String, content: String) {
        val key = normalize(path)
        if (cache.size >= maxEntries) {
            cache.remove(cache.keys.first())
        }
        cache[key] = Entry(content, System.currentTimeMillis())
    }

    @Synchronized
    fun invalidate(path: String) {
        cache.remove(normalize(path))
    }

    @Synchronized
    fun invalidateAll() {
        cache.clear()
    }

    val stats: String get() {
        return "File Content Cache: ${cache.size}/$maxEntries entries"
    }

    private fun normalize(path: String): String = File(path).absolutePath
}
