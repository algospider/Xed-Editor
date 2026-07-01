package com.rk.editor

import android.content.Context
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.rk.settings.Settings
import com.rk.theme.currentTheme
import com.rk.utils.isDarkTheme
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.tm4e.core.registry.IThemeSource

private var selectionColor: Color? = null

@Suppress("ComposableNaming")
@Composable
fun preloadSelectionColor() {
    val selectionColors = LocalTextSelectionColors.current
    val selectionBackground = selectionColors.backgroundColor
    selectionColor = selectionBackground
}

fun getSelectionColor(): Color? {
    return selectionColor
}

object ThemeManager {
    private val colorSchemeCache = ConcurrentHashMap<String, TextMateColorScheme>()

    suspend fun createColorScheme(context: Context, patchArgs: Editor.PatchArgs?) =
        withContext(Dispatchers.IO) {
            val cacheKey = getCacheKey(context)

            colorSchemeCache[cacheKey]?.let {
                return@withContext it
            }

            val darkTheme = isDarkTheme(context)
            val amoled = Settings.amoled

            val themeModel =
                when {
                    darkTheme && amoled ->
                        buildThemeModel(
                            context = context,
                            basePath = TEXTMATE_AMOLED_PREFIX + DARCULA_THEME,
                            baseName = DARCULA_THEME,
                            darkTheme = true,
                        )
                    darkTheme ->
                        buildThemeModel(
                            context = context,
                            basePath = TEXTMATE_PREFIX + DARCULA_THEME,
                            baseName = DARCULA_THEME,
                            darkTheme = true,
                        )
                    else ->
                        buildThemeModel(
                            context = context,
                            basePath = TEXTMATE_PREFIX + QUIETLIGHT_THEME,
                            baseName = QUIETLIGHT_THEME,
                            darkTheme = false,
                        )
                }

            XedColorScheme(patchArgs, themeModel).also {
                colorSchemeCache[cacheKey] = it
                return@withContext it
            }
        }

    fun createColorSchemeBlocking(context: Context, patchArgs: Editor.PatchArgs?): TextMateColorScheme {
        val cacheKey = getCacheKey(context)
        colorSchemeCache[cacheKey]?.let { return it }

        val darkTheme = isDarkTheme(context)
        val amoled = Settings.amoled

        val themeModel =
            when {
                darkTheme && amoled ->
                    buildThemeModelBlocking(
                        context = context,
                        basePath = TEXTMATE_AMOLED_PREFIX + DARCULA_THEME,
                        baseName = DARCULA_THEME,
                        darkTheme = true,
                    )
                darkTheme ->
                    buildThemeModelBlocking(
                        context = context,
                        basePath = TEXTMATE_PREFIX + DARCULA_THEME,
                        baseName = DARCULA_THEME,
                        darkTheme = true,
                    )
                else ->
                    buildThemeModelBlocking(
                        context = context,
                        basePath = TEXTMATE_PREFIX + QUIETLIGHT_THEME,
                        baseName = QUIETLIGHT_THEME,
                        darkTheme = false,
                    )
            }

        return XedColorScheme(patchArgs, themeModel).also { colorSchemeCache[cacheKey] = it }
    }

    private suspend fun buildThemeModel(context: Context, basePath: String, baseName: String, darkTheme: Boolean) =
        withContext(Dispatchers.IO) {
            buildThemeModelSync(context, basePath, baseName, darkTheme)
        }

    private fun buildThemeModelBlocking(context: Context, basePath: String, baseName: String, darkTheme: Boolean): ThemeModel {
        return buildThemeModelSync(context, basePath, baseName, darkTheme)
    }

    private fun buildThemeModelSync(context: Context, basePath: String, baseName: String, darkTheme: Boolean): ThemeModel {
        val inputStream = context.assets.open(basePath)
        return InputStreamReader(inputStream).use { reader ->
            val jsonElement = JsonParser.parseReader(reader)
            val jsonObject = jsonElement.asJsonObject

            val selectedTheme = currentTheme.value
            val tokenArray =
                when {
                    selectedTheme == null -> JsonArray()
                    darkTheme -> selectedTheme.darkTokenColors
                    else -> selectedTheme.lightTokenColors
                }

            val arrayName =
                when {
                    jsonObject.has("settings") -> "settings"
                    jsonObject.has("tokenColors") -> "tokenColors"
                    else -> null
                }

            selectedTheme?.let { jsonObject.add("name", JsonPrimitive(it.name)) }

            if (!tokenArray.isEmpty) {
                if (arrayName != null) {
                    if (selectedTheme?.inheritBase == true) {
                        val existingTokenColors = jsonObject[arrayName].asJsonArray
                        existingTokenColors.addAll(tokenArray)
                    } else {
                        jsonObject.remove(arrayName)
                        jsonObject.add(arrayName, tokenArray)
                    }
                } else {
                    jsonObject.add("tokenColors", tokenArray)
                }
            }

            val bytes = jsonObject.toString().toByteArray(Charsets.UTF_8)
            val bais = ByteArrayInputStream(bytes)
            ThemeModel(IThemeSource.fromInputStream(bais, baseName, null))
        }
    }
}
