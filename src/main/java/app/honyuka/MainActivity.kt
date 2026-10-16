package app.honyuka

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.honyuka.model.ChapterEntry
import app.honyuka.model.ConfirmationRequest
import app.honyuka.model.GeminiModelPreset
import app.honyuka.model.HonyukaUiState
import app.honyuka.model.LocalBackend
import app.honyuka.model.LocalModel
import app.honyuka.model.LocalModelStatus
import app.honyuka.model.RunStatus
import app.honyuka.model.SourceLanguage
import app.honyuka.model.TargetLanguage
import app.honyuka.model.ThemeMode
import app.honyuka.model.TranslationEngine
import app.honyuka.translation.LocalModelManager
import app.honyuka.ui.BilingualLabel
import app.honyuka.ui.HonyukaTheme
import app.honyuka.ui.HonyukaViewModel
import app.honyuka.ui.JpTextStyle
import app.honyuka.ui.Kawaii
import app.honyuka.ui.KawaiiCard
import app.honyuka.ui.KawaiiChip
import app.honyuka.ui.KawaiiLoader
import app.honyuka.ui.KawaiiRow
import app.honyuka.ui.Mascot
import app.honyuka.ui.Mood
import app.honyuka.ui.PillButton
import app.honyuka.ui.PillStyle
import app.honyuka.ui.Segmented
import app.honyuka.ui.SoftProgressBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream

private const val THUMBNAIL_MAX_SIDE_PX = 320

class MainActivity : ComponentActivity() {

    private val viewModel: HonyukaViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()
        setContent {
            val pickRoot = rememberTreePickerLauncher()
            val state by viewModel.state.collectAsStateWithLifecycle()
            val darkTheme = when (state.themeMode) {
                ThemeMode.AUTO -> isSystemInDarkTheme()
                ThemeMode.WASHI -> false
                ThemeMode.YORU -> true
            }
            // Status/nav bar icons follow the app theme, not just the system setting.
            DisposableEffect(darkTheme) {
                val style = SystemBarStyle.auto(Color.Transparent.toArgb(), Color.Transparent.toArgb()) { darkTheme }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }
            HonyukaTheme(themeMode = state.themeMode) {
                HonyukaApp(
                    viewModel = viewModel,
                    state = state,
                    onPickRoot = pickRoot,
                )
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (!viewModel.hasNotificationPermission()) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
            }
        }
    }

    @Composable
    private fun rememberTreePickerLauncher(): () -> Unit {
        val launcher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree(),
        ) { uri: Uri? ->
            uri?.let(::persistTreePermission)
            uri?.let(viewModel::onRootSelected)
        }
        return remember(launcher) { { launcher.launch(null) } }
    }

    private fun persistTreePermission(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            contentResolver.takePersistableUriPermission(uri, flags)
        }
    }
}

