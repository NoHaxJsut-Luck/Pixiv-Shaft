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
import java.security.MessageDigest
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
    val attempts: List<TranslationAttemptRecord> = emptyList(),
    val retryDecisions: List<TranslationRetryDecisionRecord> = emptyList(),
)

internal object TranslationFailureLogger {

    private const val LOG_DIRECTORY = "PixivShaft/translation-logs"
    private const val MAX_TEXT_CHARS = 12_000
    private const val MAX_PROMPT_CHARS = 16_000
    private const val MAX_ERROR_BODY_CHARS = 8_000
    private val sensitiveHeaderRegex = Regex(
        pattern = "(?im)^(authorization|proxy-authorization|cookie|set-cookie|x-api-key)\\s*[:=].*$",
    )
    private val bearerTokenRegex = Regex("(?i)bearer\\s+[a-z0-9._~+/-]+=*")
    private val xaiKeyRegex = Regex("(?i)xai-[a-z0-9_-]{12,}")

    suspend fun save(
        context: Context,
        model: String,
        from: String,
        to: String,
        totalSourceChars: Int,
        totalChunks: Int,
        session: TranslationSessionDiagnostics,
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
            session = session,
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
        session: TranslationSessionDiagnostics,
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
        appendLine("Conversation ID: ${session.conversationId}")
        appendLine("Prompt version: ${session.promptVersion}")
        appendLine("Prompt config source: ${sanitizeForLog(session.promptConfigSource)}")
        appendLine("Priority processing: ${session.priorityProcessing}")
        appendLine(
            "Retry policy: topLevelMax=${session.topLevelChunkMaxChars}, " +
                "parallelCap=${session.parallelChunkCap}, globalRequestCap=${session.globalRequestCap}, " +
                "attemptCap=${session.chunkAttemptCap}, maxSplitDepth=${session.maxSplitDepth}",
        )
        appendLine("Security: API keys, authorization headers, cookies, and account credentials are never logged.")

        failures.sortedBy { it.chunkIndex }.forEachIndexed { recordIndex, record ->
            appendLine()
            appendLine("===== Failure ${recordIndex + 1} =====")
            appendLine("Chunk: ${record.chunkIndex + 1}/${record.totalChunks}")
            appendLine("Error type: ${record.error.javaClass.simpleName}")
            appendLine("Error message: ${sanitizeForLog(record.error.message.orEmpty())}")
            if (record.error is TranslationApiException) {
                appendLine("HTTP status: ${record.error.statusCode}")
                appendLine("Request ID: ${sanitizeForLog(record.error.requestId.orEmpty())}")
            }
            appendLine("Failed source length: ${record.sourceChunk.length}")
            appendLine("Failed source SHA-256: ${sha256(record.sourceChunk)}")
            appendLine("--- Failed source chunk ---")
            appendLine(sanitizeForLog(record.sourceChunk.take(MAX_TEXT_CHARS)))
            appendLine("--- Model output ---")
            appendLine(sanitizeForLog(record.modelOutput?.take(MAX_TEXT_CHARS) ?: "<not available>"))

            appendLine()
            appendLine("--- Attempt diagnostics (${record.attempts.size}) ---")
            record.attempts.sortedBy { it.sequence }.forEach { attempt ->
                appendAttempt(attempt)
            }

            appendLine()
            appendLine("--- Retry decisions (${record.retryDecisions.size}) ---")
            record.retryDecisions.sortedBy { it.sequence }.forEach { decision ->
                appendRetryDecision(decision)
            }
        }
    }

