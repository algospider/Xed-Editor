package com.rk.ai.bridge.tools

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.PathMatcher
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

object WorkspaceFileIndex {

    private data class IndexSnapshot(
        val workspacePath: String,
        val byName: Map<String, List<File>>,
        val byExtension: Map<String, List<File>>,
        val byDirectory: Map<File, List<File>>,
        val allFiles: List<File>,
        val timestamp: Long,
    )

    @Volatile
    private var snapshot: IndexSnapshot? = null

    private val rebuildLock = Any()

    fun getWorkspacePath(): String = snapshot?.workspacePath ?: ""

    fun ensureIndexed(workspacePath: String) {
        val existing = snapshot
        if (existing != null && existing.workspacePath == workspacePath) return
        synchronized(rebuildLock) {
            val recheck = snapshot
            if (recheck != null && recheck.workspacePath == workspacePath) return
            snapshot = buildIndex(workspacePath)
        }
    }

    fun refresh(workspacePath: String) {
        synchronized(rebuildLock) {
            snapshot = buildIndex(workspacePath)
        }
    }

    fun notifyFileCreated(absolutePath: String) {
        val s = snapshot ?: return
        val file = File(absolutePath)
        if (!file.isFile || shouldIgnore(file)) return
        synchronized(rebuildLock) {
            val current = snapshot ?: return
            if (current.workspacePath.isBlank()) return
            snapshot = addToSnapshot(current, file)
        }
    }

    fun notifyFileModified(absolutePath: String) {
        notifyFileCreated(absolutePath)
    }

    fun notifyFileDeleted(absolutePath: String) {
        val s = snapshot ?: return
        val file = File(absolutePath)
        synchronized(rebuildLock) {
            val current = snapshot ?: return
            if (current.workspacePath.isBlank()) return
            snapshot = removeFromSnapshot(current, file)
        }
    }

    fun findByNamePattern(pattern: String, maxResults: Int = 100): List<File> {
        val idx = snapshot ?: return emptyList()
        val lower = pattern.lowercase()
        val isGlob = pattern.contains('*') || pattern.contains('?')
        val extGlob = isGlob && !pattern.contains(File.separatorChar) && !pattern.contains('/')

        if (isGlob) {
            if (extGlob) {
                val ext = pattern.substringAfterLast('.').lowercase()
                if (pattern.startsWith("*.") && ext.length <= 10) {
                    val files = idx.byExtension[ext] ?: emptyList()
                    val matcher = globToMatcher(pattern)
                    return if (matcher != null) files.filter { matcher.matches(Paths.get(it.name)) }.take(maxResults)
                    else files.take(maxResults)
                }
            }
            val matcher = globToMatcher(pattern) ?: return emptyList()
            return idx.allFiles.filter { matcher.matches(Paths.get(it.name)) }.take(maxResults)
        }

        if (lower.contains('/')) {
            val candidates = idx.byName[lower.substringAfterLast('/')] ?: return emptyList()
            return candidates.filter { it.absolutePath.lowercase().contains(lower) }.take(maxResults)
        }

        return idx.byName[lower]?.take(maxResults) ?: run {
            val prefix = idx.byName.entries
                .filter { it.key.startsWith(lower) }
                .flatMap { it.value }
                .take(maxResults)
            if (prefix.isNotEmpty()) return prefix
            idx.byName.entries
                .filter { it.key.contains(lower) }
                .flatMap { it.value }
                .take(maxResults)
        }
    }

    fun findExactName(name: String): List<File> {
        val idx = snapshot ?: return emptyList()
        return idx.byName[name.lowercase()] ?: emptyList()
    }

    fun findByExtension(ext: String): List<File> {
        val idx = snapshot ?: return emptyList()
        return idx.byExtension[ext.lowercase().removePrefix(".")] ?: emptyList()
    }

    fun allFiles(): List<File> = snapshot?.allFiles ?: emptyList()

    fun fileCount(): Int = snapshot?.allFiles?.size ?: 0

