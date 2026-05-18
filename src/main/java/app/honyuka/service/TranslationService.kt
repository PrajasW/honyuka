package app.honyuka.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import app.honyuka.MainActivity
import app.honyuka.R
import app.honyuka.data.HonyukaPreferences
import app.honyuka.data.HonyukaStorage
import app.honyuka.export.RenderedChapterExporter
import app.honyuka.model.ChapterEntry
import app.honyuka.model.SourceLanguage
import app.honyuka.model.TargetLanguage
import app.honyuka.model.TranslationEngine
import app.honyuka.translation.TranslationPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TranslationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var translationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val chapter = intent?.toChapterEntry()
        if (chapter == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val sourceLang = intent.getStringExtra(EXTRA_SOURCE_LANG)
            ?.let { name -> SourceLanguage.entries.firstOrNull { it.name == name } }
            ?: SourceLanguage.JAPANESE
        val targetLang = intent.getStringExtra(EXTRA_TARGET_LANG)
            ?.let { name -> TargetLanguage.entries.firstOrNull { it.name == name } }
            ?: TargetLanguage.ENGLISH
        val engine = intent.getStringExtra(EXTRA_ENGINE)
            ?.let { name -> TranslationEngine.entries.firstOrNull { it.name == name } }
            ?: TranslationEngine.GEMINI
        val geminiApiKey = intent.getStringExtra(EXTRA_GEMINI_API_KEY).orEmpty()
        val geminiModelName = intent.getStringExtra(EXTRA_GEMINI_MODEL).orEmpty()
        val deepLApiKey = intent.getStringExtra(EXTRA_DEEPL_API_KEY).orEmpty()

        startForeground(NOTIFICATION_ID, buildNotification("Starting…", chapter.chapterDisplayName, 0))

        translationJob?.cancel()
        translationJob = scope.launch {
            runTranslation(chapter, sourceLang, targetLang, engine, geminiApiKey, geminiModelName, deepLApiKey)
        }

        return START_NOT_STICKY
    }

    private suspend fun runTranslation(
        chapter: ChapterEntry,
        sourceLanguage: SourceLanguage,
        targetLanguage: TargetLanguage,
        translationEngine: TranslationEngine,
        geminiApiKey: String,
        geminiModelName: String,
        deepLApiKey: String,
    ) {
        val preferences = HonyukaPreferences(this)
        val storage = HonyukaStorage(this, preferences)
        val pipeline = TranslationPipeline(this)
        val exporter = RenderedChapterExporter(this, storage)

        try {
            val chapterDocument = withContext(Dispatchers.IO) {
                storage.resolveOriginalChapterDocument(chapter)
            } ?: run {
                updateNotification("Error", "Could not open chapter", 0)
                broadcastResult(chapter, success = false, error = "Unable to open chapter")
                stopSelf()
                return
            }

            val translations = pipeline.translateChapter(
                chapter = chapter,
                chapterDocument = chapterDocument,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                translationEngine = translationEngine,
                geminiApiKey = geminiApiKey,
                geminiModelName = geminiModelName,
                deepLApiKey = deepLApiKey,
            ) { title, detail, progress ->
                updateNotification(title, detail, (progress * 100).toInt())
                broadcastProgress(chapter, title, detail, progress)
            }

            updateNotification("Saving translation", chapter.chapterDisplayName, 95)
            storage.writeTranslation(chapter, translations)

            updateNotification("Rendering chapter", chapter.chapterDisplayName, 97)
            exporter.exportTranslatedSibling(chapter, translations)

            updateNotification("Exporting to Local Source", chapter.chapterDisplayName, 99)
            exporter.exportToLocalSource(chapter, translations)

            updateNotification("Completed ✓", chapter.chapterDisplayName, 100)
            broadcastResult(chapter, success = true)
        } catch (e: Exception) {
            Log.e(TAG, "Translation failed for ${chapter.storageKey}", e)
            updateNotification("Translation failed", e.message ?: "Unknown error", 0)
            broadcastResult(chapter, success = false, error = e.message)
        } finally {
            stopSelf()
        }
    }

    private fun updateNotification(title: String, detail: String, progress: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(title, detail, progress))
    }

    private fun buildNotification(title: String, detail: String, progress: Int): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(detail)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(progress < 100)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress.coerceIn(0, 100), false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    private fun broadcastProgress(chapter: ChapterEntry, title: String, detail: String, progress: Float) {
        sendBroadcast(Intent(ACTION_PROGRESS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STORAGE_KEY, chapter.storageKey)
            putExtra(EXTRA_PROGRESS_TITLE, title)
            putExtra(EXTRA_PROGRESS_DETAIL, detail)
            putExtra(EXTRA_PROGRESS_VALUE, progress)
        })
    }

    private fun broadcastResult(chapter: ChapterEntry, success: Boolean, error: String? = null) {
        sendBroadcast(Intent(ACTION_RESULT).apply {
            setPackage(packageName)
            putExtra(EXTRA_STORAGE_KEY, chapter.storageKey)
            putExtra(EXTRA_RESULT_SUCCESS, success)
            if (error != null) putExtra(EXTRA_RESULT_ERROR, error)
        })
    }

    private fun ensureNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Translation Progress",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows translation progress for manga chapters"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TranslationService"
        private const val CHANNEL_ID = "honyuka_translation"
        private const val NOTIFICATION_ID = 42

        const val ACTION_PROGRESS = "app.honyuka.TRANSLATION_PROGRESS"
        const val ACTION_RESULT = "app.honyuka.TRANSLATION_RESULT"
        const val EXTRA_STORAGE_KEY = "storage_key"
        const val EXTRA_PROGRESS_TITLE = "progress_title"
        const val EXTRA_PROGRESS_DETAIL = "progress_detail"
        const val EXTRA_PROGRESS_VALUE = "progress_value"
        const val EXTRA_RESULT_SUCCESS = "result_success"
        const val EXTRA_RESULT_ERROR = "result_error"

        private const val EXTRA_SOURCE_NAME = "source_name"
        private const val EXTRA_MANGA_NAME = "manga_name"
        private const val EXTRA_CHAPTER_DISPLAY = "chapter_display"
        private const val EXTRA_CHAPTER_BASE = "chapter_base"
        private const val EXTRA_CHAPTER_URI = "chapter_uri"
        private const val EXTRA_THUMBNAIL_URI = "thumbnail_uri"
        private const val EXTRA_IS_CBZ = "is_cbz"
        private const val EXTRA_TRANSLATED = "translated"
        private const val EXTRA_SOURCE_LANG = "source_lang"
        private const val EXTRA_TARGET_LANG = "target_lang"
        private const val EXTRA_ENGINE = "engine"
        private const val EXTRA_GEMINI_API_KEY = "gemini_api_key"
        private const val EXTRA_GEMINI_MODEL = "gemini_model"
        private const val EXTRA_DEEPL_API_KEY = "deepl_api_key"

        fun buildIntent(
            context: Context,
            chapter: ChapterEntry,
            sourceLanguage: SourceLanguage,
            targetLanguage: TargetLanguage,
            translationEngine: TranslationEngine,
            geminiApiKey: String,
            geminiModelName: String,
            deepLApiKey: String,
        ): Intent {
            return Intent(context, TranslationService::class.java).apply {
                putExtra(EXTRA_SOURCE_NAME, chapter.sourceName)
                putExtra(EXTRA_MANGA_NAME, chapter.mangaName)
                putExtra(EXTRA_CHAPTER_DISPLAY, chapter.chapterDisplayName)
                putExtra(EXTRA_CHAPTER_BASE, chapter.chapterBaseName)
                putExtra(EXTRA_CHAPTER_URI, chapter.chapterUri.toString())
                putExtra(EXTRA_THUMBNAIL_URI, chapter.thumbnailUri?.toString())
                putExtra(EXTRA_IS_CBZ, chapter.isCbz)
                putExtra(EXTRA_TRANSLATED, chapter.translated)
                putExtra(EXTRA_SOURCE_LANG, sourceLanguage.name)
                putExtra(EXTRA_TARGET_LANG, targetLanguage.name)
                putExtra(EXTRA_ENGINE, translationEngine.name)
                putExtra(EXTRA_GEMINI_API_KEY, geminiApiKey)
                putExtra(EXTRA_GEMINI_MODEL, geminiModelName)
                putExtra(EXTRA_DEEPL_API_KEY, deepLApiKey)
            }
        }

        private fun Intent.toChapterEntry(): ChapterEntry? {
            val sourceName = getStringExtra(EXTRA_SOURCE_NAME) ?: return null
            val mangaName = getStringExtra(EXTRA_MANGA_NAME) ?: return null
            val chapterDisplay = getStringExtra(EXTRA_CHAPTER_DISPLAY) ?: return null
            val chapterBase = getStringExtra(EXTRA_CHAPTER_BASE) ?: return null
            val chapterUri = getStringExtra(EXTRA_CHAPTER_URI)?.let { android.net.Uri.parse(it) } ?: return null
            val thumbnailUri = getStringExtra(EXTRA_THUMBNAIL_URI)?.let { android.net.Uri.parse(it) }
            return ChapterEntry(
                sourceName = sourceName,
                mangaName = mangaName,
                chapterDisplayName = chapterDisplay,
                chapterBaseName = chapterBase,
                chapterUri = chapterUri,
                thumbnailUri = thumbnailUri,
                isCbz = getBooleanExtra(EXTRA_IS_CBZ, false),
                translated = getBooleanExtra(EXTRA_TRANSLATED, false),
            )
        }
    }
}
