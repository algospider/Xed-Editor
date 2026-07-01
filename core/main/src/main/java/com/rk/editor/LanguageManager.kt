package com.rk.editor

import com.rk.utils.application
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred

object LanguageManager {
    private val grammarRegistryInitialized = CompletableDeferred<Unit>()
    private val languageCache = ConcurrentHashMap<String, TextMateLanguage>()

    suspend fun initGrammarRegistry() {
        if (grammarRegistryInitialized.isCompleted) return

        FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(application!!.assets))
        GrammarRegistry.getInstance().loadGrammars(TEXTMATE_PREFIX + LANGUAGES_FILE)

        grammarRegistryInitialized.complete(Unit)
    }

    suspend fun createLanguage(textmateScope: String, createIdentifiers: Boolean = true): TextMateLanguage {
        val cacheKey = "$textmateScope|$createIdentifiers"
        languageCache[cacheKey]?.let { return it }

        grammarRegistryInitialized.await()

        val language = TextMateLanguage.create(textmateScope, createIdentifiers)
        languageCache[cacheKey] = language
        return language
    }

    fun createLanguageBlocking(textmateScope: String, createIdentifiers: Boolean = true): TextMateLanguage {
        val cacheKey = "$textmateScope|$createIdentifiers"
        languageCache[cacheKey]?.let { return it }

        if (!grammarRegistryInitialized.isCompleted) {
            FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(application!!.assets))
            GrammarRegistry.getInstance().loadGrammars(TEXTMATE_PREFIX + LANGUAGES_FILE)
            grammarRegistryInitialized.complete(Unit)
        }

        val language = TextMateLanguage.create(textmateScope, createIdentifiers)
        languageCache[cacheKey] = language
        return language
    }
}
