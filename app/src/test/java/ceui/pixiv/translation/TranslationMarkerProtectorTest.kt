package ceui.pixiv.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationMarkerProtectorTest {

    @Test
    fun protectsAndRestoresPixivMarkers() {
        val source = "前文[uploadedimage:123]后文[newpage][ruby:空>そら]"
        val protector = TranslationMarkerProtector.protect(source)

        assertTrue(protector.protectedText.contains("SHAFT_MARKER"))
        assertEquals(source, protector.restore(protector.protectedText))
    }

    @Test(expected = TranslationOutputException::class)
    fun rejectsMissingMarkers() {
        val protector = TranslationMarkerProtector.protect("正文[uploadedimage:123]")

        protector.restore("译文")
    }

    @Test
    fun restoresSourceBoundaryWhitespace() {
        val parts = TranslationChunkParts.from("\n\n原文\n")

        assertEquals("\n\n译文\n", parts.restore("  译文  "))
    }
}
