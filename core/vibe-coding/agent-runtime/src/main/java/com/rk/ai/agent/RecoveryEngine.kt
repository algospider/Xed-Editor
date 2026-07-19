package com.rk.ai.agent

import android.util.Log
import com.rk.ai.models.UIMessage
import com.rk.ai.models.UIMessagePart
import com.rk.ai.core.MessageRole
import java.io.File

private const val TAG = "RecoveryEngine"

data class RecoveryAction(
    val action: String,
    val params: Map<String, String> = emptyMap(),
    val message: String = "",
)

/**
 * Maps a tool name and error pattern to one or more alternative tools.
 *
 * This lets the execution engine suggest a different approach when a tool
 * keeps failing, rather than retrying the same tool with the same input.
 */
data class ToolAlternative(
    val suggestedTool: String,
    val reason: String,
    val inputTemplate: String = "",
)

class RecoveryEngine {

    /**
     * Analyzes a tool failure and returns a recovery action when an
     * automatic fix is possible (e.g. creating a missing directory).
     *
     * Returns null when no automatic recovery is available.
     */
    fun analyzeFailure(
        toolName: String,
        errorMessage: String,
        toolInput: String,
        workspaceRoot: String?,
    ): RecoveryAction? {
        if (errorMessage.contains("ENOENT") || errorMessage.contains("No such file") || errorMessage.contains("not found") && errorMessage.contains("directory")) {
            val path = extractPath(errorMessage, toolInput)
            if (path != null) {
                return RecoveryAction(
                    action = "create_directory",
                    params = mapOf("path" to path),
                    message = "Parent directory missing for '$path' — auto-creating it and retrying.",
                )
            }
        }

        if (errorMessage.contains("File not found") || errorMessage.contains("does not exist")) {
            val path = extractPathFromInput(toolInput)
            if (path != null && toolName in listOf("readFile", "cat", "editFile")) {
                return RecoveryAction(
                    action = "skip_missing_file",
                    params = mapOf("path" to path),
                    message = "File '$path' does not exist (requested by $toolName). Reporting absence.",
                )
            }
        }

        if (errorMessage.contains("permission denied") || errorMessage.contains("EACCES")) {
            val path = extractPath(errorMessage, toolInput)
            return RecoveryAction(
                action = "permission_error",
                params = mapOf("path" to (path ?: "unknown")),
                message = "Permission denied for path. Suggest using workspace root.",
            )
        }

        if (errorMessage.contains("timed out") || errorMessage.contains("timeout") || errorMessage.contains("deadline")) {
            return RecoveryAction(
                action = "retry_with_timeout",
                message = "Tool timed out. Retry with a shorter operation or different approach.",
            )
        }

        if (errorMessage.contains("invalid json") || errorMessage.contains("JSON parse")) {
            return RecoveryAction(
                action = "fix_json",
                message = "Invalid JSON in tool arguments. Fix the format and retry.",
            )
        }

        // — new patterns —

        if (errorMessage.contains("disk full") || errorMessage.contains("no space") || errorMessage.contains("quota")) {
            return RecoveryAction(
                action = "disk_full",
                message = "Disk space or quota exceeded. Try smaller writes or free up space.",
            )
        }

        if (errorMessage.contains("merge conflict") || errorMessage.contains("conflict") && errorMessage.contains("git")) {
            return RecoveryAction(
                action = "git_conflict",
                message = "Git merge conflict detected. Resolve conflicts manually or stash changes.",
            )
        }

        if ((errorMessage.contains("Could not find") || errorMessage.contains("not found the specified text")) && toolName == "editFile") {
            return RecoveryAction(
                action = "edit_old_string_mismatch",
                message = "editFile oldString did not match file content. Read the file first to see current content, then retry with the exact text.",
            )
        }

        if ((errorMessage.contains("stdin") || errorMessage.contains("tty") || errorMessage.contains("interactive")) && toolName == "runCommand") {
            return RecoveryAction(
                action = "command_needs_stdin",
                message = "Command appears to require interactive input. Use a non-interactive equivalent or pipe input via echo/printf.",
            )
        }

        return null
    }

