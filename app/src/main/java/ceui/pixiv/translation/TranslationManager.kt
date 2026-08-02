package ceui.pixiv.translation

import android.content.Context
import androidx.annotation.WorkerThread
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.coroutines.executeAsync
import timber.log.Timber
import java.io.IOException
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.random.Random

class TranslationManager private constructor() {

    companion object {
        private const val CHUNK_MAX_CHARS = 1600
        private const val PARALLEL_CHUNK_CAP = 4
        private const val GLOBAL_REQUEST_CAP = 4
        private const val CHUNK_ATTEMPT_CAP = 4
        private const val MAX_SPLIT_DEPTH = 2
        private const val CONTENT_PROGRESS_INTERVAL_NS = 250_000_000L
        private const val CONTEXT_BEFORE_CHARS = 320
        private const val CONTEXT_AFTER_CHARS = 160
        private const val PROMPT_VERSION = 4
        private val ID_LINE_REGEX = Regex("""-\s*id:\s*\d+""")

        @Volatile
        private var instance: TranslationManager? = null

        fun getInstance(): TranslationManager {
            return instance ?: synchronized(this) {
                instance ?: TranslationManager().also { instance = it }
            }
        }
    }

    private val promptBuilder = PromptBuilder()
    private val fallbackStrategy = FallbackStrategy()
    private val requestSemaphore = Semaphore(GLOBAL_REQUEST_CAP)
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    suspend fun translate(
        text: String,
        apiKey: String,
        from: String = "Japanese",
        to: String = "Chinese",
        model: String = TranslationSettingsStore.getModel(),
        context: Context? = null,
        onProgress: (String) -> Unit = {},
        onProgressUpdate: (
            completedChunks: Int,
            totalChunks: Int,
            processedSourceChars: Int,
            totalSourceChars: Int,
        ) -> Unit = { _, _, _, _ -> },
    ): String {
        require(apiKey.isNotBlank()) { "API key must not be blank" }
        context?.let { TranslationConfig.getInstance().loadConfig(it) }
        coroutineContext.ensureActive()

        val chunks = TranslationTextChunker.split(text, CHUNK_MAX_CHARS)
        if (chunks.isEmpty()) return text
        val conversationId = UUID.randomUUID().toString()

        val cache = context?.applicationContext?.let { appContext ->
            withContext(Dispatchers.IO) {
                TranslationCacheStore.create(
                    context = appContext,
                    sourceText = text,
                    model = model,
                    from = from,
                    to = to,
                    promptVersion = PROMPT_VERSION,
                    chunkCount = chunks.size,
                )
            }
        }
        val cachedResults = cache?.load() ?: MutableList(chunks.size) { null }
        val stateLock = Any()
        val results = cachedResults.toMutableList()
        val drafts = MutableList<String?>(chunks.size) { null }
        val initiallyCompleted = results.count { it != null }
        val completedCount = AtomicInteger(initiallyCompleted)
        val processedSourceChars = AtomicInteger(
            chunks.indices.sumOf { index -> if (results[index] != null) chunks[index].length else 0 },
        )
        val lastContentProgressNs = AtomicLong(0L)
        val totalSourceChars = text.length

        suspend fun publishProgress(force: Boolean = false) {
            val now = System.nanoTime()
            val previous = lastContentProgressNs.get()
            val shouldPublish = force ||
                (now - previous >= CONTENT_PROGRESS_INTERVAL_NS &&
                    lastContentProgressNs.compareAndSet(previous, now))
            if (!shouldPublish) return

            val snapshot = synchronized(stateLock) {
                chunks.indices.map { index -> results[index] ?: drafts[index] ?: chunks[index] }
            }
            val partialText = TranslationTextChunker.merge(snapshot)
            withContext(Dispatchers.Main.immediate) {
                runCatching { onProgress(partialText) }
                    .onFailure { Timber.w(it, "Translation preview callback failed") }
            }
        }

        suspend fun publishCountProgress() {
            val completed = completedCount.get()
            val chars = processedSourceChars.get()
            withContext(Dispatchers.Main.immediate) {
                runCatching {
                    onProgressUpdate(completed, chunks.size, chars, totalSourceChars)
                }.onFailure { Timber.w(it, "Translation progress callback failed") }
            }
        }

        if (initiallyCompleted > 0) {
            publishCountProgress()
            publishProgress(force = true)
        }

        val missingIndices = chunks.indices.filter { results[it] == null }
        if (missingIndices.isNotEmpty()) {
            val parallelism = minOf(PARALLEL_CHUNK_CAP, missingIndices.size)
            val dispatcher = Dispatchers.IO.limitedParallelism(max(1, parallelism))
            coroutineScope {
                val jobs: List<Job> = missingIndices.map { index ->
                    launch(dispatcher) {
                        val chunk = chunks[index]
                        val translated = processChunkWithRetry(
                            chunk = chunk,
                            contextBefore = chunks.getOrNull(index - 1)
                                ?.takeLast(CONTEXT_BEFORE_CHARS)
                                .orEmpty(),
                            contextAfter = chunks.getOrNull(index + 1)
                                ?.take(CONTEXT_AFTER_CHARS)
                                .orEmpty(),
                            conversationId = conversationId,
                            apiKey = apiKey,
                            maxAttempts = CHUNK_ATTEMPT_CAP,
                            from = from,
                            to = to,
                            model = model,
                            onPartial = { partial ->
                                synchronized(stateLock) { drafts[index] = partial }
                                publishProgress()
                            },
                        )

                        synchronized(stateLock) {
                            results[index] = translated
                            drafts[index] = null
                        }
                        cache?.saveChunk(index, translated)
                        processedSourceChars.addAndGet(chunk.length)
                        completedCount.incrementAndGet()
                        publishCountProgress()
                        publishProgress(force = completedCount.get() == chunks.size)
                    }
                }
                jobs.joinAll()
            }
        }

        val completedResults = synchronized(stateLock) { results.toList() }
        if (completedResults.any { it == null }) {
            throw TranslationOutputException("Translation ended with incomplete chunks")
        }
        return TranslationTextChunker.merge(completedResults.filterNotNull())
    }

