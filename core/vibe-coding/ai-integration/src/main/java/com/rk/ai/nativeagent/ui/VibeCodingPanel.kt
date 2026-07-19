@file:OptIn(ExperimentalUuidApi::class, ExperimentalMaterial3Api::class)
package com.rk.ai.nativeagent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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

@Composable
fun VibeCodingPanel(
    engine: VibeCodingEngine,
    modifier: Modifier = Modifier,
) {
    val state by engine.state.collectAsState()
    val colorScheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // ── Responsive detection ──
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp
    val isTablet = screenWidthDp >= 600.dp
    val sidebarWidth = if (isTablet) 260.dp else (screenWidthDp * 0.72f).coerceAtMost(280.dp).coerceAtLeast(200.dp)

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

    val workspacePath by remember {
        derivedStateOf { try { engine.ideService.getPrimaryWorkspacePath() } catch (_: Exception) { "" } }
    }

    val hasTodos = state.todos.isNotEmpty()

    // ── Modal bottom sheets (tool panels) ──
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
                                PaletteCommand("init", "Init", "Initialize project with AGENTS.md", "Initialize project with AGENTS.md based on codebase analysis", "Project"),
                                PaletteCommand("review", "Review", "Review recent code changes", "Review all uncommitted changes for bugs and quality", "Code"),
                                PaletteCommand("test", "Test", "Run tests and analyze results", "Run the test suite and report failures with fix suggestions", "Code"),
                                PaletteCommand("commit", "Commit", "Stage and commit changes", "Stage all changes and create a descriptive commit", "Git"),
                                PaletteCommand("push", "Push", "Push commits to remote", "Push the current branch to origin", "Git"),
                                PaletteCommand("plan", "Plan", "Create execution plan", "Create a step-by-step plan for a complex task", "General"),
                                PaletteCommand("summarize", "Summarize", "Summarize current conversation", "Create a summary of the conversation context for reference", "General"),
                            )
                        }
                        val fileCommands = remember(state.commandCatalog) {
                            engine.getCommandCatalog().filter { it.id.startsWith("file:") }
                                .map { cmd -> PaletteCommand(id = cmd.id, name = cmd.title, description = cmd.description, prompt = cmd.prompt, category = cmd.category) }
                        }
                        CommandPaletteSheet(builtinCommands = builtinCommands, fileCommands = fileCommands,
                            onDismiss = { activePanel = ToolPanel.NONE },
                            onExecuteCommand = { command -> engine.sendMessage(command.prompt); activePanel = ToolPanel.NONE },
                            onRefreshCommands = { engine.refreshCommands() }, modifier = Modifier.fillMaxSize())
                    }
                    ToolPanel.SKILLS -> {
                        val settings by engine.settingsStore.settingsFlow.collectAsState()
                        val currentAssistant = settings.getCurrentAssistant()
                        SkillBrowserPanel(skillsDir = "$workspacePath/.xed/skills", enabledSkills = currentAssistant.enabledSkills,
                            onToggleSkill = { skillName, enabled -> scope.launch { engine.settingsStore.update { s -> s.copy(assistants = s.assistants.map { a -> if (a.id == currentAssistant.id) { val updatedSkills = if (enabled) a.enabledSkills + skillName else a.enabledSkills - skillName; a.copy(enabledSkills = updatedSkills) } else a }) } } },
                            onEditSkill = { engine.openFileInEditor(it) }, onDismiss = { activePanel = ToolPanel.NONE }, modifier = Modifier.fillMaxSize())
                    }
                    ToolPanel.AGENTS -> AgentConfigPanel(settingsStore = engine.settingsStore, onDismiss = { activePanel = ToolPanel.NONE }, modifier = Modifier.fillMaxSize())
                    ToolPanel.PERMISSIONS -> PermissionEditorPanel(engine = engine, onDismiss = { activePanel = ToolPanel.NONE }, modifier = Modifier.fillMaxSize())
                    ToolPanel.INSTRUCTIONS -> InstructionsEditorPanel(workspacePath = workspacePath, onDismiss = { activePanel = ToolPanel.NONE }, modifier = Modifier.fillMaxSize())
                    ToolPanel.PLUGINS -> PluginManagerPanel(onDismiss = { activePanel = ToolPanel.NONE }, modifier = Modifier.fillMaxSize())
                    ToolPanel.NONE -> {}
                }
            }
        }
    }

    // ── Main layout ──
    Box(modifier = modifier.fillMaxSize()) {

        // On tablet: Row with persistent sidebars
        // On phone: single-column with overlay sidebars
        if (isTablet) {
            TabletLayout(
                showFiles = showFiles,
                showHistory = showHistory,
                onHideFiles = { showFiles = false },
                onHideHistory = { showHistory = false },
                sidebarWidth = sidebarWidth,
                engine = engine,
                state = state,
                colorScheme = colorScheme,
                context = context,
                hasTodos = hasTodos,
                workspacePath = workspacePath,
                sessionToRename = sessionToRename,
                activePanel = activePanel,
                historyRefreshTrigger = historyRefreshTrigger,
                conversationToDelete = conversationToDelete,
                onToggleFiles = { showFiles = !showFiles },
                onToggleHistory = { showHistory = !showHistory },
                onOpenPanel = { activePanel = it },
                onShowClearDialog = { showClearDialog = true },
                onShowExportDialog = { showExportDialog = true },
                onSettings = { showSettings = true },
                onStopConfirm = { showStopConfirmDialog = true },
                onSetSessionToRename = { sessionToRename = it },
                onSetConversationToDelete = {
                    conversationToDelete = it
                    showDeleteConfirmDialog = true
                },
                onIncrementHistoryRefresh = { historyRefreshTrigger++ },
            )
        } else {
            PhoneLayout(
                showFiles = showFiles,
                showHistory = showHistory,
                onHideFiles = { showFiles = false },
                onHideHistory = { showHistory = false },
                sidebarWidth = sidebarWidth,
                engine = engine,
                state = state,
                colorScheme = colorScheme,
                context = context,
                hasTodos = hasTodos,
                workspacePath = workspacePath,
                sessionToRename = sessionToRename,
                activePanel = activePanel,
                historyRefreshTrigger = historyRefreshTrigger,
                conversationToDelete = conversationToDelete,
                onToggleFiles = { showFiles = !showFiles },
                onToggleHistory = { showHistory = !showHistory },
                onOpenPanel = { activePanel = it },
                onShowClearDialog = { showClearDialog = true },
                onShowExportDialog = { showExportDialog = true },
                onSettings = { showSettings = true },
                onStopConfirm = { showStopConfirmDialog = true },
                onSetSessionToRename = { sessionToRename = it },
                onSetConversationToDelete = {
                    conversationToDelete = it
                    showDeleteConfirmDialog = true
                },
                onIncrementHistoryRefresh = { historyRefreshTrigger++ },
            )
        }

        // ── Snackbar host ──
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp),
        )
    }

    // ── Undo snackbar ──
    LaunchedEffect(state.recentlyDeletedMessage) {
        if (state.recentlyDeletedMessage != null) {
            val result = snackbarHostState.showSnackbar(message = "Message deleted", actionLabel = "Undo", duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) engine.undoDeleteMessage()
        }
    }

    // ── Dialogs ──
    if (sessionToRename != null) RenameSessionDialog(sessionId = sessionToRename!!, currentTitle = state.sessionTree.find { it.id == sessionToRename }?.title ?: "New Session", onRename = { id, title -> engine.renameSession(id, title) }, onDismiss = { sessionToRename = null })
    if (showStopConfirmDialog) StopConfirmDialog(onStop = { engine.stopGeneration() }, onDismiss = { showStopConfirmDialog = false })
    if (showExportDialog) ExportConversationDialog(state = state, onDismiss = { showExportDialog = false })
    if (showClearDialog) ClearConversationDialog(onClear = { engine.clearConversation() }, onDismiss = { showClearDialog = false })
    if (showDeleteConfirmDialog && conversationToDelete != null) {
        AlertDialog(onDismissRequest = { showDeleteConfirmDialog = false }, title = { Text("Delete Conversation") },
            text = { Text("Delete conversation \"${conversationToDelete!!.title.ifBlank { "Untitled" }}\"? This cannot be undone.") },
            confirmButton = { TextButton(onClick = { engine.deleteConversation(conversationToDelete!!.id); historyRefreshTrigger++; showDeleteConfirmDialog = false; conversationToDelete = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { showDeleteConfirmDialog = false }) { Text("Cancel") } })
    }
    if (showSettings) VibeCodingSettingsSheet(engine = engine, onDismiss = { showSettings = false })
}

