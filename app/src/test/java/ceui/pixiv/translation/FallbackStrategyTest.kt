package ceui.pixiv.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackStrategyTest {

    private val strategy = FallbackStrategy()

    @Test
    fun ordinaryPolicyTextIsNotClassifiedAsRefusal() {
        val result = strategy.detectErrorType(null, "这项政策违反了旧规则。")

        assertEquals(FallbackStrategy.ErrorType.UNKNOWN, result)
    }

    @Test
    fun explicitRefusalIsDetected() {
        val result = strategy.detectErrorType(null, "抱歉，我无法为您提供这段内容的翻译。")

        assertEquals(FallbackStrategy.ErrorType.BLOCKED_CONTENT, result)
    }

    @Test
    fun translationRejectedWordingIsDetected() {
        val variants = listOf(
            "该段落翻译不通过。",
            "抱歉，这部分无法翻译。",
            "Sorry, I cannot translate this passage.",
            "申し訳ありませんが、この文章は翻訳できません。",
        )

        variants.forEach { result ->
            assertEquals(
                FallbackStrategy.ErrorType.BLOCKED_CONTENT,
                strategy.classifyOutput("これは翻訳対象の長い文章です。".repeat(20), result),
            )
        }
    }

    @Test
    fun abnormallyShortResultIsRejected() {
        val source = "これは長い小説本文です。".repeat(40)

        assertTrue(strategy.isSuspiciouslyShort(source, "翻译失败"))
        assertEquals(
            FallbackStrategy.ErrorType.FORMAT_ERROR,
            strategy.classifyOutput(source, "只有一句很短的结果。"),
        )
    }

    @Test
    fun firstBlockedRetrySplitsImmediately() {
        val config = strategy.handleBlockedContent("あ".repeat(1000), retryCount = 0)

        assertTrue(config.shouldRetry)
        assertTrue(config.useAlternativePrompt)
        assertTrue(config.splitIntoSmallerChunks)
        assertEquals(420, config.maxChunkSize)
    }

    @Test
    fun unchangedJapaneseIsRejectedButChineseTranslationPasses() {
        val source = "これはテストです。よろしくお願いします。"

        assertTrue(strategy.looksMostlyUntranslated(source, source))
        assertFalse(strategy.looksMostlyUntranslated(source, "这是一个测试，请多关照。"))
    }
}
