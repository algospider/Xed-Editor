@file:OptIn(ExperimentalUuidApi::class)
package com.rk.ai.nativeagent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import com.rk.ai.nativeagent.engine.VibeCodingEngine
import com.rk.ai.nativeagent.ui.components.*
import com.rk.ai.nativeagent.ui.panels.*
import com.rk.ai.persistence.settings.getCurrentAssistant
import com.rk.components.compose.sheet.XedBottomSheet
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi

/** Panels opened via ModalBottomSheet from the toolbar. */
enum class ToolPanel {
    NONE, COMMANDS, SKILLS, AGENTS, PERMISSIONS, INSTRUCTIONS, PLUGINS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VibeCodingPanel(
    engine: VibeCodingEngine,
    modifier: Modifier = Modifier,
) {
    val state by engine.state.collectAsState()
    val colorScheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val isTablet = configuration.screenWidthDp >= 600

    // ── UI state ──
    var showSettings by remember { mutableStateOf(false) }
    var activePanel by remember { mutableStateOf(ToolPanel.NONE) }
    var showHistory by remember { mutableStateOf(false) }
    var showFiles by remember { mutableStateOf(false) }
    var historyRefreshTrigger by remember { mutableStateOf(0) }

    // Dialogs
    var showClearDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var showStopConfirmDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var sessionToRename by remember { mutableStateOf<kotlin.uuid.Uuid?>(null) }
    var conversationToDelete by remember { mutableStateOf<com.rk.ai.models.Conversation?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val snackbarHostState = remember { SnackbarHostState() }

    val hasTodos = state.todos.isNotEmpty()

    // ── Modal bottom sheets ──
    if (activePanel != ToolPanel.NONE) {
        XedBottomSheet(
            onDismissRequest = { activePanel = ToolPanel.NONE },
            sheetState = sheetState,
            showDragHandle = true,
            showCloseButton = false,
            title = null,
        ) {
            Box(modifier = Modifier.fillMaxHeight(0.88f)) {
                when (activePanel) {
                    ToolPanel.COMMANDS -> {
                        val builtinCommands = remember {
                            listOf(
                                PaletteCommand("init", "Init", "Initialize project instructions (AGENTS.md)", "Initialize project with AGENTS.md based on codebase analysis", "Project"),
                                PaletteCommand("review", "Review", "Review recent code changes", "Review all uncommitted changes for bugs and quality", "Code"),
                                PaletteCommand("test", "Test", "Run tests and analyze results", "Run the test suite and report failures with fix suggestions", "Code"),
                                PaletteCommand("commit", "Commit", "Stage and commit changes", "Stage all changes and create a descriptive commit", "Git"),
                                PaletteCommand("push", "Push", "Push commits to remote", "Push the current branch to origin", "Git"),
                                PaletteCommand("changelog", "Changelog", "Generate changelog from recent commits", "Generate a changelog file from git history", "Project"),
                                PaletteCommand("summarize", "Summarize", "Summarize current conversation", "Create a summary of the conversation context for reference", "General"),
                                PaletteCommand("plan", "Plan", "Create execution plan", "Create a step-by-step plan for a complex task", "General"),
                                PaletteCommand("issues", "Issues", "Find matching GitHub issues", "Search GitHub issues matching the current context", "Git"),
                                PaletteCommand("feature-dev", "Feature Dev", "Guided feature development", "Systematic 7-phase feature dev: discover, explore, design, implement, review", "Feature"),
                            )
                        }
                        val fileCommands = remember(state.commandCatalog) {
                            engine.getCommandCatalog()
                                .filter { it.id.startsWith("file:") }
                                .map { cmd ->
                                    PaletteCommand(
                                        id = cmd.id, name = cmd.title, description = cmd.description,
                                        prompt = cmd.prompt, category = cmd.category,
                                    )
                                }
                        }
                        CommandPaletteSheet(
                            builtinCommands = builtinCommands,
                            fileCommands = fileCommands,
                            onDismiss = { activePanel = ToolPanel.NONE },
                            onExecuteCommand = { command ->
                                engine.sendMessage(command.prompt)
                                activePanel = ToolPanel.NONE
                            },
                            onRefreshCommands = { engine.refreshCommands() },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    ToolPanel.SKILLS -> {
                        val settings by engine.settingsStore.settingsFlow.collectAsState()
                        val currentAssistant = settings.getCurrentAssistant()
                        SkillBrowserPanel(
                            skillsDir = "$workspacePath/.xed/skills",
                            enabledSkills = currentAssistant.enabledSkills,
                            onToggleSkill = { skillName, enabled ->
                                scope.launch {
                                    engine.settingsStore.update { s ->
                                        s.copy(assistants = s.assistants.map { a ->
                                            if (a.id == currentAssistant.id) {
                                                val updatedSkills = if (enabled) a.enabledSkills + skillName else a.enabledSkills - skillName
                                                a.copy(enabledSkills = updatedSkills)
                                            } else a
                                        })
                                    }
                                }
                            },
                            onEditSkill = { engine.openFileInEditor(it) },
                            onDismiss = { activePanel = ToolPanel.NONE },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    ToolPanel.AGENTS -> AgentConfigPanel(
                        settingsStore = engine.settingsStore,
                        onDismiss = { activePanel = ToolPanel.NONE },
                        modifier = Modifier.fillMaxSize(),
                    )
                    ToolPanel.PERMISSIONS -> PermissionEditorPanel(
                        engine = engine,
                        onDismiss = { activePanel = ToolPanel.NONE },
                        modifier = Modifier.fillMaxSize(),
                    )
                    ToolPanel.INSTRUCTIONS -> InstructionsEditorPanel(
                        workspacePath = workspacePath,
                        onDismiss = { activePanel = ToolPanel.NONE },
                        modifier = Modifier.fillMaxSize(),
                    )
                    ToolPanel.PLUGINS -> PluginManagerPanel(
                        onDismiss = { activePanel = ToolPanel.NONE },
                        modifier = Modifier.fillMaxSize(),
                    )
                    ToolPanel.NONE -> {}
                }
            }
        }
    }

    val workspacePath by remember {
        derivedStateOf { try { engine.ideService.getPrimaryWorkspacePath() } catch (_: Exception) { "" } }
    }

    // ── Main scaffold ──
    Scaffold(
        modifier = modifier.fillMaxSize().onKeyEvent { event ->
            if (event.key == Key.Escape && event.type == KeyEventType.KeyUp) {
                if (state.isProcessing) { showStopConfirmDialog = true; true } else false
            } else false
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {

            Row(modifier = Modifier.fillMaxSize()) {
                // File tree sidebar
                AnimatedVisibility(
                    visible = showFiles,
                    enter = slideInHorizontally { -it },
                    exit = slideOutHorizontally { -it },
                ) {
                    VibeCodingFileTreeSidebar(
                        ideService = engine.ideService,
                        workspacePath = workspacePath,
                        onOpenFile = { path -> engine.openFileInEditor(path) },
                        onDismiss = { showFiles = false },
                        modifier = Modifier.width(260.dp).fillMaxHeight(),
                    )
                }

                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    // Toolbar
                    VibeCodingToolbar(
                        state = state,
                        engine = engine,
                        showFiles = showFiles,
                        showHistory = showHistory,
                        onToggleFiles = { showFiles = !showFiles },
                        onToggleHistory = { showHistory = !showHistory },
                        onOpenPanel = { activePanel = it },
                        onShowClearDialog = { showClearDialog = true },
                        onShowExportDialog = { showExportDialog = true },
                        onSettings = { showSettings = true },
                    )

                    // Session tabs (minimal — only visible with multiple sessions)
                    if (state.sessionTree.size > 1) {
                        VibeCodingSessionTabs(
                            sessionTree = state.sessionTree,
                            activeSessionId = state.activeSessionId,
                            isProcessing = state.isProcessing,
                            onSwitchSession = { engine.switchToSession(it) },
                            onNewBranch = {
                                val parent = state.activeSessionId ?: return@VibeCodingSessionTabs
                                engine.createBranchSession(parent)
                            },
                            onRenameSession = { id, _ -> sessionToRename = id },
                            onCloseSession = { id -> engine.closeSession(id) },
                        )
                    }

                    // Main content
                    Box(modifier = Modifier.weight(1f)) {
                        VibeCodingContentStack(
                            state = state,
                            engine = engine,
                            context = context,
                            hasTodos = hasTodos,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    // Error banner
                    AnimatedVisibility(
                        visible = state.error != null,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = colorScheme.errorContainer.copy(alpha = 0.8f),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = state.error ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colorScheme.onErrorContainer,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { engine.clearError() }, modifier = Modifier.size(20.dp)) {
                                    Icon(Icons.Outlined.Close, contentDescription = "Dismiss", modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }

                    // Status bar
                    VibeCodingStatusBar(
                        state = state,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )

                    // Input
                    VibeCodingInput(
                        isProcessing = state.isProcessing,
                        onSend = { text, parts -> engine.sendMessage(text, parts) },
                        onStop = {
                            if (state.toolExecutions.isNotEmpty() || state.taskTree != null) {
                                showStopConfirmDialog = true
                            } else {
                                engine.stopGeneration()
                            }
                        },
                    )
                }
            }

            // History sidebar
            AnimatedVisibility(
                visible = showHistory,
                enter = slideInHorizontally { it },
                exit = slideOutHorizontally { it },
            ) {
                VibeCodingConversationSidebar(
                    conversationRepo = engine.generationHandler.conversationRepo,
                    currentConversationId = state.currentConversationId,
                    assistantId = engine.getCurrentAssistantId(),
                    refreshTrigger = historyRefreshTrigger,
                    onSelectConversation = { conversation ->
                        engine.loadConversation(conversation)
                        showHistory = false
                    },
                    onDeleteConversation = { conv ->
                        conversationToDelete = conv
                        showDeleteConfirmDialog = true
                    },
                    onDismiss = { showHistory = false },
                    modifier = Modifier.width(260.dp).fillMaxHeight(),
                )
            }
        }
    }

    // ── Undo snackbar ──
    LaunchedEffect(state.recentlyDeletedMessage) {
        if (state.recentlyDeletedMessage != null) {
            val result = snackbarHostState.showSnackbar(
                message = "Message deleted",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) {
                engine.undoDeleteMessage()
            }
        }
    }

    // ── Dialogs ──
    if (sessionToRename != null) {
        RenameSessionDialog(
            sessionId = sessionToRename!!,
            currentTitle = state.sessionTree.find { it.id == sessionToRename }?.title ?: "New Session",
            onRename = { id, title -> engine.renameSession(id, title) },
            onDismiss = { sessionToRename = null },
        )
    }
    if (showStopConfirmDialog) {
        StopConfirmDialog(
            onStop = { engine.stopGeneration() },
            onDismiss = { showStopConfirmDialog = false },
        )
    }
    if (showExportDialog) {
        ExportConversationDialog(state = state, onDismiss = { showExportDialog = false })
    }
    if (showClearDialog) {
        ClearConversationDialog(
            onClear = { engine.clearConversation() },
            onDismiss = { showClearDialog = false },
        )
    }
    if (showDeleteConfirmDialog && conversationToDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Delete Conversation") },
            text = {
                Text("Delete conversation \"${conversationToDelete!!.title.ifBlank { "Untitled" }}\"? This cannot be undone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    engine.deleteConversation(conversationToDelete!!.id)
                    historyRefreshTrigger++
                    showDeleteConfirmDialog = false
                    conversationToDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) { Text("Cancel") }
            },
        )
    }
    if (showSettings) {
        VibeCodingSettingsSheet(engine = engine, onDismiss = { showSettings = false })
    }
}
