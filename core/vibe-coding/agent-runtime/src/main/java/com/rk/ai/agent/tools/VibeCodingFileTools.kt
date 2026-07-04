@file:OptIn(ExperimentalUuidApi::class)

package com.rk.ai.agent.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.uuid.ExperimentalUuidApi
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray
import com.rk.ai.agent.RecoveryHint
import com.rk.ai.agent.deriveRecoveryHint
import com.rk.ai.models.InputSchema
import com.rk.ai.models.Tool
import com.rk.ai.models.UIMessagePart
import com.rk.ai.service.IdeService
import java.io.File

class VibeCodingFileTools(
    private val ideService: IdeService,
    private val fileContentCache: FileContentCache = FileContentCache(),
) {
    companion object {
        private fun extractPath(obj: com.google.gson.JsonObject): String? {
            return obj["path"]?.asJsonPrimitive?.asString
                ?: obj["filePath"]?.asJsonPrimitive?.asString
                ?: obj["file"]?.asJsonPrimitive?.asString
                ?: obj["sourcePath"]?.asJsonPrimitive?.asString
                ?: obj["destPath"]?.asJsonPrimitive?.asString
                ?: obj["outputPath"]?.asJsonPrimitive?.asString
                ?: obj["target"]?.asJsonPrimitive?.asString
        }

        private fun pathNotFoundError(rawPath: String, workspacePath: String?): String {
            val ws = workspacePath?.takeIf { it.isNotBlank() } ?: "none"
            return "ERROR: Path could not be resolved: '$rawPath'\n" +
                "Workspace: $ws\n" +
                "SUGGESTION: Use an absolute path or a path relative to workspace root. " +
                "Call getProjectStructure or listFiles to verify the path exists."
        }

        private fun buildWorkspaceMsg(ideService: IdeService): String {
            val ws = ideService.getPrimaryWorkspacePath()
            return ws.takeIf { it.isNotBlank() }?.let { "Workspace: $it" } ?: "No workspace configured"
        }

        fun parseFilePaths(element: com.google.gson.JsonElement?): List<String> {
            if (element == null) return emptyList()
            if (element is JsonArray) {
                return element.mapNotNull { it.asJsonPrimitive?.asString?.trim() }.filter { it.isNotBlank() }
            }
            val raw = element.asJsonPrimitive?.asString?.trim() ?: return emptyList()
            if (raw.isBlank()) return emptyList()
            if (raw.startsWith("[")) {
                return runCatching {
                    val arr = JsonParser.parseString(raw).asJsonArray
                    arr.mapNotNull { it.asJsonPrimitive?.asString?.trim() }.filter { it.isNotBlank() }
                }.getOrDefault(
                    raw.removeSurrounding("[", "]").split(",").map { it.trim().removeSurrounding("\"") }.filter { it.isNotBlank() }
                )
            }
            return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
        }

        private fun countMatches(text: String, search: String): Int {
            var count = 0
            var idx = 0
            while (true) {
                idx = text.indexOf(search, idx)
                if (idx == -1) break
                count++
                idx += search.length
            }
            return count
        }

        private fun buildRecoveryMsg(error: String, toolName: String): String? {
            val hint = deriveRecoveryHint(toolName, error)
            return if (hint != null) "[RECOVERY] $error. ${hint.message}" else null
        }
    }

    private val readFile = Tool(
        name = "readFile",
        description = "Read a file by path. Supports line ranges (1-indexed). Content truncated at 250KB. " +
            "Use for single file reads. For multiple files, prefer readFiles (batch). " +
            "For search-then-read, prefer searchAndRead. " +
            "Example: {\"path\": \"src/main.kt\"} or {\"path\": \"src/main.kt\", \"startLine\": 10, \"endLine\": 50}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("path") { put("type", "string"); put("description", "Absolute or workspace-relative path to the file") }
                    putJsonObject("startLine") { put("type", "integer"); put("description", "First line to read (1-indexed). Use with endLine to read a specific range.") }
                    putJsonObject("endLine") { put("type", "integer"); put("description", "Last line to read (inclusive). Use with startLine.") }
                },
                required = listOf("path"),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val rawPath = extractPath(obj)
                ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing required 'path' argument.\nSUGGESTION: Provide an absolute or workspace-relative path, e.g. {\"path\": \"src/main.kt\"}"))
            val startLine = obj["startLine"]?.asJsonPrimitive?.asInt
            val endLine = obj["endLine"]?.asJsonPrimitive?.asInt
            val resolved = ideService.resolvePath(rawPath)
            if (resolved == null) return@Tool listOf(UIMessagePart.Text(pathNotFoundError(rawPath, ideService.getPrimaryWorkspacePath())))
            val filePath = resolved.absolutePath

            if (startLine == null && endLine == null) {
                val cached = fileContentCache.get(filePath)
                if (cached != null) return@Tool listOf(UIMessagePart.Text(cached))
            }

            val content = ideService.getFileContent(filePath, startLine, endLine)
            if (content != null) {
                if (content.isEmpty()) {
                    listOf(UIMessagePart.Text("(empty file — 0 bytes)"))
                } else {
                    if (startLine == null && endLine == null) fileContentCache.put(filePath, content)
                    listOf(UIMessagePart.Text(content))
                }
            } else {
                listOf(UIMessagePart.Text("ERROR: File not found: $rawPath\nSUGGESTION: Verify the file exists with listFiles. If the path is relative, it must be relative to the workspace root."))
            }
        },
    )

    private val readFiles = Tool(
        name = "readFiles",
        description = "Read MULTIPLE files in one call (batch mode). Pass a JSON array of paths. " +
            "Use INSTEAD of repeated readFile calls to minimize round-trips. " +
            "Example: {\"filePaths\": [\"src/a.kt\", \"src/b.kt\"]}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("filePaths") {
                        put("type", "array")
                        put("description", "Array of file path strings to read (e.g. [\"src/a.kt\", \"src/b.kt\"])")
                        putJsonObject("items") { put("type", "string") }
                    }
                },
                required = listOf("filePaths"),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val paths = parseFilePaths(obj["filePaths"])
            if (paths.isEmpty()) return@Tool listOf(UIMessagePart.Text("ERROR: Missing filePaths argument.\nEXPECTED: {\"filePaths\": [\"file1.kt\", \"file2.kt\"]}"))
            val results = paths.map { rawPath ->
                val resolved = ideService.resolvePath(rawPath)
                val filePath = resolved?.absolutePath ?: rawPath
                val cached = fileContentCache.get(filePath)
                val content = cached ?: ideService.getFileContent(filePath, null, null)
                if (cached == null && content != null) fileContentCache.put(filePath, content)
                if (content != null) "--- $rawPath ---\n$content"
                else "--- $rawPath ---\n[FILE NOT FOUND]"
            }
            listOf(UIMessagePart.Text(results.joinToString("\n\n")))
        },
    )

    private val writeFile = Tool(
        name = "writeFile",
        description = "Write or OVERWRITE a file completely. Creates parent directories automatically. " +
            "Use for creating new files or full rewrites. " +
            "For targeted edits, prefer editFile (surgical find-and-replace) to minimize context and potential errors. " +
            "For multiple files, prefer applyBatchEdits. " +
            "Example: {\"filePath\": \"src/main.kt\", \"content\": \"fun main() {}\"}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("filePath") { put("type", "string"); put("description", "Absolute path to the file to write") }
                    putJsonObject("content") { put("type", "string"); put("description", "The full content to write. Overwrites existing content entirely.") }
                },
                required = listOf("filePath", "content"),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val path = extractPath(obj)
                ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing 'filePath' or 'path' argument.\nEXPECTED: {\"filePath\": \"src/main.kt\", \"content\": \"...\"}"))
            val content = obj["content"]?.asJsonPrimitive?.asString
                ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing 'content' argument.\nEXPECTED: {\"filePath\": \"src/main.kt\", \"content\": \"...\"}"))
            val file = ideService.resolvePath(path)
            if (file == null) return@Tool listOf(UIMessagePart.Text(pathNotFoundError(path, ideService.getPrimaryWorkspacePath())))
            try {
                file.parentFile?.mkdirs()
                ideService.writeFile(file, content)
                fileContentCache.invalidate(file.absolutePath)
                listOf(UIMessagePart.Text("OK ${file.absolutePath} (${content.length} bytes)"))
            } catch (e: Exception) {
                val hint = buildRecoveryMsg(e.message ?: "Write failed", "writeFile")
                listOf(UIMessagePart.Text("ERROR: Failed to write $path: ${e.message}${hint?.let { "\n$it" } ?: ""}"))
            }
        },
    )

    private val editFile = Tool(
        name = "editFile",
        description = "Surgical find-and-replace in a file. " +
            "PROVIDE ENOUGH CONTEXT in oldString for a UNIQUE match (include surrounding lines). " +
            "Use replaceAll=true to replace all occurrences. " +
            "Use editFile instead of writeFile when you only need to change a small portion of a file. " +
            "For multiple edits in one file, use multiEditFile. " +
            "For read+edit in one call, use readAndEdit. " +
            "Example: {\"filePath\": \"src/main.kt\", \"oldString\": \"fun oldName()\", \"newString\": \"fun newName()\"}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("filePath") { put("type", "string"); put("description", "Absolute path to the file to edit") }
                    putJsonObject("oldString") { put("type", "string"); put("description", "The EXACT text to find. Must match whitespace exactly. Include enough surrounding lines for a unique match. Use the full line(s) as they appear in the file.") }
                    putJsonObject("newString") { put("type", "string"); put("description", "The replacement text. Can be empty string to delete oldString.") }
                    putJsonObject("replaceAll") { put("type", "boolean"); put("description", "Set true to replace ALL occurrences instead of just the first. Use when you're sure the text appears multiple times and all should be replaced.") }
                },
                required = listOf("filePath", "oldString", "newString"),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val filePath = extractPath(obj)
                ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing required 'filePath' or 'path'."))
            val oldString = obj["oldString"]?.asJsonPrimitive?.asString
                ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing required 'oldString'."))
            val newString = obj["newString"]?.asJsonPrimitive?.asString
                ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing required 'newString'."))
            val replaceAll = obj["replaceAll"]?.asJsonPrimitive?.asBoolean ?: false
            val file = ideService.resolvePath(filePath)
            if (file == null) return@Tool listOf(UIMessagePart.Text(pathNotFoundError(filePath, ideService.getPrimaryWorkspacePath())))
            val resolvedPath = file.absolutePath

            val content = ideService.getFileContent(resolvedPath, null, null)
            if (content == null) return@Tool listOf(UIMessagePart.Text("ERROR: File not found: $resolvedPath\nSUGGESTION: Verify the file exists with listFiles."))

            val matchCount = countMatches(content, oldString)
            if (matchCount == 0) {
                val contextHint = oldString.lines().let { lines ->
                    if (lines.size >= 3) "\nTRY: Include the line BEFORE and AFTER your target text to make the match unique."
                    else "\nTRY: Read the file first to see exact content. Whitespace (spaces vs tabs) must match exactly."
                }
                return@Tool listOf(UIMessagePart.Text("ERROR: Text not found in $resolvedPath (0 matches).$contextHint"))
            }
            if (matchCount > 1 && !replaceAll) {
                return@Tool listOf(UIMessagePart.Text(
                    "ERROR: Found $matchCount matches in $resolvedPath. " +
                    "Use replaceAll=true to replace all, or add more surrounding context to oldString for a unique match."
                ))
            }

            val result = if (replaceAll) content.replace(oldString, newString)
            else content.replaceFirst(oldString, newString)
            ideService.writeFile(file, result)
            fileContentCache.invalidate(resolvedPath)
            val replaced = if (replaceAll) " (replaced all $matchCount occurrences)" else ""
            listOf(UIMessagePart.Text("OK $resolvedPath$replaced"))
        },
    )

    private val readAndEdit = Tool(
        name = "readAndEdit",
        description = "COMBINED: Read a file, apply a single edit, then return both before and after content. " +
            "Saves 2 round-trips vs readFile + editFile + readFile. " +
            "Exactly the same parameters as editFile, but returns BEFORE and AFTER for verification. " +
            "Use this when you need to see the result immediately. " +
            "Example: {\"filePath\": \"src/main.kt\", \"oldString\": \"foo()\", \"newString\": \"bar()\"}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("filePath") { put("type", "string"); put("description", "Absolute path to the file") }
                    putJsonObject("oldString") { put("type", "string"); put("description", "The exact text to find. Must match whitespace exactly. Include surrounding lines for uniqueness.") }
                    putJsonObject("newString") { put("type", "string"); put("description", "The replacement text. Can be empty to delete oldString.") }
                    putJsonObject("replaceAll") { put("type", "boolean"); put("description", "Replace all occurrences (default: false)") }
                },
                required = listOf("filePath", "oldString", "newString"),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val filePath = extractPath(obj) ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing filePath or path"))
            val oldString = obj["oldString"]?.asJsonPrimitive?.asString ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing oldString"))
            val newString = obj["newString"]?.asJsonPrimitive?.asString ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing newString"))
            val replaceAll = obj["replaceAll"]?.asJsonPrimitive?.asBoolean ?: false

            val file = ideService.resolvePath(filePath)
            if (file == null) return@Tool listOf(UIMessagePart.Text(pathNotFoundError(filePath, ideService.getPrimaryWorkspacePath())))
            val resolvedPath = file.absolutePath

            val cached = fileContentCache.get(resolvedPath)
            val content = cached ?: ideService.getFileContent(resolvedPath, null, null)
            if (content == null) return@Tool listOf(UIMessagePart.Text("ERROR: File not found: $resolvedPath"))
            if (cached == null) fileContentCache.put(resolvedPath, content)

            val matchCount = countMatches(content, oldString)
            if (matchCount == 0) return@Tool listOf(UIMessagePart.Text("ERROR: Text not found in $resolvedPath. Ensure exact whitespace matching."))
            if (matchCount > 1 && !replaceAll) return@Tool listOf(UIMessagePart.Text("ERROR: Found $matchCount matches. Use replaceAll=true or add more context."))

            val result = if (replaceAll) content.replace(oldString, newString) else content.replaceFirst(oldString, newString)
            ideService.writeFile(file, result)
            fileContentCache.invalidate(resolvedPath)
            listOf(UIMessagePart.Text("BEFORE:\n$content\n\nAFTER:\n$result\n\nOK $resolvedPath"))
        },
    )

    private val multiEditFile = Tool(
        name = "multiEditFile",
        description = "ATOMIC multi-edit: Replace multiple blocks of text in a single file at once. " +
            "Each edit must be uniquely matchable. Fails ENTIRELY if any single edit cannot be applied. " +
            "Use for multiple changes in one file (e.g. rename a class and its constructor). " +
            "For single edits, use editFile. For multi-file edits, use applyBatchEdits. " +
            "Example: {\"filePath\": \"src/main.kt\", \"edits\": [{\"oldString\": \"class A\", \"newString\": \"class B\"}, {\"oldString\": \"A()\", \"newString\": \"B()\"}]}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("filePath") { put("type", "string"); put("description", "Absolute path to the file") }
                    putJsonObject("edits") {
                        put("type", "array")
                        put("description", "Array of {oldString, newString} objects. Each must have unique match.")
                        putJsonObject("items") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("oldString") { put("type", "string"); put("description", "Exact text to replace") }
                                putJsonObject("newString") { put("type", "string"); put("description", "Replacement text") }
                            }
                            putJsonArray("required") {
                                add(kotlinx.serialization.json.JsonPrimitive("oldString"))
                                add(kotlinx.serialization.json.JsonPrimitive("newString"))
                            }
                        }
                    }
                },
                required = listOf("filePath", "edits"),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val filePath = extractPath(obj) ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing filePath or path"))
            val editsArr = obj["edits"]?.asJsonArray ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing edits array"))
            val file = ideService.resolvePath(filePath)
            if (file == null) return@Tool listOf(UIMessagePart.Text(pathNotFoundError(filePath, ideService.getPrimaryWorkspacePath())))
            var content = ideService.getFileContent(file.absolutePath, null, null)
                ?: return@Tool listOf(UIMessagePart.Text("ERROR: File not found: ${file.absolutePath}"))
            var successCount = 0
            val errors = mutableListOf<String>()
            for (i in 0 until editsArr.size()) {
                val editObj = editsArr[i].asJsonObject
                val oldString = editObj["oldString"]?.asJsonPrimitive?.asString ?: ""
                val newString = editObj["newString"]?.asJsonPrimitive?.asString ?: ""
                if (oldString.isEmpty()) continue
                val mc = countMatches(content, oldString)
                if (mc == 0) errors.add("Edit ${i+1}: Text not found (check whitespace): ${oldString.take(80)}...")
                else if (mc > 1) errors.add("Edit ${i+1}: Found $mc matches. Add more context: ${oldString.take(80)}...")
                else { content = content.replaceFirst(oldString, newString); successCount++ }
            }
            if (successCount > 0 && errors.isEmpty()) {
                ideService.writeFile(file, content)
                fileContentCache.invalidate(file.absolutePath)
                listOf(UIMessagePart.Text("OK $successCount edits to ${file.absolutePath}"))
            } else {
                listOf(UIMessagePart.Text("ERROR: No changes written.\n${errors.joinToString("\n")}"))
            }
        }
    )

    private val applyBatchEdits = Tool(
        name = "applyBatchEdits",
        description = "Apply changes to MULTIPLE files at once in a single call. " +
            "Keys are file paths, values are the FULL new file content. " +
            "Use for cross-file changes to MINIMIZE ROUND-TRIPS (e.g. creating an interface and its implementation). " +
            "For single-file multi-edit, use multiEditFile. " +
            "Example: {\"edits\": {\"src/a.kt\": \"content a...\", \"src/b.kt\": \"content b...\"}}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("edits") { put("type", "object"); put("description", "JSON object where keys are file paths, values are full file content strings. Example: {\"path/to/file.kt\": \"new content...\"}") }
                },
                required = listOf("edits"),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val editsObj = obj["edits"]?.asJsonObject
                ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing 'edits' object. Expected: {\"edits\": {\"file1.kt\": \"content1\", \"file2.kt\": \"content2\"}}"))
            val results = mutableListOf<String>()
            for ((path, contentVal) in editsObj.entrySet()) {
                val file = ideService.resolvePath(path)
                if (file == null) { results.add("FAIL: $path (could not resolve)"); continue }
                val text = contentVal?.asJsonPrimitive?.asString
                if (text == null) { results.add("FAIL: $path (content must be a string)"); continue }
                try {
                    file.parentFile?.mkdirs()
                    ideService.writeFile(file, text)
                    fileContentCache.invalidate(file.absolutePath)
                    results.add("OK ${file.absolutePath} (${text.length} bytes)")
                } catch (e: Exception) { results.add("FAIL: $path (${e.message})") }
            }
            listOf(UIMessagePart.Text(results.joinToString("\n")))
        },
    )

    private val createFile = Tool(
        name = "createFile",
        description = "Create a new file with optional initial content. Parent directories are created automatically. " +
            "Fails if the file already exists. For overwriting, use writeFile. " +
            "Example: {\"filePath\": \"src/newfile.kt\", \"content\": \"fun main() { println(\\\"hi\\\") }\"}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("filePath") { put("type", "string"); put("description", "Absolute path for the new file") }
                    putJsonObject("content") { put("type", "string"); put("description", "Initial file content (optional, defaults to empty)") }
                },
                required = listOf("filePath"),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val filePath = extractPath(obj)
                ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing required 'filePath' or 'path'."))
            val content = obj["content"]?.asJsonPrimitive?.asString ?: ""
            val file = ideService.resolvePath(filePath)
            if (file == null) return@Tool listOf(UIMessagePart.Text(pathNotFoundError(filePath, ideService.getPrimaryWorkspacePath())))
            if (file.exists()) return@Tool listOf(UIMessagePart.Text("ERROR: File already exists: $filePath\nSUGGESTION: Use writeFile to overwrite, or choose a different path."))
            try {
                file.parentFile?.mkdirs()
                ideService.writeFile(file, content)
                fileContentCache.invalidate(file.absolutePath)
                listOf(UIMessagePart.Text("OK Created ${file.absolutePath} (${content.length} bytes)"))
            } catch (e: Exception) {
                listOf(UIMessagePart.Text("ERROR: Could not create $filePath: ${e.message}"))
            }
        },
    )

    private val deleteFile = Tool(
        name = "deleteFile",
        description = "Permanently delete a file from the workspace. Cannot be undone. " +
            "Use with caution — only delete files you're sure are no longer needed. " +
            "Example: {\"filePath\": \"src/old_unused_file.kt\"}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("filePath") { put("type", "string"); put("description", "Absolute path of the file to delete") }
                },
                required = listOf("filePath"),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val filePath = extractPath(obj)
                ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing 'filePath' or 'path'."))
            val file = ideService.resolvePath(filePath)
            if (file == null || !file.exists()) return@Tool listOf(UIMessagePart.Text("ERROR: File not found: $filePath\nSUGGESTION: Verify the path with listFiles."))
            try {
                ideService.deleteFile(filePath)
                fileContentCache.invalidate(file.absolutePath)
                listOf(UIMessagePart.Text("OK Deleted $filePath"))
            } catch (e: Exception) {
                listOf(UIMessagePart.Text("ERROR: Could not delete $filePath: ${e.message}"))
            }
        },
    )

    private val renameFile = Tool(
        name = "renameFile",
        description = "Rename or move a file or directory within the workspace. " +
            "Source must exist, destination must NOT exist. " +
            "Example: {\"sourcePath\": \"src/old_name.kt\", \"destPath\": \"src/new_name.kt\"}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("sourcePath") { put("type", "string"); put("description", "Current absolute path of the file or directory") }
                    putJsonObject("destPath") { put("type", "string"); put("description", "New absolute path for the file or directory") }
                },
                required = listOf("sourcePath", "destPath"),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val sourcePath = obj["sourcePath"]?.asJsonPrimitive?.asString
                ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing 'sourcePath'."))
            val destPath = obj["destPath"]?.asJsonPrimitive?.asString
                ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing 'destPath'."))
            val source = ideService.resolvePath(sourcePath)
            val dest = ideService.resolvePath(destPath)
            if (source == null || !source.exists()) return@Tool listOf(UIMessagePart.Text("ERROR: Source not found: $sourcePath"))
            if (dest == null) return@Tool listOf(UIMessagePart.Text("ERROR: Could not resolve destination: $destPath"))
            if (dest.exists()) return@Tool listOf(UIMessagePart.Text("ERROR: Destination already exists: $destPath"))
            try {
                ideService.renameFile(sourcePath, destPath)
                fileContentCache.invalidate(source.absolutePath)
                fileContentCache.invalidate(dest.absolutePath)
                listOf(UIMessagePart.Text("OK $sourcePath → $destPath"))
            } catch (e: Exception) {
                listOf(UIMessagePart.Text("ERROR: Rename failed: ${e.message}"))
            }
        },
    )

    private val listFiles = Tool(
        name = "listFiles",
        description = "List files in a directory. Supports recursive mode and maxFiles limit. " +
            "Use to explore the workspace structure. " +
            "For a full project tree, use getProjectStructure. " +
            "Example: {\"path\": \".\", \"recursive\": true, \"maxFiles\": 50}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("path") { put("type", "string"); put("description", "Directory path to list (use \".\" for workspace root)") }
                    putJsonObject("recursive") { put("type", "boolean"); put("description", "List files recursively (default: false)") }
                    putJsonObject("maxFiles") { put("type", "integer"); put("description", "Maximum number of files to return (default: 500, max: 5000)") }
                },
                required = listOf("path"),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val path = obj["path"]?.asJsonPrimitive?.asString ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing 'path'."))
            val recursive = obj["recursive"]?.asJsonPrimitive?.asBoolean ?: false
            val maxFiles = (obj["maxFiles"]?.asJsonPrimitive?.asInt ?: 500).coerceIn(1, 5000)
            val file = ideService.resolvePath(path)
            if (file != null && file.isDirectory) {
                val entries = ideService.listFiles(file, recursive, maxFiles)
                listOf(UIMessagePart.Text(if (entries.isNotEmpty()) entries.joinToString("\n") else "(empty directory)"))
            } else {
                listOf(UIMessagePart.Text("ERROR: Directory not found: $path\nSUGGESTION: Use getProjectStructure to see available directories."))
            }
        },
    )

    private val findFiles = Tool(
        name = "findFiles",
        description = "Find files by glob pattern (e.g. '*.kt' or '**/*.java'). " +
            "Returns matching file paths within the workspace. " +
            "Use when you know the file name or extension but not the exact path. " +
            "Example: {\"query\": \"**/*.kt\"} or {\"query\": \"*ViewModel*\", \"path\": \"src/\"}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("query") { put("type", "string"); put("description", "Glob pattern (e.g. '*.kt', '**/*.java', '*Test*')") }
                    putJsonObject("limit") { put("type", "integer"); put("description", "Maximum results (default: 100, max: 200)") }
                    putJsonObject("path") { put("type", "string"); put("description", "Directory to scope the search (default: workspace root)") }
                },
                required = listOf("query"),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val query = obj["query"]?.asJsonPrimitive?.asString ?: obj["pattern"]?.asJsonPrimitive?.asString
                ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing 'query' (glob pattern). Example: {\"query\": \"*.kt\"}"))
            val limit = (obj["limit"]?.asJsonPrimitive?.asInt ?: 100).coerceIn(1, 200)
            val rawPath = obj["path"]?.asJsonPrimitive?.asString
            val resolvedDir = if (rawPath != null) ideService.resolvePath(rawPath)?.absolutePath else null
            val results = ideService.findFiles(query, limit, resolvedDir ?: rawPath)
            if (results.size() > 0) {
                val text = results.joinToString("\n") { element ->
                    when {
                        element.isJsonObject -> {
                            val p = element.asJsonObject["path"]?.asString ?: element.toString()
                            val n = element.asJsonObject["name"]?.asString
                            if (n != null) "$p ($n)" else p
                        }
                        element.isJsonPrimitive -> element.asString
                        else -> element.toString()
                    }
                }
                listOf(UIMessagePart.Text(text.ifEmpty { "(no matches)" }))
            } else {
                listOf(UIMessagePart.Text("No matches for: $query\nSUGGESTION: Try a broader pattern (e.g. *$query* instead of $query)"))
            }
        },
    )

    private val tail = Tool(
        name = "tail",
        description = "Read the LAST N lines of a file. " +
            "Useful for checking log output, recent additions to generated files, or file endings. " +
            "More efficient than readFile when you only need the end of a file. " +
            "Example: {\"path\": \"build.log\", \"lines\": 20}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("path") { put("type", "string"); put("description", "Absolute or workspace-relative path") }
                    putJsonObject("lines") { put("type", "integer"); put("description", "Number of lines from the bottom (default: 10, max: 10000)") }
                },
                required = listOf("path"),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val rawPath = extractPath(obj) ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing 'path'."))
            val n = (obj["lines"]?.asJsonPrimitive?.asInt ?: 10).coerceIn(1, 10000)
            val resolved = ideService.resolvePath(rawPath)
            if (resolved == null) return@Tool listOf(UIMessagePart.Text("ERROR: Could not resolve path: $rawPath"))
            val filePath = resolved.absolutePath
            var fullContent = fileContentCache.get(filePath)
            if (fullContent == null) {
                fullContent = ideService.getFileContent(filePath, null, null)
                if (fullContent != null) fileContentCache.put(filePath, fullContent)
            }
            if (fullContent == null) return@Tool listOf(UIMessagePart.Text("ERROR: File not found: $rawPath"))
            val lines = fullContent.split("\n")
            val tailLines = lines.takeLast(n)
            listOf(UIMessagePart.Text(tailLines.joinToString("\n")))
        },
    )

    private val wc = Tool(
        name = "wc",
        description = "Count lines, words, characters, and bytes in a file. " +
            "Returns a JSON object with counts. Useful for quick file size assessment. " +
            "Example: {\"path\": \"src/main.kt\"}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("path") { put("type", "string"); put("description", "Absolute or workspace-relative path") }
                },
                required = listOf("path"),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val path = extractPath(obj) ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing 'path'."))
            val file = ideService.resolvePath(path)
            if (file == null) return@Tool listOf(UIMessagePart.Text("ERROR: Path could not be resolved: $path"))
            var text = fileContentCache.get(file.absolutePath)
            if (text == null) {
                text = ideService.getFileContent(file.absolutePath, null, null)
                if (text != null) fileContentCache.put(file.absolutePath, text)
            }
            if (text == null) return@Tool listOf(UIMessagePart.Text("ERROR: File not found: ${file.absolutePath}"))
            val lines = if (text.isEmpty()) 0L else { val c = text.count { it == '\n' }.toLong(); if (text.last() != '\n') c + 1 else c }
            val words = text.split(Regex("\\s+")).count { it.isNotBlank() }.toLong()
            listOf(UIMessagePart.Text(JsonObject().apply {
                addProperty("lines", lines); addProperty("words", words)
                addProperty("characters", text.length.toLong()); addProperty("bytes", text.encodeToByteArray().size.toLong())
                addProperty("path", file.absolutePath)
            }.toString()))
        },
    )

    private val stat = Tool(
        name = "stat",
        description = "Get file metadata: size, permissions, modified time, extension, parent. " +
            "Returns JSON. Use to check if a file exists or verify file details before editing. " +
            "Example: {\"path\": \"src/main.kt\"}",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("path") { put("type", "string"); put("description", "Absolute or workspace-relative path") }
                },
                required = listOf("path"),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val path = extractPath(obj) ?: return@Tool listOf(UIMessagePart.Text("ERROR: Missing 'path'."))
            val file = ideService.resolvePath(path)
            if (file == null) return@Tool listOf(UIMessagePart.Text("ERROR: Path could not be resolved: $path"))
            val exists = file.exists()
            listOf(UIMessagePart.Text(JsonObject().apply {
                addProperty("path", file.absolutePath); addProperty("name", file.name)
                addProperty("exists", exists)
                addProperty("isDirectory", if (exists) file.isDirectory else false)
                addProperty("isFile", if (exists) file.isFile else false)
                addProperty("isHidden", if (exists) file.isHidden else false)
                addProperty("isReadable", file.canRead()); addProperty("isWritable", file.canWrite())
                addProperty("isExecutable", file.canExecute())
                addProperty("size", if (exists) file.length() else 0)
                addProperty("extension", file.extension); addProperty("parent", file.parent)
            }.toString()))
        },
    )

    val all: List<Tool> = listOf(
        readFile, readFiles, readAndEdit, writeFile, editFile, multiEditFile, applyBatchEdits,
        createFile, deleteFile, renameFile, listFiles, findFiles, tail, wc, stat,
    )

    private fun humanReadableSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var size = bytes.toDouble()
        for (unit in units) { size /= 1024.0; if (size < 1024.0) return "%.1f %s".format(size, unit) }
        return "%.1f PB".format(size)
    }
}