@Composable
private fun HonyukaApp(
    viewModel: HonyukaViewModel,
    state: HonyukaUiState,
    onPickRoot: () -> Unit,
) {
    val c = Kawaii.colors
    var settingsOpen by remember { mutableStateOf(false) }
    val sourceNodes = remember(state.chapters) { state.chapters.toSourceNodes() }

    var selectedSource by remember { mutableStateOf<SourceNode?>(null) }
    var selectedManga by remember { mutableStateOf<MangaNode?>(null) }

    val goBack: () -> Unit = {
        when {
            settingsOpen -> settingsOpen = false
            selectedManga != null -> selectedManga = null
            else -> selectedSource = null
        }
    }
    val canGoBack = settingsOpen || selectedSource != null
    BackHandler(enabled = canGoBack, onBack = goBack)

    val (title, subtitle) = when {
        settingsOpen -> "Settings" to "せってい"
        selectedManga != null -> selectedManga!!.name to "${selectedSource!!.name} · チャプター"
        selectedSource != null -> selectedSource!!.name to "ダウンロード"
        else -> "Honyuka" to "ほんやく · manga translator"
    }

    // At most one mascot per screen: the header avatar hides when a card shows one.
    val run = state.runStatus
    val jobShowsMascot = run != null || state.activeJob != null || state.message != null
    val emptyShowsMascot = !settingsOpen && state.chapters.isEmpty()
    val showHeaderMascot = !jobShowsMascot && !emptyShowsMascot

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(c.bg)
            .statusBarsPadding(),
    ) {
        Header(
            title = title,
            subtitle = subtitle,
            canGoBack = canGoBack,
            showMascot = showHeaderMascot,
            showSettings = !settingsOpen,
            onBack = goBack,
            onSettings = { settingsOpen = true },
        )

        run?.let { status ->
            RunBanner(
                status = status,
                onStop = viewModel::stopTranslation,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )
        }

        if (settingsOpen) {
            SettingsScreen(state = state, viewModel = viewModel, onPickRoot = onPickRoot)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                state.activeJob?.takeIf { run == null }?.let { job ->
                    item(key = "job") {
                        JobCard(title = job.title, detail = job.detail, progress = job.progress, running = state.isBusy)
                    }
                }
                state.message?.let { message ->
                    if (state.activeJob == null) {
                        item(key = "message") { MessageCard(message = message) }
                    }
                }

                if (state.chapters.isEmpty()) {
                    item(key = "empty") {
                        EmptyStateCard(
                            hasCompatibleRoot = state.hasCompatibleRoot,
                            showMascot = !jobShowsMascot,
                            onPickRoot = onPickRoot,
                        )
                    }
                } else when {
                    selectedManga != null -> {
                        items(selectedManga!!.chapters, key = { it.storageKey }) { chapter ->
                            ChapterCard(
                                chapter = chapter,
                                enabled = !state.isBusy,
                                isRunning = state.activeChapterKey == chapter.storageKey,
                                onTranslate = { viewModel.translateChapter(chapter) },
                                onRerender = { viewModel.reRenderChapter(chapter) },
                            )
                        }
                    }
                    selectedSource != null -> {
                        items(selectedSource!!.manga, key = { it.name }) { manga ->
                            MangaCard(manga = manga, onClick = { selectedManga = manga })
                        }
                    }
                    else -> {
                        item(key = "sources-label") {
                            BilingualLabel("Your downloads", "ダウンロードしたまんが")
                        }
                        items(sourceNodes, key = { it.name }) { source ->
                            SourceCard(source = source, onClick = { selectedSource = source })
                        }
                    }
                }
            }
        }
    }

    state.confirmationRequest?.let { request ->
        ConfirmationDialog(
            request = request,
            onConfirm = { viewModel.confirmTranslation(request.chapter) },
            onCancel = { viewModel.cancelTranslation() },
        )
    }
}

@Composable
private fun Header(
    title: String,
    subtitle: String,
    canGoBack: Boolean,
    showMascot: Boolean,
    showSettings: Boolean,
    onBack: () -> Unit,
    onSettings: () -> Unit,
) {
    val c = Kawaii.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (canGoBack) {
            RoundIconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = c.text)
            }
        } else if (showMascot) {
            Mascot(size = 44.dp, mood = Mood.WINK)
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = c.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = subtitle, style = JpTextStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (showSettings) {
            RoundIconButton(onClick = onSettings) {
                Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = c.text)
            }
        }
    }
}

