@file:OptIn(ExperimentalUuidApi::class)
package com.rk.ai.nativeagent.engine

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import com.rk.ai.agent.AILoggingManager
import com.rk.ai.agent.AppEventBus
import com.rk.ai.agent.GenerationHandler
import com.rk.ai.agent.events.SessionTodo
import com.rk.ai.agent.events.VibeCodingEvent
import com.rk.ai.agent.events.VibeCodingEventBus
import com.rk.ai.agent.files.ConfigProvider
import com.rk.ai.agent.files.DefaultContentSeeder
import com.rk.ai.agent.files.FilesManager
import com.rk.ai.agent.files.SkillManager
import com.rk.ai.agent.executor.AgentOrchestrator
import com.rk.ai.agent.executor.AgentPhase
import com.rk.ai.agent.executor.ExecutionEngine
import com.rk.ai.agent.files.XedConfigLoader
import com.rk.ai.agent.indexer.ProjectIndexer
import com.rk.ai.agent.tools.ToolValidator
import com.rk.ai.agent.agents.AgentResult
import com.rk.ai.agent.hooks.HookContext
import com.rk.ai.agent.hooks.HookEvent
import com.rk.ai.agent.hooks.HookManager
import com.rk.ai.agent.hooks.HookResult
import com.rk.ai.agent.hooks.SecurityHook
import com.rk.ai.agent.tools.LocalTools
import com.rk.ai.agent.tools.ToolCache
import com.rk.ai.agent.tools.ToolRouter
import com.rk.ai.agent.tools.VibeCodingToolRegistry
import com.rk.ai.agent.transformers.Base64ImageToLocalFileTransformer
import com.rk.ai.agent.transformers.PlaceholderTransformer
import com.rk.ai.agent.transformers.PromptInjectionTransformer
import com.rk.ai.agent.transformers.RegexOutputTransformer
import com.rk.ai.agent.transformers.TimeReminderTransformer
import com.rk.ai.agent.transformers.ToolTagSanitizerTransformer
import com.rk.ai.agent.tools.SuggestionStore
import com.rk.ai.agent.agents.AgentRegistry
import com.rk.ai.agent.context.ContextMemoryManager
import com.rk.ai.agent.plan.PlanManager
import com.rk.ai.core.AppScope
import com.rk.ai.mcp.McpManager
import com.rk.ai.models.Tool
import com.rk.ai.models.ToolApprovalState
import com.rk.ai.core.MessageRole
import com.rk.ai.models.UIMessage
import com.rk.ai.models.UIMessagePart
import com.rk.ai.persistence.db.AppDatabase
import com.rk.ai.persistence.db.fts.MessageFtsManager
import com.rk.ai.persistence.repo.ConversationRepository
import com.rk.ai.persistence.repo.FilesRepository
import com.rk.ai.persistence.repo.MemoryRepository
import com.rk.ai.persistence.settings.SettingsStore
import com.rk.ai.persistence.settings.findModelById
import com.rk.ai.persistence.settings.getCurrentAssistant
import com.rk.ai.providers.ProviderManager
import com.rk.ai.service.IdeService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val TAG = "VibeCodingEngine"
private val defaultJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private fun buildOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.MINUTES)
    .build()

private fun buildDatabase(context: Context): AppDatabase =
    Room.databaseBuilder(
        context.applicationContext,
        AppDatabase::class.java,
        "vibecoding_db",
    ).fallbackToDestructiveMigration().build()

private val shutdownHooksRegistered = java.util.concurrent.atomic.AtomicBoolean(false)
private fun registerShutdownHookForDatabase(db: AppDatabase) {
    if (shutdownHooksRegistered.compareAndSet(false, true)) {
        Runtime.getRuntime().addShutdownHook(Thread {
            try { db.close() } catch (_: Exception) { }
        })
    }
}

/**
 * Central facade for the vibe-coding AI engine. Owns all dependencies and
 * delegates specialized operations to focused sub-modules.
 */