    suspend fun validateConfiguration(apiKey: String, model: String = TranslationSettingsStore.getModel()) {
        require(apiKey.isNotBlank()) { "API key must not be blank" }
        val request = Request.Builder()
            .url("https://api.x.ai/v1/models")
            .addHeader("Authorization", "Bearer ${apiKey.trim()}")
            .get()
            .build()

        requestSemaphore.withPermit {
            client.newCall(request).executeAsync().use { response ->
                if (!response.isSuccessful) throw createApiException(response)
                val body = response.body?.string().orEmpty()
                val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
                    ?: throw IOException("Invalid models response")
                val models = root.getAsJsonArray("data")
                    ?.flatMap { element ->
                        val modelObject = element.takeIf { it.isJsonObject }?.asJsonObject
                            ?: return@flatMap emptyList()
                        buildList {
                            modelObject.get("id")
                                ?.takeIf { it.isJsonPrimitive }
                                ?.asString
                                ?.let(::add)
                            modelObject.getAsJsonArray("aliases")
                                ?.mapNotNull { alias ->
                                    alias.takeIf { it.isJsonPrimitive }?.asString
                                }
                                ?.let(::addAll)
                        }
                    }
                    .orEmpty()
                if (models.isNotEmpty() && model !in models) {
                    throw TranslationModelUnavailableException(model)
                }
            }
        }
    }