@Composable
private fun RoundIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    val c = Kawaii.colors
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(c.card)
            .border(1.dp, c.border, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

// ── Settings ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsScreen(
    state: HonyukaUiState,
    viewModel: HonyukaViewModel,
    onPickRoot: () -> Unit,
) {
    val c = Kawaii.colors
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            SettingsSection("Theme", "テーマ") {
                Segmented(
                    options = ThemeMode.entries,
                    selected = state.themeMode,
                    label = { mode ->
                        when (mode) {
                            ThemeMode.AUTO -> "Auto"
                            ThemeMode.WASHI -> "☀️ Washi"
                            ThemeMode.YORU -> "🌙 Yoru"
                        }
                    },
                    onSelect = viewModel::onThemeModeSelected,
                )
            }
        }

        item {
            SettingsSection("Mihon folder", "ほぞんばしょ") {
                Text(formatRootSummary(state.rootSummary), style = MaterialTheme.typography.bodyMedium, color = c.textSoft)
                StatusChip(
                    text = if (state.hasCompatibleRoot) "downloads/ found" else "Pick the folder that has downloads/",
                    good = state.hasCompatibleRoot,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Pick folder", onClick = onPickRoot, compact = true)
                    PillButton("Rescan", onClick = viewModel::refresh, style = PillStyle.SOFT, compact = true)
                }
            }
        }

        item {
            SettingsSection("Languages", "ことば") {
                Text("From", style = MaterialTheme.typography.labelLarge, color = c.textSoft)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SourceLanguage.entries.forEach { option ->
                        KawaiiChip(option.label, selected = option == state.sourceLanguage, onClick = { viewModel.onSourceLanguageSelected(option) })
                    }
                }
                Text("To", style = MaterialTheme.typography.labelLarge, color = c.textSoft)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TargetLanguage.entries.forEach { option ->
                        KawaiiChip(
                            option.label,
                            selected = option == state.targetLanguage,
                            onClick = { viewModel.onTargetLanguageSelected(option) },
                            accent = c.sora,
                        )
                    }
                }
            }
        }

        item {
            SettingsSection("Translator", "ほんやくエンジン") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TranslationEngine.entries.forEach { option ->
                        KawaiiChip(option.label, selected = option == state.translationEngine, onClick = { viewModel.onTranslationEngineSelected(option) })
                    }
                }
                Text(state.translationEngine.description, style = MaterialTheme.typography.bodySmall, color = c.textSoft)
            }
        }

        when (state.translationEngine) {
            TranslationEngine.GEMINI -> item { GeminiSettings(state, viewModel) }
            TranslationEngine.ON_DEVICE -> item { OnDeviceSettings(state, viewModel) }
            TranslationEngine.DEEPL -> item {
                SettingsSection("DeepL", "かぎ") {
                    KawaiiTextField(state.deepLApiKey, viewModel::onDeepLApiKeyChanged, "DeepL API key", secret = true)
                    Text(
                        "Free keys ending in :fx use the free endpoint automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = c.textSoft,
                    )
                }
            }
            else -> Unit
        }
    }
}

@Composable
private fun SettingsSection(
    english: String,
    japanese: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    KawaiiCard {
        BilingualLabel(english, japanese)
        content()
    }
}

@Composable
private fun GeminiSettings(state: HonyukaUiState, viewModel: HonyukaViewModel) {
    val c = Kawaii.colors
    val presetNames = remember { GeminiModelPreset.entries.map { it.modelName }.toSet() }
    val fetched = state.availableGeminiModels.filterNot { it in presetNames }
    SettingsSection("Gemini model", "モデル") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GeminiModelPreset.entries.forEach { preset ->
                SelectableRow(
                    title = preset.label,
                    subtitle = preset.description,
                    selected = state.geminiModelName == preset.modelName,
                    onClick = { viewModel.onGeminiModelNameChanged(preset.modelName) },
                )
            }
        }
        if (fetched.isNotEmpty()) {
            Text("More models for your key", style = MaterialTheme.typography.labelLarge, color = c.textSoft)
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                fetched.forEach { model ->
                    KawaiiChip(model, selected = state.geminiModelName == model, onClick = { viewModel.onGeminiModelNameChanged(model) }, accent = c.sora)
                }
            }
        }
        state.modelListStatus?.let { status ->
            Text(status, style = MaterialTheme.typography.bodySmall, color = if (status.startsWith("❌")) c.danger else c.textSoft)
        }
        KawaiiTextField(state.geminiModelName, viewModel::onGeminiModelNameChanged, "Model name")
        KawaiiTextField(state.geminiApiKey, viewModel::onGeminiApiKeyChanged, "Gemini API key", secret = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(
                text = if (state.apiTestStatus == "Testing…") "Testing…" else "Test key",
                onClick = viewModel::testGeminiApiKey,
                enabled = state.geminiApiKey.isNotBlank(),
                compact = true,
            )
            PillButton(
                text = "Refresh models",
                onClick = viewModel::refreshGeminiModels,
                enabled = state.geminiApiKey.isNotBlank(),
                style = PillStyle.SOFT,
                compact = true,
            )
        }
        state.apiTestStatus?.takeIf { it != "Testing…" }?.let { status ->
            Text(status, style = MaterialTheme.typography.bodySmall, color = if (status.startsWith("✅")) c.matcha else c.danger)
        }
    }
}

