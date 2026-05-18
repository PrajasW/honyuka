package app.honyuka.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import androidx.documentfile.provider.DocumentFile
import app.honyuka.R
import app.honyuka.data.HonyukaStorage
import app.honyuka.model.ChapterEntry
import app.honyuka.model.PageTranslation
import app.honyuka.model.TranslationBlock
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.Enumeration
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class RenderedChapterExporter(
    private val context: Context,
    private val storage: HonyukaStorage,
) {
    private companion object {
        const val RENDERED_JPEG_QUALITY = 88
        const val MIN_TEXT_SIZE_PX = 20
        const val MAX_TEXT_SIZE_PX = 80
        const val RENDERED_TEXT_SIZE_SCALE = 0.60f
        const val MAX_COLLISION_SHIFT_STEPS = 6
        const val TEXT_COLLISION_PADDING_PX = 4f
        val COLLISION_TEXT_SCALE_FACTORS = floatArrayOf(1f, 0.9f, 0.8f, 0.7f)
    }

    private val translationTypeface: Typeface =
        ResourcesCompat.getFont(context, R.font.animeace) ?: Typeface.DEFAULT_BOLD

    fun exportTranslatedSibling(
        chapter: ChapterEntry,
        translations: Map<String, PageTranslation>,
    ) {
        if (translations.isEmpty()) return

        val sourceChapter = requireNotNull(storage.resolveOriginalChapterDocument(chapter)) {
            "Unable to locate the original chapter in downloads."
        }
        val translatedChapter = storage.prepareTranslatedChapterDocument(chapter)

        if (chapter.isCbz) {
            exportArchiveChapter(sourceChapter, translatedChapter, translations)
        } else {
            exportDirectoryChapter(sourceChapter, translatedChapter, translations)
        }
    }

    fun exportToLocalSource(
        chapter: ChapterEntry,
        translations: Map<String, PageTranslation>,
    ) {
        if (translations.isEmpty()) return

        val sourceChapter = requireNotNull(storage.resolveOriginalChapterDocument(chapter)) {
            "Unable to locate the original chapter in downloads."
        }
        val localTarget = storage.prepareLocalSourceExportTarget(chapter)
        exportChapterAsArchive(sourceChapter, localTarget, translations, chapter.isCbz)
    }

    private fun exportDirectoryChapter(
        sourceDir: DocumentFile,
        targetDir: DocumentFile,
        translations: Map<String, PageTranslation>,
    ) {
        processDirectory(sourceDir, targetDir, translations)
    }

    private fun processDirectory(
        sourceDir: DocumentFile,
        targetDir: DocumentFile,
        translations: Map<String, PageTranslation>,
    ) {
        sourceDir.listFiles().forEach { child ->
            when {
                child.isDirectory -> {
                    val targetChild = targetDir.findFile(child.name.orEmpty()) ?: targetDir.createDirectory(child.name.orEmpty())
                    requireNotNull(targetChild) { "Unable to create translated subdirectory ${child.name.orEmpty()}." }
                    processDirectory(child, targetChild, translations)
                }
                child.isFile -> {
                    val pageTranslation = translations[child.name.orEmpty()]
                    val originalBytes = context.contentResolver.openInputStream(child.uri).use { input ->
                        requireNotNull(input) { "Unable to open source page ${child.name.orEmpty()}." }
                        input.readBytes()
                    }
                    val renderedPage = if (pageTranslation != null) {
                        renderPage(originalBytes, child.name.orEmpty(), pageTranslation)
                    } else {
                        RenderedPage(child.name.orEmpty(), originalBytes)
                    }
                    val targetName = renderedPage.fileName
                    val targetFile = targetDir.findFile(targetName)
                        ?: targetDir.createFile(mimeTypeForPage(targetName, child.type), targetName)
                    requireNotNull(targetFile) { "Unable to create translated page ${targetName}." }
                    context.contentResolver.openOutputStream(targetFile.uri, "wt").use { output ->
                        requireNotNull(output) { "Unable to write translated page ${targetFile.name.orEmpty()}." }
                        output.write(renderedPage.bytes)
                    }
                }
            }
        }
    }

    private fun exportArchiveChapter(
        sourceArchive: DocumentFile,
        targetArchive: DocumentFile,
        translations: Map<String, PageTranslation>,
    ) {
        exportArchiveToArchive(sourceArchive, targetArchive, translations)
    }

    private fun exportChapterAsArchive(
        sourceChapter: DocumentFile,
        targetArchive: DocumentFile,
        translations: Map<String, PageTranslation>,
        sourceIsArchive: Boolean,
    ) {
        if (sourceIsArchive) {
            exportArchiveToArchive(sourceChapter, targetArchive, translations)
        } else {
            exportDirectoryToArchive(sourceChapter, targetArchive, translations)
        }
    }

    private fun exportArchiveToArchive(
        sourceArchive: DocumentFile,
        targetArchive: DocumentFile,
        translations: Map<String, PageTranslation>,
    ) {
        val tempInput = File.createTempFile("honyuka-source-", ".cbz", context.cacheDir)
        val tempOutput = File.createTempFile("honyuka-rendered-", ".cbz", context.cacheDir)

        try {
            context.contentResolver.openInputStream(sourceArchive.uri).use { input ->
                requireNotNull(input) { "Unable to open backup chapter archive." }
                tempInput.outputStream().use { output -> input.copyTo(output) }
            }

            ZipFile(tempInput).use { zipFile ->
                ZipOutputStream(tempOutput.outputStream()).use { zipOutput ->
                    val entries = zipFile.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val bytes = if (entry.isDirectory) {
                            ByteArray(0)
                        } else {
                            zipFile.getInputStream(entry).use { it.readBytes() }
                        }
                        val pageName = entry.name.substringAfterLast('/')
                        val renderedPage = if (!entry.isDirectory && translations.containsKey(pageName)) {
                            renderPage(bytes, pageName, translations.getValue(pageName))
                        } else {
                            RenderedPage(pageName, bytes)
                        }
                        val outputEntryName = if (entry.isDirectory) {
                            entry.name
                        } else {
                            replaceLeafName(entry.name, renderedPage.fileName)
                        }
                        zipOutput.putNextEntry(ZipEntry(outputEntryName))
                        if (!entry.isDirectory) {
                            zipOutput.write(renderedPage.bytes)
                        }
                        zipOutput.closeEntry()
                    }
                }
            }

            context.contentResolver.openOutputStream(targetArchive.uri, "wt").use { output ->
                requireNotNull(output) { "Unable to write translated chapter archive." }
                tempOutput.inputStream().use { input -> input.copyTo(output) }
            }
        } finally {
            tempInput.delete()
            tempOutput.delete()
        }
    }

    private fun exportDirectoryToArchive(
        sourceDir: DocumentFile,
        targetArchive: DocumentFile,
        translations: Map<String, PageTranslation>,
    ) {
        val tempOutput = File.createTempFile("honyuka-rendered-", ".cbz", context.cacheDir)
        try {
            ZipOutputStream(tempOutput.outputStream()).use { zipOutput ->
                writeDirectoryToArchive(
                    rootDir = sourceDir,
                    currentDir = sourceDir,
                    zipOutput = zipOutput,
                    translations = translations,
                )
            }
            context.contentResolver.openOutputStream(targetArchive.uri, "wt").use { output ->
                requireNotNull(output) { "Unable to write local source export archive." }
                tempOutput.inputStream().use { input -> input.copyTo(output) }
            }
        } finally {
            tempOutput.delete()
        }
    }

    private fun writeDirectoryToArchive(
        rootDir: DocumentFile,
        currentDir: DocumentFile,
        zipOutput: ZipOutputStream,
        translations: Map<String, PageTranslation>,
    ) {
        currentDir.listFiles().forEach { child ->
            if (child.isDirectory) {
                writeDirectoryToArchive(rootDir, child, zipOutput, translations)
            } else if (child.isFile) {
                val relativePath = buildRelativePath(rootDir, child)
                val pageTranslation = translations[child.name.orEmpty()]
                val originalBytes = context.contentResolver.openInputStream(child.uri).use { input ->
                    requireNotNull(input) { "Unable to open source page ${child.name.orEmpty()}." }
                    input.readBytes()
                }
                val renderedPage = if (pageTranslation != null) {
                    renderPage(originalBytes, child.name.orEmpty(), pageTranslation)
                } else {
                    RenderedPage(child.name.orEmpty(), originalBytes)
                }
                zipOutput.putNextEntry(ZipEntry(replaceLeafName(relativePath, renderedPage.fileName)))
                zipOutput.write(renderedPage.bytes)
                zipOutput.closeEntry()
            }
        }
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

        val sourceBitmap = decodeBitmap(originalBytes, pageName)
        val bitmap = sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
        if (bitmap != sourceBitmap) {
            sourceBitmap.recycle()
        }

        try {
            val canvas = Canvas(bitmap)
            val renderPlans = buildRenderPlans(drawableBlocks, bitmap.width, bitmap.height)
            renderPlans.forEach { plan -> drawTranslationBackground(canvas, plan) }
            renderPlans.forEach { plan -> drawTranslationText(canvas, plan) }
            return RenderedPage(translatedPageName(pageName), compressBitmap(bitmap))
        } finally {
            bitmap.recycle()
        }
    }

    private fun buildRenderPlans(
        blocks: List<TranslationBlock>,
        pageWidth: Int,
        pageHeight: Int,
    ): List<RenderedBlockPlan> {
        val plans = mutableListOf<RenderedBlockPlan>()
        val occupiedTextRegions = mutableListOf<RectF>()
        blocks.forEach { block ->
            buildCollisionAwarePlan(block, pageWidth, pageHeight, occupiedTextRegions)?.let { plan ->
                plans += plan
                occupiedTextRegions += plan.occupiedTextRect
            }
        }
        return plans
    }

    private fun drawTranslationBackground(
        canvas: Canvas,
        plan: RenderedBlockPlan,
    ) {
        canvas.save()
        canvas.rotate(plan.angle, plan.backgroundRect.centerX(), plan.backgroundRect.centerY())

        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(plan.backgroundRect, 8f, 8f, backgroundPaint)
        canvas.restore()
    }

    private fun drawTranslationText(
        canvas: Canvas,
        plan: RenderedBlockPlan,
    ) {
        canvas.save()
        canvas.rotate(plan.angle, plan.textRect.centerX(), plan.textRect.centerY())
        canvas.translate(plan.textX, plan.textY)
        plan.layout.draw(canvas)
        canvas.restore()
    }

    private fun buildCollisionAwarePlan(
        block: TranslationBlock,
        pageWidth: Int,
        pageHeight: Int,
        occupiedTextRegions: List<RectF>,
    ): RenderedBlockPlan? {
        if (block.translation.isBlank()) return null

        val baseBackgroundRect = buildBackgroundRect(block)
        val baseTextRect = buildTextRect(block)
        val basePlacementRect = unionOf(baseBackgroundRect, baseTextRect)
        val candidateDeltas = buildCandidateDeltas(
            basePlacementRect = basePlacementRect,
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
                    backgroundRect = baseBackgroundRect.shifted(dx, dy),
                    textRect = baseTextRect.shifted(dx, dy),
                    textScale = textScale,
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
        backgroundRect: RectF,
        textRect: RectF,
        textScale: Float,
    ): RenderedBlockPlan {
        val angle = if (block.angle > 85f) 0f else block.angle
        val contentWidth = max(ceil(textRect.width()).toInt(), 1)
        val availableHeight = max(ceil(textRect.height()).toInt(), 1)
        val textPaint = buildBestFitTextPaint(block.translation, contentWidth, availableHeight, textScale)
        val layout = createLayout(block.translation, textPaint, contentWidth)
        val textX = textRect.left
        val textY = textRect.top + max((textRect.height() - layout.height) / 2f, 0f)
        val occupiedTextRect = rotatedBounds(
            rect = buildActualTextBounds(textRect, textY, layout).expanded(TEXT_COLLISION_PADDING_PX),
            angle = angle,
            pivotX = textRect.centerX(),
            pivotY = textRect.centerY(),
        )

        return RenderedBlockPlan(
            backgroundRect = backgroundRect,
            textRect = textRect,
            angle = angle,
            layout = layout,
            textX = textX,
            textY = textY,
            occupiedTextRect = occupiedTextRect,
        )
    }

    private fun buildBestFitTextPaint(
        text: String,
        width: Int,
        height: Int,
        textScale: Float = 1f,
    ): TextPaint {
        var low = MIN_TEXT_SIZE_PX
        var high = MAX_TEXT_SIZE_PX
        var best = MIN_TEXT_SIZE_PX

        while (low <= high) {
            val mid = (low + high) / 2
            val paint = baseTextPaint(mid.toFloat())
            val layout = createLayout(text, paint, width)
            if (layout.fitsInside(text, width, height)) {
                best = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        return baseTextPaint(max(best * RENDERED_TEXT_SIZE_SCALE * textScale, MIN_TEXT_SIZE_PX.toFloat()))
    }

    private fun baseTextPaint(textSize: Float): TextPaint {
        return TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            this.textSize = textSize
            typeface = translationTypeface
            textAlign = Paint.Align.LEFT
            isSubpixelText = true
            letterSpacing = 0f
        }
    }

    private fun createLayout(
        text: String,
        paint: TextPaint,
        width: Int,
    ): StaticLayout {
        return StaticLayout.Builder
            .obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(true)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
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
        sourceText: String,
        width: Int,
        height: Int,
    ): Boolean {
        if (this.height > height) return false
        for (lineIndex in 0 until lineCount) {
            if (getLineLeft(lineIndex) < 0f) return false
            if (getLineRight(lineIndex) > width) return false
            if (getLineWidth(lineIndex) > width) return false
        }
        if (breaksWordAcrossLines(sourceText)) return false
        return true
    }

    private fun StaticLayout.breaksWordAcrossLines(sourceText: String): Boolean {
        for (lineIndex in 0 until lineCount - 1) {
            val beforeBreak = previousNonWhitespaceIndex(sourceText, getLineEnd(lineIndex) - 1)
            val afterBreak = nextNonWhitespaceIndex(sourceText, getLineStart(lineIndex + 1))
            if (beforeBreak == null || afterBreak == null) continue
            if (sourceText[beforeBreak] == '\n' || sourceText[afterBreak] == '\n') continue
            if (sourceText.substring(beforeBreak + 1, afterBreak).any(Char::isWhitespace)) continue
            if (sourceText[beforeBreak].isWordPart() && sourceText[afterBreak].isWordPart()) {
                return true
            }
        }
        return false
    }

    private fun previousNonWhitespaceIndex(
        text: String,
        startIndex: Int,
    ): Int? {
        for (index in startIndex.coerceAtMost(text.lastIndex) downTo 0) {
            if (!text[index].isWhitespace()) return index
        }
        return null
    }

    private fun nextNonWhitespaceIndex(
        text: String,
        startIndex: Int,
    ): Int? {
        for (index in startIndex.coerceAtLeast(0)..text.lastIndex) {
            if (!text[index].isWhitespace()) return index
        }
        return null
    }

    private fun Char.isWordPart(): Boolean {
        return isLetterOrDigit() || this == '\'' || this == '-'
    }

    private fun unionOf(
        first: RectF,
        second: RectF,
    ): RectF {
        return RectF(
            min(first.left, second.left),
            min(first.top, second.top),
            max(first.right, second.right),
            max(first.bottom, second.bottom),
        )
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
    ): ByteArray {
        val flattenedBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also { target ->
            Canvas(target).apply {
                drawColor(Color.WHITE)
                drawBitmap(bitmap, 0f, 0f, null)
            }
        }
        return try {
            ByteArrayOutputStream().use { output ->
                flattenedBitmap.compress(Bitmap.CompressFormat.JPEG, RENDERED_JPEG_QUALITY, output)
                output.toByteArray()
            }
        } finally {
            flattenedBitmap.recycle()
        }
    }

    private fun buildRelativePath(
        rootDir: DocumentFile,
        file: DocumentFile,
    ): String {
        val rootName = rootDir.name.orEmpty()
        val filePath = file.uri.path.orEmpty()
        val anchor = "/$rootName/"
        val relative = filePath.substringAfter(anchor, file.name.orEmpty())
        return relative.substringAfter('/', file.name.orEmpty())
    }

    private fun translatedPageName(pageName: String): String {
        val stem = pageName.substringBeforeLast('.', pageName)
        return "$stem.jpg"
    }

    private fun replaceLeafName(path: String, newLeafName: String): String {
        val separatorIndex = path.lastIndexOf('/')
        return if (separatorIndex >= 0) {
            "${path.substring(0, separatorIndex + 1)}$newLeafName"
        } else {
            newLeafName
        }
    }

    private fun mimeTypeForPage(
        pageName: String,
        fallback: String?,
    ): String {
        return when {
            pageName.endsWith(".jpg", ignoreCase = true) || pageName.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
            pageName.endsWith(".png", ignoreCase = true) -> "image/png"
            pageName.endsWith(".webp", ignoreCase = true) -> "image/webp"
            else -> fallback ?: "application/octet-stream"
        }
    }

    private fun buildBackgroundRect(block: TranslationBlock): RectF {
        val padX = block.symWidth / 2f
        val padY = block.symHeight / 2f
        val left = max(block.x - padX / 2f, 0f)
        val top = max(block.y - padY / 2f, 0f)
        val width = max(block.width + padX, 4f)
        val height = max(block.height + padY, 4f)
        return RectF(left, top, left + width, top + height)
    }

    private fun buildTextRect(block: TranslationBlock): RectF {
        val padX = block.symWidth * 2f
        val padY = block.symHeight
        val left = max(block.x - padX / 2f, 0f)
        val top = max(block.y - padY / 2f, 0f)
        val width = max(block.width + padX, 4f)
        val height = max(block.height + padY, 4f)
        return RectF(left, top, left + width, top + height)
    }

    private data class RenderedBlockPlan(
        val backgroundRect: RectF,
        val textRect: RectF,
        val angle: Float,
        val layout: StaticLayout,
        val textX: Float,
        val textY: Float,
        val occupiedTextRect: RectF,
    )

    private data class RenderedPage(
        val fileName: String,
        val bytes: ByteArray,
    )
}
