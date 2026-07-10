@file:OptIn(ExperimentalUuidApi::class)
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rk.ai.nativeagent.engine.SessionNode
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Composable
internal fun VibeCodingSessionTabs(
    sessionTree: List<SessionNode>,
    activeSessionId: Uuid?,
    isProcessing: Boolean,
    onSwitchSession: (Uuid) -> Unit,
    onNewBranch: () -> Unit,
    onRenameSession: ((Uuid, String) -> Unit)? = null,
    onCloseSession: ((Uuid) -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(sessionTree) { node ->
                val isActive = node.id == activeSessionId
                Surface(
                    onClick = { onSwitchSession(node.id) },
                    shape = RoundedCornerShape(6.dp),
                    color = if (isActive) MaterialTheme.colorScheme.surfaceContainerHigh
                    else MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.widthIn(max = 140.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (node.parentId != null) {
                            Icon(Icons.Outlined.SubdirectoryArrowRight, null, modifier = Modifier.size(10.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(2.dp))
                        }
                        Text(
                            text = node.title,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (onCloseSession != null) {
                            IconButton(onClick = { onCloseSession(node.id) }, modifier = Modifier.size(16.dp)) {
                                Icon(Icons.Outlined.Close, "Close", modifier = Modifier.size(10.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                            }
                        }
                    }
                }
            }
            item {
                FilledTonalIconButton(
                    onClick = onNewBranch,
                    modifier = Modifier.size(24.dp),
                    enabled = !isProcessing,
                ) {
                    Icon(Icons.Outlined.Add, "New Session", modifier = Modifier.size(12.dp))
                }
            }
        }
    }
}