@Composable
private fun OnDeviceSettings(state: HonyukaUiState, viewModel: HonyukaViewModel) {
    val c = Kawaii.colors
    SettingsSection("On-device model", "オフラインモデル") {
        Text(
            "Runs Gemma on your phone: free, private, no quota. Slower than Gemini — a chapter can take a few minutes. Download once on Wi-Fi.",
            style = MaterialTheme.typography.bodySmall,
            color = c.textSoft,
        )
        LocalModel.entries.forEach { model ->
            LocalModelCard(
                model = model,
                status = state.localModelStatus[model] ?: LocalModelStatus(),
                selected = state.localModel == model,
                onSelect = { viewModel.onLocalModelSelected(model) },
                onDownload = { viewModel.downloadLocalModel(model) },
                onCancel = { viewModel.cancelLocalModelDownload(model) },
                onDelete = { viewModel.deleteLocalModel(model) },
            )
        }
        Text("Runs on", style = MaterialTheme.typography.labelLarge, color = c.textSoft)
        Segmented(
            options = LocalBackend.entries,
            selected = state.localBackend,
            label = { it.label },
            onSelect = viewModel::onLocalBackendSelected,
        )
        Text(
            "Auto tries the GPU first and falls back to CPU — and remembers if the GPU crashed on this phone.",
            style = MaterialTheme.typography.bodySmall,
            color = c.textFaint,
        )
    }
}

@Composable
private fun LocalModelCard(
    model: LocalModel,
    status: LocalModelStatus,
    selected: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val c = Kawaii.colors
    KawaiiCard(
        tint = if (selected) c.sakuraSoft else c.bgSoft,
        onClick = onSelect,
        contentPadding = PaddingValues(14.dp),
    ) {
        KawaiiRow {
            Column(modifier = Modifier.weight(1f)) {
                Text(model.label, style = MaterialTheme.typography.titleSmall, color = c.text)
                Text(model.description, style = MaterialTheme.typography.bodySmall, color = c.textSoft)
            }
            StatusChip(
                text = when {
                    status.downloaded -> "Ready"
                    status.downloading -> "${(status.progress * 100).toInt()}%"
                    else -> LocalModelManager.formatBytes(model.sizeBytes)
                },
                good = status.downloaded,
            )
        }
        if (status.downloading) {
            SoftProgressBar(status.progress)
        }
        status.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.danger) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                status.downloaded -> PillButton("Delete", onClick = onDelete, style = PillStyle.GHOST, compact = true)
                status.downloading -> PillButton("Cancel", onClick = onCancel, style = PillStyle.SOFT, compact = true)
                else -> PillButton("Download", onClick = onDownload, compact = true)
            }
        }
    }
}

@Composable
private fun SelectableRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val c = Kawaii.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) c.sakuraSoft else c.bgSoft)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .background(if (selected) c.sakura else Color.Transparent)
                .border(1.5.dp, if (selected) c.sakura else c.textFaint, CircleShape),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.text)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = c.textSoft)
        }
    }
}

