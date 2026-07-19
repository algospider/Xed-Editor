@file:OptIn(ExperimentalUuidApi::class)
package com.rk.ai.agent

import com.rk.ai.models.ExecutionState
import com.rk.ai.models.UIMessage
import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi

/**
 * Sealed interface for all chunk types emitted by the generation pipeline.
 * Each chunk represents a distinct event during text generation.
 */
@Serializable
sealed interface GenerationChunk {
    data class Messages(
        val messages: List<UIMessage>,
    ) : GenerationChunk

    data class CompactionNeeded(
        val reason: String,
    ) : GenerationChunk

    data class ToolStateChanged(
        val toolCallId: String,
        val toolName: String,
        val executionState: ExecutionState,
    ) : GenerationChunk

    data class StepStarted(
        val stepIndex: Int,
    ) : GenerationChunk

    data class StepFinished(
        val stepIndex: Int,
        val cost: Float = 0f,
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        val reasoningTokens: Int = 0,
    ) : GenerationChunk

    data class GenerationError(
        val errorMessage: String,
        val errorType: String = "UnknownError",
    ) : GenerationChunk
}