    @WorkerThread
    fun translateForJava(
        context: Context?,
        text: String,
        apiKey: String,
        from: String,
        to: String,
        onProgress: java.util.function.Consumer<String>,
        onProgressUpdate: TranslationProgressListener,
    ): String {
        return runBlocking {
            translate(
                text = text,
                apiKey = apiKey,
                from = from,
                to = to,
                context = context,
                onProgress = { onProgress.accept(it) },
                onProgressUpdate = { completed, total, processedChars, totalChars ->
                    onProgressUpdate.onProgress(completed, total, processedChars, totalChars)
                },
            )
        }
    }

    private suspend fun processChunkWithRetry(
        chunk: String,
        contextBefore: String,
        contextAfter: String,
        conversationId: String,
        apiKey: String,
        maxAttempts: Int,
        from: String,
        to: String,
        model: String,
        splitDepth: Int = 0,
        initialConfig: FallbackStrategy.RetryConfig? = null,
        onPartial: suspend (String) -> Unit,
    ): String {
        var attempt = 0
        var attemptConfig = initialConfig

        while (attempt < maxAttempts) {
            coroutineContext.ensureActive()
            try {
                val result = processChunk(
                    chunk = chunk,
                    contextBefore = contextBefore,
                    contextAfter = contextAfter,
                    conversationId = conversationId,
                    apiKey = apiKey,
                    from = from,
                    to = to,
                    model = model,
                    retry = attempt,
                    retryConfig = attemptConfig,
                    onPartial = onPartial,
                )

                val errorType = fallbackStrategy.detectErrorType(null, result)
                val stillUntranslated = fallbackStrategy.looksMostlyUntranslated(chunk, result)
                if (errorType != FallbackStrategy.ErrorType.BLOCKED_CONTENT &&
                    errorType != FallbackStrategy.ErrorType.FORMAT_ERROR &&
                    !stillUntranslated
                ) {
                    return result
                }

                val nextConfig = when {
                    errorType == FallbackStrategy.ErrorType.BLOCKED_CONTENT ->
                        fallbackStrategy.handleBlockedContent(chunk, attempt)
                    else -> fallbackStrategy.handleFormatError(chunk, attempt)
                }
                if (!nextConfig.shouldRetry || attempt + 1 >= maxAttempts) {
                    throw TranslationOutputException("The model returned an incomplete or refused translation")
                }

                waitBeforeRetry(nextConfig.delayMs)
                if (nextConfig.splitIntoSmallerChunks &&
                    chunk.length > nextConfig.maxChunkSize &&
                    splitDepth < MAX_SPLIT_DEPTH
                ) {
                    return translateSmallerChunks(
                        chunk = chunk,
                        contextBefore = contextBefore,
                        contextAfter = contextAfter,
                        conversationId = conversationId,
                        apiKey = apiKey,
                        from = from,
                        to = to,
                        model = model,
                        maxChunkSize = nextConfig.maxChunkSize,
                        splitDepth = splitDepth + 1,
                        initialConfig = nextConfig.copy(splitIntoSmallerChunks = false),
                        onPartial = onPartial,
                    )
                }

                attemptConfig = nextConfig
                attempt++
            } catch (e: CancellationException) {
                throw e
            } catch (e: TranslationApiException) {
                if (!e.isRetryable || attempt + 1 >= maxAttempts) throw e
                val nextConfig = fallbackStrategy.handleNetworkError(attempt)
                if (!nextConfig.shouldRetry) throw e
                waitBeforeRetry(max(nextConfig.delayMs, e.retryAfterMs ?: 0L))
                attemptConfig = nextConfig
                attempt++
            } catch (e: TranslationOutputException) {
                if (attempt + 1 >= maxAttempts) throw e
                val nextConfig = fallbackStrategy.handleFormatError(chunk, attempt)
                if (!nextConfig.shouldRetry) throw e
                waitBeforeRetry(nextConfig.delayMs)
                attemptConfig = nextConfig
                attempt++
            } catch (e: SocketTimeoutException) {
                if (attempt + 1 >= maxAttempts) throw IOException("Translation timed out", e)
                val nextConfig = fallbackStrategy.handleTimeout(chunk, attempt)
                if (!nextConfig.shouldRetry) throw IOException("Translation timed out", e)
                waitBeforeRetry(nextConfig.delayMs)
                attemptConfig = nextConfig
                attempt++
            } catch (e: IOException) {
                if (attempt + 1 >= maxAttempts) throw e
                val nextConfig = fallbackStrategy.handleNetworkError(attempt)
                if (!nextConfig.shouldRetry) throw e
                waitBeforeRetry(nextConfig.delayMs)
                attemptConfig = nextConfig
                attempt++
            }
        }

        throw IOException("Chunk translation failed after $maxAttempts attempts")
    }

