package com.rk.ai.agent.context

data class FileInfo(
    val path: String,
    val summary: String = "",
    val symbols: List<String> = emptyList(),
    val lineCount: Int = 0,
    val lastModified: Long = 0,
)

class ProjectMemory {
    private var projectSummary: String = ""
    private var structureCache: String = ""
    private val fileIndex = mutableMapOf<String, FileInfo>()
    private val symbolIndex = mutableMapOf<String, MutableList<String>>()
    private val storage = mutableMapOf<String, String>()

    fun getCachedSummary(): String = projectSummary
    fun setSummary(summary: String) { projectSummary = summary }

    fun getCachedStructure(): String = structureCache
    fun setStructure(structure: String) { structureCache = structure }

    fun indexFile(path: String, symbols: List<String>, lineCount: Int) {
        fileIndex[path] = FileInfo(path = path, symbols = symbols, lineCount = lineCount, lastModified = System.currentTimeMillis())
    }

    fun getFileInfo(path: String): FileInfo? = fileIndex[path]

    fun hasFile(path: String): Boolean = fileIndex.containsKey(path)

    fun findFiles(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        val queryTokens = query.lowercase()
            .split(Regex("[\\s,._\\-/(){}\\[\\]<>:;\"']+"))
            .filter { it.length >= 2 }
            .toSet()
        if (queryTokens.isEmpty()) return fileIndex.keys.filter { it.lowercase().contains(query.lowercase()) }

        return fileIndex.keys
            .map { path ->
                val pathLower = path.lowercase()
                val components = pathLower.split("/", "\\").flatMap { it.split(".", "_", "-") }.filter { it.length >= 2 }
                val matchScore = queryTokens.sumOf { qt ->
                    when {
                        pathLower.contains(qt) -> 2
                        components.any { c -> c.contains(qt) || qt.contains(c) } -> 1
                        else -> 0
                    }.toInt()
                }
                path to matchScore
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(20)
            .map { it.first }
    }

    fun indexSymbol(name: String, filePath: String) {
        val paths = symbolIndex.getOrPut(name.lowercase()) { mutableListOf() }
        if (filePath !in paths) paths.add(filePath)
    }

    fun findSymbol(name: String): List<String> {
        if (name.isBlank()) return emptyList()
        val exact = symbolIndex[name.lowercase()]
        if (exact != null) return exact

        val nameLower = name.lowercase()
        return symbolIndex.entries
            .filter { (key, _) -> key.contains(nameLower) || nameLower.contains(key) }
            .flatMap { it.value }
            .distinct()
            .take(20)
    }

    fun storeRaw(key: String, value: String) { storage[key] = value }
    fun getRaw(key: String): String? = storage[key]

    fun hasProjectInfo(): Boolean = projectSummary.isNotBlank() || fileIndex.isNotEmpty()

    fun clear() {
        projectSummary = ""
        structureCache = ""
        fileIndex.clear()
        symbolIndex.clear()
        storage.clear()
    }
}
