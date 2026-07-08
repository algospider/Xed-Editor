package com.rk.ai.nativeagent.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.rk.ai.agent.executor.AgentPhase

/** Single source of truth for phase labels and colors. */
object PhaseDisplay {
    fun label(phase: AgentPhase): String = when (phase) {
        AgentPhase.IDLE -> "Idle"
        AgentPhase.PLANNING -> "Planning"
        AgentPhase.ANALYZING -> "Analyzing"
        AgentPhase.INDEXING -> "Indexing"
        AgentPhase.EXPLORING -> "Exploring"
        AgentPhase.EXECUTING -> "Executing"
        AgentPhase.VERIFYING -> "Verifying"
        AgentPhase.COMPLETED -> "Completed"
        AgentPhase.FAILED -> "Failed"
    }

    fun color(phase: AgentPhase, colors: ColorScheme): Color = when (phase) {
        AgentPhase.IDLE -> colors.outlineVariant
        AgentPhase.PLANNING -> colors.tertiary
        AgentPhase.ANALYZING, AgentPhase.INDEXING -> colors.secondary
        AgentPhase.EXPLORING -> colors.tertiary.copy(alpha = 0.8f)
        AgentPhase.EXECUTING -> colors.primary
        AgentPhase.VERIFYING -> colors.error
        AgentPhase.COMPLETED -> colors.primary
        AgentPhase.FAILED -> colors.error
    }

    fun isActive(phase: AgentPhase): Boolean =
        phase !in listOf(AgentPhase.IDLE, AgentPhase.COMPLETED, AgentPhase.FAILED)
}
