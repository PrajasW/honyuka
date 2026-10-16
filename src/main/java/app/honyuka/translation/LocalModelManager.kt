package app.honyuka.translation

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.StatFs
import app.honyuka.data.HonyukaPreferences
import app.honyuka.model.LocalModel
import app.honyuka.model.LocalModelStatus
import java.io.File

/**
 * Downloads and tracks on-device models. Uses the system DownloadManager, so
 * multi-GB downloads resume after network drops, keep going when the app is
 * closed, and show their own notification.
 */
object LocalModelManager {

    private const val MODELS_DIR = "models"

    fun modelFile(context: Context, model: LocalModel): File {
        val dir = context.getExternalFilesDir(MODELS_DIR) ?: File(context.filesDir, MODELS_DIR)
        return File(dir, model.fileName)
    }

    fun isDownloaded(context: Context, model: LocalModel): Boolean {
        val file = modelFile(context, model)
        return file.isFile && file.length() == model.sizeBytes
    }

    /** Bytes needed on top of what's free, or 0 when there's room (model + 10% headroom). */
    fun missingSpaceBytes(context: Context, model: LocalModel): Long {
        val dir = modelFile(context, model).parentFile ?: return 0L
        dir.mkdirs()
        val free = StatFs(dir.absolutePath).availableBytes
        val needed = model.sizeBytes + model.sizeBytes / 10
        return (needed - free).coerceAtLeast(0L)
    }

    /** Starts (or resumes tracking of) a download. Returns an error message, or null. */
    fun startDownload(context: Context, model: LocalModel): String? {
        if (isDownloaded(context, model)) return null
        val preferences = HonyukaPreferences(context)
        if (queryProgress(context, preferences.localModelDownloadId(model))?.running == true) return null

        val missing = missingSpaceBytes(context, model)
        if (missing > 0) {
            return "Not enough storage — free up about ${formatBytes(missing)} and try again."
        }
        val file = modelFile(context, model)
        file.delete()

        val request = DownloadManager.Request(Uri.parse(model.downloadUrl))
            .setTitle("Honyuka · ${model.label}")
            .setDescription("On-device translation model")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, MODELS_DIR, model.fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
        val id = downloadManager(context).enqueue(request)
        preferences.saveLocalModelDownloadId(model, id)
        return null
    }

    fun cancelDownload(context: Context, model: LocalModel) {
        val preferences = HonyukaPreferences(context)
        val id = preferences.localModelDownloadId(model)
        if (id >= 0) downloadManager(context).remove(id)
        preferences.saveLocalModelDownloadId(model, -1L)
        modelFile(context, model).delete()
    }

    fun delete(context: Context, model: LocalModel) = cancelDownload(context, model)

    fun status(context: Context, model: LocalModel): LocalModelStatus {
        if (isDownloaded(context, model)) return LocalModelStatus(downloaded = true, progress = 1f)
        val id = HonyukaPreferences(context).localModelDownloadId(model)
        val progress = queryProgress(context, id) ?: return LocalModelStatus()
        return when {
            progress.failed -> LocalModelStatus(error = "Download failed (${progress.reason}). Tap download to retry.")
            progress.running -> LocalModelStatus(downloading = true, progress = progress.fraction)
            else -> LocalModelStatus()
        }
    }

    private data class Progress(val running: Boolean, val failed: Boolean, val fraction: Float, val reason: Int)

    private fun queryProgress(context: Context, id: Long): Progress? {
        if (id < 0) return null
        downloadManager(context).query(DownloadManager.Query().setFilterById(id)).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
            return Progress(
                running = status == DownloadManager.STATUS_RUNNING ||
                    status == DownloadManager.STATUS_PENDING ||
                    status == DownloadManager.STATUS_PAUSED,
                failed = status == DownloadManager.STATUS_FAILED,
                fraction = fraction,
                reason = reason,
            )
        }
    }

    private fun downloadManager(context: Context): DownloadManager {
        return context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    }

    fun formatBytes(bytes: Long): String {
        val gb = bytes / 1_000_000_000.0
        return if (gb >= 1) "%.1f GB".format(gb) else "%d MB".format(bytes / 1_000_000)
    }
}
