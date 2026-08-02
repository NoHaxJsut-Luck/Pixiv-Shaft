package ceui.pixiv.translation

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import ceui.lisa.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal data class TranslationFailureRecord(
    val chunkIndex: Int,
    val totalChunks: Int,
    val sourceChunk: String,
    val modelOutput: String?,
    val error: IOException,
)

internal object TranslationFailureLogger {

    private const val LOG_DIRECTORY = "PixivShaft/translation-logs"
    private const val MAX_TEXT_CHARS = 12_000

    suspend fun save(
        context: Context,
        model: String,
        from: String,
        to: String,
        totalSourceChars: Int,
        totalChunks: Int,
        failures: List<TranslationFailureRecord>,
    ): String? = withContext(Dispatchers.IO) {
        if (failures.isEmpty()) return@withContext null

        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        val fileName = "shaft-translation-failure-$timestamp.txt"
        val logText = buildLogText(
            model = model,
            from = from,
            to = to,
            totalSourceChars = totalSourceChars,
            totalChunks = totalChunks,
            failures = failures,
        )

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveWithMediaStore(context, fileName, logText)
            } else {
                saveOnLegacyAndroid(context, fileName, logText)
            }
        }.recoverCatching { error ->
            Timber.w(error, "Unable to save translation log to public Downloads")
            saveToAppDownloads(context, fileName, logText)
        }.onFailure { error ->
            Timber.e(error, "Unable to save translation failure log")
        }.getOrNull()
    }

    internal fun buildLogText(
        model: String,
        from: String,
        to: String,
        totalSourceChars: Int,
        totalChunks: Int,
        failures: List<TranslationFailureRecord>,
    ): String = buildString {
        appendLine("Shaft Translation Failure Log")
        appendLine("Generated (UTC): ${utcTimestamp()}")
        appendLine("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Model: $model")
        appendLine("Language: $from -> $to")
        appendLine("Source characters: $totalSourceChars")
        appendLine("Top-level chunks: $totalChunks")
        appendLine("Failed top-level chunks: ${failures.size}")
        appendLine("Security: API keys, authorization headers, cookies, and account credentials are never logged.")

        failures.sortedBy { it.chunkIndex }.forEachIndexed { recordIndex, record ->
            appendLine()
            appendLine("===== Failure ${recordIndex + 1} =====")
            appendLine("Chunk: ${record.chunkIndex + 1}/${record.totalChunks}")
            appendLine("Error type: ${record.error.javaClass.simpleName}")
            appendLine("Error message: ${record.error.message.orEmpty()}")
            if (record.error is TranslationApiException) {
                appendLine("HTTP status: ${record.error.statusCode}")
                appendLine("Request ID: ${record.error.requestId.orEmpty()}")
            }
            appendLine("Failed source length: ${record.sourceChunk.length}")
            appendLine("--- Failed source chunk ---")
            appendLine(record.sourceChunk.take(MAX_TEXT_CHARS))
            appendLine("--- Model output ---")
            appendLine(record.modelOutput?.take(MAX_TEXT_CHARS) ?: "<not available>")
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveWithMediaStore(context: Context, fileName: String, content: String): String {
        val resolver = context.contentResolver
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$LOG_DIRECTORY"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Unable to create translation log in Downloads")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
            } ?: throw IOException("Unable to open translation log output stream")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
        return "Download/$LOG_DIRECTORY/$fileName"
    }

    @Suppress("DEPRECATION")
    private fun saveOnLegacyAndroid(context: Context, fileName: String, content: String): String {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) throw IOException("Storage permission is not granted")

        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            LOG_DIRECTORY,
        )
        val target = writeFile(directory, fileName, content)
        return target.absolutePath
    }

    private fun saveToAppDownloads(context: Context, fileName: String, content: String): String {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.filesDir
        val target = writeFile(File(root, LOG_DIRECTORY), fileName, content)
        return target.absolutePath
    }

    private fun writeFile(directory: File, fileName: String, content: String): File {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IOException("Unable to create translation log directory")
        }
        return File(directory, fileName).also { target ->
            target.writeText(content, Charsets.UTF_8)
        }
    }

    private fun utcTimestamp(): String = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        Locale.US,
    ).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())
}