    /**
     * Suggests alternative tools when a tool keeps failing or produces
     * poor results. Returns null when the current tool is the best option.
     */
    fun suggestAlternativeTool(
        toolName: String,
        errorMessage: String,
        failureCount: Int,
    ): ToolAlternative? {
        if (failureCount < 2) return null // don't suggest alternatives on first failure

        return when (toolName) {
            "editFile" -> when {
                errorMessage.contains("Could not find", ignoreCase = true) ||
                    errorMessage.contains("not found the specified text", ignoreCase = true) -> {
                    ToolAlternative(
                        suggestedTool = "writeFile",
                        reason = "editFile failed to find the target text (tried $failureCount times). " +
                            "Use writeFile to overwrite the whole file with the corrected content.",
                    )
                }
                errorMessage.contains("multiple matches", ignoreCase = true) ||
                    errorMessage.contains("Found multiple", ignoreCase = true) -> {
                    ToolAlternative(
                        suggestedTool = "editFile",
                        reason = "oldString matched multiple locations. Add more surrounding context " +
                            "(include neighboring lines above and below) to make oldString unique.",
                        inputTemplate = """{"filePath": "<path>", "oldString": "<unique surrounding block>", "newString": "<replacement>"}""",
                    )
                }
                else -> null
            }

            "readFile", "cat", "getFileContent" -> when {
                errorMessage.contains("not found", ignoreCase = true) ||
                    errorMessage.contains("does not exist", ignoreCase = true) -> {
                    ToolAlternative(
                        suggestedTool = "searchCode",
                        reason = "File not found. Use searchCode to locate the file by its content or symbols.",
                    )
                }
                else -> null
            }

            "writeFile" -> when {
                errorMessage.contains("permission", ignoreCase = true) ||
                    errorMessage.contains("EACCES", ignoreCase = true) -> {
                    ToolAlternative(
                        suggestedTool = "runCommand",
                        reason = "writeFile lacks permission. Try writing via runCommand with 'tee' or 'cat'.",
                        inputTemplate = """{"command": "cat > <path> << 'EOF'\n<content>\nEOF"}""",
                    )
                }
                else -> null
            }

            "searchCode", "grep" -> {
                ToolAlternative(
                    suggestedTool = "searchSymbols",
                    reason = "searchCode returned no useful results (tried $failureCount times). " +
                        "Try searchSymbols to find definitions and usages by name.",
                )
            }

            "runCommand" -> when {
                errorMessage.contains("not found", ignoreCase = true) &&
                    !errorMessage.contains("file", ignoreCase = true) -> {
                    ToolAlternative(
                        suggestedTool = "runCommand",
                        reason = "Command not found. Check if the tool is installed or use an alternative command.",
                    )
                }
                errorMessage.contains("stdin", ignoreCase = true) ||
                    errorMessage.contains("interactive", ignoreCase = true) -> {
                    ToolAlternative(
                        suggestedTool = "runCommand",
                        reason = "Command needs input. Prefix with echo/printf to pipe input non-interactively.",
                        inputTemplate = """{"command": "echo '<input>' | <command>"}""",
                    )
                }
                else -> null
            }

            "listFiles", "ls" -> {
                ToolAlternative(
                    suggestedTool = "getProjectStructure",
                    reason = "listFiles may not show the full layout. Use getProjectStructure for a project overview.",
                )
            }

            else -> null
        }
    }

    fun buildRecoveryMessage(
        toolName: String,
        errorMessage: String,
        recoveryAction: RecoveryAction?,
    ): UIMessage {
        val text = buildString {
            appendLine("[RECOVERY] Tool '$toolName' failed.")
            if (recoveryAction != null) {
                appendLine("Recovery: ${recoveryAction.message}")
            } else {
                appendLine("No automatic recovery available.")
                appendLine("Error: $errorMessage")
                appendLine("Suggestion: Try a different approach or check tool arguments.")
            }
        }
        return UIMessage.system(text)
    }

