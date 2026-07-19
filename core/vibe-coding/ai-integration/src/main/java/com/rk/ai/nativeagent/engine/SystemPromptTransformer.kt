@file:OptIn(ExperimentalUuidApi::class)
package com.rk.ai.nativeagent.engine

import com.rk.ai.agent.transformers.InputMessageTransformer
import com.rk.ai.agent.transformers.TransformerContext
import com.rk.ai.core.MessageRole
import com.rk.ai.models.UIMessage
import com.rk.ai.models.UIMessagePart
import kotlin.uuid.ExperimentalUuidApi

/**
 * Injects and manages system prompts for the generation pipeline.
 * Handles initial system prompt injection, workspace context, and plan context.
 */
class SystemPromptTransformer(
    private val systemPromptBuilder: SystemPromptBuilder,
) : InputMessageTransformer {

    @Volatile
    private var initialPromptInjected = false

    override suspend fun transform(
        ctx: TransformerContext,
        messages: List<UIMessage>,
    ): List<UIMessage> {
        if (messages.isEmpty()) return messages

        val result = mutableListOf<UIMessage>()

        // Inject system prompt once at the very beginning
        if (!initialPromptInjected) {
            val systemPrompt = systemPromptBuilder.buildInitialSystemPrompt(ctx.model)
            if (systemPrompt.isNotBlank()) {
                result.add(UIMessage.system(systemPrompt))
            }
            initialPromptInjected = true
        }

        // Strip stale system-context messages (workspace + plan) from history
        val cleaned = messages.filterNot { msg ->
            msg.role == MessageRole.SYSTEM &&
            msg.parts.any { part ->
                part is UIMessagePart.Text && (
                    part.text.contains("<workspace_context>") ||
                    part.text.contains("<active_plan>")
                )
            }
        }

        result.addAll(cleaned)

        // Append fresh workspace context
        val ctxBlock = systemPromptBuilder.buildWorkspaceContext()
        if (ctxBlock.isNotBlank()) {
            result.add(UIMessage.system(ctxBlock))
        }

        // Append fresh plan context (if a plan is active)
        val planCtx = systemPromptBuilder.buildPlanContext()
        if (planCtx.isNotBlank()) {
            result.add(UIMessage.system(planCtx))
        }

        return result
    }

    fun reset() {
        initialPromptInjected = false
        systemPromptBuilder.reset()
    }
}
