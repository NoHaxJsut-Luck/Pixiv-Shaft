package ceui.pixiv.translation

import android.util.Log
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * 翻译失败时的降级策略
 */
class FallbackStrategy {

    companion object {
        private const val TAG = "FallbackStrategy"
    }

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
            exception is IOException && exception.message?.contains("HTTP 4") == true -> ErrorType.BLOCKED_CONTENT
            responseContent != null && isBlockedResponse(responseContent) -> ErrorType.BLOCKED_CONTENT
            responseContent != null && isFormatError(responseContent) -> ErrorType.FORMAT_ERROR
            exception is IOException -> ErrorType.NETWORK_ERROR
            else -> ErrorType.UNKNOWN
        }
    }

    /**
     * 检测是否是屏蔽响应
     */
    private fun isBlockedResponse(content: String): Boolean {
        val blockedKeywords = listOf(
            "无法提供",
            "无法继续提供",
            "无法为您提供",
            "不适合进行翻译",
            "不适合翻译",
            "不能协助翻译",
            "无法完成该翻译"
        )

        val lowerContent = content.lowercase()
        return content.length < 500 && blockedKeywords.any { lowerContent.contains(it.lowercase()) }
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

    /**
     * 处理屏蔽内容错误
     */
    fun handleBlockedContent(chunk: String, retryCount: Int): RetryConfig {
        Log.w(TAG, "Detected blocked content, retry count: $retryCount")
        return when {
            retryCount == 0 -> {
                // 第一次重试：使用更温和的prompt
                RetryConfig(
                    shouldRetry = true,
                    useAlternativePrompt = true,
                    temperature = 0.3,
                    delayMs = 1000
                )
            }
            retryCount == 1 -> {
                RetryConfig(
                    shouldRetry = true,
                    splitIntoSmallerChunks = true,
                    maxChunkSize = 400,
                    delayMs = 1500
                )
            }
            retryCount == 2 -> {
                RetryConfig(
                    shouldRetry = true,
                    useAlternativePrompt = true,
                    splitIntoSmallerChunks = true,
                    maxChunkSize = 280,
                    temperature = 0.35,
                    delayMs = 1800
                )
            }
            else -> {
                // 放弃重试
                Log.e(TAG, "Blocked content cannot be translated after multiple retries")
                RetryConfig(shouldRetry = false)
            }
        }
    }

    /**
     * 处理超时错误
     */
    fun handleTimeout(chunk: String, retryCount: Int): RetryConfig {
        Log.w(TAG, "Detected timeout, retry count: $retryCount, chunk size: ${chunk.length}")
        return when {
            retryCount == 0 -> {
                RetryConfig(
                    shouldRetry = true,
                    delayMs = 900
                )
            }
            retryCount == 1 && chunk.length > 500 -> {
                RetryConfig(
                    shouldRetry = true,
                    splitIntoSmallerChunks = true,
                    maxChunkSize = chunk.length / 2,
                    delayMs = 2200
                )
            }
            retryCount < 5 -> {
                RetryConfig(
                    shouldRetry = true,
                    delayMs = 1600L * (retryCount + 1)
                )
            }
            else -> {
                // 放弃重试
                Log.e(TAG, "Timeout error persists after multiple retries")
                RetryConfig(shouldRetry = false)
            }
        }
    }

    /**
     * 处理格式错误
     */
    fun handleFormatError(chunk: String, retryCount: Int): RetryConfig {
        Log.w(TAG, "Detected format error (untranslated content), retry count: $retryCount")
        return when {
            retryCount == 0 -> {
                // 第一次：使用更明确的prompt
                RetryConfig(
                    shouldRetry = true,
                    useAlternativePrompt = true,
                    temperature = 0.25,
                    delayMs = 1000
                )
            }
            retryCount == 1 -> {
                RetryConfig(
                    shouldRetry = true,
                    splitIntoSmallerChunks = true,
                    maxChunkSize = 480,
                    temperature = 0.2,
                    delayMs = 900
                )
            }
            retryCount < 4 -> {
                RetryConfig(
                    shouldRetry = true,
                    useAlternativePrompt = retryCount >= 2,
                    temperature = 0.2,
                    delayMs = 1400L
                )
            }
            else -> {
                Log.e(TAG, "Format error persists, translation may be incomplete")
                RetryConfig(shouldRetry = false)
            }
        }
    }

    /**
     * 处理网络错误
     */
    fun handleNetworkError(retryCount: Int): RetryConfig {
        Log.w(TAG, "Detected network error, retry count: $retryCount")
        return when {
            retryCount < 5 -> {
                RetryConfig(
                    shouldRetry = true,
                    delayMs = (700L * Math.pow(1.8, retryCount.toDouble())).toLong().coerceAtMost(12_000L)
                )
            }
            else -> {
                Log.e(TAG, "Network error persists after multiple retries")
                RetryConfig(shouldRetry = false)
            }
        }
    }

    /**
     * 处理未知错误
     */
    fun handleUnknownError(retryCount: Int): RetryConfig {
        Log.w(TAG, "Detected unknown error, retry count: $retryCount")
        return when {
            retryCount < 4 -> {
                RetryConfig(
                    shouldRetry = true,
                    delayMs = 900L * (retryCount + 1)
                )
            }
            else -> {
                Log.e(TAG, "Unknown error persists")
                RetryConfig(shouldRetry = false)
            }
        }
    }

    /**
     * 获取替代prompt（更温和的版本）
     */
    fun getAlternativePrompt(): String {
        return "你是日文小说译者。将正文译为自然流畅的简体中文，保留情绪与节奏。" +
            "对话里的感叹、应答、迟疑（如「えっ」「あっ」「ん」「はぁ」「ううっ」）及呻吟、喘息等拟声，须译成符合语境的中文语气词或拟声，勿整段留假名。" +
            "人名、地名、作品名可保留或通用译名。保留[ruby:…]、[uploadedimage:…]等标记。只输出译文。"
    }
}
