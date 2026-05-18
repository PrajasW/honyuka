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
        val translationsDir = root.findFile(TRANSLATIONS_DIR)

        return downloadsDir.listFiles()
            .filter { it.isDirectory && !it.name.isNullOrBlank() }
            .sortedByNaturalName()
            .flatMap { sourceDir ->
                sourceDir.listFiles()
                    .filter { it.isDirectory && !it.name.isNullOrBlank() }
                    .sortedByNaturalName()
                    .flatMap { mangaDir ->
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
                                    translated = resolveTranslatedChapterDocument(
                                        sourceName = sourceDir.name!!,
                                        mangaName = mangaDir.name!!,
                                        chapterBaseName = baseName,
                                        isCbz = chapterFile.isFile,
                                    )?.exists() == true ||
                                        translationFileFor(
                                            translationsDir = translationsDir,
                                            sourceName = sourceDir.name!!,
                                            mangaName = mangaDir.name!!,
                                            chapterBaseName = baseName,
                                        )?.exists() == true,
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

    fun resolveInputChapterDocument(chapter: ChapterEntry): DocumentFile? {
        return resolveBackupChapterDocument(chapter) ?: resolveChapterDocument(chapter)
    }

    fun resolveOriginalChapterDocument(chapter: ChapterEntry): DocumentFile? {
        return resolveChapterDocument(chapter)
    }

    fun prepareTranslatedChapterDocument(chapter: ChapterEntry): DocumentFile {
        val root = requireNotNull(rootDirectory()) { "Storage root is not configured." }
        val mangaDir = root.findFile(DOWNLOADS_DIR)
            ?.findFile(chapter.sourceName)
            ?.findFile(chapter.mangaName)
        requireNotNull(mangaDir) { "Unable to locate manga downloads directory." }

        resolveTranslatedChapterDocument(chapter)?.let { existing ->
            deleteRecursively(existing)
        }

        val translatedName = translatedChapterName(chapter.chapterBaseName)
        return if (chapter.isCbz) {
            mangaDir.createFile("application/vnd.comicbook+zip", "$translatedName.cbz")
                ?: error("Unable to create translated chapter archive.")
        } else {
            mangaDir.createDirectory(translatedName)
                ?: error("Unable to create translated chapter folder.")
        }
    }

    fun prepareLocalSourceExportTarget(
        chapter: ChapterEntry,
    ): DocumentFile {
        val root = requireNotNull(rootDirectory()) { "Storage root is not configured." }
        val localRoot = root.findFile(LOCAL_SOURCE_DIR) ?: root.createDirectory(LOCAL_SOURCE_DIR)
        requireNotNull(localRoot) { "Unable to locate or create the local source root." }

        val mangaDirName = buildValidFilename("${chapter.mangaName} (Honyuka)")
        val mangaDir = localRoot.findFile(mangaDirName) ?: localRoot.createDirectory(mangaDirName)
        requireNotNull(mangaDir) { "Unable to create local source manga directory." }

        val chapterFileName = "${buildValidFilename("${chapter.chapterBaseName}$TRANSLATED_SUFFIX")}.cbz"
        mangaDir.findFile(chapterFileName)?.delete()
        return mangaDir.createFile("application/vnd.comicbook+zip", chapterFileName)
            ?: error("Unable to create local source export archive.")
    }

    fun ensureOriginalChapterBackup(chapter: ChapterEntry): DocumentFile {
        resolveBackupChapterDocument(chapter)?.let { return it }

        val liveChapter = requireNotNull(resolveChapterDocument(chapter)) {
            "Unable to locate the live chapter in downloads."
        }
        val root = requireNotNull(rootDirectory()) { "Storage root is not configured." }
        val mangaDir = root.findFile(DOWNLOADS_DIR)
            ?.findFile(chapter.sourceName)
            ?.findFile(chapter.mangaName)
        requireNotNull(mangaDir) { "Unable to locate manga downloads directory." }

        val backupRoot = mangaDir.findFile(BACKUPS_DIR) ?: mangaDir.createDirectory(BACKUPS_DIR)
        requireNotNull(backupRoot) { "Unable to create per-manga backup directory." }

        return if (chapter.isCbz) {
            val backupName = "${chapter.chapterBaseName}.cbz"
            val file = backupRoot.createFile("application/vnd.comicbook+zip", backupName)
                ?: error("Unable to create chapter backup archive.")
            copyFileContents(liveChapter, file)
            file
        } else {
            val folder = backupRoot.createDirectory(chapter.chapterBaseName)
                ?: error("Unable to create chapter backup folder.")
            copyDirectoryContents(liveChapter, folder)
            folder
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

    fun resolveTranslatedChapterDocument(chapter: ChapterEntry): DocumentFile? {
        return resolveTranslatedChapterDocument(
            sourceName = chapter.sourceName,
            mangaName = chapter.mangaName,
            chapterBaseName = chapter.chapterBaseName,
            isCbz = chapter.isCbz,
        )
    }

    private fun resolveTranslatedChapterDocument(
        sourceName: String,
        mangaName: String,
        chapterBaseName: String,
        isCbz: Boolean,
    ): DocumentFile? {
        val root = rootDirectory() ?: return null
        val mangaDir = root.findFile(DOWNLOADS_DIR)
            ?.findFile(sourceName)
            ?.findFile(mangaName)
            ?: return null

        val translatedName = translatedChapterName(chapterBaseName)
        val candidates = buildList {
            add(translatedName)
            if (isCbz) add("$translatedName.cbz")
        }
        return candidates.asSequence().mapNotNull(mangaDir::findFile).firstOrNull()
    }

    private fun resolveBackupChapterDocument(chapter: ChapterEntry): DocumentFile? {
        val root = rootDirectory() ?: return null
        val mangaDir = root.findFile(DOWNLOADS_DIR)
            ?.findFile(chapter.sourceName)
            ?.findFile(chapter.mangaName)
            ?.findFile(BACKUPS_DIR)
            ?: return null

        val backupNames = buildList {
            add(chapter.chapterBaseName)
            if (chapter.isCbz) add("${chapter.chapterBaseName}.cbz")
        }

        return backupNames
            .asSequence()
            .mapNotNull(mangaDir::findFile)
            .firstOrNull()
            ?: resolveLegacyBackupChapterDocument(root, chapter)
    }

    private fun resolveLegacyBackupChapterDocument(
        root: DocumentFile,
        chapter: ChapterEntry,
    ): DocumentFile? {
        val mangaDir = root.findFile(LEGACY_BACKUPS_DIR)
            ?.findFile(DOWNLOADS_DIR)
            ?.findFile(chapter.sourceName)
            ?.findFile(chapter.mangaName)
            ?: return null

        val backupNames = buildList {
            add(chapter.chapterBaseName)
            if (chapter.isCbz) add("${chapter.chapterBaseName}.cbz")
        }

        return backupNames
            .asSequence()
            .mapNotNull(mangaDir::findFile)
            .firstOrNull()
    }

    private fun copyDirectoryContents(source: DocumentFile, destination: DocumentFile) {
        source.listFiles().forEach { child ->
            when {
                child.isDirectory -> {
                    val targetDir = destination.findFile(child.name.orEmpty())
                        ?: destination.createDirectory(child.name.orEmpty())
                    requireNotNull(targetDir) { "Unable to create backup subdirectory ${child.name.orEmpty()}." }
                    copyDirectoryContents(child, targetDir)
                }
                child.isFile -> {
                    val targetFile = destination.createFile(child.type ?: "application/octet-stream", child.name.orEmpty())
                        ?: error("Unable to create backup file ${child.name.orEmpty()}.")
                    copyFileContents(child, targetFile)
                }
            }
        }
    }

    private fun copyFileContents(source: DocumentFile, destination: DocumentFile) {
        context.contentResolver.openInputStream(source.uri).use { input ->
            requireNotNull(input) { "Unable to open source file ${source.name.orEmpty()}." }
            context.contentResolver.openOutputStream(destination.uri, "wt").use { output ->
                requireNotNull(output) { "Unable to open destination file ${destination.name.orEmpty()}." }
                input.copyTo(output)
            }
        }
    }

    private fun deleteRecursively(file: DocumentFile) {
        if (file.isDirectory) {
            file.listFiles().forEach(::deleteRecursively)
        }
        file.delete()
    }

    private fun isSupportedChapterContainer(file: DocumentFile): Boolean {
        val name = file.name?.lowercase(Locale.ROOT) ?: return false
        return file.isDirectory || (file.isFile && name.endsWith(".cbz"))
    }

    private fun isVisibleChapterContainer(file: DocumentFile): Boolean {
        if (!isSupportedChapterContainer(file)) return false
        val name = file.name.orEmpty()
        val baseName = name.removeSuffix(".cbz")
        return baseName != BACKUPS_DIR && !baseName.endsWith(TRANSLATED_SUFFIX)
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

    private fun translatedChapterName(originalBaseName: String): String {
        return "$originalBaseName$TRANSLATED_SUFFIX"
    }

    private fun buildValidFilename(original: String): String {
        val trimmed = original.trim('.', ' ')
        if (trimmed.isEmpty()) return "(invalid)"
        val builder = StringBuilder(trimmed.length)
        trimmed.forEach { character ->
            val isAllowed = character !in setOf('"', '*', '/', ':', '<', '>', '?', '\\', '|') &&
                character.code !in 0..31 &&
                character.code != 127
            builder.append(if (isAllowed) character else '_')
        }
        return builder.toString().take(240)
    }

    private companion object {
        const val DOWNLOADS_DIR = "downloads"
        const val LOCAL_SOURCE_DIR = "local"
        const val TRANSLATIONS_DIR = "translations"
        const val BACKUPS_DIR = "__honyuka_backup__"
        const val LEGACY_BACKUPS_DIR = "honyuka_backups"
        const val TRANSLATED_SUFFIX = "_translated"
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
