package ceui.pixiv.translation

import android.content.Context
import androidx.annotation.WorkerThread
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
import java.util.Date
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.random.Random

class TranslationManager private constructor() {

    companion object {
        private const val CHUNK_MAX_CHARS = 1250
        private const val PARALLEL_CHUNK_CAP = 8
        private const val GLOBAL_REQUEST_CAP = 8
        private const val CHUNK_ATTEMPT_CAP = 4
        private const val MAX_SPLIT_DEPTH = 2
        private const val CONTENT_PROGRESS_INTERVAL_NS = 650_000_000L
        private const val STREAM_PREVIEW_INTERVAL_NS = 450_000_000L
        private const val CONTEXT_BEFORE_CHARS = 320
        private const val CONTEXT_AFTER_CHARS = 160
        private const val PROMPT_VERSION = 5
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

    private data class ApiCompletion(
        val content: String,
        val diagnostics: TranslationResponseDiagnostics,
    )

    suspend fun translate(
        text: String,
        apiKey: String,
        from: String = "Japanese",
        to: String = "Chinese",
        model: String = TranslationSettingsStore.getModel(),
        priorityProcessing: Boolean = TranslationSettingsStore.isPriorityEnabled(),
        context: Context? = null,
        onProgress: (String) -> Unit = {},
        onProgressUpdate: (
            completedChunks: Int,
            totalChunks: Int,
            processedSourceChars: Int,
            totalSourceChars: Int,
        ) -> Unit = { _, _, _, _ -> },
        onRetry: (TranslationRetryEvent) -> Unit = {},
        onPartialResult: (TranslationPartialResultEvent) -> Unit = {},
    ): String {
        require(apiKey.isNotBlank()) { "API key must not be blank" }
        context?.let { TranslationConfig.getInstance().loadConfig(it) }
        coroutineContext.ensureActive()

        val chunks = TranslationTextChunker.split(text, CHUNK_MAX_CHARS)
        if (chunks.isEmpty()) return text
        val conversationId = UUID.randomUUID().toString()
        val sessionDiagnostics = TranslationSessionDiagnostics(
            conversationId = conversationId,
            promptVersion = PROMPT_VERSION,
            promptConfigSource = TranslationConfig.getInstance().getPromptSource(),
            topLevelChunkMaxChars = CHUNK_MAX_CHARS,
            parallelChunkCap = PARALLEL_CHUNK_CAP,
            globalRequestCap = GLOBAL_REQUEST_CAP,
            chunkAttemptCap = CHUNK_ATTEMPT_CAP,
            maxSplitDepth = MAX_SPLIT_DEPTH,
            priorityProcessing = priorityProcessing,
        )

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
        val initiallyCompleted = results.count { it != null }
        val completedCount = AtomicInteger(initiallyCompleted)
        val successfulCount = AtomicInteger(initiallyCompleted)
        val failures = ConcurrentLinkedQueue<TranslationFailureRecord>()
        val diagnosticSequence = AtomicLong(0L)
        val attemptDiagnostics = ConcurrentLinkedQueue<TranslationAttemptRecord>()
        val retryDiagnostics = ConcurrentLinkedQueue<TranslationRetryDecisionRecord>()
        val processedSourceChars = AtomicInteger(
            chunks.indices.sumOf { index -> if (results[index] != null) chunks[index].length else 0 },
        )
        val lastContentProgressNs = AtomicLong(0L)
        val latestPreview = AtomicReference("")
        val totalSourceChars = text.length

        suspend fun publishProgress(preview: String, force: Boolean = false) {
            latestPreview.set(preview.takeLast(180))
            val now = System.nanoTime()
            val previous = lastContentProgressNs.get()
            val shouldPublish = force ||
                (now - previous >= CONTENT_PROGRESS_INTERVAL_NS &&
                    lastContentProgressNs.compareAndSet(previous, now))
            if (!shouldPublish) return

            withContext(Dispatchers.Main.immediate) {
                runCatching { onProgress(latestPreview.get()) }
                    .onFailure { Timber.w(it, "Translation preview callback failed") }
            }
        }

        suspend fun publishRetry(event: TranslationRetryEvent) {
            withContext(Dispatchers.Main.immediate) {
                runCatching { onRetry(event) }
                    .onFailure { Timber.w(it, "Translation retry callback failed") }
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

        suspend fun publishPartialResult(event: TranslationPartialResultEvent) {
            withContext(Dispatchers.Main.immediate) {
                runCatching { onPartialResult(event) }
                    .onFailure { Timber.w(it, "Translation partial-result callback failed") }
            }
        }

        if (initiallyCompleted > 0) {
            publishCountProgress()
            results.filterNotNull().lastOrNull()?.let { publishProgress(it, force = true) }
        }

        val missingIndices = chunks.indices.filter { results[it] == null }
        if (missingIndices.isNotEmpty()) {
            val parallelism = minOf(PARALLEL_CHUNK_CAP, missingIndices.size)
            val dispatcher = Dispatchers.IO.limitedParallelism(max(1, parallelism))
            try {
                coroutineScope {
                    missingIndices.map { index ->
                        async(dispatcher) {
                            val chunk = chunks[index]
                            val translated = try {
                                processChunkWithRetry(
                                    chunk = chunk,
                                    chunkIndex = index,
                                    totalChunks = chunks.size,
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
                                    priorityProcessing = priorityProcessing,
                                    splitPath = (index + 1).toString(),
                                    onRetry = ::publishRetry,
                                    onPartial = { partial ->
                                        publishProgress(partial)
                                    },
                                    onAttemptDiagnostic = { record ->
                                        attemptDiagnostics.add(
                                            record.copy(sequence = diagnosticSequence.incrementAndGet()),
                                        )
                                    },
                                    onRetryDiagnostic = { record ->
                                        retryDiagnostics.add(
                                            record.copy(sequence = diagnosticSequence.incrementAndGet()),
                                        )
                                    },
                                )
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: IOException) {
                                failures.add(
                                    createFailureRecord(
                                        chunkIndex = index,
                                        totalChunks = chunks.size,
                                        topLevelChunk = chunk,
                                        error = error,
                                        attempts = attemptDiagnostics.filter {
                                            it.topLevelChunkIndex == index
                                        },
                                        retryDecisions = retryDiagnostics.filter {
                                            it.topLevelChunkIndex == index
                                        },
                                    ),
                                )
                                if (error is TranslationApiException && !error.isRetryable) {
                                    throw error
                                }
                                val fallback = buildFailedChunkFallback(
                                    context = context,
                                    chunkIndex = index,
                                    totalChunks = chunks.size,
                                    sourceChunk = chunk,
                                )
                                synchronized(stateLock) {
                                    results[index] = fallback
                                }
                                processedSourceChars.addAndGet(chunk.length)
                                completedCount.incrementAndGet()
                                publishCountProgress()
                                publishProgress(
                                    fallback,
                                    force = completedCount.get() == chunks.size,
                                )
                                return@async
                            }

                            synchronized(stateLock) {
                                results[index] = translated
                            }
                            cache?.saveChunk(index, translated)
                            successfulCount.incrementAndGet()
                            processedSourceChars.addAndGet(chunk.length)
                            completedCount.incrementAndGet()
                            publishCountProgress()
                            publishProgress(translated, force = completedCount.get() == chunks.size)
                        }
                    }.awaitAll()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                val logLocation = context?.applicationContext?.let { appContext ->
                    TranslationFailureLogger.save(
                        context = appContext,
                        model = model,
                        from = from,
                        to = to,
                        totalSourceChars = totalSourceChars,
                        totalChunks = chunks.size,
                        session = sessionDiagnostics,
                        failures = failures.toList(),
                    )
                }
                throw TranslationLoggedException(error, logLocation)
            }
        }

        val completedResults = synchronized(stateLock) { results.toList() }
        if (completedResults.any { it == null }) {
            throw TranslationOutputException("Translation ended with incomplete chunks")
        }
        val failureSnapshot = failures.toList()
        if (failureSnapshot.isNotEmpty()) {
            val logLocation = context?.applicationContext?.let { appContext ->
                TranslationFailureLogger.save(
                    context = appContext,
                    model = model,
                    from = from,
                    to = to,
                    totalSourceChars = totalSourceChars,
                    totalChunks = chunks.size,
                    session = sessionDiagnostics,
                    failures = failureSnapshot,
                )
            }
            if (successfulCount.get() == 0) {
                throw TranslationLoggedException(failureSnapshot.first().error, logLocation)
            }
            publishPartialResult(
                TranslationPartialResultEvent(
                    failedChunks = failureSnapshot.size,
                    totalChunks = chunks.size,
                    logLocation = logLocation,
                ),
            )
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
                onRetry = { event -> onProgressUpdate.onRetry(event) },
                onPartialResult = { event -> onProgressUpdate.onPartialResult(event) },
            )
        }
    }

    private suspend fun processChunkWithRetry(
        chunk: String,
        chunkIndex: Int,
        totalChunks: Int,
        contextBefore: String,
        contextAfter: String,
        conversationId: String,
        apiKey: String,
        maxAttempts: Int,
        from: String,
        to: String,
        model: String,
        priorityProcessing: Boolean,
        splitDepth: Int = 0,
        splitPath: String,
        initialConfig: FallbackStrategy.RetryConfig? = null,
        onRetry: suspend (TranslationRetryEvent) -> Unit,
        onPartial: suspend (String) -> Unit,
        onAttemptDiagnostic: (TranslationAttemptRecord) -> Unit,
        onRetryDiagnostic: (TranslationRetryDecisionRecord) -> Unit,
    ): String {
        var attempt = 0
        var attemptConfig = initialConfig
        var consecutiveRefusals = 0

        while (attempt < maxAttempts) {
            coroutineContext.ensureActive()
            var lastFailure: Exception? = null
            var retryAfterMs = 0L
            val errorType = try {
                val result = processChunk(
                    chunk = chunk,
                    contextBefore = contextBefore,
                    contextAfter = contextAfter,
                    conversationId = conversationId,
                    apiKey = apiKey,
                    from = from,
                    to = to,
                    model = model,
                    priorityProcessing = priorityProcessing,
                    retry = attempt,
                    retryConfig = attemptConfig,
                    onPartial = onPartial,
                    chunkIndex = chunkIndex,
                    totalChunks = totalChunks,
                    splitDepth = splitDepth,
                    splitPath = splitPath,
                    maxAttempts = maxAttempts,
                    onAttemptDiagnostic = onAttemptDiagnostic,
                )

                return result
            } catch (e: CancellationException) {
                throw e
            } catch (e: TranslationApiException) {
                if (!e.isRetryable) throw e
                lastFailure = e
                retryAfterMs = e.retryAfterMs ?: 0L
                FallbackStrategy.ErrorType.NETWORK_ERROR
            } catch (e: TranslationRefusedException) {
                lastFailure = e
                FallbackStrategy.ErrorType.BLOCKED_CONTENT
            } catch (e: TranslationMarkerException) {
                lastFailure = e
                FallbackStrategy.ErrorType.FORMAT_ERROR
            } catch (e: TranslationOutputException) {
                lastFailure = e
                FallbackStrategy.ErrorType.FORMAT_ERROR
            } catch (e: SocketTimeoutException) {
                lastFailure = IOException("Translation timed out", e)
                FallbackStrategy.ErrorType.TIMEOUT
            } catch (e: IOException) {
                lastFailure = e
                FallbackStrategy.ErrorType.NETWORK_ERROR
            }

            consecutiveRefusals = if (errorType == FallbackStrategy.ErrorType.BLOCKED_CONTENT) {
                consecutiveRefusals + 1
            } else {
                0
            }

            if (attempt + 1 >= maxAttempts) {
                onRetryDiagnostic(
                    TranslationRetryDecisionRecord(
                        topLevelChunkIndex = chunkIndex,
                        splitPath = splitPath,
                        splitDepth = splitDepth,
                        completedAttempt = attempt + 1,
                        classifiedError = errorType.name,
                        consecutiveRefusals = consecutiveRefusals,
                        action = "stop",
                        shouldRetry = false,
                        splitRequested = false,
                        splitPerformed = false,
                        currentChunkLength = chunk.length,
                        requestedMaxChunkSize = null,
                        nextAlternativePrompt = null,
                        nextTemperature = null,
                        baseDelayMs = null,
                        terminalReason = "attempt cap reached",
                    ),
                )
                throw lastFailure ?: TranslationOutputException("Translation output validation failed")
            }

            val nextConfig = when (errorType) {
                FallbackStrategy.ErrorType.BLOCKED_CONTENT ->
                    fallbackStrategy.handleBlockedContent(chunk, attempt)
                FallbackStrategy.ErrorType.FORMAT_ERROR ->
                    fallbackStrategy.handleFormatError(chunk, attempt)
                FallbackStrategy.ErrorType.TIMEOUT ->
                    fallbackStrategy.handleTimeout(chunk, attempt)
                else -> fallbackStrategy.handleNetworkError(attempt)
            }
            if (!nextConfig.shouldRetry) {
                onRetryDiagnostic(
                    TranslationRetryDecisionRecord(
                        topLevelChunkIndex = chunkIndex,
                        splitPath = splitPath,
                        splitDepth = splitDepth,
                        completedAttempt = attempt + 1,
                        classifiedError = errorType.name,
                        consecutiveRefusals = consecutiveRefusals,
                        action = "stop",
                        shouldRetry = false,
                        splitRequested = nextConfig.splitIntoSmallerChunks,
                        splitPerformed = false,
                        currentChunkLength = chunk.length,
                        requestedMaxChunkSize = nextConfig.maxChunkSize,
                        nextAlternativePrompt = nextConfig.useAlternativePrompt,
                        nextTemperature = nextConfig.temperature,
                        baseDelayMs = nextConfig.delayMs,
                        terminalReason = "retry strategy declined retry",
                    ),
                )
                throw lastFailure ?: TranslationOutputException("Translation retry was rejected")
            }

            val shouldSplit = nextConfig.splitIntoSmallerChunks &&
                chunk.length > nextConfig.maxChunkSize &&
                splitDepth < MAX_SPLIT_DEPTH
            if (errorType == FallbackStrategy.ErrorType.BLOCKED_CONTENT &&
                fallbackStrategy.shouldStopRepeatedRefusal(
                    consecutiveRefusals = consecutiveRefusals,
                    chunkLength = chunk.length,
                    splitDepth = splitDepth,
                    maxSplitDepth = MAX_SPLIT_DEPTH,
                )
            ) {
                onRetryDiagnostic(
                    TranslationRetryDecisionRecord(
                        topLevelChunkIndex = chunkIndex,
                        splitPath = splitPath,
                        splitDepth = splitDepth,
                        completedAttempt = attempt + 1,
                        classifiedError = errorType.name,
                        consecutiveRefusals = consecutiveRefusals,
                        action = "stop",
                        shouldRetry = false,
                        splitRequested = nextConfig.splitIntoSmallerChunks,
                        splitPerformed = false,
                        currentChunkLength = chunk.length,
                        requestedMaxChunkSize = nextConfig.maxChunkSize,
                        nextAlternativePrompt = nextConfig.useAlternativePrompt,
                        nextTemperature = nextConfig.temperature,
                        baseDelayMs = nextConfig.delayMs,
                        terminalReason = "repeated refusal at maximum split depth",
                    ),
                )
                throw lastFailure ?: TranslationRefusedException(
                    "The model repeatedly refused the smallest retry chunk",
                )
            }
            onRetryDiagnostic(
                TranslationRetryDecisionRecord(
                    topLevelChunkIndex = chunkIndex,
                    splitPath = splitPath,
                    splitDepth = splitDepth,
                    completedAttempt = attempt + 1,
                    classifiedError = errorType.name,
                    consecutiveRefusals = consecutiveRefusals,
                    action = if (shouldSplit) "split" else "retry same chunk",
                    shouldRetry = true,
                    splitRequested = nextConfig.splitIntoSmallerChunks,
                    splitPerformed = shouldSplit,
                    currentChunkLength = chunk.length,
                    requestedMaxChunkSize = nextConfig.maxChunkSize,
                    nextAlternativePrompt = nextConfig.useAlternativePrompt,
                    nextTemperature = nextConfig.temperature,
                    baseDelayMs = max(nextConfig.delayMs, retryAfterMs),
                    terminalReason = null,
                ),
            )
            onRetry(
                TranslationRetryEvent(
                    chunkIndex = chunkIndex,
                    totalChunks = totalChunks,
                    attempt = attempt + 2,
                    maxAttempts = maxAttempts,
                    splitting = shouldSplit,
                ),
            )
            waitBeforeRetry(max(nextConfig.delayMs, retryAfterMs))

            if (shouldSplit) {
                return translateSmallerChunks(
                    chunk = chunk,
                    chunkIndex = chunkIndex,
                    totalChunks = totalChunks,
                    contextBefore = contextBefore,
                    contextAfter = contextAfter,
                    conversationId = conversationId,
                    apiKey = apiKey,
                    from = from,
                    to = to,
                    model = model,
                    priorityProcessing = priorityProcessing,
                    maxChunkSize = nextConfig.maxChunkSize,
                    splitDepth = splitDepth + 1,
                    splitPath = splitPath,
                    initialConfig = nextConfig.copy(splitIntoSmallerChunks = false),
                    onRetry = onRetry,
                    onPartial = onPartial,
                    onAttemptDiagnostic = onAttemptDiagnostic,
                    onRetryDiagnostic = onRetryDiagnostic,
                )
            }

            attemptConfig = nextConfig
            attempt++
        }

        throw IOException("Chunk translation failed after $maxAttempts attempts")
    }

    private suspend fun translateSmallerChunks(
        chunk: String,
        chunkIndex: Int,
        totalChunks: Int,
        contextBefore: String,
        contextAfter: String,
        conversationId: String,
        apiKey: String,
        from: String,
        to: String,
        model: String,
        priorityProcessing: Boolean,
        maxChunkSize: Int,
        splitDepth: Int,
        splitPath: String,
        initialConfig: FallbackStrategy.RetryConfig,
        onRetry: suspend (TranslationRetryEvent) -> Unit,
        onPartial: suspend (String) -> Unit,
        onAttemptDiagnostic: (TranslationAttemptRecord) -> Unit,
        onRetryDiagnostic: (TranslationRetryDecisionRecord) -> Unit,
    ): String {
        val subChunks = TranslationTextChunker.split(chunk, maxChunkSize)
        val completed = MutableList<String?>(subChunks.size) { null }
        val dispatcher = Dispatchers.IO.limitedParallelism(minOf(4, subChunks.size).coerceAtLeast(1))
        coroutineScope {
            subChunks.mapIndexed { index, subChunk ->
                async(dispatcher) {
                    val translated = processChunkWithRetry(
                        chunk = subChunk,
                        chunkIndex = chunkIndex,
                        totalChunks = totalChunks,
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
                        priorityProcessing = priorityProcessing,
                        splitDepth = splitDepth,
                        splitPath = "$splitPath.${index + 1}",
                        initialConfig = initialConfig,
                        onRetry = onRetry,
                        onPartial = onPartial,
                        onAttemptDiagnostic = onAttemptDiagnostic,
                        onRetryDiagnostic = onRetryDiagnostic,
                    )
                    synchronized(completed) { completed[index] = translated }
                }
            }.awaitAll()
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
        priorityProcessing: Boolean,
        retry: Int,
        retryConfig: FallbackStrategy.RetryConfig?,
        onPartial: suspend (String) -> Unit,
        chunkIndex: Int,
        totalChunks: Int,
        splitDepth: Int,
        splitPath: String,
        maxAttempts: Int,
        onAttemptDiagnostic: (TranslationAttemptRecord) -> Unit,
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
        val userInstruction = listOf(markerInstruction, continuityPrompt)
            .filter { it.isNotBlank() }
            .joinToString("\n")
        val (systemPrompt, userPrompt) = if (retryConfig?.useAlternativePrompt == true) {
            fallbackStrategy.getAlternativePrompt() to
                "$userInstruction\n\n<translation_source>\n${protector.protectedText}\n</translation_source>"
        } else {
            promptBuilder.buildTranslationRequest(
                text = protector.protectedText,
                from = from,
                to = to,
                isMultiParagraph = false,
                htmlOnly = userInstruction,
            )
        }

        val temperature = retryConfig?.temperature ?: if (retry == 0) 0.15 else 0.1
        val reasoningEffort = reasoningEffortForModel(model)
        val startedAtUtc = diagnosticUtcTimestamp()
        val startedAtNs = System.nanoTime()
        var completion: ApiCompletion? = null
        var rawResult: String? = null
        var normalizedResult: String? = null
        var validationClassification: FallbackStrategy.ErrorType? = null
        var attemptFailure: Throwable? = null

        val requestBody = buildRequestBody(
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            model = model,
            temperature = temperature,
            priorityProcessing = priorityProcessing,
        )
        val request = buildRequest(apiKey, requestBody, conversationId)
        try {
            try {
                completion = requestSemaphore.withPermit {
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
                val response = requireNotNull(completion)
                rawResult = response.content
                if (response.content.isEmpty() && response.diagnostics.sseDataEvents != null) {
                    throw IOException("Empty streaming response")
                }
                if (response.diagnostics.finishReason != null &&
                    response.diagnostics.finishReason != "stop"
                ) {
                    throw TranslationOutputException(
                        message = "The model stopped with reason: ${response.diagnostics.finishReason}",
                        modelOutput = response.content,
                    )
                }
            } catch (error: TranslationOutputException) {
                if (error.sourceChunk != null) throw error
                throw TranslationOutputException(
                    message = error.message ?: "The model returned incomplete output",
                    sourceChunk = chunk,
                    modelOutput = error.modelOutput,
                    cause = error,
                )
            }

            val normalized = normalizeModelOutput(requireNotNull(rawResult))
            normalizedResult = normalized
            validationClassification = fallbackStrategy.classifyOutput(chunk, normalized)
            // Validate before restoring markers. A refusal omits all placeholders and must be
            // handled by the blocked-content strategy, not misreported as marker corruption.
            fallbackStrategy.requireValidOutput(chunk, normalized)
            val restored = try {
                protector.restore(normalized)
            } catch (error: TranslationMarkerException) {
                throw TranslationMarkerException(
                    message = error.message ?: "A protected Pixiv marker was changed by the model",
                    sourceChunk = chunk,
                    modelOutput = normalized,
                    cause = error,
                )
            }
            return parts.restore(restored)
        } catch (error: Throwable) {
            attemptFailure = error
            throw error
        } finally {
            val responseDiagnostics = completion?.diagnostics
                ?: (attemptFailure as? TranslationApiException)?.let { apiError ->
                    TranslationResponseDiagnostics(
                        httpStatus = apiError.statusCode,
                        contentType = apiError.contentType,
                        requestId = apiError.requestId,
                        completionId = null,
                        responseModel = null,
                        systemFingerprint = null,
                        finishReason = null,
                        promptTokens = null,
                        completionTokens = null,
                        totalTokens = null,
                        cachedPromptTokens = null,
                        reasoningTokens = null,
                        sseDataEvents = null,
                        malformedSseEvents = null,
                        apiErrorBody = apiError.responseBody,
                    )
                }
            runCatching {
                onAttemptDiagnostic(
                    TranslationAttemptRecord(
                        topLevelChunkIndex = chunkIndex,
                        totalTopLevelChunks = totalChunks,
                        splitPath = splitPath,
                        splitDepth = splitDepth,
                        attempt = retry + 1,
                        maxAttempts = maxAttempts,
                        startedAtUtc = startedAtUtc,
                        durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNs),
                        sourceText = chunk,
                        contextBeforeLength = contextBefore.length,
                        contextAfterLength = contextAfter.length,
                        promptMode = if (retryConfig?.useAlternativePrompt == true) {
                            "alternative"
                        } else {
                            "configured"
                        },
                        systemPrompt = systemPrompt,
                        userPrompt = userPrompt,
                        model = model,
                        reasoningEffort = reasoningEffort,
                        temperature = temperature,
                        priorityProcessing = priorityProcessing,
                        response = responseDiagnostics,
                        rawModelOutput = rawResult,
                        normalizedModelOutput = normalizedResult,
                        validationClassification = validationClassification?.name,
                        sourceEqualsNormalizedOutput = normalizedResult?.let {
                            chunk.trim() == it.trim()
                        },
                        errorType = attemptFailure?.javaClass?.simpleName,
                        errorMessage = attemptFailure?.message,
                    ),
                )
            }.onFailure { Timber.w(it, "Unable to collect translation attempt diagnostics") }
        }
    }

    private fun createFailureRecord(
        chunkIndex: Int,
        totalChunks: Int,
        topLevelChunk: String,
        error: IOException,
        attempts: List<TranslationAttemptRecord>,
        retryDecisions: List<TranslationRetryDecisionRecord>,
    ): TranslationFailureRecord {
        val contentError = error as? TranslationContentException
        return TranslationFailureRecord(
            chunkIndex = chunkIndex,
            totalChunks = totalChunks,
            sourceChunk = contentError?.sourceChunk ?: topLevelChunk,
            modelOutput = contentError?.modelOutput,
            error = error,
            attempts = attempts,
            retryDecisions = retryDecisions,
        )
    }

    private fun buildFailedChunkFallback(
        context: Context?,
        chunkIndex: Int,
        totalChunks: Int,
        sourceChunk: String,
    ): String {
        val header = context?.getString(
            ceui.lisa.R.string.translation_failed_chunk_header,
            chunkIndex + 1,
            totalChunks,
        ) ?: "[Translation failed for chunk ${chunkIndex + 1}/$totalChunks; original text follows]"
        val footer = context?.getString(
            ceui.lisa.R.string.translation_failed_chunk_footer,
        ) ?: "[End of untranslated chunk]"
        return "\n$header\n$sourceChunk\n$footer\n"
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
        priorityProcessing: Boolean,
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
            addProperty("max_tokens", 4096)
            addProperty("stream", true)
            if (priorityProcessing) {
                addProperty("service_tier", "priority")
            }
            reasoningEffortForModel(model)?.let { effort ->
                addProperty("reasoning_effort", effort)
            }
            add("messages", messages)
        }.toString()
    }

    private fun reasoningEffortForModel(model: String): String? =
        if (model == "grok-4.3" || model.startsWith("grok-4.3-")) "none" else null

    private fun diagnosticUtcTimestamp(): String = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        Locale.US,
    ).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())

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
    ): ApiCompletion {
        val source = response.body?.source() ?: throw IOException("Empty response body")
        val content = StringBuilder()
        var finishReason: String? = null
        var completionId: String? = null
        var responseModel: String? = null
        var systemFingerprint: String? = null
        var promptTokens: Int? = null
        var completionTokens: Int? = null
        var totalTokens: Int? = null
        var cachedPromptTokens: Int? = null
        var reasoningTokens: Int? = null
        var sseDataEvents = 0
        var malformedSseEvents = 0
        var lastPreviewNs = 0L

        while (!source.exhausted()) {
            coroutineContext.ensureActive()
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val data = line.removePrefix("data:").trim()
            if (data == "[DONE]") break
            if (data.isEmpty()) continue
            sseDataEvents++

            val root = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull()
            if (root == null) {
                malformedSseEvents++
                continue
            }
            completionId = root.stringOrNull("id") ?: completionId
            responseModel = root.stringOrNull("model") ?: responseModel
            systemFingerprint = root.stringOrNull("system_fingerprint") ?: systemFingerprint
            root.getAsJsonObject("usage")?.let { usage ->
                promptTokens = usage.intOrNull("prompt_tokens") ?: promptTokens
                completionTokens = usage.intOrNull("completion_tokens") ?: completionTokens
                totalTokens = usage.intOrNull("total_tokens") ?: totalTokens
                cachedPromptTokens = usage.getAsJsonObject("prompt_tokens_details")
                    ?.intOrNull("cached_tokens") ?: cachedPromptTokens
                reasoningTokens = usage.getAsJsonObject("completion_tokens_details")
                    ?.intOrNull("reasoning_tokens") ?: reasoningTokens
            }
            val choice = root.getAsJsonArray("choices")?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
                ?: continue
            choice.get("finish_reason")?.takeIf { !it.isJsonNull }?.let { finishReason = it.asString }
            val delta = choice.getAsJsonObject("delta") ?: continue
            val piece = extractMessageContentOrNull(delta).orEmpty()
            if (piece.isNotEmpty()) {
                content.append(piece)
                val now = System.nanoTime()
                if (now - lastPreviewNs >= STREAM_PREVIEW_INTERVAL_NS) {
                    lastPreviewNs = now
                    onPartial(content.toString())
                }
            }
        }

        if (content.isNotEmpty()) onPartial(content.toString())
        return ApiCompletion(
            content = content.toString(),
            diagnostics = TranslationResponseDiagnostics(
                httpStatus = response.code,
                contentType = response.body?.contentType()?.toString(),
                requestId = response.header("x-request-id"),
                completionId = completionId,
                responseModel = responseModel,
                systemFingerprint = systemFingerprint,
                finishReason = finishReason,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                totalTokens = totalTokens,
                cachedPromptTokens = cachedPromptTokens,
                reasoningTokens = reasoningTokens,
                sseDataEvents = sseDataEvents,
                malformedSseEvents = malformedSseEvents,
            ),
        )
    }

