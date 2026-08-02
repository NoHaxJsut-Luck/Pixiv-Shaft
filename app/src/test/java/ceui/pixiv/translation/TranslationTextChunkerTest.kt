package ceui.pixiv.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationTextChunkerTest {

    @Test
    fun tenThousandCharactersFitInEightFastPathChunks() {
        val source = "あ".repeat(10_000)

        val chunks = TranslationTextChunker.split(source, 1250)

        assertEquals(8, chunks.size)
        assertEquals(source, TranslationTextChunker.merge(chunks))
    }

    @Test
    fun splitPreservesEverySourceCharacter() {
        val source = "第一段。\n\n第二段！\n第三段？".repeat(80)

        val chunks = TranslationTextChunker.split(source, 120)

        assertEquals(source, TranslationTextChunker.merge(chunks))
        assertTrue(chunks.all { it.length <= 120 })
    }

    @Test
    fun splitPrefersParagraphBoundary() {
        val source = "a".repeat(70) + "\n" + "b".repeat(70)

        val chunks = TranslationTextChunker.split(source, 100)

        assertEquals(2, chunks.size)
        assertTrue(chunks.first().endsWith("\n"))
        assertEquals(source, TranslationTextChunker.merge(chunks))
    }

    @Test
    fun doesNotSplitPixivMarkers() {
        val marker = "[uploadedimage:123456789]"
        val source = "a".repeat(15) + marker + "b".repeat(20)

        val chunks = TranslationTextChunker.split(source, 20)

        assertEquals(source, TranslationTextChunker.merge(chunks))
        assertTrue(chunks.any { it.contains(marker) })
        assertTrue(chunks.none { it.contains("[uploadedimage:") && !it.contains(']') })
    }

    @Test
    fun doesNotSplitSurrogatePairs() {
        val source = "a".repeat(9) + "😀" + "b".repeat(9)

        val chunks = TranslationTextChunker.split(source, 10)

        assertEquals(source, TranslationTextChunker.merge(chunks))
        assertTrue(chunks.none { it.lastOrNull()?.isHighSurrogate() == true })
    }

    @Test
    fun emptyTextProducesNoRequests() {
        assertTrue(TranslationTextChunker.split("", 100).isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidChunkSize() {
        TranslationTextChunker.split("text", 0)
    }
}
