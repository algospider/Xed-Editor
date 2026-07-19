package com.rk.ai.agent.review

import android.util.Log
import com.rk.ai.agent.context.ContextBundle
import com.rk.ai.models.ExecutionState
import com.rk.ai.models.UIMessagePart

private const val TAG = "SelfReviewer"

data class ReviewReport(
    val passed: Boolean,
    val score: Int = 100,
    val feedback: String = "",
    val suggestions: List<String> = emptyList(),
    val missingInfo: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val patterns: List<String> = emptyList(),
    val edgeCases: List<String> = emptyList(),
    val securityChecks: List<String> = emptyList(),
    val qualityFlags: List<String> = emptyList(),
)

class SelfReviewer(
    /** After how many reads of the same file path we emit a warning. */
    private val maxReadsBeforeWarning: Int = 3,
) {
    /** Tracks how many times each file path has been read this generation. */
    private val readHistory = mutableMapOf<String, Int>()

    /** Tools that read file content. */
    private val readTools = setOf(
        "readFile", "readFiles", "readAndEdit", "cat", "getFileContent",
    )

    /** Tracks edited files and their operations for cross-file consistency checks. */
    private val trackedEdits = mutableListOf<Pair<String, String>>()

    fun reviewToolResults(
        toolName: String,
        toolInput: String,
        result: List<UIMessagePart>,
        executionState: ExecutionState,
        context: ContextBundle?,
        loopInfo: LoopInfo? = null,
        filePath: String? = null,
    ): ReviewReport {
        val issues = mutableListOf<String>()
        val suggestions = mutableListOf<String>()
        val missingInfo = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val patterns = mutableListOf<String>()
        val edgeCases = mutableListOf<String>()
        val securityChecks = mutableListOf<String>()
        val qualityFlags = mutableListOf<String>()

        if (executionState is ExecutionState.Error) {
            issues.add("Tool '$toolName' failed: ${executionState.error}")
            val suggestion = generateSuggestion(toolName, executionState.error)
            if (suggestion != null) suggestions.add(suggestion)
            return ReviewReport(
                passed = false,
                score = 0,
                feedback = issues.joinToString("\n"),
                suggestions = suggestions,
                missingInfo = listOf("Tool execution error"),
            )
        }

        val outputText = result.joinToString("\n") { part ->
            if (part is UIMessagePart.Text) part.text else ""
        }

        if (outputText.isBlank() || outputText == "null" || outputText == "[]") {
            issues.add("Tool returned empty result")
            suggestions.add("Tool '$toolName' returned no output - check if the input was valid")
        }

        // ── Loop detection awareness ──────────────────────────────────────
        if (loopInfo != null) {
            when (loopInfo.severity) {
                LoopSeverity.CRITICAL -> {
                    issues.add("CRITICAL LOOP: ${loopInfo.description}")
                    suggestions.add(loopInfo.suggestion)
                    patterns.add("critical-loop")
                }
                LoopSeverity.WARNING -> {
                    warnings.add("Loop warning: ${loopInfo.description}")
                    suggestions.add(loopInfo.suggestion)
                    patterns.add("warning-loop")
                }
            }
        }

        // ── Redundant read detection ──────────────────────────────────────
        if (filePath != null && toolName in readTools) {
            val count = readHistory.getOrDefault(filePath, 0) + 1
            readHistory[filePath] = count
            if (count >= maxReadsBeforeWarning) {
                val msg = "File '$filePath' read $count times — consider caching its content"
                if (count > maxReadsBeforeWarning + 2) {
                    issues.add(msg)
                } else {
                    warnings.add(msg)
                }
                suggestions.add(
                    "You've read '$filePath' $count times. Cache its content rather than re-reading. " +
                        "Use readFiles([]) to batch reads, or readAndEdit to read+edit in one call."
                )
                patterns.add("redundant-read")
            }
        }

        // ── Tool-specific review ─────────────────────────────────────────
        if (executionState is ExecutionState.Completed) {
            when {
                toolName == "getFileContent" || toolName == "readFile" || toolName == "cat" -> {
                    if (outputText.contains("null")) {
                        issues.add("File content is null - file may not exist")
                        suggestions.add("Verify the file path exists and try alternative paths")
                    }
                    if (outputText.length < 100 && !outputText.contains("error", ignoreCase = true)) {
                        missingInfo.add("Content seems truncated or unexpectedly short")
                        suggestions.add("File may be empty or not fully read - consider specifying line range")
                    }
                }

                toolName == "writeFile" || toolName == "editFile" || toolName == "applyBatchEdits" -> {
                    if (outputText.contains("Error", ignoreCase = true) || outputText.contains("Failed", ignoreCase = true)) {
                        issues.add("Write/Edit operation reported errors")
                    }
                    if (toolInput.contains(".kt") || toolInput.contains(".java") || toolInput.contains(".kts")) {
                        patterns.add("Code file modified - should run getDiagnostics to verify")
                        suggestions.add("Run getDiagnostics on the modified file")
                    }
                    // Edit-input validation
                    if (toolName == "editFile") {
                        validateEditFileInput(toolInput, issues, suggestions)
                    } else if (toolName == "applyBatchEdits") {
                        if (toolInput.contains("oldString") && (toolInput.contains("\"\"") || toolInput.contains("''"))) {
                            warnings.add("Batch edit contains empty oldStrings — may cause unintended replacements")
                            suggestions.add("Verify each edit's oldString is non-empty and specific enough")
                        }
                    }
                }

                toolName == "listFiles" || toolName == "ls" -> {
                    if (outputText.isBlank() || outputText == "[]") {
                        issues.add("Directory appears empty - may not exist")
                        suggestions.add("Check the parent directory exists and try expanding the path")
                    }
                }

                toolName == "searchCode" || toolName == "grep" || toolName == "searchSymbols" -> {
                    if (outputText.contains("No results") || outputText.startsWith("No ")) {
                        issues.add("Search returned no results")
                        suggestions.add("Try different search terms or check file extensions")
                    }
                    if (outputText.length > 5000) {
                        patterns.add("Search returned many results - might need more specific query")
                    }
                }

                toolName == "runCommand" -> {
                    if (outputText.contains("error", ignoreCase = true) && !outputText.contains("0 error", ignoreCase = true)) {
                        issues.add("Command reported errors")
                        securityChecks.add("Check command output for compilation errors")
                    }
                    if (outputText.contains("warning", ignoreCase = true) && !outputText.contains("0 warning", ignoreCase = true)) {
                        warnings.add("Command reported warnings")
                        qualityFlags.add("Consider fixing warnings for cleaner code")
                    }
                    if (outputText.length > 10000) {
                        patterns.add("Command output is very large - check for relevant error messages")
                    }
                }

                toolName == "getDiagnostics" -> {
                    if (outputText.contains("error", ignoreCase = true)) {
                        issues.add("Diagnostics found errors that need fixing")
                        qualityFlags.add("All diagnostics errors must be resolved before task completion")
                    }
                    if (outputText.contains("warning", ignoreCase = true)) {
                        warnings.add("Diagnostics found warnings - consider addressing them")
                    }
                }

                toolName == "gitCommit" || toolName == "gitPush" -> {
                    if (outputText.contains("error", ignoreCase = true) || outputText.contains("failed", ignoreCase = true)) {
                        issues.add("Git operation failed")
                        suggestions.add("Check git status and resolve conflicts before retrying")
                    }
                }
            }
        }

        val shortOkTools = setOf(
            "writeFile", "createFile", "deleteFile", "renameFile",
            "showMessage", "writeToClipboard", "rejectDiff",
            "recordSuggestionFeedback",
        )
        if (toolName !in shortOkTools && toolName !in readTools) {
            if (outputText.length in 1..50 && !outputText.contains("error", ignoreCase = true)) {
                missingInfo.add("Output seems unexpectedly short (${outputText.length} chars)")
                suggestions.add("The tool returned very little data - verify the input was correct")
            }
        }

        if (outputText.isNotBlank()) {
            val securityPatterns = listOf(
                Regex("""["']api[_-]?key["']\s*[:=]\s*["'][^"']{8,}["']""", RegexOption.IGNORE_CASE) to "Hardcoded API key detected",
                Regex("""["']password["']\s*[:=]\s*["'][^"']{4,}["']""", RegexOption.IGNORE_CASE) to "Hardcoded password detected",
                Regex("""["']secret["']\s*[:=]\s*["'][^"']{4,}["']""", RegexOption.IGNORE_CASE) to "Hardcoded secret detected",
                Regex("""["']token["']\s*[:=]\s*["'][^"']{8,}["']""", RegexOption.IGNORE_CASE) to "Hardcoded token detected",
                Regex("""-----BEGIN\s+(RSA|DSA|EC|PGP|OPENSSH)\s+PRIVATE\s+KEY-----""") to "Private key in output",
            )
            for ((pattern, warning) in securityPatterns) {
                if (pattern.containsMatchIn(outputText)) {
                    securityChecks.add(warning)
                }
            }

            val qualityPatterns = mapOf(
                Regex("""(?i)\bTODO\b""") to "TODO in code",
                Regex("""(?i)\bFIXME\b""") to "FIXME in code",
                Regex("""(?i)\bHACK\b""") to "HACK in code",
                Regex("""(?i)\bXXX\b""") to "XXX in code",
                Regex("""System\.out\.print""") to "System.out usage - prefer logger",
                Regex("""printStackTrace""") to "printStackTrace - prefer logger",
                Regex("""\.printStackTrace\(\)""") to "printStackTrace in catch block",
                Regex("""\bnull\s*!""") to "!! assertion may cause NPE",
                Regex("""as\s+\w+""") to "Unsafe cast - prefer safe cast (as?)",
                Regex("""@Suppress""") to "Suppressed warnings - should verify",
            )
            for ((pattern, warning) in qualityPatterns) {
                if (pattern.containsMatchIn(outputText)) {
                    qualityFlags.add("$warning (matched pattern)")
                }
            }
        }

        val allIssues = issues + warnings.map { "Warning: $it" } +
            securityChecks.map { "Security: $it" } + qualityFlags.map { "Quality: $it" }

        return ReviewReport(
            passed = issues.isEmpty() && securityChecks.filter {
                it.contains("API key", ignoreCase = true) ||
                    it.contains("password", ignoreCase = true) ||
                    it.contains("secret", ignoreCase = true) ||
                    it.contains("private key", ignoreCase = true)
            }.isEmpty(),
            score = when {
                issues.isNotEmpty() -> 30
                securityChecks.isNotEmpty() -> 50
                qualityFlags.isNotEmpty() -> 70
                warnings.isNotEmpty() -> 85
                else -> 100
            },
            feedback = allIssues.joinToString("\n"),
            suggestions = suggestions.distinct(),
            missingInfo = missingInfo,
            warnings = warnings,
            patterns = patterns.distinct(),
            edgeCases = edgeCases.distinct(),
            securityChecks = securityChecks.distinct(),
            qualityFlags = qualityFlags.distinct(),
        )
    }

    /**
     * Validates an editFile input — checks that oldString is present, non-empty,
     * and has reasonable length for a targeted edit.
     */
    private fun validateEditFileInput(
        input: String,
        issues: MutableList<String>,
        suggestions: MutableList<String>,
    ) {
        val oldStringMatch = Regex(""""oldString"\s*:\s*"((?:[^"\\]|\\.)*)""").find(input)
        if (oldStringMatch == null) {
            issues.add("editFile input missing 'oldString' field")
            suggestions.add("Include the exact text to replace as 'oldString' in the editFile arguments")
            return
        }
        val oldString = oldStringMatch.groupValues[1]
        if (oldString.isBlank()) {
            issues.add("editFile 'oldString' is empty — cannot perform targeted edit")
            suggestions.add("Provide the exact text to replace; use writeFile for full-file writes")
        } else if (oldString.length < 3) {
            warnings.add("editFile 'oldString' is very short (${oldString.length} chars) — may match unintended locations")
            suggestions.add("Include more surrounding context in oldString to ensure the correct block is replaced")
        }
    }

    fun shouldRetry(report: ReviewReport, attempt: Int, maxAttempts: Int): Boolean {
        if (attempt >= maxAttempts) return false

        // Security issues require a different approach, not a simple retry
        if (report.securityChecks.isNotEmpty()) return false

        // If score is high enough, no need to retry
        if (report.score >= 90) return false

        // If there are concrete issues, retry (up to maxAttempts)
        if (report.issues.isNotEmpty()) return true

        // If information is missing but no errors, retry with better input
        if (report.missingInfo.isNotEmpty() && report.score < 70) return true

        // Low score with warnings only — retry
        if (report.score < 70) return true

        return false
    }

    /** Records an edit operation for cross-file consistency analysis. */
    fun trackEdit(filePath: String, action: String) {
        if (trackedEdits.size >= 100) {
            trackedEdits.removeAt(0)
        }
        trackedEdits.add(filePath to action)
    }

    /** Scans tracked edits for cross-file consistency issues. */
    fun crossFileConsistencyCheck(): ReviewReport {
        val warnings = mutableListOf<String>()
        val suggestions = mutableListOf<String>()
        val patterns = mutableListOf<String>()

        if (trackedEdits.isEmpty()) return ReviewReport(passed = true, score = 100)

        // Group edits by file
        val editsByFile = trackedEdits.groupBy { it.first }
            .mapValues { (_, edits) -> edits.map { it.second } }

        // Check for files edited more than 3 times — suggest batching
        for ((filePath, actions) in editsByFile) {
            if (actions.size > 3) {
                warnings.add("File '$filePath' was edited ${actions.size} times — consider batching with multiEditFile or applyBatchEdits")
                suggestions.add("Use multiEditFile or applyBatchEdits to combine edits on '$filePath' into a single call")
                patterns.add("frequent-edits")
            }
        }

        // Check for deleted/renamed files — warn about remaining imports/references
        val deletedRenamedFiles = editsByFile.keys.filter { filePath ->
            editsByFile[filePath]?.any { it == "deleteFile" || it == "renameFile" } == true
        }
        if (deletedRenamedFiles.isNotEmpty()) {
            warnings.add("Files were deleted or renamed: ${deletedRenamedFiles.joinToString(", ")} — verify no remaining imports or references")
            suggestions.add("Search the codebase for imports or references to deleted/renamed files and clean them up")
            patterns.add("file-removal")
        }

        // Multiple code files modified — suggest getDiagnostics on ALL
        val ktFiles = editsByFile.keys.filter { it.endsWith(".kt") || it.endsWith(".java") || it.endsWith(".kts") }
        if (ktFiles.size > 1) {
            suggestions.add("Multiple code files modified (${ktFiles.size}). Run getDiagnostics on all affected files to catch cross-file inconsistencies")
            patterns.add("cross-file-changes")
        }

        val allWarnings = warnings + suggestions.map { "Suggestion: $it" }

        return ReviewReport(
            passed = warnings.isEmpty(),
            score = if (warnings.isEmpty()) 100 else 80,
            feedback = allWarnings.joinToString("\n"),
            warnings = warnings,
            suggestions = suggestions.distinct(),
            patterns = patterns.distinct(),
        )
    }

    /** Clears per-generation tracking state. Call at the start of each task. */
    fun resetTracking() {
        readHistory.clear()
        trackedEdits.clear()
    }

    private fun generateSuggestion(toolName: String, error: String): String? {
        return when {
            error.contains("not found", ignoreCase = true) -> {
                if (toolName in listOf("readFile", "cat", "editFile", "writeFile")) {
                    "Verify the file path using getProjectStructure or listFiles, then retry"
                } else {
                    "Verify the path exists and try listing the parent directory"
                }
            }
            error.contains("permission", ignoreCase = true) -> "Check file permissions or try a different location"
            error.contains("timeout", ignoreCase = true) -> "Operation timed out - try a smaller scope or retry"
            error.contains("network", ignoreCase = true) || error.contains("connect", ignoreCase = true) -> "Network issue - check connectivity and retry"
            error.contains("multiple matches", ignoreCase = true) || error.contains("Found multiple", ignoreCase = true) -> "Provide more surrounding context in oldString, or use replaceAll=true"
            error.contains("Could not find", ignoreCase = true) || error.contains("not found the specified text", ignoreCase = true) -> "Check exact whitespace and content in the file, use dryRun=true first to verify"
            error.contains("syntax", ignoreCase = true) || error.contains("parse", ignoreCase = true) -> "Syntax error in tool input — check JSON formatting and special characters"
            error.contains("invalid", ignoreCase = true) && error.contains("argument", ignoreCase = true) -> "Invalid argument — check tool documentation for expected parameter names and types"
            else -> null
        }
    }
}
