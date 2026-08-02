package ceui.pixiv.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationOutputTest {

    private val manager = TranslationManager.getInstance()

    @Test
    fun continuityPromptIncludesAdjacentSourceButNotWhenEmpty() {
        assertEquals("", manager.buildContinuityPrompt("", ""))

        val prompt = manager.buildContinuityPrompt("前の文", "次の文")
        assertTrue(prompt.contains("前の文"))
        assertTrue(prompt.contains("次の文"))
        assertTrue(prompt.contains("不要翻译或输出"))
    }

    @Test
    fun parsesRetryAfterSecondsAndHttpDate() {
        assertEquals(2500L, manager.parseRetryAfter("2.5", nowMs = 0L))
        assertEquals(
            1000L,
            manager.parseRetryAfter(
                "Thu, 01 Jan 1970 00:00:02 GMT",
                nowMs = 1000L,
            ),
        )
        assertEquals(null, manager.parseRetryAfter("not-a-date", nowMs = 0L))
    }

    @Test
    fun removesMarkdownFence() {
        assertEquals("译文", manager.normalizeModelOutput("```text\n译文\n```"))
    }

    @Test
    fun unwrapsUnexpectedYamlOutput() {
        val yaml = """
            - id: 1
              text: |
                第一段
            - id: 2
              text: |
                第二段
        """.trimIndent()

        assertEquals("第一段\n\n第二段", manager.normalizeModelOutput(yaml))
    }

    @Test
    fun ordinaryTextContainingTextLabelIsUnchanged() {
        val source = "正文中出现 text: 但不是 YAML"

        assertEquals(source, manager.normalizeModelOutput(source))
    }
}
