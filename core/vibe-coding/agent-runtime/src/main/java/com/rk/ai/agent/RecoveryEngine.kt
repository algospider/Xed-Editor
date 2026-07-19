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

class RecoveryEngine {

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

        return null
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
                // Always returns true — the caller should retry with a fresh timeout
                Log.i(TAG, "Recovery: retrying with fresh timeout")
                true
            }

            "skip_missing_file" -> {
                // Inform the caller to skip this operation; the file doesn't exist
                Log.i(TAG, "Recovery: skipping missing file: ${action.params["path"]}")
                true
            }

            "permission_error" -> {
                // Cannot auto-resolve permission errors — report and let the caller decide
                Log.w(TAG, "Recovery: permission error at ${action.params["path"]} — cannot auto-resolve")
                false
            }

            "fix_json" -> {
                // JSON format issues need LLM intervention, not auto-fixable here
                Log.w(TAG, "Recovery: JSON error — needs LLM intervention, not auto-fixing")
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

    companion object {
        val AUTO_RETRYABLE_TOOLS = setOf(
            "readFile", "readFiles", "cat", "writeFile", "editFile",
            "createFile", "deleteFile", "renameFile",
            "listFiles", "ls", "findFiles", "glob",
            "head", "tail", "wc", "countLines", "stat",
            "runCommand", "getFileContent",
        )

        val ALWAYS_RETRY = setOf("runCommand", "webFetch", "webSearch")

        fun isRetryable(toolName: String, errorMessage: String): Boolean {
            if (toolName in ALWAYS_RETRY) return true
            if (toolName in AUTO_RETRYABLE_TOOLS) return true
            val nonRetryableSignals = listOf(
                "invalid syntax", "syntax error", "undefined variable",
                "import error", "module not found",
                "permission denied", "EACCES",
            )
            return nonRetryableSignals.none { errorMessage.contains(it, ignoreCase = true) }
        }
    }
}
