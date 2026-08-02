package ceui.pixiv.translation

import android.content.Context
import ceui.lisa.R

object TranslationErrorMessages {

    @JvmStatic
    fun get(context: Context, error: Throwable): String {
        return when (error) {
            is TranslationApiException -> when {
                error.isAuthenticationError -> context.getString(R.string.translation_auth_failed)
                error.isRateLimited -> context.getString(R.string.translation_rate_limited)
                error.statusCode in 400..499 -> context.getString(
                    R.string.translation_api_request_failed,
                    error.statusCode,
                )
                else -> context.getString(R.string.translation_service_unavailable)
            }
            is TranslationModelUnavailableException -> context.getString(
                R.string.translation_model_unavailable,
                error.model,
            )
            is TranslationOutputException -> context.getString(R.string.translation_output_invalid)
            else -> context.getString(R.string.translation_failed)
        }
    }
}
