@file:OptIn(ExperimentalUuidApi::class)

package com.rk.ai.agent.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import com.rk.ai.models.InputSchema
import com.rk.ai.models.Tool
import com.rk.ai.models.UIMessagePart
import com.rk.ai.service.IdeService
import com.rk.settings.Settings
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI

private const val GITHUB_API = "https://api.github.com"
private const val GITHUB_API_TIMEOUT_MS = 20_000
private val REPO_FORMAT = Regex("^[\\w.-]+/[\\w.-]+\$")

class VibeCodingGitHubTools(private val ideService: IdeService) {

    private fun validateRepo(repo: String): UIMessagePart.Text? =
        if (!repo.matches(REPO_FORMAT))
            UIMessagePart.Text("ERROR: Repo must be in 'owner/repo' format (e.g. 'torvalds/linux'). You passed: '$repo'")
        else null

    private fun JsonObject.decodeBase64Content(): String? {
        val raw = get("content")?.asString?.replace("\n", "") ?: return null
        return java.util.Base64.getDecoder().decode(raw).toString(Charsets.UTF_8)
    }

    private fun githubApiGet(urlStr: String): String {
        val conn = URI(urlStr).toURL().openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = GITHUB_API_TIMEOUT_MS
            conn.readTimeout = GITHUB_API_TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "Xed-Editor/2.0")
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")

            val responseCode = conn.responseCode
            if (responseCode == 403) {
                val resetTime = conn.getHeaderField("X-RateLimit-Reset")?.toLongOrNull()
                val msg = if (resetTime != null) {
                    val waitSec = (resetTime * 1000 - System.currentTimeMillis()) / 1000
                    "GitHub API rate limited. Resets in ${waitSec}s"
                } else "GitHub API rate limited"
                throw RuntimeException(msg)
            }
            if (responseCode == 404) throw RuntimeException("Not found (404)")

