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
    GEMINI_2_5_FLASH(
        label = "Gemini 2.5 Flash",
        modelName = "gemini-2.5-flash",
        description = "Fast, cheap, excellent for manga dialogue — recommended default",
    ),
    GEMINI_2_5_PRO(
        label = "Gemini 2.5 Pro",
        modelName = "gemini-2.5-pro",
        description = "Higher quality for nuanced translations, slower and more expensive",
    ),
    GEMINI_3_FLASH_PREVIEW(
        label = "Gemini 3 Flash Preview",
        modelName = "gemini-3-flash-preview",
        description = "Latest preview model — experimental",
    ),
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
    val geminiModelName: String = GeminiModelPreset.GEMINI_2_5_FLASH.modelName,
    val geminiApiKey: String = "",
    val deepLApiKey: String = "",
    val chapters: List<ChapterEntry> = emptyList(),
    val activeJob: ActiveJob? = null,
    val activeChapterKey: String? = null,
    val isBusy: Boolean = false,
    val message: String? = null,
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
