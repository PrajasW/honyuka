package app.honyuka.data

import android.content.Context
import android.net.Uri
import app.honyuka.model.GeminiModelPreset
import app.honyuka.model.LocalBackend
import app.honyuka.model.LocalModel
import app.honyuka.model.ThemeMode
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
        val saved = prefs.getString(KEY_GEMINI_MODEL_NAME, null)?.trim()
        // One-time migration: move users off presets from older builds. Done once so a
        // model the user later picks from the fetched list is never overridden.
        if (!prefs.getBoolean(KEY_MODEL_PRESETS_MIGRATED, false)) {
            prefs.edit().putBoolean(KEY_MODEL_PRESETS_MIGRATED, true).apply()
            if (saved != null && saved in GeminiModelPreset.RETIRED_MODEL_NAMES) {
                saveGeminiModelName(GeminiModelPreset.DEFAULT.modelName)
                return GeminiModelPreset.DEFAULT.modelName
            }
        }
        return saved?.takeIf { it.isNotBlank() } ?: GeminiModelPreset.DEFAULT.modelName
    }

    fun availableGeminiModels(): List<String> {
        return prefs.getString(KEY_GEMINI_MODEL_LIST, null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            .orEmpty()
    }

    fun saveAvailableGeminiModels(models: List<String>) {
        prefs.edit().putString(KEY_GEMINI_MODEL_LIST, models.joinToString("\n")).apply()
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

    fun localModel(): LocalModel = enumPref(KEY_LOCAL_MODEL, LocalModel.DEFAULT)

    fun saveLocalModel(model: LocalModel) {
        prefs.edit().putString(KEY_LOCAL_MODEL, model.name).apply()
    }

    fun localBackend(): LocalBackend = enumPref(KEY_LOCAL_BACKEND, LocalBackend.AUTO)

    fun saveLocalBackend(backend: LocalBackend) {
        prefs.edit().putString(KEY_LOCAL_BACKEND, backend.name).apply()
    }

    fun themeMode(): ThemeMode = enumPref(KEY_THEME_MODE, ThemeMode.AUTO)

    fun saveThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    /** DownloadManager id of an in-progress model download, or -1. */
    fun localModelDownloadId(model: LocalModel): Long = prefs.getLong(KEY_DOWNLOAD_ID_PREFIX + model.name, -1L)

    fun saveLocalModelDownloadId(model: LocalModel, id: Long) {
        prefs.edit().putLong(KEY_DOWNLOAD_ID_PREFIX + model.name, id).apply()
    }

    private inline fun <reified T : Enum<T>> enumPref(key: String, default: T): T {
        val saved = prefs.getString(key, null) ?: return default
        return enumValues<T>().firstOrNull { it.name == saved } ?: default
    }

    private companion object {
        const val KEY_ROOT_URI = "root_uri"
        const val KEY_SOURCE_LANGUAGE = "source_language"
        const val KEY_TARGET_LANGUAGE = "target_language"
        const val KEY_TRANSLATION_ENGINE = "translation_engine"
        const val KEY_GEMINI_MODEL_NAME = "gemini_model_name"
        const val KEY_GEMINI_API_KEY = "gemini_api_key"
        const val KEY_GEMINI_MODEL_LIST = "gemini_model_list"
        const val KEY_MODEL_PRESETS_MIGRATED = "model_presets_migrated_v2"
        const val KEY_LOCAL_MODEL = "local_model"
        const val KEY_LOCAL_BACKEND = "local_backend"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_DOWNLOAD_ID_PREFIX = "local_model_download_"
        const val KEY_DEEPL_API_KEY = "deepl_api_key"
    }
}
