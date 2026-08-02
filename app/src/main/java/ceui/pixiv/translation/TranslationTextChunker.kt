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
                avoidSplittingMarker(text, start, hardEnd)
                    ?: findBoundary(text, start, avoidSplittingSurrogatePair(text, start, hardEnd))
            }

            chunks += text.substring(start, end)
            start = end
        }
        return chunks
    }

    fun merge(chunks: List<String>): String = chunks.joinToString(separator = "")

    private fun avoidSplittingMarker(text: String, start: Int, hardEnd: Int): Int? {
        val lastOpen = text.lastIndexOf('[', hardEnd - 1)
        val lastClose = text.lastIndexOf(']', hardEnd - 1)
        if (lastOpen < start || lastOpen < lastClose) return null

        val markerEnd = text.indexOf(']', hardEnd)
        if (markerEnd == -1) return null
        return if (lastOpen > start) lastOpen else markerEnd + 1
    }

    private fun avoidSplittingSurrogatePair(text: String, start: Int, hardEnd: Int): Int {
        if (hardEnd <= start || hardEnd >= text.length) return hardEnd
        return if (text[hardEnd - 1].isHighSurrogate() && text[hardEnd].isLowSurrogate()) {
            hardEnd - 1
        } else {
            hardEnd
        }
    }

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