class VibeCodingEngine(
    private val context: Context,
    val ideService: IdeService,
    scope: CoroutineScope? = null,
    private val json: Json = defaultJson,
    okHttpClient: OkHttpClient = buildOkHttpClient(),
) {
    val suggestionsFlow: MutableStateFlow<List<kotlinx.serialization.json.JsonObject>> = MutableStateFlow(emptyList())
    private val engineScope: CoroutineScope =
        scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val appScope = AppScope()

    val database = buildDatabase(context)
    private val memoryRepo = MemoryRepository(database.memoryDao())
    val conversationRepo = ConversationRepository(
        conversationDAO = database.conversationDao(),
        messageNodeDAO = database.messageNodeDao(),
        favoriteDAO = database.favoriteDao(),
        database = database,
        messageFtsManager = MessageFtsManager(database),
    )
    private val filesRepo = FilesRepository(database.managedFileDao())

    val providerManager = ProviderManager(okHttpClient, context)
    val settingsStore = SettingsStore(context, appScope)

    private val eventBus = AppEventBus()
    private val filesManager = FilesManager(context, filesRepo, appScope)
    private val mcpManager = McpManager(settingsStore, appScope, VibeCodingFileManager(context))
    private val skillManager = SkillManager(context, settingsStore) {
        try { ideService.getPrimaryWorkspacePath() } catch (_: Exception) { "" }
    }
    private val localTools = LocalTools(context, eventBus)

    val vibeEventBus = VibeCodingEventBus()
    val contextMemoryManager = ContextMemoryManager()

    val generationHandler = GenerationHandler(
        context = context,
        providerManager = providerManager,
        json = json,
        memoryRepo = memoryRepo,
        conversationRepo = conversationRepo,
        aiLoggingManager = AILoggingManager(),
        contextMemory = contextMemoryManager,
    )

    val toolRegistry = VibeCodingToolRegistry(ideService, context, providerManager, settingsStore)
    val hookManager = HookManager()
    val permissionManager = PermissionManager()

    val toolCache = ToolCache()
    val toolRouter = ToolRouter(toolCache, null)
    val projectIndexer = ProjectIndexer(ideService)
    val executionEngine = ExecutionEngine(
        ideService = ideService,
        contextMemory = contextMemoryManager,
        toolCache = toolCache,
        toolRouter = toolRouter,
        projectIndexer = projectIndexer,
    ).also { engine ->
        engine.registerHook(
            com.rk.ai.agent.hooks.HookEvent.BEFORE_COMMAND,
            com.rk.ai.agent.hooks.SecurityHook(),
        )
        engine.registerHook(
            com.rk.ai.agent.hooks.HookEvent.BEFORE_FILE_WRITE,
            com.rk.ai.agent.hooks.SecurityHook(),
        )
        engine.registerHook(
            com.rk.ai.agent.hooks.HookEvent.BEFORE_FILE_EDIT,
            com.rk.ai.agent.hooks.SecurityHook(),
        )
    }

    val orchestrator = AgentOrchestrator(
        ideService = ideService,
        contextMemory = contextMemoryManager,
        toolCache = toolCache,
        toolRouter = toolRouter,
        executionEngine = executionEngine,
        projectIndexer = projectIndexer,
    )

    val configProvider = ConfigProvider(
        context = context,
        settingsStore = settingsStore,
        workspacePath = { ideService.getPrimaryWorkspacePath() },
        scope = engineScope,
    )

    private val toolValidator = ToolValidator()

    // Sub-modules
    private val systemPromptBuilder = SystemPromptBuilder(ideService)
    val systemPromptTransformer = SystemPromptTransformer(systemPromptBuilder)

    private val configManager = EngineConfigManager(
        context = context,
        ideService = ideService,
        permissionManager = permissionManager,
        systemPromptBuilder = systemPromptBuilder,
        hookManager = hookManager,
        updateState = { transform -> _state.value = _state.value.transform(); updateDebugInfo() },
    )

    private val toolDefinitions = VibeCodingToolDefinitions(
        getState = { _state.value },
        updateState = { transform -> _state.value = _state.value.transform(); updateDebugInfo() },
        getCommandCatalog = { configManager.getCommandCatalog() },
        engineScope = engineScope,
        vibeEventBus = vibeEventBus,
        mcpManager = mcpManager,
        toolRegistry = toolRegistry,
        localTools = localTools,
        settingsStore = settingsStore,
        skillManager = skillManager,
        configProvider = configProvider,
        permissionManager = permissionManager,
        toolValidator = toolValidator,
        json = json,
    )

    private val sessionManager = SessionManager(
        getState = { _state.value },
        updateState = { transform -> _state.value = _state.value.transform(); updateDebugInfo() },
        conversationRepo = conversationRepo,
        engineScope = engineScope,
        vibeEventBus = vibeEventBus,
        getCurrentAssistantId = ::getCurrentAssistantId,
        getCommandCatalogSnapshot = { configManager.getCommandCatalog() },
        getPermissionRulesSnapshot = { permissionManager.rules },
    )

    val agentRegistry = AgentRegistry(context, ideService, providerManager, settingsStore) { prompt, contextStr ->
        this@VibeCodingEngine.generateWithLLM(
            prompt = prompt,
            tools = buildToolList(
                settingsStore.settingsFlow.value.getCurrentAssistant(),
                settingsStore.settingsFlow.value,
            ),
            context = com.rk.ai.agent.context.ContextBundle(),
            conversationHistory = _state.value.messages,
        )
    }

    val generationPipeline = GenerationPipeline(
        generationHandler = generationHandler,
        permissionManager = permissionManager,
        vibeEventBus = vibeEventBus,
        engineScope = engineScope,
        onStateUpdate = { transform ->
            _state.value = _state.value.transform()
            updateDebugInfo()
        },
        onSaveSession = { sessionManager.saveCurrentSessionMessages() },
        onSaveConversation = suspend { sessionManager.saveConversation() },
        getState = { _state.value },
    )

    private val _state = MutableStateFlow(VibeCodingState())
    val state: StateFlow<VibeCodingState> = _state.asStateFlow()

    init {
        registerShutdownHookForDatabase(database)
        _state.value = _state.value.copy(
            permissionAutoRespondRules = permissionManager.rules,
        )
        configProvider.unifiedConfig.value.let { cfg ->
            configManager.applyPermissionRules(cfg)
        }
        engineScope.launch {
            configProvider.unifiedConfig.collect { cfg ->
                configManager.applyPermissionRules(cfg)
            }
        }
        engineScope.launch {
            SuggestionStore.suggestions.collect { suggestionsFlow.value = it }
        }
        val securityHook = SecurityHook { severity, message, toolName, filePath ->
            val alert = SecurityAlert(id = Uuid.random().toString(), severity = severity, message = message, toolName = toolName, filePath = filePath)
            addSecurityAlert(alert)
            engineScope.launch {
                vibeEventBus.emit(VibeCodingEvent.SecurityAlert(
                    severity = severity,
                    message = message,
                    toolName = toolName,
                    filePath = filePath,
                ))
            }
        }
        hookManager.register(HookEvent.BEFORE_FILE_WRITE, securityHook)
        hookManager.register(HookEvent.BEFORE_FILE_EDIT, securityHook)

        toolRegistry.onAgentResult = { name, result ->
            val status = when (result) {
                is AgentResult.Success -> AgentActivityStatus.COMPLETED
                is AgentResult.Failure -> AgentActivityStatus.FAILED
                is AgentResult.NotAttempted -> AgentActivityStatus.PENDING
            }
            updateAgentActivity(name, status, result)
        }

        DefaultContentSeeder.seedIfNeeded(context)
        configManager.loadFileCommandsIntoCatalog()
        configManager.loadProjectConfig()

        orchestrator.setPhaseChangeListener { phase ->
            _state.value = _state.value.copy(currentPhase = phase)
        }

        // Retry workspace path detection if empty
        engineScope.launch {
            while (_state.value.workspacePath.isBlank()) {
                delay(3000)
                try {
                    val path = ideService.getPrimaryWorkspacePath()
                    if (path.isNotBlank()) {
                        _state.value = _state.value.copy(workspacePath = path)
                        configManager.xedConfig = XedConfigLoader.loadConfig(path)
                        configManager.applyConfigPermissions()
                        break
                    }
                } catch (_: Exception) { }
            }
        }
    }

    // ── State accessors ─────────────────────────────────────────────

    val messages: List<UIMessage> get() = _state.value.messages
    val isProcessing: Boolean get() = _state.value.isProcessing

    // ── Config delegation ───────────────────────────────────────────

    fun loadProjectConfig() = configManager.loadProjectConfig()
    fun refreshProjectConfig() = configManager.refreshProjectConfig()
    fun isToolEnabled(toolName: String): Boolean = configManager.isToolEnabled(toolName)
    fun applyConfigPermissions() = configManager.applyConfigPermissions()
    fun loadFileCommandsIntoCatalog() = configManager.loadFileCommandsIntoCatalog()
    fun refreshCommands() = configManager.refreshCommands()
    suspend fun evaluateHooks(event: HookEvent, ctx: HookContext): HookResult = configManager.evaluateHooks(event, ctx)
    fun addPermissionAutoRespondRule(rule: PermissionAutoRespondRule) = configManager.addPermissionAutoRespondRule(rule)
    fun removePermissionAutoRespondRule(idOrPattern: String) = configManager.removePermissionAutoRespondRule(idOrPattern)
    fun addCommandToCatalog(entry: CommandCatalogEntry) = configManager.addCommandToCatalog(entry)
    fun removeCommandFromCatalog(id: String) = configManager.removeCommandFromCatalog(id)
    fun getCommandCatalog(): List<CommandCatalogEntry> = configManager.getCommandCatalog()

    // ── Tool list delegation ────────────────────────────────────────

    fun buildToolList(
        assistant: com.rk.ai.models.Assistant,
        settings: com.rk.ai.persistence.settings.Settings,
    ): List<Tool> = toolDefinitions.buildToolList(assistant, settings)

    // ── Session delegation ──────────────────────────────────────────

    fun setSessionTodos(sessionId: Uuid, todos: List<SessionTodo>) {
        _state.value = _state.value.copy(todos = todos)
        engineScope.launch {
            vibeEventBus.emit(VibeCodingEvent.TodoUpdated(sessionId, todos))
        }
    }

    fun createBranchSession(parentSessionId: Uuid, title: String = "Branch"): Uuid =
        sessionManager.createBranchSession(parentSessionId, title)
    fun switchToSession(sessionId: Uuid) = sessionManager.switchToSession(sessionId)
    fun closeSession(sessionId: Uuid) = sessionManager.closeSession(sessionId)
    fun renameSession(sessionId: Uuid, newTitle: String) = sessionManager.renameSession(sessionId, newTitle)

    // ── Conversation / Message sending ──────────────────────────────

    fun sendMessage(text: String, extraParts: List<UIMessagePart> = emptyList()) {
        sessionManager.ensureSessionExists(text.trim())
        val trimmed = text.trim()
        generationPipeline.execute(
            text = trimmed,
            extraParts = extraParts,
            buildConfig = ::buildGenerationConfig,
        )
    }

    fun sendOrchestrated(goal: String) {
        sessionManager.ensureSessionExists(goal.trim())
        val job = engineScope.launch {
            val currentMessages = _state.value.messages
            val userMsg = UIMessage(
                role = MessageRole.USER,
                parts = listOf(UIMessagePart.Text(goal.trim())),
            )
            _state.value = _state.value.copy(
                isProcessing = true,
                currentPhase = AgentPhase.PLANNING,
                messages = currentMessages + userMsg,
            )
            engineScope.launch { vibeEventBus.emit(VibeCodingEvent.GenerationStarted) }
            val config = buildGenerationConfig()
            val tools = config?.tools ?: emptyList()

            // Wire progress messages from orchestrator into the conversation
            orchestrator.setProgressListener { progressMsg ->
                val sysMsg = UIMessage(
                    role = MessageRole.SYSTEM,
                    parts = listOf(UIMessagePart.Text(progressMsg)),
                )
                _state.value = _state.value.copy(
                    messages = _state.value.messages + sysMsg,
                )
            }

            val result = orchestrator.execute(goal, tools) { prompt, contextTools, contextBundle ->
                generateWithLLM(
                    prompt = prompt,
                    tools = contextTools,
                    context = contextBundle,
                    conversationHistory = currentMessages,
                )
            }
            _state.value = _state.value.copy(
                isProcessing = false,
                currentPhase = result.phase,
                taskTree = result.taskTree,
                modifiedFiles = result.modifiedFiles,
            )
            if (result.success) {
                val msg = UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text(result.summary)),
                )
                _state.value = _state.value.copy(messages = _state.value.messages + msg)
            } else {
                val errorMsg = UIMessage(
                    role = MessageRole.ASSISTANT,
                    parts = listOf(UIMessagePart.Text("Failed: ${result.errors.joinToString(", ")}")),
                )
                _state.value = _state.value.copy(
                    messages = _state.value.messages + errorMsg,
                    error = result.errors.joinToString(", "),
                )
            }
            engineScope.launch { vibeEventBus.emit(VibeCodingEvent.GenerationFinished) }
            sessionManager.saveConversation()
        }
        orchestrator.setRunningJob(job)
    }

    fun runAutonomous(goal: String) {
        sendOrchestrated(goal)
    }

    fun submitAnswer(answer: String) {
        val pending = _state.value.pendingQuestion ?: return
        val answerMsg = UIMessage(
            role = MessageRole.USER,
            parts = listOf(UIMessagePart.Text(answer)),
        )
        _state.value = _state.value.copy(
            messages = _state.value.messages + answerMsg,
            pendingQuestion = null,
        )
        generationPipeline.resume(::buildGenerationConfig)
    }

    fun stopGeneration() {
        generationPipeline.cancel()
        orchestrator.stop()
        _state.value = _state.value.copy(isProcessing = false, currentPhase = AgentPhase.IDLE)
    }

    // ── Conversation persistence ────────────────────────────────────

    fun saveConversation() {
        engineScope.launch { sessionManager.saveConversation() }
    }

    fun loadConversation(conversation: com.rk.ai.models.Conversation) =
        sessionManager.loadConversation(conversation)

    fun deleteConversation(conversationId: Uuid) =
        sessionManager.deleteConversation(conversationId)

    // ── Agent activity ──────────────────────────────────────────────

    fun trackAgentActivity(activity: AgentActivity) {
        _state.value = _state.value.copy(
            agentActivities = _state.value.agentActivities + activity,
        )
    }

    fun updateAgentActivity(agentName: String, status: AgentActivityStatus, result: AgentResult? = null) {
        val activities = _state.value.agentActivities.toMutableList()
        val idx = activities.indexOfLast { it.agentName == agentName && it.status == AgentActivityStatus.RUNNING }
        if (idx >= 0) {
            activities[idx] = activities[idx].copy(
                status = status,
                result = result,
                completedAt = if (status == AgentActivityStatus.COMPLETED || status == AgentActivityStatus.FAILED)
                    System.currentTimeMillis() else null,
            )
            _state.value = _state.value.copy(agentActivities = activities)
        }
    }

    // ── Security alerts ─────────────────────────────────────────────

    fun addSecurityAlert(alert: SecurityAlert) {
        _state.value = _state.value.copy(
            securityAlerts = _state.value.securityAlerts + alert,
        )
    }

    fun dismissSecurityAlert(id: String?) {
        if (id == null) return
        _state.value = _state.value.copy(
            securityAlerts = _state.value.securityAlerts.filter { it.id != id },
        )
    }

    fun clearSecurityAlerts() {
        _state.value = _state.value.copy(securityAlerts = emptyList())
    }

    // ── Tool approval ───────────────────────────────────────────────

    fun approveTool(toolCallId: String) {
        if (isProcessing) return
        updateToolApproval(toolCallId, ToolApprovalState.Approved)
    }

    fun denyTool(toolCallId: String, reason: String = "") {
        if (isProcessing) return
        updateToolApproval(toolCallId, ToolApprovalState.Denied(reason))
    }

    fun answerTool(toolCallId: String, answer: String) {
        if (isProcessing) return
        updateToolApproval(toolCallId, ToolApprovalState.Answered(answer))
    }

    private fun updateToolApproval(toolCallId: String, newState: ToolApprovalState) {
        val messages = _state.value.messages.toMutableList()
        for (i in messages.indices) {
            val msg = messages[i]
            val updatedParts = msg.parts.map { part ->
                if (part is UIMessagePart.Tool && part.toolCallId == toolCallId) {
                    part.copy(approvalState = newState)
                } else part
            }
            if (updatedParts !== msg.parts) {
                messages[i] = msg.copy(parts = updatedParts)
                _state.value = _state.value.copy(messages = messages)
                sessionManager.saveCurrentSessionMessages()
                generationPipeline.resume(::buildGenerationConfig)
                return
            }
        }
    }

    // ── Message management ──────────────────────────────────────────

    fun deleteMessage(index: Int) {
        val msgs = _state.value.messages.toMutableList()
        if (index in msgs.indices) {
            val deleted = msgs.removeAt(index)
            _state.value = _state.value.copy(
                messages = msgs,
                recentlyDeletedMessage = index to deleted,
            )
            sessionManager.saveCurrentSessionMessages()
        }
    }

    fun clearConversation() {
        PlanManager.clear()
        _state.value = VibeCodingState(
            commandCatalog = configManager.getCommandCatalog(),
            permissionAutoRespondRules = permissionManager.rules,
        )
        systemPromptTransformer.reset()
    }

    fun undoDeleteMessage() {
        val deleted = _state.value.recentlyDeletedMessage ?: return
        val (index, msg) = deleted
        val msgs = _state.value.messages.toMutableList()
        val insertAt = index.coerceIn(0, msgs.size)
        msgs.add(insertAt, msg)
        _state.value = _state.value.copy(messages = msgs, recentlyDeletedMessage = null)
        sessionManager.saveCurrentSessionMessages()
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    // ── Debug & UI state ────────────────────────────────────────────

    fun toggleSuggestions() {
        _state.value = _state.value.copy(showSuggestions = !_state.value.showSuggestions)
    }

    fun toggleDebugMode() {
        val newMode = !_state.value.debugMode
        _state.value = _state.value.copy(debugMode = newMode)
        if (!newMode) {
            _state.value = _state.value.copy(debugInfo = null)
        }
    }

    private var lastTokenEstimateAt = 0L
    private var lastEstimateMessageCount = -1

    fun updateDebugInfo() {
        val s = _state.value
        val msgs = s.messages
        var updated = s
        // Throttle the O(n) token estimate: it only feeds a status-bar readout, so
        // there is no need to recompute it on every streamed chunk. Force a refresh
        // whenever the message count changes (e.g. conversation cleared/loaded).
        val now = SystemClock.elapsedRealtime()
        if (msgs.size != lastEstimateMessageCount || now - lastTokenEstimateAt >= 300L) {
            lastEstimateMessageCount = msgs.size
            lastTokenEstimateAt = now
            val tokenEstimate = if (msgs.isNotEmpty()) com.rk.ai.agent.TokenEstimator.estimate(msgs) else null
            updated = if (s.contextTokens != tokenEstimate) s.copy(contextTokens = tokenEstimate) else s
        }
        if (s.debugMode) {
            val lastUser = msgs.lastOrNull { it.role == MessageRole.USER }
            val lastAssistant = msgs.lastOrNull { it.role == MessageRole.ASSISTANT }
            val toolCalls = msgs.flatMap { it.getTools() }.map { "${it.toolName}(${it.input.take(100)}) -> ${it.statusLabel}" }
            val settings = settingsStore.settingsFlow.value
            val model = settings.findModelById(settings.chatModelId)
            updated = updated.copy(
                debugInfo = DebugInfo(
                    lastPrompt = lastUser?.toText() ?: "",
                    lastResponse = lastAssistant?.toText() ?: "",
                    lastToolCalls = toolCalls.takeLast(20),
                    inputMessages = msgs.filter { it.role == MessageRole.USER },
                    outputMessages = msgs.filter { it.role == MessageRole.ASSISTANT },
                    modelName = model?.displayName?.ifEmpty { model.modelId } ?: "No model",
                    totalTokens = s.toolExecutions.sumOf { it.tokens },
                ),
            )
        }
        if (updated !== s) _state.value = updated
    }

    // ── Misc ────────────────────────────────────────────────────────

    fun dispose() {
        generationPipeline.cancel()
        orchestrator.stop()
        engineScope.coroutineContext[Job]?.cancel()
        appScope.coroutineContext[Job]?.cancel()
        database.close()
    }

    fun getCurrentAssistantId(): Uuid {
        val settings = settingsStore.settingsFlow.value
        return runCatching { settings.getCurrentAssistant().id }.getOrElse {
            Uuid.parse("0950e2dc-9bd5-4801-afa3-aa887aa36b4e")
        }
    }

    fun openFileInEditor(path: String) {
        ideService.openFile(File(path))
    }

    // ── Internal ────────────────────────────────────────────────────

    private suspend fun generateWithLLM(
        prompt: String,
        tools: List<Tool>,
        context: com.rk.ai.agent.context.ContextBundle,
        conversationHistory: List<UIMessage> = emptyList(),
    ): String {
        val config = buildGenerationConfig() ?: return ""
        val settings = config.settings
        val model = config.model
        val assistant = config.assistant
        val memories = config.memories
        val inputTransformers = config.inputTransformers
        val outputTransformers = config.outputTransformers

        val messages = conversationHistory + listOf(
            UIMessage.user(prompt)
        )
        var resultText = ""
        generationHandler.generateText(
            settings = settings,
            model = model,
            messages = messages,
            assistant = assistant,
            memories = memories,
            tools = tools,
            inputTransformers = inputTransformers,
            outputTransformers = outputTransformers,
            maxSteps = 50,
        ).collect { chunk ->
            when (chunk) {
                is com.rk.ai.agent.GenerationChunk.Messages -> {
                    val lastMsg = chunk.messages.lastOrNull()
                    if (lastMsg != null) {
                        resultText = lastMsg.toText()
                    }
                }
                is com.rk.ai.agent.GenerationChunk.GenerationError -> { }
                else -> { }
            }
        }
        return resultText
    }

    private suspend fun buildGenerationConfig(): GenerationConfig? {
        val settings = settingsStore.settingsFlow.value
        val model = settings.findModelById(settings.chatModelId)
        if (model == null) {
            _state.value = _state.value.copy(
                isProcessing = false,
                error = "No model selected. Configure a provider and model in VibeCoding settings.",
            )
            engineScope.launch { vibeEventBus.emit(VibeCodingEvent.GenerationError) }
            return null
        }

        val assistant = settings.getCurrentAssistant()
        val tools = buildToolList(assistant, settings)

        val memories = if (assistant.enableMemory) {
            val memoryAssistantId = if (assistant.useGlobalMemory) {
                MemoryRepository.GLOBAL_MEMORY_ID
            } else {
                assistant.id.toString()
            }
            memoryRepo.getMemoriesOfAssistant(memoryAssistantId)
        } else null

        val inputTransformers = listOfNotNull(
            systemPromptTransformer,
            PlaceholderTransformer,
            if (assistant.enableTimeReminder) TimeReminderTransformer else null,
            PromptInjectionTransformer,
        )

        val outputTransformers = listOfNotNull(
            ToolTagSanitizerTransformer,
            RegexOutputTransformer,
            Base64ImageToLocalFileTransformer.also { it.filesManager = filesManager },
        )

        return GenerationConfig(
            settings = settings,
            model = model,
            assistant = assistant,
            messages = _state.value.messages,
            tools = tools,
            memories = memories,
            inputTransformers = inputTransformers,
            outputTransformers = outputTransformers,
        )
    }
}
