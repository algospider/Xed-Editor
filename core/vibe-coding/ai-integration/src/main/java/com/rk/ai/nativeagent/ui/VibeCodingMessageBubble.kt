package com.rk.ai.nativeagent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rk.ai.core.MessageRole
import com.rk.ai.models.UIMessage
import com.rk.ai.models.UIMessagePart
import com.rk.ai.nativeagent.ui.markdown.MarkdownContent

@Composable
fun VibeCodingMessageBubble(
    message: UIMessage,
    onApproveTool: ((String) -> Unit)? = null,
    onDenyTool: ((String, String) -> Unit)? = null,
    onAnswerTool: ((String, String) -> Unit)? = null,
    onCopy: ((String) -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val isUser = message.role == MessageRole.USER
    val isSystem = message.role == MessageRole.SYSTEM
    val colorScheme = MaterialTheme.colorScheme

    if (isSystem) {
        SystemMessage(message = message)
        return
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 14.dp, topEnd = 14.dp,
                bottomStart = if (isUser) 14.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 14.dp,
            ),
            color = if (isUser) colorScheme.primaryContainer else colorScheme.surfaceContainerHigh,
            tonalElevation = if (isUser) 0.dp else 1.dp,
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // Header: role label
                Text(
                    text = if (isUser) "You" else "Assistant",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    fontWeight = FontWeight.SemiBold,
                    color = if (isUser) colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                    else colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(bottom = 4.dp),
                )

                // Parts
                message.parts.forEachIndexed { i, part ->
                    MessagePartContent(part, isUser, onApproveTool, onDenyTool, onAnswerTool)
                    if (i < message.parts.lastIndex) Spacer(Modifier.height(4.dp))
                }
            }
        }

        // Copy button (assistant only)
        if (onCopy != null && !isUser && !isSystem) {
            IconButton(
                onClick = { onCopy(message.toText()) },
                modifier = Modifier.size(20.dp).align(Alignment.BottomEnd).offset(x = (-4).dp, y = 0.dp),
            ) {
                Icon(
                    Icons.Outlined.ContentCopy,
                    contentDescription = "Copy",
                    modifier = Modifier.size(12.dp),
                    tint = colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                )
            }
        }

        // Delete (user messages only)
        if (onDelete != null && isUser) {
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(20.dp).align(Alignment.TopEnd).offset(x = 0.dp, y = 0.dp),
            ) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "Delete",
                    modifier = Modifier.size(12.dp),
                    tint = colorScheme.error.copy(alpha = 0.4f),
                )
            }
        }
    }
}

@Composable
private fun SystemMessage(message: UIMessage) {
    val colorScheme = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(6.dp)).clickable { expanded = !expanded },
        color = colorScheme.surfaceContainerLowest.copy(alpha = 0.5f),
        shape = RoundedCornerShape(6.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Info, null, modifier = Modifier.size(12.dp), tint = colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (expanded) "System ▼" else "System ▶",
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
            }
            AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
                Text(
                    text = message.toText().take(500),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, fontFamily = FontFamily.Monospace),
                    color = colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun MessagePartContent(
    part: UIMessagePart,
    isUser: Boolean,
    onApproveTool: ((String) -> Unit)?,
    onDenyTool: ((String, String) -> Unit)?,
    onAnswerTool: ((String, String) -> Unit)?,
) {
    when (part) {
        is UIMessagePart.Text -> {
            if (isUser) {
                Text(
                    text = part.text,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                )
            } else {
                MarkdownContent(text = part.text, modifier = Modifier.fillMaxWidth())
            }
        }
        is UIMessagePart.Reasoning -> MessageReasoningBlock(part)
        is UIMessagePart.Tool -> VibeCodingToolCard(
            part = part,
            onApprove = { onApproveTool?.invoke(part.toolCallId) },
            onDeny = { reason -> onDenyTool?.invoke(part.toolCallId, reason) },
            onAnswer = { answer -> onAnswerTool?.invoke(part.toolCallId, answer) },
        )
        is UIMessagePart.StepStart -> StepIndicator(stepIndex = part.stepIndex, totalSteps = 0)
        is UIMessagePart.StepFinish -> StepFinishIndicator(part.stepIndex, part.inputTokens, part.outputTokens, part.reasoningTokens, part.cost)
        is UIMessagePart.Image -> AttachmentLabel("🖼 Image: ${part.url.take(40)}...")
        is UIMessagePart.Document -> AttachmentLabel("📎 ${part.fileName}")
        else -> AttachmentLabel("[${part::class.simpleName}]", muted = true)
    }
}

@Composable
private fun StepIndicator(stepIndex: Int, totalSteps: Int) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(Icons.Outlined.PlayArrow, null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Text("Step ${stepIndex + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun StepFinishIndicator(stepIndex: Int, inputTokens: Int, outputTokens: Int, reasoningTokens: Int, cost: Float) {
    val tokenInfo = buildString {
        if (inputTokens > 0) append("Δ $inputTokens ")
        if (outputTokens > 0) append("◻ $outputTokens ")
        if (reasoningTokens > 0) append("~ $reasoningTokens ")
        if (cost > 0f) append("$${String.format("%.4f", cost)}")
    }
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.2f),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(Icons.Outlined.CheckCircleOutline, null, modifier = Modifier.size(10.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
            Text("Step ${stepIndex + 1} done | ${tokenInfo.ifEmpty { "complete" }}", style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun AttachmentLabel(text: String, muted: Boolean = false) {
    Text(text, style = MaterialTheme.typography.bodySmall,
        color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
        fontStyle = if (muted) FontStyle.Normal else FontStyle.Italic)
}

@Composable
private fun MessageReasoningBlock(part: UIMessagePart.Reasoning) {
    val colorScheme = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(colorScheme.tertiaryContainer.copy(alpha = 0.3f))
            .clickable { expanded = !expanded }
            .padding(8.dp),
    ) {
        Text(
            text = if (expanded) "▼ Thinking" else "▶ Thinking",
            style = MaterialTheme.typography.labelSmall,
            color = colorScheme.onTertiaryContainer, fontWeight = FontWeight.SemiBold,
        )
        AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Text(part.reasoning, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                color = colorScheme.onTertiaryContainer.copy(alpha = 0.8f), modifier = Modifier.padding(top = 4.dp))
        }
    }
}
