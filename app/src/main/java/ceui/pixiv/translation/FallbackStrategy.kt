package ceui.pixiv.translation

import java.io.IOException
import java.net.SocketTimeoutException

/**
 * 翻译失败时的降级策略
 */
class FallbackStrategy {

    /**
     * 错误类型
     */
    enum class ErrorType {
        BLOCKED_CONTENT,   // 内容被屏蔽
        TIMEOUT,           // 超时
        FORMAT_ERROR,      // 格式错误
        NETWORK_ERROR,     // 网络错误
        UNKNOWN            // 未知错误
    }

    /**
     * 重试配置
     */
    data class RetryConfig(
        val shouldRetry: Boolean,
        val useAlternativePrompt: Boolean = false,
        val splitIntoSmallerChunks: Boolean = false,
        val maxChunkSize: Int = 500,
        val delayMs: Long = 2000,
        val temperature: Double = 0.5
    )

    /**
     * 检测错误类型
     */
    fun detectErrorType(exception: Exception?, responseContent: String? = null): ErrorType {
        return when {
            exception is SocketTimeoutException -> ErrorType.TIMEOUT
            responseContent != null && isBlockedResponse(responseContent) -> ErrorType.BLOCKED_CONTENT
            responseContent != null && isFormatError(responseContent) -> ErrorType.FORMAT_ERROR
            exception is IOException -> ErrorType.NETWORK_ERROR
            else -> ErrorType.UNKNOWN
        }
    }

    fun classifyOutput(source: String, result: String): ErrorType? {
        return when {
            isBlockedResponse(result) -> ErrorType.BLOCKED_CONTENT
            isFormatError(result) -> ErrorType.FORMAT_ERROR
            looksMostlyUntranslated(source, result) -> ErrorType.FORMAT_ERROR
            isSuspiciouslyShort(source, result) -> ErrorType.FORMAT_ERROR
            else -> null
        }
    }

    fun requireValidOutput(source: String, result: String) {
        when (classifyOutput(source, result)) {
            ErrorType.BLOCKED_CONTENT -> throw TranslationRefusedException(
                "The model refused to translate this chunk",
            )
            ErrorType.FORMAT_ERROR -> throw TranslationOutputException(
                "The model returned an incomplete or invalid translation",
            )
            else -> Unit
        }
    }

    fun shouldStopRepeatedRefusal(
        consecutiveRefusals: Int,
        chunkLength: Int,
        splitDepth: Int,
        maxSplitDepth: Int,
    ): Boolean = consecutiveRefusals >= 2 &&
        chunkLength <= 320 &&
        splitDepth >= maxSplitDepth

    /**
     * 检测是否是屏蔽响应
     */
    private fun isBlockedResponse(content: String): Boolean {
        val compact = content.trim().lowercase()
        if (compact.length > 500) return false

        val strongRefusalPhrases = listOf(
            "翻译不通过",
            "翻譯不通過",
            "未通过翻译",
            "未通過翻譯",
            "无法提供",
            "无法继续提供",
            "无法为您提供",
            "不适合进行翻译",
            "不适合翻译",
            "不能协助翻译",
            "无法完成该翻译",
            "無法完成該翻譯",
            "i cannot translate",
            "i can't translate",
            "unable to translate",
            "cannot assist with this translation",
            "can't assist with this translation",
            "翻訳できません",
            "翻訳することはできません",
            "翻訳をお手伝いできません",
        )
        if (strongRefusalPhrases.any(compact::contains)) return true

        val genericRefusalPhrases = listOf(
            "无法翻译",
            "無法翻譯",
            "不能翻译",
            "不能翻譯",
            "无法协助",
            "無法協助",
            "cannot help with",
            "can't help with",
            "お手伝いできません",
        )
        val startsLikeRefusal = compact.startsWith("抱歉") ||
            compact.startsWith("对不起") ||
            compact.startsWith("對不起") ||
            compact.startsWith("sorry") ||
            compact.startsWith("申し訳")
        return genericRefusalPhrases.any(compact::contains) &&
            (compact.length < 180 || startsLikeRefusal)
    }

    /**
     * 检测是否是格式错误
     */
    private fun isFormatError(content: String): Boolean {
        // 检查是否包含大量日文（说明没有翻译）
        val japaneseChars = content.count { it in '\u3040'..'\u309F' || it in '\u30A0'..'\u30FF' }
        val totalChars = content.length
        return if (totalChars > 0) {
            // 阈值过高会把含大量专名/引用的正常译文误判为失败
            japaneseChars.toDouble() / totalChars > 0.55
        } else {
            false
        }
    }

    private fun kanaCount(s: String): Int =
        s.count { it in '\u3040'..'\u309F' || it in '\u30A0'..'\u30FF' }

