package com.rk.ai.agent.indexer

import android.util.Log
import com.rk.ai.service.IdeService
import com.google.gson.JsonElement
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import java.io.File

private const val TAG = "ProjectIndexer"

data class ScannedFile(
    val path: String,
    val lastModified: Long,
    val size: Long,
)

data class ModuleInfo(
    val name: String,
    val path: String,
)

data class SymbolInfo(
    val name: String,
    val file: String,
    val line: Int,
    val kind: String,
)

data class DependencyInfo(
    val name: String,
    val version: String?,
    val group: String?,
)

data class IndexResult(
    val files: List<ScannedFile>,
    val modules: List<ModuleInfo>,
    val symbols: List<SymbolInfo>,
    val dependencies: List<DependencyInfo>,
    val packageStructure: Map<String, List<String>>,
)

class ProjectIndexer(private val ideService: IdeService) {

    /** Timestamp of the last full index for change detection. */
    private var lastIndexTime: Long = 0L

    /** Cached file list so [reindexChanged] can diff against it. */
    private var knownFiles: Map<String, ScannedFile> = emptyMap()

    /** Most-recent full index result, used for incremental updates. */
    private var cachedIndex: IndexResult? = null

    /**
     * Full project index with all five dimensions scanned concurrently.
     *
     * After the first call, [reindexChanged] should be preferred for
     * subsequent indexing operations to avoid re-scanning unchanged files.
     */
    suspend fun index(workspacePath: String): IndexResult = coroutineScope {
        val filesDeferred = async { scanFiles(workspacePath) }
        val modulesDeferred = async { scanModules(workspacePath) }
        val symbolsDeferred = async {
            val files = filesDeferred.await()
            scanSymbols(files)
        }
        val depsDeferred = async { scanDependencies(workspacePath) }
        val pkgDeferred = async {
            val files = filesDeferred.await()
            scanPackageStructure(files)
        }

        val files = filesDeferred.await()
        lastIndexTime = System.currentTimeMillis()
        knownFiles = files.associateBy { it.path }

        val result = IndexResult(
            files = files,
            modules = modulesDeferred.await(),
            symbols = symbolsDeferred.await(),
            dependencies = depsDeferred.await(),
            packageStructure = pkgDeferred.await(),
        )
        cachedIndex = result
        result
    }

    /**
     * Incremental re-index that only scans files changed since the last index.
     *
     * Returns the most recent complete [IndexResult] — either a merged delta
     * from the cache plus changed files, or the cached index directly when
     * nothing has changed. This is safe to call on every symbol-search or
     * stats query without a full re-scan.
     *
     * @return the current [IndexResult] (never null after first [index] call).
     */
    suspend fun reindexChanged(workspacePath: String): IndexResult = coroutineScope {
        val cached = cachedIndex
        if (cached == null || lastIndexTime == 0L) {
            return@coroutineScope index(workspacePath)
        }

        // Lightweight: scan file list only
        val currentFiles = scanFiles(workspacePath)
        val changedFiles = currentFiles.filter { file ->
            val known = knownFiles[file.path]
            known == null || known.lastModified != file.lastModified || known.size != file.size
        }

        if (changedFiles.isEmpty() && currentFiles.size == knownFiles.size) {
            Log.i(TAG, "No file changes since last index — returning cached result")
            return@coroutineScope cached
        }

        Log.i(TAG, "Re-indexing ${changedFiles.size} changed files (${currentFiles.size} total)")
        lastIndexTime = System.currentTimeMillis()
        knownFiles = currentFiles.associateBy { it.path }

        // Only re-scan what changed; keep the rest from cache
        val changedPaths = changedFiles.map { it.path }.toSet()

        val newSymbols = if (changedFiles.isNotEmpty()) {
            val fresh = scanSymbols(changedFiles)
            // Merge: keep cached symbols from unchanged files, add new ones
            val cachedSymbols = cached.symbols.filter { it.file !in changedPaths }
            cachedSymbols + fresh
        } else cached.symbols

        val newPkgStructure = if (changedFiles.any { it.path.endsWith(".kt") || it.path.endsWith(".java") }) {
            scanPackageStructure(currentFiles)
        } else cached.packageStructure

        val result = IndexResult(
            files = currentFiles,
            modules = cached.modules, // modules rarely change
            symbols = newSymbols,
            dependencies = cached.dependencies, // deps rarely change
            packageStructure = newPkgStructure,
        )
        cachedIndex = result
        result
    }

