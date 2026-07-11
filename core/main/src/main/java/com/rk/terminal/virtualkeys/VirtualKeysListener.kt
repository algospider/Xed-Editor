package com.rk.terminal.virtualkeys

import android.view.View
import android.widget.Button
import com.termux.terminal.TerminalSession

class VirtualKeysListener(val session: TerminalSession) : VirtualKeysView.IVirtualKeysView {

    override fun onVirtualKeyButtonClick(view: View?, buttonInfo: VirtualKeyButton?, button: Button?) {
        val bi = buttonInfo ?: return

        if (bi.isMacro) {
            val keys = bi.key.split(" ")
            var ctrl = false
            var alt = false
            var shift = false
            var fn = false
            for (key in keys) {
                when (key) {
                    "CTRL" -> ctrl = true
                    "ALT" -> alt = true
                    "SHIFT" -> shift = true
                    "FN" -> fn = true
                    else -> {
                        writeKey(key, ctrl, alt)
                        ctrl = false; alt = false; shift = false; fn = false
                    }
                }
            }
            return
        }

        writeKey(bi.key, false, false)
    }

    private fun writeKey(key: String, ctrl: Boolean, alt: Boolean) {
        // Ctrl+letter: send control code 1-26 (Ctrl+A = 0x01, ..., Ctrl+Z = 0x1A)
        if (ctrl && key.length == 1) {
            val c = key[0].lowercaseChar()
            if (c in 'a'..'z') {
                session.write((c - 'a' + 1).toChar().toString())
                return
            }
        }

        val mapped = mapSpecialKey(key)
        if (mapped != null) {
            session.write(mapped)
            return
        }

        session.write(key)
    }

    companion object {
        private fun mapSpecialKey(key: String): String? = when (key) {
            "UP" -> "\u001B[A"
            "DOWN" -> "\u001B[B"
            "LEFT" -> "\u001B[D"
            "RIGHT" -> "\u001B[C"
            "ENTER" -> "\u000D"
            "PGUP" -> "\u001B[5~"
            "PGDN" -> "\u001B[6~"
            "TAB" -> "\u0009"
            "HOME" -> "\u001B[H"
            "END" -> "\u001B[F"
            "ESC" -> "\u001B"
            else -> null
        }
    }

    override fun performVirtualKeyButtonHapticFeedback(
        view: View?,
        buttonInfo: VirtualKeyButton?,
        button: Button?,
    ): Boolean {
        return false
    }
}
