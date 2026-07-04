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
        description = "Open a side-by-side diff view for user review. " +
            "Use BEFORE writeFile/editFile for high-risk changes to get user confirmation. " +
            "Shows old vs new content for the user to approve/reject. " +
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
        description = "Get file content AFTER user reviewed a diff. " +
            "Use after openDiff to check what the user approved. " +
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
        description = "Reject/close a pending diff view for a file. " +
            "Use when the user doesn't want to apply the proposed changes. " +
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
