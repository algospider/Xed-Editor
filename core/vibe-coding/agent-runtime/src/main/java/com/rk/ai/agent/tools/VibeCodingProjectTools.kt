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
import java.io.File
import com.rk.ai.agent.indexer.ProjectIndexer

class VibeCodingProjectTools(private val ideService: IdeService) {

    private val projectIndexer = ProjectIndexer(ideService)

    private val getProjectStructure = Tool(
        name = "getProjectStructure",
        description = "Returns a hierarchical directory tree of the project. " +
            "Use FIRST to understand the project layout. Adjust maxDepth to go deeper. " +
            "Call this once and cache the result — the structure rarely changes. " +
            "Example: {} or {\"path\": \"src/\", \"maxDepth\": 4, \"maxItems\": 100}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("path") { put("type", "string"); put("description", "Directory path to explore (default: workspace root)") }
                    putJsonObject("maxDepth") { put("type", "integer"); put("description", "Maximum depth (default: 3, max: 10)") }
                    putJsonObject("maxItems") { put("type", "integer"); put("description", "Maximum items (default: 200, max: 1000)") }
                },
                required = emptyList<String>(),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val path = obj["path"]?.asJsonPrimitive?.asString ?: ideService.getPrimaryWorkspacePath()
            val maxDepth = (obj["maxDepth"]?.asJsonPrimitive?.asInt ?: 3).coerceIn(1, 10)
            val maxItems = (obj["maxItems"]?.asJsonPrimitive?.asInt ?: 200).coerceIn(1, 1000)
            val structure = ideService.getProjectStructure(path, maxDepth, maxItems)
            listOf(UIMessagePart.Text(structure.ifEmpty { "(empty project)" }))
        },
    )

    private val getProjectSummary = Tool(
        name = "getProjectSummary",
        description = "ONE-CALL ORIENTATION: Returns README, build files, config, open tabs, and git status. " +
            "Call this FIRST when starting any task to understand the workspace state. " +
            "Example: {} or {\"path\": \"/path/to/project\"}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("path") { put("type", "string"); put("description", "Project path (default: workspace root)") }
                },
                required = emptyList<String>(),
            )
        },
        execute = { args ->
            val path = args.asJsonObject["path"]?.asJsonPrimitive?.asString ?: ideService.getPrimaryWorkspacePath()
            val config = ideService.getProjectConfig(path)
            val structure = ideService.getProjectStructure(path, 2, 100)
            listOf(UIMessagePart.Text(buildString {
                appendLine("Project: ${config["name"]?.asString ?: path.split("/").lastOrNull() ?: "Unknown"}")
                appendLine("Path: $path")
                config["configFiles"]?.asJsonArray?.let { files ->
                    if (files.size() > 0) appendLine("Config: ${files.joinToString(", ") { it.asString }}")
                }
                appendLine(); appendLine(structure)
            }))
        },
    )

    private val getProjectConfig = Tool(
        name = "getProjectConfig",
        description = "Detect project configuration: build system, language, frameworks. " +
            "Use to understand what build system and language the project uses before running commands. " +
            "Example: {} or {\"workspacePath\": \"/path\"}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("workspacePath") { put("type", "string"); put("description", "Project path (default: workspace root)") }
                },
                required = emptyList<String>(),
            )
        },
        execute = { args ->
            val workspace = args.asJsonObject["workspacePath"]?.asJsonPrimitive?.asString ?: ideService.getPrimaryWorkspacePath()
            val config = ideService.getProjectConfig(workspace)
            listOf(UIMessagePart.Text(config.keySet().joinToString("\n") { "$it: ${config[it]}" }))
        },
    )

    private val getSymbolUnderCursor = Tool(
        name = "getSymbolUnderCursor",
        description = "Get the symbol (function, class, variable) at the user's cursor in the active editor. " +
            "Use to understand what the user is currently focused on. " +
            "Example: no args needed.",
        execute = { _ ->
            val symbol = ideService.getSymbolUnderCursor()
            if (symbol != null && symbol.keySet().size > 0) {
                listOf(UIMessagePart.Text(symbol.keySet().joinToString("\n") { "$it: ${symbol[it]}" }))
            } else {
                listOf(UIMessagePart.Text("No symbol at cursor"))
            }
        },
    )

    private val getProjectInstructions = Tool(
        name = "getProjectInstructions",
        description = "Read project-level AI instruction files: CLAUDE.md, AGENTS.md (recursive), .cursorrules, copilot-instructions.md. " +
            "Call this AFTER getProjectSummary to learn project-specific coding conventions and rules. " +
            "These files contain developer guidelines for AI behavior. " +
            "Example: {} or {\"workspacePath\": \"/path\"}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("workspacePath") { put("type", "string"); put("description", "Project path (default: workspace root)") }
                },
                required = emptyList<String>(),
            )
        },
        execute = { args ->
            val workspace = args.asJsonObject["workspacePath"]?.asJsonPrimitive?.asString ?: ideService.getPrimaryWorkspacePath()
            if (workspace.isBlank()) return@Tool listOf(UIMessagePart.Text("No workspace configured. Open a project first."))
            val workspaceFile = File(workspace)
            if (!workspaceFile.exists() || !workspaceFile.isDirectory) return@Tool listOf(UIMessagePart.Text("Workspace path does not exist: $workspace"))
            val sections = mutableListOf<String>()
            for (candidate in listOf(
                workspaceFile.resolve("CLAUDE.md"), workspaceFile.resolve(".claude/CLAUDE.md"),
                workspaceFile.resolve(".claude.md"), workspaceFile.resolve(".cursorrules"),
                workspaceFile.resolve(".github/copilot-instructions.md"),
            )) { if (candidate.exists() && candidate.isFile) sections.add("=== ${candidate.name} ===\n${candidate.readText()}") }
            val agentsFiles = mutableListOf<File>()
            var dir: File? = workspaceFile
            while (dir != null && dir.exists() && dir.isDirectory) {
                val af = dir.resolve("AGENTS.md")
                if (af.exists() && af.isFile) agentsFiles.add(af)
                dir = if (dir.parentFile != null && dir.parentFile != dir) dir.parentFile else null
            }
            for (file in agentsFiles.reversed()) sections.add("=== AGENTS.md (${file.parentFile?.name ?: "/"}) ===\n${file.readText()}")
            if (sections.isNotEmpty()) listOf(UIMessagePart.Text("Instructions at ${workspaceFile.name}:\n\n${sections.joinToString("\n\n")}"))
            else listOf(UIMessagePart.Text("No instructions found (checked: CLAUDE.md, AGENTS.md, .cursorrules, copilot-instructions.md)"))
        },
    )

    private val searchProjectInstructions = Tool(
        name = "searchProjectInstructions",
        description = "Find AGENTS.md files near a specific subdirectory. " +
            "AGENTS.md files contain per-directory developer guidelines. " +
            "Example: {\"path\": \"src/features/login/\"}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("path") { put("type", "string"); put("description", "Directory to search from (walks up to find nearest AGENTS.md)") }
                },
                required = listOf("path"),
            )
        },
        execute = { args ->
            val searchPath = args.asJsonObject["path"]?.asJsonPrimitive?.asString ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing 'path'."))
            val searchFile = File(searchPath)
            if (!searchFile.exists()) return@Tool listOf(UIMessagePart.Text("Path not found: $searchPath"))
            val found = mutableListOf<File>()
            val da = searchFile.resolve("AGENTS.md")
            if (da.exists() && da.isFile) found.add(da)
            var dir: File? = searchFile
            while (dir != null && dir.exists() && dir.isDirectory) {
                val af = dir.resolve("AGENTS.md")
                if (af.exists() && af.isFile) found.add(af)
                dir = if (dir.parentFile != null && dir.parentFile != dir) dir.parentFile else null
            }
            if (found.isNotEmpty()) {
                listOf(UIMessagePart.Text(found.reversed().joinToString("\n\n") { "=== ${it.parentFile?.name ?: "/"} (${it.absolutePath}) ===\n${it.readText()}" }))
            } else listOf(UIMessagePart.Text("No AGENTS.md found near: $searchPath"))
        },
    )

    private val indexCodebase = Tool(
        name = "indexCodebase",
        description = "Build or query a searchable codebase index (files, symbols, modules, deps). " +
            "Use 'build' once to create index, then 'search', 'stats', or 'architecture' for analysis. " +
            "Example: {\"action\": \"build\"} or {\"action\": \"search\", \"query\": \"User\"} or {\"action\": \"stats\"}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("action") { put("type", "string"); put("description", "Action: 'build', 'search', 'stats', 'architecture', 'keyFiles'") }
                    putJsonObject("query") { put("type", "string"); put("description", "Search query for 'search' action (symbol name)") }
                    putJsonObject("depth") { put("type", "integer"); put("description", "Directory depth to index (default: 5)") }
                },
                required = listOf("action"),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val action = obj["action"]?.asJsonPrimitive?.asString ?: "build"
            val query = obj["query"]?.asJsonPrimitive?.asString ?: ""
            val workspacePath = ideService.getPrimaryWorkspacePath()
            val result = when (action.lowercase()) {
                "build" -> { val idx = projectIndexer.index(workspacePath); "Indexed ${idx.files.size} files, ${idx.symbols.size} symbols, ${idx.modules.size} modules." }
                "search" -> { val idx = projectIndexer.index(workspacePath); "Symbols:\n${idx.symbols.filter { it.name.contains(query, true) }.take(50).joinToString("\n") { "- [${it.kind}] ${it.name} in ${it.file.substringAfterLast("/")}:${it.line}" }}" }
                "stats" -> { val idx = projectIndexer.index(workspacePath); "Files: ${idx.files.size}\nSymbols: ${idx.symbols.size}\nModules: ${idx.modules.size}\nDeps: ${idx.dependencies.size}" }
                "architecture" -> {
                    val config = ideService.getProjectConfig(workspacePath)
                    val idx = projectIndexer.index(workspacePath)
                    val pkgs = idx.packageStructure.keys.sortedByDescending { idx.packageStructure[it]?.size ?: 0 }.take(15)
                    "Project: ${config["name"]?.asString ?: workspacePath.split("/").lastOrNull()}\nModules: ${idx.modules.joinToString(", ") { it.name }}\nPackages:\n${pkgs.joinToString("\n") { "- $it (${idx.packageStructure[it]?.size ?: 0} files)" }}"
                }
                "keyFiles" -> ideService.getProjectStructure(workspacePath, 4, 200)
                else -> "Unknown action: $action"
            }
            listOf(UIMessagePart.Text(result))
        },
    )

    private val semanticSearch = Tool(
        name = "semanticSearch",
        description = "Search code by CONCEPT, not just text (symbol name, pattern, or structure). " +
            "Use when searchCode/searchSymbols don't find what you need. " +
            "Example: {\"query\": \"authentication\"} or {\"query\": \"api endpoint handler\"}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("query") { put("type", "string"); put("description", "Search concept or pattern (e.g. \"database\", \"API\", \"error handling\")") }
                    putJsonObject("maxResults") { put("type", "integer"); put("description", "Max results (default: 20)") }
                },
                required = listOf("query"),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val query = obj["query"]?.asJsonPrimitive?.asString ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing 'query'."))
            val maxResults = (obj["maxResults"]?.asJsonPrimitive?.asInt ?: 20).coerceIn(1, 100)
            val ws = ideService.getPrimaryWorkspacePath()
            val index = projectIndexer.index(ws)
            val q = query.lowercase()
            val syms = index.symbols.filter { it.name.lowercase().contains(q) || it.file.lowercase().contains(q) }.take(maxResults)
            val files = index.files.filter { it.path.lowercase().contains(q) }.take(maxResults)
            listOf(UIMessagePart.Text(buildString {
                appendLine("Semantic search: $query")
                if (syms.isNotEmpty()) { appendLine("\nSymbols:"); syms.forEach { appendLine("- [${it.kind}] ${it.name} in ${it.file}:${it.line}") } }
                if (files.isNotEmpty()) { appendLine("\nFiles:"); files.forEach { appendLine("- ${it.path}") } }
                if (syms.isEmpty() && files.isEmpty()) append("\nNo matches. Try a different concept.")
            }))
        },
    )

    val all: List<Tool> = listOf(
        getProjectStructure, getProjectSummary, getProjectConfig,
        getSymbolUnderCursor, getProjectInstructions, searchProjectInstructions,
        indexCodebase, semanticSearch,
    )
}
