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
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import app.honyuka.MainActivity
import app.honyuka.R
import app.honyuka.data.HonyukaPreferences
import app.honyuka.data.HonyukaStorage
import app.honyuka.export.RenderedChapterExporter
import app.honyuka.model.ChapterEntry
import app.honyuka.model.LocalBackend
import app.honyuka.model.LocalModel
import app.honyuka.model.PageTranslation
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
        val geminiModelName = intent.getStringExtra(EXTRA_GEMINI_MODEL) ?: ""
        val deepLApiKey = intent.getStringExtra(EXTRA_DEEPL_API_KEY) ?: ""
        val localModel = intent.getStringExtra(EXTRA_LOCAL_MODEL)
            ?.let { name -> LocalModel.entries.firstOrNull { it.name == name } }
            ?: LocalModel.DEFAULT
        val localBackend = intent.getStringExtra(EXTRA_LOCAL_BACKEND)
            ?.let { name -> LocalBackend.entries.firstOrNull { it.name == name } }
            ?: LocalBackend.AUTO
        val rerenderOnly = intent.getBooleanExtra(EXTRA_RERENDER_ONLY, false)
        val isConfirmed = intent.getBooleanExtra(EXTRA_CONFIRMED, false)

        startForeground(NOTIFICATION_ID, buildNotification(
            if (rerenderOnly) "Re-rendering…" else "Starting…",
            chapter.chapterDisplayName, 0,
        ))

        translationJob?.cancel()
        translationJob = scope.launch {
            runTranslation(
                chapter, sourceLang, targetLang, engine, geminiApiKey, geminiModelName, deepLApiKey,
                localModel, localBackend, rerenderOnly, isConfirmed,
            )
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
        localModel: LocalModel,
        localBackend: LocalBackend,
        rerenderOnly: Boolean,
        isConfirmed: Boolean,
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

            val saved = storage.readTranslation(chapter)
            var translations = saved

            val hasValidSidecar = !saved.isNullOrEmpty() && saved.values.any { it.blocks.isNotEmpty() }

            if (rerenderOnly) {
                updateNotification("Loading translations", chapter.chapterDisplayName, 10)
                broadcastProgress(chapter, "Loading translations", "Reading saved translation data", 0.1f)
                if (!hasValidSidecar) {
                    updateNotification("Error", "No saved translation found", 0)
                    broadcastResult(chapter, success = false, error = "No saved translation found for this chapter. Translate it first.")
                    stopSelf()
                    return
                }
            } else {
                if (!hasValidSidecar) {
                    translations = pipeline.extractText(
                        chapter = chapter,
                        chapterDocument = chapterDocument,
                        sourceLanguage = sourceLanguage,
                    ) { title, detail, progress ->
                        updateNotification(title, detail, (progress * 100).toInt())
                        broadcastProgress(chapter, title, detail, progress)
                    }
                    // Save the sidecar immediately to cache the OCR results
                    storage.writeTranslation(chapter, translations)
                }

                val needsTranslation = translations!!.values.any { page -> 
                    page.blocks.any { it.translation.isBlank() } 
                }

                if (needsTranslation) {
                    if (translationEngine == TranslationEngine.GEMINI && !isConfirmed) {
                        // Only lines that will really be sent are counted (skips translated,
                        // duplicate, watermark and remembered lines).
                        val (chunks, blocks) = pipeline.estimateGeminiChunks(
                            pages = translations,
                            sourceLanguage = sourceLanguage,
                            targetLanguage = targetLanguage,
                            geminiModelName = geminiModelName,
                        )
                        if (chunks > 0) {
                            sendBroadcast(Intent(ACTION_REQUIRE_CONFIRMATION).apply {
                                setPackage(packageName)
                                putExtra(EXTRA_STORAGE_KEY, chapter.storageKey)
                                putExtra(EXTRA_CHUNK_COUNT, chunks)
                                putExtra(EXTRA_BLOCK_COUNT, blocks)
                            })
                            return
                        }
                    }

                    val mutableTranslations = translations as MutableMap<String, PageTranslation>
                    // On-device inference can take many minutes; keep the CPU awake meanwhile.
                    val wakeLock = if (translationEngine == TranslationEngine.ON_DEVICE) {
                        (getSystemService(Context.POWER_SERVICE) as PowerManager)
                            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Honyuka:OnDeviceTranslation")
                            .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
                    } else {
                        null
                    }
                    try {
                    pipeline.translateText(
                        pageTranslations = mutableTranslations,
                        sourceLanguage = sourceLanguage,
                        targetLanguage = targetLanguage,
                        translationEngine = translationEngine,
                        geminiApiKey = geminiApiKey,
                        geminiModelName = geminiModelName,
                        deepLApiKey = deepLApiKey,
                        localModel = localModel,
                        localBackend = localBackend,
                        // Save after every request so a later failure never loses paid-for work.
                        onChunkSaved = { storage.writeTranslation(chapter, mutableTranslations) },
                    ) { title, detail, progress ->
                        updateNotification(title, detail, (progress * 100).toInt())
                        broadcastProgress(chapter, title, detail, progress)
                    }
                    } finally {
                        wakeLock?.takeIf { it.isHeld }?.release()
                    }
                    updateNotification("Saving translation", chapter.chapterDisplayName, 95)
                    storage.writeTranslation(chapter, translations)
                }
            }

            updateNotification("Exporting to Local Source", chapter.chapterDisplayName, 97)
            broadcastProgress(chapter, "Rendering chapter", "Writing pages to the Local Source", 0.97f)
            exporter.exportToLocalSource(
                chapter = chapter,
                translations = translations!!,
                translatorLabel = if (rerenderOnly) null else translationEngine.label,
                targetLanguage = if (rerenderOnly) null else targetLanguage,
            )

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
        const val ACTION_REQUIRE_CONFIRMATION = "app.honyuka.TRANSLATION_REQUIRE_CONFIRMATION"
        const val EXTRA_STORAGE_KEY = "storage_key"
        const val EXTRA_PROGRESS_TITLE = "progress_title"
        const val EXTRA_PROGRESS_DETAIL = "progress_detail"
        const val EXTRA_PROGRESS_VALUE = "progress_value"
        const val EXTRA_RESULT_SUCCESS = "result_success"
        const val EXTRA_RESULT_ERROR = "result_error"
        const val EXTRA_CHUNK_COUNT = "chunk_count"
        const val EXTRA_BLOCK_COUNT = "block_count"

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
        private const val EXTRA_LOCAL_MODEL = "local_model"
        private const val EXTRA_LOCAL_BACKEND = "local_backend"
        private const val WAKE_LOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L
        private const val EXTRA_RERENDER_ONLY = "rerender_only"
        private const val EXTRA_CONFIRMED = "confirmed"

        fun buildIntent(
            context: Context,
            chapter: ChapterEntry,
            sourceLanguage: SourceLanguage,
            targetLanguage: TargetLanguage,
            translationEngine: TranslationEngine,
            geminiApiKey: String,
            geminiModelName: String,
            deepLApiKey: String,
            localModel: LocalModel = LocalModel.DEFAULT,
            localBackend: LocalBackend = LocalBackend.AUTO,
            confirmed: Boolean = false,
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
                putExtra(EXTRA_LOCAL_MODEL, localModel.name)
                putExtra(EXTRA_LOCAL_BACKEND, localBackend.name)
                putExtra(EXTRA_CONFIRMED, confirmed)
            }
        }

        fun buildRerenderIntent(
            context: Context,
            chapter: ChapterEntry,
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
                putExtra(EXTRA_RERENDER_ONLY, true)
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