            val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            stream.bufferedReader().use { it.readText() }
        } finally {
            try { conn.errorStream?.use { it.readBytes() } } catch (_: Exception) { }
            try { conn.inputStream?.use { it.readBytes() } } catch (_: Exception) { }
            conn.disconnect()
        }
    }

    private val githubRepoInfo = Tool(
        name = "githubRepoInfo",
        description = "Get GitHub repo metadata: stars, forks, description, language, license, topics. " +
            "Use to check a project's popularity, tech stack, or license before using it. " +
            "Example: {\"repo\": \"anomalyco/opencode\"}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("repo") { put("type", "string"); put("description", "Repository in format 'owner/repo' (e.g. 'torvalds/linux')") }
                },
                required = listOf("repo"),
            )
        },
        execute = { args ->
            val repo = args.asJsonObject["repo"]?.asJsonPrimitive?.asString
                ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing 'repo'. Format: owner/repo"))
            validateRepo(repo)?.let { return@Tool listOf(it) }

            try {
                val data = JsonParser.parseString(githubApiGet("$GITHUB_API/repos/$repo")).asJsonObject
                val text = buildString {
                    appendLine("Repository: ${data.get("full_name")?.asString ?: repo}")
                    appendLine("Description: ${data.get("description")?.asString ?: "N/A"}")
                    appendLine("Stars: ${data.get("stargazers_count")?.asInt ?: 0}")
                    appendLine("Forks: ${data.get("forks_count")?.asInt ?: 0}")
                    appendLine("Open Issues: ${data.get("open_issues_count")?.asInt ?: 0}")
                    appendLine("Language: ${data.get("language")?.asString ?: "N/A"}")
                    appendLine("License: ${data.getAsJsonObject("license")?.get("spdx_id")?.asString ?: "N/A"}")
                    appendLine("Topics: ${data.getAsJsonArray("topics")?.joinToString(", ") { it.asString } ?: "none"}")
                    appendLine("URL: ${data.get("html_url")?.asString ?: ""}")
                    appendLine("Default Branch: ${data.get("default_branch")?.asString ?: "main"}")
                    val pushedAt = data.get("pushed_at")?.asString ?: ""
                    if (pushedAt.isNotBlank()) appendLine("Last Push: $pushedAt")
                }
                listOf(UIMessagePart.Text(text))
            } catch (e: Exception) {
                listOf(UIMessagePart.Text("ERROR: $repo — ${e.message ?: "GitHub API call failed"}. SUGGESTION: Check the repo name and your internet connection."))
            }
        },
    )

    private val githubReadme = Tool(
        name = "githubReadme",
        description = "Fetch the README of a GitHub repo (raw markdown content). " +
            "Use to understand a project's purpose, installation, and usage. " +
            "Example: {\"repo\": \"anomalyco/opencode\"}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("repo") { put("type", "string"); put("description", "Repository in format 'owner/repo'") }
                },
                required = listOf("repo"),
            )
        },
        execute = { args ->
            val repo = args.asJsonObject["repo"]?.asJsonPrimitive?.asString
                ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing 'repo'. Format: owner/repo"))
            validateRepo(repo)?.let { return@Tool listOf(it) }

            try {
                val data = JsonParser.parseString(githubApiGet("$GITHUB_API/repos/$repo/readme")).asJsonObject
                val decoded = data.decodeBase64Content()
                    ?: return@Tool listOf(UIMessagePart.Text("No README content found for $repo"))
                listOf(UIMessagePart.Text(decoded))
            } catch (e: Exception) {
                listOf(UIMessagePart.Text("ERROR: $repo README — ${e.message ?: "fetch failed"}. SUGGESTION: Check repo name."))
            }
        },
    )

    private val githubFileFetch = Tool(
        name = "githubFileFetch",
        description = "Fetch a specific file from a GitHub repo by path. Optionally specify a branch. " +
            "Use to read source files from GitHub without cloning the repo. " +
            "Example: {\"repo\": \"anomalyco/opencode\", \"path\": \"README.md\"} " +
            "Example with branch: {\"repo\": \"torvalds/linux\", \"path\": \"Makefile\", \"branch\": \"master\"}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("repo") { put("type", "string"); put("description", "Repository in format 'owner/repo'") }
                    putJsonObject("path") { put("type", "string"); put("description", "File path within the repository") }
                    putJsonObject("branch") { put("type", "string"); put("description", "Branch name (default: repository default branch)") }
                },
                required = listOf("repo", "path"),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val repo = obj["repo"]?.asJsonPrimitive?.asString ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing 'repo'. Format: owner/repo"))
            val filePath = obj["path"]?.asJsonPrimitive?.asString ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing 'path'."))
            val branch = obj["branch"]?.asJsonPrimitive?.asString.orEmpty()

            val url = buildString {
                append("$GITHUB_API/repos/$repo/contents/$filePath")
                if (branch.isNotBlank()) append("?ref=$branch")
            }

            try {
                val data = JsonParser.parseString(githubApiGet(url)).asJsonObject
                val decoded = data.decodeBase64Content()
                    ?: return@Tool listOf(UIMessagePart.Text("ERROR: Not a file or no content at $repo/$filePath. SUGGESTION: Use a path to a file, not a directory."))
                listOf(UIMessagePart.Text(decoded))
            } catch (e: Exception) {
                listOf(UIMessagePart.Text("ERROR: $repo/$filePath — ${e.message ?: "fetch failed"}. SUGGESTION: Check path, repo, and branch."))
            }
        },
    )

    private val githubSearchCode = Tool(
        name = "githubSearchCode",
        description = "Search code on GitHub using the search API. Optionally scope to a repo. " +
            "Use to find how projects implement specific patterns or use APIs. " +
            "Example: {\"query\": \"suspend function\", \"limit\": 5} " +
            "Scoped: {\"query\": \"class Tool\", \"repo\": \"anomalyco/opencode\", \"limit\": 10}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("query") { put("type", "string"); put("description", "Search query") }
                    putJsonObject("limit") { put("type", "integer"); put("description", "Maximum results (default: 10, max: 50)") }
                    putJsonObject("repo") { put("type", "string"); put("description", "Limit search to a specific repository (owner/repo)") }
                },
                required = listOf("query"),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val query = obj["query"]?.asJsonPrimitive?.asString ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing 'query'."))
            val limit = (obj["limit"]?.asJsonPrimitive?.asInt ?: 10).coerceIn(1, 50)
            val repo = obj["repo"]?.asJsonPrimitive?.asString.orEmpty()

            val q = if (repo.isNotBlank()) "$query+repo:$repo" else query
            val url = "$GITHUB_API/search/code?q=${java.net.URLEncoder.encode(q, "UTF-8")}&per_page=$limit"
            try {
                val data = JsonParser.parseString(githubApiGet(url)).asJsonObject
                val items = data.getAsJsonArray("items") ?: JsonArray()

                val text = buildString {
                    val total = data.get("total_count")?.asInt ?: 0
                    appendLine("Found $total results (showing ${items.size()})")
                    appendLine()
                    if (items.size() == 0) appendLine("No code matches for query: $query")
                    items.forEach { item ->
                        val itemObj = item.asJsonObject
                        appendLine("File: ${itemObj.get("path")?.asString ?: "?"}")
                        appendLine("Repo: ${itemObj.getAsJsonObject("repository")?.get("full_name")?.asString ?: "?"}")
                        appendLine("URL: ${itemObj.get("html_url")?.asString ?: "?"}")
                        appendLine()
                    }
                }
                listOf(UIMessagePart.Text(text))
            } catch (e: Exception) {
                listOf(UIMessagePart.Text("ERROR: GitHub code search — ${e.message ?: "failed"}. SUGGESTION: Simplify your query or check connectivity."))
            }
        },
    )

    private fun githubApiPost(urlStr: String, body: String): String {
        val conn = URI(urlStr).toURL().openConnection() as HttpURLConnection
        return try {
            conn.doOutput = true
            conn.connectTimeout = GITHUB_API_TIMEOUT_MS
            conn.readTimeout = GITHUB_API_TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "Xed-Editor/2.0")
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("Content-Type", "application/json")
            val pw = Settings.git_password.ifBlank { null }
            val un = Settings.git_username.ifBlank { null }
            if (pw != null && un != null) {
                val basic = java.util.Base64.getEncoder().encodeToString("$un:$pw".toByteArray())
                conn.setRequestProperty("Authorization", "Basic $basic")
            } else if (pw != null) {
                conn.setRequestProperty("Authorization", "Bearer $pw")
            }
            conn.outputStream.use { it.write(body.toByteArray()) }
            val responseCode = conn.responseCode
            if (responseCode == 403) {
                val resetTime = conn.getHeaderField("X-RateLimit-Reset")?.toLongOrNull()
                throw RuntimeException("API rate limited${if (resetTime != null) ", resets ${(resetTime * 1000 - System.currentTimeMillis()) / 1000}s" else ""}")
            }
            val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            val text = stream.bufferedReader().use { it.readText() }
            if (responseCode !in 200..299) throw RuntimeException("HTTP $responseCode: $text")
            text
        } finally {
            runCatching { conn.errorStream?.use { it.readBytes() } }
            runCatching { conn.inputStream?.use { it.readBytes() } }
            conn.disconnect()
        }
    }

    private val createPullRequest = Tool(
        name = "createPullRequest",
        description = "Create a GitHub Pull Request via the GitHub API. " +
            "Push the branch first with gitPush, then create the PR. " +
            "Uses git username/password from settings for auth. " +
            "Example: {\"repo\": \"owner/repo\", \"title\": \"feat: add user authentication\"}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("repo") { put("type", "string"); put("description", "Repository in format 'owner/repo' (e.g. 'torvalds/linux')") }
                    putJsonObject("title") { put("type", "string"); put("description", "PR title") }
                    putJsonObject("body") { put("type", "string"); put("description", "PR description/body (optional)") }
                    putJsonObject("base") { put("type", "string"); put("description", "Base/target branch (default: main)") }
                    putJsonObject("head") { put("type", "string"); put("description", "Head/source branch (default: current branch)") }
                },
                required = listOf("repo", "title"),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val repo = obj["repo"]?.asJsonPrimitive?.asString
                ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing 'repo'. Format: owner/repo"))
            val title = obj["title"]?.asJsonPrimitive?.asString
                ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing 'title'."))
            validateRepo(repo)?.let { return@Tool listOf(it) }
            val body = obj["body"]?.asJsonPrimitive?.asString ?: ""
            val base = obj["base"]?.asJsonPrimitive?.asString ?: "main"
            val head = obj["head"]?.asJsonPrimitive?.asString ?: ""

            if (head.isBlank()) {
                return@Tool listOf(UIMessagePart.Text("ERROR: Head branch is required. Specify 'head' or use current branch."))
            }

            try {
                val jsonBody = buildJsonObject {
                    put("title", title)
                    put("head", head)
                    put("base", base)
                    if (body.isNotBlank()) put("body", body)
                }.toString()
                val response = githubApiPost("$GITHUB_API/repos/$repo/pulls", jsonBody)
                val data = com.google.gson.JsonParser.parseString(response).asJsonObject
                val prUrl = data.get("html_url")?.asString ?: data.get("url")?.asString ?: response
                listOf(UIMessagePart.Text("PR created: $prUrl"))
            } catch (e: Exception) {
                listOf(UIMessagePart.Text("ERROR: createPullRequest failed: ${e.message}"))
            }
        },
    )

    val all: List<Tool> = listOf(githubRepoInfo, githubReadme, githubFileFetch, githubSearchCode, createPullRequest)
}