@Composable
private fun KawaiiTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    secret: Boolean = false,
) {
    val c = Kawaii.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        shape = RoundedCornerShape(18.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = c.bgSoft,
            unfocusedContainerColor = c.bgSoft,
            focusedBorderColor = c.sakura,
            unfocusedBorderColor = c.border,
            focusedLabelColor = c.sakura,
            unfocusedLabelColor = c.textSoft,
            cursorColor = c.sakura,
            focusedTextColor = c.text,
            unfocusedTextColor = c.text,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun StatusChip(text: String, good: Boolean) {
    val c = Kawaii.colors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (good) c.matchaSoft else c.bgSoft)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (good) c.text else c.textSoft)
    }
}

// ── Library ─────────────────────────────────────────────────────────────────

@Composable
private fun SourceCard(
    source: SourceNode,
    onClick: () -> Unit,
) {
    val c = Kawaii.colors
    KawaiiCard(onClick = onClick) {
        KawaiiRow {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(c.soraSoft),
                contentAlignment = Alignment.Center,
            ) {
                Text("📚", style = MaterialTheme.typography.titleMedium)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(source.name, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${source.manga.size} manga · ${source.manga.sumOf { it.chapters.size }} chapters",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSoft,
                )
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = c.textFaint)
        }
    }
}

@Composable
private fun MangaCard(
    manga: MangaNode,
    onClick: () -> Unit,
) {
    val c = Kawaii.colors
    KawaiiCard(onClick = onClick, contentPadding = PaddingValues(12.dp)) {
        KawaiiRow {
            MangaThumbnail(thumbnailChapter = manga.thumbnailChapter, title = manga.name)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(manga.name, style = MaterialTheme.typography.titleMedium, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusChip("${manga.chapters.size} chapters", good = false)
                    val translatedCount = manga.chapters.count { it.translated }
                    if (translatedCount > 0) StatusChip("$translatedCount translated", good = true)
                }
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = c.textFaint)
        }
    }
}

@Composable
private fun ChapterCard(
    chapter: ChapterEntry,
    enabled: Boolean,
    isRunning: Boolean,
    onTranslate: () -> Unit,
    onRerender: () -> Unit,
) {
    val c = Kawaii.colors
    KawaiiCard(contentPadding = PaddingValues(14.dp)) {
        KawaiiRow {
            Column(modifier = Modifier.weight(1f)) {
                Text(chapter.chapterDisplayName, style = MaterialTheme.typography.titleSmall, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    if (chapter.isCbz) "Archive (.cbz)" else "Image folder",
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSoft,
                )
            }
            StatusChip(if (chapter.translated) "Translated ✓" else "New", good = chapter.translated)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(
                text = when {
                    isRunning -> "Working…"
                    chapter.translated -> "Translate again"
                    else -> "Translate"
                },
                onClick = onTranslate,
                enabled = enabled,
                compact = true,
            )
            if (chapter.translated) {
                PillButton("Re-render", onClick = onRerender, enabled = enabled, style = PillStyle.SOFT, compact = true)
            }
        }
    }
}

@Composable
private fun MangaThumbnail(
    thumbnailChapter: ChapterEntry?,
    title: String,
) {
    val c = Kawaii.colors
    val bitmap by rememberThumbnailBitmap(thumbnailChapter)
    Box(
        modifier = Modifier
            .size(width = 60.dp, height = 84.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(c.sakuraSoft),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text("📖", style = MaterialTheme.typography.titleMedium)
        }
    }
}

// ── Status cards ────────────────────────────────────────────────────────────

/**
 * Always-visible card for the running translation: chapter, current step, part X of Y,
 * elapsed time, a rough time left, and a Stop button.
 */
