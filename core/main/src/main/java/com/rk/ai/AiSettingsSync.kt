@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)
package com.rk.ai

import android.content.Context
import com.rk.ai.nativeagent.VibeCodingProvider
import com.rk.ai.providers.ProviderSetting
import com.rk.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object AiSettingsSync {
    /**
     * Synchronizes an API key across Xed Editor settings and active VibeCoding engine.
     */
    fun syncApiKey(apiKey: String, scope: CoroutineScope = CoroutineScope(Dispatchers.IO)) {
        val trimmed = apiKey.trim()
        Settings.ai_api_key = trimmed

        val engine = VibeCodingProvider.engineRef.get() ?: return
        scope.launch {
            try {
                engine.settingsStore.update { s ->
                    val updated = s.providers.map { provider ->
                        if (provider is ProviderSetting.Google) {
                            provider.copy(apiKey = trimmed, enabled = true)
                        } else provider
                    }
                    s.copy(providers = updated)
                }
            } catch (_: Exception) {}
        }
    }
}
