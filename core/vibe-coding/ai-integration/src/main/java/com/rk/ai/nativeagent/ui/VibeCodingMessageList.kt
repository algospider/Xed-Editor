@file:OptIn(ExperimentalUuidApi::class)
package com.rk.ai.nativeagent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rk.ai.agent.executor.AgentPhase
import com.rk.ai.models.UIMessage
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi

@Composable
fun VibeCodingMessageList(
    messages: List<UIMessage>,
    isProcessing: Boolean,
    currentPhase: AgentPhase = AgentPhase.IDLE,
    onApproveTool: ((String) -> Unit)? = null,
    onDenyTool: ((String, String) -> Unit)? = null,
    onAnswerTool: ((String, String) -> Unit)? = null,
    onCopyMessage: ((String) -> Unit)? = null,
    onDeleteMessage: ((Int) -> Unit)? = null,
    onApplyCode: ((String, String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // ── Smart auto-scroll ──
    val isNearBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = layoutInfo.totalItemsCount
            totalItems == 0 || lastVisible >= totalItems - 3
        }
    }

    // Scroll to bottom when new messages arrive (only if already near bottom).
    // Also scrolls on first composition after layout has settled.
    val initialScrollDone = remember { mutableStateOf(false) }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            if (isNearBottom) {
                listState.animateScrollToItem(messages.size - 1)
                initialScrollDone.value = true
            } else if (!initialScrollDone.value) {
                // First composition — scroll to bottom after layout settles
                kotlinx.coroutines.delay(50)
                listState.scrollToItem(messages.size - 1)
                initialScrollDone.value = true
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(messages, key = { it.id }) { message ->
                val msgIndex = messages.indexOf(message)
                VibeCodingMessageBubble(
                    message = message,
                    onApproveTool = onApproveTool,
                    onDenyTool = onDenyTool,
                    onAnswerTool = onAnswerTool,
                    onCopy = onCopyMessage,
                    onDelete = if (msgIndex >= 0 && onDeleteMessage != null) {
                        { onDeleteMessage(msgIndex) }
                    } else null,
                    onApplyCode = onApplyCode,
                )
            }

            if (isProcessing) {
                item { ThinkingIndicator(phase = currentPhase) }
            }

            // Bottom spacer so last message isn't hidden behind FAB
            item { Spacer(Modifier.height(8.dp)) }
        }

        // ── Scroll-to-bottom FAB (appears when scrolled up) ──
        val scope = rememberCoroutineScope()
        AnimatedVisibility(
            visible = !isNearBottom && messages.isNotEmpty(),
            enter = fadeIn() + slideInVertically { it / 4 },
            exit = fadeOut() + slideOutVertically { it / 4 },
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 4.dp),
        ) {
            Surface(
                onClick = { scope.launch { listState.animateScrollToItem(messages.size - 1) } },
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 3.dp,
                shadowElevation = 4.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Jump to latest",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 11.sp,
                    )
                    Icon(
                        Icons.Outlined.KeyboardArrowDown,
                        contentDescription = "Scroll to bottom",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun ThinkingIndicator(phase: AgentPhase) {
    val colorScheme = MaterialTheme.colorScheme
    val phaseLabel = PhaseDisplay.label(phase)
    val phaseColor = PhaseDisplay.color(phase, colorScheme)

    val infiniteTransition = rememberInfiniteTransition(label = "thinking")
    val dotsAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(animation = tween(800, easing = EaseInOutCubic), repeatMode = RepeatMode.Reverse),
        label = "dotsAlpha",
    )

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(shape = RoundedCornerShape(4.dp), color = phaseColor.copy(alpha = 0.15f)) {
                Text(
                    text = phaseLabel,
                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                    color = phaseColor,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    fontSize = 10.sp,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(3) { index ->
                    Box(
                        modifier = Modifier.size(4.dp)
                            .alpha(when (index) {
                                0 -> dotsAlpha
                                1 -> (dotsAlpha + 0.3f).coerceAtMost(1f)
                                else -> (dotsAlpha + 0.6f).coerceAtMost(1f)
                            })
                            .background(color = phaseColor, shape = RoundedCornerShape(2.dp)),
                    )
                }
            }
        }
    }
}