    private fun handleJsonResponse(response: Response): ApiCompletion {
        val responseBody = response.body?.string() ?: throw IOException("Empty response body")
        val root = JsonParser.parseString(responseBody).asJsonObject
        val choice = root.getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
            ?: throw IOException("Invalid response structure: missing choices")
        val finishReason = choice.get("finish_reason")?.takeIf { !it.isJsonNull }?.asString
        val message = choice.getAsJsonObject("message")
            ?: throw IOException("Invalid response structure: missing message")
        val content = extractMessageContentOrNull(message)
            ?: throw IOException("Invalid response structure: missing content")
        val usage = root.getAsJsonObject("usage")
        return ApiCompletion(
            content = content,
            diagnostics = TranslationResponseDiagnostics(
                httpStatus = response.code,
                contentType = response.body?.contentType()?.toString(),
                requestId = response.header("x-request-id"),
                completionId = root.stringOrNull("id"),
                responseModel = root.stringOrNull("model"),
                systemFingerprint = root.stringOrNull("system_fingerprint"),
                finishReason = finishReason,
                promptTokens = usage?.intOrNull("prompt_tokens"),
                completionTokens = usage?.intOrNull("completion_tokens"),
                totalTokens = usage?.intOrNull("total_tokens"),
                cachedPromptTokens = usage?.getAsJsonObject("prompt_tokens_details")
                    ?.intOrNull("cached_tokens"),
                reasoningTokens = usage?.getAsJsonObject("completion_tokens_details")
                    ?.intOrNull("reasoning_tokens"),
                sseDataEvents = null,
                malformedSseEvents = null,
            ),
        )
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
        val contentType = response.body?.contentType()?.toString()
        val responseBody = runCatching { response.body?.string() }.getOrNull()
        val retryAfterMs = parseRetryAfter(response.header("Retry-After"))
        return TranslationApiException(
            statusCode = response.code,
            retryAfterMs = retryAfterMs,
            requestId = response.header("x-request-id"),
            contentType = contentType,
            responseBody = responseBody,
        )
    }

    private fun JsonObject.stringOrNull(name: String): String? = get(name)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        ?.asString

    private fun JsonObject.intOrNull(name: String): Int? = get(name)
        ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
        ?.asInt

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
