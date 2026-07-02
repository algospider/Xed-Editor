package com.rk.ai.bridge.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import java.io.File

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
    private const val MAX_FILE_SIZE = 10_485_760L

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

        val regex = if (isRegex) {
            try {
                query.toRegex(setOf(RegexOption.MULTILINE))
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
        if (isRegex && regex == null) {
            return SearchReport(emptyList(), 0, 0, false, 0)
        }
        val plainLower = if (isRegex) null else query.lowercase()

        val matchingFiles = if (fileFilter != null) files.filter(fileFilter) else files
        if (matchingFiles.isEmpty()) {
            return SearchReport(emptyList(), 0, 0, false, 0)
        }

        val semaphore = Semaphore(concurrency)
        val allResults = java.util.Collections.synchronizedList(mutableListOf<MatchResult>())
        val truncatedFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        var filesSearched = 0

        coroutineScope {
            val jobs = matchingFiles.map { file ->
                async(Dispatchers.IO) {
                    semaphore.acquire()
                    try {
                        if (truncatedFlag.get() || !isActive) {
                            return@async emptyList<MatchResult>()
                        }
                        if (!file.isFile || !file.canRead() || file.length() > MAX_FILE_SIZE) {
                            return@async emptyList<MatchResult>()
                        }
                        val lines: List<String>
                        try {
                            lines = file.readLines()
                        } catch (_: Exception) {
                            return@async emptyList<MatchResult>()
                        }
                        val fileResults = mutableListOf<MatchResult>()

                        for ((i, line) in lines.withIndex()) {
                            if (fileResults.size + allResults.size >= maxResults) {
                                truncatedFlag.set(true)
                                break
                            }
                            val matched = if (isRegex) {
                                regex!!.containsMatchIn(line)
                            } else {
                                line.lowercase().contains(plainLower!!)
                            }

                            if (matched) {
                                val beforeStart = (i - contextLines).coerceAtLeast(0)
                                val beforeEnd = i
                                val before = lines.subList(beforeStart, beforeEnd)
                                val afterStart = i + 1
                                val afterEnd = (i + contextLines + 1).coerceAtMost(lines.size)
                                val after = lines.subList(afterStart, afterEnd)

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
                if (results.isNotEmpty()) {
                    filesSearched++
                }
            }
        }

        val sorted = allResults
            .sortedWith(compareBy({ it.file.absolutePath }, { it.lineNumber }))
            .take(maxResults)

        val wasTruncated = truncatedFlag.get() || sorted.size < allResults.size

        return SearchReport(
            results = sorted,
            filesSearched = filesSearched,
            totalMatches = sorted.size,
            truncated = wasTruncated,
            durationMs = (System.nanoTime() - startNanos) / 1_000_000,
        )
    }
}
