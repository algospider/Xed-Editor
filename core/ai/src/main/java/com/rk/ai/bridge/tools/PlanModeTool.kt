package com.rk.ai.bridge.tools

import com.google.gson.JsonObject
import com.rk.ai.agent.plan.PlanManager
import com.rk.ai.agent.plan.PlanStatus
import com.rk.ai.agent.plan.StepStatus
import com.rk.ai.bridge.McpToolContext
import com.rk.ai.bridge.McpToolResult

/**
 * Structured plan-then-execute tool.
 *
 * The AI calls this to create, present, and track work plans.
 * When a plan is awaiting approval, [PlanManager.buildContext] injects a
 * directive into the system prompt saying "do NOT write code until approved".
 *
 * Actions:
 *   create   — create plan with title + step descriptions → AWAITING_APPROVAL
 *   approve  — user approved → ACTIVE
 *   reject   — user rejected → back to DRAFT (AI refines)
 *   status   — print current plan + progress
 *   update   — mark a step in_progress / completed / failed / skipped
 *   cancel   — abandon the plan
 */
class PlanModeTool : BaseMcpTool() {
    override fun getCategory(): String = "AI Planning"
    override fun getName(): String = "planMode"
    override fun getDescription(): String = buildString {
        append("Create, approve, or track structured execution plans. ")
        append("Actions: create (new plan with steps), approve (user accepts), ")
        append("reject (user rejects — refine plan), status (show progress), ")
        append("update (mark step in_progress/completed/failed/skipped), cancel.")
    }

    override fun getRequiredParams(): Map<String, String> = mapOf("action" to "string")
    override fun getRequiredParamDescriptions(): Map<String, String> = mapOf(
        "action" to "create | approve | reject | status | update | cancel"
    )

    override fun getOptionalParams(): Map<String, String> = mapOf(
        "title" to "string",
        "description" to "string",
        "steps" to "string",
        "stepId" to "string",
        "stepStatus" to "string",
        "result" to "string",
        "reason" to "string",
    )

    override fun getOptionalParamDescriptions(): Map<String, String> = mapOf(
        "title" to "Plan title (required for create)",
        "description" to "Optional plan overview",
        "steps" to "JSON array of strings: each step's description, or array of {description, details} objects (required for create)",
        "stepId" to "Step identifier (required for update). Can be 'step-N' or step description text.",
        "stepStatus" to "New status for step: in_progress, completed, failed, skipped (required for update)",
        "result" to "Optional result/summary text for the step (update)",
        "reason" to "Rejection reason (reject action)",
    )

    override suspend fun executeValidated(args: JsonObject, context: McpToolContext): McpToolResult {
        return when (val action = requireString(args, "action").lowercase()) {
            "create" -> actCreate(args)
            "approve" -> actApprove()
            "reject" -> actReject(args)
            "status" -> actStatus()
            "update" -> actUpdate(args)
            "cancel" -> actCancel()
            else -> McpToolResult.error("Unknown action: $action. Use: create, approve, reject, status, update, cancel")
        }
    }

    private fun actCreate(args: JsonObject): McpToolResult {
        val title = optionalString(args, "title").ifBlank { return McpToolResult.error("title is required for create") }
        val description = optionalString(args, "description")
        val stepsRaw = optionalString(args, "steps").ifBlank { return McpToolResult.error("steps (JSON array) is required for create") }

        val stepDescriptions = parseSteps(stepsRaw)
        if (stepDescriptions.isEmpty()) return McpToolResult.error("steps must be a non-empty JSON array")

        val plan = PlanManager.createPlan(title, description, stepDescriptions)
        return McpToolResult.success(
            buildString {
                appendLine("## Plan Created: ${plan.title}")
                if (plan.description.isNotBlank()) appendLine("> ${plan.description}")
                appendLine()
                appendLine("| # | Step | Status |")
                appendLine("|---|------|--------|")
                plan.steps.forEachIndexed { i, step ->
                    appendLine("| ${i + 1} | ${step.description} | ⏳ ${step.status.name} |")
                    if (step.details.isNotBlank()) appendLine("  _${step.details}_")
                }
                appendLine()
                appendLine("**Status:** Awaiting your approval.")
                appendLine("Type **'approve'** to start executing, or **'reject'** with feedback to refine.")
            },
            mapOf("planId" to plan.id, "status" to plan.status.name, "steps" to plan.steps.size.toString()),
        )
    }

    private fun actApprove(): McpToolResult {
        if (!PlanManager.isAwaitingApproval()) {
            val current = PlanManager.getActive()
            return McpToolResult.error(
                if (current == null) "No plan to approve. Create one first with planMode create."
                else "Plan is already ${current.status.name}. Nothing to approve."
            )
        }
        val plan = PlanManager.approvePlan()
        return McpToolResult.success(
            buildString {
                appendLine("## Plan Approved ✅")
                appendLine()
                appendLine("Starting execution: **${plan?.title ?: "Untitled"}**")
                appendLine()
                plan?.steps?.forEachIndexed { i, step ->
                    val icon = if (i == 0) "[→]" else "[ ]"
                    appendLine("$icon ${step.description}")
                }
                appendLine()
                appendLine("Begin with step 1. Call planMode update after each step.")
            },
            mapOf("planId" to plan?.id.orEmpty(), "status" to PlanStatus.ACTIVE.name),
        )
    }