    private fun buildIndex(workspacePath: String): IndexSnapshot {
        val root = File(workspacePath)
        if (!root.exists() || !root.isDirectory) {
            return IndexSnapshot(workspacePath, emptyMap(), emptyMap(), emptyMap(), emptyList(), currentTimeNanos())
        }
        val byName = ConcurrentHashMap<String, MutableList<File>>()
        val byExtension = ConcurrentHashMap<String, MutableList<File>>()
        val byDirectory = ConcurrentHashMap<File, MutableList<File>>()
        val allFiles = mutableListOf<File>()

        root.walkTopDown()
            .onEnter { !it.name.startsWith('.') && it.name !in IGNORED_DIRS }
            .filter { it.isFile }
            .forEach { file ->
                addToMaps(allFiles, byName, byExtension, byDirectory, file)
            }

        return IndexSnapshot(
            workspacePath = workspacePath,
            byName = byName.entries.associate { it.key to it.value.toList() },
            byExtension = byExtension.entries.associate { it.key to it.value.toList() },
            byDirectory = byDirectory.entries.associate { it.key to it.value.toList() },
            allFiles = allFiles,
            timestamp = currentTimeNanos(),
        )
    }

    private fun addToMaps(
        allFiles: MutableList<File>,
        byName: ConcurrentHashMap<String, MutableList<File>>,
        byExtension: ConcurrentHashMap<String, MutableList<File>>,
        byDirectory: ConcurrentHashMap<File, MutableList<File>>,
        file: File,
    ) {
        allFiles.add(file)
        val name = file.name.lowercase()
        byName.computeIfAbsent(name) { mutableListOf() }.add(file)
        val ext = file.extension.lowercase()
        if (ext.isNotBlank()) {
            byExtension.computeIfAbsent(ext) { mutableListOf() }.add(file)
        }
        val parent = file.parentFile
        if (parent != null) {
            byDirectory.computeIfAbsent(parent) { mutableListOf() }.add(file)
        }
    }

    private fun addToSnapshot(snapshot: IndexSnapshot, file: File): IndexSnapshot {
        val name = file.name.lowercase()
        val ext = file.extension.lowercase()
        val parent = file.parentFile

        val newByName = snapshot.byName.toMutableMap()
        newByName[name] = (newByName[name] ?: emptyList()) + file

        val newByExt = snapshot.byExtension.toMutableMap()
        if (ext.isNotBlank()) {
            newByExt[ext] = (newByExt[ext] ?: emptyList()) + file
        }

        val newByDir = snapshot.byDirectory.toMutableMap()
        if (parent != null) {
            newByDir[parent] = (newByDir[parent] ?: emptyList()) + file
        }

        val newAllFiles = snapshot.allFiles + file

        return snapshot.copy(
            byName = newByName,
            byExtension = newByExt,
            byDirectory = newByDir,
            allFiles = newAllFiles,
            timestamp = currentTimeNanos(),
        )
    }

    private fun removeFromSnapshot(snapshot: IndexSnapshot, file: File): IndexSnapshot {
        val name = file.name.lowercase()
        val ext = file.extension.lowercase()
        val parent = file.parentFile
        val canonical = file.absolutePath

        fun <T> removeFrom(list: List<T>, predicate: (T) -> Boolean): List<T> {
            val filtered = list.filterNot(predicate)
            return if (filtered.size == list.size) list else filtered
        }

        val newByName = snapshot.byName.mapValues { (_, files) ->
            removeFrom(files) { it.absolutePath == canonical }
        }.filterValues { it.isNotEmpty() }

        val newByExt = snapshot.byExtension.mapValues { (_, files) ->
            removeFrom(files) { it.absolutePath == canonical }
        }.filterValues { it.isNotEmpty() }

        val newByDir = snapshot.byDirectory.mapValues { (_, files) ->
            removeFrom(files) { it.absolutePath == canonical }
        }.filterValues { it.isNotEmpty() }

        val newAllFiles = snapshot.allFiles.filter { it.absolutePath != canonical }

        return snapshot.copy(
            byName = newByName,
            byExtension = newByExt,
            byDirectory = newByDir,
            allFiles = newAllFiles,
            timestamp = currentTimeNanos(),
        )
    }

    private fun shouldIgnore(file: File): Boolean {
        var parent = file.parentFile
        while (parent != null) {
            if (parent.name.startsWith(".") || parent.name in IGNORED_DIRS) return true
            parent = parent.parentFile
        }
        return false
    }

    fun findByParent(dir: File): List<File> {
        val idx = snapshot ?: return emptyList()
        return idx.byDirectory[dir] ?: emptyList()
    }

    private fun globToMatcher(pattern: String): PathMatcher? {
        return try {
            FileSystems.getDefault().getPathMatcher("glob:$pattern")
        } catch (_: Exception) { null }
    }

    private fun currentTimeNanos(): Long = System.nanoTime()

    private val IGNORED_DIRS = setOf(".git", ".svn", ".hg", "node_modules", ".gradle", "build", "target", ".idea", ".xed", ".opencode", ".codebase-memory", "__pycache__")
}