    /**
     * Executes a recovery action and returns true if the error condition was resolved.
     * The caller should retry the failed operation after a successful recovery.
     */
    fun executeRecovery(action: RecoveryAction): Boolean {
        return when (action.action) {
            "create_directory" -> {
                val path = action.params["path"]
                if (path != null) {
                    try {
                        val dir = File(path)
                        val created = dir.mkdirs()
                        if (created) {
                            Log.i(TAG, "Recovery: created directory $path")
                            true
                        } else if (dir.exists()) {
                            Log.i(TAG, "Recovery: directory already exists $path")
                            true
                        } else {
                            Log.w(TAG, "Recovery: failed to create directory $path")
                            false
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Recovery: exception creating directory $path", e)
                        false
                    }
                } else {
                    false
                }
            }

            "retry_with_timeout" -> {
                Log.i(TAG, "Recovery: retrying with fresh timeout")
                true
            }

            "skip_missing_file" -> {
                Log.i(TAG, "Recovery: skipping missing file: ${action.params["path"]}")
                true
            }

            "permission_error" -> {
                Log.w(TAG, "Recovery: permission error at ${action.params["path"]} — cannot auto-resolve")
                false
            }

            "fix_json" -> {
                Log.w(TAG, "Recovery: JSON error — needs LLM intervention, not auto-fixing")
                false
            }

            // New recovery actions — mostly informational, return false to avoid blind retries
            "disk_full", "git_conflict", "edit_old_string_mismatch", "command_needs_stdin" -> {
                Log.w(TAG, "Recovery: '${action.action}' — cannot auto-resolve, needs LLM intervention")
                false
            }

            else -> {
                Log.w(TAG, "Recovery: unknown action '${action.action}'")
                false
            }
        }
    }

    /**
     * @return true if this error type justifies retrying the tool after recovery.
     */
    fun shouldRetryAfterRecovery(action: RecoveryAction): Boolean {
        return action.action in setOf("create_directory", "retry_with_timeout")
    }

    private fun extractPath(errorMessage: String, toolInput: String): String? {
        val patterns = listOf(
            Regex("'(/[^']*)'"),
            Regex("\"(/[^\"]*)\""),
            Regex("No such file or directory at (/[^ ]*)"),
            Regex("ENOENT.*?(/[^ ,)\\n]+)"),
        )
        for (pattern in patterns) {
            val match = pattern.find(errorMessage) ?: pattern.find(toolInput)
            if (match != null) {
                val path = match.groupValues[1]
                if (path.startsWith("/")) {
                    val parent = File(path).parent
                    if (parent != null) return parent
                }
            }
        }
        return null
    }

    private fun extractPathFromInput(toolInput: String): String? {
        val patterns = listOf(
            Regex("\"path\"\\s*:\\s*\"([^\"]+)\""),
            Regex("\"filePath\"\\s*:\\s*\"([^\"]+)\""),
            Regex("\"file\"\\s*:\\s*\"([^\"]+)\""),
        )
        for (pattern in patterns) {
            val match = pattern.find(toolInput)
            if (match != null) {
                val path = match.groupValues[1]
                if (path.startsWith("/")) return path
            }
        }
        return null
    }

    companion object {
        /**
         * Tools where a simple retry (same input) is likely to work after recovery.
         * Idempotent read-only tools and tools that may fail transiently.
         */
        val AUTO_RETRYABLE_TOOLS = setOf(
            "readFile", "readFiles", "cat", "writeFile", "editFile",
            "createFile", "deleteFile", "renameFile",
            "listFiles", "ls", "findFiles", "glob",
            "head", "tail", "wc", "countLines", "stat",
            "runCommand", "getFileContent",
        )

        /**
         * Tools that should always be retried regardless of error message
         * (network-dependent or inherently flaky tools).
         */
        val ALWAYS_RETRY = setOf("runCommand", "webFetch", "webSearch")

        /**
         * Error-message signals that make retrying pointless.
         */
        private val NON_RETRYABLE_SIGNALS = listOf(
            "invalid syntax", "syntax error", "undefined variable",
            "import error", "module not found",
            "permission denied", "EACCES",
            "disk full", "no space left", "quota exceeded",
            "merge conflict", "conflict", "not a git repository",
            "stdin", "interactive",
        )

        fun isRetryable(toolName: String, errorMessage: String): Boolean {
            if (toolName in ALWAYS_RETRY) return true
            // Check for definitive non-retryable signals first
            for (signal in NON_RETRYABLE_SIGNALS) {
                if (errorMessage.contains(signal, ignoreCase = true)) return false
            }
            // Fall back to the auto-retryable list for other tools
            return toolName in AUTO_RETRYABLE_TOOLS
        }
    }
}
