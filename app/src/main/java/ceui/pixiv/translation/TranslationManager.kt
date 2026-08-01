package ceui.pixiv.translation

import android.content.Context
import android.util.Log
import androidx.annotation.WorkerThread
import ceui.lisa.BuildConfig
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.coroutines.executeAsync
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class TranslationManager private constructor() {

    companion object {
        private const val TAG = "TranslationManager"

        @Volatile
        private var instance: TranslationManager? = null

        fun getInstance(): TranslationManager {
            return instance ?: synchronized(this) {
                instance ?: TranslationManager().also { instance = it }
            }
        }

        private fun isLikelyTimeout(e: Throwable): Boolean {
            if (e is SocketTimeoutException) return true
            val msg = e.message ?: return false
            return msg.contains("timeout", ignoreCase = true) ||
                msg.contains("timed out", ignoreCase = true)
        }

        private val ID_LINE_REGEX = Regex("""-\s*id:\s*\d+""")

        /** 单块字符上限：略增大可减少请求次数、缩短总等待（在模型上限内折中） */
        private const val CHUNK_MAX_CHARS = 1600

        /** 并发翻译块数；过大易 429，过小总耗时长 */
        private const val PARALLEL_CHUNK_CAP = 4

        /** 每块最多尝试轮数（含首次请求） */
        private const val CHUNK_ATTEMPT_CAP = 4

        /** Avoid rebuilding the whole novel list for every near-simultaneous chunk completion. */
        private const val CONTENT_PROGRESS_INTERVAL_NS = 250_000_000L
    }

    private val promptBuilder = PromptBuilder()
    private val fallbackStrategy = FallbackStrategy()
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    suspend fun translate(
        text: String,
        apiKey: String,
        from: String = "Japanese",
        to: String = "Chinese",
        context: Context? = null,
        onProgress: (String) -> Unit = {},
        onProgressUpdate: (completedChunks: Int, totalChunks: Int, processedSourceChars: Int, totalSourceChars: Int) -> Unit = { _, _, _, _ -> },
    ): String {
        context?.let { TranslationConfig.getInstance().loadConfig(it) }
        coroutineContext.ensureActive()

        val chunks = TranslationTextChunker.split(text, CHUNK_MAX_CHARS)
        if (chunks.isEmpty()) return text
        Log.d(TAG, "Split into ${chunks.size} chunks.")
        val results = Collections.synchronizedList(MutableList<String?>(chunks.size) { null })
        val parallelism = minOf(PARALLEL_CHUNK_CAP, maxOf(1, chunks.size))
        val dispatcher = Dispatchers.IO.limitedParallelism(parallelism)
        val completedCount = AtomicInteger(0)
        val processedSourceChars = AtomicInteger(0)
        val lastContentProgressNs = AtomicLong(0L)
        val totalSourceChars = text.length

        coroutineScope {
            val jobs: List<Job> = chunks.mapIndexed { index, chunk ->
                launch(dispatcher) {
                    coroutineContext.ensureActive()
                    Log.d(TAG, "Translating chunk $index...")
                    val translated = processChunkWithRetry(chunk, apiKey, CHUNK_ATTEMPT_CAP, from, to)
                    results[index] = translated
                    processedSourceChars.addAndGet(chunk.length)
                    Log.d(TAG, "Chunk $index result: ${translated.take(50)}...")

                    val completed = completedCount.incrementAndGet()
                    val chars = processedSourceChars.get()
                    withContext(Dispatchers.Main) {
                        onProgressUpdate(completed, chunks.size, chars, totalSourceChars)
                    }

                    val now = System.nanoTime()
                    val previous = lastContentProgressNs.get()
                    val shouldPublishContent = completed == chunks.size ||
                        (now - previous >= CONTENT_PROGRESS_INTERVAL_NS &&
                            lastContentProgressNs.compareAndSet(previous, now))
                    if (shouldPublishContent) {
                        val snapshot = synchronized(results) {
                            results.mapIndexed { resultIndex, result -> result ?: chunks[resultIndex] }
                        }
                        val partialText = TranslationTextChunker.merge(snapshot)
                        withContext(Dispatchers.Main) {
                            onProgress(partialText)
                        }
                    }
                }
            }
            jobs.joinAll()
        }

        val finalResult = TranslationTextChunker.merge(results.filterNotNull())
        Log.d(TAG, "Final translation result: ${finalResult.take(50)}...")
        return finalResult
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
                onProgressUpdate = { c, t, pc, tc ->
                    onProgressUpdate.onProgress(c, t, pc, tc)
                },
            )
        }
    }

    private suspend fun processChunkWithRetry(
        chunk: String,
        apiKey: String,
        maxRetries: Int,
        from: String,
        to: String,
    ): String {
        var currentChunk = chunk
        var retryCount = 0

        while (retryCount < maxRetries) {
            try {
                val result = processChunk(currentChunk, apiKey, from, to, retryCount)

                val errorType = fallbackStrategy.detectErrorType(null, result)
                val stillJp = fallbackStrategy.looksMostlyUntranslated(currentChunk, result)
                if (errorType == FallbackStrategy.ErrorType.BLOCKED_CONTENT ||
                    errorType == FallbackStrategy.ErrorType.FORMAT_ERROR ||
                    stillJp
                ) {
                    val retryConfig = when {
                        errorType == FallbackStrategy.ErrorType.BLOCKED_CONTENT ->
                            fallbackStrategy.handleBlockedContent(currentChunk, retryCount)
                        stillJp || errorType == FallbackStrategy.ErrorType.FORMAT_ERROR ->
                            fallbackStrategy.handleFormatError(currentChunk, retryCount)
                        else -> FallbackStrategy.RetryConfig(shouldRetry = false)
                    }

                    if (retryConfig.shouldRetry) {
                        Log.w(TAG, "Response quality issue detected, applying fallback strategy...")
                        kotlinx.coroutines.delay(retryConfig.delayMs)

                        if (retryConfig.splitIntoSmallerChunks && currentChunk.length > retryConfig.maxChunkSize) {
                            val subChunks = TranslationTextChunker.split(currentChunk, retryConfig.maxChunkSize)
                            val subResults = mutableListOf<String>()
                            for (subChunk in subChunks) {
                                val subResult = processChunk(subChunk, apiKey, from, to, retryCount, retryConfig)
                                subResults.add(subResult)
                            }
                            return TranslationTextChunker.merge(subResults)
                        }

                        retryCount++
                        continue
                    } else {
                        Log.w(TAG, "Translation quality issue but max retries reached, returning result")
                        return result
                    }
                }

                return result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                when {
                    isLikelyTimeout(e) -> {
                        Log.e(TAG, "Timeout on chunk: ${currentChunk.take(50)}... (retry $retryCount/$maxRetries)")

                        val retryConfig = fallbackStrategy.handleTimeout(currentChunk, retryCount)
                        if (retryConfig.shouldRetry) {
                            kotlinx.coroutines.delay(retryConfig.delayMs)

                            if (retryConfig.splitIntoSmallerChunks && currentChunk.length > retryConfig.maxChunkSize) {
                                val subChunks = TranslationTextChunker.split(currentChunk, retryConfig.maxChunkSize)
                                val subResults = mutableListOf<String>()
                                for (subChunk in subChunks) {
                                    try {
                                        val subResult = processChunk(subChunk, apiKey, from, to, 0, retryConfig)
                                        subResults.add(subResult)
                                    } catch (ex: Exception) {
                                        if (ex is CancellationException) throw ex
                                        throw IOException("Sub-chunk translation failed", ex)
                                    }
                                }
                                return TranslationTextChunker.merge(subResults)
                            }

                            retryCount++
                        } else {
                            throw IOException("Translation timed out after retries", e)
                        }
                    }
                    e is IOException -> {
                        Log.e(TAG, "Network error: ${e.message} (retry $retryCount/$maxRetries)")

                        val retryConfig = fallbackStrategy.handleNetworkError(retryCount)
                        if (retryConfig.shouldRetry) {
                            kotlinx.coroutines.delay(retryConfig.delayMs)
                            retryCount++
                        } else {
                            throw IOException("Translation network error after retries", e)
                        }
                    }
                    else -> {
                        Log.e(TAG, "Unexpected error: ${e.message} (retry $retryCount/$maxRetries)")

                        val retryConfig = fallbackStrategy.handleUnknownError(retryCount)
                        if (retryConfig.shouldRetry) {
                            kotlinx.coroutines.delay(retryConfig.delayMs)
                            retryCount++
                        } else {
                            throw IOException("Translation failed after retries", e)
                        }
                    }
                }
            }
        }

        throw IOException("Chunk translation failed after $maxRetries attempts")
    }

    private suspend fun processChunk(
        chunk: String,
        apiKey: String,
        from: String,
        to: String,
        retry: Int,
        retryConfig: FallbackStrategy.RetryConfig? = null,
    ): String {
        val (systemPrompt, userPrompt) = if (retryConfig?.useAlternativePrompt == true) {
            Pair(fallbackStrategy.getAlternativePrompt(), chunk)
        } else {
            // 小说正文常含 \n\n；若走 YAML 多段模式，模型会返回 - id:/text:| 结构，客户端未解析会整段显示在 UI 上
            promptBuilder.buildTranslationRequest(
                text = chunk,
                from = from,
                to = to,
                isMultiParagraph = false,
            )
        }

        val requestBody = buildRequestBody(
            systemPrompt,
            userPrompt,
            retry,
            retryConfig?.temperature ?: if (retry < 2) 0.5 else 0.3,
        )
        val request = buildRequest(apiKey, requestBody)

        return client.newCall(request).executeAsync().use { response ->
            handleResponse(response)
        }
    }

    private fun buildRequestBody(systemPrompt: String, userPrompt: String, retry: Int, temperature: Double): String {
        val systemMessage = JsonObject().apply {
            addProperty("role", "system")
            addProperty("content", systemPrompt)
        }
        val userMessage = JsonObject().apply {
            addProperty("role", "user")
            addProperty("content", userPrompt)
        }
        val messages = com.google.gson.JsonArray().apply {
            add(systemMessage)
            add(userMessage)
        }

        return JsonObject().apply {
            addProperty("model", "grok-3-mini")
            addProperty("temperature", temperature)
            addProperty("max_tokens", 8192)
            add("messages", messages)
        }.toString()
    }

    private fun buildRequest(apiKey: String, requestBody: String): Request {
        val body = requestBody.toRequestBody("application/json; charset=utf-8".toMediaType())
        return Request.Builder()
            .url("https://api.x.ai/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()
    }

    private fun handleResponse(response: okhttp3.Response): String {
        val responseBody = response.body?.string()

        if (BuildConfig.DEBUG) {
            Log.d(TAG, "Response HTTP ${response.code}, body prefix: ${responseBody?.take(80)}...")
        }

        if (!response.isSuccessful) {
            val errorBody = responseBody ?: "No response body"
            if (BuildConfig.DEBUG) {
                Log.e(TAG, "Status code: ${response.code} Response: ${errorBody.take(500)}")
            } else {
                Log.e(TAG, "Translation API failed with HTTP ${response.code}")
            }
            throw IOException("API request failed: HTTP ${response.code}")
        }

        val jsonResponse = responseBody?.let { JsonParser.parseString(it).asJsonObject }
            ?: throw IOException("Empty response body")

        val choices = jsonResponse.getAsJsonArray("choices")
            ?: throw IOException("Invalid response structure: missing choices")

        if (choices.size() == 0) {
            throw IOException("Invalid response structure: empty choices")
        }

        val message = choices[0].asJsonObject.getAsJsonObject("message")
            ?: throw IOException("Invalid response structure: missing message")

        val rawContent = extractMessageContent(message)
        return normalizeModelOutput(rawContent)
    }

    private fun extractMessageContent(message: JsonObject): String {
        if (!message.has("content")) {
            throw IOException("Invalid response structure: missing content")
        }
        val el = message.get("content")
        if (el.isJsonNull) {
            throw IOException("Invalid response structure: null content")
        }
        return when {
            el.isJsonPrimitive && el.asJsonPrimitive.isString -> el.asString
            el.isJsonArray -> {
                val arr = el.asJsonArray
                val sb = StringBuilder()
                for (i in 0 until arr.size()) {
                    val part = arr[i]
                    if (part.isJsonObject) {
                        val obj = part.asJsonObject
                        if (obj.has("text")) {
                            val t = obj.get("text")
                            if (t.isJsonPrimitive && t.asJsonPrimitive.isString) {
                                sb.append(t.asString)
                            }
                        }
                    } else if (part.isJsonPrimitive && part.asJsonPrimitive.isString) {
                        sb.append(part.asString)
                    }
                }
                sb.toString().ifEmpty {
                    throw IOException("Unsupported content array shape")
                }
            }
            else -> el.toString().trim('"')
        }
    }

    internal fun normalizeModelOutput(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```")) {
            val firstNl = s.indexOf('\n')
            if (firstNl > 0) {
                s = s.substring(firstNl + 1)
            } else {
                s = s.removePrefix("```")
            }
            if (s.endsWith("```")) {
                s = s.removeSuffix("```").trim()
            }
        }
        return stripYamlTranslationArtifactsIfPresent(s.trim())
    }

    /**
     * 若模型仍返回 IMT 式 YAML 列表（- id: / text: |），拆成纯文本段落；否则原样返回。
     */
    internal fun stripYamlTranslationArtifactsIfPresent(input: String): String {
        val t = input.trim()
        if (t.isEmpty()) return input
        if (!t.contains("text:")) return input
        val lines = t.lines()
        val hasIdEntry = lines.any { it.trim().matches(ID_LINE_REGEX) }
        if (!hasIdEntry) return input

        val paragraphs = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            if (!lines[i].trim().matches(ID_LINE_REGEX)) {
                i++
                continue
            }
            i++
            while (i < lines.size && lines[i].isBlank()) i++
            if (i >= lines.size) break
            val header = lines[i].trim()
            if (!header.startsWith("text:")) {
                i++
                continue
            }
            if (!header.contains("|")) {
                i++
                continue
            }
            i++
            val rawBlock = mutableListOf<String>()
            while (i < lines.size) {
                val L = lines[i]
                if (L.trim().matches(ID_LINE_REGEX)) break
                rawBlock.add(L)
                i++
            }
            val nonBlank = rawBlock.filter { it.isNotBlank() }
            val minIndent = nonBlank.minOfOrNull { line -> line.takeWhile { c -> c == ' ' || c == '\t' }.length } ?: 0
            val para = rawBlock.joinToString("\n") { line ->
                if (line.isBlank()) "" else line.drop(minIndent)
            }.trim()
            if (para.isNotEmpty()) paragraphs.add(para)
        }
        return paragraphs.joinToString("\n\n").ifBlank { input }
    }

}