    /**
     * Parallel file scanning grouped by extension.
     */
    private suspend fun scanFiles(path: String): List<ScannedFile> = supervisorScope {
        val extensions = listOf(
            "kt", "java", "kts", "xml", "gradle", "properties",
            "json", "yml", "yaml", "toml", "cfg", "md"
        )
        val chunkSize = 6
        val results = extensions.chunked(chunkSize).flatMap { chunk ->
            chunk.map { ext ->
                async {
                    try {
                        val results = ideService.findFiles("**/*.$ext", 2000, path)
                        results.mapNotNull { element ->
                            try {
                                val pathStr = element.asString
                                val file = File(pathStr)
                                if (file.exists()) {
                                    ScannedFile(pathStr, file.lastModified(), file.length())
                                } else null
                            } catch (_: Exception) { null }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to scan *.$ext: ${e.message}")
                        emptyList()
                    }
                }
            }
        }.flatMap { it.await() }
        results.distinctBy { it.path }
    }

    private suspend fun scanModules(path: String): List<ModuleInfo> {
        val modules = mutableListOf<ModuleInfo>()
        val settingsContent = try {
            ideService.getFileContent("$path/settings.gradle.kts")
        } catch (_: Exception) { null }
        if (settingsContent != null) {
            val modulePattern = Regex("""include\(":([^"]+)"\)""")
            for (match in modulePattern.findAll(settingsContent)) {
                val moduleName = match.groupValues[1]
                val modulePath = "$path/${moduleName.replace(":", "/")}"
                modules.add(ModuleInfo(moduleName, modulePath))
            }
        }
        return modules
    }

    /**
     * Scans symbols across files with priority ordering.
     *
     * Files are prioritized so that, within the MAX cap, the most useful
     * symbols are extracted first:
     *   1. Source code files (.kt, .java) — highest priority
     *   2. Recently modified files (latest first)
     *   3. Smaller files first (faster to parse)
     */
    private suspend fun scanSymbols(files: List<ScannedFile>): List<SymbolInfo> = supervisorScope {
        val patterns = listOf(
            Regex("""^(class|object|interface|data class|sealed class|enum class|abstract class)\s+(\w+)""", RegexOption.MULTILINE),
            Regex("""^fun\s+(\w+)""", RegexOption.MULTILINE),
            Regex("""^val\s+(\w+)\s""", RegexOption.MULTILINE),
            Regex("""^var\s+(\w+)\s""", RegexOption.MULTILINE),
        )

        val MAX_SYMBOL_FILES = 500
        val MAX_FILE_SIZE = 500_000L

        // Priority scoring: higher = more likely to contain useful symbols
        fun priority(file: ScannedFile): Int {
            val ext = file.path.substringAfterLast(".")
            val sourceBonus = if (ext in setOf("kt", "java", "kts")) 100 else 0
            val sizePenalty = when {
                file.size > MAX_FILE_SIZE -> -50
                file.size > 200_000 -> -10
                else -> 0
            }
            return sourceBonus + sizePenalty
        }

        files.sortedByDescending { priority(it) }
            .take(MAX_SYMBOL_FILES)
            .filter { it.size <= MAX_FILE_SIZE }
            .chunked(20) // process 20 files in parallel per batch
            .flatMap { batch ->
                batch.map { file ->
                    async {
                        val symbols = mutableListOf<SymbolInfo>()
                        try {
                            val content = File(file.path).readText()
                            for (pattern in patterns) {
                                for (match in pattern.findAll(content)) {
                                    val kind = match.groupValues[1].let { raw ->
                                        when {
                                            raw in listOf("class", "data class", "sealed class", "abstract class", "enum class") -> "class"
                                            raw == "object" || raw == "interface" -> raw
                                            raw == "fun" -> "fun"
                                            raw == "val" || raw == "var" -> "property"
                                            else -> raw
                                        }
                                    }
                                    val name = match.groupValues[2]
                                    val line = content.substring(0, match.range.first).count { it == '\n' } + 1
                                    symbols.add(SymbolInfo(name, file.path, line, kind))
                                }
                            }
                        } catch (_: Exception) { }
                        symbols
                    }
                }
            }.flatMap { it.await() }
    }

    private suspend fun scanDependencies(path: String): List<DependencyInfo> {
        val deps = mutableListOf<DependencyInfo>()
        val libsContent = try {
            ideService.getFileContent("$path/gradle/libs.versions.toml")
        } catch (_: Exception) { null }
        if (libsContent != null) {
            val libPattern = Regex("""(\S+)\s*=\s*"([^"]+)"(?::"([^"]+)")?""")
            for (match in libPattern.findAll(libsContent)) {
                deps.add(DependencyInfo(match.groupValues[1], match.groupValues[2], match.groupValues[3]))
            }
        }
        return deps
    }

    /**
     * Package-structure scanning with parallel batches.
     */
    private suspend fun scanPackageStructure(files: List<ScannedFile>): Map<String, List<String>> = supervisorScope {
        val sourceFiles = files.filter { it.path.endsWith(".kt") || it.path.endsWith(".java") }
        val pkgPattern = Regex("""^package\s+([\w.]+)""")

        sourceFiles.chunked(30).flatMap { batch ->
            batch.map { file ->
                async {
                    val result = mutableListOf<Pair<String, String>>()
                    try {
                        val firstLine = File(file.path).useLines { it.firstOrNull() ?: "" }
                        val pkgMatch = pkgPattern.find(firstLine)
                        if (pkgMatch != null) {
                            result.add(pkgMatch.groupValues[1] to file.path)
                        }
                    } catch (_: Exception) { }
                    result
                }
            }
        }.flatMap { it.await() }
            .groupBy({ it.first }, { it.second })
    }
}
