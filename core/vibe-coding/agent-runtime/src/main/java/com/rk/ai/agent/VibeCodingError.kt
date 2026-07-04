package com.rk.ai.agent

sealed class VibeCodingError(
    open val message: String,
    open val cause: Throwable? = null,
) {
    override fun toString(): String = buildString {
        append("[${this@VibeCodingError::class.simpleName}]")
        append(" $message")
        if (cause != null) append(" (cause: ${cause?.message})")
    }

    // ── Tool errors ──
    sealed class ToolError(message: String, cause: Throwable? = null) : VibeCodingError(message, cause) {
        data class NotFound(val toolName: String) : ToolError("Tool '$toolName' not found in registry")
        data class ExecutionFailed(val toolName: String, override val cause: Throwable) : ToolError("Tool '$toolName' execution failed", cause)
        data class InvalidArgs(val toolName: String, val validationErrors: List<String>) : ToolError("Invalid arguments for '$toolName': ${validationErrors.joinToString("; ")}")
        data class PermissionDenied(val toolName: String, val reason: String) : ToolError("Permission denied for '$toolName': $reason")
        data class ValidationError(val toolName: String, val schemaPath: String, val actual: String) : ToolError("Validation failed for '$toolName' at $schemaPath: expected $actual")
    }

    // ── Config errors ──
    sealed class ConfigError(message: String, cause: Throwable? = null) : VibeCodingError(message, cause) {
        data class ParseError(val configPath: String, override val cause: Throwable) : ConfigError("Failed to parse config at $configPath", cause)
        data class NotFound(val configPath: String) : ConfigError("Config not found at $configPath")
        data class ValidationError(val configPath: String, val field: String, val reason: String) : ConfigError("Config at $configPath has invalid field '$field': $reason")
    }

    // ── Agent errors ──
    sealed class AgentError(message: String, cause: Throwable? = null) : VibeCodingError(message, cause) {
        data class ExecutionFailed(val agentName: String, val taskId: String, override val cause: Throwable) : AgentError("Agent '$agentName' execution failed for task $taskId", cause)
        data class NotFound(val agentName: String) : AgentError("Agent '$agentName' not found")
        data class NotAvailable(val agentName: String, val reason: String) : AgentError("Agent '$agentName' not available: $reason")
        data class MaxStepsExceeded(val agentName: String, val taskId: String, val maxSteps: Int) : AgentError("Agent '$agentName' exceeded max steps ($maxSteps) for task $taskId")
    }

    // ── Generation errors ──
    sealed class GenerationError(message: String, cause: Throwable? = null) : VibeCodingError(message, cause) {
        data class ModelNotFound(val modelId: String) : GenerationError("Model '$modelId' not found in any provider")
        data class ProviderFailed(val modelId: String, val providerName: String, override val cause: Throwable) : GenerationError("Provider '$providerName' failed for model '$modelId'", cause)
        data class CompactionFailed(override val cause: Throwable) : GenerationError("Context compaction failed", cause)
        data class MaxCompactionsExceeded(val count: Int) : GenerationError("Max compactions exceeded ($count)")
        data class DoomLoopDetected(val toolName: String) : GenerationError("Doom loop detected: repeated calls to '$toolName'")
        data class ContextOverflow(val contextUsed: Int, val contextLimit: Int) : GenerationError("Context overflow: $contextUsed/$contextLimit tokens used")
    }

    // ── Persistence errors ──
    sealed class PersistenceError(message: String, cause: Throwable? = null) : VibeCodingError(message, cause) {
        data class DatabaseError(override val cause: Throwable) : PersistenceError("Database operation failed", cause)
        data class SerializationError(override val cause: Throwable) : PersistenceError("Serialization failed", cause)
    }

    // ── Security errors ──
    sealed class SecurityError(message: String, cause: Throwable? = null) : VibeCodingError(message, cause) {
        data class Blocked(val toolName: String, val pattern: String, val severity: String) : SecurityError("Blocked by security hook: $pattern ($severity) in tool '$toolName'")
        data class Warning(val toolName: String, val pattern: String, val severity: String) : SecurityError("Security warning: $pattern ($severity) in tool '$toolName'")
    }

    // ── Plugin errors ──
    sealed class PluginError(message: String, cause: Throwable? = null) : VibeCodingError(message, cause) {
        data class LoadFailed(val pluginId: String, override val cause: Throwable) : PluginError("Plugin '$pluginId' failed to load", cause)
        data class UnsupportedVersion(val pluginId: String, val version: String, val minVersion: String) : PluginError("Plugin '$pluginId' requires version $version, minimum supported is $minVersion")
    }

    // ── File errors ──
    sealed class FileError(message: String, cause: Throwable? = null) : VibeCodingError(message, cause) {
        data class NotFound(val path: String) : FileError("File not found at '$path'")
        data class ReadFailed(val path: String, override val cause: Throwable) : FileError("Failed to read '$path'", cause)
        data class WriteFailed(val path: String, override val cause: Throwable) : FileError("Failed to write '$path'", cause)
        data class ParseFailed(val path: String, override val cause: Throwable) : FileError("Failed to parse '$path'", cause)
    }
}

