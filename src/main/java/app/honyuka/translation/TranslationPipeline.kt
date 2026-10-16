package app.honyuka.translation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import app.honyuka.model.ChapterEntry
import app.honyuka.model.PageTranslation
import app.honyuka.model.SourceLanguage
import app.honyuka.model.TargetLanguage
import app.honyuka.model.TranslationBlock
import app.honyuka.model.TranslationEngine
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizerOptionsInterface
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

class TranslationPipeline(
    private val context: Context,
) {

    suspend fun extractText(
        chapter: ChapterEntry,
        chapterDocument: DocumentFile,
        sourceLanguage: SourceLanguage,
        onProgress: (title: String, detail: String, progress: Float) -> Unit,
    ): Map<String, PageTranslation> {
        val pages = loadPages(chapter, chapterDocument)
        require(pages.isNotEmpty()) {
            "No readable pages were found in this chapter. The chapter opened, but no image pages could be decoded from the folder or archive."
        }

        val pageTranslations = linkedMapOf<String, PageTranslation>()
        TextRecognitionEngine(sourceLanguage).use { recognizer ->
            pages.forEachIndexed { index, page ->
                onProgress(
                    "Recognising text",
                    "${chapter.chapterDisplayName}: page ${index + 1} of ${pages.size}",
                    (index + 1).toFloat() / (pages.size.coerceAtLeast(1) * 2f),
                )
                val bytes = page.openStream().use { it.readBytes() }
                require(bytes.isNotEmpty()) {
                    "Image page `${page.name}` was empty."
                }
                val bitmap = recognizer.decodeBitmap(bytes, page.name)
                try {
                    val result = recognizer.recognize(bitmap)
                    val translation = convertToPageTranslation(result.textBlocks, bitmap.width, bitmap.height)
                    if (translation.blocks.isNotEmpty()) {
                        pageTranslations[page.name] = translation
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        }

        require(pageTranslations.isNotEmpty()) {
            "Pages loaded successfully, but no text bubbles were detected. This chapter may be image-light, stylized, or use an unsupported OCR source language."
        }
        return pageTranslations
    }

    suspend fun translateText(
        pageTranslations: MutableMap<String, PageTranslation>,
        sourceLanguage: SourceLanguage,
        targetLanguage: TargetLanguage,
        translationEngine: TranslationEngine,
        geminiApiKey: String,
        geminiModelName: String,
        deepLApiKey: String,
        onChunkSaved: () -> Unit = {},
        onProgress: (title: String, detail: String, progress: Float) -> Unit,
    ) {
        onProgress("Translating text", "Using ${translationEngine.label} for manga dialogue", 0.65f)
        createTranslationEngine(
            context = context,
            engine = translationEngine,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            geminiApiKey = geminiApiKey,
            geminiModelName = geminiModelName,
            deepLApiKey = deepLApiKey,
            onChunkDone = { done, total ->
                onChunkSaved()
                onProgress(
                    "Translating text",
                    "Gemini request $done of $total done",
                    0.65f + 0.3f * done / total.coerceAtLeast(1),
                )
            },
        ).use { translator ->
            translator.translate(pageTranslations)
        }
        onProgress("Saving sidecar", "Preparing reader-compatible JSON output", 0.95f)
    }

    /**
     * Estimates the Gemini requests a translation run will make. Only counts lines
     * that will actually be sent: already-translated blocks, watermarks, duplicates
     * and lines found in the translation memory are excluded.
     *
     * @return (request count, number of blocks that will receive a new translation)
     */
    fun estimateGeminiChunks(
        pages: Map<String, PageTranslation>,
        sourceLanguage: SourceLanguage,
        targetLanguage: TargetLanguage,
        geminiModelName: String,
    ): Pair<Int, Int> {
        return GeminiTranslationEngine(
            cacheDir = context.cacheDir,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            apiKey = "",
            modelName = geminiModelName,
        ).plan(pages)
    }

    private fun loadPages(
        chapter: ChapterEntry,
        chapterDocument: DocumentFile,
    ): List<PagePayload> {
        return if (chapter.isCbz) {
            loadPagesFromCbz(chapterDocument)
        } else {
            loadPagesFromDirectory(chapterDocument)
        }
    }

    private fun loadPagesFromDirectory(chapterDir: DocumentFile): List<PagePayload> {
        return collectImageFiles(chapterDir)
            .sortedWith { left, right -> naturalCompare(left.name.orEmpty(), right.name.orEmpty()) }
            .map { file ->
                // Page keys are trimmed leaf names; RenderedChapterExporter looks them up the same way.
                PagePayload(file.name.orEmpty().trim()) {
                    requireNotNull(context.contentResolver.openInputStream(file.uri)) {
                        "Unable to open image page ${file.name.orEmpty()}."
                    }
                }
            }
    }

    private fun loadPagesFromCbz(file: DocumentFile): List<PagePayload> {
        val pageBuffers = mutableListOf<Pair<String, ByteArray>>()
        val tempArchive = File.createTempFile("honyuka-", ".cbz", context.cacheDir)
        try {
            context.contentResolver.openInputStream(file.uri)?.use { stream ->
                tempArchive.outputStream().use { output ->
                    stream.copyTo(output)
                }
            } ?: error("Unable to open CBZ archive.")

            ZipFile(tempArchive).use { zipFile ->
                val entries = zipFile.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name.substringAfterLast('/').trim()
                    if (!entry.isDirectory) {
                        val bytes = zipFile.getInputStream(entry).use { it.readBytes() }
                        if (isImageEntry(name, bytes)) {
                            pageBuffers += name to bytes
                        }
                    }
                }
            }
        } finally {
            tempArchive.delete()
        }

        return pageBuffers
            .sortedWith { left, right -> naturalCompare(left.first, right.first) }
            .map { (name, bytes) ->
                PagePayload(name) { ByteArrayInputStream(bytes) }
            }
    }

    private fun collectImageFiles(directory: DocumentFile): List<DocumentFile> {
        if (!directory.isDirectory) return emptyList()
        return directory.listFiles().flatMap { child ->
            when {
                child.isDirectory -> collectImageFiles(child)
                child.isFile && isSupportedImageDocument(child) -> listOf(child)
                else -> emptyList()
            }
        }
    }

    private fun convertToPageTranslation(
        blocks: List<Text.TextBlock>,
        width: Int,
        height: Int,
    ): PageTranslation {
        val translation = PageTranslation(imgWidth = width.toFloat(), imgHeight = height.toFloat())
        blocks
            .filter { it.boundingBox != null && it.text.trim().length > 1 }
            .sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
            .forEach { block ->
                val bounds = block.boundingBox ?: return@forEach
                val symbolBounds = block.lines.firstOrNull()
                    ?.elements?.firstOrNull()
                    ?.symbols?.firstOrNull()
                    ?.boundingBox
                    ?: bounds
                translation.blocks += TranslationBlock(
                    text = block.text,
                    width = bounds.width().toFloat(),
                    height = bounds.height().toFloat(),
                    x = bounds.left.toFloat(),
                    y = bounds.top.toFloat(),
                    symHeight = symbolBounds.height().toFloat(),
                    symWidth = symbolBounds.width().toFloat(),
                    angle = block.lines.firstOrNull()?.angle ?: 0f,
                )
            }
        translation.blocks = smartMergeBlocks(translation.blocks, 50, 30, 30)
        return translation
    }

    private fun smartMergeBlocks(
        blocks: List<TranslationBlock>,
        widthThreshold: Int,
        xThreshold: Int,
        yThreshold: Int,
    ): MutableList<TranslationBlock> {
        if (blocks.isEmpty()) return mutableListOf()
        val merged = mutableListOf<TranslationBlock>()
        var current = blocks.first()
        for (index in 1 until blocks.size) {
            val next = blocks[index]
            current = if (shouldMergeTextBlock(current, next, widthThreshold, xThreshold, yThreshold)) {
                mergeTextBlocks(current, next)
            } else {
                merged += current
                next
            }
        }
        merged += current
        return merged
    }

    private fun shouldMergeTextBlock(
        first: TranslationBlock,
        second: TranslationBlock,
        widthThreshold: Int,
        xThreshold: Int,
        yThreshold: Int,
    ): Boolean {
        val widthSimilar = second.width < first.width || abs(first.width - second.width) < widthThreshold
        val xClose = abs(first.x - second.x) < xThreshold
        val yClose = (second.y - (first.y + first.height)) < yThreshold
        return widthSimilar && xClose && yClose
    }

    private fun mergeTextBlocks(
        first: TranslationBlock,
        second: TranslationBlock,
    ): TranslationBlock {
        val newX = minOf(first.x, second.x)
        val newY = minOf(first.y, second.y)
        val newWidth = maxOf(first.x + first.width, second.x + second.width) - newX
        val newHeight = maxOf(first.y + first.height, second.y + second.height) - newY
        return TranslationBlock(
            text = "${first.text} ${second.text}".trim(),
            translation = "${first.translation} ${second.translation}".trim(),
            width = newWidth,
            height = newHeight,
            x = newX,
            y = newY,
            symHeight = maxOf(first.symHeight, second.symHeight),
            symWidth = maxOf(first.symWidth, second.symWidth),
            angle = first.angle,
        )
    }

    private fun isImageName(name: String?): Boolean {
        val lowered = name?.lowercase(Locale.ROOT) ?: return false
        return lowered.endsWith(".jpg") ||
            lowered.endsWith(".jpeg") ||
            lowered.endsWith(".gif") ||
            lowered.endsWith(".heif") ||
            lowered.endsWith(".jxl") ||
            lowered.endsWith(".png") ||
            lowered.endsWith(".webp") ||
            lowered.endsWith(".avif")
    }

    private fun isSupportedImageDocument(file: DocumentFile): Boolean {
        if (isImageName(file.name)) return true
        val mimeType = file.type?.lowercase(Locale.ROOT)
        if (mimeType?.startsWith("image/") == true) return true
        return context.contentResolver.openInputStream(file.uri)?.use(::isDecodableImageStream) == true
    }

    private fun isImageEntry(name: String, bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth > 0 && options.outHeight > 0) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val decoded = runCatching {
                val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.isMutableRequired = false
                }
            }.getOrNull()
            if (decoded != null) {
                decoded.recycle()
                return true
            }
        }
        return isImageName(name)
    }

    private fun isDecodableImageStream(stream: InputStream): Boolean {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(stream, null, options)
        return options.outWidth > 0 && options.outHeight > 0
    }

    private data class PagePayload(
        val name: String,
        val openStream: () -> InputStream,
    )
}

