package app.honyuka.ui

import android.Manifest
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.honyuka.data.HonyukaPreferences
import app.honyuka.data.HonyukaStorage
import app.honyuka.model.ActiveJob
import app.honyuka.model.ChapterEntry
import app.honyuka.model.ConfirmationRequest
import app.honyuka.model.HonyukaUiState
import app.honyuka.model.SourceLanguage
import app.honyuka.model.TargetLanguage
import app.honyuka.model.TranslationEngine
import app.honyuka.service.TranslationService
import app.honyuka.translation.GeminiTestResult
import app.honyuka.translation.LocalModelManager
import app.honyuka.translation.fetchGeminiModels
import app.honyuka.model.LocalBackend
import app.honyuka.model.LocalModel
import app.honyuka.model.LocalModelStatus
import app.honyuka.model.ThemeMode
import kotlinx.coroutines.delay
import app.honyuka.translation.testGeminiConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HonyukaViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val preferences = HonyukaPreferences(application)
    private val storage = HonyukaStorage(application, preferences)

    private val _state = MutableStateFlow(
        HonyukaUiState(
            rootSummary = storage.rootSummary(),
            hasCompatibleRoot = storage.hasCompatibleRoot(),
            sourceLanguage = preferences.sourceLanguage(),
            targetLanguage = preferences.targetLanguage(),
            translationEngine = preferences.translationEngine(),
            geminiModelName = preferences.geminiModelName(),
            availableGeminiModels = preferences.availableGeminiModels(),
            localModel = preferences.localModel(),
            localBackend = preferences.localBackend(),
            themeMode = preferences.themeMode(),
            geminiApiKey = preferences.geminiApiKey(),
            deepLApiKey = preferences.deepLApiKey(),
        ),
    )
    val state: StateFlow<HonyukaUiState> = _state.asStateFlow()
    private val appContext = getApplication<Application>()

    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val title = intent.getStringExtra(TranslationService.EXTRA_PROGRESS_TITLE) ?: return
            val detail = intent.getStringExtra(TranslationService.EXTRA_PROGRESS_DETAIL) ?: return
            val progress = intent.getFloatExtra(TranslationService.EXTRA_PROGRESS_VALUE, 0f)
            _state.update {
                it.copy(activeJob = ActiveJob(title, detail, progress))
            }
        }
    }

    private val resultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val success = intent.getBooleanExtra(TranslationService.EXTRA_RESULT_SUCCESS, false)
            val error = intent.getStringExtra(TranslationService.EXTRA_RESULT_ERROR)
            val storageKey = intent.getStringExtra(TranslationService.EXTRA_STORAGE_KEY).orEmpty()

            if (success) {
                Toast.makeText(appContext, "Translation completed", Toast.LENGTH_SHORT).show()
                viewModelScope.launch {
                    val chapters = withContext(Dispatchers.IO) { storage.scanChapters() }
                    _state.update {
                        it.copy(
                            chapters = chapters,
                            isBusy = false,
                            activeChapterKey = null,
                            activeJob = ActiveJob("Completed", "Translation saved for $storageKey", 1f),
                            message = null,
                        )
                    }
                }
            } else {
                Toast.makeText(appContext, error ?: "Translation failed", Toast.LENGTH_LONG).show()
                _state.update {
                    it.copy(
                        isBusy = false,
                        activeChapterKey = null,
                        activeJob = null,
                        message = error ?: "Translation failed.",
                    )
                }
            }
        }
    }

    private val confirmationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val storageKey = intent.getStringExtra(TranslationService.EXTRA_STORAGE_KEY) ?: return
            val chunks = intent.getIntExtra(TranslationService.EXTRA_CHUNK_COUNT, 0)
            val blocks = intent.getIntExtra(TranslationService.EXTRA_BLOCK_COUNT, 0)
            
            val chapter = _state.value.chapters.find { it.storageKey == storageKey }
            if (chapter == null) {
                _state.update {
                    it.copy(isBusy = false, activeChapterKey = null, activeJob = null, message = "Chapter list changed — try again.")
                }
                return
            }
            _state.update {
                it.copy(
                    confirmationRequest = ConfirmationRequest(chapter, chunks, blocks),
                    activeJob = ActiveJob("Waiting for confirmation", "OCR completed", 0.65f),
                )
            }
        }
    }

    init {
        val progressFilter = IntentFilter(TranslationService.ACTION_PROGRESS)
        val resultFilter = IntentFilter(TranslationService.ACTION_RESULT)
        val confirmFilter = IntentFilter(TranslationService.ACTION_REQUIRE_CONFIRMATION)
        ContextCompat.registerReceiver(appContext, progressReceiver, progressFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(appContext, resultReceiver, resultFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(appContext, confirmationReceiver, confirmFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
        refreshLocalModelStatus()
        viewModelScope.launch {
            TranslationService.runStatus.collect { status ->
                _state.update {
                    it.copy(
                        runStatus = status,
                        isBusy = if (status != null) true else it.isBusy,
                        activeChapterKey = status?.chapterKey ?: it.activeChapterKey,
                    )
                }
            }
        }
        // Poll model downloads while any is running (DownloadManager has no push updates).
        viewModelScope.launch {
            while (true) {
                delay(1_000)
                if (_state.value.localModelStatus.values.any { it.downloading }) refreshLocalModelStatus()
            }
        }
    }

    private fun refreshLocalModelStatus() {
        viewModelScope.launch {
            val statuses = withContext(Dispatchers.IO) {
                LocalModel.entries.associateWith { LocalModelManager.status(appContext, it) }
            }
            _state.update { it.copy(localModelStatus = statuses) }
        }
    }

    fun onLocalModelSelected(model: LocalModel) {
        preferences.saveLocalModel(model)
        _state.update { it.copy(localModel = model) }
    }

    fun onLocalBackendSelected(backend: LocalBackend) {
        preferences.saveLocalBackend(backend)
        _state.update { it.copy(localBackend = backend) }
    }

    fun onThemeModeSelected(mode: ThemeMode) {
        preferences.saveThemeMode(mode)
        _state.update { it.copy(themeMode = mode) }
    }

    fun downloadLocalModel(model: LocalModel) {
        onLocalModelSelected(model)
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) { LocalModelManager.startDownload(appContext, model) }
            if (error != null) {
                _state.update {
                    it.copy(localModelStatus = it.localModelStatus + (model to LocalModelStatus(error = error)))
                }
            } else {
                refreshLocalModelStatus()
            }
        }
    }

    fun cancelLocalModelDownload(model: LocalModel) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { LocalModelManager.cancelDownload(appContext, model) }
            refreshLocalModelStatus()
        }
    }

    fun deleteLocalModel(model: LocalModel) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { LocalModelManager.delete(appContext, model) }
            refreshLocalModelStatus()
        }
    }

    override fun onCleared() {
        appContext.unregisterReceiver(progressReceiver)
        appContext.unregisterReceiver(resultReceiver)
        appContext.unregisterReceiver(confirmationReceiver)
        super.onCleared()
    }

    fun onRootSelected(uri: android.net.Uri) {
        storage.saveRoot(uri)
        _state.update {
            it.copy(
                rootSummary = storage.rootSummary(),
                hasCompatibleRoot = storage.hasCompatibleRoot(),
                message = null,
            )
        }
        refresh()
    }

    fun onSourceLanguageSelected(language: SourceLanguage) {
        preferences.saveSourceLanguage(language)
        _state.update { it.copy(sourceLanguage = language) }
    }

    fun onTargetLanguageSelected(language: TargetLanguage) {
        preferences.saveTargetLanguage(language)
        _state.update { it.copy(targetLanguage = language) }
    }

    fun onTranslationEngineSelected(engine: TranslationEngine) {
        preferences.saveTranslationEngine(engine)
        _state.update { it.copy(translationEngine = engine) }
    }

    fun onGeminiModelNameChanged(modelName: String) {
        preferences.saveGeminiModelName(modelName)
        _state.update { it.copy(geminiModelName = modelName, apiTestStatus = null) }
    }

    fun onGeminiApiKeyChanged(apiKey: String) {
        preferences.saveGeminiApiKey(apiKey)
        _state.update { it.copy(geminiApiKey = apiKey, apiTestStatus = null) }
    }

    fun onDeepLApiKeyChanged(apiKey: String) {
        preferences.saveDeepLApiKey(apiKey)
        _state.update { it.copy(deepLApiKey = apiKey) }
    }

    fun testGeminiApiKey() {
        val currentState = state.value
        _state.update { it.copy(apiTestStatus = "Testing…") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                testGeminiConnection(
                    apiKey = currentState.geminiApiKey,
                    modelName = currentState.geminiModelName,
                )
            }
            val statusText = when (result) {
                is GeminiTestResult.Success ->
                    "✅ Working — \"おい待て\" → \"${result.translatedText}\""
                is GeminiTestResult.Failure ->
                    "❌ Failed — ${result.message}"
            }
            _state.update { it.copy(apiTestStatus = statusText) }
        }
    }

    fun refreshGeminiModels() {
        val apiKey = state.value.geminiApiKey
        _state.update { it.copy(modelListStatus = "Loading models…") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { fetchGeminiModels(apiKey) }
            result.onSuccess { models ->
                preferences.saveAvailableGeminiModels(models)
                _state.update {
                    it.copy(
                        availableGeminiModels = models,
                        modelListStatus = "${models.size} models available for this key",
                    )
                }
            }.onFailure { error ->
                _state.update { it.copy(modelListStatus = "❌ ${error.message ?: "Could not load models"}") }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            Log.d(TAG, "Refreshing chapter list")
            _state.update {
                it.copy(
                    isBusy = true,
                    activeJob = ActiveJob(
                        title = "Scanning downloads",
                        detail = "Looking for reader-compatible chapter folders and CBZ archives",
                        progress = 0.15f,
                    ),
                    message = null,
                )
            }
            runCatching {
                withContext(Dispatchers.IO) { storage.scanChapters() }
            }.onSuccess { chapters ->
                _state.update {
                    it.copy(
                        chapters = chapters,
                        hasCompatibleRoot = storage.hasCompatibleRoot(),
                        isBusy = false,
                        activeJob = null,
                        activeChapterKey = null,
                        message = if (!storage.hasCompatibleRoot()) {
                            "The selected folder is missing a `downloads` directory. Pick the reader storage root, not an individual chapter folder."
                        } else {
                            null
                        },
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        isBusy = false,
                        activeJob = null,
                        activeChapterKey = null,
                        message = error.message ?: "Unable to scan the selected root.",
                    )
                }
                Log.e(TAG, "Refresh failed", error)
            }
        }
    }

    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun translateChapter(chapter: ChapterEntry) {
        val currentState = state.value
        val translationEngine = currentState.translationEngine
        val selectedApiKey = when (translationEngine) {
            TranslationEngine.GEMINI -> currentState.geminiApiKey
            TranslationEngine.DEEPL -> currentState.deepLApiKey
            else -> ""
        }
        if (translationEngine == TranslationEngine.ON_DEVICE &&
            !LocalModelManager.isDownloaded(appContext, currentState.localModel)
        ) {
            val message = "Download ${currentState.localModel.label} in Settings → On-device model first. 📥"
            Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
            _state.update { it.copy(message = message) }
            return
        }
        if (translationEngine.requiresApiKey && selectedApiKey.isBlank()) {
            val message = "Add a ${translationEngine.label} API key in Settings before using this translator."
            Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
            _state.update { it.copy(message = message) }
            return
        }

        Log.d(TAG, "Starting translation service for ${chapter.storageKey}")
        Toast.makeText(appContext, "Starting translation for ${chapter.chapterDisplayName}", Toast.LENGTH_SHORT).show()
        _state.update {
            it.copy(
                isBusy = true,
                activeChapterKey = chapter.storageKey,
                activeJob = ActiveJob("Starting", "Initialising translation engine", 0f),
            )
        }
        val intent = TranslationService.buildIntent(
            context = appContext,
            chapter = chapter,
            sourceLanguage = currentState.sourceLanguage,
            targetLanguage = currentState.targetLanguage,
            translationEngine = currentState.translationEngine,
            geminiApiKey = currentState.geminiApiKey,
            geminiModelName = currentState.geminiModelName,
            deepLApiKey = currentState.deepLApiKey,
            localModel = currentState.localModel,
            localBackend = currentState.localBackend,
            confirmed = false,
        )
        ContextCompat.startForegroundService(appContext, intent)
    }

    fun confirmTranslation(chapter: ChapterEntry) {
        val currentState = state.value
        _state.update {
            it.copy(
                confirmationRequest = null,
                activeJob = ActiveJob("Resuming", "Sending translation requests to Gemini", 0.65f),
            )
        }
        val intent = TranslationService.buildIntent(
            context = appContext,
            chapter = chapter,
            sourceLanguage = currentState.sourceLanguage,
            targetLanguage = currentState.targetLanguage,
            translationEngine = currentState.translationEngine,
            geminiApiKey = currentState.geminiApiKey,
            geminiModelName = currentState.geminiModelName,
            deepLApiKey = currentState.deepLApiKey,
            localModel = currentState.localModel,
            localBackend = currentState.localBackend,
            confirmed = true,
        )
        ContextCompat.startForegroundService(appContext, intent)
    }

    /** Stop button: halts the running translation; finished parts stay saved. */
    fun stopTranslation() {
        TranslationService.stop(appContext)
    }

    fun cancelTranslation() {
        _state.update {
            it.copy(
                confirmationRequest = null,
                isBusy = false,
                activeChapterKey = null,
                activeJob = null,
                message = "Translation cancelled. OCR results were saved to disk.",
            )
        }
    }

    fun reRenderChapter(chapter: ChapterEntry) {
        Log.d(TAG, "Starting re-render service for ${chapter.storageKey}")
        Toast.makeText(appContext, "Re-rendering ${chapter.chapterDisplayName}", Toast.LENGTH_SHORT).show()

        _state.update {
            it.copy(
                isBusy = true,
                message = null,
                activeChapterKey = chapter.storageKey,
                activeJob = ActiveJob(
                    title = "Re-rendering",
                    detail = chapter.chapterDisplayName,
                    progress = 0.02f,
                ),
            )
        }

        val serviceIntent = TranslationService.buildRerenderIntent(
            context = appContext,
            chapter = chapter,
        )
        ContextCompat.startForegroundService(appContext, serviceIntent)
    }

    private companion object {
        const val TAG = "Honyuka"
    }
}