    private fun actReject(args: JsonObject): McpToolResult {
        val reason = optionalString(args, "reason", "No reason provided")
        val plan = PlanManager.rejectPlan(reason)
        return McpToolResult.success(
            buildString {
                appendLine("## Plan Rejected")
                appendLine()
                appendLine("Reason: $reason")
                appendLine()
                appendLine("Please refine the plan based on feedback and present it again.")
                if (plan.steps.isNotEmpty()) {
                    appendLine()
                    appendLine("Current draft still has ${plan.steps.size} steps:")
                    plan.steps.forEach { appendLine("- ${it.description}") }
                }
            },
            mapOf("status" to PlanStatus.DRAFT.name),
        )
    }

    private fun actStatus(): McpToolResult {
        val plan = PlanManager.getActive()
        if (plan == null) {
            return McpToolResult.success(
                "No active plan. Create one with planMode action=create title=... steps=[...]"
            )
        }
        val context = PlanManager.buildContext()
        val progress = if (plan.totalSteps > 0) {
            "${(plan.completedSteps.toFloat() / plan.totalSteps * 100).toInt()}%"
        } else "—"
        return McpToolResult.success(
            buildString {
                appendLine("## Plan Status: ${plan.title}")
                appendLine("**Status:** ${plan.status.name} · **Progress:** $progress")
                appendLine()
                appendLine(context)
            },
            mapOf(
                "planId" to plan.id, "status" to plan.status.name,
                "completed" to plan.completedSteps.toString(),
                "total" to plan.totalSteps.toString(),
            ),
        )
    }

    private fun actUpdate(args: JsonObject): McpToolResult {
        val stepId = optionalString(args, "stepId").ifBlank { return McpToolResult.error("stepId is required") }
        val rawStatus = optionalString(args, "stepStatus").ifBlank { return McpToolResult.error("stepStatus is required") }
        val result = optionalString(args, "result").ifBlank { null }

        val status = parseStepStatus(rawStatus)
            ?: return McpToolResult.error("Invalid stepStatus: $rawStatus. Use: in_progress, completed, failed, skipped")

        val plan = PlanManager.updateStep(stepId, status, result)
            ?: PlanManager.updateStepByDescription(stepId, status, result)

        if (plan == null) {
            val active = PlanManager.getActive()
            val validIds = active?.steps?.joinToString(", ") { "'${it.id}' (${it.description})" } ?: "—"
            return McpToolResult.error("Step '$stepId' not found. Valid step IDs: $validIds")
        }

        val updatedStep = plan.steps.firstOrNull { it.id == stepId || it.description == stepId }

        return McpToolResult.success(
            buildString {
                appendLine("## Plan Updated")
                appendLine()
                appendLine("**Step:** ${updatedStep?.description ?: stepId}")
                appendLine("**Status:** $rawStatus")
                if (result != null) appendLine("**Result:** $result")
                appendLine()
                appendLine(PlanManager.buildContext().take(2000))
                if (plan.status == PlanStatus.COMPLETED) {
                    appendLine()
                    appendLine("🎉 **All steps completed!**")
                }
            },
            mapOf(
                "planId" to plan.id, "status" to plan.status.name,
                "completed" to plan.completedSteps.toString(),
                "total" to plan.totalSteps.toString(),
                "allDone" to (plan.status == PlanStatus.COMPLETED).toString(),
            ),
        )
    }

    private fun actCancel(): McpToolResult {
        val plan = PlanManager.cancelPlan()
        return McpToolResult.success(
            if (plan != null) "Plan '${plan.title}' cancelled."
            else "No active plan to cancel.",
            mapOf("status" to "cancelled"),
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun parseSteps(raw: String): List<Pair<String, String>> {
        return try {
            val arr = com.google.gson.JsonParser.parseString(raw).asJsonArray
            arr.map { elem ->
                when {
                    elem.isJsonPrimitive && elem.asJsonPrimitive.isString ->
                        elem.asString to ""
                    elem.isJsonObject -> {
                        val obj = elem.asJsonObject
                        val desc = obj.get("description")?.asString ?: obj.get("desc")?.asString ?: ""
                        val details = obj.get("details")?.asString ?: obj.get("detail")?.asString ?: ""
                        desc to details
                    }
                    else -> "" to ""
                }
            }.filter { it.first.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseStepStatus(raw: String): StepStatus? = when (raw.lowercase()) {
        "in_progress", "inprogress", "started" -> StepStatus.IN_PROGRESS
        "completed", "done", "complete" -> StepStatus.COMPLETED
        "failed", "error" -> StepStatus.FAILED
        "skipped", "skip" -> StepStatus.SKIPPED
        "pending" -> StepStatus.PENDING
        else -> null
    }
}