private class TextRecognitionEngine(
    sourceLanguage: SourceLanguage,
) : Closeable {

    private val recognizer = TextRecognition.getClient(sourceLanguage.toRecognizerOptions())

    fun decodeBitmap(bytes: ByteArray, pageName: String): Bitmap {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { return it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.isMutableRequired = true
                }
            }.getOrNull()?.let { return it }
        }
        error("Image page `${pageName}` could not be decoded. It may use an unsupported format.")
    }

    fun recognize(bitmap: Bitmap): Text {
        return Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
    }

    override fun close() {
        recognizer.close()
    }
}

private interface TextTranslationEngine : Closeable {
    fun translate(pages: MutableMap<String, PageTranslation>)
}

private fun createTranslationEngine(
    context: Context,
    engine: TranslationEngine,
    sourceLanguage: SourceLanguage,
    targetLanguage: TargetLanguage,
    geminiApiKey: String,
    geminiModelName: String,
    deepLApiKey: String,
    onChunkDone: (done: Int, total: Int) -> Unit,
): TextTranslationEngine {
    return when (engine) {
        TranslationEngine.GEMINI -> GeminiTranslationEngine(
            cacheDir = context.cacheDir,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            apiKey = geminiApiKey,
            modelName = geminiModelName,
            onChunkDone = onChunkDone,
        )
        TranslationEngine.DEEPL -> DeepLTranslationEngine(
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            apiKey = deepLApiKey,
        )
        TranslationEngine.GOOGLE_TRANSLATE -> GoogleTranslateWebEngine(sourceLanguage, targetLanguage)
        TranslationEngine.MLKIT -> MlKitTranslationEngine(sourceLanguage, targetLanguage)
    }
}

private class MlKitTranslationEngine(
    sourceLanguage: SourceLanguage,
    targetLanguage: TargetLanguage,
) : TextTranslationEngine {

    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(sourceLanguage.ocrCode)
            .setTargetLanguage(TranslateLanguage.fromLanguageTag(targetLanguage.languageTag) ?: TranslateLanguage.ENGLISH)
            .build(),
    )

    override fun translate(pages: MutableMap<String, PageTranslation>) {
        Tasks.await(translator.downloadModelIfNeeded(DownloadConditions.Builder().build()))
        var totalBlocks = 0
        var fallbackBlocks = 0
        pages.values.forEach { page ->
            page.blocks = page.blocks
                .mapNotNull { block ->
                    if (block.translation.isNotBlank()) return@mapNotNull block.takeUnless { it.shouldBeDropped() }
                    totalBlocks++
                    val translatedText = block.text
                        .split("\n")
                        .mapNotNull { line ->
                            line.trim()
                                .takeIf { it.isNotEmpty() }
                                ?.let { Tasks.await(translator.translate(it)) }
                                ?.takeIf { it.isNotBlank() }
                        }
                        .joinToString("\n")
                    if (translatedText.isBlank()) fallbackBlocks++
                    block.translation = translatedText.ifBlank { block.text }
                    block.takeUnless { block.shouldBeDropped() }
                }
                .toMutableList()
        }
        if (totalBlocks > 0 && fallbackBlocks == totalBlocks) {
            error(
                "ML Kit translation failed — none of the $totalBlocks text blocks could be translated. " +
                    "The offline translation model may not have downloaded correctly. " +
                    "Check your network connection and try again."
            )
        }
    }

    override fun close() {
        translator.close()
    }
}

