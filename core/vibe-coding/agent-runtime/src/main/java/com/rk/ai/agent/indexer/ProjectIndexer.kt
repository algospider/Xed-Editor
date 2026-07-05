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

    /**
     * Full project index using parallel scanning for each dimension.
     * All five scans run concurrently for maximum throughput.
     */
    suspend fun index(workspacePath: String): IndexResult = coroutineScope {
        val filesDeferred = async { scanFiles(workspacePath) }
        val modulesDeferred = async { scanModules(workspacePath) }
        val symbolsDeferred = async {
            // Symbol scanning depends on files, so we pass the deferred result
            val files = filesDeferred.await()
            scanSymbols(files)
        }
        val depsDeferred = async { scanDependencies(workspacePath) }
        val pkgDeferred = async {
            val files = filesDeferred.await()
            scanPackageStructure(files)
        }

        IndexResult(
            files = filesDeferred.await(),
            modules = modulesDeferred.await(),
            symbols = symbolsDeferred.await(),
            dependencies = depsDeferred.await(),
            packageStructure = pkgDeferred.await(),
        )
    }

    /**
     * Parallel file scanning across all extensions at once.
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
     * Parallel symbol scanning across files using coroutines.
     */
    private suspend fun scanSymbols(files: List<ScannedFile>): List<SymbolInfo> = supervisorScope {
        val patterns = listOf(
            Regex("""^(class|object|interface|data class|sealed class|enum class|abstract class)\s+(\w+)""", RegexOption.MULTILINE),
            Regex("""^fun\s+(\w+)""", RegexOption.MULTILINE),
            Regex("""^val\s+(\w+)\s""", RegexOption.MULTILINE),
            Regex("""^var\s+(\w+)\s""", RegexOption.MULTILINE),
        )

        val MAX_SYMBOL_FILES = 200
        val MAX_FILE_SIZE = 500_000L

        files.take(MAX_SYMBOL_FILES)
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
     * Parallel package-structure scanning.
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
