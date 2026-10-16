package app.honyuka.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.honyuka.model.ChapterEntry
import app.honyuka.model.PageTranslation
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.OutputStream
import java.util.Locale

class HonyukaStorage(
    private val context: Context,
    private val preferences: HonyukaPreferences,
) {

    fun saveRoot(uri: Uri) {
        preferences.saveRootUri(uri)
    }

    fun rootSummary(): String? = preferences.rootUri()?.toString()

    fun hasCompatibleRoot(): Boolean {
        val root = rootDirectory() ?: return false
        return root.findFile(DOWNLOADS_DIR)?.isDirectory == true
    }

    fun scanChapters(): List<ChapterEntry> {
        val root = rootDirectory() ?: return emptyList()
        val downloadsDir = root.findFile(DOWNLOADS_DIR) ?: return emptyList()
        // List the local source once instead of a findFile() per chapter (slow over SAF).
        val exportedByManga = root.findFile(LOCAL_SOURCE_DIR)
            ?.listFiles()
            ?.filter { it.isDirectory && it.name != null }
            ?.associate { dir -> dir.name!! to dir.listFiles().mapNotNull { it.name }.toSet() }
            .orEmpty()

        return downloadsDir.listFiles()
            .filter { it.isDirectory && !it.name.isNullOrBlank() }
            .sortedByNaturalName()
            .flatMap { sourceDir ->
                sourceDir.listFiles()
                    .filter { it.isDirectory && !it.name.isNullOrBlank() }
                    .sortedByNaturalName()
                    .flatMap { mangaDir ->
                        val exported = exportedByManga[localMangaDirName(mangaDir.name!!)].orEmpty()
                        mangaDir.listFiles()
                            .filter { isVisibleChapterContainer(it) }
                            .sortedByNaturalName()
                            .map { chapterFile ->
                                val baseName = chapterFile.name!!.removeSuffix(".cbz")
                                ChapterEntry(
                                    sourceName = sourceDir.name!!,
                                    mangaName = mangaDir.name!!,
                                    chapterDisplayName = baseName,
                                    chapterBaseName = baseName,
                                    chapterUri = chapterFile.uri,
                                    thumbnailUri = chapterThumbnailUri(chapterFile),
                                    isCbz = chapterFile.isFile,
                                    translated = localChapterFileNames(baseName).any { it in exported },
                                )
                            }
                    }
            }
    }

    fun resolveChapterDocument(chapter: ChapterEntry): DocumentFile? {
        val root = rootDirectory() ?: return null
        val mangaDir = root.findFile(DOWNLOADS_DIR)
            ?.findFile(chapter.sourceName)
            ?.findFile(chapter.mangaName)
            ?: return null

        val candidateNames = buildList {
            add(chapter.chapterBaseName)
            if (chapter.isCbz) {
                add("${chapter.chapterBaseName}.cbz")
            }
        }

        return candidateNames
            .asSequence()
            .mapNotNull(mangaDir::findFile)
            .firstOrNull()
    }

    fun resolveOriginalChapterDocument(chapter: ChapterEntry): DocumentFile? {
        return resolveChapterDocument(chapter)
    }

    /** `local/<manga> (Honyuka)/`, created if needed. Mihon's Local Source lists each folder here as a manga. */
    fun localSourceMangaDirectory(chapter: ChapterEntry): DocumentFile {
        val root = requireNotNull(rootDirectory()) { "Storage root is not configured." }
        val localRoot = root.findFile(LOCAL_SOURCE_DIR) ?: root.createDirectory(LOCAL_SOURCE_DIR)
        requireNotNull(localRoot) { "Unable to locate or create the local source root." }

        val mangaDirName = localMangaDirName(chapter.mangaName)
        val mangaDir = localRoot.findFile(mangaDirName) ?: localRoot.createDirectory(mangaDirName)
        return requireNotNull(mangaDir) { "Unable to create local source manga directory." }
    }

    /** Title shown in Mihon for the translated copy (also the local folder name). */
    fun localSeriesTitle(chapter: ChapterEntry): String = localMangaDirName(chapter.mangaName)

    /**
     * Creates (replacing any previous export) `local/<manga> (Honyuka)/<chapter>.cbz`.
     * Exports from older builds named `<chapter>_translated.cbz` are removed so Mihon
     * doesn't list the chapter twice.
     */
    fun prepareLocalSourceExportTarget(chapter: ChapterEntry): DocumentFile {
        val mangaDir = localSourceMangaDirectory(chapter)
        val names = localChapterFileNames(chapter.chapterBaseName)
        names.forEach { name -> mangaDir.findFile(name)?.delete() }
        // octet-stream keeps the file name exactly as given; some providers rewrite
        // the extension for comic-book MIME types.
        return mangaDir.createFile("application/octet-stream", names.first())
            ?: error("Unable to create local source export archive.")
    }

    /** Writes (or overwrites) [fileName] in the local manga folder. */
    fun writeLocalMangaFile(chapter: ChapterEntry, fileName: String, bytes: ByteArray) {
        val mangaDir = localSourceMangaDirectory(chapter)
        val file = mangaDir.findFile(fileName)
            ?: mangaDir.createFile("application/octet-stream", fileName)
            ?: error("Unable to create $fileName in the local source folder.")
        context.contentResolver.openOutputStream(file.uri, "wt").use { output ->
            requireNotNull(output) { "Unable to write $fileName." }
            output.write(bytes)
        }
    }

    /** Writes [fileName] into the local manga folder only if it doesn't exist yet. */
    fun writeLocalMangaFileIfMissing(
        chapter: ChapterEntry,
        fileName: String,
        bytes: () -> ByteArray?,
    ) {
        val mangaDir = localSourceMangaDirectory(chapter)
        if (mangaDir.findFile(fileName) != null) return
        val content = bytes() ?: return
        val file = mangaDir.createFile("application/octet-stream", fileName) ?: return
        context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(content) }
    }

    /** Mihon uses any `cover.*` image in the manga folder. */
    fun hasLocalCover(chapter: ChapterEntry): Boolean {
        return localSourceMangaDirectory(chapter).listFiles().any { file ->
            file.isFile && file.name.orEmpty().substringBeforeLast('.').equals("cover", ignoreCase = true)
        }
    }

    fun writeTranslation(
        chapter: ChapterEntry,
        translations: Map<String, PageTranslation>,
    ) {
        val root = requireNotNull(rootDirectory()) { "Storage root is not configured." }
        val translationsRoot = root.findFile(TRANSLATIONS_DIR) ?: root.createDirectory(TRANSLATIONS_DIR)
        requireNotNull(translationsRoot) { "Unable to create translations directory." }

        val sourceDir = translationsRoot.findFile(chapter.sourceName) ?: translationsRoot.createDirectory(chapter.sourceName)
        val mangaDir = sourceDir?.findFile(chapter.mangaName) ?: sourceDir?.createDirectory(chapter.mangaName)
        requireNotNull(mangaDir) { "Unable to create manga translation directory." }

        val fileName = "${buildValidFilename(chapter.chapterBaseName)}.json"
        mangaDir.findFile(fileName)?.delete()
        val file = mangaDir.createFile("application/json", fileName)
        requireNotNull(file) { "Unable to create translation JSON." }

        context.contentResolver.openOutputStream(file.uri, "wt").use { output: OutputStream? ->
            requireNotNull(output) { "Unable to open translation output stream." }
            output.write(Json.encodeToString(translations).toByteArray())
        }
    }

    /**
     * Load a previously saved translation sidecar for the given chapter.
     * Returns null if no sidecar exists.
     */
    fun readTranslation(chapter: ChapterEntry): Map<String, PageTranslation>? {
        val root = rootDirectory() ?: return null
        val translationsDir = root.findFile(TRANSLATIONS_DIR) ?: return null
        val file = translationFileFor(
            translationsDir = translationsDir,
            sourceName = chapter.sourceName,
            mangaName = chapter.mangaName,
            chapterBaseName = chapter.chapterBaseName,
        ) ?: return null
        if (!file.exists()) return null
        return try {
            val jsonText = context.contentResolver.openInputStream(file.uri)?.use { input ->
                input.bufferedReader(Charsets.UTF_8).readText()
            } ?: return null
            Json.decodeFromString<Map<String, PageTranslation>>(jsonText)
        } catch (_: Exception) {
            null
        }
    }

    private fun rootDirectory(): DocumentFile? {
        val rootUri = preferences.rootUri() ?: return null
        return DocumentFile.fromTreeUri(context, rootUri)
    }

    private fun translationFileFor(
        translationsDir: DocumentFile?,
        sourceName: String,
        mangaName: String,
        chapterBaseName: String,
    ): DocumentFile? {
        return translationsDir
            ?.findFile(sourceName)
            ?.findFile(mangaName)
            ?.findFile("${buildValidFilename(chapterBaseName)}.json")
    }

    private fun isSupportedChapterContainer(file: DocumentFile): Boolean {
        val name = file.name?.lowercase(Locale.ROOT) ?: return false
        return file.isDirectory || (file.isFile && name.endsWith(".cbz"))
    }

    private fun isVisibleChapterContainer(file: DocumentFile): Boolean {
        if (!isSupportedChapterContainer(file)) return false
        val name = file.name.orEmpty()
        val baseName = name.removeSuffix(".cbz")
        // Mihon writes in-progress downloads to `<chapter>_tmp` / `<chapter>.cbz_tmp`
        // and ignores them; so do we. Hidden entries are never chapters.
        return !name.startsWith(".") &&
            !name.endsWith("_tmp") &&
            baseName != BACKUPS_DIR &&
            !baseName.endsWith(TRANSLATED_SUFFIX)
    }

    private fun chapterThumbnailUri(chapterFile: DocumentFile): Uri? {
        if (!chapterFile.isDirectory) return null
        return collectImagePages(chapterFile)
            .sortedByNaturalName()
            .firstOrNull()
            ?.uri
    }

    private fun collectImagePages(directory: DocumentFile): List<DocumentFile> {
        return directory.listFiles().flatMap { child ->
            when {
                child.isDirectory -> collectImagePages(child)
                child.isFile && isImageName(child.name) -> listOf(child)
                else -> emptyList()
            }
        }
    }

    private fun isImageName(name: String?): Boolean {
        val lowered = name?.lowercase(Locale.ROOT) ?: return false
        return lowered.endsWith(".jpg") ||
            lowered.endsWith(".jpeg") ||
            lowered.endsWith(".png") ||
            lowered.endsWith(".webp") ||
            lowered.endsWith(".gif") ||
            lowered.endsWith(".heif") ||
            lowered.endsWith(".avif")
    }

    private fun localMangaDirName(mangaName: String): String = buildValidFilename("$mangaName (Honyuka)")

    /** Current export name first, then the `_translated` name used by older builds. */
    private fun localChapterFileNames(chapterBaseName: String): List<String> {
        val maxBaseBytes = MAX_FILE_NAME_BYTES - ".cbz".length - TRANSLATED_SUFFIX.length
        return listOf(
            "${buildValidFilename(chapterBaseName, maxBaseBytes)}.cbz",
            "${buildValidFilename("$chapterBaseName$TRANSLATED_SUFFIX", maxBaseBytes + TRANSLATED_SUFFIX.length)}.cbz",
        )
    }

    /** Mirrors Mihon's `DiskUtil.buildValidFilename`, including its UTF-8 byte limit. */
    private fun buildValidFilename(original: String, maxBytes: Int = MAX_FILE_NAME_BYTES): String {
        val trimmed = original.trim('.', ' ')
        if (trimmed.isEmpty()) return "(invalid)"
        val builder = StringBuilder(trimmed.length)
        trimmed.forEach { character ->
            val isAllowed = character !in setOf('"', '*', '/', ':', '<', '>', '?', '\\', '|') &&
                character.code !in 0..31 &&
                character.code != 127
            builder.append(if (isAllowed) character else '_')
        }
        return truncateToUtf8Bytes(builder.toString(), maxBytes).ifEmpty { "(invalid)" }
    }

    private fun truncateToUtf8Bytes(value: String, maxBytes: Int): String {
        if (value.toByteArray(Charsets.UTF_8).size <= maxBytes) return value
        var bytes = 0
        var end = 0
        while (end < value.length) {
            val codePoint = value.codePointAt(end)
            val size = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
            if (bytes + size > maxBytes) break
            bytes += size
            end += Character.charCount(codePoint)
        }
        return value.substring(0, end)
    }

    private companion object {
        const val DOWNLOADS_DIR = "downloads"
        const val LOCAL_SOURCE_DIR = "local"
        const val TRANSLATIONS_DIR = "translations"
        const val BACKUPS_DIR = "__honyuka_backup__"
        const val TRANSLATED_SUFFIX = "_translated"
        const val MAX_FILE_NAME_BYTES = 240
    }
}

private fun List<DocumentFile>.sortedByNaturalName(): List<DocumentFile> {
    return sortedWith { left, right ->
        naturalCompare(left.name.orEmpty(), right.name.orEmpty())
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