private class GoogleTranslateWebEngine(
    private val sourceLanguage: SourceLanguage,
    private val targetLanguage: TargetLanguage,
) : TextTranslationEngine {

    override fun translate(pages: MutableMap<String, PageTranslation>) {
        var totalBlocks = 0
        var fallbackBlocks = 0
        // One request per unique block (not per line); repeated lines are reused.
        val memo = HashMap<String, String>()
        pages.values.forEach { page ->
            page.blocks = page.blocks
                .mapNotNull { block ->
                    if (block.translation.isNotBlank()) return@mapNotNull block.takeUnless { it.shouldBeDropped() }
                    totalBlocks++
                    val sourceText = block.text
                        .split("\n")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .joinToString("\n")
                    val translatedText = if (sourceText.isEmpty()) {
                        ""
                    } else {
                        memo.getOrPut(sourceText) {
                            runCatching { translateLine(sourceText) }.getOrDefault("")
                        }
                    }
                    if (translatedText.isBlank()) fallbackBlocks++
                    block.translation = translatedText.ifBlank { block.text }
                    block.takeUnless { block.shouldBeDropped() }
                }
                .toMutableList()
        }
        if (totalBlocks > 0 && fallbackBlocks == totalBlocks) {
            error(
                "Google Translate failed — none of the $totalBlocks text blocks could be translated. " +
                    "Check your network connection and try again."
            )
        }
    }

    private fun translateLine(text: String): String {
        val encoded = URLEncoder.encode(text, Charsets.UTF_8.name())
        val url = "https://translate.googleapis.com/translate_a/single" +
            "?client=gtx" +
            "&sl=${sourceLanguage.languageTag}" +
            "&tl=${targetLanguage.languageTag}" +
            "&dt=t" +
            "&q=$encoded"
        val body = httpGet(url)
        val segments = JSONArray(body).getJSONArray(0)
        return buildString {
            for (index in 0 until segments.length()) {
                append(segments.getJSONArray(index).optString(0))
            }
        }.trim()
    }

    override fun close() = Unit
}

private class DeepLTranslationEngine(
    private val sourceLanguage: SourceLanguage,
    private val targetLanguage: TargetLanguage,
    private val apiKey: String,
) : TextTranslationEngine {

    override fun translate(pages: MutableMap<String, PageTranslation>) {
        require(apiKey.isNotBlank()) { "DeepL API key is required for the DeepL translator." }

        val blockRefs = pages.values.flatMap { page ->
            page.blocks
                .filter { it.translation.isBlank() }
                .map { block -> BlockRef(block = block, text = block.text.trim()) }
        }.filter { it.text.isNotBlank() && !WATERMARK_REGEX.containsMatchIn(it.text) }

        var fallbackBlocks = 0
        blockRefs.chunked(MAX_TEXTS_PER_REQUEST).forEach { batch ->
            val translations = translateBatch(batch.map { it.text })
            batch.zip(translations).forEach { (ref, translatedText) ->
                if (translatedText.isBlank()) fallbackBlocks++
                ref.block.translation = translatedText.ifBlank { ref.block.text }
            }
        }
        if (blockRefs.isNotEmpty() && fallbackBlocks == blockRefs.size) {
            error(
                "DeepL translation failed — none of the ${blockRefs.size} text blocks received a translation. " +
                    "Check your DeepL API key and network connection."
            )
        }

        pages.values.forEach { page ->
            page.blocks = page.blocks
                .mapNotNull { block -> block.takeUnless { it.shouldBeDropped() } }
                .toMutableList()
        }
    }

    private fun translateBatch(texts: List<String>): List<String> {
        val request = JSONObject()
            .put("text", JSONArray(texts))
            .put("source_lang", sourceLanguage.toDeepLSourceCode())
            .put("target_lang", targetLanguage.toDeepLTargetCode())
            .put("context", texts.joinToString("\n").take(MAX_CONTEXT_CHARS))
            .put("split_sentences", "nonewlines")
            .put("preserve_formatting", true)

        val response = httpPostJson(
            url = "${deepLBaseUrl()}/v2/translate",
            body = request.toString(),
            headers = mapOf("Authorization" to "DeepL-Auth-Key ${apiKey.trim()}"),
        )
        val translations = JSONObject(response).getJSONArray("translations")
        return List(translations.length()) { index ->
            translations.getJSONObject(index).getString("text").trim()
        }
    }

    private fun deepLBaseUrl(): String {
        return if (apiKey.trim().endsWith(":fx")) {
            "https://api-free.deepl.com"
        } else {
            "https://api.deepl.com"
        }
    }

    override fun close() = Unit

    private data class BlockRef(
        val block: TranslationBlock,
        val text: String,
    )

    private companion object {
        const val MAX_TEXTS_PER_REQUEST = 50
        const val MAX_CONTEXT_CHARS = 8_000
    }
}

/**
 * Gemini translation engine tuned to spend as little API quota as possible.
 *
 * Only text is sent (OCR runs on-device). Per run:
 * - blocks that already have a translation are skipped, so a retry only sends what is left
 * - watermark/URL blocks are never sent
 * - identical lines are sent once and the result is fanned out to every block
 * - lines seen before (any chapter) come from [GeminiTranslationMemory] for free
 * - a typical chapter fits in one request; progress is reported after every request
 *   so the caller can save it, and a failure keeps everything translated so far
 * - thinking is turned down to the minimum the model allows (thinking tokens are billed)
 */
