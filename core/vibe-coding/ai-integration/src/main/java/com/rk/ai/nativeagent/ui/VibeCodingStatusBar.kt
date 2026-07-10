package com.rk.ai.nativeagent.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rk.ai.agent.executor.AgentPhase
import com.rk.ai.nativeagent.engine.VibeCodingState

@Composable
fun VibeCodingStatusBar(
    state: VibeCodingState,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val phaseColor = PhaseDisplay.color(state.currentPhase, colorScheme)
    val isActive = PhaseDisplay.isActive(state.currentPhase)

    val infiniteTransition = rememberInfiniteTransition(label = "statusPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f, targetValue = 1.0f,
        animationSpec = infiniteRepeatable(animation = tween(800, easing = EaseInOutCubic), repeatMode = RepeatMode.Reverse),
        label = "statusPulse",
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = colorScheme.surfaceContainerHigh,
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // Phase
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(
                    modifier = Modifier.size(6.dp).clip(CircleShape)
                        .background(if (isActive) phaseColor.copy(alpha = pulseAlpha) else phaseColor.copy(alpha = 0.6f)),
                )
                Text(
                    text = state.phaseLabel,
                    style = MaterialTheme.typography.labelSmall, fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isActive) phaseColor else phaseColor.copy(alpha = 0.6f),
                )
            }

            // Right: minimal stats
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Context tokens
                if (state.contextTokens != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Memory, null, modifier = Modifier.size(8.dp), tint = colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                        Spacer(Modifier.width(2.dp))
                        Text(
                            text = formatTokenCount(state.contextTokens),
                            style = MaterialTheme.typography.labelSmall, fontSize = 9.sp,
                            color = colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }

                // Tool executions
                if (state.toolExecutions.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Build, null, modifier = Modifier.size(8.dp), tint = colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                        Spacer(Modifier.width(2.dp))
                        Text("${state.toolExecutions.size}", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = colorScheme.onSurfaceVariant)
                    }
                }

                // Todos
                if (state.todos.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.TaskAlt, null, modifier = Modifier.size(8.dp),
                            tint = if (state.completedTodos == state.todos.size) colorScheme.primary else colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                        Spacer(Modifier.width(2.dp))
                        Text("${state.completedTodos}/${state.todos.size}", style = MaterialTheme.typography.labelSmall, fontSize = 9.sp, color = colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun formatTokenCount(tokens: Int): String = when {
    tokens >= 1_000_000 -> "${(tokens / 100_000f).toInt() / 10f}M"
    tokens >= 1_000 -> "${(tokens / 100f).toInt() / 10f}k"
    else -> tokens.toString()
}