// ── Tablet layout: persistent sidebars in a Row ──
@Composable
private fun TabletLayout(
    showFiles: Boolean,
    showHistory: Boolean,
    onHideFiles: () -> Unit,
    onHideHistory: () -> Unit,
    sidebarWidth: Dp,
    engine: VibeCodingEngine,
    state: com.rk.ai.nativeagent.engine.VibeCodingState,
    colorScheme: ColorScheme,
    context: android.content.Context,
    hasTodos: Boolean,
    workspacePath: String,
    sessionToRename: kotlin.uuid.Uuid?,
    activePanel: ToolPanel,
    historyRefreshTrigger: Int,
    conversationToDelete: com.rk.ai.models.Conversation?,
    onToggleFiles: () -> Unit,
    onToggleHistory: () -> Unit,
    onOpenPanel: (ToolPanel) -> Unit,
    onShowClearDialog: () -> Unit,
    onShowExportDialog: () -> Unit,
    onSettings: () -> Unit,
    onStopConfirm: () -> Unit,
    onSetSessionToRename: (kotlin.uuid.Uuid?) -> Unit,
    onSetConversationToDelete: (com.rk.ai.models.Conversation?) -> Unit,
    onIncrementHistoryRefresh: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        // File tree sidebar (persistent)
        AnimatedVisibility(visible = showFiles, enter = slideInHorizontally { -it }, exit = slideOutHorizontally { -it }) {
            VibeCodingFileTreeSidebar(ideService = engine.ideService, workspacePath = workspacePath,
                onOpenFile = { path -> engine.openFileInEditor(path) }, onDismiss = onHideFiles,
                modifier = Modifier.width(sidebarWidth).fillMaxHeight())
        }

        MainContent(
            engine = engine, state = state, colorScheme = colorScheme, context = context,
            hasTodos = hasTodos, workspacePath = workspacePath,
            onToggleFiles = onToggleFiles, onToggleHistory = onToggleHistory,
            onOpenPanel = onOpenPanel, onShowClearDialog = onShowClearDialog,
            onShowExportDialog = onShowExportDialog, onSettings = onSettings,
            onStopConfirm = onStopConfirm,
            onRenameSession = { id, _ -> onSetSessionToRename(id) },
            showFiles = showFiles, showHistory = showHistory,
        )

        // History sidebar (persistent)
        AnimatedVisibility(visible = showHistory, enter = slideInHorizontally { it }, exit = slideOutHorizontally { it }) {
            VibeCodingConversationSidebar(conversationRepo = engine.generationHandler.conversationRepo,
                currentConversationId = state.currentConversationId, assistantId = engine.getCurrentAssistantId(),
                refreshTrigger = historyRefreshTrigger,
                onSelectConversation = { conversation -> engine.loadConversation(conversation); onHideHistory() },
                onDeleteConversation = { conv -> onSetConversationToDelete(conv) },
                onDismiss = onHideHistory, modifier = Modifier.width(sidebarWidth).fillMaxHeight())
        }
    }
}