private class GeminiTranslationEngine(
    cacheDir: File,
    private val sourceLanguage: SourceLanguage,
    private val targetLanguage: TargetLanguage,
    private val apiKey: String,
    private val modelName: String,
    private val onChunkDone: (done: Int, total: Int) -> Unit = { _, _ -> },
) : TextTranslationEngine {

    /** One unique line of dialogue and every block that shows it. */
    private data class DialogueEntry(
        val globalId: Int,
        val pageName: String,
        val text: String,
        val targets: List<TranslationBlock>,
    )

    init {
        GeminiTranslationMemory.attach(cacheDir)
    }

    /** Returns (request count, blocks to be translated) without calling the API. */
    fun plan(pages: Map<String, PageTranslation>): Pair<Int, Int> {
        val pending = collectPending(pages, applyMemoryHits = false)
        return packChunks(pending).size to pending.sumOf { it.targets.size }
    }

    override fun translate(pages: MutableMap<String, PageTranslation>) {
        require(apiKey.isNotBlank()) { "Gemini API key is required for the Gemini translator." }
        require(modelName.isNotBlank()) { "Gemini model name is required." }

        val pending = collectPending(pages, applyMemoryHits = true)
        val chunks = packChunks(pending)

        var failure: Exception? = null
        var translatedEntries = 0
        for ((chunkIndex, chunk) in chunks.withIndex()) {
            // Pace consecutive requests to stay under per-minute limits.
            if (chunkIndex > 0) Thread.sleep(INTER_REQUEST_DELAY_MS)
            val resultMap = try {
                translateChunk(chunk, depth = 0)
            } catch (e: Exception) {
                failure = e
                break
            }

            val learned = HashMap<String, String>()
            for (entry in chunk) {
                val translation = resultMap[entry.globalId]
                    ?.takeIf { it.isNotBlank() && it != "NULL" }
                    ?: continue
                entry.targets.forEach { it.translation = translation }
                learned[memoryKey(entry.text)] = translation
                translatedEntries++
            }
            GeminiTranslationMemory.putAll(learned)
            onChunkDone(chunkIndex + 1, chunks.size)
        }
        GeminiTranslationMemory.persist()

        failure?.let { error ->
            if (translatedEntries == 0) throw error
            throw IllegalStateException(
                "Gemini stopped after translating $translatedEntries of ${pending.size} lines: ${error.message} " +
                    "Progress was saved — translate again later and only the remaining lines will be sent.",
                error,
            )
        }

        if (pending.isNotEmpty()) {
            val missing = pending.size - translatedEntries
            if (translatedEntries == 0) {
                error(
                    "Gemini translation failed — none of the ${pending.size} lines could be translated. " +
                        "Check your Gemini API key and model (\"$modelName\") in Settings."
                )
            }
            if (pending.size > 2 && missing > pending.size / 2) {
                error(
                    "Gemini translation mostly failed — only $translatedEntries of ${pending.size} lines were translated. " +
                        "Progress was saved; translate again to retry the rest."
                )
            }
        }

        // Drop watermark / junk blocks
        pages.values.forEach { page ->
            page.blocks = page.blocks
                .mapNotNull { block -> block.takeUnless { it.shouldBeDropped() } }
                .toMutableList()
        }
    }

    /**
     * Collects the lines that actually need an API call: untranslated, not a
     * watermark, de-duplicated, and not already in the translation memory.
     * Memory hits are written straight onto the blocks when [applyMemoryHits].
     */
    private fun collectPending(
        pages: Map<String, PageTranslation>,
        applyMemoryHits: Boolean,
    ): List<DialogueEntry> {
        val groups = LinkedHashMap<String, Triple<String, String, MutableList<TranslationBlock>>>()
        for ((pageName, page) in pages) {
            for (block in page.blocks) {
                if (block.translation.isNotBlank()) continue
                val text = block.text.trim()
                if (text.isEmpty() || WATERMARK_REGEX.containsMatchIn(text)) continue

                val remembered = GeminiTranslationMemory.get(memoryKey(text))
                if (remembered != null) {
                    if (applyMemoryHits) block.translation = remembered
                    continue
                }
                groups.getOrPut(normaliseForCache(text)) { Triple(pageName, text, mutableListOf()) }
                    .third += block
            }
        }
        return groups.values.mapIndexed { index, (pageName, text, targets) ->
            DialogueEntry(index, pageName, text, targets)
        }
    }

    // ── Chunk Packer ────────────────────────────────────────────────────────

    /**
     * Groups [entries] into translation chunks, each targeting ≤ [TOKEN_BUDGET]
     * estimated input tokens.  Boundaries always fall between pages so that
     * same-page dialogue is never split.
     */
    private fun packChunks(entries: List<DialogueEntry>): List<List<DialogueEntry>> {
        if (entries.isEmpty()) return emptyList()

        val pageGroups = entries.groupBy { it.pageName }.values

        val chunks = mutableListOf<List<DialogueEntry>>()
        var currentChunk = mutableListOf<DialogueEntry>()
        var currentTokens = 0

        for (pageEntries in pageGroups) {
            val pageTokens = pageEntries.sumOf { estimateTokens(it.text) }

            // If adding this page would exceed the budget, flush the current chunk
            // (unless it's empty — a single huge page still goes through)
            if (currentChunk.isNotEmpty() && currentTokens + pageTokens > TOKEN_BUDGET) {
                chunks += currentChunk.toList()
                currentChunk = mutableListOf()
                currentTokens = 0
            }

            currentChunk += pageEntries
            currentTokens += pageTokens
        }
        if (currentChunk.isNotEmpty()) {
            chunks += currentChunk.toList()
        }
        return chunks
    }

    /**
     * Estimate the number of tokens a text string will consume.
     * CJK characters ≈ 1.5 tokens/char; Latin ≈ 0.25 tokens/char (≈4 chars/token).
     * We also add a small overhead per entry for the `[[id,"…"]]` framing.
     */
    private fun estimateTokens(text: String): Int {
        var tokens = ENTRY_OVERHEAD_TOKENS
        for (ch in text) {
            tokens += if (ch.code > 0x2E80) 2 else 1  // CJK range heuristic (slightly overcount for safety)
        }
        return (tokens * 10 + 14) / 15  // ≈ ×0.67 adjustment to approximate real tokeniser
    }

    // ── Compact Wire Format & API Call ──────────────────────────────────────

    /**
     * Translates one chunk. On a malformed or truncated response the chunk is
     * split in half, at most [MAX_SPLIT_DEPTH] times; past that the lines are
     * left untranslated for the next run rather than spending more requests.
     * API and network errors are not split — they were already retried and
     * splitting would only burn more quota.
     */
    private fun translateChunk(chunk: List<DialogueEntry>, depth: Int): Map<Int, String> {
        return try {
            val raw = callGemini(buildCompactInput(chunk))
            parseCompactOutput(raw, chunk)
        } catch (e: Exception) {
            if (e is GeminiApiException || e is HttpException || e is IOException) throw e
            if (depth >= MAX_SPLIT_DEPTH || chunk.size < 2) return emptyMap()

            val mid = chunk.size / 2
            Thread.sleep(INTER_REQUEST_DELAY_MS)
            val firstHalf = translateChunk(chunk.subList(0, mid), depth + 1)
            Thread.sleep(INTER_REQUEST_DELAY_MS)
            firstHalf + translateChunk(chunk.subList(mid, chunk.size), depth + 1)
        }
    }

    private fun buildCompactInput(chunk: List<DialogueEntry>): String {
        val sb = StringBuilder(chunk.size * 40)
        sb.append('[')
        chunk.forEachIndexed { index, entry ->
            if (index > 0) sb.append(',')
            sb.append('[')
            sb.append(entry.globalId)
            sb.append(',')
            sb.append(jsonEscape(entry.text))
            sb.append(']')
        }
        sb.append(']')
        return sb.toString()
    }

    private fun jsonEscape(text: String): String {
        val sb = StringBuilder(text.length + 2)
        sb.append('"')
        for (ch in text) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /**
     * Calls the Gemini API and returns the raw response text.
     * - 429/503: waits as long as the server asks (RetryInfo), else exponential backoff
     * - network errors: retried once
     * - a 400 about thinking settings: retried with the next weaker thinking config
     */
    private fun callGemini(compactInput: String): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/${modelName.trim()}:generateContent"
        val headers = mapOf("x-goog-api-key" to apiKey.trim())

        var attempt = 0
        var networkRetries = 0
        while (true) {
            val thinking = GeminiThinking.configFor(modelName)
            try {
                val response = httpPostJson(
                    url = url,
                    body = buildGeminiPayload(systemPrompt(), compactInput, modelName, thinking).toString(),
                    headers = headers,
                )

                val root = JSONObject(response)
                root.optJSONObject("error")?.let { error ->
                    throw GeminiApiException(
                        statusCode = error.optInt("code", 0),
                        retryable = false,
                        message = formatGeminiErrorMessage(
                            errorBody = error,
                            fallbackMessage = error.optString("message", response),
                            modelName = modelName,
                        ),
                    )
                }

                val candidate = root.getJSONArray("candidates").getJSONObject(0)
                if (candidate.optString("finishReason", "") == "MAX_TOKENS") {
                    error("Gemini response truncated (MAX_TOKENS). Chunk too large.")
                }
                return candidate.responseText()
            } catch (e: HttpException) {
                if (e.statusCode == 400 && thinking != null && e.responseBody.contains("thinking", ignoreCase = true)) {
                    GeminiThinking.markRejected(modelName)
                    continue
                }
                val geminiError = e.toGeminiApiException(modelName)
                val retryable = geminiError?.retryable ?: (e.statusCode in RETRYABLE_STATUS_CODES)
                if (!retryable || ++attempt >= MAX_API_RETRIES) {
                    throw geminiError ?: e
                }
                val serverDelayMs = e.retryDelaySeconds()?.let { it * 1_000L }
                val backoffMs = BASE_BACKOFF_MS * (1L shl (attempt - 1).coerceAtMost(4))
                Thread.sleep((serverDelayMs ?: backoffMs).coerceIn(1_000L, MAX_RETRY_WAIT_MS))
            } catch (e: IOException) {
                if (networkRetries++ >= 1) throw e
                Thread.sleep(BASE_BACKOFF_MS)
            }
        }
    }

    // ── JSON Stability Layer ────────────────────────────────────────────────

    private fun parseCompactOutput(raw: String, chunk: List<DialogueEntry>): Map<Int, String> {
        val array = repairAndParseArray(sanitiseJson(raw))

        val result = HashMap<Int, String>(chunk.size)
        val expectedIds = chunk.map { it.globalId }.toSet()

        for (i in 0 until array.length()) {
            val entry = array.optJSONArray(i) ?: continue
            if (entry.length() < 2) continue
            // The response schema returns ids as strings; optInt parses both.
            val id = entry.optInt(0, -1)
            val translation = entry.optString(1, "").trim()
            if (id in expectedIds && translation.isNotBlank()) {
                result[id] = translation
            }
        }

        return result
    }

    private fun sanitiseJson(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```")) {
            s = s.removePrefix("```json").removePrefix("```")
        }
        if (s.endsWith("```")) {
            s = s.removeSuffix("```")
        }
        s = s.trim()
        s = s.replace(Regex(",\\s*]"), "]")
        s = s.replace(Regex(",\\s*}"), "}")
        s = s.replace("﻿", "").replace("​", "")
        return s
    }

    private fun repairAndParseArray(text: String): JSONArray {
        runCatching { return JSONArray(text) }

        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start >= 0 && end > start) {
            runCatching { return JSONArray(text.substring(start, end + 1)) }
        }

        // If the LLM returned an object with a single key wrapping the array
        runCatching {
            val obj = JSONObject(text)
            val key = obj.keys().asSequence().firstOrNull()
            if (key != null) {
                val inner = obj.optJSONArray(key)
                if (inner != null) return inner
            }
        }

        error("JSON repair failed: could not parse Gemini response as [[id,\"text\"]] array.")
    }

    // ── Cache Helpers ───────────────────────────────────────────────────────

    private fun normaliseForCache(text: String): String {
        return text.trim()
            .replace(WHITESPACE_REGEX, " ")
            .lowercase(Locale.ROOT)
    }

    private fun memoryKey(text: String): String {
        return "${modelName.trim()}|${sourceLanguage.languageTag}>${targetLanguage.languageTag}|${normaliseForCache(text)}"
    }

    // ── Prompt ──────────────────────────────────────────────────────────────

    private fun systemPrompt(): String {
        return "You are a manga translator. Translate ${sourceLanguage.label} dialogue to concise natural ${targetLanguage.label}.\n" +
            "Input/output format: [[id,\"text\"],...]\n" +
            "Example:\n" +
            "Input: [[0,\"おい待て\"],[1,\"逃げろ！\"]]\n" +
            "Output: [[\"0\",\"Hey, wait!\"],[\"1\",\"Run!\"]]\n" +
            "Rules: Preserve IDs. Translate ALL text to ${targetLanguage.label}. Keep it short for speech bubbles. " +
            "Preserve names and honorifics. Watermarks/URLs → RTMTH. Return JSON array only."
    }

    override fun close() = Unit

    private companion object {
        /**
         * Target input token budget per request. Most chapters fit in one request;
         * current Flash/Flash-Lite models allow far more output than this needs.
         */
        const val TOKEN_BUDGET = 20_000

        /** Per-entry framing overhead: `[id,""]` ≈ 8 tokens */
        const val ENTRY_OVERHEAD_TOKENS = 8

        /** Maximum attempts for a single Gemini API call on 429/503 errors. */
        const val MAX_API_RETRIES = 5

        /** Initial backoff delay when the server gives no RetryInfo. */
        const val BASE_BACKOFF_MS = 2_000L

        /** Upper bound on a single wait before retrying. */
        const val MAX_RETRY_WAIT_MS = 60_000L

        /** How many times a malformed chunk may be halved before giving up on it. */
        const val MAX_SPLIT_DEPTH = 2

        /** Delay between consecutive requests (ms). */
        const val INTER_REQUEST_DELAY_MS = 1_500L

        val RETRYABLE_STATUS_CODES = setOf(429, 503)
        val WHITESPACE_REGEX = Regex("\\s+")
    }
}

