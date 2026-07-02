package com.rk.ai.bridge.tools

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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
        updateMetrics(metrics, toolName, durationMs, success)
        updateMetrics(allTimeMetrics, toolName, durationMs, success)
    }

    private fun updateMetrics(
        map: ConcurrentHashMap<String, MutableMetrics>,
        toolName: String,
        durationMs: Long,
        success: Boolean,
    ) {
        val m = map.computeIfAbsent(toolName) { MutableMetrics() }
        m.callCount.incrementAndGet()
        m.totalTimeMs.addAndGet(durationMs)

        casMin(m.minTimeMs, durationMs)
        casMax(m.maxTimeMs, durationMs)

        if (!success) m.errorCount.incrementAndGet()
    }

    private fun casMin(field: AtomicLong, candidate: Long) {
        while (true) {
            val cur = field.get()
            if (candidate >= cur) break
            if (field.compareAndSet(cur, candidate)) break
        }
    }

    private fun casMax(field: AtomicLong, candidate: Long) {
        while (true) {
            val cur = field.get()
            if (candidate <= cur) break
            if (field.compareAndSet(cur, candidate)) break
        }
    }

    fun getSnapshot(): Map<String, ToolMetrics> = toMetricsMap(metrics)

    fun getAllTime(): Map<String, ToolMetrics> = toMetricsMap(allTimeMetrics)

    fun getToolMetrics(toolName: String): ToolMetrics? {
        val m = metrics[toolName] ?: return null
        return toMetrics(toolName, m)
    }

    fun reset() { metrics.clear() }

    fun resetAll() { metrics.clear(); allTimeMetrics.clear() }

    private fun toMetricsMap(source: ConcurrentHashMap<String, MutableMetrics>): Map<String, ToolMetrics> {
        return source.mapValues { (name, m) -> toMetrics(name, m) }
    }

    private fun toMetrics(name: String, m: MutableMetrics): ToolMetrics {
        return ToolMetrics(
            callCount = m.callCount.get(),
            totalTimeMs = m.totalTimeMs.get(),
            minTimeMs = m.minTimeMs.get().let { if (it == Long.MAX_VALUE) 0L else it },
            maxTimeMs = m.maxTimeMs.get(),
            errorCount = m.errorCount.get(),
        )
    }
}
