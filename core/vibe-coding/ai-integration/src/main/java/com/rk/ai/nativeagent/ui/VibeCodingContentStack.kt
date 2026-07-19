@file:OptIn(ExperimentalUuidApi::class)
package com.rk.ai.nativeagent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rk.ai.nativeagent.engine.VibeCodingEngine
import com.rk.ai.nativeagent.engine.VibeCodingState
import com.rk.ai.nativeagent.ui.components.SecurityAlertBanner
import kotlin.uuid.ExperimentalUuidApi

@Composable
internal fun VibeCodingContentStack(
    state: VibeCodingState,
    engine: VibeCodingEngine,
    context: android.content.Context,
    hasTodos: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // Security alerts
        if (state.hasSecurityAlerts) {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 100.dp)) {
                items(state.securityAlerts.takeLast(3), key = { it.id ?: it.message.take(50) }) { alert ->
                    SecurityAlertBanner(alert = alert, onDismiss = { engine.dismissSecurityAlert(alert.id) })
                }
            }
        }

        // Todo panel
        if (hasTodos) {
            VibeCodingTodoPanel(
                visible = true,
                todos = state.todos,
                completedCount = state.completedTodos,
                onClear = { state.activeSessionId?.let { engine.setSessionTodos(it, emptyList()) } },
                colorScheme = MaterialTheme.colorScheme,
            )
        }

        // Main message list
        Box(modifier = Modifier.weight(1f)) {
            if (state.messages.isEmpty() && !state.isProcessing) {
                VibeCodingEmptyState(
                    colorScheme = MaterialTheme.colorScheme,
                    workspacePath = state.workspacePath,
                    onQuickAction = { prompt -> engine.sendMessage(prompt) },
                )
            } else {
                VibeCodingMessageList(
                    messages = state.messages,
                    isProcessing = state.isProcessing,
                    currentPhase = state.currentPhase,
                    onApproveTool = { engine.approveTool(it) },
                    onDenyTool = { id, reason -> engine.denyTool(id, reason) },
                    onAnswerTool = { id, answer -> engine.answerTool(id, answer) },
                    onCopyMessage = { text ->
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("VibeCoding", text))
                    },
                    onDeleteMessage = { index -> engine.deleteMessage(index) },
                    onApplyCode = { code, _ ->
                        engine.ideService.insertAtCursor(code)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