fun VibeCodingError.toUserMessage(): String = when (this) {
    is VibeCodingError.ToolError.NotFound -> "[RECOVERY] Tool '${toolName}' is not available. Available tools are listed in the system prompt. Check for typos."
    is VibeCodingError.ToolError.ExecutionFailed -> "[RECOVERY] Tool '${toolName}' failed: ${cause.message}. Try with different parameters or a different tool."
    is VibeCodingError.ToolError.InvalidArgs -> "[RECOVERY] Invalid arguments for '${toolName}': ${validationErrors.joinToString("; ")}. Check the parameter schema and ensure all required fields are present with correct types."
    is VibeCodingError.ToolError.PermissionDenied -> "[RECOVERY] Permission denied for '${toolName}': $reason. This tool requires user approval — ask the user to approve it."
    is VibeCodingError.ToolError.ValidationError -> "[RECOVERY] Schema validation failed for '${toolName}': expected $actual at $schemaPath. Verify argument types match."
    is VibeCodingError.GenerationError.ModelNotFound -> "[RECOVERY] Model '$modelId' is not configured. Add it in AI Settings → Model Configuration."
    is VibeCodingError.GenerationError.ProviderFailed -> "[RECOVERY] Provider '$providerName' returned an error: ${cause.message}. Check network or provider API key settings."
    is VibeCodingError.GenerationError.ContextOverflow -> "[RECOVERY] Context limit reached ($contextUsed/$contextLimit tokens). Try simplifying the request or starting a fresh conversation."
    is VibeCodingError.GenerationError.DoomLoopDetected -> "[RECOVERY] I noticed a loop calling '$toolName'. Trying a different approach now."
    is VibeCodingError.GenerationError.MaxCompactionsExceeded -> "[RECOVERY] Max compactions reached ($count). Too much context has accumulated. Consider summarizing and starting fresh."
    is VibeCodingError.SecurityError.Blocked -> "[RECOVERY] Blocked for security: $pattern ($severity) in tool '$toolName'. Operation not permitted."
    is VibeCodingError.SecurityError.Warning -> "[RECOVERY] Security warning: $pattern ($severity) in tool '$toolName'. Review the content before proceeding."
    is VibeCodingError.FileError.NotFound -> "[RECOVERY] File '$path' was not found. Use getProjectStructure or listFiles to verify the path exists, or check for typos."
    is VibeCodingError.FileError.ReadFailed -> "[RECOVERY] Could not read '$path': ${cause.message}. File may be locked or permissions may be insufficient."
    is VibeCodingError.FileError.WriteFailed -> "[RECOVERY] Could not write '$path': ${cause.message}. Check if parent directories exist (use createFile first)."
    is VibeCodingError.FileError.ParseFailed -> "[RECOVERY] Failed to parse '$path': ${cause.message}. Check file format."
    is VibeCodingError.ConfigError.ParseError -> "[RECOVERY] Failed to parse configuration in '$configPath'. Check for syntax errors."
    is VibeCodingError.ConfigError.NotFound -> "[RECOVERY] Config not found at '$configPath'. It may have been moved or not yet created."
    is VibeCodingError.ConfigError.ValidationError -> "[RECOVERY] Config at '$configPath' has invalid field '$field': $reason. Edit the config to fix it."
    is VibeCodingError.AgentError.ExecutionFailed -> "[RECOVERY] Agent '$agentName' encountered an error: ${cause.message}. Try a simpler request."
    is VibeCodingError.AgentError.NotFound -> "[RECOVERY] Agent '$agentName' is not available. Use listAgents to see available agents."
    is VibeCodingError.AgentError.MaxStepsExceeded -> "[RECOVERY] Agent '$agentName' exceeded max steps ($maxSteps). Task too complex — break it into smaller parts."
    is VibeCodingError.PluginError.LoadFailed -> "[RECOVERY] Plugin '$pluginId' failed to load: ${cause.message}. Check plugin configuration."
    is VibeCodingError.PluginError.UnsupportedVersion -> "[RECOVERY] Plugin '$pluginId' requires version $version, minimum supported is $minVersion. Update the plugin."
    is VibeCodingError.PersistenceError.DatabaseError -> "[RECOVERY] Database operation failed: ${cause.message}. Try restarting the app."
    is VibeCodingError.PersistenceError.SerializationError -> "[RECOVERY] Serialization failed: ${cause.message}. Data may be corrupted."
    else -> "[RECOVERY] $message"
}

data class RecoveryHint(
    val action: String,
    val message: String,
    val nextTool: String? = null,
    val nextArgs: Map<String, Any>? = null,
)

fun deriveRecoveryHint(toolName: String, errorMessage: String): RecoveryHint? {
    val msg = errorMessage.lowercase()
    return when {
        msg.contains("not found") && toolName in listOf("readFile", "readFiles", "editFile", "writeFile", "deleteFile", "renameFile", "tail", "wc", "stat") ->
            RecoveryHint("list_directory", "File not found. List the parent directory to verify the path.", "listFiles", mapOf("path" to "/"))
        msg.contains("multiple matches") ->
            RecoveryHint("add_context", "Found multiple matches. Add more surrounding lines to oldString or use replaceAll=true.")
        msg.contains("not found") && toolName in listOf("searchCode", "searchSymbols") ->
            RecoveryHint("broaden_search", "Search returned nothing. Try a broader term, different case, or check file extensions.")
        msg.contains("timeout") || msg.contains("timed out") ->
            RecoveryHint("retry_timeout", "Operation timed out. Try with a smaller scope or increase timeout.")
        msg.contains("permission") || msg.contains("denied") ->
            RecoveryHint("check_permissions", "Permission denied. Ensure the file is not read-only and you have access.")
        msg.contains("network") || msg.contains("connect") || msg.contains("dns") ->
            RecoveryHint("check_network", "Network error. Check connectivity and retry.")
        msg.contains("no space") || msg.contains("disk") ->
            RecoveryHint("free_space", "Disk space issue. Free up space on the device.")
        toolName == "runCommand" && msg.contains("not found") ->
            RecoveryHint("install_tool", "Command not found. The tool may need to be installed first.")
        toolName in listOf("gitCommit", "gitPush", "gitCheckout", "gitBranch") && msg.contains("not a git repository") ->
            RecoveryHint("init_git", "Not a git repository. Initialize with 'git init' first.")
        else -> null
    }
}
