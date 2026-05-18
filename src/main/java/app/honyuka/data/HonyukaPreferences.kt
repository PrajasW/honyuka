package app.honyuka.data

import android.content.Context
import android.net.Uri
import app.honyuka.model.GeminiModelPreset
import app.honyuka.model.SourceLanguage
import app.honyuka.model.TargetLanguage
import app.honyuka.model.TranslationEngine

class HonyukaPreferences(context: Context) {

    private val prefs = context.getSharedPreferences("honyuka_prefs", Context.MODE_PRIVATE)

    fun rootUri(): Uri? = prefs.getString(KEY_ROOT_URI, null)?.let(Uri::parse)

    fun saveRootUri(uri: Uri) {
        prefs.edit().putString(KEY_ROOT_URI, uri.toString()).apply()
    }

    fun sourceLanguage(): SourceLanguage {
        val saved = prefs.getString(KEY_SOURCE_LANGUAGE, SourceLanguage.JAPANESE.name)
        return SourceLanguage.entries.firstOrNull { it.name == saved } ?: SourceLanguage.JAPANESE
    }

    fun saveSourceLanguage(language: SourceLanguage) {
        prefs.edit().putString(KEY_SOURCE_LANGUAGE, language.name).apply()
    }

    fun targetLanguage(): TargetLanguage {
        val saved = prefs.getString(KEY_TARGET_LANGUAGE, TargetLanguage.ENGLISH.name)
        return TargetLanguage.entries.firstOrNull { it.name == saved } ?: TargetLanguage.ENGLISH
    }

    fun saveTargetLanguage(language: TargetLanguage) {
        prefs.edit().putString(KEY_TARGET_LANGUAGE, language.name).apply()
    }

    fun translationEngine(): TranslationEngine {
        val saved = prefs.getString(KEY_TRANSLATION_ENGINE, TranslationEngine.GEMINI.name)
        return TranslationEngine.entries.firstOrNull { it.name == saved } ?: TranslationEngine.GEMINI
    }

    fun saveTranslationEngine(engine: TranslationEngine) {
        prefs.edit().putString(KEY_TRANSLATION_ENGINE, engine.name).apply()
    }

    fun geminiModelName(): String {
        return prefs.getString(KEY_GEMINI_MODEL_NAME, GeminiModelPreset.GEMINI_2_5_FLASH.modelName)
            ?.takeIf { it.isNotBlank() }
            ?: GeminiModelPreset.GEMINI_2_5_FLASH.modelName
    }

    fun saveGeminiModelName(modelName: String) {
        prefs.edit().putString(KEY_GEMINI_MODEL_NAME, modelName.trim()).apply()
    }

    fun geminiApiKey(): String = prefs.getString(KEY_GEMINI_API_KEY, "").orEmpty()

    fun saveGeminiApiKey(apiKey: String) {
        prefs.edit().putString(KEY_GEMINI_API_KEY, apiKey.trim()).apply()
    }

    fun deepLApiKey(): String = prefs.getString(KEY_DEEPL_API_KEY, "").orEmpty()

    fun saveDeepLApiKey(apiKey: String) {
        prefs.edit().putString(KEY_DEEPL_API_KEY, apiKey.trim()).apply()
    }

    private companion object {
        const val KEY_ROOT_URI = "root_uri"
        const val KEY_SOURCE_LANGUAGE = "source_language"
        const val KEY_TARGET_LANGUAGE = "target_language"
        const val KEY_TRANSLATION_ENGINE = "translation_engine"
        const val KEY_GEMINI_MODEL_NAME = "gemini_model_name"
        const val KEY_GEMINI_API_KEY = "gemini_api_key"
        const val KEY_DEEPL_API_KEY = "deepl_api_key"
    }
}
