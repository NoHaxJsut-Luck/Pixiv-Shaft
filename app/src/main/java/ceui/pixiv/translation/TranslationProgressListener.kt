package ceui.pixiv.translation

/**
 * Java/Kotlin callback for translation progress (chunk count + source character counts).
 */
fun interface TranslationProgressListener {
    fun onProgress(
        completedChunks: Int,
        totalChunks: Int,
        processedSourceChars: Int,
        totalSourceChars: Int,
    )

    fun onRetry(event: TranslationRetryEvent) = Unit
}