@Composable
private fun RunBanner(
    status: RunStatus,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Kawaii.colors
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val chunked = status.chunksTotal > 0
    val progress = if (chunked) {
        (status.chunksDone.toFloat() / status.chunksTotal).coerceIn(0.02f, 1f)
    } else {
        status.progress
    }
    val eta = if (chunked && status.chunksDone in 1 until status.chunksTotal && status.chunksStartedAtMs > 0) {
        val perChunk = (now - status.chunksStartedAtMs) / status.chunksDone
        "~${formatDuration(perChunk * (status.chunksTotal - status.chunksDone))} left"
    } else {
        null
    }
    KawaiiCard(modifier = modifier, tint = c.sakuraSoft) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Mascot(size = 48.dp, mood = if (status.stopping) Mood.SLEEPY else Mood.YUM, bounce = !status.stopping)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (status.stopping) "Stopping…" else "Translating now",
                    style = MaterialTheme.typography.titleSmall,
                    color = c.text,
                )
                Text(
                    status.chapterName,
                    style = MaterialTheme.typography.bodySmall,
                    color = c.textSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!status.stopping) {
                PillButton("Stop", onClick = onStop, style = PillStyle.GHOST, compact = true)
            }
        }
        Text(status.detail, style = MaterialTheme.typography.bodyMedium, color = c.text)
        SoftProgressBar(progress, color = c.sakura, track = c.card)
        Text(
            listOfNotNull("${formatDuration(now - status.startedAtMs)} elapsed", eta).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = c.textSoft,
        )
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

@Composable
private fun JobCard(
    title: String,
    detail: String,
    progress: Float,
    running: Boolean,
) {
    val c = Kawaii.colors
    KawaiiCard(tint = if (running) c.sakuraSoft else c.matchaSoft) {
        if (running) {
            KawaiiLoader(mascotSize = 64.dp)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Mascot(size = 48.dp, mood = Mood.YUM)
                BilingualLabel("All done! Open Mihon → Local source 🎉", "できました")
            }
        }
        Text(title, style = MaterialTheme.typography.titleSmall, color = c.text)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = c.textSoft)
        if (running) {
            SoftProgressBar(progress, color = c.sakura, track = c.card)
        }
    }
}

@Composable
private fun MessageCard(message: String) {
    val c = Kawaii.colors
    KawaiiCard(tint = c.yuzuSoft) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Mascot(size = 48.dp, mood = Mood.DIZZY)
            Column(modifier = Modifier.weight(1f)) {
                Text("Oops, something got tangled", style = MaterialTheme.typography.titleSmall, color = c.text)
                Text("ごめんね", style = JpTextStyle)
            }
        }
        Text(message, style = MaterialTheme.typography.bodyMedium, color = c.text)
    }
}

@Composable
private fun ConfirmationDialog(
    request: ConfirmationRequest,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val c = Kawaii.colors
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = c.card,
        shape = RoundedCornerShape(24.dp),
        title = { BilingualLabel("Ready to translate?", "ほんやくしますか") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Text found in ${request.chapter.chapterDisplayName}.", color = c.text)
                Text(
                    "${request.blockCount} bubbles need translating — about ${request.chunkCount} Gemini request(s).",
                    color = c.textSoft,
                )
                Text("Already-translated and repeated lines are free.", style = MaterialTheme.typography.bodySmall, color = c.textFaint)
            }
        },
        confirmButton = { PillButton("Translate", onClick = onConfirm, compact = true) },
        dismissButton = { PillButton("Not now", onClick = onCancel, style = PillStyle.GHOST, compact = true) },
    )
}

@Composable
private fun EmptyStateCard(
    hasCompatibleRoot: Boolean,
    showMascot: Boolean,
    onPickRoot: () -> Unit,
) {
    val c = Kawaii.colors
    KawaiiCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (showMascot) Mascot(size = 88.dp, mood = Mood.SLEEPY)
            Text(
                text = if (hasCompatibleRoot) "No downloaded chapters yet 💤" else "Pick your Mihon folder to begin 📂",
                style = MaterialTheme.typography.titleMedium,
                color = c.text,
                textAlign = TextAlign.Center,
            )
            Text(
                text = if (hasCompatibleRoot) "Download a chapter in Mihon, then pull it in here." else "Choose the folder that contains downloads/.",
                style = MaterialTheme.typography.bodyMedium,
                color = c.textSoft,
                textAlign = TextAlign.Center,
            )
            Text(if (hasCompatibleRoot) "まだありません" else "フォルダをえらんでね", style = JpTextStyle)
            if (!hasCompatibleRoot) {
                PillButton("Pick folder", onClick = onPickRoot)
            }
        }
    }
}