    private suspend fun translateSmallerChunks(
        chunk: String,
        contextBefore: String,
        contextAfter: String,
        conversationId: String,
        apiKey: String,
        from: String,
        to: String,
        model: String,
        maxChunkSize: Int,
        splitDepth: Int,
        initialConfig: FallbackStrategy.RetryConfig,
        onPartial: suspend (String) -> Unit,
    ): String {
        val subChunks = TranslationTextChunker.split(chunk, maxChunkSize)
        val completed = MutableList<String?>(subChunks.size) { null }
        subChunks.forEachIndexed { index, subChunk ->
            val translated = processChunkWithRetry(
                chunk = subChunk,
                contextBefore = subChunks.getOrNull(index - 1)
                    ?.takeLast(CONTEXT_BEFORE_CHARS)
                    ?: contextBefore.takeLast(CONTEXT_BEFORE_CHARS),
                contextAfter = subChunks.getOrNull(index + 1)
                    ?.take(CONTEXT_AFTER_CHARS)
                    ?: contextAfter.take(CONTEXT_AFTER_CHARS),
                conversationId = conversationId,
                apiKey = apiKey,
                maxAttempts = CHUNK_ATTEMPT_CAP,
                from = from,
                to = to,
                model = model,
                splitDepth = splitDepth,
                initialConfig = initialConfig,
                onPartial = { partial ->
                    val preview = subChunks.indices.map { subIndex ->
                        completed[subIndex] ?: if (subIndex == index) partial else subChunks[subIndex]
                    }
                    onPartial(TranslationTextChunker.merge(preview))
                },
            )
            completed[index] = translated
            onPartial(
                TranslationTextChunker.merge(
                    subChunks.indices.map { subIndex -> completed[subIndex] ?: subChunks[subIndex] },
                ),
            )
        }
        return TranslationTextChunker.merge(completed.filterNotNull())
    }

    private suspend fun processChunk(
        chunk: String,
        contextBefore: String,
        contextAfter: String,
        conversationId: String,
        apiKey: String,
        from: String,
        to: String,
        model: String,
        retry: Int,
        retryConfig: FallbackStrategy.RetryConfig?,
        onPartial: suspend (String) -> Unit,
    ): String {
        val parts = TranslationChunkParts.from(chunk)
        if (parts.coreText.isEmpty()) return chunk

        val protector = TranslationMarkerProtector.protect(parts.coreText)
        val continuityPrompt = buildContinuityPrompt(contextBefore, contextAfter)
        val markerInstruction = if (protector.protectedText != parts.coreText) {
            "形如 ⟦SHAFT_MARKER_0000⟧ 的占位符必须逐字保留，不能删除、改写或移动。"
        } else {
            ""
        }
        val (systemPrompt, userPrompt) = if (retryConfig?.useAlternativePrompt == true) {
            (fallbackStrategy.getAlternativePrompt() + continuityPrompt) to
                "$markerInstruction\n\n${protector.protectedText}"
        } else {
            promptBuilder.buildTranslationRequest(
                text = protector.protectedText,
                from = from,
                to = to,
                isMultiParagraph = false,
                termsPrompt = continuityPrompt,
                htmlOnly = markerInstruction,
            )
        }

        val requestBody = buildRequestBody(
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            model = model,
            temperature = retryConfig?.temperature ?: if (retry < 2) 0.3 else 0.2,
        )
        val request = buildRequest(apiKey, requestBody, conversationId)
        val rawResult = requestSemaphore.withPermit {
            client.newCall(request).executeAsync().use { response ->
                if (!response.isSuccessful) throw createApiException(response)
                if (response.body?.contentType()?.toString()?.contains("text/event-stream") == true) {
                    handleStreamingResponse(response) { partial ->
                        val restoredPartial = protector.restorePartial(normalizePartialOutput(partial))
                        onPartial(parts.restore(restoredPartial))
                    }
                } else {
                    handleJsonResponse(response)
                }
            }
        }

        val normalized = normalizeModelOutput(rawResult)
        val restored = protector.restore(normalized)
        return parts.restore(restored)
    }

