package ceui.pixiv.translation

data class TranslationPartialResultEvent(
    val failedChunks: Int,
    val totalChunks: Int,
    val logLocation: String?,
)
