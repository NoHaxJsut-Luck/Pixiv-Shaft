package ceui.pixiv.translation

internal class TranslationMarkerProtector private constructor(
    val protectedText: String,
    private val replacements: List<Pair<String, String>>,
) {

    fun restore(translatedText: String): String {
        var result = translatedText
        replacements.forEach { (token, marker) ->
            if (result.windowed(token.length).count { it == token } != 1) {
                throw TranslationMarkerException("A protected Pixiv marker was changed by the model")
            }
            result = result.replace(token, marker)
        }
        return result
    }

    fun restorePartial(partialText: String): String {
        var result = partialText
        replacements.forEach { (token, marker) ->
            result = result.replace(token, marker)
        }
        return result
    }

    companion object {
        private val MARKER_REGEX = Regex(
            """\[(?:uploadedimage|pixivimage|newpage|chapter|jumpuri|ruby)(?::[^\]\r\n]*)?]""",
            RegexOption.IGNORE_CASE,
        )

        fun protect(text: String): TranslationMarkerProtector {
            val replacements = mutableListOf<Pair<String, String>>()
            val protected = MARKER_REGEX.replace(text) { match ->
                val token = "⟦SHAFT_MARKER_${replacements.size.toString().padStart(4, '0')}⟧"
                replacements += token to match.value
                token
            }
            return TranslationMarkerProtector(protected, replacements)
        }
    }
}

internal data class TranslationChunkParts(
    val leadingWhitespace: String,
    val coreText: String,
    val trailingWhitespace: String,
) {
    fun restore(translatedCore: String): String =
        leadingWhitespace + translatedCore.trim() + trailingWhitespace

    companion object {
        fun from(source: String): TranslationChunkParts {
            val coreStart = source.indexOfFirst { !it.isWhitespace() }
            if (coreStart == -1) {
                return TranslationChunkParts(source, "", "")
            }
            val coreEndExclusive = source.indexOfLast { !it.isWhitespace() } + 1
            return TranslationChunkParts(
                leadingWhitespace = source.substring(0, coreStart),
                coreText = source.substring(coreStart, coreEndExclusive),
                trailingWhitespace = source.substring(coreEndExclusive),
            )
        }
    }
}