    internal fun buildContinuityPrompt(contextBefore: String, contextAfter: String): String {
        if (contextBefore.isBlank() && contextAfter.isBlank()) return ""
        return buildString {
            append("\n相邻原文只用于统一人名、术语、指代和语气；不要翻译或输出这些上下文。")
            if (contextBefore.isNotBlank()) {
                append("\n<previous_source_context>\n")
                append(contextBefore.takeLast(CONTEXT_BEFORE_CHARS))
                append("\n</previous_source_context>")
            }
            if (contextAfter.isNotBlank()) {
                append("\n<next_source_context>\n")
                append(contextAfter.take(CONTEXT_AFTER_CHARS))
                append("\n</next_source_context>")
            }
        }
    }

    private fun buildRequestBody(
        systemPrompt: String,
        userPrompt: String,
        model: String,
        temperature: Double,
    ): String {
        val messages = JsonArray().apply {
            add(JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", systemPrompt)
            })
            add(JsonObject().apply {
                addProperty("role", "user")
                addProperty("content", userPrompt)
            })
        }

        return JsonObject().apply {
            addProperty("model", model)
            addProperty("temperature", temperature)
            addProperty("max_tokens", 8192)
            addProperty("stream", true)
            if (model == "grok-4.3" || model.startsWith("grok-4.3-")) {
                addProperty("reasoning_effort", "none")
            }
            add("messages", messages)
        }.toString()
    }

    private fun buildRequest(apiKey: String, requestBody: String, conversationId: String): Request {
        val body = requestBody.toRequestBody("application/json; charset=utf-8".toMediaType())
        return Request.Builder()
            .url("https://api.x.ai/v1/chat/completions")
            .addHeader("Authorization", "Bearer ${apiKey.trim()}")
            .addHeader("Content-Type", "application/json")
            .addHeader("x-grok-conv-id", conversationId)
            .post(body)
            .build()
    }

    private suspend fun handleStreamingResponse(
        response: Response,
        onPartial: suspend (String) -> Unit,
    ): String {
        val source = response.body?.source() ?: throw IOException("Empty response body")
        val content = StringBuilder()
        var finishReason: String? = null

        while (!source.exhausted()) {
            coroutineContext.ensureActive()
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data == "[DONE]") break
            if (data.isEmpty()) continue

            val root = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull()
                ?: continue
            val choice = root.getAsJsonArray("choices")?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
                ?: continue
            choice.get("finish_reason")?.takeIf { !it.isJsonNull }?.let { finishReason = it.asString }
            val delta = choice.getAsJsonObject("delta") ?: continue
            val piece = extractMessageContentOrNull(delta).orEmpty()
            if (piece.isNotEmpty()) {
                content.append(piece)
                onPartial(content.toString())
            }
        }

        if (finishReason == "length") {
            throw TranslationOutputException("The model output was truncated")
        }
        return content.toString().ifEmpty { throw IOException("Empty streaming response") }
    }

    private fun handleJsonResponse(response: Response): String {
        val responseBody = response.body?.string() ?: throw IOException("Empty response body")
        val root = JsonParser.parseString(responseBody).asJsonObject
        val choice = root.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
            ?: throw IOException("Invalid response structure: missing choices")
        if (choice.get("finish_reason")?.takeIf { !it.isJsonNull }?.asString == "length") {
            throw TranslationOutputException("The model output was truncated")
        }
        val message = choice.getAsJsonObject("message")
            ?: throw IOException("Invalid response structure: missing message")
        return extractMessageContentOrNull(message)
            ?: throw IOException("Invalid response structure: missing content")
    }

    private fun extractMessageContentOrNull(message: JsonObject): String? {
        val element = message.get("content") ?: return null
        if (element.isJsonNull) return null
        return when {
            element.isJsonPrimitive && element.asJsonPrimitive.isString -> element.asString
            element.isJsonArray -> buildString {
                element.asJsonArray.forEach { part ->
                    when {
                        part.isJsonPrimitive && part.asJsonPrimitive.isString -> append(part.asString)
                        part.isJsonObject -> part.asJsonObject.get("text")
                            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                            ?.let { append(it.asString) }
                    }
                }
            }
            else -> null
        }
    }

    private fun createApiException(response: Response): TranslationApiException {
        response.body?.close()
        val retryAfterMs = parseRetryAfter(response.header("Retry-After"))
        return TranslationApiException(
            statusCode = response.code,
            retryAfterMs = retryAfterMs,
            requestId = response.header("x-request-id"),
        )
    }

    internal fun parseRetryAfter(value: String?, nowMs: Long = System.currentTimeMillis()): Long? {
        val normalized = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        normalized.toDoubleOrNull()?.let { seconds ->
            return (seconds * 1000).toLong().coerceAtLeast(0L)
        }
        val date = runCatching {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
                isLenient = false
            }.parse(normalized)
        }.getOrNull() ?: return null
        return (date.time - nowMs).coerceAtLeast(0L)
    }

    private suspend fun waitBeforeRetry(baseDelayMs: Long) {
        delay(baseDelayMs + Random.nextLong(150L, 550L))
    }

    private fun normalizePartialOutput(raw: String): String {
        return raw.removePrefix("```text\n")
            .removePrefix("```\n")
            .removeSuffix("```")
    }

    internal fun normalizeModelOutput(raw: String): String {
        var output = raw.trim()
        if (output.startsWith("```")) {
            val firstNewline = output.indexOf('\n')
            output = if (firstNewline > 0) output.substring(firstNewline + 1) else output.removePrefix("```")
            output = output.removeSuffix("```").trim()
        }
        return stripYamlTranslationArtifactsIfPresent(output)
    }

    internal fun stripYamlTranslationArtifactsIfPresent(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || !trimmed.contains("text:")) return input
        val lines = trimmed.lines()
        if (lines.none { it.trim().matches(ID_LINE_REGEX) }) return input

        val paragraphs = mutableListOf<String>()
        var index = 0
        while (index < lines.size) {
            if (!lines[index].trim().matches(ID_LINE_REGEX)) {
                index++
                continue
            }
            index++
            while (index < lines.size && lines[index].isBlank()) index++
            if (index >= lines.size) break
            val header = lines[index].trim()
            if (!header.startsWith("text:") || !header.contains("|")) {
                index++
                continue
            }
            index++
            val rawBlock = mutableListOf<String>()
            while (index < lines.size && !lines[index].trim().matches(ID_LINE_REGEX)) {
                rawBlock += lines[index]
                index++
            }
            val minIndent = rawBlock.filter { it.isNotBlank() }
                .minOfOrNull { line -> line.takeWhile { it == ' ' || it == '\t' }.length }
                ?: 0
            val paragraph = rawBlock.joinToString("\n") { line ->
                if (line.isBlank()) "" else line.drop(minIndent)
            }.trim()
            if (paragraph.isNotEmpty()) paragraphs += paragraph
        }
        return paragraphs.joinToString("\n\n").ifBlank { input }
    }
}
