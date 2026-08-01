package ceui.pixiv.translation

/** Pure text chunking helpers kept independent from Android and network code for unit testing. */
internal object TranslationTextChunker {

    private val sentenceBoundaries = setOf('。', '！', '？', '」', '』', '）', '】', '.', '!', '?', '"', ')', ']')
    private val softBoundaries = setOf('\n', ' ', '、', '，', ',', '　')

    fun split(text: String, maxChunkSize: Int): List<String> {
        require(maxChunkSize > 0) { "maxChunkSize must be greater than zero" }
        if (text.isEmpty()) return emptyList()

        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val hardEnd = (start + maxChunkSize).coerceAtMost(text.length)
            val end = if (hardEnd == text.length) {
                hardEnd
            } else {
                findBoundary(text, start, hardEnd)
            }

            chunks += text.substring(start, end)
            start = end
        }
        return chunks
    }

    fun merge(chunks: List<String>): String = chunks.joinToString(separator = "")

    private fun findBoundary(text: String, start: Int, hardEnd: Int): Int {
        val searchStart = (hardEnd - 200).coerceAtLeast(start)

        for (index in hardEnd - 1 downTo searchStart) {
            if (text[index] == '\n') return index + 1
        }
        for (index in hardEnd - 1 downTo searchStart) {
            if (text[index] in sentenceBoundaries) return index + 1
        }
        for (index in hardEnd - 1 downTo searchStart) {
            if (text[index] in softBoundaries) return index + 1
        }
        return hardEnd
    }
}