/**
 * Process-wide translation memory, persisted in the app cache so repeated lines
 * (names, SFX, reactions) cost nothing in later runs and later chapters.
 * Keyed by model + language pair + normalised text; least-recently-used entries
 * are evicted past [MAX_ENTRIES].
 */
private object GeminiTranslationMemory {
    private const val FILE_NAME = "gemini_translation_memory.json"
    private const val MAX_ENTRIES = 5_000

    private var file: File? = null
    private var dirty = false
    private val entries = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > MAX_ENTRIES
        }
    }

    @Synchronized
    fun attach(cacheDir: File) {
        if (file != null) return
        val target = File(cacheDir, FILE_NAME)
        file = target
        if (!target.exists()) return
        runCatching {
            val json = JSONObject(target.readText())
            json.keys().forEach { key -> entries[key] = json.getString(key) }
        }
    }

    @Synchronized
    fun get(key: String): String? = entries[key]

    @Synchronized
    fun putAll(values: Map<String, String>) {
        if (values.isEmpty()) return
        entries.putAll(values)
        dirty = true
    }

    @Synchronized
    fun persist() {
        val target = file ?: return
        if (!dirty) return
        runCatching {
            val json = JSONObject()
            entries.forEach { (key, value) -> json.put(key, value) }
            target.writeText(json.toString())
            dirty = false
        }
    }
}

