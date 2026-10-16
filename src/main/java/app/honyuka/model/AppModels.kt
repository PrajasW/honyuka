package app.honyuka.model

import android.net.Uri
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.serialization.Serializable

enum class SourceLanguage(
    val label: String,
    val ocrCode: String,
    val languageTag: String,
) {
    CHINESE("Chinese", TranslateLanguage.CHINESE, "zh-CN"),
    JAPANESE("Japanese", TranslateLanguage.JAPANESE, "ja"),
    KOREAN("Korean", TranslateLanguage.KOREAN, "ko"),
    ENGLISH("English", TranslateLanguage.ENGLISH, "en"),
}

enum class TargetLanguage(
    val label: String,
    val languageTag: String,
) {
    ENGLISH("English", "en"),
    HINDI("Hindi", "hi"),
    INDONESIAN("Indonesian", "id"),
    SPANISH("Spanish", "es"),
    FRENCH("French", "fr"),
    GERMAN("German", "de"),
    PORTUGUESE("Portuguese", "pt"),
    ITALIAN("Italian", "it"),
    KOREAN("Korean", "ko"),
    JAPANESE("Japanese", "ja"),
    THAI("Thai", "th"),
    VIETNAMESE("Vietnamese", "vi"),
}

enum class TranslationEngine(
    val label: String,
    val description: String,
    val requiresApiKey: Boolean = false,
) {
    GEMINI("Gemini", "Best quality for manga dialogue", requiresApiKey = true),
    DEEPL("DeepL API", "High-quality API translation; requires internet", requiresApiKey = true),
    GOOGLE_TRANSLATE("Google Translate", "Fast web translation"),
    MLKIT("ML Kit Offline", "On-device fallback"),
}

enum class GeminiModelPreset(
    val label: String,
    val modelName: String,
    val description: String,
) {
    // `-latest` aliases are maintained by Google and always point at the newest
    // model in each tier, so these presets don't go stale between releases.
    FLASH_LITE_LATEST(
        label = "Flash-Lite (latest)",
        modelName = "gemini-flash-lite-latest",
        description = "Cheapest, largest free daily quota — recommended for manga dialogue",
    ),
    FLASH_LATEST(
        label = "Flash (latest)",
        modelName = "gemini-flash-latest",
        description = "Better nuance, smaller free quota",
    ),
    PRO_LATEST(
        label = "Pro (latest)",
        modelName = "gemini-pro-latest",
        description = "Highest quality, slowest and most expensive — little or no free quota",
    ),
    ;

    companion object {
        val DEFAULT = FLASH_LITE_LATEST

        /** Hard-coded model names shipped by older builds; migrated to [DEFAULT]. */
        val RETIRED_MODEL_NAMES = setOf(
            "gemini-2.0-flash",
            "gemini-2.5-flash",
            "gemini-2.5-flash-lite",
            "gemini-2.5-pro",
            "gemini-3-flash-preview",
            "gemini-3.5-flash",
        )
    }
}

data class ChapterEntry(
    val sourceName: String,
    val mangaName: String,
    val chapterDisplayName: String,
    val chapterBaseName: String,
    val chapterUri: Uri,
    val thumbnailUri: Uri?,
    val isCbz: Boolean,
    val translated: Boolean,
) {
    val storageKey: String = "$sourceName/$mangaName/$chapterBaseName"
}

data class ActiveJob(
    val title: String,
    val detail: String,
    val progress: Float,
)

data class HonyukaUiState(
    val rootSummary: String? = null,
    val hasCompatibleRoot: Boolean = false,
    val sourceLanguage: SourceLanguage = SourceLanguage.JAPANESE,
    val targetLanguage: TargetLanguage = TargetLanguage.ENGLISH,
    val translationEngine: TranslationEngine = TranslationEngine.GEMINI,
    val geminiModelName: String = GeminiModelPreset.DEFAULT.modelName,
    val availableGeminiModels: List<String> = emptyList(),
    val modelListStatus: String? = null,
    val geminiApiKey: String = "",
    val deepLApiKey: String = "",
    val chapters: List<ChapterEntry> = emptyList(),
    val activeJob: ActiveJob? = null,
    val activeChapterKey: String? = null,
    val isBusy: Boolean = false,
    val message: String? = null,
    val apiTestStatus: String? = null,
    val confirmationRequest: ConfirmationRequest? = null,
)

data class ConfirmationRequest(
    val chapter: ChapterEntry,
    val chunkCount: Int,
    val blockCount: Int,
)

@Serializable
data class PageTranslation(
    var blocks: MutableList<TranslationBlock> = mutableListOf(),
    var imgWidth: Float = 0f,
    var imgHeight: Float = 0f,
)

@Serializable
data class TranslationBlock(
    var text: String,
    var translation: String = "",
    var width: Float,
    var height: Float,
    var x: Float,
    var y: Float,
    var symHeight: Float,
    var symWidth: Float,
    val angle: Float,
)
