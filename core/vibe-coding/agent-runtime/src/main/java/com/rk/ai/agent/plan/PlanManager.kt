package com.rk.ai.agent.plan

/**
 * Singleton plan manager that tracks the active plan across generation turns.
 *
 * The AI calls [PlanModeTool] actions; [PlanManager] holds state and provides
 * a context block injected into every system prompt while a plan is active.
 */
object PlanManager {

    @Volatile
    private var active: Plan? = null

    // ── queries ──────────────────────────────────────────────────────────────

    fun getActive(): Plan? = active

    fun isPlanActive(): Boolean = active != null && active!!.status == PlanStatus.ACTIVE

    fun isAwaitingApproval(): Boolean = active != null && active!!.status == PlanStatus.AWAITING_APPROVAL

    fun isInPlanMode(): Boolean = active != null && active!!.status in setOf(
        PlanStatus.ACTIVE, PlanStatus.AWAITING_APPROVAL,
    )

    // ── mutations ────────────────────────────────────────────────────────────

    fun createPlan(
        title: String,
        description: String = "",
        stepDescriptions: List<Pair<String, String>>,
    ): Plan {
        val steps = stepDescriptions.mapIndexed { i, (desc, details) ->
            PlanStep(id = "step-${i + 1}", description = desc, details = details)
        }
        val plan = Plan(
            id = "plan-${System.currentTimeMillis()}",
            title = title,
            description = description,
            steps = steps,
            status = PlanStatus.AWAITING_APPROVAL,
        ).also { active = it }
        return plan
    }

    fun approvePlan(): Plan? {
        val p = active ?: return null
        val approved = p.copy(
            status = PlanStatus.ACTIVE,
            approvedAt = System.currentTimeMillis(),
        ).also { active = it }
        return approved
    }

    fun rejectPlan(reason: String = ""): Plan {
        val p = active?.copy(status = PlanStatus.DRAFT) ?: Plan(
            id = "empty", title = "", steps = emptyList(), status = PlanStatus.DRAFT,
        )
        active = p
        return p
    }

    fun cancelPlan(): Plan? {
        val p = active?.copy(status = PlanStatus.CANCELLED)
        active = null
        return p
    }

    fun updateStep(stepId: String, status: StepStatus, result: String? = null): Plan? {
        val p = active ?: return null
        val idx = p.steps.indexOfFirst { it.id == stepId }
        if (idx < 0) return null

        val newSteps = p.steps.toMutableList().apply {
            set(idx, get(idx).copy(status = status, result = result))
        }
        val allDone = newSteps.all { it.status in setOf(StepStatus.COMPLETED, StepStatus.SKIPPED) }
        active = p.copy(
            steps = newSteps,
            status = if (allDone) PlanStatus.COMPLETED else PlanStatus.ACTIVE,
            completedAt = if (allDone) System.currentTimeMillis() else null,
        )
        return active
    }

    fun updateStepByDescription(desc: String, status: StepStatus, result: String? = null): Plan? {
        val p = active ?: return null
        val idx = p.steps.indexOfFirst { it.description == desc || it.id == desc }
        if (idx < 0) return null
        return updateStep(p.steps[idx].id, status, result)
    }

    // ── plan context (injected into system prompt) ───────────────────────────

    /**
     * Returns an XML block describing the active plan. Injected into the system
     * prompt each turn so the AI always knows which step it's on.
     *
     * Returns empty string when no plan is active.
     */
    fun buildContext(): String {
        val p = active ?: return ""
        return buildString {
            appendLine("<active_plan>")
            appendLine("  title: ${p.title}")
            appendLine("  status: ${p.status.name}")
            if (p.description.isNotBlank()) appendLine("  description: ${p.description}")
            appendLine("  steps:")
            for (step in p.steps) {
                val icon = when (step.status) {
                    StepStatus.COMPLETED -> "[✓]"
                    StepStatus.IN_PROGRESS -> "[→]"
                    StepStatus.FAILED -> "[✗]"
                    StepStatus.SKIPPED -> "[-]"
                    StepStatus.PENDING -> "[ ]"
                }
                appendLine("    $icon ${step.id}: ${step.description}")
                if (step.details.isNotBlank()) appendLine("      details: ${step.details}")
                if (step.result != null) appendLine("      result: ${step.result.take(200)}")
            }
            appendLine("  progress: ${p.progressSummary}")
            when (p.status) {
                PlanStatus.AWAITING_APPROVAL -> {
                    appendLine("  ⚠ PLAN IS NOT APPROVED YET.")
                    appendLine("  Do NOT execute any file modifications, writes, or destructive operations.")
                    appendLine("  Present the plan to the user and wait for them to type 'approve' or call planMode with action=approve.")
                }
                PlanStatus.ACTIVE -> {
                    val current = p.steps.firstOrNull { it.status == StepStatus.PENDING }
                    if (current != null) {
                        appendLine("  current_step: ${current.id} — ${current.description}")
                    }
                }
                else -> {}
            }
            appendLine("</active_plan>")
        }
    }

    fun clear() { active = null }
}
