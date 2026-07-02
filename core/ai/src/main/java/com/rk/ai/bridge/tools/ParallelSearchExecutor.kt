package com.rk.ai.bridge.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

data class MatchResult(
    val file: File,
    val lineNumber: Int,
    val line: String,
    val contextBefore: List<String> = emptyList(),
    val contextAfter: List<String> = emptyList(),
)

data class SearchReport(
    val results: List<MatchResult>,
    val filesSearched: Int,
    val totalMatches: Int,
    val truncated: Boolean,
    val durationMs: Long,
)

object ParallelSearchExecutor {

    private const val DEFAULT_CONCURRENCY = 4
    private const val MAX_FILE_SIZE = 10_485_760L // 10MB

    suspend fun search(
        files: List<File>,
        query: String,
        isRegex: Boolean = false,
        contextLines: Int = 2,
        maxResults: Int = 100,
        concurrency: Int = DEFAULT_CONCURRENCY,
        fileFilter: ((File) -> Boolean)? = null,
    ): SearchReport {
        val startNanos = System.nanoTime()
        val regex = if (isRegex) try { query.toRegex(setOf(RegexOption.MULTILINE)) } catch (_: Exception) { null }
        val plainLower = if (!isRegex) query.lowercase() else null
        if (isRegex && regex == null) return SearchReport(emptyList(), 0, 0, false, 0)

        val matchingFiles = if (fileFilter != null) files.filter(fileFilter) else files
        if (matchingFiles.isEmpty()) return SearchReport(emptyList(), 0, 0, false, 0)

        val semaphore = Semaphore(concurrency)
        val allResults = mutableListOf<MatchResult>()
        var truncated = false
        var filesSearched = 0

        coroutineScope {
            val jobs = matchingFiles.map { file ->
                async(Dispatchers.IO) {
                    semaphore.acquire()
                    try {
                        if (truncated || !isActive) return@async emptyList<MatchResult>()
                        if (!file.isFile || !file.canRead() || file.length() > MAX_FILE_SIZE) return@async emptyList()
                        val lines = try { file.readLines() } catch (_: Exception) { return@async emptyList() }
                        val fileResults = mutableListOf<MatchResult>()

                        for ((i, line) in lines.withIndex()) {
                            if (fileResults.size + allResults.size >= maxResults) {
                                truncated = true
                                break
                            }
                            val matched = if (isRegex) regex!!.containsMatchIn(line)
                            else line.lowercase().contains(plainLower!!)

                            if (matched) {
                                val before = ((i - contextLines).coerceAtLeast(0) until i).map { lines[it] }
                                val after = ((i + 1)..(i + contextLines).coerceAtMost(lines.size - 1)).map { lines[it] }
                                fileResults.add(MatchResult(
                                    file = file,
                                    lineNumber = i + 1,
                                    line = line,
                                    contextBefore = before,
                                    contextAfter = after,
                                ))
                            }
                        }
                        fileResults
                    } finally {
                        semaphore.release()
                    }
                }
            }

            val batchResults = jobs.awaitAll()
            for (results in batchResults) {
                allResults.addAll(results)
                if (results.isNotEmpty()) filesSearched++
            }
        }

        val sorted = allResults.sortedWith(compareBy({ it.file.absolutePath }, { it.lineNumber }))
            .take(maxResults)

        return SearchReport(
            results = sorted,
            filesSearched = filesSearched,
            totalMatches = sorted.size,
            truncated = truncated || sorted.size < allResults.size,
            durationMs = (System.nanoTime() - startNanos) / 1_000_000,
        )
    }
}