/**
 * Picks the cheapest thinking setting each model accepts. Thinking tokens are
 * billed as output, so they are turned down as far as the model allows. If the
 * API rejects a setting, the next weaker option is used for the rest of the process.
 */
private object GeminiThinking {
    private val rejectedCount = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private fun candidates(modelName: String): List<JSONObject?> {
        val name = modelName.trim().lowercase(Locale.ROOT)
        val budget = { tokens: Int -> JSONObject().put("thinkingBudget", tokens) }
        val level = { value: String -> JSONObject().put("thinkingLevel", value) }
        return when {
            name.contains("gemini-1.") || name.contains("gemini-2.0") || name.startsWith("gemma") -> listOf(null)
            name.contains("gemini-2.5") && name.contains("pro") -> listOf(budget(128), null)
            name.contains("gemini-2.5") -> listOf(budget(0), null)
            name.contains("pro") -> listOf(level("low"), null)
            // Gemini 3+ Flash / Flash-Lite and the `-latest` aliases.
            else -> listOf(level("minimal"), level("low"), budget(0), null)
        }
    }

    fun configFor(modelName: String): JSONObject? {
        val options = candidates(modelName)
        val index = rejectedCount[modelName.trim()] ?: 0
        return options.getOrNull(index)
    }

    fun markRejected(modelName: String) {
        rejectedCount.merge(modelName.trim(), 1, Int::plus)
    }
}

private fun buildGeminiPayload(
    systemPrompt: String,
    userText: String,
    modelName: String,
    thinkingConfig: JSONObject?,
): JSONObject {
    val generationConfig = JSONObject()
        .put("responseMimeType", "application/json")
        .put(
            "responseSchema",
            JSONObject()
                .put("type", "ARRAY")
                .put(
                    "items",
                    JSONObject()
                        .put("type", "ARRAY")
                        .put("items", JSONObject().put("type", "STRING")),
                ),
        )
    // Gemini 3+ is tuned for the default temperature (lower values can cause
    // looping); older models translate more consistently at a low temperature.
    val name = modelName.lowercase(Locale.ROOT)
    if (name.contains("gemini-2.") || name.contains("gemini-1.")) {
        generationConfig.put("temperature", 0.3)
    }
    if (thinkingConfig != null) {
        generationConfig.put("thinkingConfig", thinkingConfig)
    }

    return JSONObject()
        .put(
            "system_instruction",
            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))),
        )
        .put(
            "contents",
            JSONArray().put(
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", userText))),
            ),
        )
        .put("generationConfig", generationConfig)
        .put("safetySettings", safetySettings())
}

/** Concatenates the non-thought text parts of a Gemini candidate. */
private fun JSONObject.responseText(): String {
    val parts = getJSONObject("content").getJSONArray("parts")
    return buildString {
        for (index in 0 until parts.length()) {
            val part = parts.optJSONObject(index) ?: continue
            if (part.optBoolean("thought", false)) continue
            append(part.optString("text", ""))
        }
    }
}

private fun HttpException.retryDelaySeconds(): Int? {
    val errorBody = runCatching { JSONObject(responseBody).optJSONObject("error") }.getOrNull() ?: return null
    return extractRetryDelaySeconds(errorBody)
}

private fun safetySettings(): JSONArray {
    return JSONArray(
        listOf(
            "HARM_CATEGORY_HARASSMENT",
            "HARM_CATEGORY_HATE_SPEECH",
            "HARM_CATEGORY_SEXUALLY_EXPLICIT",
            "HARM_CATEGORY_DANGEROUS_CONTENT",
        ).map { category ->
            JSONObject()
                .put("category", category)
                .put("threshold", "BLOCK_NONE")
        },
    )
}

private fun SourceLanguage.toRecognizerOptions(): TextRecognizerOptionsInterface {
    return when (this) {
        SourceLanguage.CHINESE -> ChineseTextRecognizerOptions.Builder().build()
        SourceLanguage.JAPANESE -> JapaneseTextRecognizerOptions.Builder().build()
        SourceLanguage.KOREAN -> KoreanTextRecognizerOptions.Builder().build()
        SourceLanguage.ENGLISH -> TextRecognizerOptions.DEFAULT_OPTIONS
    }
}

private fun SourceLanguage.toDeepLSourceCode(): String {
    return when (this) {
        SourceLanguage.CHINESE -> "ZH"
        SourceLanguage.JAPANESE -> "JA"
        SourceLanguage.KOREAN -> "KO"
        SourceLanguage.ENGLISH -> "EN"
    }
}

private fun TargetLanguage.toDeepLTargetCode(): String {
    return when (this) {
        TargetLanguage.ENGLISH -> "EN-US"
        TargetLanguage.PORTUGUESE -> "PT-BR"
        else -> languageTag.uppercase(Locale.ROOT)
    }
}

private val WATERMARK_REGEX =
    Regex("""(?i)(https?://\S+|www\.\S+|[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}|[a-z0-9-]+\.(com|org|net|io|gg|me|co)\S*)""")

