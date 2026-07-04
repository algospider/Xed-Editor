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

class VibeCodingEditorTools(private val ideService: IdeService) {

    private val getOpenFiles = Tool(
        name = "getOpenFiles",
        description = "Lists all files currently open in editor tabs. " +
            "Use to understand what the user is currently working on. " +
            "Example: no args needed.",
        execute = { _ ->
            val files = ideService.getOpenFiles()
            listOf(UIMessagePart.Text(if (files.isNotEmpty()) files.joinToString("\n") { it["path"]?.asString ?: it.toString() } else "No files open"))
        },
    )

    private val getActiveFile = Tool(
        name = "getActiveFile",
        description = "Returns the path and full content of the file currently visible in the active editor tab. " +
            "Use to see what the user is looking at right now. Content truncated at 500KB. " +
            "Example: no args needed.",
        execute = { _ ->
            val json = ideService.getActiveFile()
            if (json != null) {
                val path = json["path"]?.asString ?: "unknown"
                val content = json["content"]?.asString ?: ""
                listOf(UIMessagePart.Text("File: $path\n\n$content"))
            } else {
                listOf(UIMessagePart.Text("No active file open\nSUGGESTION: Ask the user to open a file first."))
            }
        },
    )

    private val getSelection = Tool(
        name = "getSelection",
        description = "Returns the text currently selected by the user in the active editor. " +
            "Use to see what the user has highlighted before making changes. " +
            "Example: no args needed.",
        execute = { _ ->
            val selection = ideService.getSelection()
            listOf(UIMessagePart.Text(selection.ifEmpty { "No text selected" }))
        },
    )

    private val openFile = Tool(
        name = "openFile",
        description = "Open a file in an editor tab so the user can see it. " +
            "Use to draw the user's attention to a specific file. " +
            "Example: {\"filePath\": \"src/main.kt\"}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("filePath") { put("type", "string"); put("description", "Absolute path to the file to open") }
                },
                required = listOf("filePath"),
            )
        },
        execute = { args ->
            val filePath = args.asJsonObject["filePath"]?.asJsonPrimitive?.asString
                ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing 'filePath'."))
            ideService.openFile(File(filePath))
            listOf(UIMessagePart.Text("OK Opened $filePath"))
        },
    )

    private val replaceSelection = Tool(
        name = "replaceSelection",
        description = "Replace the user's current text selection with new text. " +
            "Opens a review tab for user confirmation. " +
            "Example: {\"newContent\": \"replacement text here\"}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("newContent") { put("type", "string"); put("description", "Text to replace the selection with") }
                },
                required = listOf("newContent"),
            )
        },
        execute = { args ->
            val newContent = args.asJsonObject["newContent"]?.asJsonPrimitive?.asString
                ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing 'newContent'."))
            ideService.replaceSelection(newContent)
            listOf(UIMessagePart.Text("OK Selection replaced"))
        },
    )

    private val insertAtCursor = Tool(
        name = "insertAtCursor",
        description = "Insert text at the user's current cursor position. " +
            "Opens a review tab for user confirmation. " +
            "Example: {\"newContent\": \"text to insert\"}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("newContent") { put("type", "string"); put("description", "Text to insert at the cursor") }
                },
                required = listOf("newContent"),
            )
        },
        execute = { args ->
            val newContent = args.asJsonObject["newContent"]?.asJsonPrimitive?.asString
                ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing 'newContent'."))
            ideService.insertAtCursor(newContent)
            listOf(UIMessagePart.Text("OK Inserted at cursor"))
        },
    )

    private val saveOpenFiles = Tool(
        name = "saveOpenFiles",
        description = "Save ALL unsaved changes in all open editor tabs. " +
            "Call this BEFORE running commands that read from disk (runCommand, build commands, etc.) " +
            "to ensure the latest content is on disk. " +
            "Example: no args needed.",
        execute = { _ ->
            val result = ideService.saveAllFiles()
            listOf(UIMessagePart.Text(result.ifEmpty { "OK All files saved" }))
        },
    )

    private val refreshOpenEditors = Tool(
        name = "refreshOpenEditors",
        description = "Reload all open editor tabs from disk (non-dirty tabs only). " +
            "Use after git checkout or file operations that change content externally. " +
            "Example: no args needed.",
        execute = { _ ->
            ideService.refreshEditors(null, false)
            listOf(UIMessagePart.Text("OK Open editors refreshed"))
        },
    )

    private val refreshFile = Tool(
        name = "refreshFile",
        description = "Reload a specific editor tab from disk. " +
            "Use after external changes to a specific file (e.g. git pull). " +
            "Example: {\"filePath\": \"src/main.kt\"}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("filePath") { put("type", "string"); put("description", "Absolute path of the file to refresh") }
                },
                required = listOf("filePath"),
            )
        },
        execute = { args ->
            val filePath = args.asJsonObject["filePath"]?.asJsonPrimitive?.asString
                ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing 'filePath'."))
            ideService.refreshEditors(filePath, false)
            listOf(UIMessagePart.Text("OK Refreshed $filePath"))
        },
    )

    val all: List<Tool> = listOf(
        getOpenFiles, getActiveFile, getSelection,
        openFile, replaceSelection, insertAtCursor,
        saveOpenFiles, refreshOpenEditors, refreshFile,
    )
}
