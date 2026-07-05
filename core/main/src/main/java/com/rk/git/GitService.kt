package com.rk.git

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.rk.settings.Settings
import java.io.File
import java.util.LinkedHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider

class GitService {

    private data class GitCacheEntry(val git: Git, val createdAt: Long)
    private val repoCache = LinkedHashMap<String, GitCacheEntry>(4, 0.75f, true)
    private val repoCacheTtlMs = 10_000L
    private val repoCacheMaxSize = 8

    private suspend fun getRepo(workspacePath: String): Git? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        repoCache[workspacePath]?.let {
            if (now - it.createdAt < repoCacheTtlMs) return@withContext it.git
            runCatching { it.git.close() }
            repoCache.remove(workspacePath)
        }
        runCatching {
            val repoDir = File(workspacePath)
            val builder = FileRepositoryBuilder().readEnvironment().findGitDir(repoDir)
            val repo = builder.build() ?: return@runCatching null
            if (repo.directory == null) { repo.close(); return@runCatching null }
            val git = Git(repo)
            repoCache[workspacePath] = GitCacheEntry(git, now)
            if (repoCache.size > repoCacheMaxSize) {
                repoCache.keys.firstOrNull()?.let { k ->
                    repoCache[k]?.git?.close(); repoCache.remove(k)
                }
            }
            git
        }.getOrNull()
    }

    private fun invalidateRepoCache(workspacePath: String) {
        repoCache.remove(workspacePath)?.git?.let { runCatching { it.close() } }
    }

    private data class StatusCache(val path: String, val result: JsonObject, val timestamp: Long)
    private var lastStatus: StatusCache? = null

    suspend fun getGitStatus(workspacePath: String): JsonObject {
        val now = System.currentTimeMillis()
        lastStatus?.let {
            if (it.path == workspacePath && now - it.timestamp < 3000) return it.result
        }

        val result = JsonObject()
        if (workspacePath.isBlank()) return result.apply { addProperty("error", "workspacePath required") }
        withContext(Dispatchers.IO) {
            runCatching {
                val git = getRepo(workspacePath) ?: run { result.addProperty("error", "not a git repository"); return@withContext }
                val repo = git.repository
                val status = git.status().call()
                result.addProperty("branch", repo.branch ?: "HEAD")
                result.add("changes", JsonArray().apply {
                    status.added.forEach { add(JsonObject().apply { addProperty("file", it); addProperty("type", "staged_added") }) }
                    status.changed.forEach { add(JsonObject().apply { addProperty("file", it); addProperty("type", "staged_modified") }) }
                    status.modified.forEach { add(JsonObject().apply { addProperty("file", it); addProperty("type", "working_tree_modified") }) }
                    status.removed.forEach { add(JsonObject().apply { addProperty("file", it); addProperty("type", "staged_removed") }) }
                    status.missing.forEach { add(JsonObject().apply { addProperty("file", it); addProperty("type", "working_tree_deleted") }) }
                    status.untracked.forEach { add(JsonObject().apply { addProperty("file", it); addProperty("type", "untracked") }) }
                    status.conflicting.forEach { add(JsonObject().apply { addProperty("file", it); addProperty("type", "conflicting") }) }
                })
                result.addProperty("totalChanges", result.getAsJsonArray("changes").size())
            }.onFailure { result.addProperty("error", it.message ?: "git error") }
        }
        lastStatus = StatusCache(workspacePath, result, now)
        return result
    }

    suspend fun getGitDiff(workspacePath: String): String {
        if (workspacePath.isBlank()) return "workspacePath required"
        return withContext(Dispatchers.IO) {
            runCatching {
                val git = getRepo(workspacePath) ?: return@withContext "not a git repository"
                val repo = git.repository

                val output = StringBuilder()

                val status = git.status().call()
                val changedFiles = status.added + status.changed + status.removed +
                    status.missing + status.modified + status.untracked + status.conflicting
                if (changedFiles.isNotEmpty()) {
                    output.appendLine("Changed files:")
                    changedFiles.sorted().forEach { output.appendLine("  $it") }
                    output.appendLine()
                }

                runCatching {
                    val baos = java.io.ByteArrayOutputStream()
                    val formatter = org.eclipse.jgit.diff.DiffFormatter(baos)
                    formatter.setRepository(repo)

                    runCatching {
                        val unstagedDiff = git.diff().call()
                        formatter.format(unstagedDiff)
                    }.onFailure { e ->
                        if (output.isEmpty()) output.appendLine("unstaged diff error: ${e.message}")
                    }
                    runCatching {
                        val stagedDiff = git.diff().setCached(true).call()
                        formatter.format(stagedDiff)
                    }.onFailure { e ->
                        if (output.isEmpty()) output.appendLine("staged diff error: ${e.message}")
                    }

                    formatter.close()

                    val diffOutput = baos.toString(Charsets.UTF_8.name())
                    if (diffOutput.isNotBlank()) {
                        if (output.isNotEmpty()) output.appendLine("--- diff ---\n")
                        output.append(diffOutput)
                    }
                }.onFailure { e ->
                    if (output.isEmpty()) output.appendLine("diff error: ${e.message}")
                }

                output.toString().ifBlank { "no changes" }
            }.getOrElse { "error: ${it.message}" }
        }
    }

    suspend fun gitCommit(workspacePath: String, message: String, all: Boolean): String = withContext(Dispatchers.IO) {
        runCatching {
            val git = getRepo(workspacePath) ?: return@withContext "not a git repository"
            val commit = git.commit()
            commit.setMessage(message)
            if (all) commit.setAll(true)
            val name = Settings.git_name.ifBlank { null }
            val email = Settings.git_email.ifBlank { null }
            if (name != null && email != null) {
                val ident = PersonIdent(name, email)
                commit.setAuthor(ident)
                commit.setCommitter(ident)
            }
            val rev = commit.call()
            lastStatus = null
            invalidateRepoCache(workspacePath)
            "committed ${rev.name.take(7)}: $message"
        }.getOrElse { "error: ${it.message}" }
    }

    suspend fun gitCheckout(workspacePath: String, target: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val git = getRepo(workspacePath) ?: return@withContext "not a git repository"
            git.checkout().setName(target).call()
            lastStatus = null
            invalidateRepoCache(workspacePath)
            "checked out $target"
        }.getOrElse { "error: ${it.message}" }
    }

    suspend fun gitLog(workspacePath: String, maxCount: Int = 10, branch: String? = null): String = withContext(Dispatchers.IO) {
        runCatching {
            val git = getRepo(workspacePath) ?: return@withContext "not a git repository"
            val logCmd = git.log().setMaxCount(maxCount)
            if (branch != null) {
                val ref = git.repository.findRef(branch) ?: return@withContext "branch not found: $branch"
                logCmd.add(ref.objectId)
            }
            logCmd.call().joinToString("\n") { rev ->
                val dt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(rev.authorIdent.`when`)
                "${rev.name.take(7)} ${rev.shortMessage} (${rev.authorIdent.name}, $dt)"
            }.ifEmpty { "no commits found" }
        }.getOrElse { "error: ${it.message}" }
    }

    suspend fun gitBranch(workspacePath: String, action: String, branchName: String?): String = withContext(Dispatchers.IO) {
        runCatching {
            val git = getRepo(workspacePath) ?: return@withContext "not a git repository"
            when (action) {
                "list" -> {
                    val current = git.repository.branch ?: "HEAD"
                    git.branchList().call().joinToString("\n") { ref ->
                        val name = org.eclipse.jgit.lib.Repository.shortenRefName(ref.name)
                        val marker = if (name == current) "* " else "  "
                        "$marker$name"
                    }
                }
                "create" -> {
                    if (branchName.isNullOrBlank()) return@withContext "error: branchName required for create"
                    git.branchCreate().setName(branchName).call()
                    invalidateRepoCache(workspacePath)
                    "created branch $branchName"
                }
                "delete" -> {
                    if (branchName.isNullOrBlank()) return@withContext "error: branchName required for delete"
                    git.branchDelete().setBranchNames(branchName).setForce(false).call()
                    invalidateRepoCache(workspacePath)
                    "deleted branch $branchName"
                }
                else -> "error: unknown action '$action', use list/create/delete"
            }
        }.getOrElse { "error: ${it.message}" }
    }

    suspend fun gitPush(workspacePath: String, remote: String, branch: String?, setUpstream: Boolean, force: Boolean): String = withContext(Dispatchers.IO) {
        runCatching {
            val git = getRepo(workspacePath) ?: return@withContext "not a git repository"
            val branchName = branch ?: git.repository.branch ?: return@withContext "error: no branch specified and not on any branch"
            val creds = UsernamePasswordCredentialsProvider(Settings.git_username, Settings.git_password)

            val results = git.push()
                .setRemote(remote)
                .setCredentialsProvider(creds)
                .setForce(force)
                .add("refs/heads/$branchName:refs/heads/$branchName")
                .call()

            val sb = StringBuilder()
            for (result in results) {
                for (update in result.remoteUpdates) {
                    sb.appendLine("${update.remoteName}: ${update.status}")
                    update.message?.let { sb.appendLine("  $it") }
                }
            }
            if (setUpstream && results.any { r -> r.remoteUpdates.any { it.status == org.eclipse.jgit.transport.RemoteRefUpdate.Status.OK } }) {
                runCatching {
                    val config = git.repository.config
                    config.setString("branch", branchName, "remote", remote)
                    config.setString("branch", branchName, "merge", "refs/heads/$branchName")
                    config.save()
                    sb.appendLine("upstream tracking set for $branchName -> $remote/$branchName")
                }
            }
            sb.toString().ifEmpty { "push ok" }
        }.getOrElse { e ->
            val m = e.message ?: ""
            if (m.contains("Auth", true) || m.contains("401") || m.contains("403")) "auth error: check git username/password in settings"
            else "error: $m"
        }
    }

    suspend fun gitPull(workspacePath: String, remote: String, branch: String?): String = withContext(Dispatchers.IO) {
        runCatching {
            val git = getRepo(workspacePath) ?: return@withContext "not a git repository"
            val pullCmd = git.pull()
                .setRemote(remote)
                .setCredentialsProvider(UsernamePasswordCredentialsProvider(Settings.git_username, Settings.git_password))
            if (branch != null) pullCmd.setRemoteBranchName(branch)
            val result = pullCmd.call()
            lastStatus = null
            invalidateRepoCache(workspacePath)
            if (result.isSuccessful) {
                val mergeResult = result.mergeResult
                buildString {
                    append("pull ok")
                    if (mergeResult != null) {
                        append("; merge: ${mergeResult.mergeStatus}")
                        mergeResult.newHead?.let { append("; HEAD: ${it.name.take(7)}") }
                    }
                }
            } else {
                buildString {
                    appendLine("pull had issues:")
                    result.mergeResult?.let { mr ->
                        append("merge: ${mr.mergeStatus}")
                        mr.conflicts?.let { c -> if (c.isNotEmpty()) append("; conflicts: ${c.keys.joinToString(", ")}") }
                    }
                }
            }
        }.getOrElse { e ->
            val m = e.message ?: ""
            if (m.contains("Auth", true) || m.contains("401") || m.contains("403")) "auth error: check git username/password in settings"
            else "error: $m"
        }
    }

    suspend fun getGitRoot(workspacePath: String): String? = withContext(Dispatchers.IO) {
        if (workspacePath.isBlank()) return@withContext null
        runCatching {
            FileRepositoryBuilder().findGitDir(File(workspacePath)).takeIf { it.gitDir != null }?.build()?.workTree?.canonicalPath
        }.getOrNull()
    }
}