private fun TranslationBlock.shouldBeDropped(): Boolean {
    return text.contains(WATERMARK_REGEX) || translation.contains(WATERMARK_REGEX) || translation.contains("RTMTH", ignoreCase = true)
}

private fun httpGet(url: String, headers: Map<String, String> = emptyMap()): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 20_000
    connection.readTimeout = 30_000
    connection.requestMethod = "GET"
    headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
    return connection.readResponse()
}

private fun httpPostJson(
    url: String,
    body: String,
    headers: Map<String, String> = emptyMap(),
): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 20_000
    connection.readTimeout = 60_000
    connection.requestMethod = "POST"
    connection.doOutput = true
    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
    headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
    connection.outputStream.use { output ->
        output.write(body.toByteArray(Charsets.UTF_8))
    }
    return connection.readResponse()
}

private fun HttpURLConnection.readResponse(): String {
    return try {
        val code = responseCode
        val responseBody = (if (code in 200..299) inputStream else errorStream)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            .orEmpty()
        if (code !in 200..299) {
            throw HttpException(
                statusCode = code,
                requestUrl = url.toString(),
                responseBody = responseBody,
                message = "HTTP $code from $url: $responseBody",
            )
        }
        responseBody
    } finally {
        disconnect()
    }
}

// ── Gemini API Test ─────────────────────────────────────────────────────────

sealed class GeminiTestResult {
    data class Success(val translatedText: String) : GeminiTestResult()
    data class Failure(val message: String) : GeminiTestResult()
}

/**
 * Sends a minimal translation request to the Gemini API to verify that the
 * API key and model name are valid and working.
 *
 * Must be called from a background thread (uses blocking HTTP).
 */
fun testGeminiConnection(apiKey: String, modelName: String): GeminiTestResult {
    if (apiKey.isBlank()) return GeminiTestResult.Failure("API key is empty.")
    if (modelName.isBlank()) return GeminiTestResult.Failure("Model name is empty.")

    val systemPrompt = "You are a manga translator. Translate Japanese dialogue to concise natural English.\n" +
        "Input/output format: [[id,\"text\"],...]\n" +
        "Rules: Translate ALL text to English. Return JSON array only."
    val testInput = "[[0,\"おい待て\"]]"

    return try {
        // Same payload shape as real translations, so this also validates the
        // thinking/schema settings for the chosen model.
        var response: String? = null
        while (response == null) {
            val thinking = GeminiThinking.configFor(modelName)
            try {
                response = httpPostJson(
                    url = "https://generativelanguage.googleapis.com/v1beta/models/${modelName.trim()}:generateContent",
                    body = buildGeminiPayload(systemPrompt, testInput, modelName, thinking).toString(),
                    headers = mapOf("x-goog-api-key" to apiKey.trim()),
                )
            } catch (e: HttpException) {
                if (e.statusCode == 400 && thinking != null && e.responseBody.contains("thinking", ignoreCase = true)) {
                    GeminiThinking.markRejected(modelName)
                } else {
                    throw e
                }
            }
        }

        val root = JSONObject(checkNotNull(response))
        root.optJSONObject("error")?.let { error ->
            return GeminiTestResult.Failure(
                formatGeminiErrorMessage(
                    errorBody = error,
                    fallbackMessage = error.optString("message", "Unknown error"),
                    modelName = modelName,
                )
            )
        }

        val candidates = root.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            // Check for prompt block (safety filter)
            val blockReason = root.optJSONObject("promptFeedback")
                ?.optString("blockReason", "")
                .orEmpty()
            return if (blockReason.isNotBlank()) {
                GeminiTestResult.Failure("Request blocked by safety filter: $blockReason")
            } else {
                GeminiTestResult.Failure("No response from model — it may be unavailable.")
            }
        }

        val text = candidates.getJSONObject(0).responseText()

        // Parse the response to extract the translated text
        val array = runCatching { JSONArray(text) }.getOrNull()
        val translated = array?.optJSONArray(0)?.optString(1, "")?.trim().orEmpty()

        if (translated.isNotBlank()) {
            GeminiTestResult.Success(translated)
        } else {
            GeminiTestResult.Success(text.take(100))  // Show raw response if parsing fails
        }
    } catch (e: HttpException) {
        val geminiError = e.toGeminiApiException(modelName)
        val statusHint = geminiError?.message ?: when (e.statusCode) {
            400 -> "Bad request — the model name \"$modelName\" may be invalid."
            401, 403 -> "Authentication failed — check your API key."
            404 -> "Model \"$modelName\" not found — pick a different model."
            429 -> "Rate limited — you're sending too many requests. Wait a minute."
            503 -> "Gemini service temporarily unavailable. Try again shortly."
            else -> "HTTP ${e.statusCode}"
        }
        GeminiTestResult.Failure(statusHint)
    } catch (e: Exception) {
        GeminiTestResult.Failure(e.message ?: "Unknown error")
    }
}

/**
 * Lists the Gemini text models this API key can call (`generateContent`),
 * leaving out image, audio, TTS, embedding and live-only variants.
 * `-latest` aliases come first, then newest versions first.
 *
 * Must be called from a background thread (uses blocking HTTP).
 */
