package ceui.pixiv.translation

import java.io.IOException

class TranslationApiException(
    val statusCode: Int,
    val retryAfterMs: Long? = null,
    val requestId: String? = null,
) : IOException("Translation API request failed with HTTP $statusCode") {
    val isAuthenticationError: Boolean
        get() = statusCode == 401 || statusCode == 403

    val isRateLimited: Boolean
        get() = statusCode == 429

    val isRetryable: Boolean
        get() = isRateLimited || statusCode == 408 || statusCode >= 500
}

open class TranslationContentException(
    message: String,
    val sourceChunk: String? = null,
    val modelOutput: String? = null,
    cause: Throwable? = null,
) : IOException(message, cause)

class TranslationOutputException(
    message: String,
    sourceChunk: String? = null,
    modelOutput: String? = null,
    cause: Throwable? = null,
) : TranslationContentException(message, sourceChunk, modelOutput, cause)

class TranslationMarkerException(
    message: String,
    sourceChunk: String? = null,
    modelOutput: String? = null,
    cause: Throwable? = null,
) : TranslationContentException(message, sourceChunk, modelOutput, cause)

class TranslationRefusedException(
    message: String,
    sourceChunk: String? = null,
    modelOutput: String? = null,
    cause: Throwable? = null,
) : TranslationContentException(message, sourceChunk, modelOutput, cause)

class TranslationLoggedException(
    val originalError: IOException,
    val logLocation: String?,
) : IOException(originalError.message, originalError)

class TranslationModelUnavailableException(val model: String) :
    IOException("Translation model is unavailable: $model")