// ── Phone layout: overlay sidebars over main content ──
@Composable
private fun PhoneLayout(
    showFiles: Boolean,
    showHistory: Boolean,
    onHideFiles: () -> Unit,
    onHideHistory: () -> Unit,
    sidebarWidth: Dp,
    engine: VibeCodingEngine,
    state: com.rk.ai.nativeagent.engine.VibeCodingState,
    colorScheme: ColorScheme,
    context: android.content.Context,
    hasTodos: Boolean,
    workspacePath: String,
    sessionToRename: kotlin.uuid.Uuid?,
    activePanel: ToolPanel,
    historyRefreshTrigger: Int,
    conversationToDelete: com.rk.ai.models.Conversation?,
    onToggleFiles: () -> Unit,
    onToggleHistory: () -> Unit,
    onOpenPanel: (ToolPanel) -> Unit,
    onShowClearDialog: () -> Unit,
    onShowExportDialog: () -> Unit,
    onSettings: () -> Unit,
    onStopConfirm: () -> Unit,
    onSetSessionToRename: (kotlin.uuid.Uuid?) -> Unit,
    onSetConversationToDelete: (com.rk.ai.models.Conversation?) -> Unit,
    onIncrementHistoryRefresh: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Main content fills the whole area
        MainContent(
            engine = engine, state = state, colorScheme = colorScheme, context = context,
            hasTodos = hasTodos, workspacePath = workspacePath,
            onToggleFiles = onToggleFiles, onToggleHistory = onToggleHistory,
            onOpenPanel = onOpenPanel, onShowClearDialog = onShowClearDialog,
            onShowExportDialog = onShowExportDialog, onSettings = onSettings,
            onStopConfirm = onStopConfirm,
            onRenameSession = { id, _ -> onSetSessionToRename(id) },
            showFiles = showFiles, showHistory = showHistory,
        )

        // File sidebar overlay (slides from left, with scrim)
        AnimatedVisibility(
            visible = showFiles,
            enter = fadeIn() + slideInHorizontally { -it },
            exit = fadeOut() + slideOutHorizontally { -it },
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Scrim
                Box(
                    modifier = Modifier.fillMaxSize()
                        .background(colorScheme.scrim.copy(alpha = 0.45f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onHideFiles,
                        )
                )
                // Sidebar
                VibeCodingFileTreeSidebar(
                    ideService = engine.ideService, workspacePath = workspacePath,
                    onOpenFile = { path -> engine.openFileInEditor(path) },
                    onDismiss = onHideFiles,
                    modifier = Modifier.width(sidebarWidth).fillMaxHeight(),
                )
            }
        }

        // History sidebar overlay (slides from right, with scrim)
        AnimatedVisibility(
            visible = showHistory,
            enter = fadeIn() + slideInHorizontally { it },
            exit = fadeOut() + slideOutHorizontally { it },
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Scrim
                Box(
                    modifier = Modifier.fillMaxSize()
                        .background(colorScheme.scrim.copy(alpha = 0.45f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onHideHistory,
                        )
                )
                // Sidebar — align to right
                Box(modifier = Modifier.fillMaxSize().wrapContentSize(align = Alignment.CenterEnd)) {
                    VibeCodingConversationSidebar(
                        conversationRepo = engine.generationHandler.conversationRepo,
                        currentConversationId = state.currentConversationId,
                        assistantId = engine.getCurrentAssistantId(),
                        refreshTrigger = historyRefreshTrigger,
                        onSelectConversation = { conversation ->
                            engine.loadConversation(conversation)
                            onHideHistory()
                        },
                        onDeleteConversation = { conv -> onSetConversationToDelete(conv) },
                        onDismiss = onHideHistory,
                        modifier = Modifier.width(sidebarWidth).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

// ── Shared main content column ──
@Composable
private fun MainContent(
    engine: VibeCodingEngine,
    state: com.rk.ai.nativeagent.engine.VibeCodingState,
    colorScheme: ColorScheme,
    context: android.content.Context,
    hasTodos: Boolean,
    workspacePath: String,
    onToggleFiles: () -> Unit,
    onToggleHistory: () -> Unit,
    onOpenPanel: (ToolPanel) -> Unit,
    onShowClearDialog: () -> Unit,
    onShowExportDialog: () -> Unit,
    onSettings: () -> Unit,
    onStopConfirm: () -> Unit,
    onRenameSession: ((kotlin.uuid.Uuid, String) -> Unit)? = null,
    showFiles: Boolean = false,
    showHistory: Boolean = false,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Toolbar (compact) with editor context
        VibeCodingToolbar(
            engine = engine, state = state,
            showFiles = showFiles, showHistory = showHistory,
            onToggleFiles = onToggleFiles, onToggleHistory = onToggleHistory,
            onOpenPanel = onOpenPanel, onShowClearDialog = onShowClearDialog,
            onShowExportDialog = onShowExportDialog, onSettings = onSettings,
        )

        // Session tabs (only when 2+ sessions exist)
        if (state.sessionTree.size > 1) {
            VibeCodingSessionTabs(sessionTree = state.sessionTree, activeSessionId = state.activeSessionId,
                isProcessing = state.isProcessing, onSwitchSession = { engine.switchToSession(it) },
                onNewBranch = { val parent = state.activeSessionId ?: return@VibeCodingSessionTabs; engine.createBranchSession(parent) },
                onRenameSession = onRenameSession,
                onCloseSession = { id -> engine.closeSession(id) })
        }

        // Main content
        Box(modifier = Modifier.weight(1f)) {
            VibeCodingContentStack(state = state, engine = engine, context = context, hasTodos = hasTodos, modifier = Modifier.fillMaxSize())
        }

        // Error banner
        AnimatedVisibility(visible = state.error != null, enter = fadeIn(), exit = fadeOut()) {
            Surface(modifier = Modifier.fillMaxWidth(), color = colorScheme.errorContainer.copy(alpha = 0.8f)) {
                Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(text = state.error ?: "", style = MaterialTheme.typography.bodySmall, color = colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
                    IconButton(onClick = { engine.clearError() }, modifier = Modifier.size(20.dp)) { Icon(Icons.Outlined.Close, "Dismiss", modifier = Modifier.size(14.dp)) }
                }
            }
        }

        // Status bar + Input
        VibeCodingStatusBar(state = state, engine = engine)
        VibeCodingInput(isProcessing = state.isProcessing,
            onSend = { text, parts -> engine.sendMessage(text, parts) },
            onStop = { if (state.toolExecutions.isNotEmpty() || state.taskTree != null) onStopConfirm() else engine.stopGeneration() },
            engine = engine)
    }
}
