package com.rk.ai.bridge.tools

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

data class ToolMetrics(
    val callCount: Long,
    val totalTimeMs: Long,
    val minTimeMs: Long,
    val maxTimeMs: Long,
    val errorCount: Long,
) {
    val avgTimeMs: Double get() = if (callCount > 0) totalTimeMs.toDouble() / callCount else 0.0
}

object ToolPerformanceTracker {

    private data class MutableMetrics(
        val callCount: AtomicLong = AtomicLong(0),
        val totalTimeMs: AtomicLong = AtomicLong(0),
        val minTimeMs: AtomicLong = AtomicLong(Long.MAX_VALUE),
        val maxTimeMs: AtomicLong = AtomicLong(0),
        val errorCount: AtomicLong = AtomicLong(0),
    )

    private val metrics = ConcurrentHashMap<String, MutableMetrics>()
    private val allTimeMetrics = ConcurrentHashMap<String, MutableMetrics>()

    fun record(toolName: String, durationMs: Long, success: Boolean) {
        val m = metrics.computeIfAbsent(toolName) { MutableMetrics() }
        m.callCount.incrementAndGet()
        m.totalTimeMs.addAndGet(durationMs)

        runCatching {
            while (true) {
                val cur = m.minTimeMs.get()
                if (durationMs >= cur) break
                if (m.minTimeMs.compareAndSet(cur, durationMs)) break
            }
        }
        runCatching {
            while (true) {
                val cur = m.maxTimeMs.get()
                if (durationMs <= cur) break
                if (m.maxTimeMs.compareAndSet(cur, durationMs)) break
            }
        }

        if (!success) m.errorCount.incrementAndGet()

        // Also update all-time
        val a = allTimeMetrics.computeIfAbsent(toolName) { MutableMetrics() }
        a.callCount.incrementAndGet()
        a.totalTimeMs.addAndGet(durationMs)
        runCatching {
            while (true) { val c = a.minTimeMs.get(); if (durationMs >= c) break; if (a.minTimeMs.compareAndSet(c, durationMs)) break }
        }
        runCatching {
            while (true) { val c = a.maxTimeMs.get(); if (durationMs <= c) break; if (a.maxTimeMs.compareAndSet(c, durationMs)) break }
        }
        if (!success) a.errorCount.incrementAndGet()
    }

    fun getSnapshot(): Map<String, ToolMetrics> {
        return metrics.mapValues { (_, m) ->
            ToolMetrics(
                callCount = m.callCount.get(),
                totalTimeMs = m.totalTimeMs.get(),
                minTimeMs = m.minTimeMs.get().let { if (it == Long.MAX_VALUE) 0L else it },
                maxTimeMs = m.maxTimeMs.get(),
                errorCount = m.errorCount.get(),
            )
        }
    }

    fun getAllTime(): Map<String, ToolMetrics> {
        return allTimeMetrics.mapValues { (_, m) ->
            ToolMetrics(
                callCount = m.callCount.get(),
                totalTimeMs = m.totalTimeMs.get(),
                minTimeMs = m.minTimeMs.get().let { if (it == Long.MAX_VALUE) 0L else it },
                maxTimeMs = m.maxTimeMs.get(),
                errorCount = m.errorCount.get(),
            )
        }
    }

    fun getToolMetrics(toolName: String): ToolMetrics? {
        val m = metrics[toolName] ?: return null
        return ToolMetrics(
            callCount = m.callCount.get(),
            totalTimeMs = m.totalTimeMs.get(),
            minTimeMs = m.minTimeMs.get().let { if (it == Long.MAX_VALUE) 0L else it },
            maxTimeMs = m.maxTimeMs.get(),
            errorCount = m.errorCount.get(),
        )
    }

    fun reset() { metrics.clear() }

    fun resetAll() { metrics.clear(); allTimeMetrics.clear() }
}