@Composable
private fun rememberThumbnailBitmap(chapter: ChapterEntry?): State<Bitmap?> {
    val context = LocalContext.current
    return produceState<Bitmap?>(initialValue = null, chapter?.storageKey) {
        value = if (chapter == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    if (chapter.isCbz) {
                        context.contentResolver.openInputStream(chapter.chapterUri).use { input ->
                            ZipInputStream(requireNotNull(input)).use { zipInput ->
                                decodeFirstArchiveImage(zipInput)
                            }
                        }
                    } else {
                        val thumbnailUri = chapter.thumbnailUri ?: return@runCatching null
                        context.contentResolver.openInputStream(thumbnailUri).use { input ->
                            decodeThumbnailBytes(requireNotNull(input).readBytes())
                        }
                    }
                }.getOrNull()
            }
        }
    }
}

private fun decodeFirstArchiveImage(zipInput: ZipInputStream): Bitmap? {
    while (true) {
        val entry = zipInput.nextEntry ?: return null
        if (!entry.isDirectory && entry.name.isPageImageName()) {
            val bytes = zipInput.readBytes()
            zipInput.closeEntry()
            return decodeThumbnailBytes(bytes)
        }
        zipInput.closeEntry()
    }
}

private fun decodeThumbnailBytes(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    val largestSide = maxOf(bounds.outWidth, bounds.outHeight)
    while (largestSide / sampleSize > THUMBNAIL_MAX_SIDE_PX) {
        sampleSize *= 2
    }

    val options565 = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options565)?.let { return it }

    // Fallback: ARGB_8888 handles WebP, HEIF, and other formats RGB_565 can't decode
    val optionsArgb = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, optionsArgb)
}

private fun String.isPageImageName(): Boolean {
    val name = substringAfterLast('/').lowercase()
    return name.endsWith(".jpg") ||
        name.endsWith(".jpeg") ||
        name.endsWith(".png") ||
        name.endsWith(".webp")
}

private fun formatRootSummary(rootSummary: String?): String {
    if (rootSummary.isNullOrBlank()) return "No storage root selected"
    val uri = runCatching { Uri.parse(rootSummary) }.getOrNull() ?: return rootSummary
    val treeId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
    val path = treeId?.substringAfter(':', missingDelimiterValue = "")
        ?.takeIf { it.isNotBlank() }
    val volume = treeId?.substringBefore(':', missingDelimiterValue = "")
    return when {
        path != null && volume == "primary" -> "Internal storage / $path"
        path != null && !volume.isNullOrBlank() -> "$volume / $path"
        path != null -> path
        volume == "primary" -> "Internal storage"
        !volume.isNullOrBlank() -> volume
        else -> uri.lastPathSegment ?: rootSummary
    }
}

private fun List<ChapterEntry>.toSourceNodes(): List<SourceNode> {
    return groupBy { it.sourceName }
        .toSortedMap(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
        .map { (sourceName, sourceChapters) ->
            SourceNode(
                name = sourceName,
                manga = sourceChapters
                    .groupBy { it.mangaName }
                    .toSortedMap(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
                    .map { (mangaName, mangaChapters) ->
                        MangaNode(
                            sourceName = sourceName,
                            name = mangaName,
                            thumbnailChapter = mangaChapters.firstOrNull { it.thumbnailUri != null }
                                ?: mangaChapters.firstOrNull(),
                            chapters = mangaChapters.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.chapterDisplayName }),
                        )
                    },
            )
        }
}

private data class SourceNode(
    val name: String,
    val manga: List<MangaNode>,
)

private data class MangaNode(
    val sourceName: String,
    val name: String,
    val thumbnailChapter: ChapterEntry?,
    val chapters: List<ChapterEntry>,
)

