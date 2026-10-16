package app.honyuka.translation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import app.honyuka.model.ChapterEntry
import app.honyuka.model.LocalBackend
import app.honyuka.model.LocalModel
import app.honyuka.model.PageTranslation
import app.honyuka.model.SourceLanguage
import app.honyuka.model.TargetLanguage
import app.honyuka.model.TranslationBlock
import app.honyuka.model.TranslationEngine
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
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
        localModel: LocalModel = LocalModel.DEFAULT,
        localBackend: LocalBackend = LocalBackend.AUTO,
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
            localModel = localModel,
            localBackend = localBackend,
            onChunkDone = { done, total ->
                onChunkSaved()
                onProgress(
                    "Translating text",
                    if (translationEngine == TranslationEngine.ON_DEVICE) {
                        "On-device: chunk $done of $total"
                    } else {
                        "Gemini request $done of $total done"
                    },
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
                    text = block.readingOrderText(),
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

    /**
     * ML Kit returns the lines of vertical Japanese in arbitrary order. Manga columns
     * read top-to-bottom, right-to-left, so vertical blocks are re-joined by right edge.
     */
    private fun Text.TextBlock.readingOrderText(): String {
        val boxed = lines.filter { it.boundingBox != null }
        if (boxed.size <= 1) return text
        val verticalCount = boxed.count { line ->
            val box = line.boundingBox!!
            box.height() > box.width() * VERTICAL_ASPECT
        }
        val ordered = if (verticalCount * 2 > boxed.size) {
            boxed.sortedByDescending { it.boundingBox!!.right }
        } else {
            boxed.sortedWith(compareBy({ it.boundingBox!!.top }, { it.boundingBox!!.left }))
        }
        return ordered.joinToString("\n") { it.text }
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
        // Two vertical columns side by side: the right one is read first.
        val bothVertical = first.height > first.width * VERTICAL_ASPECT && second.height > second.width * VERTICAL_ASPECT
        val (lead, follow) = if (bothVertical && second.x > first.x) second to first else first to second
        return TranslationBlock(
            text = "${lead.text}\n${follow.text}".trim(),
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

    private companion object {
        /** A line or block taller than this × its width is treated as vertical text. */
        const val VERTICAL_ASPECT = 1.3f
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
    localModel: LocalModel,
    localBackend: LocalBackend,
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
        TranslationEngine.ON_DEVICE -> LocalLlmTranslationEngine(
            context = context,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            model = localModel,
            backendPreference = localBackend,
            onChunkDone = onChunkDone,
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
                    if (!block.needsTranslation()) return@mapNotNull block.takeUnless { it.shouldBeDropped() }
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
                    block.translation = translatedText
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
                    if (!block.needsTranslation()) return@mapNotNull block.takeUnless { it.shouldBeDropped() }
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
                    block.translation = translatedText
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
                .filter { it.needsTranslation() }
                .map { block -> BlockRef(block = block, text = block.text.trim()) }
        }.filter { it.text.isNotBlank() && !WATERMARK_REGEX.containsMatchIn(it.text) }

        var fallbackBlocks = 0
        blockRefs.chunked(MAX_TEXTS_PER_REQUEST).forEach { batch ->
            val translations = translateBatch(batch.map { it.text })
            batch.zip(translations).forEach { (ref, translatedText) ->
                if (translatedText.isBlank()) fallbackBlocks++
                ref.block.translation = translatedText
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
 * Shared batching for LLM translators (Gemini API and on-device Gemma).
 *
 * Per run:
 * - blocks that already have a translation are skipped, so a retry only sends what is left
 * - watermark/URL blocks are never sent
 * - identical lines are sent once and the result is fanned out to every block
 * - lines seen before (any chapter) come from [GeminiTranslationMemory] for free
 * - lines are packed into chunks of [tokenBudget]; progress is reported after every chunk
 *   so the caller can save it, and a failure keeps everything translated so far
 */
private abstract class LlmBatchTranslationEngine(
    cacheDir: File,
    protected val sourceLanguage: SourceLanguage,
    protected val targetLanguage: TargetLanguage,
    private val onChunkDone: (done: Int, total: Int) -> Unit,
) : TextTranslationEngine {

    /** One unique line of dialogue and every block that shows it. */
    protected data class DialogueEntry(
        val globalId: Int,
        val pageName: String,
        val text: String,
        val targets: List<TranslationBlock>,
    )

    /** Shown in error messages, e.g. "Gemini" or "On-device model". */
    protected abstract val engineName: String

    /** Identifies the model in translation-memory keys. */
    protected abstract val memoryModelKey: String

    /** Estimated input tokens per request. */
    protected abstract val tokenBudget: Int

    /** How many times a malformed chunk may be halved before its lines are skipped. */
    protected abstract val maxSplitDepth: Int

    /** Pause between requests (ms); 0 for on-device. */
    protected abstract val interRequestDelayMs: Long

    /** Sends one prompt and returns the raw model output. */
    protected abstract fun complete(systemPrompt: String, userText: String): String

    /**
     * Errors that must stop the run instead of splitting the chunk (quota,
     * network, model failed to load…). Malformed output is never fatal.
     */
    protected abstract fun isFatal(error: Exception): Boolean

    init {
        GeminiTranslationMemory.attach(cacheDir)
    }

    /** Returns (request count, blocks to be translated) without calling the model. */
    fun plan(pages: Map<String, PageTranslation>): Pair<Int, Int> {
        val pending = collectPending(pages, applyMemoryHits = false)
        return packChunks(pending).size to pending.sumOf { it.targets.size }
    }

    /** Called once before the first request (e.g. to load a local model). */
    protected open fun prepare() = Unit

    override fun translate(pages: MutableMap<String, PageTranslation>) {
        val pending = collectPending(pages, applyMemoryHits = true)
        val chunks = packChunks(pending)
        if (chunks.isNotEmpty()) prepare()

        var failure: Exception? = null
        var translatedEntries = 0
        for ((chunkIndex, chunk) in chunks.withIndex()) {
            if (chunkIndex > 0 && interRequestDelayMs > 0) Thread.sleep(interRequestDelayMs)
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
                "$engineName stopped after translating $translatedEntries of ${pending.size} lines: ${error.message} " +
                    "Progress was saved — translate again later and only the remaining lines will be sent.",
                error,
            )
        }

        if (pending.isNotEmpty()) {
            val missing = pending.size - translatedEntries
            if (translatedEntries == 0) {
                error(
                    "$engineName translation failed — none of the ${pending.size} lines could be translated. " +
                        "Check the translator settings and try again."
                )
            }
            if (pending.size > 2 && missing > pending.size / 2) {
                error(
                    "$engineName translation mostly failed — only $translatedEntries of ${pending.size} lines were translated. " +
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
     * Collects the lines that actually need the model: untranslated, not a
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
                if (!block.needsTranslation()) continue
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

    /**
     * Groups [entries] into chunks of ≤ [tokenBudget] estimated input tokens.
     * Boundaries fall between pages when possible so same-page dialogue stays
     * together; a single page bigger than the budget is split by line.
     */
    private fun packChunks(entries: List<DialogueEntry>): List<List<DialogueEntry>> {
        if (entries.isEmpty()) return emptyList()

        val chunks = mutableListOf<List<DialogueEntry>>()
        var currentChunk = mutableListOf<DialogueEntry>()
        var currentTokens = 0

        fun flush() {
            if (currentChunk.isNotEmpty()) chunks += currentChunk.toList()
            currentChunk = mutableListOf()
            currentTokens = 0
        }

        for (pageEntries in entries.groupBy { it.pageName }.values) {
            val pageTokens = pageEntries.sumOf { estimateTokens(it.text) }
            if (currentChunk.isNotEmpty() && currentTokens + pageTokens > tokenBudget) flush()
            if (pageTokens <= tokenBudget) {
                currentChunk += pageEntries
                currentTokens += pageTokens
            } else {
                for (entry in pageEntries) {
                    val tokens = estimateTokens(entry.text)
                    if (currentChunk.isNotEmpty() && currentTokens + tokens > tokenBudget) flush()
                    currentChunk += entry
                    currentTokens += tokens
                }
            }
        }
        flush()
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

    /**
     * Translates one chunk. On a malformed or truncated response the chunk is
     * split in half, at most [maxSplitDepth] times; past that the lines are
     * left untranslated for the next run.
     */
    private fun translateChunk(chunk: List<DialogueEntry>, depth: Int): Map<Int, String> {
        return try {
            parseCompactTranslations(complete(systemPrompt(), buildCompactInput(chunk)), chunk.map { it.globalId }.toSet())
        } catch (e: Exception) {
            Log.w("HonyukaLlm", "$engineName chunk of ${chunk.size} lines failed (depth $depth)", e)
            if (isFatal(e)) throw e
            if (depth >= maxSplitDepth || chunk.size < 2) return emptyMap()

            val mid = chunk.size / 2
            if (interRequestDelayMs > 0) Thread.sleep(interRequestDelayMs)
            val firstHalf = translateChunk(chunk.subList(0, mid), depth + 1)
            if (interRequestDelayMs > 0) Thread.sleep(interRequestDelayMs)
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
            sb.append(JSONObject.quote(entry.text))
            sb.append(']')
        }
        sb.append(']')
        return sb.toString()
    }

    private fun normaliseForCache(text: String): String {
        return text.trim()
            .replace(WHITESPACE_REGEX, " ")
            .lowercase(Locale.ROOT)
    }

    private fun memoryKey(text: String): String {
        return "$memoryModelKey|${sourceLanguage.languageTag}>${targetLanguage.languageTag}|${normaliseForCache(text)}"
    }

    protected open fun systemPrompt(): String = translationSystemPrompt(sourceLanguage, targetLanguage)

    override fun close() = Unit

    private companion object {
        /** Per-entry framing overhead: `[id,""]` ≈ 8 tokens */
        const val ENTRY_OVERHEAD_TOKENS = 8
        val WHITESPACE_REGEX = Regex("\\s+")
    }
}

/**
 * @param stringIds Gemini's response schema returns ids as strings; free-form local
 * models copy the example literally, so they get plain numeric ids.
 */
private fun translationSystemPrompt(source: SourceLanguage, target: TargetLanguage, stringIds: Boolean = true): String {
    val exampleOutput = if (stringIds) {
        "[[\"0\",\"Hey, wait!\"],[\"1\",\"Run!\"]]"
    } else {
        "[[0,\"Hey, wait!\"],[1,\"Run!\"]]"
    }
    return "You are a manga translator. Translate ${source.label} dialogue to concise natural ${target.label}.\n" +
        "Input/output format: [[id,\"text\"],...]\n" +
        "Example:\n" +
        "Input: [[0,\"おい待て\"],[1,\"逃げろ！\"]]\n" +
        "Output: $exampleOutput\n" +
        "Rules: Preserve IDs. Translate ALL text to ${target.label}. Keep it short for speech bubbles. " +
        "Preserve names and honorifics. Watermarks/URLs → RTMTH. Return JSON array only."
}

/** Parses `[[id,"text"],…]` model output (ids may be numbers or strings). */
private fun parseCompactTranslations(raw: String, expectedIds: Set<Int>): Map<Int, String> {
    val array = runCatching { repairAndParseArray(sanitiseJson(raw)) }.getOrElse { error ->
        return lenientParse(raw, expectedIds).ifEmpty { throw error }
    }
    val result = HashMap<Int, String>(expectedIds.size)
    for (i in 0 until array.length()) {
        val entry = array.optJSONArray(i) ?: continue
        if (entry.length() < 2) continue
        val id = entry.optInt(0, -1)
        val translation = entry.optString(1, "").trim()
        if (id in expectedIds && translation.isNotBlank()) {
            result[id] = translation
        }
    }
    return result
}

private val TRAILING_COMMA_ARRAY = Regex(",\\s*\\]")
private val TRAILING_COMMA_OBJECT = Regex(",\\s*\\}")

/**
 * Last-resort reader for almost-JSON such as `["1,"text"]` or `[2,""text"]`, which small
 * on-device models sometimes produce: pulls out each `[id, "text"]` pair by pattern.
 */
private val LENIENT_ENTRY = Regex("""\[\s*"?(\d+)"?\s*,\s*"+((?:[^"\\]|\\.)*)"\s*\]""")

private fun lenientParse(raw: String, expectedIds: Set<Int>): Map<Int, String> {
    val result = HashMap<Int, String>()
    for (match in LENIENT_ENTRY.findAll(raw)) {
        val id = match.groupValues[1].toIntOrNull() ?: continue
        if (id !in expectedIds) continue
        val body = match.groupValues[2]
        val text = runCatching { JSONArray("[\"$body\"]").getString(0) }.getOrDefault(body).trim()
        if (text.isNotBlank()) result[id] = text
    }
    return result
}

/** Strips markdown fences, trailing commas and zero-width chars from LLM JSON. */
private fun sanitiseJson(raw: String): String {
    var s = raw.trim()
    if (s.startsWith("```")) {
        s = s.removePrefix("```json").removePrefix("```")
    }
    if (s.endsWith("```")) {
        s = s.removeSuffix("```")
    }
    s = s.trim()
    // ] and } must be escaped: Android's ICU regex engine rejects them bare.
    s = s.replace(TRAILING_COMMA_ARRAY, "]")
    s = s.replace(TRAILING_COMMA_OBJECT, "}")
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

    error("JSON repair failed: could not parse model response as [[id,\"text\"]] array.")
}

/**
 * Gemini API translator, tuned to spend as little quota as possible: one
 * request per ~20k input tokens (most chapters take one), minimal thinking,
 * a response schema, and RetryInfo-aware retries.
 */
private class GeminiTranslationEngine(
    cacheDir: File,
    sourceLanguage: SourceLanguage,
    targetLanguage: TargetLanguage,
    private val apiKey: String,
    private val modelName: String,
    onChunkDone: (done: Int, total: Int) -> Unit = { _, _ -> },
) : LlmBatchTranslationEngine(cacheDir, sourceLanguage, targetLanguage, onChunkDone) {

    override val engineName = "Gemini"
    override val memoryModelKey = modelName.trim()
    override val tokenBudget = 20_000
    override val maxSplitDepth = 2
    override val interRequestDelayMs = 1_500L

    override fun translate(pages: MutableMap<String, PageTranslation>) {
        require(apiKey.isNotBlank()) { "Gemini API key is required for the Gemini translator." }
        require(modelName.isNotBlank()) { "Gemini model name is required." }
        super.translate(pages)
    }

    // API and network errors were already retried; splitting would only burn more quota.
    override fun isFatal(error: Exception): Boolean {
        return error is GeminiApiException || error is HttpException || error is IOException
    }

    /**
     * - 429/503: waits as long as the server asks (RetryInfo), else exponential backoff
     * - network errors: retried once
     * - a 400 about thinking settings: retried with the next weaker thinking config
     */
    override fun complete(systemPrompt: String, userText: String): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/${modelName.trim()}:generateContent"
        val headers = mapOf("x-goog-api-key" to apiKey.trim())

        var attempt = 0
        var networkRetries = 0
        while (true) {
            val thinking = GeminiThinking.configFor(modelName)
            try {
                val response = httpPostJson(
                    url = url,
                    body = buildGeminiPayload(systemPrompt, userText, modelName, thinking).toString(),
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

    private companion object {
        /** Maximum attempts for a single Gemini API call on 429/503 errors. */
        const val MAX_API_RETRIES = 5

        /** Initial backoff delay when the server gives no RetryInfo. */
        const val BASE_BACKOFF_MS = 2_000L

        /** Upper bound on a single wait before retrying. */
        const val MAX_RETRY_WAIT_MS = 60_000L

        val RETRYABLE_STATUS_CODES = setOf(429, 503)
    }
}

/** The on-device model failed to load or run; splitting the chunk won't help. */
private class LocalModelException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Translates with a downloaded Gemma model through LiteRT-LM — free, offline
 * and private, but much slower than the API. Chunks are small because the
 * on-device context window is small; each chunk gets a fresh conversation.
 */
private class LocalLlmTranslationEngine(
    private val context: Context,
    sourceLanguage: SourceLanguage,
    targetLanguage: TargetLanguage,
    private val model: LocalModel,
    private val backendPreference: LocalBackend,
    onChunkDone: (done: Int, total: Int) -> Unit,
) : LlmBatchTranslationEngine(context.cacheDir, sourceLanguage, targetLanguage, onChunkDone) {

    override val engineName = "On-device model"
    override val memoryModelKey = "local:${model.fileName}"
    override val tokenBudget = 900
    override val maxSplitDepth = 3
    override val interRequestDelayMs = 0L

    private var engine: Engine? = null

    override fun prepare() {
        val file = LocalModelManager.modelFile(context, model)
        if (!LocalModelManager.isDownloaded(context, model)) {
            throw LocalModelException("${model.label} isn't downloaded yet. Download it in Settings → On-device model first.")
        }
        // Some GPU drivers crash natively while LiteRT-LM builds the GPU engine (seen on
        // Tensor G4), which can't be caught. A marker file written before the GPU attempt
        // survives that crash; if it's still there next time, Auto skips the GPU for good.
        val gpuMarker = File(context.filesDir, GPU_CRASH_MARKER)
        val gpuCrashedBefore = gpuMarker.exists()
        val backends = when (backendPreference) {
            LocalBackend.AUTO -> if (gpuCrashedBefore) listOf(cpuBackend()) else listOf(Backend.GPU(), cpuBackend())
            LocalBackend.GPU -> listOf(Backend.GPU())
            LocalBackend.CPU -> listOf(cpuBackend())
        }
        var lastError: Throwable? = null
        for (backend in backends) {
            val isGpu = backend is Backend.GPU
            if (isGpu && backendPreference == LocalBackend.AUTO) gpuMarker.writeText("1")
            val candidate = Engine(
                EngineConfig(
                    modelPath = file.absolutePath,
                    backend = backend,
                    maxNumTokens = MAX_CONTEXT_TOKENS,
                    cacheDir = context.cacheDir.absolutePath,
                ),
            )
            try {
                candidate.initialize()
                if (isGpu) gpuMarker.delete()
                engine = candidate
                return
            } catch (t: Throwable) {
                lastError = t
                runCatching { candidate.close() }
            }
        }
        throw LocalModelException(
            "Couldn't load ${model.label} on this phone (${lastError?.message}). " +
                "Try the CPU backend or the smaller model in Settings.",
            lastError,
        )
    }

    /** Use most cores; LiteRT-LM's default CPU thread count is conservative. */
    private fun cpuBackend(): Backend {
        val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 8)
        return Backend.CPU(numOfThreads = threads)
    }

    override fun isFatal(error: Exception): Boolean = error is LocalModelException

    override fun systemPrompt(): String = translationSystemPrompt(sourceLanguage, targetLanguage, stringIds = false)

    override fun complete(systemPrompt: String, userText: String): String {
        val activeEngine = engine ?: throw LocalModelException("On-device model is not loaded.")
        val conversation = try {
            activeEngine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(systemPrompt),
                    samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.3, seed = 0),
                    maxOutputToken = MAX_OUTPUT_TOKENS,
                    thinkingConfig = ThinkingConfig(enableThinking = false),
                ),
            )
        } catch (t: Throwable) {
            throw LocalModelException("On-device model stopped responding: ${t.message}", t)
        }
        return conversation.use {
            val reply = it.sendMessage(userText)
            val text = reply.contents.contents
                .filterIsInstance<Content.Text>()
                .joinToString("") { part -> part.text }
            Log.d(TAG, "Local model reply: ${text.length} chars")
            text
        }
    }

    override fun close() {
        runCatching { engine?.close() }
        engine = null
    }

    private companion object {
        /** Prompt + output must fit; Gemma 4 E2B/E4B allow much more, but memory is tight. */
        const val MAX_CONTEXT_TOKENS = 4_096
        const val MAX_OUTPUT_TOKENS = 2_048
        const val GPU_CRASH_MARKER = "litertlm_gpu_attempt"
        const val TAG = "HonyukaLocalLlm"
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
