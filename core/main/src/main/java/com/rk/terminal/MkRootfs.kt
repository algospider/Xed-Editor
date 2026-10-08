package com.rk.terminal

import android.app.Activity
import android.content.Context
import android.os.Build
import com.rk.XedConstants
import com.rk.file.child
import com.rk.file.localBinDir
import com.rk.file.localLibDir
import com.rk.file.sandboxDir
import com.rk.file.sandboxHomeDir
import com.rk.resources.getString
import com.rk.resources.strings
import com.rk.utils.LoadingPopup
import com.rk.utils.getTempDir
import com.rk.utils.isMainThread
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import androidx.appcompat.app.AppCompatActivity

enum class NEXT_STAGE {
    NONE,
    EXTRACTION,
}

fun rootfsUrl(): String {
    val abi = Build.SUPPORTED_ABIS
    return when {
        abi.contains("x86_64") -> XedConstants.ROOTFS_X64
        abi.contains("arm64-v8a") -> XedConstants.ROOTFS_ARM64
        else -> XedConstants.ROOTFS_ARM
    }
}

suspend fun getNextStage(context: Context): NEXT_STAGE {
    if (isMainThread()) {
        throw RuntimeException("IO operation on the main thread")
    }

    val prootFile = localBinDir(context).child("proot")
    val tallocFile = localLibDir(context).child("libtalloc.so.2")
    val sandboxFile = File(getTempDir(), "sandbox.tar.gz")

    val abi = Build.SUPPORTED_ABIS
    val isArm64 = abi.contains("arm64-v8a")
    val isX86_64 = abi.contains("x86_64")

    if (!prootFile.exists()) {
        val url = when {
            isX86_64 -> XedConstants.PROOT_X64
            isArm64 -> XedConstants.PROOT_ARM64
            else -> XedConstants.PROOT_ARM
        }
        downloadFile(context, url, prootFile, "proot")
        prootFile.setExecutable(true)
    }

    if (!tallocFile.exists()) {
        val url = when {
            isX86_64 -> XedConstants.TALLOC_X64
            isArm64 -> XedConstants.TALLOC_ARM64
            else -> XedConstants.TALLOC_ARM
        }
        downloadFile(context, url, tallocFile, "libtalloc")
    }

    val isExtracted =
        sandboxDir().child("bin/bash").exists() ||
            sandboxDir().child("usr/bin/bash").exists()

    if (!isExtracted) {
        // NOTE: the rootfs download happens inside setup.sh via curl so the
        // user sees a realtime progress bar in the terminal. Kotlin only drops
        // obvious garbage (interrupted partials) so setup re-downloads cleanly.
        val minBytes = 25L * 1024 * 1024
        if (sandboxFile.exists() && (sandboxFile.length() < minBytes || !isGzip(sandboxFile))) {
            try { sandboxFile.delete() } catch (_: Exception) {}
        }
        return NEXT_STAGE.EXTRACTION
    }

    return NEXT_STAGE.NONE
}

private fun isGzip(file: File): Boolean {
    if (!file.exists() || file.length() < 2) return false
    return try {
        val hdr = ByteArray(2)
        file.inputStream().use { it.read(hdr) }
        hdr[0] == 0x1f.toByte() && hdr[1] == 0x8b.toByte()
    } catch (_: Exception) { false }
}

private fun downloadFile(context: Context, url: String, outputFile: File, label: String) {
    val activity = context as? AppCompatActivity
    val loadingPopup = activity?.let {
        LoadingPopup(it).setMessage("${strings.downloading.getString()} $label...").show()
    }

    fun progress(message: String) {
        activity?.runOnUiThread { loadingPopup?.setMessage(message) }
    }

    try {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        val request = Request.Builder().url(url).build()

        // Atomic download: partial files never masquerade as complete archives
        val tmp = File(outputFile.parentFile, outputFile.name + ".part")
        try { tmp.delete() } catch (_: Exception) {}
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw RuntimeException("Failed to download $label: ${response.code}")

            val total = response.body?.contentLength() ?: -1L
            var copied = 0L
            var lastBucket = -1
            response.body?.byteStream()?.use { input ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                        copied += n
                        if (total > 0) {
                            // Throttle UI updates to 5% steps
                            val bucket = ((copied * 100) / total / 5).toInt()
                            if (bucket != lastBucket) {
                                lastBucket = bucket
                                val doneMb = copied / 1048576.0
                                val totalMb = total / 1048576.0
                                val pct = ((copied * 100) / total).toInt()
                                progress(
                                    "${strings.downloading.getString()} $label… $pct% (" +
                                        "%.1f / %.1f MB".format(doneMb, totalMb) + ")",
                                )
                            }
                        }
                    }
                }
            }
            if (total > 0 && lastBucket >= 0) {
                progress("${strings.downloading.getString()} $label… 100%")
            }
        }
        if (!tmp.renameTo(outputFile)) {
            tmp.copyTo(outputFile, overwrite = true)
            try { tmp.delete() } catch (_: Exception) {}
        }
    } finally {
        loadingPopup?.hide()
    }
}
