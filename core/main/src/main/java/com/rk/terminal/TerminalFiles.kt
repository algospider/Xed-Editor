package com.rk.terminal

import com.rk.file.child
import com.rk.file.createFileIfNot
import com.rk.file.localBinDir
import com.rk.file.localDir
import com.rk.file.sandboxDir
import com.rk.utils.application

fun setupTerminalFiles() {
    if (sandboxDir().exists().not() || localBinDir().exists().not()) return

    with(localDir().child("stat")) {
        if (exists().not()) {
            createFileIfNot()
            writeText(stat)
        }
    }

    with(localDir().child("vmstat")) {
        if (exists().not()) {
            createFileIfNot()
            writeText(vmstat)
        }
    }

    with(localBinDir().child("termux-x11")) {
        if (exists().not()) {
            createFileIfNot()
            writeText(application!!.assets.open("terminal/termux-x11.sh").bufferedReader().use { it.readText() })
        }
    }

    val internalFiles = listOf("init", "sandbox", "setup", "utils", "gemini-cli", "gemini-cli-headless", "opencode-cli", "opencode-cli-headless", "antigravity-cli", "antigravity-cli-headless", "codex-cli", "codex-cli-headless", "claude-cli", "claude-cli-headless", "xdg-open", "vim", "code")
    internalFiles.forEach { setupAssetFile(it) }

    application!!.assets.list("terminal/lsp")?.forEach { setupLspFile(it.removeSuffix(".sh")) }

    listOf("apt", "apt-get", "dpkg").forEach {
        val f = localBinDir().child(it)
        if (f.exists()) {
            try {
                if (f.readText().contains(".xed-fixup")) f.delete()
            } catch (_: Exception) {}
        }
    }

    repairRootfsBashrc()
}

/**
 * One-time repair: Ubuntu's /etc/bash.bashrc calls bare `groups` for the sudo
 * hint. Guest coreutils may lack it → falls through to host /system/bin/groups
 * which fails under proot on some kernels. Guard it (idempotent).
 */
fun repairRootfsBashrc() {
    try {
        val f = sandboxDir().child("etc/bash.bashrc")
        if (!f.exists() || !f.isFile) return
        val text = f.readText()
        if ("groups 2>/dev/null || id -Gn" in text) return // already patched
        val patched = text.replace(
            "case \" $(groups) \" in",
            "case \" $(groups 2>/dev/null || id -Gn 2>/dev/null || echo) \" in",
        )
        if (patched != text) f.writeText(patched)
    } catch (_: Exception) {}
}

fun setupLspFile(fileName: String) = setupAssetFile("lsp/$fileName")

fun setupAssetFile(fileName: String) {
    with(localBinDir().child(fileName)) {
        parentFile?.mkdir()
        val assetText = application!!.assets.open("terminal/$fileName.sh").bufferedReader().use { it.readText() }
        if (exists().not() || readText() != assetText) {
            createFileIfNot()
            writeText(assetText)
        }
    }
}
