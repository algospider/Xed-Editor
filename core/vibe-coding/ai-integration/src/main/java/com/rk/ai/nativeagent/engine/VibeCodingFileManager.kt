@file:OptIn(ExperimentalUuidApi::class)
package com.rk.ai.nativeagent.engine

import android.content.Context
import com.rk.ai.mcp.FileManager
import java.io.File
import kotlin.uuid.ExperimentalUuidApi

/**
 * FileManager implementation for MCP uploads in the vibe-coding engine.
 */
class VibeCodingFileManager(private val context: Context) : FileManager {
    override suspend fun saveUploadFromBytes(
        bytes: ByteArray,
        displayName: String,
        mimeType: String,
    ): String {
        val dir = File(context.filesDir, "vibecoding_mcp").also { it.mkdirs() }
        val file = File(dir, "${java.util.UUID.randomUUID()}_$displayName")
        file.writeBytes(bytes)
        return file.absolutePath
    }

    override fun getFile(id: String): File = File(id)
}
