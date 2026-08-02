package ceui.pixiv.translation

import android.content.Context
import ceui.lisa.R

object TranslationErrorMessages {

    @JvmStatic
    fun get(context: Context, error: Throwable): String {
        val loggedError = error as? TranslationLoggedException
        val actualError = loggedError?.originalError ?: error
        val message = when (actualError) {
            is TranslationApiException -> when {
                actualError.isAuthenticationError -> context.getString(R.string.translation_auth_failed)
                actualError.isRateLimited -> context.getString(R.string.translation_rate_limited)
                actualError.statusCode in 400..499 -> context.getString(
                    R.string.translation_api_request_failed,
                    actualError.statusCode,
                )
                else -> context.getString(R.string.translation_service_unavailable)
            }
            is TranslationModelUnavailableException -> context.getString(
                R.string.translation_model_unavailable,
                actualError.model,
            )
            is TranslationRefusedException -> context.getString(R.string.translation_model_refused)
            is TranslationMarkerException -> context.getString(R.string.translation_marker_invalid)
            is TranslationOutputException -> context.getString(R.string.translation_output_invalid)
            else -> context.getString(R.string.translation_failed)
        }
        return loggedError?.logLocation?.let { location ->
            "$message\n${context.getString(R.string.translation_log_saved, location)}"
        } ?: message
    }
}