fun fetchGeminiModels(apiKey: String): Result<List<String>> = runCatching {
    require(apiKey.isNotBlank()) { "API key is empty." }
    val excluded = listOf("image", "tts", "audio", "embedding", "live", "computer-use", "robotics", "aqa", "learnlm")
    val models = mutableListOf<String>()
    var pageToken: String? = null
    try {
        do {
            val url = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000" +
                (pageToken?.let { "&pageToken=" + URLEncoder.encode(it, Charsets.UTF_8.name()) } ?: "")
            val root = JSONObject(httpGet(url, mapOf("x-goog-api-key" to apiKey.trim())))
            val list = root.optJSONArray("models") ?: JSONArray()
            for (index in 0 until list.length()) {
                val model = list.optJSONObject(index) ?: continue
                val name = model.optString("name").removePrefix("models/")
                val methods = model.optJSONArray("supportedGenerationMethods") ?: continue
                val canGenerate = (0 until methods.length()).any { methods.optString(it) == "generateContent" }
                if (canGenerate && name.startsWith("gemini") && excluded.none { name.contains(it) }) {
                    models += name
                }
            }
            pageToken = root.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (pageToken != null)
    } catch (e: HttpException) {
        throw e.toGeminiApiException("") ?: e
    }
    models.distinct().sortedWith(
        compareByDescending<String> { it.endsWith("-latest") }
            .thenByDescending { it.contains("flash-lite") }
            .thenComparator { left, right -> naturalCompare(right, left) },
    )
}

/**
 * HTTP error with status code, allowing callers to distinguish
 * retryable errors (429, 503) from permanent failures (401, 403).
 */
private class HttpException(
    val statusCode: Int,
    val requestUrl: String,
    val responseBody: String,
    message: String,
) : Exception(message)

private class GeminiApiException(
    val statusCode: Int,
    val retryable: Boolean,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

private fun HttpException.toGeminiApiException(modelName: String): GeminiApiException? {
    if (!requestUrl.contains("generativelanguage.googleapis.com")) return null
    val root = runCatching { JSONObject(responseBody) }.getOrNull() ?: return null
    val errorBody = root.optJSONObject("error") ?: return null
    val parsedStatusCode = errorBody.optInt("code", statusCode).takeIf { it > 0 } ?: statusCode
    return GeminiApiException(
        statusCode = parsedStatusCode,
        retryable = isRetryableGeminiError(errorBody),
        message = formatGeminiErrorMessage(
            errorBody = errorBody,
            fallbackMessage = message ?: "Gemini request failed.",
            modelName = modelName,
        ),
        cause = this,
    )
}

private data class GeminiQuotaInfo(
    val quotaMetric: String?,
    val quotaId: String?,
    val quotaValue: String?,
    val model: String?,
)

private fun isRetryableGeminiError(errorBody: JSONObject): Boolean {
    val code = errorBody.optInt("code", 0)
    if (code == 503) return true
    if (code != 429) return false

    val quotaInfo = extractQuotaInfo(errorBody)
    val message = errorBody.optString("message", "")
    val hardQuotaExceeded =
        quotaInfo?.quotaId?.contains("PerDay", ignoreCase = true) == true ||
            quotaInfo?.quotaMetric?.contains("free_tier_requests", ignoreCase = true) == true ||
            message.contains("current quota", ignoreCase = true) ||
            message.contains("quota exceeded", ignoreCase = true)

    return !hardQuotaExceeded
}

private fun formatGeminiErrorMessage(
    errorBody: JSONObject,
    fallbackMessage: String,
    modelName: String,
): String {
    val code = errorBody.optInt("code", 0)
    val status = errorBody.optString("status", "")
    val message = errorBody.optString("message", fallbackMessage)
    val compactMessage = message.lineSequence().firstOrNull()?.trim().orEmpty()
    val quotaInfo = extractQuotaInfo(errorBody)
    val retryDelaySeconds = extractRetryDelaySeconds(errorBody)
    val quotaModel = quotaInfo?.model?.takeIf { it.isNotBlank() } ?: modelName.trim()

    return when {
        code == 400 && message.contains("API key", ignoreCase = true) -> "Invalid Gemini API key - check it in Settings."
        code == 400 -> "Bad request for model \"$modelName\": $compactMessage"
        code == 401 || code == 403 -> "Authentication failed - check your Gemini API key."
        code == 404 -> "Model \"$modelName\" not found - pick a different model."
        code == 429 && quotaInfo != null && !isRetryableGeminiError(errorBody) -> {
            val limitText = quotaInfo.quotaValue?.takeIf { it.isNotBlank() }?.let { "$it requests/day" }
            buildString {
                append("Gemini free-tier quota exhausted")
                if (quotaModel.isNotBlank()) {
                    append(" for \"$quotaModel\"")
                }
                if (limitText != null) {
                    append(" ($limitText)")
                }
                append(". Progress is saved. Switch to a Flash-Lite model (largest free quota), wait for the daily reset, or enable billing.")
            }
        }
        code == 429 -> {
            buildString {
                append("Gemini rate limited this request")
                if (retryDelaySeconds != null) {
                    append(". Retry in about $retryDelaySeconds seconds.")
                } else {
                    append(". Wait a minute and try again.")
                }
            }
        }
        code == 503 || status == "UNAVAILABLE" -> "Gemini service is temporarily unavailable. Try again shortly."
        compactMessage.isNotBlank() -> compactMessage
        else -> fallbackMessage
    }
}

private fun extractQuotaInfo(errorBody: JSONObject): GeminiQuotaInfo? {
    val details = errorBody.optJSONArray("details") ?: return null
    for (index in 0 until details.length()) {
        val detail = details.optJSONObject(index) ?: continue
        if (!detail.optString("@type").contains("QuotaFailure")) continue
        val violation = detail.optJSONArray("violations")?.optJSONObject(0) ?: continue
        val dimensions = violation.optJSONObject("quotaDimensions")
        return GeminiQuotaInfo(
            quotaMetric = violation.optString("quotaMetric", ""),
            quotaId = violation.optString("quotaId", ""),
            quotaValue = violation.optString("quotaValue", ""),
            model = dimensions?.optString("model", ""),
        )
    }
    return null
}

private fun extractRetryDelaySeconds(errorBody: JSONObject): Int? {
    val details = errorBody.optJSONArray("details") ?: return null
    for (index in 0 until details.length()) {
        val detail = details.optJSONObject(index) ?: continue
        if (!detail.optString("@type").contains("RetryInfo")) continue
        val retryDelay = detail.optString("retryDelay", "")
        val seconds = Regex("(\\d+)s").find(retryDelay)?.groupValues?.getOrNull(1)
        return seconds?.toIntOrNull()
    }
    return null
}

private fun parseJsonObject(text: String): JSONObject {
    val trimmed = text.trim()
        .removePrefix("```json")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()
    return runCatching { JSONObject(trimmed) }
        .getOrElse {
            val start = trimmed.indexOf('{')
            val end = trimmed.lastIndexOf('}')
            require(start >= 0 && end > start) { "Translator did not return a JSON object." }
            JSONObject(trimmed.substring(start, end + 1))
        }
}

private fun naturalCompare(left: String, right: String): Int {
    val leftParts = Regex("(\\d+|\\D+)").findAll(left).map { it.value }.toList()
    val rightParts = Regex("(\\d+|\\D+)").findAll(right).map { it.value }.toList()
    val maxSize = maxOf(leftParts.size, rightParts.size)
    for (index in 0 until maxSize) {
        val a = leftParts.getOrNull(index) ?: return -1
        val b = rightParts.getOrNull(index) ?: return 1
        val result = when {
            a.all(Char::isDigit) && b.all(Char::isDigit) -> compareDigitStrings(a, b)
            else -> a.compareTo(b, ignoreCase = true)
        }
        if (result != 0) return result
    }
    return 0
}

private fun compareDigitStrings(left: String, right: String): Int {
    val normalizedLeft = left.trimStart('0').ifEmpty { "0" }
    val normalizedRight = right.trimStart('0').ifEmpty { "0" }
    return when {
        normalizedLeft.length != normalizedRight.length -> normalizedLeft.length.compareTo(normalizedRight.length)
        else -> normalizedLeft.compareTo(normalizedRight)
    }
}
