package com.rk.activities.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rk.ai.InlineAgentBar
import com.rk.ai.UnifiedToolSheet
import com.rk.commands.CommandPalette
import com.rk.commands.CommandProvider
import com.rk.editor.preloadSelectionColor
import com.rk.filetree.FileAction
import com.rk.filetree.FileActionContext
import com.rk.filetree.FileActionDialogs
import com.rk.filetree.FileIcon
import com.rk.filetree.FileTreeViewModel
import com.rk.filetree.MultiFileAction
import com.rk.filetree.MultiFileActionContext
import com.rk.filetree.getActions
import com.rk.icons.XedIcon
import com.rk.resources.drawables
import com.rk.resources.getString
import com.rk.resources.strings
import com.rk.settings.Settings
import com.rk.tabs.base.Tab
import com.rk.tabs.editor.EditorTab
import com.rk.theme.DesignTokens
import com.rk.utils.dialog
import com.rk.utils.getGitColor
import kotlinx.coroutines.launch


@Composable
fun MainContent(
    innerPadding: PaddingValues,
    mainViewModel: MainViewModel,
    fileTreeViewModel: FileTreeViewModel,
    drawerState: DrawerState,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    preloadSelectionColor()
    FileActionDialogs(fileTreeViewModel, scope, context)

    if (mainViewModel.isDraggingPalette || mainViewModel.showCommandPalette) {
        val lastUsedCommand = CommandProvider.getForId(Settings.last_used_command)
        CommandPalette(
            progress = if (mainViewModel.showCommandPalette) 1f else mainViewModel.draggingPaletteProgress.value,
            commands = CommandProvider.commandList,
            lastUsedCommand = lastUsedCommand,
            initialChildCommands = mainViewModel.commandPaletteInitialChildCommands,
            initialPlaceholder = mainViewModel.commandPaletteInitialPlaceholder,
            onDismissRequest = { scope.launch { mainViewModel.closeCommandPalette() } },
        )
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (mainViewModel.tabs.isEmpty()) {
                var showAiWizard by remember { mutableStateOf(false) }
                if (showAiWizard) {
                    com.rk.ai.AiSetupWizardDialog(
                        onDismissRequest = { showAiWizard = false },
                        onComplete = { isVibeCoding ->
                            showAiWizard = false
                            mainViewModel.bottomPanelMode = if (isVibeCoding) BottomPanelMode.VIBE_CODING else BottomPanelMode.AI
                            mainViewModel.showBottomPanel = true
                        }
                    )
                }

                EmptyEditorState(
                    onNewFile = {
                        com.rk.activities.main.MainActivity.instance?.apply {
                            fileManager.createNewFile(mimeType = "*/*", title = "newfile.txt") { file ->
                                if (file != null) {
                                    mainViewModel.editorManager.addEditorTab(file, null, true)
                                }
                            }
                        }
                    },
                    onOpenFile = { scope.launch { drawerState.open() } },
                    onOpenTerminal = {
                        mainViewModel.bottomPanelMode = BottomPanelMode.TERMINAL
                        mainViewModel.showBottomPanel = true
                    },
                    onOpenAi = {
                        if (Settings.ai_api_key.isBlank()) {
                            showAiWizard = true
                        } else {
                            mainViewModel.bottomPanelMode = BottomPanelMode.AI
                            mainViewModel.showBottomPanel = true
                        }
                    },
                    onSetupAi = { showAiWizard = true },
                )
            } else {
                val pagerState = rememberPagerState(pageCount = { mainViewModel.tabs.size })

                LaunchedEffect(mainViewModel.currentTabIndex) {
                    if (
                        mainViewModel.tabs.isNotEmpty() &&
                        mainViewModel.currentTabIndex < mainViewModel.tabs.size &&
                        pagerState.currentPage != mainViewModel.currentTabIndex
                    ) {
                        if (Settings.smooth_tabs) {
                            pagerState.animateScrollToPage(mainViewModel.currentTabIndex)
                        } else {
                            pagerState.scrollToPage(mainViewModel.currentTabIndex)
                        }
                    }
                }

                EditorTabBar(
                    mainViewModel = mainViewModel,
                    fileTreeViewModel = fileTreeViewModel,
                )

                HorizontalDivider(thickness = DesignTokens.Divider.thin)

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize().clipToBounds(),
                    beyondViewportPageCount = mainViewModel.tabs.size,
                    userScrollEnabled = false,
                    key = { it },
                ) { page ->
                    if (page < mainViewModel.tabs.size) {
                        mainViewModel.tabs[page].Content()
                    }
                }
            }
        }

        if (mainViewModel.showBottomPanel) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { mainViewModel.showBottomPanel = false },
                    ),
            )
        }

        AnimatedVisibility(
            visible = mainViewModel.showBottomPanel,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            UnifiedToolSheet(
                viewModel = mainViewModel,
                onDismissRequest = { mainViewModel.showBottomPanel = false },
                modifier = Modifier.fillMaxSize(),
            )
        }

        InlineAgentBar(
            viewModel = mainViewModel,
            visible = mainViewModel.showInlineAgent && !mainViewModel.showBottomPanel,
            onDismiss = { mainViewModel.showInlineAgent = false },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun EditorTabBar(
    mainViewModel: MainViewModel,
    fileTreeViewModel: FileTreeViewModel,
) {
    Surface(
        tonalElevation = DesignTokens.Elevation.none,
        color = MaterialTheme.colorScheme.surface,
    ) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .height(DesignTokens.TabSize.compactHeight),
        ) {
            itemsIndexed(mainViewModel.tabs, key = { index, _ -> index }) { index, tabState ->
                CompactTabItem(
                    mainViewModel = mainViewModel,
                    fileTreeViewModel = fileTreeViewModel,
                    tabState = tabState,
                    index = index,
                )
            }
        }
    }
}

