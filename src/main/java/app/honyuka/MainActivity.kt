package app.honyuka

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.honyuka.model.ChapterEntry
import app.honyuka.model.GeminiModelPreset
import app.honyuka.model.SourceLanguage
import app.honyuka.model.TargetLanguage
import app.honyuka.model.TranslationEngine
import app.honyuka.ui.HonyukaTheme
import app.honyuka.ui.HonyukaViewModel
import kotlinx.coroutines.Dispatchers
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
            HonyukaTheme {
                Surface {
                    HonyukaApp(
                        viewModel = viewModel,
                        onPickRoot = pickRoot,
                    )
                }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HonyukaApp(
    viewModel: HonyukaViewModel,
    onPickRoot: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var settingsOpen by remember { mutableStateOf(false) }
    val sourceNodes = remember(state.chapters) { state.chapters.toSourceNodes() }

    // Navigation state
    var selectedSource by remember { mutableStateOf<SourceNode?>(null) }
    var selectedManga by remember { mutableStateOf<MangaNode?>(null) }

    val titleText = when {
        selectedManga != null -> selectedManga!!.name
        selectedSource != null -> selectedSource!!.name
        else -> "Honyuka"
    }
    val subtitleText = when {
        selectedManga != null -> "${selectedSource!!.name} / ${selectedManga!!.name}"
        selectedSource != null -> "downloads / ${selectedSource!!.name}"
        else -> "Standalone manga translation companion"
    }
    val canGoBack = selectedSource != null

    BackHandler(enabled = canGoBack) {
        if (selectedManga != null) {
            selectedManga = null
        } else {
            selectedSource = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (canGoBack) {
                        IconButton(onClick = {
                            if (selectedManga != null) {
                                selectedManga = null
                            } else {
                                selectedSource = null
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                title = {
                    Column {
                        Text(
                            text = titleText,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = subtitleText,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { settingsOpen = true }) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Translation settings")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.activeJob?.let { job ->
                item {
                    JobCard(
                        title = job.title,
                        detail = job.detail,
                        progress = job.progress,
                    )
                }
            }

            state.message?.let { message ->
                item {
                    MessageCard(message = message)
                }
            }

            if (state.chapters.isEmpty()) {
                item {
                    EmptyStateCard(hasCompatibleRoot = state.hasCompatibleRoot)
                }
            } else when {
                // Level 3: Chapter list for a specific manga
                selectedManga != null -> {
                    items(selectedManga!!.chapters, key = { it.storageKey }) { chapter ->
                        ChapterDirectoryRow(
                            chapter = chapter,
                            enabled = !state.isBusy,
                            isRunning = state.activeChapterKey == chapter.storageKey,
                            onTranslate = { viewModel.translateChapter(chapter) },
                        )
                    }
                }
                // Level 2: Manga list for a specific source
                selectedSource != null -> {
                    items(selectedSource!!.manga, key = { it.name }) { manga ->
                        MangaCard(
                            manga = manga,
                            onClick = { selectedManga = manga },
                        )
                    }
                }
                // Level 1: Source list
                else -> {
                    items(sourceNodes, key = { it.name }) { source ->
                        SourceCard(
                            source = source,
                            onClick = { selectedSource = source },
                        )
                    }
                }
            }
        }
    }

    if (settingsOpen) {
        TranslationSettingsDialog(
            rootSummary = state.rootSummary,
            hasCompatibleRoot = state.hasCompatibleRoot,
            sourceLanguage = state.sourceLanguage,
            targetLanguage = state.targetLanguage,
            translationEngine = state.translationEngine,
            geminiModelName = state.geminiModelName,
            geminiApiKey = state.geminiApiKey,
            deepLApiKey = state.deepLApiKey,
            onPickRoot = onPickRoot,
            onRefresh = viewModel::refresh,
            onSourceSelected = viewModel::onSourceLanguageSelected,
            onTargetSelected = viewModel::onTargetLanguageSelected,
            onEngineSelected = viewModel::onTranslationEngineSelected,
            onGeminiModelNameChanged = viewModel::onGeminiModelNameChanged,
            onGeminiApiKeyChanged = viewModel::onGeminiApiKeyChanged,
            onDeepLApiKeyChanged = viewModel::onDeepLApiKeyChanged,
            onDismiss = { settingsOpen = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TranslationSettingsDialog(
    rootSummary: String?,
    hasCompatibleRoot: Boolean,
    sourceLanguage: SourceLanguage,
    targetLanguage: TargetLanguage,
    translationEngine: TranslationEngine,
    geminiModelName: String,
    geminiApiKey: String,
    deepLApiKey: String,
    onPickRoot: () -> Unit,
    onRefresh: () -> Unit,
    onSourceSelected: (SourceLanguage) -> Unit,
    onTargetSelected: (TargetLanguage) -> Unit,
    onEngineSelected: (TranslationEngine) -> Unit,
    onGeminiModelNameChanged: (String) -> Unit,
    onGeminiApiKeyChanged: (String) -> Unit,
    onDeepLApiKeyChanged: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var modelExpanded by remember { mutableStateOf(false) }
    var targetExpanded by remember { mutableStateOf(false) }
    val selectedPreset = GeminiModelPreset.entries.firstOrNull { it.modelName == geminiModelName }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                SettingsSection(title = "Storage Root") {
                    Text(
                        text = formatRootSummary(rootSummary),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "This is usually a one-time setup. Pick the folder that contains your reader's downloads directory.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    StatusPill(
                        text = if (hasCompatibleRoot) "downloads/ detected" else "root required",
                        emphasized = hasCompatibleRoot,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onPickRoot) {
                            Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Pick Root")
                        }
                        TextButton(onClick = onRefresh) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Refresh")
                        }
                    }
                }

                SettingsSection(title = "Languages") {
                    Text("Source language", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SourceLanguage.entries.forEach { option ->
                            FilterChip(
                                selected = option == sourceLanguage,
                                onClick = { onSourceSelected(option) },
                                label = { Text(option.label) },
                            )
                        }
                    }
                    Text("Target language", style = MaterialTheme.typography.labelLarge)
                    Box {
                        Button(onClick = { targetExpanded = true }) {
                            Text(targetLanguage.label)
                        }
                        DropdownMenu(expanded = targetExpanded, onDismissRequest = { targetExpanded = false }) {
                            TargetLanguage.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    onClick = {
                                        targetExpanded = false
                                        onTargetSelected(option)
                                    },
                                )
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Translator", style = MaterialTheme.typography.labelLarge)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TranslationEngine.entries.forEach { option ->
                            FilterChip(
                                selected = option == translationEngine,
                                onClick = { onEngineSelected(option) },
                                label = { Text(option.label) },
                            )
                        }
                    }
                    Text(
                        text = translationEngine.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (translationEngine == TranslationEngine.GEMINI) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Gemini model", style = MaterialTheme.typography.labelLarge)
                        Box {
                            Button(onClick = { modelExpanded = true }) {
                                Text(selectedPreset?.label ?: "Custom model")
                            }
                            DropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }) {
                                GeminiModelPreset.entries.forEach { preset ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(preset.label)
                                                Text(
                                                    text = preset.modelName,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        },
                                        onClick = {
                                            modelExpanded = false
                                            onGeminiModelNameChanged(preset.modelName)
                                        },
                                    )
                                }
                            }
                        }
                        OutlinedTextField(
                            value = geminiModelName,
                            onValueChange = onGeminiModelNameChanged,
                            label = { Text("Model name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = geminiApiKey,
                            onValueChange = onGeminiApiKeyChanged,
                            label = { Text("Gemini API key") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                if (translationEngine == TranslationEngine.DEEPL) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("DeepL API", style = MaterialTheme.typography.labelLarge)
                        OutlinedTextField(
                            value = deepLApiKey,
                            onValueChange = onDeepLApiKeyChanged,
                            label = { Text("DeepL API key") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = "DeepL Free keys ending in :fx use the Free API endpoint automatically.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        },
    )
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
}

@Composable
private fun SourceCard(
    source: SourceNode,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = source.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${source.manga.size} manga · ${source.manga.sumOf { it.chapters.size }} chapters",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MangaCard(
    manga: MangaNode,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MangaThumbnail(
                thumbnailChapter = manga.thumbnailChapter,
                title = manga.name,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = manga.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    StatusPill("${manga.chapters.size} chapters", emphasized = false)
                    val translatedCount = manga.chapters.count { it.translated }
                    if (translatedCount > 0) {
                        StatusPill("$translatedCount translated", emphasized = true)
                    }
                }
            }
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChapterDirectoryRow(
    chapter: ChapterEntry,
    enabled: Boolean,
    isRunning: Boolean,
    onTranslate: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chapter.chapterDisplayName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = if (chapter.isCbz) "Archive chapter (.cbz)" else "Image folder chapter",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusPill(
                text = if (chapter.translated) "Translated" else "New",
                emphasized = chapter.translated,
            )
            TextButton(onClick = onTranslate, enabled = enabled) {
                Text(
                    when {
                        isRunning -> "Working"
                        chapter.translated -> "Rebuild"
                        else -> "Translate"
                    },
                )
            }
        }
    }
}

@Composable
private fun MangaThumbnail(
    thumbnailChapter: ChapterEntry?,
    title: String,
) {
    val bitmap by rememberThumbnailBitmap(thumbnailChapter)
    Box(
        modifier = Modifier
            .size(width = 64.dp, height = 88.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
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
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
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

@Composable
private fun JobCard(
    title: String,
    detail: String,
    progress: Float,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun MessageCard(message: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(20.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun EmptyStateCard(hasCompatibleRoot: Boolean) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = if (hasCompatibleRoot) "No downloaded chapters found yet." else "Pick a compatible root folder to start scanning.",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (hasCompatibleRoot) {
                    "Honyuka only scans chapters already present in the reader's downloads folder."
                } else {
                    "Open Settings and choose the reader storage root that contains `downloads/`."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusPill(
    text: String,
    emphasized: Boolean,
) {
    Box(
        modifier = Modifier
            .background(
                color = if (emphasized) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(999.dp),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (emphasized) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