    private fun StringBuilder.appendAttempt(attempt: TranslationAttemptRecord) {
        appendLine()
        appendLine("[Attempt event ${attempt.sequence}]")
        appendLine(
            "Location: top-level ${attempt.topLevelChunkIndex + 1}/${attempt.totalTopLevelChunks}, " +
                "splitPath=${attempt.splitPath}, splitDepth=${attempt.splitDepth}",
        )
        appendLine("Attempt: ${attempt.attempt}/${attempt.maxAttempts}")
        appendLine("Started (UTC): ${attempt.startedAtUtc}")
        appendLine("Duration ms: ${attempt.durationMs}")
        appendLine("Source length: ${attempt.sourceText.length}")
        appendLine("Source SHA-256: ${sha256(attempt.sourceText)}")
        appendLine(
            "Context lengths: before=${attempt.contextBeforeLength}, after=${attempt.contextAfterLength}",
        )
        appendLine("Prompt mode: ${attempt.promptMode}")
        appendLine("Model: ${attempt.model}")
        appendLine("Reasoning effort: ${attempt.reasoningEffort ?: "<not sent>"}")
        appendLine("Temperature: ${attempt.temperature}")
        appendLine("Priority processing: ${attempt.priorityProcessing}")
        appendLine("System prompt length: ${attempt.systemPrompt.length}")
        appendLine("System prompt SHA-256: ${sha256(attempt.systemPrompt)}")
        appendLine("User prompt length: ${attempt.userPrompt.length}")
        appendLine("User prompt SHA-256: ${sha256(attempt.userPrompt)}")
        appendLine("--- Effective system prompt ---")
        appendLine(sanitizeForLog(attempt.systemPrompt.take(MAX_PROMPT_CHARS)))
        appendLine("--- Effective user prompt ---")
        appendLine(sanitizeForLog(attempt.userPrompt.take(MAX_PROMPT_CHARS)))

        val response = attempt.response
        if (response == null) {
            appendLine("Response metadata: <not available>")
        } else {
            appendLine("HTTP status: ${response.httpStatus ?: "<not available>"}")
            appendLine("Content-Type: ${sanitizeForLog(response.contentType ?: "<not available>")}")
            appendLine("Request ID: ${sanitizeForLog(response.requestId ?: "<not available>")}")
            appendLine("Completion ID: ${sanitizeForLog(response.completionId ?: "<not available>")}")
            appendLine("Response model: ${sanitizeForLog(response.responseModel ?: "<not available>")}")
            appendLine(
                "System fingerprint: ${sanitizeForLog(response.systemFingerprint ?: "<not available>")}",
            )
            appendLine("Finish reason: ${sanitizeForLog(response.finishReason ?: "<not available>")}")
            appendLine(
                "Usage: prompt=${response.promptTokens ?: "?"}, " +
                    "completion=${response.completionTokens ?: "?"}, total=${response.totalTokens ?: "?"}, " +
                    "cachedPrompt=${response.cachedPromptTokens ?: "?"}, " +
                    "reasoning=${response.reasoningTokens ?: "?"}",
            )
            appendLine(
                "SSE: dataEvents=${response.sseDataEvents ?: "<not streaming>"}, " +
                    "malformedEvents=${response.malformedSseEvents ?: "<not streaming>"}",
            )
            response.apiErrorBody?.let { body ->
                appendLine("--- API error body ---")
                appendLine(sanitizeForLog(body.take(MAX_ERROR_BODY_CHARS)))
            }
        }

        appendLine("Raw output length: ${attempt.rawModelOutput?.length ?: 0}")
        attempt.rawModelOutput?.let { appendLine("Raw output SHA-256: ${sha256(it)}") }
        appendLine("Normalized output length: ${attempt.normalizedModelOutput?.length ?: 0}")
        attempt.normalizedModelOutput?.let {
            appendLine("Normalized output SHA-256: ${sha256(it)}")
        }
        appendLine(
            "Source equals normalized output: ${attempt.sourceEqualsNormalizedOutput ?: "<not evaluated>"}",
        )
        appendLine(
            "Validation classification: ${attempt.validationClassification ?: "<not evaluated>"}",
        )
        appendLine("Attempt error type: ${attempt.errorType ?: "<none>"}")
        appendLine("Attempt error message: ${sanitizeForLog(attempt.errorMessage ?: "<none>")}")
        appendLine("--- Raw model output ---")
        appendLine(sanitizeForLog(attempt.rawModelOutput?.take(MAX_TEXT_CHARS) ?: "<not available>"))
        if (attempt.rawModelOutput != attempt.normalizedModelOutput) {
            appendLine("--- Normalized model output ---")
            appendLine(
                sanitizeForLog(attempt.normalizedModelOutput?.take(MAX_TEXT_CHARS) ?: "<not available>"),
            )
        }
    }

    private fun StringBuilder.appendRetryDecision(decision: TranslationRetryDecisionRecord) {
        appendLine()
        appendLine("[Retry decision event ${decision.sequence}]")
        appendLine(
            "Location: top-level ${decision.topLevelChunkIndex + 1}, " +
                "splitPath=${decision.splitPath}, splitDepth=${decision.splitDepth}",
        )
        appendLine("Completed attempt: ${decision.completedAttempt}")
        appendLine("Classified error: ${decision.classifiedError}")
        appendLine("Consecutive refusals: ${decision.consecutiveRefusals}")
        appendLine("Action: ${decision.action}")
        appendLine("Should retry: ${decision.shouldRetry}")
        appendLine("Split requested: ${decision.splitRequested}")
        appendLine("Split performed: ${decision.splitPerformed}")
        appendLine("Current chunk length: ${decision.currentChunkLength}")
        appendLine("Requested max chunk size: ${decision.requestedMaxChunkSize ?: "<none>"}")
        appendLine("Next alternative prompt: ${decision.nextAlternativePrompt ?: "<none>"}")
        appendLine("Next temperature: ${decision.nextTemperature ?: "<none>"}")
        appendLine("Base delay ms: ${decision.baseDelayMs ?: "<none>"}")
        appendLine("Terminal reason: ${sanitizeForLog(decision.terminalReason ?: "<none>")}")
    }

    private fun sanitizeForLog(value: String): String = value
        .replace(sensitiveHeaderRegex) { match ->
            "${match.groupValues[1]}: <redacted>"
        }
        .replace(bearerTokenRegex, "Bearer <redacted>")
        .replace(xaiKeyRegex, "xai-<redacted>")

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

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
