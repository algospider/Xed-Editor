package com.rk.ai.agent

import com.rk.ai.service.IdeService
import com.rk.ai.models.UIMessage
import com.rk.ai.core.MessageRole
import com.rk.ai.models.UIMessagePart

data class WorkspaceSnapshot(
    val workspaceRoot: String = "",
    val projectRoot: String = "",
    val activeFile: String = "",
    val openFiles: List<String> = emptyList(),
    val selection: String = "",
    val cursorPosition: Int = -1,
    val gitBranch: String = "",
    val gitChanges: Int = 0,
    val gitDiffSummary: String = "",
    val modifiedFiles: List<String> = emptyList(),
    val projectLanguage: String = "",
    val buildSystem: String = "",
    val diagnosticCount: Int = 0,
    val diagnosticDetails: List<String> = emptyList(),
    val activeFileSnippet: String = "",
) {
    fun isEmpty(): Boolean = workspaceRoot.isBlank()

    fun toContextMessage(): UIMessage = UIMessage.system(
        buildContextBlock()
    )

    fun buildContextBlock(): String = buildString {
        appendLine("<workspace_context>")
        if (workspaceRoot.isNotBlank()) appendLine("  workspace_root: $workspaceRoot")
        if (projectRoot.isNotBlank()) appendLine("  project_root: $projectRoot")
        if (activeFile.isNotBlank()) appendLine("  active_file: $activeFile")
        if (openFiles.isNotEmpty()) {
            appendLine("  open_files:")
            openFiles.forEach { appendLine("    - $it") }
        }
        if (selection.isNotBlank()) {
            appendLine("  selection:")
            selection.lines().take(20).forEach { appendLine("    $it") }
            if (selection.lines().size > 20) appendLine("    ... (${selection.lines().size} lines total)")
        }
        if (cursorPosition >= 0) appendLine("  cursor_position: line $cursorPosition")
        if (activeFileSnippet.isNotBlank()) {
            appendLine("  active_file_context:")
            activeFileSnippet.lines().forEach { appendLine("    $it") }
        }
        if (gitBranch.isNotBlank()) appendLine("  git_branch: $gitBranch")
        if (gitChanges > 0) appendLine("  git_uncommitted: $gitChanges")
        if (modifiedFiles.isNotEmpty()) {
            appendLine("  modified_files:")
            modifiedFiles.forEach { appendLine("    - $it") }
        }
        if (gitDiffSummary.isNotBlank()) {
            appendLine("  git_diff:")
            gitDiffSummary.lines().forEach { appendLine("    $it") }
        }
        if (projectLanguage.isNotBlank()) appendLine("  project_language: $projectLanguage")
        if (buildSystem.isNotBlank()) appendLine("  build_system: $buildSystem")
        if (diagnosticCount > 0) {
            appendLine("  lsp_diagnostics: $diagnosticCount")
            if (diagnosticDetails.isNotEmpty()) {
                appendLine("  diagnostic_details:")
                diagnosticDetails.take(10).forEach { appendLine("    - $it") }
                if (diagnosticDetails.size > 10) appendLine("    ... and ${diagnosticDetails.size - 10} more")
            }
        }
        appendLine("</workspace_context>")
    }
}

class WorkspaceContextCollector(
    private val ideService: IdeService,
) {
    suspend fun snapshot(): WorkspaceSnapshot {
        return try {
            val workspaceRoot = ideService.getPrimaryWorkspacePath()
            if (workspaceRoot.isNullOrBlank()) return WorkspaceSnapshot()

            val projectConfig = try {
                ideService.getProjectConfig(workspaceRoot)
            } catch (_: Exception) { com.google.gson.JsonObject() }

            val gitStatus = try {
                ideService.getGitStatus(workspaceRoot)
            } catch (_: Exception) { com.google.gson.JsonObject() }

            val gitDiff = try {
                ideService.getGitDiff(workspaceRoot)
            } catch (_: Exception) { null }

            val activeFileData = try {
                ideService.getActiveFile()
            } catch (_: Exception) { null }

            val openFilesData = try {
                ideService.getOpenFiles()
            } catch (_: Exception) { emptyList() }

            val selectionData = try {
                ideService.getSelection()
            } catch (_: Exception) { null }

            val activeFilePath = activeFileData?.let {
                it["path"]?.asString ?: it["filePath"]?.asString
            } ?: ""

            val diagnostics = try {
                if (activeFilePath.isNotBlank()) ideService.getDiagnostics(activeFilePath) else null
            } catch (_: Exception) { null }

            val diagnosticDetails = try {
                diagnostics?.asJsonArray?.mapNotNull { diag ->
                    val obj = diag.asJsonObject
                    val msg = obj["message"]?.asString ?: return@mapNotNull null
                    val line = obj["range"]?.asJsonObject?.get("start")?.asJsonObject?.get("line")?.asInt
                    val severity = obj["severity"]?.asInt
                    val sevText = when (severity) { 1 -> "ERROR"; 2 -> "WARN"; 3 -> "INFO"; else -> "HINT" }
                    if (line != null) "$sevText:$activeFilePath:${line+1}: $msg" else "$sevText: $msg"
                } ?: emptyList()
            } catch (_: Exception) { emptyList() }

            val activeFileSnippet = try {
                val cursorLine = activeFileData?.get("cursorLine")?.asInt ?: -1
                if (activeFilePath.isNotBlank() && cursorLine >= 0) {
                    val startLine = (cursorLine - 10).coerceAtLeast(1)
                    val endLine = cursorLine + 10
                    ideService.getFileContent(activeFilePath, startLine, endLine)?.let { content ->
                        val lines = content.lines()
                        lines.mapIndexed { idx, line ->
                            val lineNum = startLine + idx
                            val marker = if (lineNum == cursorLine) ">" else " "
                            "$marker${lineNum.toString().padStart(4)}| $line"
                        }.joinToString("\n")
                    } ?: ""
                } else ""
            } catch (_: Exception) { "" }

            val modifiedFiles = try {
                val changes = gitStatus["changes"]?.asJsonArray
                changes?.mapNotNull { change ->
                    change.asJsonObject["file"]?.asString ?: change.asJsonObject["path"]?.asString
                } ?: emptyList()
            } catch (_: Exception) { emptyList() }

            val diffSummary = gitDiff?.take(2000)

            WorkspaceSnapshot(
                workspaceRoot = workspaceRoot,
                projectRoot = projectConfig["projectDir"]?.asString ?: workspaceRoot,
                activeFile = activeFilePath,
                openFiles = openFilesData.mapNotNull { f ->
                    f["path"]?.asString ?: f["filePath"]?.asString
                },
                selection = selectionData ?: "",
                cursorPosition = activeFileData?.let {
                    it["cursorLine"]?.asInt ?: -1
                } ?: -1,
                gitBranch = gitStatus["branch"]?.asString ?: "",
                gitChanges = modifiedFiles.size,
                gitDiffSummary = diffSummary ?: "",
                modifiedFiles = modifiedFiles,
                projectLanguage = projectConfig["language"]?.asString ?: "",
                buildSystem = projectConfig["buildSystem"]?.asString ?: "",
                diagnosticCount = diagnostics?.asJsonArray?.size() ?: 0,
                diagnosticDetails = diagnosticDetails,
                activeFileSnippet = activeFileSnippet,
            )
        } catch (_: Exception) {
            WorkspaceSnapshot()
        }
    }

    fun buildContextMessage(snapshot: WorkspaceSnapshot): String {
        if (snapshot.isEmpty()) return ""
        return snapshot.buildContextBlock()
    }
}