@Composable
private fun CompactTabItem(
    mainViewModel: MainViewModel,
    fileTreeViewModel: FileTreeViewModel,
    tabState: Tab,
    index: Int,
) {
    var showTabMenu by remember { mutableStateOf(false) }
    var showFileActionMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val isSelected = mainViewModel.currentTabIndex == index

    val bgColor = when {
        isSelected -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> MaterialTheme.colorScheme.surface
    }

    val gitColor = getGitColor(tabState.file)
    val activeColor = gitColor ?: MaterialTheme.colorScheme.primary
    val inactiveColor = gitColor ?: MaterialTheme.colorScheme.onSurfaceVariant

    val shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)

    Box(
        modifier = Modifier
            .widthIn(min = 110.dp, max = 220.dp)
            .fillMaxHeight()
            .clip(shape)
            .background(bgColor)
            .clickable {
                if (isSelected) showTabMenu = true
                else mainViewModel.tabManager.setCurrentTab(index)
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 10.dp, end = 4.dp),
        ) {
            if (Settings.show_tab_icons && tabState.file != null) {
                FileIcon(
                    file = tabState.file!!,
                    iconTint = if (isSelected) activeColor else inactiveColor,
                )
                Spacer(Modifier.width(6.dp))
            }

            if (tabState is EditorTab && tabState.editorState.isDirty) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.tertiary),
                )
                Spacer(Modifier.width(5.dp))
            }

            Text(
                text = tabState.tabTitle.value,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                ),
                color = if (isSelected) activeColor else inactiveColor,
                modifier = Modifier.weight(1f),
            )

            IconButton(
                onClick = { onCloseTab(mainViewModel, tabState) },
                modifier = Modifier.size(26.dp),
            ) {
                Icon(
                    painter = painterResource(drawables.close),
                    contentDescription = stringResource(strings.close_this),
                    modifier = Modifier.size(13.dp),
                    tint = if (isSelected) activeColor.copy(alpha = 0.7f) else inactiveColor.copy(alpha = 0.4f),
                )
            }
        }

        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(activeColor),
            )
        }

        DropdownMenu(expanded = showTabMenu, onDismissRequest = { showTabMenu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(strings.close_this)) },
                onClick = {
                    showTabMenu = false
                    onCloseTab(mainViewModel, tabState)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(strings.close_others)) },
                onClick = {
                    showTabMenu = false
                    closeOthers(mainViewModel, index)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(strings.close_all)) },
                onClick = {
                    showTabMenu = false
                    closeAll(mainViewModel)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(strings.move_left)) },
                enabled = index > 0,
                onClick = {
                    showTabMenu = false
                    mainViewModel.tabManager.moveTab(index, index - 1)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(strings.move_right)) },
                enabled = index < mainViewModel.tabs.size - 1,
                onClick = {
                    showTabMenu = false
                    mainViewModel.tabManager.moveTab(index, index + 1)
                },
            )
            tabState.file?.let {
                val fileExists by produceState(false) { value = it.exists() }
                DropdownMenuItem(
                    text = { Text(stringResource(strings.file_actions)) },
                    enabled = fileExists,
                    trailingIcon = {
                        Icon(
                            painter = painterResource(drawables.chevron_right),
                            contentDescription = stringResource(strings.open),
                        )
                    },
                    onClick = {
                        showTabMenu = false
                        showFileActionMenu = true
                    },
                )
            }
        }

        tabState.file?.let {
            DropdownMenu(expanded = showFileActionMenu, onDismissRequest = { showFileActionMenu = false }) {
                val root = (tabState as? EditorTab)?.projectRoot
                val actions = remember(it) { getActions(it, root) }
                actions.forEach { action ->
                    when (action) {
                        is FileAction -> {
                            DropdownMenuItem(
                                text = { Text(action.title) },
                                leadingIcon = { XedIcon(action.icon, contentDescription = action.title) },
                                enabled = action.isEnabled(it),
                                onClick = {
                                    val ctx = FileActionContext(it, root, fileTreeViewModel, context)
                                    action.action(ctx)
                                    showFileActionMenu = false
                                },
                            )
                        }
                        is MultiFileAction -> {
                            val files = listOf(it)
                            DropdownMenuItem(
                                text = { Text(action.title) },
                                leadingIcon = { XedIcon(action.icon, contentDescription = action.title) },
                                enabled = action.isEnabled(files),
                                onClick = {
                                    val ctx = MultiFileActionContext(files, root, fileTreeViewModel, context)
                                    action.action(ctx)
                                    showFileActionMenu = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun onCloseTab(mainViewModel: MainViewModel, tabState: Tab) {
    val tabIndex = mainViewModel.tabs.indexOf(tabState)
    if (tabIndex == -1) return

    if (tabState is EditorTab && tabState.editorState.isDirty) {
        dialog(
            title = strings.file_unsaved.getString(),
            msg = strings.ask_unsaved.getString(),
            onOk = { mainViewModel.tabManager.removeTab(tabIndex) },
            onCancel = {},
            okString = strings.discard,
        )
    } else {
        mainViewModel.tabManager.removeTab(tabIndex)
    }
}

private fun closeOthers(mainViewModel: MainViewModel, index: Int) {
    mainViewModel.tabManager.setCurrentTab(index)

    val unsavedOtherTabs =
        mainViewModel.tabs.filterIndexed { tabIndex, tab ->
            tabIndex != index && (tab as? EditorTab)?.editorState?.isDirty == true
        }
    if (unsavedOtherTabs.isNotEmpty()) {
        dialog(
            title = strings.files_unsaved.getString(),
            msg = strings.ask_multiple_unsaved.getString(),
            onOk = { mainViewModel.tabManager.removeOtherTabs() },
            onCancel = {},
            okString = strings.discard,
        )
    } else {
        mainViewModel.tabManager.removeOtherTabs()
    }
}

private fun closeAll(mainViewModel: MainViewModel) {
    val unsavedTabs =
        mainViewModel.tabs.filter { tab ->
            (tab as? EditorTab)?.editorState?.isDirty == true
        }
    if (unsavedTabs.isNotEmpty()) {
        dialog(
            title = strings.files_unsaved.getString(),
            msg = strings.ask_multiple_unsaved.getString(),
            onOk = { mainViewModel.tabManager.removeAllTabs() },
            onCancel = {},
            okString = strings.discard,
        )
    } else {
        mainViewModel.tabManager.removeAllTabs()
    }
}

@Composable
private fun EmptyEditorState(
    onNewFile: () -> Unit,
    onOpenFile: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenAi: () -> Unit,
    onSetupAi: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 24.dp)
                .fillMaxWidth(),
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = colorScheme.primaryContainer.copy(alpha = 0.45f),
                modifier = Modifier.size(68.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        painter = painterResource(drawables.edit_note),
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = colorScheme.primary,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Text(
                text = stringResource(strings.app_name),
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = colorScheme.onSurface,
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = stringResource(strings.app_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(22.dp))

            // Beginner AI Assistant Setup Banner
            if (Settings.ai_api_key.isBlank()) {
                Surface(
                    onClick = onSetupAi,
                    shape = RoundedCornerShape(16.dp),
                    color = colorScheme.primaryContainer.copy(alpha = 0.35f),
                    modifier = Modifier
                        .widthIn(max = 380.dp)
                        .fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = colorScheme.primary,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.Psychology,
                                    contentDescription = null,
                                    tint = colorScheme.onPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Setup AI Assistant",
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                color = colorScheme.onSurface
                            )
                            Text(
                                text = "Get free Gemini API & code with AI in 1 min",
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = colorScheme.primary,
                        ) {
                            Text(
                                text = "Setup",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
            }

            // 2x2 Action Grid
            Column(
                modifier = Modifier.widthIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    QuickActionCard(
                        icon = Icons.Outlined.Add,
                        title = stringResource(strings.new_file),
                        subtitle = "Create empty file",
                        onClick = onNewFile,
                        modifier = Modifier.weight(1f),
                    )
                    QuickActionCard(
                        icon = Icons.Outlined.FolderOpen,
                        title = stringResource(strings.open_file),
                        subtitle = "Browse workspace",
                        onClick = onOpenFile,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    QuickActionCard(
                        icon = Icons.Outlined.Psychology,
                        title = "AI Agent",
                        subtitle = "VibeCoding / CLI",
                        onClick = onOpenAi,
                        modifier = Modifier.weight(1f),
                    )
                    QuickActionCard(
                        icon = Icons.Outlined.Terminal,
                        title = "Terminal",
                        subtitle = "Linux shell",
                        onClick = onOpenTerminal,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickActionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    Card(
        modifier = modifier
            .height(82.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = colorScheme.surfaceContainerHigh,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = colorScheme.primaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.size(38.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
