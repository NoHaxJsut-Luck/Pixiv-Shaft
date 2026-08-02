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

class TranslationOutputException(message: String) : IOException(message)

class TranslationModelUnavailableException(val model: String) :
    IOException("Translation model is unavailable: $model")
