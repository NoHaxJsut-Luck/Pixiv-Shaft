package ceui.pixiv.translation

data class TranslationRetryEvent(
    val chunkIndex: Int,
    val totalChunks: Int,
    val attempt: Int,
    val maxAttempts: Int,
    val splitting: Boolean,
)
