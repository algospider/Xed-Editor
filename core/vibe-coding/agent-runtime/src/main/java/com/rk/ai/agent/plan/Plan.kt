package com.rk.ai.agent.plan

/** Overall lifecycle of a plan. */
enum class PlanStatus {
    /** Being drafted — not yet presented for approval. */
    DRAFT,
    /** Presented to the user; waiting for yes/no. */
    AWAITING_APPROVAL,
    /** Approved and being executed step by step. */
    ACTIVE,
    /** All steps finished (completed or skipped). */
    COMPLETED,
    /** Cancelled before completion. */
    CANCELLED,
}

/** Status of an individual step. */
enum class StepStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    SKIPPED,
}

data class PlanStep(
    val id: String,
    val description: String,
    val details: String = "",
    val status: StepStatus = StepStatus.PENDING,
    val result: String? = null,
)

data class Plan(
    val id: String,
    val title: String,
    val description: String = "",
    val steps: List<PlanStep>,
    val status: PlanStatus = PlanStatus.DRAFT,
    val createdAt: Long = System.currentTimeMillis(),
    val approvedAt: Long? = null,
    val completedAt: Long? = null,
) {
    val completedSteps: Int get() = steps.count { it.status == StepStatus.COMPLETED }
    val totalSteps: Int get() = steps.size
    val progressSummary: String get() = "$completedSteps/$totalSteps steps completed"
    val isBlocked: Boolean get() = steps.any { it.status == StepStatus.FAILED }
}
