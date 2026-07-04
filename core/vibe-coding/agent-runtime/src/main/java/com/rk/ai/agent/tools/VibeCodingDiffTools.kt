@file:OptIn(ExperimentalUuidApi::class)

package com.rk.ai.agent.tools

import com.google.gson.JsonObject
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import com.rk.ai.models.InputSchema
import com.rk.ai.models.Tool
import com.rk.ai.models.UIMessagePart
import com.rk.ai.service.IdeService
import java.io.File

class VibeCodingDiffTools(private val ideService: IdeService) {

    private val openDiff = Tool(
        name = "openDiff",
        description = "Open a side-by-side diff for user review. User approves/rejects in the UI. " +
            "After approval, call getDiffResult to read the post-approval result. " +
            "Use for high-risk changes where you want user confirmation before writing. " +
            "Workflow: openDiff → user approves in UI → getDiffResult (reads approved file). " +
            "Example: {\"filePath\": \"src/main.kt\", \"newContent\": \"updated file content...\"}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("filePath") { put("type", "string"); put("description", "Absolute path to the file") }
                    putJsonObject("newContent") { put("type", "string"); put("description", "Proposed new content to show in diff") }
                },
                required = listOf("filePath", "newContent"),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val filePath = obj["filePath"]?.asJsonPrimitive?.asString
                ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing 'filePath'."))
            val newContent = obj["newContent"]?.asJsonPrimitive?.asString
                ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing 'newContent'."))
            val file = ideService.resolvePath(filePath)
            if (file == null || !file.exists()) return@Tool listOf(UIMessagePart.Text("ERROR: File not found: $filePath"))
            val oldContent = ideService.getFileContent(filePath, null, null) ?: ""
            ideService.showPatch(filePath, oldContent, newContent, "Review AI file change") { }
            listOf(UIMessagePart.Text("OK Diff opened for $filePath (waiting for user review)"))
        },
    )

    private val getDiffResult = Tool(
        name = "getDiffResult",
        description = "Read file content AFTER user reviewed an openDiff. " +
            "Step 3 in the workflow: openDiff → user approves in UI → getDiffResult. " +
            "Returns current file content (user-approved version if they accepted). " +
            "Example: {\"filePath\": \"src/main.kt\"}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("filePath") { put("type", "string"); put("description", "Absolute path to the file") }
                },
                required = listOf("filePath"),
            )
        },
        execute = { args ->
            val filePath = args.asJsonObject["filePath"]?.asJsonPrimitive?.asString
                ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing 'filePath'."))
            val content = ideService.getFileContent(filePath, null, null)
            listOf(UIMessagePart.Text(content ?: "ERROR: File not found: $filePath"))
        },
    )

    private val rejectDiff = Tool(
        name = "rejectDiff",
        description = "Reject/close a pending diff view. " +
            "Use when the user declined the proposed changes in the UI. " +
            "File content is NOT modified — reverts to original. " +
            "Example: {\"filePath\": \"src/main.kt\"}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("filePath") { put("type", "string"); put("description", "Absolute path to the file with the pending diff") }
                },
                required = listOf("filePath"),
            )
        },
        execute = { args ->
            val filePath = args.asJsonObject["filePath"]?.asJsonPrimitive?.asString
                ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing 'filePath'."))
            ideService.rejectPatch(filePath)
            listOf(UIMessagePart.Text("OK Rejected patch for $filePath"))
        },
    )

    val all: List<Tool> = listOf(openDiff, getDiffResult, rejectDiff)
}
