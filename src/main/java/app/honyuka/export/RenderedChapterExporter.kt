package app.honyuka.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.util.Log
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import androidx.documentfile.provider.DocumentFile
import app.honyuka.R
import app.honyuka.data.HonyukaStorage
import app.honyuka.model.ChapterEntry
import app.honyuka.model.PageTranslation
import app.honyuka.model.TargetLanguage
import app.honyuka.model.TranslationBlock
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class RenderedChapterExporter(
    private val context: Context,
    private val storage: HonyukaStorage,
) {
    private companion object {
        const val TAG = "RenderedChapterExporter"
        const val RENDERED_JPEG_QUALITY = 95
        const val MIN_TEXT_SIZE_PX = 10
        const val MAX_TEXT_SIZE_PX = 48

        /** Font range grows with page width so large scans don't get tiny text. */
        const val PAGE_WIDTH_PER_MIN_TEXT_PX = 120
        const val PAGE_WIDTH_PER_MAX_TEXT_PX = 24

        /** Tall OCR columns (vertical Japanese) are widened at most this many times. */
        const val MAX_PLACEMENT_WIDEN_FACTOR = 3f
        const val MAX_COLLISION_SHIFT_STEPS = 6
        const val TEXT_COLLISION_PADDING_PX = 6f
        const val LIGHT_REGION_LUMINANCE_THRESHOLD = 220
        const val PRECISE_MASK_PADDING_PX = 2
        const val MIN_BALANCE_WIDTH_PX = 40
        const val BALANCE_WIDTH_STEP_PX = 4
        const val SOFT_OVERLAY_STEPS = 5
        val COLLISION_TEXT_SCALE_FACTORS = floatArrayOf(1f, 0.92f, 0.84f, 0.76f)

        /** Max pixel gap between two blocks to merge them at render time. */
        const val RENDER_MERGE_GAP_PX = 15f

        const val COMIC_INFO_FILE = "ComicInfo.xml"
        const val COVER_WIDTH_PX = 600f

        /** Image types Mihon's ImageUtil recognises by extension. */
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "heif", "heic", "jxl", "bmp")
    }

    private val translationTypeface: Typeface =
        ResourcesCompat.getFont(context, R.font.animeace) ?: Typeface.DEFAULT_BOLD

    /**
     * Renders the chapter into `local/<manga> (Honyuka)/<chapter>.cbz` — the layout
     * Mihon's Local Source reads. Also keeps the manga folder's `ComicInfo.xml`
     * and `cover.jpg` in sync so Mihon shows the right title and cover.
     *
     * @param translatorLabel engine name for ComicInfo's Translator (null on re-render)
     */
    fun exportToLocalSource(
        chapter: ChapterEntry,
        translations: Map<String, PageTranslation>,
        translatorLabel: String? = null,
        targetLanguage: TargetLanguage? = null,
    ) {
        if (translations.isEmpty()) return

        val sourceChapter = requireNotNull(storage.resolveOriginalChapterDocument(chapter)) {
            "Unable to locate the original chapter in downloads."
        }
        val metadata = ExportMetadata(
            seriesTitle = storage.localSeriesTitle(chapter),
            fallbackChapterTitle = readableChapterTitle(chapter.chapterBaseName),
            translator = buildString {
                append("Honyuka")
                if (translatorLabel != null) {
                    append(" (")
                    append(translatorLabel)
                    if (targetLanguage != null) append(" → ${targetLanguage.label}")
                    append(')')
                }
            },
        )

        val localTarget = storage.prepareLocalSourceExportTarget(chapter)
        val result = writeArchive(localTarget, translations, metadata) { addEntry ->
            if (chapter.isCbz) {
                readArchiveEntries(sourceChapter, addEntry)
            } else {
                readDirectoryEntries(sourceChapter, "", addEntry)
            }
        }

        // Mihon prefers a top-level ComicInfo.xml over the chapter's copy, so keep it
        // pointing at the "(Honyuka)" title (older exports let it copy the original).
        storage.writeLocalMangaFile(chapter, COMIC_INFO_FILE, result.comicInfo.toByteArray(Charsets.UTF_8))
        if (!storage.hasLocalCover(chapter)) {
            result.firstPageBytes?.let { pageBytes ->
                storage.writeLocalMangaFileIfMissing(chapter, "cover.jpg") { buildCoverBytes(pageBytes) }
            }
        }
    }

    private fun readArchiveEntries(
        sourceArchive: DocumentFile,
        addEntry: (path: String, bytes: ByteArray) -> Unit,
    ) {
        val tempInput = File.createTempFile("honyuka-source-", ".cbz", context.cacheDir)
        try {
            context.contentResolver.openInputStream(sourceArchive.uri).use { input ->
                requireNotNull(input) { "Unable to open chapter archive." }
                tempInput.outputStream().use { output -> input.copyTo(output) }
            }
            ZipFile(tempInput).use { zipFile ->
                zipFile.entries().asSequence()
                    .filterNot { it.isDirectory }
                    .forEach { entry ->
                        addEntry(entry.name, zipFile.getInputStream(entry).use { it.readBytes() })
                    }
            }
        } finally {
            tempInput.delete()
        }
    }

    private fun readDirectoryEntries(
        directory: DocumentFile,
        prefix: String,
        addEntry: (path: String, bytes: ByteArray) -> Unit,
    ) {
        directory.listFiles().forEach { child ->
            val name = child.name.orEmpty()
            when {
                // Skip hidden files such as Mihon's `.nomedia`.
                name.isEmpty() || name.startsWith(".") -> Unit
                child.isDirectory -> readDirectoryEntries(child, "$prefix$name/", addEntry)
                child.isFile -> {
                    val bytes = context.contentResolver.openInputStream(child.uri).use { input ->
                        requireNotNull(input) { "Unable to open source page $name." }
                        input.readBytes()
                    }
                    addEntry("$prefix$name", bytes)
                }
            }
        }
    }

    /**
     * Writes a CBZ to [targetArchive]. [collect] feeds every source file through
     * `addEntry(path, bytes)`: translated pages are rendered, `ComicInfo.xml` is
     * rewritten (or created), and duplicate entry names — e.g. `001.webp` and
     * `001.png` both rendering to `001.png` — are skipped instead of crashing.
     */
    private fun writeArchive(
        targetArchive: DocumentFile,
        translations: Map<String, PageTranslation>,
        metadata: ExportMetadata,
        collect: (addEntry: (path: String, bytes: ByteArray) -> Unit) -> Unit,
    ): ArchiveExportResult {
        val tempOutput = File.createTempFile("honyuka-rendered-", ".cbz", context.cacheDir)
        try {
            var originalComicInfo: String? = null
            var firstPage: Pair<String, ByteArray>? = null
            val writtenNames = HashSet<String>()

            ZipOutputStream(tempOutput.outputStream()).use { zipOutput ->
                collect { path, bytes ->
                    val leafName = path.substringAfterLast('/')
                    if (leafName.equals(COMIC_INFO_FILE, ignoreCase = true)) {
                        if (originalComicInfo == null) originalComicInfo = bytes.toString(Charsets.UTF_8)
                        return@collect
                    }

                    val pageKey = leafName.trim()
                    val pageTranslation = translations[pageKey]
                    val rendered = if (pageTranslation != null) {
                        renderPage(bytes, leafName, pageTranslation)
                    } else {
                        RenderedPage(leafName, bytes)
                    }
                    val entryName = replaceLeafName(path, rendered.fileName)
                    if (!writtenNames.add(entryName.lowercase(Locale.ROOT))) return@collect

                    zipOutput.putNextEntry(ZipEntry(entryName))
                    zipOutput.write(rendered.bytes)
                    zipOutput.closeEntry()

                    if (isImageFileName(rendered.fileName)) {
                        val current = firstPage
                        if (current == null || entryName.compareTo(current.first, ignoreCase = true) < 0) {
                            firstPage = entryName to rendered.bytes
                        }
                    }
                }

                val comicInfo = buildComicInfo(originalComicInfo, metadata)
                zipOutput.putNextEntry(ZipEntry(COMIC_INFO_FILE))
                zipOutput.write(comicInfo.toByteArray(Charsets.UTF_8))
                zipOutput.closeEntry()

                context.contentResolver.openOutputStream(targetArchive.uri, "wt").use { output ->
                    requireNotNull(output) { "Unable to write local source export archive." }
                    zipOutput.finish()
                    tempOutput.inputStream().use { input -> input.copyTo(output) }
                }
                return ArchiveExportResult(comicInfo, firstPage?.second)
            }
        } finally {
            tempOutput.delete()
        }
    }

    /**
     * Rewrites ComicInfo.xml so Mihon groups the chapter under "<manga> (Honyuka)"
     * and credits the translator. Other fields (Title, Number, Summary, Genre…)
     * are kept; a minimal file is created if the source had none.
     */
    private fun buildComicInfo(original: String?, metadata: ExportMetadata): String {
        val base = original
            ?.takeIf { it.contains("</ComicInfo>") }
            ?: ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<ComicInfo xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\" " +
                "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n" +
                "  <Title>${xmlEscape(metadata.fallbackChapterTitle)}</Title>\n" +
                "</ComicInfo>\n")

        // Keep the original scanlator (Mihon shows Translator as the chapter's
        // scanlator) and append Honyuka, replacing any credit from an earlier export.
        val existingTranslator = Regex("<Translator>(.*?)</Translator>", RegexOption.DOT_MATCHES_ALL)
            .find(base)?.groupValues?.get(1).orEmpty()
            .substringBefore("Honyuka")
            .trim().trimEnd(',').trim()
        val translatorXml = listOf(existingTranslator, xmlEscape(metadata.translator))
            .filter { it.isNotBlank() }
            .joinToString(", ")

        val withSeries = setXmlElement(base, "Series", xmlEscape(metadata.seriesTitle))
        return setXmlElement(withSeries, "Translator", translatorXml)
    }

    /** Sets `<tag>content</tag>` (content already XML-escaped), adding it if missing. */
    private fun setXmlElement(xml: String, tag: String, content: String): String {
        val pattern = Regex("<$tag>.*?</$tag>|<$tag\\s*/>", RegexOption.DOT_MATCHES_ALL)
        return if (pattern.containsMatchIn(xml)) {
            pattern.replaceFirst(xml, Regex.escapeReplacement("<$tag>$content</$tag>"))
        } else {
            xml.replaceFirst("</ComicInfo>", "  <$tag>$content</$tag>\n</ComicInfo>")
        }
    }

    private fun xmlEscape(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }

    /** Mihon folder names look like `<scanlator>_<chapter>_<6-char hash>`; drop the hash. */
    private fun readableChapterTitle(chapterBaseName: String): String {
        return chapterBaseName.replace(Regex("_[0-9a-f]{6}$"), "").ifBlank { chapterBaseName }
    }

    /** A ~600px-wide JPEG of the first page for Mihon's library grid. */
    private fun buildCoverBytes(pageBytes: ByteArray): ByteArray? {
        val bitmap = runCatching { decodeBitmap(pageBytes, "cover") }.getOrNull() ?: return null
        return try {
            val scale = min(1f, COVER_WIDTH_PX / bitmap.width.toFloat())
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt(), (bitmap.height * scale).roundToInt(), true)
            } else {
                bitmap
            }
            try {
                ByteArrayOutputStream().use { output ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 90, output)
                    output.toByteArray()
                }
            } finally {
                if (scaled != bitmap) scaled.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun isImageFileName(name: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension in IMAGE_EXTENSIONS
    }

    private fun renderPage(
        originalBytes: ByteArray,
        pageName: String,
        translation: PageTranslation,
    ): RenderedPage {
        val drawableBlocks = translation.blocks.filter { it.translation.isNotBlank() }
        if (drawableBlocks.isEmpty()) {
            return RenderedPage(pageName, originalBytes)
        }

        val sourceBitmap = runCatching { decodeBitmap(originalBytes, pageName) }.getOrElse { error ->
            Log.w(TAG, "Keeping original page; could not decode $pageName", error)
            return RenderedPage(pageName, originalBytes)
        }
        val bitmap = sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
        if (bitmap != sourceBitmap) {
            sourceBitmap.recycle()
        }

        val outputFormat = outputFormatForPage(pageName)
        try {
            val canvas = Canvas(bitmap)
            val mergedBlocks = mergeOverlappingRenderBlocks(drawableBlocks)
            val renderPlans = buildRenderPlans(mergedBlocks, bitmap.width, bitmap.height, bitmap)
            renderPlans.forEach { plan -> cleanupSourceText(bitmap, canvas, plan) }
            renderPlans.forEach { plan -> drawTranslationBackground(canvas, plan) }
            renderPlans.forEach { plan -> drawTranslationText(canvas, plan) }
            return RenderedPage(
                fileName = translatedPageName(pageName, outputFormat),
                bytes = compressBitmap(bitmap, outputFormat),
            )
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Merges translation blocks whose bounding boxes overlap or are very close
     * (within RENDER_MERGE_GAP_PX pixels). This prevents adjacent narrow blocks
     * from rendering English text in unreadable vertical columns — instead they
     * are combined into a single wider region.
     */
    private fun mergeOverlappingRenderBlocks(
        blocks: List<TranslationBlock>,
    ): List<TranslationBlock> {
        if (blocks.size <= 1) return blocks

        val merged = mutableListOf<TranslationBlock>()
        val used = BooleanArray(blocks.size)

        for (i in blocks.indices) {
            if (used[i]) continue
            var current = blocks[i]
            used[i] = true
            var changed = true
            while (changed) {
                changed = false
                for (j in blocks.indices) {
                    if (used[j]) continue
                    if (renderRectsOverlapOrClose(current, blocks[j])) {
                        current = mergeRenderBlocks(current, blocks[j])
                        used[j] = true
                        changed = true
                    }
                }
            }
            merged += current
        }
        return merged
    }

    private fun renderRectsOverlapOrClose(
        a: TranslationBlock,
        b: TranslationBlock,
    ): Boolean {
        val gap = RENDER_MERGE_GAP_PX
        val aLeft = a.x - gap
        val aTop = a.y - gap
        val aRight = a.x + a.width + gap
        val aBottom = a.y + a.height + gap
        return !(b.x + b.width < aLeft || b.x > aRight || b.y + b.height < aTop || b.y > aBottom)
    }

    private fun mergeRenderBlocks(
        a: TranslationBlock,
        b: TranslationBlock,
    ): TranslationBlock {
        val newX = min(a.x, b.x)
        val newY = min(a.y, b.y)
        val newRight = max(a.x + a.width, b.x + b.width)
        val newBottom = max(a.y + a.height, b.y + b.height)
        // Combine translations with line break to preserve readability
        val combinedTranslation = if (a.y <= b.y) {
            "${a.translation} ${b.translation}"
        } else {
            "${b.translation} ${a.translation}"
        }
        return TranslationBlock(
            text = "${a.text} ${b.text}",
            translation = combinedTranslation.trim(),
            width = newRight - newX,
            height = newBottom - newY,
            x = newX,
            y = newY,
            symHeight = max(a.symHeight, b.symHeight),
            symWidth = max(a.symWidth, b.symWidth),
            angle = a.angle,
        )
    }

    private fun buildRenderPlans(
        blocks: List<TranslationBlock>,
        pageWidth: Int,
        pageHeight: Int,
        sampleBitmap: Bitmap,
    ): List<RenderedBlockPlan> {
        val plans = mutableListOf<RenderedBlockPlan>()
        val occupiedTextRegions = mutableListOf<RectF>()
        blocks.forEach { block ->
            buildCollisionAwarePlan(block, pageWidth, pageHeight, occupiedTextRegions, sampleBitmap)?.let { plan ->
                plans += plan
                occupiedTextRegions += plan.occupiedTextRect
            }
        }
        return plans
    }

    private fun buildCollisionAwarePlan(
        block: TranslationBlock,
        pageWidth: Int,
        pageHeight: Int,
        occupiedTextRegions: List<RectF>,
        sampleBitmap: Bitmap,
    ): RenderedBlockPlan? {
        if (block.translation.isBlank()) return null

        val cleanupRect = buildCleanupRect(block)
        val placementRect = buildPlacementRect(block)
        val regionStyle = sampleRegionStyle(sampleBitmap, cleanupRect)
        val candidateDeltas = buildCandidateDeltas(
            basePlacementRect = placementRect,
            block = block,
            pageWidth = pageWidth,
            pageHeight = pageHeight,
        )

        var bestPlan: RenderedBlockPlan? = null
        var bestOverlapArea = Float.POSITIVE_INFINITY

        COLLISION_TEXT_SCALE_FACTORS.forEach { textScale ->
            candidateDeltas.forEach { (dx, dy) ->
                val plan = buildBlockPlan(
                    block = block,
                    pageWidth = pageWidth,
                    cleanupRect = cleanupRect,
                    placementRect = placementRect.shifted(dx, dy),
                    textScale = textScale,
                    style = regionStyle,
                )
                val overlapArea = occupiedTextRegions.sumOf { occupied ->
                    plan.occupiedTextRect.overlapAreaWith(occupied).toDouble()
                }.toFloat()
                if (overlapArea <= 0f) return plan
                if (overlapArea < bestOverlapArea) {
                    bestOverlapArea = overlapArea
                    bestPlan = plan
                }
            }
        }

        return bestPlan
    }

    private fun buildBlockPlan(
        block: TranslationBlock,
        pageWidth: Int,
        cleanupRect: RectF,
        placementRect: RectF,
        textScale: Float,
        style: RegionRenderStyle,
    ): RenderedBlockPlan {
        val angle = if (block.angle > 85f) 0f else block.angle
        val contentRect = buildContentRect(placementRect)
        val contentWidth = max(ceil(contentRect.width()).toInt(), 1)
        val contentHeight = max(ceil(contentRect.height()).toInt(), 1)
        val textSpec = buildBestFitTextSpec(block.translation, contentWidth, contentHeight, textScale, style, pageWidth)
        val textX = contentRect.left
        val textY = contentRect.top + max((contentRect.height() - textSpec.fillLayout.height) / 2f, 0f)
        val actualTextBounds = buildActualTextBounds(contentRect, textY, textSpec.fillLayout)
        val backgroundPadding = max(4f, textSpec.textSize * 0.18f)
        val backgroundRect = actualTextBounds.expanded(backgroundPadding)
        val pivotX = placementRect.centerX()
        val pivotY = placementRect.centerY()
        val occupiedTextRect = rotatedBounds(
            rect = backgroundRect.expanded(TEXT_COLLISION_PADDING_PX),
            angle = angle,
            pivotX = pivotX,
            pivotY = pivotY,
        )

        return RenderedBlockPlan(
            cleanupRect = cleanupRect,
            backgroundRect = backgroundRect,
            contentRect = contentRect,
            angle = angle,
            pivotX = pivotX,
            pivotY = pivotY,
            fillLayout = textSpec.fillLayout,
            strokeLayout = textSpec.strokeLayout,
            textX = textX,
            textY = textY,
            occupiedTextRect = occupiedTextRect,
            style = style,
        )
    }

    private fun cleanupSourceText(
        bitmap: Bitmap,
        canvas: Canvas,
        plan: RenderedBlockPlan,
    ) {
        if (plan.style.isLightBackground) {
            eraseDarkTextPixels(bitmap, plan.cleanupRect, plan.style.cleanupColor, plan.style.referenceLuminance)
        } else {
            drawSoftCleanupOverlay(canvas, plan.cleanupRect, plan.style.cleanupColor)
        }
    }

    private fun eraseDarkTextPixels(
        bitmap: Bitmap,
        cleanupRect: RectF,
        cleanupColor: Int,
        referenceLuminance: Int,
    ) {
        val clippedRect = clampRectToBitmap(cleanupRect, bitmap.width, bitmap.height)
        if (clippedRect.isEmpty) return

        val width = clippedRect.width()
        val height = clippedRect.height()
        if (width <= 0 || height <= 0) return

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, clippedRect.left, clippedRect.top, width, height)

        val darkThreshold = (referenceLuminance - if (referenceLuminance >= 238) 26 else 34).coerceIn(32, 200)
        val mask = BooleanArray(pixels.size)
        pixels.indices.forEach { index ->
            mask[index] = luminanceOf(pixels[index]) <= darkThreshold
        }
        val expandedMask = dilateMask(mask, width, height)
        pixels.indices.forEach { index ->
            if (!expandedMask[index]) return@forEach
            val original = pixels[index]
            val originalLuminance = luminanceOf(original)
            val blendFactor = (0.72f + ((darkThreshold - originalLuminance).coerceAtLeast(0) / darkThreshold.toFloat()) * 0.28f)
                .coerceIn(0.72f, 1f)
            pixels[index] = blendColors(original, cleanupColor, blendFactor)
        }

        bitmap.setPixels(pixels, 0, width, clippedRect.left, clippedRect.top, width, height)
    }

    private fun drawSoftCleanupOverlay(
        canvas: Canvas,
        cleanupRect: RectF,
        cleanupColor: Int,
    ) {
        val red = Color.red(cleanupColor)
        val green = Color.green(cleanupColor)
        val blue = Color.blue(cleanupColor)
        val baseRadius = max(8f, min(cleanupRect.width(), cleanupRect.height()) * 0.18f)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        for (step in SOFT_OVERLAY_STEPS downTo 1) {
            val inset = (SOFT_OVERLAY_STEPS - step) * 1.5f
            val alpha = (28 + step * 22).coerceAtMost(210)
            paint.color = Color.argb(alpha, red, green, blue)
            canvas.drawRoundRect(
                cleanupRect.expanded(inset),
                baseRadius + inset,
                baseRadius + inset,
                paint,
            )
        }

        paint.color = Color.argb(232, red, green, blue)
        canvas.drawRoundRect(cleanupRect, baseRadius, baseRadius, paint)
    }

    private fun drawTranslationBackground(
        canvas: Canvas,
        plan: RenderedBlockPlan,
    ) {
        canvas.save()
        canvas.rotate(plan.angle, plan.pivotX, plan.pivotY)

        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = plan.style.textBackgroundColor
            style = Paint.Style.FILL
        }
        val cornerRadius = max(6f, min(plan.backgroundRect.width(), plan.backgroundRect.height()) * 0.18f)
        canvas.drawRoundRect(plan.backgroundRect, cornerRadius, cornerRadius, backgroundPaint)
        canvas.restore()
    }

    private fun drawTranslationText(
        canvas: Canvas,
        plan: RenderedBlockPlan,
    ) {
        canvas.save()
        canvas.rotate(plan.angle, plan.pivotX, plan.pivotY)
        canvas.translate(plan.textX, plan.textY)
        plan.strokeLayout.draw(canvas)
        plan.fillLayout.draw(canvas)
        canvas.restore()
    }

    private fun buildBestFitTextSpec(
        text: String,
        width: Int,
        height: Int,
        textScale: Float,
        style: RegionRenderStyle,
        pageWidth: Int,
    ): TextLayoutSpec {
        val minimumSize = max(MIN_TEXT_SIZE_PX, pageWidth / PAGE_WIDTH_PER_MIN_TEXT_PX)
        val pageMaximum = max(MAX_TEXT_SIZE_PX, pageWidth / PAGE_WIDTH_PER_MAX_TEXT_PX)
        val maximumSize = max(minimumSize, min(pageMaximum, min(width, height)))
        var low = minimumSize
        var high = maximumSize
        var best: TextLayoutSpec? = null

        while (low <= high) {
            val mid = (low + high) / 2
            val candidate = createTextLayoutSpec(
                text = text,
                width = width,
                height = height,
                textSize = (mid * textScale).coerceAtLeast(minimumSize.toFloat()),
                style = style,
            )
            if (candidate.fits) {
                best = candidate
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        // Nothing fits even at the minimum size (usually one long word in a narrow
        // bubble): let StaticLayout hyphenate instead of overflowing the box.
        return best ?: createTextLayoutSpec(
            text = text,
            width = width,
            height = height,
            textSize = minimumSize.toFloat(),
            style = style,
            allowHyphenation = true,
        )
    }

    private fun createTextLayoutSpec(
        text: String,
        width: Int,
        height: Int,
        textSize: Float,
        style: RegionRenderStyle,
        allowHyphenation: Boolean = false,
    ): TextLayoutSpec {
        val fillPaint = baseTextPaint(
            textSize = textSize,
            color = style.textColor,
            strokeWidth = 0f,
            paintStyle = Paint.Style.FILL,
        )
        val strokePaint = baseTextPaint(
            textSize = textSize,
            color = style.strokeColor,
            strokeWidth = max(1f, textSize * 0.08f),
            paintStyle = Paint.Style.STROKE,
        )
        val lineSpacingExtra = max(1f, textSize * 0.18f)
        val usableWidth = max(1, width - ceil(strokePaint.strokeWidth * 2f).toInt())
        val wrappedText = if (allowHyphenation) {
            text.trim()
        } else {
            balanceText(text, fillPaint, usableWidth).joinToString("\n")
        }
        val fillLayout = createLayout(wrappedText, fillPaint, usableWidth, lineSpacingExtra, allowHyphenation)
        val strokeLayout = createLayout(wrappedText, strokePaint, usableWidth, lineSpacingExtra, allowHyphenation)
        return TextLayoutSpec(
            fillLayout = fillLayout,
            strokeLayout = strokeLayout,
            textSize = textSize,
            fits = fillLayout.fitsInside(usableWidth, height),
        )
    }

    private fun baseTextPaint(
        textSize: Float,
        color: Int,
        strokeWidth: Float,
        paintStyle: Paint.Style,
    ): TextPaint {
        return TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.textSize = textSize
            typeface = translationTypeface
            textAlign = Paint.Align.LEFT
            isSubpixelText = true
            letterSpacing = 0f
            style = paintStyle
            this.strokeWidth = strokeWidth
            strokeJoin = Paint.Join.ROUND
            strokeMiter = 10f
        }
    }

    private fun createLayout(
        text: String,
        paint: TextPaint,
        width: Int,
        lineSpacingExtra: Float,
        allowHyphenation: Boolean = false,
    ): StaticLayout {
        return StaticLayout.Builder
            .obtain(text, 0, text.length, paint, max(width, 1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(
                if (allowHyphenation) Layout.HYPHENATION_FREQUENCY_NORMAL else Layout.HYPHENATION_FREQUENCY_NONE,
            )
            .setLineSpacing(lineSpacingExtra, 1f)
            .build()
    }

    private fun wrapText(
        text: String,
        paint: TextPaint,
        maxWidth: Int,
    ): List<String> {
        val paragraphs = text
            .split('\n')
            .map { it.trim() }
            .ifEmpty { listOf(text.trim()) }

        val wrappedLines = mutableListOf<String>()
        paragraphs.forEachIndexed { paragraphIndex, paragraph ->
            if (paragraph.isBlank()) {
                if (paragraphIndex != paragraphs.lastIndex) wrappedLines += ""
                return@forEachIndexed
            }

            val words = paragraph.split(Regex("\\s+")).filter { it.isNotBlank() }
            var currentLine = words.firstOrNull().orEmpty()
            words.drop(1).forEach { word ->
                val candidate = "$currentLine $word"
                if (paint.measureText(candidate) <= maxWidth) {
                    currentLine = candidate
                } else {
                    wrappedLines += currentLine
                    currentLine = word
                }
            }
            if (currentLine.isNotBlank()) {
                wrappedLines += currentLine
            }
            if (paragraphIndex != paragraphs.lastIndex) {
                wrappedLines += ""
            }
        }

        return if (wrappedLines.isEmpty()) listOf(text.trim()) else wrappedLines
    }

    private fun balanceText(
        text: String,
        paint: TextPaint,
        maxWidth: Int,
    ): List<String> {
        val initial = wrapText(text, paint, maxWidth)
        if (initial.size <= 1) return initial

        var best = initial
        var bestScore = balanceScore(best, paint)
        var targetWidth = maxWidth
        val minimumWidth = max(MIN_BALANCE_WIDTH_PX, (maxWidth * 0.55f).roundToInt())

        while (targetWidth > minimumWidth) {
            targetWidth -= BALANCE_WIDTH_STEP_PX
            val candidate = wrapText(text, paint, targetWidth)
            if (candidate.count { it.isNotBlank() } == 0) break
            if (candidate.size > best.size) break
            val candidateScore = balanceScore(candidate, paint)
            if (candidateScore <= bestScore) {
                best = candidate
                bestScore = candidateScore
            }
        }

        return best
    }

    private fun balanceScore(
        lines: List<String>,
        paint: TextPaint,
    ): Float {
        val nonBlankWidths = lines.filter { it.isNotBlank() }.map { paint.measureText(it) }
        if (nonBlankWidths.isEmpty()) return Float.POSITIVE_INFINITY
        val widest = nonBlankWidths.maxOrNull() ?: return Float.POSITIVE_INFINITY
        val narrowest = nonBlankWidths.minOrNull() ?: return Float.POSITIVE_INFINITY
        val average = nonBlankWidths.sum() / nonBlankWidths.size
        val lastLinePenalty = kotlin.math.abs((nonBlankWidths.lastOrNull() ?: average) - average) * 0.35f
        return (widest - narrowest) + lastLinePenalty
    }

    private fun buildCandidateDeltas(
        basePlacementRect: RectF,
        block: TranslationBlock,
        pageWidth: Int,
        pageHeight: Int,
    ): List<Pair<Float, Float>> {
        val stepX = max(max(block.symWidth, basePlacementRect.width() * 0.2f), 8f)
        val stepY = max(max(block.symHeight, basePlacementRect.height() * 0.2f), 8f)
        val directions = listOf(
            0f to -1f,
            0f to 1f,
            1f to 0f,
            -1f to 0f,
            1f to -1f,
            -1f to -1f,
            1f to 1f,
            -1f to 1f,
        )
        val rawDeltas = mutableListOf(0f to 0f)
        for (step in 1..MAX_COLLISION_SHIFT_STEPS) {
            directions.forEach { (xDirection, yDirection) ->
                rawDeltas += (xDirection * stepX * step) to (yDirection * stepY * step)
            }
        }

        val seen = mutableSetOf<String>()
        return rawDeltas.map { (dx, dy) ->
            constrainDeltaToPage(basePlacementRect, dx, dy, pageWidth.toFloat(), pageHeight.toFloat())
        }.filter { (dx, dy) ->
            seen.add("${dx.toInt()}:${dy.toInt()}")
        }
    }

    private fun constrainDeltaToPage(
        rect: RectF,
        dx: Float,
        dy: Float,
        pageWidth: Float,
        pageHeight: Float,
    ): Pair<Float, Float> {
        var constrainedX = dx
        var constrainedY = dy

        if (rect.width() <= pageWidth) {
            val shiftedLeft = rect.left + constrainedX
            val shiftedRight = rect.right + constrainedX
            if (shiftedLeft < 0f) constrainedX -= shiftedLeft
            if (shiftedRight > pageWidth) constrainedX -= shiftedRight - pageWidth
        }
        if (rect.height() <= pageHeight) {
            val shiftedTop = rect.top + constrainedY
            val shiftedBottom = rect.bottom + constrainedY
            if (shiftedTop < 0f) constrainedY -= shiftedTop
            if (shiftedBottom > pageHeight) constrainedY -= shiftedBottom - pageHeight
        }

        return constrainedX to constrainedY
    }

    private fun buildActualTextBounds(
        textRect: RectF,
        textY: Float,
        layout: StaticLayout,
    ): RectF {
        var left = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        for (lineIndex in 0 until layout.lineCount) {
            left = min(left, textRect.left + layout.getLineLeft(lineIndex))
            right = max(right, textRect.left + layout.getLineRight(lineIndex))
        }
        if (!left.isFinite() || !right.isFinite()) {
            left = textRect.left
            right = textRect.left
        }
        return RectF(left, textY, right, textY + layout.height)
    }

    private fun rotatedBounds(
        rect: RectF,
        angle: Float,
        pivotX: Float,
        pivotY: Float,
    ): RectF {
        if (angle == 0f) return RectF(rect)

        val radians = Math.toRadians(angle.toDouble())
        val cosValue = cos(radians).toFloat()
        val sinValue = sin(radians).toFloat()
        val points = arrayOf(
            rect.left to rect.top,
            rect.right to rect.top,
            rect.right to rect.bottom,
            rect.left to rect.bottom,
        ).map { (x, y) ->
            val translatedX = x - pivotX
            val translatedY = y - pivotY
            val rotatedX = translatedX * cosValue - translatedY * sinValue + pivotX
            val rotatedY = translatedX * sinValue + translatedY * cosValue + pivotY
            rotatedX to rotatedY
        }

        return RectF(
            points.minOf { it.first },
            points.minOf { it.second },
            points.maxOf { it.first },
            points.maxOf { it.second },
        )
    }

    private fun StaticLayout.fitsInside(
        width: Int,
        height: Int,
    ): Boolean {
        if (this.height > height) return false
        for (lineIndex in 0 until lineCount) {
            if (getLineLeft(lineIndex) < 0f) return false
            if (getLineRight(lineIndex) > width) return false
            if (getLineWidth(lineIndex) > width) return false
        }
        return true
    }

    private fun buildCleanupRect(block: TranslationBlock): RectF {
        val padX = max(block.symWidth * 0.5f, PRECISE_MASK_PADDING_PX.toFloat())
        val padY = max(block.symHeight * 0.35f, PRECISE_MASK_PADDING_PX.toFloat())
        return RectF(
            max(block.x - padX, 0f),
            max(block.y - padY, 0f),
            max(block.x + block.width + padX, block.x + 1f),
            max(block.y + block.height + padY, block.y + 1f),
        )
    }

    private fun buildPlacementRect(block: TranslationBlock): RectF {
        // Keep placement tight to the original text region so English text
        // doesn't spill outside the Japanese mask area.
        val padX = max(block.symWidth * 0.4f, block.width * 0.04f)
        val padY = max(block.symHeight * 0.3f, block.height * 0.04f)
        var rectLeft = max(block.x - padX, 0f)
        var rectTop = max(block.y - padY, 0f)
        var rectRight = max(block.x + block.width + padX, block.x + 4f)
        var rectBottom = max(block.y + block.height + padY, block.y + 4f)

        // Enforce minimum width for English text readability.
        // If the bounding box is too narrow (aspect ratio > 2.5:1 height:width),
        // widen it symmetrically so text doesn't stack vertically.
        val rectWidth = rectRight - rectLeft
        val rectHeight = rectBottom - rectTop
        if (rectHeight > 0 && rectWidth > 0 && rectHeight / rectWidth > 2.5f) {
            // Aim for ~1.8:1, but never more than a few times the original column
            // width so text doesn't spill far past the erased bubble.
            val targetWidth = min(rectHeight / 1.8f, rectWidth * MAX_PLACEMENT_WIDEN_FACTOR)
            val extraPad = (targetWidth - rectWidth) / 2f
            rectLeft = max(rectLeft - extraPad, 0f)
            rectRight += extraPad
        }

        return RectF(rectLeft, rectTop, rectRight, rectBottom)
    }

    private fun buildContentRect(placementRect: RectF): RectF {
        // Minimal insets — the placement rect is already tight to the mask,
        // so heavy insets would shrink the usable area too much and force
        // unreadably small font sizes.
        val insetX = max(2f, placementRect.width() * 0.04f)
        val insetY = max(2f, placementRect.height() * 0.04f)
        val safeInsetX = min(insetX, placementRect.width() / 4f)
        val safeInsetY = min(insetY, placementRect.height() / 4f)
        return RectF(
            placementRect.left + safeInsetX,
            placementRect.top + safeInsetY,
            placementRect.right - safeInsetX,
            placementRect.bottom - safeInsetY,
        )
    }

    private fun sampleRegionStyle(
        bitmap: Bitmap,
        sampleRect: RectF,
    ): RegionRenderStyle {
        val clippedRect = clampRectToBitmap(sampleRect, bitmap.width, bitmap.height)
        if (clippedRect.isEmpty) {
            return RegionRenderStyle(
                isLightBackground = true,
                cleanupColor = Color.WHITE,
                textColor = Color.BLACK,
                strokeColor = Color.WHITE,
                textBackgroundColor = Color.argb(235, 255, 255, 255),
                referenceLuminance = 255,
            )
        }

        val width = clippedRect.width()
        val height = clippedRect.height()
        val allPixels = IntArray(width * height)
        bitmap.getPixels(allPixels, 0, width, clippedRect.left, clippedRect.top, width, height)
        // Sample the outer ring of the region: dense dark lettering in the middle of
        // a white bubble would otherwise drag the median down and misclassify it.
        val ring = max(2, (min(width, height) * 0.15f).roundToInt())
        val ringPixels = if (width > ring * 2 && height > ring * 2) {
            val selected = ArrayList<Int>(width * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    if (x < ring || y < ring || x >= width - ring || y >= height - ring) {
                        selected += allPixels[y * width + x]
                    }
                }
            }
            selected.toIntArray()
        } else {
            allPixels
        }
        val pixels = ringPixels
        if (pixels.isEmpty()) {
            return RegionRenderStyle(
                isLightBackground = true,
                cleanupColor = Color.WHITE,
                textColor = Color.BLACK,
                strokeColor = Color.WHITE,
                textBackgroundColor = Color.argb(235, 255, 255, 255),
                referenceLuminance = 255,
            )
        }

        val histogram = IntArray(256)
        var totalRed = 0L
        var totalGreen = 0L
        var totalBlue = 0L
        pixels.forEach { color ->
            val luminance = luminanceOf(color)
            histogram[luminance]++
            totalRed += Color.red(color)
            totalGreen += Color.green(color)
            totalBlue += Color.blue(color)
        }

        val pixelCount = pixels.size
        val medianLuminance = histogramMedian(histogram, pixelCount)
        val brightFloor = (medianLuminance - 18).coerceAtLeast(0)
        var brightRed = 0L
        var brightGreen = 0L
        var brightBlue = 0L
        var brightCount = 0
        pixels.forEach { color ->
            if (luminanceOf(color) >= brightFloor) {
                brightRed += Color.red(color)
                brightGreen += Color.green(color)
                brightBlue += Color.blue(color)
                brightCount++
            }
        }

        val cleanupColor = if (brightCount > 0) {
            Color.rgb(
                (brightRed / brightCount).toInt(),
                (brightGreen / brightCount).toInt(),
                (brightBlue / brightCount).toInt(),
            )
        } else {
            Color.rgb(
                (totalRed / pixelCount).toInt(),
                (totalGreen / pixelCount).toInt(),
                (totalBlue / pixelCount).toInt(),
            )
        }

        val isLightBackground = medianLuminance >= LIGHT_REGION_LUMINANCE_THRESHOLD
        val textColor = if (isLightBackground) Color.BLACK else Color.WHITE
        val strokeColor = if (isLightBackground) Color.WHITE else Color.BLACK
        val backgroundBase = if (isLightBackground) {
            tintColor(cleanupColor, 10)
        } else {
            tintColor(cleanupColor, -18)
        }
        val textBackgroundColor = withAlpha(backgroundBase, if (isLightBackground) 235 else 215)

        return RegionRenderStyle(
            isLightBackground = isLightBackground,
            cleanupColor = cleanupColor,
            textColor = textColor,
            strokeColor = strokeColor,
            textBackgroundColor = textBackgroundColor,
            referenceLuminance = medianLuminance,
        )
    }

    private fun histogramMedian(
        histogram: IntArray,
        sampleCount: Int,
    ): Int {
        var cumulative = 0
        val midpoint = sampleCount / 2
        histogram.forEachIndexed { luminance, count ->
            cumulative += count
            if (cumulative >= midpoint) return luminance
        }
        return histogram.lastIndex
    }

    private fun clampRectToBitmap(
        rect: RectF,
        bitmapWidth: Int,
        bitmapHeight: Int,
    ): Rect {
        val left = rect.left.toInt().coerceIn(0, bitmapWidth)
        val top = rect.top.toInt().coerceIn(0, bitmapHeight)
        val right = ceil(rect.right).toInt().coerceIn(left, bitmapWidth)
        val bottom = ceil(rect.bottom).toInt().coerceIn(top, bitmapHeight)
        return Rect(left, top, right, bottom)
    }

    private fun dilateMask(
        mask: BooleanArray,
        width: Int,
        height: Int,
    ): BooleanArray {
        val expanded = BooleanArray(mask.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var isSet = false
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx !in 0 until width || ny !in 0 until height) continue
                        if (mask[ny * width + nx]) {
                            isSet = true
                            break
                        }
                    }
                    if (isSet) break
                }
                expanded[y * width + x] = isSet
            }
        }
        return expanded
    }

    private fun blendColors(
        from: Int,
        to: Int,
        amount: Float,
    ): Int {
        val clamped = amount.coerceIn(0f, 1f)
        val red = (Color.red(from) + (Color.red(to) - Color.red(from)) * clamped).roundToInt()
        val green = (Color.green(from) + (Color.green(to) - Color.green(from)) * clamped).roundToInt()
        val blue = (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * clamped).roundToInt()
        return Color.rgb(red.coerceIn(0, 255), green.coerceIn(0, 255), blue.coerceIn(0, 255))
    }

    private fun tintColor(
        color: Int,
        delta: Int,
    ): Int {
        return Color.rgb(
            (Color.red(color) + delta).coerceIn(0, 255),
            (Color.green(color) + delta).coerceIn(0, 255),
            (Color.blue(color) + delta).coerceIn(0, 255),
        )
    }

    private fun withAlpha(
        color: Int,
        alpha: Int,
    ): Int {
        return Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color),
        )
    }

    private fun luminanceOf(color: Int): Int {
        return (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000
    }

    private fun RectF.shifted(
        dx: Float,
        dy: Float,
    ): RectF {
        return RectF(left + dx, top + dy, right + dx, bottom + dy)
    }

    private fun RectF.expanded(amount: Float): RectF {
        return RectF(left - amount, top - amount, right + amount, bottom + amount)
    }

    private fun RectF.overlapAreaWith(other: RectF): Float {
        val overlapLeft = max(left, other.left)
        val overlapTop = max(top, other.top)
        val overlapRight = min(right, other.right)
        val overlapBottom = min(bottom, other.bottom)
        if (overlapRight <= overlapLeft || overlapBottom <= overlapTop) return 0f
        return (overlapRight - overlapLeft) * (overlapBottom - overlapTop)
    }

    private fun decodeBitmap(
        bytes: ByteArray,
        pageName: String,
    ): Bitmap {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { return it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.isMutableRequired = true
                }
            }.getOrNull()?.let { return it }
        }
        error("Unable to decode `${pageName}` for rendered export.")
    }

    private fun compressBitmap(
        bitmap: Bitmap,
        outputFormat: RenderedImageFormat,
    ): ByteArray {
        return when (outputFormat) {
            RenderedImageFormat.JPEG -> {
                val flattenedBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also { target ->
                    Canvas(target).apply {
                        drawColor(Color.WHITE)
                        drawBitmap(bitmap, 0f, 0f, null)
                    }
                }
                try {
                    ByteArrayOutputStream().use { output ->
                        flattenedBitmap.compress(Bitmap.CompressFormat.JPEG, RENDERED_JPEG_QUALITY, output)
                        output.toByteArray()
                    }
                } finally {
                    flattenedBitmap.recycle()
                }
            }

            RenderedImageFormat.PNG -> {
                ByteArrayOutputStream().use { output ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                    output.toByteArray()
                }
            }
        }
    }

    private fun outputFormatForPage(pageName: String): RenderedImageFormat {
        return when {
            pageName.endsWith(".jpg", ignoreCase = true) || pageName.endsWith(".jpeg", ignoreCase = true) ->
                RenderedImageFormat.JPEG

            pageName.endsWith(".png", ignoreCase = true) ->
                RenderedImageFormat.PNG

            else -> RenderedImageFormat.PNG
        }
    }

    private fun translatedPageName(
        pageName: String,
        format: RenderedImageFormat,
    ): String {
        val stem = pageName.substringBeforeLast('.', pageName)
        return "$stem.${format.extension}"
    }

    private fun replaceLeafName(path: String, newLeafName: String): String {
        val separatorIndex = path.lastIndexOf('/')
        return if (separatorIndex >= 0) {
            "${path.substring(0, separatorIndex + 1)}$newLeafName"
        } else {
            newLeafName
        }
    }

    private data class TextLayoutSpec(
        val fillLayout: StaticLayout,
        val strokeLayout: StaticLayout,
        val textSize: Float,
        val fits: Boolean,
    )

    private data class RegionRenderStyle(
        val isLightBackground: Boolean,
        val cleanupColor: Int,
        val textColor: Int,
        val strokeColor: Int,
        val textBackgroundColor: Int,
        val referenceLuminance: Int,
    )

    private data class RenderedBlockPlan(
        val cleanupRect: RectF,
        val backgroundRect: RectF,
        val contentRect: RectF,
        val angle: Float,
        val pivotX: Float,
        val pivotY: Float,
        val fillLayout: StaticLayout,
        val strokeLayout: StaticLayout,
        val textX: Float,
        val textY: Float,
        val occupiedTextRect: RectF,
        val style: RegionRenderStyle,
    )

    private data class ExportMetadata(
        val seriesTitle: String,
        val fallbackChapterTitle: String,
        val translator: String,
    )

    private class ArchiveExportResult(
        val comicInfo: String,
        val firstPageBytes: ByteArray?,
    )

    private data class RenderedPage(
        val fileName: String,
        val bytes: ByteArray,
    )

    private enum class RenderedImageFormat(
        val extension: String,
    ) {
        JPEG("jpg"),
        PNG("png"),
    }
}
