package com.rk

import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.rk.activities.main.MainActivity
import kotlinx.coroutines.CoroutineScope

val DefaultScope: CoroutineScope
    get() {
        return MainActivity.instance?.lifecycleScope ?: ProcessLifecycleOwner.get().lifecycleScope
    }
