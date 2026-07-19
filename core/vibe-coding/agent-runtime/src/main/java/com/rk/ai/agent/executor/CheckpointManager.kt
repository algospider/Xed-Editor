package com.rk.ai.agent.executor

import android.util.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private const val TAG = "CheckpointManager"

/**
 * Manages file backups before modifications, enabling rollback of changes
 * made during orchestrated task execution.
 *
 * Before each file write/edit, a snapshot of the original file is saved.
 * If the overall task fails, [restore] can undo all tracked changes.
 */
class CheckpointManager {

    private data class BackupEntry(
        val originalPath: String,
        val backupFile: File,
        val isNewFile: Boolean,
    )

    private val checkpoints = mutableListOf<BackupEntry>()
    private val checkpointDir: File by lazy {
        val dir = File(System.getProperty("java.io.tmpdir", "/tmp"), "xed-checkpoints")
        dir.mkdirs()
        dir
    }

    /**
     * Creates a backup of [filePath] before a write/edit operation.
     * If the file doesn't exist, it's tracked as a new file for deletion on rollback.
     */
    fun checkpoint(filePath: String) {
        val file = File(filePath)

        // Don't double-backup the same path in one session
        if (checkpoints.any { it.originalPath == filePath }) return

        if (file.exists()) {
            try {
                val backupFile = File(checkpointDir, "backup_${checkpoints.size}_${file.name}")
                Files.copy(file.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                checkpoints.add(BackupEntry(filePath, backupFile, isNewFile = false))
                Log.d(TAG, "Checkpoint saved: $filePath → $backupFile")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to backup $filePath: ${e.message}")
            }
        } else {
            // Track new files so they can be deleted on rollback
            checkpoints.add(BackupEntry(filePath, File(filePath), isNewFile = true))
            Log.d(TAG, "Checkpoint tracked (new file): $filePath")
        }
    }

    /**
     * Restores all files to their pre-modification state.
     * New files are deleted; modified files are restored from backup.
     * @return number of files restored
     */
    fun restore(): Int {
        var restored = 0
        // Process in reverse order for consistency
        for (entry in checkpoints.reversed()) {
            try {
                val file = File(entry.originalPath)
                if (entry.isNewFile) {
                    if (file.exists() && file.delete()) {
                        Log.d(TAG, "Restore: deleted new file ${entry.originalPath}")
                        restored++
                    }
                } else {
                    if (entry.backupFile.exists()) {
                        Files.copy(entry.backupFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        Log.d(TAG, "Restore: reverted ${entry.originalPath}")
                        restored++
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Restore failed for ${entry.originalPath}: ${e.message}")
            }
        }
        return restored
    }

    /**
     * Returns the list of backed-up file paths for reporting.
     */
    fun getTrackedFiles(): List<String> = checkpoints.map { it.originalPath }

    /**
     * Cleans up backup files from temp storage.
     * Does NOT modify working files — call [restore] first if rollback is needed.
     */
    fun cleanup() {
        for (entry in checkpoints) {
            if (!entry.isNewFile) {
                try {
                    entry.backupFile.delete()
                } catch (_: Exception) { }
            }
        }
        checkpoints.clear()
        // Clean up empty checkpoint dir
        try {
            checkpointDir.deleteRecursively()
        } catch (_: Exception) { }
    }

    val hasChanges: Boolean get() = checkpoints.isNotEmpty()
    val trackedFileCount: Int get() = checkpoints.size
}
