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
            Regex.fromLiteral("yaml.load("),
            SecuritySeverity.HIGH,
            "Unsafe YAML deserialization",
            "Use yaml.safe_load() instead",
        ),
        SecurityPattern(
            Regex.fromLiteral("pickle.load("),
            SecuritySeverity.CRITICAL,
            "Unsafe pickle deserialization",
            "Use JSON or Protocol Buffers",
        ),
        SecurityPattern(
            Regex(".innerHTML\\s*=", RegexOption.IGNORE_CASE),
            SecuritySeverity.HIGH,
            "Direct HTML injection (XSS risk)",
            "Use safe DOM APIs like textContent",
        ),
        SecurityPattern(
            Regex("(password|secret|api[_-]?key|credential|auth_token|access_token|bearer)\\s*[:=]\\s*['\"][^'\"]{4,}['\"]", RegexOption.IGNORE_CASE),
            SecuritySeverity.CRITICAL,
            "Hardcoded credential detected",
            "Use environment variables or a secret manager",
        ),
        SecurityPattern(
            Regex("\\bexec\\s*\\(|\\beval\\s*\\(|Runtime\\.getRuntime\\(\\)\\.exec|ProcessBuilder\\s*\\(", RegexOption.IGNORE_CASE),
            SecuritySeverity.HIGH,
            "Dynamic code execution detected",
            "Avoid executing arbitrary strings as code",
        ),
        SecurityPattern(
            Regex("rm\\s+-rf|rmdir\\s+/s|del\\s+/f", RegexOption.IGNORE_CASE),
            SecuritySeverity.CRITICAL,
            "Destructive filesystem command detected",
            "Verify this is intended and the path is controlled",
        ),
        SecurityPattern(
            Regex("sql\\s*=\\s*['\"].*\\{.*\\b(?:select|insert|update|delete|drop)\\b", RegexOption.IGNORE_CASE),
            SecuritySeverity.HIGH,
            "SQL injection risk - string interpolation in query",
            "Use parameterized queries or prepared statements",
        ),
        SecurityPattern(
            Regex("(?:path|filePath|directory)\\s*[:=]\\s*['\"].*\\.\\.[/\\\\]", RegexOption.IGNORE_CASE),
            SecuritySeverity.MEDIUM,
            "Path traversal detected in file path argument",
            "Verify the resolved path is within allowed boundaries",
        ),
        // --- NEW PATTERNS ---
        // Server-Side Template Injection (SSTI)
        SecurityPattern(
            Regex("\\{\\{.*\\..*\\}\\}|<%.*%>|\\$\\{.*\\}", RegexOption.IGNORE_CASE),
            SecuritySeverity.HIGH,
            "Possible Server-Side Template Injection (SSTI)",
            "Avoid embedding user input directly in template strings",
        ),
        // SSRF via URL construction
        SecurityPattern(
            Regex("(url|uri|endpoint|webhook)\\s*[:=]\\s*['\"](https?://)?0\\.0\\.0\\.0|['\"]localhost['\"]|['\"]127\\.0\\.0\\.1['\"]|['\"]169\\.254\\.169\\.254['\"]", RegexOption.IGNORE_CASE),
            SecuritySeverity.HIGH,
            "Possible SSRF - request to internal address",
            "Validate and restrict URLs to prevent Server-Side Request Forgery",
        ),
        // Command injection via shell metacharacters
        SecurityPattern(
            Regex("['\"];\\s*(rm|cat|curl|wget|bash|sh|python|perl)\\b", RegexOption.IGNORE_CASE),
            SecuritySeverity.CRITICAL,
            "Command injection via shell metacharacter",
            "Avoid shelling out with unsanitized input; use safe APIs",
        ),
        // LDAP injection
        SecurityPattern(
            Regex("(ldap|ldaps?)://.*\\b(\\*|\\|\\(|&\\))", RegexOption.IGNORE_CASE),
            SecuritySeverity.HIGH,
            "Possible LDAP injection in query",
            "Use parameterized LDAP queries or escape special characters",
        ),
        // XML External Entity (XXE)
        SecurityPattern(
            Regex("<!DOCTYPE\\s+|<!ENTITY\\s+", RegexOption.IGNORE_CASE),
            SecuritySeverity.HIGH,
            "Possible XXE (XML External Entity) injection",
            "Disable DTD processing and external entity resolution in XML parsers",
        ),
        // NoSQL injection
        SecurityPattern(
            Regex("\\$ne|\\$gt|\\$regex|\\$where", RegexOption.IGNORE_CASE),
            SecuritySeverity.MEDIUM,
            "Possible NoSQL injection operator in query",
            "Sanitize and validate user input before using in NoSQL queries",
        ),
        // Insecure Random / Predictable PRNG
        SecurityPattern(
            Regex("Math\\.random\\(\\).*password|Math\\.random\\(\\).*token|Math\\.random\\(\\).*secret", RegexOption.IGNORE_CASE),
            SecuritySeverity.MEDIUM,
            "Insecure random number generator used for security-sensitive value",
            "Use SecureRandom or a CSPRNG for tokens/secrets",
        ),
        // Log Injection / Log Forging
        SecurityPattern(
            Regex("log\\.(info|warn|error|debug)\\(.*\\+.*(user|input|param|request)", RegexOption.IGNORE_CASE),
            SecuritySeverity.LOW,
            "Possible log injection - concatenating user input into log message",
            "Use parameterized logging to prevent log forging",
        ),
        // Prototype Pollution (JS/TS)
        SecurityPattern(
            Regex("__proto__|prototype\\s*\\[|constructor\\s*\\.\\s*prototype", RegexOption.IGNORE_CASE),
            SecuritySeverity.HIGH,
            "Possible prototype pollution attack pattern",
            "Avoid merging untrusted objects; use Object.create(null) or Map",
        ),
        // Open Redirect
        SecurityPattern(
            Regex("(redirect|forward|next|returnTo|callback)\\s*[:=]\\s*['\"](https?://)?[^'\"]*['\"]", RegexOption.IGNORE_CASE),
            SecuritySeverity.MEDIUM,
            "Possible open redirect - URL redirect controlled by input",
            "Validate redirect URLs against an allowlist",
        ),
    )

    private val commandPatterns = listOf(
        SecurityPattern(
            Regex("rm\\s+-rf", RegexOption.IGNORE_CASE),
            SecuritySeverity.CRITICAL,
            "Destructive filesystem command (rm -rf)",
            "Verify this is intended and the path is controlled",
        ),
        SecurityPattern(
            Regex("mkfs\\b|dd\\s+if=|>\\s*/dev|>>\\s*/dev", RegexOption.IGNORE_CASE),
            SecuritySeverity.CRITICAL,
            "Dangerous disk/device operation",
            "Do not run commands that modify disk devices",
        ),
        SecurityPattern(
            Regex("curl\\s+.*\\|\\s*bash|wget\\s+.*\\|\\s*bash|curl\\s+.*\\|\\s*sh", RegexOption.IGNORE_CASE),
            SecuritySeverity.CRITICAL,
            "Piping remote script to shell",
            "Download the script separately and verify it before running",
        ),
        SecurityPattern(
            Regex(":\\(\\)\\s*\\{|fork\\s+bomb", RegexOption.IGNORE_CASE),
            SecuritySeverity.CRITICAL,
            "Fork bomb or shellshock pattern detected",
            "Do not execute fork bombs or shell exploits",
        ),
        SecurityPattern(
            Regex("\\bsudo\\b", RegexOption.IGNORE_CASE),
            SecuritySeverity.HIGH,
            "Sudo command - requires elevated privileges",
            "Avoid using sudo; the agent should operate within user permissions",
        ),
        SecurityPattern(
            Regex("chmod\\s+777", RegexOption.IGNORE_CASE),
            SecuritySeverity.HIGH,
            "Overly permissive file permissions",
            "Avoid 777 permissions; use more restrictive modes",
        ),
    )

    override suspend fun evaluate(context: HookContext): HookResult {
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
