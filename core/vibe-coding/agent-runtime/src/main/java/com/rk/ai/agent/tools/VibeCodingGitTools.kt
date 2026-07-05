@file:OptIn(ExperimentalUuidApi::class)

package com.rk.ai.agent.tools

import com.google.gson.JsonObject
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import com.rk.ai.models.InputSchema
import com.rk.ai.models.Tool
import com.rk.ai.models.UIMessagePart
import com.rk.ai.service.IdeService

class VibeCodingGitTools(private val ideService: IdeService) {

    private fun com.google.gson.JsonElement.workspaceOrPrimary(): String =
        asJsonObject["workspacePath"]?.asJsonPrimitive?.asString ?: ideService.getPrimaryWorkspacePath()

    private val getGitStatus = Tool(
        name = "getGitStatus",
        description = "Returns git status: staged, modified, untracked files, and current branch. " +
            "Check this FIRST before making commits or branches. " +
            "Example: {} or {\"workspacePath\": \"/path/to/repo\"}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("workspacePath") { put("type", "string"); put("description", "Path to the git repository (optional, uses primary workspace)") }
                },
                required = emptyList<String>(),
            )
        },
        execute = { args ->
            val workspace = args.workspaceOrPrimary()
            if (workspace.isBlank()) return@Tool listOf(UIMessagePart.Text("ERROR: No workspace configured."))
            try {
                val status = ideService.getGitStatus(workspace)
                val text = buildString {
                    status.keySet().forEach { key ->
                        val element = status.get(key)
                        if (element is com.google.gson.JsonArray) {
                            if (element.size() > 0) {
                                appendLine("$key:")
                                element.forEach { appendLine("  ${it.asString}") }
                            }
                        } else {
                            appendLine("$key: ${element.asString}")
                        }
                    }
                }
                listOf(UIMessagePart.Text(text.ifEmpty { "Working tree clean" }))
            } catch (e: Exception) {
                listOf(UIMessagePart.Text("ERROR: ${e.message}\nSUGGESTION: Is this a git repository? Run 'git init' first if not."))
            }
        },
    )

    private val getGitDiff = Tool(
        name = "getGitDiff",
        description = "Returns diff. Shows what changes would be committed. " +
            "Review before committing. Use getGitStatus first to see what files changed. " +
            "Example: {} or {\"workspacePath\": \"/path/to/repo\"}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("workspacePath") { put("type", "string"); put("description", "Path to the git repository (optional)") }
                },
                required = emptyList<String>(),
            )
        },
        execute = { args ->
            val workspace = args.workspaceOrPrimary()
            val diff = ideService.getGitDiff(workspace)
            listOf(UIMessagePart.Text(diff.ifEmpty { "No changes" }))
        },
    )

    private val gitCommit = Tool(
        name = "gitCommit",
        description = "Commit staged changes. If 'all'=true, auto-stages all modified/deleted files first. " +
            "Use a clear, concise commit message. Use getGitStatus + getGitDiff first to review changes. " +
            "Example: {\"message\": \"fix: resolve login crash\", \"all\": true}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("message") { put("type", "string"); put("description", "Commit message. Use conventional commits format: feat/fix/chore/docs/refactor/test: description") }
                    putJsonObject("all") { put("type", "boolean"); put("description", "Auto-stage all modified/deleted files before committing") }
                    putJsonObject("workspacePath") { put("type", "string"); put("description", "Path to the git repository (optional)") }
                },
                required = listOf("message"),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val message = obj["message"]?.asJsonPrimitive?.asString
                ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing 'message'."))
            val all = obj["all"]?.asJsonPrimitive?.asBoolean ?: false
            val workspace = args.workspaceOrPrimary()
            listOf(UIMessagePart.Text(ideService.gitCommit(workspace, message, all)))
        },
    )

    private val gitCheckout = Tool(
        name = "gitCheckout",
        description = "Switch branches or restore files. " +
            "Use to move between branches, create new branches, or discard changes. " +
            "Automatically stashes uncommitted changes if checkout would overwrite them. " +
            "Example: {\"target\": \"feature/new-ui\"} or {\"target\": \"main\"}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("target") { put("type", "string"); put("description", "Branch name or commit hash to switch to") }
                    putJsonObject("createBranch") { put("type", "boolean"); put("description", "Create the branch if it doesn't exist (git checkout -b)") }
                    putJsonObject("workspacePath") { put("type", "string"); put("description", "Path to the git repository (optional)") }
                },
                required = listOf("target"),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val target = obj["target"]?.asJsonPrimitive?.asString
                ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing 'target'."))
            val createBranch = obj["createBranch"]?.asJsonPrimitive?.asBoolean ?: false
            val workspace = args.workspaceOrPrimary()
            try {
                if (createBranch) {
                    val createResult = ideService.gitBranch(workspace, "create", target)
                    if (createResult.startsWith("error")) return@Tool listOf(UIMessagePart.Text(createResult))
                }
                val result = ideService.gitCheckout(workspace, target)
                listOf(UIMessagePart.Text(result))
            } catch (e: Exception) {
                listOf(UIMessagePart.Text(
                    "ERROR: Checkout failed: ${e.message}\n" +
                    "SUGGESTION: Commit or stash your changes first, then retry."
                ))
            }
        },
    )

    private val gitLog = Tool(
        name = "gitLog",
        description = "Show commit history. Use to review recent commits, find commit hashes, or understand project history. " +
            "Example: {\"maxCount\": 10} or {\"maxCount\": 5, \"branch\": \"main\"}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("maxCount") { put("type", "integer"); put("description", "Number of recent commits (default: 10)") }
                    putJsonObject("branch") { put("type", "string"); put("description", "Branch name (default: current branch)") }
                    putJsonObject("workspacePath") { put("type", "string"); put("description", "Path to the git repository (optional)") }
                },
                required = emptyList<String>(),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val maxCount = obj["maxCount"]?.asJsonPrimitive?.asInt ?: 10
            val branch = obj["branch"]?.asJsonPrimitive?.asString
            val workspace = args.workspaceOrPrimary()
            val result = ideService.gitLog(workspace, maxCount, branch)
            listOf(UIMessagePart.Text(result.ifEmpty { "No commits found" }))
        },
    )

    private val gitBranch = Tool(
        name = "gitBranch",
        description = "List, create, or delete branches. " +
            "Use 'list' to see all branches (* = current), 'create' for new branches, 'delete' for merged branches. " +
            "Example: {\"action\": \"list\"} or {\"action\": \"create\", \"branchName\": \"feature/new-thing\"}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("action") { put("type", "string"); put("description", "Action: 'list' (default), 'create', or 'delete'") }
                    putJsonObject("branchName") { put("type", "string"); put("description", "Branch name (required for create/delete)") }
                    putJsonObject("workspacePath") { put("type", "string"); put("description", "Path to the git repository (optional)") }
                },
                required = emptyList<String>(),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val action = obj["action"]?.asJsonPrimitive?.asString ?: "list"
            val branchName = obj["branchName"]?.asJsonPrimitive?.asString
            val workspace = args.workspaceOrPrimary()
            if (action != "list" && branchName.isNullOrBlank()) {
                return@Tool listOf(UIMessagePart.Text("ERROR: Missing 'branchName' for $action."))
            }
            val result = ideService.gitBranch(workspace, action, branchName)
            listOf(UIMessagePart.Text(result.ifEmpty { "OK" }))
        },
    )

    private val gitPush = Tool(
        name = "gitPush",
        description = "Push commits to a remote. Use setUpstream=true for first push of a new branch. " +
            "Commit first with gitCommit, then push. " +
            "Example: {\"remote\": \"origin\", \"branch\": \"main\"} or {\"remote\": \"origin\", \"branch\": \"feature/x\", \"setUpstream\": true}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("remote") { put("type", "string"); put("description", "Remote name (default: origin)") }
                    putJsonObject("branch") { put("type", "string"); put("description", "Branch to push (default: current branch)") }
                    putJsonObject("setUpstream") { put("type", "boolean"); put("description", "Set upstream tracking with -u flag (use for new branches)") }
                    putJsonObject("force") { put("type", "boolean"); put("description", "Force push (use with caution)") }
                    putJsonObject("workspacePath") { put("type", "string"); put("description", "Path to the git repository (optional)") }
                },
                required = emptyList<String>(),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val remote = obj["remote"]?.asJsonPrimitive?.asString ?: "origin"
            val branch = obj["branch"]?.asJsonPrimitive?.asString
            val setUpstream = obj["setUpstream"]?.asJsonPrimitive?.asBoolean ?: false
            val force = obj["force"]?.asJsonPrimitive?.asBoolean ?: false
            val workspace = args.workspaceOrPrimary()
            val result = ideService.gitPush(workspace, remote, branch, setUpstream, force)
            listOf(UIMessagePart.Text(result))
        },
    )

    private val gitPull = Tool(
        name = "gitPull",
        description = "Pull latest changes from remote. Fetches and merges the remote branch. " +
            "Use to sync with upstream before making changes. " +
            "Example: {\"remote\": \"origin\", \"branch\": \"main\"}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("remote") { put("type", "string"); put("description", "Remote name (default: origin)") }
                    putJsonObject("branch") { put("type", "string"); put("description", "Branch to pull (default: current branch)") }
                    putJsonObject("workspacePath") { put("type", "string"); put("description", "Path to the git repository (optional)") }
                },
                required = emptyList<String>(),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val remote = obj["remote"]?.asJsonPrimitive?.asString ?: "origin"
            val branch = obj["branch"]?.asJsonPrimitive?.asString
            val workspace = args.workspaceOrPrimary()
            val result = ideService.gitPull(workspace, remote, branch)
            listOf(UIMessagePart.Text(result))
        },
    )

    val all: List<Tool> = listOf(
        getGitStatus, getGitDiff, gitCommit, gitCheckout, gitPull,
        gitLog, gitBranch, gitPush,
    )
}
