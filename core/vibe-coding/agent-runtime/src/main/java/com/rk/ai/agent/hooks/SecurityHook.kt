package com.rk.ai.agent.hooks

import kotlin.text.Regex

data class SecurityPattern(
    val pattern: Regex,
    val severity: SecuritySeverity,
    val description: String,
    val suggestion: String,
)

enum class SecuritySeverity { LOW, MEDIUM, HIGH, CRITICAL }

typealias SecurityAlertCallback = (String, String, String?, String?) -> Unit

class SecurityHook(
    private val onAlert: SecurityAlertCallback? = null,
) : ToolHook {

    private val contentPatterns = listOf(
        SecurityPattern(
            Regex("""(?i)\byaml\.load\s*\(|yaml\.load_all\s*\("""),
            SecuritySeverity.HIGH,
            "Unsafe YAML deserialization - can lead to remote code execution",
            "Use yaml.safe_load() instead",
        ),
        SecurityPattern(
            Regex("""(?i)\bpickle\.load\s*\(|pickle\.loads\s*\(|cPickle\.loads?\s*\("""),
            SecuritySeverity.CRITICAL,
            "Unsafe pickle deserialization - can execute arbitrary code",
            "Use a safer serialization format like JSON or Protocol Buffers",
        ),
        SecurityPattern(
            Regex("""(?i)(?:\.innerHTML|\.outerHTML|dangerouslySetInnerHTML)\s*="""),
            SecuritySeverity.HIGH,
            "Direct HTML injection (XSS risk)",
            "Use safe DOM APIs like textContent or a sanitization library",
        ),
        SecurityPattern(
            Regex("""(?i)(?:password|secret|api[_-]?key|credential)\s*[:=]\s*['\"][^'"]{4,}['\"]"""),
            SecuritySeverity.CRITICAL,
            "Hardcoded credential detected",
            "Use environment variables or a secret manager instead",
        ),
        SecurityPattern(
            Regex("""(?i)\bexec\s*\(|\beval\s*\(|Runtime\.getRuntime\(\)\.exec|ProcessBuilder\s*\("""),
            SecuritySeverity.HIGH,
            "Dynamic code execution detected",
            "Avoid executing arbitrary strings as code",
        ),
        SecurityPattern(
            Regex("""(?i)(?:rm\s+-rf(?:\s+|$)|rmdir\s+/s|del\s+/f)"""),
            SecuritySeverity.CRITICAL,
            "Destructive filesystem command detected",
            "Verify this is intended and the path is controlled",
        ),
        SecurityPattern(
            Regex("""(?i)\bsql\s*=\s*['\"].*\{.*\b(?:select|insert|update|delete|drop)\b"""),
            SecuritySeverity.HIGH,
            "SQL injection risk - string interpolation in query",
            "Use parameterized queries or prepared statements",
        ),
        SecurityPattern(
            Regex("""(?i)(?:path|filePath|directory)\s*[:=]\s*['\"].*\.\.[/\\]"""),
            SecuritySeverity.MEDIUM,
            "Path traversal detected in file path argument",
            "Verify the resolved path is within allowed boundaries",
        ),
    )

    /** Patterns that are checked against command strings passed to runCommand */
    private val commandPatterns = listOf(
        SecurityPattern(
            Regex("""(?i)\brm\s+-rf\b"""),
            SecuritySeverity.CRITICAL,
            "Destructive filesystem command (rm -rf)",
            "Verify this is intended and the path is controlled",
        ),
        SecurityPattern(
            Regex("""(?i)\bmkfs\b|\bdd\b\s+if=|>?\s*/dev"),
            SecuritySeverity.CRITICAL,
            "Dangerous disk/device operation",
            "Do not run commands that modify disk devices",
        ),
        SecurityPattern(
            Regex("""(?i)\bcurl\s+.*\|\s*bash\b|\bwget\s+.*\|\s*bash\b|\bcurl\s+.*\|\s*sh\b"""),
            SecuritySeverity.CRITICAL,
            "Piping remote script to shell - potential remote code execution",
            "Download the script separately and verify it before running",
        ),
        SecurityPattern(
            Regex("""(?i):\(\)\s*\{|fork\s+bomb|\\\\x[0-9a-f]{2}\s*\{\s*:"""),
            SecuritySeverity.CRITICAL,
            "Fork bomb or shellshock pattern detected",
            "Do not execute fork bombs or shell exploits",
        ),
        SecurityPattern(
            Regex("""(?i)\bsudo\b"""),
            SecuritySeverity.HIGH,
            "Sudo command - requires elevated privileges",
            "Avoid using sudo; the agent should operate within user permissions",
        ),
        SecurityPattern(
            Regex("""(?i)\bchmod\s+777\b"""),
            SecuritySeverity.HIGH,
            "Overly permissive file permissions",
            "Avoid 777 permissions; use more restrictive modes",
        ),
    )

    override suspend fun evaluate(context: HookContext): HookResult {
        // Check command strings (runCommand tool)
        if (context.command != null) {
            val cmdFindings = commandPatterns.filter { it.pattern.containsMatchIn(context.command) }
            if (cmdFindings.isNotEmpty()) {
                val highestSeverity = cmdFindings.maxOf { it.severity }
                val messages = cmdFindings.joinToString("\n") {
                    "[${it.severity.name}] ${it.description}\n  Suggestion: ${it.suggestion}"
                }
                cmdFindings.forEach { finding ->
                    onAlert?.invoke(finding.severity.name, finding.description, context.toolName, context.filePath)
                }
                return when {
                    highestSeverity >= SecuritySeverity.CRITICAL -> HookResult.Block(
                        "Security blocked: Dangerous command detected:\n$messages"
                    )
                    else -> HookResult.Warn("Security warning for command:\n$messages")
                }
            }
            return HookResult.Allow
        }

        // Check file content (writeFile, editFile, etc.)
        val content = context.newContent ?: context.args["content"]?.toString() ?: return HookResult.Allow

        val findings = contentPatterns.filter { it.pattern.containsMatchIn(content) }

        if (findings.isEmpty()) return HookResult.Allow

        val highestSeverity = findings.maxOf { it.severity }
        val messages = findings.joinToString("\n") {
            "[${it.severity.name}] ${it.description}\n  Suggestion: ${it.suggestion}"
        }

        findings.forEach { finding ->
            onAlert?.invoke(finding.severity.name, finding.description, context.toolName, context.filePath)
        }

        return when {
            highestSeverity >= SecuritySeverity.CRITICAL -> HookResult.Block(
                "Security blocked: Potential security issues detected:\n$messages"
            )
            highestSeverity >= SecuritySeverity.HIGH -> HookResult.Warn(
                "Security warning:\n$messages"
            )
            else -> HookResult.Allow
        }
    }
}