    /**
     * 与 [isFormatError] 互补：整体译成中文了但仍夹大量假名（语气词/拟声未译）或完全未动原文。
     */
    fun looksMostlyUntranslated(source: String, result: String): Boolean {
        val s = source.trim()
        val r = result.trim()
        if (s.isEmpty()) return false
        if (r.isEmpty()) return true
        if (r == s) return true

        val srcKana = kanaCount(source)
        if (srcKana < 4 && !source.any { it in '\u3040'..'\u30FF' }) return false

        val resKana = kanaCount(result)
        val meaningful = r.count { !it.isWhitespace() }
        if (meaningful == 0) return true

        if (resKana >= 10 && resKana >= srcKana * 0.3) return true
        if (meaningful > 24 && resKana.toDouble() / meaningful > 0.18) return true
        return false
    }

    fun isSuspiciouslyShort(source: String, result: String): Boolean {
        val sourceLength = source.count { !it.isWhitespace() }
        if (sourceLength < 240) return false
        val resultLength = result.count { !it.isWhitespace() }
        val minimumExpected = maxOf(32, (sourceLength * 0.12).toInt())
        return resultLength < minimumExpected
    }

    /**
     * 处理屏蔽内容错误
     */
    fun handleBlockedContent(chunk: String, retryCount: Int): RetryConfig {
        return when {
            retryCount == 0 -> {
                RetryConfig(
                    shouldRetry = true,
                    useAlternativePrompt = true,
                    splitIntoSmallerChunks = chunk.length > 420,
                    maxChunkSize = 420,
                    temperature = 0.15,
                    delayMs = 120,
                )
            }
            retryCount == 1 -> {
                RetryConfig(
                    shouldRetry = true,
                    useAlternativePrompt = true,
                    splitIntoSmallerChunks = true,
                    maxChunkSize = 280,
                    temperature = 0.1,
                    delayMs = 180,
                )
            }
            retryCount == 2 -> {
                RetryConfig(
                    shouldRetry = true,
                    useAlternativePrompt = true,
                    splitIntoSmallerChunks = true,
                    maxChunkSize = 180,
                    temperature = 0.1,
                    delayMs = 250,
                )
            }
            else -> {
                // 放弃重试
                RetryConfig(shouldRetry = false)
            }
        }
    }

    /**
     * 处理超时错误
     */
    fun handleTimeout(chunk: String, retryCount: Int): RetryConfig {
        return when {
            retryCount == 0 && chunk.length > 700 -> {
                RetryConfig(
                    shouldRetry = true,
                    splitIntoSmallerChunks = true,
                    maxChunkSize = (chunk.length / 2).coerceAtLeast(360),
                    delayMs = 250,
                )
            }
            retryCount <= 1 -> {
                RetryConfig(
                    shouldRetry = true,
                    delayMs = 600,
                )
            }
            retryCount < 5 -> {
                RetryConfig(
                    shouldRetry = true,
                    delayMs = 900L * (retryCount + 1)
                )
            }
            else -> {
                // 放弃重试
                RetryConfig(shouldRetry = false)
            }
        }
    }

    /**
     * 处理格式错误
     */
    fun handleFormatError(chunk: String, retryCount: Int): RetryConfig {
        return when {
            retryCount == 0 -> {
                // 第一次：使用更明确的prompt
                RetryConfig(
                    shouldRetry = true,
                    useAlternativePrompt = true,
                    temperature = 0.15,
                    delayMs = 150,
                )
            }
            retryCount == 1 -> {
                RetryConfig(
                    shouldRetry = true,
                    splitIntoSmallerChunks = true,
                    maxChunkSize = 480,
                    temperature = 0.1,
                    delayMs = 200,
                )
            }
            retryCount < 4 -> {
                RetryConfig(
                    shouldRetry = true,
                    useAlternativePrompt = retryCount >= 2,
                    temperature = 0.1,
                    delayMs = 300L
                )
            }
            else -> {
                RetryConfig(shouldRetry = false)
            }
        }
    }

    /**
     * 处理网络错误
     */
    fun handleNetworkError(retryCount: Int): RetryConfig {
        return when {
            retryCount < 5 -> {
                RetryConfig(
                    shouldRetry = true,
                    delayMs = (500L * Math.pow(1.8, retryCount.toDouble())).toLong().coerceAtMost(10_000L)
                )
            }
            else -> {
                RetryConfig(shouldRetry = false)
            }
        }
    }

    /**
     * 处理未知错误
     */
    fun handleUnknownError(retryCount: Int): RetryConfig {
        return when {
            retryCount < 4 -> {
                RetryConfig(
                    shouldRetry = true,
                    delayMs = 900L * (retryCount + 1)
                )
            }
            else -> {
                RetryConfig(shouldRetry = false)
            }
        }
    }

    /**
     * 获取替代prompt（更温和的版本）
     */
    fun getAlternativePrompt(): String {
        return "你是专业小说译者。忠实、完整地将输入正文译为自然流畅的简体中文。" +
            "保留叙事语气、人物口吻、换行和所有占位符；语气词与拟声也要译成符合上下文的中文表达。" +
            "不要评价、解释、概括或拒绝，只输出译文正文。"
    }
}
