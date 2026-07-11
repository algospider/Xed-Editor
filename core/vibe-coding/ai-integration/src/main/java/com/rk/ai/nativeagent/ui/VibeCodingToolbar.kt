@file:OptIn(ExperimentalUuidApi::class)
package com.rk.ai.nativeagent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rk.ai.nativeagent.engine.VibeCodingEngine
import com.rk.ai.nativeagent.engine.VibeCodingState
import kotlin.uuid.ExperimentalUuidApi

private enum class OverflowAction {
    SKILLS, AGENTS, RULES, PLUGINS, PERMS, CLEAR, EXPORT
}

@Composable
internal fun VibeCodingToolbar(
    engine: VibeCodingEngine,
    state: VibeCodingState,
    showFiles: Boolean,
    showHistory: Boolean,
    onToggleFiles: () -> Unit,
    onToggleHistory: () -> Unit,
    onOpenPanel: (ToolPanel) -> Unit,
    onShowClearDialog: () -> Unit,
    onShowExportDialog: () -> Unit,
    onSettings: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    var showOverflow by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
        color = colorScheme.surfaceContainerLow,
        tonalElevation = 0.5.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Model badge
            val settings by engine.settingsStore.settingsFlow.collectAsState()
            val modelName = remember(settings.chatModelId, settings.providers) {
                val model = settings.providers.flatMap { it.models }
                    .firstOrNull { it.id == settings.chatModelId }
                if (model != null) {
                    model.displayName?.ifEmpty { model.modelId } ?: model.modelId
                } else {
                    "No model"
                }
            }
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = colorScheme.primaryContainer.copy(alpha = 0.4f),
            ) {
                Text(
                    text = modelName,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 100.dp).padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }

            Spacer(Modifier.width(6.dp))

            // Files toggle
            ToolbarIconButton(
                icon = Icons.Outlined.Folder,
                label = "Files",
                isActive = showFiles,
                onClick = onToggleFiles,
            )

            // Commands
            ToolbarIconButton(
                icon = Icons.Outlined.Terminal,
                label = "Commands",
                onClick = { onOpenPanel(ToolPanel.COMMANDS) },
            )

            // History
            ToolbarIconButton(
                icon = Icons.Outlined.History,
                label = "History",
                isActive = showHistory,
                onClick = onToggleHistory,
            )

            Spacer(Modifier.weight(1f))

            // Settings
            ToolbarIconButton(
                icon = Icons.Outlined.Settings,
                label = "Settings",
                onClick = onSettings,
            )

            // Overflow menu
            Box {
                ToolbarIconButton(
                    icon = Icons.Outlined.MoreVert,
                    label = "More",
                    onClick = { showOverflow = true },
                )
                DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                    DropdownMenuItem(
                        text = { Text("Skills", style = MaterialTheme.typography.bodySmall) },
                        onClick = { showOverflow = false; onOpenPanel(ToolPanel.SKILLS) },
                        leadingIcon = { Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(16.dp)) },
                    )
                    DropdownMenuItem(
                        text = { Text("Agents", style = MaterialTheme.typography.bodySmall) },
                        onClick = { showOverflow = false; onOpenPanel(ToolPanel.AGENTS) },
                        leadingIcon = { Icon(Icons.Outlined.Psychology, null, Modifier.size(16.dp)) },
                    )
                    DropdownMenuItem(
                        text = { Text("Project Rules", style = MaterialTheme.typography.bodySmall) },
                        onClick = { showOverflow = false; onOpenPanel(ToolPanel.INSTRUCTIONS) },
                        leadingIcon = { Icon(Icons.Outlined.Description, null, Modifier.size(16.dp)) },
                    )
                    DropdownMenuItem(
                        text = { Text("Plugins", style = MaterialTheme.typography.bodySmall) },
                        onClick = { showOverflow = false; onOpenPanel(ToolPanel.PLUGINS) },
                        leadingIcon = { Icon(Icons.Outlined.Extension, null, Modifier.size(16.dp)) },
                    )
                    DropdownMenuItem(
                        text = { Text("Permissions", style = MaterialTheme.typography.bodySmall) },
                        onClick = { showOverflow = false; onOpenPanel(ToolPanel.PERMISSIONS) },
                        leadingIcon = { Icon(Icons.Outlined.Security, null, Modifier.size(16.dp)) },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Export conversation", style = MaterialTheme.typography.bodySmall) },
                        onClick = { showOverflow = false; onShowExportDialog() },
                        leadingIcon = { Icon(Icons.Outlined.FileDownload, null, Modifier.size(16.dp)) },
                    )
                    DropdownMenuItem(
                        text = { Text("Clear chat", color = colorScheme.error, style = MaterialTheme.typography.bodySmall) },
                        onClick = { showOverflow = false; onShowClearDialog() },
                        leadingIcon = { Icon(Icons.Outlined.Delete, null, Modifier.size(16.dp), tint = colorScheme.error) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolbarIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isActive: Boolean = false,
    onClick: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val bg = if (isActive) colorScheme.primaryContainer else colorScheme.surfaceContainerHigh

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = bg,
        modifier = Modifier.padding(2.dp),
    ) {
        Icon(
            icon,
            contentDescription = label,
            modifier = Modifier.size(20.dp).padding(2.dp),
            tint = if (isActive) colorScheme.onPrimaryContainer else colorScheme.onSurfaceVariant,
        )
    }
}
