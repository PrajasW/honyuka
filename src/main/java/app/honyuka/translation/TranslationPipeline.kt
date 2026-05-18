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

    suspend fun translateChapter(
        chapter: ChapterEntry,
        chapterDocument: DocumentFile,
        sourceLanguage: SourceLanguage,
        targetLanguage: TargetLanguage,
        translationEngine: TranslationEngine,
        geminiApiKey: String,
        geminiModelName: String,
        deepLApiKey: String,
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

        onProgress("Translating text", "Using ${translationEngine.label} for manga dialogue", 0.65f)
        createTranslationEngine(
            engine = translationEngine,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            geminiApiKey = geminiApiKey,
            geminiModelName = geminiModelName,
            deepLApiKey = deepLApiKey,
        ).use { translator ->
            translator.translate(pageTranslations)
        }
        require(pageTranslations.isNotEmpty()) {
            "Pages loaded successfully, but no text bubbles were detected. This chapter may be image-light, stylized, or use an unsupported OCR source language."
        }
        onProgress("Saving sidecar", "Preparing reader-compatible JSON output", 0.95f)
        return pageTranslations
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
                PagePayload(file.name.orEmpty()) {
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
    engine: TranslationEngine,
    sourceLanguage: SourceLanguage,
    targetLanguage: TargetLanguage,
    geminiApiKey: String,
    geminiModelName: String,
    deepLApiKey: String,
): TextTranslationEngine {
    return when (engine) {
        TranslationEngine.GEMINI -> GeminiTranslationEngine(
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            apiKey = geminiApiKey,
            modelName = geminiModelName,
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
        pages.values.forEach { page ->
            page.blocks = page.blocks
                .mapNotNull { block ->
                    val translatedText = block.text
                        .split("\n")
                        .mapNotNull { line ->
                            line.trim()
                                .takeIf { it.isNotEmpty() }
                                ?.let { Tasks.await(translator.translate(it)) }
                                ?.takeIf { it.isNotBlank() }
                        }
                        .joinToString("\n")
                    block.translation = translatedText.ifBlank { block.text }
                    block.takeUnless { block.shouldBeDropped() }
                }
                .toMutableList()
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
        pages.values.forEach { page ->
            page.blocks = page.blocks
                .mapNotNull { block ->
                    val translatedText = block.text
                        .split("\n")
                        .map { line ->
                            line.trim()
                                .takeIf { it.isNotEmpty() }
                                ?.let(::translateLine)
                                .orEmpty()
                        }
                        .filter { it.isNotBlank() }
                        .joinToString("\n")
                    block.translation = translatedText.ifBlank { block.text }
                    block.takeUnless { block.shouldBeDropped() }
                }
                .toMutableList()
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
            page.blocks.map { block -> BlockRef(block = block, text = block.text.trim()) }
        }.filter { it.text.isNotBlank() }

        blockRefs.chunked(MAX_TEXTS_PER_REQUEST).forEach { batch ->
            val translations = translateBatch(batch.map { it.text })
            batch.zip(translations).forEach { (ref, translatedText) ->
                ref.block.translation = translatedText.ifBlank { ref.block.text }
            }
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

private class GeminiTranslationEngine(
    private val sourceLanguage: SourceLanguage,
    private val targetLanguage: TargetLanguage,
    private val apiKey: String,
    private val modelName: String,
) : TextTranslationEngine {

    override fun translate(pages: MutableMap<String, PageTranslation>) {
        require(apiKey.isNotBlank()) { "Gemini API key is required for the Gemini translator." }
        require(modelName.isNotBlank()) { "Gemini model name is required." }

        val batches = mutableListOf<List<Map.Entry<String, PageTranslation>>>()
        var currentBatch = mutableListOf<Map.Entry<String, PageTranslation>>()
        var currentChars = 0

        for (entry in pages.entries) {
            val pageChars = entry.value.blocks.sumOf { it.text.length }
            
            // If the batch has items AND adding this page exceeds either limit, start a new batch
            if (currentBatch.isNotEmpty() && (currentChars + pageChars > MAX_BATCH_CHARS || currentBatch.size >= MAX_BATCH_PAGES)) {
                batches.add(currentBatch)
                currentBatch = mutableListOf()
                currentChars = 0
            }
            
            currentBatch.add(entry)
            currentChars += pageChars
        }
        if (currentBatch.isNotEmpty()) {
            batches.add(currentBatch)
        }

        batches.forEach { batch ->
            val input = JSONObject()
            batch.forEach { (pageName, page) ->
                input.put(pageName, JSONArray(page.blocks.map { it.text }))
            }
            val output = try {
                requestTranslations(input)
            } catch (e: Exception) {
                // If batch fails, retry page-by-page
                if (batch.size > 1) {
                    val fallback = JSONObject()
                    batch.forEach { (pageName, page) ->
                        val singleInput = JSONObject()
                        singleInput.put(pageName, JSONArray(page.blocks.map { it.text }))
                        try {
                            val singleOutput = requestTranslations(singleInput)
                            singleOutput.optJSONArray(pageName)?.let { arr ->
                                fallback.put(pageName, arr)
                            }
                        } catch (_: Exception) {
                            // Skip this page on failure
                        }
                    }
                    fallback
                } else {
                    throw e
                }
            }
            applyTranslations(batch, output)
        }
    }

    private fun requestTranslations(input: JSONObject): JSONObject {
        val payload = JSONObject()
            .put(
                "system_instruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", systemPrompt())),
                ),
            )
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(
                            JSONObject().put(
                                "text",
                                "Translate this JSON object and return only JSON:\n$input",
                            ),
                        ),
                    ),
                ),
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseMimeType", "application/json")
                    .put("temperature", 0.3)
                    .put("topP", 0.8),
            )
            .put("safetySettings", safetySettings())

        val response = httpPostJson(
            url = "https://generativelanguage.googleapis.com/v1beta/models/${modelName.trim()}:generateContent",
            body = payload.toString(),
            headers = mapOf("x-goog-api-key" to apiKey.trim()),
        )
        val root = JSONObject(response)
        root.optJSONObject("error")?.let { error ->
            error("Gemini API error: ${error.optString("message", response)}")
        }
        val candidate = root
            .getJSONArray("candidates")
            .getJSONObject(0)

        val finishReason = candidate.optString("finishReason", "")
        if (finishReason == "MAX_TOKENS") {
            error("Gemini response was truncated (MAX_TOKENS). Retrying with smaller batch.")
        }

        val text = candidate
            .getJSONObject("content")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")
        return parseJsonObject(text)
    }

    private fun applyTranslations(
        batch: List<Map.Entry<String, PageTranslation>>,
        output: JSONObject,
    ) {
        batch.forEach { (pageName, page) ->
            val translatedBlocks = output.optJSONArray(pageName)
                ?: output.optJSONArray(pageName.substringBeforeLast('.'))
            page.blocks = page.blocks
                .mapIndexedNotNull { index, block ->
                    val translatedText = translatedBlocks
                        ?.optString(index)
                        ?.takeIf { it.isNotBlank() && it != "NULL" }
                        ?: block.text
                    block.translation = translatedText
                    block.takeUnless { block.shouldBeDropped() }
                }
                .toMutableList()
        }
    }

    private fun systemPrompt(): String {
        return """
            You are a professional manga and anime translator.

            Source language: ${sourceLanguage.label}.
            Target language: ${targetLanguage.label}.

            Requirements:
            - Preserve the exact JSON object shape: every page key must remain, and every array must keep the same item count and order.
            - Translate naturally for comic dialogue — preserve tone, emotion, and character personality.
            - Keep honorific nuance when relevant (e.g. -san, -chan, -senpai).
            - Keep translations concise — manga bubbles have limited space.
            - Do not over-explain or add commentary.
            - Handle incomplete sentences, slang, and onomatopoeia naturally.
            - Preserve names as-is unless there is a well-known localised form.
            - Replace watermarks, scanlation credits, URLs, and ads with RTMTH.
            - Return only a JSON object. Do not add markdown, comments, explanations, or extra keys.
        """.trimIndent()
    }

    override fun close() = Unit

    private companion object {
        const val MAX_BATCH_CHARS = 4000
        const val MAX_BATCH_PAGES = 20
    }
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

private fun TranslationBlock.shouldBeDropped(): Boolean {
    val watermarkRegex = Regex("""(?i)(https?://\S+|www\.\S+|[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}|[a-z0-9-]+\.(com|org|net|io|gg|me|co)\S*)""")
    return text.contains(watermarkRegex) || translation.contains(watermarkRegex) || translation.contains("RTMTH", ignoreCase = true)
}

private fun httpGet(url: String): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 20_000
    connection.readTimeout = 30_000
    connection.requestMethod = "GET"
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
        val responseBody = (if (responseCode in 200..299) inputStream else errorStream)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readText() }
            .orEmpty()
        if (responseCode !in 200..299) {
            error("HTTP $responseCode from $url: $responseBody")
        }
        responseBody
    } finally {
        disconnect()
    }
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
