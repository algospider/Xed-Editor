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
    }

    private val readFile = Tool(
        name = "readFile",
        description = "Read a file by path (supports startLine/endLine, 1-indexed, inclusive). Content truncated at 250KB.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("path") { put("type", "string"); put("description", "Absolute or workspace-relative path to the file") }
                    putJsonObject("startLine") { put("type", "integer"); put("description", "First line to read (1-indexed)") }
                    putJsonObject("endLine") { put("type", "integer"); put("description", "Last line to read (inclusive)") }
                },
                required = listOf("path"),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val rawPath = obj["path"]?.asJsonPrimitive?.asString ?: return@Tool listOf(UIMessagePart.Text("Missing path argument"))
            val startLine = obj["startLine"]?.asJsonPrimitive?.asInt
            val endLine = obj["endLine"]?.asJsonPrimitive?.asInt
            val resolved = ideService.resolvePath(rawPath)
            val filePath = resolved?.absolutePath ?: rawPath

            // Use cache only for full-file reads
            if (startLine == null && endLine == null) {
                val cached = fileContentCache.get(filePath)
                if (cached != null) {
                    return@Tool listOf(UIMessagePart.Text(cached))
                }
            }

            val content = ideService.getFileContent(filePath, startLine, endLine)
            if (content != null) {
                if (startLine == null && endLine == null) {
                    fileContentCache.put(filePath, content)
                }
                listOf(UIMessagePart.Text(content))
            } else {
                listOf(UIMessagePart.Text("File not found: $rawPath"))
            }
        },
    )

    private val readFiles = Tool(
        name = "readFiles",
        description = "Read multiple files in one call. Pass comma-separated paths or a JSON array path strings. Faster than repeated readFile calls.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("filePaths") { put("type", "string"); put("description", "Comma-separated list of paths or JSON array of path strings") }
                },
                required = listOf("filePaths"),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val paths = parseFilePaths(obj["filePaths"])
            if (paths.isEmpty()) return@Tool listOf(UIMessagePart.Text("Missing filePaths argument"))
            val results = paths.map { rawPath ->
                val resolved = ideService.resolvePath(rawPath)
                val filePath = resolved?.absolutePath ?: rawPath
                val cached = fileContentCache.get(filePath)
                val content = cached ?: ideService.getFileContent(filePath, null, null)
                if (cached == null && content != null) {
                    fileContentCache.put(filePath, content)
                }
                if (content != null) "--- $rawPath ---\n$content"
                else "--- $rawPath ---\n(FILE NOT FOUND)"
            }
            listOf(UIMessagePart.Text(results.joinToString("\n\n")))
        },
    )

    private val writeFile = Tool(
        name = "writeFile",
        description = "Write or overwrite a file. Creates parent directories automatically. Opens a review tab for user confirmation.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("path") { put("type", "string"); put("description", "Absolute path to the file") }
                    putJsonObject("content") { put("type", "string"); put("description", "The full content to write") }
                },
                required = listOf("path", "content"),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val path = obj["path"]?.asJsonPrimitive?.asString ?: return@Tool listOf(UIMessagePart.Text("Missing path argument"))
            val content = obj["content"]?.asJsonPrimitive?.asString ?: return@Tool listOf(UIMessagePart.Text("Missing content argument"))
            val file = ideService.resolvePath(path)
            if (file == null) return@Tool listOf(UIMessagePart.Text("Path could not be resolved: $path"))
            ideService.writeFile(file, content)
            fileContentCache.invalidate(file.absolutePath)
            listOf(UIMessagePart.Text("Written to ${file.absolutePath}"))
        },
    )

    private val editFile = Tool(
        name = "editFile",
        description = "Find exact text in a file and replace it. Preferred for targeted edits. Provide enough context in oldString for a unique match. Use replaceAll=true to replace all occurrences.",
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
            val filePath = obj["filePath"]?.asJsonPrimitive?.asString ?: return@Tool listOf(UIMessagePart.Text("Missing filePath"))
            val oldString = obj["oldString"]?.asJsonPrimitive?.asString ?: return@Tool listOf(UIMessagePart.Text("Missing oldString"))
            val newString = obj["newString"]?.asJsonPrimitive?.asString ?: return@Tool listOf(UIMessagePart.Text("Missing newString"))
            val replaceAll = obj["replaceAll"]?.asJsonPrimitive?.asBoolean ?: false
            val file = ideService.resolvePath(filePath)
            if (file == null) return@Tool listOf(UIMessagePart.Text("Path could not be resolved: $filePath"))
            val resolvedPath = file.absolutePath
            val content = ideService.getFileContent(resolvedPath, null, null) ?: return@Tool listOf(UIMessagePart.Text("File not found: $resolvedPath"))

            var matchCount = 0
            var searchIdx = 0
            while (true) {
                searchIdx = content.indexOf(oldString, searchIdx)
                if (searchIdx == -1) break
                matchCount++
                searchIdx += oldString.length
            }

            if (matchCount == 0) {
                return@Tool listOf(UIMessagePart.Text("Text not found in $resolvedPath. Ensure the whitespace in oldString matches the file exactly."))
            }

            if (matchCount > 1 && !replaceAll) {
                return@Tool listOf(UIMessagePart.Text(
                    "Found $matchCount matches in $resolvedPath. " +
                    "Use replaceAll=true or add more surrounding context to oldString for a unique match."
                ))
            }

            val result = content.replace(oldString, newString)
            ideService.writeFile(file, result)
            fileContentCache.invalidate(resolvedPath)
            val note = if (replaceAll) " (replaced all $matchCount occurrences)" else ""
            listOf(UIMessagePart.Text("Edited $resolvedPath$note"))
        },
    )

    private val multiEditFile = Tool(
        name = "multiEditFile",
        description = "Replace multiple blocks of text in a single file atomically. Each edit must have a unique oldString match. Fails entirely if any edit cannot be applied exactly once.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("filePath") { put("type", "string"); put("description", "Absolute path to the file") }
                    putJsonObject("edits") { 
                        put("type", "array")
                        put("description", "Array of objects containing oldString and newString")
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
            val filePath = obj["filePath"]?.asJsonPrimitive?.asString ?: return@Tool listOf(UIMessagePart.Text("Missing filePath"))
            val editsArr = obj["edits"]?.asJsonArray ?: return@Tool listOf(UIMessagePart.Text("Missing edits array"))
            
            val file = ideService.resolvePath(filePath)
            if (file == null) return@Tool listOf(UIMessagePart.Text("Path could not be resolved: $filePath"))
            
            var content = ideService.getFileContent(file.absolutePath, null, null) 
                ?: return@Tool listOf(UIMessagePart.Text("File not found: ${file.absolutePath}"))
            
            var successCount = 0
            var errorCount = 0
            val errors = mutableListOf<String>()
            
            for (i in 0 until editsArr.size()) {
                val editObj = editsArr[i].asJsonObject
                val oldString = editObj["oldString"]?.asJsonPrimitive?.asString ?: ""
                val newString = editObj["newString"]?.asJsonPrimitive?.asString ?: ""
                
                if (oldString.isEmpty()) continue
                
                val matchCount = content.split(oldString).size - 1
                if (matchCount == 0) {
                    errorCount++
                    errors.add("Edit ${i+1}: Could not find exact text to replace:\n```\n${oldString.take(200)}\n```")
                } else if (matchCount > 1) {
                    errorCount++
                    val lines = oldString.lines()
                    val hint = if (lines.size <= 2) {
                        "\nThe text appears $matchCount times. Add more surrounding lines to make it unique."
                    } else {
                        val firstLine = lines.first().trim()
                        val lastLine = lines.last().trim()
                        "\nFound $matchCount matches. Try including unique lines like:\n  First: \"$firstLine\"\n  Last: \"$lastLine\""
                    }
                    errors.add("Edit ${i+1}: Found $matchCount matches for oldString.$hint")
                } else {
                    content = content.replaceFirst(oldString, newString)
                    successCount++
                }
            }
            
            if (successCount > 0 && errorCount == 0) {
                ideService.writeFile(file, content)
                fileContentCache.invalidate(file.absolutePath)
                listOf(UIMessagePart.Text("Successfully applied $successCount edits to ${file.absolutePath}"))
            } else if (successCount > 0 && errorCount > 0) {
                listOf(UIMessagePart.Text("Failed to apply all edits. No changes were written to disk. Errors:\n" + errors.joinToString("\n")))
            } else {
                listOf(UIMessagePart.Text("Failed to apply any edits. No changes were written to disk. Errors:\n" + errors.joinToString("\n")))
            }
        }
    )

    private val applyBatchEdits = Tool(
        name = "applyBatchEdits",
        description = "Write new content to multiple files at once. Keys are file paths, values are full file content. Use for cross-file changes to minimize turns.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("edits") { put("type", "string"); put("description", "JSON object mapping file paths to their new content: {\"path/to/file.kt\": \"new content...\"}") }
                },
                required = listOf("edits"),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val editsElement = obj["edits"] ?: return@Tool listOf(UIMessagePart.Text("Missing edits argument (must be a JSON object)"))
            val editsObj = when {
                editsElement.isJsonObject -> editsElement.asJsonObject
                editsElement.isJsonPrimitive && editsElement.asJsonPrimitive.isString -> {
                    try {
                        com.google.gson.JsonParser.parseString(editsElement.asString).asJsonObject
                    } catch (e: Exception) {
                        return@Tool listOf(UIMessagePart.Text("Invalid JSON string in 'edits': ${e.message}"))
                    }
                }
                else -> return@Tool listOf(UIMessagePart.Text("'edits' must be a JSON object or a JSON string"))
            }
            val edits = mutableMapOf<String, String>()
            editsObj.entrySet().forEach { entry ->
                val path = entry.key
                val content = when {
                    entry.value.isJsonPrimitive -> entry.value.asString
                    entry.value.isJsonObject -> entry.value.toString()
                    else -> entry.value.toString()
                }
                val file = ideService.resolvePath(path)
                edits[file?.absolutePath ?: path] = content
            }
            ideService.applyBatchEdits(edits)
            edits.keys.forEach { fileContentCache.invalidate(it) }
            listOf(UIMessagePart.Text("Batch edits for ${edits.size} files applied."))
        },
    )

    private val createFile = Tool(
        name = "createFile",
        description = "Create a new file with optional initial content. Parent directories created automatically.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("filePath") { put("type", "string"); put("description", "Absolute path for the new file") }
                    putJsonObject("content") { put("type", "string"); put("description", "Initial file content (optional)") }
                },
                required = listOf("filePath"),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val filePath = obj["filePath"]?.asJsonPrimitive?.asString ?: return@Tool listOf(UIMessagePart.Text("Missing filePath"))
            val content = obj["content"]?.asJsonPrimitive?.asString
            val result = ideService.createFile(filePath, content)
            fileContentCache.invalidate(filePath)
            listOf(UIMessagePart.Text(result))
        },
    )

    private val deleteFile = Tool(
        name = "deleteFile",
        description = "Delete a file permanently from the workspace.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("filePath") { put("type", "string"); put("description", "Absolute path of the file to delete") }
                },
                required = listOf("filePath"),
            )
        },
        execute = { args ->
            val filePath = args.asJsonObject["filePath"]?.asJsonPrimitive?.asString ?: return@Tool listOf(UIMessagePart.Text("Missing filePath"))
            val result = ideService.deleteFile(filePath)
            fileContentCache.invalidate(filePath)
            listOf(UIMessagePart.Text(result))
        },
    )

    private val renameFile = Tool(
        name = "renameFile",
        description = "Rename or move a file or directory to a new workspace path.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("sourcePath") { put("type", "string"); put("description", "Current path of the file or directory") }
                    putJsonObject("destPath") { put("type", "string"); put("description", "New path for the file or directory") }
                },
                required = listOf("sourcePath", "destPath"),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val sourcePath = obj["sourcePath"]?.asJsonPrimitive?.asString ?: return@Tool listOf(UIMessagePart.Text("Missing sourcePath"))
            val destPath = obj["destPath"]?.asJsonPrimitive?.asString ?: return@Tool listOf(UIMessagePart.Text("Missing destPath"))
            val result = ideService.renameFile(sourcePath, destPath)
            fileContentCache.invalidate(sourcePath)
            fileContentCache.invalidate(destPath)
            listOf(UIMessagePart.Text(result))
        },
    )

    private val listFiles = Tool(
        name = "listFiles",
        description = "List files in a directory. Supports recursive mode and maxFiles limit.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("path") { put("type", "string"); put("description", "Directory path to list") }
                    putJsonObject("recursive") { put("type", "boolean"); put("description", "List files recursively (default: false)") }
                    putJsonObject("maxFiles") { put("type", "integer"); put("description", "Maximum number of files to return (default: 500, max: 5000)") }
                },
                required = listOf("path"),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val path = obj["path"]?.asJsonPrimitive?.asString ?: return@Tool listOf(UIMessagePart.Text("Missing path"))
            val recursive = obj["recursive"]?.asJsonPrimitive?.asBoolean ?: false
            val maxFiles = (obj["maxFiles"]?.asJsonPrimitive?.asInt ?: 500).coerceIn(1, 5000)
            val file = ideService.resolvePath(path)
            if (file != null && file.isDirectory) {
                val entries = ideService.listFiles(file, recursive, maxFiles)
                listOf(UIMessagePart.Text(entries.joinToString("\n")))
            } else {
                listOf(UIMessagePart.Text("Directory not found: $path"))
            }
        },
    )

    private val findFiles = Tool(
        name = "findFiles",
        description = "Find files by glob pattern (e.g. '*.kt' or '**/*.java'). Returns matching file paths.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("query") { put("type", "string"); put("description", "File name or glob pattern to search for (e.g. *.kt, **/*.java)") }
                    putJsonObject("pattern") { put("type", "string"); put("description", "Alternative to query") }
                    putJsonObject("limit") { put("type", "integer"); put("description", "Maximum results (default: 100)") }
                    putJsonObject("path") { put("type", "string"); put("description", "Directory to search in (default: workspace root)") }
                },
                required = emptyList(),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val query = obj["query"]?.asJsonPrimitive?.asString
                ?: obj["pattern"]?.asJsonPrimitive?.asString
                ?: return@Tool listOf(UIMessagePart.Text("Missing query/pattern"))
            val limit = obj["limit"]?.asJsonPrimitive?.asInt ?: 100
            val rawPath = obj["path"]?.asJsonPrimitive?.asString
            val resolvedDir = if (rawPath != null) ideService.resolvePath(rawPath)?.absolutePath else null
            val results = ideService.findFiles(query, limit, resolvedDir ?: rawPath)
            if (results.size() > 0) {
                val text = results.joinToString("\n") { element ->
                    when {
                        element.isJsonObject -> {
                            val path = element.asJsonObject["path"]?.asString ?: element.toString()
                            val name = element.asJsonObject["name"]?.asString
                            if (name != null) "$path ($name)" else path
                        }
                        element.isJsonPrimitive -> element.asString
                        else -> element.toString()
                    }
                }
                listOf(UIMessagePart.Text(text))
            } else {
                listOf(UIMessagePart.Text("No files found matching: $query"))
            }
        },
    )

    private val tail = Tool(
        name = "tail",
        description = "Read the last N lines of a file. Useful for logs, recent output, or the end of generated files.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("path") { put("type", "string"); put("description", "Absolute or relative path to the file") }
                    putJsonObject("filePath") { put("type", "string"); put("description", "Alternative to path") }
                    putJsonObject("file") { put("type", "string"); put("description", "Alternative to path") }
                    putJsonObject("lines") { put("type", "integer"); put("description", "Number of lines to read from the bottom (default: 10, max: 10000)") }
                    putJsonObject("count") { put("type", "integer"); put("description", "Alias for lines") }
                },
                required = emptyList(),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val rawPath = extractPath(obj) ?: return@Tool listOf(UIMessagePart.Text("Missing path/filePath/file"))
            val n = (obj["lines"]?.asJsonPrimitive?.asInt ?: obj["count"]?.asJsonPrimitive?.asInt ?: 10).coerceIn(1, 10000)
            val resolved = ideService.resolvePath(rawPath)
            val filePath = resolved?.absolutePath ?: rawPath
            var fullContent = fileContentCache.get(filePath)
            if (fullContent == null) {
                fullContent = ideService.getFileContent(filePath, null, null)
                if (fullContent != null) fileContentCache.put(filePath, fullContent)
            }
            if (fullContent == null) return@Tool listOf(UIMessagePart.Text("File not found: $rawPath"))
            val lines = fullContent.split("\n")
            val tailLines = lines.takeLast(n)
            listOf(UIMessagePart.Text(tailLines.joinToString("\n")))
        },
    )

    private val wc = Tool(
        name = "wc",
        description = "Count lines, words, characters, and bytes in a file. Accepts path/filePath/file.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("path") { put("type", "string"); put("description", "Absolute or relative path to the file") }
                    putJsonObject("filePath") { put("type", "string"); put("description", "Alternative to path") }
                    putJsonObject("file") { put("type", "string"); put("description", "Alternative to path") }
                },
                required = emptyList(),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val path = extractPath(obj) ?: return@Tool listOf(UIMessagePart.Text("Missing path/filePath/file"))
            val file = ideService.resolvePath(path) ?: return@Tool listOf(UIMessagePart.Text("Path not found: $path"))
            var text = fileContentCache.get(file.absolutePath)
            if (text == null) {
                text = ideService.getFileContent(file.absolutePath, null, null)
                if (text != null) fileContentCache.put(file.absolutePath, text)
            }
            if (text == null) return@Tool listOf(UIMessagePart.Text("File not found: ${file.absolutePath}"))
            val lines = if (text.isEmpty()) 0L else {
                val count = text.count { it == '\n' }.toLong()
                if (text.last() != '\n') count + 1 else count
            }
            val words = text.split(Regex("\\s+")).count { it.isNotBlank() }.toLong()
            val chars = text.length.toLong()
            val bytes = text.encodeToByteArray().size.toLong()
            val result = JsonObject().apply {
                addProperty("lines", lines)
                addProperty("words", words)
                addProperty("characters", chars)
                addProperty("bytes", bytes)
                addProperty("path", file.absolutePath)
            }.toString()
            listOf(UIMessagePart.Text(result))
        },
    )

    private val stat = Tool(
        name = "stat",
        description = "Get file metadata (size, permissions, modified time, extension, parent). Accepts path/filePath/file.",
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    putJsonObject("path") { put("type", "string"); put("description", "Absolute or relative path to the file") }
                    putJsonObject("filePath") { put("type", "string"); put("description", "Alternative to path") }
                    putJsonObject("file") { put("type", "string"); put("description", "Alternative to path") }
                },
                required = emptyList(),
            )
        },
        execute = { args ->
            val obj = args.asJsonObject
            val path = extractPath(obj) ?: return@Tool listOf(UIMessagePart.Text("Missing path/filePath/file"))
            val file = ideService.resolvePath(path) ?: return@Tool listOf(UIMessagePart.Text("Path not found: $path"))
            val exists = file.exists()
            val result = JsonObject().apply {
                addProperty("path", file.absolutePath)
                addProperty("name", file.name)
                addProperty("exists", exists)
                addProperty("isDirectory", if (exists) file.isDirectory else false)
                addProperty("isFile", if (exists) file.isFile else false)
                addProperty("isHidden", if (exists) file.isHidden else false)
                addProperty("isReadable", file.canRead())
                addProperty("isWritable", file.canWrite())
                addProperty("isExecutable", file.canExecute())
                addProperty("size", if (exists) file.length() else 0)
                addProperty("sizeHuman", if (exists) humanReadableSize(file.length()) else "0 B")
                addProperty("lastModified", if (exists) file.lastModified() else 0)
                addProperty("extension", file.extension)
                addProperty("parent", file.parent)
            }.toString()
            listOf(UIMessagePart.Text(result))
        },
    )

    private val readAndEdit = Tool(
        name = "readAndEdit",
        description = "Read a file, apply a single edit, then return the file content before and after. Saves one round-trip over readFile + editFile + readFile.",
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
            val filePath = obj["filePath"]?.asJsonPrimitive?.asString ?: return@Tool listOf(UIMessagePart.Text("Missing filePath"))
            val oldString = obj["oldString"]?.asJsonPrimitive?.asString ?: return@Tool listOf(UIMessagePart.Text("Missing oldString"))
            val newString = obj["newString"]?.asJsonPrimitive?.asString ?: return@Tool listOf(UIMessagePart.Text("Missing newString"))
            val replaceAll = obj["replaceAll"]?.asJsonPrimitive?.asBoolean ?: false

            val file = ideService.resolvePath(filePath)
            if (file == null) return@Tool listOf(UIMessagePart.Text("Path could not be resolved: $filePath"))
            val resolvedPath = file.absolutePath

            val cached = fileContentCache.get(resolvedPath)
            val content = cached ?: ideService.getFileContent(resolvedPath, null, null)
            if (content == null) return@Tool listOf(UIMessagePart.Text("File not found: $resolvedPath"))
            if (cached == null) fileContentCache.put(resolvedPath, content)

            var matchCount = 0
            var searchIdx = 0
            while (true) {
                searchIdx = content.indexOf(oldString, searchIdx)
                if (searchIdx == -1) break
                matchCount++
                searchIdx += oldString.length
            }

            if (matchCount == 0) {
                return@Tool listOf(UIMessagePart.Text("Text not found in $resolvedPath. Ensure the whitespace in oldString matches the file exactly."))
            }
            if (matchCount > 1 && !replaceAll) {
                return@Tool listOf(UIMessagePart.Text(
                    "Found $matchCount matches in $resolvedPath. " +
                    "Use replaceAll=true or add more surrounding context to oldString for a unique match."
                ))
            }

            val result = content.replace(oldString, newString)
            ideService.writeFile(file, result)
            fileContentCache.invalidate(resolvedPath)

            val note = if (replaceAll) " (replaced all $matchCount occurrences)" else ""
            listOf(UIMessagePart.Text("Before:\n$content\n\nAfter:\n$result\n\nEdited $resolvedPath$note"))
        },
    )

    val all: List<Tool> = listOf(
        readFile, readFiles, readAndEdit, writeFile, editFile, multiEditFile, applyBatchEdits,
        createFile, deleteFile, renameFile,
        listFiles, findFiles,
        tail, wc, stat,
    )

    private fun humanReadableSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var size = bytes.toDouble()
        for (unit in units) {
            size /= 1024.0
            if (size < 1024.0) return "%.1f %s".format(size, unit)
        }
        return "%.1f PB".format(size)
    }
}
