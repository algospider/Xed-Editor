@file:OptIn(ExperimentalUuidApi::class)
package com.rk.ai.nativeagent.engine

import android.util.Log
import com.rk.ai.agent.events.SessionTodo
import com.rk.ai.agent.events.SessionTodoStatus
import com.rk.ai.agent.events.VibeCodingEventBus
import com.rk.ai.agent.files.ConfigProvider
import com.rk.ai.agent.files.SkillManager
import com.rk.ai.agent.plan.PlanManager
import com.rk.ai.agent.tools.LocalTools
import com.rk.ai.agent.tools.ToolValidator
import com.rk.ai.agent.tools.VibeCodingToolRegistry
import com.rk.ai.agent.tools.createSearchTools
import com.rk.ai.agent.tools.createSkillTools
import com.rk.ai.mcp.McpManager
import com.rk.ai.models.InputSchema
import com.rk.ai.models.Tool
import com.rk.ai.models.UIMessagePart
import com.rk.ai.persistence.settings.SettingsStore
import com.rk.ai.persistence.settings.getCurrentAssistant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val TAG = "VibeCodingToolDefinitions"

/**
 * Defines all LLM-accessible tools for the vibe-coding engine and builds
 * the complete tool list for generation.
 */
class VibeCodingToolDefinitions(
    private val getState: () -> VibeCodingState,
    private val updateState: (VibeCodingState.() -> VibeCodingState) -> Unit,
    private val getCommandCatalog: () -> List<CommandCatalogEntry>,
    private val engineScope: CoroutineScope,
    private val vibeEventBus: VibeCodingEventBus,
    private val mcpManager: McpManager,
    private val toolRegistry: VibeCodingToolRegistry,
    private val localTools: LocalTools,
    private val settingsStore: SettingsStore,
    private val skillManager: SkillManager,
    private val configProvider: ConfigProvider,
    private val permissionManager: PermissionManager,
    private val toolValidator: ToolValidator,
    private val json: Json,
) {

    // ── Tool: todowrite ─────────────────────────────────────────────

    private fun setSessionTodos(sessionId: Uuid, todos: List<SessionTodo>) {
        updateState { copy(todos = todos) }
        engineScope.launch {
            vibeEventBus.emit(com.rk.ai.agent.events.VibeCodingEvent.TodoUpdated(sessionId, todos))
        }
    }

    private val todowriteTool = Tool(
        name = "todowrite",
        description = "Create and manage a structured task list for the current session. Use this to break down complex tasks into tracked subtasks. Each todo has a description and status (pending/in_progress/completed/cancelled). Call this at the start of multi-step work to create a plan, then update status as you complete each step. Pass an empty array to read the current todos.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("todos") {
                        put("type", "string")
                        put("description", "JSON array of todos. Each item: {\"description\": \"...\", \"status\": \"pending\"}. Options for status: pending, in_progress, completed, cancelled. Example: [{\"description\": \"Read the main file\", \"status\": \"pending\"}, {\"description\": \"Implement the fix\", \"status\": \"pending\"}]")
                    }
                },
                required = listOf("todos"),
            )
        },
        execute = { args ->
            val todosElement = args.asJsonObject["todos"]
                ?: return@Tool listOf(UIMessagePart.Text("Error: missing required argument 'todos'"))
            val todosJson = when {
                todosElement.isJsonArray -> todosElement.asJsonArray
                todosElement.isJsonPrimitive && todosElement.asJsonPrimitive.isString -> {
                    try {
                        com.google.gson.JsonParser.parseString(todosElement.asString).asJsonArray
                    } catch (e: Exception) {
                        return@Tool listOf(UIMessagePart.Text("Error: invalid JSON in 'todos': ${e.message}"))
                    }
                }
                else -> return@Tool listOf(UIMessagePart.Text("Error: 'todos' must be a JSON array or a JSON string"))
            }
            val todos = todosJson.mapIndexed { index, item ->
                val obj = item.asJsonObject
                val desc = obj["description"]?.asJsonPrimitive?.asString ?: "Untitled task"
                val statusStr = obj["status"]?.asJsonPrimitive?.asString ?: "pending"
                val status = when (statusStr.lowercase()) {
                    "in_progress" -> SessionTodoStatus.IN_PROGRESS
                    "completed" -> SessionTodoStatus.COMPLETED
                    "cancelled" -> SessionTodoStatus.CANCELLED
                    else -> SessionTodoStatus.PENDING
                }
                val id = obj["id"]?.asJsonPrimitive?.asString ?: "todo-${Uuid.random()}"
                SessionTodo(id = id, description = desc, status = status)
            }

            val currentTodos = getState().todos
            val isReadOp = todos.isEmpty()
            val sessionId = getState().activeSessionId ?: Uuid.random()

            if (!isReadOp) {
                setSessionTodos(sessionId, todos)
            }

            val displayTodos = if (isReadOp) currentTodos else todos
            val summary = buildString {
                if (isReadOp) {
                    appendLine("Current task plan (${displayTodos.size} items):")
                } else {
                    appendLine("Task plan updated (${displayTodos.size} items):")
                }
                displayTodos.forEachIndexed { i, todo ->
                    val icon = when (todo.status) {
                        SessionTodoStatus.COMPLETED -> "[✓]"
                        SessionTodoStatus.IN_PROGRESS -> "[→]"
                        SessionTodoStatus.CANCELLED -> "[✗]"
                        SessionTodoStatus.PENDING -> "[ ]"
                    }
                    appendLine("  $icon ${i + 1}. ${todo.description}")
                }
                appendLine()
                val completed = displayTodos.count { it.status == SessionTodoStatus.COMPLETED }
                val inProgress = displayTodos.count { it.status == SessionTodoStatus.IN_PROGRESS }
                appendLine("Progress: $completed/${displayTodos.size} completed, $inProgress in progress")
            }
            listOf(UIMessagePart.Text(summary))
        },
    )

    // ── Tool: planMode ──────────────────────────────────────────────

    private val planModeTool = Tool(
        name = "planMode",
        description = "Create, approve, and track structured execution plans. Actions: create (title + steps JSON → plan, awaiting approval), approve (user accepts), reject (reason), status (show progress), update (stepId + stepStatus + result), cancel. When a plan is awaiting approval, do NOT execute any changes until approved.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("action") { put("type", "string") }
                    putJsonObject("title") { put("type", "string") }
                    putJsonObject("description") { put("type", "string") }
                    putJsonObject("steps") { put("type", "string") }
                    putJsonObject("stepId") { put("type", "string") }
                    putJsonObject("stepStatus") { put("type", "string") }
                    putJsonObject("result") { put("type", "string") }
                    putJsonObject("reason") { put("type", "string") }
                },
                required = listOf("action"),
            )
        },
        execute = { args ->
            val action = args.asJsonObject["action"]?.asJsonPrimitive?.asString ?: return@Tool listOf(UIMessagePart.Text("Missing 'action'"))
            when (action.lowercase()) {
                "create" -> {
                    val title = args.asJsonObject["title"]?.asJsonPrimitive?.asString ?: return@Tool listOf(UIMessagePart.Text("title required"))
                    val description = args.asJsonObject["description"]?.asJsonPrimitive?.asString ?: ""
                    val stepsRaw = args.asJsonObject["steps"]?.asJsonPrimitive?.asString ?: return@Tool listOf(UIMessagePart.Text("steps (JSON array string) required"))
                    val steps = try {
                        val arr = com.google.gson.JsonParser.parseString(stepsRaw).asJsonArray
                        arr.map { elem ->
                            if (elem.isJsonPrimitive) elem.asString to ""
                            else if (elem.isJsonObject) {
                                val o = elem.asJsonObject
                                (o["description"]?.asString ?: o["desc"]?.asString ?: "") to (o["details"]?.asString ?: o["detail"]?.asString ?: "")
                            } else "" to ""
                        }.filter { it.first.isNotBlank() }
                    } catch (_: Exception) { return@Tool listOf(UIMessagePart.Text("Invalid steps JSON: must be an array of strings or {description, details} objects")) }

                    if (steps.isEmpty()) return@Tool listOf(UIMessagePart.Text("Steps list is empty"))
                    val plan = PlanManager.createPlan(title, description, steps)
                    val display = buildString {
                        appendLine("## Plan: ${plan.title}")
                        if (plan.description.isNotBlank()) appendLine("> ${plan.description}")
                        appendLine()
                        plan.steps.forEachIndexed { i, s -> appendLine("${i + 1}. [ ] ${s.description}") }
                        appendLine()
                        appendLine("⏳ Awaiting approval. Type 'approve' to start.")
                    }
                    listOf(UIMessagePart.Text(display))
                }
                "approve" -> {
                    if (!PlanManager.isAwaitingApproval()) return@Tool listOf(UIMessagePart.Text("No plan awaiting approval. Create one with planMode create first."))
                    val plan = PlanManager.approvePlan()
                    listOf(UIMessagePart.Text("\u2705 Plan approved! Starting: ${plan?.title ?: "Untitled"}. Begin with step 1."))
                }
                "reject" -> {
                    val reason = args.asJsonObject["reason"]?.asJsonPrimitive?.asString ?: "No reason"
                    PlanManager.rejectPlan(reason)
                    listOf(UIMessagePart.Text("Plan rejected: $reason. Refine and present again."))
                }
                "status" -> {
                    val ctx = PlanManager.buildContext()
                    if (ctx.isBlank()) listOf(UIMessagePart.Text("No active plan."))
                    else listOf(UIMessagePart.Text(ctx))
                }
                "update" -> {
                    val stepId = args.asJsonObject["stepId"]?.asJsonPrimitive?.asString ?: return@Tool listOf(UIMessagePart.Text("stepId required"))
                    val raw = args.asJsonObject["stepStatus"]?.asJsonPrimitive?.asString ?: return@Tool listOf(UIMessagePart.Text("stepStatus required"))
                    val result = args.asJsonObject["result"]?.asJsonPrimitive?.asString?.takeIf { it.isNotBlank() }
                    val status = when (raw.lowercase()) { "in_progress","inprogress","started" -> com.rk.ai.agent.plan.StepStatus.IN_PROGRESS; "completed","done" -> com.rk.ai.agent.plan.StepStatus.COMPLETED; "failed","error" -> com.rk.ai.agent.plan.StepStatus.FAILED; "skipped" -> com.rk.ai.agent.plan.StepStatus.SKIPPED; else -> return@Tool listOf(UIMessagePart.Text("bad stepStatus: $raw")) }
                    val plan = PlanManager.updateStep(stepId, status, result) ?: PlanManager.updateStepByDescription(stepId, status, result) ?: return@Tool listOf(UIMessagePart.Text("Step '$stepId' not found"))
                    val allDone = plan.status == com.rk.ai.agent.plan.PlanStatus.COMPLETED
                    listOf(UIMessagePart.Text("Step '$stepId' → $raw. ${plan.progressSummary}${if (allDone) "\n\ud83c\udf89 All steps completed!" else ""}"))
                }
                "cancel" -> {
                    PlanManager.cancelPlan()
                    listOf(UIMessagePart.Text("Plan cancelled."))
                }
                else -> listOf(UIMessagePart.Text("Unknown action: $action. Use: create, approve, reject, status, update, cancel"))
            }
        },
    )

    // ── Tool: listCustomCommands ────────────────────────────────────

    private val listCustomCommandsTool = Tool(
        name = "listCustomCommands",
        description = "Lists all custom commands loaded from .xed/commands/. These are user-defined or project-specific commands that can be executed by invoking their prompt template.",
        execute = { _ ->
            val customCmds = getCommandCatalog().filter { it.id.startsWith("file:") }
            val text = buildString {
                if (customCmds.isEmpty()) {
                    appendLine("No custom commands found. Add .md files to .xed/commands/ to create custom commands.")
                } else {
                    appendLine("Custom commands (${customCmds.size}):")
                    customCmds.forEach { cmd ->
                        appendLine("  /${cmd.slash} - ${cmd.title}")
                        appendLine("    ${cmd.description}")
                        appendLine()
                    }
                    appendLine("Use the prompt content of a command as a template for your own tasks.")
                }
            }
            listOf(UIMessagePart.Text(text))
        },
    )

    // ── Tool: askUser ───────────────────────────────────────────────

    private val askUserTool = Tool(
        name = "askUser",
        description = "Ask the user a question and wait for their response. Use when you need clarification, are blocked by an ambiguous requirement, or need the user to make a decision before proceeding. The execution will pause until the user replies.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("question") {
                        put("type", "string")
                        put("description", "The question to ask the user. Be specific about what information you need.")
                    }
                    putJsonObject("context") {
                        put("type", "string")
                        put("description", "Optional context explaining why you need this information. What are you trying to do, and what options are you considering?")
                    }
                },
                required = listOf("question"),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val question = obj["question"]?.asJsonPrimitive?.asString
                ?: return@Tool listOf(UIMessagePart.Text("Error: missing required argument 'question'"))
            val context = obj["context"]?.asJsonPrimitive?.asString ?: ""

            val pendingId = Uuid.random().toString()
            updateState { copy(pendingQuestion = PendingQuestion(id = pendingId, question = question, context = context)) }

            val display = buildString {
                appendLine("🤔 **Question for you:**")
                appendLine()
                appendLine(question)
                if (context.isNotBlank()) {
                    appendLine()
                    appendLine("*Context: $context*")
                }
                appendLine()
                appendLine("*(I'm waiting for your answer before continuing)*")
            }
            listOf(UIMessagePart.Text(display))
        },
    )

    // ── Tool list construction ──────────────────────────────────────

    /**
     * Builds the complete list of tools available to the LLM, including
     * MCP tools, registry tools, local tools, search, skills, and
     * built-in tools, all wrapped with permission checks.
     */
    fun buildToolList(
        assistant: com.rk.ai.models.Assistant,
        settings: com.rk.ai.persistence.settings.Settings,
    ): List<Tool> {
        val mcpTools = mcpManager.getAllAvailableTools().map { (serverId, mcpTool) ->
            Tool(
                name = mcpTool.name,
                description = mcpTool.description ?: "",
                parameters = mcpTool.inputSchema?.let { schema ->
                    { schema }
                } ?: { InputSchema.Obj(kotlinx.serialization.json.buildJsonObject { }) },
                execute = { args ->
                    val argsStr = try {
                        com.google.gson.Gson().toJson(args)
                    } catch (_: Exception) {
                        args.toString()
                    }
                    val kotlinxArgs = json.parseToJsonElement(argsStr).jsonObject
                    toolValidator.validateWithSchema(
                        toolName = mcpTool.name,
                        schema = mcpTool.inputSchema,
                        args = args,
                    )
                    mcpManager.callTool(serverId, mcpTool.name, kotlinxArgs)
                },
            )
        }
        val baseTools = buildList {
            addAll(toolRegistry.withMcpTools(mcpTools))
            addAll(localTools.getTools(assistant.localTools))
            if (settings.enableWebSearch) addAll(createSearchTools(settings))
            addAll(createSkillTools(
                enabledSkills = assistant.enabledSkills,
                allSkills = skillManager.listSkills(),
                skillManager = skillManager,
            ))
            add(todowriteTool)
            add(planModeTool)
            add(listCustomCommandsTool)
            add(askUserTool)
        }

        val cfg = configProvider.unifiedConfig.value
        // Apply permission wrapping to base tools so the parallel tool dispatches
        // to permission-checked versions (NOT the raw originals).
        val permittedBaseTools = baseTools
            .filter { cfg.isToolEnabled(it.name) }
            .map { permissionManager.wrapToolWithPermissionCheck(it) { getState() } }

        val parallelTool = Tool(
            name = "parallel",
            description = "Execute MULTIPLE independent tool calls CONCURRENTLY in ONE round-trip — the most efficient way to batch operations. " +
                "Use for: (1) reading multiple unrelated files at once instead of sequential readFile calls, " +
                "(2) searching and reading in parallel, (3) any set of independent operations. " +
                "Results are ordered with headers showing which call produced which output. " +
                "Do NOT parallelize state-mutating tools (writeFile, editFile) with each other or with reads of the same file — race conditions.",
            parameters = {
                InputSchema.Obj(
                    properties = buildJsonObject {
                        putJsonObject("calls") {
                            put("type", "array")
                            put("description", "Array of {tool: string, args: object} calls to run in parallel. " +
                                "Batch independent reads together (e.g., read multiple unrelated files at once). " +
                                "Tools that modify state (writeFile, editFile, etc.) must NOT be parallelized with each other or with reads of the same files.")
                            putJsonObject("items") {
                                put("type", "object")
                                putJsonObject("properties") {
                                    putJsonObject("tool") { put("type", "string"); put("description", "Tool name to call") }
                                    putJsonObject("args") { put("type", "object"); put("description", "Arguments for the tool") }
                                }
                                putJsonArray("required") { add(JsonPrimitive("tool")); add(JsonPrimitive("args")) }
                            }
                        }
                    },
                    required = listOf("calls"),
                )
            },
            execute = { args ->
                val obj = args.asJsonObject
                val calls = obj["calls"]?.asJsonArray
                    ?: return@Tool listOf(UIMessagePart.Text("Missing 'calls' array"))
                // Uses permission-wrapped tools so writeFile/editFile etc. are still
                // subject to deny/ask rules even when invoked inside a parallel batch.
                val toolMap = permittedBaseTools.associateBy { it.name }

                val results: List<List<UIMessagePart>> = coroutineScope {
                    calls.map { callElement ->
                        async {
                            val callObj = runCatching { callElement.asJsonObject }.getOrElse {
                                return@async listOf(UIMessagePart.Text("[Error] Invalid call element"))
                            }
                            val toolName = callObj["tool"]?.asJsonPrimitive?.asString ?: "?"
                            val callArgs = callObj["args"] ?: com.google.gson.JsonObject()
                            val tool = toolMap[toolName]
                            if (tool == null) {
                                listOf(UIMessagePart.Text("[Error] Unknown tool: $toolName"))
                            } else {
                                try {
                                    tool.execute(callArgs)
                                } catch (e: Exception) {
                                    listOf(UIMessagePart.Text("[Error] $toolName failed: ${e.message}"))
                                }
                            }
                        }
                    }.awaitAll()
                }

                val combined = mutableListOf<UIMessagePart>()
                for (i in results.indices) {
                    val callObj = runCatching { calls[i].asJsonObject }.getOrElse { continue }
                    val toolName = callObj["tool"]?.asJsonPrimitive?.asString ?: "?"
                    val argHint = if (callObj["args"] is com.google.gson.JsonObject) {
                        val keys = callObj["args"].asJsonObject.entrySet().take(2).joinToString(", ") { it.key }
                        if (keys.isEmpty()) "" else " ($keys)"
                    } else ""
                    combined.add(UIMessagePart.Text("\n[Result ${i + 1} - $toolName$argHint]"))
                    combined.addAll(results[i])
                }

                combined
            }
        )

        // Wrap parallelTool itself; permittedBaseTools are already wrapped above.
        return permittedBaseTools + permissionManager.wrapToolWithPermissionCheck(parallelTool) { getState() }
    }
}
