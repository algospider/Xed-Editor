package com.rk.ai.bridge.tools

import com.rk.ai.service.IdeService
import java.io.File

fun readLineRange(file: File, startInclusive: Int, endInclusive: Int?): String {
    val sb = StringBuilder()
    file.useLines { lines ->
        val iterator = lines.iterator()
        var lineNum = 1
        while (iterator.hasNext() && (endInclusive == null || lineNum <= endInclusive)) {
            val line = iterator.next()
            if (lineNum >= startInclusive) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(line)
            }
            lineNum++
        }
    }
    return sb.toString()
}

suspend fun showPatchAndApply(
    ideService: IdeService,
    file: File,
    newContent: String,
    title: String = "Review file change",
    refreshAfterApply: Boolean = true,
): String {
    val oldContent = ideService.getFileContent(file.absolutePath)
        ?: runCatching { file.readText() }.getOrDefault("")
    ideService.showPatch(file.absolutePath, oldContent, newContent, title) {
        ideService.writeFile(file, newContent)
        if (refreshAfterApply) ideService.refreshEditors(force = false)
    }
    return "Review opened in Xed Editor for ${file.absolutePath}. Results will be sent via notifications."
}
