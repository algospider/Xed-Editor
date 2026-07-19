package com.rk.ai.nativeagent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class QuickAction(
    val icon: ImageVector, val title: String, val prompt: String,
)

private val quickActions = listOf(
    QuickAction(Icons.Outlined.BugReport, "Fix Bugs", "Find and fix issues in the current code"),
    QuickAction(Icons.Outlined.Science, "Add Tests", "Write tests for the codebase"),
    QuickAction(Icons.Outlined.Refresh, "Refactor", "Refactor the codebase for better quality"),
    QuickAction(Icons.Outlined.RateReview, "Review", "Review recent changes for issues"),
    QuickAction(Icons.Outlined.AccountTree, "Plan", "Create a step-by-step plan for a task"),
)

@Composable
internal fun VibeCodingEmptyState(
    colorScheme: ColorScheme,
    workspacePath: String = "",
    onQuickAction: ((String) -> Unit)? = null,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            // ── Hero icon ──
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = colorScheme.primaryContainer.copy(alpha = 0.3f),
                modifier = Modifier.size(64.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        Icons.Outlined.AutoFixHigh,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = colorScheme.primary,
                    )
                }
            }

            // ── Title ──
            Text(
                text = "VibeCoding",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = colorScheme.onSurface,
            )

            // ── Subtitle ──
            Text(
                text = "AI-powered coding assistant for your project",
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )

            // ── Workspace info ──
            if (workspacePath.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(Icons.Outlined.Folder, null, modifier = Modifier.size(13.dp), tint = colorScheme.primary.copy(alpha = 0.6f))
                        Text(
                            workspacePath.split("/").takeLast(2).joinToString("/"),
                            style = MaterialTheme.typography.labelSmall, fontSize = 10.sp,
                            color = colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // ── Quick action cards ──
            if (onQuickAction != null) {
                Spacer(Modifier.height(2.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(quickActions) { action ->
                        Surface(
                            onClick = { onQuickAction(action.prompt) },
                            shape = RoundedCornerShape(12.dp),
                            color = colorScheme.surfaceContainerHigh,
                            tonalElevation = 1.dp,
                            modifier = Modifier.width(108.dp),
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = colorScheme.primaryContainer.copy(alpha = 0.5f),
                                    modifier = Modifier.size(30.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                        Icon(action.icon, null, modifier = Modifier.size(15.dp), tint = colorScheme.primary)
                                    }
                                }
                                Text(
                                    action.title,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colorScheme.onSurface,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }

            // ── Tip ──
            Spacer(Modifier.height(4.dp))
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = colorScheme.surfaceVariant.copy(alpha = 0.4f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Outlined.Lightbulb,
                        null,
                        modifier = Modifier.size(14.dp),
                        tint = colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                    Text(
                        text = "Type / for commands, or ask me anything about your codebase",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
